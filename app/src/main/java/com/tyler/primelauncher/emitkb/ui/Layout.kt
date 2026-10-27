package com.tyler.primelauncher.emitkb.ui

// Ported verbatim from Emit 0.1.47 (com.tyler.matrixsms.ui) for the launcher's Message Prime box; trimmed to what the keyboard uses.

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

/** Shared spacing so cards, dialogs and rows line up on every screen. */
object Ui {
    // 4/8dp grid
    val ScreenPad = 16.dp      // outer padding of every screen
    val CardPad = 12.dp        // inside a bordered box
    val Gap = 8.dp             // between siblings / buttons
    val DialogPad = 16.dp
    val Touch = 48.dp          // minimum touch target
    val DialogMaxWidth = 420.dp
    val OutgoingIndent = 48.dp   // every outgoing message starts at this same left edge; incoming are flush left
    val BubbleGap = 4.dp       // vertical space inside a message block (half of BubbleGapBetween)
    val BubbleGapBetween = 8.dp

    /** Monospace label width estimate (0.6em per char + 2x12dp padding + border). */
    fun labelWidthDp(label: String, fontSp: Float, fontScale: Float): Float = label.length * fontSp * 0.6f * fontScale + 26f
    /** Side by side only if every button fits in an equal share; otherwise stack full width. */
    fun shouldStack(availDp: Float, labels: List<String>, fontSp: Float, fontScale: Float, gapDp: Float = 10f): Boolean {
        if (labels.size < 2) return false
        val share = (availDp - gapDp * (labels.size - 1)) / labels.size
        return labels.any { labelWidthDp(it, fontSp, fontScale) > share }
    }
}

data class Btn(val label: String, val color: Color, val filled: Boolean = false, val confirm: Boolean = false, val onClick: () -> Unit)

/** One terminal-style button: single line, centered, never clipped. */
@Composable
fun TermButton(b: Btn, modifier: Modifier = Modifier, fontSp: Int = 14, haptic: Boolean = false) {
    val hapticView = androidx.compose.ui.platform.LocalView.current
    val base = if (b.filled) modifier.background(b.color, Pal.R8) else modifier.panel(Pal.S2c, b.color.copy(alpha = 0.6f), Pal.R8)
    Text(b.label, color = if (b.filled) Pal.OnPrimary else b.color, fontFamily = UiFont, fontSize = TypeScale.snap(fontSp), maxLines = 1, softWrap = false,
        overflow = TextOverflow.Clip, textAlign = TextAlign.Center,
        modifier = base.clickable { if (b.confirm) hapticView.confirm(); b.onClick() }.heightIn(min = Ui.Touch).wrapContentHeight(Alignment.CenterVertically)
            .padding(horizontal = 12.dp, vertical = 8.dp))
}

/** Equal-weight buttons in a row; stacks full-width when the screen/font scale is too tight. */
@Composable
fun ButtonRow(vararg buttons: Btn, modifier: Modifier = Modifier, fontSp: Int = 14) {
    val scale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (Ui.shouldStack(maxWidth.value, buttons.map { it.label }, fontSp.toFloat(), scale, Ui.Gap.value))
            Column(verticalArrangement = Arrangement.spacedBy(Ui.Gap)) { buttons.forEach { TermButton(it, Modifier.fillMaxWidth(), fontSp) } }
        else Row(horizontalArrangement = Arrangement.spacedBy(Ui.Gap)) { buttons.forEach { TermButton(it, Modifier.weight(1f), fontSp) } }
    }
}

@Composable
fun Modifier.dialogBox(c: Color, w: Dp = Ui.DialogMaxWidth) = this.widthIn(max = w).fillMaxWidth().background(Pal.S2c, Pal.R12).border(1.dp, Pal.Line2, Pal.R12).padding(Ui.DialogPad)

