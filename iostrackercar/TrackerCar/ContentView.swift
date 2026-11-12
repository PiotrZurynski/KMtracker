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
        .font(Theme.f(16))
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
                VStack(spacing: 14) {
                    HStack(spacing: 8) {
                        chip("PRZEJAZDY", 0)
                        chip("ROZLICZENIA", 1)
                        Spacer()
                    }.padding(.top, 12)

                    if tab == 0 {
                        if days.isEmpty { empty("Brak przejazdów") }
                        ForEach(days, id: \.day) { DayCard(day: $0.day, trips: $0.trips) }
                    } else {
                        if settlements.isEmpty { empty("Brak rozliczeń") }
                        ForEach(settlements) { s in
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(s.date.formatted(date: .long, time: .shortened))
                                        .font(Theme.f(16, .medium))
                                    Text("\(s.tripCount) przejazdów · \(Fmt.km(s.meters)) km")
                                        .font(Theme.f(13)).foregroundStyle(Theme.ink.opacity(0.55))
                                }
                                Spacer()
                                Text(Fmt.zl(s.amount)).font(Theme.f(22, .bold)).foregroundStyle(Theme.accent).monospacedDigit()
                            }
                            .padding(16)
                            .background(Theme.card, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                        }
                    }
                }.padding(.horizontal, 20).padding(.bottom, 40)
            }
        }
        .foregroundStyle(Theme.ink)
    }

    private func chip(_ t: String, _ i: Int) -> some View {
        Button { withAnimation(.snappy) { tab = i } } label: {
            Text(t).font(Theme.f(13, .medium)).tracking(2)
                .padding(.horizontal, 14).padding(.vertical, 8)
                .background(tab == i ? Theme.accent : Color.clear, in: Capsule())
                .overlay(Capsule().strokeBorder(tab == i ? Color.clear : Theme.ink.opacity(0.25)))
                .foregroundStyle(tab == i ? Theme.ink : Theme.ink.opacity(0.7))
        }
    }

    private func empty(_ t: String) -> some View {
        Text(t).font(Theme.f(15)).foregroundStyle(Theme.ink.opacity(0.5)).padding(.top, 40)
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
                VStack(spacing: 26) {
                    header
                    hero
                    StartStopButton(isOn: tracker.isTracking) {
                        tracker.isTracking ? tracker.stop() : tracker.requestAndStart()
                    }
                    if tracker.authorization == .denied || tracker.authorization == .restricted {
                        Text("Brak zgody na lokalizację. Włącz ją w Ustawieniach iOS.")
                            .font(Theme.f(14)).foregroundStyle(Theme.accent)
                    }
                    if !owed.isEmpty && !tracker.isTracking {
                        Button { confirmSettle = true } label: {
                            Text("ROZLICZ \(Fmt.zl(owedCost))")
                                .font(Theme.f(17, .medium)).tracking(3)
                                .padding(.horizontal, 30).padding(.vertical, 13)
                                .overlay(Capsule().strokeBorder(Theme.accent, lineWidth: 1.5))
                                .foregroundStyle(Theme.accent)
                        }
                    }
                }
                .padding(.horizontal, 20).padding(.bottom, 40)
            }
        }
        .foregroundStyle(Theme.ink)
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
            Text("TRACKERCAR").font(Theme.f(14, .medium)).tracking(5)
                .foregroundStyle(Theme.ink.opacity(0.55))
            Spacer()
            Button { showSettings = true } label: {
                Image(systemName: "slider.horizontal.3").foregroundStyle(Theme.ink.opacity(0.7))
            }
        }.padding(.top, 8)
    }

    private var hero: some View {
        VStack(spacing: 2) {
            Text(Fmt.km(owedMeters))
                .font(Theme.f(96, .bold))
                .monospacedDigit()
                .foregroundStyle(Theme.ink)
                .contentTransition(.numericText())
                .animation(.smooth, value: owedMeters)
            Text("KM DO ROZLICZENIA").font(Theme.f(13, .medium)).tracking(4)
                .foregroundStyle(Theme.ink.opacity(0.45))
            Text("\(Fmt.zl(owedCost)) · Twoja ½")
                .font(Theme.f(26, .semibold)).monospacedDigit()
                .foregroundStyle(Theme.accent)
                .padding(.top, 10)
        }.padding(.top, 8)
    }
}

// MARK: - Start / Stop (szkicowy znaczek)

struct StartStopButton: View {
    let isOn: Bool
    let action: () -> Void
    @State private var spin = 0.0

