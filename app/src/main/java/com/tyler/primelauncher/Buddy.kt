package com.tyler.primelauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** One chat bubble. [then] follows it (a joke's punchline). */
class ChatBubble(val text: String, val cat: Cat, val badge: String?, val hold: Double = 3.2, val then: ChatBubble? = null, val laugh: Boolean = false,
                 /** 0.1.17 ODST: the rare soft blue Vergil voice. */ val vergil: Boolean = false) {
    var t0 = -1.0; var dur = 0.0; var dragDx = 0f; var gone = false; var goneT = 0.0; var goneDir = 0f
    val rect = RectF(); var below = false
    var lines: List<String> = emptyList(); var w = 0f; var h = 0f
}

/**
 * 0.1.15 companion runtime on the stage: plays [Antics], layers continuous body language (tilt, pet, listen lean, think,
 * app nudge) into the orb's [Pose], draws chat bubbles, and owns the two new gestures. One-finger gestures are untouched:
 *  - poke = a quick TWO-finger tap on the orb (3 in a row = dizzy). Single tap stays instant tap-to-talk.
 *  - pet = a slow two-finger rub on the orb (purr: light haptic ticks).
 * The accelerometer is registered only between [resume] / [pause] (home visible) and never with Battery saver / Reduce motion.
 */
class Buddy(private val st: StageView, private val prefs: Prefs) : SensorEventListener {
    interface Listener {
        fun onBubbleTap(b: ChatBubble) {}
        fun onBubbleSwipe(b: ChatBubble) {}
        /** May the orb start a self-triggered idle extra (peekaboo) right now? Records it if true. */
        fun allowIdleAct(id: String): Boolean = false
        /** A poke / pet / little win happened: maybe a one-line reaction. */
        fun onReact(kind: String) {}
        /** 0.1.18: activities an idle beat may start right now (time-of-day gates, quiet hours, busy already applied). */
        fun beatActs(): List<PoolItem> = emptyList()
        /** 0.1.22: offer this beat to a chat bubble; true if one is now showing. */
        fun beatBubble(): Boolean = false
    }
    var listener: Listener? = null
    val geo = Geo(); private val art = Art()
    private var cur: Antic? = null; private var curT0 = 0.0; private var curEnd = 0.0
    val playing: String? get() = cur?.id
    val playingKind: AK? get() = cur?.kind
    private val pending = ArrayList<Triple<Double, String, Double>>() // (at orb time, id, from)
    private val enabled get() = prefs.companion

    // ---------- antics ----------
    /** Play [id] from [from] s; [end] < 0 = its full length. Core moments replace anything; activities / quirks only start when idle. */
    fun play(id: String, from: Double = 0.0, end: Double = -1.0): Boolean {
        if (!st.ready || !enabled) return false
        val a = Antics[id] ?: return false
        if (a.kind != AK.CORE && (prefs.reduce || cur != null)) return false
        if (a.kind == AK.CORE && cur?.id == Antics.DIZZY && id != Antics.HEADSUP && id != Antics.DIZZY) return false
        cur = a; curT0 = st.orb.t - from; curEnd = if (end > 0) end else a.len; lastCueS = from; return true
    }
    fun playLater(delayS: Double, id: String, from: Double = 0.0) { if (st.ready) pending.add(Triple(st.orb.t + delayS, id, from)) }
    fun stopAntic() { cur = null }

    /** Any finger down: an activity / quirk / bedtime ends instantly and the orb turns to you (blushing if it was studying / gaming). */
    private fun noticeTouch(x: Float, y: Float) {
        if (!st.ready) return; lastTouchT = st.orb.t; val c = cur ?: return
        if (c.kind == AK.CORE && c.id != Antics.BEDTIME) return
        if (c.kind == AK.QUIRK) { cur = null; return } // a micro / mannerism just stops; only real activities get "caught"
        geo.fingerX = x; geo.fingerY = y; geo.blush = c.blushOnCatch
        cur = null; play(Antics.CAUGHT)
    }

    // ---------- continuous layers ----------
    private var tiltX = 0.0; private var tiltV = 0.0; private var wallT = -9.0; private var gxF = 0.0
    private var shakes = ArrayList<Long>(); private var lastShakeDizzy = 0L
    private var petK = 0.0; private var petting = false; private var petX = 0f; private var petY = 0f; private var lastPurr = 0L
    private var prevTh = 0.0; private var prevMode = Mode.IDLE; private var callLoudT = -9.0; private var lastNodT = -9.0
    var lastTouchT = 0.0; private set
    private var nudgeIdx = -1; private var nudgeT = -9.0
    var bubble: ChatBubble? = null; private set
    private var nextBubble: ChatBubble? = null; private var nextAt = 0.0
    private var lastCueS = 0.0
    private fun odst() = prefs.odst
    /** ODST 4-note civic chime (Settings toggle; Chime itself stays silent unless the ringer is normal and no call is up). */
    private fun chime() { if (prefs.odst && prefs.chime && !prefs.saver) Chime.play(st.context) }

    /** Seconds since the last touch on the stage. */
    val idleS get() = if (st.ready) st.orb.t - lastTouchT else 0.0

    fun step(dt: Double) {
        if (!st.ready) return
        val o = st.orb; val t = o.t; val p = o.pose
        geo.cx = st.cxOrb(); geo.cy = st.cy; geo.r = st.orbR(); geo.W = st.W; geo.H = st.H; geo.skin = o.skin
        geo.top = if (st.headerBottom > 0) st.headerBottom else st.topInset + 160f; geo.floor = st.footerTop() - 6f
        p.reset()
        if (!enabled) { cur = null; bubble = null; return }
        val it = pending.iterator(); while (it.hasNext()) { val q = it.next(); if (t >= q.first) { it.remove(); play(q.second, q.third) } }
        val c = cur
        if (c != null) { val s = t - curT0
            if (s >= curEnd) cur = null
            else { val ps = lastCueS; lastCueS = s; if (st.orb.skin.odst && c.cues.any { it > ps && it <= s }) chime()
                c.pose(s, p, geo)
                if (c.kind == AK.CORE) p.damp(Personas.of(prefs.theme).reactionScale.let { if (prefs.reduce) it * 0.5 else it })
                val inK = if (c.kind == AK.CORE) 1.0 else Ez.sst(0.0, 0.25, s); val outK = 1 - Ez.sst(curEnd - 0.35, curEnd, s); if (inK * outK < 1) p.fade(inK * outK) } }
        val busyBody = cur != null
        // Tilt & Slosh: a marble living in the phone (accelerometer only while home is visible).
        val tiltOk = sensorOn && !busyBody && o.mode == Mode.IDLE && o.sp < 0.01 && !touching && o.sleep.k < 0.2
        val lim = (st.W / 2 - st.orbR() * 0.92f - 6f).toDouble()
        val force = if (tiltOk && abs(gxF) > 2.2) -(gxF - 2.2 * kotlin.math.sign(gxF)) * 55.0 else 0.0
        tiltV += (force - tiltX * (if (tiltOk && force != 0.0) 1.5 else 9.0) - tiltV * 3.2) * dt; tiltX += tiltV * dt
        if (abs(tiltX) > lim) { tiltX = lim * kotlin.math.sign(tiltX); if (abs(tiltV) > 40) wallT = t; tiltV *= -0.35 }
        if (abs(tiltX) > 0.3) { val k = geo.k; p.dx += tiltX / k; p.rotOff += tiltX / st.orbR(); if (!busyBody) { p.gx = 0.17 * Ez.clamp(tiltV / 300, -1.0, 1.0); p.gazeK = max(p.gazeK, min(1.0, abs(tiltV) / 120)) } }
        val wall = Ez.clamp(1 - (t - wallT) / 0.35, 0.0, 1.0); if (wall > 0) { p.axis = 0.0; p.sx -= 0.12 * wall; p.sy += 0.07 * wall }
        // Pet & Purr (live while two fingers rub the orb)
        petK += ((if (petting) 1.0 else 0.0) - petK) * min(1.0, dt * (if (petting) 4.0 else 6.0))
        if (petK > 0.01) { p.happy = max(p.happy, petK); p.dx += 0.9 * sin(t * 75) * petK + (petX - geo.cx) / geo.k * 0.12 * petK; p.dy += (petY - geo.cy) / geo.k * 0.1 * petK
            p.sy += 0.015 * sin(t * 50) * petK; p.glow += 0.6 * petK; p.rim += 0.5 * petK; p.rainMul *= 1 - 0.6 * petK }
        // Listen & Nod: lean in while listening; little nods in pauses; think = eyes up-right; "got it" as the answer lands.
        if (!busyBody) { p.dy += 8 * o.ls; p.scale += 0.03 * o.ls
            if (o.th > 0.01) { p.gx = 0.1; p.gy = -0.2; p.gazeK = max(p.gazeK, o.th * 0.85); p.spin += 1.5 * o.th } }
        if (prevTh > 0.6 && o.th <= 0.6 && o.mode != Mode.THINK) play(Antics.GOTIT)
        if (prevMode == Mode.LISTEN && o.mode == Mode.IDLE && t - lastNodT > 1.5) { lastNodT = t; play(Antics.NOD) }
        if (o.mode == Mode.TALK) { if (st.callLvl > 0.38) callLoudT = t else if (t - callLoudT in 0.3..0.45 && t - lastNodT > 2.5) { lastNodT = t; play(Antics.NOD) } }
        prevTh = o.th; prevMode = o.mode
        // App Nudge: glance at the app it thinks you want (until your finger picks a tile).
        val ns = t - nudgeT
        if (nudgeIdx >= 0 && ns < 2.2 && o.hot < 0 && o.sp > 0.3) { val sl = o.ring?.slots?.getOrNull(nudgeIdx)
            if (sl != null) { val L = hypot(sl.x, sl.y).coerceAtLeast(1f); val w = Ez.win(ns, 0.35, 2.0, 0.08, 0.15); p.gx = sl.x / L * 0.22; p.gy = sl.y / L * 0.22; p.gazeK = max(p.gazeK, w); p.open += 0.35 * Ez.bump(ns - 0.35, 0.5) } }
        // Talking: tiny pulses while a bubble's text appears.
        val b = bubble
        if (b != null) { val s = t - b.t0; if (s > 0 && s < revealS(b)) { p.scale += 0.018 * abs(sin(s * 28)); p.glow += 0.15 }
            // ODST: the face reacts to its own announcement (expression pop by department), and glows with the chime.
            if (odst() && s > 0) { p.glow += OdstArt.chimePulse(s)
                if (!busyBody && !prefs.reduce) when (b.cat) {
                    Cat.GREETING -> if (s in 0.5..1.6) p.expr = Expr.HAPPY
                    Cat.CLEAN -> if (s in 0.15..1.45) p.expr = Expr.ANGRY
                    Cat.NOTIFY -> if (s in 0.2..1.2) p.wide = 1.0
                    Cat.CAR -> if (s in 0.3..2.0) p.lid = 0.35
                    else -> {} } } }
        stepBubbles(t)
        // 0.1.18 idle beats: every 4-6 s of plain idle, a micro-animation or (~3 in 10) a full activity; boredom peekaboo stays.
        beatStep(t, busyBody, o)
    }

    // ---------- 0.1.18 idle beats ----------
    private var nextBeat = -1.0; var lastBeat: String? = null; private set
    var beats = 0; private set; var beatActs = 0; private set; var beatBubbles = 0; private set
    /** True while nothing should interrupt the orb's rest: no antic, bubble, touch, ring, voice, sleep or bedtime. */
    fun beatIdle(busyBody: Boolean = cur != null): Boolean { val o = st.orb
        return enabled && !busyBody && !prefs.reduce && o.mode == Mode.IDLE && o.sleep.k < 0.05 && !touching && o.sp < 0.01 && bubble == null && nextBubble == null && pending.isEmpty() }
    private fun beatStep(t: Double, busyBody: Boolean, o: Orb) {
        if (!beatIdle(busyBody)) { nextBeat = -1.0; return } // the 4-6 s count restarts once the orb is back at rest
        if (nextBeat < 0) { nextBeat = t + Beats.interval(Math.random()); return }
        if (idleS > 240 && listener?.allowIdleAct(Antics.PEEKABOO) == true) { play(Antics.PEEKABOO); lastTouchT = t; nextBeat = -1.0; return }
        if (t < nextBeat) return
        nextBeat = -1.0
        if (listener?.beatBubble() == true) { beatBubbles++; return }
        val per = Personas.of(prefs.theme); val acts = listener?.beatActs() ?: emptyList()
        val id = Beats.next(Math.random(), Math.random(), per.micros, acts, lastBeat) ?: return
        if (play(id)) { lastBeat = id; beats++; if (!Micros.isMicro(id) && Antics[id]?.kind == AK.ACT) beatActs++ }
    }

    fun nudge(idx: Int) { if (!st.ready || idx < 0 || !enabled) { nudgeIdx = -1; return }; nudgeIdx = idx; nudgeT = st.orb.t }

    // ---------- drawing ----------
    fun drawUnder(cv: Canvas) { if (!st.ready || !enabled) return; val c = cur ?: return; val u = c.under ?: return; art.cv = cv; art.glowOn = !prefs.saver; u(art, st.orb.t - curT0, st.orb.pose, geo) }
    fun drawOver(cv: Canvas) {
        if (!st.ready || !enabled) return; art.cv = cv; art.glowOn = !prefs.saver; val o = st.orb; val t = o.t; val k = geo.k
        cur?.let { c -> c.over?.invoke(art, t - curT0, o.pose, geo) }
        if (o.th > 0.05 && cur == null) for (i in 0 until 3) { val a = t * 3 + i * Ez.TAU / 3
            art.glyph(if (o.skin.neon) "◆" else "0", geo.cx + cos(a) * (geo.r + 16 * k), geo.cy - geo.r * 0.75 + sin(a) * (geo.r + 16 * k) * 0.35 - 20 * k, 11 * k, o.skin.head, o.th * (0.6 + 0.4 * sin(a)), 8.0) }
        if (petK > 0.05) for (sd in intArrayOf(-1, 1)) for (j in 0 until 2) art.glyph(if (sd < 0) "(" else ")", geo.cx + sd * (geo.r + (14 + j * 9) * k) + sin(t * 60) * 1.2, geo.cy.toDouble(), (16 + j * 4) * k, o.skin.body, petK * (0.7 - j * 0.3) * (0.6 + 0.4 * sin(t * 12)), 8.0)
        // 0.1.17: no app-nudge trail and no highlight box in any theme; every ring icon looks the same. The orb's
        // glance toward the suggested app (step()) is the only nudge left; the finger's own hot tile still lights as touch feedback.
    }

    // ---------- chat bubbles ----------
    private val bp = Paint(Paint.ANTI_ALIAS_FLAG); private val bs = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bt = Paint(Paint.ANTI_ALIAS_FLAG); private val bpath = Path()
    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL); private val cond = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private fun neon() = prefs.neon
    private fun reveal() = if (prefs.reduce) Reveal.GLYPH_DECODE else Personas.of(prefs.theme).reveal
    private fun revealS(b: ChatBubble) = if (prefs.reduce) 0.0 else when (reveal()) { Reveal.WORD_SLAM -> b.text.split(' ').size / 5.0; Reveal.PA -> OdstArt.revealS(b.text); else -> b.text.length / 26.0 }
    /** Show a bubble now (replaces any current one). False if the companion is off or the stage isn't up. */
    fun say(b: ChatBubble, noteBubblesShowing: Boolean = false): Boolean {
        if (!st.ready || !enabled) return false
        layout(b); b.below = noteBubblesShowing; b.t0 = st.orb.t; b.dur = (revealS(b) + b.hold).coerceIn(3.0, 8.0); bubble = b; nextBubble = null
        if (!b.laugh) chime(); return true
    }
    private fun layout(b: ChatBubble) {
        if (odst()) { val w = min(300f, st.W - 40f); b.lines = OdstArt.wrap(b.text, w - 22f, b.vergil); b.w = w; b.h = OdstArt.boxH(b.lines.size); return }
        bt.typeface = if (neon()) cond else mono; bt.textSize = if (neon()) 14f else 12.5f
        val maxW = min(220f, st.W - 60f); val out = ArrayList<String>(); var line = ""
        for (w in b.text.split(' ')) { val tt = if (line.isEmpty()) w else "$line $w"; if (bt.measureText(tt) > maxW && line.isNotEmpty()) { out.add(line); line = w } else line = tt }
        if (line.isNotEmpty()) out.add(line); b.lines = out
        b.w = (out.maxOfOrNull { bt.measureText(it) } ?: 40f) + 24f + (if (b.badge != null) 10f else 0f); b.h = out.size * 17f + 16f
    }
    private fun place(b: ChatBubble) {
        val o = st.orb; val ox = geo.cx + (o.pose.dx * geo.k).toFloat(); val r = o.radius(); val top = geo.top + 6f
        var y = st.cy - (if (odst()) r + 50f else r + 16f) - b.h; var below = b.below; if (y < top) below = true
        if (below) y = st.cy + r + 18f
        val x = if (odst()) (ox - b.w / 2).coerceIn(14f, st.W - 14f - b.w) else (ox + 24f - b.w / 2).coerceIn(14f, st.W - 14f - b.w)
        b.rect.set(x + b.dragDx, y, x + b.dragDx + b.w, y + b.h); b.below = below
    }
    private fun stepBubbles(t: Double) {
        val b = bubble
        if (b != null) { if (!touchingBubble) { val s = t - b.t0; val endS = if (b.gone) b.goneT + 0.3 else b.t0 + b.dur
                if (t >= endS) { bubble = null; val n = b.then; if (n != null && !b.gone) { nextBubble = n; nextAt = t + 0.45 } } }
            else b.t0 += 1.0 / 60 } // holding it pauses the clock
        val n = nextBubble; if (bubble == null && n != null && t >= nextAt) { nextBubble = null; say(n, b?.below == true)
            if (n.laugh) playLater(revealS(n) + 0.15, Antics.JOKELAUGH) }
    }
    fun clearBubble() { bubble = null; nextBubble = null }
    fun drawBubble(cv: Canvas) {
        val b = bubble ?: return; if (!st.ready) return; val t = st.orb.t; val s = t - b.t0; place(b)
        val neon = neon(); val sk = st.orb.skin; val r = b.rect
        val fadeIn = if (prefs.reduce) 1.0 else Ez.sst(0.0, 0.15, s); val fadeOut = if (b.gone) 1 - Ez.sst(b.goneT, b.goneT + 0.3, t) else 1 - Ez.sst(b.dur - 0.35, b.dur, s)
        val a = (fadeIn * fadeOut * (1 - min(0.8, abs(b.dragDx) / 220.0))).coerceIn(0.0, 1.0); if (a <= 0.01) return
        val pop = if (prefs.reduce || s > 0.22) 1.0 else 0.7 + 0.3 * sin(s / 0.22 * PI / 2) * 1.08
        val jolt = if (neon && !prefs.reduce && s < 0.15) (3 * sin(s * 80) * (1 - s / 0.15)).toFloat() else 0f
        val gx = if (b.gone) b.goneDir * ((t - b.goneT) * 900).toFloat() else 0f
        cv.save(); cv.translate(jolt + gx, 0f); val px = r.centerX(); val py = if (b.below) r.top else r.bottom; cv.scale(pop.toFloat(), pop.toFloat(), px, py)
        if (odst()) { val tx0 = (geo.cx + (st.orb.pose.dx * geo.k).toFloat()).coerceIn(r.left + 16f, r.right - 16f)
            val head = b.badge ?: if (b.vergil) "vgl // \u00B7\u00B7\u00B7" else "\u25A3 MUNICIPAL NOTICE"
            OdstArt.box(cv, r, head, b.lines, b.text.length, if (prefs.reduce) 9.0 else s, a, b.vergil, tx0, b.below, !prefs.saver, typed = !prefs.reduce)
            cv.restore(); return }
        // box + tail
        bpath.rewind()
        if (neon) { val c = 8f; bpath.moveTo(r.left + c, r.top); bpath.lineTo(r.right, r.top); bpath.lineTo(r.right, r.bottom - c); bpath.lineTo(r.right - c, r.bottom); bpath.lineTo(r.left, r.bottom); bpath.lineTo(r.left, r.top + c); bpath.close() }
        else bpath.addRoundRect(r, 10f, 10f, Path.Direction.CW)
        val tx = (geo.cx + 10f).coerceIn(r.left + 14f, r.right - 14f)
        if (b.below) { bpath.moveTo(tx - 6, r.top); bpath.lineTo(tx, r.top - 9); bpath.lineTo(tx + 6, r.top) } else { bpath.moveTo(tx - 6, r.bottom); bpath.lineTo(tx, r.bottom + 9); bpath.lineTo(tx + 6, r.bottom) }
        bp.style = Paint.Style.FILL; bp.color = art.withA(if (neon) 0xFF100E17.toInt() else 0xFF06120A.toInt(), 0.94 * a); cv.drawPath(bpath, bp)
        bs.strokeWidth = 1.2f; bs.color = art.withA(if (neon) sk.accent else sk.body, 0.8 * a); if (!prefs.saver) bs.setShadowLayer(8f, 0f, 0f, art.withA(sk.body, 0.6 * a)); cv.drawPath(bpath, bs); bs.clearShadowLayer()
        if (neon) { bs.strokeWidth = 0.8f; bs.color = art.withA(sk.eye, 0.5 * a); cv.drawRect(r.left + 3, r.top + 3, r.right - 3, r.bottom - 3, bs) }
        // text with the persona's reveal
        bt.typeface = if (neon) cond else mono; bt.textSize = if (neon) 14f else 12.5f; bt.textAlign = Paint.Align.LEFT
        val ink = if (neon) 0xFFEEF2F3.toInt() else sk.head; val x0 = r.left + 12f; var yb = r.top + 20f
        if (reveal() == Reveal.GLYPH_DECODE || prefs.reduce) {
            val n = if (prefs.reduce) b.text.length else floor(s * 26).toInt().coerceAtMost(b.text.length); var left = n
            for (ln in b.lines) { val show = ln.take(max(0, left)); left -= ln.length + 1; bt.color = art.withA(ink, a); cv.drawText(show, x0, yb, bt)
                if (left < 0 && left > -ln.length - 2 && n < b.text.length) { val cw = bt.measureText(show); bt.color = art.withA(sk.body, a)
                    for (j in 0 until 3) { if (show.length + j >= ln.length) break; bt.color = art.withA(sk.body, a * (1 - j * 0.3)); cv.drawText(if (Ez.hash(n + j.toDouble(), floor(t * 24), 3.0) < 0.5) "0" else "1", x0 + cw + j * bt.measureText("0"), yb, bt) } }
                yb += 17f }
        } else {
            val shown = floor(s * 5).toInt() + 1; var idx = 0; val lastS = s - (shown - 1) / 5.0
            val total = b.text.split(' ').size
            for (ln in b.lines) { var x = x0
                for (w in ln.split(' ')) { if (idx >= shown) break
                    val slam = idx == shown - 1 && shown <= total && lastS < 0.14; val sc = if (slam) (1.35 - 0.35 * (lastS / 0.14)).toFloat() else 1f
                    bt.color = art.withA(ink, a); val ww = bt.measureText(w)
                    cv.save(); cv.scale(sc, sc, x + ww / 2, yb - 5); cv.drawText(w, x, yb, bt); cv.restore(); x += ww + bt.measureText(" "); idx++ }
                yb += 17f }
        }
        // Neon badge pops in once the words are down
        val badge = b.badge
        if (neon && badge != null) { val bs0 = s - revealS(b); if (bs0 > 0) { val k = if (bs0 < 0.18) (bs0 / 0.18 * 1.25).toFloat() else (1 + 0.25 * Ez.spr(bs0 - 0.18, 8.0, 20.0)).toFloat()
            val cx = r.right - 4f; val cy = r.top + 4f; bp.color = art.withA(sk.accent, a); cv.drawCircle(cx, cy, 11f * k, bp)
            bt.textAlign = Paint.Align.CENTER; bt.color = art.withA(0xFF100E17.toInt(), a); bt.textSize = 12f * k; cv.drawText(badge, cx, cy - (bt.descent() + bt.ascent()) / 2, bt); bt.textAlign = Paint.Align.LEFT } }
        cv.restore()
    }

    // ---------- touch ----------
    private var touching = false; private var touchingBubble = false; private var bDownX = 0f; private var bMoved = false
    private var owning = false; private var twoT0 = 0L; private var cx0 = 0f; private var cy0 = 0f; private var path = 0f; private var lastCx = 0f; private var lastCy = 0f
    private val pokes = ArrayList<Double>()
    /**
     * Called first for every stage touch. True = the buddy owns this gesture (a bubble touch, or a two-finger poke / pet),
     * so StageView must not treat it as tap / hold / flick / swipe. [onOrb] = the first finger went down on the orb.
     */
    fun onTouch(e: MotionEvent, x: Float, y: Float, onOrb: Boolean, ringBusy: Boolean): Boolean {
        val d = st.d
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { touching = true; owning = false; touchingBubble = false
                val b = bubble; if (b != null && !b.gone && enabled) { val r = RectF(b.rect); r.inset(-8f, -8f); if (r.contains(x, y)) { touchingBubble = true; bDownX = x; bMoved = false; return true } }
                noticeTouch(x, y); return false }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!enabled || touchingBubble || ringBusy || e.pointerCount != 2) return owning || touchingBubble
                val i = e.actionIndex; val x2 = e.getX(i) / d; val y2 = e.getY(i) / d
                val near = hypot(x2 - st.cxOrb(), y2 - st.cy) < st.orbR() * 1.25f
                if (onOrb || near) { owning = true; twoT0 = e.eventTime; centroid(e); cx0 = lastCx; cy0 = lastCy; path = 0f; petting = false; return true }
                return false }
            MotionEvent.ACTION_MOVE -> {
                if (touchingBubble) { val b = bubble ?: return true; b.dragDx = x - bDownX; if (abs(b.dragDx) > 8) bMoved = true; return true }
                if (!owning) return false
                if (e.pointerCount >= 2) { val px = lastCx; val py = lastCy; centroid(e); path += hypot(lastCx - px, lastCy - py)
                    if (!petting && e.eventTime - twoT0 > 350 && path > 30f) { petting = true; stopIfActivity() }
                    if (petting) { petX = lastCx; petY = lastCy; if (e.eventTime - lastPurr > 110 && hypot(lastCx - px, lastCy - py) > 0.4f) { lastPurr = e.eventTime; st.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) } } }
                return true }
            MotionEvent.ACTION_POINTER_UP -> { if (owning) { endTwo(e); return true }; return touchingBubble }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { touching = false
                if (touchingBubble) { touchingBubble = false; val b = bubble ?: return true
                    if (e.actionMasked == MotionEvent.ACTION_UP) {
                        if (abs(b.dragDx) > 90f) { b.gone = true; b.goneT = st.orb.t; b.goneDir = if (b.dragDx > 0) 1f else -1f; nextBubble = null; listener?.onBubbleSwipe(b) }
                        else if (!bMoved) { bubble = null; nextBubble = null; listener?.onBubbleTap(b) }
                        else b.dragDx = 0f } else b.dragDx = 0f
                    return true }
                if (owning) { if (petting) endTwo(e); owning = false; petting = false; return true }
                return false }
        }
        return owning || touchingBubble
    }
    private fun stopIfActivity() { val c = cur; if (c != null && c.kind != AK.CORE) cur = null }
    private fun centroid(e: MotionEvent) { var sx = 0f; var sy = 0f; val n = e.pointerCount; for (i in 0 until n) { sx += e.getX(i); sy += e.getY(i) }; lastCx = sx / n / st.d; lastCy = sy / n / st.d }
    private fun endTwo(e: MotionEvent) {
        if (!st.ready) return; val t = st.orb.t
        if (petting) { petting = false; play(Antics.PETAFTER); listener?.onReact("pet") }
        else if (e.eventTime - twoT0 < 450 && path < 24f) {
            geo.pokeX = lastCx; geo.pokeY = lastCy; pokes.removeAll { t - it > 1.6 }; pokes.add(t)
            if (pokes.size >= 3) { pokes.clear(); play(Antics.DIZZY) } else { play(Antics.POKE); if (pokes.size == 1) listener?.onReact("poke") }
            st.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
        path = Float.MAX_VALUE // one poke per two-finger gesture
    }

    // ---------- accelerometer (home visible only) ----------
    private var sensorOn = false
    fun resume() {
        if (sensorOn || prefs.saver || prefs.reduce || !enabled) return
        val sm = st.context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return; val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensorOn = sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_UI)
    }
    fun pause() { if (!sensorOn) return; (st.context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.unregisterListener(this); sensorOn = false; tiltX = 0.0; tiltV = 0.0; gxF = 0.0; petting = false; touching = false; touchingBubble = false; owning = false }
    val accelOn get() = sensorOn
    override fun onSensorChanged(ev: SensorEvent) {
        val ax = ev.values[0].toDouble(); val ay = ev.values[1].toDouble(); val az = ev.values[2].toDouble()
        gxF += (ax - gxF) * 0.2
        val lin = abs(sqrt(ax * ax + ay * ay + az * az) - SensorManager.GRAVITY_EARTH); val now = ev.timestamp / 1_000_000
        if (lin > 11) { if (shakes.isEmpty() || now - shakes.last() > 120) shakes.add(now); shakes.removeAll { now - it > 900 }
            if (shakes.size >= 3 && now - lastShakeDizzy > 4000) { lastShakeDizzy = now; shakes.clear(); st.post { if (st.ready && cur?.kind != AK.CORE) { cur = null; play(Antics.DIZZY) } } } }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    @Suppress("unused") private fun colorA(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
}
