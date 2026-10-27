package com.tyler.primelauncher

import org.json.JSONObject

/** One recognised word from a Vosk final result (needs Recognizer.setWords(true)). */
data class Word(val word: String, val conf: Double)

sealed interface MatchResult {
    /** "hey prime" heard with both words at or above the threshold. */
    data class Hit(val confidence: Double, val text: String) : MatchResult
    /** "hey prime" heard, but too unsure; logged so the threshold can be tuned. */
    data class NearMiss(val confidence: Double, val text: String) : MatchResult
    data object None : MatchResult
}

/**
 * Decides whether a Vosk *final* result contains the wake phrase.
 * Partial results are deliberately ignored: on the box test they false-triggered on ~13-22% of
 * sound-alike phrases, versus ~0.5-1.5% for final results with a confidence check.
 */
object WakeWordMatcher {
    const val FIRST = "hey"
    const val SECOND = "prime"

    fun words(json: String): List<Word> {
        val arr = JSONObject(json).optJSONArray("result") ?: return emptyList()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Word(o.optString("word"), o.optDouble("conf", 0.0))
        }
    }

    fun text(json: String): String = JSONObject(json).optString("text", "")

    fun match(json: String, threshold: Double): MatchResult {
        val w = words(json)
        var best: Double? = null
        for (i in 0 until w.size - 1) {
            if (w[i].word == FIRST && w[i + 1].word == SECOND) {
                val c = minOf(w[i].conf, w[i + 1].conf)
                if (best == null || c > best) best = c
            }
        }
        return when {
            best == null -> MatchResult.None
            best >= threshold -> MatchResult.Hit(best, text(json))
            else -> MatchResult.NearMiss(best, text(json))
        }
    }
}

/** Blocks repeat triggers inside the cooldown window (e.g. Grok Bot's own voice saying "prime"). */
class TriggerGate(private val cooldownMs: Long) {
    private var lastFire: Long? = null
    fun tryFire(nowMs: Long): Boolean {
        val last = lastFire
        if (last != null && nowMs - last < cooldownMs) return false
        lastFire = nowMs
        return true
    }
}

/** User-tunable settings, clamped to safe ranges. */
data class Tuning(val threshold: Double, val cooldownSec: Int, val homeDelaySec: Double) {
    companion object {
        const val DEFAULT_THRESHOLD = 0.80
        const val DEFAULT_COOLDOWN = 10
        const val DEFAULT_HOME_DELAY = 2.0
        fun clamped(threshold: Double?, cooldownSec: Int?, homeDelaySec: Double?) = Tuning(
            threshold = (threshold ?: DEFAULT_THRESHOLD).let { if (it.isNaN()) DEFAULT_THRESHOLD else it }.coerceIn(0.50, 0.99),
            cooldownSec = (cooldownSec ?: DEFAULT_COOLDOWN).coerceIn(3, 120),
            homeDelaySec = (homeDelaySec ?: DEFAULT_HOME_DELAY).let { if (it.isNaN()) DEFAULT_HOME_DELAY else it }.coerceIn(0.0, 10.0),
        )
    }
}

/** How a launch is delivered given Android 10+ background-activity-start rules. */
enum class Route { DIRECT, FULL_SCREEN_ALERT }

object LaunchRouting {
    /** In the foreground, or with "Display over other apps" granted, Android lets us start activities directly. */
    fun route(appInForeground: Boolean, canDrawOverlays: Boolean): Route =
        if (appInForeground || canDrawOverlays) Route.DIRECT else Route.FULL_SCREEN_ALERT
}

/** Resume listening only after the cooldown AND once no extra app holds the mic (e.g. the Grok Bot call). */
object ResumePolicy {
    fun shouldResume(nowMs: Long, pausedUntilMs: Long, otherRecordings: Int, baselineOtherRecordings: Int): Boolean =
        nowMs >= pausedUntilMs && otherRecordings <= baselineOtherRecordings
}