    var body: some View {
        Button(action: action) {
            VolvoMark(label: isOn ? "STOP" : "START", isOn: isOn, spin: spin)
                .frame(width: 210, height: 210)
        }
        .buttonStyle(.plain)
        .onChange(of: isOn) { _, on in
            if on { withAnimation(.linear(duration: 9).repeatForever(autoreverses: false)) { spin = 360 } }
            else { withAnimation(.default) { spin = 0 } }
        }
        .onAppear {
            if isOn { withAnimation(.linear(duration: 9).repeatForever(autoreverses: false)) { spin = 360 } }
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
    @State private var editing: Trip?

    private var meters: Double { trips.reduce(0) { $0 + $1.meters } }
    private var cost: Double { trips.reduce(0) { $0 + $1.cost } }
    private var settled: Bool { trips.allSatisfy(\.settled) }

    var body: some View {
        VStack(spacing: 0) {
            Button { withAnimation(.spring(duration: 0.35)) { open.toggle() } } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(day.formatted(.dateTime.weekday(.wide).day().month().locale(Locale(identifier: "pl_PL"))).uppercased())
                            .font(Theme.f(16, .medium)).tracking(1)
                        Text("\(trips.count) \(trips.count == 1 ? "przejazd" : "przejazdy")")
                            .font(Theme.f(13)).foregroundStyle(Theme.ink.opacity(0.5))
                    }
                    Spacer()
                    VStack(alignment: .trailing, spacing: 2) {
                        Text("\(Fmt.km(meters)) km").font(Theme.f(18, .semibold)).monospacedDigit()
                        Text(Fmt.zl(cost)).font(Theme.f(14)).foregroundStyle(Theme.accent).monospacedDigit()
                    }
                    Image(systemName: "chevron.down").font(.caption.weight(.bold))
                        .foregroundStyle(Theme.ink.opacity(0.5)).rotationEffect(.degrees(open ? 180 : 0)).padding(.leading, 6)
                }
                .padding(16).contentShape(Rectangle())
            }.buttonStyle(.plain)

            if open {
                VStack(spacing: 0) {
                    ForEach(trips) { t in
                        HStack {
                            Text("\(t.startedAt.formatted(date: .omitted, time: .shortened))–\(t.endedAt?.formatted(date: .omitted, time: .shortened) ?? "trwa")")
                                .font(Theme.f(14)).foregroundStyle(Theme.ink.opacity(0.6)).monospacedDigit()
                            Spacer()
                            Text("\(Fmt.km(t.meters)) km").font(Theme.f(15, .medium)).monospacedDigit()
                        }
                        .padding(.horizontal, 16).padding(.vertical, 10)
                        .contentShape(Rectangle())
                        .onTapGesture { if t.endedAt != nil { editing = t } }
                        .contextMenu {
                            Button(role: .destructive) { ctx.delete(t); try? ctx.save() } label: {
                                Label("Usuń przejazd", systemImage: "trash")
                            }
                        }
                        Divider().overlay(Theme.ink.opacity(0.08))
                    }
                }
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Theme.ink.opacity(0.07)))
        .foregroundStyle(Theme.ink)
        .opacity(settled ? 0.4 : 1)
        .sheet(item: $editing) { TripEditView(trip: $0).presentationDetents([.medium]) }
    }
}

// MARK: - Edycja przejazdu

struct TripEditView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var ctx
    @Bindable var trip: Trip

    var body: some View {
        NavigationStack {
            Form {
                Section("Przejazd") {
                    row("Dystans", unit: "km", value: Binding(
                        get: { (trip.meters / 1000 * 10).rounded() / 10 },
                        set: { trip.meters = max(0, $0) * 1000 }))
                    row("Cena za litr", unit: "zł", value: $trip.pricePerLiter)
                    row("Spalanie", unit: "l/100 km", value: $trip.consumption)
                }
                Section {
                    HStack {
                        Text("Twoja część")
                        Spacer()
                        Text(Fmt.zl(trip.cost)).foregroundStyle(Theme.accent).monospacedDigit()
                    }
                }
            }
            .navigationTitle("Edytuj przejazd")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { Button("Gotowe") { try? ctx.save(); dismiss() } }
            .scrollContentBackground(.hidden)
            .background(Theme.bg)
        }
        .font(Theme.f(16))
        .tint(Theme.accent)
    }

    private func row(_ title: String, unit: String, value: Binding<Double>) -> some View {
        HStack {
            Text(title)
            Spacer()
            TextField("0", value: value, format: .number).keyboardType(.decimalPad)
                .multilineTextAlignment(.trailing).frame(width: 90)
            Text(unit).foregroundStyle(Theme.ink.opacity(0.5))
        }
    }
}
