package com.tyler.primelauncher

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Hang-up helper (opt-in, for the red button). Scoped to ai.x.grok.bot in res/xml/hangup_service.xml.
 * It does nothing unless the launcher armed a one-shot request in the last 5 s; then it looks for Grok Bot's
 * end-call button, clicks it once, and goes back home. Nothing on screen is stored or logged.
 */
class HangupService : AccessibilityService() {
    companion object {
        const val WINDOW_MS = 5_000L; const val SEARCH_MS = 3_000L
        @Volatile private var armedAt = -1L
        @Volatile var instance: HangupService? = null; private set
        fun arm() { armedAt = SystemClock.elapsedRealtime() }
        fun armed(now: Long = SystemClock.elapsedRealtime()) = armedAt >= 0 && now - armedAt < WINDOW_MS
        fun disarm() { armedAt = -1L }
        fun enabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(ctx, HangupService::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        private val WORDS = listOf("end call", "hang up", "hangup", "end the call", "leave call", "end conversation", "end")
        private val IDS = listOf("end_call", "endcall", "hang_up", "hangup", "end-call", "hang-up")
        /** Pure matcher (unit-tested): does this label / view id look like an end-call control? */
        fun isHangup(text: CharSequence?, desc: CharSequence?, viewId: String?): Boolean {
            val id = viewId?.substringAfter(":id/")?.lowercase().orEmpty()
            if (IDS.any { id.contains(it) }) return true
            for (s in listOf(desc, text)) { val l = s?.toString()?.trim()?.lowercase() ?: continue
                if (l.isEmpty() || l.length > 30) continue
                if (WORDS.any { w -> l == w || (w != "end" && l.contains(w)) }) return true }
            return false
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private var searchStarted = -1L

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e?.packageName != Grok.PKG || !armed()) return
        tryHangup()
    }

    /** Called by the launcher right after it brings the call screen forward, and on Grok Bot window events. */
    fun tryHangup() {
        if (!armed()) return
        val now = SystemClock.elapsedRealtime(); if (searchStarted < 0) { searchStarted = now; main.postDelayed(::retry, 250) }
        val root = rootInActiveWindow ?: return
        if (root.packageName != Grok.PKG) return
        val node = find(root) ?: return
        var target: AccessibilityNodeInfo? = node; while (target != null && !target.isClickable) target = target.parent
        if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
            disarm(); searchStarted = -1L
            main.postDelayed({ runCatching { startActivity(Grok.homeIntent(this)) }; Call.end() }, 400)
        }
    }
    private fun retry() {
        if (!armed() || SystemClock.elapsedRealtime() - searchStarted > SEARCH_MS) { disarm(); searchStarted = -1L; return } // not found: leave the call screen open
        tryHangup(); if (armed()) main.postDelayed(::retry, 250)
    }
    private fun find(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isVisibleToUser && isHangup(n.text, n.contentDescription, n.viewIdResourceName)) return n
        for (i in 0 until n.childCount) { val c = n.getChild(i) ?: continue; find(c)?.let { return it } }
        return null
    }
}
