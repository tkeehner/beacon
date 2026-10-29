package com.tyler.primelauncher

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 0.1.27 holiday theme (manual only). The orb is one of five shaded avatars that take turns: on every home open and
 * every 20 minutes of visible time, from a shuffled bag (all five before any repeats, never the same one twice in a row).
 * A swap plays one of three transitions, picked at random each time, and never while asleep, thinking, talking or on a call.
 */
enum class HFace { PUMPKIN, GHOST, CAT, HAT }
enum class HSwap(val dur: Double) { SMOKE(2.0), CAULDRON(1.9), COIN(1.5) }

/** Pure cycling rules (unit-tested). Bag state lives in SharedPreferences "prime". */
object HolidayCycle {
    const val SWAP_EVERY_MS = 20 * 60_000L
    private const val K_FACE = "hol.face"; private const val K_BAG = "hol.bag"; private const val K_POS = "hol.pos"
    /** The face shown right now (persona lines follow it). */
    @Volatile var face: HFace = HFace.PUMPKIN
    fun load(sp: SharedPreferences): HFace { val f = sp.getString(K_FACE, null)?.let { n -> HFace.entries.firstOrNull { it.name == n } }
        if (f != null) { face = f; return f }; val n = next(sp, java.util.Random()); return n }
    fun hasSaved(sp: SharedPreferences) = sp.getString(K_FACE, null) != null
    private const val K_LAST = "hol.lastSwap"
    /** 0.1.28: wall-clock time of the last swap. onResume fires on every shade close / screen-on / app return, so a home
     *  open only counts when the last swap is at least [SWAP_EVERY_MS] old (0.1.27 swapped on each one: "rapid fire"). */
    fun homeOpenDue(sp: SharedPreferences, now: Long) = now - sp.getLong(K_LAST, 0L) >= SWAP_EVERY_MS
    /** Visible-time rule: due once [visibleMs] of on-screen time has built up since the last swap. */
    fun timerDue(visibleMs: Double) = visibleMs >= SWAP_EVERY_MS
    fun markSwapped(sp: SharedPreferences, now: Long) { sp.edit().putLong(K_LAST, now).apply() }
    /** Next face out of the bag; reshuffles when empty so the new round never starts with the face that just showed. */
    fun next(sp: SharedPreferences, rnd: java.util.Random): HFace {
        var bag = sp.getString(K_BAG, "")!!.split(',').mapNotNull { n -> HFace.entries.firstOrNull { it.name == n } }
        var pos = sp.getInt(K_POS, 0); val last = sp.getString(K_FACE, null)
        if (bag.size != HFace.entries.size || pos >= bag.size) { bag = shuffle(rnd, last); pos = 0 }
        val f = bag[pos]; sp.edit().putString(K_BAG, bag.joinToString(",") { it.name }).putInt(K_POS, pos + 1).putString(K_FACE, f.name).apply(); face = f; return f
    }
    fun shuffle(rnd: java.util.Random, avoidFirst: String?): List<HFace> {
        val a = HFace.entries.toMutableList(); for (i in a.size - 1 downTo 1) { val j = rnd.nextInt(i + 1); val t = a[i]; a[i] = a[j]; a[j] = t }
        if (avoidFirst != null && a[0].name == avoidFirst) { val t = a[0]; a[0] = a[1]; a[1] = t }
        return a
    }
    fun pickSwap(rnd: java.util.Random): HSwap = HSwap.entries[rnd.nextInt(HSwap.entries.size)]
    /** A swap only plays on a resting orb: awake, not thinking / listening / talking, no call, no activity running, ring closed. */
    fun canSwap(sleepE: Double, asleep: Boolean, mode: Mode, think: Double, inCall: Boolean, anticBusy: Boolean, ringOpen: Boolean) =
        !asleep && sleepE < 0.05 && mode == Mode.IDLE && think < 0.05 && !inCall && !anticBusy && !ringOpen
}

/** Theme sync between Beacon and Emit (both directions, every theme). See ThemeSyncReceiver for the receiving side. */
object ThemeSync {
    const val ACTION = "com.tyler.themesync.SET"
    const val EXTRA_THEME = "theme"; const val EXTRA_TS = "ts"
    const val EMIT_PKG = "com.tyler.matrixsms"; const val EMIT_RX = "com.tyler.matrixsms.ui.ThemeSyncReceiver"
    val IDS = setOf("matrix", "neon", "odst", "holiday")
    /** HomeActivity sets this to re-skin live when Emit changes the theme. */
    @Volatile var listener: (() -> Unit)? = null
    fun send(ctx: Context, theme: String, now: Long = System.currentTimeMillis()) {
        if (theme !in IDS) return
        Prefs(ctx).sp.edit().putLong("themeTs", now).apply()
        val i = Intent(ACTION).setComponent(ComponentName(EMIT_PKG, EMIT_RX)).putExtra(EXTRA_THEME, theme).putExtra(EXTRA_TS, now)
        runCatching { ctx.sendBroadcast(i, "com.tyler.primelauncher.permission.THEME_SYNC") /* only Emit (knownCerts) holds it */ }
    }
    /** Apply an incoming theme: known id, different from now, and newer than the last local or remote change. Never re-sends. */
    fun accept(prefs: Prefs, theme: String?, ts: Long): Boolean {
        if (theme == null || theme !in IDS || theme == prefs.theme) return false
        if (ts <= prefs.sp.getLong("themeTs", 0L)) return false
        prefs.sp.edit().putLong("themeTs", ts).apply(); prefs.theme = theme; return true
    }
}

/** Exported, but guarded by Beacon's own THEME_SYNC permission (signature|knownSigner: only Emit's signing cert may hold it). */
class ThemeSyncReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != ThemeSync.ACTION) return
        if (ThemeSync.accept(Prefs(c), i.getStringExtra(ThemeSync.EXTRA_THEME), i.getLongExtra(ThemeSync.EXTRA_TS, 0L))) ThemeSync.listener?.invoke()
    }
}

/**
 * Shaded holiday avatars on Canvas (no 3D engine): the still parts of each face are baked once into a bitmap
 * (radial body gradient lit from the upper left, blurred ambient-occlusion creases, a glyph surface, a specular spot and a
 * lower-right rim light); eyes, holes, mouths and the moving parts (jaw, hem, hat tip, ears, tail, stem) draw live every
 * frame. Battery saver ([glowOn] false) bakes only the gradient + glyphs and skips blur, specular, rim and eye glow.
 */
class HolidayFace(private val dpr: Float) {
    companion object { /** Bake radius (dp); drawn scaled to the live, breathing radius. */ const val BASE_R = 102f }
    var glowOn = true
    var face = HFace.PUMPKIN; private set
    private var from = HFace.PUMPKIN; private var kind = HSwap.SMOKE; private var t0 = -99.0
    var lastT = 0.0; private set
    /** Advance the swap clock from the frame step (not only from draw), so a swap ends even when nothing is drawn. */
    fun tick(t: Double) { if (t > lastT) lastT = t }
    val swapping get() = lastT - t0 < kind.dur
    fun setFace(f: HFace) { face = f; t0 = -99.0 }
    fun startSwap(to: HFace, k: HSwap, t: Double) { from = face; face = to; kind = k; t0 = t; lastT = t }
    /** Tests / screenshots: transition progress 0..1 (or -1 when idle). */
    fun swapProgress(t: Double) = if (t - t0 < kind.dur) (t - t0) / kind.dur else -1.0
    val swapKind get() = kind

