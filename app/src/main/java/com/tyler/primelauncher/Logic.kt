package com.tyler.primelauncher

import kotlin.math.abs
import kotlin.math.hypot

/** Pure, unit-tested decisions shared by the home screen. */
object Logic {
    /** Flick from the orb: direction if the drag was quick and long enough, else null (hold/slow drag = ring). */
    fun flickDir(dx: Float, dy: Float, ms: Long, minDist: Float = 46f, maxMs: Long = 420): String? {
        if (ms > maxMs || hypot(dx, dy) < minDist) return null
        return if (abs(dx) > abs(dy)) (if (dx > 0) "right" else "left") else (if (dy > 0) "down" else "up")
    }

    /** Ring = top 8 by foreground time (ties: name), then pins; falls back to alphabetical without usage data. */
    /** 0.1.12: the app ring shows only the user's pinned apps, in pin order. */
    const val MAX_PINS = 12
    fun pinnedRing(installed: Collection<String>, pins: List<String>): List<String> { val have = installed.toSet(); return pins.filter { it in have }.distinct().take(MAX_PINS) }
    /** Search suggestions (empty query): most-used apps, then pins. Was the ring's source before 0.1.12. */
    fun ringPkgs(installed: List<Pair<String, String>>, usageMs: Map<String, Long>, pins: List<String>, max: Int = 8): List<String> {
        val have = installed.map { it.first }.toSet(); val pinOk = pins.filter { it in have }.distinct().take(8)
        val auto = installed.filter { it.first !in pinOk }.sortedWith(compareByDescending<Pair<String, String>> { usageMs[it.first] ?: 0L }.thenBy { it.second.lowercase() })
            .take(max).map { it.first }
        return auto + pinOk
    }

    /**
     * [lively] (0.1.13) scales how often the idle orb glances, perks up and fidgets (1 = daytime); [yawn] = occasional slow
     * night yawns. 0.1.14 pushes the contrast harder still (perkier morning, sleepier night). 0.1.13 also widened the gap between the buckets so the time of day actually reads on the orb.
     */
    data class Mood(val name: String, val pace: Double, val bright: Double, val lid: Double, val tired: Double, val dim: Float, val lively: Double = 1.0, val yawn: Boolean = false)
    fun mood(hour: Double, level: Float, charging: Boolean): Mood {
        var m = when {
            hour >= 5 && hour < 10 -> Mood("bright morning", 1.5, 1.32, 1.18, 0.0, 1.08f, lively = 1.75)
            hour >= 10 && hour < 17 -> Mood("daytime", 1.0, 1.0, 1.0, 0.0, 1f, lively = 1.0)
            hour >= 17 && hour < 21.5 -> Mood("winding down", 0.72, 0.82, 0.82, 0.18, 0.87f, lively = 0.6)
            else -> Mood("calm night", 0.35, 0.58, 0.6, 0.5, 0.66f, lively = 0.3, yawn = true)
        }
        if (level < 0.2f && !charging) m = Mood("tired", m.pace * 0.4, m.bright * 0.7, m.lid * 0.42, 1.0, m.dim * 0.85f, lively = m.lively * 0.35, yawn = true)
        else if (charging && level < 0.995f) m = m.copy(name = m.name + " · charging", bright = m.bright * 1.08)
        return m
    }

    /** Unread panel: bottom edge sits above the orb (and its badge) so the orb stays fully visible. Returns [bottomY, maxHeight] in dp. */
    fun trayBox(orbCy: Float, orbR: Float, topInset: Float): FloatArray {
        val floor = minOf(orbCy - orbR * 1.12f, orbCy - orbR * 1.02f - 15f) - 8f
        return floatArrayOf(floor, maxOf(120f, floor - topInset - 8f))
    }

    /** WMO weather code -> label + kind (sun, cloud, rain, snow, storm, fog). */
    fun cond(code: Int): Pair<String, String> = when (code) {
        0 -> "Clear" to "sun"; 1 -> "Mostly clear" to "sun"; 2 -> "Partly cloudy" to "cloud"; 3 -> "Overcast" to "cloud"
        45, 48 -> "Fog" to "fog"; in 51..57 -> "Drizzle" to "rain"; in 61..67 -> "Rain" to "rain"; in 71..77 -> "Snow" to "snow"
        in 80..82 -> "Showers" to "rain"; 85, 86 -> "Snow showers" to "snow"; in 95..99 -> "Thunderstorm" to "storm"; else -> "—" to "cloud"
    }

