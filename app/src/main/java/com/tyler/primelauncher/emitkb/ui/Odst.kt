package com.tyler.primelauncher.emitkb.ui

/**
 * ODST. Amber + steel blue on a rainy night city, VISR yellow-green outlines, chamfered radio-comms panels and
 * armor-plate keys. Original art and sounds only: no game names, assets or audio.
 */
val OdstTheme = EmitTheme(
    id = "odst",
    name = "ODST",
    blurb = "rainy night city, amber comms panels, VISR outlines",
    palette = ThemePalette(
        bg = 0xFF05070B,
        s1 = 0xFF0C1118,
        s2 = 0xFF121922,
        line = 0xFF2A3442,
        line2 = 0xFF3A4757,
        primary = 0xFFFFB23F,
        body = 0xFFE9EEF5,
        dim = 0xFFA3B0BF,
        faint = 0xFF8D9BAD,
        onPrimary = 0xFF0A0D10,
        accent2 = 0xFF8FB8E0,
        accent3 = 0xFFD4FF3A,
        outBg = 0xFF201608,
        outLine = 0xFF6B4A16,
        outLabel = 0xFFFFC266,
        inBg = 0xFF0E1620,
        inLine = 0xFF2F4A66,
        sent = 0xFFFFF1DC,
        incoming = 0xFFE3EEFA,
        danger = 0xFFFF6B6B,
        warnBg = 0xFF1A1206,
        warnLine = 0xFF5A3F14,
        primeBg = 0xFF0C1118,
        unread = 0xFFE9EEF5,
        selBg = 0xFF2A1D08,
        toggleOn = 0xFF2A1D08,
        toggleOff = 0xFF121922,
        rowLine = 0xFF1E2733,
        scrim = 0xCC05070B,
        scrimStrong = 0xDD05070B,
        mediaScrim = 0xB305070B,
        mark = 0x33FFB23F,
        searchBg = 0xFFD4FF3A,
        searchFg = 0xFF0A0D10,
        glitchA = 0x55FFB23F,
        glitchB = 0x55D4FF3A,
        glitchScan = 0x30000000,
        crtScan = 0x30000000,
        keyDown = 0xFF0A0D10,
    ),
    corners = CornerStyle.CUT,
    rain = RainStyle.CITY,
    avatar = AvatarStyle.CALLSIGN,
    bodyFont = FontId.MONO,
    displayFont = FontId.OXANIUM,
    keyFont = FontId.OXANIUM,
    keyStyle = KeyStyle.ARMOR,
    fixedBubbles = true,
    titleGlow = true,
    scanlinesAlways = false,
    ambientGlitch = false,
    swatches = listOf(0xFF05070B, 0xFF121922, 0xFFFFB23F, 0xFF8FB8E0, 0xFFD4FF3A),
    chrome = ThemeChrome(
        inbox = "COMMS",
        settings = "// SETTINGS",
        about = "// ABOUT",
        prime = "// PRIME",
        link = "// LINK",
        dayPrefix = "// ",
        uppercaseNames = true,
        sectionSlash = true,
        rainLabel = "rainy city",
        fab = "+ NEW CHANNEL",
    ),
    comms = true,
)

/** Pure ODST helpers (JVM-testable). */
object Comms {
    /** VISR night mode: after sunset (fixed 7 pm - 6 am, no location needed). */
    fun visrNight(hour: Int) = hour >= 19 || hour < 6
    /** Two-letter callsign for a contact tile: initials of the first two words, else the first two letters/digits. */
    fun callsign(name: String): String {
        val words = name.uppercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return "??"
        if (words.size >= 2) return "${words[0].first()}${words[1].first()}"
        val w = words[0]; return if (w.length >= 2) w.take(2) else w + "-"
    }
    /** Panel header for a radio-comms message: "ALPHA-1 ▸ 21:03" (outgoing: "YOU ▸ 21:03"). */
    fun header(who: String, time: String, outgoing: Boolean) = (if (outgoing) "YOU" else who.uppercase()) + " \u25B8 " + time
}
