import Foundation

struct LatLng: Codable, Equatable {
    let lat: Double
    let lng: Double
}

/// Built-in Singapore areas, distance maths and fuzzy name matching.
enum Geo {
    /// Straight-line distance x this factor ~= road distance in Singapore.
    static let road = 1.35

    static let areas: [String: LatLng] = [
        "Admiralty": LatLng(lat: 1.4406, lng: 103.8009),
        "Alexandra": LatLng(lat: 1.287, lng: 103.802),
        "Aljunied": LatLng(lat: 1.3164, lng: 103.8829),
        "Ang Mo Kio": LatLng(lat: 1.3691, lng: 103.8454),
        "Ayer Rajah": LatLng(lat: 1.293, lng: 103.786),
        "Balestier": LatLng(lat: 1.3255, lng: 103.85),
        "Bartley": LatLng(lat: 1.3425, lng: 103.8796),
        "Bedok": LatLng(lat: 1.324, lng: 103.93),
        "Bendemeer": LatLng(lat: 1.313, lng: 103.863),
        "Bishan": LatLng(lat: 1.3508, lng: 103.8485),
        "Boon Lay": LatLng(lat: 1.3387, lng: 103.706),
        "Bras Basah": LatLng(lat: 1.2966, lng: 103.8506),
        "Bugis": LatLng(lat: 1.3005, lng: 103.8558),
        "Bukit Batok": LatLng(lat: 1.359, lng: 103.7637),
        "Bukit Merah": LatLng(lat: 1.2819, lng: 103.8239),
        "Bukit Permei": LatLng(lat: 1.2735, lng: 103.827),
        "Bukit Panjang": LatLng(lat: 1.379, lng: 103.7616),
        "Bukit Timah": LatLng(lat: 1.3294, lng: 103.8021),
        "Buona Vista": LatLng(lat: 1.3072, lng: 103.79),
        "Changi Airport": LatLng(lat: 1.3644, lng: 103.9915),
        "Changi Business Park": LatLng(lat: 1.334, lng: 103.964),
        "Changi Village": LatLng(lat: 1.389, lng: 103.988),
        "Chinatown": LatLng(lat: 1.2836, lng: 103.8443),
        "Choa Chu Kang": LatLng(lat: 1.3854, lng: 103.7443),
        "Clarke Quay": LatLng(lat: 1.2884, lng: 103.8465),
        "Clementi": LatLng(lat: 1.3151, lng: 103.7652),
        "Commonwealth": LatLng(lat: 1.3025, lng: 103.798),
        "Cuppage": LatLng(lat: 1.301, lng: 103.8405),
        "Defu": LatLng(lat: 1.353, lng: 103.892),
        "Dhoby Ghaut": LatLng(lat: 1.299, lng: 103.8455),
        "Dover": LatLng(lat: 1.3114, lng: 103.7786),
        "Eunos": LatLng(lat: 1.3196, lng: 103.903),
        "Expo": LatLng(lat: 1.335, lng: 103.9615),
        "Farrer Park": LatLng(lat: 1.3124, lng: 103.8543),
        "Farrer Road": LatLng(lat: 1.3172, lng: 103.8077),
        "Fernvale": LatLng(lat: 1.392, lng: 103.876),
        "Geylang": LatLng(lat: 1.318, lng: 103.887),
        "HarbourFront": LatLng(lat: 1.2653, lng: 103.822),
        "Hillview": LatLng(lat: 1.3626, lng: 103.7674),
        "Holland Village": LatLng(lat: 1.3112, lng: 103.7958),
        "Hougang": LatLng(lat: 1.3612, lng: 103.8863),
        "Joo Koon": LatLng(lat: 1.3277, lng: 103.6782),
        "Jurong East": LatLng(lat: 1.3331, lng: 103.7423),
        "Jurong West": LatLng(lat: 1.3404, lng: 103.709),
        "Kaki Bukit": LatLng(lat: 1.3355, lng: 103.905),
        "Kallang": LatLng(lat: 1.31, lng: 103.865),
        "Kallang Bahru": LatLng(lat: 1.3165, lng: 103.8705),
        "Katong": LatLng(lat: 1.305, lng: 103.905),
        "Kembangan": LatLng(lat: 1.321, lng: 103.913),
        "Kent Ridge": LatLng(lat: 1.2936, lng: 103.7845),
        "Kovan": LatLng(lat: 1.36, lng: 103.885),
        "Kranji": LatLng(lat: 1.425, lng: 103.762),
        "Lavender": LatLng(lat: 1.3074, lng: 103.863),
        "Lentor": LatLng(lat: 1.385, lng: 103.836),
        "Little India": LatLng(lat: 1.3066, lng: 103.8492),
        "Lorong Chuan": LatLng(lat: 1.3517, lng: 103.864),
        "Loyang": LatLng(lat: 1.37, lng: 103.97),
        "MacPherson": LatLng(lat: 1.326, lng: 103.89),
        "Mandai": LatLng(lat: 1.41, lng: 103.79),
        "Marina Bay": LatLng(lat: 1.282, lng: 103.8585),
        "Marine Parade": LatLng(lat: 1.303, lng: 103.907),
        "Mount Sinai": LatLng(lat: 1.32, lng: 103.802),
        "Mountbatten": LatLng(lat: 1.306, lng: 103.883),
        "Newton": LatLng(lat: 1.3138, lng: 103.838),
        "Novena": LatLng(lat: 1.3204, lng: 103.8438),
        "One-North": LatLng(lat: 1.2995, lng: 103.7873),
        "Orchard": LatLng(lat: 1.3048, lng: 103.8318),
        "Outram": LatLng(lat: 1.2803, lng: 103.8395),
        "Pasir Panjang": LatLng(lat: 1.276, lng: 103.791),
        "Pasir Ris": LatLng(lat: 1.3721, lng: 103.9474),
        "Paya Lebar": LatLng(lat: 1.318, lng: 103.893),
        "Pioneer": LatLng(lat: 1.3376, lng: 103.6973),
        "Potong Pasir": LatLng(lat: 1.3313, lng: 103.8688),
        "Punggol": LatLng(lat: 1.4043, lng: 103.902),
        "Queenstown": LatLng(lat: 1.2942, lng: 103.806),
        "Raffles Place": LatLng(lat: 1.284, lng: 103.8514),
        "Redhill": LatLng(lat: 1.2896, lng: 103.8168),
        "Robin Estate": LatLng(lat: 1.32, lng: 103.826),
        "Science Park": LatLng(lat: 1.2905, lng: 103.788),
        "Seah Street": LatLng(lat: 1.2965, lng: 103.8545),
        "Seletar": LatLng(lat: 1.404, lng: 103.869),
        "Selegie": LatLng(lat: 1.301, lng: 103.85),
        "Sembawang": LatLng(lat: 1.4491, lng: 103.8185),
        "Sengkang": LatLng(lat: 1.3917, lng: 103.895),
        "Serangoon North": LatLng(lat: 1.3705, lng: 103.8735),
        "Sentosa": LatLng(lat: 1.2494, lng: 103.8303),
        "Serangoon": LatLng(lat: 1.35, lng: 103.873),
        "East Coast Road": LatLng(lat: 1.3065, lng: 103.905),
        "Siglap": LatLng(lat: 1.312, lng: 103.927),
        "Simei": LatLng(lat: 1.3432, lng: 103.9533),
        "Sixth Avenue": LatLng(lat: 1.331, lng: 103.797),
        "Springleaf": LatLng(lat: 1.398, lng: 103.818),
        "Springside": LatLng(lat: 1.3995, lng: 103.8165),
        "Stevens": LatLng(lat: 1.32, lng: 103.826),
        "Sungei Kadut": LatLng(lat: 1.414, lng: 103.753),
        "Suntec City": LatLng(lat: 1.2945, lng: 103.859),
        "Tai Seng": LatLng(lat: 1.3358, lng: 103.8879),
        "Tampines": LatLng(lat: 1.354, lng: 103.944),
        "Tanah Merah": LatLng(lat: 1.327, lng: 103.946),
        "Tanjong Pagar": LatLng(lat: 1.2765, lng: 103.8455),
        "Teban Gardens": LatLng(lat: 1.322, lng: 103.744),
        "Telok Blangah": LatLng(lat: 1.2706, lng: 103.8097),
        "Thomson": LatLng(lat: 1.329, lng: 103.84),
        "Tiong Bahru": LatLng(lat: 1.286, lng: 103.827),
        "Toa Payoh": LatLng(lat: 1.3343, lng: 103.8563),
        "Tuas": LatLng(lat: 1.32, lng: 103.65),
        "Ubi": LatLng(lat: 1.33, lng: 103.899),
        "Upper Bukit Timah": LatLng(lat: 1.355, lng: 103.77),
        "Upper Thomson": LatLng(lat: 1.354, lng: 103.833),
        "West Coast": LatLng(lat: 1.304, lng: 103.765),
        "Woodlands": LatLng(lat: 1.436, lng: 103.786),
        "Yio Chu Kang": LatLng(lat: 1.3818, lng: 103.8448),
        "Yishun": LatLng(lat: 1.429, lng: 103.835)
    ]

