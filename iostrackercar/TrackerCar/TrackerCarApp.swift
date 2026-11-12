import SwiftUI
import SwiftData
import CoreLocation

@main
struct TrackerCarApp: App {
    @State private var tracker = LocationTracker()
    private let container: ModelContainer

    init() {
        let c = try! ModelContainer(for: Trip.self, Settlement.self)
        container = c
        // Tryb podglądu (tylko gdy uruchomiono z argumentem -demo, np. w symulatorze).
        if ProcessInfo.processInfo.arguments.contains("-demo") { Demo.seed(c.mainContext) }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(tracker)
                .preferredColorScheme(.dark)
        }
        .modelContainer(container)
    }
}

enum Demo {
    @MainActor
    static func seed(_ ctx: ModelContext) {
        guard (try? ctx.fetchCount(FetchDescriptor<Trip>())) == 0 else { return }
        let cal = Calendar.current
        func add(daysAgo: Int, hour: Int, minutes: Int, km: Double, settled: Bool = false) {
            let day = cal.date(byAdding: .day, value: -daysAgo, to: Date())!
            let start = cal.date(bySettingHour: hour, minute: 0, second: 0, of: day)!
            let t = Trip(price: 6.19, consumption: 7.5, split: 2)
            t.startedAt = start
            t.endedAt = start.addingTimeInterval(Double(minutes) * 60)
            t.meters = km * 1000
            t.settled = settled
            // przykładowa trasa w okolicy Olsztyna
            for i in 0...40 {
                let x = Double(i) / 40
                t.append(CLLocationCoordinate2D(latitude: 53.775 + 0.03 * x + 0.006 * sin(x * 9),
                                                longitude: 20.45 + 0.07 * x + 0.004 * cos(x * 7)))
            }
            ctx.insert(t)
        }
        add(daysAgo: 0, hour: 8, minutes: 25, km: 14.2)
        add(daysAgo: 0, hour: 16, minutes: 30, km: 15.1)
        add(daysAgo: 1, hour: 9, minutes: 40, km: 22.8)
        add(daysAgo: 3, hour: 7, minutes: 55, km: 48.6)
        add(daysAgo: 9, hour: 8, minutes: 30, km: 31.0, settled: true)
        ctx.insert(Settlement(meters: 31_000, amount: 7.2, tripCount: 1))
        try? ctx.save()
    }
}
