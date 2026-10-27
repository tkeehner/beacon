package com.tyler.primelauncher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Full-screen canvas: wallpaper, Prime orb, ring of apps, charging + notification effects, and the home gestures. Draws in dp. */
@SuppressLint("ViewConstructor")
class StageView(ctx: Context, private val prefs: Prefs, private val host: Host) : View(ctx), Choreographer.FrameCallback {
    interface Host {
        fun ringApps(): List<AppEntry>
        fun onOrbTap(); fun onRingPick(a: AppEntry); fun onFlick(dir: String)
        fun onSwipeUp(); fun onSwipeDown(); fun onLongPressBg(); fun onFrame(t: Double); fun ringHint(s: String?)
    }
    val d = resources.displayMetrics.density
    var W = 411f; private set; var H = 914f; private set
    var topInset = 24f; var bottomInset = 48f; var bottomReserve = 86f
    var cy = 454f; private set
    lateinit var orb: Orb; private set
    /** The orb exists only after the first layout; callers that can run earlier (pause, broadcasts) check this. */
    val ready get() = this::orb.isInitialized
    private var rain: Rain? = null; private var ring: List<AppEntry> = emptyList()
    private var running = false; private var lastNs = 0L; private var skip = false
    var level = 0.8f; var charging = false; var plugT = -99.0; var noteT = -99.0; var dimOverlay = 0f
    var suspended = false // a full-screen panel covers the stage
    /** Live Grok Bot call: [inCall] target, [callK] eased 0..1, [callLvl] 0..1 voice level (real or synthetic). */
    var inCall = false; var callK = 0.0; var callLvl = 0.0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wave = android.graphics.Path()

    fun cxOrb() = W / 2
    fun orbR() = if (prefs.neon) 96f else 102f

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { W = w / d; H = h / d; rebuild() }
    fun layoutInsets(top: Float, bottom: Float) { topInset = top; bottomInset = bottom; if (width > 0) rebuild() }

    /** Rebuild orb + wallpaper (theme, saver, wallpaper or size change). */
    fun rebuild() {
        cy = (topInset + (H - bottomInset - bottomReserve)) / 2 + 24
        val old = if (this::orb.isInitialized) orb else null
        val q = if (prefs.saver) 0.55 else 1.0
        ring = host.ringApps()
        orb = Orb(cxOrb(), cy, orbR(), if (prefs.neon) Skin.NEON else Skin.MATRIX, d, q, buildRing(), prefs.glitch)
        orb.amp = if (prefs.reduce) 0.5 else 1.0
        if (old != null) { orb.t = old.t; orb.nextBlink = old.t + 1.5; orb.nextGlitch = old.t + 1; orb.mode = old.mode; orb.syncMode(); orb.lively = old.lively; orb.yawny = old.yawny; orb.sleep.copyFrom(old.sleep); old.recycle() }
        else if (sleepPending) orb.sleep.sleepNow()
        sleepPending = false
        rain?.recycle(); rain = makeRain()
    }
    private var sleepPending = false
    /** Screen went off / locked: Prime is asleep (instantly, nobody is watching). Safe before the first layout. */
    fun sleepOrb() { if (this::orb.isInitialized) orb.sleep.sleepNow() else sleepPending = true }
    /**
     * Unlocked and back on Home (0.1.7): a SLOW wake ([SleepState.SLOW_WAKE_S]) — lids open and the glow brightens from the
     * sleeping pose — while [reveal] fades the rain and every home widget up from black with it. Reduce Motion: instant.
     * Returns false if Prime was already awake.
     */
    fun wakeOrb(dur: Double = SleepState.SLOW_WAKE_S): Boolean {
        sleepPending = false; if (!this::orb.isInitialized || !orb.sleep.asleep) return false
        if (prefs.reduce) { wakePending = false; orb.sleep.copyFrom(SleepState()); invalidate(); return true }
        // 0.1.6: right after onResume the first frames are the slow ones (surface + texture re-upload). Starting the ramp
        // then made the orb sit "stuck" asleep and jump. Hold the wake until two frames are on screen, then ramp smoothly.
        if (running && framesSinceStart < 2) { wakePending = true; pendingDur = dur; return true }
        return orb.wake(dur)
    }
    private var wakePending = false; private var pendingDur = SleepState.SLOW_WAKE_S; private var framesSinceStart = 0
    /** True while the orb is waking or awake (a deferred wake counts). */
    val awakeOrWaking get() = wakePending || (this::orb.isInitialized && !orb.sleep.asleep)
    /**
     * 0..1 opacity of everything except the orb (rain here; HomeActivity applies it to the widgets). It IS the orb's wake:
     * 0 while asleep or a wake is queued, then 1 - eased sleep, so the screen and the orb come up as one and nothing can
     * fade on its own (an instant wake / quick return is simply 1).
     */
    val reveal: Float get() = when {
        !this::orb.isInitialized -> if (sleepPending) 0f else 1f
        wakePending -> 0f
        else -> (1.0 - orb.sleep.e).toFloat().coerceIn(0f, 1f)
    }
    fun refreshRing() { ring = host.ringApps(); if (this::orb.isInitialized) orb.setRingSpec(buildRing()) }

