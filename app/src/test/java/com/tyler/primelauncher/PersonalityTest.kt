package com.tyler.primelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** 0.1.13 orb personality: glance/saccade scheduler, perk-ups, fidgets, yawns, envelopes and mood contrast. Pure JVM. */
class PersonalityTest {
    private class Run(val looks: Int, val perks: List<Double>, val fidgets: Int, val yawns: List<Double>, val maxReach: Double, val darts: Int, val c: Curiosity)
    private fun run(secs: Double, lively: Double = 1.0, amp: Double = 1.0, yawny: Boolean = false, seed: Int = 7, idle: (Double) -> Boolean = { true }): Run {
        val c = Curiosity(Rng(seed)).apply { this.lively = lively; this.amp = amp; this.yawny = yawny }
        var t = 0.0; val perks = ArrayList<Double>(); val yawns = ArrayList<Double>(); var fidgets = 0; var reach = 0.0; var darts = 0
        while (t < secs) { t += 1 / 60.0; val ev = c.step(t, idle(t))
            if (ev and Curiosity.PERK != 0) perks.add(t); if (ev and Curiosity.YAWN != 0) yawns.add(t); if (ev and Curiosity.FIDGET != 0) fidgets++
            if (ev and Curiosity.GLANCE != 0) { reach = maxOf(reach, hypot(c.tx, c.ty)); if (c.rate == Curiosity.DART_RATE) darts++ } }
        return Run(c.looks, perks, fidgets, yawns, reach, darts, c)
    }

