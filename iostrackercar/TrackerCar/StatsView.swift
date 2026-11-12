import SwiftUI
import SwiftData
import Charts

struct StatsView: View {
    @Environment(\.accentIdx) private var _accent
    // Tylko zakończone przejazdy: trwający przejazd nie wywołuje przeliczania statystyk przy każdym zapisie.
    @Query(filter: #Predicate<Trip> { $0.endedAt != nil }, sort: \Trip.startedAt) private var finished: [Trip]

    private struct Month: Identifiable {
        let date: Date, km: Double, cost: Double, count: Int
        var id: Date { date }
    }

    private func makeMonths() -> [Month] {
        let cal = Calendar.current
        let groups = Dictionary(grouping: finished) { cal.date(from: cal.dateComponents([.year, .month], from: $0.startedAt))! }
        return groups.map { Month(date: $0.key, km: $0.value.reduce(0) { $0 + $1.meters } / 1000,
                                  cost: $0.value.reduce(0) { $0 + $1.cost }, count: $0.value.count) }
            .sorted { $0.date < $1.date }.suffix(6).map { $0 }
    }

    var body: some View {
        // Wszystko liczone raz na render (wcześniej grupowanie wykonywane 3-4 razy).
        let months = makeMonths()
        let cal = Calendar.current
        let current = months.first { cal.isDate($0.date, equalTo: Date(), toGranularity: .month) }
        let longest = finished.reduce(0) { max($0, $1.meters) }
        let average = finished.isEmpty ? 0 : finished.reduce(0) { $0 + $1.meters } / Double(finished.count)
        ZStack {
            Theme.bg.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Text("STATYSTYKI").font(Theme.f(14, .medium)).tracking(5)
                        .foregroundStyle(Theme.ink.opacity(0.55)).padding(.top, 12)

                    HStack(spacing: 10) {
                        tile("TEN MIESIĄC", "\(Fmt.km((current?.km ?? 0) * 1000)) km", Fmt.zl(current?.cost ?? 0))
                        tile("PRZEJAZDY", "\(current?.count ?? 0)", "w tym miesiącu")
                    }
                    HStack(spacing: 10) {
                        tile("NAJDŁUŻSZY", "\(Fmt.km(longest)) km", "")
                        tile("ŚREDNIO", "\(Fmt.km(average)) km", "na przejazd")
                    }

                    VStack(alignment: .leading, spacing: 10) {
                        Text("KM / MIESIĄC").font(Theme.f(13, .medium)).tracking(3)
                            .foregroundStyle(Theme.ink.opacity(0.45))
                        if months.isEmpty {
                            Text("Brak danych").font(Theme.f(15)).foregroundStyle(Theme.ink.opacity(0.5))
                                .frame(maxWidth: .infinity).padding(.vertical, 40)
                        } else {
                            Chart(months) {
                                BarMark(x: .value("Miesiąc", $0.date, unit: .month), y: .value("km", $0.km))
                                    .foregroundStyle(Theme.accent).cornerRadius(4)
                            }
                            .chartXAxis {
                                AxisMarks(values: .stride(by: .month)) { _ in
                                    AxisValueLabel(format: .dateTime.month(.abbreviated).locale(Locale(identifier: "pl_PL")))
                                        .foregroundStyle(Theme.ink.opacity(0.6))
                                }
                            }
                            .chartYAxis { AxisMarks { _ in AxisValueLabel().foregroundStyle(Theme.ink.opacity(0.6)) } }
                            .frame(height: 200)
                        }
                    }
                    .padding(16)
                    .background(Theme.card, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
                .padding(.horizontal, 20).padding(.bottom, 40)
            }
        }
        .foregroundStyle(Theme.ink)
    }

    private func tile(_ title: String, _ value: String, _ sub: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(Theme.f(12, .medium)).tracking(3).foregroundStyle(Theme.ink.opacity(0.45))
            Text(value).font(Theme.f(30, .bold)).monospacedDigit()
            Text(sub).font(Theme.f(13)).foregroundStyle(Theme.accent)
        }
        .frame(maxWidth: .infinity, alignment: .leading).padding(16)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}