    private val baked = HashMap<String, Bitmap>()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG); private val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
    private val path = Path(); private val path2 = Path(); private val rf = RectF(); private val dst = RectF()

    /** Blur and shadow radii are in device pixels and ignore the canvas matrix; everything here is in dp. */
    private fun px(dp: Float) = (dp * dpr).coerceAtLeast(0.5f)
    private fun a(c: Int, al: Double) = Color.argb((al.coerceIn(0.0, 1.0) * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))
    private fun mix(c1: Int, c2: Int, k: Double): Int { val q = k.coerceIn(0.0, 1.0)
        return Color.argb(255, (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * q).toInt(), (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * q).toInt(), (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * q).toInt()) }

    // ---------- palettes: light, mid, dark, rim ----------
    private fun pal(f: HFace) = when (f) {
        HFace.PUMPKIN -> intArrayOf(0xFFFFB44E.toInt(), 0xFFE5700F.toInt(), 0xFF5C2403.toInt(), 0xFFFFC27A.toInt())
        HFace.GHOST -> intArrayOf(0xFFF6F8FF.toInt(), 0xFFC9D1E4.toInt(), 0xFF5E6782.toInt(), 0xFF9CC8FF.toInt())
        HFace.CAT -> intArrayOf(0xFF64588C.toInt(), 0xFF2E2742.toInt(), 0xFF0A0810.toInt(), 0xFFAE84FF.toInt())
        HFace.HAT -> intArrayOf(0xFFB98AF0.toInt(), 0xFF6B34A8.toInt(), 0xFF1E0B33.toInt(), 0xFFD7B4FF.toInt())
    }

    // ---------- silhouettes in R units, centered at (0,0) ----------
    private fun bodyPath(f: HFace, R: Float, out: Path) { out.rewind()
        when (f) {
            HFace.PUMPKIN -> { out.addOval(-1.05f * R, -0.9f * R, 1.05f * R, 0.92f * R, Path.Direction.CW) }
            HFace.GHOST -> { out.addCircle(0f, -0.05f * R, R * 0.95f, Path.Direction.CW); path2.rewind(); path2.addRect(-0.95f * R, -0.05f * R, 0.95f * R, 1.32f * R, Path.Direction.CW); out.op(path2, Path.Op.UNION) }
            HFace.CAT -> out.addOval(-0.98f * R, -0.82f * R, 0.98f * R, 0.92f * R, Path.Direction.CW)
            HFace.HAT -> { out.addOval(-1.22f * R, 0.42f * R, 1.22f * R, 0.82f * R, Path.Direction.CW); path2.rewind()
                path2.moveTo(-0.78f * R, 0.62f * R); path2.lineTo(0.78f * R, 0.62f * R); path2.lineTo(0.3f * R, -0.62f * R); path2.lineTo(-0.28f * R, -0.64f * R); path2.close(); out.op(path2, Path.Op.UNION) }
        }
    }
    private fun creases(f: HFace, R: Float, out: Path) { out.rewind()
        when (f) {
            HFace.PUMPKIN -> for (x in floatArrayOf(-0.55f, -0.2f, 0.2f, 0.55f)) { out.moveTo(x * R * 0.7f, -0.86f * R); out.quadTo(x * R * 1.25f, 0f, x * R * 0.7f, 0.88f * R) }
            HFace.GHOST -> for (x in floatArrayOf(-0.57f, -0.19f, 0.19f, 0.57f)) { out.moveTo(x * R, 0.62f * R); out.quadTo(x * R + 0.03f * R, 0.85f * R, x * R, 1.25f * R) }
            HFace.CAT -> { out.moveTo(-0.15f * R, 0.42f * R); out.quadTo(0f, 0.5f * R, 0.15f * R, 0.42f * R) }
            HFace.HAT -> { out.moveTo(-0.78f * R, 0.64f * R); out.quadTo(0f, 0.72f * R, 0.78f * R, 0.64f * R) }
        }
    }

    /** Bake the still body of [f] at radius [R] dp. */
    private fun bake(f: HFace, R: Float): Bitmap {
        val key = "${f.name}:${glowOn}"; baked[key]?.let { return it }
        val S = 3.0f * R; val pxs = (S * dpr).toInt().coerceAtLeast(8)
        val bm = Bitmap.createBitmap(pxs, pxs, Bitmap.Config.ARGB_8888); val c = Canvas(bm); c.scale(dpr, dpr); c.translate(S / 2, S / 2)
        val pl = pal(f); val body = Path(); bodyPath(f, R, body)
        // 1. base volume: radial gradient lit from the upper left
        p.shader = RadialGradient(-0.38f * R, -0.45f * R, 1.75f * R, intArrayOf(pl[0], pl[1], pl[2]), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP); c.drawPath(body, p); p.shader = null
        c.save(); c.clipPath(body)
        if (f == HFace.HAT) { // brim lighter on top, darker underneath; cone shaded along the light
            p.shader = LinearGradient(-0.8f * R, -0.6f * R, 0.8f * R, 0.6f * R, a(Color.WHITE, 0.18), a(Color.BLACK, 0.35), Shader.TileMode.CLAMP); c.drawRect(-1.3f * R, -0.7f * R, 1.3f * R, 0.62f * R, p); p.shader = null }
        if (glowOn) {
            // 2. ambient occlusion: blurred dark creases + a dark falloff toward the edge
            creases(f, R, path); sp.strokeWidth = 0.09f * R; sp.color = a(Color.BLACK, 0.55); sp.maskFilter = BlurMaskFilter(px(0.07f * R), BlurMaskFilter.Blur.NORMAL); c.drawPath(path, sp); sp.maskFilter = null
            p.shader = RadialGradient(0f, 0f, 1.2f * R, intArrayOf(0, 0, a(Color.BLACK, 0.45)), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP); c.drawRect(-1.5f * R, -1.5f * R, 1.5f * R, 1.5f * R, p); p.shader = null
        }
        // 3. glyph surface (kept in battery saver: it is the theme's texture)
        tp.textSize = 0.12f * R; tp.color = a(pl[0], 0.22); tp.clearShadowLayer(); val rr = Rng(f.ordinal * 7 + 3); var y = -1.4f * R
        while (y < 1.4f * R) { var x = -1.4f * R; while (x < 1.4f * R) { c.drawText(if (rr.next() < 0.5) "0" else "1", x, y, tp); x += 0.11f * R }; y += 0.14f * R }
        if (glowOn) {
            // 4. specular spot, upper left
            p.shader = RadialGradient(-0.4f * R, -0.48f * R, 0.32f * R, intArrayOf(a(Color.WHITE, 0.75), a(Color.WHITE, 0.0)), null, Shader.TileMode.CLAMP); c.drawCircle(-0.4f * R, -0.48f * R, 0.32f * R, p); p.shader = null
        }
        c.restore()
        if (glowOn) {
            // 5. rim light on the lower right edge (back light)
            c.save(); path2.rewind(); path2.moveTo(1.5f * R, -0.6f * R); path2.lineTo(1.5f * R, 1.5f * R); path2.lineTo(-0.6f * R, 1.5f * R); path2.close(); c.clipPath(path2); c.clipPath(body)
            sp.strokeWidth = 0.08f * R; sp.color = a(pl[3], 0.7); sp.maskFilter = BlurMaskFilter(px(0.06f * R), BlurMaskFilter.Blur.NORMAL); c.drawPath(body, sp); sp.maskFilter = null; c.restore()
        }
        if (glowOn) { // soft edge: feather the silhouette outline so it reads rounded, not cut out
            sp.strokeWidth = 0.03f * R; sp.color = a(pl[2], 0.5); sp.maskFilter = BlurMaskFilter(px(0.025f * R), BlurMaskFilter.Blur.NORMAL); c.drawPath(body, sp); sp.maskFilter = null }
        baked[key] = bm; return bm
    }
    fun recycle() { baked.values.forEach { it.recycle() }; baked.clear() }

    /** One frame. [open] 0..1.6 eye openness, [gx]/[gy] gaze -1.4..1.4, [glow] eye/hole brightness, [sleepE] 0 awake .. 1 asleep. */
    fun draw(cv: Canvas, cx: Float, cy: Float, R: Float, t: Double, ps: Pose, open: Double, gx: Double, gy: Double, glow: Double, sleepE: Double) {
        lastT = t
        val pr = swapProgress(t)
        if (pr < 0) { drawFace(cv, face, cx, cy, R, t, ps, open, gx, gy, glow, sleepE, 1.0, 0f, 0f, 1f, 1f, 0f); return }
        when (kind) {
            HSwap.COIN -> { val ang = pr * 2 * PI; val showFrom = pr < 0.25; var sx = max(0.04, abs(cos(ang))).toFloat()
                if (pr > 0.8) sx *= (1 + 0.08 * sin((pr - 0.8) / 0.2 * PI)).toFloat()
                drawFace(cv, if (showFrom) from else face, cx, cy, R, t, ps, open, gx, gy, glow * (if (sx < 0.2f) 0.6 else 1.0), sleepE, 1.0, 0f, 0f, sx, 1f, 0f) }
            HSwap.SMOKE -> { val s = pr * kind.dur
                if (s < 1.0) exitOf(cv, from, s / 1.0, cx, cy, R, t, ps, open, gx, gy, glow, sleepE)
                else enterOf(cv, face, ((s - 1.0) / 1.0).coerceIn(0.0, 1.0), cx, cy, R, t, ps, open, gx, gy, glow, sleepE)
                smoke(cv, cx, cy, R, s) }
            HSwap.CAULDRON -> { val s = pr * kind.dur
                if (s < 0.9) exitOf(cv, from, s / 0.9, cx, cy, R, t, ps, open, gx, gy, glow, sleepE)
                if (s > 1.0) enterOf(cv, face, ((s - 1.0) / 0.9).coerceIn(0.0, 1.0), cx, cy, R, t, ps, open, gx, gy, glow, sleepE)
                vortex(cv, cx, cy, R, s) }
        }
    }
    /** Character exits (k 0..1): ghost fades up, cat leaps off, pumpkin flickers out, hat winks and spins away. */
    private fun exitOf(cv: Canvas, f: HFace, k: Double, cx: Float, cy: Float, R: Float, t: Double, ps: Pose, open: Double, gx: Double, gy: Double, glow: Double, sleepE: Double) {
        val e = k * k
        when (f) {
            HFace.GHOST -> drawFace(cv, f, cx, cy, R, t, ps, open, gx, gy, glow, sleepE, 1 - k, 0f, (-0.7 * R * k).toFloat(), 1f, 1f, 0f)
            HFace.CAT -> { val crouch = if (k < 0.3) k / 0.3 else 0.0; val up = if (k >= 0.3) (k - 0.3) / 0.7 else 0.0
                drawFace(cv, f, cx, cy, R, t, ps, open, gx, gy, glow, sleepE, 1 - up * up, 0f, (-1.6 * R * up * up + 0.08 * R * crouch).toFloat(), (1 + 0.1 * crouch - 0.08 * up).toFloat(), (1 - 0.18 * crouch + 0.12 * up).toFloat(), 0f) }
            HFace.PUMPKIN -> { val fl = if (Ez.hash(floor(t * 20), 5.0, 1.0) > 0.5) 1.0 else 0.4; drawFace(cv, f, cx, cy, R, t, ps, open, gx, gy, glow * fl * (1 - k), sleepE, 1 - e, 0f, 0f, 1f, 1f, 0f) }
            HFace.HAT -> drawFace(cv, f, cx, cy, R, t, ps, open, gx, gy, glow, sleepE, 1 - e, (0.9 * R * e).toFloat(), (-1.1 * R * e).toFloat(), (0.4 + 0.6 * abs(cos(k * PI * 1.5))).toFloat(), 1f, (40 * e).toFloat(), wink = k < 0.35)
        }
    }
    /** Character entrances (k 0..1): ghost drifts in, cat leaps in and lands, pumpkin lights up, hat drops in. */
    private fun enterOf(cv: Canvas, f: HFace, k: Double, cx: Float, cy: Float, R: Float, t: Double, ps: Pose, open: Double, gx: Double, gy: Double, glow: Double, sleepE: Double) {
        val e = 1 - (1 - k) * (1 - k)
        when (f) {
            HFace.GHOST -> drawFace(cv, f, cx, cy, R, t, ps, open, gx, gy, glow, sleepE, k, 0f, (0.4 * R * (1 - e)).toFloat(), 1f, 1f, 0f)
            HFace.CAT, HFace.HAT -> { val fall = if (k < 0.6) k / 0.6 else 1.0; val land = if (k >= 0.6) (k - 0.6) / 0.4 else 0.0; val sq = sin(land * PI) * (1 - land)
                drawFace(cv, f, cx, cy, R, t, ps, if (f == HFace.HAT && k < 0.6) 0.05 else open, gx, gy, glow, sleepE, min(1.0, k * 3), 0f, (-1.4 * R * (1 - fall) * (1 - fall)).toFloat(), (1 + 0.14 * sq).toFloat(), (1 - 0.2 * sq).toFloat(), 0f, happy = if (land > 0.5) 1.0 else 0.0) }
            HFace.PUMPKIN -> drawFace(cv, f, cx, cy, R, t, ps, open, gx, gy, glow * e * (if (k < 0.5 && Ez.hash(floor(t * 20), 7.0, 1.0) > 0.5) 0.4 else 1.0), sleepE, min(1.0, k * 2), 0f, 0f, 1f, 1f, 0f)
        }
    }
    private fun smoke(cv: Canvas, cx: Float, cy: Float, R: Float, s: Double) {
        if (s < 0.3 || s > 1.6) return; val k = (s - 0.3) / 1.3; val bloom = sin(k * PI); val rnd = Rng(5)
        for (i in 0 until 22) { val ang = rnd.next() * 2 * PI; val d0 = rnd.next() * 0.9; val r0 = (0.25 + rnd.next() * 0.35) * R * (0.6 + 1.1 * k)
            val x = cx + (cos(ang) * d0 * R * (0.7 + 0.6 * k)).toFloat(); val y = cy + (sin(ang) * d0 * R * 0.8 - 0.3 * R * k).toFloat() - (0.1 * R * i / 22).toFloat()
            val c = if (i % 5 == 0) 0xFF6B4A8A.toInt() else 0xFF8E8A96.toInt()
            p.shader = RadialGradient(x, y, r0.toFloat().coerceAtLeast(1f), intArrayOf(a(c, 0.85 * bloom), a(c, 0.0)), null, Shader.TileMode.CLAMP); cv.drawCircle(x, y, r0.toFloat(), p) }
        p.shader = null
    }
    private fun vortex(cv: Canvas, cx: Float, cy: Float, R: Float, s: Double) {
        if (s > 1.85) return; val k = (s / 1.85).coerceIn(0.0, 1.0); val pull = sin(k * PI); val rnd = Rng(11)
        tp.textSize = 0.14f * R; if (glowOn) tp.setShadowLayer(px(0.06f * R), 0f, 0f, a(0xFFFF8C1E.toInt(), 0.8)) else tp.clearShadowLayer()
        for (i in 0 until 90) { val a0 = rnd.next() * 2 * PI; val r0 = 0.3 + rnd.next() * 0.7; val sp0 = 0.6 + rnd.next()
            val rr = R * (r0 * (1 + 0.5 * pull) - 0.6 * pull * r0 * (if (k > 0.5) 1.0 else 0.4)); val ang = a0 + k * 7 * sp0
            val x = cx + (cos(ang) * rr).toFloat(); val y = cy + (sin(ang) * rr * 0.75).toFloat()
            tp.color = a(mix(0xFFFF8C1E.toInt(), 0xFFB57EF0.toInt(), pull), pull * 0.95); cv.drawText(if (i % 2 == 0) "0" else "1", x, y, tp) }
        tp.clearShadowLayer()
        sp.strokeWidth = 0.05f * R; sp.color = a(0xFFB57EF0.toInt(), 0.7 * pull); if (glowOn) sp.setShadowLayer(px(0.08f * R), 0f, 0f, a(0xFFB57EF0.toInt(), 0.7 * pull))
        rf.set(cx - 0.45f * R, cy - 0.2f * R, cx + 0.45f * R, cy + 0.2f * R); cv.drawArc(rf, (k * 900).toFloat(), 260f, false, sp); sp.clearShadowLayer()
    }

    /** Draw [f] with an extra transform (alpha, offset, scale, rotation); expression overrides for transitions. */
    private fun drawFace(cv: Canvas, f: HFace, cx: Float, cy: Float, R: Float, t: Double, ps: Pose, open0: Double, gx: Double, gy: Double, glow: Double, sleepE: Double,
                         alpha: Double, ox: Float, oy: Float, sx: Float, sy: Float, rot: Float, wink: Boolean = false, happy: Double = 0.0, jaw: Double = 0.0) {
        if (alpha <= 0.01) return
        val bodyK = (alpha * ps.bodyK.coerceIn(0.05, 1.0)).coerceIn(0.0, 1.0)
        // ghost: always floats (slower asleep)
        val fy = if (f == HFace.GHOST) (sin(t * 2 * PI / (2.4 + 3.6 * sleepE)) * 0.06 * R).toFloat() else 0f
        // contact shadow on the "floor", shrinks as the avatar rises
        val lift = (-(oy + fy) / R).coerceIn(-0.3f, 2f); val shW = R * (0.95f - 0.25f * lift).coerceAtLeast(0.3f)
        val shY = cy + R * (if (f == HFace.GHOST) 1.42f else if (f == HFace.HAT) 1.02f else 1.12f)
        if (glowOn || true) { p.shader = RadialGradient(cx + ox, shY, shW.coerceAtLeast(1f), intArrayOf(a(Color.BLACK, 0.7 * bodyK * (1 - 0.4 * lift).coerceIn(0.2, 1.0)), a(Color.BLACK, 0.0)), null, Shader.TileMode.CLAMP)
            cv.save(); cv.scale(1f, 0.16f, cx + ox, shY); cv.drawCircle(cx + ox, shY, shW, p); cv.restore(); p.shader = null }
        cv.save(); cv.translate(cx + ox, cy + oy + fy); if (rot != 0f) cv.rotate(rot); if (sx != 1f || sy != 1f) cv.scale(sx, sy)
        val open = if (wink) open0 else open0; val hap = max(happy, ps.happy.coerceIn(0.0, 1.0)); val wk = if (wink) 1.0 else ps.wink.coerceIn(0.0, 1.0)
        val ex = (gx * 0.07 * R).toFloat(); val ey = (gy * 0.06 * R).toFloat(); val g = (glow * alpha).coerceIn(0.0, 2.2)
        // parts behind the body
        if (f == HFace.CAT) { catTail(cv, R, t, ps, bodyK); catEars(cv, R, ps, bodyK) }
        val bm = bake(f, BASE_R); val S = 3.0f * R
        if (f == HFace.GHOST) ghostBody(cv, bm, R, t, ps, bodyK)
        else { dst.set(-S / 2, -S / 2, S / 2, S / 2); p.alpha = (bodyK * 255).toInt(); cv.drawBitmap(bm, null, dst, p); p.alpha = 255 }
        when (f) {
            HFace.PUMPKIN -> pumpkin(cv, R, t, ps, open, hap, wk, ex, ey, g, bodyK)
            HFace.GHOST -> ghost(cv, R, t, ps, open, hap, wk, ex, ey, g, bodyK)
            HFace.CAT -> cat(cv, R, ps, open, hap, wk, ex, ey, g, bodyK)
            HFace.HAT -> hat(cv, R, t, ps, open, hap, wk, ex, ey, g, bodyK)
        }
        cv.restore()
    }

    /** Glowing hole/eye fill: dark AO ring, then an inner glow from a bright core. */
    private fun glowFill(cv: Canvas, shape: Path, core: Int, edge: Int, ccx: Float, ccy: Float, rad: Float, k: Double, R: Float) {
        if (glowOn) { sp.strokeWidth = 0.07f * R; sp.color = a(Color.BLACK, 0.6 * k.coerceAtMost(1.0)); sp.maskFilter = BlurMaskFilter(px(0.05f * R), BlurMaskFilter.Blur.NORMAL); cv.drawPath(shape, sp); sp.maskFilter = null }
        p.shader = RadialGradient(ccx, ccy, rad.coerceAtLeast(1f), intArrayOf(a(core, min(1.0, 0.35 + 0.65 * k)), a(edge, min(1.0, 0.25 + 0.6 * k)), a(mix(edge, Color.BLACK, 0.5), 0.9)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        if (glowOn) p.setShadowLayer(0.12f * R * k.toFloat().coerceIn(0f, 1.6f), 0f, 0f, a(edge, 0.8 * min(1.0, k))); cv.drawPath(shape, p); p.clearShadowLayer(); p.shader = null
    }
    private fun arcEye(cv: Canvas, x: Float, y: Float, w: Float, col: Int, R: Float, k: Double) {
        path.rewind(); path.moveTo(x - w, y + 0.05f * R); path.quadTo(x, y - 0.12f * R, x + w, y + 0.05f * R)
        sp.strokeWidth = 0.07f * R; sp.color = a(col, 0.95 * k); if (glowOn) sp.setShadowLayer(px(0.08f * R), 0f, 0f, a(col, 0.8 * k)); cv.drawPath(path, sp); sp.clearShadowLayer()
    }
    private fun lineEye(cv: Canvas, x: Float, y: Float, w: Float, col: Int, R: Float, k: Double) { sp.strokeWidth = 0.05f * R; sp.color = a(col, 0.9 * k); cv.drawLine(x - w, y, x + w, y, sp) }
    private fun spiral(cv: Canvas, x: Float, y: Float, w: Float, col: Int, t: Double, R: Float, k: Double) {
        path.rewind(); for (j in 0..30) { val u = j / 30.0; val ang = u * 4 * PI + t * 7; val rr = u * w; val px = x + (cos(ang) * rr).toFloat(); val py = y + (sin(ang) * rr * 0.8).toFloat(); if (j == 0) path.moveTo(px, py) else path.lineTo(px, py) }
        sp.strokeWidth = 0.035f * R; sp.color = a(col, 0.9 * k); cv.drawPath(path, sp)
    }

    /** 0.1.28 cute pass: white catchlights (big + small) on an eye whose box is w x h at (x, y). */
    private fun catchlight(cv: Canvas, x: Float, y: Float, w: Float, h: Float, k: Double) {
        p.color = a(Color.WHITE, 0.95 * k); cv.drawCircle(x - w * 0.32f, y - h * 0.38f, max(w, h) * 0.26f, p)
        p.color = a(Color.WHITE, 0.75 * k); cv.drawCircle(x + w * 0.3f, y + h * 0.3f, max(w, h) * 0.11f, p)
    }
    /** Soft pink blush under each eye. */
    private fun blush(cv: Canvas, dx: Float, y: Float, R: Float, k: Double, al: Double = 0.55) {
        for (s in floatArrayOf(-1f, 1f)) { val x = s * dx
            p.shader = RadialGradient(x, y, 0.16f * R, intArrayOf(a(0xFFFF7FA8.toInt(), al * k), a(0xFFFF7FA8.toInt(), 0.0)), null, Shader.TileMode.CLAMP)
            rf.set(x - 0.17f * R, y - 0.1f * R, x + 0.17f * R, y + 0.1f * R); cv.drawOval(rf, p); p.shader = null }
    }
    /** Small friendly closed smile. */
    private fun smile(cv: Canvas, x: Float, y: Float, w: Float, col: Int, R: Float, k: Double, deep: Double = 0.0) {
        path.rewind(); path.moveTo(x - w, y); path.quadTo(x, y + w * (0.75f + 0.5f * deep.toFloat()), x + w, y)
        sp.strokeWidth = 0.045f * R; sp.color = a(col, 0.95 * k); cv.drawPath(path, sp)
    }
    private fun pumpkin(cv: Canvas, R: Float, t: Double, ps: Pose, open: Double, hap: Double, wk: Double, ex: Float, ey: Float, g: Double, k: Double) {
        // stem (wobbles with htip)
        cv.save(); cv.rotate((ps.htip * 12).toFloat(), 0f, -0.86f * R); path.rewind(); path.moveTo(-0.08f * R, -0.82f * R); path.lineTo(0.06f * R, -0.82f * R); path.quadTo(0.14f * R, -1.0f * R, 0.12f * R, -1.12f * R); path.lineTo(0.0f, -1.12f * R); path.quadTo(-0.02f * R, -0.95f * R, -0.08f * R, -0.82f * R)
        p.shader = LinearGradient(-0.1f * R, -1.1f * R, 0.15f * R, -0.85f * R, 0xFF7FB85A.toInt(), 0xFF2E5A1E.toInt(), Shader.TileMode.CLAMP); p.alpha = (k * 255).toInt(); cv.drawPath(path, p); p.shader = null; p.alpha = 255; cv.restore()
        val Y = 0xFFFFE27A.toInt(); val O = 0xFFFF8A1A.toInt(); val gk = g * (0.85 + 0.15 * sin(t * 9) * sin(t * 5.3))
        if (ps.spiral > 0.3) { spiral(cv, -0.33f * R + ex, -0.18f * R + ey, 0.16f * R, Y, t, R, gk); spiral(cv, 0.33f * R + ex, -0.18f * R + ey, 0.16f * R, Y, t, R, gk) }
        else for ((i, s) in floatArrayOf(-1f, 1f).withIndex()) { val x = s * 0.33f * R + ex; val y = -0.16f * R + ey; val closed = i == 1 && wk > 0.5
            if (hap > 0.5 || (closed && hap <= 0.5 && i == 1)) { arcEye(cv, x, y, 0.16f * R, Y, R, gk); continue }
            val h = (0.3f * R * open.toFloat()).coerceAtLeast(0.02f * R)
            if (h < 0.05f * R) { lineEye(cv, x, y, 0.15f * R, Y, R, gk); continue }
            path2.rewind(); path2.moveTo(x - 0.17f * R, y + 0.1f * R); path2.lineTo(x, y + 0.1f * R - h); path2.lineTo(x + 0.17f * R, y + 0.1f * R); path2.close()
            glowFill(cv, path2, Y, O, x, y, 0.22f * R, gk, R) }
        // jagged mouth; laughing opens it taller
        val m = 1f + 0.5f * hap.toFloat(); path2.rewind(); path2.moveTo(-0.6f * R, 0.2f * R)
        val top = floatArrayOf(-0.42f, 0.3f, -0.28f, 0.18f, -0.14f, 0.3f, 0f, 0.2f, 0.14f, 0.3f, 0.28f, 0.18f, 0.42f, 0.3f, 0.6f, 0.2f)
        for (i in top.indices step 2) path2.lineTo(top[i] * R, top[i + 1] * R)
        val bot = floatArrayOf(0.45f, 0.48f, 0.22f, 0.58f, 0.1f, 0.48f, -0.06f, 0.6f, -0.26f, 0.56f, -0.45f, 0.48f)
        for (i in bot.indices step 2) path2.lineTo(bot[i] * R, (0.2f + (bot[i + 1] - 0.2f) * m) * R); path2.close()
        glowFill(cv, path2, Y, O, 0f, 0.38f * R, 0.55f * R, gk, R)
    }
    /** The ghost is ONE shaded body: the baked bitmap (dome + skirt, lit, AO folds, glyphs, rim) painted through the live
     *  wavy-hem silhouette with a BitmapShader, so the hem moves without becoming a separate flat band. */
    private val shM = Matrix()
    private fun ghostBody(cv: Canvas, bm: Bitmap, R: Float, t: Double, ps: Pose, k: Double) {
        val amp = 0.08f * R * (1f + 0.6f * ps.hhem.toFloat()); val ph = t * (2.0 + 6 * ps.hhem)
        path.rewind(); path.addCircle(0f, -0.05f * R, R * 0.95f, Path.Direction.CW); path2.rewind(); path2.moveTo(-0.95f * R, -0.05f * R); path2.lineTo(0.95f * R, -0.05f * R)
        val n = 48; for (i in 0..n) { val u = 1.0 - i.toDouble() / n; val x = (-0.95 + 1.9 * u).toFloat() * R
            // scalloped hem: 4 lobes, points at the folds, a gentle travelling wave
            val lobe = abs(sin(u * 4 * PI)).toFloat(); path2.lineTo(x, 1.02f * R + 0.14f * R * lobe + (sin(u * 2 * PI * 1.5 + ph) * amp).toFloat()) }
        path2.close(); path.op(path2, Path.Op.UNION)
        val S = 3.0f * R; val sc = S / bm.width; shM.setScale(sc, sc); shM.postTranslate(-S / 2, -S / 2)
        p.shader = BitmapShader(bm, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(shM) }; p.alpha = (k * 255).toInt(); cv.drawPath(path, p); p.shader = null; p.alpha = 255
        if (glowOn) { // soft rim along the moving hem so its edge reads as the same lit volume
            cv.save(); cv.clipPath(path); sp.strokeWidth = 0.06f * R; sp.color = a(pal(HFace.GHOST)[2], 0.45 * k); sp.maskFilter = BlurMaskFilter(px(0.05f * R), BlurMaskFilter.Blur.NORMAL); cv.drawPath(path2, sp); sp.maskFilter = null; cv.restore() }
    }
    private fun ghost(cv: Canvas, R: Float, t: Double, ps: Pose, open: Double, hap: Double, wk: Double, ex: Float, ey: Float, g: Double, k: Double) {
        val D = 0xFF141824.toInt(); val B = 0xFF6FB4FF.toInt()
        for ((i, s) in floatArrayOf(-1f, 1f).withIndex()) { val x = s * 0.3f * R + ex; val y = -0.22f * R + ey
            if (ps.spiral > 0.3) { spiral(cv, x, y, 0.14f * R, D, t, R, k); continue }
            if (hap > 0.5 || (i == 1 && wk > 0.5)) { arcEye(cv, x, y, 0.14f * R, D, R, k); continue }
            val ry = (0.24f * R * open.toFloat()).coerceAtLeast(0.015f * R); if (ry < 0.04f * R) { lineEye(cv, x, y, 0.16f * R, D, R, k); continue }
            path2.rewind(); path2.addOval(x - 0.18f * R, y - ry, x + 0.18f * R, y + ry, Path.Direction.CW); glowFill(cv, path2, mix(D, B, 0.35 * g.coerceAtMost(1.0)), D, x, y - ry * 0.3f, 0.22f * R, 0.4 * k, R)
            catchlight(cv, x, y, 0.18f * R, ry, k) }
        // 0.1.29: no mouth; the ghost speaks with its eyes
        blush(cv, 0.52f * R, 0.06f * R, R, k, 0.6)
    }
    private fun catEars(cv: Canvas, R: Float, ps: Pose, k: Double) { val pl = pal(HFace.CAT)
        for (s in floatArrayOf(-1f, 1f)) { cv.save(); if (s > 0) cv.rotate((-14 * ps.hear).toFloat(), 0.6f * R, -0.55f * R)
            path.rewind(); path.moveTo(s * 0.88f * R, -0.25f * R); path.lineTo(s * 0.8f * R, -1.18f * R); path.lineTo(s * 0.2f * R, -0.8f * R); path.close()
            p.shader = LinearGradient(s * 0.2f * R, -1.1f * R, s * 0.8f * R, -0.4f * R, pl[0], pl[2], Shader.TileMode.CLAMP); p.alpha = (k * 255).toInt(); cv.drawPath(path, p); p.shader = null
            path.rewind(); path.moveTo(s * 0.74f * R, -0.45f * R); path.lineTo(s * 0.72f * R, -0.98f * R); path.lineTo(s * 0.38f * R, -0.78f * R); path.close(); p.color = a(0xFFB0507A.toInt(), 0.75 * k); cv.drawPath(path, p); p.alpha = 255; cv.restore() } }
    private fun catTail(cv: Canvas, R: Float, t: Double, ps: Pose, k: Double) { val sw = (0.25 * sin(t * 0.9) + ps.htail).toFloat()
        path.rewind(); path.moveTo(0.55f * R, 0.75f * R); path.cubicTo(1.25f * R, 0.85f * R, 1.2f * R, (0.1f - 0.5f * sw) * R, (1.05f + 0.25f * sw) * R, (-0.35f - 0.3f * sw) * R)
        sp.strokeWidth = 0.16f * R; sp.color = a(0xFF1A1526.toInt(), k); cv.drawPath(path, sp); if (glowOn) { sp.strokeWidth = 0.03f * R; sp.color = a(0xFFAE84FF.toInt(), 0.5 * k); cv.drawPath(path, sp) } }
    private fun cat(cv: Canvas, R: Float, ps: Pose, open: Double, hap: Double, wk: Double, ex: Float, ey: Float, g: Double, k: Double) {
        val G = 0xFFC8FF5A.toInt(); val G2 = 0xFF5FAA10.toInt()
        for ((i, s) in floatArrayOf(-1f, 1f).withIndex()) { val x = s * 0.36f * R + ex * 0.5f; val y = -0.12f * R + ey * 0.5f
            if (ps.spiral > 0.3) { spiral(cv, x, y, 0.16f * R, G, 0.0, R, g); continue }
            if (hap > 0.5 || (i == 1 && wk > 0.5)) { arcEye(cv, x, y, 0.17f * R, G, R, g); continue }
            val ry = (0.2f * R * open.toFloat()).coerceAtLeast(0.01f * R); if (ry < 0.035f * R) { lineEye(cv, x, y, 0.19f * R, G, R, g); continue }
            path2.rewind(); path2.addOval(x - 0.21f * R, y - ry, x + 0.21f * R, y + ry, Path.Direction.CW)
            glowFill(cv, path2, G, G2, x, y, 0.24f * R, g, R)
            val pw = (0.07f * R * (0.7f + 0.6f * (1.2f - open.toFloat().coerceIn(0f, 1.2f)))); val px = x + ex * 0.6f; p.color = a(0xFF050A02.toInt(), 0.95)
            rf.set(px - pw, y - ry * 0.8f, px + pw, y + ry * 0.8f); cv.drawOval(rf, p); catchlight(cv, x, y, 0.2f * R, ry, 1.0) }
        blush(cv, 0.56f * R, 0.16f * R, R, k, 0.55)
        path.rewind(); path.moveTo(-0.07f * R, 0.18f * R); path.lineTo(0.07f * R, 0.18f * R); path.lineTo(0f, 0.27f * R); path.close(); p.color = a(0xFFE681A8.toInt(), k); cv.drawPath(path, p)
        sp.strokeWidth = 0.018f * R; sp.color = a(0xFF9C92BE.toInt(), 0.8 * k)
        for (s in floatArrayOf(-1f, 1f)) for (j in -1..1) cv.drawLine(s * 0.22f * R, (0.27f + j * 0.05f) * R, s * 0.78f * R, (0.22f + j * 0.12f) * R, sp)
        sp.strokeWidth = 0.025f * R; rf.set(-0.16f * R, 0.2f * R, 0f, 0.36f * R); cv.drawArc(rf, 0f, 180f, false, sp); rf.set(0f, 0.2f * R, 0.16f * R, 0.36f * R); cv.drawArc(rf, 0f, 180f, false, sp)
    }
    private fun hat(cv: Canvas, R: Float, t: Double, ps: Pose, open: Double, hap: Double, wk: Double, ex: Float, ey: Float, g: Double, k: Double) {
        val pl = pal(HFace.HAT)
        // crooked tip = the hat's head / eyebrow: htip -1 droops, +1 perks; sad/asleep droops a little
        val tip = (ps.htip + 0.45 * hap - 0.3 * (1 - open).coerceIn(0.0, 1.0)).coerceIn(-1.0, 1.0); val ang = Math.toRadians(35 - 55 * tip)
        val bx = 0.01f * R; val by = -0.6f * R; val jx = bx + 0.06f * R; val jy = by - 0.36f * R
        val tx = jx + (sin(ang + 0.7) * 0.45 * R).toFloat(); val ty = jy - (cos(ang + 0.7) * 0.45 * R).toFloat()
        path.rewind(); path.moveTo(-0.29f * R, -0.58f * R); path.lineTo(0.31f * R, -0.58f * R); path.quadTo(jx + 0.12f * R, jy + 0.05f * R, tx, ty); path.quadTo(jx - 0.06f * R, jy, -0.29f * R, -0.58f * R); path.close()
        p.shader = LinearGradient(-0.3f * R, jy, 0.4f * R, -0.5f * R, pl[0], pl[2], Shader.TileMode.CLAMP); p.alpha = (k * 255).toInt(); cv.drawPath(path, p); p.shader = null; p.alpha = 255
        // band + buckle (glossy)
        path.rewind(); path.moveTo(-0.7f * R, 0.58f * R); path.lineTo(0.7f * R, 0.58f * R); path.lineTo(0.62f * R, 0.36f * R); path.lineTo(-0.62f * R, 0.36f * R); path.close()
        p.shader = LinearGradient(0f, 0.36f * R, 0f, 0.58f * R, 0xFFFFB04A.toInt(), 0xFFA04A08.toInt(), Shader.TileMode.CLAMP); p.alpha = (k * 255).toInt(); cv.drawPath(path, p); p.shader = null; p.alpha = 255
        sp.strokeWidth = 0.05f * R; sp.color = a(0xFFFFD25A.toInt(), k); rf.set(-0.13f * R, 0.37f * R, 0.13f * R, 0.58f * R); cv.drawRect(rf, sp)
        val G = 0xFFC8FF8A.toInt(); val G2 = 0xFF4E9A2A.toInt(); val sq = 1f - 0.55f * ps.lid.toFloat().coerceIn(0f, 1f)
        for ((i, s) in floatArrayOf(-1f, 1f).withIndex()) { val x = s * 0.24f * R + ex * 0.6f; val y = 0.0f * R + ey * 0.4f
            if (ps.spiral > 0.3) { spiral(cv, x, y, 0.12f * R, G, t, R, g); continue }
            if (hap > 0.5 || (i == 1 && wk > 0.5)) { arcEye(cv, x, y, 0.13f * R, G, R, g); continue }
            val ry = (0.17f * R * open.toFloat() * sq).coerceAtLeast(0.01f * R); if (ry < 0.035f * R) { lineEye(cv, x, y, 0.14f * R, G, R, g); continue }
            path2.rewind(); path2.addOval(x - 0.15f * R, y - ry, x + 0.15f * R, y + ry, Path.Direction.CW); glowFill(cv, path2, G, G2, x, y, 0.18f * R, g, R)
            p.color = a(0xFF0A1405.toInt(), 0.95); rf.set(x + ex * 0.4f - 0.07f * R, y - ry * 0.6f, x + ex * 0.4f + 0.07f * R, y + ry * 0.7f); cv.drawOval(rf, p); catchlight(cv, x, y, 0.15f * R, ry, 1.0) }
        blush(cv, 0.42f * R, 0.2f * R, R, k, 0.5)
    }
}

/** Per-avatar idle micros (4-6 s beats) and big antics (Scheduler activities). Pose math only; props draw through [Art]. */
object HolidayAntics {
    // pumpkin
    const val FLICKER = "m.hol.flicker"; const val LAUGH = "m.hol.laugh"; const val STEM = "m.hol.stem"; const val EMBERS = "hol.act.embers"
    // ghost
    const val HEM = "m.hol.hem"; const val FADE = "m.hol.fade"; const val BOO = "hol.act.boo"
    // cat
    const val SLOWBLINK = "m.hol.slowblink"; const val EAR = "m.hol.ear"; const val TAIL = "m.hol.tail"; const val POUNCE = "hol.act.pounce"
    // witch hat
    const val TIPFLOP = "m.hol.tipflop"; const val HOP = "m.hol.hop"; const val SQUINT = "m.hol.squint"; const val SPARKLE = "m.hol.sparkle"; const val SPELL = "hol.act.spell"; const val BRIMSPIN = "hol.act.brimspin"

    fun micros(f: HFace) = when (f) {
        HFace.PUMPKIN -> listOf(FLICKER, LAUGH, STEM); HFace.GHOST -> listOf(HEM, FADE); HFace.CAT -> listOf(SLOWBLINK, EAR, TAIL)
        HFace.HAT -> listOf(TIPFLOP, HOP, SQUINT, SPARKLE) }
    fun acts(f: HFace) = when (f) { HFace.PUMPKIN -> listOf(EMBERS); HFace.GHOST -> listOf(BOO); HFace.CAT -> listOf(POUNCE); HFace.HAT -> listOf(SPELL, BRIMSPIN) }

    private fun sparkles(a: Art, s: Double, p: Pose, g: Geo, n: Int, x0: Double, y0: Double, spread: Double, life: Double) {
        val r = Rng(31); for (i in 0 until n) { val d = i * 0.12; val k = Ez.bump(s - d, 0.5); val x = x0 + (r.next() - 0.5) * spread * g.k; val y = y0 + (r.next() - 0.5) * spread * 0.6 * g.k
            a.glyph("+", x, y, 14 * g.k, 0xFFFFD35A.toInt(), k * min(1.0, (life - s) * 4), 8.0) } }
    private fun tipX(p: Pose, g: Geo) = g.ox(p) + 0.45 * g.or(p) * cos(Math.toRadians(35 - 55 * p.htip) - PI / 2 + 0.7).coerceAtLeast(-1.0)
    private fun tipY(p: Pose, g: Geo) = g.oy(p) - 1.25 * g.or(p)

    val list: List<Antic> by lazy { listOf(
        Antic(FLICKER, 0.6, AK.QUIRK, { s, p, _ -> val dip = if (Ez.hash(floor(s * 22), 9.0, 1.0) > 0.55) 0.7 else 1.0; p.glow *= if (s < 0.5) dip else 1.0; p.glow += 0.35 * Ez.bump(s - 0.12, 0.12) }),
        Antic(LAUGH, 0.9, AK.QUIRK, { s, p, _ -> val sq = Ez.bump(s - 0.05, 0.3); val rb = Ez.bump(s - 0.3, 0.3); p.sx += 0.06 * sq - 0.04 * rb; p.sy -= 0.08 * sq - 0.05 * rb; p.glow += 0.6 * Ez.bump(s, 0.9); p.happy = max(p.happy, Ez.win(s, 0.05, 0.85, 0.08, 0.15)) }),
        Antic(STEM, 0.5, AK.QUIRK, { s, p, _ -> p.htip = exp(-6 * s) * sin(s * 24) * 0.7 }),
        Antic(EMBERS, 1.8, AK.ACT, { s, p, _ -> p.glow += 0.8 * Ez.sst(0.0, 0.6, s) * (1 - Ez.sst(1.3, 1.8, s)); p.flash = 0.3 * Ez.bump(s - 0.5, 0.4); p.happy = max(p.happy, Ez.win(s, 0.4, 1.5, 0.1, 0.2)) },
            over = { a, s, p, g -> a.burst(g, g.ox(p), g.oy(p) + 0.35 * g.or(p), s - 0.55, 20, 70.0, 1.1, 41, 1.6, -PI / 2, 40.0) }),
        Antic(HEM, 0.8, AK.QUIRK, { s, p, _ -> p.hhem = Ez.bump(s, 0.8) }),
        Antic(FADE, 1.4, AK.QUIRK, { s, p, _ -> p.bodyK = 1 - 0.85 * Ez.win(s, 0.0, 1.4, 0.8, 0.4) }),
        Antic(BOO, 1.2, AK.ACT, { s, p, _ -> p.dy += 10 * Ez.bump(s, 0.3) - 18 * Ez.bump(s - 0.3, 0.5); p.scale += 0.15 * Ez.bump(s - 0.3, 0.5); p.open += 0.4 * Ez.bump(s - 0.3, 0.6); p.hhem = Ez.bump(s - 0.2, 0.9) },
            over = { a, s, p, g -> a.glyph("BOO!", g.ox(p) + 1.1 * g.or(p), g.oy(p) - 0.9 * g.or(p), 22 * g.k, 0xFFF6F8FF.toInt(), Ez.bump(s - 0.35, 0.8), 10.0) }),
        Antic(SLOWBLINK, 0.9, AK.QUIRK, { s, p, _ -> p.blink = Ez.sst(0.0, 0.2, s) * (1 - Ez.sst(0.55, 0.9, s)) }),
        Antic(EAR, 0.4, AK.QUIRK, { s, p, _ -> p.hear = Ez.bump(s, 0.12) + 0.7 * Ez.bump(s - 0.2, 0.12) }),
        Antic(TAIL, 0.7, AK.QUIRK, { s, p, _ -> p.htail = 0.8 * Ez.bump(s, 0.7) }),
        Antic(POUNCE, 1.3, AK.ACT, { s, p, _ -> val c = Ez.win(s, 0.0, 0.32, 0.12, 0.05); val up = Ez.bump(s - 0.3, 0.45); val land = Ez.bump(s - 0.75, 0.3)
            p.sx += 0.1 * c - 0.08 * up + 0.1 * land; p.sy -= 0.18 * c - 0.1 * up + 0.14 * land; p.dy += 8 * c - 60 * up; p.open += 0.3 * c; p.htail = 0.6 * c }),
        Antic(TIPFLOP, 0.8, AK.QUIRK, { s, p, _ -> p.htip = -0.7 * Ez.sst(0.0, 0.25, s) * exp(-5 * max(0.0, s - 0.25)) * cos(max(0.0, s - 0.25) * 14) }),
        Antic(HOP, 0.7, AK.QUIRK, { s, p, _ -> val c = Ez.bump(s, 0.12); val up = Ez.bump(s - 0.12, 0.33); val land = Ez.bump(s - 0.45, 0.22); p.sx += 0.06 * c + 0.08 * land; p.sy -= 0.08 * c + 0.1 * land; p.dy -= 20 * up; p.happy = max(p.happy, up) }),
        Antic(SQUINT, 0.6, AK.QUIRK, { s, p, _ -> val w = Ez.bump(s, 0.6); p.roll -= 9 * w; p.lid = max(p.lid, 0.55 * w) }),
        Antic(SPARKLE, 1.0, AK.QUIRK, { s, p, _ -> p.htip += 0.3 * Ez.bump(s, 1.0) }, over = { a, s, p, g -> sparkles(a, s, p, g, 5, tipX(p, g), tipY(p, g), 50.0, 1.0) }),
        Antic(SPELL, 1.6, AK.ACT, { s, p, _ -> p.htip = 0.6 * Ez.win(s, 0.0, 1.3, 0.2, 0.2) - 0.6 * Ez.bump(s - 1.3, 0.3); p.flash = 0.4 * Ez.bump(s - 1.1, 0.2); p.glow += 0.5 * Ez.bump(s - 1.0, 0.4); p.happy = max(p.happy, Ez.win(s, 1.1, 1.6, 0.05, 0.1)) },
            over = { a, s, p, g -> val k = Ez.sst(0.2, 1.3, s); val fade = 1 - Ez.sst(1.3, 1.6, s); val x0 = tipX(p, g); val y0 = tipY(p, g)
                for (i in 0 until 40) { val u = i / 40.0; if (u > k) break; val ang = u * 4 * PI + s * 3; val rr = (10 + u * 80) * g.k
                    a.glyph(if (i % 2 == 0) "0" else "1", x0 + cos(ang) * rr, y0 + sin(ang) * rr * 0.55 + u * 30 * g.k, 11 * g.k, if (i % 3 == 0) 0xFFFF8C1E.toInt() else 0xFFB57EF0.toInt(), 0.9 * fade, 6.0) } }),
        Antic(BRIMSPIN, 1.0, AK.ACT, { s, p, _ -> p.sx *= max(0.06, abs(cos(Ez.sst(0.0, 1.0, s) * 2 * PI))); p.htip = -0.3 * sin(s * 2 * PI) }),
    ) }
}

/** Holiday voice: shared spooky lines + the current avatar's own 12 (no kitchen or study content). Sentence case, decoded like Matrix. */
object HolidayPersona : Persona {
    override val id = "holiday"; override val upper = false; override val reveal = Reveal.GLYPH_DECODE; override val reactionScale = 0.9
    override val quirks = listOf(Antics.BOB); override val quirkMinS = 40.0; override val quirkSpanS = 50.0
    override val micros: List<String> get() = listOf(Micros.GLANCE_L, Micros.GLANCE_R, Micros.LOOKUP, Micros.DBLINK, Micros.TILT) + HolidayAntics.micros(HolidayCycle.face) + HolidayAntics.micros(HolidayCycle.face)
    override val badges = emptyMap<Cat, String>()
    override val pool: List<PoolItem> get() = HolidayAntics.acts(HolidayCycle.face).map { PoolItem(it, Kind.ACT, 1.5, 60, 0.0, 24.0) } +
        listOf(PoolItem(Antics.TIDY, Kind.ACT, 1.0, 240, 0.0, 24.0), PoolItem(Antics.RAINCATCH, Kind.ACT, 1.0, 120, 0.0, 24.0)) +
        PoolIds.SHARED_BUBBLES + listOf(PoolItem(PoolIds.JOKE, Kind.BUB, 2.0, 240, 0.0, 24.0), PoolItem(PoolIds.MUSING, Kind.BUB, 2.0, 180, 0.0, 24.0))

    /** Each avatar's 12: greetings, jokes, tips -> greeting / musing; reminders -> break / clean. */
    val faceLines: Map<HFace, Map<Cat, List<String>>> = mapOf(
        HFace.PUMPKIN to mapOf(Cat.GREETING to listOf("Evening! I'm all lit up.", "Gourd to see you!", "I'm not two-faced, just carved."),
            Cat.MUSING to listOf("Pumpkin spice and everything nice.", "Squash those bugs before bed.", "Patch Tuesday? Carve out time.", "Strong passwords: hard to carve.", "Who carved this smile? Me.", "Trick or treat? Both, please."),
            Cat.BREAK to listOf("Drink some water, gourd friend.", "Keep your candle burning bright."), Cat.CLEAN to listOf("Clean your room, it's spooky.")),
        HFace.GHOST to mapOf(Cat.GREETING to listOf("Boo! Missed me?", "I'm here in spirit.", "Floating by to say hi."),
            Cat.MUSING to listOf("I'm see-through, like good logs.", "Ghosts love sheets. You? Spreadsheets.", "Boo-lean logic: true or boo.", "Don't ghost your friends.", "Phish smell? Don't click.", "MFA keeps the spirits out.", "Lights flickering? That's me."),
            Cat.BREAK to listOf("Water break. Even ghosts hydrate."), Cat.CLEAN to listOf("Tidy up. Haunted rooms are messy.")),
        HFace.CAT to mapOf(Cat.GREETING to listOf("Mrrow. You're back.", "Purr. Hi.", "*slow blink*"),
            Cat.MUSING to listOf("I always land on my feet.", "Purr-fect timing.", "Nine lives, one password? No.", "Curiosity is fine. Clicking isn't.", "Encrypt it. Cats hide things too.", "Your chair is mine now."),
            Cat.BREAK to listOf("Nap now. Everything else later.", "Drink water. I'm judging.", "Feed me. Then yourself.")),
        HFace.HAT to mapOf(Cat.GREETING to listOf("Greetings, apprentice.", "Hat's on, spells ready.", "Welcome back to the coven."),
            Cat.MUSING to listOf("Witch way to the snacks?", "I put a spell on your to-do list.", "Brew-tiful day, isn't it?", "A hash spell: same in, same out.", "Symmetric: one key, both ways.", "Least privilege. Even for brooms."),
            Cat.BREAK to listOf("Potion of focus: water. Drink.", "Abraca-done. Rest a bit."), Cat.CLEAN to listOf("Sweep your room. Brooms optional."))
    )
    private val base: Map<Cat, List<String>> = mapOf(
        Cat.GREETING to listOf("Welcome back to the haunt, {name}.", "Evening, {name}. The bats saved you a seat.", "The candles lit themselves. Hi.", "Rise and shine. Or rise and haunt.",
            "You're back! The cobwebs missed you.", "Good to see you, {name}.", "Hello from the pumpkin patch.", "The moon is up. So are you.", "A spooky good day starts now.", "Knock knock. It's the haunt."),
        Cat.BREAK to listOf("Step away for five. The ghosts will wait.", "Stretch like a cat. Then a bat.", "Eyes off the screen for a minute.", "Sip some water. Even witches do.",
            "Stand up and haunt the hallway.", "A short break keeps the spell strong.", "Breathe in. Boo out.", "Look out a window. Moon check.", "Shoulders down. Nothing's chasing you.", "Five minutes off. I'll guard the crypt."),
        Cat.CLEAN to listOf("This room is haunted by laundry.", "Cobweb patrol: ten minutes?", "Sweep the floor. Brooms are for flying later.", "The dust bunnies are forming a coven.",
            "Clean desk, clear spirits.", "One trash run scares off the mess.", "Bed made? Even vampires make their coffin.", "Tidy up before the ghosts do.", "Dishes are piling up like tombstones.", "Ten minutes. The room will thank you."),
        Cat.CAR to listOf("Your carriage needs a tune-up.", "Check the tires before the full moon.", "Oil change? The broom can't do it all.", "Wipers ready for spooky rain?",
            "Headlights bright enough for a dark road?", "Even hearses need maintenance.", "A quick fluids check keeps it alive.", "Weekend quest: book the car service.", "Vacuum the crumbs. Bats like crumbs.", "Small fix now, no horror later."),
        Cat.NOTIFY to listOf("A message crept in. Read it?", "Someone sent a raven. Open it?", "New message from the other side.", "A note slid under the crypt door.",
            "Tap and I'll read the scroll.", "A spirit is reaching out. Listen?", "One new message. No tricks.", "The bats delivered mail.", "Fresh message. Want to hear it?", "A whisper arrived. Read it?"),
        Cat.MUSING to listOf("The bats are doing laps again.", "I counted the candles. Thirteen.", "Somewhere a door creaks. Lovely.", "The moon looks extra round tonight.",
            "A cobweb is just lace with ambition.", "I heard a howl. Probably the wind.", "Fog rolled in. Cozy.", "The tombstones are very quiet neighbors."),
        Cat.REACT to listOf("Eek!", "That tickled.", "Boo to you too.", "Spooky!", "Again! Again!", "Inbox zero. Not even a ghost.", "Hehe.", "You found me.", "Spellbinding.", "Fang-tastic."))
    override val lines: Map<Cat, List<String>> get() { val f = faceLines[HolidayCycle.face] ?: emptyMap()
        return base.mapValues { (c, l) -> (f[c] ?: emptyList()) + l } }
    override val jokes = listOf(
        "Why don't ghosts lie?" to "You can see right through them.", "What do you call a cleaning skeleton?" to "The grim sweeper.",
        "Why did the vampire read the paper?" to "He heard it had great circulation.", "What's a ghost's favorite dessert?" to "I scream.",
        "How do witches keep their hair in place?" to "Scare spray.", "Why are graveyards noisy?" to "Because of all the coffin.",
        "What room has no doors?" to "A mushroom. Spooky, right?", "What do you call a witch at the beach?" to "A sand-witch.",
        "Why did the skeleton skip the party?" to "He had no body to go with.", "What's a cat's favorite color?" to "Purr-ple.")
}
