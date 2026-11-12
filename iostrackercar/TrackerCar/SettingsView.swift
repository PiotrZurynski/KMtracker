import SwiftUI
import SwiftData

struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var ctx
    @State private var price = Prefs.price
    @State private var cons = Prefs.consumption
    @State private var split = Prefs.split
    @State private var url = Prefs.sourceURL
    @State private var regex = Prefs.sourceRegex
    @State private var status = ""
    @State private var loading = false

    var body: some View {
        NavigationStack {
            Form {
                Section("Paliwo") {
                    HStack {
                        Text("Cena za litr")
                        Spacer()
                        TextField("zł", value: $price, format: .number).keyboardType(.decimalPad)
                            .multilineTextAlignment(.trailing).frame(width: 90)
                        Text("zł")
                    }
                    HStack {
                        Text("Spalanie")
                        Spacer()
                        TextField("l/100 km", value: $cons, format: .number).keyboardType(.decimalPad)
                            .multilineTextAlignment(.trailing).frame(width: 90)
                        Text("l/100 km")
                    }
                    Stepper("Dzielone na: \(split)", value: $split, in: 1...6)
                    Button("Zastosuj cenę i spalanie do nierozliczonych przejazdów") {
                        let all = (try? ctx.fetch(FetchDescriptor<Trip>(predicate: #Predicate { !$0.settled }))) ?? []
                        all.forEach { if price > 0 { $0.pricePerLiter = price }; if cons > 0 { $0.consumption = cons } }
                        try? ctx.save()
                        status = "Zaktualizowano \(all.count) przejazdów ✓"
                    }
                    if let d = Prefs.priceDate {
                        Text("Cena z \(d.formatted(date: .abbreviated, time: .shortened))")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
                Section {
                    TextField("https://… (źródło ceny)", text: $url)
                        .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                    TextField("Regex z grupą na cenę", text: $regex)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    Button {
                        Prefs.sourceURL = url; Prefs.sourceRegex = regex
                        loading = true
                        Task {
                            do { price = try await FuelPriceService.fetch(); status = "Pobrano ✓" }
                            catch { status = error.localizedDescription }
                            loading = false
                        }
                    } label: {
                        HStack { Text("Pobierz cenę z internetu"); if loading { ProgressView() } }
                    }
                    if !status.isEmpty { Text(status).font(.footnote).foregroundStyle(.secondary) }
                } header: { Text("Automatyczna cena (opcjonalnie)") }
                  footer: { Text("Bez internetu używana jest ostatnia zapisana cena.") }

                Section { Text("Dane trzymane są wyłącznie na tym telefonie. Lokalizacja jest używana tylko między Start a Stop.")
                    .font(.footnote).foregroundStyle(.secondary) }
            }
            .navigationTitle("Ustawienia")
            .toolbar { Button("Gotowe") { save(); dismiss() } }
            .scrollContentBackground(.hidden)
            .background(Theme.bg)
        }
        .font(Theme.f(16))
        .tint(Theme.accent)
    }

    private func save() {
        if price > 0 { Prefs.price = price }
        if cons > 0 { Prefs.consumption = cons }
        Prefs.split = split
        Prefs.sourceURL = url
        Prefs.sourceRegex = regex
    }
}
