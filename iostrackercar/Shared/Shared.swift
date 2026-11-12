import SwiftUI
import ActivityKit

// MARK: - Live Activity (wspólne dla apki i widgetu)

struct TripAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var meters: Double
        var cost: Double
    }
    var startedAt: Date
    var accent: Int = 0       // wybrany motyw (indeks koloru akcentu)
}

// MARK: - Motyw

enum Theme {
    static let bg = Color(red: 0x1b/255, green: 0x1b/255, blue: 0x1b/255)      // #1b1b1b
    /// Motywy: kolor akcentu do wyboru w Ustawieniach (domyślnie czerwony #e9204f).
    static let accentOptions: [(name: String, color: Color)] = [
        ("Czerwony", Color(red: 0xe9/255, green: 0x20/255, blue: 0x4f/255)),
        ("Turkus", Color(red: 0x1f/255, green: 0xd6/255, blue: 0xc6/255)),
        ("Limonka", Color(red: 0xc6/255, green: 0xff/255, blue: 0x3d/255)),
        ("Fiolet", Color(red: 0xa7/255, green: 0x8b/255, blue: 0xfa/255)),
        ("Pomarańcz", Color(red: 0xff/255, green: 0x7a/255, blue: 0x2f/255)),
        ("Niebieski", Color(red: 0x3b/255, green: 0x9b/255, blue: 0xff/255)),
    ]
    static func accent(_ index: Int) -> Color { accentOptions[min(max(index, 0), accentOptions.count - 1)].color }
    static var accent: Color { accent(Prefs.accentIndex) }
    static let ink = Color(red: 0xf3/255, green: 0xf3/255, blue: 0xf3/255)     // #f3f3f3
    static let card = ink.opacity(0.06)

    /// Oswald (czcionka dołączona do apki).
    static func f(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        .custom("Oswald", size: size).weight(weight)
    }
}

// MARK: - Formatowanie

enum Fmt {
    static func km(_ meters: Double) -> String {
        (meters / 1000).formatted(.number.precision(.fractionLength(1)).locale(Locale(identifier: "pl_PL")))
    }
    static func zl(_ v: Double) -> String {
        v.formatted(.currency(code: "PLN").locale(Locale(identifier: "pl_PL")))
    }
}

// MARK: - Ustawienia (UserDefaults, tylko lokalnie)

enum Prefs {
    static let d = UserDefaults.standard
    static var price: Double {
        get { d.object(forKey: "price") as? Double ?? 6.0 }
        set { d.set(newValue, forKey: "price"); d.set(Date(), forKey: "priceDate") }
    }
    static var consumption: Double {      // l/100 km
        get { d.object(forKey: "cons") as? Double ?? 7.0 }
        set { d.set(newValue, forKey: "cons") }
    }
    static var split: Int {
        get { max(1, d.object(forKey: "split") as? Int ?? 2) }
        set { d.set(max(1, newValue), forKey: "split") }
    }
    static var accentIndex: Int {
        get { d.integer(forKey: "accent") }
        set { d.set(newValue, forKey: "accent") }
    }
    static var priceDate: Date? { d.object(forKey: "priceDate") as? Date }
    static var sourceURL: String {
        get { d.string(forKey: "srcURL") ?? "" }
        set { d.set(newValue, forKey: "srcURL") }
    }
    static var sourceRegex: String {
        get { d.string(forKey: "srcRegex") ?? "" }
        set { d.set(newValue, forKey: "srcRegex") }
    }
}
