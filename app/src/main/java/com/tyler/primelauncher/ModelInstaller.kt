package com.tyler.primelauncher

import android.content.Context
import java.io.File

/**
 * Vosk needs real files, so the bundled model is copied out of the APK once per app version (~68 MB, a few seconds).
 * 0.1.12: each version gets its own folder (vosk-model-v<code>). The copy goes into a private temp folder and is renamed
 * into place only once complete, so a reader never sees a half-written model and no folder is rewritten in place.
 */
object ModelInstaller {
    private const val ASSET_DIR = "model"
    private const val PREFIX = "vosk-model"

    fun ensure(ctx: Context): File {
        val version = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
        return install(ctx.noBackupFilesDir, version) { out -> copy(ctx, ASSET_DIR, out) }
    }

    /** Pure core (unit-tested): returns base/vosk-model-v<version>, creating it atomically via [fill] if missing. */
    @Synchronized fun install(base: File, version: Long, fill: (File) -> Unit): File {
        val dir = File(base, "$PREFIX-v$version"); val marker = File(dir, ".installed")
        if (marker.exists()) { prune(base, dir); return dir }
        val tmp = File(base, "$PREFIX-v$version.tmp-${System.nanoTime()}")
        try {
            tmp.mkdirs(); fill(tmp); File(tmp, ".installed").createNewFile()
            if (dir.exists()) dir.deleteRecursively() // only an incomplete leftover can be here (no marker)
            check(tmp.renameTo(dir)) { "model swap failed" }
        } finally { if (tmp.exists()) tmp.deleteRecursively() }
        prune(base, dir); return dir
    }
    /** Remove this app's stale model copies (older versions, the pre-0.1.12 folder, abandoned temp folders). Only called
     *  from [install], which the single listener thread runs before it opens the model, so nothing is reading them. */
    private fun prune(base: File, keep: File) {
        base.listFiles()?.filter { it.isDirectory && it.name.startsWith(PREFIX) && it.name != keep.name && !it.name.startsWith("${keep.name}.tmp-") }
            ?.forEach { it.deleteRecursively() }
    }

    private fun copy(ctx: Context, assetPath: String, out: File) {
        val children = ctx.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            ctx.assets.open(assetPath).use { input -> out.outputStream().use { input.copyTo(it, 1 shl 16) } }
        } else {
            out.mkdirs()
            children.forEach { copy(ctx, "$assetPath/$it", File(out, it)) }
        }
    }

    fun grammar(ctx: Context): String = ctx.assets.open("grammar.json").bufferedReader().readText()
}
