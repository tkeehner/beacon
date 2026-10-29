package com.tyler.primelauncher.emitkb.ui

/** Holiday keyboard palette, copied from Emit 0.1.50 (keyboard only: no avatars here, drizzle stands in for the side icons). */
val HolidayTheme = EmitTheme(
    id = "holiday",
    name = "Holiday",
    blurb = "candle-lit night, drifting bats and cobwebs, a character for every contact",
    palette = ThemePalette(
        bg = 0xFF0A0608, s1 = 0xFF160D0A, s2 = 0xFF21140E, line = 0xFF3A2416, line2 = 0xFF553420,
        primary = 0xFFFF8C1E, body = 0xFFFFE2C8, dim = 0xFFC99A72, faint = 0xFFB0866A, onPrimary = 0xFF140A04,
        accent2 = 0xFFB57EF0, accent3 = 0xFFFFD35A,
        outBg = 0xFF2A1406, outLine = 0xFF7A3E12, outLabel = 0xFFFFB066,
        inBg = 0xFF1A1024, inLine = 0xFF4A3070, sent = 0xFFFFF0E0, incoming = 0xFFF0E6FF,
        danger = 0xFFFF6B6B, warnBg = 0xFF1E1006, warnLine = 0xFF5A3010,
        primeBg = 0xFF160D0A, unread = 0xFFFFE2C8, selBg = 0xFF2E1808, toggleOn = 0xFF2E1808, toggleOff = 0xFF21140E,
        rowLine = 0xFF26170F, scrim = 0xCC0A0608, scrimStrong = 0xDD0A0608, mediaScrim = 0xB30A0608,
        mark = 0x33FF8C1E, searchBg = 0xFFFFD35A, searchFg = 0xFF140A04,
        glitchA = 0x55FF8C1E, glitchB = 0x55B57EF0, glitchScan = 0x30000000, crtScan = 0x30000000, keyDown = 0xFF140A04,
    ),
    corners = CornerStyle.ROUNDED,
    rain = RainStyle.DRIZZLE,
    avatar = AvatarStyle.GLYPH,
    bodyFont = FontId.MONO,
    displayFont = FontId.MONO,
    keyFont = FontId.MONO,
    keyStyle = KeyStyle.NEON,
    fixedBubbles = true,
    titleGlow = true,
    scanlinesAlways = false,
    ambientGlitch = false,
    swatches = listOf(0xFF0A0608, 0xFF21140E, 0xFFFF8C1E, 0xFFB57EF0, 0xFFFFD35A),
    chrome = ThemeChrome(
        inbox = "INBOX", settings = "settings", about = "about", prime = "prime", link = "link",
        dayPrefix = "", uppercaseNames = false, sectionSlash = false, rainLabel = "drifting spooks", fab = "+ new chat",
    ),
)