    @Test fun idleGlancesAreMoreFrequentAndWiderThanBefore() {
        val r = run(300.0)
        // Pre-0.1.13: one glance every 3.5-8 s (~52 in 5 min), reach 0.05-0.11 of R.
        assertTrue("looks=${r.looks}", r.looks >= 100) // 0.1.13 floor was 70
        assertTrue("reach=${r.maxReach}", r.maxReach > 0.2 && r.maxReach <= Curiosity.MAX_K * 1.2 + 0.03)
        assertTrue("saccades: more darts than looks (micro-saccades / double-takes)", r.darts > r.looks)
    }
    @Test fun sometimesDoubleTakesAndLocksOn() {
        val r = run(600.0); assertTrue("double-takes=${r.c.doubleTakes}", r.c.doubleTakes >= 40); assertTrue("lock-ons=${r.c.lockOns}", r.c.lockOns >= 5)
    }
    @Test fun dartsAreFastThenSettleSlowly() {
        val c = Curiosity(Rng(3)); var t = 0.0; var sawDart = false; var sawSettle = false
        while (t < 30) { t += 1 / 60.0; val ev = c.step(t, true)
            if (ev and Curiosity.GLANCE != 0) { if (c.rate == Curiosity.DART_RATE) sawDart = true else if (c.rate == Curiosity.EASE_RATE) { sawSettle = true; assertTrue("settles near centre", hypot(c.tx, c.ty) < Curiosity.MAX_K * Curiosity.PERK_REACH * 1.08 * Curiosity.SETTLE + 1e-6) } } }
        assertTrue(sawDart); assertTrue(sawSettle)
    }
    @Test fun perkUpEvery8To16Seconds() {
        val p = run(600.0).perks; assertTrue("perks=${p.size}", p.size in 35..80)
        p.zipWithNext { a, b -> b - a }.forEach { assertTrue("gap $it", it >= Curiosity.PERK_MIN_S - 0.05 && it <= Curiosity.PERK_MAX_S + 0.05) }
    }
    @Test fun fidgetsKeepItFromSittingStill() { val r = run(120.0); assertTrue("fidgets=${r.fidgets}", r.fidgets >= 15) }
    @Test fun moodScalesCuriosity() {
        val morning = run(300.0, lively = Logic.mood(7.0, 0.8f, false).lively); val night = run(300.0, lively = Logic.mood(23.5, 0.8f, false).lively)
        assertTrue("morning ${morning.looks} > day-ish, night ${night.looks}", morning.looks > night.looks * 2)
        assertTrue(morning.fidgets > night.fidgets); assertTrue(morning.perks.size > night.perks.size)
    }
    @Test fun reduceMotionHalvesReach() {
        val full = run(300.0); val reduced = run(300.0, amp = 0.5)
        assertEquals(full.maxReach * 0.5, reduced.maxReach, full.maxReach * 0.12)
    }
    @Test fun yawnsOnlyWhenSleepy() {
        assertTrue(run(300.0, yawny = false).yawns.isEmpty())
        val y = run(300.0, lively = 0.3, yawny = true).yawns; assertTrue("yawns=${y.size}", y.size in 4..14)
        y.zipWithNext { a, b -> b - a }.forEach { assertTrue(it >= 22.0) }
    }
    @Test fun busyOrbDoesNothingAndTimersDontPileUp() {
        val r = run(60.0, idle = { false }); assertEquals(0, r.looks); assertTrue(r.perks.isEmpty()); assertEquals(0, r.fidgets)
        // Back to idle after a long busy spell: no burst of queued events in the first frame.
        val c = Curiosity(Rng(7)); var t = 0.0; while (t < 60) { t += 1 / 60.0; c.step(t, false) }
        val ev = c.step(t + 1 / 60.0, true); assertEquals(0, ev and (Curiosity.PERK or Curiosity.FIDGET or Curiosity.YAWN))
    }
    @Test fun touchSnapTakesOverTheEyes() {
        val c = Curiosity(Rng(1)); var t = 0.0; while (!c.busy) { t += 1 / 60.0; c.step(t, true) }
        c.snap(t); assertFalse("plan dropped", c.busy); assertEquals(Curiosity.TOUCH_RATE, c.rate, 0.0)
        c.step(t + 0.5, false); assertEquals("snap is brief", Curiosity.EASE_RATE, c.rate, 0.0)
        assertTrue("no glance right after a touch", c.nextGlance >= t + 3 - 1e-9)
    }
    @Test fun envelopes() {
        assertEquals(0.0, Env.perk(-0.1), 0.0); assertTrue(Env.perk(0.2) > 0.95); assertTrue(Env.perk(1.4) < 0.06); assertEquals(0.0, Env.perk(Env.PERK_S), 0.0)
        assertEquals(1.0, Env.yawnShut(1.0), 1e-9); assertTrue("slow close", Env.yawnShut(0.3) < 0.5); assertEquals(0.0, Env.yawnShut(Env.YAWN_S), 0.0)
        assertTrue("a yawn is much longer than a blink (0.24-0.64 s)", Env.YAWN_S > 2.0)
        assertTrue(Env.surge(0.08) > 0.7); assertTrue(Env.surge(1.2) < 0.03); assertEquals(0.0, Env.fidget(Env.FIDGET_S), 0.0)
    }
    @Test fun moodContrast() {
        val m = Logic.mood(7.0, 0.8f, false); val d = Logic.mood(13.0, 0.8f, false); val e = Logic.mood(19.0, 0.8f, false); val n = Logic.mood(23.5, 0.8f, false)
        assertTrue(m.pace > d.pace && d.pace > e.pace && e.pace > n.pace); assertTrue(m.bright > d.bright && d.bright > e.bright && e.bright > n.bright)
        assertTrue(m.lively > d.lively && d.lively > e.lively && e.lively > n.lively); assertTrue("perkier morning than 0.1.13 (pace 1.3, lively 1.45)", m.pace >= 1.45 && m.lively >= 1.7)
        assertTrue("sleepier night than 0.1.13 (pace 0.45, lid 0.7, lively 0.4)", n.pace <= 0.4 && n.lid <= 0.62 && n.tired >= 0.45 && n.lively <= 0.3); assertTrue(n.yawn); assertFalse(m.yawn || d.yawn || e.yawn)
        val low = Logic.mood(13.0, 0.1f, false)
        assertTrue("low battery droops harder than 0.1.12 (lid 0.62, pace 0.65)", low.lid <= 0.45 && low.pace <= 0.4 && low.lively < 0.4)
        assertEquals(d.lively, Logic.mood(13.0, 0.1f, true).lively, 0.0) // charging: not tired
    }
}
