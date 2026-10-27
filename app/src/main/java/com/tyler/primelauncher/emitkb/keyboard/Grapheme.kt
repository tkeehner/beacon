package com.tyler.primelauncher.emitkb.keyboard

/**
 * Pure (unit-tested) "previous grapheme boundary" for backspace. Handles emoji sequences without ICU:
 * variation selectors, skin-tone modifiers, keycaps, tag sequences (subdivision flags), ZWJ sequences,
 * regional-indicator flag pairs and combining marks.
 */
object Grapheme {
    private const val ZWJ = 0x200D
    private fun isRegional(cp: Int) = cp in 0x1F1E6..0x1F1FF
    private fun isExtender(cp: Int) = cp == 0xFE0F || cp == 0xFE0E || cp in 0x1F3FB..0x1F3FF || cp == 0x20E3 ||
        cp in 0xE0020..0xE007F || Character.getType(cp).let { it == Character.NON_SPACING_MARK.toInt() || it == Character.ENCLOSING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt() }

    fun previousBoundary(text: CharSequence, index: Int): Int {
        if (index <= 0) return 0
        var j = index
        var cp = Character.codePointBefore(text, j); j -= Character.charCount(cp)
        if (isRegional(cp)) {
            var n = 0; var k = j
            while (k > 0) { val p = Character.codePointBefore(text, k); if (!isRegional(p)) break; n++; k -= Character.charCount(p) }
            return if (n % 2 == 1) j - 2 else j
        }
        while (true) {
            while (isExtender(cp) && j > 0) { cp = Character.codePointBefore(text, j); j -= Character.charCount(cp) }
            if (j > 0 && text[j - 1].code == ZWJ) {
                j -= 1
                if (j > 0) { cp = Character.codePointBefore(text, j); j -= Character.charCount(cp); continue }
            }
            return j
        }
    }
}
