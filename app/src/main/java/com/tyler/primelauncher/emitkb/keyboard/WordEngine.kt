package com.tyler.primelauncher.emitkb.keyboard

import java.io.BufferedReader
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log10

/** Three suggestion slots; [autocorrect] = what space/punctuation will replace the current word with (null = keep). */
data class Slots(val left: String?, val center: String?, val right: String?, val autocorrect: String? = null)

/**
 * Fully offline word model: unigram frequencies (zipf*100), bigrams, user-learned words/bigrams and protected
 * words (contacts). Pure JVM - unit-tested. Thread-safe for reads; user data mutations are synchronized.
 */
class WordEngine(
    private val freq: Map<String, Int>,
    bigrams: Map<String, List<Pair<String, Int>>>,
) {
    private val byFreq: List<String> = freq.entries.sortedByDescending { it.value }.map { it.key }
    private val byLen: Map<Int, List<String>> = byFreq.groupBy { it.length }
    private val bi: Map<String, List<Pair<String, Int>>> = bigrams
    val wordCount get() = freq.size
    val bigramKeys get() = bi.size
    private val user = HashMap<String, Int>()
    private val userBi = HashMap<String, HashMap<String, Int>>()
    private val protectedWords = HashSet<String>()

    @Synchronized fun learn(word: String, prev: String?) {
        val w = word.lowercase(); if (w.isBlank() || !w.all { it.isLetter() || it == '\'' }) return
        user[w] = (user[w] ?: 0) + 1
        if (prev != null) { val p = prev.lowercase(); val m = userBi.getOrPut(p) { HashMap() }; m[w] = (m[w] ?: 0) + 1 }
    }
    /** Explicitly accept a word (e.g. after reverting an autocorrect): never autocorrect it again. */
    @Synchronized fun accept(word: String) { val w = word.lowercase(); user[w] = maxOf(user[w] ?: 0, ACCEPTED) }
    @Synchronized fun protect(words: Collection<String>) { words.forEach { protectedWords += it.lowercase() } }
    @Synchronized fun userSnapshot(): Pair<Map<String, Int>, Map<String, Map<String, Int>>> = HashMap(user) to userBi.mapValues { HashMap(it.value) }
    @Synchronized fun loadUser(words: Map<String, Int>, bigrams: Map<String, Map<String, Int>>) {
        user.putAll(words); bigrams.forEach { (k, v) -> userBi.getOrPut(k) { HashMap() }.putAll(v) }
    }

    @Synchronized fun isKnown(word: String): Boolean {
        val w = word.lowercase()
        return w in freq || (user[w] ?: 0) >= LEARNED || w in protectedWords
    }

    // ---------- correction ----------
    data class Candidate(val word: String, val cost: Float, val score: Float)

    fun corrections(typed: String, limit: Int = 3): List<Candidate> {
        val t = typed.lowercase()
        if (t.length < 2 || !t.all { it.isLetter() || it == '\'' }) return emptyList()
        val maxCost = if (t.length <= 3) 1.0f else if (t.length <= 6) 1.6f else 2.1f
        val out = ArrayList<Candidate>()
        for (len in (t.length - 2).coerceAtLeast(1)..(t.length + 2)) {
            val list = byLen[len] ?: continue
            for (w in list) {
                val c = Distance.weighted(t, w, maxCost)
                if (c <= maxCost) out += Candidate(w, c, score(w, c))
            }
        }
        val uw = synchronized(this) { user.filter { it.value >= LEARNED }.keys.toList() }
        uw.forEach { w -> if (abs(w.length - t.length) <= 2) { val c = Distance.weighted(t, w, maxCost); if (c <= maxCost) out += Candidate(w, c, score(w, c)) } }
        return out.distinctBy { it.word }.sortedByDescending { it.score }.take(limit)
    }

    private fun score(w: String, cost: Float): Float {
        val z = (freq[w] ?: 150) / 100f
        val u = synchronized(this) { user[w] ?: 0 }
        return z + (if (u > 0) 0.6f + log10(1f + u) else 0f) - 2.3f * cost
    }

    /** Word to auto-replace [typed] with, or null if not confident / typed is a real word. */
    fun autocorrect(typed: String): String? {
        CONTRACTIONS[typed.lowercase()]?.let { return matchCase(typed, it) }
        if (typed == "i") return "I"
        if (typed.length < 2) return null
        val lower = typed.lowercase()
        val userOk = synchronized(this) { (user[lower] ?: 0) >= LEARNED || lower in protectedWords }
        if (userOk) return null
        val known = lower in freq
        if (known) {
            if (lower.length <= 2) return null
            // Common misspellings live in web word lists (teh, recieve). Only replace a known word when a much more
            // frequent word is one cheap edit away.
            val z = freq[lower] ?: 0
            val best = corrections(typed, 3).firstOrNull { it.word != lower } ?: return null
            return if (best.cost <= 0.6f && (freq[best.word] ?: 0) - z >= 250) matchCase(typed, best.word) else null
        }
        if (typed.any { it.isDigit() } || (typed.length > 1 && typed.drop(1).any { it.isUpperCase() })) return null
        val c = corrections(typed, 2)
        val best = c.firstOrNull() ?: return null
        val second = c.getOrNull(1)
        val confident = best.cost <= (if (typed.length <= 4) 1.0f else 1.6f) && (second == null || best.score - second.score >= 0.35f)
        return if (confident) matchCase(typed, best.word) else null
    }

    fun completions(prefix: String, limit: Int = 3): List<String> {
        val p = prefix.lowercase(); if (p.isEmpty()) return emptyList()
        val out = ArrayList<String>(limit)
        val uw = synchronized(this) { user.entries.filter { it.value >= LEARNED && it.key.startsWith(p) && it.key != p }.sortedByDescending { it.value }.map { it.key } }
        out += uw.take(limit)
        for (w in byFreq) { if (out.size >= limit) break; if (w.length > p.length && w.startsWith(p) && w !in out) out += w }
        return out
    }

    fun nextWords(prev: String?, limit: Int = 3): List<String> {
        val p = prev?.lowercase()
        val scores = LinkedHashMap<String, Float>()
        if (p != null) {
            synchronized(this) { userBi[p]?.forEach { (w, n) -> scores[w] = 1_000_000f + n } }
            bi[p]?.forEach { (w, n) -> scores[w] = maxOf(scores[w] ?: 0f, n.toFloat()) }
        }
        val ranked = scores.entries.sortedByDescending { it.value }.map { it.key }.toMutableList()
        for (w in DEFAULT_STARTS) { if (ranked.size >= limit) break; if (w !in ranked) ranked += w }
        return ranked.take(limit).map { if (it == "i") "I" else it }
    }

    /** Suggestion strip contents for the word being typed ([current] may be empty). */
    fun slots(prev: String?, current: String, autocorrectOn: Boolean): Slots {
        if (current.isEmpty()) {
            val n = nextWords(prev); val cap = prev == null
            fun f(s: String?) = s?.let { if (cap) it.replaceFirstChar(Char::uppercaseChar) else it }
            return Slots(f(n.getOrNull(1)), f(n.getOrNull(0)), f(n.getOrNull(2)))
        }
        val ac = if (autocorrectOn) autocorrect(current) else null
        val comps = completions(current, 3).map { matchCase(current, it) }
        val list = ArrayList<String>()
        ac?.let { list += it }
        if (isKnown(current)) list += current
        // Best completion always gets a visible slot, even when an autocorrect owns the center.
        comps.firstOrNull()?.let { list += it }
        corrections(current, 3).forEach { if (!isKnown(current)) list += matchCase(current, it.word) }
        list += comps
        val d = list.distinct().filter { it != current || isKnown(current) }
        val center = d.getOrNull(0)
        val left = if (center != current) "\"$current\"" else d.getOrNull(2)
        return Slots(left, center, d.getOrNull(1), ac)
    }

    companion object {
        const val LEARNED = 2; const val ACCEPTED = 1000
        val DEFAULT_STARTS = listOf("i", "the", "to", "and", "you", "it", "is", "a")
        val CONTRACTIONS = mapOf(
            "im" to "I'm", "ive" to "I've", "dont" to "don't", "cant" to "can't", "wont" to "won't", "didnt" to "didn't",
            "doesnt" to "doesn't", "isnt" to "isn't", "wasnt" to "wasn't", "arent" to "aren't", "youre" to "you're",
            "theyre" to "they're", "thats" to "that's", "whats" to "what's", "couldnt" to "couldn't", "shouldnt" to "shouldn't",
            "wouldnt" to "wouldn't", "havent" to "haven't", "hasnt" to "hasn't",
        )

        fun matchCase(typed: String, word: String): String = when {
            word.startsWith("I'") || word == "I" -> word
            typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
            typed.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar(Char::uppercaseChar)
            else -> word
        }

        fun load(words: InputStream, bigrams: InputStream?): WordEngine =
            fromBytes(maybeGunzip(words.use { it.readBytes() }), bigrams?.use { maybeGunzip(it.readBytes()) })

        private fun maybeGunzip(b: ByteArray): ByteArray =
            if (b.size > 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte()) GZIPInputStream(b.inputStream()).use { it.readBytes() } else b

        /**
         * Hand-rolled parser over raw bytes (no regex/split, no per-line readers): lines "word<TAB>n" and
         * "w1<SPACE>w2<TAB>n". ASCII-only words, so bytes map 1:1 to chars.
         */
        fun fromBytes(words: ByteArray, bigrams: ByteArray?): WordEngine {
            val f = HashMap<String, Int>(64_000)
            parseLines(words) { line, s, tab, e -> f[ascii(line, s, tab)] = num(line, tab + 1, e) }
            val b = HashMap<String, MutableList<Pair<String, Int>>>(8_000)
            if (bigrams != null) parseLines(bigrams) { line, s, tab, e ->
                var sp = -1; for (i in s until tab) if (line[i] == ' '.code.toByte()) { sp = i; break }
                if (sp > s) b.getOrPut(ascii(line, s, sp)) { ArrayList(4) } += ascii(line, sp + 1, tab) to num(line, tab + 1, e)
            }
            return WordEngine(f, b.mapValues { e -> if (e.value.size <= 6) e.value else e.value.sortedByDescending { it.second }.take(6) })
        }

        private inline fun parseLines(buf: ByteArray, onLine: (ByteArray, Int, Int, Int) -> Unit) {
            var s = 0; val n = buf.size
            while (s < n) {
                var e = s; var tab = -1
                while (e < n && buf[e] != '\n'.code.toByte()) { if (tab < 0 && buf[e] == '\t'.code.toByte()) tab = e; e++ }
                if (tab > s) onLine(buf, s, tab, e)
                s = e + 1
            }
        }
        private fun ascii(b: ByteArray, s: Int, e: Int): String = String(b, s, e - s, Charsets.ISO_8859_1)
        private fun num(b: ByteArray, s: Int, e: Int): Int { var v = 0; for (i in s until e) { val d = b[i] - 48; if (d in 0..9) v = v * 10 + d }; return v }
    }
}

