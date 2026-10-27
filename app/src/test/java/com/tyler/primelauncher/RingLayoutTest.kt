package com.tyler.primelauncher

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Process
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.hypot

/** 0.1.11: the app ring stays below the clock/date/weather header and above the footer hint; the orb never moves. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h914dp-420dpi") // reference phone: 1080x2400 at density 420
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RingLayoutTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val all = listOf("com.twitter.android" to "X", "com.google.android.youtube" to "YouTube", "ai.x.grok" to "Grok", "ai.x.grok.bot" to "Grok Bot",
        "com.google.android.apps.maps" to "Maps", "com.samsung.android.messaging" to "Messages", "com.android.chrome" to "Chrome", "com.tyler.matrixsms" to "Emit",
        "com.google.android.gm" to "Gmail", "com.whatsapp" to "WhatsApp", "com.android.settings" to "Settings", "com.sec.android.app.camera" to "Camera",
        "com.google.android.calendar" to "Calendar", "com.spotify.music" to "Spotify", "com.sec.android.gallery3d" to "Gallery", "com.samsung.android.dialer" to "Phone")

    // ---------- pure fit ----------
    @Test fun fitSquashesOnlyWhatIsNeeded() {
        val r = Ring(emptyList(), orbitSlots(floatArrayOf(150f, 200f, -12f), 9, -90f, 50f), listOf(floatArrayOf(150f, 200f, -12f)))
        assertEquals("roomy: unchanged", 1f, fitRing(r, 400f, 400f).fy, 0f)
        val f = fitRing(r, 170f, 400f); assertTrue(f.fy < 1f && f.fy >= 0.6f)
        assertTrue(f.slots.all { -RingBox.top(it) <= 170f + 1e-3f }); assertTrue("x untouched", f.slots.zip(r.slots).all { (a, b) -> a.x == b.x })
        val tight = fitRing(r, 60f, 60f); assertTrue("hard clamp keeps every tile inside", tight.slots.all { -RingBox.top(it) <= 60f + 1e-3f && RingBox.bottom(it) <= 60f + 1e-3f })
        assertEquals(0.6f, tight.fy, 0f)
    }

    // ---------- real layout ----------
    /** Real HomeActivity with all 16 apps installed and the first [nPins] pinned (0.1.12: the ring shows pins only). */
    private fun home(theme: String, nPins: Int, font: Float = 1.5f): HomeActivity {
        org.robolectric.RuntimeEnvironment.setFontScale(font) // large-font setting (font_scale 1.5)
        Apps.setForTest(all.map { AppEntry(it.first, it.second, ComponentName(it.first, "${it.first}.Main"), Process.myUserHandle()) }, all.mapIndexed { i, a -> a.first to (100000L - i) }.toMap())
        app.getSharedPreferences("prime", 0).edit().clear().putBoolean("onboarded", true).putString("theme", theme).putString("pins", all.reversed().take(nPins).joinToString(",") { it.first }).commit()
        val ctl = Robolectric.buildActivity(HomeActivity::class.java).create(); val h = ctl.get(); h.stage.manualFrames = true
        ctl.start().resume().visible()
        val root = h.window.decorView; root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 1080, 2400)
        // typical system-bar insets for this screen size (88 px top, 126 px bottom)
        root.dispatchApplyWindowInsets(android.view.WindowInsets.Builder().setInsets(android.view.WindowInsets.Type.systemBars(), android.graphics.Insets.of(0, 88, 0, 126)).build())
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 1080, 2400)
        ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS); root.layout(0, 0, 1080, 2400); ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
        assertEquals("insets applied", 33.5f, h.stage.topInset, 0.5f)
        h.stage.refreshRing(); return h
    }
    private fun check(h: HomeActivity, label: String) {
        val st = h.stage; val rg = st.orb.ring!!; val hb = st.headerBottom
        assertTrue("$label: header measured ($hb)", hb > 100f)
        val tops = rg.slots.take(rg.tiles.size).map { st.cy + RingBox.top(it) }; val bots = rg.slots.take(rg.tiles.size).map { st.cy + RingBox.bottom(it) }
        assertTrue("$label: topmost icon ${tops.min()} must clear header $hb", tops.min() >= hb + 8f)
        assertTrue("$label: lowest label ${bots.max()} must clear footer ${st.footerTop()}", bots.max() <= st.footerTop() - 4f)
        assertTrue("$label: footer above nav bar", st.footerTop() + 18f <= st.H - st.bottomInset)
        val sl = rg.slots.take(rg.tiles.size)
        for (i in sl.indices) { assertTrue("$label: tile $i clear of the orb", hypot(sl[i].x, sl[i].y) >= st.orbR() * 0.95f + sl[i].s / 2 * 0.5f)
            for (j in i + 1 until sl.size) assertTrue("$label: tiles $i/$j overlap", hypot(sl[i].x - sl[j].x, sl[i].y - sl[j].y) >= sl[i].s * 0.9f) }
        println("RING $label: header=%.0f top=%.0f bottom=%.0f footer=%.0f cy=%.0f fy=%.2f".format(hb, tops.min(), bots.max(), st.footerTop(), st.cy, rg.fy))
    }
    @Test fun neonNinePinsClearsHeaderAndFooter() { check(home("neon", 9), "neon/9") }
    @Test fun matrixNinePinsClearsHeaderAndFooter() { check(home("matrix", 9), "matrix/9") }
    @Test fun everyFontScaleFits() { for (f in listOf(1f, 1.3f, 1.5f, 2f)) for (t in listOf("neon", "matrix")) check(home(t, 9, font = f), "$t/9 font=$f") }
    @Test fun ringShowsOnlyPinnedAppsAtManyCounts() {
        for (n in listOf(1, 3, 5, 8, 10, 11, 12)) for (t in listOf("neon", "matrix")) { val h = home(t, n)
            val pins = h.prefs.pins; val shown = h.ringApps().map { it.pkg }
            assertEquals("$t/$n: ring = pins, in pin order", pins, shown); assertEquals(n, h.stage.orb.ring!!.tiles.size); check(h, "$t/$n pins") }
    }
    @Test fun pinsBeyondTheCapAreIgnored() { val h = home("neon", 16); assertEquals(Logic.MAX_PINS, h.ringApps().size); check(h, "neon/16 pins (capped)") }
    @Test fun zeroPinsShowsOrbAloneWithHint() {
        for (t in listOf("neon", "matrix")) { val h = home(t, 0); val st = h.stage; val rg = st.orb.ring!!
            assertTrue(rg.tiles.isEmpty() && rg.slots.isEmpty() && rg.orbits.isEmpty())
            val x = st.cxOrb() * st.d; val y = st.cy * st.d; st.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0))
            ShadowLooper.idleMainLooper(450, TimeUnit.MILLISECONDS); var ns = 1_000_000_000L; repeat(60) { ns += 16_666_667L; st.doFrame(ns) }
            assertTrue(st.ringOpen); st.dispatchTouchEvent(MotionEvent.obtain(0, 500, MotionEvent.ACTION_UP, x, y, 0)); repeat(5) { ns += 16_666_667L; st.doFrame(ns) }
            val hints = ArrayList<View>(); h.window.decorView.findViewsWithText(hints, "NO PINNED APPS", View.FIND_VIEWS_WITH_TEXT)
            assertTrue("$t: empty ring explains how to pin", hints.any { it.visibility == View.VISIBLE })
            if (t == "neon") { val b = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888); h.window.decorView.draw(Canvas(b))
                File("build/smoke").apply { mkdirs() }.let { File(it, "31-ring-zero-pins.png").outputStream().use { o -> b.compress(Bitmap.CompressFormat.PNG, 90, o) } } }
            st.closeRing() }
    }
    @Test fun orbDoesNotMove() { val h = home("neon", 9); val cy = h.stage.cy; h.stage.refreshRing(); assertEquals(cy, h.stage.cy, 0f)
        val expect = (h.stage.topInset + (h.stage.H - h.stage.bottomInset - h.stage.bottomReserve)) / 2 + 24; assertEquals(expect, cy, 0.01f) }

    @Test fun renderOpenRingForVisualCheck() {
        val h = home("neon", 9); val st = h.stage; val x = st.cxOrb() * st.d; val y = st.cy * st.d
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0); st.dispatchTouchEvent(down); ShadowLooper.idleMainLooper(450, TimeUnit.MILLISECONDS)
        assertTrue(st.ringOpen); var ns = 1_000_000_000L; repeat(90) { ns += 16_666_667L; st.doFrame(ns) }
        val up = MotionEvent.obtain(0, 500, MotionEvent.ACTION_UP, x, y, 0); st.dispatchTouchEvent(up); repeat(20) { ns += 16_666_667L; st.doFrame(ns) }
        val b = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888); h.window.decorView.draw(Canvas(b))
        File("build/smoke").apply { mkdirs() }.let { File(it, "30-ring-neon-9.png").outputStream().use { o -> b.compress(Bitmap.CompressFormat.PNG, 90, o) } }
        check(h, "neon/9 open")
    }
}