    /// Area names, longest first, so "Serangoon North" wins over "Serangoon".
    static let names: [String] = areas.keys.sorted { $0.count > $1.count }

    /// Approx. road km between two points.
    static func km(_ a: LatLng, _ b: LatLng) -> Double {
        let r = Double.pi / 180
        let dLat = (b.lat - a.lat) * r, dLng = (b.lng - a.lng) * r
        let h = pow(sin(dLat / 2), 2) + cos(a.lat * r) * cos(b.lat * r) * pow(sin(dLng / 2), 2)
        return 2 * 6371.0 * asin(sqrt(h)) * road
    }

    static func inSingapore(_ p: LatLng) -> Bool {
        (1.15...1.48).contains(p.lat) && (103.6...104.1).contains(p.lng)
    }

    static func norm(_ s: String) -> String {
        let lowered = s.lowercased()
        let cleaned = lowered.unicodeScalars.map { CharacterSet.alphanumerics.contains($0) ? Character($0) : " " }
        return String(cleaned).split(separator: " ").joined(separator: " ")
    }

    /// Best built-in area for text like "Science Park Drive" or "Bugis Junction".
    static func matchArea(_ text: String) -> String? {
        let t = norm(text)
        if t.count < 3 { return nil }
        if let n = names.first(where: { norm($0) == t }) { return n }
        if let n = names.first(where: { t.hasPrefix(norm($0)) }) { return n }
        if t.count >= 4, let n = names.first(where: { norm($0).hasPrefix(t) }) { return n }
        if let n = names.first(where: { t.contains(norm($0)) }) { return n }
        return nil
    }
}
