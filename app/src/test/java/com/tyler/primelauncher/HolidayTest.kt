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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HolidayTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val out = File(System.getProperty("holiday.out") ?: "build/holiday-shots").apply { mkdirs() }

    @Test fun bagShowsAllFacesBeforeARepeatAndNeverBackToBack() {
        val sp = ctx.getSharedPreferences("bagtest", 0); val r = java.util.Random(7); var last: HFace? = null
        repeat(40) { round ->
            val seen = (0 until HFace.entries.size).map { HolidayCycle.next(sp, r).also { f -> assertNotEquals("no back-to-back repeat (round $round)", last, f); last = f } }
            assertEquals("each round has every face", HFace.entries.toSet(), seen.toSet())
        }
        assertEquals(last, HolidayCycle.load(sp))
    }

    @Test fun swapOnlyWhenRestingAndAllThreeTransitionsGetPicked() {
        assertTrue(HolidayCycle.canSwap(0.0, false, Mode.IDLE, 0.0, false, false, false))
        assertFalse("asleep", HolidayCycle.canSwap(0.0, true, Mode.IDLE, 0.0, false, false, false))
        assertFalse("dozing", HolidayCycle.canSwap(0.5, false, Mode.IDLE, 0.0, false, false, false))
        assertFalse("talking", HolidayCycle.canSwap(0.0, false, Mode.TALK, 0.0, false, false, false))
        assertFalse("thinking", HolidayCycle.canSwap(0.0, false, Mode.IDLE, 0.6, false, false, false))
        assertFalse("call", HolidayCycle.canSwap(0.0, false, Mode.IDLE, 0.0, true, false, false))
        assertFalse("antic", HolidayCycle.canSwap(0.0, false, Mode.IDLE, 0.0, false, true, false))
        val r = java.util.Random(3); assertEquals(HSwap.entries.toSet(), (0 until 60).map { HolidayCycle.pickSwap(r) }.toSet())
        assertEquals(20 * 60_000L, HolidayCycle.SWAP_EVERY_MS.toLong())
    }

    @Test fun themeSyncAcceptsOnlyNewerKnownDifferentThemes() {
        val p = Prefs(ctx); p.theme = "matrix"; p.sp.edit().putLong("themeTs", 100L).commit()
        assertFalse("older", ThemeSync.accept(p, "holiday", 50L))
        assertFalse("unknown", ThemeSync.accept(p, "evil", 500L))
        assertFalse("same", ThemeSync.accept(p, "matrix", 500L))
        assertTrue(ThemeSync.accept(p, "holiday", 500L)); assertEquals("holiday", p.theme)
        assertFalse("replay of the same message is ignored (no ping-pong)", ThemeSync.accept(p, "odst", 500L))
        ThemeSync.send(ctx, "neon", 900L); assertEquals(900L, p.sp.getLong("themeTs", 0))
        val sent = org.robolectric.Shadows.shadowOf(ctx as android.app.Application).broadcastIntents.last()
        assertEquals("com.tyler.matrixsms", sent.component?.packageName); assertEquals("neon", sent.getStringExtra("theme"))
        assertEquals(setOf("matrix", "neon", "odst", "holiday"), ThemeSync.IDS)
    }

    @Test fun holidayPersonaHasNoKitchenOrStudyAndEveryFaceHasAntics() {
        for (f in HFace.entries) { HolidayCycle.face = f
            assertTrue(HolidayPersona.micros.any { it.startsWith("m.hol.") }); assertTrue(HolidayPersona.pool.any { it.id.startsWith("hol.act.") })
            for (id in HolidayAntics.micros(f) + HolidayAntics.acts(f)) assertNotNull(id, Antics.all[id]) }
        val words = Regex("kitchen|cook|recipe|study|homework|flashcard", RegexOption.IGNORE_CASE)
        Cat.entries.forEach { c -> HolidayPersona.lines[c].orEmpty().forEach { assertFalse(it, words.containsMatchIn(it)) } }
        assertEquals("only holiday wording", false, HolidayPersona.lines[Cat.GREETING].orEmpty().any { it.contains("hallo" + "ween", true) })
    }

    @Test fun fourFacesNoSkull() {
        assertEquals(listOf("PUMPKIN", "GHOST", "CAT", "HAT"), HFace.entries.map { it.name })
        assertTrue(Antics.all.keys.none { it.contains("rattle") || it.contains("chatter") })
        val sp = ctx.getSharedPreferences("oldbag", 0); sp.edit().putString("hol.face", "SKULL").putString("hol.bag", "SKULL,CAT,HAT,GHOST,PUMPKIN").putInt("hol.pos", 1).commit()
        val f = HolidayCycle.load(sp); assertTrue(f in HFace.entries) // a saved 0.1.28 skull is replaced
        repeat(8) { assertTrue(HolidayCycle.next(sp, java.util.Random(it.toLong())) in HFace.entries) }
    }

    @Test fun homeOpenOnlySwapsAfterTwentyMinutes() {
        val sp = ctx.getSharedPreferences("rapid", 0); val t0 = 5_000_000_000L
        assertTrue("never swapped -> due", HolidayCycle.homeOpenDue(sp, t0))
        HolidayCycle.markSwapped(sp, t0)
        for (s in listOf(1L, 8L, 60L, 600L, 1199L)) assertFalse("not due ${s}s later", HolidayCycle.homeOpenDue(sp, t0 + s * 1000))
        assertTrue(HolidayCycle.homeOpenDue(sp, t0 + 20 * 60_000L))
        assertFalse(HolidayCycle.timerDue(19 * 60_000.0)); assertTrue(HolidayCycle.timerDue(20 * 60_000.0))
    }

    @Test fun repeatedResumesDoNotRapidFireSwaps() {
        ctx.getSharedPreferences("prime", 0).edit().putBoolean("onboarded", true).putString("theme", "holiday").commit()
        val ctl = Robolectric.buildActivity(HomeActivity::class.java).create(); val h = ctl.get(); h.stage.manualFrames = true; ctl.start().resume().visible()
        val root = h.window.decorView; root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 1080, 2400)
        frames(h, 400) // the first open may swap once
        val face = HolidayCycle.face
        repeat(15) { ctl.pause(); ctl.resume(); frames(h, 8 * 60) } // a resume every ~8 s for 2 minutes
        assertEquals("no swap within 20 minutes of the last one", face, HolidayCycle.face); assertFalse(h.stage.orb.hol!!.swapping)
        Prefs(ctx).sp.edit().putLong("hol.lastSwap", System.currentTimeMillis() - 21 * 60_000L).commit()
        ctl.pause(); ctl.resume(); var n = 0; while (HolidayCycle.face == face && n++ < 60) frames(h, 60) // waits for a resting moment (no antic / bubble)
        assertNotEquals("a home open after 20 min swaps", face, HolidayCycle.face)
    }

    private var clock = 1_000_000_000L
    private fun frames(h: HomeActivity, n: Int) { repeat(n) { clock += 16_666_667L; h.stage.doFrame(clock); ShadowLooper.idleMainLooper(17L, java.util.concurrent.TimeUnit.MILLISECONDS) } }
    private fun snap(h: HomeActivity, name: String) { val root = h.window.decorView; val b = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(b)); File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) } }

    @Test fun rendersEveryFaceEveryTransitionAndTheSaverPath() {
        ctx.getSharedPreferences("prime", 0).edit().putBoolean("onboarded", true).putString("theme", "holiday").commit()
        val ctl = Robolectric.buildActivity(HomeActivity::class.java).create(); val h = ctl.get(); h.stage.manualFrames = true; ctl.start().resume().visible()
        val root = h.window.decorView; root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 1080, 2400)
        ShadowLooper.idleMainLooper(600L, java.util.concurrent.TimeUnit.MILLISECONDS); frames(h, 30)
        val hf = assertNotNull(h.stage.orb.hol).let { h.stage.orb.hol!! }
        for (f in HFace.entries) { hf.setFace(f); frames(h, 20); snap(h, "face-${f.name.lowercase()}") }
        for (k in HSwap.entries) { h.stage.holidaySwapNow(k); frames(h, (k.dur * 30).toInt()); assertTrue(hf.swapping); snap(h, "swap-${k.name.lowercase()}-mid")
            assertFalse("buddy waits during a swap", h.stage.buddy.beatIdle()); frames(h, (k.dur * 40).toInt()); assertFalse(hf.swapping) }
        for (f in HFace.entries) { hf.setFace(f); HolidayCycle.face = f; HolidayAntics.acts(f).forEach { id -> assertTrue(id, h.stage.buddy.play(id)); frames(h, 40); snap(h, "act-$id"); frames(h, 200) } }
        h.stage.orb.glowOn = false; hf.setFace(HFace.PUMPKIN); frames(h, 20); snap(h, "saver-pumpkin")
    }
}
