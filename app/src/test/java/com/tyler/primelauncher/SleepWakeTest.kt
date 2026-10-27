package com.tyler.primelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Orb sleep envelope + when the home screen plays the wake-up. Pure JVM. */
class SleepWakeTest {
    @Test fun asleepDropsLidRaisesTiredSlowsAndDims() {
        val s = SleepState(); s.sleepNow()
        assertEquals(SleepState.LID, s.lid(1.0), 1e-9); assertEquals(1.0, s.tired(0.0), 1e-9)
        assertTrue(s.pace(1.0) < 0.25)
        val dim = (0..56).map { s.bright(1.0, it / 10.0) }
        assertTrue("dimmed", dim.max() < 0.65); assertTrue("still breathing", dim.max() - dim.min() > 0.2)
    }
    @Test fun awakeIsIdentity() {
        val s = SleepState(); assertEquals(0.8, s.lid(0.8), 1e-9); assertEquals(0.2, s.tired(0.2), 1e-9); assertEquals(1.0, s.pace(1.0), 1e-9); assertEquals(0.9, s.bright(0.9, 3.0), 1e-9)
        assertFalse("nothing to wake", s.wake())
    }
    @Test fun wakeRampsOpenWithSurge() {
        val s = SleepState(); s.sleepNow(); assertTrue(s.wake())
        var peak = 0.0; var t = 0.0
        while (t < 2.0) { s.step(1 / 60.0); t += 1 / 60.0; peak = maxOf(peak, s.surge()) }
        assertEquals(0.0, s.k, 1e-9); assertEquals(1.0, s.lid(1.0), 1e-9); assertTrue("glow surge on wake", peak > 0.3)
        assertEquals(0.0, s.surge(), 1e-9)
    }
    @Test fun fallAsleepIsGradual() {
        val s = SleepState(); s.fallAsleep(); s.step(0.5); assertTrue(s.k > 0 && s.k < 0.5); repeat(300) { s.step(1 / 60.0) }; assertEquals(1.0, s.k, 1e-9)
    }
    @Test fun gateUnlockOntoHome() {
        val g = WakeGate(); g.screenOff(); g.paused()
        assertEquals(WakeGate.Act.NONE, g.unlocked(1000))           // unlock broadcast arrives before Home resumes
        assertEquals(WakeGate.Act.WAKE, g.resumed(1300, locked = false))
        assertEquals(WakeGate.Act.NONE, g.resumed(2000, false))     // already awake
    }
    @Test fun gateResumeBehindKeyguardThenUnlock() {
        val g = WakeGate(); g.screenOff()
        assertEquals(WakeGate.Act.NONE, g.resumed(100, locked = true))
        assertEquals(WakeGate.Act.WAKE, g.unlocked(900))
    }
    @Test fun gateNoSecureLock() {
        val g = WakeGate(); g.screenOff(); assertEquals(WakeGate.Act.WAKE, g.resumed(50, locked = false))
    }
    @Test fun gateUnlockedIntoOtherAppThenHomeLater() {
        val g = WakeGate(); g.screenOff(); g.paused(); g.unlocked(0)
        assertEquals(WakeGate.Act.AWAKE_QUIET, g.resumed(60_000, false)); assertFalse(g.asleep)
    }
    @Test fun gateNotAsleepNeverWakes() {
        val g = WakeGate(); assertEquals(WakeGate.Act.NONE, g.resumed(0, false)); g.paused(); assertEquals(WakeGate.Act.NONE, g.unlocked(10))
    }
    @Test fun gateArrivalAfterLockedResume() {
        val g = WakeGate(); g.screenOff(); g.paused()
        assertEquals("phone order: resume while keyguard still locked", WakeGate.Act.NONE, g.resumed(1000, locked = true))
        assertEquals("focus while still locked", WakeGate.Act.NONE, g.arrived(1100, locked = true))
        assertEquals("keyguard gone -> arrival wakes", WakeGate.Act.WAKE, g.arrived(1300, locked = false))
        assertEquals("only once", WakeGate.Act.NONE, g.arrived(1400, locked = false)); assertFalse(g.asleep)
    }
    @Test fun gateArrivalNeedsResumeAndSleep() {
        val g = WakeGate(); assertEquals(WakeGate.Act.NONE, g.arrived(0, false)) // never slept: focus changes do nothing
        g.screenOff(); assertEquals("not resumed yet", WakeGate.Act.NONE, g.arrived(10, false)); assertTrue(g.asleep)
        g.paused(); g.unlocked(0); g.resumed(1, locked = true); assertEquals("stale unlock -> instant", WakeGate.Act.AWAKE_QUIET, g.arrived(60_000, false))
    }
}
