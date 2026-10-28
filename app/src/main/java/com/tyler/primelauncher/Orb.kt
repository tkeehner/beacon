package com.tyler.primelauncher

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
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
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Prime code-sphere, ported from the simulator's Canvas engine. Works in dp; sprites are rendered at px resolution. */
enum class Mode { IDLE, THINK, SUMMON, LISTEN, TALK }

class Skin(val body: Int, val head: Int, val accent: Int, val glyphs: String, val eye: Int, val neon: Boolean, val odst: Boolean = false) {
    /** Cut corners + condensed upper-case labels (Neon City and ODST). */
    val angular get() = neon || odst
    companion object {
        val MATRIX = Skin(0xFF3DFF7A.toInt(), 0xFFE2FFE9.toInt(), 0xFF3DFF7A.toInt(), "01", 0xFF3DFF7A.toInt(), false)
        val NEON = Skin(0xFFF7E733.toInt(), 0xFF3EE8F0.toInt(), 0xFFFF3D81.toInt(), "0123456789ABCDEF", 0xFF3EE8F0.toInt(), true)
        /** 0.1.17 ODST: amber comms + steel, the orb is the sage-green eyes-only face (see OdstFace). */
        val ODST = Skin(0xFFFFB23F.toInt(), 0xFFE9EEF5.toInt(), 0xFF8FB8E0.toInt(), "0123456789", 0xFF8FB8E0.toInt(), false, true)
        fun of(theme: String) = when (theme) { "neon" -> NEON; "odst" -> ODST; else -> MATRIX }
        /** Orb radius per skin (dp): the ODST face is deliberately small, like the reference. */
        fun radius(theme: String) = when (theme) { "neon" -> 96f; "odst" -> 96f; else -> 102f }
    }
}

class Slot(val x: Float, val y: Float, val s: Float)
class RingTile(val name: String, val letter: String, val icon: IconPaths?, val pinned: Boolean)
class Ring(val tiles: List<RingTile>, val slots: List<Slot>, val orbits: List<FloatArray> = emptyList(), val fy: Float = 1f)

/** Extent of a ring tile around its slot centre, in dp: the hot tile grows 14%, the label sits under it. */
object RingBox {
    const val HOT = 1.14f; const val LABEL = 6f + 14f
    fun top(sl: Slot) = sl.y - sl.s / 2 * HOT
    fun bottom(sl: Slot) = sl.y + sl.s / 2 * HOT + LABEL
}
/**
 * 0.1.11: keep the ring between the clock/date/weather header and the footer hint without moving the orb.
 * Squashes the ring vertically (every slot's y and the drawn orbit outlines by the same factor [Ring.fy]) just enough
 * that the topmost tile clears [safeTop] and the lowest label clears [safeBottom] (both are offsets from the orb centre's
 * y: safeTop = how far above the centre the ring may reach, safeBottom = how far below). Never squashes below [minFy];
 * anything still out of bounds is clamped individually as a last resort. Pure, unit-tested.
 */
fun fitRing(r: Ring, upRoom: Float, downRoom: Float, minFy: Float = 0.6f): Ring {
    if (r.slots.isEmpty()) return r
    var fy = 1f
    for (sl in r.slots) {
        val half = sl.s / 2 * RingBox.HOT
        if (sl.y < 0) { val need = (upRoom - half) / -sl.y; if (need < fy) fy = need } // -y*fy + half <= upRoom
        if (sl.y > 0) { val need = (downRoom - half - RingBox.LABEL) / sl.y; if (need < fy) fy = need }
    }
    fy = fy.coerceIn(minFy, 1f)
    if (fy >= 1f) return r
    val slots = r.slots.map { sl -> var y = sl.y * fy
        y = y.coerceIn(-upRoom + sl.s / 2 * RingBox.HOT, downRoom - sl.s / 2 * RingBox.HOT - RingBox.LABEL); Slot(sl.x, y, sl.s) }
    return Ring(r.tiles, slots, r.orbits, fy)
}

fun ringSlots(n: Int, radius: Float, s: Float): List<Slot> = (0 until n).map { i ->
    val a = -PI / 2 + i.toDouble() / n * PI * 2; Slot((cos(a) * radius).toFloat(), (sin(a) * radius).toFloat(), s)
}
fun orbitSlots(orbit: FloatArray, count: Int, phaseDeg: Float, s: Float): List<Slot> {
    val (rx, ry, rot) = Triple(orbit[0], orbit[1], orbit[2]); val r = rot * PI / 180
    return (0 until count).map { i -> val a = (phaseDeg + 360f / count * i) * PI / 180; val x = rx * cos(a); val y = ry * sin(a)
        Slot((x * cos(r) - y * sin(r)).toFloat(), (x * sin(r) + y * cos(r)).toFloat(), s) }
}

/** Park-Miller RNG (same sequence as the simulator's makeRng). */
class Rng(seed: Int) { private var s: Long = (seed % 2147483647L).let { if (it <= 0) it + 2147483646L else it }
    fun next(): Double { s = (s * 16807L) % 2147483647L; return (s - 1) / 2147483646.0 } }

private fun clamp(v: Double, a: Double, b: Double) = max(a, min(b, v))
private fun ease(p: Double) = if (p < 0.5) 4 * p * p * p else 1 - (-2 * p + 2).pow(3) / 2
private fun smooth(a: Double, b: Double, v: Double): Double { val x = clamp((v - a) / (b - a), 0.0, 1.0); return x * x * (3 - 2 * x) }

/**
 * Sleep/wake envelope layered over the orb's mood-driven lid/tired/pace/bright (HomeActivity keeps easing those toward the
 * time-of-day mood; this scales them, so the two never fight). [k] 1 = asleep, 0 = awake. Pure math, unit-tested.
 */
