package com.tyler.primelauncher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/** Red themed hang-up button (icon only). Matrix: terminal-red ring with scanline glow. Neon City: cut-corner neon red plate. */
@SuppressLint("ViewConstructor")
class HangupButton(ctx: Context, private val neon: Boolean, private val reduce: Boolean) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG); private val path = Path(); private val handset = Path()
    private val red = if (neon) 0xFFFF2A4D.toInt() else 0xFFFF3131.toInt()
    private val t0 = System.nanoTime()
    init { contentDescription = "Hang up"; isClickable = true; isFocusable = true }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val s = min(w, h); val cx = w / 2; val cy = h / 2; val r = s * 0.40f
        val t = (System.nanoTime() - t0) / 1e9; val pulse = if (reduce) 0.5 else 0.5 + 0.5 * sin(t * 3.0)
        p.style = Paint.Style.FILL; p.blendMode = BlendMode.PLUS
        p.shader = RadialGradient(cx, cy, s * 0.5f, intArrayOf(argb(red, 0.30 + 0.20 * pulse), argb(red, 0.0)), floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, s * 0.5f, p); p.shader = null; p.blendMode = null
        // plate
        if (neon) { val c = r * 0.32f; path.rewind(); path.moveTo(cx - r + c, cy - r); path.lineTo(cx + r, cy - r); path.lineTo(cx + r, cy + r - c); path.lineTo(cx + r - c, cy + r); path.lineTo(cx - r, cy + r); path.lineTo(cx - r, cy - r + c); path.close()
            p.color = argb(red, 0.92); cv.drawPath(path, p); p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.03f; p.color = argb(0xFFFFB3C0.toInt(), 0.8); cv.drawPath(path, p) }
        else { p.color = 0xFF1A0000.toInt(); cv.drawCircle(cx, cy, r, p); p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.045f; p.color = argb(red, 0.95); cv.drawCircle(cx, cy, r, p) }
        // handset, rotated down (the universal "end call" glyph)
        p.style = Paint.Style.FILL; p.color = if (neon) Color.WHITE else red
        val hw = r * 1.1f; val hh = r * 0.42f
        handset.rewind()
        handset.moveTo(cx - hw / 2, cy + hh * 0.35f)
        handset.cubicTo(cx - hw / 2, cy - hh * 0.9f, cx + hw / 2, cy - hh * 0.9f, cx + hw / 2, cy + hh * 0.35f)
        handset.lineTo(cx + hw * 0.30f, cy + hh * 0.55f); handset.lineTo(cx + hw * 0.22f, cy + hh * 0.05f)
        handset.cubicTo(cx + hw * 0.08f, cy - hh * 0.12f, cx - hw * 0.08f, cy - hh * 0.12f, cx - hw * 0.22f, cy + hh * 0.05f)
        handset.lineTo(cx - hw * 0.30f, cy + hh * 0.55f); handset.close()
        cv.drawPath(handset, p)
        if (!reduce && isAttachedToWindow) postInvalidateOnAnimation()
    }
    private fun argb(c: Int, a: Double) = Color.argb((a.coerceIn(0.0, 1.0) * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))
}
