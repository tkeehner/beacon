package com.tyler.primelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTest {
    private fun result(vararg w: Pair<String, Double>) =
        """{"result":[${w.joinToString(",") { """{"conf":${it.second},"end":1.0,"start":0.5,"word":"${it.first}"}""" }}],"text":"${w.joinToString(" ") { it.first }}"}"""

    @Test fun hitWhenBothWordsConfident() {
        val r = WakeWordMatcher.match(result("hey" to 0.95, "prime" to 0.91), 0.8)
        assertEquals(MatchResult.Hit(0.91, "hey prime"), r)
    }
    @Test fun nearMissUsesWeakerWord() {
        val r = WakeWordMatcher.match(result("hey" to 0.99, "prime" to 0.6), 0.8)
        assertEquals(MatchResult.NearMiss(0.6, "hey prime"), r)
    }
    @Test fun phraseInsideSentence() {
        val r = WakeWordMatcher.match(result("so" to 0.7, "hey" to 0.9, "prime" to 0.85, "call" to 0.5, "mom" to 0.4), 0.8)
        assertTrue(r is MatchResult.Hit)
    }
    @Test fun wordsMustBeAdjacentAndOrdered() {
        assertEquals(MatchResult.None, WakeWordMatcher.match(result("prime" to 1.0, "hey" to 1.0), 0.5))
        assertEquals(MatchResult.None, WakeWordMatcher.match(result("hey" to 1.0, "there" to 1.0, "prime" to 1.0), 0.5))
        assertEquals(MatchResult.None, WakeWordMatcher.match(result("stay" to 1.0, "prime" to 1.0), 0.5))
    }
    @Test fun emptyAndUnkResults() {
        assertEquals(MatchResult.None, WakeWordMatcher.match("""{"text":""}""", 0.5))
        assertEquals(MatchResult.None, WakeWordMatcher.match(result("[unk]" to 1.0), 0.5))
    }
    @Test fun bestOfRepeatedPhrase() {
        val r = WakeWordMatcher.match(result("hey" to 0.6, "prime" to 0.6, "hey" to 0.9, "prime" to 0.95), 0.8)
        assertEquals(0.9, (r as MatchResult.Hit).confidence, 1e-9)
    }
    @Test fun cooldownBlocksRepeats() {
        val g = TriggerGate(10_000)
        assertTrue(g.tryFire(1_000)); assertFalse(g.tryFire(5_000)); assertFalse(g.tryFire(10_999)); assertTrue(g.tryFire(11_000))
    }
    @Test fun tuningIsClamped() {
        assertEquals(Tuning(0.80, 10, 2.0), Tuning.clamped(null, null, null))
        assertEquals(Tuning(0.99, 120, 10.0), Tuning.clamped(5.0, 999, 60.0))
        assertEquals(Tuning(0.50, 3, 0.0), Tuning.clamped(0.1, 0, -1.0))
        assertEquals(Tuning(0.80, 10, 2.0), Tuning.clamped(Double.NaN, null, Double.NaN))
    }
    @Test fun routing() {
        assertEquals(Route.DIRECT, LaunchRouting.route(appInForeground = true, canDrawOverlays = false))
        assertEquals(Route.DIRECT, LaunchRouting.route(appInForeground = false, canDrawOverlays = true))
        assertEquals(Route.FULL_SCREEN_ALERT, LaunchRouting.route(appInForeground = false, canDrawOverlays = false))
    }
    @Test fun resumeWaitsForCooldownAndCallEnd() {
        assertFalse(ResumePolicy.shouldResume(5, 10, 0, 0))   // still cooling down
        assertFalse(ResumePolicy.shouldResume(20, 10, 1, 0))  // Grok Bot call still holds the mic
        assertTrue(ResumePolicy.shouldResume(20, 10, 0, 0))
        assertTrue(ResumePolicy.shouldResume(20, 10, 1, 1))   // something already held the mic before the trigger
    }
    @Test fun grammarAssetHasPhraseAndUnk() {
        val f = listOf(java.io.File("src/main/assets/grammar.json"), java.io.File("app/src/main/assets/grammar.json")).first { it.exists() }
        val arr = org.json.JSONArray(f.readText())
        val words = (0 until arr.length()).map { arr.getString(it) }
        assertEquals("hey prime", words.first()); assertTrue("[unk]" in words); assertEquals(words.size, words.toSet().size)
    }
}
