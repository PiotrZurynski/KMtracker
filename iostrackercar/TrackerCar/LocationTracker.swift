import Foundation
import CoreLocation
import SwiftData
import ActivityKit
import UIKit
import UserNotifications

/// Liczy kilometry tylko między Start a Stop.
/// Oszczędność baterii: brak ciągłego GPS-a poza przejazdem, distanceFilter 15 m,
/// tryb automotive, zapis do bazy co ~100 m.
@MainActor
@Observable
final class LocationTracker: NSObject, CLLocationManagerDelegate {
    var isTracking = false
    var authorization: CLAuthorizationStatus = .notDetermined
    private(set) var current: Trip?
    /// Przerzedzona trasa dla mapy na żywo (odświeżana rzadko, max ~300 punktów).
    private(set) var liveRoute: [CLLocationCoordinate2D] = []

    private let manager = CLLocationManager()
    @ObservationIgnored private var context: ModelContext?
    @ObservationIgnored private var last: CLLocation?
    @ObservationIgnored private var unsaved = 0.0
    @ObservationIgnored private var lastActivityUpdate = Date.distantPast
    @ObservationIgnored private var lastActivityBucket = -1
    @ObservationIgnored private var activity: Activity<TripAttributes>?
    @ObservationIgnored private var livePath: [CLLocationCoordinate2D] = []
    @ObservationIgnored private var lastRoutePublish = Date.distantPast

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
        manager.distanceFilter = 15
        manager.activityType = .automotiveNavigation
        manager.pausesLocationUpdatesAutomatically = false
        authorization = manager.authorizationStatus
    }

    /// Wywołaj raz przy starcie – wznawia przerwany przejazd (np. po ubiciu apki).
    func attach(_ context: ModelContext) {
        guard self.context == nil else { return }   // wznowienie sprawdzamy tylko raz, nie przy każdym wejściu na zakładkę
        self.context = context
        guard !isTracking else { return }
        var d = FetchDescriptor<Trip>(predicate: #Predicate { $0.endedAt == nil })
        d.fetchLimit = 1
        if let open = try? context.fetch(d).first {
            current = open
            livePath = downsampled(open.route, limit: 300)
            liveRoute = livePath
            beginUpdates()
            activity = Activity<TripAttributes>.activities.first
            if activity == nil { startActivity(open) }
        }
    }

    func start() {
        guard let context, !isTracking, current == nil else { return }   // brak drugiego równoległego przejazdu
        switch manager.authorizationStatus {
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
            return
        case .denied, .restricted:
            pendingStart = false
            return
        default: break
        }
        let trip = Trip(price: Prefs.price, consumption: Prefs.consumption, split: Prefs.split)
        context.insert(trip)
        try? context.save()
        current = trip
        last = nil
        unsaved = 0
        lastActivityBucket = -1
        livePath = []
        liveRoute = []
        beginUpdates()
        startActivity(trip)
        UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
    }

    func stop() {
        manager.stopUpdatingLocation()
        manager.allowsBackgroundLocationUpdates = false
        manager.showsBackgroundLocationIndicator = false
        isTracking = false
        cancelReminder()
        if let trip = current {
            trip.endedAt = Date()
            // Przejazd bez dystansu nie ma sensu zapisywać.
            if trip.meters < 50 { context?.delete(trip) }
        }
        try? context?.save()
        endActivity()
        current = nil
        last = nil
        unsaved = 0
        livePath = []
        liveRoute = []
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }

    // MARK: Przypomnienie „zapomniałeś kliknąć Stop"
    // Lokalne powiadomienie przesuwane o 10 min po każdym ruchu. Gdy auto stoi, GPS nie
    // zgłasza zmian i powiadomienie samo wyskakuje – bez żadnego dodatkowego zużycia baterii.

    private var lastReminder = Date.distantPast
    private let reminderID = "idle-reminder"

    private func scheduleReminder(force: Bool = false) {
        guard force || Date().timeIntervalSince(lastReminder) > 60 else { return }
        lastReminder = Date()
        let c = UNMutableNotificationContent()
        c.title = "TrackerCar"
        c.body = "Auto stoi od 10 minut. Zatrzymać przejazd?"
        c.sound = .default
        let req = UNNotificationRequest(identifier: reminderID, content: c,
                                        trigger: UNTimeIntervalNotificationTrigger(timeInterval: 600, repeats: false))
        UNUserNotificationCenter.current().add(req)
    }

    private func cancelReminder() {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [reminderID])
    }

    private func beginUpdates() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
        scheduleReminder(force: true)
        manager.allowsBackgroundLocationUpdates = true
        manager.showsBackgroundLocationIndicator = true
        manager.startUpdatingLocation()
        isTracking = true
    }

    // MARK: CLLocationManagerDelegate

    nonisolated func locationManager(_ m: CLLocationManager, didUpdateLocations locs: [CLLocation]) {
        Task { @MainActor in locs.forEach(self.ingest) }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ m: CLLocationManager) {
        Task { @MainActor in
            self.authorization = m.authorizationStatus
            // odmowa kasuje oczekujący Start (inaczej późniejsze włączenie zgody w Ustawieniach samo uruchomiłoby przejazd)
            if m.authorizationStatus == .denied || m.authorizationStatus == .restricted { self.pendingStart = false }
            if m.authorizationStatus == .authorizedWhenInUse || m.authorizationStatus == .authorizedAlways,
               !self.isTracking, self.current == nil, self.pendingStart { self.pendingStart = false; self.start() }
        }
    }
    private var pendingStart = false
    func requestAndStart() { pendingStart = true; start() }

    // MARK: Filtrowanie pomiarów

    private func ingest(_ loc: CLLocation) {
        guard isTracking, let trip = current else { return }
        // odrzuć słabe / stare pomiary
        guard loc.horizontalAccuracy >= 0, loc.horizontalAccuracy <= 50,
              abs(loc.timestamp.timeIntervalSinceNow) < 10 else { return }
        guard let prev = last else { last = loc; trip.append(loc.coordinate); recordLive(loc.coordinate); return }
        let delta = loc.distance(from: prev)
        let dt = loc.timestamp.timeIntervalSince(prev.timestamp)
        guard delta >= max(8, loc.horizontalAccuracy * 0.5),   // szum GPS na postoju
              dt > 0, delta / dt < 70 else { return }          // skoki >250 km/h
        last = loc
        trip.meters += delta
        trip.append(loc.coordinate)
        recordLive(loc.coordinate)
        scheduleReminder()
        unsaved += delta
        if unsaved >= 100 { try? context?.save(); unsaved = 0 }
        updateActivity(trip)
    }

    /// Trasa dla mapy na żywo: mała tablica w pamięci (bez dekodowania Data), publikowana co ≥8 s.
    private func recordLive(_ c: CLLocationCoordinate2D) {
        livePath.append(c)
        if livePath.count > 300 {
            // przerzedź co drugi punkt, zachowując ostatni
            let lastPoint = livePath[livePath.count - 1]
            var out: [CLLocationCoordinate2D] = []
            out.reserveCapacity(livePath.count / 2 + 2)
            for i in stride(from: 0, to: livePath.count - 1, by: 2) { out.append(livePath[i]) }
            out.append(lastPoint)
            livePath = out
        }
        let now = Date()
        if now.timeIntervalSince(lastRoutePublish) >= 8 || liveRoute.count < 2 {
            lastRoutePublish = now
            liveRoute = livePath
        }
    }

    // MARK: Live Activity / Dynamic Island

    private func startActivity(_ trip: Trip) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        let state = TripAttributes.ContentState(meters: trip.meters, cost: trip.cost)
        activity = try? Activity.request(
            attributes: TripAttributes(startedAt: trip.startedAt, accent: Prefs.accentIndex),
            content: .init(state: state, staleDate: nil))
    }

    private func updateActivity(_ trip: Trip) {
        guard activity != nil else { return }
        // Widget pokazuje km z 1 miejscem po przecinku – aktualizuj tylko gdy wyświetlana wartość się zmieni.
        let bucket = Int(trip.meters / 100)
        guard bucket != lastActivityBucket,
              Date().timeIntervalSince(lastActivityUpdate) > 5 else { return }  // limit odświeżeń
        lastActivityBucket = bucket
        lastActivityUpdate = Date()
        let state = TripAttributes.ContentState(meters: trip.meters, cost: trip.cost)
        Task { await activity?.update(.init(state: state, staleDate: nil)) }
    }

    private func endActivity() {
        let a = activity
        activity = nil
        Task { await a?.end(nil, dismissalPolicy: .immediate) }
    }
}
