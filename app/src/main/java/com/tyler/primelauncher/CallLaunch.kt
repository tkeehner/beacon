package com.tyler.primelauncher

import android.app.Activity
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import java.lang.reflect.Method

/**
 * 0.1.20: how orb tap / flick-to-Prime / lock-alert tap / "Hey Prime" start a Grok Bot voice call.
 * One method only: 0.1.19's "System assist APIs" (confirmed on a real phone): [Activity.showAssist], then the
 * hidden SearchManager.launchAssist → StatusBarManager.startAssist. The old AssistTrampoline flow (+ bounce home) is
 * only a silent last resort. Every step logs under [TAG] (`adb logcat -s BeaconAssist`).
 */
object CallLaunch {
    const val TAG = "BeaconAssist"

    /** Seam for tests; on the phone this is [Activity.showAssist]. */
    @Volatile var showAssist: (Activity, Bundle) -> Boolean = { a, b -> a.showAssist(b) }
    /** Seam for tests; on the phone these are the reflective @SystemApi / hidden calls. */
    @Volatile var hiddenApis: (Context) -> Boolean = ::viaHiddenApis

    fun log(msg: String) = Log.i(TAG, msg)
    fun warn(msg: String, t: Throwable? = null) = if (t == null) Log.w(TAG, msg) else Log.w(TAG, msg, t)

    /**
     * The 0.1.19 "System assist APIs" path, now the only one: [Activity.showAssist] when a Beacon activity is passed
     * (orb tap, flick, alert tap), else / on failure the hidden assist APIs; if all fail, the old trampoline (silent).
     * "Hey Prime" passes no activity and goes straight to the hidden APIs, exactly as in 0.1.19.
     */
    fun fire(ctx: Context, source: String, activity: Activity? = null) {
        log("fire source=$source homeVisible=${PrimeApp.homeVisible} act=${activity?.javaClass?.simpleName}")
        val ok = runCatching { viaSystem(ctx, activity) }.getOrElse { warn("system assist threw; falling back", it); false }
        if (!ok) { log("falling back to trampoline source=$source"); current(ctx, source); return }
        Call.begin(source); log("system assist started call (session/overlay; no trampoline bounce)")
    }

    fun viaSystem(ctx: Context, activity: Activity?): Boolean {
        if (activity != null && !activity.isFinishing) {
            val ok = runCatching {
                val args = Bundle().apply { putString(Intent.EXTRA_ASSIST_PACKAGE, ctx.packageName) }
                showAssist(activity, args).also { log("Activity.showAssist returned $it") }
            }.getOrElse { warn("Activity.showAssist threw", it); false }
            if (ok) return true
        } else log("Activity.showAssist skipped (no foreground activity)")
        return hiddenApis(ctx)
    }

    /** Last-resort flow (pre-0.1.20 default): Grok's AssistTrampoline via ACTION_ASSIST, then come home. */
    fun current(ctx: Context, source: String): Boolean {
        log("current: starting AssistTrampoline source=$source")
        val app = ctx.applicationContext
        val ok = runCatching {
            app.startActivity(Grok.assistIntent().apply { putExtra(Intent.EXTRA_ASSIST_PACKAGE, app.packageName) }, noAnim(app))
            true
        }.getOrElse { warn("current trampoline failed", it); Apps.launchPkg(app, Grok.PKG) }
        if (!ok) { Call.end(); return false }
        Call.begin(source); Grok.homeSoon(app); return true
    }

    /** SearchManager.launchAssist (@SystemApi) → StatusBarManager.startAssist (hidden); usually blocked for third-party apps. */
    fun viaHiddenApis(ctx: Context): Boolean {
        runCatching {
            val sm = ctx.getSystemService(SearchManager::class.java) ?: return@runCatching false
            val m = SearchManager::class.java.getMethod("launchAssist", Bundle::class.java)
            m.invoke(sm, Bundle()); log("SearchManager.launchAssist invoked"); true
        }.getOrElse { warn("SearchManager.launchAssist blocked/failed (${it.javaClass.simpleName}: ${it.message})"); false }.let { if (it) return true }
        runCatching {
            val sbm = ctx.getSystemService(Context.STATUS_BAR_SERVICE) ?: return@runCatching false
            val m: Method = sbm.javaClass.getMethod("startAssist", Bundle::class.java)
            m.invoke(sbm, Bundle()); log("StatusBarManager.startAssist invoked"); true
        }.getOrElse { warn("StatusBarManager.startAssist blocked/failed (${it.javaClass.simpleName}: ${it.message})"); false }.let { if (it) return true }
        return false
    }

    fun noAnim(ctx: Context) = android.app.ActivityOptions.makeCustomAnimation(ctx.applicationContext, 0, 0).toBundle()
}
