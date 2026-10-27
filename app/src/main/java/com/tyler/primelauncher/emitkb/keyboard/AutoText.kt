package com.tyler.primelauncher.emitkb.keyboard

import com.tyler.primelauncher.emitkb.ui.EditState

/** A just-applied autocorrect, so an immediate backspace can undo it. */
data class Correction(val original: String, val corrected: String, val separator: String, val endAfter: Int)

/** Pure text transforms around word boundaries (unit-tested). */
object AutoText {
    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '\''

    fun before(s: EditState) = s.text.substring(0, minOf(s.start, s.end).coerceIn(0, s.text.length))

    /** The word immediately before the cursor (may be empty). */
    fun currentWord(s: EditState): String = before(s).takeLastWhile(::isWordChar)

    /** The word before the current one, if in the same sentence. */
    fun previousWord(s: EditState): String? {
        val b = before(s).dropLastWhile(::isWordChar)
        val trimmed = b.trimEnd(' ')
        if (trimmed.isEmpty() || !isWordChar(trimmed.last())) return null
        return trimmed.takeLastWhile(::isWordChar).lowercase().ifEmpty { null }
    }

    /** True if the next typed letter should be capitalized. */
    fun shouldCapitalize(s: EditState): Boolean {
        val b = before(s)
        if (b.isBlank()) return true
        val t = b.trimEnd(' ')
        return b.endsWith(" ") && (t.endsWith(".") || t.endsWith("!") || t.endsWith("?")) || b.endsWith("\n")
    }

    /**
     * Type a separator (" ", ".", ",", "!", "?", "\n"). Applies autocorrect to the word before the cursor when
     * [corrector] returns a replacement, and turns double-space after a word into ". ".
     */
    fun separator(s: EditState, sep: String, corrector: ((String) -> String?)?): Pair<EditState, Correction?> {
        val b = before(s)
        if (sep == " " && b.endsWith(" ") && b.length >= 2 && isWordChar(b[b.length - 2])) {
            return EditState(s.text.substring(0, b.length - 1) + ". " + s.text.substring(b.length), b.length + 1) to null
        }
        val word = currentWord(s)
        val fix = if (word.isNotEmpty() && corrector != null) corrector(word)?.takeIf { it != word } else null
        if (fix == null) return s.insert(sep) to null
        val startW = b.length - word.length
        val newText = s.text.substring(0, startW) + fix + sep + s.text.substring(b.length)
        val end = startW + fix.length + sep.length
        return EditState(newText, end) to Correction(word, fix, sep, end)
    }

    /** Backspace; if it immediately follows an autocorrect, revert to the original word (drop the separator). */
    fun backspace(s: EditState, last: Correction?): Pair<EditState, Boolean> {
        if (last != null && s.start == s.end && s.start == last.endAfter) {
            val tail = last.corrected + last.separator
            val b = before(s)
            if (b.endsWith(tail)) {
                val st = b.length - tail.length
                return EditState(s.text.substring(0, st) + last.original + s.text.substring(b.length), st + last.original.length) to true
            }
        }
        return s.backspace() to false
    }

    /** Replace the current word with a tapped suggestion and add a space. */
    fun applySuggestion(s: EditState, word: String): EditState {
        val b = before(s); val cur = currentWord(s)
        val st = b.length - cur.length
        val rest = s.text.substring(b.length)
        val space = if (rest.startsWith(" ")) "" else " "
        return EditState(s.text.substring(0, st) + word + space + rest, st + word.length + 1)
    }
}
