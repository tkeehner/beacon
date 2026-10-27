package com.tyler.primelauncher

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Process
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** Drives the real HomeActivity on Robolectric (native graphics) and renders frames to PNGs for a visual check on the box. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeSmokeTest {
    private val pkgs = listOf("com.google.android.gm" to "Gmail", "com.android.chrome" to "Chrome", "com.twitter.android" to "X", "com.google.android.youtube" to "YouTube",
        "com.tyler.matrixsms" to "Emit", "ai.x.grok.bot" to "Grok Bot", "com.google.android.apps.maps" to "Maps", "com.sec.android.app.camera" to "Camera",
        "com.android.settings" to "Settings", "com.whatsapp" to "WhatsApp", "com.example.unknown" to "Zed Unknown")
    private val out = File("build/smoke").apply { mkdirs() }

    private var clock = 1_000_000_000L
    private fun frames(h: HomeActivity, n: Int) { repeat(n) { clock += 16_666_667L; h.stage.doFrame(clock); ShadowLooper.idleMainLooper(17L, java.util.concurrent.TimeUnit.MILLISECONDS) } }
    private fun snap(h: HomeActivity, name: String) {
        val root = h.window.decorView; val b = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(Canvas(b)); File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) }
    }

    @Test fun homeDrawerSearchSettingsBubblesTray() {
        Apps.setForTest(pkgs.map { AppEntry(it.first, it.second, ComponentName(it.first, "$it.first.Main"), Process.myUserHandle()) }, mapOf("com.google.android.gm" to 9000L, "com.twitter.android" to 8000L))
        androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().getSharedPreferences("prime", 0).edit().putBoolean("onboarded", true).commit()
        val ctl = Robolectric.buildActivity(HomeActivity::class.java).create(); val h = ctl.get(); h.stage.manualFrames = true; ctl.start().resume().visible()
        h.prefs.onboarded = true
        val root = h.window.decorView; root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 1080, 2400)
        ShadowLooper.idleMainLooper(600L, java.util.concurrent.TimeUnit.MILLISECONDS); h.stage.stop(); h.stage.start(); frames(h, 40)
        assertTrue("orb centred", kotlin.math.abs(h.stage.cxOrb() - h.stage.W / 2) < 1f)
        snap(h, "01-home-matrix")
        // ring
        val px = h.stage.cxOrb() * h.stage.d; val py = h.stage.cy * h.stage.d
        h.stage.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, px, py, 0))
        ShadowLooper.idleMainLooper(400L, java.util.concurrent.TimeUnit.MILLISECONDS); frames(h, 70); assertTrue("hold opens ring", h.stage.ringOpen)
        h.stage.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 500, android.view.MotionEvent.ACTION_UP, px, py, 0)); frames(h, 5); assertTrue("ring sticky after release", h.stage.ringOpen); snap(h, "02-ring"); h.stage.closeRing(); frames(h, 40)
        // notifications -> bubbles + unread + tray
        repeat(4) { i -> NotifStore.posted(Note("k$i", if (i % 2 == 0) "com.google.android.gm" else "com.twitter.android", "Sender $i", "Message body number $i", 1000L + i, null, true, true), true) }
        ShadowLooper.idleMainLooper(600L, java.util.concurrent.TimeUnit.MILLISECONDS); frames(h, 20); snap(h, "03-bubbles")
        h.window.decorView.findViewsWithText(ArrayList(), "4", View.FIND_VIEWS_WITH_TEXT)
        assertEquals(4, NotifStore.all().size); assertEquals(2, NotifStore.counts().size)
        ShadowLooper.idleMainLooper(9500L, java.util.concurrent.TimeUnit.MILLISECONDS); frames(h, 10)
        val unreadOpen = HomeActivity::class.java.getDeclaredMethod("openTray").apply { isAccessible = true }; unreadOpen.invoke(h)
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 1080, 2400)
        val trayF = HomeActivity::class.java.getDeclaredField("tray").apply { isAccessible = true }; val tray = trayF.get(h) as View
        val loc = IntArray(2); tray.getLocationInWindow(loc); val trayBottomDp = (loc[1] + tray.height) / h.stage.d
        val orbTop = h.stage.cy - h.stage.orbR(); println("TRAY bottom ${trayBottomDp}dp, orb top ${orbTop}dp, tray h ${tray.height / h.stage.d}dp")
        assertTrue("tray must not cover orb", trayBottomDp < orbTop); snap(h, "04-tray")
        // drawer, search, settings
        h.openDrawer(); ShadowLooper.idleMainLooper(400L, java.util.concurrent.TimeUnit.MILLISECONDS); frames(h, 5); snap(h, "05-drawer"); h.closePanel(false)
        h.openSearch(); ShadowLooper.idleMainLooper(400L, java.util.concurrent.TimeUnit.MILLISECONDS); frames(h, 5); snap(h, "06-search"); h.closePanel(false)
        h.openSettings(); ShadowLooper.idleMainLooper(400L, java.util.concurrent.TimeUnit.MILLISECONDS); frames(h, 5); snap(h, "07-settings"); h.closePanel(false)
        // neon + icons + wallpaper variants + charging + reduce motion
        h.prefs.theme = "neon"; h.applyLook(); frames(h, 40); snap(h, "08-home-neon")
        h.stage.charging = true; frames(h, 30); snap(h, "09-charging"); h.stage.charging = false
        h.openDrawer(); ShadowLooper.idleMainLooper(400L, java.util.concurrent.TimeUnit.MILLISECONDS); snap(h, "10-drawer-neon"); h.closePanel(false)
        for (s in IconStyle.values()) { h.prefs.icons = s; h.applyLook(); frames(h, 2) }
        for (w in listOf("glyphs", "streaks", "solid", "off")) { h.prefs.wpStyle = w; h.stage.rebuild(); frames(h, 3) }
        h.prefs.wpGlyphs = "kana"; h.prefs.wpStyle = "glyphs"; h.prefs.theme = "matrix"; h.prefs.reduce = true; h.applyLook(); frames(h, 10); snap(h, "11-kana-reduce")
        h.openOnboarding(); ShadowLooper.idleMainLooper(400L, java.util.concurrent.TimeUnit.MILLISECONDS); snap(h, "12-onboarding"); h.closePanel(false)
        // flicks: camera/emit/grok just start activities; torch handled without crashing
        for (d in Quick.DIRS) h.onFlick(d)
        h.sendPrime("hello prime"); ShadowLooper.idleMainLooper(800L, java.util.concurrent.TimeUnit.MILLISECONDS)
        val started = shadowOf(h).nextStartedActivity; println("last started: ${started?.component ?: started?.action}")
        ctl.pause().stop().destroy()
    }
    @Test fun perfFrameBudget() {
        Apps.setForTest(pkgs.map { AppEntry(it.first, it.second, ComponentName(it.first, "$it.first.Main"), Process.myUserHandle()) }, emptyMap())
        val c0 = Robolectric.buildActivity(HomeActivity::class.java).create(); val h = c0.get(); h.stage.manualFrames = true; c0.start().resume().visible(); h.prefs.onboarded = true
        val root = h.window.decorView; root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 1080, 2400)
        val b = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888); val c = Canvas(b); h.stage.orb.step(1.0)
        repeat(10) { h.stage.orb.step(1 / 60.0); h.stage.draw(c) }
        val t0 = System.nanoTime(); val n = 60; repeat(n) { h.stage.orb.step(1 / 60.0); h.stage.draw(c) }
        println("PERF software canvas (box CPU, no GPU): %.2f ms/frame".format((System.nanoTime() - t0) / 1e6 / n))
    }
}
