package com.zx.stacker

import kotlin.math.max

/** A job already on board. picked = parcel already collected (no pickup stop needed). */
data class Job(val p: LatLng, val d: LatLng, val picked: Boolean)

enum class Verdict { TAKE, OK, SKIP, LATE }

/** One stop in a planned route. job = -1 means your final destination. */
data class PlanStop(val job: Int, val pickup: Boolean, val legKm: Double, val etaMin: Double)

/** A planned route: total km, minutes to finish (incl. 5 min per pickup/drop), and stop order. */
data class Plan(val km: Double, val minutes: Double, val stops: List<PlanStop>)

data class Score(
    val takeHome: Double,      // cash you keep from this order
    val netGain: Double,       // take-home minus petrol for the extra km = what this order adds to your pocket
    val perHour: Double,       // S$/hr for the extra time this order costs, after petrol + other costs
    val minFare: Double,       // shown fare needed to hit your target
    val pickupKm: Double,      // distance from you / your route to the pickup
    val maxPickupKm: Double,   // furthest pickup that still hits target (as a standalone job)
    val extraKm: Double,
    val extraMin: Double,
    val finishMin: Double,     // minutes from now until you reach your destination (or finish all jobs) with this order
    val spareMin: Double?,     // minutes to spare before your deadline with this order (null = no deadline)
    val verdict: Verdict
)

/** Same maths as the web tracker. Pure Kotlin. */
object Scorer {
    const val KEEP = 0.785        // share of shown fare you keep (15% commission + 9% VAT on it + ~5% CPF)
    const val KEEP_PRI = 0.95     // share of priority fee you keep (CPF only)
    const val FUEL = 0.25         // S$/km  (8 km/L at S$2/L)
    const val OTHER = 1.0         // S$/hr other costs
    const val SPEED = 25.0        // km/h average incl. traffic
    const val STOP_MIN = 5.0      // minutes per pickup or drop

    fun minutes(km: Double, stops: Int) = km / SPEED * 60 + stops * STOP_MIN

    private class Stop(val loc: LatLng, val job: Int, val pickup: Boolean)

    /**
     * Shortest route from start through every pickup/drop (pickups before their drops),
     * finishing at [end] if given. Exact search up to 10 stops, greedy beyond.
     */
    fun plan(start: LatLng, jobs: List<Job>, end: LatLng? = null): Plan {
        val stops = ArrayList<Stop>()
        jobs.forEachIndexed { i, j ->
            if (!j.picked) stops.add(Stop(j.p, i, true))
            stops.add(Stop(j.d, i, false))
        }
        val order: List<Int> = if (stops.size > 10) greedy(start, stops, jobs) else exact(start, stops, jobs, end)

        val out = ArrayList<PlanStop>()
        var cur = start; var km = 0.0; var min = 0.0
        for (i in order) {
            val s = stops[i]
            val leg = Geo.km(cur, s.loc)
            km += leg; min += leg / SPEED * 60
            out.add(PlanStop(s.job, s.pickup, leg, min))
            min += STOP_MIN
            cur = s.loc
        }
        if (end != null) {
            val leg = Geo.km(cur, end)
            km += leg; min += leg / SPEED * 60
            out.add(PlanStop(-1, false, leg, min))
        }
        return Plan(km, min, out)
    }

    private fun allowed(i: Int, stops: List<Stop>, jobs: List<Job>, used: BooleanArray): Boolean {
        val s = stops[i]
        if (s.pickup || jobs[s.job].picked) return true
        val pi = stops.indexOfFirst { it.job == s.job && it.pickup }
        return pi < 0 || used[pi]
    }

    private fun exact(start: LatLng, stops: List<Stop>, jobs: List<Job>, end: LatLng?): List<Int> {
        if (stops.isEmpty()) return emptyList()
        val used = BooleanArray(stops.size)
        val path = IntArray(stops.size)
        var bestCost = Double.MAX_VALUE
        var best = IntArray(0)
        fun go(cur: LatLng, cost: Double, n: Int) {
            if (cost >= bestCost) return
            if (n == stops.size) {
                val total = cost + (end?.let { Geo.km(cur, it) } ?: 0.0)
                if (total < bestCost) { bestCost = total; best = path.copyOf() }
                return
            }
            for (i in stops.indices) {
                if (used[i] || !allowed(i, stops, jobs, used)) continue
                used[i] = true; path[n] = i
                go(stops[i].loc, cost + Geo.km(cur, stops[i].loc), n + 1)
                used[i] = false
            }
        }
        go(start, 0.0, 0)
        return best.toList()
    }

    private fun greedy(start: LatLng, stops: List<Stop>, jobs: List<Job>): List<Int> {
        val used = BooleanArray(stops.size); var cur = start; val out = ArrayList<Int>()
        repeat(stops.size) {
            var bi = -1; var bd = Double.MAX_VALUE
            for (i in stops.indices) {
                if (used[i] || !allowed(i, stops, jobs, used)) continue
                val d = Geo.km(cur, stops[i].loc); if (d < bd) { bd = d; bi = i }
            }
            used[bi] = true; out.add(bi); cur = stops[bi].loc
        }
        return out
    }

    /** Cash you'd keep from these orders minus petrol for the whole route. */
    fun netEarnings(orders: List<Pair<Double, Double>>, routeKm: Double): Double =
        orders.sumOf { (fare, pri) -> fare * KEEP + pri * KEEP_PRI } - FUEL * routeKm

    /** Rule: shown fare >= b + a * (km to pickup + job km). */
    fun coefficients(target: Double): Pair<Double, Double> {
        val t = target + OTHER
        val a = (t / SPEED + FUEL) / KEEP
        val b = (t * 2 * STOP_MIN / 60) / KEEP
        return a to b
    }

    /**
     * Score an offer as an addition to your current route.
     * [end] = where you must finish; [minutesLeft] = minutes until you must be there.
     * With a destination, km you'd drive anyway to get there is free, so offers heading your way score higher.
     */
    fun score(
        start: LatLng, current: List<Job>, p: LatLng, d: LatLng, fare: Double, priority: Double, target: Double,
        end: LatLng? = null, minutesLeft: Double? = null
    ): Score {
        val base = plan(start, current, end)
        val with = plan(start, current + Job(p, d, false), end)
        val xk = max(with.km - base.km, 0.3)
        val xm = max(with.minutes - base.minutes, 2 * STOP_MIN)
        val take = fare * KEEP + priority * KEEP_PRI
        val perHour = (take - FUEL * xk) / xm * 60 - OTHER
        val need = ((target + OTHER) / 60 * xm + FUEL * xk - priority * KEEP_PRI) / KEEP

        val routePts = ArrayList<LatLng>().apply {
            add(start); current.forEach { if (!it.picked) add(it.p); add(it.d) }
        }
        val pickupKm = routePts.minOf { Geo.km(it, p) }
        val (a, b) = coefficients(target)
        val maxPick = (fare + priority * KEEP_PRI / KEEP - b) / a - Geo.km(p, d)
        val spare = minutesLeft?.let { it - with.minutes }

        val verdict = when {
            spare != null && spare < 0 -> Verdict.LATE
            perHour >= target * 1.25 -> Verdict.TAKE
            perHour >= target -> Verdict.OK
            else -> Verdict.SKIP
        }
        return Score(take, take - FUEL * xk, perHour, max(need, 0.0), pickupKm, max(maxPick, 0.0), xk, xm, with.minutes, spare, verdict)
    }
}
