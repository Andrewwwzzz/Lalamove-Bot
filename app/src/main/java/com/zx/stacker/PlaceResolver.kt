package com.zx.stacker

import android.content.Context
import android.location.Geocoder
import android.os.Handler
import android.os.Looper
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Turns a place name into coordinates: built-in area table first,
 * then Android's Geocoder (needs mobile data), cached so each name is looked up once.
 */
class PlaceResolver(ctx: Context) {
    private val store = Store(ctx)
    private val geocoder = if (Geocoder.isPresent()) Geocoder(ctx.applicationContext, Locale.ENGLISH) else null
    private val mem = ConcurrentHashMap<String, LatLng>()
    private val failed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val pending: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Instant answer if known, else null (call resolveAsync to look it up). */
    fun cached(name: String): LatLng? {
        Geo.matchArea(name)?.let { return Geo.AREAS[it] }
        val key = name.trim().lowercase()
        mem[key]?.let { return it }
        return store.cachedPlace(key)?.also { mem[key] = it }
    }

    fun resolveAsync(name: String, done: ((LatLng?) -> Unit)? = null) {
        cached(name)?.let { done?.invoke(it); return }
        val key = name.trim().lowercase()
        if (key in failed) { done?.invoke(null); return }
        if (!pending.add(key) && done == null) return
        exec.execute {
            val r = lookup(name)
            if (r != null) { mem[key] = r; store.putPlace(key, r) } else failed.add(key)
            pending.remove(key)
            if (done != null) main.post { done(r) }
        }
    }

    private fun lookup(name: String): LatLng? = try {
        @Suppress("DEPRECATION")
        geocoder?.getFromLocationName("$name, Singapore", 1, 1.15, 103.6, 1.48, 104.1)
            ?.firstOrNull()
            ?.let { LatLng(it.latitude, it.longitude) }
            ?.takeIf { Geo.inSingapore(it) }
    } catch (e: Exception) {
        null
    }
}
