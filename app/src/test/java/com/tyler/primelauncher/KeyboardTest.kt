package com.tyler.primelauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
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

/** Message Prime box + Emit's keyboard: opens on tap, types, survives insets passes, closes on back / send / tap outside. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyboardTest {
    private val out = File("build/smoke").apply { mkdirs() }
    private fun idle(ms: Long = 300) = ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS)
    private fun lay(h: HomeActivity) { val r = h.window.decorView; r.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY)); r.layout(0, 0, W, H) }
    private val W get() = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics.widthPixels
    private val H get() = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics.heightPixels
    private fun snap(h: HomeActivity, name: String) { lay(h); val r = h.window.decorView; val b = Bitmap.createBitmap(r.width, r.height, Bitmap.Config.ARGB_8888); r.draw(Canvas(b)); File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) } }
    /** Let Compose recompose + animate (frames) with layout passes in between. */
    private fun settle(h: HomeActivity) { var i = 0; while (i < 30 || (h.keys.custom && h.keys.view.height == 0 && i < 200)) { idle(16); androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); lay(h); i++ } }
    private fun field(h: HomeActivity): EditText = HomeActivity::class.java.getDeclaredField("pmsg").apply { isAccessible = true }.get(h) as EditText
    private fun box(h: HomeActivity): View = HomeActivity::class.java.getDeclaredField("pbox").apply { isAccessible = true }.get(h) as View

    @Test fun messagePrimeKeyboard() {
        Call.end()
        androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().getSharedPreferences("prime", 0).edit().putBoolean("onboarded", true).commit()
        val ctl = Robolectric.buildActivity(HomeActivity::class.java).create(); val h = ctl.get(); h.stage.manualFrames = true; ctl.start().resume().visible()
        lay(h); idle(600)
        var e = field(h)
        assertFalse("system IME suppressed for the box", e.showSoftInputOnFocus)
        assertFalse(h.keys.open)
        // tap the box
        e.performClick(); settle(h)
        assertSame("no overlay rebuild on keyboard open", e, field(h)); assertTrue("tap focuses the box", e.hasFocus()); assertTrue("Emit keyboard open", h.keys.custom)
        val kbH = h.keys.view.height; assertTrue("keyboard has height ($kbH)", kbH > 300)
        val bl = IntArray(2); box(h).getLocationInWindow(bl); val kl = IntArray(2); h.keys.view.getLocationInWindow(kl)
        assertTrue("box sits above the keyboard (box bottom ${bl[1] + box(h).height}, kb top ${kl[1]})", bl[1] + box(h).height <= kl[1])
        // typing through the keyboard controller (what each key does)
        h.keys.kb.edit { it.insert("hey prime") }; idle()
        assertEquals("hey prime", e.text.toString()); assertEquals(9, e.selectionStart)
        h.keys.kb.backspace(); assertEquals("hey prim", e.text.toString())
        h.keys.kb.separator(" "); assertEquals("hey prim ", e.text.toString().let { it })
        snap(h, "20-keyboard-matrix")
        // regression: an insets pass (what the IME animation triggers) must not rebuild the box
        h.window.decorView.dispatchApplyWindowInsets(WindowInsets.Builder().build()); idle()
        assertSame("box survives insets", e, field(h)); assertTrue(h.keys.open); assertTrue(e.hasFocus())
        // back closes
        h.back(); idle(); assertFalse(h.keys.open); assertFalse(e.hasFocus())
        // reopen, tap outside closes and the tap does not reach the orb
        e.performClick(); idle(); assertTrue(h.keys.open)
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 540f, 300f, 0); h.dispatchTouchEvent(down)
        h.dispatchTouchEvent(MotionEvent.obtain(0, 50, MotionEvent.ACTION_UP, 540f, 300f, 0)); idle()
        assertFalse("tap outside closes", h.keys.open); assertFalse(h.stage.ringOpen)
        // theme swap keeps text, neon keyboard
        e.performClick(); idle(); h.prefs.theme = "neon"; h.applyLook(); idle(); lay(h)
        assertEquals("text kept across theme change", "hey prim ", field(h).text.toString())
        field(h).performClick(); settle(h); assertTrue(h.keys.custom); snap(h, "21-keyboard-neon")
        // send key closes and clears (webhook accepts)
        val rec = MessagePrimeTest.fakeHook(h)
        h.keys.kb.target!!.onAction!!.invoke(); idle(900)
        assertFalse(h.keys.open); assertEquals("", field(h).text.toString()); assertEquals(1, rec.calls.size)
        PrimeHook.transport = PrimeHook.https; PrimeHook.syncForTest = false; HookStore.sealer = KeystoreSealer
        // Emit's ⌨ key -> system keyboard, [mx] chip back
        field(h).performClick(); idle(); h.keys.kb.useSystem = true; idle(); assertTrue(field(h).showSoftInputOnFocus); assertTrue(h.keys.open && !h.keys.custom)
        h.keys.useMatrix(); idle(); assertTrue(h.keys.custom); assertFalse(field(h).showSoftInputOnFocus)
        ctl.pause(); idle(); assertFalse("leaving home closes the keyboard", h.keys.open)
        ctl.stop().destroy()
    }

    @Test fun pauseBeforeFirstLayoutDoesNotCrash() {
        val ctl = Robolectric.buildActivity(HomeActivity::class.java).create().start().resume()
        ctl.pause().stop().destroy() // 0.1.1 first install crashed here (closeRing touched the orb before layout)
    }
}