    private fun makeRain(): Rain {
        val auto = prefs.wpStyle == "auto"; val style = if (auto) (if (prefs.neon) "streaks" else "glyphs") else prefs.wpStyle
        val rgb = if (prefs.wpHue >= 0) Rain.hsl(prefs.wpHue) else if (style == "streaks" && prefs.wpHue < 0) 0 else if (prefs.neon) 0xFF3EE8F0.toInt() else 0xFF3DFF7A.toInt()
        return Rain(W, H, cy, d, style, prefs.wpGlyphs, prefs.wpDensity, prefs.wpSpeed, rgb, prefs.neon)
    }

    /** Bottom of the clock/date/weather header in dp (set by HomeActivity once laid out); <0 = estimate from the insets. */
    var headerBottom = -1f
        set(v) { if (field != v) { field = v; if (this::orb.isInitialized) orb.setRingSpec(buildRing()) } }
    /** Top of the "tap an app" footer hint in dp (HomeActivity places it at bottomInset + 100 from the bottom). */
    fun footerTop() = H - bottomInset - 100f - 18f
    /** 0.1.11: the ring must not ride up into the header or down into the footer hint (see [fitRing]). */
    private fun buildRing(): Ring {
        val hb = if (headerBottom > 0) headerBottom else topInset + 18f + if (prefs.neon) 150f else 142f
        return fitRing(rawRing(), upRoom = cy - (hb + 10f), downRoom = footerTop() - 8f - cy)
    }
    private fun rawRing(): Ring {
        val tiles = ring.map { RingTile(it.label, IconMath.letter(it.label), IconArt.paths(it.pkg, prefs.icons), it.pkg in prefs.pins) }
        // 0.1.12: every ring tile is a pin, all on one orbit (room for Logic.MAX_PINS = 12); 0 pins = the orb alone.
        val n = tiles.size; if (n == 0) return Ring(tiles, emptyList(), emptyList())
        return if (prefs.neon) Ring(tiles, orbitSlots(floatArrayOf(150f, 200f, -12f), n, -90f, 50f), listOf(floatArrayOf(150f, 200f, -12f))) else Ring(tiles, ringSlots(n, 140f, 54f))
    }

    // ---------- frame loop ----------
    /** Tests only: frames are driven by hand instead of by Choreographer. */
    var manualFrames = false
    fun start() { if (!running) { running = true; lastNs = 0; framesSinceStart = 0; if (!manualFrames) Choreographer.getInstance().postFrameCallback(this) } }
    fun stop() { running = false; Choreographer.getInstance().removeFrameCallback(this) }
    override fun doFrame(ns: Long) {
        if (!running) return
        if (!manualFrames) Choreographer.getInstance().postFrameCallback(this)
        // Fully asleep (eyes shut, slow 5.6 s breath): 30 fps is plenty and halves the cost while it sits there.
        val deepSleep = this::orb.isInitialized && orb.sleep.asleep && orb.sleep.k >= 0.999 && !wakePending && !inCall && !summoned
        val lowFps = prefs.saver || prefs.reduce || suspended || deepSleep
        if (lowFps) { skip = !skip; if (skip) return }
        val dt = if (lastNs == 0L) 1 / 60.0 else ((ns - lastNs) / 1e9).coerceIn(0.0, 0.1); lastNs = ns
        if (!this::orb.isInitialized) return
        if (wakePending && framesSinceStart >= 2) { wakePending = false; orb.wake(pendingDur) }
        orb.step(if (prefs.reduce) dt * 0.5 else dt); framesSinceStart++
        callK = (callK + if (inCall) dt / 0.6 else -dt / 0.5).coerceIn(0.0, 1.0)
        if (!prefs.reduce) rain?.step(dt.toFloat())
        host.onFrame(orb.t)
        if (!suspended) invalidate()
    }

