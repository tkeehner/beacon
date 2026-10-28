package com.tyler.primelauncher

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 0.1.17 ODST skin: the orb is a small sage-green disc with a thick white ring and two big white eyes (the reference
 * face). That eyes-only face is the resting state; the eight mouth expressions only "pop" briefly at key beats and flip
 * back like a sign panel. Original drawing, no game assets.
 */
object Expr {
    const val EYES = 0; const val NEUTRAL = 1; const val HAPPY = 2; const val SAD = 3; const val ANGRY = 4; const val BORED = 5
    const val CONFUSED = 6; const val SLEEPING = 7; const val OFF = 8
    val NAMES = listOf("eyes", "neutral", "happy", "sad", "angry", "bored", "confused", "sleeping", "off")
    /** Which face to show: an antic's explicit pop wins; otherwise the shared body-language cues map onto the ODST set. Pure. */
    fun target(p: Pose, sleepE: Double): Int = when {
        p.expr != EYES -> p.expr
        sleepE > 0.75 -> SLEEPING
        p.spiral > 0.5 -> CONFUSED
        p.happy > 0.55 -> HAPPY
        else -> EYES
    }
}

/**
 * Sign-panel flip between faces: the old face squashes to a sliver, the new one opens out ([d] s in all). A pop holds at
 * least [hold] s before it may flip back to the eyes, so a cue hovering at its threshold can't flicker. Pure, unit-tested.
 */
class FaceFlip(val d: Double = 0.26, val hold: Double = 0.45) {
    var shown = Expr.EYES; private set
    /** Face to draw this frame and its horizontal scale (1 = flat on, ~0 = edge on). */
    var face = Expr.EYES; private set
    var sx = 1.0; private set
    private var from = Expr.EYES; private var t0 = -99.0
    fun step(t: Double, target: Int) {
        if (target != shown && !(target == Expr.EYES && shown != Expr.EYES && t - t0 < hold)) {
            // retargeting during the first half keeps the face that is visibly closing
            from = if (t - t0 < d / 2) from else shown; shown = target; t0 = t
        }
        val e = t - t0
        when {
            e < d / 2 -> { face = from; sx = 1 - e / (d / 2) }
            e < d -> { face = shown; sx = (e - d / 2) / (d / 2) }
            else -> { face = shown; sx = 1.0 }
        }
        sx = sx.coerceIn(0.02, 1.0)
    }
    fun snap(e: Int) { shown = e; from = e; t0 = -99.0; face = e; sx = 1.0 }
}

class OdstFace {
    companion object {
        const val FACE = 0xFF5E7E4D.toInt(); const val VERGIL = 0xFF3C9BFF.toInt(); const val VISR = 0xFFD4FF3A.toInt()
        const val AMBER = 0xFFFFB23F.toInt(); const val STEEL = 0xFF6F9CCC.toInt(); const val WHITE = 0xFFFFFFFF.toInt()
        private fun withA(c: Int, a: Double) = Color.argb((a.coerceIn(0.0, 1.0) * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))
    }
    /** Live inputs for one frame (gaze in mockup units, roughly -1..1). */
    class State { var gx = 0.0; var gy = 0.0; var blink = 0.0; var lid = 0.0; var wide = 0.0; var glow = 1.0; var vergil = 0.0; var visr = false; var dart = 0.0; var showMarks = true }

