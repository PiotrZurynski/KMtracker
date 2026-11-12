import Foundation
import CoreLocation
import SwiftData
import ActivityKit
import UIKit

/// Liczy kilometry tylko między Start a Stop.
/// Oszczędność baterii: brak ciągłego GPS-a poza przejazdem, distanceFilter 15 m,
/// tryb automotive, zapis do bazy co ~100 m.
@MainActor
@Observable
final class LocationTracker: NSObject, CLLocationManagerDelegate {
    var isTracking = false
    var authorization: CLAuthorizationStatus = .notDetermined
    private(set) var current: Trip?

    private let manager = CLLocationManager()
    private var context: ModelContext?
    private var last: CLLocation?
    private var unsaved = 0.0
    private var lastActivityUpdate = Date.distantPast
    private var activity: Activity<TripAttributes>?

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
        self.context = context
        guard !isTracking else { return }
        let d = FetchDescriptor<Trip>(predicate: #Predicate { $0.endedAt == nil })
        if let open = try? context.fetch(d).first {
            current = open
            beginUpdates()
            activity = Activity<TripAttributes>.activities.first
            if activity == nil { startActivity(open) }
        }
    }

    func start() {
        guard let context else { return }
        switch manager.authorizationStatus {
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
            return
        case .denied, .restricted:
            return
        default: break
        }
        let trip = Trip(price: Prefs.price, consumption: Prefs.consumption, split: Prefs.split)
        context.insert(trip)
        try? context.save()
        current = trip
        last = nil
        beginUpdates()
        startActivity(trip)
        UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
    }

    func stop() {
        manager.stopUpdatingLocation()
        manager.allowsBackgroundLocationUpdates = false
        manager.showsBackgroundLocationIndicator = false
        isTracking = false
        if let trip = current {
            trip.endedAt = Date()
            // Przejazd bez dystansu nie ma sensu zapisywać.
            if trip.meters < 50 { context?.delete(trip) }
        }
        try? context?.save()
        endActivity()
        current = nil
        last = nil
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }

    private func beginUpdates() {
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
        guard let prev = last else { last = loc; trip.append(loc.coordinate); return }
        let delta = loc.distance(from: prev)
        let dt = loc.timestamp.timeIntervalSince(prev.timestamp)
        guard delta >= max(8, loc.horizontalAccuracy * 0.5),   // szum GPS na postoju
              dt > 0, delta / dt < 70 else { return }          // skoki >250 km/h
        last = loc
        trip.meters += delta
        trip.append(loc.coordinate)
        unsaved += delta
        if unsaved >= 100 { try? context?.save(); unsaved = 0 }
        updateActivity(trip)
    }

    // MARK: Live Activity / Dynamic Island

    private func startActivity(_ trip: Trip) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        let state = TripAttributes.ContentState(meters: trip.meters, cost: trip.cost)
        activity = try? Activity.request(
            attributes: TripAttributes(startedAt: trip.startedAt),
            content: .init(state: state, staleDate: nil))
    }

    private func updateActivity(_ trip: Trip) {
        guard Date().timeIntervalSince(lastActivityUpdate) > 3 else { return }  // limit odświeżeń
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
