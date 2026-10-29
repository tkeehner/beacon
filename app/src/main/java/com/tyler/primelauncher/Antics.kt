package com.tyler.primelauncher

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * 0.1.15 companion body language. A [Pose] is a per-frame offset on top of the orb's own life (Orb.kt applies it): move,
 * squash along an axis, roll, eye gaze / openness / happy ^^ / dizzy spiral / wink, glow, rim and code-rain speed.
 * Pose x/y offsets are in the mockup's units (orb radius 92) and Orb scales them to the real radius.
 */
class Pose {
    var dx = 0.0; var dy = 0.0; var sx = 1.0; var sy = 1.0; var axis = 0.0; var roll = 0.0; var scale = 1.0
    var gx = 0.0; var gy = 0.0; var gazeK = 0.0
    var open = 1.0; var blink = 0.0; var happy = 0.0; var spiral = 0.0; var wink = 0.0
    var glow = 1.0; var rim = 0.0; var rainMul = 1.0; var spin = 1.0; var bodyK = 1.0; var flash = 0.0; var rotOff = 0.0
    /** 0.1.17 ODST face: an explicit expression pop ([Expr], EYES = the resting eyes-only face), lid / wide eyes,
     *  the blue Vergil look, HUD opacity and the two HUD labels. Other skins ignore these. */
    var expr = Expr.EYES; var lid = 0.0; var wide = 0.0; var vergil = 0.0; var hudA = 1.0; var tag1: String? = null; var tag2: String? = null
    /** ODST face opacity (Pod Drop hides it until touchdown). */
    var faceA = 1.0
    /** 0.1.27 holiday avatar parts: skull jaw drop, ghost hem wave, hat tip (-1 droop .. +1 perk) / pumpkin stem, cat ear / tail, fx phase. */
    var hjaw = 0.0; var hhem = 0.0; var htip = 0.0; var hear = 0.0; var htail = 0.0; var hfx = 0.0
    fun reset() { dx = 0.0; dy = 0.0; sx = 1.0; sy = 1.0; axis = 0.0; roll = 0.0; scale = 1.0; gx = 0.0; gy = 0.0; gazeK = 0.0
        open = 1.0; blink = 0.0; happy = 0.0; spiral = 0.0; wink = 0.0; glow = 1.0; rim = 0.0; rainMul = 1.0; spin = 1.0; bodyK = 1.0; flash = 0.0; rotOff = 0.0
        expr = Expr.EYES; lid = 0.0; wide = 0.0; vergil = 0.0; hudA = 1.0; tag1 = null; tag2 = null; faceA = 1.0
        hjaw = 0.0; hhem = 0.0; htip = 0.0; hear = 0.0; htail = 0.0; hfx = 0.0 }
    /** Shrink every deviation from rest by [k] (persona reaction scale, fade-outs). */
    fun damp(k: Double) { dx *= k; dy *= k; roll *= k; sx = 1 + (sx - 1) * k; sy = 1 + (sy - 1) * k; scale = 1 + (scale - 1) * k; rotOff *= k }
    fun fade(k: Double) { damp(k); gazeK *= k; open = 1 + (open - 1) * k; blink *= k; happy *= k; spiral *= k; wink *= k; glow = 1 + (glow - 1) * k
        rim *= k; rainMul = 1 + (rainMul - 1) * k; spin = 1 + (spin - 1) * k; bodyK = 1 + (bodyK - 1) * k; flash *= k
        lid *= k; wide *= k; vergil *= k; hudA = 1 + (hudA - 1) * k; faceA = 1 + (faceA - 1) * k; if (k < 0.5) expr = Expr.EYES
        hjaw *= k; hhem *= k; htip *= k; hear *= k; htail *= k; hfx *= k }
}

object Ez {
    const val TAU = PI * 2
    fun clamp(v: Double, a: Double, b: Double) = if (v < a) a else if (v > b) b else v
    fun sst(a: Double, b: Double, v: Double): Double { val x = clamp((v - a) / (b - a), 0.0, 1.0); return x * x * (3 - 2 * x) }
    fun win(t: Double, a: Double, b: Double, fi: Double, fo: Double) = sst(a, a + fi, t) * (1 - sst(b - fo, b, t))
    fun bump(s: Double, d: Double) = if (s < 0 || s > d) 0.0 else sin(PI * s / d)
    fun spr(s: Double, k: Double, w: Double) = if (s < 0) 0.0 else exp(-k * s) * cos(w * s)
    fun sprS(s: Double, k: Double, w: Double) = if (s < 0) 0.0 else exp(-k * s) * sin(w * s)
    fun blinkAt(t: Double, t0: Double): Double { val d = t - t0; return if (d >= 0 && d < 0.24) sin(PI * d / 0.24) else 0.0 }
    fun easeIO(p: Double) = if (p < 0.5) 4 * p * p * p else 1 - (-2 * p + 2).pow(3) / 2
    fun lerp(a: Double, b: Double, k: Double) = a + (b - a) * k
    fun hash(a: Double, b: Double, c: Double): Double { val h = sin(a * 12.9898 + b * 78.233 + c * 37.719) * 43758.5453; return h - floor(h) }
    /** Piecewise eased steps: each [t0, x, y, d] eases from the previous value to (x, y) over d seconds. */
    fun steps(t: Double, arr: Array<DoubleArray>): DoubleArray { var x = 0.0; var y = 0.0
        for (s in arr) { val d = if (s.size > 3) s[3] else 0.07; val k = sst(s[0], s[0] + d, t); x = lerp(x, s[1], k); y = lerp(y, s[2], k) }; return doubleArrayOf(x, y) }
}

/** Stage geometry for props, in dp. The mockup's absolute coordinates (CX 180, CY 410, R 92) map through [X] / [Y]. */
class Geo {
    var cx = 205f; var cy = 454f; var r = 102f; var W = 411f; var H = 914f; var top = 184f; var floor = 748f
    var skin: Skin = Skin.MATRIX
    /** Where a poke landed / where the finger that interrupted an activity is (dp), set by StageView. */
    var pokeX = 0f; var pokeY = 0f; var fingerX = 0f; var fingerY = 0f; var blush = false
    val k get() = r / 92.0
    fun X(m: Double) = cx + (m - 180) * k
    fun Y(m: Double) = cy + (m - 410) * k
    fun u(m: Double) = m * k
    fun ox(p: Pose) = cx + p.dx * k
    fun oy(p: Pose) = cy + p.dy * k
    fun or(p: Pose) = r * p.scale
    /** Clamp a y so a prop stays between the clock header and the footer hint. */
    fun yIn(y: Double) = Ez.clamp(y, top + 8.0, floor - 6.0)
    val neon get() = skin.neon
    val odst get() = skin.odst
    /** Upper-case skins (Neon City, ODST) shout their little captions. */
    val loud get() = skin.neon || skin.odst
}

/** Prop drawing helpers on the stage canvas (already scaled to dp). */
class Art {
    lateinit var cv: Canvas
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
    private val disp = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val path = Path(); private val rf = RectF()
    var glowOn = true
    fun withA(c: Int, a: Double) = Color.argb((Ez.clamp(a, 0.0, 1.0) * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))
    fun glyph(ch: String, x: Double, y: Double, size: Double, col: Int, a: Double, glow: Double = 8.0, condensed: Boolean = false) {
        if (a <= 0.01 || size <= 0.5) return
        tp.typeface = if (condensed) disp else mono; tp.textSize = size.toFloat(); tp.color = withA(col, a)
        if (glowOn && glow > 0) tp.setShadowLayer(glow.toFloat(), 0f, 0f, withA(col, a)) else tp.clearShadowLayer()
        cv.drawText(ch, x.toFloat(), (y - (tp.descent() + tp.ascent()) / 2).toFloat(), tp)
    }
    fun line(col: Int, w: Double, a: Double, blur: Double = 6.0, build: Path.() -> Unit) {
        if (a <= 0.01) return
        path.rewind(); path.build(); sp.strokeWidth = w.toFloat(); sp.color = withA(col, a)
        if (glowOn && blur > 0) sp.setShadowLayer(blur.toFloat(), 0f, 0f, withA(col, a)) else sp.clearShadowLayer()
        cv.drawPath(path, sp); sp.clearShadowLayer()
    }
    fun rrect(x: Double, y: Double, w: Double, h: Double, r: Double, fill: Int?, stroke: Int?, a: Double, sw: Double = 1.3, blur: Double = 8.0) {
        if (a <= 0.01) return
        rf.set(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat())
        if (fill != null) { fp.color = withA(fill, a * Color.alpha(fill) / 255.0); cv.drawRoundRect(rf, r.toFloat(), r.toFloat(), fp) }
        if (stroke != null) { sp.strokeWidth = sw.toFloat(); sp.color = withA(stroke, a); if (glowOn && blur > 0) sp.setShadowLayer(blur.toFloat(), 0f, 0f, withA(stroke, a)); cv.drawRoundRect(rf, r.toFloat(), r.toFloat(), sp); sp.clearShadowLayer() }
    }
    fun dot(x: Double, y: Double, r: Double, col: Int, a: Double) { if (a <= 0.01) return; fp.color = withA(col, a); cv.drawCircle(x.toFloat(), y.toFloat(), r.toFloat(), fp) }
    /** Glyph confetti burst (deterministic per seed). */
    fun burst(g: Geo, x0: Double, y0: Double, s: Double, n: Int, speed: Double, life: Double, seed: Int, spread: Double, dirA: Double?, grav: Double) {
        if (s < 0 || s > life) return
        val r = Rng(seed + 1); val k = g.k
        for (i in 0 until n) { val a = if (dirA == null) i.toDouble() / n * Ez.TAU + r.next() * 0.3 else dirA + (r.next() - 0.5) * spread
            val v = speed * (0.6 + 0.6 * r.next()) * k; val x = x0 + cos(a) * v * s; val y = y0 + sin(a) * v * s + grav * k * s * s * 0.5
            val ch = if (r.next() < 0.5) "0" else "1"; val sz = (9 + r.next() * 4) * k; val col = if (r.next() < 0.5) g.skin.head else g.skin.body
            glyph(ch, x, y, sz, col, (1 - s / life) * 0.95, 6.0) }
    }
    fun blush(g: Geo, p: Pose, a: Double) {
        if (a <= 0.01) return
        val E = if (g.odst) arrayOf(doubleArrayOf(-0.40, 0.01), doubleArrayOf(0.40, 0.01)) else if (g.neon) arrayOf(doubleArrayOf(-0.45, -0.22), doubleArrayOf(-0.02, -0.19)) else arrayOf(doubleArrayOf(-0.47, -0.3), doubleArrayOf(-0.07, -0.25))
        val o = g.or(p); val c = 0xFFFF6B8A.toInt(); fp.color = withA(c, a * 0.45); if (glowOn) fp.setShadowLayer(10f, 0f, 0f, withA(c, a * 0.45))
        for (e in E) { val x = g.ox(p) + (e[0] + p.gx * p.gazeK) * o; val y = g.oy(p) + (e[1] + 0.24 + p.gy * p.gazeK) * o
            rf.set((x - o * 0.1).toFloat(), (y - o * 0.045).toFloat(), (x + o * 0.1).toFloat(), (y + o * 0.045).toFloat()); cv.drawOval(rf, fp) }
        fp.clearShadowLayer()
    }
}