    private val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL) }
    private val bold = Typeface.create("sans-serif", Typeface.BOLD)
    private val rf = RectF(); private val path = Path()
    private val glow = HashMap<Int, RadialGradient>()
    // Unit-radius gradients, placed with the canvas matrix (no shader churn per frame).
    private val vergilBody = RadialGradient(-0.3f, -0.35f, 1.1f, 0xFF4A8FD6.toInt(), 0xFF2F6FB8.toInt(), Shader.TileMode.CLAMP)
    private val visrBody = RadialGradient(-0.3f, -0.35f, 1.1f, 0xFF0E1A06.toInt(), 0xFF050A02.toInt(), Shader.TileMode.CLAMP)
    private val offBody = RadialGradient(-0.3f, -0.35f, 1.1f, 0xFF26302A.toInt(), 0xFF121815.toInt(), Shader.TileMode.CLAMP)
    var glowOn = true

    private fun glowGrad(c: Int) = glow.getOrPut(c) { RadialGradient(0f, 0f, 2.1f, intArrayOf(withA(c, 0.44), withA(c, 0.44), withA(c, 0.0)), floatArrayOf(0f, 0.8f / 2.1f, 1.9f / 2.1f), Shader.TileMode.CLAMP) }

    /** The face at ([x], [y]) with radius [r] (dp). [flipX] squashes it horizontally for the panel flip. */
    fun draw(cv: Canvas, x: Float, y: Float, r: Float, t: Double, e: Int, flipX: Double, f: State, alpha: Double = 1.0) {
        if (r < 1f || alpha <= 0.01) return
        val v = f.vergil > 0.5; val off = e == Expr.OFF; val a = alpha.coerceIn(0.0, 1.0)
        cv.save(); cv.translate(x, y); cv.scale(flipX.toFloat().coerceAtLeast(0.02f), 1f)
        // soft glow
        if (!off) { val gc = if (v) VERGIL else if (f.visr) VISR else 0xFF9FD48A.toInt(); fp.shader = glowGrad(gc); fp.alpha = (255 * a * (f.glow / 2).coerceIn(0.0, 1.0)).toInt()
            cv.save(); cv.scale(r, r); cv.drawCircle(0f, 0f, 2.1f, fp); cv.restore(); fp.shader = null; fp.alpha = 255 }
        // disc + ring
        val lw = r * 0.075f; val discR = r - lw * 0.5f
        when { off -> fp.shader = offBody; v -> fp.shader = vergilBody; f.visr -> fp.shader = visrBody; else -> { fp.shader = null; fp.color = FACE } }
        fp.alpha = (255 * a).toInt(); cv.save(); cv.scale(discR, discR); cv.drawCircle(0f, 0f, 1f, fp); cv.restore(); fp.shader = null
        sp.strokeWidth = lw; sp.color = withA(if (off) 0xFF59625C.toInt() else if (f.visr && !v) VISR else WHITE, a)
        if (f.visr && !v && glowOn) sp.setShadowLayer(8f, 0f, 0f, withA(VISR, a)) else sp.clearShadowLayer()
        cv.drawCircle(0f, 0f, r - lw / 2, sp); sp.clearShadowLayer()
        if (v) { cv.save(); path.rewind(); path.addCircle(0f, 0f, r * 0.95f, Path.Direction.CW); cv.clipPath(path); fp.color = withA(0xFFBFE3FF.toInt(), 0.45 * a)
            for (i in 0 until 4) { val yy = -r + (((t * 60 + i * 17) % (2 * r)).toFloat()); cv.drawRect(-r, yy, r, yy + 1.5f, fp) }; cv.restore() }
        if (off) { fp.color = withA(0xFF3A443D.toInt(), a); cv.drawCircle(0f, r * 0.45f, r * 0.07f, fp); cv.restore(); return }
        val lidCol = if (v) 0xFF3C80C8.toInt() else if (f.visr) 0xFF050A02.toInt() else FACE
        fp.color = withA(WHITE, a); sp.color = withA(WHITE, a); sp.strokeWidth = r * 0.09f
        val bl = f.blink.coerceIn(0.0, 1.0)
        if (e == Expr.EYES) {
            // idle life from the mockup: dart squash on quick glances, a curious asymmetry every 6.7 s, optional lid / wide
            val dart = f.dart.coerceIn(0.0, 1.0); val cur = if (t % 6.7 > 5.6) Ez.bump(t % 6.7 - 5.6, 1.1) else 0.0; val wide = f.wide.coerceIn(0.0, 1.0)
            val ex = r * 0.40; val ey = r * 0.01 + f.gy * r * 0.07; val rx = r * 0.274 * (1 + 0.10 * dart + 0.05 * wide); val ry0 = r * 0.263 * (1 - 0.12 * dart + 0.08 * wide)
            for (s in intArrayOf(-1, 1)) { val k = if (s < 0) 1 - 0.12 * cur else 1 + 0.06 * cur; val cx = s * ex + f.gx * r * 0.10; val ry = ry0 * k * max(0.07, 1 - bl)
                rf.set((cx - rx).toFloat(), (ey - ry).toFloat(), (cx + rx).toFloat(), (ey + ry).toFloat()); cv.drawOval(rf, fp)
                val lid = f.lid.coerceIn(0.0, 0.95); if (lid > 0.005) { fp.color = withA(lidCol, a); cv.drawRect((cx - rx - 1).toFloat(), (ey - ry0 - 1).toFloat(), (cx + rx + 1).toFloat(), (ey - ry0 - 1 + (2 * ry0 + 2) * lid).toFloat(), fp); fp.color = withA(WHITE, a) } }
            cv.restore(); return
        }
        val ex = r * 0.38f; val ey = -r * 0.10f; val er = r * 0.16f; val gx = (f.gx * r * 0.18).toFloat(); val gy = (f.gy * r * 0.14).toFloat()
        fun dot(px: Float, py: Float, rad: Float) { val ry = rad * max(0.12, 1 - bl).toFloat(); rf.set(px + gx - rad, py + gy - ry, px + gx + rad, py + gy + ry); cv.drawOval(rf, fp) }
        fun mouth(curve: Float, w: Float = 0.34f, yy: Float = 0.38f) { path.rewind(); path.moveTo(-r * w, r * yy); path.quadTo(0f, r * (yy + curve), r * w, r * yy); cv.drawPath(path, sp) }
        fun arc(cx: Float, cy: Float, rad: Float, a0: Double, a1: Double, fill: Boolean = false) { rf.set(cx - rad, cy - rad, cx + rad, cy + rad)
            cv.drawArc(rf, Math.toDegrees(a0).toFloat(), Math.toDegrees(a1 - a0).toFloat(), fill, if (fill) fp else sp) }
        fun seg(x0: Float, y0: Float, x1: Float, y1: Float) = cv.drawLine(x0, y0, x1, y1, sp)
        when (e) {
            Expr.NEUTRAL -> { dot(-ex, ey, er); dot(ex, ey, er); mouth(0f, 0.26f) }
            Expr.HAPPY -> { arc(-ex + gx, ey + er * 0.6f + gy, er * 1.3f, PI * 1.1, PI * 1.9); arc(ex + gx, ey + er * 0.6f + gy, er * 1.3f, PI * 1.1, PI * 1.9); mouth(0.32f, 0.36f, 0.3f) }
            Expr.SAD -> { dot(-ex, ey + r * 0.06f, er * 0.9f); dot(ex, ey + r * 0.06f, er * 0.9f); sp.strokeWidth = r * 0.07f
                seg(-ex - er * 1.4f, ey - er * 1.2f, -ex + er * 1.2f, ey - er * 2.2f); seg(ex + er * 1.4f, ey - er * 1.2f, ex - er * 1.2f, ey - er * 2.2f); sp.strokeWidth = r * 0.09f; mouth(-0.24f, 0.26f, 0.5f) }
            Expr.ANGRY -> { dot(-ex, ey + r * 0.04f, er * 0.95f); dot(ex, ey + r * 0.04f, er * 0.95f)
                seg(-ex - er * 1.5f, ey - er * 2.2f, -ex + er * 1.4f, ey - er * 1f); seg(ex + er * 1.5f, ey - er * 2.2f, ex - er * 1.4f, ey - er * 1f); mouth(-0.14f, 0.3f, 0.46f) }
            Expr.BORED -> { for (s in intArrayOf(-1, 1)) { arc(s * ex + gx, ey + gy, er, 0.0, PI, fill = true); seg(s * ex - er * 1.3f + gx, ey + gy, s * ex + er * 1.3f + gx, ey + gy) }
                seg(-r * 0.18f, r * 0.42f, r * 0.3f, r * 0.38f) }
            Expr.CONFUSED -> { dot(-ex, ey, er * 0.75f); dot(ex, ey - r * 0.03f, er * 1.3f); sp.strokeWidth = r * 0.07f; seg(ex - er * 1.3f, ey - er * 2.6f, ex + er * 1.4f, ey - er * 3f); sp.strokeWidth = r * 0.09f
                path.rewind(); path.moveTo(-r * 0.3f, r * 0.42f); for (i in 1..6) path.lineTo(-r * 0.3f + i * r * 0.1f, r * 0.42f + (if (i % 2 == 1) -1 else 1) * r * 0.05f); cv.drawPath(path, sp) }
            Expr.SLEEPING -> { for (s in intArrayOf(-1, 1)) arc(s * ex, ey - er * 0.2f, er * 1.1f, PI * 0.1, PI * 0.9); cv.drawCircle(0f, r * 0.44f, r * 0.07f, fp) }
        }
        cv.restore()
        if (!f.showMarks) return
        if (e == Expr.SLEEPING) { tp.typeface = bold; tp.textAlign = Paint.Align.LEFT; tp.textSize = r * 0.34f
            for (i in 0 until 3) { val s = (t * 0.6 + i / 3.0) % 1; tp.color = withA(0xFFE8FFF0.toInt(), (1 - s) * 0.9 * a); cv.drawText("z", (x + r * 0.8 + s * r * 0.56).toFloat(), (y - r * 0.7 - s * r * 0.8).toFloat(), tp) } }
        if (e == Expr.CONFUSED) { tp.typeface = bold; tp.textAlign = Paint.Align.LEFT; tp.textSize = r * 0.5f; tp.color = withA(WHITE, 0.9 * a)
            cv.drawText("?", x + r + r * 0.19f, (y - r - r * 0.06f + sin(t * 4) * r * 0.06).toFloat(), tp) }
    }

    /**
     * The HUD around the face (mockup hud()): steel + amber arcs turning in opposite directions, a tick ring, corner
     * brackets, two labels and horizon lines. [k] = scale from the mockup (face radius / 32). VISR: all yellow-green.
     */
    fun hud(cv: Canvas, x: Float, y: Float, r: Float, w: Float, t: Double, a: Double, visr: Boolean, label1: String?, label2: String?, spin: Double = 1.0, pulse: Double = 0.0) {
        if (a <= 0.01) return
        val c1 = if (visr) VISR else AMBER; val c2 = if (visr) VISR else STEEL
        // 0.1.18: the face grew to the other skins' orb size; the HUD keeps its mockup weight (k) and hugs the face instead of scaling with it
        val k = 1.45f
        sp.strokeCap = Paint.Cap.BUTT; sp.strokeWidth = 1.4f
        fun glowOn(c: Int, al: Double) { if (glowOn) sp.setShadowLayer(5f, 0f, 0f, withA(c, al)) else sp.clearShadowLayer() }
        val r1 = r + 12 * k + (pulse * 4 * k).toFloat(); val r2 = r + 18 * k
        sp.color = withA(c2, a); glowOn(c2, a); rf.set(x - r1, y - r1, x + r1, y + r1)
        for (i in 0 until 3) { val s = t * 0.35 * spin + i * 2 * PI / 3; cv.drawArc(rf, Math.toDegrees(s).toFloat(), Math.toDegrees(0.8).toFloat(), false, sp) }
        sp.color = withA(c1, a); glowOn(c1, a); rf.set(x - r2, y - r2, x + r2, y + r2)
        for (i in 0 until 4) { val s = -t * 0.2 * spin + i * PI / 2 + 0.3; cv.drawArc(rf, Math.toDegrees(s).toFloat(), Math.toDegrees(0.35).toFloat(), false, sp) }
        sp.strokeWidth = 1f
        for (i in 0 until 36) { val an = i * 2 * PI / 36; val big = i % 9 == 0; val len = if (big) 6 * k else 2.5f * k; sp.color = withA(c1, a * (if (big) 0.9 else 0.45))
            val ca = cos(an).toFloat(); val sa = sin(an).toFloat(); cv.drawLine(x + ca * (r2 + 4 * k), y + sa * (r2 + 4 * k), x + ca * (r2 + 4 * k + len), y + sa * (r2 + 4 * k + len), sp) }
        val b = min(r + 32 * k, w / 2 - 20f); val L = 9 * k; sp.color = withA(c2, a * 0.8); glowOn(c2, a * 0.8)
        for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) { path.rewind(); path.moveTo(x + sx * b, y + sy * (b - L)); path.lineTo(x + sx * b, y + sy * b); path.lineTo(x + sx * (b - L), y + sy * b); cv.drawPath(path, sp) }
        sp.clearShadowLayer()
        tp.typeface = Typeface.MONOSPACE; tp.textSize = 9 * k; tp.textAlign = Paint.Align.LEFT; tp.color = withA(c1, a * 0.85); cv.drawText(label1 ?: "SYS\u00B7NOMINAL", x + b - 40 * k, y - b - 5 * k, tp)
        tp.textAlign = Paint.Align.RIGHT; tp.color = withA(c2, a * 0.85); cv.drawText(label2 ?: "SECTOR 07", x - b + 40 * k, y + b + 12 * k, tp)
        sp.color = withA(c2, a * 0.5); cv.drawLine(14f, y, x - b - 6 * k, y, sp); cv.drawLine(x + b + 6 * k, y, w - 14f, y, sp)
        sp.strokeCap = Paint.Cap.ROUND
    }
}