class SleepState {
    companion object {
        const val LID = 0.05; const val FALL_S = 2.4; const val WAKE_S = 1.1; const val SURGE_S = 1.4; const val BREATH_S = 5.6
        /** 0.1.7: unlock-to-home wake. Slow and gentle; the whole home screen fades up with it (see StageView.reveal). */
        const val SLOW_WAKE_S = 2.2
        private fun smooth01(x: Double): Double { val c = x.coerceIn(0.0, 1.0); return c * c * (3 - 2 * c) }
    }
    var k = 0.0; private set
    var target = 0.0; private set
    /** Seconds since the last wake started (large = no surge). */
    var sinceWake = 99.0; private set
    val asleep get() = target >= 1.0
    /** Duration of the current/last wake ramp, and its glow surge (longer + softer for the slow wake). */
    var wakeS = WAKE_S; private set
    private var surgeS = SURGE_S; private var surgeAmp = 0.85
    /** Eased amount used by every effect. */
    val e get() = smooth01(k)
    fun sleepNow() { k = 1.0; target = 1.0; sinceWake = 99.0 }
    fun fallAsleep() { target = 1.0 }
    /** Starts the wake ramp; false if already awake (nothing to play). */
    fun wake(dur: Double = WAKE_S): Boolean {
        if (target == 0.0 && k <= 0.0) return false
        target = 0.0; sinceWake = 0.0; wakeS = dur.coerceAtLeast(0.05)
        val f = wakeS / WAKE_S; surgeS = SURGE_S * (1 + (f - 1) * 0.75); surgeAmp = 0.85 / kotlin.math.sqrt(f.coerceAtLeast(1.0)) // slow wake: longer, softer bloom
        return true
    }
    fun copyFrom(o: SleepState) { k = o.k; target = o.target; sinceWake = o.sinceWake; wakeS = o.wakeS; surgeS = o.surgeS; surgeAmp = o.surgeAmp }
    fun step(dt: Double) {
        sinceWake += dt
        k = if (target > k) min(target, k + dt / FALL_S) else max(target, k - dt / wakeS)
    }
    fun lid(base: Double) = base + (LID - base) * e
    fun tired(base: Double) = base + (1.0 - base) * e
    fun pace(base: Double) = base * (1 - 0.82 * e)
    /** Dimmed, slow breathing glow while asleep, plus a surge that peaks just after waking. */
    fun bright(base: Double, t: Double): Double {
        val breath = 0.5 + 0.5 * sin(t * 2 * PI / BREATH_S)
        return base * (1 - e * (0.72 - 0.3 * breath)) + surge()
    }
    fun surge(): Double { val s = sinceWake; return if (s < 0 || s > surgeS) 0.0 else surgeAmp * sin(PI * s / surgeS).pow(2) * exp(-s * 1.2 * SURGE_S / surgeS) }
    /** Extra slow, deep size breathing while asleep (multiplier on radius). */
    fun breathScale(t: Double) = 1 + 0.035 * e * sin(t * 2 * PI / BREATH_S)
}

class Orb(var cx: Float, var cy: Float, val r: Float, val skin: Skin, val dpr: Float, quality: Double, var ring: Ring?, var glitch: Boolean = true) {
    private companion object {
        val EYES = arrayOf(doubleArrayOf(-0.47, -0.3, 0.15, 0.25, 0.38), doubleArrayOf(-0.07, -0.25, 0.16, 0.24, 0.5))
        val VIS = arrayOf(doubleArrayOf(-0.45, -0.22, 0.22), doubleArrayOf(-0.02, -0.19, -0.22))
    }
    private class Col(val lon: Double, val speed: Double, val heads: DoubleArray, val tail: Double, val chars: IntArray, val mask: BooleanArray, val jit: Double)
    private class Spark(val a: Double, val len: Int, val f: Double, val ph: Double, val ch: Int)
    private class Flyer(val tile: Int, val ox: Double, val oy: Double, val ma: Double, val md: Double, val tx: Double, val ty: Double, val delay: Double, val ch: Int, val ph: Double)

