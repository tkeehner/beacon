package com.tyler.primelauncher

import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 0.1.17 ODST activities, ported from the approved mockups (index.html scenes). Mockup phone = 270 x 600 px with the face
 * at (135, 300), R 32; [S] maps a mockup px to dp on this screen. Gaze is in mockup units (x0.2 = orb gaze units).
 * Props never ride into the clock header or the Message Prime footer.
 */
object OdstAntics {
    const val ROUNDS = "odst.rounds"; const val TRAFFIC = "odst.traffic"; const val BILLBOARD = "odst.billboard"; const val TIDY = "odst.tidy"
    const val RAINWATCH = "odst.rainwatch"; const val SIGNAL = "odst.signal"; const val VERGIL = "odst.vergil"
    const val PODDROP = "odst.poddrop"; const val PODDROP_QUIET = "odst.poddrop.quiet"; const val VISR_ON = "odst.visr"; const val SCAN = "q.odst.scan"

    private fun S(g: Geo) = g.W / 270.0
    private fun mx(g: Geo, m: Double) = g.cx + (m - 135) * S(g)
    private fun my(g: Geo, m: Double) = g.cy + (m - 300) * S(g)
    /** Mockup px of face offset -> Pose units (orb radius 92). */
    private fun pd(g: Geo, m: Double) = m * S(g) / g.k
    private fun look(p: Pose, mgx: Double, mgy: Double, k: Double) { p.gx = mgx * 0.2; p.gy = mgy * 0.2; p.gazeK = max(p.gazeK, k) }
    private fun pop(p: Pose, t: Double, t0: Double, d: Double, e: Int) { if (t >= t0 && t < t0 + d) p.expr = e }
    /** Mockup font size, shrunk so [text] fits [maxW] (Android's bold face runs wider than the mockup's). */
    private fun fit(p: Paint, text: String, size: Float, maxW: Float) { p.textSize = size; val w = p.measureText(text); if (w > maxW) p.textSize = size * maxW / w }
    private fun roadY(g: Geo) = min(my(g, 470.0), g.floor - 18.0).toFloat()

    /** Rounds feed rectangles (dp): 2 x 2 above the face, shrunk if the header leaves less room. */
    fun feeds(g: Geo): List<RectF> {
        val s = S(g); val top = max(my(g, 92.0), g.top + 10.0); val bottom = g.cy - g.r - 30.0; val f = ((bottom - top) / (124 * s)).coerceIn(0.55, 1.0)
        return (0 until 4).map { i -> val x = g.cx + (-98 + (i % 2) * 104) * s * f; val y = top + (i / 2) * 66 * s * f; RectF(x.toFloat(), y.toFloat(), (x + 92 * s * f).toFloat(), (y + 58 * s * f).toFloat()) }
    }
    private fun roundsSel(t: Double) = when { t > 4.6 -> 3; t > 3.6 -> 2; t > 2.4 -> 1; else -> 0 }

