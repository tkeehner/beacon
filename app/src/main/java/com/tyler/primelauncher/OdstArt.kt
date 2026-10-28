package com.tyler.primelauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** 0.1.17 ODST skin: PA bubbles, activity props, the rainy-city wallpaper and the 4-note chime. All original. */
object OdstArt {
    const val AMBER = 0xFFFFB23F.toInt(); const val STEEL = 0xFF6F9CCC.toInt(); const val VISR = 0xFFD4FF3A.toInt(); const val VERGIL = 0xFF3C9BFF.toInt()
    const val INK = 0xFF0A0D10.toInt()
    /** Chime note onsets (s): E5, C5, G5, D5. */
    val CHIME = doubleArrayOf(0.0, 0.15, 0.3, 0.45)
    fun chimePulse(s: Double): Double { var p = 0.0; for (d in CHIME) if (s > d) p += exp(-(s - d) * 9) * 0.5; return p }
    /** Seconds for a PA bubble's text: the chime, then typed at 40 chars/s. */
    fun revealS(text: String) = 0.62 + max(0.3, text.length / 40.0)

    private fun withA(c: Int, a: Double) = Color.argb((a.coerceIn(0.0, 1.0) * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))
    val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.MITER }
    val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path(); private val rf = RectF()
    private val sansB = Typeface.create("sans-serif", Typeface.BOLD); private val sans = Typeface.create("sans-serif", Typeface.NORMAL)
    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    const val LH = 17f; const val HEAD = 19f

    fun chamfer(p: Path, x: Float, y: Float, w: Float, h: Float, k: Float) { p.moveTo(x + k, y); p.lineTo(x + w, y); p.lineTo(x + w, y + h - k); p.lineTo(x + w - k, y + h); p.lineTo(x, y + h); p.lineTo(x, y + k); p.close() }
    fun bodyPaint(vergil: Boolean): Paint { tp.typeface = if (vergil) sans else sansB; tp.textSize = if (vergil) 14f else 13f; tp.textAlign = Paint.Align.LEFT; return tp }
    fun wrap(text: String, maxW: Float, vergil: Boolean): List<String> {
        val q = bodyPaint(vergil); val out = ArrayList<String>()
        for (para in text.split('\n')) { var line = ""
            for (w in para.split(' ')) { val tt = if (line.isEmpty()) w else "$line $w"; if (q.measureText(tt) > maxW && line.isNotEmpty()) { out.add(line); line = w } else line = tt }
            out.add(line) }
        return out
    }
    fun boxH(lines: Int) = HEAD + 10f + lines * LH + 6f
    fun chimeDots(cv: Canvas, x: Float, y: Float, s: Double, col: Int, a: Double) {
        for (i in 0 until 4) { val on = s >= CHIME[i] && s < CHIME[i] + 0.5; fp.color = withA(col, a * (if (on) 1.0 else 0.25)); cv.drawCircle(x + i * 8f, y, if (on) 2.8f else 2.1f, fp) }
    }
    /**
     * Amber PA panel (mockup bubble()): chamfered box, a header bar with the department and the chime dots, then the body
     * typed out after the chime. [tailX] points the tail at the face; [tailUp] when the box sits below it.
     */
    fun box(cv: Canvas, r: RectF, head: String, lines: List<String>, textLen: Int, s: Double, a: Double, vergil: Boolean, tailX: Float, tailUp: Boolean, glow: Boolean, typed: Boolean = true) {
        if (a <= 0.01) return
        val col = if (vergil) VERGIL else AMBER
        path.rewind(); chamfer(path, r.left, r.top, r.width(), r.height(), 10f)
        if (tailUp) { path.moveTo(tailX - 7, r.top); path.lineTo(tailX, r.top - 9); path.lineTo(tailX + 7, r.top) } else { path.moveTo(tailX - 7, r.bottom); path.lineTo(tailX, r.bottom + 9); path.lineTo(tailX + 7, r.bottom) }
        fp.color = withA(if (vergil) 0xFF061228.toInt() else 0xFF0A0E14.toInt(), 0.92 * a); cv.drawPath(path, fp)
        sp.strokeWidth = 1.3f; sp.color = withA(col, a); if (glow) sp.setShadowLayer(8f, 0f, 0f, withA(col, a)) else sp.clearShadowLayer(); cv.drawPath(path, sp); sp.clearShadowLayer()
        // header
        fp.color = withA(col, a); cv.drawRect(r.left + 1.5f, r.top + 1.5f, r.right - 1.5f, r.top + HEAD, fp)
        tp.typeface = mono; tp.textSize = 10.5f; tp.textAlign = Paint.Align.LEFT; tp.color = withA(INK, a); cv.drawText(head, r.left + 9f, r.top + 14f, tp)
        chimeDots(cv, r.right - 40f, r.top + 10.5f, s, INK, a)
        // body
        val n = if (!typed) textLen else (Ez.clamp((s - 0.62) / max(0.3, textLen / 40.0), 0.0, 1.0) * textLen).toInt()
        val q = bodyPaint(vergil); q.color = withA(if (vergil) 0xFFCFE6FF.toInt() else 0xFFF3F1EA.toInt(), a); var used = 0
        lines.forEachIndexed { i, l -> val take = (n - used).coerceIn(0, l.length); used += l.length + 1; if (take > 0) cv.drawText(l, 0, take, r.left + 11f, r.top + HEAD + 17f + i * LH, q) }
    }
    /** Short activity caption in a PA panel above the face ([s] = seconds since it started, [dur] = how long it shows). */
    fun caption(a: Art, g: Geo, text: String, s: Double, dur: Double, head: String = "\u25A3 MUNICIPAL NOTICE") {
        if (s < 0 || s > dur) return
        val al = Ez.sst(0.0, 0.12, s) * (1 - Ez.sst(dur - 0.3, dur, s))
        val w = min(g.W - 40f, 280f); val lines = wrap(text, w - 22f, false); val h = boxH(lines.size)
        var y = g.cy - g.r - 52f - h; if (y < g.top + 4) y = g.top + 4f // 0.1.18: hugs the (now orb-sized) face's HUD
        rf.set(g.cx - w / 2, y, g.cx + w / 2, y + h); box(a.cv, RectF(rf), head, lines, text.length, s, al, false, g.cx, false, a.glowOn)
    }

    // ---------- activity props (mockup units scaled by u = dp per mockup px) ----------
    fun feed(cv: Canvas, x: Float, y: Float, w: Float, h: Float, i: Int, t: Double, a: Double) {
        if (a <= 0.01) return; val u = w / 92f
        cv.save(); cv.clipRect(x, y, x + w, y + h); fp.color = withA(0xFF0B1118.toInt(), a); cv.drawRect(x, y, x + w, y + h, fp)
        val base = withA(0xFF1A2533.toInt(), a)
        when (i) {
            0 -> { fp.color = base; cv.drawRect(x, y + h * 0.62f, x + w, y + h, fp); fp.color = withA(0xFF2A3A4C.toInt(), a); cv.drawRect(x, y + h * 0.75f, x + w, y + h * 0.75f + 2 * u, fp)
                val cx = x + (((t * 30) % (92 + 30)) - 20).toFloat() * u; fp.color = withA(0xFF9FB0C4.toInt(), a); cv.drawRect(cx, y + h * 0.62f, cx + 18 * u, y + h * 0.62f + 7 * u, fp); fp.color = withA(0xFFFFD27A.toInt(), a); cv.drawRect(cx + 17 * u, y + h * 0.64f, cx + 19 * u, y + h * 0.64f + 2 * u, fp) }
            1 -> { fp.color = base; cv.drawRect(x + w * 0.35f, y + h * 0.2f, x + w * 0.65f, y + h * 0.9f, fp); fp.color = withA(if (sin(t * 2) > 0) 0xFFFFCF7A.toInt() else 0xFF7A6A40.toInt(), a); cv.drawRect(x + w * 0.46f, y + h * 0.08f, x + w * 0.54f, y + h * 0.08f + 4 * u, fp) }
            2 -> { fp.color = base; cv.drawRect(x, y + h * 0.7f, x + w, y + h, fp); fp.color = withA(0xFFA0BEDC.toInt(), 0.35 * a)
                for (k in 0 until 12) { val ry = y + (((t * 90 + k * 23) % 58).toFloat()) * u; val rx = x + Ez.hash(k.toDouble(), 0.0, 0.0).toFloat() * w; cv.drawRect(rx, ry, rx + 1, ry + 5 * u, fp) }
                fp.color = withA(0xFF2B3A4D.toInt(), a); cv.drawRect(x + w * 0.2f, y + h * 0.35f, x + w * 0.2f + 4 * u, y + h * 0.7f, fp); fp.color = withA(0xFFFFCF7A.toInt(), a); cv.drawRect(x + w * 0.2f - 2 * u, y + h * 0.32f, x + w * 0.2f + 6 * u, y + h * 0.32f + 3 * u, fp) }
            else -> { fp.color = base; cv.drawRect(x, y + h * 0.68f, x + w, y + h, fp); fp.color = withA(0xFF26323F.toInt(), a); cv.drawRect(x + w * 0.75f, y + h * 0.3f, x + w * 0.95f, y + h * 0.68f, fp)
                val k = Ez.clamp((t - 4.2) / 1.6, 0.0, 1.0); if (k > 0 && k < 1) { val cx = x - 10 * u + (k * (w + 20 * u)).toFloat(); val cy = y + h * 0.66f; fp.color = withA(0xFFE9E9E9.toInt(), a) // the cat
                    rf.set(cx - 6 * u, cy - 3 * u, cx + 6 * u, cy + 3 * u); cv.drawOval(rf, fp); cv.drawCircle(cx + 6 * u, cy - 3 * u, 2.6f * u, fp); cv.drawRect(cx - 8 * u, cy - 4 * u, cx - 6.5f * u, cy, fp) } }
        }
        fp.color = withA(Color.WHITE, 0.05 * a); for (k in 0 until 6) { val ly = y + (((t * 40 + k * 13) % 58).toFloat()) * u; cv.drawRect(x, ly, x + w, ly + 1, fp) }
        cv.restore()
        sp.strokeWidth = 1f; sp.color = withA(STEEL, a); cv.drawRect(x + 0.5f, y + 0.5f, x + w - 0.5f, y + h - 0.5f, sp)
        tp.typeface = Typeface.MONOSPACE; tp.textSize = 8.5f * u.coerceAtLeast(1f); tp.textAlign = Paint.Align.LEFT; tp.color = withA(0xFFCFE0F5.toInt(), a); cv.drawText("CAM 0${i + 1}", x + 4 * u, y + 10 * u, tp)
        if (sin(t * 5) > 0) { fp.color = withA(0xFFFF4B4B.toInt(), a); cv.drawCircle(x + w - 8 * u, y + 7 * u, 2.4f * u, fp) }
    }
    fun brackets(cv: Canvas, x: Float, y: Float, w: Float, h: Float, a: Double, glow: Boolean, col: Int = AMBER, L: Float = 9f) {
        if (a <= 0.01) return; sp.strokeWidth = 2f; sp.color = withA(col, a); if (glow) sp.setShadowLayer(8f, 0f, 0f, withA(col, a))
        for ((px, py, sx, sy) in listOf(floatArrayOf(x, y, 1f, 1f), floatArrayOf(x + w, y, -1f, 1f), floatArrayOf(x + w, y + h, -1f, -1f), floatArrayOf(x, y + h, 1f, -1f))) {
            path.rewind(); path.moveTo(px, py + sy * L); path.lineTo(px, py); path.lineTo(px + sx * L, py); cv.drawPath(path, sp) }
        sp.clearShadowLayer()
    }
    private operator fun FloatArray.component4() = this[3]
    /** Mockup carX: four cars queue at the stop line, then go on green. In mockup px relative to the road's left edge. */
    fun carX(i: Int, t: Double, green: Double, stopX: Double): Double { val v = 58.0; val x0 = -24.0 - i * 40; val q = stopX - 16 - i * 30
        if (t < green) return min(x0 + v * t, q); val xq = min(x0 + v * green, q); val s = max(0.0, t - green - i * 0.18); return xq + v * 1.3 * s * min(1.0, s * 1.5 + 0.2) }
    /** Road + light + queued cars. [ox] = screen x of mockup x 0, [roadY] in dp, [u] dp per mockup px. */
    fun traffic(cv: Canvas, ox: Float, roadY: Float, u: Float, w: Float, h: Float, t: Double, green: Double, a: Double, glow: Boolean) {
        if (a <= 0.01) return; val X = { m: Double -> ox + (m * u).toFloat() }; val stop = 135.0 + 30
        fp.color = withA(0xFF11161D.toInt(), a); cv.drawRect(0f, roadY - 14 * u, w, roadY + 16 * u, fp); cv.drawRect(X(135.0 + 46), roadY + 10 * u, X(135.0 + 72), h, fp)
        sp.strokeWidth = 1f; sp.color = withA(0xFFFFD278.toInt(), 0.5 * a); sp.pathEffect = DashPathEffect(floatArrayOf(8 * u, 8 * u), 0f); cv.drawLine(0f, roadY + u, w, roadY + u, sp); sp.pathEffect = null
        fp.color = withA(0xFFD8DDE4.toInt(), a); cv.drawRect(X(stop), roadY - 14 * u, X(stop) + 2 * u, roadY, fp)
        val px = X(stop + 4); val py = roadY - 68 * u; fp.color = withA(0xFF2A313A.toInt(), a); cv.drawRect(px, py, px + 3 * u, py + 52 * u, fp)
        rf.set(px - 5 * u, py - 30 * u, px + 8 * u, py); fp.color = withA(0xFF0C0F13.toInt(), a); cv.drawRoundRect(rf, 3 * u, 3 * u, fp)
        val g = t >= green
        for ((k, pair) in listOf(0xFFFF3B30.toInt() to !g, 0xFFFFB23F.toInt() to (abs(t - green) < 0.25), 0xFF3DFF7A.toInt() to g).withIndex()) { val (col, on) = pair
            fp.color = withA(col, a * (if (on) 1.0 else 0.15)); if (on && glow) fp.setShadowLayer(10f, 0f, 0f, withA(col, a)); cv.drawCircle(px + 1.5f * u, py - (24 - k * 9) * u, 3 * u, fp); fp.clearShadowLayer() }
        val cols = intArrayOf(0xFF8FA6C0.toInt(), 0xFFC0A98F.toInt(), 0xFF9FC09F.toInt(), 0xFFB08FC0.toInt())
        for (i in 0 until 4) { val x = X(carX(i, t, green, stop)); if (x < -40 * u || x > w + 30 * u) continue
            rf.set(x, roadY - 11 * u, x + 24 * u, roadY - 2 * u); fp.color = withA(cols[i], a); cv.drawRoundRect(rf, 3 * u, 3 * u, fp); fp.color = withA(0xFF1B222B.toInt(), a); cv.drawRect(x + 6 * u, roadY - 14 * u, x + 18 * u, roadY - 10 * u, fp)
            fp.color = withA(0xFFFFE7A8.toInt(), a); cv.drawRect(x + 23 * u, roadY - 9 * u, x + 25 * u, roadY - 7 * u, fp); fp.color = withA(0xFFFF5A5A.toInt(), a); cv.drawRect(x - u, roadY - 9 * u, x + u, roadY - 7 * u, fp) }
        if (t < green) { val k = ((t + 0.4) % 2.2) / 2.2; val y = roadY + 10 * u + (k * (h - roadY - 10 * u)).toFloat(); rf.set(X(135.0 + 52), y, X(135.0 + 62), y + 20 * u); fp.color = withA(0xFFD0C080.toInt(), a); cv.drawRoundRect(rf, 3 * u, 3 * u, fp) }
    }
    fun truckX(t: Double): Double = if (t < 2.8) -80 + t * ((135 - 60 + 80) / 2.8) else if (t < 4.4) 135.0 - 60 else 135.0 - 60 + (t - 4.4) * 70
    fun truck(cv: Canvas, ox: Float, roadY: Float, u: Float, w: Float, t: Double, a: Double, glow: Boolean) {
        if (a <= 0.01) return; val x = ox + (truckX(t) * u).toFloat(); val y = roadY - 2 * u
        fp.color = withA(0xFF11161D.toInt(), a); cv.drawRect(0f, roadY - 14 * u, w, roadY + 16 * u, fp)
        val lift = if (t > 3) Ez.bump(t - 3, 1.3) else 0.0; val bx = ox + (135 - 6) * u; val by = roadY - 14 * u - (lift * 26).toFloat() * u
        cv.save(); cv.translate(bx, by); cv.rotate(Math.toDegrees(-lift * 1.4).toFloat()); rf.set(-6 * u, -14 * u, 6 * u, 0f); fp.color = withA(0xFF3C7A46.toInt(), a); cv.drawRoundRect(rf, 2 * u, 2 * u, fp); cv.restore()
        rf.set(x, y - 26 * u, x + 48 * u, y - 4 * u); fp.color = withA(0xFFD9D4C4.toInt(), a); cv.drawRoundRect(rf, 3 * u, 3 * u, fp); fp.color = withA(0xFFB8B2A0.toInt(), a); for (i in 0 until 4) cv.drawRect(x + (6 + i * 10) * u, y - 24 * u, x + (8 + i * 10) * u, y - 6 * u, fp)
        rf.set(x + 48 * u, y - 20 * u, x + 64 * u, y - 4 * u); fp.color = withA(0xFFE7C24A.toInt(), a); cv.drawRoundRect(rf, 3 * u, 3 * u, fp); fp.color = withA(0xFF7FB2DF.toInt(), a); cv.drawRect(x + 53 * u, y - 17 * u, x + 61 * u, y - 11 * u, fp)
        fp.color = withA(0xFF121212.toInt(), a); for (wx in floatArrayOf(10f, 38f, 56f)) cv.drawCircle(x + wx * u, y - 3 * u, 4.5f * u, fp)
        val bl = sin(t * 9) > 0; fp.color = withA(if (bl) AMBER else 0xFF5A3A10.toInt(), a); if (bl && glow) fp.setShadowLayer(10f, 0f, 0f, withA(AMBER, a)); cv.drawRect(x + 54 * u, y - 24 * u, x + 59 * u, y - 21 * u, fp); fp.clearShadowLayer()
        for (k in 0 until 5) { val s = (t * 1.6 + k / 5.0) % 1; fp.color = withA(0xFF9AA3AD.toInt(), a * (1 - s) * 0.35); cv.drawCircle(x - (4 + s * 22).toFloat() * u, y - (6 + s * 10 - sin(k.toDouble()) * 3).toFloat() * u, (2 + s * 5).toFloat() * u, fp) }
    }
    fun thumb(cv: Canvas, x: Float, y: Float, s: Float, a: Double) {
        if (a <= 0.01) return; cv.save(); cv.translate(x, y); cv.scale(s, s); fp.color = withA(Color.WHITE, a)
        rf.set(-7f, -2f, 7f, 11f); cv.drawRoundRect(rf, 3f, 3f, fp); rf.set(-3f, -13f, 3f, 0f); cv.drawRoundRect(rf, 3f, 3f, fp); fp.color = withA(OdstFace.FACE, a); cv.drawRect(-9f, 0f, -6f, 11f, fp); cv.restore()
    }
    fun staticFx(cv: Canvas, x: Float, y: Float, w: Float, h: Float, a: Double, t: Double) {
        if (a <= 0.01) return; val f = floor(t * 24)
        for (i in 0 until 90) { val hx = Ez.hash(i.toDouble(), f, 1.0); val hy = Ez.hash(i + 3.0, f, 1.0); fp.color = withA(if (hx > 0.5) Color.WHITE else 0xFF7F93AA.toInt(), a)
            val px = x + (hx * w).toFloat(); val py = y + (hy * h).toFloat(); cv.drawRect(px, py, px + 2 + (Ez.hash(i.toDouble(), 7.0, 0.0) * 10).toFloat(), py + 1.2f, fp) }
    }
}