    /** Effective charging: plugged in and not already full. */
    fun charging(plugged: Boolean, level: Float) = plugged && level < 0.995f

    /** Call timer under the orb: m:ss, then h:mm:ss. */
    fun callClock(sec: Long): String { val s = sec.coerceAtLeast(0); return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }
    /** Synthetic voice level when the real one isn't readable: speech-like rhythm, or a slow breath with reduce motion. */
    fun callPulse(t: Double, reduce: Boolean): Double = if (reduce) 0.3 + 0.1 * kotlin.math.sin(t * 1.4)
        else (0.38 + 0.26 * kotlin.math.sin(t * 7.3) * kotlin.math.sin(t * 2.1) + 0.14 * kotlin.math.sin(t * 13.0) + 0.1 * kotlin.math.sin(t * 0.9)).coerceIn(0.0, 1.0)
}

/**
 * When the home-screen orb should wake. Screen off = asleep. It wakes (animation) when Home is in front AND the phone is
 * unlocked, whichever of "resumed" / "unlocked" (ACTION_USER_PRESENT) arrives last. Unlocking into another app and coming
 * Home much later just shows Prime awake, without replaying the wake-up.
 */
class WakeGate(private val windowMs: Long = 8_000L) {
    enum class Act { NONE, WAKE, AWAKE_QUIET }
    var asleep = false; private set
    private var unlockedAt = -1L
    private var resumed = false
    fun screenOff() { asleep = true; unlockedAt = -1L }
    fun paused() { resumed = false }
    /** ACTION_USER_PRESENT. */
    fun unlocked(now: Long): Act { unlockedAt = now; return if (asleep && resumed) { asleep = false; Act.WAKE } else Act.NONE }
    /** HomeActivity.onResume; [locked] = keyguard still showing. */
    fun resumed(now: Long, locked: Boolean): Act { resumed = true; return arrived(now, locked) }
    /**
     * 0.1.8: Home is resumed AND the keyguard is gone (window focus, or a frame-time check). On the phone the unlock-to-home
     * onResume runs while isKeyguardLocked() is still true (the keyguard is marked hidden ~40-80 ms later), so onResume alone
     * can't see the unlock; this is the moment Home actually arrives on screen.
     */
    fun arrived(now: Long, locked: Boolean): Act {
        if (!resumed || !asleep || locked) return Act.NONE
        asleep = false
        val fresh = unlockedAt < 0 || now - unlockedAt <= windowMs // no USER_PRESENT seen yet (no secure lock / still in flight) counts as fresh
        return if (fresh) Act.WAKE else Act.AWAKE_QUIET
    }
}

/**
 * 0.1.12: at most one "Hey Prime" listener per process. [start] hands out a fresh stop [Token] per worker; the worker body
 * runs only after the previous worker's body (including its finally/cleanup) has fully returned. A stopped worker that
 * has not started its body yet skips it. Fixes the libvosk SIGSEGV from two listener threads overlapping on stop/start.
 */
class SingleWorker(private val name: String) {
    class Token { @Volatile var stopped = false; internal set }
    private val gate = java.util.concurrent.Semaphore(1)
    private var cur: Token? = null; private var thread: Thread? = null
    /** Starts a worker unless one is already live (not stopped). Returns true if a new one was started. */
    @Synchronized fun start(body: (Token) -> Unit): Boolean {
        if (cur?.stopped == false) return false
        val t = Token()
        val th = Thread({
            gate.acquireUninterruptibly()
            try { if (!t.stopped) body(t) } finally { gate.release(); synchronized(this) { if (cur === t) { cur = null; thread = null } } }
        }, name)
        cur = t; thread = th; th.start(); return true
    }
    /** Asks the live worker to stop (flag + interrupt). Returns at once; the next [start] waits for it to finish. */
    @Synchronized fun stop() { cur?.let { it.stopped = true }; thread?.interrupt() }
    val live: Boolean @Synchronized get() = cur?.stopped == false
}