    val list: List<Antic> by lazy { listOf(
        // ---- City Rounds: flips through four camera feeds; a cat crossing CAM 04 makes its day ----
        Antic(ROUNDS, 8.5, AK.ACT, { t, p, g ->
            p.tag1 = "CCTV\u00B7LINK"; p.tag2 = "4 FEEDS"; p.hudA = 1 - 0.4 * Ez.win(t, 0.3, 6.3, 0.4, 0.3)
            if (t > 1.2 && t < 6.2) { val r = feeds(g)[roundsSel(t)]; look(p, ((r.centerX() - g.cx) / S(g) / 60.0).coerceIn(-1.2, 1.2), -1.0, Ez.win(t, 1.2, 6.2, 0.15, 0.2)) }
            if (t > 3.6 && t < 4.7) p.lid = 0.3; pop(p, t, 4.8, 1.2, Expr.HAPPY); p.glow += OdstArt.chimePulse(t - 6.4)
        }, over = { a, t, _, g ->
            val fa = Ez.win(t, 0.3, 6.3, 0.4, 0.3); val fr = feeds(g)
            fr.forEachIndexed { i, r -> OdstArt.feed(a.cv, r.left, r.top, r.width(), r.height(), i, t, fa * Ez.sst(0.3 + i * 0.15, 0.6 + i * 0.15, t)) }
            if (t > 1.2 && t < 6.2) { val r = fr[roundsSel(t)]; OdstArt.brackets(a.cv, r.left - 3, r.top - 3, r.width() + 6, r.height() + 6, fa, a.glowOn) }
            OdstArt.caption(a, g, "ALL SECTORS NOMINAL.", t - 6.4, 2.1)
        }, cues = doubleArrayOf(6.4)),
        // ---- Traffic Control: cars queue at the light; it flips it to green with the chime ----
        Antic(TRAFFIC, 8.0, AK.ACT, { t, p, _ ->
            p.tag1 = "SIGNAL\u00B7CTRL"; p.tag2 = "JCT 4-B"; if (t < 5.5) look(p, 0.6, 1.0, Ez.win(t, 0.2, 5.5, 0.2, 0.3))
            if (t > 2 && t < 3.2) p.lid = 0.3; pop(p, t, 3.35, 1.0, Expr.HAPPY); p.glow += OdstArt.chimePulse(t - 3.2)
        }, under = { a, t, _, g -> val s = S(g).toFloat()
            OdstArt.traffic(a.cv, (g.cx - 135 * s), roadY(g), s, g.W, g.H, t, 3.2, Ez.win(t, 0.0, 7.8, 0.3, 0.3), a.glowOn)
        }, over = { a, t, _, g -> val s = S(g).toFloat()
            if (t > 3.1 && t < 3.9) OdstArt.chimeDots(a.cv, g.cx + (30 - 18) * s, roadY(g) - 104 * s, t - 3.2, OdstArt.AMBER, 1.0)
            OdstArt.caption(a, g, "INTERSECTION OPTIMIZED.", t - 5.7, 2.2)
        }, cues = doubleArrayOf(3.2, 5.7)),
        // ---- Billboard Swap: hacks the umbrella ad into a public-health notice ----
        Antic(BILLBOARD, 7.0, AK.ACT, { t, p, _ ->
            p.tag1 = "AD\u00B7NET"; p.tag2 = "BOARD 12"; look(p, 0.0, -1.0, Ez.win(t, 0.2, 6.6, 0.3, 0.3)); if (t > 2.5 && t < 3.4) p.wide = 1.0
            pop(p, t, 3.5, 1.1, Expr.HAPPY); p.glow += OdstArt.chimePulse(t - 2.6)
        }, over = { a, t, _, g ->
            val al = Ez.win(t, 0.2, 6.8, 0.4, 0.3); if (al <= 0.01) return@Antic; val s = S(g).toFloat(); val cv = a.cv; val fp = OdstArt.fp; val tp = OdstArt.tp
            val w = min(198 * s, g.W - 24f); val h = 64 * s * (w / (198 * s)); val x = g.cx - w / 2; val y = max(my(g, 112.0), g.top + 10.0).toFloat()
            fp.color = Color.argb((255 * al).toInt(), 0x2A, 0x31, 0x3A); cv.drawRect(x + 30 * s, y + h, x + 34 * s, y + h + 40 * s, fp); cv.drawRect(x + w - 34 * s, y + h, x + w - 30 * s, y + h + 40 * s, fp)
            fp.color = Color.argb((255 * al).toInt(), 8, 11, 16); cv.drawRect(x, y, x + w, y + h, fp); val n = 6; val sw = w / n; val k = w / 198f
            for (i in 0 until n) { val tf = 2.8 + i * 0.1; val sx = if (t > tf - 0.12 && t < tf + 0.12) kotlin.math.abs(t - tf) / 0.12 else 1.0; val newAd = t > tf
                cv.save(); cv.clipRect(x + i * sw, y, x + (i + 1) * sw, y + h); val c = x + i * sw + sw / 2; cv.scale(sx.toFloat().coerceAtLeast(0.02f), 1f, c, y)
                tp.textAlign = Paint.Align.CENTER
                if (!newAd) { fp.color = Color.argb((255 * al).toInt(), 0x14, 0x30, 0x4E); cv.drawRect(x, y, x + w, y + h, fp); tp.color = Color.argb((255 * al).toInt(), 0x9F, 0xC6, 0xFF)
                    tp.typeface = android.graphics.Typeface.DEFAULT_BOLD; fit(tp, "BUY MORE UMBRELLAS", 14 * k * s, w * 0.9f); cv.drawText("BUY MORE UMBRELLAS", x + w / 2, y + 30 * k * s, tp); tp.typeface = android.graphics.Typeface.DEFAULT; fit(tp, "(YOU WILL NEED THEM)", 9 * k * s, w * 0.9f); cv.drawText("(YOU WILL NEED THEM)", x + w / 2, y + 46 * k * s, tp) }
                else { fp.color = Color.argb((255 * al).toInt(), 0x1D, 0x13, 0x06); cv.drawRect(x, y, x + w, y + h, fp); tp.color = Color.argb((255 * al).toInt(), 0xFF, 0xB2, 0x3F)
                    tp.typeface = android.graphics.Typeface.DEFAULT_BOLD; fit(tp, "HYDRATE, CITIZEN", 17 * k * s, w * 0.9f); if (a.glowOn) tp.setShadowLayer(12f, 0f, 0f, OdstArt.AMBER); cv.drawText("HYDRATE, CITIZEN", x + w / 2, y + 32 * k * s, tp); tp.clearShadowLayer()
                    tp.typeface = android.graphics.Typeface.MONOSPACE; fit(tp, "\u2014 A MESSAGE FROM YOUR CITY \u2014", 9 * k * s, w * 0.9f); cv.drawText("\u2014 A MESSAGE FROM YOUR CITY \u2014", x + w / 2, y + 50 * k * s, tp) }
                cv.restore() }
            if (t > 2.5 && t < 3.0) OdstArt.staticFx(cv, x, y, w, h, 0.8 * al, t)
            OdstArt.sp.strokeWidth = 2f; OdstArt.sp.color = Color.argb((255 * al).toInt(), 0x3A, 0x43, 0x50); cv.drawRect(x, y, x + w, y + h, OdstArt.sp)
        }, cues = doubleArrayOf(2.6)),
        // ---- Keep It Tidy (evenings): the garbage truck putters by; thumbs-up ----
        Antic(TIDY, 8.0, AK.ACT, { t, p, _ ->
            p.tag1 = "SANITATION"; p.tag2 = "ROUTE 3"; look(p, ((OdstArt.truckX(t) + 30 - 135) / 120).coerceIn(-1.2, 1.2), 1.0, Ez.win(t, 0.2, 7.6, 0.3, 0.3))
            pop(p, t, 4.2, 1.4, Expr.HAPPY); p.glow += OdstArt.chimePulse(t - 5.0)
        }, under = { a, t, _, g -> val s = S(g).toFloat(); OdstArt.truck(a.cv, g.cx - 135 * s, roadY(g), s, g.W, t, Ez.win(t, 0.0, 7.9, 0.2, 0.3), a.glowOn) },
        over = { a, t, p, g -> val s = S(g).toFloat(); OdstArt.thumb(a.cv, (g.ox(p) + g.r + 26 * s).toFloat(), (g.oy(p) + 4 * s).toFloat(), 1.3f * s, Ez.win(t, 4.2, 7.0, 0.2, 0.3))
            OdstArt.caption(a, g, "WASTE COLLECTION: ON SCHEDULE.", t - 5.0, 2.8) }, cues = doubleArrayOf(5.0)),
        // ---- Rain Watch (rain or night): watches the rain, dozes off, the chime startles it ----
        Antic(RAINWATCH, 8.0, AK.ACT, { t, p, g ->
            p.tag1 = "PRECIP\u00B7HEAVY"; p.tag2 = "WX 07"; p.rainMul = 1 + 0.7 * Ez.win(t, 0.2, 7.6, 0.6, 0.6); p.hudA = 1 - 0.55 * Ez.win(t, 3.0, 5.4, 0.6, 0.1)
            if (t < 3) look(p, 0.0, -1.0, Ez.win(t, 0.2, 3.0, 0.3, 0.1)); if (t > 1.8 && t < 3) p.lid = 0.4
            pop(p, t, 3.0, 2.45, Expr.SLEEPING); pop(p, t, 5.45, 1.0, Expr.CONFUSED)
            p.dy += pd(g, (if (t > 3 && t < 5.45) 6.0 else 0.0) - 16 * Ez.bump(t - 5.45, 0.45))
            if (t > 3 && t < 5.45) p.glow *= 0.4 else p.glow += OdstArt.chimePulse(t - 5.3)
        }, over = { a, t, _, g -> if (t > 5.2 && t < 6.2) OdstArt.chimeDots(a.cv, g.cx - 12f, (g.cy - g.r - 30 * S(g)).toFloat(), t - 5.3, OdstArt.AMBER, 1.0) },
        cues = doubleArrayOf(5.3)),
        // ---- Signal Hunt: radar sweep, an audio-log pill, static over the face... then found ----
        Antic(SIGNAL, 9.0, AK.ACT, { t, p, _ ->
            p.tag1 = "RF\u00B7SCAN"; p.tag2 = "BAND 3"; pop(p, t, 2.2, 1.3, Expr.CONFUSED); pop(p, t, 4.7, 1.1, Expr.HAPPY); if (t > 3.5 && t < 4.7) p.lid = 0.35; p.glow += OdstArt.chimePulse(t - 5.6)
        }, under = { a, t, _, g ->
            val sa = Ez.win(t, 1.2, 4.8, 0.3, 0.3); if (sa <= 0.01) return@Antic; val s = S(g).toFloat(); val rr = g.r + 60 * s; val an = (t - 1.2) * 2.4
            val cv = a.cv; val fp = OdstArt.fp; fp.color = Color.argb((70 * sa).toInt(), 255, 178, 63); val rf = RectF(g.cx - rr, g.cy - rr, g.cx + rr, g.cy + rr)
            cv.drawArc(rf, Math.toDegrees(an - 0.5).toFloat(), 28.6f, true, fp)
            OdstArt.sp.strokeWidth = 1.5f; OdstArt.sp.color = Color.argb((255 * sa).toInt(), 255, 178, 63); cv.drawLine(g.cx, g.cy, (g.cx + cos(an) * rr).toFloat(), (g.cy + sin(an) * rr).toFloat(), OdstArt.sp)
        }, over = { a, t, _, g ->
            val s = S(g).toFloat(); val cv = a.cv; val found = t > 4.6; val pa = Ez.win(t, 0.3, 8.8, 0.2, 0.3)
            if (pa > 0.01) { val w = 124 * s; val h = 26 * s; val x = g.cx - 62 * s; val y = min((g.cy + g.r + 64 * s).toDouble(), g.floor - h - 4.0).toFloat(); val col = if (found) 0xFF3DFF7A.toInt() else OdstArt.AMBER
                val path = android.graphics.Path(); OdstArt.chamfer(path, x, y, w, h, 6 * s); OdstArt.fp.color = Color.argb((230 * pa).toInt(), 10, 14, 20); cv.drawPath(path, OdstArt.fp)
                OdstArt.sp.strokeWidth = 1.2f; OdstArt.sp.color = a.withA(col, pa); cv.drawPath(path, OdstArt.sp)
                OdstArt.tp.typeface = android.graphics.Typeface.MONOSPACE; OdstArt.tp.textSize = 9 * s; OdstArt.tp.textAlign = Paint.Align.LEFT; OdstArt.tp.color = a.withA(col, pa)
                cv.drawText(if (found) "SIGNAL ACQUIRED \u2713" else "AUDIO LOG \u25B8 ???", x + 8 * s, y + 11 * s, OdstArt.tp); OdstArt.fp.color = a.withA(col, pa)
                for (i in 0 until 22) { val amp = if (found) 0.3 + 0.7 * kotlin.math.abs(sin(i * 0.7 + t * 6)) else Ez.hash(i.toDouble(), floor(t * 12), 2.0) * (if (t > 2.4 && t < 4.6) 1.0 else 0.3)
                    cv.drawRect(x + (8 + i * 5) * s, (y + (20 - amp * 6) * s).toFloat(), x + (11 + i * 5) * s, y + 21 * s, OdstArt.fp) }
                if ((t > 0.35 && t < 0.6) || (t > 0.95 && t < 1.2)) { OdstArt.sp.color = a.withA(OdstArt.AMBER, 0.6); cv.drawCircle(g.cx + 60 * s, y + 13 * s, (8 + ((t * 4) % 1) * 10).toFloat() * s, OdstArt.sp) } }
            val so = Ez.win(t, 2.4, 4.6, 0.5, 0.15); val e = g.r + 40 * s; OdstArt.staticFx(cv, g.cx - e, g.cy - e, 2 * e, 2 * e, so * 0.8, t)
            OdstArt.caption(a, g, "SIGNAL ACQUIRED. LOGGING.", t - 5.6, 3.2)
        }, cues = doubleArrayOf(5.6)),
        // ---- Vergil moment (rare, late, long screen time): blue flicker, a softer voice (bubble from CompanionBrain) ----
        Antic(VERGIL, 7.2, AK.ACT, { t, p, _ ->
            val v = vergilK(t); p.vergil = v; if (t > 0.9 && t < 2.2) p.lid = 0.35; if (v > 0.5) { p.glow *= 1.3; p.tag1 = "vgl//"; p.tag2 = "// listening" }
        }, under = { a, t, _, g -> if (vergilK(t) > 0.5) { OdstArt.fp.color = Color.argb(31, 0x3C, 0x9B, 0xFF); a.cv.drawRect(0f, 0f, g.W, g.H, OdstArt.fp) } }),
        podDrop(PODDROP, true), podDrop(PODDROP_QUIET, false),
        // ---- VISR switching on at sunset: a happy blink at the new view ----
        Antic(VISR_ON, 3.0, AK.ACT, { t, p, _ -> p.tag1 = "VISR\u00B7ON"; p.tag2 = "LOW LIGHT"; if (t > 0.2 && t < 1.0) p.wide = 1.0; pop(p, t, 1.0, 1.0, Expr.HAPPY) }),
        // ---- idle mannerism: a slow scan left then right ----
        Antic(SCAN, 3.0, AK.QUIRK, { s, p, _ -> val l = Ez.win(s, 0.2, 1.3, 0.2, 0.2); val r = Ez.win(s, 1.4, 2.6, 0.2, 0.2); p.gx = 0.2 * (r - l); p.gy = -0.02; p.gazeK = max(l, r); p.lid = 0.18 * (l + r); p.tag1 = "SCANNING" }),
    ) }

