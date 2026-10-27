package com.tyler.primelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** 0.1.13: the orb wires the personality in (touch snap, lean, mode-entry kicks, surges, yawns, Reduce Motion). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrbPersonalityTest {
    private fun orb(neon: Boolean = false) = Orb(205f, 430f, if (neon) 96f else 102f, if (neon) Skin.NEON else Skin.MATRIX, 2.625f, 1.0, null, true)
    private fun Orb.run(s: Double) { var k = 0.0; while (k < s) { step(1 / 60.0); k += 1 / 60.0 } }

    @Test fun touchDownSnapsFastAndBounces() {
        val a = orb(); a.run(1.0); a.touchDown(a.cx + 300f, a.cy)
        assertTrue(a.kickAge < 0.01); assertTrue("0.1.14: much bigger than a plain kick", a.kickStrength >= Bold.TOUCH_KICK && a.kickStrength > 1.8); assertEquals(Curiosity.TOUCH_RATE, a.gazeRate, 0.0)
        a.run(0.15); val snapped = a.gx
        val b = orb(); b.run(1.0); b.touch(b.cx + 300f, b.cy); b.run(0.15)
        assertTrue("touchDown $snapped vs old soft drift ${b.gx}", snapped > b.gx * 1.5 && snapped > 0.08)
    }
    @Test fun sphereLeansTowardGaze() {
        val o = orb(); o.run(0.5); o.touchDown(o.cx + 400f, o.cy - 50f); o.run(0.4)
        assertTrue("leans ~2x 0.1.13: ${o.leanX()}", o.leanX() > o.r * 0.06); assertTrue(o.roll() > 3.0); assertTrue(o.leanX() < o.r * 0.11); assertTrue(o.roll() < 7.0)
    }
    @Test fun idleOrbLooksAround() {
        val o = orb(); var moves = 0; var last = 0.0; var t = 0.0
        while (t < 30) { o.step(1 / 60.0); t += 1 / 60.0; if (abs(o.gx - last) > 0.002) moves++; last = o.gx }
        assertTrue("looks=${o.curious.looks}", o.curious.looks >= 10); assertTrue(moves > 100)
    }
    @Test fun enteringThinkKicksAndSurges() {
        for (neon in listOf(false, true)) { val o = orb(neon); o.run(1.0); o.mode = Mode.THINK; o.step(1 / 60.0)
            assertTrue(o.kickAge < 0.05); assertTrue(o.kickStrength >= 2.2); o.run(0.1); assertTrue("surge", o.surgeLevel() > 0.5) }
        val s = orb(); s.run(1.0); s.mode = Mode.SUMMON; s.step(1 / 60.0); assertTrue(s.kickStrength >= 1.8)
    }
    @Test fun thinkingRadiusPulsesHarder() {
        val o = orb(); o.mode = Mode.THINK; o.run(1.0); var lo = 1e9; var hi = 0.0
        repeat(120) { o.step(1 / 60.0); val r = o.radius().toDouble(); lo = minOf(lo, r); hi = maxOf(hi, r) }
        assertTrue("radius swing ${hi - lo}", hi - lo > o.r * 0.13)
    }
    @Test fun quickWakeRecoilsSlowWakePopsOnce() {
        val a = orb(); a.sleep.sleepNow(); a.wake(); assertTrue(a.kickStrength >= 2.3)
        val b = orb(); b.run(0.1); b.sleep.sleepNow(); val before = b.kickAge; b.wake(SleepState.SLOW_WAKE_S); assertTrue(b.kickAge >= before)
        var popped = false; repeat(240) { b.step(1 / 60.0); if (b.kickAge < 0.02 && b.kickStrength >= 1.5) popped = true }; assertTrue(popped)
    }
    @Test fun nightYawnIsALongDeepBlink() {
        val o = orb(); o.yawny = true; o.lively = 0.3; var maxShut = 0.0; var shutFrames = 0; var t = 0.0
        while (t < 120) { o.step(1 / 60.0); t += 1 / 60.0; maxShut = maxOf(maxShut, o.yawnLevel()); if (o.yawnLevel() > 0.9) shutFrames++ }
        assertEquals(1.0, maxShut, 1e-6); assertTrue("held shut ~0.5 s+ per yawn", shutFrames > 25)
    }
    @Test fun reduceMotionTonesItDown() {
        val full = orb(); full.run(0.5); full.touchDown(full.cx + 400f, full.cy); full.run(0.4)
        val red = orb(); red.amp = 0.5; red.run(0.5); red.touchDown(red.cx + 400f, red.cy); red.run(0.4)
        assertTrue(red.kickStrength < full.kickStrength); assertTrue("lean ${red.leanX()} vs ${full.leanX()}", red.leanX() < full.leanX() * 0.5)
        var maxFull = 0.0; var maxRed = 0.0; val a = orb(); val b = orb().apply { amp = 0.5 }
        repeat(3600) { a.step(1 / 60.0); b.step(1 / 60.0); maxFull = maxOf(maxFull, abs(a.gx)); maxRed = maxOf(maxRed, abs(b.gx)) }
        assertTrue("idle reach $maxRed vs $maxFull", maxRed < maxFull * 0.65)
    }
    @Test fun frozenLockScreenOrbStaysPut() {
        val o = orb().apply { still = true }; o.run(30.0); assertEquals(0, o.curious.looks); assertEquals(0.0, o.leanX(), 1e-9)
    }
}
