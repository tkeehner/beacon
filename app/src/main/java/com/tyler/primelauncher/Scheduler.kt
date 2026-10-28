package com.tyler.primelauncher

/**
 * 0.1.15 companion: when does the orb do something on its own? Pure logic (no Android), unit-tested in SchedulerTest.
 *
 * Every [Rules.TICK_MIN] minutes of home-on-screen time it rolls: ~10% an activity, ~6% a chat bubble, from the active
 * persona's weighted pool. Items have their own cooldowns and hour windows, the last pick never repeats back to back,
 * there are minimum gaps between activities / bubbles / anything, at most [Rules.BUB_DAY_CAP] bubbles a day, nothing in
 * quiet hours (23:30-07:00) or while busy (Do Not Disturb, a call, Hey Prime / Message Prime, typing, battery saver).
 * Swiping a bubble away mutes that kind for the rest of the day; three swipes in a week halve its weight.
 */
enum class Kind { ACT, BUB }

data class PoolItem(val id: String, val kind: Kind, val weight: Double, val cooldownMin: Int, val from: Double = 0.0, val to: Double = 24.0,
                    val weekendOnly: Boolean = false, val reminder: Boolean = false,
                    /** 0.1.17 ODST Rain Watch: only while it is raining or after dark (7 pm on). */
                    val rainOrNight: Boolean = false)

object Rules {
    const val TICK_MIN = 5L
    const val P_ACT = 0.10; const val P_BUB = 0.06
    const val GAP_ACT = 20L; const val GAP_BUB = 30L; const val GAP_ANY = 10L
    const val BUB_DAY_CAP = 6
    const val QUIET_FROM = 23.5; const val QUIET_TO = 7.0
    /** Below this battery level activities stop; only reminder bubbles may still show. */
    const val LOW_BATT = 0.15f
    const val DISMISS_WEEK = 3
}

/** [nowMin] = minutes on any monotonic-enough wall clock (epoch minutes), [hour] = local hour of day 0..24, [day] = local day number. */
data class SchedCtx(val nowMin: Long, val hour: Double, val day: Long, val weekend: Boolean = false, val busy: Boolean = false, val battLow: Boolean = false, val raining: Boolean = false)

class SchedState {
    val last = HashMap<String, Long>()
    var lastId = ""
    var lastAct = NEVER; var lastBub = NEVER; var lastAny = NEVER
    var day = -1L; var bubToday = 0
    val muted = HashSet<String>()
    /** id -> days it was swiped away (kept for a week). */
    val dismissed = HashMap<String, MutableList<Long>>()

    fun rollDay(d: Long) {
        if (d == day) return
        day = d; bubToday = 0; muted.clear()
        dismissed.values.forEach { l -> l.removeAll { d - it >= 7 } }; dismissed.entries.removeAll { it.value.isEmpty() }
    }

    /** Compact text form for SharedPreferences (no org.json so it stays a plain JVM class). */
    fun encode(): String = buildString {
        append("v1|").append(lastId).append('|').append(lastAct).append('|').append(lastBub).append('|').append(lastAny).append('|').append(day).append('|').append(bubToday).append('|')
        append(last.entries.joinToString(",") { "${it.key}=${it.value}" }).append('|')
        append(muted.joinToString(",")).append('|')
        append(dismissed.entries.joinToString(",") { e -> e.key + "=" + e.value.joinToString(":") })
    }

    companion object {
        const val NEVER = Long.MIN_VALUE / 4
        fun decode(s: String?): SchedState {
            val st = SchedState(); if (s.isNullOrBlank() || !s.startsWith("v1|")) return st
            runCatching {
                val p = s.split('|')
                st.lastId = p[1]; st.lastAct = p[2].toLong(); st.lastBub = p[3].toLong(); st.lastAny = p[4].toLong(); st.day = p[5].toLong(); st.bubToday = p[6].toInt()
                if (p[7].isNotEmpty()) p[7].split(',').forEach { kv -> val i = kv.lastIndexOf('='); st.last[kv.substring(0, i)] = kv.substring(i + 1).toLong() }
                if (p[8].isNotEmpty()) st.muted.addAll(p[8].split(','))
                if (p.size > 9 && p[9].isNotEmpty()) p[9].split(',').forEach { kv -> val i = kv.lastIndexOf('=')
                    st.dismissed[kv.substring(0, i)] = kv.substring(i + 1).split(':').filter { it.isNotEmpty() }.map { it.toLong() }.toMutableList() }
            }.onFailure { return SchedState() }
            return st
        }
    }
}

object Scheduler {
    fun quiet(hour: Double) = hour >= Rules.QUIET_FROM || hour < Rules.QUIET_TO
    private fun inHours(it: PoolItem, c: SchedCtx) = (!it.weekendOnly || c.weekend) && c.hour >= it.from && c.hour < it.to && (!it.rainOrNight || c.raining || c.hour >= 19.0 || c.hour < 7.0)
    private fun since(t: Long, now: Long) = if (t == SchedState.NEVER) Long.MAX_VALUE else now - t

