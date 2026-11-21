package com.zx.stacker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Order(
    val pickup: String,
    val drop: String,
    val p: LatLng,
    val d: LatLng,
    val fare: Double,
    val priority: Double,
    var picked: Boolean
)

/** Where you need to be, and the time (epoch ms) you need to be there by. */
data class Destination(val name: String, val loc: LatLng, val byMillis: Long)

/** Settings, current orders, last GPS fix and looked-up places, kept in SharedPreferences. */
class Store(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences("stacker", Context.MODE_PRIVATE)

    var target: Double
        get() = prefs.getFloat("target", 14f).toDouble()
        set(v) { prefs.edit().putFloat("target", v.toFloat()).apply() }

    fun here(): LatLng? {
        if (!prefs.contains("hereLat")) return null
        return LatLng(prefs.getFloat("hereLat", 0f).toDouble(), prefs.getFloat("hereLng", 0f).toDouble())
    }

    fun saveHere(p: LatLng) {
        prefs.edit().putFloat("hereLat", p.lat.toFloat()).putFloat("hereLng", p.lng.toFloat()).apply()
    }

    fun orders(): MutableList<Order> {
        val out = ArrayList<Order>()
        val arr = try { JSONArray(prefs.getString("orders", "[]")) } catch (e: Exception) { JSONArray() }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Order(
                    o.optString("pickup"), o.optString("drop"),
                    LatLng(o.optDouble("pLat"), o.optDouble("pLng")),
                    LatLng(o.optDouble("dLat"), o.optDouble("dLng")),
                    o.optDouble("fare", 0.0), o.optDouble("priority", 0.0), o.optBoolean("picked", false)
                )
            )
        }
        return out
    }

    fun saveOrders(list: List<Order>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("pickup", it.pickup).put("drop", it.drop)
                    .put("pLat", it.p.lat).put("pLng", it.p.lng)
                    .put("dLat", it.d.lat).put("dLng", it.d.lng)
                    .put("fare", it.fare).put("priority", it.priority).put("picked", it.picked)
            )
        }
        prefs.edit().putString("orders", arr.toString()).apply()
    }

    fun destination(): Destination? {
        val name = prefs.getString("destName", null) ?: return null
        val by = prefs.getLong("destBy", 0L)
        if (by < System.currentTimeMillis() - 30 * 60_000L) return null   // expired 30+ min ago
        return Destination(name, LatLng(prefs.getFloat("destLat", 0f).toDouble(), prefs.getFloat("destLng", 0f).toDouble()), by)
    }

    fun saveDestination(d: Destination?) {
        val e = prefs.edit()
        if (d == null) e.remove("destName").remove("destLat").remove("destLng").remove("destBy")
        else e.putString("destName", d.name).putFloat("destLat", d.loc.lat.toFloat()).putFloat("destLng", d.loc.lng.toFloat()).putLong("destBy", d.byMillis)
        e.apply()
    }

    fun cachedPlace(name: String): LatLng? {
        val o = try { JSONObject(prefs.getString("geo", "{}")) } catch (e: Exception) { return null }
        val a = o.optJSONArray(name.lowercase()) ?: return null
        return LatLng(a.optDouble(0), a.optDouble(1))
    }

    @Synchronized
    fun putPlace(name: String, p: LatLng) {
        val o = try { JSONObject(prefs.getString("geo", "{}")) } catch (e: Exception) { JSONObject() }
        o.put(name.lowercase(), JSONArray().put(p.lat).put(p.lng))
        prefs.edit().putString("geo", o.toString()).apply()
    }
}
