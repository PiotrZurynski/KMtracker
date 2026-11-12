import Foundation

/// Pobiera cenę litra paliwa. Brak oficjalnego API stacji Moya, więc źródło jest
/// konfigurowalne w Ustawieniach: adres HTTPS + wyrażenie regularne z jedną grupą
/// przechwytującą cenę (np. `(\d+[,.]\d{2})`). Bez źródła cenę wpisujesz ręcznie.
enum FuelPriceService {
    enum Failure: LocalizedError {
        case notConfigured, badURL, parse
        var errorDescription: String? {
            switch self {
            case .notConfigured: "Ustaw źródło ceny w Ustawieniach lub wpisz cenę ręcznie."
            case .badURL: "Adres musi zaczynać się od https://"
            case .parse: "Nie znaleziono ceny na stronie."
            }
        }
    }

    private static let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral     // bez cache i ciasteczek
        c.timeoutIntervalForRequest = 10
        c.waitsForConnectivity = false
        return URLSession(configuration: c)
    }()

    static func fetch() async throws -> Double {
        guard !Prefs.sourceURL.isEmpty, !Prefs.sourceRegex.isEmpty else { throw Failure.notConfigured }
        let src = Prefs.sourceURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: src), url.scheme?.lowercased() == "https", url.host?.isEmpty == false,
              Prefs.sourceRegex.count <= 300 else { throw Failure.badURL }
        let (data, response) = try await session.data(from: url)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) { throw Failure.parse }
        guard data.count < 2_000_000,
              let html = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1),
              let re = try? NSRegularExpression(pattern: Prefs.sourceRegex),
              let m = re.firstMatch(in: html, range: NSRange(html.startIndex..., in: html)),
              m.numberOfRanges > 1, let r = Range(m.range(at: 1), in: html),
              let v = Double(html[r].replacingOccurrences(of: ",", with: ".")),
              (2...20).contains(v) else { throw Failure.parse }
        return v
    }
}
