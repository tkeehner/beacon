package com.tyler.primelauncher.emitkb.keyboard

import com.tyler.primelauncher.emitkb.ui.EditState
import org.junit.Assert.assertEquals
import org.junit.Test

class GraphemeTest {
    private fun bs(t: String) = EditState(t, t.length).backspace().text
    @Test fun plainLetter() = assertEquals("ab", bs("abc"))
    @Test fun simpleEmoji() = assertEquals("hi ", bs("hi 😀"))
    @Test fun skinTone() = assertEquals("ok ", bs("ok 👍🏽"))
    @Test fun zwjFamily() = assertEquals("x", bs("x👨‍👩‍👧‍👦"))
    @Test fun zwjWithToneAndVs() = assertEquals("", bs("🧑🏻‍❤️‍💋‍🧑🏼"))
    @Test fun heartVs16() = assertEquals("a", bs("a❤️"))
    @Test fun keycap() = assertEquals("n", bs("n1️⃣"))
    @Test fun flagPairs() { assertEquals("🇺🇸", bs("🇺🇸🇨🇦")); assertEquals("", bs("🇺🇸")) }
    @Test fun tagFlag() = assertEquals("z", bs("z🏴󠁧󠁢󠁳󠁣󠁴󠁿"))
    @Test fun rainbowFlag() = assertEquals("", bs("🏳️‍🌈"))
    @Test fun midText() { val t = "a😀b"; assertEquals("ab", EditState(t, 3).backspace().text) }
    @Test fun combiningAccent() = assertEquals("caf", bs("cafe\u0301"))
}
