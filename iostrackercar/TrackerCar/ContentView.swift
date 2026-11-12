import SwiftUI
import SwiftData

struct ContentView: View {
    @AppStorage("tab") private var tab = 0   // ustawiane też argumentem -tab w podglądzie
    var body: some View {
        TabView(selection: $tab) {
            HomeView().tabItem { Label("Start", systemImage: "car.fill") }.tag(0)
            TripsView().tabItem { Label("Przejazdy", systemImage: "list.bullet") }.tag(1)
        }
        .tint(Theme.accent)
    }
}

// MARK: - Zakładka Przejazdy

struct TripsView: View {
    @Environment(LocationTracker.self) private var tracker
    @Query(sort: \Trip.startedAt, order: .reverse) private var trips: [Trip]
    @Query(sort: \Settlement.date, order: .reverse) private var settlements: [Settlement]
    @State private var tab = 0

    private var days: [(day: Date, trips: [Trip])] {
        Dictionary(grouping: trips.filter { $0.endedAt != nil || $0.id == tracker.current?.id }) {
            Calendar.current.startOfDay(for: $0.startedAt)
        }
        .map { (day: $0.key, trips: $0.value.sorted { $0.startedAt > $1.startedAt }) }
        .sorted { $0.day > $1.day }
    }

    var body: some View {
        ZStack {
            Theme.bg.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 16) {
                    Picker("", selection: $tab) {
                        Text("Przejazdy").tag(0)
                        Text("Rozliczenia").tag(1)
                    }.pickerStyle(.segmented).padding(.top, 8)

                    if tab == 0 {
                        if days.isEmpty { empty("Brak przejazdów") }
                        ForEach(days, id: \.day) { DayCard(day: $0.day, trips: $0.trips) }
                    } else {
                        if settlements.isEmpty { empty("Brak rozliczeń") }
                        ForEach(settlements) { s in
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(s.date.formatted(date: .long, time: .shortened))
                                        .font(.subheadline.weight(.semibold))
                                    Text("\(s.tripCount) przejazdów · \(Fmt.km(s.meters)) km")
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                Text(Fmt.zl(s.amount)).font(.headline).foregroundStyle(Theme.accent).monospacedDigit()
                            }
                            .padding(16)
                            .background(Theme.card, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                        }
                    }
                }.padding(.horizontal, 20).padding(.bottom, 40)
            }
        }
    }

    private func empty(_ t: String) -> some View {
        Text(t).font(.footnote).foregroundStyle(.secondary).padding(.top, 40)
    }
}

// MARK: - Zakładka Start

struct HomeView: View {
    @Environment(\.modelContext) private var ctx
    @Environment(LocationTracker.self) private var tracker
    @Query(sort: \Trip.startedAt, order: .reverse) private var trips: [Trip]
    @State private var showSettings = false
    @State private var confirmSettle = false

    private var owed: [Trip] { trips.filter { !$0.settled } }
    private var owedMeters: Double { owed.reduce(0) { $0 + $1.meters } }
    private var owedCost: Double { owed.reduce(0) { $0 + $1.cost } }

