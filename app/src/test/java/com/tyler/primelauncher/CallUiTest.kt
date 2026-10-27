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

/** Live Grok Bot call: halo + timer + red hang-up button (Matrix, Neon City, reduce motion). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CallUiTest {
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

    @Test fun callHaloAndHangup() {
        androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().getSharedPreferences("prime", 0).edit().putBoolean("onboarded", true).commit()
        val ctl = Robolectric.buildActivity(HomeActivity::class.java).create(); val h = ctl.get(); h.stage.manualFrames = true; ctl.start().resume().visible()
        lay(h); idle(600)
        var clock = 1_000_000_000L; fun frames(n: Int) { repeat(n) { clock += 16_666_667L; h.stage.doFrame(clock); idle(17) } }
        val hang = HomeActivity::class.java.getDeclaredField("hangup").apply { isAccessible = true }
        Call.begin("test"); frames(60); lay(h)
        assertTrue(Call.active); assertTrue(h.stage.callK > 0.9); assertEquals(View.VISIBLE, (hang.get(h) as View).visibility)
        snap(h, "22-call-matrix")
        h.prefs.theme = "neon"; h.applyLook(); frames(60); snap(h, "23-call-neon")
        h.prefs.reduce = true; h.applyLook(); frames(20); snap(h, "24-call-reduce")
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val target = android.content.Intent(app, HomeActivity::class.java).setAction("grok-call-screen")
        Call.grokPosted("grok|call", true, android.app.PendingIntent.getActivity(app, 3, target, android.app.PendingIntent.FLAG_IMMUTABLE))
        (hang.get(h) as View).performClick(); idle(800)
        val started = org.robolectric.Shadows.shadowOf(app).nextStartedActivity ?: org.robolectric.Shadows.shadowOf(h).nextStartedActivity
        assertEquals("hang-up opens the call notification's screen", "grok-call-screen", started?.action)
        assertFalse("helper not armed when the accessibility service is off", HangupService.armed())
        Call.grokRemoved("grok|call")
        Call.end(); frames(60); lay(h); assertEquals(View.GONE, (hang.get(h) as View).visibility)
        ctl.pause().stop().destroy()
    }
}
