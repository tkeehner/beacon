package com.tyler.primelauncher.emitkb.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** Neon palette for per-chat colors. Pure logic (unit-tested): ids, lookups, dim variant. */
data class NeonColor(val id: String, val label: String, val argb: Long)

object ChatPalette {
    val DEFAULT = NeonColor("green", "Terminal green", 0xFF00FF41)
    val ALL = listOf(
        DEFAULT,
        NeonColor("cyan", "Cyan", 0xFF00E5FF),
        NeonColor("amber", "Amber", 0xFFFFB000),
        NeonColor("magenta", "Magenta", 0xFFFF2BD6),
        NeonColor("red", "Red", 0xFFFF3131),
        NeonColor("ice", "Ice blue", 0xFF9AD9FF),
    )
    fun byId(id: String?): NeonColor = ALL.firstOrNull { it.id == id } ?: DEFAULT

    /** The owner's own sent text is always terminal green. */
    const val SENT_ARGB = 0xFFB8F5C8  // owner's own text: one pale-green body color in every chat

    /** Incoming text: full chosen color, except default green chats keep the dimmer green. */
    fun incoming(c: NeonColor): Long = if (c.id == DEFAULT.id) dim(c.argb) else c.argb

    fun luminance(argb: Long): Double {
        fun lin(c: Long): Double { val v = c / 255.0; return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }
        return 0.2126 * lin((argb shr 16) and 0xFF) + 0.7152 * lin((argb shr 8) and 0xFF) + 0.0722 * lin(argb and 0xFF)
    }
    fun contrastOnBlack(argb: Long) = (luminance(argb) + 0.05) / 0.05
    /** dim() by [factor], raised just enough to reach [minRatio] contrast on black. */
    fun dimForContrast(argb: Long, factor: Float, minRatio: Double): Long {
        var f = factor
        while (f < 1f && contrastOnBlack(dim(argb, f)) < minRatio) f += 0.01f
        return dim(argb, f.coerceAtMost(1f))
    }

