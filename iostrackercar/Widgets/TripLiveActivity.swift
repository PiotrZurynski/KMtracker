import WidgetKit
import SwiftUI
import ActivityKit

@main
struct TrackerCarWidgets: WidgetBundle {
    var body: some Widget { TripLiveActivity() }
}

struct TripLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: TripAttributes.self) { ctx in
            // Ekran blokady
            HStack {
                VStack(alignment: .leading) {
                    Text("PRZEJAZD").font(.caption2.weight(.bold)).tracking(3).foregroundStyle(.secondary)
                    Text("\(Fmt.km(ctx.state.meters)) km")
                        .font(.system(size: 34, weight: .black, design: .rounded)).foregroundStyle(Theme.accent)
                        .monospacedDigit()
                }
                Spacer()
                VStack(alignment: .trailing) {
                    Text(Fmt.zl(ctx.state.cost)).font(.title3.weight(.semibold)).monospacedDigit()
                    Text(timerInterval: ctx.attributes.startedAt...Date.distantFuture, countsDown: false)
                        .font(.caption).foregroundStyle(.secondary).monospacedDigit().frame(width: 70, alignment: .trailing)
                }
            }
            .padding().activityBackgroundTint(Theme.bg)
        } dynamicIsland: { ctx in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Image(systemName: "car.fill").foregroundStyle(Theme.accent).font(.title2)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(Fmt.zl(ctx.state.cost)).font(.headline).monospacedDigit()
                }
                DynamicIslandExpandedRegion(.center) {
                    Text("\(Fmt.km(ctx.state.meters)) km")
                        .font(.system(size: 30, weight: .black, design: .rounded))
                        .foregroundStyle(Theme.accent).monospacedDigit()
                }
                DynamicIslandExpandedRegion(.bottom) {
                    Text(timerInterval: ctx.attributes.startedAt...Date.distantFuture, countsDown: false)
                        .font(.caption).foregroundStyle(.secondary).monospacedDigit()
                }
            } compactLeading: {
                Image(systemName: "car.fill").foregroundStyle(Theme.accent)
            } compactTrailing: {
                Text(Fmt.km(ctx.state.meters)).font(.caption.weight(.bold)).monospacedDigit()
                    .foregroundStyle(Theme.accent)
            } minimal: {
                Image(systemName: "car.fill").foregroundStyle(Theme.accent)
            }
            .keylineTint(Theme.accent)
        }
    }
}
