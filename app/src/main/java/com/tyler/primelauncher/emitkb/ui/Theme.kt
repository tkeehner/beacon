package com.tyler.primelauncher.emitkb.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** How panels are cut. Matrix keeps the historical rounded corners. */
enum class CornerStyle { ROUNDED, CUT }

/** Background animation behind inbox, chats, and the keyboard. */
enum class RainStyle { KATAKANA, DRIZZLE, CITY }

/** Contact tile. Matrix keeps katakana; a later theme can swap the whole tile. */
enum class AvatarStyle { KATAKANA, GLYPH, CALLSIGN }

/** Which bundled face a role uses. Resolved in composition so the theme stays JVM-testable. */
enum class FontId { MONO, OXANIUM }

/** Pressed-key treatment. MATRIX is today's face; NEON fills the key and pops a glowing glyph. */
enum class KeyStyle { MATRIX, NEON, ARMOR }

/**
 * Every color the UI paints, as opaque (or explicitly translucent) ARGB.
 * Text roles are checked by [textPairs] so a new theme cannot ship an unreadable pair.
 */
data class ThemePalette(
    val bg: Long,
    val s1: Long,
    val s2: Long,
    val line: Long,
    val line2: Long,
    val primary: Long,
    val body: Long,
    val dim: Long,
    val faint: Long,
    val onPrimary: Long,
    val accent2: Long,
    val accent3: Long,
    val outBg: Long,
    val outLine: Long,
    val outLabel: Long,
    val inBg: Long,
    val inLine: Long,
    val sent: Long,
    val incoming: Long,
    val danger: Long,
    val warnBg: Long,
    val warnLine: Long,
    val primeBg: Long,
    val unread: Long,
    val selBg: Long,
    val toggleOn: Long,
    val toggleOff: Long,
    val rowLine: Long,
    val scrim: Long,
    val scrimStrong: Long,
    val mediaScrim: Long,
    val mark: Long,
    val searchBg: Long,
    val searchFg: Long,
    val glitchA: Long,
    val glitchB: Long,
    val glitchScan: Long,
    val crtScan: Long,
    val keyDown: Long,
) {
    /** Foreground/background pairs that must clear WCAG AA (4.5:1). Names are for the failure message. */
    fun textPairs(): List<Triple<String, Long, Long>> = listOf(
        Triple("body on bg", body, bg),
        Triple("body on s1", body, s1),
        Triple("body on s2", body, s2),
        Triple("sent on outBg", sent, outBg),
        Triple("incoming on inBg", incoming, inBg),
        Triple("primary on bg", primary, bg),
        Triple("primary on s1", primary, s1),
        Triple("primary on s2", primary, s2),
        Triple("accent2 on bg", accent2, bg),
        Triple("accent2 on s1", accent2, s1),
        Triple("accent2 on inBg", accent2, inBg),
        Triple("accent3 on bg", accent3, bg),
        Triple("accent3 on s1", accent3, s1),
        Triple("accent3 on s2", accent3, s2),
        Triple("accent3 on warnBg", accent3, warnBg),
        Triple("dim on s1", dim, s1),
        Triple("faint on s1", faint, s1),
        Triple("faint on s2", faint, s2),
        Triple("faint on bg", faint, bg),
        Triple("onPrimary on primary", onPrimary, primary),
        Triple("danger on s1", danger, s1),
        Triple("danger on bg", danger, bg),
        Triple("primary on outBg", primary, outBg),
        Triple("outLabel on outBg", outLabel, outBg),
        Triple("outLabel on s1", outLabel, s1),
        Triple("unread on s1", unread, s1),
        Triple("body on selBg", body, selBg),
        Triple("body on primeBg", body, primeBg),
        Triple("searchFg on searchBg", searchFg, searchBg),
    )
}

/** Strings that are part of the look (terminal prompts vs a display title). */
data class ThemeChrome(
    val inbox: String,
    val settings: String,
    val about: String,
    val prime: String,
    val link: String,
    /** Inserted before a date divider label. Matrix is blank, so the label text is unchanged. */
    val dayPrefix: String,
    val uppercaseNames: Boolean,
    val sectionSlash: Boolean,
    val rainLabel: String,
    /** Floating inbox action. Matrix keeps the bracketed label. */
    val fab: String,
)

data class EmitTheme(
    val id: String,
    val name: String,
    val blurb: String,
    val palette: ThemePalette,
    val corners: CornerStyle,
    val rain: RainStyle,
    val avatar: AvatarStyle,
    val bodyFont: FontId,
    val displayFont: FontId,
    val keyFont: FontId,
    val keyStyle: KeyStyle,
    /** When true, the default (uncolored) chat uses [ThemePalette.incoming]/[ThemePalette.sent] instead of the green tint. */
    val fixedBubbles: Boolean,
    /** Static title shadow, independent of the CRT toggle. */
    val titleGlow: Boolean,
    /** Static scanline overlay even when the CRT toggle is off. */
    val scanlinesAlways: Boolean,
    /** Occasional full-frame flicker in addition to the screen-change glitch. */
    val ambientGlitch: Boolean,
    val swatches: List<Long>,
    val chrome: ThemeChrome,
    /** ODST: radio-comms message headers, VISR corner outlines and the TRANSMISSION SENT squelch on send. */
    val comms: Boolean = false,
) {
    fun shape(radius: Dp): Shape = shape(radius, radius, radius, radius)

    /** [CornerStyle.ROUNDED] is RoundedCornerShape. CUT chamfers the top-left and bottom-right only. */
    fun shape(tl: Dp, tr: Dp, br: Dp, bl: Dp): Shape = when (corners) {
        CornerStyle.ROUNDED -> RoundedCornerShape(tl, tr, br, bl)
        CornerStyle.CUT -> CutCornerShape(tl, br)
    }
}

