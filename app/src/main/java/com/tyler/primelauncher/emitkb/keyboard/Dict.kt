package com.tyler.primelauncher.emitkb.keyboard

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.InputStream

sealed interface DictState {
    data object Loading : DictState
    data class Ready(val engine: WordEngine, val words: Int) : DictState
    data class Failed(val msg: String) : DictState
}

/** App-level dictionary loader: one load per process, on its own IO scope (not tied to any screen). */
object Dict {
    private const val TAG = "MatrixSMS"
    const val WORDS = "dict/words.bin"; const val BIGRAMS = "dict/bigrams.bin"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<DictState>(DictState.Loading)
    val state: StateFlow<DictState> = _state
    @Volatile private var started = false

    fun ensureLoaded(context: Context, contactNames: () -> List<String>) {
        synchronized(this) { if (started) return; started = true }
        val app = context.applicationContext
        scope.launch {
            val t0 = System.currentTimeMillis()
            Log.i(TAG, "dict: loading")
            try {
                val e = load({ app.assets.open(WORDS) }, { app.assets.open(BIGRAMS) })
                Log.i(TAG, "dict: ${e.wordCount} words, ${e.bigramKeys} bigram keys in ${System.currentTimeMillis() - t0}ms")
                runCatching { UserDict.load(app, e) }.onFailure { Log.w(TAG, "dict: user dict load failed", it) }
                runCatching { e.protect(contactNames().flatMap { it.split(' ', '-', '.') }.filter { it.length > 1 }) }
                    .onFailure { Log.w(TAG, "dict: contacts protect failed", it) }
                _state.value = DictState.Ready(e, e.wordCount)
            } catch (t: Throwable) {
                Log.e(TAG, "dict: load FAILED", t)
                _state.value = DictState.Failed(t.javaClass.simpleName + ": " + (t.message ?: ""))
            }
        }
    }

    /** Pure loader (unit-tested against the real asset files). */
    fun load(words: () -> InputStream, bigrams: () -> InputStream): WordEngine {
        val e = WordEngine.load(words(), runCatching { bigrams() }.getOrNull())
        check(e.wordCount > 1000) { "word list too small (${e.wordCount})" }
        return e
    }
}