    /** True if a bubble of [id] may show now (any source: scheduled, greeting, message offer, reaction). */
    fun canBubble(st: SchedState, c: SchedCtx, id: String, cooldownMin: Int = 0, reminder: Boolean = false): Boolean {
        st.rollDay(c.day)
        if (quiet(c.hour) || c.busy) return false
        if (c.battLow && !reminder) return false
        if (st.bubToday >= Rules.BUB_DAY_CAP || id in st.muted) return false
        if (since(st.lastBub, c.nowMin) < Rules.GAP_BUB || since(st.lastAny, c.nowMin) < Rules.GAP_ANY) return false
        return since(st.last[id] ?: SchedState.NEVER, c.nowMin) >= cooldownMin
    }

    /** True if an activity may start now (used for self-triggered extras too). */
    fun canAct(st: SchedState, c: SchedCtx): Boolean {
        st.rollDay(c.day)
        return !quiet(c.hour) && !c.busy && !c.battLow && since(st.lastAct, c.nowMin) >= Rules.GAP_ACT && since(st.lastAny, c.nowMin) >= Rules.GAP_ANY
    }

    fun eligible(st: SchedState, pool: List<PoolItem>, c: SchedCtx, kind: Kind): List<PoolItem> = pool.filter { it ->
        it.kind == kind && it.id != st.lastId && inHours(it, c) && since(st.last[it.id] ?: SchedState.NEVER, c.nowMin) >= it.cooldownMin &&
            (if (kind == Kind.BUB) canBubble(st, c, it.id, 0, it.reminder) else canAct(st, c))
    }

    /**
     * 0.1.18 idle-beat activities: pool activities allowed right now by their time-of-day / rain gates, quiet hours,
     * busy and low battery. Per-item cooldowns and the 20-min activity gap don't apply (they should come often), and beats
     * are never recorded, so the bubble cadence is untouched.
     */
    fun beatActs(pool: List<PoolItem>, c: SchedCtx): List<PoolItem> =
        if (quiet(c.hour) || c.busy || c.battLow) emptyList() else pool.filter { it.kind == Kind.ACT && inHours(it, c) }

    fun weight(st: SchedState, it: PoolItem) = if ((st.dismissed[it.id]?.size ?: 0) >= Rules.DISMISS_WEEK) it.weight / 2 else it.weight

    /** One roll. Returns the picked item (already recorded) or null. [rnd] returns uniform 0..1. */
    fun tick(st: SchedState, pool: List<PoolItem>, c: SchedCtx, rnd: () -> Double): PoolItem? {
        st.rollDay(c.day)
        if (quiet(c.hour) || c.busy || since(st.lastAny, c.nowMin) < Rules.GAP_ANY) return null
        val r = rnd()
        val kind = when { r < Rules.P_ACT -> Kind.ACT; r < Rules.P_ACT + Rules.P_BUB -> Kind.BUB; else -> return null }
        val cands = eligible(st, pool, c, kind); if (cands.isEmpty()) return null
        val total = cands.sumOf { weight(st, it) }; var x = rnd() * total
        var pick = cands.last(); for (it in cands) { x -= weight(st, it); if (x <= 0) { pick = it; break } }
        record(st, pick.id, pick.kind, c); return pick
    }

    fun record(st: SchedState, id: String, kind: Kind, c: SchedCtx) {
        st.rollDay(c.day); st.last[id] = c.nowMin; st.lastId = id; st.lastAny = c.nowMin
        if (kind == Kind.ACT) st.lastAct = c.nowMin else { st.lastBub = c.nowMin; st.bubToday++ }
    }

    /** A bubble was swiped away: mute that kind for the day and remember it for the weekly halving. */
    fun dismiss(st: SchedState, id: String, c: SchedCtx) { st.rollDay(c.day); st.muted.add(id); st.dismissed.getOrPut(id) { ArrayList() }.add(c.day) }
}

/**
 * Lines inside a category come out of a shuffled bag: every line once before any repeats, and a new round never starts
 * with the line that ended the last one. State is three ints so it persists cheaply.
 */
class LineBag(val n: Int, private val seed: Long, var round: Int = 0, var pos: Int = 0, var lastIdx: Int = -1) {
    private fun order(r: Int): IntArray {
        val a = IntArray(n) { it }; val rng = java.util.Random(seed * 7919 + r)
        for (i in n - 1 downTo 1) { val j = rng.nextInt(i + 1); val t = a[i]; a[i] = a[j]; a[j] = t }
        return a
    }
    /** Round [r]'s order; if it would start with the line that ended round r-1, the first two swap (n > 2 never moves the last). */
    private fun finalOrder(r: Int): IntArray {
        val o = order(r)
        if (r > 0 && n > 2 && o[0] == order(r - 1)[n - 1]) { val t = o[0]; o[0] = o[1]; o[1] = t }
        return o
    }
    fun next(): Int {
        if (n <= 0) return -1
        if (n == 1) { lastIdx = 0; return 0 }
        if (n == 2) { val i = if (lastIdx == 0) 1 else 0; lastIdx = i; return i }
        if (pos >= n) { round++; pos = 0 }
        val i = finalOrder(round)[pos]; pos++; lastIdx = i; return i
    }
    fun encode() = "$n:$round:$pos:$lastIdx"
    companion object {
        fun decode(s: String?, n: Int, seed: Long): LineBag {
            val p = s?.split(':'); if (p == null || p.size != 4 || p[0].toIntOrNull() != n) return LineBag(n, seed)
            return LineBag(n, seed, p[1].toIntOrNull() ?: 0, p[2].toIntOrNull() ?: 0, p[3].toIntOrNull() ?: -1)
        }
    }
}
