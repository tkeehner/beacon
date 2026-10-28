package com.tyler.primelauncher

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * 0.1.18 idle beats: every 4-6 s of plain idle the orb does SOMETHING. About 3 in 10 beats are a full activity
 * from the persona's pool (time-of-day gates and quiet hours still apply); the rest are these short micro-animations.
 * Micros are tiny [AK.QUIRK] antics (0.7-2.6 s, pose math only, no props, no sound) so they cost nothing extra.
 */
object Micros {
    // shared
    const val GLANCE_L = "m.glance.l"; const val GLANCE_R = "m.glance.r"; const val BOB = "m.bob"; const val PULSE = "m.pulse"
    const val DBLINK = "m.dblink"; const val TILT = "m.tilt"; const val LOOKUP = "m.lookup"
    // Matrix
    const val GLYPH = "m.matrix.glyph"; const val RAIN = "m.matrix.rain"
    // Neon City
    const val FLICKER = "m.neon.flicker"; const val JOLT = "m.neon.jolt"; const val RIM = "m.neon.rim"
    // ODST
    const val SCAN = "m.odst.scan"; const val FLIP = "m.odst.flip"; const val PING = "m.odst.ping"; const val SQUINT = "m.odst.squint"

    private fun glance(s: Double, p: Pose, dir: Double) { val w = Ez.win(s, 0.1, 1.15, 0.18, 0.25); p.gx = 0.17 * dir * w; p.gy = -0.02 * w; p.gazeK = w }

    val list: List<Antic> by lazy { listOf(
        Antic(GLANCE_L, 1.3, AK.QUIRK, { s, p, _ -> glance(s, p, -1.0) }),
        Antic(GLANCE_R, 1.3, AK.QUIRK, { s, p, _ -> glance(s, p, 1.0) }),
        Antic(LOOKUP, 1.4, AK.QUIRK, { s, p, _ -> val w = Ez.win(s, 0.1, 1.25, 0.2, 0.3); p.gx = 0.03 * w; p.gy = -0.18 * w; p.gazeK = w; p.open += 0.1 * w }),
        Antic(BOB, 1.0, AK.QUIRK, { s, p, _ -> p.dy -= 6 * Ez.bump(s - 0.08, 0.42) - 2 * Ez.bump(s - 0.5, 0.3); p.sy += 0.04 * Ez.bump(s - 0.5, 0.25); p.sx -= 0.03 * Ez.bump(s - 0.5, 0.25) }),
        Antic(PULSE, 0.9, AK.QUIRK, { s, p, _ -> val b = Ez.bump(s - 0.05, 0.7); p.scale += 0.05 * b; p.glow += 0.45 * b; p.rim += 0.3 * b }),
        Antic(DBLINK, 0.8, AK.QUIRK, { s, p, _ -> p.blink = max(Ez.bump(s - 0.05, 0.2), Ez.bump(s - 0.34, 0.2)) }),
        Antic(TILT, 1.3, AK.QUIRK, { s, p, _ -> val w = Ez.win(s, 0.05, 1.2, 0.25, 0.35); p.roll = 9 * w; p.gx = 0.05 * w; p.gazeK = 0.5 * w }),
        // Matrix: the code sphere spins up and flickers bright, then settles; or the rain surges for a moment.
        Antic(GLYPH, 1.0, AK.QUIRK, { s, p, _ -> val w = Ez.win(s, 0.0, 0.95, 0.1, 0.4); p.spin = 1 + 3.5 * w; p.flash = 0.35 * (if (floor(s * 18) % 3 == 0.0) 1.0 else 0.0) * w; p.rim += 0.4 * w }),
        Antic(RAIN, 1.8, AK.QUIRK, { s, p, _ -> val w = Ez.win(s, 0.0, 1.75, 0.3, 0.6); p.rainMul = 1 + 1.3 * w; p.gy = -0.12 * w; p.gazeK = 0.6 * w }),
        // Neon City: a tube-light flicker, a quick glitch jolt, a rim-light sweep.
        Antic(FLICKER, 0.9, AK.QUIRK, { s, p, _ -> val on = if (s < 0.6) (if (Ez.hash(floor(s * 22), 3.0, 1.0) > 0.45) 1.0 else 0.25) else 1.0; p.glow *= on; p.rim += (1 - on) * 0.6 }),
        Antic(JOLT, 0.7, AK.QUIRK, { s, p, _ -> val k = exp(-s * 7); p.dx += 4 * sin(s * 90) * k; p.flash = 0.4 * exp(-s * 12); p.sx += 0.03 * k }),
        Antic(RIM, 1.2, AK.QUIRK, { s, p, _ -> val b = Ez.bump(s - 0.05, 1.05); p.rim += 0.9 * b; p.rotOff += 0.6 * b }),
        // ODST: a slow eye scan, a sign-panel flip to the neutral face and back, a HUD ping, a suspicious squint.
        Antic(SCAN, 2.6, AK.QUIRK, { s, p, _ -> val l = Ez.win(s, 0.15, 1.15, 0.2, 0.2); val r = Ez.win(s, 1.25, 2.45, 0.2, 0.2); p.gx = 0.2 * (r - l); p.gy = -0.02; p.gazeK = max(l, r); p.lid = 0.15 * (l + r) }),
        Antic(FLIP, 1.4, AK.QUIRK, { s, p, _ -> if (s > 0.1 && s < 0.85) p.expr = Expr.NEUTRAL }),
        Antic(PING, 1.0, AK.QUIRK, { s, p, _ -> p.hudA = if (s < 0.35 && floor(s * 20) % 2 == 0.0) 0.35 else 1.0; p.glow += 0.4 * Ez.bump(s - 0.3, 0.6); p.tag2 = if (s < 0.9) "PING" else null }),
        Antic(SQUINT, 1.3, AK.QUIRK, { s, p, _ -> val w = Ez.win(s, 0.05, 1.2, 0.2, 0.3); p.lid = 0.45 * w; p.gx = 0.06 * w; p.gazeK = w }),
    ) }