enum class AK { CORE, ACT, QUIRK }

class Antic(val id: String, val len: Double, val kind: AK, val pose: (Double, Pose, Geo) -> Unit,
            val under: ((Art, Double, Pose, Geo) -> Unit)? = null, val over: ((Art, Double, Pose, Geo) -> Unit)? = null,
            /** Interrupted by a touch: drop it and blush (study / game activities). */
            val blushOnCatch: Boolean = false,
            /** 0.1.17: seconds into the antic where the skin's chime plays (ODST's 4-note civic chime). */
            val cues: DoubleArray = DoubleArray(0))

object Antics {
    // core moments
    const val POKE = "poke"; const val DIZZY = "dizzy"; const val WELCOME = "welcome"; const val HEADSUP = "headsup"; const val NOD = "nod"; const val GOTIT = "gotit"
    const val BEDTIME = "bedtime"; const val PEEKABOO = "peekaboo"; const val SNACK = "snack"; const val RAINCATCH = "raincatch"; const val PETAFTER = "petafter"
    const val LITTLEWIN = "littlewin"; const val CAUGHT = "caught"; const val JOKELAUGH = "jokelaugh"
    // Matrix activities
    const val SCROLL = "act.scroll"; const val FISHING = "act.fishing"; const val ZEN = "act.zen"; const val GARDEN = "act.garden"; const val STARGAZER = "act.stargazer"
    const val FLASHCARDS = "act.flashcards"; const val TIDY = "act.tidy"
    // Neon City activities
    const val SKATE = "act.skate"; const val DJ = "act.dj"; const val ARCADE = "act.arcade"; const val GRAFFITI = "act.graffiti"; const val SIGN = "act.sign"; const val JUGGLE = "act.juggle"
    // idle mannerisms
    const val DEJAVU = "q.dejavu"; const val STARE = "q.stare"; const val BOB = "q.bob"; const val WINK = "q.wink"

    private fun gazeTo(p: Pose, dx: Double, dy: Double, mag: Double, k: Double) { val L = hypot(dx, dy).coerceAtLeast(1e-3); p.gx = dx / L * mag; p.gy = dy / L * mag; p.gazeK = max(p.gazeK, k) }