/** Elevated surface with a 1dp hairline. Corner treatment comes from the active theme. */
@Composable
fun Modifier.panel(bg: Color = Pal.S1c, line: Color = Pal.Line, shape: androidx.compose.ui.graphics.Shape = Pal.R10) =
    this.background(bg, shape).border(1.dp, line, shape)

/** Status chip: [● E2E]. Glow dot only when [on]. */
@Composable
fun Chip(label: String, on: Boolean = false, color: Color = Pal.Primary, desc: String? = null) {
    Row(Modifier.panel(Pal.S2c, if (on) color.copy(alpha = 0.45f) else Pal.Line2, Pal.R8).padding(horizontal = 7.dp, vertical = 3.dp)
        .then(if (desc != null) Modifier.semantics(mergeDescendants = true) { contentDescription = desc } else Modifier),
        verticalAlignment = Alignment.CenterVertically) {
        if (on) { Box(Modifier.size(6.dp).background(color, androidx.compose.foundation.shape.CircleShape)); Spacer(Modifier.width(5.dp)) }
        Text(label, color = if (on) color else Pal.Dim, fontFamily = UiFont, fontSize = TypeScale.Micro, maxLines = 1, softWrap = false)
    }
}

/** Pill toggle (48dp touch row is the caller's). Cut themes use a small chamfer; an 11dp cut would eat the track. */
@Composable
fun Pill(on: Boolean) {
    val cut = LocalTheme.current.corners == CornerStyle.CUT
    val track = if (cut) CutCornerShape(3.dp, 3.dp) else androidx.compose.foundation.shape.RoundedCornerShape(11.dp)
    val knob = if (cut) CutCornerShape(2.dp, 2.dp) else androidx.compose.foundation.shape.CircleShape
    Box(Modifier.size(38.dp, 22.dp).panel(if (on) Pal.ToggleOn else Pal.ToggleOff, if (on && cut) Pal.Primary else if (on) Pal.OutLine else Pal.Line2, track),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.padding(horizontal = 3.dp).size(14.dp).background(if (on) Pal.Primary else Pal.Faint, knob))
    }
}

/** CRT scanlines + header glow (display setting, off by default). */
object UiPrefs {
    private const val P = "ui_prefs"
    val crt = kotlinx.coroutines.flow.MutableStateFlow<Boolean?>(null)
    fun load(c: android.content.Context): Boolean = crt.value ?: c.getSharedPreferences(P, 0).getBoolean("crt", false).also { crt.value = it }
    fun setCrt(c: android.content.Context, on: Boolean) { c.getSharedPreferences(P, 0).edit().putBoolean("crt", on).apply(); crt.value = on }
    /** Link previews: OFF by default; nothing is fetched unless this is on. */
    val previews = kotlinx.coroutines.flow.MutableStateFlow<Boolean?>(null)
    fun previewsOn(c: android.content.Context): Boolean = previews.value ?: c.getSharedPreferences(P, 0).getBoolean("previews", false).also { previews.value = it }
    fun setPreviews(c: android.content.Context, on: Boolean) { c.getSharedPreferences(P, 0).edit().putBoolean("previews", on).apply(); previews.value = on }
}
/** Background rain level: 0 off, 1 subtle (default), 2 more. */
object RainPref {
    val level = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    fun load(c: android.content.Context): Int = level.value ?: c.getSharedPreferences("ui_prefs", 0).getInt("rain", 1).coerceIn(0, 2).also { level.value = it }
    fun set(c: android.content.Context, v: Int) { c.getSharedPreferences("ui_prefs", 0).edit().putInt("rain", v).apply(); level.value = v }
}

/** Active theme id, stored next to the rain level. Unknown ids fall back to Matrix. */
object ThemePref {
    val id = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    fun load(c: android.content.Context): String =
        id.value ?: (c.getSharedPreferences("ui_prefs", 0).getString("theme", MatrixTheme.id) ?: MatrixTheme.id).also { id.value = it }
    fun set(c: android.content.Context, themeId: String) {
        val resolved = Themes.all.firstOrNull { it.id == themeId }?.id ?: MatrixTheme.id
        c.getSharedPreferences("ui_prefs", 0).edit().putString("theme", resolved).apply()
        id.value = resolved
    }
}

