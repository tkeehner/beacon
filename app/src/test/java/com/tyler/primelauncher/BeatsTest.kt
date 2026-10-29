package com.tyler.primelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.1.18 idle beats (pure rules): 4-6 s cadence, ~3 in 10 are activities, no back-to-back repeats, gates kept. */
class BeatsTest {
    private val day = 2026301L
    private fun ctx(h: Double, busy: Boolean = false, raining: Boolean = false, battLow: Boolean = false) =
        SchedCtx(day * 1440 + (h * 60).toLong(), h, day, false, busy, battLow, raining)

    @Test fun intervalIsFourToSixSeconds() {
        assertEquals(4.0, Beats.interval(0.0), 1e-9); assertEquals(6.0, Beats.interval(1.0), 1e-9); assertEquals(5.0, Beats.interval(0.5), 1e-9)
        val r = java.util.Random(3); repeat(1000) { val i = Beats.interval(r.nextDouble()); assertTrue(i in 4.0..6.0) }
    }
    @Test fun neverTheSameBeatTwiceInARow() {
        val r = java.util.Random(11)
        for (per in Personas.all) { var last: String? = null; val acts = per.pool.filter { it.kind == Kind.ACT }
            repeat(3000) { val id = Beats.next(r.nextDouble(), r.nextDouble(), per.micros, acts, last); assertNotNull(id); assertNotEquals("${per.id} repeat", last, id); last = id } }
    }
    @Test fun aboutThreeInTenBeatsAreActivities() {
        val r = java.util.Random(5)
        for (per in Personas.all) { var last: String? = null; var acts = 0; val n = 20000; val pool = per.pool.filter { it.kind == Kind.ACT }
            repeat(n) { val id = Beats.next(r.nextDouble(), r.nextDouble(), per.micros, pool, last)!!; if (!Micros.isMicro(id) && Antics[id]!!.kind == AK.ACT) acts++; last = id }
            val share = acts.toDouble() / n; assertTrue("${per.id} share $share", share in 0.26..0.34) }
    }
    @Test fun noAllowedActivityMeansAMicro() {
        val r = java.util.Random(9); repeat(500) { val id = Beats.next(0.0, r.nextDouble(), Micros.ODST, emptyList(), null)!!; assertTrue(id in Micros.ODST) }
    }
    @Test fun everyPersonaHasAVariedThemedMicroPool() {
        for (per in Personas.all) { assertTrue(per.id, per.micros.distinct().size >= 8)
            for (id in per.micros) { val a = Antics[id]; assertNotNull(id, a); assertEquals(id, AK.QUIRK, a!!.kind); assertTrue("$id short", a.len <= 4.0) } }
        assertTrue(Micros.GLYPH in MatrixPersona.micros && Micros.RAIN in MatrixPersona.micros)
        assertTrue(Micros.FLICKER in NeonPersona.micros && Micros.JOLT in NeonPersona.micros)
        assertTrue(Micros.SCAN in OdstPersona.micros && Micros.FLIP in OdstPersona.micros && Micros.PING in OdstPersona.micros)
        assertTrue(Micros.GLYPH !in OdstPersona.micros && Micros.FLICKER !in MatrixPersona.micros)
    }
    @Test fun beatActivitiesKeepTimeOfDayRainQuietBusyAndBatteryGates() {
        val ids = { c: SchedCtx -> Scheduler.beatActs(OdstPersona.pool, c).map { it.id } }
        assertTrue(OdstAntics.TIDY !in ids(ctx(14.0))); assertTrue(OdstAntics.TIDY in ids(ctx(18.0)))
        assertTrue(OdstAntics.RAINWATCH !in ids(ctx(14.0))); assertTrue(OdstAntics.RAINWATCH in ids(ctx(14.0, raining = true))); assertTrue(OdstAntics.RAINWATCH in ids(ctx(20.0)))
        assertTrue(ids(ctx(23.8)).isEmpty()); assertTrue(ids(ctx(3.0)).isEmpty()); assertTrue(ids(ctx(12.0, busy = true)).isEmpty()); assertTrue(ids(ctx(12.0, battLow = true)).isEmpty())
        assertTrue(Antics.GARDEN !in Scheduler.beatActs(MatrixPersona.pool, ctx(14.0)).map { it.id }); assertTrue(Antics.GARDEN in Scheduler.beatActs(MatrixPersona.pool, ctx(8.0)).map { it.id })
        // activity beats ignore per-item cooldowns and never record
        val st = SchedState(); Scheduler.record(st, OdstAntics.ROUNDS, Kind.ACT, ctx(12.0)); assertTrue(OdstAntics.ROUNDS in ids(ctx(12.1)))
    }

    // ---------- 0.1.22 beat bubbles ----------
    private fun sctx(sec: Long, busy: Boolean = false, battLow: Boolean = false): SchedCtx { val h = sec / 3600.0
        return SchedCtx(day * 1440 + sec / 60, h, day, false, busy, battLow, false, day * 86400 + sec) }