    /** Vergil flicker on, hold, flicker off (mockup). */
    fun vergilK(t: Double): Double = when {
        t > 1.4 && t < 2.2 -> if (Ez.hash(floor(t * 14), 0.0, 0.0) > 0.45) 1.0 else 0.0
        t >= 2.2 && t < 5.8 -> 1.0
        t >= 5.8 && t < 6.3 -> if (Ez.hash(floor(t * 16) + 5, 0.0, 0.0) > 0.5) 1.0 else 0.0
        else -> 0.0
    }

    /** Pod Drop on unlock (played from 1.1 s): streak from the sky, thud, HUD boot glitch, the face boots up. */
    private fun podDrop(id: String, caption: Boolean) = Antic(id, 6.6, AK.CORE, { t, p, g ->
        p.faceA = if (t >= 1.75) 1.0 else 0.0
        p.expr = if (t < 2.6) Expr.OFF else if (t >= 3.3 && t < 4.4) Expr.HAPPY else Expr.EYES
        val sh = if (t > 1.75) exp(-(t - 1.75) * 6) * 5 else 0.0; p.dx += pd(g, sin(t * 90) * sh); p.dy += pd(g, cos(t * 77) * sh + (if (t < 1.9) 4.0 else 0.0))
        p.hudA = if (t < 2.0) 0.0 else if (t < 3.1) (if (Ez.hash(floor(t * 20), 1.0, 1.0) > 0.35) 1.0 else 0.17) else 1.0
        p.tag1 = "CIVIC OS v0.1"; p.tag2 = "BOOT OK"; p.glow += OdstArt.chimePulse(t - 3.5)
    }, over = { a, t, _, g ->
        val s = S(g).toFloat(); val cv = a.cv
        if (t > 2.0 && t < 3.1) { OdstArt.tp.typeface = android.graphics.Typeface.MONOSPACE; OdstArt.tp.textSize = 9 * s; OdstArt.tp.textAlign = Paint.Align.LEFT; OdstArt.tp.color = OdstArt.AMBER
            val y0 = min((g.cy + g.r + 70 * s).toDouble(), g.floor - 50.0).toFloat()
            listOf("CIVIC OS v0.1 // BOOT", "SENSORS ........ OK", "RAIN ........ HEAVY", "RESIDENT ...... YOU").forEachIndexed { i, l -> if (t > 2.05 + i * 0.22) cv.drawText(l, 24 * s, y0 + i * 12 * s, OdstArt.tp) } }
        if (t > 1.75 && t < 3) { val k = t - 1.75; val rr = (g.r + k * 120 * s).toFloat(); OdstArt.sp.strokeWidth = 2f; OdstArt.sp.color = a.withA(0xFFFFD9A0.toInt(), (1 - k / 1.25) * 0.8)
            cv.drawOval(g.cx - rr, g.cy + g.r * 0.6f - rr * 0.3f, g.cx + rr, g.cy + g.r * 0.6f + rr * 0.3f, OdstArt.sp)
            for (i in 0 until 18) { val an = PI + Ez.hash(i.toDouble(), 0.0, 0.0) * PI; val v = (40 + Ez.hash(i.toDouble(), 2.0, 0.0) * 90) * s
                a.dot(g.cx + cos(an) * v * k, g.cy + g.r * 0.5 + sin(an) * v * k * 0.4 + k * k * 30 * s, 1.6 * s, 0xFFB9A88F.toInt(), (1 - k / 1.25) * 0.6) } }
        if (t > 1.25 && t < 1.8) { val k = Ez.easeIO(Ez.clamp((t - 1.25) / 0.5, 0.0, 1.0)); val x0 = g.W + 30.0; val y0 = -40.0; val x = Ez.lerp(x0, g.cx.toDouble(), k); val y = Ez.lerp(y0, g.cy.toDouble(), k)
            a.line(0xFFFFDCA0.toInt(), 7.0 * s, 0.95, 18.0) { moveTo(Ez.lerp(x0, x, 0.55).toFloat(), Ez.lerp(y0, y, 0.55).toFloat()); lineTo(x.toFloat(), y.toFloat()) }
            a.dot(x, y, 6.0 * s, Color.WHITE, 1.0) }
        if (caption) OdstArt.caption(a, g, "CITY SERVICES ONLINE.", t - 3.5, 2.9)
    }, cues = if (caption) doubleArrayOf(3.5) else DoubleArray(0))
}
