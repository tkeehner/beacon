package com.tyler.primelauncher.emitkb.keyboard

import com.tyler.primelauncher.emitkb.ui.EditState
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class KeyboardEngineTest {
    companion object {
        lateinit var e: WordEngine
        @BeforeClass @JvmStatic fun load() {
            e = WordEngine.load(File("src/main/assets/dict/words.bin").inputStream(), File("src/main/assets/dict/bigrams.bin").inputStream())
        }
    }

    @Test fun adjacentCheaperThanFar() = assertTrue(Distance.weighted("teh", "the") < 1f && Distance.subCost('q', 'w') < Distance.subCost('q', 'p'))
    @Test fun correctsTransposition() = assertEquals("the", e.autocorrect("teh"))
    @Test fun correctsAdjacentTypo() = assertEquals("hello", e.autocorrect("hrllo"))
    @Test fun correctsMissingLetter() = assertEquals("because", e.autocorrect("becuse"))
    @Test fun keepsRealWords() = assertNull(e.autocorrect("there"))
    @Test fun keepsCase() = assertEquals("The", e.autocorrect("Teh"))
    @Test fun contractions() { assertEquals("don't", e.autocorrect("dont")); assertEquals("I'm", e.autocorrect("im")) }
    @Test fun loneI() = assertEquals("I", e.autocorrect("i"))
    @Test fun noCorrectionForGibberish() = assertNull(e.autocorrect("xqzvbk"))
    @Test fun protectedContactNameNotCorrected() { e.protect(listOf("Alice", "Zelinsky")); assertNull(e.autocorrect("Zelinsky")) }
    @Test fun acceptedWordNotCorrected() { e.accept("yeet"); assertNull(e.autocorrect("yeet")) }
    @Test fun completion() = assertTrue(e.completions("tomo").contains("tomorrow"))
    @Test fun nextWordFromBigrams() = assertTrue(e.nextWords("of").contains("the"))
    @Test fun learnedBigramRanksFirst() { repeat(3) { e.learn("pizza", "want") }; assertEquals("pizza", e.nextWords("want").first()) }
    @Test fun speed() {
        val t = System.nanoTime(); repeat(20) { e.slots("i", "becuse", true) }
        val ms = (System.nanoTime() - t) / 20 / 1e6
        assertTrue("slots took ${ms}ms", ms < 40) // JVM desktop budget; phone runs this off the main thread
    }

    // ---- AutoText: apply + revert
    private val fix: (String) -> String? = { e.autocorrect(it) }

    @Test fun spaceAutocorrects() {
        val (s, c) = AutoText.separator(EditState("i love teh", 10), " ", fix)
        assertEquals("i love the ", s.text); assertEquals(11, s.start); assertEquals("teh", c!!.original)
    }
    @Test fun backspaceRevertsAutocorrect() {
        val (s, c) = AutoText.separator(EditState("see teh", 7), " ", fix)
        val (r, reverted) = AutoText.backspace(s, c)
        assertTrue(reverted); assertEquals("see teh", r.text); assertEquals(7, r.start)
    }
    @Test fun backspaceAfterMovingCursorDoesNotRevert() {
        val (s, c) = AutoText.separator(EditState("see teh", 7), " ", fix)
        val (r, reverted) = AutoText.backspace(s.copy(start = 2, end = 2), c)
        assertFalse(reverted); assertEquals("se the ", r.text)
    }
    @Test fun punctuationAutocorrects() = assertEquals("ok the.", AutoText.separator(EditState("ok teh", 6), ".", fix).first.text)
    @Test fun noCorrectorJustInserts() = assertEquals("teh ", AutoText.separator(EditState("teh", 3), " ", null).first.text)
    @Test fun doubleSpacePeriod() = assertEquals("hi. ", AutoText.separator(EditState("hi ", 3), " ", fix).first.text)
    @Test fun capitalizeRules() {
        assertTrue(AutoText.shouldCapitalize(EditState("", 0)))
        assertTrue(AutoText.shouldCapitalize(EditState("ok. ", 4)))
        assertFalse(AutoText.shouldCapitalize(EditState("ok ", 3)))
        assertTrue(AutoText.shouldCapitalize(EditState("hi\n", 3)))
    }
    @Test fun wordsAroundCursor() {
        val s = EditState("going to th", 11)
        assertEquals("th", AutoText.currentWord(s)); assertEquals("to", AutoText.previousWord(s))
    }
    @Test fun applySuggestionReplacesWord() = assertEquals("going to the ", AutoText.applySuggestion(EditState("going to th", 11), "the").text)
}
