package com.tyler.primelauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 0.1.23: the seven-hour weather row under the date. 0.1.26: each tile = hour, solid temperature line, unobstructed themed icon,
 * condition word; skinned per theme (Matrix terminal, Neon glow, ODST HUD). Current hour gets a brighter outline.
 * When there is nothing to show it draws one panel of the same height with a status line ("Location off" etc.).
 */
enum class TileSkin { MATRIX, NEON, ODST, HOLIDAY }

class WxTiles(ctx: Context, private var pal: Pal) : View(ctx) {
    var hours: List<WxHour> = emptyList(); private set
    var status: String? = null; private set
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG); private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val r = RectF(); private val path = Path()

    fun show(h: List<WxHour>, status: String?) { hours = h; this.status = status; contentDescription = describe(); invalidate() }
    fun setPal(p: Pal) { pal = p; invalidate() }
    fun describe(): String = status ?: hours.joinToString("; ") { "${it.label} ${it.temp}° ${word(it)}" }

    /** Huge system font (clock grows a lot): drop the word line so the ring keeps room around the orb. */
    private val compact get() = resources.configuration.fontScale >= 1.8f
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), ((if (compact) COMPACT_DP else HEIGHT_DP) * d).toInt())

    private fun a(c: Int, al: Int) = Color.argb(al, Color.red(c), Color.green(c), Color.blue(c))
    /** 0.1.26 per-theme tile skin. */
    val skin get() = skinOf(pal)
    private fun clipPath(rr: RectF, cut: Float) = path.apply { reset(); moveTo(rr.left + cut, rr.top); lineTo(rr.right, rr.top); lineTo(rr.right, rr.bottom - cut)
        lineTo(rr.right - cut, rr.bottom); lineTo(rr.left, rr.bottom); lineTo(rr.left, rr.top + cut); close() }
    private fun panel(rr: RectF, hot: Boolean) {
        val c = canvas
        when (skin) {
            TileSkin.MATRIX -> {
                fill.color = 0xE6020A04.toInt(); c.drawRect(rr, fill)
                line.strokeCap = Paint.Cap.BUTT; line.strokeWidth = 1f; line.color = 0x2600FF55
                var y = rr.top + 2 * d; while (y < rr.bottom - 1) { c.drawLine(rr.left + 1, y, rr.right - 1, y, line); y += 2 * d }
                line.strokeWidth = (if (hot) 1.5f else 1f) * d; line.color = if (hot) MX_GREEN else 0xFF1E6E37.toInt(); c.drawRect(rr, line); line.strokeCap = Paint.Cap.ROUND
            }
            TileSkin.NEON -> {
                val rad = 6 * d; fill.color = 0xD90C0816.toInt(); c.drawRoundRect(rr, rad, rad, fill)
                val glowC = if (hot) pal.p2 else pal.p
                line.strokeWidth = 1.6f * d; line.color = glowC; line.setShadowLayer(4f * d, 0f, 0f, glowC); c.drawRoundRect(rr, rad, rad, line); line.clearShadowLayer()
                line.strokeWidth = 0.6f * d; line.color = if (hot) Color.WHITE else 0xCCC8FAFF.toInt()
                r2.set(rr.left + 1.6f * d, rr.top + 1.6f * d, rr.right - 1.6f * d, rr.bottom - 1.6f * d); c.drawRoundRect(r2, rad - 1.6f * d, rad - 1.6f * d, line)
            }
            TileSkin.ODST -> {
                val cut = 6 * d; fill.color = 0xDC0E140F.toInt(); c.drawPath(clipPath(rr, cut), fill)
                line.strokeWidth = 0.8f * d; line.color = ODST_DIM; c.drawPath(clipPath(rr, cut), line)
                val bc = if (hot) ODST_WHITE else ODST_SAGE; val l = 6 * d; val i = 1.5f * d; line.strokeWidth = 1.2f * d; line.color = bc; line.strokeCap = Paint.Cap.SQUARE
                c.drawLine(rr.left + i, rr.bottom - i, rr.left + i + l, rr.bottom - i, line); c.drawLine(rr.left + i, rr.bottom - i, rr.left + i, rr.bottom - i - l, line)
                c.drawLine(rr.right - i, rr.top + i, rr.right - i - l, rr.top + i, line); c.drawLine(rr.right - i, rr.top + i, rr.right - i, rr.top + i + l, line)
                c.drawLine(rr.left + cut + 2 * d, rr.top + i, rr.left + cut + 8 * d, rr.top + i, line); c.drawLine(rr.right - cut - 8 * d, rr.bottom - i, rr.right - cut - 2 * d, rr.bottom - i, line)
                line.strokeCap = Paint.Cap.ROUND
            }
            TileSkin.HOLIDAY -> { // 0.1.27: candle-lit card, ember outline, a little cobweb in the corner
                val rad = 5 * d; fill.color = 0xE0160D0A.toInt(); c.drawRoundRect(rr, rad, rad, fill)
                val oc = if (hot) HOL_ORANGE else 0xFF8A4A1C.toInt()
                line.strokeWidth = (if (hot) 1.5f else 1f) * d; line.color = oc; if (hot) line.setShadowLayer(3f * d, 0f, 0f, HOL_ORANGE)
                c.drawRoundRect(rr, rad, rad, line); line.clearShadowLayer()
                line.strokeWidth = 0.6f * d; line.color = 0x66B57EF0; val w = 7 * d; val x0 = rr.right - 1.5f * d; val y0 = rr.top + 1.5f * d
                c.drawLine(x0, y0, x0 - w, y0 + w, line); c.drawLine(x0 - w * 0.55f, y0, x0, y0 + w * 0.55f, line); c.drawLine(x0 - w, y0, x0, y0 + w, line)
            }
        }
    }
    private lateinit var canvas: Canvas
    private val r2 = RectF()
    private fun hourColor(hot: Boolean) = when (skin) { TileSkin.MATRIX -> if (hot) MX_GREEN else pal.dim; TileSkin.NEON -> if (hot) pal.p2 else pal.p; TileSkin.ODST -> if (hot) ODST_WHITE else ODST_SAGE; TileSkin.HOLIDAY -> if (hot) HOL_ORANGE else 0xFFB98A64.toInt() }
    private fun tempColor() = when (skin) { TileSkin.MATRIX -> MX_PALE; TileSkin.NEON -> Color.WHITE; TileSkin.ODST -> ODST_WHITE; TileSkin.HOLIDAY -> 0xFFFFE2C8.toInt() }
    private fun wordColor(hr: WxHour) = when (skin) { TileSkin.MATRIX -> 0xFF5EC87E.toInt(); TileSkin.NEON -> 0xFFFF78B4.toInt(); TileSkin.ODST -> if (kind(hr) == "rain" || kind(hr) == "storm") ODST_SAGE else ODST_DIM; TileSkin.HOLIDAY -> if (kind(hr) == "rain" || kind(hr) == "storm") 0xFFB57EF0.toInt() else 0xFFE0A060.toInt() }
    private fun wordText(hr: WxHour, hot: Boolean) = when (skin) { TileSkin.MATRIX -> word(hr).uppercase() + if (hot) "_" else ""; TileSkin.ODST -> word(hr).uppercase(); TileSkin.NEON, TileSkin.HOLIDAY -> pal.up(word(hr)) }
    private fun hourText(hr: WxHour, hot: Boolean) = if (hot) pal.up("Now") else if (skin == TileSkin.MATRIX) hr.label.replace(" ", "").lowercase() else pal.up(hr.label)

    override fun onDraw(c: Canvas) {
        canvas = c; val h = height.toFloat(); val st = status
        if (st != null || hours.isEmpty()) {
            val uw = usableWidth(); r.set(1f, 1f, uw - 1f, h - 1f); panel(r, false)
            txt.typeface = pal.bold; txt.textSize = 13 * d; txt.color = pal.body
            c.drawText(pal.up(st ?: "Weather…"), uw / 2, h / 2 - 1 * d, txt)
            txt.typeface = pal.font; txt.textSize = 10 * d; txt.color = pal.dim
            if (!compact) c.drawText(pal.up("tap to retry"), uw / 2, h / 2 + 13 * d, txt); return
        }
        val n = 7; val gap = 3 * d; val usable = usableWidth(); val tw = (usable - gap * (n - 1)) / n
        val fs = min(1f, tw / (40 * d))
        lastTemp.clear(); lastIcon.clear(); lastTempSize.clear(); lastOther.clear()
        hours.take(n).forEachIndexed { i, hr ->
            val hot = i == 0; val x = i * (tw + gap); r.set(x + 0.5f, 0.5f, x + tw - 0.5f, h - 0.5f); panel(r, hot)
            val cx = x + tw / 2
            if (skin == TileSkin.MATRIX) { txt.typeface = pal.font; txt.textSize = 7 * d; txt.color = pal.dim; txt.textAlign = Paint.Align.LEFT; c.drawText(">", x + 2.5f * d, 8.5f * d, txt); txt.textAlign = Paint.Align.CENTER }
            val hs = hourText(hr, hot); txt.typeface = if (hot || skin == TileSkin.ODST) pal.bold else pal.font; txt.textSize = fit(hs, 9f * d * fs, tw - 10 * d); txt.color = hourColor(hot)
            c.drawText(hs, cx, HOUR_BASE_DP * d, txt)
            // 0.1.26: temperature on its own line under the hour, solid and fully opaque, largest text on the tile
            val ts = "${hr.temp}°"; txt.typeface = pal.bold; txt.textSize = fit(ts, (if (compact) 13f else TEMP_DP) * d * fs, tw - 4 * d); txt.color = tempColor()
            val tb = (if (compact) 29f else TEMP_BASE_DP) * d; c.drawText(ts, cx, tb, txt)
            val tW = txt.measureText(ts); val fm = txt.fontMetrics; lastTemp.add(RectF(cx - tW / 2, tb + fm.ascent * 0.75f, cx + tW / 2, tb + fm.descent))
            lastTempSize.add(txt.textSize)
            if (compact) return@forEachIndexed
            val isz = min(tw * 0.28f, ICON_DP * d * fs); val vc = ICON_CENTER_DP * d; val icy = vc + isz * 0.4f
            icon(c, cx, icy, isz, kind(hr), hr.day); lastIcon.add(RectF(cx - isz, vc - isz * 0.85f, cx + isz, vc + isz * 0.85f))
            val ws = wordText(hr, hot); txt.typeface = if (skin == TileSkin.ODST) pal.bold else pal.font; txt.textSize = fit(ws, 8f * d * fs, tw - 4 * d); txt.color = wordColor(hr)
            lastOther.add(txt.textSize); c.drawText(ws, cx, h - 5 * d, txt)
        }
    }
    /** Last-drawn temperature and icon bounds per tile (tests). */
    val lastTemp = ArrayList<RectF>(); val lastIcon = ArrayList<RectF>(); val lastTempSize = ArrayList<Float>(); val lastOther = ArrayList<Float>()

    private fun fit(s: String, size: Float, maxW: Float): Float { txt.textSize = size; val m = txt.measureText(s); return if (m > maxW && m > 0) size * maxW / m else size }
    private val loc = IntArray(2)
    /** Width the row may use: its own width, clamped so it never runs past the screen's right edge (right margin mirrors left). */
    fun usableWidth(): Float { getLocationOnScreen(loc); val sw = resources.displayMetrics.widthPixels
        val left = loc[0].coerceAtLeast(0); return min(width.toFloat(), (sw - 2 * left - loc[0].coerceAtMost(0)).toFloat().coerceAtLeast(1f)) }
    /** Tile rects in this view's coordinates (for tests). */
    fun tileRects(): List<RectF> { val n = 7; val gap = 3 * d; val tw = (usableWidth() - gap * (n - 1)) / n; return (0 until n).map { i -> val x = i * (tw + gap); RectF(x, 0f, x + tw, height.toFloat()) } }

    private fun icon(c: Canvas, cx: Float, cy: Float, s: Float, kind: String, day: Boolean) {
        val (main, acc) = when (skin) { TileSkin.MATRIX -> MX_GREEN to 0xFF1FBF55.toInt(); TileSkin.NEON -> pal.p to pal.p2; TileSkin.ODST -> 0xFFC9D8C6.toInt() to ODST_SAGE; TileSkin.HOLIDAY -> HOL_ORANGE to 0xFFB57EF0.toInt() }
        line.strokeWidth = 1.6f * d; line.color = main
        if (skin == TileSkin.NEON) line.setShadowLayer(3f * d, 0f, 0f, if (kind == "rain" || kind == "storm") acc else main)
        when (kind) {
            "sun" -> if (day) { c.drawCircle(cx, cy - s * 0.35f, s * 0.45f, line)
                    for (k in 0 until 8) { val an = k * Math.PI / 4; val r0 = s * 0.7f; val r1 = s * 0.95f
                        c.drawLine(cx + (r0 * cos(an)).toFloat(), cy - s * 0.35f + (r0 * sin(an)).toFloat(), cx + (r1 * cos(an)).toFloat(), cy - s * 0.35f + (r1 * sin(an)).toFloat(), line) } }
                else { path.reset(); path.addCircle(cx, cy - s * 0.35f, s * 0.6f, Path.Direction.CW); c.drawPath(path, line); line.clearShadowLayer(); fill.color = a(pal.s1, 0xFF); c.drawCircle(cx + s * 0.3f, cy - s * 0.55f, s * 0.5f, fill) }
            "fog" -> for (k in 0 until 3) { val y = cy - s * 0.9f + k * s * 0.45f; c.drawLine(cx - s, y, cx + s, y, line) }
            else -> { line.color = if (kind == "rain" || kind == "storm") acc else main
                val y = cy - s * 0.35f; c.drawPath(cloud(cx, y, s), line)
                if (kind == "storm") { line.color = pal.hi; c.drawLine(cx + s * 0.1f, y + s * 0.5f, cx - s * 0.2f, y + s * 0.95f, line); c.drawLine(cx - s * 0.2f, y + s * 0.95f, cx + s * 0.15f, y + s * 0.95f, line) }
            }
        }
        line.clearShadowLayer()
    }
    /** 0.1.26: one clean outline (three puffs + flat base unioned), no filled bar. */
    private val cp = Path(); private val tmp = Path()
    private fun cloud(cx: Float, y: Float, s: Float): Path {
        cp.reset(); cp.addCircle(cx - s * 0.5f, y + s * 0.1f, s * 0.38f, Path.Direction.CW)
        tmp.reset(); tmp.addCircle(cx + s * 0.05f, y - s * 0.25f, s * 0.5f, Path.Direction.CW); cp.op(tmp, Path.Op.UNION)
        tmp.reset(); tmp.addCircle(cx + s * 0.55f, y + s * 0.12f, s * 0.36f, Path.Direction.CW); cp.op(tmp, Path.Op.UNION)
        tmp.reset(); tmp.addRoundRect(RectF(cx - s * 0.85f, y + s * 0.05f, cx + s * 0.9f, y + s * 0.48f), s * 0.2f, s * 0.2f, Path.Direction.CW); cp.op(tmp, Path.Op.UNION)
        return cp
    }

    companion object {
        const val HEIGHT_DP = 64f; const val COMPACT_DP = 36f
        // 0.1.26 layout (dp from tile top): hour, temperature line, icon, condition word
        const val HOUR_BASE_DP = 11f; const val TEMP_DP = 14f; const val TEMP_BASE_DP = 27f; const val ICON_DP = 10f; const val ICON_CENTER_DP = 41f
        const val MX_GREEN = 0xFF3DFF7A.toInt(); const val MX_PALE = 0xFFE2FFE9.toInt()
        const val ODST_SAGE = 0xFF9DB89A.toInt(); const val ODST_WHITE = 0xFFF2F5F0.toInt(); const val ODST_DIM = 0xFF6F8370.toInt(); const val HOL_ORANGE = 0xFFFF8C1E.toInt()
        fun skinOf(p: Pal) = if (p.holiday) TileSkin.HOLIDAY else if (p.odst) TileSkin.ODST else if (p.neon) TileSkin.NEON else TileSkin.MATRIX
        /** Visual center of the drawn icon (icons are drawn above their anchor by ~0.35-0.45 of size). */
        fun iconCenterY(icy: Float, s: Float) = icy - s * 0.4f
        fun kind(h: WxHour) = Logic.cond(h.code).second
        fun word(h: WxHour) = when (kind(h)) { "sun" -> if (h.day) "Sunny" else "Clear"; "rain" -> "Rainy"; "snow" -> "Snowy"; "storm" -> "Storm"; "fog" -> "Foggy"; else -> "Cloudy" }
    }
}
