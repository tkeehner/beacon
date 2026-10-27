package com.tyler.primelauncher

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Theme palette + tiny view helpers (all views are built in code). */
class Pal(val neon: Boolean) {
    val bg = if (neon) 0xFF07060B.toInt() else 0xFF000000.toInt()
    val s1 = if (neon) 0xFF100E17.toInt() else 0xFF0B0F0C.toInt()
    val p = if (neon) 0xFF3EE8F0.toInt() else 0xFF3DFF7A.toInt()
    val p2 = if (neon) 0xFFFF3D81.toInt() else 0xFFE2FFE9.toInt()
    val hi = if (neon) 0xFFF7E733.toInt() else 0xFF3DFF7A.toInt()
    val body = if (neon) 0xFFEEF2F3.toInt() else 0xFFB8F5C8.toInt()
    val dim = if (neon) 0xFF8A8D9E.toInt() else 0xFF5E8F6B.toInt()
    val line = if (neon) 0x553EE8F0 else 0x443DFF7A
    val font: Typeface = if (neon) Typeface.create("sans-serif-condensed", Typeface.NORMAL) else Typeface.MONOSPACE
    val bold: Typeface = if (neon) Typeface.create("sans-serif-condensed", Typeface.BOLD) else Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    fun up(s: String) = if (neon) s.uppercase() else s
    fun panel(ctx: Context, radiusDp: Float = 16f, alpha: Int = 0xF2): GradientDrawable = GradientDrawable().apply {
        setColor(Color.argb(alpha, Color.red(s1), Color.green(s1), Color.blue(s1))); cornerRadius = if (neon) ctx.dp(3f) else ctx.dp(radiusDp); setStroke(ctx.dp(1f).toInt(), line) }
    fun chip(ctx: Context, sel: Boolean): GradientDrawable = GradientDrawable().apply {
        setColor(if (sel) p else Color.TRANSPARENT); cornerRadius = if (neon) ctx.dp(2f) else ctx.dp(10f); setStroke(ctx.dp(1f).toInt(), if (sel) p else line) }
}

fun Context.dp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
fun Context.dpi(v: Float): Int = dp(v).toInt()

fun Context.text(pal: Pal, s: String, sizeSp: Float, color: Int = pal.body, bold: Boolean = false, lines: Int = 1): TextView = TextView(this).apply {
    text = s; textSize = sizeSp; setTextColor(color); typeface = if (bold) pal.bold else pal.font; includeFontPadding = false
    if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
}
fun Context.row(vararg v: View, gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; this.gravity = gravity; v.forEach { addView(it) } }
fun Context.col(vararg v: View): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; v.forEach { addView(it) } }
fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT) = ViewGroup.MarginLayoutParams(w, h)
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