    val all: Map<String, Antic> by lazy { listOf(
        // ---- Poke & Giggle: one poke (two-finger tap) squashes along the poke direction and giggles ----
        Antic(POKE, 1.5, AK.CORE, { s, p, g ->
            p.axis = atan2((g.pokeY - g.cy).toDouble(), (g.pokeX - g.cx).toDouble()); val d = 0.14 * Ez.spr(s, 5.0, 16.0); p.sx -= d; p.sy += d * 0.6
            p.glow += 0.55 * exp(-4 * s); p.rim += 0.6 * exp(-5 * s); p.dx += 3.5 * Ez.sprS(s, 2.4, 38.0); p.roll += 6 * Ez.sprS(s, 2.4, 19.0); p.happy = Ez.win(s, 0.02, 1.15, 0.08, 0.25)
        }, over = { a, s, _, g -> a.burst(g, g.pokeX.toDouble(), g.pokeY.toDouble(), s, 6, 70.0, 0.8, 11, 1.6, -0.7, 90.0) }),
        // ---- three pokes in a row (or a hard shake): dizzy spiral eyes, stars, shake it off ----
        Antic(DIZZY, 2.4, AK.CORE, { s, p, _ ->
            p.spiral = Ez.win(s, 0.06, 1.3, 0.12, 0.25); p.roll += 16 * Ez.sprS(s, 1.1, 7.0)
            if (s < 1.4) { p.dx += 6 * cos(s * 6.5) * exp(-s * 1.2); p.dy += 4 * sin(s * 6.5) * exp(-s * 1.2) }
            val sh = s - 1.35; if (sh > 0 && sh < 0.35) p.dx += 5 * sin(sh * 60) * (1 - sh / 0.35)
            p.blink = Ez.blinkAt(s, 1.75); p.happy = max(p.happy, 0.65 * Ez.win(s, 1.82, 2.18, 0.06, 0.1))
        }, over = { a, s, p, g -> if (s < 1.3) { val al = 1 - Ez.sst(1.0, 1.3, s); for (i in 0 until 3) { val ang = s * 5 + i * Ez.TAU / 3
            a.glyph("*", g.ox(p) + cos(ang) * g.u(58.0), g.oy(p) - g.or(p) - g.u(14.0) + sin(ang) * g.u(10.0), g.u(15.0), g.skin.head, al, 10.0) } } }),
        // ---- Welcome Home: rides on the slow unlock wake (played from 1.05 s): notices you, hops, happy squint ----
        Antic(WELCOME, 4.7, AK.CORE, { t, p, _ ->
            p.open += 0.4 * Ez.bump(t - 1.05, 0.5); p.gy = 0.06 * Ez.sst(1.0, 1.2, t); p.gazeK = 0.6 * Ez.win(t, 1.0, 4.2, 0.1, 0.3)
            val cr = Ez.sst(1.35, 1.6, t) * (1 - Ez.sst(1.6, 1.68, t)); p.sy -= 0.16 * cr; p.sx += 0.1 * cr; p.dy += 10 * cr
            val u = (t - 1.65) / 0.55; if (u > 0 && u < 1) { p.dy -= 44 * sin(PI * u); p.sy += 0.12 * Ez.bump(u, 0.5); p.sx -= 0.06 * Ez.bump(u, 0.5) }
            val l = t - 2.2; if (l > 0 && l < 1) { p.sy -= 0.2 * Ez.spr(l, 6.0, 14.0); p.sx += 0.14 * Ez.spr(l, 6.0, 14.0) }
            val u2 = (t - 2.75) / 0.35; if (u2 > 0 && u2 < 1) p.dy -= 16 * sin(PI * u2); val l2 = t - 3.1; if (l2 > 0 && l2 < 0.8) { p.sy -= 0.1 * Ez.spr(l2, 6.0, 14.0); p.sx += 0.07 * Ez.spr(l2, 6.0, 14.0) }
            p.happy = Ez.win(t, 1.62, 3.55, 0.1, 0.3); val sg = t - 1.92; if (sg > 0) { p.glow += 0.6 * exp(-3 * sg); p.rim += 0.8 * exp(-3 * sg); p.rainMul += 3 * exp(-2 * sg) }
            p.gx = -0.07 * Ez.win(t, 3.7, 4.1, 0.08, 0.1); p.blink = Ez.blinkAt(t, 4.35)
        }, over = { a, t, _, g -> a.burst(g, g.cx.toDouble(), g.cy - g.u(40.0), t - 1.92, 20, 120.0, 1.1, 31, 0.0, null, 40.0) }),
        // ---- Heads-Up: glance up at what arrived ("!"); important ones (played from 2.9 s) make it jump ("!!") ----
        Antic(HEADSUP, 5.5, AK.CORE, { t, p, _ ->
            val a1 = Ez.win(t, 0.62, 1.75, 0.08, 0.15); p.gx = 0.14 * a1; p.gy = -0.22 * a1 + 0.04 * Ez.win(t, 1.8, 2.6, 0.1, 0.2); p.gazeK = max(a1, Ez.win(t, 1.8, 2.6, 0.1, 0.2))
            p.dy = -6 * a1; p.roll = 4 * a1; p.open = 1 + 0.3 * Ez.bump(t - 0.62, 0.5); p.rim += 0.4 * Ez.bump(t - 0.62, 0.6)
            p.dy += 5 * Ez.bump(t - 1.85, 0.25) + 5 * Ez.bump(t - 2.15, 0.25)
            val s = t - 3; val a2 = Ez.win(t, 3.0, 4.4, 0.04, 0.2); if (s > 0 && s < 0.55) { p.dx += 3.5 * sin(s * 95) * (1 - s / 0.55); p.scale += 0.04 * (1 - s / 0.55) }
            if (a2 > 0) { p.gy += -0.24 * a2; p.gazeK = max(p.gazeK, a2) }; p.open += 0.45 * a2
            if (s > 0) { p.rim += 1.2 * exp(-3 * s); p.flash = 0.9 * exp(-5 * s); p.glow += 0.5 * exp(-3 * s) }; if (s > 0 && s < 0.8) p.rainMul += 3.0
            p.blink = Ez.blinkAt(t, 4.7)
        }, over = { a, t, p, g ->
            val s = t - 0.65; if (s > 0 && s < 1.6) { val sc = if (s < 0.15) s / 0.15 * 1.3 else 1 + 0.3 * Ez.spr(s - 0.15, 8.0, 20.0)
                a.glyph("!", g.ox(p) + g.u(34.0), g.oy(p) - g.or(p) - g.u(26.0), g.u(22.0) * sc, g.skin.head, 1 - Ez.sst(1.3, 1.6, s), 12.0) }
            val s2 = t - 3.02; if (s2 > 0 && s2 < 1.6) { val sc = if (s2 < 0.12) s2 / 0.12 * 1.4 else 1 + 0.3 * Ez.spr(s2 - 0.12, 8.0, 20.0)
                a.glyph("!!", g.ox(p), g.oy(p) - g.or(p) - g.u(30.0), g.u(24.0) * sc, if (g.neon) g.skin.accent else g.skin.head, 1 - Ez.sst(1.3, 1.6, s2), 14.0) }
        }),
        // ---- Listen & Nod: a little "mm-hm" in your pauses; "got it" when Prime has the answer ----
        Antic(NOD, 0.45, AK.CORE, { s, p, _ -> p.dy += 7 * Ez.bump(s, 0.32); p.open -= 0.3 * Ez.bump(s, 0.32); p.happy = 0.35 * Ez.bump(s, 0.32) }),
        Antic(GOTIT, 1.5, AK.CORE, { s, p, _ -> p.happy = Ez.win(s, 0.05, 0.9, 0.08, 0.2); p.scale += 0.08 * Ez.sprS(s, 4.0, 16.0); p.glow += 0.4 * exp(-3 * s); p.blink = Ez.blinkAt(s, 1.15) }),
        // ---- Bedtime Settle: big yawn, nods off, startles, snuggles down; holds until the screen times out or you touch ----
        Antic(BEDTIME, 45.0, AK.CORE, { t, p, _ ->
            p.open = 0.5; p.rainMul = 0.45; p.spin = 0.4; p.glow = 0.8
            val y = t - 0.4; if (y > 0 && y < 2.1) { val sh = if (y < 0.8) y / 0.8 else if (y < 1.35) 1.0 else 1 - (y - 1.35) / 0.75; p.blink = max(p.blink, Ez.sst(0.0, 1.0, sh))
                p.sy += 0.09 * Ez.bump(y, 2.1); p.sx -= 0.02 * Ez.bump(y, 2.1); p.roll -= 5 * Ez.bump(y, 2.1); p.gy -= 0.08 * Ez.bump(y, 2.1); p.gazeK = Ez.bump(y, 2.1) }
            val d = Ez.sst(2.5, 3.4, t) * (1 - Ez.sst(3.45, 3.5, t)); p.dy += 7 * d; p.roll += 5 * d; p.open -= 0.42 * d
            val j = t - 3.45; if (j > 0 && j < 1) { p.open += 0.5 * exp(-3 * j); p.dy -= 6 * exp(-6 * j); p.scale += 0.05 * Ez.sprS(j, 5.0, 16.0) }
            val q = Ez.sst(3.7, 5.0, t); p.dy += 20 * q; p.sy *= 1 - 0.1 * q; p.sx *= 1 + 0.07 * q; p.open = Ez.lerp(p.open, 0.04, q); p.glow = Ez.lerp(p.glow, 0.45, q)
            p.bodyK = Ez.lerp(1.0, 0.6, q); p.rainMul = Ez.lerp(p.rainMul, 0.2, q)
            p.fade(Ez.sst(0.0, 0.4, t))
        }, over = { a, t, p, g -> if (t > 4.1) { val c = (t - 4.1) % 1.65; for (z0 in doubleArrayOf(0.0, 0.55, 1.1)) { var s = c - z0; if (s < 0) s += 1.65; if (s > 1.4) continue
            a.glyph("z", g.ox(p) + g.or(p) * 0.55 + g.u(26.0) * s, g.oy(p) - g.or(p) * 0.35 - g.u(50.0) * s, g.u(10 + s * 9), g.skin.head, 0.8 * (1 - Ez.sst(0.9, 1.4, s)) * Ez.sst(0.0, 0.15, s), 8.0) } } }),
        // ---- Peekaboo: bored after a while untouched; rolls off-screen, peeks twice, pops back with a "boo" ----
        Antic(PEEKABOO, 5.5, AK.ACT, { t, p, _ ->
            val xy = Ez.steps(t, arrayOf(doubleArrayOf(0.3, 290.0, 0.0, 0.8), doubleArrayOf(1.3, 205.0, 0.0, 0.3), doubleArrayOf(1.95, 290.0, 0.0, 0.15), doubleArrayOf(2.25, 290.0, -150.0, 0.01),
                doubleArrayOf(2.5, 200.0, -150.0, 0.3), doubleArrayOf(3.15, 290.0, -150.0, 0.12), doubleArrayOf(3.4, 290.0, 0.0, 0.01), doubleArrayOf(3.5, 0.0, 0.0, 0.4)))
            p.dx = xy[0]; p.dy = xy[1] - 30 * Ez.bump(t - 3.5, 0.4); p.rotOff = xy[0] / 92; p.gx = -0.16 * (if (xy[0] > 150) 1.0 else 0.0) * Ez.sst(1.3, 1.5, t); p.gy = if (xy[1] < -100) 0.08 else 0.0; p.gazeK = if (xy[0] > 150) 1.0 else 0.0
            p.open = 1 + 0.3 * Ez.bump(t - 1.4, 0.4) + 0.25 * Ez.bump(t - 2.6, 0.4); p.blink = max(Ez.blinkAt(t, 1.7), max(Ez.blinkAt(t, 2.85), Ez.blinkAt(t, 5.1)))
            p.happy = Ez.win(t, 3.6, 4.8, 0.1, 0.3); val l = t - 3.9; if (l > 0 && l < 0.8) { p.sy -= 0.15 * Ez.spr(l, 6.0, 14.0); p.sx += 0.1 * Ez.spr(l, 6.0, 14.0) }
            if (t > 3.7) p.glow += 0.5 * exp(-3 * (t - 3.7))
        }, over = { a, t, _, g -> a.burst(g, g.cx.toDouble(), g.cy - g.u(30.0), t - 3.75, 16, 110.0, 1.0, 77, 0.0, null, 60.0)
            val s = t - 3.75; if (s > 0 && s < 1.1) a.glyph(if (g.loud) "BOO!" else "boo.", g.cx.toDouble(), g.cy - g.r - g.u(36.0), g.u(20.0) * (if (s < 0.12) s / 0.12 else 1.0), g.skin.head, 1 - Ez.sst(0.8, 1.1, s), 12.0) }),
        // ---- Snack Time: charger plugged in -> perks up and gulps the charge stream (played from 1.2 s) ----
        Antic(SNACK, 5.6, AK.CORE, { t, p, _ ->
            val fed = Ez.sst(1.6, 3.6, t); p.open = 1 + 0.3 * Ez.bump(t - 1.25, 0.5); p.glow = Ez.lerp(0.8, 1.25, fed); p.rainMul = Ez.lerp(0.6, 1.2, Ez.sst(1.2, 3.6, t))
            p.gy = 0.2 * Ez.win(t, 1.25, 3.5, 0.15, 0.3); p.gazeK = Ez.win(t, 1.25, 3.5, 0.15, 0.3)
            for (gq in doubleArrayOf(2.0, 2.6, 3.2)) { val s = t - gq; p.sy += 0.1 * Ez.bump(s, 0.25); p.sx -= 0.05 * Ez.bump(s, 0.25); p.dy += 4 * Ez.bump(s, 0.25); p.rim += 0.4 * Ez.bump(s, 0.3) }
            p.happy = Ez.win(t, 3.7, 4.7, 0.1, 0.3); p.scale += 0.07 * Ez.sprS(t - 3.7, 4.0, 16.0); p.blink = Ez.blinkAt(t, 5.3)
        }),
        // ---- Rain Catcher: chomps a falling rain glyph, smugly bats the next one away ----
        Antic(RAINCATCH, 5.5, AK.ACT, { t, p, g ->
            val d1x = g.X(202.0); val d1y = g.Y(130 + (t - 0.3) * 165); var x = 0.0; var y = 0.0
            if (t < 1.45) { val dx = d1x - g.cx; val dy = d1y - g.cy; val L = hypot(dx, dy); x = dx / L * 0.2 * Ez.sst(0.4, 0.6, t); y = dy / L * 0.2 * Ez.sst(0.4, 0.6, t) }
            else if (t > 2.2 && t < 3.5) { val d2 = drop2(t, g); val dx = d2[0] - g.cx; val dy = d2[1] - g.cy; val L = hypot(dx, dy); x = dx / L * 0.21 * Ez.sst(2.2, 2.4, t); y = dy / L * 0.21 * Ez.sst(2.2, 2.4, t) }
            p.gx = x; p.gy = y; p.gazeK = Ez.win(t, 0.3, 4.6, 0.2, 0.4); p.dy = -18 * Ez.bump(t - 1.2, 0.35); p.sy += 0.1 * Ez.bump(t - 1.2, 0.3); p.sx -= 0.05 * Ez.bump(t - 1.2, 0.3)
            val ch = t - 1.45; if (ch > 0 && ch < 0.9) { p.sy -= 0.08 * Ez.spr(ch, 6.0, 16.0); p.flash = 0.6 * exp(-5 * ch); p.glow += 0.5 * exp(-3 * ch) }
            p.happy = max(Ez.win(t, 1.5, 2.2, 0.08, 0.2), 0.75 * Ez.win(t, 3.6, 4.5, 0.08, 0.2)); p.dx = 12 * Ez.bump(t - 3.15, 0.28); p.roll = 14 * Ez.bump(t - 3.15, 0.28); p.open -= 0.4 * Ez.bump(t - 3.15, 0.28)
            p.blink = Ez.blinkAt(t, 4.9)
        }, over = { a, t, _, g ->
            fun drop(x: Double, y: Double, al: Double) { for (k in 4 downTo 1) a.glyph(if (k % 2 == 1) "0" else "1", x, y - g.u(13.0 * k), g.u(12.0), g.skin.body, al * 0.12 * (5 - k), 4.0); a.glyph("1", x, y, g.u(18.0), g.skin.head, al, 12.0) }
            if (t > 0.3 && t < 1.45) drop(g.X(202.0), g.Y(130 + (t - 0.3) * 165), Ez.sst(0.3, 0.45, t))
            if (t > 2.2) { val d = drop2(t, g); drop(d[0], d[1], Ez.sst(2.2, 2.35, t) * (1 - Ez.sst(4.0, 4.4, t))) }
        }),
        Antic(PETAFTER, 1.3, AK.CORE, { s, p, _ -> p.happy = 0.8 * (1 - Ez.sst(0.5, 1.2, s)); p.scale -= 0.03 * Ez.bump(s, 0.8); p.glow += 0.3 * (1 - Ez.sst(0.0, 1.2, s)); p.blink = Ez.blinkAt(s, 1.0) }),
        // ---- Little Wins: inbox cleared -> spinning hop + glyph confetti ----
        Antic(LITTLEWIN, 4.2, AK.CORE, { t, p, _ ->
            p.gy = -0.2 * Ez.win(t, 0.55, 1.1, 0.08, 0.1); p.gazeK = Ez.win(t, 0.55, 1.1, 0.08, 0.1); p.open += 0.35 * Ez.bump(t - 0.6, 0.5)
            val cr = Ez.sst(0.95, 1.15, t) * (1 - Ez.sst(1.15, 1.22, t)); p.sy -= 0.15 * cr; p.sx += 0.1 * cr; p.dy += 9 * cr
            val u = (t - 1.2) / 0.6; if (u > 0 && u < 1) { p.dy -= 50 * sin(PI * u); p.roll += 360 * Ez.easeIO(u); p.rotOff = Ez.TAU * Ez.easeIO(u) }
            val l = t - 1.8; if (l > 0 && l < 1) { p.sy -= 0.18 * Ez.spr(l, 6.0, 14.0); p.sx += 0.12 * Ez.spr(l, 6.0, 14.0) }
            val u2 = (t - 2.35) / 0.3; if (u2 > 0 && u2 < 1) p.dy -= 14 * sin(PI * u2); p.happy = Ez.win(t, 1.25, 3.3, 0.1, 0.3)
            val s = t - 1.5; if (s > 0) { p.glow += 0.6 * exp(-3 * s); p.rim += exp(-3 * s); p.rainMul += 3 * exp(-2 * s) }; p.blink = Ez.blinkAt(t, 3.8)
        }, over = { a, t, _, g -> a.burst(g, g.cx.toDouble(), g.cy - g.u(50.0), t - 1.5, 30, 160.0, 1.4, 55, 0.0, null, 180.0); a.burst(g, g.cx.toDouble(), g.cy - g.u(50.0), t - 1.55, 14, 90.0, 1.2, 56, 0.0, null, 120.0) }),
        // ---- the interrupt rule: a touch ends any activity; the orb looks at your finger (blushing if caught studying / gaming) ----
        Antic(CAUGHT, 2.4, AK.CORE, { s, p, g ->
            gazeTo(p, (g.fingerX - g.cx).toDouble(), (g.fingerY - g.cy).toDouble(), 0.2, Ez.sst(0.0, 0.1, s) * (1 - Ez.sst(2.0, 2.4, s)))
            p.dy -= 12 * Ez.bump(s, 0.3); p.open += 0.45 * Ez.bump(s, 0.6)
            if (g.blush) { p.happy = 0.8 * Ez.win(s, 0.5, 2.3, 0.15, 0.3); p.roll = -8 * Ez.win(s, 0.5, 2.3, 0.15, 0.3) }
            else if (g.odst && s > 0.03 && s < 1.55) p.expr = Expr.CONFUSED // mockup "interrupt": confused flip, then back to the eyes
        }, over = { a, s, p, g -> if (g.blush) a.blush(g, p, Ez.win(s, 0.5, 2.3, 0.15, 0.3))
            if (g.odst && !g.blush) OdstArt.caption(a, g, "INQUIRY RECEIVED.", s - 0.45, 1.9) }),
        // ---- after a joke's punchline: Neon wiggle-laughs ("BA-DUM TSS"), Matrix just... nods ----
        Antic(JOKELAUGH, 1.6, AK.CORE, { s, p, g ->
            if (g.odst) { p.dy -= 10 * Ez.bump(s, 0.4); p.expr = if (s > 0.05 && s < 1.35) Expr.HAPPY else Expr.EYES; p.glow += 0.3 * exp(-3 * s) }
            else if (g.neon) { p.dy -= 22 * Ez.bump(s, 0.4); p.happy = Ez.win(s, 0.0, 1.1, 0.08, 0.3); p.flash = 0.5 * exp(-5 * s); p.roll += 6 * Ez.sprS(s, 4.0, 14.0) + 5 * sin(s * 30) * Ez.win(s, 0.3, 1.2, 0.1, 0.2); p.rim += 0.6 * exp(-3 * s) }
            else { p.dy += 4 * Ez.bump(s, 0.7); p.open = min(p.open, Ez.lerp(1.0, 0.72, Ez.win(s, 0.0, 1.6, 0.3, 0.4))) }
        }, over = { a, s, p, g -> if (g.neon && s < 1.2) a.glyph("BA-DUM TSS", g.ox(p) + g.u(60.0), g.oy(p) - g.or(p) - g.u(8.0), g.u(11.0), g.skin.accent, Ez.win(s, 0.1, 1.2, 0.1, 0.3), 8.0, condensed = true) }),

        // =================== Matrix activities ===================
        Antic(SCROLL, 8.0, AK.ACT, { t, p, _ ->
            val b = Ez.win(t, 0.6, 6.9, 0.4, 0.4); p.gy = 0.17 * b; p.gx = b * (((t * 0.9) % 1) * 0.16 - 0.1) * (if (t < 5.2 || t > 6) 1.0 else 0.0); p.gazeK = b; p.dy = 4 * b; p.open -= 0.15 * b; p.roll = -3 * b
            p.open += 0.35 * Ez.bump(t - 5.2, 0.45); p.happy = 0.75 * Ez.win(t, 5.3, 6.1, 0.1, 0.2); p.blink = max(Ez.blinkAt(t, 3.6), Ez.blinkAt(t, 7.3))
        }, over = { a, t, p, g ->
            val u = Ez.sst(0.6, 1.2, t) * (1 - Ez.sst(6.4, 6.9, t)); if (u <= 0) return@Antic
            val k = g.k; val x = g.ox(p) + 14 * k; val y = g.oy(p) + g.or(p) * 0.38; val wd = 76 * k; val hh = 66 * k * u; val x0 = x - wd / 2
            a.rrect(x0, y, wd, hh, 0.0, if (g.neon) 0xFF15121F.toInt() else 0xEB07140B.toInt(), null, 1.0)
            a.line(g.skin.head, 3.0, 1.0, 8.0) { moveTo((x0 - 4).toFloat(), y.toFloat()); lineTo((x0 + wd + 4).toFloat(), y.toFloat()) }
            a.line(g.skin.head, 3.0, 1.0, 8.0) { moveTo((x0 - 4).toFloat(), (y + hh).toFloat()); lineTo((x0 + wd + 4).toFloat(), (y + hh).toFloat()) }
            val dec = max(0.0, (t - 1.3) * 7); val runes = "ᚠᚢᚦᚨᚱᚲᚷᚹᚺᚾᛁᛃ0123"
            for (li in 0 until 6) { val yy = (10 + li * 10) * k; if (yy > hh - 3) break
                for (c in 0 until 8) { val idx = li * 8 + c; val ok = idx < dec
                    val ch = if (ok) runes[(Ez.hash(idx.toDouble(), 5.0, 5.0) * 16).toInt()].toString() else if (Ez.hash(idx.toDouble(), floor(t * 14), 2.0) < 0.5) "0" else "1"
                    a.glyph(ch, x0 + (6 + c * 9.2) * k, y + yy, 9 * k, if (ok) g.skin.head else g.skin.body, if (ok) 1.0 else 0.35, 0.0) } }
            if (t > 5.2 && t < 6) a.glyph("!", g.ox(p) + g.or(p) * 0.7, g.oy(p) - g.or(p) - 10 * k, 18 * k, g.skin.head, Ez.win(t, 5.2, 6.0, 0.05, 0.2), 10.0)
        }),
        Antic(FISHING, 9.0, AK.ACT, { t, p, _ ->
            val a = Ez.win(t, 0.4, 8.3, 0.3, 0.4); val hold = Ez.win(t, 5.9, 7.4, 0.1, 0.1); p.gx = (0.15 * a) * (1 - hold); p.gy = (-0.12 * a) * (1 - hold) - 0.22 * hold; p.gazeK = max(a, hold)
            p.roll = 3 * sin(t * 1.4) * a - 6 * Ez.win(t, 4.4, 5.4, 0.1, 0.1); p.open += 0.25 * Ez.bump(t - 3.2, 0.3) + 0.45 * Ez.bump(t - 4.2, 0.5); p.dx = 1.2 * sin(t * 40) * Ez.win(t, 4.4, 5.4, 0.05, 0.05)
            p.happy = Ez.win(t, 5.6, 7.6, 0.1, 0.2); val gg = t - 7.45; if (gg > 0 && gg < 0.8) { p.sy -= 0.08 * Ez.spr(gg, 6.0, 16.0); p.flash = 0.6 * exp(-5 * gg) }; p.blink = Ez.blinkAt(t, 8.5)
        }, over = { art, t, p, g ->
            val a = Ez.win(t, 0.4, 8.3, 0.3, 0.4); val k = g.k; val ox = g.ox(p); val oy = g.oy(p); val orr = g.or(p)
            val bx = ox + orr * 0.78; val by = oy - orr * 0.15; val tx = g.X(315.0); val ty = g.yIn(g.Y(195.0))
            for (c in 0 until 7) { val y = ((t * 80 + c * 34) % 260) * k + ty - 20 * k; if (t > 4.2 && y > ty + 60 * k) continue
                art.glyph(if (Ez.hash(c.toDouble(), floor(t * 3), 5.0) < 0.5) "0" else "1", tx, y, 11 * k, if (c == 6) g.skin.head else g.skin.body, 0.35 * (c / 7.0 + 0.2) * Ez.sst(0.0, 0.4, t) * (1 - Ez.sst(8.5, 9.0, t)), 4.0) }
            if (a <= 0) return@Antic
            art.line(g.skin.body, 2.2, a) { moveTo(bx.toFloat(), by.toFloat()); quadTo((bx + 40 * k).toFloat(), (by - 120 * k).toFloat(), tx.toFloat(), ty.toFloat()) }
            var L = 150 * Ez.sst(0.8, 1.3, t); L += 4 * Ez.bump(t - 3.2, 0.3) + 10 * Ez.bump(t - 4.2, 0.25); L = Ez.lerp(L, 16.0, Ez.sst(4.4, 5.4, t)); val hy = ty + L * k
            art.line(g.skin.head, 0.8, a * 0.8, 3.0) { moveTo(tx.toFloat(), ty.toFloat()); lineTo(tx.toFloat(), hy.toFloat()) }
            art.line(g.skin.head, 1.2, a, 3.0) { addArc((tx - 6 * k).toFloat(), (hy - 3 * k).toFloat(), tx.toFloat(), (hy + 3 * k).toFloat(), 0f, 180f) }
            if (t > 4.2 && t < 7.45) { var gx = tx; var gy = hy + 8 * k
                if (t > 5.4) { val u = Ez.sst(5.4, 5.9, t); gx = Ez.lerp(tx, ox + 15 * k, u); gy = Ez.lerp(hy + 8 * k, oy - orr - 20 * k, u) - 30 * k * sin(PI * u) + 3 * k * sin(t * 6) }
                if (t > 7.2) { val u = Ez.sst(7.2, 7.45, t); gy = Ez.lerp(gy, oy - orr * 0.3, u) }
                art.glyph("1", gx, gy, 20 * k, g.skin.head, 1.0, 14.0) }
        }),
        Antic(ZEN, 8.0, AK.ACT, { t, p, _ ->
            val z = Ez.win(t, 0.5, 7.1, 0.8, 0.8); p.open = Ez.lerp(1.0, 0.04, z); p.dy = -18 * z + 4 * sin(t * 1.6) * z; p.rainMul = Ez.lerp(1.0, 0.25, z); p.spin = Ez.lerp(1.0, 0.3, z)
            p.glow = 1 + 0.25 * sin(t * 1.2) * z; p.rim = 0.3 * z * (0.5 + 0.5 * sin(t * 1.2)); p.blink = Ez.blinkAt(t, 7.5)
        }, under = { a, t, p, g -> val z = Ez.win(t, 0.5, 7.1, 0.8, 0.8); val k = g.k
            a.fp.color = a.withA(g.skin.body, 0.25 * (1 - 0.5 * z)); a.cv.drawOval((g.cx - 60 * k * (1 - 0.3 * z)).toFloat(), (g.cy + g.r + 28 * k).toFloat(), (g.cx + 60 * k * (1 - 0.3 * z)).toFloat(), (g.cy + g.r + 40 * k).toFloat(), a.fp)
            for (i in 0 until 6) { val an = t * 0.8 + i * Ez.TAU / 6; if (sin(an) > 0) continue; a.glyph("·", g.ox(p) + cos(an) * (g.or(p) + 30 * k), g.oy(p) + sin(an) * 22 * k, 18 * k, g.skin.head, z * 0.5, 8.0) }
        }, over = { a, t, p, g -> val z = Ez.win(t, 0.5, 7.1, 0.8, 0.8); val k = g.k
            for (i in 0 until 6) { val an = t * 0.8 + i * Ez.TAU / 6; if (sin(an) <= 0) continue; a.glyph("·", g.ox(p) + cos(an) * (g.or(p) + 30 * k), g.oy(p) + sin(an) * 22 * k, 22 * k, g.skin.head, z, 10.0) }
        }),
        Antic(TIDY, 8.0, AK.ACT, { t, p, g ->
            val d = Ez.win(t, 0.4, 6.4, 0.5, 0.6); val drop = min(118.0, (g.floor - 40 - g.cy) / g.k - 50); p.dy = drop * d; p.scale = 1 - 0.18 * d
            p.dx = d * (-100 + 200 * Ez.sst(1.2, 5.0, t) + 12 * sin(t * 9) * Ez.win(t, 1.2, 5.0, 0.2, 0.2)); p.gy = 0.22 * d; p.gx = 0.08 * d; p.gazeK = d; p.roll = 6 * sin(t * 9) * Ez.win(t, 1.2, 5.0, 0.2, 0.2)
            p.happy = Ez.win(t, 5.6, 7.0, 0.1, 0.3); p.blink = Ez.blinkAt(t, 7.4)
        }, over = { a, t, p, g ->
            val k = g.k; val d = Ez.win(t, 0.4, 6.4, 0.5, 0.6); val fy = g.floor - 22.0; val ox = g.ox(p); val bx = ox - g.or(p) * 0.2 + 10 * k * sin(t * 9); val sweeping = t > 1 && t < 5.3; val end = g.X(330.0)
            for (i in 0 until 9) { val x0 = g.X(62.0 + i * 28 + Ez.hash(i.toDouble(), 1.0, 1.0) * 10); var x = x0
                if (sweeping || t >= 5.3) x = max(x0, min(end, (if (t >= 5.3) end else bx + 16 * k) + (if (t >= 5.3) -i * 2.0 * k else 0.0))); if (sweeping && x0 > bx + 16 * k) x = x0
                val al = if (t > 5.5) 1 - Ez.sst(5.5, 5.7, t) else 1.0
                a.glyph(if (Ez.hash(i.toDouble(), 2.0, 2.0) < 0.5) "0" else "1", x, fy - (if (x > x0) Ez.hash(i.toDouble(), 3.0, 3.0) * 6 * k else 0.0), 11 * k, g.skin.body, 0.7 * al * Ez.sst(0.0, 0.2, t) * (1 - Ez.sst(7.5, 8.0, t)), 5.0) }
            a.burst(g, end, fy - 6, t - 5.55, 12, 60.0, 0.8, 9, 0.0, null, -40.0)
            if (d > 0.05) { val oy = g.oy(p); val orr = g.or(p)
                a.line(g.skin.head, 1.8, d) { moveTo((ox - orr * 0.55).toFloat(), (oy + orr * 0.5).toFloat()); lineTo(bx.toFloat(), (fy - 10).toFloat()) }
                for (j in -2..2) a.line(g.skin.body, 1.2, d, 3.0) { moveTo(bx.toFloat(), (fy - 12).toFloat()); lineTo((bx + (j * 4 + 3) * k).toFloat(), (fy + 4).toFloat()) } }
            if (t > 5.8 && t < 7) a.glyph("✦", ox + g.or(p) * 0.8, g.oy(p) - g.or(p) - 6 * k, 14 * k, g.skin.head, Ez.win(t, 5.8, 7.0, 0.1, 0.3), 10.0)
        }),
        Antic(GARDEN, 9.0, AK.ACT, { t, p, _ ->
            val d = Ez.win(t, 0.4, 7.8, 0.6, 0.6); p.dx = -6 * d; p.dy = 60 * d; p.scale = 1 - 0.12 * d; p.gx = 0.2 * d; p.gy = 0.16 * d; p.gazeK = d; p.roll = 8 * Ez.win(t, 2.0, 5.0, 0.2, 0.2)
            p.happy = Ez.win(t, 5.8, 7.6, 0.1, 0.3); p.dy -= 18 * Ez.bump(t - 6.1, 0.35); p.blink = Ez.blinkAt(t, 8.5)
        }, over = { a, t, p, g ->
            val pa = Ez.win(t, 0.4, 8.6, 0.4, 0.4); if (pa <= 0) return@Antic; val k = g.k; val px = g.X(292.0); val py = g.floor - 14.0
            a.line(g.skin.body, 1.4, pa) { moveTo((px - 16 * k).toFloat(), (py - 14 * k).toFloat()); lineTo((px + 16 * k).toFloat(), (py - 14 * k).toFloat()); lineTo((px + 11 * k).toFloat(), (py + 6 * k).toFloat()); lineTo((px - 11 * k).toFloat(), (py + 6 * k).toFloat()); close() }
            val h = Ez.lerp(10.0, 74.0, Ez.sst(2.2, 5.5, t)) * k; val top = py - 14 * k
            a.line(g.skin.body, 1.6, pa) { moveTo(px.toFloat(), top.toFloat()); quadTo((px - 6 * k).toFloat(), (top - h / 2).toFloat(), px.toFloat(), (top - h).toFloat()) }
            for ((f, ch, ox) in listOf(Triple(0.35, "{", -9.0), Triple(0.6, "}", 9.0), Triple(0.85, "{", -8.0))) if (h > f * 74 * k) a.glyph(ch, px + ox * k, top - h * f, 12 * k, g.skin.body, pa * Ez.sst(f * 74 * k, f * 74 * k + 8 * k, h), 6.0)
            if (t > 5.5) a.glyph("✿", px, top - h - 4 * k, 18 * k * Ez.sst(5.5, 5.8, t), if (g.neon) g.skin.accent else g.skin.head, pa, 14.0)
            val w = Ez.win(t, 1.6, 5.2, 0.3, 0.3); if (w > 0) { val sx = g.ox(p) + g.or(p) * 0.85; val sy = g.oy(p) - g.or(p) * 0.05
                a.cv.save(); a.cv.translate(sx.toFloat(), sy.toFloat()); a.cv.rotate(Math.toDegrees(0.5 * w).toFloat()); a.cv.scale(k.toFloat(), k.toFloat())
                a.rrect(-8.0, -8.0, 18.0, 14.0, 3.0, null, g.skin.head, w, 1.3, 5.0); a.line(g.skin.head, 1.3, w, 5.0) { moveTo(10f, -4f); lineTo(22f, -10f) }; a.cv.restore()
                val tipx = sx + 18 * k; val tipy = sy + 2 * k
                for (j in 0 until 5) { val u = ((t * 1.6 + j / 5.0) % 1); a.glyph("·", Ez.lerp(tipx, px, u), Ez.lerp(tipy, top - h * 0.3, u), 14 * k, g.skin.head, w * sin(PI * u), 6.0) } }
        }),
        Antic(STARGAZER, 9.0, AK.ACT, { t, p, _ ->
            val sc = Ez.win(t, 0.6, 8.0, 0.4, 0.4) * (1 - Ez.win(t, 4.55, 6.6, 0.08, 0.3)); p.dx = 12 * sc; p.roll = 6 * sc; p.gx = 0.2 * sc; p.gy = -0.1 * sc; p.open = 1 - 0.15 * sc
            val sky = Ez.win(t, 4.55, 6.6, 0.08, 0.3); p.gy -= 0.22 * sky; p.gx -= 0.05 * sky; p.gazeK = max(Ez.win(t, 0.6, 8.0, 0.4, 0.4), sky); p.open += 0.45 * Ez.bump(t - 4.6, 0.5)
            p.scale += 0.06 * Ez.sprS(t - 4.6, 5.0, 16.0); p.happy = Ez.win(t, 5.3, 6.5, 0.1, 0.2); p.rainMul = 0.6 + 0.4 * (1 - Ez.win(t, 0.0, 9.0, 0.4, 0.4)); p.blink = Ez.blinkAt(t, 8.3)
        }, under = { a, t, _, g ->
            val al = Ez.win(t, 0.0, 9.0, 0.3, 0.3); val k = g.k; val y0 = g.top + 14.0; val y1 = max(y0 + 40, g.cy - g.r - 30.0)
            for (i in 0 until 16) { val x = 30 * k + Ez.hash(i.toDouble(), 9.0, 9.0) * (g.W - 60 * k); val y = y0 + Ez.hash(i.toDouble(), 8.0, 8.0) * (y1 - y0)
                a.glyph("·", x, y, (10 + Ez.hash(i.toDouble(), 7.0, 7.0) * 8) * k, g.skin.head, al * (0.3 + 0.5 * abs(sin(t * (1 + Ez.hash(i.toDouble(), 6.0, 6.0) * 2) + i))), 6.0) }
            val s = (t - 4.3) / 0.7; if (s > 0 && s < 1) { val x = Ez.lerp(g.W - 50.0 * k, 110 * k, s); val y = Ez.lerp(y0 + 10, y0 + (y1 - y0) * 0.7, s)
                a.line(g.skin.head, 1.6, al * (1 - s), 10.0) { moveTo(x.toFloat(), y.toFloat()); lineTo((x + 40 * k).toFloat(), (y - 22 * k).toFloat()) }; a.glyph("*", x, y, 14 * k, g.skin.head, al, 12.0) }
        }, over = { a, t, p, g ->
            val al = Ez.win(t, 0.5, 8.4, 0.4, 0.4); if (al <= 0) return@Antic; val k = g.k; val ex = g.cx + g.r * 0.82; val ey = g.cy - g.r * 0.32; val ox2 = g.X(348.0); val oy2 = g.Y(290.0)
            a.line(g.skin.body, 7.0, al, 8.0) { moveTo(ex.toFloat(), ey.toFloat()); lineTo(ox2.toFloat(), oy2.toFloat()) }
            a.line(Color.BLACK, 4.0, al, 0.0) { moveTo((ex + 4).toFloat(), (ey - 3).toFloat()); lineTo((ox2 - 3).toFloat(), (oy2 + 2).toFloat()) }
            val mx = (ex + ox2) / 2; val my = (ey + oy2) / 2; for (dx in doubleArrayOf(-22.0, 0.0, 22.0)) a.line(g.skin.body, 1.3, al, 4.0) { moveTo(mx.toFloat(), my.toFloat()); lineTo((mx + dx * k).toFloat(), (g.cy + g.r + 40 * k).toFloat()) }
            if (t > 5.3 && t < 6.5) a.glyph("✦", g.cx - g.r * 0.3, g.cy - g.r - 18 * k, 14 * k, g.skin.head, Ez.win(t, 5.3, 6.5, 0.1, 0.3), 10.0)
            if (t > 4.6 && t < 5.3) a.glyph("!", g.cx + 20 * k, g.cy - g.r - 20 * k, 20 * k, g.skin.head, Ez.win(t, 4.6, 5.3, 0.05, 0.2), 12.0)
        }),
        Antic(FLASHCARDS, 5.8, AK.ACT, { t, p, _ ->
            val a = Ez.win(t, 0.5, 5.4, 0.3, 0.3); p.gx = 0.12 * a; p.gy = 0.17 * a; p.gazeK = a; p.open -= 0.1 * a
            p.dy += 6 * Ez.bump(t - 2.2, 0.3); p.happy = max(p.happy, 0.5 * Ez.bump(t - 2.2, 0.5)); if (t > 3.4 && t < 4.4) { p.gx = 0.1; p.gy = -0.18; p.open = 0.8 }
            p.happy = max(p.happy, 0.6 * Ez.win(t, 4.6, 5.5, 0.1, 0.3)); p.blink = Ez.blinkAt(t, 5.0)
        }, over = { a, t, p, g ->
            val k = g.k
            fun card(x: Double, y: Double, rot: Double, sx: Double, face: String, al: Double) { if (al <= 0) return
                a.cv.save(); a.cv.translate(x.toFloat(), y.toFloat()); a.cv.rotate(Math.toDegrees(rot).toFloat()); a.cv.scale((sx * k).toFloat(), k.toFloat())
                a.rrect(-36.0, -24.0, 72.0, 48.0, 6.0, if (g.neon) 0xFF15121F.toInt() else 0xFF07140B.toInt(), g.skin.body, al, 1.2, 6.0)
                a.glyph(face, -20.0 * sign(if (sx == 0.0) 1.0 else sx), 0.0, 16.0, g.skin.head, al, 0.0)
                a.fp.color = a.withA(g.skin.body, 0.6 * al); a.cv.drawRect(-6f, -8f, 28f, -5f, a.fp); a.cv.drawRect(-6f, 0f, 20f, 3f, a.fp); a.cv.drawRect(-6f, 8f, 24f, 11f, a.fp); a.cv.restore() }
            val bx = g.ox(p) + 34 * k; val by = g.oy(p) + g.or(p) * 0.78
            val f1 = (t - 1.8) / 0.35; card(bx, by, -0.05, if (f1 < 0) 1.0 else if (f1 < 1) cos(PI * f1) else -1.0, if (f1 < 0.5) "Q" else "A", Ez.win(t, 0.5, 3.0, 0.3, 0.25) * (if (t < 3) 1.0 else 0.0))
            if (t > 3) card(bx + (1 - Ez.sst(3.0, 3.3, t)) * 60 * k, by, 0.04, 1.0, "Q", Ez.sst(3.0, 3.3, t) * (1 - Ez.sst(4.6, 5.2, t)))
        }, blushOnCatch = true),

        // =================== Neon City activities ===================
        Antic(SKATE, 7.0, AK.ACT, { t, p, _ ->
            val on = Ez.win(t, 0.5, 6.4, 0.2, 0.3); val v = 95 * sin((t - 1) * 1.1) * Ez.sst(1.0, 1.4, t) * (1 - Ez.sst(5.8, 6.3, t)); p.dx = v; p.rotOff = v / 92
            val vel = cos((t - 1) * 1.1); p.gx = 0.15 * sign(vel) * on; p.gazeK = on; p.roll = -6 * sign(vel) * on
            val u = (t - 3.4) / 0.5; if (u > 0 && u < 1) p.dy -= 48 * sin(PI * u); val l = t - 3.9; if (l > 0 && l < 0.8) { p.sy -= 0.16 * Ez.spr(l, 6.0, 14.0); p.sx += 0.1 * Ez.spr(l, 6.0, 14.0) }
            val u2 = (t - 5.1) / 0.35; if (u2 > 0 && u2 < 1) p.dy -= 20 * sin(PI * u2); p.happy = Ez.win(t, 3.95, 4.9, 0.08, 0.2); p.dy -= 10 * Ez.bump(t - 0.5, 0.3); p.blink = Ez.blinkAt(t, 6.6)
        }, over = { a, t, p, g ->
            val al = Ez.win(t, 0.5, 6.6, 0.15, 0.3); if (al <= 0) return@Antic; val k = g.k; val u = (t - 3.4) / 0.5; val flip = u > 0 && u < 1
            val bx = g.ox(p); val by = g.cy + g.r + 8 * k - (if (flip) 30 * k * sin(PI * u) else 0.0) - (if (t > 5.1 && t < 5.45) 12 * k * sin(PI * (t - 5.1) / 0.35) else 0.0)
            a.cv.save(); a.cv.translate(bx.toFloat(), by.toFloat()); a.cv.scale(k.toFloat(), (k * (if (flip) cos(2 * PI * u) else 1.0)).toFloat())
            a.line(g.skin.accent, 3.0, al, 10.0) { moveTo(-36f, -5f); lineTo(-30f, 0f); lineTo(30f, 0f); lineTo(36f, -5f) }; a.dot(-22.0, 6.0, 3.2, g.skin.head, al); a.dot(22.0, 6.0, 3.2, g.skin.head, al); a.cv.restore()
            val spd = abs(cos((t - 1) * 1.1)) * Ez.win(t, 1.2, 5.8, 0.2, 0.3); val dir = -sign(cos((t - 1) * 1.1)); val ox = g.ox(p); val oy = g.oy(p); val orr = g.or(p)
            for (j in 0 until 3) a.line(g.skin.head, 1.2, spd * 0.6, 6.0) { moveTo((ox + dir * (orr + 8 * k)).toFloat(), (oy - 24 * k + j * 24 * k).toFloat()); lineTo((ox + dir * (orr + (40 + j * 8) * k)).toFloat(), (oy - 24 * k + j * 24 * k).toFloat()) }
            if (t > 3.95 && t < 4.9) a.glyph("★", ox + orr * 0.7, oy - orr - 12 * k, 18 * k, g.skin.body, Ez.win(t, 3.95, 4.9, 0.05, 0.2), 12.0)
        }),
        Antic(DJ, 7.0, AK.ACT, { t, p, _ ->
            val on = Ez.win(t, 0.4, 6.4, 0.3, 0.4); p.dy = 4 * abs(sin(PI * t * 2)) * on + 6 * on; p.roll = 5 * sin(PI * t) * on; p.happy = 0.6 * on * (1 - Ez.win(t, 3.0, 3.6, 0.05, 0.05)); p.gy = 0.14 * on; p.gazeK = on
            if (t > 3 && t < 3.6) { p.dx = 4 * sin(t * 40); p.open = 1.3 }; val d = t - 4.5; if (d > 0) { p.flash = 0.7 * exp(-4 * d); p.rim += 1.2 * exp(-2.5 * d); p.scale += 0.08 * Ez.sprS(d, 4.0, 16.0) }
            p.rainMul = 1 + on * (0.2 + 0.8 * abs(sin(PI * t * 2))); p.blink = Ez.blinkAt(t, 6.7)
        }, over = { a, t, _, g ->
            val al = Ez.win(t, 0.4, 6.5, 0.3, 0.3); if (al <= 0) return@Antic; val k = g.k; val y = g.cy + g.r + 20 * k; val cx = g.cx.toDouble()
            a.rrect(cx - 80 * k, y - 12 * k, 160 * k, 28 * k, 6 * k, 0xFF120F1B.toInt(), g.skin.accent, al, 1.4, 10.0)
            for ((i, px) in listOf(0 to -46.0, 1 to 46.0)) { a.line(g.skin.head, 1.2, al, 0.0) { addCircle((cx + px * k).toFloat(), (y + 2 * k).toFloat(), (11 * k).toFloat(), Path.Direction.CW) }
                val ang = if (i == 0 && t > 3 && t < 3.6) sin(t * 30) * 1.5 else t * 8
                a.line(g.skin.head, 1.2, al, 0.0) { moveTo((cx + px * k).toFloat(), (y + 2 * k).toFloat()); lineTo((cx + px * k + cos(ang) * 10 * k).toFloat(), (y + 2 * k + sin(ang) * 10 * k).toFloat()) } }
            for (i in 0 until 5) { a.fp.color = a.withA(if (i % 2 == 1) g.skin.accent else g.skin.body, al); a.cv.drawRect((cx + (-14 + i * 6) * k).toFloat(), (y - 2 * k).toFloat(), (cx + (-11 + i * 6) * k).toFloat(), (y + (6 + 4 * abs(sin(t * 6 + i))) * k).toFloat(), a.fp) }
            val base = g.floor - 8.0; val bw = (g.W - 72) / 16.0
            for (i in 0 until 16) { val h = (6 + 40 * abs(sin(PI * t * 2 + i * 0.7)) * (0.4 + 0.6 * abs(sin(i * 1.3 + t))) * (if (t > 4.5) 1.3 else 1.0)) * k
                a.fp.color = a.withA(if (i % 3 == 0) g.skin.accent else if (i % 3 == 1) g.skin.head else g.skin.body, al * 0.7); a.cv.drawRect((36 + i * bw).toFloat(), (base - h).toFloat(), (36 + i * bw + bw * 0.56).toFloat(), base.toFloat(), a.fp) }
            for (i in 0 until 3) { val s = ((t * 0.8 + i / 3.0) % 1); a.glyph("♪", cx + (-60 + i * 55 + 10 * sin(s * 6)) * k, y - (20 + s * 120) * k, 14 * k, if (i % 2 == 1) g.skin.head else g.skin.accent, al * sin(PI * s), 10.0) }
        }),
        Antic(ARCADE, 6.2, AK.ACT, { t, p, _ ->
            val on = Ez.win(t, 0.4, 5.0, 0.3, 0.4); p.dx = -16 * on + 1.5 * sin(t * 50) * on * (if (t < 3.5) 1.0 else 0.0); p.gx = 0.22 * on; p.gy = -0.02; p.gazeK = on
            p.glow += 0.2 * sin(t * 14) * on; p.open = 1 + 0.3 * Ez.bump(t - 3.5, 0.4); p.happy = Ez.win(t, 3.6, 5.2, 0.1, 0.3); p.dy -= 20 * Ez.bump(t - 3.6, 0.4); p.blink = Ez.blinkAt(t, 5.6)
        }, over = { a, t, _, g ->
            val al = Ez.win(t, 0.4, 5.6, 0.3, 0.5); if (al <= 0) return@Antic; val k = g.k; val x = g.X(310.0); val y = g.Y(440.0)
            a.rrect(x - 30 * k, y - 60 * k, 60 * k, 120 * k, 6 * k, 0xFF120F1B.toInt(), g.skin.accent, al, 1.4, 10.0)
            a.rrect(x - 22 * k, y - 50 * k, 44 * k, 36 * k, 0.0, null, g.skin.head, al, 1.0, 0.0)
            if (t < 3.5) for (i in 0 until 4) { a.fp.color = a.withA(if (i % 2 == 1) g.skin.body else g.skin.accent, al); val bx = x + (-18 + (t * 20 + i * 11) % 36) * k; val by = y + (-44 + i * 7) * k; a.cv.drawRect(bx.toFloat(), by.toFloat(), (bx + 5 * k).toFloat(), (by + 4 * k).toFloat(), a.fp) }
            val hi = t > 3.5; val txt = if (hi) (if (g.neon) "HI-SCORE!" else "hi-score!") else (floor(Ez.clamp(t - 0.4, 0.0, 9.0) * 1337).toInt()).toString().padStart(5, '0')
            a.glyph(txt, x, y - 22 * k, 7 * k, if (hi && floor(t * 8).toInt() % 2 == 1) g.skin.accent else g.skin.head, al, 0.0)
            a.dot(x - 8 * k, y + 4 * k, 3 * k, g.skin.accent, al); a.dot(x + 8 * k, y + 4 * k, 3 * k, g.skin.body, al)
        }, blushOnCatch = true),
        Antic(GRAFFITI, 8.0, AK.ACT, { t, p, g ->
            val on = Ez.win(t, 0.4, 7.4, 0.5, 0.6); p.dx = 70 * on; p.dy = min(110.0, (g.floor - 30 - g.cy) / g.k - 50) * on; p.scale = 1 - 0.15 * on
            val q = Ez.sst(1.2, 4.6, t); val pt = tagPoint(q); p.gx = on * Ez.clamp((pt[0] - 250) / 300, -0.22, 0.22); p.gy = on * -0.2; p.gazeK = on; p.dx += 1.5 * sin(t * 30) * Ez.win(t, 1.2, 4.6, 0.1, 0.1)
            p.happy = Ez.win(t, 5.4, 6.8, 0.1, 0.3); p.dy -= 16 * Ez.bump(t - 5.5, 0.35); p.blink = Ez.blinkAt(t, 7.6)
        }, over = { a, t, p, g ->
            val q = Ez.sst(1.2, 4.6, t); val fade = 1 - Ez.sst(6.8, 7.6, t); val k = g.k; val oyTag = max(g.top + 16.0 - g.Y(190.0), 0.0)
            val tx = { x: Double -> g.X(x) }
            val ty = { y: Double -> g.Y(y) + oyTag }
            if (q > 0) { for ((col, w, off) in listOf(Triple(g.skin.head, 8.0, 2.0), Triple(g.skin.accent, 5.0, 0.0))) { var rem = q * TAG_TOT
                    a.line(col, w * k, fade * (if (col == g.skin.head) 0.5 else 1.0), 12.0) { for (sg in TAG_SEGS) { if (rem <= 0) break; val kk = min(1.0, rem / sg[4])
                        moveTo((tx(sg[0]) + off).toFloat(), (ty(sg[1]) + off).toFloat()); lineTo((tx(Ez.lerp(sg[0], sg[2], kk)) + off).toFloat(), (ty(Ez.lerp(sg[1], sg[3], kk)) + off).toFloat()); rem -= sg[4] } } }
                val dr = Ez.sst(4.8, 5.8, t); for (dd in listOf(doubleArrayOf(110.0, 250.0, 26.0), doubleArrayOf(200.0, 275.0, 18.0), doubleArrayOf(150.0, 250.0, 14.0)))
                    a.line(g.skin.accent, 2.4 * k, fade, 0.0) { moveTo(tx(dd[0]).toFloat(), ty(dd[1]).toFloat()); lineTo(tx(dd[0]).toFloat(), (ty(dd[1]) + dd[2] * k * dr).toFloat()) } }
            val spv = Ez.win(t, 1.1, 4.7, 0.1, 0.2); if (spv > 0) { val nx = g.ox(p) - g.or(p) * 0.75; val ny = g.oy(p) - g.or(p) * 0.75; val pt = tagPoint(q)
                a.cv.save(); a.cv.translate(nx.toFloat(), ny.toFloat()); a.cv.rotate(-34f); a.cv.scale(k.toFloat(), k.toFloat()); a.rrect(-5.0, -12.0, 10.0, 22.0, 3.0, g.skin.head, null, spv); a.cv.restore()
                for (j in 0 until 8) { val u = Ez.hash(j.toDouble(), floor(t * 20), 4.0); a.glyph("·", Ez.lerp(nx, tx(pt[0]), u) + (Ez.hash(j.toDouble(), 2.0, floor(t * 20)) - 0.5) * 10 * u * k, Ez.lerp(ny, ty(pt[1]), u), 9 * k, g.skin.accent, spv * 0.7, 6.0) } }
        }),
        Antic(SIGN, 7.0, AK.ACT, { t, p, _ ->
            val look = Ez.win(t, 0.7, 5.2, 0.2, 0.3); p.gy = -0.22 * look; p.gazeK = look; p.open = 1 - 0.25 * Ez.win(t, 1.2, 2.9, 0.2, 0.1); p.dx = 1.5 * sin(t * 20) * Ez.win(t, 2.2, 2.9, 0.1, 0.1)
            val u = (t - 2.9) / 0.5; if (u > 0 && u < 1) { p.dy -= 70 * sin(PI * u); p.sy += 0.1 * Ez.bump(u, 0.6) }; val l = t - 3.4; if (l > 0 && l < 0.8) { p.sy -= 0.15 * Ez.spr(l, 6.0, 14.0); p.sx += 0.1 * Ez.spr(l, 6.0, 14.0) }
            p.happy = Ez.win(t, 3.6, 5.0, 0.1, 0.3); if (t > 3.2) p.rim += 0.8 * exp(-3 * (t - 3.2)); p.blink = Ez.blinkAt(t, 6.2)
        }, over = { a, t, _, g ->
            val al = Ez.win(t, 0.3, 6.7, 0.3, 0.4); if (al <= 0) return@Antic; val k = g.k; val y = max(g.top + 40.0, g.cy - g.r - 120 * k); val sh = if (t > 3.2 && t < 3.7) 3 * sin(t * 60) * k else 0.0; val cx = g.cx.toDouble()
            a.rrect(cx - 70 * k + sh, y - 28 * k, 140 * k, 46 * k, 8 * k, null, g.skin.head, al, 1.5, 10.0)
            val word = if (g.neon) "OPEN" else "open"
            for (i in 0 until 4) { var on = 1.0; if (i == 2) { if (t > 0.6 && t < 3.25) on = if (Ez.hash(floor(t * 14), 3.0, 3.0) < 0.35) 1.0 else 0.12; if (t > 5.6 && t < 5.8) on = 0.12 }
                val bright = if (t > 3.25 && t < 3.7) 1.6 else 1.0; a.glyph(word[i].toString(), cx + (-45 + i * 30) * k + sh, y - 5 * k, 30 * k, g.skin.accent, al * on, 18 * on * bright, condensed = true) }
            if (t > 2.2 && t < 2.9) a.glyph("#!?", cx + g.r * 0.6, g.cy - g.r - 14 * k, 13 * k, g.skin.body, Ez.win(t, 2.2, 2.9, 0.05, 0.1), 8.0)
        }),
        Antic(JUGGLE, 7.0, AK.ACT, { t, p, g ->
            val on = Ez.win(t, 0.3, 5.0, 0.2, 0.01); p.dy = 3 * abs(sin(PI * (t - 0.6) / 0.4)) * on; val bs = juggle(t, g); var top: DoubleArray? = null; for (b in bs) if (top == null || b[2] < top[2]) top = b
            if (top != null && t < 5.2) { gazeTo(p, top[1] - g.cx, top[2] - g.cy, 0.2, on) }
            val s = t - 5.35; if (s > 0 && s < 1) { p.sy -= 0.12 * Ez.spr(s, 6.0, 16.0); p.sx += 0.08 * Ez.spr(s, 6.0, 16.0); p.open -= 0.5 * Ez.bump(s, 0.4) }
            p.happy = Ez.win(t, 5.7, 6.6, 0.1, 0.2); p.roll = -8 * Ez.win(t, 5.7, 6.6, 0.1, 0.3); p.blink = Ez.blinkAt(t, 6.75)
        }, over = { a, t, p, g ->
            for (b in juggle(t, g)) a.glyph(if (b[0] == 1.0) "1" else "0", b[1], b[2], 24 * g.k, if (b[0] == 1.0) g.skin.head else g.skin.body, b[3], 12.0)
            val s = t - 5.35; if (s > 0 && s < 0.7) a.glyph("*", g.cx + 18 * g.k, g.cy - g.r - 18 * g.k, 16 * g.k, g.skin.head, 1 - s / 0.7, 10.0); a.blush(g, p, Ez.win(t, 5.7, 6.6, 0.1, 0.3))
        }),

        // =================== idle mannerisms ===================
        Antic(DEJAVU, 2.7, AK.QUIRK, { s, p, _ ->
            val g1 = Ez.win(s, 0.2, 0.9, 0.12, 0.15); val g2 = Ez.win(s, 1.35, 2.05, 0.12, 0.15); p.gx = 0.16 * (g1 + g2); p.gy = -0.04 * (g1 + g2); p.gazeK = max(g1, g2)
            p.flash = 0.5 * exp(-6 * max(0.0, s - 1.3)) * (if (s > 1.3) 1.0 else 0.0); p.rim += 0.4 * Ez.bump(s - 1.3, 0.4); p.open -= 0.15 * g2; p.blink = Ez.blinkAt(s, 2.3)
        }),
        Antic(STARE, 3.2, AK.QUIRK, { s, p, _ -> val w = Ez.win(s, 0.2, 3.0, 0.5, 0.5); p.open -= 0.45 * w; p.gx = 0.0; p.gy = 0.02 * w; p.gazeK = w; p.blink = max(Ez.blinkAt(s, 1.6), 0.0) }),
        Antic(BOB, 4.0, AK.QUIRK, { s, p, _ -> val on = Ez.win(s, 0.0, 4.0, 0.3, 0.4); p.dy = 3 * abs(sin(PI * s * 2)) * on; p.roll = 4 * sin(PI * s) * on; p.happy = 0.3 * on },
            over = { a, s, p, g -> val on = Ez.win(s, 0.0, 4.0, 0.3, 0.4); for (i in 0 until 2) { val u = ((s * 0.5 + i * 0.5) % 1); a.glyph("♪", g.ox(p) + g.or(p) * (0.8 + 0.2 * i) + 10 * g.k * sin(u * 6), g.oy(p) - g.or(p) * 0.6 - u * 60 * g.k, 13 * g.k, if (i == 0) g.skin.accent else g.skin.head, on * sin(PI * u), 8.0) } }),
        Antic(WINK, 0.9, AK.QUIRK, { s, p, _ -> p.wink = Ez.bump(s - 0.1, 0.5); p.happy = 0.25 * Ez.bump(s - 0.1, 0.6); p.roll = 4 * Ez.bump(s - 0.1, 0.6) }),
    ).plus(OdstAntics.list).plus(Micros.list).plus(HolidayAntics.list).associateBy { it.id } }

