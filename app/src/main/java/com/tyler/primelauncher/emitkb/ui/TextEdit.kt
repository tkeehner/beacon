package com.tyler.primelauncher.emitkb.ui

/** Pure text-editing ops for the in-app keyboard (unit-tested). Selection is [start,end) in chars. */
data class EditState(val text: String, val start: Int, val end: Int = start) {
    private val lo get() = minOf(start, end).coerceIn(0, text.length)
    private val hi get() = maxOf(start, end).coerceIn(0, text.length)

    fun insert(s: String): EditState {
        val t = text.substring(0, lo) + s + text.substring(hi)
        return EditState(t, lo + s.length)
    }

    fun backspace(): EditState {
        if (lo != hi) return EditState(text.substring(0, lo) + text.substring(hi), lo)
        if (lo == 0) return EditState(text, 0)
        // Delete one whole grapheme (a full emoji incl. skin tone / ZWJ family / flag), never half of one.
        val b = com.tyler.primelauncher.emitkb.keyboard.Grapheme.previousBoundary(text, lo)
        return EditState(text.substring(0, b) + text.substring(lo), b)
    }
}

/** Shift state machine: tap = one-shot shift, double-tap (<350ms) = caps lock, tap again = off. */
enum class ShiftMode { OFF, ONCE, LOCK }

fun nextShift(current: ShiftMode, nowMs: Long, lastTapMs: Long): ShiftMode = when {
    current == ShiftMode.ONCE && nowMs - lastTapMs < 350 -> ShiftMode.LOCK
    current == ShiftMode.OFF -> ShiftMode.ONCE
    else -> ShiftMode.OFF
}

/** After typing a letter, one-shot shift drops back to OFF; lock stays. */
fun afterChar(current: ShiftMode): ShiftMode = if (current == ShiftMode.ONCE) ShiftMode.OFF else current