    private val SHARED = listOf(GLANCE_L, GLANCE_R, LOOKUP, BOB, PULSE, DBLINK, TILT)
    val MATRIX = SHARED + listOf(GLYPH, RAIN)
    val NEON = SHARED + listOf(FLICKER, JOLT, RIM)
    /** ODST is eyes-first: no squash bob (the face is a flat panel), pulse kept as a glow. */
    val ODST = listOf(GLANCE_L, GLANCE_R, LOOKUP, PULSE, DBLINK, TILT, SCAN, FLIP, PING, SQUINT)
    fun isMicro(id: String?) = id != null && id.startsWith("m.")
}

/** Idle beat rules (pure, unit-tested). */
object Beats {
    const val MIN_S = 4.0; const val SPAN_S = 2.0
    /** Share of beats that are a full activity (roughly 1 in 3 or 4). */
    const val P_ACT = 0.3
    /** Seconds until the next beat: uniform 4..6. */
    fun interval(r: Double) = MIN_S + SPAN_S * r.coerceIn(0.0, 1.0)
    /** Pick from [ids] uniformly, never [last] (unless it is the only one). */
    fun pickNot(ids: List<String>, last: String?, r: Double): String? {
        val c = ids.distinct().filter { it != last }.ifEmpty { ids.distinct() }; if (c.isEmpty()) return null
        return c[(r * c.size).toInt().coerceIn(0, c.size - 1)]
    }
    /** Weighted activity pick, never [last]. */
    fun pickAct(items: List<PoolItem>, last: String?, r: Double): String? {
        val c = items.filter { it.kind == Kind.ACT && it.id != last }; if (c.isEmpty()) return null
        var x = r * c.sumOf { it.weight }; for (it in c) { x -= it.weight; if (x <= 0) return it.id }; return c.last().id
    }
    /** One beat: an activity with probability [P_ACT] when one is allowed ([acts] already gated), else a micro. */
    fun next(r1: Double, r2: Double, micros: List<String>, acts: List<PoolItem>, last: String?): String? {
        if (r1 < P_ACT) pickAct(acts, last, r2)?.let { return it }
        return pickNot(micros, last, r2)
    }
}
