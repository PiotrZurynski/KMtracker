import Foundation
import SwiftData
import CoreLocation

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
    var routeData: Data = Data()      // trasa: pary Double (lat, lon), tylko lokalnie

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

extension Trip {
    var route: [CLLocationCoordinate2D] {
        let n = routeData.count / 16
        guard n > 0 else { return [] }
        return routeData.withUnsafeBytes { raw in
            (0..<n).map {
                CLLocationCoordinate2D(latitude: raw.loadUnaligned(fromByteOffset: $0 * 16, as: Double.self),
                                       longitude: raw.loadUnaligned(fromByteOffset: $0 * 16 + 8, as: Double.self))
            }
        }
    }

    func append(_ c: CLLocationCoordinate2D) {
        var a = [c.latitude, c.longitude]
        a.withUnsafeBytes { routeData.append(contentsOf: $0) }
    }
}

/// Zapis dokonanego rozliczenia (historia).
@Model
final class Settlement {
    var id: UUID = UUID()
    var date: Date = Date()
    var meters: Double = 0
    var amount: Double = 0
    var tripCount: Int = 0

    init(meters: Double, amount: Double, tripCount: Int) {
        self.meters = meters
        self.amount = amount
        self.tripCount = tripCount
    }
}
