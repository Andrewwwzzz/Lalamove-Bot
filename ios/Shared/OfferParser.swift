import Foundation

/// One line of text found on screen, with its box in screen pixels.
struct OcrLine {
    let text: String
    let left: Int, top: Int, right: Int, bottom: Int
}

struct ParsedOffer: Equatable {
    let pickup: String
    let drop: String
    let fare: Double
    let priority: Double
    let jobKm: Double?      // Record screen "11.7 km"
    let pickupKm: Double?   // Pickup screen "(~2.7 km) Pick Up Now"
    let type: String?       // "Regular", "Pooling", ...
    var extraStops: Int = 0 // "1 stop" / "2 stops" line between pickup and drop
    var key: String { "\(pickup)|\(drop)|\(fare)" }
}

/**
 Turns the text on a Lalamove list into orders. Same rules as the Android parser.

 Pickup tab (offers):           Record → Ongoing (your jobs):
   (~2.7 km) Pick Up Now          11.7 km      # 3632...
   Regular                        Suntec City
   Serangoon North                Bedok
   East Coast Road                (note)
   Car                            S$15.70
   S$17.90 + 1.70
 */
enum OfferParser {
    private static func rx(_ p: String) -> NSRegularExpression {
        try! NSRegularExpression(pattern: p, options: [.caseInsensitive])
    }
    private static let fareRx = rx(#"(?:S\s?)?\$\s?(\d{1,3}(?:[.,]\d{1,2})?)"#)
    private static let pickupHeaderRx = rx(#"^[^\d]{0,4}(\d{1,3}(?:[.,]\d{1,2})?)\s?km\s?\)?.*p[il1]ck\s?up"#)
    private static let kmRx = rx(#"^[^\d\w]{0,4}(\d{1,3}(?:[.,]\d{1,2})?)\s?km\b"#)
    private static let cardStartRx = rx(#"^(p[il1]ck\s?up\s?now|in\s?progress)$"#)
    private static let bonusRx = rx(#"\+\s?\$?\s?(\d{1,3}[.,]\d{1,2})"#)
    private static let idRx = rx(#"#\s?\d{6,}"#)
    private static let timeRx = rx(#"\d{1,2}:\d{2}"#)
    private static let stopsRx = rx(#"^(\d{1,2})\s?stops?\b"#)

    private static let types: Set<String> = ["regular", "pooling", "priority", "scheduled", "express", "multi-stop", "multi stop"]

    /// App chrome and labels that are never place names.
    private static let uiWords: Set<String> = [
        "ongoing", "completed", "canceled", "cancelled", "in progress", "pickup", "pick up", "pick up now", "record",
        "wallet", "mission", "profile", "accept", "take order", "details", "breakdown", "rates",
        "yesterday", "today", "lte", "delivery fee", "surcharge", "deductions", "commission", "vat",
        "earnings", "received by credit", "priority", "priority fee", "available", "orders", "filter",
        "sort", "nearby", "all", "car", "van", "lorry", "motorcycle", "mpv", "scheduled", "immediate", "now",
        "on duty", "off duty", "home", "en route", "above 30 km", "other", "regular", "pooling", "offer reader",
        "courier", "bike", "truck", "1.7m van", "2.4m van", "10ft lorry", "14ft lorry", "heatmap", "new"
    ]

    /// First match's capture group 1 (and the full match range).
    private static func match(_ r: NSRegularExpression, _ s: String) -> (whole: Range<String.Index>, g1: String)? {
        let ns = NSRange(s.startIndex..., in: s)
        guard let m = r.firstMatch(in: s, range: ns),
              let whole = Range(m.range, in: s) else { return nil }
        let g1 = m.numberOfRanges > 1 ? Range(m.range(at: 1), in: s).map { String(s[$0]) } ?? "" : ""
        return (whole, g1)
    }
    private static func has(_ r: NSRegularExpression, _ s: String) -> Bool { match(r, s) != nil }
    private static func num(_ s: String) -> Double? { Double(s.replacingOccurrences(of: ",", with: ".")) }

    static func isPlace(_ raw: String) -> Bool {
        let s = raw.trimmingCharacters(in: .whitespaces)
        guard (3...40).contains(s.count), let f = s.first, f.isLetter, !f.isLowercase else { return false }
        if has(timeRx, s) || has(idRx, s) { return false }
        if uiWords.contains(s.lowercased()) { return false }
        let letters = s.filter { $0.isLetter }.count
        let nonSpace = s.filter { !$0.isWhitespace }.count
        if letters < 3 || Double(letters) / Double(max(nonSpace, 1)) < 0.7 { return false }
        if s.split(whereSeparator: { $0.isWhitespace }).count > 6 { return false } // notes are sentences
        return true
    }

    static func parse(_ lines: [OcrLine]) -> [ParsedOffer] {
        let sorted = lines.sorted { ($0.top / 12, $0.left) < ($1.top / 12, $1.left) }
        var out: [ParsedOffer] = []
        var places: [String] = []
        var inCard = false
        var jobKm: Double? = nil, pickupKm: Double? = nil, type: String? = nil, priority = 0.0, stops = 0

        func startCard() { places = []; jobKm = nil; pickupKm = nil; type = nil; priority = 0; stops = 0; inCard = true }

        for line in sorted {
            let t = line.text.trimmingCharacters(in: .whitespaces)
            if t.isEmpty { continue }
            let low = t.lowercased()

            if let ph = match(pickupHeaderRx, t) { startCard(); pickupKm = num(ph.g1); continue }
            if has(cardStartRx, t) { if !inCard || !places.isEmpty { startCard() }; continue }
            if let k = match(kmRx, t) {
                startCard()
                if let c = t.first, !c.isNumber { pickupKm = num(k.g1) } else { jobKm = num(k.g1) }
                continue
            }
            if inCard, let st = match(stopsRx, t) { stops = Int(st.g1) ?? 1; continue }
            if low.contains("multi") && low.contains("stop") { stops = max(stops, 1) }
            if types.contains(low) {
                if !inCard { startCard() }   // header missed: the type tag still marks a new card
                type = t; continue
            }
            if inCard && t.hasPrefix("+") {
                if let b = match(bonusRx, t) { priority += num(b.g1) ?? 0 }
                continue
            }
            if let fm = match(fareRx, t) {
                guard let v = num(fm.g1) else { continue }
                if low.contains("priority") { priority = v; continue }
                let rest = String(t[fm.whole.upperBound...])
                if let b = match(bonusRx, rest) { priority += num(b.g1) ?? 0 }
                if inCard && (3...300).contains(v) && places.count >= 2 {
                    out.append(ParsedOffer(pickup: places[0], drop: places[1],
                                           fare: v, priority: priority, jobKm: jobKm, pickupKm: pickupKm, type: type, extraStops: stops))
                }
                places = []; inCard = false
                continue
            }
            if has(idRx, t) { continue }
            if inCard && isPlace(t) { places.append(t) }
        }
        var seen = Set<String>()
        return out.filter { seen.insert($0.key).inserted }
    }

    /// Lalamove's Record screen has the Ongoing / Completed / Canceled tabs at the top.
    static func isRecordScreen(_ lines: [OcrLine]) -> Bool {
        let t = Set(lines.map { $0.text.trimmingCharacters(in: .whitespaces).lowercased() })
        return t.contains("ongoing") && t.contains("completed") && (t.contains("canceled") || t.contains("cancelled"))
    }

    static func hasInProgress(_ lines: [OcrLine]) -> Bool {
        lines.contains { $0.text.trimmingCharacters(in: .whitespaces).lowercased() == "in progress" }
    }
}