/**
 * ODST wallpaper: a rainy night skyline with lit windows, falling rain and drops on the glass (mockup bg()/glass()).
 * The skyline is rendered once into a bitmap per mode; VISR night mode swaps it for yellow-green building outlines and
 * scanlines. Only rain, drops, antenna lights and the VISR sweep move.
 */
class CityBackdrop(private val W: Float, private val H: Float, private val dpr: Float, private val density: Float, private val speed: Float) {
    private class B(val x: Float, val w: Float, val h: Float, val seed: Double, val ant: Boolean)
    private class Streak(val x: Float, val y: Float, val l: Float, val v: Float, val a: Float)
    private class Drop(val x: Float, val y: Float, val s: Float, val v: Float, val t0: Float)
    private val u = W / 270f
    private val far = ArrayList<B>(); private val near = ArrayList<B>(); private val streaks = ArrayList<Streak>(); private val drops = ArrayList<Drop>()
    private val horizon = H * 440f / 600f
    private var day: Bitmap? = null; private var night: Bitmap? = null; private var dropSprite: Bitmap? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG); private val dst = RectF()
    private val q = 0.6f // bitmaps at 60% of screen resolution: soft city, small memory
    var visr = false; private var visrK = 0f; private var sweepT = -9.0; private var lastT = 0.0

    init {
        val r = Rng(77); var x = -10f
        while (x < 270 + 10) { val w = (14 + r.next() * 26).toFloat(); val h = (90 + r.next() * 170).toFloat(); far.add(B(x, w, h, r.next() * 1000, false)); x += w + 2 + (r.next() * 4).toFloat() }
        x = -6f; while (x < 270 + 10) { val w = (22 + r.next() * 34).toFloat(); val h = (50 + r.next() * 120).toFloat(); near.add(B(x, w, h, r.next() * 1000, r.next() < 0.3)); x += w + (r.next() * 3).toFloat() }
        repeat((46 * density).toInt().coerceAtLeast(8)) { drops.add(Drop((r.next() * W).toFloat(), (r.next() * H).toFloat(), ((1.2 + r.next() * 3.2) * u).toFloat(), if (r.next() < 0.25) ((18 + r.next() * 40) * u).toFloat() else 0f, (r.next() * 6).toFloat())) }
        repeat((110 * density).toInt().coerceAtLeast(20)) { streaks.add(Streak((r.next() * W * 1.3).toFloat(), (r.next() * H).toFloat(), ((8 + r.next() * 16) * u).toFloat(), ((420 + r.next() * 260) * u * speed).toFloat(), (0.12 + r.next() * 0.22).toFloat())) }
    }
    private fun mx(m: Float) = m * u
    private fun my(m: Float) = horizon + (m - 440f) * u
    private fun render(outline: Boolean): Bitmap {
        val bw = (W * dpr * q).toInt().coerceAtLeast(1); val bh = (H * dpr * q).toInt().coerceAtLeast(1)
        val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888); val c = Canvas(b); c.scale(dpr * q, dpr * q); val f = Paint(Paint.ANTI_ALIAS_FLAG)
        if (outline) {
            c.drawColor(0xFF010301.toInt()); f.style = Paint.Style.STROKE; f.strokeWidth = 1f; f.color = OdstArt.VISR; f.setShadowLayer(6f, 0f, 0f, OdstArt.VISR)
            for ((arr, base, al) in listOf(Triple(far, 420f, 0.35f), Triple(near, 480f, 0.9f))) { f.alpha = (255 * al).toInt(); for (bb in arr) c.drawRect(mx(bb.x) + 0.5f, my(base) - bb.h * u + 0.5f, mx(bb.x + bb.w) - 0.5f, H + 2, f) }
            f.alpha = 128; c.drawLine(0f, my(480f) + 0.5f, W, my(480f) + 0.5f, f)
            return b
        }
        f.shader = LinearGradient(0f, 0f, 0f, H, intArrayOf(0xFF03060C.toInt(), 0xFF0A1422.toInt(), 0xFF1C1A1E.toInt(), 0xFF070809.toInt()), floatArrayOf(0f, 0.55f, 0.78f, 1f), Shader.TileMode.CLAMP); c.drawRect(0f, 0f, W, H, f)
        f.shader = RadialGradient(W / 2, horizon, 260 * u, Color.argb(46, 255, 150, 60), Color.argb(0, 255, 150, 60), Shader.TileMode.CLAMP); c.drawRect(0f, 0f, W, H, f); f.shader = null
        fun layer(arr: List<B>, base: Float, col: Int, winA: Double, dk: Double) { for (bb in arr) { val y = my(base) - bb.h * u; f.color = col; c.drawRect(mx(bb.x), y, mx(bb.x + bb.w), H, f)
            if (bb.ant) c.drawRect(mx(bb.x + bb.w / 2 - 0.5f), y - 14 * u, mx(bb.x + bb.w / 2 + 0.5f), y, f)
            val cols = (bb.w / 5).toInt(); val rows = (bb.h / 7).toInt()
            for (i in 0 until cols) for (j in 0 until rows) { val hv = Ez.hash(bb.seed, i.toDouble(), j.toDouble()); if (hv > winA) continue
                f.color = if (hv < winA * 0.25) Color.argb((255 * 0.5 * dk).toInt(), 150, 190, 255) else Color.argb((255 * 0.65 * dk).toInt(), 255, (170 + hv * 60).toInt(), 90)
                c.drawRect(mx(bb.x + 2 + i * 5), y + (4 + j * 7) * u, mx(bb.x + 4 + i * 5), y + (7 + j * 7) * u, f) } } }
        layer(far, 420f, 0xFF0B1220.toInt(), 0.33, 0.55); layer(near, 480f, 0xFF05080C.toInt(), 0.22, 1.0)
        f.shader = LinearGradient(0f, horizon, 0f, H, Color.argb(26, 120, 140, 170), Color.argb(128, 0, 0, 0), Shader.TileMode.CLAMP); c.drawRect(0f, horizon, W, H, f); f.shader = null
        return b
    }
    private fun sprite(): Bitmap { val s = (24 * dpr).toInt().coerceAtLeast(4); val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val c = Canvas(b); val f = Paint(Paint.ANTI_ALIAS_FLAG)
        f.shader = RadialGradient(s * 0.35f, s * 0.35f, s / 2f, intArrayOf(Color.argb(90, 255, 255, 255), Color.argb(31, 180, 200, 230), Color.argb(72, 255, 255, 255)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawOval(s * 0.1f, 0f, s * 0.9f, s.toFloat(), f); return b }
    /** Switch VISR night mode; a sweep line rolls down the screen as it changes. */
    fun setVisr(on: Boolean, t: Double, animate: Boolean) { if (on == visr) return; visr = on; sweepT = if (animate) t else -9.0; if (!animate) visrK = if (on) 1f else 0f }
    fun draw(cv: Canvas, t: Double, dim: Float, rainMul: Float, glow: Boolean) {
        val dt = (t - lastT).coerceIn(0.0, 0.1); lastT = t
        val target = if (visr) 1f else 0f; val sw = t - sweepT
        visrK = if (sw in 0.0..0.9) (if (visr) Ez.sst(0.0, 0.9, sw).toFloat() else 1 - Ez.sst(0.0, 0.9, sw).toFloat()) else target
        if (dt <= 0.0 && sw > 0.9) visrK = target
        val a = (dim * 255).toInt().coerceIn(0, 255)
        if (visrK < 1f) { val b = day ?: render(false).also { day = it }; p.alpha = a; dst.set(0f, 0f, W, H); cv.drawBitmap(b, null, dst, p) }
        if (visrK > 0f) { val b = night ?: render(true).also { night = it }; p.alpha = (a * visrK).toInt(); dst.set(0f, 0f, W, H); cv.drawBitmap(b, null, dst, p) }
        // antenna lights
        for (bb in near) if (bb.ant && sin(t * 3 + bb.seed) > 0.2) { p.color = Color.argb((230 * dim * (1 - visrK)).toInt(), 255, 60, 50); val x = mx(bb.x + bb.w / 2 - 1); val y = my(480f) - bb.h * u - 15 * u; cv.drawRect(x, y, x + 2 * u, y + 2 * u, p) }
        // falling rain
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f; val rm = rainMul.coerceIn(0.3f, 2.2f)
        for (s in streaks) { val y = ((s.y + t * s.v) % (H + 30 * u)).toFloat() - 20 * u; val x = (((s.x - t * s.v * 0.18) % (W * 1.3)) + W * 1.3).toFloat() % (W * 1.3f)
            p.color = if (visrK > 0.5f) OdstArt.VISR else 0xFF9FB8D8.toInt(); p.alpha = (255 * s.a * dim * rm * (if (visrK > 0.5f) 0.7f else 1f)).toInt().coerceIn(0, 255); if (p.alpha < 3) continue
            cv.drawLine(x, y, x - s.l * 0.18f, y + s.l, p) }
        p.style = Paint.Style.FILL
        // drops on the glass
        val spr = dropSprite ?: sprite().also { dropSprite = it }; val ga = (1 - 0.6f * visrK) * dim * min(1.4f, rm)
        for (d in drops) { var y = d.y; if (d.v > 0) { val s = ((t + d.t0) % 7); y = ((d.y + s * d.v) % H).toFloat()
                p.style = Paint.Style.STROKE; p.strokeWidth = d.s * 0.6f; p.color = 0xFFCFE0FF.toInt(); p.alpha = (255 * 0.12 * ga).toInt().coerceIn(0, 255); cv.drawLine(d.x, y - d.s, d.x + sin(y * 0.05f) * 1.5f, (y - d.s - s * d.v * 0.35).toFloat(), p); p.style = Paint.Style.FILL }
            p.alpha = (255 * ga).toInt().coerceIn(0, 255); dst.set(d.x - d.s * 0.8f, y - d.s, d.x + d.s * 0.8f, y + d.s); cv.drawBitmap(spr, null, dst, p) }
        // VISR scanlines + the sweep line
        if (visrK > 0.01f) { p.color = OdstArt.VISR; p.alpha = (255 * 0.06 * visrK * dim).toInt(); var y = ((t * 40) % 4).toFloat(); while (y < H) { cv.drawRect(0f, y, W, y + 1, p); y += 4f } }
        if (sw in 0.0..0.9) { val y = (sw / 0.9 * H).toFloat(); p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = OdstArt.VISR; p.alpha = (255 * dim).toInt(); if (glow) p.setShadowLayer(14f, 0f, 0f, OdstArt.VISR); cv.drawLine(0f, y, W, y, p); p.clearShadowLayer(); p.style = Paint.Style.FILL }
        p.alpha = 255
    }
    fun recycle() { day?.recycle(); day = null; night?.recycle(); night = null; dropSprite?.recycle(); dropSprite = null }
}

/** The original 4-note civic chime (E5 -> C5 -> G5 -> D5, soft bell partials). Plays only with the ringer on normal. */
object Chime {
    const val RATE = 22050
    private val NOTES = doubleArrayOf(659.25, 523.25, 783.99, 587.33)
    private val PARTIALS = arrayOf(doubleArrayOf(1.0, 0.22), doubleArrayOf(2.01, 0.06), doubleArrayOf(3.0, 0.025))
    /** Mono 16-bit PCM, ~1.4 s. Pure (unit-tested). */
    fun samples(rate: Int = RATE): ShortArray {
        val n = (rate * 1.4).toInt(); val out = DoubleArray(n)
        NOTES.forEachIndexed { i, f -> val t0 = i * 0.15
            for (j in 0 until n) { val tt = j.toDouble() / rate - t0; if (tt < 0 || tt > 0.9) continue
                val env = if (tt < 0.008) tt / 0.008 else exp(-(tt - 0.008) / 0.9 * 9.2)
                var v = 0.0; for (pp in PARTIALS) v += pp[1] * sin(2 * PI * f * pp[0] * tt); out[j] += v * env } }
        val peak = out.maxOf { abs(it) }.coerceAtLeast(1e-6); val k = 0.42 / peak
        return ShortArray(n) { (out[it] * k * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort() }
    }
    private var pcm: ShortArray? = null; private var lastMs = 0L
    fun allowed(ctx: Context): Boolean { val am = ctx.getSystemService(AudioManager::class.java) ?: return false
        return am.ringerMode == AudioManager.RINGER_MODE_NORMAL && am.mode == AudioManager.MODE_NORMAL }
    fun play(ctx: Context) {
        val now = android.os.SystemClock.uptimeMillis(); if (now - lastMs < 1200 || !allowed(ctx)) return; lastMs = now
        runCatching {
            val data = pcm ?: samples().also { pcm = it }
            val tr = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(data.size * 2).build()
            tr.write(data, 0, data.size); tr.play()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { tr.release() } }, 1700)
        }
    }
}
