package com.tyler.primelauncher

import android.content.Context
import android.content.SharedPreferences

/** All launcher settings (SharedPreferences "prime"). */
class Prefs(ctx: Context) {
    val sp: SharedPreferences = ctx.applicationContext.getSharedPreferences("prime", Context.MODE_PRIVATE)
    private fun s(k: String, d: String) = sp.getString(k, d) ?: d
    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    var theme: String get() = s("theme", "matrix"); set(v) = sp.edit().putString("theme", v).apply()
    val neon get() = theme == "neon"
    /** 0.1.17 ODST skin. */
    val odst get() = theme == "odst"
    /** 0.1.27 holiday skin (manual only): five shaded holiday avatars that take turns. */
    val holiday get() = theme == "holiday"
    /** ODST VISR night mode: "auto" (after dark), "on", "off". */
    var visr: String get() = s("visr", "auto"); set(v) = sp.edit().putString("visr", v).apply()
    /** ODST 4-note civic chime on PA panels (still silent on vibrate / silent / in calls). */
    var chime: Boolean get() = b("chime", true); set(v) = sp.edit().putBoolean("chime", v).apply()
    var icons: IconStyle get() = runCatching { IconStyle.valueOf(s("icons", "ABSTRACT")) }.getOrDefault(IconStyle.ABSTRACT); set(v) = sp.edit().putString("icons", v.name).apply()
    var glitch: Boolean get() = b("glitch", true); set(v) = sp.edit().putBoolean("glitch", v).apply()
    var reduce: Boolean get() = b("reduce", false); set(v) = sp.edit().putBoolean("reduce", v).apply()
    var saver: Boolean get() = b("saver", false); set(v) = sp.edit().putBoolean("saver", v).apply()
    var wake: Boolean get() = b("wake", true); set(v) = sp.edit().putBoolean("wake", v).apply()
    var bubbles: Boolean get() = b("bubbles", true); set(v) = sp.edit().putBoolean("bubbles", v).apply()
    var wxGps: Boolean get() = b("wxGps", true); set(v) = sp.edit().putBoolean("wxGps", v).apply()
    var wpStyle: String get() = s("wpStyle", "auto"); set(v) = sp.edit().putString("wpStyle", v).apply()
    var wpGlyphs: String get() = s("wpGlyphs", "binary"); set(v) = sp.edit().putString("wpGlyphs", v).apply()
    var wpDensity: Float get() = sp.getFloat("wpDensity", 1f); set(v) = sp.edit().putFloat("wpDensity", v).apply()
    var wpSpeed: Float get() = sp.getFloat("wpSpeed", 1f); set(v) = sp.edit().putFloat("wpSpeed", v).apply()
    var wpHue: Int get() = sp.getInt("wpHue", -1); set(v) = sp.edit().putInt("wpHue", v).apply()
    /** Experimental lock-screen sleeping orb (LockOrbActivity). OFF by default: see README 0.1.4. */
    var lockOrb: Boolean get() = b("lockOrb", false); set(v) = sp.edit().putBoolean("lockOrb", v).apply()
    var quickOn: Boolean get() = b("quickOn", true); set(v) = sp.edit().putBoolean("quickOn", v).apply()
    fun quick(dir: String): String = s("quick.$dir", Quick.DEFAULTS[dir] ?: Quick.NONE)
    fun setQuick(dir: String, v: String) = sp.edit().putString("quick.$dir", v).apply()
    var pins: List<String> get() = s("pins", "").split(',').filter { it.isNotBlank() }; set(v) = sp.edit().putString("pins", v.joinToString(",")).apply()
    var onboarded: Boolean get() = b("onboarded", false); set(v) = sp.edit().putBoolean("onboarded", v).apply()
    var homeVisits: Int get() = sp.getInt("homeVisits", 0); set(v) = sp.edit().putInt("homeVisits", v).apply()
    var wxCache: String get() = s("wxCache", ""); set(v) = sp.edit().putString("wxCache", v).apply()
    // 0.1.15 companion (all local to this phone)
    /** Orb activities, reactions and body language. */
    var companion: Boolean get() = b("companion", true); set(v) = sp.edit().putBoolean("companion", v).apply()
    /** The orb's own chat bubbles (separate from notification bubbles). */
    var chat: Boolean get() = b("chat", true); set(v) = sp.edit().putBoolean("chat", v).apply()
    /** What Prime calls you in bubbles (blank = no name). */
    var buddyName: String get() = s("buddyName", ""); set(v) = sp.edit().putString("buddyName", v.trim().take(24)).apply()
    /** Optional study progress for study nudges (blank = generic lines only). */
}

object Quick {
    const val NONE = "none"; const val CAMERA = "camera"; const val TORCH = "torch"; const val PRIME = "prime"; const val DRAWER = "drawer"; const val SEARCH = "search"
    const val EMIT = "app:com.tyler.matrixsms"; const val GROK_BOT = "app:ai.x.grok.bot"
    val DEFAULTS = mapOf("up" to CAMERA, "left" to EMIT, "right" to GROK_BOT, "down" to TORCH)
    val DIRS = listOf("up", "left", "right", "down")
}
