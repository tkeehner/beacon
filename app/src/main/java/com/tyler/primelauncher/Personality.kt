package com.tyler.primelauncher

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 0.1.13 (amplified in 0.1.14): the orb's personality, as pure math (unit-tested, no Android, no allocation per step).
 *
 * [Curiosity] decides WHEN the idle orb looks around, perks up, fidgets or yawns, and WHERE its eyes go; [Orb] turns the
 * events into motion with the envelopes in [Env]. Two dials drive it: [Curiosity.lively] (time-of-day mood: >1 perky
 * morning, <1 calm night) scales how often things happen, and [Curiosity.amp] (0.5 with Reduce Motion) scales how far.
 * Reduce Motion also runs the orb's clock at half speed (StageView), so frequencies halve on top of the amplitude.
 */
class Curiosity(private val rnd: Rng) {
    companion object {
        const val GLANCE = 1; const val PERK = 2; const val FIDGET = 4; const val YAWN = 8
        // ---- 0.1.14 "way more": every personality dial, in one place (0.1.13 value in brackets). ----
        /** Eye chase speed (1/s): a saccade dart [15], a touch snap [22], and the soft settle [5, the pre-0.1.13 constant]. */
        const val DART_RATE = 20.0; const val TOUCH_RATE = 28.0; const val EASE_RATE = 5.0
        /** Glance reach (fraction of the orb radius), before [amp]. [0.075..0.145; pre-0.1.13 0.05..0.11] */
        const val MIN_K = 0.12; const val MAX_K = 0.23
        /** Seconds between idle looks, at daytime mood: GAP_MIN + rnd * GAP_SPAN [1.2 + 2.2 = 1.2..3.4 s]. */
        const val GAP_MIN = 0.7; const val GAP_SPAN = 1.3
        /** Look kind odds: r < LOCK_P = long stare [0.15], r < DOUBLE_P = double-take [0.40 -> 25%; now 0.58 -> 40%]. */
        const val LOCK_P = 0.18; const val DOUBLE_P = 0.58
        /** Long stare length LOCK_MIN_S + rnd * LOCK_SPAN_S [1.7 + 1.1]. */
        const val LOCK_MIN_S = 1.5; const val LOCK_SPAN_S = 1.1
        /** Where the eyes settle after a look, as a fraction of the look [0.2]. */
        const val SETTLE = 0.15
        /** Perk-up every PERK_MIN_S..PERK_MAX_S s [15..30], reaching PERK_REACH x a normal look [1.15]. */
        const val PERK_MIN_S = 8.0; const val PERK_MAX_S = 16.0; const val PERK_REACH = 1.3
        /** Fidget every FIDGET_MIN_S + rnd * FIDGET_SPAN_S s [5 + 7 = 5..12]. */
        const val FIDGET_MIN_S = 3.0; const val FIDGET_SPAN_S = 4.0
        const val FIDGET_RAIN = 0; const val FIDGET_RIPPLE = 1; const val FIDGET_SWEEP = 2
        private const val Q = 4
    }
    /** Mood: how often things happen (1 = daytime). */
    var lively = 1.0
    /** Motion amount: 1 normal, 0.5 Reduce Motion. */
    var amp = 1.0
    /** Night mood: occasional slow yawn. */
    var yawny = false
    /** Current gaze target (fraction of R) and how fast the eyes should chase it (per second). */
    var tx = 0.0; private set
    var ty = 0.0; private set
    var rate = EASE_RATE; private set
    var fidgetKind = FIDGET_RAIN; private set
    /** Stats for tests: looks, double-takes and lock-ons planned so far. */
    var looks = 0; private set
    var doubleTakes = 0; private set
    var lockOns = 0; private set
    var nextGlance = 1.2; private set
    var nextPerk = 12.0; private set
    var nextFidget = 6.0; private set
    var nextYawn = 20.0; private set
    // Planned saccade steps: time, target, chase rate. Fixed arrays, reused (no per-frame allocation).
    private val qt = DoubleArray(Q); private val qx = DoubleArray(Q); private val qy = DoubleArray(Q); private val qr = DoubleArray(Q)
    private var qn = 0; private var qi = 0; private var rateUntil = -1.0
    /** True while a glance sequence is still playing (the orb holds its own gaze decay off meanwhile). */
    val busy get() = qi < qn

    private fun live() = lively.coerceIn(0.2, 2.0)
    private fun gap() = (GAP_MIN + rnd.next() * GAP_SPAN) / live()
    private fun add(t: Double, x: Double, y: Double, r: Double) { if (qn < Q) { qt[qn] = t; qx[qn] = x; qy[qn] = y; qr[qn] = r; qn++ } }

