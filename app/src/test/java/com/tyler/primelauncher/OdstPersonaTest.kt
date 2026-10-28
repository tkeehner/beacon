package com.tyler.primelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** 0.1.17 ODST skin: line bank, PA voice, Vergil lines, pool + Rain Watch gating, face flip, chime. Placeholder data only. */
class OdstPersonaTest {
    private val per = OdstPersona
    private val day = 2026301L
    private fun ctx(h: Double, raining: Boolean = false) = SchedCtx(day * 1440 + (h * 60).toLong(), h, day, raining = raining)

    @Test fun registeredAsThirdSkin() {
        assertEquals(OdstPersona, Personas.of("odst")); assertTrue(OdstPersona in Personas.all); assertEquals(MatrixPersona, Personas.of("anything"))
        assertEquals(Reveal.PA, per.reveal); assertTrue(per.upper)
    }
    @Test fun publicAddressVoiceIsAllCapsWithDepartmentHeaders() {
        for ((cat, ls) in per.lines) { assertTrue("$cat >= 9", ls.size >= 9)
            for (l in ls) { val stripped = l.replace(Regex("\\{[a-z]+\\}"), ""); assertEquals(l, stripped.uppercase(), stripped) } }
        for ((q, a) in per.jokes) { assertEquals(q, q.uppercase()); assertEquals(a, a.uppercase()) }
        for (c in Cat.values()) assertTrue("header for $c", per.badges[c]?.startsWith("\u25A3 ") == true)
    }
    @Test fun noGoodnightNoNamesBaked() {
        val all = per.lines.values.flatten() + per.jokes.flatMap { listOf(it.first, it.second) } + per.vergil
        for (l in all) { val low = l.lowercase(); assertFalse(l, "goodnight" in low || "good night" in low) }
    }
    @Test fun vergilIsSoftLowercaseAndRare() {
        assertTrue(per.vergil.size >= 6); for (l in per.vergil) assertEquals(l, l.lowercase(), l)
        assertTrue(MatrixPersona.vergil.isEmpty() && NeonPersona.vergil.isEmpty())
        assertNotNull(Antics[OdstAntics.VERGIL]); assertEquals(AK.ACT, Antics[OdstAntics.VERGIL]!!.kind)
    }
    @Test fun poolIdsExistAndHaveChimeCues() {
        for (it in per.pool) if (it.kind == Kind.ACT) assertNotNull(it.id, Antics[it.id]) else assertNotNull(it.id, PoolIds.cat(it.id))
        for (id in listOf(OdstAntics.ROUNDS, OdstAntics.TRAFFIC, OdstAntics.BILLBOARD, OdstAntics.TIDY, OdstAntics.SIGNAL, OdstAntics.RAINWATCH, OdstAntics.PODDROP)) {
            val a = Antics[id]!!; assertTrue("$id cue", a.cues.isNotEmpty()); for (c in a.cues) assertTrue("$id cue in range", c > 0 && c < a.len) }
        assertTrue(Antics[OdstAntics.PODDROP_QUIET]!!.cues.isEmpty()); assertEquals(AK.CORE, Antics[OdstAntics.PODDROP]!!.kind)
    }
    @Test fun rainWatchOnlyWhenRainingOrAfterDark() {
        val pool = listOf(PoolItem("rw", Kind.ACT, 1.0, 0, rainOrNight = true))
        assertNull("dry afternoon", Scheduler.tick(SchedState(), pool, ctx(14.0)) { 0.0 })
        assertNotNull("rainy afternoon", Scheduler.tick(SchedState(), pool, ctx(14.0, raining = true)) { 0.0 })
        assertNotNull("dry evening", Scheduler.tick(SchedState(), pool, ctx(20.0)) { 0.0 })
        assertNull("quiet hours still win", Scheduler.tick(SchedState(), pool, ctx(23.8, raining = true)) { 0.0 })
        val rw = per.pool.first { it.id == OdstAntics.RAINWATCH }; assertTrue(rw.rainOrNight)
        val tidy = per.pool.first { it.id == OdstAntics.TIDY }; assertEquals(17.0, tidy.from, 0.0); assertEquals(23.0, tidy.to, 0.0)
    }
    @Test fun odstDayKeepsCapsAndQuietHours() {
        val st = SchedState(); var bubbles = 0; val rnd = java.util.Random(7)
        var m = 7 * 60L; while (m < 24 * 60) { val h = m / 60.0; val c = SchedCtx(day * 1440 + m, h, day, raining = true)
            val pick = Scheduler.tick(st, per.pool, c) { rnd.nextDouble() }
            if (pick != null) { assertFalse("quiet at $h", Scheduler.quiet(h)); if (pick.kind == Kind.BUB) bubbles++ }
            m += Rules.TICK_MIN }
        assertTrue("cap", bubbles <= Rules.BUB_DAY_CAP)
    }
    @Test fun visrAutoRule() {
        val night = Wx(50, 61, false, 60, 40, "Placeholder", 1_000L, 0.0, 0.0, false)
        assertTrue(visrNight(night, 12, 2_000L)); assertFalse(visrNight(night.copy(day = true), 22, 2_000L))
        assertTrue("stale forecast falls back to the clock", visrNight(night.copy(day = true), 22, 1_000L + 7 * 3600_000L))
        assertFalse(visrNight(null, 12, 0L)); assertTrue(visrNight(null, 19, 0L)); assertTrue(visrNight(null, 5, 0L)); assertFalse(visrNight(null, 6, 0L))
        assertTrue(isRainCode(61) && isRainCode(81) && isRainCode(95)); assertFalse(isRainCode(0) || isRainCode(3) || isRainCode(71))
    }
    @Test fun exprTargetAndFlip() {
        val p = Pose(); p.reset(); assertEquals(Expr.EYES, Expr.target(p, 0.0)); assertEquals(Expr.SLEEPING, Expr.target(p, 0.9))
        p.happy = 0.8; assertEquals(Expr.HAPPY, Expr.target(p, 0.0)); p.expr = Expr.ANGRY; assertEquals(Expr.ANGRY, Expr.target(p, 0.9))
        val f = FaceFlip(); f.step(0.0, Expr.EYES); assertEquals(Expr.EYES, f.face)
        f.step(1.0, Expr.HAPPY); f.step(1.05, Expr.HAPPY); assertEquals("old face closes first", Expr.EYES, f.face); assertTrue(f.sx < 1)
        f.step(1.2, Expr.HAPPY); assertEquals(Expr.HAPPY, f.face); f.step(1.4, Expr.HAPPY); assertEquals(1.0, f.sx, 1e-9)
        f.step(1.45, Expr.EYES); assertEquals("pop holds before flipping back", Expr.HAPPY, f.face)
        f.step(2.0, Expr.EYES); f.step(2.4, Expr.EYES); assertEquals(Expr.EYES, f.face)
    }
    @Test fun chimeIsOriginalFourNotesAndQuietAtTheEnd() {
        val s = Chime.samples(); assertEquals((Chime.RATE * 1.4).toInt(), s.size)
        val peak = s.maxOf { abs(it.toInt()) }; assertTrue(peak in 12000..14500)
        assertTrue("decays", abs(s.last().toInt()) < 300)
        for (i in 0 until 4) { val at = ((i * 0.15 + 0.05) * Chime.RATE).toInt(); assertTrue("note $i onset", (at until at + 200).any { abs(s[it].toInt()) > 1500 }) }
        assertEquals(0.0, OdstArt.chimePulse(-0.1), 0.0); assertTrue(OdstArt.chimePulse(0.05) > 0.3)
    }
}
