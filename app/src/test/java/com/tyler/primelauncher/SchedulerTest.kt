package com.tyler.primelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.1.15 companion scheduler: cooldowns, quiet hours, no back-to-back repeats, gaps, daily bubble cap, mutes, line bags. */
class SchedulerTest {
    private val day = 2026300L
    private fun ctx(min: Long, busy: Boolean = false, weekend: Boolean = false, battLow: Boolean = false, d: Long = day) =
        SchedCtx(d * 1440 + min, min / 60.0, d, weekend, busy, battLow)
    private val ACT_R = { 0.0 }            // r < P_ACT -> activity, first eligible
    private val BUB_R = { Rules.P_ACT + 0.01 } // P_ACT <= r < P_ACT + P_BUB -> bubble, first eligible

    @Test fun quietHoursBlockEverything() {
        val pool = listOf(PoolItem("a", Kind.ACT, 1.0, 0), PoolItem("b", Kind.BUB, 1.0, 0))
        for (h in listOf(23.5, 23.9, 0.0, 3.0, 6.99)) {
            val st = SchedState(); val c = ctx((h * 60).toLong())
            assertTrue("quiet at $h", Scheduler.quiet(h))
            assertNull(Scheduler.tick(st, pool, c, ACT_R)); assertNull(Scheduler.tick(st, pool, c, BUB_R))
            assertFalse(Scheduler.canBubble(st, c, "b")); assertFalse(Scheduler.canAct(st, c))
        }
        for (h in listOf(7.0, 12.0, 23.4)) { assertFalse(Scheduler.quiet(h)); assertNotNull(Scheduler.tick(SchedState(), pool, ctx((h * 60).toLong()), ACT_R)) }
    }

    @Test fun busyBlocksAndLowBatteryOnlyAllowsReminders() {
        val pool = listOf(PoolItem("a", Kind.ACT, 1.0, 0), PoolItem("joke", Kind.BUB, 1.0, 0), PoolItem("study", Kind.BUB, 1.0, 0, reminder = true))
        assertNull(Scheduler.tick(SchedState(), pool, ctx(600, busy = true), ACT_R)); assertNull(Scheduler.tick(SchedState(), pool, ctx(600, busy = true), BUB_R))
        assertNull("no activities on low battery", Scheduler.tick(SchedState(), pool, ctx(600, battLow = true), ACT_R))
        assertEquals("study", Scheduler.tick(SchedState(), pool, ctx(600, battLow = true), BUB_R)?.id)
    }

    @Test fun cooldownsAreRespected() {
        val pool = listOf(PoolItem("scroll", Kind.ACT, 1.0, 90), PoolItem("fish", Kind.ACT, 1.0, 120))
        val st = SchedState()
        assertEquals("scroll", Scheduler.tick(st, pool, ctx(600), ACT_R)?.id)
        assertEquals("fish", Scheduler.tick(st, pool, ctx(625), ACT_R)?.id)          // scroll excluded (last + cooldown)
        assertNull("both cooling down", Scheduler.tick(st, pool, ctx(660), ACT_R))     // 60 min after scroll, 35 after fish
        assertEquals("scroll back after 90 min", "scroll", Scheduler.tick(st, pool, ctx(691), ACT_R)?.id)
        assertNull("fish still cooling (120)", Scheduler.eligible(st, pool, ctx(740), Kind.ACT).firstOrNull { it.id == "fish" })
        assertNotNull(Scheduler.eligible(st, pool, ctx(746), Kind.ACT).firstOrNull { it.id == "fish" })
    }

    @Test fun gapsBetweenMoments() {
        val pool = listOf(PoolItem("a", Kind.ACT, 1.0, 0), PoolItem("b", Kind.ACT, 1.0, 0), PoolItem("x", Kind.BUB, 1.0, 0), PoolItem("y", Kind.BUB, 1.0, 0))
        val st = SchedState(); assertNotNull(Scheduler.tick(st, pool, ctx(600), ACT_R))
        assertNull("10 min between anything", Scheduler.tick(st, pool, ctx(605), BUB_R))
        assertNull("20 min between activities", Scheduler.tick(st, pool, ctx(615), ACT_R))
        assertNotNull("a bubble after 10", Scheduler.tick(st, pool, ctx(611), BUB_R))
        assertNull("10 min between anything", Scheduler.tick(st, pool, ctx(618), BUB_R))
        assertNotNull("0.1.22: no 30-min bubble gap any more", Scheduler.tick(st, pool, ctx(622), BUB_R))
    }

    @Test fun neverTheSameThingTwiceInARow() {
        val pool = listOf(PoolItem("a", Kind.ACT, 100.0, 0), PoolItem("b", Kind.ACT, 0.01, 0), PoolItem("c", Kind.BUB, 100.0, 0), PoolItem("d", Kind.BUB, 0.01, 0))
        val st = SchedState(); var last = ""; val rng = java.util.Random(4); var n = 0
        var t = 7 * 60L
        while (t < 23 * 60) { val r = rng.nextDouble(); val k = if (r < 0.5) ACT_R else BUB_R
            val p = Scheduler.tick(st, pool, ctx(t), { if (n++ % 2 == 0) k() else rng.nextDouble() }); if (p != null) { assertNotEquals("back-to-back at $t", last, p.id); last = p.id }
            t += 35 }
    }