/** Keyboard-aware Damerau-Levenshtein (optimal string alignment). Adjacent-key substitutions are cheaper. */
object Distance {
    private val pos = HashMap<Char, Pair<Float, Float>>().apply {
        listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f).forEachIndexed { r, (row, off) ->
            row.forEachIndexed { i, c -> put(c, (i + off) to r.toFloat()) }
        }
    }
    fun adjacent(a: Char, b: Char): Boolean {
        val p = pos[a] ?: return false; val q = pos[b] ?: return false
        return hypot(p.first - q.first, p.second - q.second) <= 1.25f
    }
    fun subCost(a: Char, b: Char) = if (a == b) 0f else if (adjacent(a, b)) 0.45f else 1f

    fun weighted(a: String, b: String, maxCost: Float = Float.MAX_VALUE): Float {
        val n = a.length; val m = b.length
        var pp = FloatArray(m + 1); var prev = FloatArray(m + 1) { it.toFloat() }; var cur = FloatArray(m + 1)
        for (i in 1..n) {
            cur[0] = i.toFloat(); var rowMin = cur[0]
            for (j in 1..m) {
                var v = minOf(prev[j] + 1f, cur[j - 1] + 1f, prev[j - 1] + subCost(a[i - 1], b[j - 1]))
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, pp[j - 2] + 0.6f)
                cur[j] = v; if (v < rowMin) rowMin = v
            }
            if (rowMin > maxCost) return Float.MAX_VALUE
            val t = pp; pp = prev; prev = cur; cur = t
        }
        return prev[m]
    }
}
