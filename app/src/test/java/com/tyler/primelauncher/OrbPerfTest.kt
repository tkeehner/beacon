package com.tyler.primelauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.IdentityHashMap

/**
 * Per-frame draw cost of the orb + wallpaper, counted on a recording Canvas: draw calls, distinct textures (each one is a
 * separate GPU upload / batch break on the phone) and fresh Shader objects (allocations + RenderThread program setup).
 * Prints a PERF line for before/after comparisons and guards against regressions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrbPerfTest {
    private class Counting(b: Bitmap) : Canvas(b) {
        var bitmaps = 0; var other = 0; val tex = IdentityHashMap<Bitmap, Boolean>(); val shaders = IdentityHashMap<Shader, Boolean>()
        private fun sh(p: Paint?) { p?.shader?.let { shaders[it] = true } }
        override fun drawBitmap(b: Bitmap, src: Rect?, dst: RectF, p: Paint?) { bitmaps++; tex[b] = true; sh(p); super.drawBitmap(b, src, dst, p) }
        override fun drawCircle(cx: Float, cy: Float, r: Float, p: Paint) { other++; sh(p); super.drawCircle(cx, cy, r, p) }
        override fun drawRect(l: Float, t: Float, r: Float, b: Float, p: Paint) { other++; sh(p); super.drawRect(l, t, r, b, p) }
        override fun drawPath(path: android.graphics.Path, p: Paint) { other++; sh(p); super.drawPath(path, p) }
        override fun drawLine(a: Float, b: Float, c: Float, d: Float, p: Paint) { other++; sh(p); super.drawLine(a, b, c, d, p) }
        override fun drawArc(l: Float, t: Float, r: Float, b: Float, s: Float, w: Float, u: Boolean, p: Paint) { other++; sh(p); super.drawArc(l, t, r, b, s, w, u, p) }
        override fun drawOval(l: Float, t: Float, r: Float, b: Float, p: Paint) { other++; sh(p); super.drawOval(l, t, r, b, p) }
    }
    data class Cost(val draws: Int, val bitmaps: Int, val textures: Int, val newShadersPerFrame: Double, val ms: Double)

    private fun measure(neon: Boolean, wp: String, asleep: Boolean): Cost {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>(); val d = 2.625f
        val W = 411f; val H = 914f; val cy = 430f
        val orb = Orb(W / 2, cy, if (neon) 96f else 102f, if (neon) Skin.NEON else Skin.MATRIX, d, 1.0, null, true)
        val rain = Rain(W, H, cy, d, wp, "binary", 1f, 1f, if (neon) 0xFF3EE8F0.toInt() else 0xFF3DFF7A.toInt(), neon)
        if (asleep) orb.sleep.sleepNow()
        val b = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        fun frame(c: Canvas) { orb.step(1 / 60.0); rain.step(1 / 60f); c.save(); c.scale(d, d); rain.draw(c, orb.t, 1f); orb.draw(c); c.restore() }
        val warm = Canvas(b); repeat(20) { frame(warm) }
        val n = 30; val seen = IdentityHashMap<Shader, Boolean>(); var newSh = 0; var last: Counting? = null
        val t0 = System.nanoTime()
        repeat(n) { val c = Counting(b); frame(c); for (s in c.shaders.keys) if (seen.put(s, true) == null) newSh++; last = c }
        val ms = (System.nanoTime() - t0) / 1e6 / n
        val c = last!!; orb.recycle(); rain.recycle()
        return Cost(c.bitmaps + c.other, c.bitmaps, c.tex.size, newSh.toDouble() / n, ms)
    }

    /** 0.1.13 (0.1.14 values): personality (idle saccades/perks/fidgets, THINK surges + glitch storms, touch) must stay allocation-free and add no draws. */
    @Test fun personalityAddsNoPerFrameCost() {
        for (neon in listOf(false, true)) {
            val d = 2.625f; val orb = Orb(205f, 430f, if (neon) 96f else 102f, if (neon) Skin.NEON else Skin.MATRIX, d, 1.0, null, true)
            val b = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888); val warm = Canvas(b)
            fun frame(c: Canvas) { orb.step(1 / 60.0); c.save(); c.scale(d, d); orb.draw(c); c.restore() }
            // Warm up every path once (idle, touch, think, listen, back to idle) so first-use caches are filled.
            repeat(600) { i -> orb.mode = when (i / 120) { 1 -> Mode.THINK; 2 -> Mode.LISTEN; else -> Mode.IDLE }; if (i == 60) orb.touchDown(400f, 300f); frame(warm) }
            val seen = IdentityHashMap<Shader, Boolean>(); var newSh = 0; var late = 0; var maxDraws = 0; val n = 1800
            // 0.1.14: also cycles SUMMON / LISTEN and a quick wake so the bigger kicks, surges and glitch storms are all covered.
            var maxTex = 0
            for (i in 0 until n) { orb.mode = when ((i / 150) % 5) { 1 -> Mode.THINK; 3 -> if (i % 300 < 225) Mode.SUMMON else Mode.LISTEN; else -> Mode.IDLE }; if (i % 200 == 30) orb.touchDown(100f + i % 300, 600f)
                if (i == 1300) { orb.sleep.sleepNow(); orb.wake() }
                val c = Counting(b); frame(c); for (s in c.shaders.keys) if (seen.put(s, true) == null) { newSh++; if (i >= n / 2) late++ }; maxDraws = maxOf(maxDraws, c.bitmaps + c.other); maxTex = maxOf(maxTex, c.tex.size) }
            println("PERF personality ${if (neon) "neon" else "matrix"}: maxDraws=$maxDraws textures=$maxTex newShaders/frame=${"%.3f".format(newSh.toDouble() / n)} secondHalf=$late looks=${orb.curious.looks}")
            assertTrue("still one orb atlas: $maxTex textures", maxTex <= 1)
            assertTrue("no steady per-frame shader allocation", newSh.toDouble() / n < 0.1)
            assertTrue("rim-glow cache settles: $late new shaders in the second half", late.toDouble() / (n / 2) < 0.02)
            orb.recycle()
        }
    }

    @Test fun drawCostPerFrame() {
        val cases = listOf(Triple(true, "streaks", false), Triple(true, "streaks", true), Triple(false, "glyphs", false), Triple(false, "glyphs", true))
        val res = cases.map { (neon, wp, sleep) -> val c = measure(neon, wp, sleep)
            println("PERF ${if (neon) "neon" else "matrix"}/$wp/${if (sleep) "asleep" else "awake"}: draws=${c.draws} bitmapDraws=${c.bitmaps} textures=${c.textures} newShaders/frame=${"%.1f".format(c.newShadersPerFrame)} sw=${"%.2f".format(c.ms)}ms")
            c }
        for (c in res) {
            assertTrue("one orb atlas + one wallpaper atlas, not a texture per glyph (0.1.5 had 47)", c.textures <= 3)
            assertTrue("no per-frame shader allocation (0.1.5 made 74/frame)", c.newShadersPerFrame < 2.0)
        }
    }
}