    override fun onDraw(cv: Canvas) {
        if (!this::orb.isInitialized) return
        cv.save(); cv.scale(d, d)
        rain?.draw(cv, orb.t, reveal)
        drawCharge(cv); drawCallHalo(cv, behind = true); orb.draw(cv); drawNotePulse(cv); drawVoiceRings(cv); drawCallHalo(cv, behind = false)
        if (dimOverlay > 0.01f) { paint.shader = null; paint.color = Color.argb((dimOverlay * 255).toInt().coerceIn(0, 200), 0, 0, 0); cv.drawRect(0f, 0f, W, H, paint) }
        cv.restore()
    }
    private fun rgbA(c: Int, a: Double) = Color.argb((a.coerceIn(0.0, 1.0) * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))

    private var chargeKey: FloatArray? = null; private var chargeLin: Shader? = null; private var chargeRad: Shader? = null
    private fun drawCharge(cv: Canvas) {
        val t = orb.t; val plug = t - plugT < 4.0; if (!charging && !plug) return
        val neon = prefs.neon; val c1 = if (neon) 0xFFF7E733.toInt() else 0xFF3DFF7A.toInt(); val c2 = if (neon) 0xFF3EE8F0.toInt() else 0xFFE2FFE9.toInt()
        val cx = cxOrb(); val R = orb.radius(); val y0 = H - bottomInset - 2; val y1 = cy + R * 0.92f
        paint.blendMode = BlendMode.PLUS; paint.style = Paint.Style.FILL
        val key = floatArrayOf(y0, cy + orbR() * 0.92f, cx, c1.toFloat()) // fixed endpoint: the breathing radius must not rebuild the shader each frame
        if (chargeKey == null || !chargeKey!!.contentEquals(key)) { chargeKey = key
            chargeLin = LinearGradient(0f, y0, 0f, key[1], rgbA(c1, 0.2), rgbA(c1, 0.0), Shader.TileMode.CLAMP); chargeRad = RadialGradient(cx, y0, 70f, rgbA(c1, 0.32), rgbA(c1, 0.0), Shader.TileMode.CLAMP) }
        paint.shader = chargeLin; cv.drawRect(cx - 16, y1, cx + 16, y0, paint)
        paint.shader = chargeRad; cv.drawRect(cx - 70, y0 - 70, cx + 70, y0 + 2, paint); paint.shader = null
        if (!prefs.reduce) { val n = 34; for (i in 0 until n) { val u = ((t * 0.42 + i.toDouble() / n + ((i * 0.618) % 1) * 0.13) % 1); val e = u * u * (3 - 2 * u)
            val y = y0 + (y1 - y0) * e; val x = cx + sin(u * 10 + t * 2.4 + i * 1.7) * 14 * (1 - e) + sin(i * 2.3) * 4
            paint.color = rgbA(if (i % 3 != 0) c1 else c2, sin(PI * u) * 0.9); cv.drawCircle(x.toFloat(), y.toFloat(), (1.2 + 1.4 * (1 - e)).toFloat(), paint) } }
        val pul = 0.5 + 0.5 * sin(t * 3.2); paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = rgbA(c1, 0.25 + 0.25 * pul)
        cv.drawArc(cx - R * 1.05f, cy - R * 1.05f, cx + R * 1.05f, cy + R * 1.05f, 50.4f, 79.2f, false, paint)
        paint.style = Paint.Style.FILL; paint.blendMode = null
    }
    private fun drawNotePulse(cv: Canvas) {
        val k = orb.t - noteT; if (k < 0 || k > 1.3 || prefs.reduce) return
        paint.blendMode = BlendMode.PLUS; paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.6f
        for (i in 0 until 2) { val kk = k - i * 0.2; if (kk < 0) continue; paint.color = rgbA(if (prefs.neon) 0xFFFF3D81.toInt() else 0xFFE2FFE9.toInt(), max(0.0, 1 - kk / 1.1) * 0.6)
            cv.drawCircle(cxOrb(), cy, orb.radius() * (1.03f + kk.toFloat() * 0.6f), paint) }
        paint.style = Paint.Style.FILL; paint.blendMode = null
    }
    /**
     * "On a call with Prime" halo. Matrix: a ring of green sound-wave bars around the orb with a soft glow.
     * Neon City: a cyan + magenta waveform ring (chromatic split) with rotating dashed orbit arcs.
     * Reduce motion: one softly breathing ring, no moving bars.
     */
    private fun drawCallHalo(cv: Canvas, behind: Boolean) {
        val k = callK * callK * (3 - 2 * callK); if (k < 0.01) return
        val t = orb.t; val R = orb.radius(); val cx = cxOrb(); val neon = prefs.neon; val lv = callLvl.coerceIn(0.0, 1.0)
        val c1 = if (neon) 0xFF3EE8F0.toInt() else 0xFF3DFF7A.toInt(); val c2 = if (neon) 0xFFFF3D81.toInt() else 0xFFE2FFE9.toInt()
        paint.blendMode = BlendMode.PLUS
        if (behind) { // glow behind the orb
            val g = 0.16 + 0.14 * lv + (if (prefs.reduce) 0.05 * sin(t * 1.4) else 0.0)
            paint.style = Paint.Style.FILL; paint.shader = RadialGradient(cx, cy, R * 1.9f, intArrayOf(rgbA(c1, 0.0), rgbA(c1, g * k), rgbA(c1, 0.0)), floatArrayOf(0.45f, 0.62f, 1f), Shader.TileMode.CLAMP)
            cv.drawCircle(cx, cy, R * 1.9f, paint); paint.shader = null; paint.blendMode = null; return
        }
        paint.style = Paint.Style.STROKE
        if (prefs.reduce) {
            paint.strokeWidth = 2f; paint.color = rgbA(c1, k * (0.35 + 0.15 * sin(t * 1.4))); cv.drawCircle(cx, cy, R * 1.16f, paint)
            paint.style = Paint.Style.FILL; paint.blendMode = null; return
        }
        val base = R * 1.13f
        if (!neon) {
            val n = 72; paint.strokeWidth = 2.2f; paint.strokeCap = Paint.Cap.ROUND
            for (i in 0 until n) { val a = i * 2 * PI / n
                val w = 0.5 + 0.5 * sin(a * 3 + t * 2.3) * sin(a * 5 - t * 3.1) // travelling wave
                val h = (3 + (6 + 22 * lv) * (0.35 + 0.65 * w * w)) * k
                val ca = kotlin.math.cos(a).toFloat(); val sa = sin(a).toFloat()
                paint.color = rgbA(if (i % 9 == 0) c2 else c1, (0.35 + 0.55 * w) * k)
                cv.drawLine(cx + ca * base, cy + sa * base, cx + ca * (base + h.toFloat()), cy + sa * (base + h.toFloat()), paint) }
            paint.strokeCap = Paint.Cap.BUTT
        } else {
            for ((j, col) in listOf(c1, c2).withIndex()) {
                val p = wave.apply { rewind() }; val n = 120; val off = if (j == 0) -1.2f else 1.2f
                for (i in 0..n) { val a = i * 2 * PI / n
                    val r = base + 4 + (3 + 14 * lv) * k * (sin(a * 6 + t * (2.6 + j * 0.7)) * 0.6 + sin(a * 11 - t * 3.3) * 0.4)
                    val x = (cx + off + kotlin.math.cos(a) * r).toFloat(); val y = (cy + sin(a) * r).toFloat(); if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }
                paint.strokeWidth = 1.6f; paint.color = rgbA(col, 0.75 * k); cv.drawPath(p, paint)
            }
            paint.strokeWidth = 2.4f; val rr = R * 1.42f; val rot = (t * 40 % 360).toFloat()
            for (i in 0 until 6) { paint.color = rgbA(if (i % 2 == 0) c1 else 0xFFF7E733.toInt(), (0.25 + 0.35 * lv) * k)
                cv.drawArc(cx - rr, cy - rr, cx + rr, cy + rr, rot + i * 60f, 22f, false, paint) }
        }
        // expanding "speech" ripples, faster and brighter when Prime is louder
        paint.strokeWidth = 1.4f
        for (i in 0 until 3) { val ph = (t * (0.45 + 0.5 * lv) + i / 3.0) % 1; paint.color = rgbA(if (i % 2 == 0) c1 else c2, k * (1 - ph) * (0.22 + 0.4 * lv))
            cv.drawCircle(cx, cy, R * (1.25f + ph.toFloat() * 0.42f), paint) }
        paint.style = Paint.Style.FILL; paint.blendMode = null
    }
    private fun drawVoiceRings(cv: Canvas) {
        if (callK > 0.5) return // the call halo replaces the plain listening rings
        if (orb.ls < 0.02) return; val R = orb.radius(); paint.blendMode = BlendMode.PLUS; paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.2f
        for (i in 0 until 3) { val ph = (orb.t * 0.7 + i / 3.0) % 1; val col = if (prefs.neon) (if (i % 2 == 1) 0xFF3EE8F0.toInt() else 0xFFFF3D81.toInt()) else 0xFF3DFF7A.toInt()
            paint.color = rgbA(col, orb.ls * (1 - ph) * (0.18 + 0.5 * orb.lvl)); cv.drawCircle(cxOrb(), cy, R * (1.08f + ph.toFloat() * 0.55f), paint) }
        paint.style = Paint.Style.FILL; paint.blendMode = null
    }