    /** A touch (or anything external) takes over the eyes: drop the plan and chase fast for [dur] s. */
    fun snap(t: Double, r: Double = TOUCH_RATE, dur: Double = 0.3) { qn = 0; qi = 0; rate = r; rateUntil = t + dur; if (nextGlance < t + 3) nextGlance = t + 3 }

    /** Plans one look: a dart to a point, sometimes a double-take or a long lock-on, then a settle back toward centre. */
    private fun plan(t: Double, kBoost: Double, forceLock: Boolean) {
        qn = 0; qi = 0; looks++
        val a = rnd.next() * PI * 2; val k = (MIN_K + rnd.next() * (MAX_K - MIN_K)) * kBoost * amp
        val x = cos(a) * k; val y = sin(a) * k * 0.75; val r = rnd.next()
        val back: Double
        when {
            forceLock || r < LOCK_P -> { lockOns++; add(t, x, y, DART_RATE); back = t + LOCK_MIN_S + rnd.next() * LOCK_SPAN_S }
            r < DOUBLE_P -> { doubleTakes++ // look, flick away, look back
                add(t, x, y, DART_RATE); val t2 = t + 0.18 + rnd.next() * 0.1; add(t2, -x * 0.45, y * 0.3, DART_RATE)
                val t3 = t2 + 0.2 + rnd.next() * 0.12; add(t3, x * 1.08, y * 1.08, DART_RATE); back = t3 + 0.8 + rnd.next() * 0.7 }
            else -> { add(t, x, y, DART_RATE) // dart + a small corrective micro-saccade
                val t2 = t + 0.3 + rnd.next() * 0.3; add(t2, x + (rnd.next() - 0.5) * 0.04 * amp, y + (rnd.next() - 0.5) * 0.03 * amp, DART_RATE); back = t2 + 0.5 + rnd.next() * 0.8 }
        }
        add(back, x * SETTLE, y * SETTLE, EASE_RATE)
        nextGlance = back + gap()
    }

    /**
     * Advances to time [t]. [idle] = nothing else wants the orb (IDLE mode, awake, not recently touched, not frozen).
     * Returns a bit set of events that start now: [GLANCE] (new [tx]/[ty]), [PERK], [FIDGET] ([fidgetKind]), [YAWN].
     */
    fun step(t: Double, idle: Boolean): Int {
        if (t > rateUntil) rate = EASE_RATE
        if (!idle) { // busy elsewhere: drop any plan and keep the timers from piling up
            qn = 0; qi = 0
            if (nextGlance < t + 0.8) nextGlance = t + 0.8; if (nextPerk < t + 4) nextPerk = t + 4
            if (nextFidget < t + 2) nextFidget = t + 2; if (nextYawn < t + 6) nextYawn = t + 6
            return 0
        }
        var ev = 0
        if (t > nextPerk) { ev = ev or PERK; plan(t, PERK_REACH, forceLock = true); nextPerk = t + (PERK_MIN_S + rnd.next() * (PERK_MAX_S - PERK_MIN_S)) / sqrt(live()) }
        else if (!busy && t > nextGlance) plan(t, 1.0, forceLock = false)
        while (qi < qn && t >= qt[qi]) { tx = qx[qi]; ty = qy[qi]; rate = qr[qi]; rateUntil = t + (if (qr[qi] > EASE_RATE) 0.22 else 0.0); qi++; ev = ev or GLANCE }
        if (t > nextFidget) { ev = ev or FIDGET; fidgetKind = (rnd.next() * 3).toInt().coerceIn(0, 2); nextFidget = t + (FIDGET_MIN_S + rnd.next() * FIDGET_SPAN_S) / live() }
        if (!yawny) { if (nextYawn < t + 8) nextYawn = t + 8 }
        else if (t > nextYawn) { ev = ev or YAWN; nextYawn = t + 22 + rnd.next() * 26; if (nextGlance < t + Env.YAWN_S) nextGlance = t + Env.YAWN_S }
        return ev
    }
}