    operator fun get(id: String): Antic? = all[id]

    private fun drop2(t: Double, g: Geo): DoubleArray {
        if (t < 3.3) return doubleArrayOf(g.X(252.0), g.Y(120 + (t - 2.2) * 200)); val s = t - 3.3
        return doubleArrayOf(g.X(252 + s * 280), g.Y(340 - s * 230 + s * s * 300))
    }
    /** Three glyphs thrown in a cascade, then a miss that bonks the orb. Each row: [index, x, y, alpha]. */
    private fun juggle(t: Double, g: Geo): List<DoubleArray> {
        val out = ArrayList<DoubleArray>(3); val k = g.k; val cx = g.cx.toDouble(); val cy = g.cy.toDouble(); val r0 = g.r.toDouble()
        for (i in 0 until 3) { if (t < 0.3) continue
            if (t < 0.6) { val u = (t - 0.3) / 0.3; out.add(doubleArrayOf(i.toDouble(), cx + (i - 1) * 30 * k * u, cy - r0 * 0.6 - (40 * u + 20 * i * u) * k, u)); continue }
            val T = (t - 0.6) / 1.2 + i / 3.0; val u = T % 1; val kk = floor(T).toInt(); var x = (if ((kk + i) % 2 == 1) 1 else -1) * Ez.lerp(-40.0, 40.0, u) * k; var y = cy - r0 - 6 * k - 110 * k * 4 * u * (1 - u)
            if (t > 5) { val s = t - 5; if (i == 0) { val u2 = min(1.0, s / 0.35); x = Ez.lerp(x, 18 * k, u2); y = Ez.lerp(y, cy - r0 - 6 * k, u2 * u2); if (s > 0.35) { x = 18 * k + s * 40 * k; y = cy - r0 - 6 * k + (s - 0.35) * (s - 0.35) * 900 * k } } else y += s * s * 700 * k }
            out.add(doubleArrayOf(i.toDouble(), cx + x, y, if (t > 5) 1 - Ez.sst(5.6, 6.1, t) else 1.0)) }
        return out
    }
    private val TAG_PTS = arrayOf(doubleArrayOf(70.0, 250.0), doubleArrayOf(90.0, 190.0), doubleArrayOf(110.0, 250.0), doubleArrayOf(130.0, 190.0), doubleArrayOf(150.0, 250.0), doubleArrayOf(150.0, 250.0),
        doubleArrayOf(175.0, 200.0), doubleArrayOf(200.0, 190.0), doubleArrayOf(215.0, 215.0), doubleArrayOf(190.0, 235.0), doubleArrayOf(220.0, 255.0), doubleArrayOf(200.0, 275.0), doubleArrayOf(170.0, 270.0),
        doubleArrayOf(175.0, 200.0), doubleArrayOf(175.0, 200.0), doubleArrayOf(240.0, 260.0), doubleArrayOf(255.0, 190.0), doubleArrayOf(270.0, 260.0), doubleArrayOf(235.0, 225.0), doubleArrayOf(280.0, 225.0),
        doubleArrayOf(60.0, 300.0), doubleArrayOf(160.0, 292.0), doubleArrayOf(300.0, 288.0))
    /** [ax, ay, bx, by, length] per tag stroke segment. */
    private val TAG_SEGS: List<DoubleArray> = (1 until TAG_PTS.size).map { i -> val a = TAG_PTS[i - 1]; val b = TAG_PTS[i]; doubleArrayOf(a[0], a[1], b[0], b[1], hypot(b[0] - a[0], b[1] - a[1])) }
    private val TAG_TOT = TAG_SEGS.sumOf { it[4] }
    private fun tagPoint(q: Double): DoubleArray { var rem = q * TAG_TOT
        for (s in TAG_SEGS) { if (rem <= s[4]) { val k = if (s[4] > 0) rem / s[4] else 0.0; return doubleArrayOf(Ez.lerp(s[0], s[2], k), Ez.lerp(s[1], s[3], k)) }; rem -= s[4] }
        val s = TAG_SEGS.last(); return doubleArrayOf(s[2], s[3]) }
}
