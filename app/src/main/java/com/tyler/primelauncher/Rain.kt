package com.tyler.primelauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** Wallpaper: code-rain glyphs, neon streaks, solid glow or off. Works in dp. */
class Rain(private val W: Float, private val H: Float, private val cy: Float, private val dpr: Float, val style: String, glyphSet: String,
           private val density: Float, speed: Float, private val rgb: Int, private val neon: Boolean) {
    private class Drop(var x: Float, var y: Float, val v: Float, val n: Int, val a: Float, val gi: IntArray, val c: Int, val h: Float)
    private val drops = ArrayList<Drop>()
    /** 0.1.6: glyph sprites in one atlas, and streak fades pre-rendered into a tiny strip texture (was a new LinearGradient per streak per frame). */
    private var atlas: Bitmap? = null; private val cells = ArrayList<android.graphics.Rect>()
    private var strip: Bitmap? = null; private val stripCols = HashMap<Int, android.graphics.Rect>()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG); private val dst = RectF()
    private var solid: RadialGradient? = null; private var floorG: LinearGradient? = null

    companion object {
        val SETS = mapOf("binary" to "01", "hex" to "0123456789ABCDEF")
        /** Original kana-like glyphs: 2-4 strokes on a 3x3 lattice (not a real script). */
        val KANA: List<List<FloatArray>> = run {
            val r = Rng(4242); val P = arrayOf(floatArrayOf(.2f, .15f), floatArrayOf(.5f, .12f), floatArrayOf(.8f, .15f), floatArrayOf(.2f, .5f), floatArrayOf(.5f, .5f), floatArrayOf(.8f, .5f), floatArrayOf(.25f, .88f), floatArrayOf(.5f, .88f), floatArrayOf(.78f, .85f))
            (0 until 40).map { val n = 2 + (r.next() * 3).toInt(); val g = ArrayList<FloatArray>()
                repeat(n) { val ai = (r.next() * 9).toInt(); var bi = (r.next() * 9).toInt(); if (ai == bi) bi = (ai + 4) % 9; val a = P[ai]; val b = P[bi]; val bend = ((r.next() - 0.5) * 0.5).toFloat()
                    g.add(floatArrayOf(a[0], a[1], b[0], b[1], (a[0] + b[0]) / 2 + bend, (a[1] + b[1]) / 2 - bend * 0.5f)) }
                if (r.next() < 0.5) g.forEach { q -> q[0] = 1 - q[0]; q[2] = 1 - q[2]; q[4] = 1 - q[4] }; g }
        }
        /** Same HSL -> RGB as the simulator's hsl2rgb. */
        fun hsl(h: Int, sat: Float = 1f, l: Float = 0.6f): Int { val a = sat * min(l, 1 - l)
            fun f(n: Int): Int { val k = (n + h / 30f) % 12f; return (255 * (l - a * maxOf(-1f, minOf(k - 3, 9 - k, 1f)))).roundToInt() }
            return Color.rgb(f(0), f(8), f(4)) }
    }

    init {
        val r = Rng(99); val set = SETS[glyphSet]; val ng = set?.length ?: KANA.size
        val sz = ceil(14 * dpr).toInt(); val sprites = ArrayList<Bitmap>()
        for (i in 0 until ng) { val b = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888); val c = Canvas(b); val q = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = rgb }
            if (set != null) { q.typeface = Typeface.MONOSPACE; q.textSize = 13 * dpr; q.textAlign = Paint.Align.CENTER; c.drawText(set[i].toString(), sz / 2f, sz / 2f - (q.descent() + q.ascent()) / 2, q) }
            else { q.style = Paint.Style.STROKE; q.strokeWidth = 1.3f * dpr; q.strokeCap = Paint.Cap.ROUND; val m = sz * 0.12f; val w = sz - 2 * m
                for (s in KANA[i]) { val path = android.graphics.Path(); path.moveTo(m + s[0] * w, m + s[1] * w); path.quadTo(m + s[4] * w, m + s[5] * w, m + s[2] * w, m + s[3] * w); c.drawPath(path, q) } }
            sprites.add(b) }
        if (sprites.isNotEmpty()) { val pad = 2; val stride = sz + pad * 2; val a = Bitmap.createBitmap(stride * sprites.size, stride, Bitmap.Config.ARGB_8888); val ac = Canvas(a)
            sprites.forEachIndexed { i, b -> val x = i * stride + pad; ac.drawBitmap(b, x.toFloat(), pad.toFloat(), null); cells.add(android.graphics.Rect(x, pad, x + sz, pad + sz)); b.recycle() }; atlas = a }
        if (style == "streaks") repeat((70 * density).roundToInt()) {
            val cols = intArrayOf(0xFF3EE8F0.toInt(), 0xFFFF3D81.toInt(), 0xFF3EE8F0.toInt(), 0xFFF7E733.toInt())
            drops.add(Drop((r.next() * W).toFloat(), (r.next() * H).toFloat(), ((30 + r.next() * 80) * speed).toFloat(), 0, (0.16 * (1.2 + r.next() * 1.4)).toFloat(), IntArray(0), cols[(r.next() * 4).toInt()], (30 + r.next() * 110).toFloat())) }
        else if (style == "glyphs") { val step = if (density > 1.3f) 11f else 15f; val prob = if (density < 0.8f) 0.3 else if (density > 1.3f) 0.72 else 0.5; var x = 4f
            while (x < W) { if (r.next() < prob) { val n = 6 + (r.next() * 12).toInt(); val gi = IntArray(n) { (r.next() * ng).toInt() }
                drops.add(Drop(x, (r.next() * H).toFloat(), ((18 + r.next() * 40) * speed).toFloat(), n, (0.06 + r.next() * 0.12).toFloat(), gi, 0, 0f)) }; x += step } }
    }

    /** One 3-px column per streak colour: transparent at the top fading to the colour at the bottom (same as the old gradient). */
    private fun buildStrip(): Bitmap {
        val colors = (if (rgb != 0) listOf(rgb) else drops.map { it.c }).distinct().ifEmpty { listOf(Color.WHITE) }; val hpx = 128
        val b = Bitmap.createBitmap(colors.size * 3, hpx, Bitmap.Config.ARGB_8888); val c = Canvas(b); val q = Paint()
        colors.forEachIndexed { i, col -> q.shader = LinearGradient(0f, 0f, 0f, hpx.toFloat(), Color.TRANSPARENT, col, Shader.TileMode.CLAMP); c.drawRect(i * 3f, 0f, i * 3f + 3, hpx.toFloat(), q)
            stripCols[col] = android.graphics.Rect(i * 3 + 1, 0, i * 3 + 2, hpx) } // sample the middle column only: no bleed between colours
        strip = b; return b
    }
    fun step(dt: Float) { for (d in drops) { d.y += d.v * dt; val top = if (style == "streaks") d.h else d.n * 14f; if (d.y - top > H) d.y = -10f } }

    fun draw(cv: Canvas, t: Double, dim: Float) {
        cv.drawColor(if (neon) 0xFF07060B.toInt() else Color.BLACK)
        when (style) {
            "solid" -> { val g = solid ?: RadialGradient(W / 2, cy, H * 0.75f, Color.argb(51, Color.red(rgb), Color.green(rgb), Color.blue(rgb)), Color.argb(10, Color.red(rgb), Color.green(rgb), Color.blue(rgb)), Shader.TileMode.CLAMP).also { solid = it }
                p.shader = g; p.alpha = (255 * dim).toInt().coerceIn(0, 255); cv.drawRect(0f, 0f, W, H, p); p.shader = null }
            "streaks" -> { val st = strip ?: buildStrip(); for (d in drops) { val src = stripCols[if (rgb != 0) rgb else d.c] ?: continue
                p.alpha = (min(1f, d.a * dim) * 255).toInt(); if (p.alpha < 3) continue; dst.set(d.x, d.y - d.h, d.x + 1, d.y); cv.drawBitmap(st, src, dst, p) } }
            "glyphs" -> { val at = atlas; val ng = cells.size; if (at != null && ng > 0) { val tt = floor(t * 3).toInt()
                for (d in drops) for (j in 0 until d.n) { p.alpha = (min(1f, d.a * dim * (j + 1) / d.n) * 255).toInt(); if (p.alpha < 3) continue
                    val y = d.y - (d.n - j) * 14 - 11; if (y < -14 || y > H) continue
                    dst.set(d.x - 1, y, d.x + 13, y + 14); cv.drawBitmap(at, cells[d.gi[((j + tt + d.x.toInt()) % d.n + d.n) % d.n] % ng], dst, p) } } }
        }
        if (neon && style != "off") { val g = floorG ?: LinearGradient(0f, H - 300, 0f, H, 0x00FF3D81, 0x14FF3D81, Shader.TileMode.CLAMP).also { floorG = it }
            p.shader = g; p.alpha = (255 * dim).toInt().coerceIn(0, 255); cv.drawRect(0f, H - 300, W, H, p); p.shader = null }
        p.alpha = 255
    }
    fun recycle() { atlas?.recycle(); atlas = null; strip?.recycle(); strip = null }
}
