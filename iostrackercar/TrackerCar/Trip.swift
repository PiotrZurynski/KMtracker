import Foundation
import SwiftData

@Model
final class Trip {
    var id: UUID = UUID()
    var startedAt: Date = Date()
    var endedAt: Date?            // nil = przejazd trwa
    var meters: Double = 0
    var pricePerLiter: Double = 6.0   // migawka z chwili startu
    var consumption: Double = 7.0
    var split: Int = 2
    var settled: Bool = false

    init(price: Double, consumption: Double, split: Int) {
        self.pricePerLiter = price
        self.consumption = consumption
        self.split = split
    }

    /// Twoja część kosztu paliwa (po podziale).
    var cost: Double {
        meters / 1000 * consumption / 100 * pricePerLiter / Double(max(1, split))
    }
}