/** Shapes (0..1 unless noted) of the personality moves, by seconds since they started. Pure. */
object Env {
    const val PERK_S = 1.5; const val FIDGET_S = 0.9; const val YAWN_S = 2.4; const val SURGE_S = 1.3
    /** Eyes pop wide fast, hold a beat, relax. */
    fun perk(s: Double) = if (s < 0 || s >= PERK_S) 0.0 else min(1.0, s / 0.12) * exp(-max(0.0, s - 0.35) * 3.2)
    fun fidget(s: Double) = if (s < 0 || s >= FIDGET_S) 0.0 else sin(PI * s / FIDGET_S)
    /** Yawn lid closure: slow close (0.8 s), hold shut, slow open; 1 = shut. */
    fun yawnShut(s: Double): Double { if (s < 0 || s >= YAWN_S) return 0.0
        val u = when { s < 0.8 -> s / 0.8; s < 1.25 -> 1.0; else -> 1 - (s - 1.25) / (YAWN_S - 1.25) }.coerceIn(0.0, 1.0); return u * u * (3 - 2 * u) }
    /** Body stretch during the yawn (multiplier offset, 0..1). */
    fun yawnSwell(s: Double) = if (s < 0 || s >= YAWN_S) 0.0 else sin(PI * s / YAWN_S)
    /** Event glow surge: sharp rise, exponential fade. */
    fun surge(s: Double) = if (s < 0 || s >= SURGE_S) 0.0 else min(1.0, s / 0.08) * exp(-s * 3.4)
}

/**
 * 0.1.14 "way more": the Orb-side motion dials (0.1.13 value in brackets). Everything that scales with [Orb.amp] is still
 * halved by Reduce Motion, and the orb clock still runs at half speed there.
 */
object Bold {
    /** Touch: soft follow reach [0.13], finger-down snap reach [0.14] and its bounce [1.35]. */
    const val TOUCH_REACH = 0.2; const val SNAP_REACH = 0.23; const val TOUCH_KICK = 1.9
    /** Lean toward the gaze: x / y drift per unit gaze (x R) [0.3 / 0.22] and roll (degrees per unit gaze) [22].
     *  At the max look (~0.23 R) that is ~10% of the size and ~6 deg. */
    const val LEAN_X = 0.44; const val LEAN_Y = 0.32; const val ROLL_DEG = 26.0
    /** Mode-entry bounces: THINK [1.5], SUMMON [1.3], quick wake [1.6], slow-wake pop [1.1]. */
    const val THINK_KICK = 2.2; const val SUMMON_KICK = 1.8; const val WAKE_KICK = 2.3; const val SLOW_WAKE_KICK = 1.5
    /** Radius bounce per unit kick [0.07]. */
    const val KICK_SWELL = 0.085
    /** Perk-up: bounce [0.55], eye-pop [0.32], radius swell [0.03], glow [0.15]. */
    const val PERK_KICK = 1.1; const val PERK_EYES = 0.65; const val PERK_SWELL = 0.06; const val PERK_GLOW = 0.32
    /** Event glow surge: eye/body glow [0.5], rim gain [x1], radius swell [0 = none]. */
    const val SURGE_GLOW = 1.0; const val SURGE_RIM = 1.9; const val SURGE_SWELL = 0.05
    /** Idle life: breathing [0.028], glow wobble [0.2], yawn stretch [0.045], rain-burst fidget [1.4], ripple fidget [0.03]. */
    const val BREATH = 0.04; const val GLOW_WOBBLE = 0.28; const val YAWN_SWELL = 0.08; const val FIDGET_RAIN = 2.8; const val FIDGET_RIPPLE = 0.055
    /** Idle column flicks per second [1.6] and the scan sweep gap SWEEP_MIN_S + rnd * SWEEP_SPAN_S [7 + 6]. */
    const val FLICKS = 2.6; const val SWEEP_MIN_S = 4.5; const val SWEEP_SPAN_S = 4.0
    /** THINK: rain speed-up base + wobble [2.6 + 1.2], spin [0.35], radius pulse [0.05], glow pulse base + flash [0.12 + 0.38]. */
    const val THINK_RAIN = 4.2; const val THINK_RAIN_WOBBLE = 1.8; const val THINK_SPIN = 0.7; const val THINK_PULSE = 0.08
    const val THINK_GLOW = 0.2; const val THINK_FLASH = 0.55
    /** SUMMON rain speed-up [1.5]; LISTEN/TALK rain base + per-level [0.4 + 2.2]. */
    const val SUMMON_RAIN = 2.5; const val LISTEN_RAIN = 0.7; const val LISTEN_RAIN_LVL = 3.0
    /** Neon glitch: gap while thinking GLITCH_THINK_MIN + rnd * SPAN [0.3 + 0.6], idle [1.8 + 3.2]; extra length while
     *  thinking [0.03 s]; tear width gain with thinking [0.8] (scaled by amp); rim split while thinking [2.4]. */
    const val GLITCH_THINK_MIN = 0.15; const val GLITCH_THINK_SPAN = 0.35; const val GLITCH_IDLE_MIN = 1.0; const val GLITCH_IDLE_SPAN = 2.0
    const val GLITCH_THINK_LEN = 0.06; const val GLITCH_TEAR = 1.6; const val THINK_SPLIT = 3.6
}
