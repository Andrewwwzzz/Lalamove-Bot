package com.zx.stacker

/** One line of text found on screen, with its box in screen pixels. */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

data class ParsedOffer(
    val pickup: String,
    val drop: String,
    val fare: Double,
    val priority: Double,
    val jobKm: Double?,        // trip distance (Record screen "11.7 km")
    val pickupKm: Double? = null, // Lalamove's distance from you to the pickup (Pickup screen "(~2.7 km) Pick Up Now")
    val type: String? = null,     // "Regular", "Pooling", ...
    val extraStops: Int = 0,      // "1 stop" / "2 stops" line between pickup and drop
    val top: Int = 0,             // card's top edge on screen (px)
    val bottom: Int = 0           // card's bottom edge on screen (px)
) {
    val key get() = "$pickup|$drop|$fare"
}

/**
 * Turns the text on a Lalamove list into orders. Two layouts:
 *
 * Pickup tab (offers):           Record → Ongoing (your jobs):
 *   (~2.7 km) Pick Up Now          11.7 km      # 3632...
 *   Regular                        Suntec City
 *   Serangoon North                Bedok
 *   East Coast Road                (note)
 *   Car                            S$15.70
 *   S$17.90
 *
 * A card header (distance line) starts a card; the S$ amount closes it;
 * the first two place-like lines in between are pickup and drop.
 * Pure Kotlin (no Android) so it can be unit-tested.
 */
object OfferParser {
    private val FARE = Regex("""(?:S\s?)?\$\s?(\d{1,3}(?:[.,]\d{1,2})?)""", RegexOption.IGNORE_CASE)
    /** "(~2.7 km) Pick Up Now" — distance to pickup, starts an offer card. */
    private val PICKUP_HEADER = Regex("""^[^\d]{0,4}(\d{1,3}(?:[.,]\d{1,2})?)\s?km\s?\)?.*p[il1]ck\s?up""", RegexOption.IGNORE_CASE)
    /** "Pick Up Now" / "In Progress" alone (number lost or split off) still starts a card. */
    private val CARD_START = Regex("""^(p[il1]ck\s?up\s?now|in\s?progress)$""", RegexOption.IGNORE_CASE)
    /** "+ 1.70" bonus after the fare (on the same line or its own line). */
    private val BONUS = Regex("""\+\s?\$?\s?(\d{1,3}[.,]\d{1,2})""")
    /** "11.7 km" — trip distance, starts a Record card. */
    private val KM = Regex("""^[^\d\w]{0,4}(\d{1,3}(?:[.,]\d{1,2})?)\s?km\b""", RegexOption.IGNORE_CASE)
    private val ID = Regex("""#\s?\d{6,}""")
    private val TIME = Regex("""\d{1,2}:\d{2}""")
    private val STOPS = Regex("""^(\d{1,2})\s?stops?\b""", RegexOption.IGNORE_CASE)

    private val TYPES = setOf("regular", "pooling", "priority", "scheduled", "express", "multi-stop", "multi stop")

    /** App chrome and labels that are never place names. */
    private val UI_WORDS = setOf(
        "ongoing", "completed", "canceled", "cancelled", "in progress", "pickup", "pick up", "pick up now", "record",
        "wallet", "mission", "profile", "accept", "take order", "details", "breakdown", "rates",
        "yesterday", "today", "lte", "delivery fee", "surcharge", "deductions", "commission", "vat",
        "earnings", "received by credit", "priority", "priority fee", "available", "orders", "filter",
        "sort", "nearby", "all", "car", "van", "lorry", "motorcycle", "mpv", "scheduled", "immediate", "now",
        "on duty", "off duty", "home", "en route", "above 30 km", "other", "regular", "pooling", "offer reader",
        "courier", "bike", "truck", "1.7m van", "2.4m van", "10ft lorry", "14ft lorry", "heatmap", "new"
    )

    private fun num(s: String) = s.replace(',', '.').toDoubleOrNull()

    private fun isPlace(t: String): Boolean {
        val s = t.trim()
        if (s.length !in 3..40) return false
        if (!s.first().isLetter() || s.first().isLowerCase()) return false
        if (TIME.containsMatchIn(s) || ID.containsMatchIn(s)) return false
        if (s.lowercase() in UI_WORDS) return false
        val letters = s.count { it.isLetter() }
        if (letters < 3 || letters.toDouble() / s.count { !it.isWhitespace() } < 0.7) return false
        if (s.split(Regex("\\s+")).size > 6) return false   // notes are sentences
        return true
    }

    fun parse(lines: List<OcrLine>): List<ParsedOffer> {
        val sorted = lines.sortedWith(compareBy<OcrLine> { it.top / 12 }.thenBy { it.left })
        val out = ArrayList<ParsedOffer>()
        val places = ArrayList<String>()
        var inCard = false
        var jobKm: Double? = null
        var pickupKm: Double? = null
        var type: String? = null
        var priority = 0.0
        var cardTop = 0
        var stops = 0

        fun startCard(top: Int) { places.clear(); jobKm = null; pickupKm = null; type = null; priority = 0.0; stops = 0; inCard = true; cardTop = top }

        for (line in sorted) {
            val t = line.text.trim()
            if (t.isEmpty()) continue
            val low = t.lowercase()

            val ph = PICKUP_HEADER.find(t)
            if (ph != null) { startCard(line.top); pickupKm = num(ph.groupValues[1]); continue }
            if (CARD_START.matches(t)) { if (!inCard || places.isNotEmpty()) startCard(line.top); continue }

            val km = KM.find(t)
            if (km != null) {
                startCard(line.top)
                // "(~2.7 km)" split from "Pick Up Now" is still the distance to pickup
                if (!t.first().isDigit()) pickupKm = num(km.groupValues[1]) else jobKm = num(km.groupValues[1])
                continue
            }

            val st = STOPS.find(t)
            if (inCard && st != null) { stops = st.groupValues[1].toIntOrNull() ?: 1; continue }
            if (low.contains("multi") && low.contains("stop")) stops = maxOf(stops, 1)
            if (low in TYPES) {
                if (!inCard) startCard(line.top)   // header was missed: the type tag still marks a new card
                type = t; continue
            }
            if (inCard && t.startsWith("+")) { BONUS.find(t)?.let { priority += num(it.groupValues[1]) ?: 0.0 }; continue }

            val fm = FARE.find(t)
            if (fm != null) {
                val v = num(fm.groupValues[1]) ?: continue
                if (low.contains("priority")) { priority = v; continue }
                BONUS.find(t.substring(fm.range.last + 1))?.let { priority += num(it.groupValues[1]) ?: 0.0 }
                if (inCard && v in 3.0..300.0 && places.size >= 2) {
                    out.add(ParsedOffer(places[0], places[1], v, priority, jobKm, pickupKm, type, stops, cardTop, line.bottom))
                }
                places.clear(); inCard = false
                continue
            }
            if (ID.containsMatchIn(t)) continue
            if (inCard && isPlace(t)) places.add(t)
        }
        return out.distinctBy { it.key }
    }
}
