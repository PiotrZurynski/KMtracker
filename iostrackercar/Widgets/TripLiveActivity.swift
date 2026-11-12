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
                    Text("PRZEJAZD").font(Theme.f(12, .medium)).tracking(3).foregroundStyle(Theme.ink.opacity(0.5))
                    Text("\(Fmt.km(ctx.state.meters)) km")
                        .font(Theme.f(40, .bold)).foregroundStyle(Theme.ink).monospacedDigit()
                }
                Spacer()
                VStack(alignment: .trailing) {
                    Text(Fmt.zl(ctx.state.cost)).font(Theme.f(22, .semibold)).foregroundStyle(Theme.accent(ctx.attributes.accent)).monospacedDigit()
                    Text(timerInterval: ctx.attributes.startedAt...Date.distantFuture, countsDown: false)
                        .font(Theme.f(13)).foregroundStyle(Theme.ink.opacity(0.6)).monospacedDigit()
                        .frame(width: 70, alignment: .trailing)
                }
            }
            .padding().activityBackgroundTint(Theme.bg)
        } dynamicIsland: { ctx in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Image(systemName: "car.fill").foregroundStyle(Theme.accent(ctx.attributes.accent)).font(.title2)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(Fmt.zl(ctx.state.cost)).font(Theme.f(18, .semibold)).foregroundStyle(Theme.accent(ctx.attributes.accent)).monospacedDigit()
                }
                DynamicIslandExpandedRegion(.center) {
                    Text("\(Fmt.km(ctx.state.meters)) km")
                        .font(Theme.f(34, .bold)).foregroundStyle(Theme.ink).monospacedDigit()
                }
                DynamicIslandExpandedRegion(.bottom) {
                    Text(timerInterval: ctx.attributes.startedAt...Date.distantFuture, countsDown: false)
                        .font(Theme.f(13)).foregroundStyle(Theme.ink.opacity(0.6)).monospacedDigit()
                }
            } compactLeading: {
                Image(systemName: "car.fill").foregroundStyle(Theme.accent(ctx.attributes.accent))
            } compactTrailing: {
                Text(Fmt.km(ctx.state.meters)).font(Theme.f(14, .semibold)).monospacedDigit()
                    .foregroundStyle(Theme.accent(ctx.attributes.accent))
            } minimal: {
                Image(systemName: "car.fill").foregroundStyle(Theme.accent(ctx.attributes.accent))
            }
            .keylineTint(Theme.accent(ctx.attributes.accent))
        }
    }
}
