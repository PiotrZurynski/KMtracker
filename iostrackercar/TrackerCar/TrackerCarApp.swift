import SwiftUI
import SwiftData

@main
struct TrackerCarApp: App {
    @State private var tracker = LocationTracker()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(tracker)
                .preferredColorScheme(.dark)
        }
        .modelContainer(for: Trip.self)
    }
}
