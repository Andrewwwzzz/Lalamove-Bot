package com.zx.stacker

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(val lat: Double, val lng: Double)

/** Built-in Singapore areas, distance maths and fuzzy name matching. Pure Kotlin (no Android). */
object Geo {
    /** Straight-line distance x this factor ~= road distance in Singapore. */
    const val ROAD = 1.35

    val AREAS: Map<String, LatLng> = linkedMapOf(
        "Admiralty" to LatLng(1.4406, 103.8009),
        "Alexandra" to LatLng(1.287, 103.802),
        "Aljunied" to LatLng(1.3164, 103.8829),
        "Ang Mo Kio" to LatLng(1.3691, 103.8454),
        "Ayer Rajah" to LatLng(1.293, 103.786),
        "Balestier" to LatLng(1.3255, 103.85),
        "Bartley" to LatLng(1.3425, 103.8796),
        "Bedok" to LatLng(1.324, 103.93),
        "Bendemeer" to LatLng(1.313, 103.863),
        "Bishan" to LatLng(1.3508, 103.8485),
        "Boon Lay" to LatLng(1.3387, 103.706),
        "Bras Basah" to LatLng(1.2966, 103.8506),
        "Bugis" to LatLng(1.3005, 103.8558),
        "Bukit Batok" to LatLng(1.359, 103.7637),
        "Bukit Merah" to LatLng(1.2819, 103.8239),
        "Bukit Permei" to LatLng(1.2735, 103.827),
        "Bukit Panjang" to LatLng(1.379, 103.7616),
        "Bukit Timah" to LatLng(1.3294, 103.8021),
        "Buona Vista" to LatLng(1.3072, 103.79),
        "Changi Airport" to LatLng(1.3644, 103.9915),
        "Changi Business Park" to LatLng(1.334, 103.964),
        "Changi Village" to LatLng(1.389, 103.988),
        "Chinatown" to LatLng(1.2836, 103.8443),
        "Choa Chu Kang" to LatLng(1.3854, 103.7443),
        "Clarke Quay" to LatLng(1.2884, 103.8465),
        "Clementi" to LatLng(1.3151, 103.7652),
        "Commonwealth" to LatLng(1.3025, 103.798),
        "Cuppage" to LatLng(1.301, 103.8405),
        "Defu" to LatLng(1.353, 103.892),
        "Dhoby Ghaut" to LatLng(1.299, 103.8455),
        "Dover" to LatLng(1.3114, 103.7786),
        "Eunos" to LatLng(1.3196, 103.903),
        "Expo" to LatLng(1.335, 103.9615),
        "Farrer Park" to LatLng(1.3124, 103.8543),
        "Farrer Road" to LatLng(1.3172, 103.8077),
        "Fernvale" to LatLng(1.392, 103.876),
        "Geylang" to LatLng(1.318, 103.887),
        "HarbourFront" to LatLng(1.2653, 103.822),
        "Hillview" to LatLng(1.3626, 103.7674),
        "Holland Village" to LatLng(1.3112, 103.7958),
        "Hougang" to LatLng(1.3612, 103.8863),
        "Joo Koon" to LatLng(1.3277, 103.6782),
        "Jurong East" to LatLng(1.3331, 103.7423),
        "Jurong West" to LatLng(1.3404, 103.709),
        "Kaki Bukit" to LatLng(1.3355, 103.905),
        "Kallang" to LatLng(1.31, 103.865),
        "Kallang Bahru" to LatLng(1.3165, 103.8705),
        "Katong" to LatLng(1.305, 103.905),
        "Kembangan" to LatLng(1.321, 103.913),
        "Kent Ridge" to LatLng(1.2936, 103.7845),
        "Kovan" to LatLng(1.36, 103.885),
        "Kranji" to LatLng(1.425, 103.762),
        "Lavender" to LatLng(1.3074, 103.863),
        "Lentor" to LatLng(1.385, 103.836),
        "Little India" to LatLng(1.3066, 103.8492),
        "Lorong Chuan" to LatLng(1.3517, 103.864),
        "Loyang" to LatLng(1.37, 103.97),
        "MacPherson" to LatLng(1.326, 103.89),
        "Mandai" to LatLng(1.41, 103.79),
        "Marina Bay" to LatLng(1.282, 103.8585),
        "Marine Parade" to LatLng(1.303, 103.907),
        "Mount Sinai" to LatLng(1.32, 103.802),
        "Mountbatten" to LatLng(1.306, 103.883),
        "Newton" to LatLng(1.3138, 103.838),
        "Novena" to LatLng(1.3204, 103.8438),
        "One-North" to LatLng(1.2995, 103.7873),
        "Orchard" to LatLng(1.3048, 103.8318),
        "Outram" to LatLng(1.2803, 103.8395),
        "Pasir Panjang" to LatLng(1.276, 103.791),
        "Pasir Ris" to LatLng(1.3721, 103.9474),
        "Paya Lebar" to LatLng(1.318, 103.893),
        "Pioneer" to LatLng(1.3376, 103.6973),
        "Potong Pasir" to LatLng(1.3313, 103.8688),
        "Punggol" to LatLng(1.4043, 103.902),
        "Queenstown" to LatLng(1.2942, 103.806),
        "Raffles Place" to LatLng(1.284, 103.8514),
        "Redhill" to LatLng(1.2896, 103.8168),
        "Robin Estate" to LatLng(1.32, 103.826),
        "Science Park" to LatLng(1.2905, 103.788),
        "Seah Street" to LatLng(1.2965, 103.8545),
        "Seletar" to LatLng(1.404, 103.869),
        "Selegie" to LatLng(1.301, 103.85),
        "Sembawang" to LatLng(1.4491, 103.8185),
        "Sengkang" to LatLng(1.3917, 103.895),
        "Serangoon North" to LatLng(1.3705, 103.8735),
        "Sentosa" to LatLng(1.2494, 103.8303),
        "Serangoon" to LatLng(1.35, 103.873),
        "East Coast Road" to LatLng(1.3065, 103.905),
        "Siglap" to LatLng(1.312, 103.927),
        "Simei" to LatLng(1.3432, 103.9533),
        "Sixth Avenue" to LatLng(1.331, 103.797),
        "Springleaf" to LatLng(1.398, 103.818),
        "Springside" to LatLng(1.3995, 103.8165),
        "Stevens" to LatLng(1.32, 103.826),
        "Sungei Kadut" to LatLng(1.414, 103.753),
        "Suntec City" to LatLng(1.2945, 103.859),
        "Tai Seng" to LatLng(1.3358, 103.8879),
        "Tampines" to LatLng(1.354, 103.944),
        "Tanah Merah" to LatLng(1.327, 103.946),
        "Tanjong Pagar" to LatLng(1.2765, 103.8455),
        "Teban Gardens" to LatLng(1.322, 103.744),
        "Telok Blangah" to LatLng(1.2706, 103.8097),
        "Thomson" to LatLng(1.329, 103.84),
        "Tiong Bahru" to LatLng(1.286, 103.827),
        "Toa Payoh" to LatLng(1.3343, 103.8563),
        "Tuas" to LatLng(1.32, 103.65),
        "Ubi" to LatLng(1.33, 103.899),
        "Upper Bukit Timah" to LatLng(1.355, 103.77),
        "Upper Thomson" to LatLng(1.354, 103.833),
        "West Coast" to LatLng(1.304, 103.765),
        "Woodlands" to LatLng(1.436, 103.786),
        "Yio Chu Kang" to LatLng(1.3818, 103.8448),
        "Yishun" to LatLng(1.429, 103.835)
    )

    /** Approx. road km between two points. */
    fun km(a: LatLng, b: LatLng): Double {
        val r = Math.PI / 180
        val dLat = (b.lat - a.lat) * r
        val dLng = (b.lng - a.lng) * r
        val h = sin(dLat / 2).let { it * it } +
            cos(a.lat * r) * cos(b.lat * r) * sin(dLng / 2).let { it * it }
        return 2 * 6371.0 * asin(sqrt(h)) * ROAD
    }

    fun inSingapore(p: LatLng): Boolean = p.lat in 1.15..1.48 && p.lng in 103.6..104.1

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Best built-in area for a text like "Science Park Drive" or "Bugis Junction". */
    fun matchArea(text: String): String? {
        val t = norm(text)
        if (t.length < 3) return null
        val names = AREAS.keys
        names.firstOrNull { norm(it) == t }?.let { return it }
        names.firstOrNull { t.startsWith(norm(it)) }?.let { return it }
        names.firstOrNull { norm(it).startsWith(t) && t.length >= 4 }?.let { return it }
        names.filter { t.contains(norm(it)) }.maxByOrNull { it.length }?.let { return it }
        return null
    }
}
