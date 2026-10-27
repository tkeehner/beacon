package com.tyler.primelauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.core.graphics.PathParser
import org.json.JSONObject
import java.util.Calendar

/**
 * Icon pack + Abstract motifs, drawn as vectors (assets/icons.json).
 * The public build ships an EMPTY "pack" (no brand-shaped icons) and only original Abstract motifs; PACK falls back to Letters.
 */
class IconPaths(val s: Path, val a: Path?, val f: Path?, val w: Float)

enum class IconStyle { PACK, ABSTRACT, LETTERS }

object IconArt {
    private var pack: Map<String, IconPaths> = emptyMap(); private var abstract: Map<String, IconPaths> = emptyMap(); private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        val j = JSONObject(ctx.assets.open("icons.json").bufferedReader().readText())
        pack = parse(j.getJSONObject("pack")); abstract = parse(j.getJSONObject("abstract")); loaded = true
    }
    private fun path(d: String): Path? = if (d.isBlank()) null else PathParser.createPathFromPathData(d)
    private fun parse(o: JSONObject): Map<String, IconPaths> = o.keys().asSequence().associateWith { k ->
        val v = o.getJSONObject(k); val a = v.optString("a")
        IconPaths(path(v.getString("s"))!!, if (a == "@DATE") path(IconMath.dateDigits(Calendar.getInstance().get(Calendar.DAY_OF_MONTH))) else path(a), path(v.optString("f")), v.optDouble("w", 1.7).toFloat())
    }
    fun has(pkg: String) = pack.containsKey(pkg)
    fun packKeys(): Set<String> = pack.keys
    fun paths(pkg: String, style: IconStyle): IconPaths? = when (style) { IconStyle.PACK -> pack[pkg]; IconStyle.ABSTRACT -> abstract[pkg]; IconStyle.LETTERS -> null }

    private val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Draws an icon in its own 24x24 units (caller scales). Matrix: solid fills; Neon: outlines only. */
    fun strokeIcon(cv: Canvas, ic: IconPaths, ink: Int, accent: Int, glow: Boolean, neon: Boolean, alpha: Float = 1f, strokeMul: Float = 1f) {
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        fun stroke(p: Path, c: Int, w: Float) { sp.strokeWidth = w * strokeMul; sp.color = c; sp.alpha = a
            if (glow) { sp.setShadowLayer(if (neon) 2.6f else 1.8f, 0f, 0f, c) } else sp.clearShadowLayer(); cv.drawPath(p, sp) }
        stroke(ic.s, ink, ic.w); ic.a?.let { stroke(it, accent, ic.w) }
        ic.f?.let { if (neon) stroke(it, accent, 1.3f) else { fp.color = accent; fp.alpha = a; cv.drawPath(it, fp) } }
        sp.clearShadowLayer()
    }

    /** App tile bitmap (drawer, search, bubbles, tray). size in px. */
    fun tile(pkg: String, label: String, style: IconStyle, neon: Boolean, sizePx: Int, density: Float): Bitmap {
        val b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888); val c = Canvas(b); val s = sizePx.toFloat(); val d = density
        val p = Paint(Paint.ANTI_ALIAS_FLAG); val body = if (neon) 0xFF3EE8F0.toInt() else 0xFF3DFF7A.toInt(); val acc = if (neon) 0xFFFF3D81.toInt() else 0xFFE2FFE9.toInt()
        val shape = Path(); val inset = 1.2f * d
        if (neon) { val k = s * 0.18f; shape.moveTo(inset + k, inset); shape.lineTo(s - inset, inset); shape.lineTo(s - inset, s - inset - k); shape.lineTo(s - inset - k, s - inset); shape.lineTo(inset, s - inset); shape.lineTo(inset, inset + k); shape.close() }
        else shape.addRoundRect(inset, inset, s - inset, s - inset, s * 0.24f, s * 0.24f, Path.Direction.CW)
        p.color = if (neon) 0xFF100E17.toInt() else 0xFF0B0F0C.toInt(); c.drawPath(shape, p)
        val ic = paths(pkg, style)
        if (!neon && style == IconStyle.PACK && ic != null) { c.save(); c.clipPath(shape); val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2B00FF41; typeface = Typeface.MONOSPACE; textSize = s * 0.11f }
            var y = s * 0.13f; var row = 0; while (y < s + 4) { c.drawText(if (row % 2 == 0) "10 0 1 01" else "0 1 10 0", -row * s * 0.05f, y, tp); y += s * 0.13f; row++ }; c.restore() }
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f * d; p.color = if (neon) Color.argb(115, 62, 232, 240) else Color.argb(90, 61, 255, 122); c.drawPath(shape, p)
        if (ic != null) { val k = s * 0.6f / 24f; c.save(); c.translate(s / 2 - 12 * k, s / 2 - 12 * k); c.scale(k, k)
            strokeIcon(c, ic, body, acc, glow = true, neon = neon, strokeMul = if (neon) 0.9f else 1f); c.restore() }
        else { val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = body; textAlign = Paint.Align.CENTER; textSize = s * 0.4f; typeface = if (neon) Typeface.create("sans-serif-condensed", Typeface.BOLD) else Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setShadowLayer(3f * d, 0f, 0f, body) }
            c.drawText(IconMath.letter(label), s / 2, s / 2 - (tp.descent() + tp.ascent()) / 2, tp) }
        if (style != IconStyle.LETTERS) { val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (neon) acc else body; alpha = if (neon) 130 else 100; textSize = s * 0.12f; typeface = Typeface.MONOSPACE }
            c.drawText(IconMath.codeTag(pkg, neon), s * 0.09f, s * 0.93f, tp) }
        return b
    }
    val blur = BlurMaskFilter(3f, BlurMaskFilter.Blur.NORMAL)
}

/** Pure helpers (unit-tested). */
object IconMath {
    fun letter(label: String): String = (label.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '?').toString()
    fun codeTag(pkg: String, neon: Boolean): String { var h = 0L; for (ch in pkg) h = (h * 31 + ch.code) and 0xffffffffL; return if (neon) "%02X".format(h and 0xff) else (h and 3).toString(2).padStart(2, '0') }
    private fun n(v: Double): String { val r = Math.round(v * 100) / 100.0; return if (r == Math.floor(r)) r.toLong().toString() else r.toString() }
    /** 7-segment style date digits for the Calendar icon (same table as iconpack.ts). */
    fun dateDigits(day: Int): String {
        val s = day.toString(); val x0 = if (s.length < 2) 10.5 else 8.2; val y = 11.8; val gap = 1.6
        return s.mapIndexed { i, ch -> val X = x0 + i * (3 + gap); val Xs = n(X); val Y = n(y); val X3 = n(X + 3); val Ym = n(y + 2.5)
            when (ch) { '0' -> "M$Xs ${Y}h3v5h-3z"; '1' -> "M${n(X + 0.5)} ${n(y + 1)}l1-1v5"; '2' -> "M$Xs ${Y}h3v2.5h-3v2.5h3"; '3' -> "M$Xs ${Y}h3v5h-3M$Xs ${Ym}h3"
                '4' -> "M$Xs ${Y}v2.5h3M$X3 ${Y}v5"; '5' -> "M$X3 ${Y}h-3v2.5h3v2.5h-3"; '6' -> "M$X3 ${Y}h-3v5h3v-2.5h-3"; '7' -> "M$Xs ${Y}h3v5"
                '8' -> "M$Xs ${Y}h3v5h-3zM$Xs ${Ym}h3"; else -> "M$X3 ${Ym}h-3v-2.5h3v5h-3" } }.joinToString("")
    }
}
