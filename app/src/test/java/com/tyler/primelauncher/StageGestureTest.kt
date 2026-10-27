package com.tyler.primelauncher

import android.content.ComponentName
import android.os.Process
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * Orb gestures on the real StageView. Regression for 0.1.3: hold orb -> release on empty (ring goes sticky) -> tap a tile
 * launched the app AND then voice mode, because the stale onOrb flag made the tap's ACTION_UP call onOrbTap().
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StageGestureTest {
    private class FakeHost(val apps: List<AppEntry>) : StageView.Host {
        val log = ArrayList<String>()
        override fun ringApps() = apps
        override fun onOrbTap() { log += "voice" }
        override fun onRingPick(a: AppEntry) { log += "pick:${a.pkg}" }
        override fun onFlick(dir: String) { log += "flick:$dir" }
        override fun onSwipeUp() { log += "up" }
        override fun onSwipeDown() { log += "down" }
        override fun onLongPressBg() { log += "settings" }
        var frames = 0
        override fun onFrame(t: Double) { frames++ }
        override fun ringHint(s: String?) {}
    }
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var host: FakeHost; private lateinit var stage: StageView
    private var now = 0L

    @Before fun setUp() {
        val apps = listOf("com.google.android.gm" to "Gmail", "com.android.chrome" to "Chrome", "com.twitter.android" to "X", "com.tyler.matrixsms" to "Emit")
            .map { AppEntry(it.first, it.second, ComponentName(it.first, "${it.first}.Main"), Process.myUserHandle()) }
        host = FakeHost(apps)
        // Attached to a real window so postDelayed (hold-to-summon, long-press) timers run like on the phone.
        val act = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        stage = StageView(act, Prefs(act), host); act.setContentView(stage)
        stage.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)); stage.layout(0, 0, 1080, 2400)
        assertTrue(stage.ready)
    }
    private fun ev(action: Int, xDp: Float, yDp: Float) { val e = MotionEvent.obtain(0, now, action, xDp * stage.d, yDp * stage.d, 0); stage.onTouchEvent(e); e.recycle() }
    private fun tap(xDp: Float, yDp: Float) { ev(MotionEvent.ACTION_DOWN, xDp, yDp); now += 60; ev(MotionEvent.ACTION_UP, xDp, yDp); now += 60 }
    private fun frames(n: Int) = repeat(n) { stage.orb.step(1 / 60.0) }
    private fun summonSticky() {
        ev(MotionEvent.ACTION_DOWN, stage.cxOrb(), stage.cy); ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS); now += 400
        assertTrue("hold summons the ring", stage.ringOpen); frames(80)
        ev(MotionEvent.ACTION_UP, stage.cxOrb(), stage.cy); now += 60
        assertTrue("release on empty leaves the ring open (sticky)", stage.ringOpen)
    }

    @Test fun stickyTapOnTileLaunchesAppOnlyNoVoice() {
        summonSticky()
        val sl = stage.orb.ring!!.slots[0]
        tap(stage.orb.cx + sl.x, stage.orb.cy + sl.y)
        assertFalse(stage.ringOpen)
        assertEquals(listOf("pick:com.google.android.gm"), host.log) // slot 0 = first ring app
    }
    @Test fun stickyTapOutsideClosesRingNoVoice() {
        summonSticky()
        tap(30f, stage.H - 60f)
        assertFalse(stage.ringOpen); assertEquals(emptyList<String>(), host.log)
        // and a sticky tap on the orb itself also just closes
        summonSticky(); tap(stage.cxOrb(), stage.cy); assertFalse(stage.ringOpen); assertEquals(emptyList<String>(), host.log)
    }
    @Test fun plainOrbTapStillOpensVoice() {
        tap(stage.cxOrb(), stage.cy); assertEquals(listOf("voice"), host.log)
        // also right after a sticky-ring interaction
        host.log.clear(); summonSticky(); tap(30f, stage.H - 60f); frames(60)
        tap(stage.cxOrb(), stage.cy); assertEquals(listOf("voice"), host.log)
    }
    @Test fun otherGesturesUnchanged() {
        // flick up from the orb (quick actions on by default)
        ev(MotionEvent.ACTION_DOWN, stage.cxOrb(), stage.cy); now += 20; ev(MotionEvent.ACTION_MOVE, stage.cxOrb(), stage.cy - 40); now += 80
        ev(MotionEvent.ACTION_UP, stage.cxOrb(), stage.cy - 90); now += 60
        // swipe up / down on the background
        val bx = 40f; val by = stage.H - 150f
        ev(MotionEvent.ACTION_DOWN, bx, by); now += 50; ev(MotionEvent.ACTION_MOVE, bx, by - 50); now += 50; ev(MotionEvent.ACTION_UP, bx, by - 120); now += 60
        ev(MotionEvent.ACTION_DOWN, bx, 200f); now += 50; ev(MotionEvent.ACTION_MOVE, bx, 250f); now += 50; ev(MotionEvent.ACTION_UP, bx, 320f); now += 60
        // long-press background opens settings, and its UP does nothing else
        ev(MotionEvent.ACTION_DOWN, bx, by); ShadowLooper.idleMainLooper(700, TimeUnit.MILLISECONDS); now += 700; ev(MotionEvent.ACTION_UP, bx, by); now += 60
        assertEquals(listOf("flick:up", "up", "down", "settings"), host.log)
    }
    @Test fun sleepAndWakeSurviveRebuild() {
        stage.sleepOrb(); assertTrue(stage.orb.sleep.asleep); stage.rebuild(); assertTrue("rebuild keeps sleep", stage.orb.sleep.asleep)
        assertTrue(stage.wakeOrb()); frames(150); assertEquals(0.0, stage.orb.sleep.k, 1e-9); assertFalse(stage.wakeOrb())
    }
    @Test fun wakeAfterResumeWaitsForFirstFramesThenRamps() {
        stage.manualFrames = true; stage.sleepOrb(); stage.start()
        assertTrue(stage.wakeOrb()); assertTrue("queued, not yet ramping into the slow first frames", stage.orb.sleep.asleep); assertTrue(stage.awakeOrWaking)
        var ns = 1_000_000_000L; repeat(3) { ns += 16_666_667L; stage.doFrame(ns) }
        assertFalse("ramp started after two frames", stage.orb.sleep.asleep)
        repeat(150) { ns += 16_666_667L; stage.doFrame(ns) }; assertEquals(0.0, stage.orb.sleep.k, 1e-9)
        stage.stop()
    }
    @Test fun deepSleepRunsAtHalfRate() {
        stage.manualFrames = true; stage.sleepOrb(); stage.start(); val t0 = stage.orb.t
        var ns = 1_000_000_000L; repeat(60) { ns += 16_666_667L; stage.doFrame(ns) }
        assertTrue("~30 of 60 frames drawn while fully asleep (got ${host.frames})", host.frames in 28..32)
        assertTrue("animation time still runs at real speed", stage.orb.t - t0 > 0.9)
        stage.wakeOrb(); host.frames = 0; repeat(60) { ns += 16_666_667L; stage.doFrame(ns) }
        assertTrue("full rate once waking (got ${host.frames})", host.frames >= 50)
        stage.stop()
    }

    // ---------- 0.1.7: slow wake + whole-screen fade ----------
    private fun run(n: Int, ns0: Long): Long { var ns = ns0; repeat(n) { ns += 16_666_667L; stage.doFrame(ns) }; return ns }
    @Test fun unlockWakeIsSlowAndRevealFadesWithIt() {
        stage.manualFrames = true; stage.sleepOrb(); assertEquals("hidden while asleep", 0f, stage.reveal, 0f); stage.start()
        assertTrue(stage.wakeOrb()); assertEquals("hidden while the wake is queued", 0f, stage.reveal, 0f)
        var ns = run(3, 1_000_000_000L); assertFalse(stage.orb.sleep.asleep); assertEquals(SleepState.SLOW_WAKE_S, stage.orb.sleep.wakeS, 1e-9)
        assertTrue("longer than the old 1.1 s", SleepState.SLOW_WAKE_S in 1.8..2.5)
        // Smooth, monotonic fade at full rate, no stall: sample every frame for the whole ramp.
        var last = stage.reveal; var steps = 0; var maxJump = 0f
        repeat(150) { ns += 16_666_667L; stage.doFrame(ns); val r = stage.reveal; assertTrue("never goes backwards", r >= last - 1e-6f); if (r > last) steps++; maxJump = maxOf(maxJump, r - last); last = r }
        assertEquals("fully shown", 1f, stage.reveal, 1e-6f); assertEquals(0.0, stage.orb.sleep.k, 1e-9)
        assertTrue("fades over many frames (got $steps)", steps >= 100); assertTrue("no pop (max step $maxJump)", maxJump < 0.03f)
        stage.stop()
    }
    @Test fun slowWakeIsStillHalfwayAtOneSecondAndDoneByTwoAndAHalf() {
        stage.manualFrames = true; stage.sleepOrb(); stage.start(); stage.wakeOrb(); var ns = run(3, 1_000_000_000L)
        ns = run(60, ns); assertTrue("still waking after 1 s (reveal ${stage.reveal})", stage.reveal in 0.2f..0.8f); assertTrue(stage.orb.sleep.k > 0.0)
        run(90, ns); assertEquals(1f, stage.reveal, 1e-6f)
        stage.stop()
    }
    @Test fun reduceMotionWakesInstantly() {
        Prefs(ctx).reduce = true; stage.manualFrames = true; stage.sleepOrb(); stage.start()
        assertTrue(stage.wakeOrb()); assertFalse("no queued ramp", stage.orb.sleep.asleep); assertEquals(0.0, stage.orb.sleep.k, 1e-9); assertEquals(1f, stage.reveal, 0f)
        stage.stop(); Prefs(ctx).reduce = false
    }
    @Test fun awakeStageIsFullyShown() { assertEquals(1f, stage.reveal, 0f); assertFalse(stage.wakeOrb()); assertEquals(1f, stage.reveal, 0f) }
}
