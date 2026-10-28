package com.tyler.primelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
        // beats ignore per-item cooldowns but never record, so bubble pacing is untouched
        val st = SchedState(); Scheduler.record(st, OdstAntics.ROUNDS, Kind.ACT, ctx(12.0)); assertTrue(OdstAntics.ROUNDS in ids(ctx(12.1)))
    }
}
