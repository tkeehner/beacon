package com.tyler.primelauncher.emitkb.keyboard

import android.content.Context
import java.io.File

/** Persists learned words/bigrams in app-private storage (never uploaded, excluded from backup). */
object UserDict {
    private fun file(c: Context) = File(c.filesDir, "user_dict.tsv")

    fun load(c: Context, e: WordEngine) = runCatching {
        val f = file(c); if (!f.exists()) return@runCatching
        val w = HashMap<String, Int>(); val b = HashMap<String, HashMap<String, Int>>()
        f.forEachLine { l ->
            val p = l.split('\t')
            when { p.size == 2 -> w[p[0]] = p[1].toIntOrNull() ?: 1
                p.size == 3 -> b.getOrPut(p[0]) { HashMap() }[p[1]] = p[2].toIntOrNull() ?: 1 }
        }
        e.loadUser(w, b)
    }

    fun save(c: Context, e: WordEngine) = runCatching {
        val (w, b) = e.userSnapshot()
        val sb = StringBuilder()
        w.forEach { (k, v) -> sb.append(k).append('\t').append(v).append('\n') }
        b.forEach { (p, m) -> m.forEach { (k, v) -> sb.append(p).append('\t').append(k).append('\t').append(v).append('\n') } }
        val tmp = File(c.filesDir, "user_dict.tmp"); tmp.writeText(sb.toString()); tmp.renameTo(file(c))
    }
}

/** Keyboard settings (local). */
class KeyboardPrefs(c: Context) {
    private val p = c.getSharedPreferences("keyboard", Context.MODE_PRIVATE)
    var autocorrect: Boolean get() = p.getBoolean("autocorrect", true); set(v) { p.edit().putBoolean("autocorrect", v).apply() }
    var autocap: Boolean get() = p.getBoolean("autocap", true); set(v) { p.edit().putBoolean("autocap", v).apply() }
    var suggestions: Boolean get() = p.getBoolean("suggestions", true); set(v) { p.edit().putBoolean("suggestions", v).apply() }
}