    var body: some View {
        ZStack {
            Theme.bg.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 28) {
                    header
                    hero
                    StartStopButton(isOn: tracker.isTracking) {
                        tracker.isTracking ? tracker.stop() : tracker.requestAndStart()
                    }
                    if tracker.authorization == .denied || tracker.authorization == .restricted {
                        Text("Brak zgody na lokalizację. Włącz ją w Ustawieniach iOS.")
                            .font(.footnote).foregroundStyle(Theme.stop)
                    }
                    if !owed.isEmpty && !tracker.isTracking {
                        Button { confirmSettle = true } label: {
                            Text("ROZLICZ \(Fmt.zl(owedCost))")
                                .font(.subheadline.weight(.heavy)).tracking(2)
                                .padding(.horizontal, 28).padding(.vertical, 14)
                                .overlay(Capsule().strokeBorder(Theme.accent.opacity(0.6)))
                                .foregroundStyle(Theme.accent)
                        }
                    }
                }
                .padding(.horizontal, 20).padding(.bottom, 40)
            }
        }
        .onAppear { tracker.attach(ctx) }
        .sheet(isPresented: $showSettings) { SettingsView().presentationDetents([.medium, .large]) }
        .confirmationDialog("Rozliczyć \(Fmt.zl(owedCost)) (\(Fmt.km(owedMeters)) km)?", isPresented: $confirmSettle, titleVisibility: .visible) {
            Button("Rozlicz") {
                ctx.insert(Settlement(meters: owedMeters, amount: owedCost, tripCount: owed.count))
                owed.forEach { $0.settled = true }
                try? ctx.save()
                UINotificationFeedbackGenerator().notificationOccurred(.success)
            }
        }
    }

    private var header: some View {
        HStack {
            Text("TRACKERCAR").font(.caption.weight(.heavy)).tracking(4)
                .foregroundStyle(.white.opacity(0.5))
            Spacer()
            Button { showSettings = true } label: {
                Image(systemName: "slider.horizontal.3").foregroundStyle(.white.opacity(0.7))
            }
        }.padding(.top, 8)
    }

    private var hero: some View {
        VStack(spacing: 6) {
            Text(Fmt.km(owedMeters))
                .font(.system(size: 76, weight: .black, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(LinearGradient(colors: [Theme.accent, Theme.accent2],
                                                startPoint: .leading, endPoint: .trailing))
                .contentTransition(.numericText())
                .animation(.smooth, value: owedMeters)
            Text("KM DO ROZLICZENIA").font(.caption2.weight(.bold)).tracking(3)
                .foregroundStyle(.white.opacity(0.4))
            Text("\(Fmt.zl(owedCost)) · Twoja ½")
                .font(.title3.weight(.semibold)).monospacedDigit()
                .padding(.top, 8)
        }.padding(.top, 12)
    }

}

// MARK: - Start / Stop

struct StartStopButton: View {
    let isOn: Bool
    let action: () -> Void
    @State private var spin = 0.0

    var body: some View {
        Button(action: action) {
            ZStack {
                Circle().fill((isOn ? Theme.stop : Theme.accent).opacity(0.18)).blur(radius: 30)
                Circle()
                    .strokeBorder(AngularGradient(colors: [Theme.accent, Theme.accent2, Theme.accent],
                                                  center: .center), lineWidth: 3)
                    .rotationEffect(.degrees(spin))
                    .opacity(isOn ? 1 : 0.35)
                Circle().fill(.white.opacity(0.04)).padding(10)
                Text(isOn ? "STOP" : "START")
                    .font(.system(size: 28, weight: .heavy, design: .rounded)).tracking(3)
                    .foregroundStyle(isOn ? Theme.stop : Theme.accent)
            }
            .frame(width: 170, height: 170)
        }
        .buttonStyle(.plain)
        .onChange(of: isOn) { _, on in
            if on { withAnimation(.linear(duration: 4).repeatForever(autoreverses: false)) { spin = 360 } }
            else { withAnimation(.default) { spin = 0 } }
        }
        .onAppear {
            if isOn { withAnimation(.linear(duration: 4).repeatForever(autoreverses: false)) { spin = 360 } }
        }
        .sensoryFeedback(.impact, trigger: isOn)
    }
}

// MARK: - Dzień (zbiorczy, rozwijany)

struct DayCard: View {
    @Environment(\.modelContext) private var ctx
    let day: Date
    let trips: [Trip]
    @State private var open = UserDefaults.standard.bool(forKey: "expand")

    private var meters: Double { trips.reduce(0) { $0 + $1.meters } }
    private var cost: Double { trips.reduce(0) { $0 + $1.cost } }
    private var settled: Bool { trips.allSatisfy(\.settled) }

    var body: some View {
        VStack(spacing: 0) {
            Button { withAnimation(.spring(duration: 0.35)) { open.toggle() } } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(day.formatted(.dateTime.weekday(.wide).day().month().locale(Locale(identifier: "pl_PL"))))
                            .font(.subheadline.weight(.semibold))
                        Text("\(trips.count) \(trips.count == 1 ? "przejazd" : "przejazdy")")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    VStack(alignment: .trailing, spacing: 2) {
                        Text("\(Fmt.km(meters)) km").font(.subheadline.weight(.bold)).monospacedDigit()
                        Text(Fmt.zl(cost)).font(.caption).foregroundStyle(Theme.accent).monospacedDigit()
                    }
                    Image(systemName: "chevron.down").font(.caption.weight(.bold))
                        .foregroundStyle(.secondary).rotationEffect(.degrees(open ? 180 : 0)).padding(.leading, 6)
                }
                .padding(16).contentShape(Rectangle())
            }.buttonStyle(.plain)

            if open {
                VStack(spacing: 0) {
                    ForEach(trips) { t in
                        HStack {
                            Text("\(t.startedAt.formatted(date: .omitted, time: .shortened))–\(t.endedAt?.formatted(date: .omitted, time: .shortened) ?? "trwa")")
                                .font(.footnote).foregroundStyle(.secondary).monospacedDigit()
                            Spacer()
                            Text("\(Fmt.km(t.meters)) km").font(.footnote.weight(.semibold)).monospacedDigit()
                        }
                        .padding(.horizontal, 16).padding(.vertical, 10)
                        .contextMenu {
                            Button(role: .destructive) { ctx.delete(t); try? ctx.save() } label: {
                                Label("Usuń przejazd", systemImage: "trash")
                            }
                        }
                        Divider().overlay(.white.opacity(0.06))
                    }
                }
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(.white.opacity(0.06)))
        .opacity(settled ? 0.4 : 1)
    }
}