/**
 * Top-left and bottom-right chamfer. A zero cut on either end leaves that corner square,
 * so grouped rows still go flat where they meet.
 */
class CutCornerShape(val tl: Dp, val br: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val maxCut = minOf(size.width, size.height) / 2f
        val t = with(density) { tl.toPx() }.coerceIn(0f, maxCut)
        val b = with(density) { br.toPx() }.coerceIn(0f, maxCut)
        val path = Path().apply {
            moveTo(t, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, size.height - b)
            lineTo(size.width - b, size.height)
            lineTo(0f, size.height)
            lineTo(0f, t)
            close()
        }
        return Outline.Generic(path)
    }
    override fun equals(other: Any?) = other is CutCornerShape && other.tl == tl && other.br == br
    override fun hashCode() = 31 * tl.hashCode() + br.hashCode()
}

/** Today's Emit look, expressed as tokens. Every value is the color the UI already painted. */
val MatrixTheme = EmitTheme(
    id = "matrix",
    name = "Matrix",
    blurb = "terminal green, code rain (default)",
    palette = ThemePalette(
        bg = Pal.BG,
        s1 = Pal.S1,
        s2 = Pal.S2,
        line = Pal.LINE,
        line2 = Pal.LINE2,
        primary = Pal.PRIMARY,
        body = Pal.BODY,
        dim = Pal.DIM,
        faint = Pal.FAINT,
        onPrimary = 0xFF001A08,
        accent2 = Pal.CYAN,
        accent3 = 0xFFFFB000,
        outBg = Pal.OUT_BG,
        outLine = Pal.OUT_LINE,
        outLabel = Pal.OUT_LABEL,
        inBg = Pal.S1,
        inLine = Pal.LINE,
        sent = Pal.BODY,
        incoming = Pal.BODY,
        danger = 0xFFFF3131,
        warnBg = 0xFF120E06,
        warnLine = 0xFF4A3A1C,
        primeBg = 0xFF06140B,
        unread = 0xFF9CCBA9,
        selBg = 0xFF0F3A1E,
        toggleOn = 0xFF0F3A1E,
        toggleOff = 0xFF1A231D,
        rowLine = 0xFF141E17,
        scrim = 0xCC000000,
        scrimStrong = 0xDD000000,
        mediaScrim = 0xB3000000,
        mark = 0x26FFB000,
        searchBg = 0xFFFFB000,
        searchFg = 0xFF000000,
        glitchA = 0x55FF0040,
        glitchB = 0x5500FFFF,
        glitchScan = 0x22000000,
        crtScan = 0x1F000000,
        keyDown = 0xFFFFFFFF,
    ),
    corners = CornerStyle.ROUNDED,
    rain = RainStyle.KATAKANA,
    avatar = AvatarStyle.KATAKANA,
    bodyFont = FontId.MONO,
    displayFont = FontId.MONO,
    keyFont = FontId.MONO,
    keyStyle = KeyStyle.MATRIX,
    fixedBubbles = false,
    titleGlow = false,
    scanlinesAlways = false,
    ambientGlitch = false,
    swatches = listOf(Pal.BG, Pal.S2, Pal.PRIMARY, Pal.CYAN, Pal.BODY),
    chrome = ThemeChrome(
        inbox = "~\$ inbox",
        settings = "root@emit:~\$ settings",
        about = "root@emit:~\$ about",
        prime = "~\$ prime",
        link = "root@emit:~\$ link_pc",
        dayPrefix = "",
        uppercaseNames = false,
        sectionSlash = false,
        rainLabel = "background rain",
        fab = "[+ NEW]",
    ),
)

object Themes {
    val all: List<EmitTheme> = listOf(MatrixTheme, NeonCityTheme, OdstTheme)
    fun byId(id: String?): EmitTheme = all.firstOrNull { it.id == id } ?: MatrixTheme
}

val LocalTheme = staticCompositionLocalOf { MatrixTheme }

@Composable
fun FontId.family(): FontFamily = when (this) {
    FontId.MONO -> Mono
    FontId.OXANIUM -> Oxanium
}

val UiFont: FontFamily @Composable get() = LocalTheme.current.bodyFont.family()
val DisplayFont: FontFamily @Composable get() = LocalTheme.current.displayFont.family()
val KeyFont: FontFamily @Composable get() = LocalTheme.current.keyFont.family()

/** Neon City's default chat uses the theme's own bubble colors. A picked chat color still tints. */
@Composable
fun fixedDefaultChat(): Boolean =
    LocalTheme.current.fixedBubbles && LocalAccent.current.argb == ChatPalette.DEFAULT.argb

@Composable
fun groupedShape(top: Boolean, bottom: Boolean): Shape {
    val r = 12.dp
    val z = 0.dp
    return LocalTheme.current.shape(if (top) r else z, if (top) r else z, if (bottom) r else z, if (bottom) r else z)
}