    @Test fun dailyBubbleCapAndNewDayReset() {
        val pool = (1..8).map { PoolItem("b$it", Kind.BUB, 1.0, 0) }
        val st = SchedState(); var shown = 0; var t = 7 * 60L
        while (t < 23 * 60) { if (Scheduler.tick(st, pool, ctx(t), BUB_R) != null) shown++; t += 11 }
        assertEquals(Rules.BUB_DAY_CAP, shown)
        assertFalse("event bubbles share the cap", Scheduler.canBubble(st, ctx(22 * 60), "greet"))
        assertNotNull("new day, new budget", Scheduler.tick(st, pool, ctx(8 * 60, d = day + 1), BUB_R))
    }

    @Test fun swipeMutesForTheDayAndThreeSwipesHalveWeight() {
        val joke = PoolItem("joke", Kind.BUB, 2.0, 0); val st = SchedState()
        Scheduler.dismiss(st, "joke", ctx(600))
        assertFalse(Scheduler.canBubble(st, ctx(700), "joke")); assertTrue(Scheduler.canBubble(st, ctx(700), "study"))
        assertTrue("unmuted tomorrow", Scheduler.canBubble(st, ctx(700, d = day + 1), "joke"))
        Scheduler.dismiss(st, "joke", ctx(600, d = day + 2)); Scheduler.dismiss(st, "joke", ctx(600, d = day + 3))
        assertEquals(1.0, Scheduler.weight(st, joke), 1e-9)
        st.rollDay(day + 9); assertEquals("a week later it recovers", 2.0, Scheduler.weight(st, joke), 1e-9)
    }

    @Test fun hourWindowsAndWeekends() {
        val car = PoolItem("car", Kind.BUB, 1.0, 0, 10.0, 18.0, weekendOnly = true)
        assertTrue(Scheduler.eligible(SchedState(), listOf(car), ctx(12 * 60), Kind.BUB).isEmpty())
        assertEquals(1, Scheduler.eligible(SchedState(), listOf(car), ctx(12 * 60, weekend = true), Kind.BUB).size)
        assertTrue(Scheduler.eligible(SchedState(), listOf(car), ctx(19 * 60, weekend = true), Kind.BUB).isEmpty())
    }

    @Test fun stateSurvivesPrefs() {
        val pool = listOf(PoolItem("a.b", Kind.ACT, 1.0, 90), PoolItem("x", Kind.BUB, 1.0, 0)); val st = SchedState()
        Scheduler.tick(st, pool, ctx(600), ACT_R); Scheduler.tick(st, pool, ctx(640), BUB_R); Scheduler.dismiss(st, "x", ctx(641))
        val back = SchedState.decode(st.encode())
        assertEquals(st.encode(), back.encode()); assertEquals("x", back.lastId); assertEquals(1, back.bubToday); assertTrue("x" in back.muted)
        assertNull("cooldown survives a restart", Scheduler.eligible(back, pool, ctx(670), Kind.ACT).firstOrNull())
        assertEquals(SchedState().encode(), SchedState.decode("garbage").encode())
    }

    @Test fun realDayWithPersonaPoolsKeepsEveryRule() {
        for (per in Personas.all) for (seed in 1..40) {
            val st = SchedState(); val rng = java.util.Random(seed.toLong()); val picks = ArrayList<Pair<Long, PoolItem>>()
            var t = 0L; while (t < 24 * 60) { Scheduler.tick(st, per.pool, ctx(t), { rng.nextDouble() })?.let { picks.add(t to it) }; t += Rules.TICK_MIN }
            assertTrue("${per.id}: ≤ cap", picks.count { it.second.kind == Kind.BUB } <= Rules.BUB_DAY_CAP)
            for (i in picks.indices) { val (tm, p) = picks[i]; assertFalse("quiet ${tm / 60.0}", Scheduler.quiet(tm / 60.0)); assertTrue(tm / 60.0 >= p.from && tm / 60.0 < p.to)
                if (i > 0) { assertNotEquals(picks[i - 1].second.id, p.id); assertTrue(tm - picks[i - 1].first >= Rules.GAP_ANY) }
                val prevSame = picks.subList(0, i).lastOrNull { it.second.id == p.id }; if (prevSame != null) assertTrue("${p.id} cooldown", tm - prevSame.first >= p.cooldownMin) }
        }
    }

    @Test fun lineBagShowsEveryLineBeforeRepeatingAndNeverRepeatsBackToBack() {
        val bag = LineBag(12, 99); var prev = -1
        repeat(60) { round -> val seen = HashSet<Int>()
            repeat(12) { val i = bag.next(); assertNotEquals("round $round", prev, i); prev = i; seen.add(i) }
            assertEquals("round $round is a full permutation", 12, seen.size) }
        // persisted state continues the same sequence
        val a = LineBag(12, 5); repeat(7) { a.next() }; val b = LineBag.decode(a.encode(), 12, 5)
        assertEquals(a.next(), b.next()); assertEquals(a.next(), b.next())
        // a different line count (e.g. study progress set) starts a fresh bag
        assertEquals(0, LineBag.decode(a.encode(), 7, 5).pos)
        val two = LineBag(2, 1); var p2 = -1; repeat(10) { val i = two.next(); assertNotEquals(p2, i); p2 = i }
    }
}
