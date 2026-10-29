package com.tyler.primelauncher

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Weather tiles: Open-Meteo + BigDataCloud reverse geocode (no keys). 0.1.23: "Where I am" never falls back to a saved spot; it reports Location off / not gathered. Call [fetch] off the main thread. */
/** 0.1.23: one hourly tile. [at] = epoch ms of the hour's start; [hour] = local clock hour 0..23 at the forecast spot. */
data class WxHour(val at: Long, val hour: Int, val temp: Int, val code: Int, val day: Boolean) {
    val label get() = "${if (hour % 12 == 0) 12 else hour % 12} ${if (hour < 12) "AM" else "PM"}"
}

data class Wx(val temp: Int, val code: Int, val day: Boolean, val hi: Int, val lo: Int, val place: String, val fetched: Long, val lat: Double, val lon: Double, val gps: Boolean, val hours: List<WxHour> = emptyList()) {
    fun toJson(): String = JSONObject().put("temp", temp).put("code", code).put("day", day).put("hi", hi).put("lo", lo).put("place", place).put("fetched", fetched).put("lat", lat).put("lon", lon).put("gps", gps)
        .put("hours", JSONArray().apply { hours.forEach { put(JSONArray().put(it.at).put(it.hour).put(it.temp).put(it.code).put(if (it.day) 1 else 0)) } }).toString()
    /** The next [n] hours from [now] (current hour first); drops hours that have fully passed since the fetch. */
    fun upcoming(now: Long, n: Int = 7): List<WxHour> = hours.filter { it.at + 3_600_000L > now }.take(n)
    companion object {
        fun from(s: String): Wx? = runCatching { val o = JSONObject(s)
            val hs = o.optJSONArray("hours")?.let { a -> (0 until a.length()).mapNotNull { i -> runCatching { val r = a.getJSONArray(i); WxHour(r.getLong(0), r.getInt(1), r.getInt(2), r.getInt(3), r.getInt(4) == 1) }.getOrNull() } } ?: emptyList()
            Wx(o.getInt("temp"), o.getInt("code"), o.getBoolean("day"), o.getInt("hi"), o.getInt("lo"), o.getString("place"), o.getLong("fetched"), o.getDouble("lat"), o.getDouble("lon"), o.getBoolean("gps"), hs) }.getOrNull()
        /** Parse an Open-Meteo forecast response (unit-tested). */
        fun parseForecast(j: JSONObject, place: String, lat: Double, lon: Double, gps: Boolean, now: Long): Wx {
            val c = j.getJSONObject("current"); val d = j.getJSONObject("daily")
            return Wx(c.getDouble("temperature_2m").roundToInt(), c.getInt("weather_code"), c.optInt("is_day", 1) == 1, d.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(), d.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(), place, now, lat, lon, gps, parseHours(j, now))
        }
        /** Open-Meteo "hourly" block (local times + utc_offset_seconds) -> the next 7 hours, current hour first. */
        fun parseHours(j: JSONObject, now: Long, n: Int = 7): List<WxHour> {
            val h = j.optJSONObject("hourly") ?: return emptyList(); val ts = h.optJSONArray("time") ?: return emptyList()
            val tp = h.getJSONArray("temperature_2m"); val wc = h.getJSONArray("weather_code"); val dy = h.optJSONArray("is_day")
            val off = j.optLong("utc_offset_seconds", 0L) * 1000L
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            val out = ArrayList<WxHour>()
            for (i in 0 until ts.length()) { val s = ts.getString(i); val at = (fmt.parse(s)?.time ?: continue) - off
                if (at + 3_600_000L <= now || tp.isNull(i) || wc.isNull(i)) continue
                out.add(WxHour(at, s.substring(11, 13).toInt(), tp.getDouble(i).roundToInt(), wc.getInt(i), (dy?.optInt(i, 1) ?: 1) == 1)); if (out.size >= n) break }
            return out
        }
        fun parsePlace(g: JSONObject): String = listOf(g.optString("city").ifBlank { g.optString("locality") },
            g.optString("principalSubdivisionCode").removePrefix("US-").ifBlank { g.optString("principalSubdivision") }).filter { it.isNotBlank() }.joinToString(", ")
    }
}

object Weather {
    // Fallback when location is off. Placeholder: set your own default (geographic centre of the contiguous US).
    const val HOME_LAT = 39.8283; const val HOME_LON = -98.5795; const val HOME_NAME = "Default location"
    const val REFRESH_MS = 30 * 60 * 1000L
    /** 0.1.23: opening Home refetches location + weather when the last fetch is older than this. */
    const val RESUME_MS = 3 * 60 * 1000L
    private const val FIX_MAX_AGE_MS = 5 * 60 * 1000L
    const val LOC_OK = 0; const val LOC_OFF = 1; const val LOC_NONE = 2
    /** Outcome of the last location step (only meaningful with "Where I am"). */
    @Volatile var locState = LOC_OK
    class NoLocation(val state: Int) : Exception("location " + if (state == LOC_OFF) "off" else "not gathered")
    private val io = Executors.newSingleThreadExecutor()
    private val cb = Executors.newSingleThreadExecutor() // location callback must not run on the blocked io thread

    fun hasLocation(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun locate(ctx: Context, useGps: Boolean): Location? {
        if (!useGps || !hasLocation(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java)
        val providers = buildList { if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER); add(LocationManager.NETWORK_PROVIDER); add(LocationManager.PASSIVE_PROVIDER) }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < FIX_MAX_AGE_MS) return last
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
    private fun locationEnabled(ctx: Context) = runCatching { ctx.getSystemService(LocationManager::class.java).isLocationEnabled }.getOrDefault(true)
    /** Live-location state without fetching: permission or the system location switch off -> LOC_OFF. */
    fun permState(ctx: Context): Int = if (!hasLocation(ctx) || !locationEnabled(ctx)) LOC_OFF else LOC_OK
    fun fetch(ctx: Context, prefs: Prefs): Wx {
        val live = prefs.wxGps
        if (live && permState(ctx) == LOC_OFF) { locState = LOC_OFF; throw NoLocation(LOC_OFF) }
        val loc = if (live) locate(ctx, true) else null
        if (live && loc == null) { locState = LOC_NONE; throw NoLocation(LOC_NONE) } // never fall back to the saved spot
        locState = LOC_OK; val gps = loc != null
        // Only the explicit "Default location" setting uses the fixed point.
        val lat = loc?.latitude ?: HOME_LAT; val lon = loc?.longitude ?: HOME_LON; val prev = Wx.from(prefs.wxCache)
        val j = getJson("https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code,is_day&hourly=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&temperature_unit=fahrenheit&timezone=auto&forecast_days=2".format(java.util.Locale.US, lat, lon))
        val place = if (!gps) HOME_NAME else if (prev != null && prev.gps && hypot(prev.lat - lat, prev.lon - lon) < 0.05 && prev.place.isNotBlank()) prev.place
            else runCatching { Wx.parsePlace(getJson("https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=%.3f&longitude=%.3f&localityLanguage=en".format(java.util.Locale.US, lat, lon))) }.getOrDefault("").ifBlank { "your location" }
        val w = Wx.parseForecast(j, place, lat, lon, gps, System.currentTimeMillis()); prefs.wxCache = w.toJson(); return w
    }
    fun fetchAsync(ctx: Context, prefs: Prefs, done: (Wx?) -> Unit) { io.execute { val w = runCatching { fetch(ctx, prefs) }.getOrNull(); android.os.Handler(android.os.Looper.getMainLooper()).post { done(w) } } }
}