    /** Simulate home on screen 07:00-23:30 with a beat every 4-6 s plus ~3 s of antic: bubbles average ~2 min apart. */
    @Test fun beatBubblesAverageAboutTwoMinutesInEveryTheme() {
        for (per in Personas.all) { val st = SchedState(); val r = java.util.Random(per.id.hashCode().toLong()); val times = ArrayList<Long>()
            var sec = 7 * 3600L + 1; var lastId = ""
            while (sec < 9 * 3600L) { val p = Scheduler.beatBubble(st, per.pool, sctx(sec), r.nextDouble(), r.nextDouble())
                if (p != null) { assertNotEquals("${per.id} repeat kind", lastId, p.id); lastId = p.id; times.add(sec); sec += 7 } // bubble shows ~7 s
                sec += (Beats.interval(r.nextDouble()) + 3).toLong() }
            val gaps = times.zipWithNext { a, b -> b - a }; assertTrue(per.id, gaps.isNotEmpty())
            assertTrue("${per.id} min gap", gaps.all { it >= Rules.BEAT_BUB_GAP_S })
            val avg = gaps.average(); assertTrue("${per.id} avg $avg", avg in 95.0..170.0) }
    }
    @Test fun beatBubblesKeepQuietBusyCapAndMutes() {
        val pool = listOf(PoolItem("j", Kind.BUB, 1.0, 999), PoolItem("m", Kind.BUB, 1.0, 999))
        assertNull(Scheduler.beatBubble(SchedState(), pool, sctx(23 * 3600L + 40 * 60), 0.0, 0.0))
        assertNull(Scheduler.beatBubble(SchedState(), pool, sctx(5 * 3600L), 0.0, 0.0))
        assertNull(Scheduler.beatBubble(SchedState(), pool, sctx(12 * 3600L, busy = true), 0.0, 0.0))
        assertNull("low battery: reminders only", Scheduler.beatBubble(SchedState(), pool, sctx(12 * 3600L, battLow = true), 0.0, 0.0))
        assertNull("roll misses", Scheduler.beatBubble(SchedState(), pool, sctx(12 * 3600L), Rules.P_BEAT_BUB, 0.0))
        val st = SchedState(); var sec = 8 * 3600L; var n = 0
        while (sec < 22 * 3600L) { if (Scheduler.beatBubble(st, pool, sctx(sec), 0.0, 0.0) != null) n++; sec += 91 }
        assertEquals("daily cap", Rules.BUB_DAY_CAP, n)
        val st2 = SchedState(); Scheduler.dismiss(st2, "j", sctx(12 * 3600L))
        repeat(20) { i -> assertEquals("m", Scheduler.beatBubble(st2, pool, sctx(12 * 3600L + i * 100L), 0.0, 0.0)?.id ?: "m") }
    }
    @Test fun beatBubbleGapCoversEventBubblesAndSurvivesRestart() {
        val pool = listOf(PoolItem("j", Kind.BUB, 1.0, 0), PoolItem("m", Kind.BUB, 1.0, 0)); val st = SchedState()
        Scheduler.record(st, "greet", Kind.BUB, sctx(12 * 3600L))
        assertNull(Scheduler.beatBubble(st, pool, sctx(12 * 3600L + 60), 0.0, 0.0))
        val back = SchedState.decode(st.encode()); assertEquals(st.encode(), back.encode())
        assertNull(Scheduler.beatBubble(back, pool, sctx(12 * 3600L + 89), 0.0, 0.0))
        assertNotNull(Scheduler.beatBubble(back, pool, sctx(12 * 3600L + 90), 0.0, 0.0))
    }
    @Test fun reminderBubblesKeepTheirCooldowns() {
        val brk = PoolItem("break", Kind.BUB, 1.0, 240, reminder = true); val joke = PoolItem("joke", Kind.BUB, 1.0, 240); val st = SchedState()
        assertEquals("break", Scheduler.beatBubble(st, listOf(brk, joke), sctx(12 * 3600L), 0.0, 0.0)?.id)
        assertEquals("joke", Scheduler.beatBubble(st, listOf(brk, joke), sctx(12 * 3600L + 100), 0.0, 0.0)?.id)
        assertNull("break cooling, joke can't repeat back to back", Scheduler.beatBubble(st, listOf(brk, joke), sctx(12 * 3600L + 200), 0.0, 0.0))
        assertEquals("joke ignores its 240-min cooldown in beats", "joke", Scheduler.beatBubble(st, listOf(joke, PoolItem("m", Kind.BUB, 1.0, 0)), sctx(12 * 3600L + 300), 0.0, 0.0)?.let { Scheduler.beatBubble(st, listOf(joke), sctx(12 * 3600L + 400), 0.0, 0.0)?.id })
    }
}