    // ---------- gestures ----------
    private var downX = 0f; private var downY = 0f; private var downT = 0L; private var onOrb = false; private var summoned = false; private var sticky = false
    private var longFired = false; private var moved = false
    /** Set when ACTION_DOWN fully handled the gesture (a tap while the ring is sticky); MOVE/UP then do nothing. */
    private var swallow = false
    private val holdRun = Runnable { if (onOrb && !moved) summon() }
    private val longRun = Runnable { if (!onOrb && !moved) { longFired = true; host.onLongPressBg() } }
    private fun summon() { summoned = true; refreshRing(); orb.mode = Mode.SUMMON; performHapticFeedback(HAPTIC_FEEDBACK_ENABLED_LONG_PRESS()); host.ringHint(if (ring.isEmpty()) EMPTY_HINT else "slide to an app · release") }
    private val EMPTY_HINT = "no pinned apps · pin them in the app drawer"
    @Suppress("FunctionName") private fun HAPTIC_FEEDBACK_ENABLED_LONG_PRESS() = android.view.HapticFeedbackConstants.LONG_PRESS
    fun closeRing() { if (!this::orb.isInitialized) { summoned = false; sticky = false; return }; summoned = false; sticky = false; orb.hot = -1; if (orb.mode == Mode.SUMMON) orb.mode = Mode.IDLE; host.ringHint(null) }
    val ringOpen get() = summoned

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x / d; val y = e.y / d
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Reset every per-gesture flag first, so nothing from the previous gesture (e.g. the hold that summoned the ring) leaks in.
                downX = x; downY = y; downT = e.eventTime; moved = false; longFired = false; swallow = false; onOrb = false
                removeCallbacks(holdRun); removeCallbacks(longRun)
                if (sticky) {
                    // 0.1.3 bug: onOrb was still true from the summoning hold, so this tap's ACTION_UP fell through to onOrbTap()
                    // and opened voice mode (even after launching the picked app). The sticky tap now owns the whole gesture.
                    swallow = true
                    val i = orb.hitTile(x, y); if (i >= 0 && i < ring.size) { val a = ring[i]; closeRing(); host.onRingPick(a) } else closeRing(); return true
                }
                onOrb = hypot(x - cxOrb(), y - cy) < orbR() * 1.1f
                orb.touchDown(x, y); orb.hovT = if (onOrb) 1.0 else 0.0 // 0.1.13: snap + bounce on finger down
                if (onOrb) postDelayed(holdRun, 320) else postDelayed(longRun, 600)
            }
            MotionEvent.ACTION_MOVE -> {
                if (swallow) return true
                if (hypot(x - downX, y - downY) > 12f) { moved = true; removeCallbacks(longRun) }
                orb.touch(x, y)
                if (summoned) { val h = orb.hitTile(x, y); if (h != orb.hot) { orb.hot = h; if (h >= 0) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
                    host.ringHint(if (h >= 0 && h < ring.size) "release to open ${ring[h].label}" else if (ring.isEmpty()) EMPTY_HINT else "slide to an app · release") }
                else if (onOrb && moved && !prefs.quickOn && e.eventTime - downT > 200) { removeCallbacks(holdRun); summon() }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdRun); removeCallbacks(longRun); orb.hovT = 0.0
                if (swallow) { swallow = false; onOrb = false; return true } // a sticky-ring tap: already handled on DOWN
                val dx = x - downX; val dy = y - downY; val ms = e.eventTime - downT
                if (e.actionMasked == MotionEvent.ACTION_CANCEL) { if (summoned) closeRing(); return true }
                if (summoned) { val h = orb.hot; if (h >= 0 && h < ring.size) { val a = ring[h]; closeRing(); host.onRingPick(a) } else { sticky = true; orb.still = false; host.ringHint(if (ring.isEmpty()) EMPTY_HINT else "tap an app · tap outside to close") }; return true }
                if (longFired) return true
                if (onOrb) {
                    val dir = if (prefs.quickOn) Logic.flickDir(dx, dy, ms) else null
                    when { dir != null -> host.onFlick(dir); !moved -> host.onOrbTap(); else -> {} }
                } else if (moved && ms < 700) {
                    if (dy < -60 && kotlin.math.abs(dy) > kotlin.math.abs(dx)) host.onSwipeUp()
                    else if (dy > 60 && kotlin.math.abs(dy) > kotlin.math.abs(dx)) host.onSwipeDown()
                }
            }
        }
        return true
    }
}
