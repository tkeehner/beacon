package com.tyler.primelauncher.emitkb.ui

// Ported verbatim from Emit 0.1.47 (com.tyler.matrixsms.ui) for the launcher's Message Prime box; trimmed to what the keyboard uses.

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import kotlin.math.abs
import kotlin.random.Random

val Black: Color @Composable get() = Pal.Bg
val Amber: Color @Composable get() = Color(LocalTheme.current.palette.accent3)
/** JetBrains Mono NL (no ligatures; SIL OFL 1.1, see assets/licenses). Missing glyphs (katakana) fall back to the system font. */
val Mono = FontFamily(
    androidx.compose.ui.text.font.Font(com.tyler.primelauncher.R.font.jbmono_regular, androidx.compose.ui.text.font.FontWeight.Normal),
    androidx.compose.ui.text.font.Font(com.tyler.primelauncher.R.font.jbmono_medium, androidx.compose.ui.text.font.FontWeight.Medium),
    androidx.compose.ui.text.font.Font(com.tyler.primelauncher.R.font.jbmono_bold, androidx.compose.ui.text.font.FontWeight.Bold))

/** Oxanium (SIL OFL), variable on the weight axis. Titles and keys only. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val Oxanium = FontFamily(
    androidx.compose.ui.text.font.Font(com.tyler.primelauncher.R.font.oxanium, androidx.compose.ui.text.font.FontWeight.Normal,
        variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(400))),
    androidx.compose.ui.text.font.Font(com.tyler.primelauncher.R.font.oxanium, androidx.compose.ui.text.font.FontWeight.Medium,
        variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(500))),
    androidx.compose.ui.text.font.Font(com.tyler.primelauncher.R.font.oxanium, androidx.compose.ui.text.font.FontWeight.SemiBold,
        variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(600))),
    androidx.compose.ui.text.font.Font(com.tyler.primelauncher.R.font.oxanium, androidx.compose.ui.text.font.FontWeight.Bold,
        variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(700))),
)

/** True when the system animator duration scale is 0 (Remove animations / battery saver): effects go static. */
@Composable
fun rememberReduceMotion(): Boolean {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return remember { Motion.reduced(android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)) }
}
object Motion { fun reduced(animatorScale: Float) = animatorScale == 0f }

const val GLYPHS = "ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜﾝ"

fun glyphFor(key: String): Char = GLYPHS[abs(key.hashCode()) % GLYPHS.length]

/** Faint falling-code background, or the theme's drizzle. Idle when the level is off. */
@Composable
fun CodeRain(modifier: Modifier = Modifier, alpha: Float = 0.18f) {
    if (alpha <= 0f) return
    if (LocalTheme.current.rain == RainStyle.DRIZZLE) NeonDrizzle(modifier, alpha) else KatakanaRain(modifier, alpha)
}

@Composable
private fun KatakanaRain(modifier: Modifier, alpha: Float) {
    var tick by remember { mutableLongStateOf(0L) }
    val still = rememberReduceMotion()
    LaunchedEffect(still) { while (!still) { delay(70); tick++ } }
    val drops = remember { mutableStateListOf<Float>() }
    val paint = remember {
        Paint().apply { color = android.graphics.Color.rgb(0, 255, 65); typeface = Typeface.MONOSPACE; isAntiAlias = true }
    }
    val accent = LocalAccent.current.argb.toInt()
    Canvas(modifier.fillMaxSize().alpha(alpha)) {
        paint.color = accent
        val cell = 14.dp.toPx(); paint.textSize = cell
        val cols = (size.width / cell).toInt() + 1
        while (drops.size < cols) drops.add(Random.nextFloat() * (size.height / cell + 12f) - (if (still) 0f else 6f))
        tick // read to trigger redraw
        for (i in 0 until cols) {
            val y = drops[i]
            for (k in 0 until 12) {
                val row = y - k
                if (row < 0) continue
                paint.alpha = (255 * (1f - k / 12f)).toInt()
                drawContext.canvas.nativeCanvas.drawText(GLYPHS.random().toString(), i * cell, row * cell, paint)
            }
            drops[i] = if (y * cell > size.height + 12 * cell && Random.nextFloat() > 0.95f) 0f else y + 0.5f
        }
    }
}

/** Vertical neon streaks. Shares the rain's 70ms tick; nothing is scheduled when rain is off. */
@Composable
private fun NeonDrizzle(modifier: Modifier, alpha: Float) {
    var tick by remember { mutableLongStateOf(0L) }
    val still = rememberReduceMotion()
    LaunchedEffect(still) { while (!still) { delay(70); tick++ } }
    val seeds = remember { List(26) { Random.nextFloat() } }
    val palette = LocalTheme.current.palette
    val colors = listOf(Color(palette.accent2), Color(palette.accent3), Color(palette.primary), Color(palette.accent2))
    Canvas(modifier.fillMaxSize().alpha(alpha)) {
        tick
        val h = size.height
        seeds.forEachIndexed { i, seed ->
            val len = 36f + (i % 5) * 26f
            val x = ((seed * 97f + i * 41f) % size.width.coerceAtLeast(1f))
            val travel = if (still) seed * h else ((seed * h) + tick * (6f + i % 4)) % (h + len)
            val y = travel - len
            drawLine(colors[i % colors.size].copy(alpha = 0.45f), Offset(x, y), Offset(x, y + len), strokeWidth = 1.6f)
        }
        drawRect(Color(palette.accent3).copy(alpha = 0.05f), Offset(0f, h * 0.72f), androidx.compose.ui.geometry.Size(size.width, h * 0.28f))
    }
}