    var t = 0.0; var rot = 0.0; var mode = Mode.IDLE; var sp = 0.0; var th = 0.0; var blink = 0.0; private var blinkStart = -9.0; var nextBlink = 2.2
    var gx = 0.0; var gy = 0.0; private var tgx = 0.0; private var tgy = 0.0; private var touchT = -99.0; var hot = -1
    var ls = 0.0; var lvl = 0.0; var hov = 0.0; var hovT = 0.0; private var kickT = -9.0; var still = false
    private var sweepT = -9.0; private var nextSweep = 6.0; private var flick = -1; private var flickT = -9.0
    private val tileK = FloatArray(32); var pace = 1.0; var bright = 1.0; var lid = 1.0; var tired = 0.0
    /** Asleep on lock / screen off; [wake] plays eyes-open + glow surge + kick. */
    val sleep = SleepState()
    /** 0.1.15 companion body language (StageView sets it every frame; identity = no effect). */
    val pose = Pose()
    private var glitchT = -9.0; private var glitchDur = 0.0; var nextGlitch = 1.6
    // Glitch bands (y offset, half height, x shift): fixed slots reused, since THINK now glitches several times a second.
    private val bands = Array(4) { DoubleArray(3) }; private var nBands = 0
    private val rnd = Rng(7); private val nG = skin.glyphs.length
    /** 0.1.13 personality: [lively] = mood (time of day) dial, [amp] = 0.5 with Reduce Motion, [yawny] = night yawns. */
    var lively = 1.0; var amp = 1.0; var yawny = false
    val curious = Curiosity(rnd)
    private var kickAmp = 1.0; private var perkT = -99.0; private var fidgetT = -99.0; private var yawnT = -99.0; private var surgeT = -99.0
    private var prevMode = Mode.IDLE; private var wakeKick = false
    val gs: Double = max(4.5, r * 0.062) / sqrt(if (quality > 1) 1.0 else if (quality < 0.6) 0.8 else 1.0)
    private val nLat = ((r * PI * 0.92) / (gs * 1.12)).roundToInt()
    // Per-row latitude, cos, sin and edge fade: constant, so computed once instead of ~2,600 trig calls per frame.
    private val latTh = DoubleArray(nLat) { i -> 1.42 - (i + 0.5) * (2 * 1.42 / nLat) }
    private val latCos = DoubleArray(nLat) { cos(latTh[it]) }; private val latSin = DoubleArray(nLat) { sin(latTh[it]) }
    private val latEdge = DoubleArray(nLat) { smooth(0.0, 0.18, latCos[it]) }
    private val cols = ArrayList<Col>(); private val sparks = ArrayList<Spark>(); private val flyers = ArrayList<Flyer>()
    /** 0.1.6: all glyph sprites live in ONE atlas bitmap (was 48 separate bitmaps = 48 GPU uploads and a batch break per switch). */
    private var atlas: Bitmap? = null; private val cells = ArrayList<android.graphics.Rect>(); private val quality = quality
    // Cached draw objects (0.1.6): the old draw() built 4-5 RadialGradients and 2-3 Paints every frame.
    private val bodyGlow: RadialGradient = RadialGradient(0f, 0f, 1f, withA(skin.body, 0.07), withA(skin.body, 0.02), Shader.TileMode.CLAMP)
    private val eyeGlow: RadialGradient = RadialGradient(0f, 0f, 1f, intArrayOf(withA(skin.eye, 1.0), withA(skin.eye, 0.4), withA(skin.eye, 0.0)), floatArrayOf(0f, 0.75f, 1f), Shader.TileMode.CLAMP)
    private val ringCache = HashMap<Long, RadialGradient>()
    private val sp2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; blendMode = BlendMode.PLUS }
    private val op = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; pathEffect = DashPathEffect(floatArrayOf(3f, 6f), 0f) }
    private val fp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokes = doubleArrayOf(3.2, 0.06, 1.8, 0.1, 0.8, 0.16)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG); private val dst = RectF(); private val path = Path()
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val mono: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val disp: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    /** 0.1.17 ODST: face renderer, sign-panel flip, VISR night mode (StageView sets [visr]) and the glance dart. */
    private val odstFace = if (skin.odst) OdstFace() else null; val flip = FaceFlip(); private val fs = OdstFace.State()
    var visr = false; var glowOn = true; private var pgx = 0.0; private var pgy = 0.0; private var dartK = 0.0
    /** Tests: the ODST face showing right now. */
    val odstExpr get() = flip.face

    init {
        val nCols = if (skin.odst) 0 else (64 * quality).roundToInt()
        for (c in 0 until nCols) {
            val chars = IntArray(nLat) { (rnd.next() * nG).toInt() }; val mask = BooleanArray(nLat) { rnd.next() < 0.8 }
            val nh = 1 + (rnd.next() * 2).toInt(); val heads = DoubleArray(nh) { rnd.next() * nLat * 1.4 - nLat * 0.2 }
            cols.add(Col(c.toDouble() / nCols * PI * 2, 7 + rnd.next() * 9, heads, 6 + rnd.next() * 10, chars, mask, rnd.next()))
        }
        repeat(if (skin.odst) 0 else (90 * quality).roundToInt()) {
            val vert = rnd.next() < 0.6; val a = if (vert) (if (rnd.next() < 0.5) -PI / 2 else PI / 2) + (rnd.next() - 0.5) * 1.3 else rnd.next() * PI * 2
            sparks.add(Spark(a, 1 + (rnd.next() * 3).toInt(), 2 + rnd.next() * 7, rnd.next() * 6.28, (rnd.next() * nG).toInt()))
        }
        ring?.let { buildFlyers(it) }
        val sprites = ArrayList<Bitmap>(); for (col in intArrayOf(skin.body, skin.head, skin.accent)) for (ch in skin.glyphs) sprites.add(sprite(ch, col))
        val sz = sprites[0].width; val pad = 2; val stride = sz + pad * 2
        val a = Bitmap.createBitmap(stride * nG, stride * 3, Bitmap.Config.ARGB_8888); val ac = Canvas(a)
        sprites.forEachIndexed { i, b -> val x = (i % nG) * stride + pad; val y = (i / nG) * stride + pad; ac.drawBitmap(b, x.toFloat(), y.toFloat(), null); cells.add(android.graphics.Rect(x, y, x + sz, y + sz)); b.recycle() }
        atlas = a
    }

    private fun g(tier: Int, ch: Int): Int = tier * nG + (((ch % nG) + nG) % nG)
    private fun sprite(ch: Char, color: Int): Bitmap {
        val s = kotlin.math.ceil(gs * 2 * dpr).toInt().coerceAtLeast(4); val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val q = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = if (skin.neon) disp else mono; textSize = (gs * (if (skin.neon) 1.0 else 1.05) * dpr).toFloat(); textAlign = Paint.Align.CENTER; this.color = color }
        val y = s / 2f - (q.descent() + q.ascent()) / 2
        q.setShadowLayer((gs * 0.6 * dpr).toFloat(), 0f, 0f, if (color == skin.head && !skin.neon) skin.body else color); c.drawText(ch.toString(), s / 2f, y, q)
        q.clearShadowLayer(); c.drawText(ch.toString(), s / 2f, y, q); return b
    }
    private fun buildFlyers(ring: Ring) {
        val per = (30 * quality).roundToInt()
        ring.slots.forEachIndexed { i, sl ->
            val a = atan2(sl.y.toDouble(), sl.x.toDouble()); val h = sl.s / 2.0
            for (j in 0 until per) {
                val u = j.toDouble() / per * 4; val side = floor(u).toInt(); val f = u - side
                val (px, py) = when (side) { 0 -> -h + f * 2 * h to -h; 1 -> h to -h + f * 2 * h; 2 -> h - f * 2 * h to h; else -> -h to h - f * 2 * h }
                val rr = sqrt(rnd.next()); val oa = rnd.next() * PI * 2
                flyers.add(Flyer(i, cos(oa) * rr, sin(oa) * rr, a + (rnd.next() - 0.5) * 1.6, 1.5 + rnd.next() * 0.9, sl.x + px, sl.y + py, rnd.next() * 0.3, (rnd.next() * nG).toInt(), rnd.next() * 6.28))
            }
        }
    }
    fun setRingSpec(r: Ring) { ring = r; flyers.clear(); buildFlyers(r); hot = -1 }
    fun forceBlink() { blinkStart = t; nextBlink = t + 3 + rnd.next() * 3 }
    /** 0.1.13: while thinking the neon skin glitches far more often (0.3-0.9 s apart, was 1.8-5 s), longer and with wider tears. */
    private fun forceGlitch() { val thk = mode == Mode.THINK || th > 0.3; glitchT = t; glitchDur = 0.09 + rnd.next() * 0.1 + (if (thk) Bold.GLITCH_THINK_LEN else 0.0)
        nextGlitch = t + (if (thk) Bold.GLITCH_THINK_MIN + rnd.next() * Bold.GLITCH_THINK_SPAN else Bold.GLITCH_IDLE_MIN + rnd.next() * Bold.GLITCH_IDLE_SPAN); nBands = min(bands.size, 2 + (rnd.next() * 3).toInt())
        val tear = 1 + Bold.GLITCH_TEAR * th * amp
        for (i in 0 until nBands) { val b = bands[i]; b[0] = (rnd.next() * 2 - 1) * r; b[1] = 3 + rnd.next() * 10; b[2] = (if (rnd.next() < 0.5) -1 else 1) * (4 + rnd.next() * 12) * tear } }
    private fun glitching() = skin.neon && glitch && t - glitchT < glitchDur
    /** Eyes follow a touch point (dp, screen coordinates). */
    fun touch(x: Float, y: Float) { val dx = x - cx; val dy = y - cy; val L = hypot(dx, dy).coerceAtLeast(1f); val k = min(1f, L / (r * 2.5f))
        val g = Bold.TOUCH_REACH * (0.6 + 0.4 * amp); tgx = dx / L * k * g; tgy = dy / L * k * g; touchT = t } // 0.1.13: was 0.1
    /**
     * 0.1.13: a finger landing (ACTION_DOWN). The eyes SNAP to it (fast chase, wider reach than [touch]) and the orb bounces;
     * the idle glance plan is dropped. Toned down with Reduce Motion.
     */
    fun touchDown(x: Float, y: Float) { val dx = x - cx; val dy = y - cy; val L = hypot(dx, dy).coerceAtLeast(1f); val k = min(1f, L / (r * 2.2f)) * Bold.SNAP_REACH * (0.6 + 0.4 * amp)
        tgx = dx / L * k; tgy = dy / L * k; touchT = t; curious.snap(t); kick(Bold.TOUCH_KICK) }
    fun lookAt(fx: Double, fy: Double) { tgx = clamp(fx, -0.16, 0.16); tgy = clamp(fy, -0.14, 0.14); touchT = t }
    /** Bounce. [s] = strength (1 = the original kick); extra strength above 1 is halved with Reduce Motion. */
    /** After setting [mode] directly on a fresh orb (theme rebuild): don't replay the mode-entry kick. */
    fun syncMode() { prevMode = mode }
    fun kick(s: Double = 1.0) { kickT = t; kickAmp = if (s <= 1.0) s else 1 + (s - 1) * amp }
    /** Tests: seconds since the last kick and its strength. */
    val kickAge get() = t - kickT
    val kickStrength get() = kickAmp
    /** Tests: current eye chase rate and perk / yawn / surge levels. */
    val gazeRate get() = curious.rate
    fun perkLevel() = Env.perk(t - perkT) * amp
    fun yawnLevel() = Env.yawnShut(t - yawnT)
    fun surgeLevel() = Env.surge(t - surgeT) * (0.5 + 0.5 * amp)
    /** 0.1.13 lean: the whole sphere drifts and rolls slightly toward where it is looking (dp, dp, degrees). */
    /** Eye gaze after the pose override ([Pose.gazeK] blends from Prime's own gaze to the pose's). */
    fun egx() = gx + (pose.gx - gx) * pose.gazeK
    fun egy() = gy + (pose.gy - gy) * pose.gazeK
    fun leanX() = egx() * r * Bold.LEAN_X * amp
    fun leanY() = egy() * r * Bold.LEAN_Y * amp
    fun roll() = egx() * Bold.ROLL_DEG * amp
    /** Wake up: eyes ramp open, tiredness clears, glow surges and the orb kicks. False if it was already awake. */
    /** 0.1.13: quick wake = a big recoil (1.6x kick) + glow surge; the slow unlock wake pops once as the eyes come open. */
    fun wake(dur: Double = SleepState.WAKE_S): Boolean { if (!sleep.wake(dur)) return false
        if (dur <= SleepState.WAKE_S) { kick(Bold.WAKE_KICK); surgeT = t } else wakeKick = true; lookAt(0.0, -0.06); return true }
    private fun kickEnv(): Double { val k = t - kickT; return if (k < 0 || k > 0.7) 0.0 else exp(-k * 6) * sin(k * 24) }
    fun radius(): Float { val fs = t - fidgetT; val ripple = if (curious.fidgetKind == Curiosity.FIDGET_RIPPLE) Bold.FIDGET_RIPPLE * amp * Env.fidget(fs) * sin(fs * 30) else 0.0
        return (r * (1 + 0.045 * hov + Bold.KICK_SWELL * kickAmp * kickEnv() + 0.02 * lvl * (1 - ls) + ripple + Bold.YAWN_SWELL * amp * Env.yawnSwell(t - yawnT) + Bold.PERK_SWELL * perkLevel() + Bold.SURGE_SWELL * surgeLevel())
            * (1 + Bold.BREATH * sin(t * PI * 2 / 4.2) + Bold.THINK_PULSE * th * sin(t * 9)) * (1 - 0.45 * ease(sp)) * (1 + ls * (0.03 + 0.09 * lvl)) * sleep.breathScale(t) * pose.scale).toFloat() }

    fun step(dt0: Double) {
        val dt = if (dt0.isNaN()) 0.0 else dt0.coerceIn(0.0, 0.1)
        t += dt
        sleep.step(dt)
        sp = clamp(sp + if (mode == Mode.SUMMON) dt / 0.9 else -dt / 0.7, 0.0, 1.0)
        th = clamp(th + if (mode == Mode.THINK) dt / 0.35 else -dt / 0.5, 0.0, 1.0)
        ls = clamp(ls + if (mode == Mode.LISTEN || mode == Mode.TALK) dt / 0.3 else -dt / 0.6, 0.0, 1.0)
        // 0.1.13 Layer 2: entering THINK / SUMMON lands with a bigger recoil + glow surge (LISTEN / TALK: surge only).
        if (mode != prevMode) { when (mode) {
                Mode.THINK -> { kick(Bold.THINK_KICK); surgeT = t; if (skin.neon && glitch) nextGlitch = t }
                Mode.SUMMON -> { kick(Bold.SUMMON_KICK); surgeT = t }
                Mode.LISTEN, Mode.TALK -> surgeT = t
                Mode.IDLE -> {} }
            prevMode = mode }
        if (wakeKick && sleep.k < 0.25) { wakeKick = false; kick(Bold.SLOW_WAKE_KICK); surgeT = t }
        if (sleep.asleep) wakeKick = false
        if (t > nextBlink && t - yawnT > Env.YAWN_S) forceBlink()
        if (skin.neon && glitch && t > nextGlitch) forceGlitch()
        val bl = 0.24 + 0.4 * sleep.tired(tired); val bd = t - blinkStart; blink = max(if (bd >= 0 && bd < bl) sin(PI * bd / bl) else 0.0, Env.yawnShut(t - yawnT))
        hov += (hovT - hov) * min(1.0, dt * 8)
        if (!still) {
            // 0.1.13 Layer 1: Curiosity plans saccades / double-takes / lock-ons, perk-ups, fidgets and (at night) yawns.
            curious.lively = lively; curious.amp = amp; curious.yawny = yawny
            val ev = curious.step(t, mode == Mode.IDLE && sleep.k < 0.3 && t - touchT > 3)
            if (ev and Curiosity.GLANCE != 0) { tgx = curious.tx; tgy = curious.ty }
            if (ev and Curiosity.PERK != 0) { perkT = t; kick(Bold.PERK_KICK * amp) }
            if (ev and Curiosity.FIDGET != 0) { fidgetT = t; when (curious.fidgetKind) {
                    Curiosity.FIDGET_SWEEP -> { sweepT = t; nextSweep = t + Bold.SWEEP_MIN_S + rnd.next() * Bold.SWEEP_SPAN_S }
                    else -> { flick = (rnd.next() * cols.size).toInt(); flickT = t } } }
            if (ev and Curiosity.YAWN != 0) { yawnT = t; blinkStart = -9.0; nextBlink = t + Env.YAWN_S + 2 + rnd.next() * 2 }
            if (t > nextSweep) { sweepT = t; nextSweep = t + Bold.SWEEP_MIN_S + rnd.next() * Bold.SWEEP_SPAN_S }
            if (rnd.next() < dt * Bold.FLICKS) { flick = (rnd.next() * cols.size).toInt(); flickT = t }
        }
        if (t - touchT > 2.6 && !curious.busy) { tgx *= max(0.0, 1 - dt * 1.5); tgy *= max(0.0, 1 - dt * 1.5) }
        val kk = min(1.0, dt * curious.rate); gx += (tgx - gx) * kk; gy += (tgy - gy) * kk
        if (skin.odst && dt > 0) { val ex = egx(); val ey = egy(); val v = hypot(ex - pgx, ey - pgy) / dt; pgx = ex; pgy = ey
            dartK = max(dartK * exp(-dt * 10), clamp((v - 0.25) * 0.5, 0.0, 1.0)) } // a quick glance squashes the eyes (mockup idleLife)
        val fid = if (curious.fidgetKind == Curiosity.FIDGET_RAIN) Bold.FIDGET_RAIN * amp * Env.fidget(t - fidgetT) else 0.0
        val pc = sleep.pace(pace); val speedMul = pc * (1 + th * (Bold.THINK_RAIN + Bold.THINK_RAIN_WOBBLE * sin(t * 3.1)) + sp * Bold.SUMMON_RAIN + ls * (Bold.LISTEN_RAIN + Bold.LISTEN_RAIN_LVL * lvl) + fid) * pose.rainMul
        rot += dt * (0.2 * pose.spin + Bold.THINK_SPIN * th) * pc
        for (c in cols) for (h in c.heads.indices) {
            val before = floor(c.heads[h]).toInt(); c.heads[h] += dt * c.speed * speedMul; val after = floor(c.heads[h]).toInt()
            for (i in max(before + 1, 0)..min(after, nLat - 1)) if (rnd.next() < 0.5) c.chars[i] = (rnd.next() * nG).toInt()
            if (c.heads[h] > nLat + c.tail) c.heads[h] = -rnd.next() * nLat * 0.6
        }
    }

    private fun bandShift(y: Double): Double { if (!glitching()) return 0.0; val dy = y - cy; for (i in 0 until nBands) { val b = bands[i]; if (abs(dy - b[0]) < b[1]) return b[2] }; return 0.0 }
    private fun withA(c: Int, a: Double) = Color.argb((clamp(a, 0.0, 1.0) * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))
    /** Rim glow. Unit-radius gradient cached per (colour, a1, a2) at 1/50 steps and placed with the canvas matrix. */
    private fun ringGrad(cv: Canvas, x: Float, y: Float, R: Float, c: Int, a1: Double, a2: Double) {
        // 0.1.13: 1/50 steps (was 1/100) and a bigger cap, so THINK / surge glow swings reuse cached gradients instead of
        // overflowing the cache and re-minting shaders every cycle. A 0.02 alpha step on the rim is not visible.
        val q1 = (clamp(a1, 0.0, 1.0) * 50).roundToInt() * 2; val q2 = (clamp(a2, 0.0, 1.0) * 50).roundToInt() * 2
        val key = (c.toLong() shl 16) or (q1.toLong() shl 8) or q2.toLong()
        if (ringCache.size > 192) ringCache.clear()
        val g = ringCache.getOrPut(key) { RadialGradient(0f, 0f, 1f, intArrayOf(withA(c, 0.0), withA(c, 0.0), withA(c, q1 / 100.0), withA(c, q2 / 100.0), withA(c, 0.0)), floatArrayOf(0f, 0.82f / 1.08f, (0.82f + 0.26f * 0.62f) / 1.08f, (0.82f + 0.26f * 0.8f) / 1.08f, 1f), Shader.TileMode.CLAMP) }
        val rr = (R * 1.08f).coerceAtLeast(1f); p.shader = g; p.alpha = 255; cv.save(); cv.translate(x, y); cv.scale(rr, rr); cv.drawCircle(0f, 0f, 1f, p); cv.restore(); p.shader = null
    }
    private fun blit(cv: Canvas, cell: Int, x: Double, y: Double, w: Double, h: Double, a: Double) {
        val b = atlas ?: return
        p.alpha = (clamp(a, 0.0, 1.0) * 255).toInt(); dst.set((x - w / 2).toFloat(), (y - h / 2).toFloat(), (x + w / 2).toFloat(), (y + h / 2).toFloat()); cv.drawBitmap(b, cells[cell], dst, p)
    }

    fun draw(cv: Canvas) {
        if (skin.odst) { drawOdst(cv); return }
        val R = radius().toDouble(); val n = nLat; val gl = glitching(); val K = skin
        // 0.1.13 lean: one canvas transform for the whole sphere (no per-glyph cost); the app ring below stays put.
        val ps = pose; val pk = (r / 92.0).toFloat()
        cv.save(); cv.translate(leanX().toFloat() + (ps.dx * pk).toFloat(), leanY().toFloat() + (ps.dy * pk).toFloat())
        if (abs(ps.sx - 1) > 1e-3 || abs(ps.sy - 1) > 1e-3) { val ad = Math.toDegrees(ps.axis).toFloat(); cv.rotate(ad, cx, cy); cv.scale(ps.sx.toFloat(), ps.sy.toFloat(), cx, cy); cv.rotate(-ad, cx, cy) }
        cv.rotate(roll().toFloat() + ps.roll.toFloat(), cx, cy)
        val gx = egx(); val gy = egy()
        val scatter = sin(PI * min(1.0, sp * 1.15)) * r * 0.55
        p.blendMode = BlendMode.PLUS
        p.shader = bodyGlow; p.alpha = 255; val rb = R.toFloat().coerceAtLeast(1f)
        cv.save(); cv.translate(cx, cy); cv.scale(rb, rb); cv.drawCircle(0f, 0f, 1f, p); cv.restore(); p.shader = null
        val latMax = 1.42; val swp = t - sweepT; val sw = if (swp >= 0 && swp < 1.4) latMax - swp / 1.4 * 2 * latMax else -9.0
        val fl = if (flick >= 0 && t - flickT < 0.35) cols[flick] else null
        // Asleep: the body glyphs dim and breathe too (not just the eyes), and near-invisible cells are skipped.
        val se = sleep.e; val bodyK = (1 - se * (0.6 - 0.3 * (0.5 + 0.5 * sin(t * 2 * PI / SleepState.BREATH_S)))) * ps.bodyK; val cut = 0.03 + 0.04 * se; val flash = ps.flash
        for (c in cols) {
            val phi = c.lon + rot + ps.rotOff; val cp = cos(phi); if (cp < 0.03) continue; val sphi = sin(phi); val wsc = 0.3 + 0.7 * cp
            for (i in 0 until n) {
                if (!c.mask[i]) continue
                val th2 = latTh[i]; val ct = latCos[i]
                var b = 0.24; var head = false
                for (h in c.heads) { val d = h - i; if (d >= 0 && d < c.tail) { val v = 1 - d / c.tail * 0.82; if (v > b) b = v; if (d < 1) head = true } }
                if (sw > -9) { val dd = abs(th2 - sw); if (dd < 0.16) b = max(b, 1 - dd / 0.16) }
                if (fl != null && c === fl) b = max(b, 0.95 * (1 - (t - flickT) / 0.35))
                if (flash > 0.01) b = max(b, flash * (0.45 + 0.55 * (((c.chars[i] * 7 + i * 3) % 11) / 10.0)))
                val z = ct * cp; val fr = 1 - z
                var a = b * (0.62 + 0.75 * fr * fr) * latEdge[i] * bodyK
                var x = cx + R * ct * sphi; var y = cy - R * latSin[i]
                if (scatter > 0.5) { val dx = x - cx; val dy = y - cy; val L = hypot(dx, dy).coerceAtLeast(1.0); val s = scatter * (0.6 + c.jit * 0.8) * (0.5 + 0.5 * L / R); x += dx / L * s; y += dy / L * s; a *= 1 - 0.35 * sp }
                if (a < cut) continue
                if (gl) x += bandShift(y)
                val tier = if (head) 1 else if (K.neon && (c.chars[i] * 7 + i) % 23 == 0) 2 else 0
                val w = gs * 2 * wsc; val h2 = gs * 2
                blit(cv, g(tier, c.chars[i]), x, y, w, h2, min(1.0, a))
                if (gl && a > 0.2) { blit(cv, g(2, c.chars[i]), x - 3, y, w, h2, min(1.0, a) * 0.55); blit(cv, g(1, c.chars[i]), x + 3, y, w, h2, min(1.0, a) * 0.55) }
            }
        }
        // Rim glow uses a 0.1-step surge so a surge reuses a handful of cached gradients instead of minting new ones.
        val sg = surgeLevel(); val sgq = floor(min(1.0, sg * Bold.SURGE_RIM + ps.rim * 0.5) * 10) / 10; val thq = floor(th * 10) / 10
        val open = sleep.lid(lid) * (1 + 0.15 * hov + Bold.PERK_EYES * perkLevel()) * (1 - blink) * (1 - 0.55 * th) * (1 - 0.6 * sp) * (1 + ls * (0.18 + 0.35 * lvl)) * ps.open.coerceAtLeast(0.0) * (1 - ps.blink.coerceIn(0.0, 1.0))
        val pulse = sleep.bright(bright, t) * (0.8 + 0.25 * hov + 0.15 * lvl + Bold.GLOW_WOBBLE * sin(t * 2.2) + th * (Bold.THINK_GLOW + Bold.THINK_FLASH * sin(t * 10)) + ls * (0.15 + 0.5 * lvl) + Bold.SURGE_GLOW * sg + Bold.PERK_GLOW * perkLevel()) * ps.glow
        if (!K.neon) {
            val hap = ps.happy.coerceIn(0.0, 1.0); val spi = ps.spiral.coerceIn(0.0, 1.0); val shape = max(hap, spi)
            for ((ei, e) in EYES.withIndex()) {
                val wk = if (ei == 1) (1 - ps.wink.coerceIn(0.0, 1.0)) else 1.0
                val ecx = cx + (e[0] + gx) * R; val ecy = cy + (e[1] + gy) * R; val rx = e[2] * R; val ry0 = e[3] * R; val ry = max(1.2, ry0 * open * wk); val rotE = e[4]
                cv.save(); cv.translate(ecx.toFloat(), ecy.toFloat()); cv.rotate(Math.toDegrees(rotE).toFloat())
                cv.save(); cv.scale(1f, (max(1.2, ry * (1 - shape) + ry0 * 0.5 * shape) / rx).toFloat())
                val er = (rx * 1.25).toFloat().coerceAtLeast(1f); p.shader = eyeGlow; p.alpha = (clamp(0.55 * pulse, 0.0, 1.0) * 255).toInt()
                cv.scale(er, er); cv.drawCircle(0f, 0f, 1f, p); p.shader = null; p.alpha = 255; cv.restore()
                val st = gs * 0.78; val sw2 = gs * 1.5
                if (shape < 0.02) { var yy = -ry
                    while (yy <= ry) { var xx = -rx
                        while (xx <= rx) { val ee = xx * xx / (rx * rx) + yy * yy / (ry * ry)
                            if (ee <= 1) { val hsh = sin(xx * 12.9898 + yy * 78.233 + floor(t * 6 + xx) * 3.1) * 43758.5; val ch = if (hsh - floor(hsh) < 0.5) 0 else 1
                                cv.save(); cv.translate(xx.toFloat(), yy.toFloat()); cv.rotate(-Math.toDegrees(rotE).toFloat()); blit(cv, g(1, ch), 0.0, 0.0, sw2, sw2, (0.55 + 0.45 * (1 - ee)) * pulse); cv.restore() }
                            xx += st * 0.8 }
                        yy += st }
                } else {
                    // 0.1.15: happy ^^ arcs and dizzy spirals are drawn with the same glyph cells, cross-faded with the open eye.
                    val box = max(ry, ry0); var yy = -box; val band = gs * 1.25
                    while (yy <= box) { var xx = -rx
                        while (xx <= rx) { var a = 0.0
                            val ee = xx * xx / (rx * rx) + yy * yy / (ry * ry); if (ee <= 1) a += (0.55 + 0.45 * (1 - ee)) * (1 - shape)
                            if (hap > 0.01) { val q = xx / rx; val arcY = -0.4 * ry0 + 0.8 * ry0 * q * q; val d = abs(yy - arcY); if (d < band) { val f = d / band; a += hap * (1.15 - f * f) * (1 - 0.25 * q * q) } }
                            if (spi > 0.01) { val yn = yy * rx / ry0; val rr = hypot(xx, yn) / rx; if (rr < 1) { val ang = kotlin.math.atan2(yn, xx); var f = (rr * 2.2 - (ang + t * 7) / (2 * PI)) % 1.0; if (f < 0) f += 1.0
                                val dd = min(f, 1 - f); if (dd < 0.26) a += spi * (1.15 - dd / 0.26) } }
                            if (a > 0.03) { val hsh = sin(xx * 12.9898 + yy * 78.233 + floor(t * 6 + xx) * 3.1) * 43758.5; val ch = if (hsh - floor(hsh) < 0.5) 0 else 1
                                cv.save(); cv.translate(xx.toFloat(), yy.toFloat()); cv.rotate(-Math.toDegrees(rotE).toFloat()); blit(cv, g(1, ch), 0.0, 0.0, sw2, sw2, min(1.0, a) * pulse); cv.restore() }
                            xx += st * 0.8 }
                        yy += st }
                }
                cv.restore()
            }
        } else {
            val thinkMag = th > 0.2 && sin(t * 7) > 0
            val hap = ps.happy.coerceIn(0.0, 1.0); val spi = ps.spiral.coerceIn(0.0, 1.0); val shape = max(hap, spi)
            for ((ei, e) in VIS.withIndex()) {
                val wk = if (ei == 1) (1 - ps.wink.coerceIn(0.0, 1.0)) else 1.0
                val slope = e[2]; val ecx = cx + (e[0] + gx) * R + (if (gl) bandShift(cy + e[1] * R) else 0.0); val ecy = cy + (e[1] + gy) * R
                val rx = 0.19 * R; val ry = max(0.8, 0.075 * R * open * wk * (1 + 0.15 * th * sin(t * 12))); val c = if (thinkMag) K.accent else K.eye
                val vp = pulse * (1 - shape)
                cv.save(); cv.translate(ecx.toFloat(), ecy.toFloat())
                if (vp > 0.01) { path.reset()
                    path.moveTo((-rx).toFloat(), (-ry + slope * -rx).toFloat()); path.lineTo((rx * 0.85).toFloat(), (-ry + slope * rx).toFloat()); path.lineTo(rx.toFloat(), (ry + slope * rx).toFloat()); path.lineTo((-rx * 0.85).toFloat(), (ry + slope * -rx).toFloat()); path.close()
                    for (si in 0 until 3) { sp2.strokeWidth = (gs * strokes[si * 2]).toFloat(); sp2.color = withA(c, strokes[si * 2 + 1] * vp); cv.drawPath(path, sp2) }
                    p.style = Paint.Style.FILL; p.color = withA(c, 0.375 * vp); cv.drawPath(path, p); p.color = Color.BLACK
                    sp2.strokeWidth = 1f; sp2.color = withA(c, 0.81 * (1 - shape)); cv.drawPath(path, sp2)
                    val st = gs * 0.72; val sw2 = gs * 1.3; var xx = -rx * 0.9
                    while (xx <= rx * 0.9) { var yy = -ry
                        while (yy <= ry) { val hsh = sin(xx * 12.9898 + yy * 78.233 + floor(t * 8 + xx) * 3.1) * 43758.5; val ch = ((hsh - floor(hsh)) * nG).toInt()
                            blit(cv, g(if (thinkMag) 2 else 1, ch), xx, yy + slope * xx, sw2, sw2, 0.9 * vp); yy += st }
                        xx += st * 0.75 }
                }
                // 0.1.15: happy = a glowing ^ chevron; dizzy = a spinning spiral, both stroked like the visor.
                if (shape > 0.01) { path.reset()
                    if (hap >= spi) { val h = 0.075 * R * 1.6; path.moveTo((-rx * 0.85).toFloat(), (h * 0.55).toFloat()); path.lineTo(0f, (-h * 0.75).toFloat()); path.lineTo((rx * 0.85).toFloat(), (h * 0.55).toFloat()) }
                    else { val n = 40; for (j in 0..n) { val u = j.toDouble() / n; val ang = u * 4 * PI + t * 7; val rr = u * rx * 0.75; val x = (cos(ang) * rr).toFloat(); val y = (sin(ang) * rr * 0.75).toFloat(); if (j == 0) path.moveTo(x, y) else path.lineTo(x, y) } }
                    for (si in 0 until 3) { sp2.strokeWidth = (gs * strokes[si * 2] * 0.8).toFloat(); sp2.color = withA(c, min(1.0, strokes[si * 2 + 1] * 2.2) * pulse * shape); cv.drawPath(path, sp2) }
                    sp2.strokeWidth = 2f; sp2.color = withA(c, 0.9 * shape); cv.drawPath(path, sp2)
                }
                cv.restore()
            }
        }
        if (K.neon) { val off = (2.2 + (if (gl) 4 + 3 * th else 0.0) + Bold.THINK_SPLIT * th).toFloat()
            ringGrad(cv, cx - off, cy, R.toFloat(), K.accent, 0.08 + 0.06 * thq, 0.34 + 0.12 * sgq); ringGrad(cv, cx + off, cy, R.toFloat(), K.eye, 0.08 + 0.06 * thq, 0.34 + 0.12 * sgq); ringGrad(cv, cx, cy, R.toFloat(), K.body, 0.1 + 0.14 * thq + 0.08 * sgq, 0.3 + 0.28 * thq + 0.25 * sgq)
        } else ringGrad(cv, cx, cy, R.toFloat(), K.body, 0.16 + 0.16 * thq + 0.16 * ls + 0.1 * sgq, 0.42 + 0.32 * thq + 0.36 * ls * (0.4 + lvl) + 0.3 * sgq)
        val ss = gs * 1.6
        for (s in sparks) {
            val f = min(1.0, (0.5 + 0.5 * sin(t * s.f * (1 + ls) + s.ph)).pow(3) * (1 - 0.5 * sp) * (1 + ls * 1.5 * lvl)); if (f < 0.05) continue
            val dx = cos(s.a); val dy = sin(s.a)
            for (j in 0 until s.len) { val rr = R + gs * (0.6 + j * 1.15) + scatter * 0.6; val tier = if (j == s.len - 1) (if (K.neon && s.ch % 4 == 0) 2 else 1) else 0
                blit(cv, g(tier, s.ch + j), cx + dx * rr, cy + dy * rr, ss, ss, f * (1 - j / (s.len + 0.5))) }
        }
        val rg = ring
        if (sp > 0 && rg != null) drawFlyers(cv, rg, R)
        if (gl) for (bi in 0 until nBands) { val b = bands[bi]; p.color = withA(if (b[2] > 0) K.eye else K.accent, 0.5 * 0.35); cv.drawRect((cx - R * 1.3 + b[2]).toFloat(), (cy + b[0] - 0.75).toFloat(), (cx + R * 1.3 + b[2]).toFloat(), (cy + b[0] + 0.75).toFloat(), p) }
        p.blendMode = null; p.alpha = 255
        cv.restore() // end lean
        if (rg != null) drawTiles(cv, rg)
    }

    private fun drawFlyers(cv: Canvas, rg: Ring, R: Double) { val K = skin
            if (rg.orbits.isNotEmpty()) { op.color = withA(K.eye, 0.9 * smooth(0.3, 0.9, sp) * 0.5)
                for (o in rg.orbits) { cv.save(); cv.translate(cx, cy); cv.scale(1f, rg.fy); cv.rotate(o[2]); cv.drawOval(-o[0], -o[1], o[0], o[1], op); cv.restore() } }
            val fs = gs * 1.7
            for (f in flyers) {
                val pp = clamp((sp - f.delay) / 0.62, 0.0, 1.0); if (pp <= 0) continue; val e = ease(pp)
                val ox = cx + f.ox * R; val oy = cy + f.oy * R; val mx = cx + cos(f.ma) * r * f.md; val my = cy + sin(f.ma) * r * f.md
                val tx = cx + f.tx; val ty = cy + f.ty; val u = 1 - e
                val x = u * u * ox + 2 * u * e * mx + e * e * tx; val y = u * u * oy + 2 * u * e * my + e * e * ty
                val hotF = f.tile == hot; val tier = if (hotF || pp < 1) 1 else if (K.neon && f.ch % 5 == 0) 2 else 0
                blit(cv, g(tier, f.ch + floor(t * 4 + f.ph).toInt()), x, y, fs, fs, clamp((0.5 + 0.5 * sin(t * 7 + f.ph)) * 0.6 + 0.4, 0.0, 1.0) * (if (hotF) 1.0 else 0.85))
            }
    }

    /** ODST: HUD (stays put), then the face with the shared lean / squash / roll body language, then the ring. */
    private fun drawOdst(cv: Canvas) {
        val face = odstFace ?: return; val R = radius(); val ps = pose; val pk = (r / 92.0).toFloat()
        val hudA = 0.9 * ps.hudA * (1 - smooth(0.0, 0.5, sp)) * (1 - 0.8 * sleep.e)
        face.glowOn = glowOn
        face.hud(cv, cx, cy, r, cx * 2, t, hudA, visr, ps.tag1 ?: (if (visr) "VISR\u00B7ON" else null), ps.tag2 ?: (if (visr) "LOW LIGHT" else null), 1 + 3 * th, ls * (0.3 + 0.7 * lvl))
        cv.save(); cv.translate(leanX().toFloat() + (ps.dx * pk).toFloat(), leanY().toFloat() + (ps.dy * pk).toFloat())
        if (abs(ps.sx - 1) > 1e-3 || abs(ps.sy - 1) > 1e-3) { val ad = Math.toDegrees(ps.axis).toFloat(); cv.rotate(ad, cx, cy); cv.scale(ps.sx.toFloat(), ps.sy.toFloat(), cx, cy); cv.rotate(-ad, cx, cy) }
        cv.rotate(roll().toFloat() + ps.roll.toFloat(), cx, cy)
        val open = sleep.lid(lid) * (1 + 0.15 * hov + Bold.PERK_EYES * perkLevel()) * (1 - 0.55 * th) * (1 - 0.6 * sp) * (1 + ls * (0.18 + 0.35 * lvl)) * ps.open.coerceAtLeast(0.0)
        fs.gx = clamp(egx() / 0.2, -1.4, 1.4); fs.gy = clamp(egy() / 0.2, -1.4, 1.4); fs.blink = max(blink, ps.blink); fs.dart = dartK
        fs.lid = max(ps.lid, clamp((1 - open) * 0.9, 0.0, 0.85)); fs.wide = max(ps.wide, clamp((open - 1) * 2, 0.0, 1.0))
        fs.glow = ps.glow * clamp(sleep.bright(bright, t) * (1 + 0.25 * hov + 0.3 * ls * lvl + Bold.SURGE_GLOW * surgeLevel()), 0.3, 2.2) + ps.rim * 0.4
        fs.vergil = ps.vergil; fs.visr = visr; fs.showMarks = !still || sleep.e > 0.5
        flip.step(t, Expr.target(ps, sleep.e)); face.draw(cv, cx, cy, R, t, flip.face, flip.sx, fs, ps.faceA)
        cv.restore()
        val rg = ring
        if (sp > 0 && rg != null) { p.blendMode = BlendMode.PLUS; drawFlyers(cv, rg, R.toDouble()); p.blendMode = null; p.alpha = 255; drawTiles(cv, rg) }
    }

    private fun tilePath(x: Float, y: Float, h: Float) {
        path.reset()
        if (skin.angular) { val c = min(10f, h / 2); path.moveTo(x - h + c, y - h); path.lineTo(x + h, y - h); path.lineTo(x + h, y + h - c); path.lineTo(x + h - c, y + h); path.lineTo(x - h, y + h); path.lineTo(x - h, y - h + c); path.close() }
        else path.addRoundRect(x - h, y - h, x + h, y + h, 14f, 14f, Path.Direction.CW)
    }
    private fun drawTiles(cv: Canvas, rg: Ring) {
        if (sp < 0.55) return; val K = skin
        val backOut = { k: Double -> val c1 = 1.9; val c3 = c1 + 1; 1 + c3 * (k - 1).pow(3) + c1 * (k - 1).pow(2) }
        fp.reset(); fp.isAntiAlias = true
        rg.slots.forEachIndexed { i, sl ->
            if (i >= rg.tiles.size) return@forEachIndexed
            val kk = if (still) smooth(0.72, 1.0, sp) else clamp((sp - 0.6 - i * 0.022) / 0.26, 0.0, 1.0); if (kk <= 0) return@forEachIndexed
            val ta = smooth(0.0, 0.5, kk); val x = cx + sl.x; val y = cy + sl.y; val isHot = i == hot; val h = sl.s / 2
            if (i < tileK.size) tileK[i] += ((if (isHot) 1f else 0f) - tileK[i]) * 0.25f
            val sc = ((if (still) 1.0 else backOut(kk)) * (1 + 0.14 * tileK[min(i, tileK.size - 1)] + if (isHot) 0.03 * sin(t * 10) else 0.0)).toFloat()
            cv.save(); cv.scale(sc, sc, x, y); tilePath(x, y, h)
            fp.style = Paint.Style.FILL; fp.alpha = 255
            if (isHot) { fp.color = K.body; fp.setShadowLayer(26f, 0f, 0f, K.body) } else { fp.clearShadowLayer(); fp.color = if (K.odst) 0xD90C1118.toInt() else if (K.neon) 0xD9100E17.toInt() else 0xD10B0F0C.toInt() }
            fp.alpha = (ta * Color.alpha(fp.color)).toInt(); cv.drawPath(path, fp); fp.clearShadowLayer()
            if (!isHot) { fp.style = Paint.Style.STROKE; fp.strokeWidth = 1f; fp.color = withA(if (K.neon) K.eye else K.body, 0.35 * ta); cv.drawPath(path, fp) }
            val tile = rg.tiles[i]; val ink = if (isHot) (if (K.odst) 0xFF0A0D10.toInt() else if (K.neon) 0xFF0B0A00.toInt() else 0xFF001A08.toInt()) else if (K.neon) K.eye else K.body
            val ac = if (isHot) ink else if (K.neon || K.odst) K.accent else K.head
            val ic = tile.icon
            if (ic != null) { val k = sl.s * 0.56f / 24f; cv.save(); cv.translate(x - 12 * k, y - 12 * k); cv.scale(k, k)
                IconArt.strokeIcon(cv, ic, ink, ac, glow = !isHot, neon = K.angular, alpha = ta.toFloat()); cv.restore() }
            else { tp.typeface = mono; tp.textSize = sl.s * (if (tile.letter.length > 1) 0.32f else 0.42f); tp.color = withA(ink, ta); cv.drawText(tile.letter, x, y - (tp.descent() + tp.ascent()) / 2, tp) }
            if (tile.pinned) { fp.style = Paint.Style.FILL; fp.color = withA(if (K.neon) K.accent else K.head, ta); cv.drawCircle(x + h - 3, y - h + 3, 3.2f, fp) }
            val raw = if (tile.name.length > 11) tile.name.take(10) + "\u2026" else tile.name
            tp.typeface = if (K.angular) disp else mono; tp.textSize = if (K.angular) 10f else 10.5f; tp.isFakeBoldText = isHot
            tp.color = withA(if (isHot) K.body else if (K.odst) 0xFFE9EEF5.toInt() else if (K.neon) 0xFFEEF2F3.toInt() else 0xFFB8F5C8.toInt(), ta); tp.setShadowLayer(4f, 0f, 0f, Color.BLACK)
            cv.drawText(if (K.angular) raw.uppercase() else raw, x, y + h + 6 - tp.ascent(), tp); tp.clearShadowLayer(); tp.isFakeBoldText = false
            cv.restore()
        }
    }
    fun hitTile(x: Float, y: Float): Int { val rg = ring ?: return -1; if (sp < 0.6) return -1
        for (i in rg.slots.indices) { val sl = rg.slots[i]; if (hypot(x - (cx + sl.x), y - (cy + sl.y)) < sl.s * 0.75f) return i }; return -1 }
    fun recycle() { atlas?.recycle(); atlas = null; ringCache.clear() }
}
