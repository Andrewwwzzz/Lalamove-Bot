import Foundation

enum Verdict: String { case take, ok, skip }

struct Score {
    let takeHome: Double     // cash you keep from this order
    let netGain: Double      // take-home minus petrol for the extra km
    let perHour: Double      // S$/hr for the extra time, after petrol + other costs
    let minFare: Double      // shown fare needed to hit the target
    let pickupKm: Double
    let maxPickupKm: Double  // furthest pickup that still hits target (standalone)
    let extraKm: Double
    let extraMin: Double
    let verdict: Verdict
}

/// Same maths as the Android app and web tracker.
enum Scorer {
    static let keep = 0.785       // share of shown fare you keep (15% commission + 9% VAT on it + ~5% CPF)
    static let keepPriority = 0.95 // share of priority fee you keep (CPF only)
    static let fuel = 0.25        // S$/km (8 km/L at S$2/L)
    static let other = 1.0        // S$/hr other costs
    static let speed = 25.0       // km/h average incl. traffic
    static let stopMin = 5.0      // minutes per pickup or drop

    static func minutes(_ km: Double, stops: Int) -> Double { km / speed * 60 + Double(stops) * stopMin }

    /// Shortest km visiting all points from start (exact up to 8 points, greedy beyond).
    static func routeKm(from start: LatLng, through pts: [LatLng]) -> Double {
        if pts.isEmpty { return 0 }
        if pts.count > 8 {
            var left = pts, cur = start, total = 0.0
            while !left.isEmpty {
                let i = left.indices.min { Geo.km(cur, left[$0]) < Geo.km(cur, left[$1]) }!
                total += Geo.km(cur, left[i]); cur = left[i]; left.remove(at: i)
            }
            return total
        }
        var best = Double.greatestFiniteMagnitude
        var used = [Bool](repeating: false, count: pts.count)
        func go(_ cur: LatLng, _ cost: Double, _ n: Int) {
            if cost >= best { return }
            if n == pts.count { best = cost; return }
            for i in pts.indices where !used[i] {
                used[i] = true
                go(pts[i], cost + Geo.km(cur, pts[i]), n + 1)
                used[i] = false
            }
        }
        go(start, 0, 0)
        return best
    }

    /// Rule: shown fare >= b + a * (km to pickup + job km).
    static func coefficients(target: Double) -> (a: Double, b: Double) {
        let t = target + other
        return ((t / speed + fuel) / keep, (t * 2 * stopMin / 60) / keep)
    }

    /**
     Score an offer.
     - pickupKm: Lalamove's own "(~X km)" distance from you to the pickup.
     - onBoardDrops: drop-offs of jobs you're already carrying. With none, it's a standalone job.
       With some, the offer's drop is slotted into the best place on the way.
     */
    static func score(pickup p: LatLng, drop d: LatLng, pickupKm dead: Double, fare: Double, priority: Double,
                      onBoardDrops drops: [LatLng], target: Double) -> Score {
        let job = Geo.km(p, d)
        let extraKm: Double
        if drops.isEmpty {
            extraKm = dead + job
        } else {
            // You're roughly near the pickup; compare finishing your drops with and without this order.
            let base = routeKm(from: p, through: drops)
            let with = routeKm(from: p, through: drops + [d])
            extraKm = dead + max(with - base, 0.3)
        }
        let extraMin = minutes(extraKm, stops: 2)
        let take = fare * keep + priority * keepPriority
        let perHour = (take - fuel * extraKm) / extraMin * 60 - other
        let need = ((target + other) / 60 * extraMin + fuel * extraKm - priority * keepPriority) / keep
        let (a, b) = coefficients(target: target)
        let maxPick = (fare + priority * keepPriority / keep - b) / a - job
        let verdict: Verdict = perHour >= target * 1.25 ? .take : (perHour >= target ? .ok : .skip)
        return Score(takeHome: take, netGain: take - fuel * extraKm, perHour: perHour, minFare: max(need, 0),
                     pickupKm: dead, maxPickupKm: max(maxPick, 0), extraKm: extraKm, extraMin: extraMin, verdict: verdict)
    }
}