/** Glitch and key-glow. Both default on, same prefs file as rain. Off skips the effect; it does not add a redraw loop. */
object EffectPrefs {
    private const val P = "ui_prefs"
    val glitch = kotlinx.coroutines.flow.MutableStateFlow<Boolean?>(null)
    val keyGlow = kotlinx.coroutines.flow.MutableStateFlow<Boolean?>(null)
    fun glitchOn(c: android.content.Context): Boolean =
        glitch.value ?: c.getSharedPreferences(P, 0).getBoolean("glitch", true).also { glitch.value = it }
    fun setGlitch(c: android.content.Context, on: Boolean) { c.getSharedPreferences(P, 0).edit().putBoolean("glitch", on).apply(); glitch.value = on }
    fun keyGlowOn(c: android.content.Context): Boolean =
        keyGlow.value ?: c.getSharedPreferences(P, 0).getBoolean("key_glow", true).also { keyGlow.value = it }
    fun setKeyGlow(c: android.content.Context, on: Boolean) { c.getSharedPreferences(P, 0).edit().putBoolean("key_glow", on).apply(); keyGlow.value = on }
}

@Composable
fun glitchEnabled(): Boolean {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val v by EffectPrefs.glitch.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { EffectPrefs.glitchOn(ctx) }
    return v ?: true
}

@Composable
fun keyGlowEnabled(): Boolean {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val v by EffectPrefs.keyGlow.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { EffectPrefs.keyGlowOn(ctx) }
    return v ?: true
}
@androidx.compose.runtime.Composable
fun rainAlpha(): Float {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val v by RainPref.level.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { RainPref.load(ctx) }
    return Pal.RAIN[(v ?: 1).coerceIn(0, 2)]
}
/** Dark translucent backing for small text drawn straight over the rain (keeps >= 4.5:1 vs the brightest glyph). */
@Composable
fun Modifier.scrim() = this.background(Pal.Bg.copy(alpha = Pal.SURFACE_A), Pal.R8).padding(horizontal = 4.dp, vertical = 1.dp)
val LocalCrt = androidx.compose.runtime.staticCompositionLocalOf { false }

/** 150ms fade+scale in for dialog / sheet content (instant when animations are off). */
@Composable
fun DialogEnter(content: @Composable () -> Unit) {
    val still = rememberReduceMotion()
    val st = androidx.compose.runtime.remember { androidx.compose.animation.core.MutableTransitionState(still) }.apply { targetState = true }
    androidx.compose.animation.AnimatedVisibility(st, enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(150)) +
        androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(150), initialScale = 0.96f)) { content() }
}

/** Type scale: every Text uses one of these (Meta is the floor for timestamps/status). */
object TypeScale {
    val Title = 18.sp; val Body = 14.sp; val Label = 12.sp; val Meta = 11.sp; val Micro = 10.sp
    fun forSp(n: Int): Int = when { n >= 20 -> n; n >= 16 -> 18; n >= 14 -> 14; n >= 12 -> 12; n == 11 -> 11; else -> 10 }
    fun snap(n: Int) = forSp(n).sp
}

/** 48dp minimum touch target around any clickable, without shrinking larger content. */
fun Modifier.tap(enabled: Boolean = true, onClick: () -> Unit) =
    // Height-only floor + vertical centering: keeps the child's own width/horizontal alignment (0.1.27 used
    // wrapContentSize(CenterStart), which shrank rows and pushed centered glyphs/labels off-center or out of view).
    this.heightIn(min = Ui.Touch).clickable(enabled = enabled, onClick = onClick).wrapContentHeight(Alignment.CenterVertically)

/** Confirm haptic for send / approve / reject / update success. */
fun android.view.View.confirm() {
    performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM else android.view.HapticFeedbackConstants.VIRTUAL_KEY)
}
