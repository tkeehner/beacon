package com.tyler.primelauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** Phone-density stills (SM-G781V: 1080x2400 @ 450dpi) of every holiday face, every swap, and battery saver. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w384dp-h853dp-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HolidayRenderShot {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val out = File(System.getProperty("holiday.out") ?: "build/holiday").apply { mkdirs() }
    private var clock = 1_000_000_000L
    private fun frames(h: HomeActivity, n: Int) { repeat(n) { clock += 16_666_667L; h.stage.doFrame(clock); ShadowLooper.idleMainLooper(17L, java.util.concurrent.TimeUnit.MILLISECONDS) } }
    private fun snap(h: HomeActivity, name: String): Bitmap { val root = h.window.decorView; val b = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(b)); File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 95, it) }; return b }

    @Test fun phoneDensityStills() {
        ctx.getSharedPreferences("prime", 0).edit().putBoolean("onboarded", true).putString("theme", "holiday").commit()
        val ctl = Robolectric.buildActivity(HomeActivity::class.java).create(); val h = ctl.get(); h.stage.manualFrames = true; ctl.start().resume().visible()
        val root = h.window.decorView; root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 1080, 2400)
        ShadowLooper.idleMainLooper(600L, java.util.concurrent.TimeUnit.MILLISECONDS); frames(h, 30)
        val hf = h.stage.orb.hol!!
        for (saver in listOf(false, true)) { h.stage.orb.glowOn = !saver
            for (f in HFace.entries) { hf.setFace(f); frames(h, 20); snap(h, (if (saver) "saver-" else "face-") + f.name.lowercase()) } }
        h.stage.orb.glowOn = true
        for (k in HSwap.entries) for (q in listOf(0.3, 0.6)) { hf.setFace(HFace.GHOST); h.stage.holidaySwapNow(k); frames(h, (k.dur * 60 * q).toInt()); snap(h, "swap-${k.name.lowercase()}-${(q * 100).toInt()}"); frames(h, 200) }
    }
}