    /** Darken each RGB channel to [factor] (default 65%) - used for incoming text. */
    fun dim(argb: Long, factor: Float = 0.65f): Long {
        val a = (argb shr 24) and 0xFF
        fun ch(shift: Int) = ((((argb shr shift) and 0xFF) * factor).toLong()).coerceIn(0, 255)
        return (a shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/**
 * Contact-name color (0.1.47): names are highlighted the way Prime's is, in the chat's chosen color.
 * A chat left on the default color uses the theme's primary (the color Prime's name already uses).
 * Lightened toward white only if needed to clear WCAG AA on [bg]; the hue is kept.
 */
object NameColor {
    fun argb(chat: NeonColor, primary: Long, bg: Long): Long =
        Pal.ensure(if (chat.id == ChatPalette.DEFAULT.id) primary else chat.argb, bg, 4.5)
    fun isDefault(chat: NeonColor) = chat.id == ChatPalette.DEFAULT.id
}

/** Current accent for whatever is being drawn (a chat's color, or default green). */
val LocalAccent = compositionLocalOf { ChatPalette.DEFAULT }

/** Primary UI green (mockup #3DFF7A). Constant: chat colors never change it. */
val Green: Color @Composable get() = Pal.Primary
val DimGreen: Color @Composable get() = Pal.Dim
/** Secondary text; clears 4.5:1 on every (tinted) surface. */
val FaintGreen: Color @Composable get() = Pal.Faint
/** This chat's own color (only for subtle tints and the other person's accents). */
val Accent: Color @Composable get() = Color(LocalAccent.current.argb)

/** "Modern terminal" palette (mockups 0.1.32) + per-chat tint math (pure, unit-tested). */
object Pal {
    const val PRIMARY = 0xFF3DFF7AL; const val BODY = 0xFFB8F5C8L; const val DIM = 0xFF7A9C83L; const val FAINT = 0xFF729379L
    const val BG = 0xFF000000L; const val S1 = 0xFF0B0F0CL; const val S2 = 0xFF111814L; const val LINE = 0xFF1C2A20L; const val LINE2 = 0xFF25382BL
    const val OUT_BG = 0xFF0C1A11L; const val OUT_LINE = 0xFF1F3D29L; const val OUT_LABEL = 0xFF5FAE79L
    const val CYAN = 0xFF4FE3F0L
    /** Live colors. With the default theme these are the constants above; a theme swaps the whole set. */
    val Primary: Color @Composable get() = Color(LocalTheme.current.palette.primary)
    val Body: Color @Composable get() = Color(LocalTheme.current.palette.body)
    val Dim: Color @Composable get() = Color(LocalTheme.current.palette.dim)
    val Faint: Color @Composable get() = Color(LocalTheme.current.palette.faint)
    val S1c: Color @Composable get() = Color(LocalTheme.current.palette.s1)
    val S2c: Color @Composable get() = Color(LocalTheme.current.palette.s2)
    val Line: Color @Composable get() = Color(LocalTheme.current.palette.line)
    val Line2: Color @Composable get() = Color(LocalTheme.current.palette.line2)
    val OutBg: Color @Composable get() = Color(LocalTheme.current.palette.outBg)
    val OutLine: Color @Composable get() = Color(LocalTheme.current.palette.outLine)
    val OutLabel: Color @Composable get() = Color(LocalTheme.current.palette.outLabel)
    val Cyan: Color @Composable get() = Color(LocalTheme.current.palette.accent2)
    val OnPrimary: Color @Composable get() = Color(LocalTheme.current.palette.onPrimary)
    val WarnBg: Color @Composable get() = Color(LocalTheme.current.palette.warnBg)
    val WarnLine: Color @Composable get() = Color(LocalTheme.current.palette.warnLine)
    val PrimeBg: Color @Composable get() = Color(LocalTheme.current.palette.primeBg)
    val Unread: Color @Composable get() = Color(LocalTheme.current.palette.unread)
    val SelBg: Color @Composable get() = Color(LocalTheme.current.palette.selBg)
    val ToggleOn: Color @Composable get() = Color(LocalTheme.current.palette.toggleOn)
    val ToggleOff: Color @Composable get() = Color(LocalTheme.current.palette.toggleOff)
    val RowLine: Color @Composable get() = Color(LocalTheme.current.palette.rowLine)
    val Scrim: Color @Composable get() = Color(LocalTheme.current.palette.scrim)
    val ScrimStrong: Color @Composable get() = Color(LocalTheme.current.palette.scrimStrong)
    val MediaScrim: Color @Composable get() = Color(LocalTheme.current.palette.mediaScrim)
    val Mark: Color @Composable get() = Color(LocalTheme.current.palette.mark)
    val SearchBg: Color @Composable get() = Color(LocalTheme.current.palette.searchBg)
    val SearchFg: Color @Composable get() = Color(LocalTheme.current.palette.searchFg)
    val KeyDown: Color @Composable get() = Color(LocalTheme.current.palette.keyDown)
    val Bg: Color @Composable get() = Color(LocalTheme.current.palette.bg)
    val R8: Shape @Composable get() = LocalTheme.current.shape(8.dp)
    val R10: Shape @Composable get() = LocalTheme.current.shape(10.dp)
    val R12: Shape @Composable get() = LocalTheme.current.shape(12.dp)

    fun mix(a: Long, b: Long, t: Float): Long {
        fun ch(x: Long, sh: Int) = (x shr sh) and 0xFF
        fun m(sh: Int) = (ch(a, sh) + (ch(b, sh) - ch(a, sh)) * t).toLong().coerceIn(0, 255)
        return (0xFFL shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }
    fun contrast(fg: Long, bg: Long): Double {
        val a = ChatPalette.luminance(fg); val b = ChatPalette.luminance(bg)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }
    /** Lighten toward white until fg reaches [min] contrast on bg. */
    fun ensure(fg: Long, bg: Long, min: Double = 4.5): Long {
        var c = fg; var t = 0f
        while (contrast(c, bg) < min && t < 1f) { t += 0.05f; c = mix(fg, 0xFFFFFFFFL, t) }
        return c
    }
    /** Per-chat tint strengths: slight. */
    /** Background rain intensity for off / subtle / more, and surface opacities over it. */
    val RAIN = floatArrayOf(0f, 0.18f, 0.32f)
    const val SURFACE_A = 0.82f; const val ROW_A = 0.70f; const val BUBBLE_A = 0.92f; const val CHATBG_A = 0.80f
    /** Pure alpha compositing (over is opaque result). */
    fun over(top: Long, bottom: Long, a: Float) = mix(bottom, top, a)
    /** Brightest rain pixel: accent at full glyph alpha times layer alpha, over black. */
    fun rainPeak(accent: Long, level: Int) = mix(BG, accent, RAIN[level])
    data class Tint(val bg: Long, val s1: Long, val line: Long, val incoming: Long, val name: Long)
    /** Defaults are the Matrix bases, so existing callers (and the contrast tests) stay on those numbers. */
    fun tint(accent: Long, bg0: Long = BG, s10: Long = S1, line0: Long = LINE, body0: Long = BODY, dim0: Long = DIM): Tint {
        val bg = mix(bg0, accent, 0.035f); val s1 = mix(s10, accent, 0.055f); val line = mix(line0, accent, 0.14f)
        val incoming = ensure(mix(body0, accent, 0.40f), s1, 4.5)
        val name = ensure(mix(dim0, accent, 0.55f), bg, 4.5)
        return Tint(bg, s1, line, incoming, name)
    }
}

/** Tint for the chat currently provided by LocalAccent, over the active theme's surfaces. */
val ChatTint: Pal.Tint @Composable get() {
    val p = LocalTheme.current.palette
    return Pal.tint(LocalAccent.current.argb, p.bg, p.s1, p.line, p.body, p.dim)
}
