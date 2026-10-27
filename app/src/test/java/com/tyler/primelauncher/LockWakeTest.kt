package com.tyler.primelauncher

import android.content.ComponentName
import android.content.Intent
import android.os.Process
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/** Home: screen off -> orb asleep; unlock + resume -> wake animation. Lock-screen orb: off by default, any touch leaves. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LockWakeTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private var clock = 1_000_000_000L

    @Test fun homeSleepsOnScreenOffAndWakesOnReturn() {
        Apps.setForTest(listOf(AppEntry("com.android.chrome", "Chrome", ComponentName("com.android.chrome", "x.Main"), Process.myUserHandle())), emptyMap())
        app.getSharedPreferences("prime", 0).edit().putBoolean("onboarded", true).commit()
        val ctl = Robolectric.buildActivity(HomeActivity::class.java).create(); val h = ctl.get(); h.stage.manualFrames = true; ctl.start().resume().visible()
        val root = h.window.decorView; root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 1080, 2400)
        ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS)
        assertFalse(h.stage.orb.sleep.asleep)
        ctl.pause(); app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF)); ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
        assertTrue("asleep after screen off", h.stage.orb.sleep.asleep); assertEquals(1.0, h.stage.orb.sleep.k, 1e-9)
        ctl.stop(); app.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT)); ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
        ctl.restart().start().resume(); ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
        assertTrue("wake started (or queued for the first frames) on return", h.stage.awakeOrWaking)
        assertEquals("widgets start hidden on an unlock-to-home", 0f, h.overlayAlpha, 0f)
        h.stage.stop(); h.stage.start(); repeat(30) { clock += 16_666_667L; h.stage.doFrame(clock) }
        assertTrue("widgets mid-fade with the orb (alpha ${h.overlayAlpha})", h.overlayAlpha > 0f && h.overlayAlpha < 1f)
        repeat(130) { clock += 16_666_667L; h.stage.doFrame(clock) }
        assertEquals("fully awake after the slow wake", 0.0, h.stage.orb.sleep.k, 1e-9); assertEquals(1f, h.overlayAlpha, 0f)
        // A quick app switch (no screen-off) must not replay the fade.
        ctl.pause(); ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS); ctl.stop().restart().start().resume(); ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
        assertFalse(h.stage.orb.sleep.asleep); assertEquals("no fade on a quick return", 1f, h.overlayAlpha, 0f)
        repeat(5) { clock += 16_666_667L; h.stage.doFrame(clock) }; assertEquals(1f, h.overlayAlpha, 0f); assertEquals(0.0, h.stage.orb.sleep.k, 1e-9)
        ctl.pause().stop().destroy()
    }
    @Test fun staleUnlockShowsHomeInstantly() {
        Apps.setForTest(listOf(AppEntry("com.android.chrome", "Chrome", ComponentName("com.android.chrome", "x.Main"), Process.myUserHandle())), emptyMap())
        app.getSharedPreferences("prime", 0).edit().putBoolean("onboarded", true).commit()
        val ctl = Robolectric.buildActivity(HomeActivity::class.java).create(); val h = ctl.get(); h.stage.manualFrames = true; ctl.start().resume().visible()
        val root = h.window.decorView; root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 1080, 2400)
        ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS)
        ctl.pause(); app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF)); ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
        ctl.stop(); app.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT)); ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
        ShadowLooper.idleMainLooper(10_000, TimeUnit.MILLISECONDS) // unlocked into another app 10 s ago (window is 8 s)
        ctl.restart().start().resume(); ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
        assertFalse(h.stage.orb.sleep.asleep); assertEquals("stale unlock: shown at once", 1f, h.overlayAlpha, 0f)
        ctl.pause().stop().destroy()
    }
    // ---------- 0.1.8: unlock straight to Home, the way the phone actually delivers it ----------
    // Phone log (22:54:38): keyguardGoingAway -> HomeActivity.onResume (isKeyguardLocked still TRUE) -> keyguard hidden
    // (+79 ms) -> USER_PRESENT from SystemUI (skipped for NOT_EXPORTED receivers) -> window focus (+281 ms).
    private fun homeUp(): Pair<org.robolectric.android.controller.ActivityController<HomeActivity>, HomeActivity> {
        Apps.setForTest(listOf(AppEntry("com.android.chrome", "Chrome", ComponentName("com.android.chrome", "x.Main"), Process.myUserHandle())), emptyMap())
        app.getSharedPreferences("prime", 0).edit().putBoolean("onboarded", true).commit(); km.setKeyguardLocked(false)
        val ctl = Robolectric.buildActivity(HomeActivity::class.java).create(); val h = ctl.get(); h.stage.manualFrames = true; ctl.start().resume().visible()
        val root = h.window.decorView; root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 1080, 2400)
        ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS); assertFalse(h.stage.orb.sleep.asleep); return ctl to h
    }
    /** Screen off with Home on top, then the phone's under-keyguard resume, then unlock begins (Home resumed, keyguard still "locked"). */
    private fun lockAndStartUnlock(ctl: org.robolectric.android.controller.ActivityController<HomeActivity>, h: HomeActivity) {
        ctl.pause(); app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF)); km.setKeyguardLocked(true); ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS); ctl.stop()
        ctl.restart().start().resume(); ShadowLooper.idleMainLooper(30, TimeUnit.MILLISECONDS); h.onWindowFocusChanged(true) // spurious resume under the keyguard
        assertTrue("still asleep under the keyguard", h.stage.orb.sleep.asleep); assertEquals("hidden under the keyguard", 0f, h.overlayAlpha, 0f)
        ctl.pause().stop(); ShadowLooper.idleMainLooper(1500, TimeUnit.MILLISECONDS)
        ctl.restart().start().resume(); ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS) // keyguardGoingAway -> onResume, still locked
        assertTrue("onResume alone can't see the unlock yet", h.stage.orb.sleep.asleep); assertEquals(0f, h.overlayAlpha, 0f)
    }
    private fun playFade(h: HomeActivity) {
        h.stage.stop(); h.stage.start(); var mid = false
        repeat(170) { clock += 16_666_667L; h.stage.doFrame(clock); val a = h.overlayAlpha; if (a > 0.2f && a < 0.8f) mid = true }
        assertTrue("widgets/rain fade up through the middle", mid); assertEquals(1f, h.overlayAlpha, 0f); assertEquals(0.0, h.stage.orb.sleep.k, 1e-9)
    }
    @Test fun unlockDirectlyToHomePlaysFadeOnFocus() {
        val (ctl, h) = homeUp(); lockAndStartUnlock(ctl, h)
        km.setKeyguardLocked(false); h.onWindowFocusChanged(true) // keyguard gone, Home gets focus; NO USER_PRESENT (the phone skipped it)
        assertTrue("slow wake starts on arrival, not on the next app return", h.stage.awakeOrWaking)
        assertEquals(SleepState.SLOW_WAKE_S, h.stage.orb.sleep.let { h.stage.stop(); h.stage.start(); repeat(3) { clock += 16_666_667L; h.stage.doFrame(clock) }; it.wakeS }, 1e-9)
        playFade(h); ctl.pause().stop().destroy()
    }
    @Test fun unlockDirectlyToHomePlaysFadeViaFrameCheckWithoutFocusOrBroadcast() {
        val (ctl, h) = homeUp(); lockAndStartUnlock(ctl, h)
        km.setKeyguardLocked(false); h.stage.stop(); h.stage.start()
        var n = 0; while (h.stage.orb.sleep.asleep && n < 40) { clock += 16_666_667L; h.stage.doFrame(clock); ShadowLooper.idleMainLooper(17, TimeUnit.MILLISECONDS); n++ }
        assertTrue("frame check wakes within ~0.3 s (took $n frames)", !h.stage.orb.sleep.asleep && n <= 20)
        playFade(h); ctl.pause().stop().destroy()
    }
    @Test fun unlockBroadcastWhileResumedPlaysFade() {
        val (ctl, h) = homeUp(); lockAndStartUnlock(ctl, h)
        app.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT)); ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
        assertTrue(h.stage.awakeOrWaking); km.setKeyguardLocked(false); playFade(h); ctl.pause().stop().destroy()
    }
    @Test fun returnFromAppOrShadeDoesNotReplayFade() {
        val (ctl, h) = homeUp()
        ctl.pause().stop(); ShadowLooper.idleMainLooper(3000, TimeUnit.MILLISECONDS); ctl.restart().start().resume(); h.onWindowFocusChanged(true)
        ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS); repeat(5) { clock += 16_666_667L; h.stage.doFrame(clock) }
        assertFalse(h.stage.orb.sleep.asleep); assertEquals("app return: no fade", 1f, h.overlayAlpha, 0f); assertEquals(0.0, h.stage.orb.sleep.k, 1e-9)
        h.onWindowFocusChanged(false); h.onWindowFocusChanged(true) // notification shade down/up
        repeat(5) { clock += 16_666_667L; h.stage.doFrame(clock) }; assertEquals("shade: no fade", 1f, h.overlayAlpha, 0f); assertEquals(0.0, h.stage.orb.sleep.k, 1e-9)
        ctl.pause().stop().destroy()
    }
    @Test fun unlockReceiverIsExportedSoSystemUiCanDeliverIt() {
        val (ctl, _) = homeUp()
        val w = shadowOf(app).registeredReceivers.filter { it.intentFilter.hasAction(Intent.ACTION_USER_PRESENT) && !it.intentFilter.hasAction(Intent.ACTION_SCREEN_ON) } // lockRx (PrimeApp's also has SCREEN_ON)
        assertTrue("home registers an unlock receiver", w.isNotEmpty())
        assertTrue("registered RECEIVER_EXPORTED (flags ${w.map { it.flags }})", w.all { it.flags and android.content.Context.RECEIVER_EXPORTED != 0 })
        ctl.pause().stop().destroy()
    }

    // ---------- 0.1.9: the other two unlock receivers are exported too (SystemUI sends USER_PRESENT) ----------
    @Test fun primeAppUnlockReceiverIsExported() {
        val w = shadowOf(app).registeredReceivers.filter { it.intentFilter.hasAction(Intent.ACTION_USER_PRESENT) && it.intentFilter.hasAction(Intent.ACTION_SCREEN_ON) }
        assertTrue("PrimeApp registers its screen/unlock receiver", w.isNotEmpty())
        assertTrue("RECEIVER_EXPORTED (flags ${w.map { it.flags }})", w.all { it.flags and android.content.Context.RECEIVER_EXPORTED != 0 })
    }
    @Test fun lockOrbLeavesOnUnlockAndItsReceiverIsExported() {
        val (_, a) = show()
        val w = shadowOf(app).registeredReceivers.filter { it.intentFilter.hasAction(Intent.ACTION_USER_PRESENT) && !it.intentFilter.hasAction(Intent.ACTION_SCREEN_ON) && it.context === a }
            .ifEmpty { shadowOf(app).registeredReceivers.filter { it.intentFilter.hasAction(Intent.ACTION_USER_PRESENT) && !it.intentFilter.hasAction(Intent.ACTION_SCREEN_ON) } }
        assertTrue("lock orb registers an unlock receiver", w.isNotEmpty())
        assertTrue("RECEIVER_EXPORTED (flags ${w.map { it.flags }})", w.all { it.flags and android.content.Context.RECEIVER_EXPORTED != 0 })
        assertFalse(a.isFinishing); app.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT)); idle(50)
        assertTrue("leaves the moment the phone is unlocked", a.isFinishing)
    }

    @Test fun lockOrbIsOffByDefault() { assertFalse(Prefs(app).lockOrb) }
    // ---------- lock-screen orb (0.1.5 auto-arm) ----------
    private val km get() = shadowOf(app.getSystemService(android.app.KeyguardManager::class.java))
    private fun idle(ms: Long) = ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS) // bounded: the orb reposts frames forever
    private fun show(): Pair<org.robolectric.android.controller.ActivityController<LockOrbActivity>, LockOrbActivity> {
        LockOrbActivity.noFramesForTest = true; km.setKeyguardLocked(true)
        val ctl = Robolectric.buildActivity(LockOrbActivity::class.java).create().start().resume().visible(); val a = ctl.get()
        val v = a.window.decorView; v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)); v.layout(0, 0, 1080, 2400)
        return ctl to a
    }
    private fun touch(a: LockOrbActivity) { val e = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 500f, 500f, 0); a.window.decorView.dispatchTouchEvent(e); e.recycle() }

    @Test fun autoArmsUnlockPromptWithoutTouch() {
        val (_, a) = show()
        assertEquals("not before the wake beat", 0, a.armCount)
        idle(LockOrbActivity.ARM_DELAY_MS + 50)
        assertEquals("prompt requested with no touch", 1, a.armCount); assertTrue(a.armed); assertFalse(a.isFinishing)
        idle(3000); assertEquals("armed once, no spam", 1, a.armCount)
        km.setKeyguardLocked(false) // fingerprint accepted -> onDismissSucceeded
        idle(50); assertTrue("goes home on success", a.isFinishing)
    }
    @Test fun cancelledPromptKeepsOrbAndOnlyReArmsOnTouch() {
        val (_, a) = show(); idle(LockOrbActivity.ARM_DELAY_MS + 50); assertEquals(1, a.armCount)
        km.setKeyguardLocked(true) // re-setting locked fires onDismissCancelled on the pending request (user backed out)
        idle(5000)
        assertFalse("orb stays after cancel", a.isFinishing); assertFalse(a.armed); assertEquals("no automatic re-prompt", 1, a.armCount)
        touch(a); assertEquals("touch re-arms", 2, a.armCount); assertTrue(a.armed)
        km.setKeyguardLocked(true); idle(LockOrbActivity.TOUCH_FALLBACK_MS + 200)
        assertFalse("cancel after a touch also keeps the orb", a.isFinishing)
    }
    @Test fun touchWithNoAnswerFallsBackAndLeaves() {
        val (_, a) = show(); touch(a); assertEquals(1, a.armCount)
        idle(LockOrbActivity.TOUCH_FALLBACK_MS + 200); assertTrue("never stuck after a touch", a.isFinishing)
    }
    @Test fun leavesOnUnlockBroadcast() { val (_, a) = show(); app.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT)); idle(50); assertTrue(a.isFinishing) }
    @Test fun leavesOnScreenOff() { val (_, a) = show(); app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF)); idle(50); assertTrue(a.isFinishing) }
    @Suppress("DEPRECATION") @Test fun leavesOnBack() { val (_, a) = show(); a.onBackPressed(); assertTrue(a.isFinishing) }
    @Test fun leavesAfterMaxShowTimeout() {
        val (_, a) = show(); idle(LockOrbActivity.ARM_DELAY_MS + 50); km.setKeyguardLocked(true) // cancelled, orb idling
        idle(LockOrbActivity.MAX_SHOW_MS + 500); assertTrue("time cap", a.isFinishing)
    }
    @Test fun neverShowsWhenUnlocked() {
        LockOrbActivity.noFramesForTest = true; km.setKeyguardLocked(false)
        val b = Robolectric.buildActivity(LockOrbActivity::class.java).create().get(); assertTrue(b.isFinishing); assertEquals(0, b.armCount)
    }
    @Test fun promptErrorLeaves() {
        // Unlocked between show and arm: Robolectric reports onDismissError -> overlay must get out of the way.
        val (_, a) = show(); km.setKeyguardLocked(false); idle(LockOrbActivity.ARM_DELAY_MS + 50); assertTrue(a.isFinishing)
    }
}
