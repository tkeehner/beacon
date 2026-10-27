package com.tyler.primelauncher

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Weather bar: Open-Meteo + BigDataCloud reverse geocode (no keys). Falls back to a configurable default location. Call [fetch] off the main thread. */
data class Wx(val temp: Int, val code: Int, val day: Boolean, val hi: Int, val lo: Int, val place: String, val fetched: Long, val lat: Double, val lon: Double, val gps: Boolean) {
    fun toJson(): String = JSONObject().put("temp", temp).put("code", code).put("day", day).put("hi", hi).put("lo", lo).put("place", place).put("fetched", fetched).put("lat", lat).put("lon", lon).put("gps", gps).toString()
    companion object {
        fun from(s: String): Wx? = runCatching { val o = JSONObject(s); Wx(o.getInt("temp"), o.getInt("code"), o.getBoolean("day"), o.getInt("hi"), o.getInt("lo"), o.getString("place"), o.getLong("fetched"), o.getDouble("lat"), o.getDouble("lon"), o.getBoolean("gps")) }.getOrNull()
        /** Parse an Open-Meteo forecast response (unit-tested). */
        fun parseForecast(j: JSONObject, place: String, lat: Double, lon: Double, gps: Boolean, now: Long): Wx {
            val c = j.getJSONObject("current"); val d = j.getJSONObject("daily")
            return Wx(c.getDouble("temperature_2m").roundToInt(), c.getInt("weather_code"), c.optInt("is_day", 1) == 1, d.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(), d.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(), place, now, lat, lon, gps)
        }
        fun parsePlace(g: JSONObject): String = listOf(g.optString("city").ifBlank { g.optString("locality") },
            g.optString("principalSubdivisionCode").removePrefix("US-").ifBlank { g.optString("principalSubdivision") }).filter { it.isNotBlank() }.joinToString(", ")
    }
}

object Weather {
    // Fallback when location is off. Placeholder: set your own default (geographic centre of the contiguous US).
    const val HOME_LAT = 39.8283; const val HOME_LON = -98.5795; const val HOME_NAME = "Default location"
    const val REFRESH_MS = 30 * 60 * 1000L
    private val io = Executors.newSingleThreadExecutor()
    private val cb = Executors.newSingleThreadExecutor() // location callback must not run on the blocked io thread

    fun hasLocation(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun locate(ctx: Context, useGps: Boolean): Location? {
        if (!useGps || !hasLocation(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java)
        val providers = buildList { if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER); add(LocationManager.NETWORK_PROVIDER); add(LocationManager.PASSIVE_PROVIDER) }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < REFRESH_MS) return last
        val p = providers.firstOrNull() ?: return last
        val latch = CountDownLatch(1); var got: Location? = null
        runCatching { lm.getCurrentLocation(p, CancellationSignal(), cb) { got = it; latch.countDown() } }.onFailure { latch.countDown() }
        latch.await(12, TimeUnit.SECONDS); return got ?: last
    }
    private fun getJson(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 9000; c.readTimeout = 9000; c.instanceFollowRedirects = true; c.setRequestProperty("User-Agent", "Beacon/0.1")
        try { if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}"); return JSONObject(c.inputStream.bufferedReader().readText()) } finally { c.disconnect() }
    }
    fun fetch(ctx: Context, prefs: Prefs): Wx {
        val loc = locate(ctx, prefs.wxGps); val gps = loc != null
        val lat = loc?.latitude ?: HOME_LAT; val lon = loc?.longitude ?: HOME_LON; val prev = Wx.from(prefs.wxCache)
        val j = getJson("https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&temperature_unit=fahrenheit&timezone=auto&forecast_days=1".format(java.util.Locale.US, lat, lon))
        val place = if (!gps) HOME_NAME else if (prev != null && prev.gps && hypot(prev.lat - lat, prev.lon - lon) < 0.05 && prev.place.isNotBlank()) prev.place
            else runCatching { Wx.parsePlace(getJson("https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=%.3f&longitude=%.3f&localityLanguage=en".format(java.util.Locale.US, lat, lon))) }.getOrDefault("").ifBlank { "your location" }
        val w = Wx.parseForecast(j, place, lat, lon, gps, System.currentTimeMillis()); prefs.wxCache = w.toJson(); return w
    }
    fun fetchAsync(ctx: Context, prefs: Prefs, done: (Wx?) -> Unit) { io.execute { val w = runCatching { fetch(ctx, prefs) }.getOrNull(); android.os.Handler(android.os.Looper.getMainLooper()).post { done(w) } } }
}
