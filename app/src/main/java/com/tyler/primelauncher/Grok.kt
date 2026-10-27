package com.tyler.primelauncher

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings

/** "Hey Prime" method C: ASSIST into Grok Bot's trampoline, then back to this home screen after 2 s while the call keeps going. */
object Grok {
    const val PKG = "ai.x.grok.bot"
    const val TRAMPOLINE = "expo.modules.nativehelpers.AssistTrampolineActivity"
    const val TRIGGER_CHANNEL = "trigger"
    private const val TRIGGER_ID = 42
    const val HOME_DELAY_MS = 2000L
    private val main = Handler(Looper.getMainLooper())

    fun assistIntent(): Intent = Intent(Intent.ACTION_ASSIST).setComponent(ComponentName(PKG, TRAMPOLINE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    fun homeIntent(ctx: Context): Intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(ctx.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

    /** From the orb tap or the wake word. */
    fun fire(ctx: Context, source: String) {
        if (PrimeApp.homeVisible || Settings.canDrawOverlays(ctx)) runNow(ctx, source) else postAlert(ctx)
    }
    fun runNow(ctx: Context, source: String) {
        val app = ctx.applicationContext
        val ok = runCatching { app.startActivity(assistIntent()); true }.getOrElse { Apps.launchPkg(app, PKG) }
        if (!ok) { Call.end(); return }
        Call.begin(source)
        main.postDelayed({ runCatching { app.startActivity(homeIntent(app)) } }, HOME_DELAY_MS)
    }
    private fun postAlert(ctx: Context) {
        val pi = PendingIntent.getActivity(ctx, 7, Intent(ctx, TriggerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, TRIGGER_CHANNEL).setSmallIcon(R.drawable.ic_stat).setContentTitle("Hey Prime")
            .setContentText("Tap to talk to Prime").setCategory(Notification.CATEGORY_CALL).setFullScreenIntent(pi, true)
            .setContentIntent(pi).setAutoCancel(true).setTimeoutAfter(15_000).build()
        ctx.getSystemService(NotificationManager::class.java).notify(TRIGGER_ID, n)
    }
    fun cancelAlert(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(TRIGGER_ID)
}

/**
 * Is the owner on a live voice call with Grok Bot? No root, so it is inferred from signals any app may read:
 * 1. Grok Bot's own ongoing notification (seen by our NotificationListener) - a foreground-service / call-category one counts on its own;
 * 2. the audio system: MODE_IN_COMMUNICATION / MODE_IN_CALL, a voice-communication player, or another app holding the mic.
 * Audio alone only counts for a call this launcher started (orb tap / Hey Prime), so a WhatsApp call doesn't light Prime up.
 */
object Call {
    @Volatile var active = false; private set
    @Volatile var startedAt = 0L; private set
    /** Grok Bot's ongoing notifications by key (from NotifService): is-call flag + tap target for the hang-up button. */
    private val grokNotes = java.util.concurrent.ConcurrentHashMap<String, Pair<Boolean, PendingIntent?>>()
    private val tracker = CallTracker()
    var onChange: (() -> Unit)? = null

    fun begin(source: String) { synchronized(this) { tracker.begin(SystemClock.elapsedRealtime()); refresh(SystemClock.elapsedRealtime(), null) } }
    fun end() { synchronized(this) { tracker.reset(); set(false) } }

    /** NotifService feed. */
    fun grokPosted(key: String, isCall: Boolean, tap: PendingIntent?) { grokNotes[key] = isCall to tap }
    fun grokRemoved(key: String) { grokNotes.remove(key) }
    fun grokReset() { grokNotes.clear() }
    /** Best way back into the call screen: the call notification's own tap target. */
    fun callIntent(): PendingIntent? = grokNotes.values.sortedByDescending { it.first }.firstOrNull { it.second != null }?.second

    fun signals(ctx: Context): CallSignals {
        val am = ctx.getSystemService(AudioManager::class.java)
        val mode = runCatching { am.mode }.getOrDefault(AudioManager.MODE_NORMAL)
        val voicePlay = runCatching { am.activePlaybackConfigurations.any { it.audioAttributes.usage == android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION } }.getOrDefault(false)
        val others = runCatching { am.activeRecordingConfigurations.size - (if (WakeService.micOpen) 1 else 0) }.getOrDefault(0).coerceAtLeast(0)
        val notes = grokNotes.values
        return CallSignals(notes.isNotEmpty(), notes.any { it.first }, mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL, voicePlay, others)
    }
    /** Poll from the UI clock and the wake-word thread. */
    fun poll(ctx: Context) { synchronized(this) { refresh(SystemClock.elapsedRealtime(), signals(ctx)) } }
    private fun refresh(now: Long, s: CallSignals?) { set(tracker.update(now, s)) }
    private fun set(on: Boolean) {
        if (on == active) return
        if (on) startedAt = tracker.since; active = on
        onChange?.let { f -> Handler(Looper.getMainLooper()).post(f) }
    }

    /** Hang-up button: bring Grok Bot's call screen forward so the owner can end it there (an app can't end another app's call). */
    fun openCallScreen(ctx: Context): Boolean {
        val pi = callIntent()
        if (pi != null) {
            val opts = if (android.os.Build.VERSION.SDK_INT >= 34) android.app.ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle() else null
            if (runCatching { pi.send(ctx, 0, null, null, null, null, opts); true }.getOrDefault(false)) return true
        }
        return Apps.launchPkg(ctx, Grok.PKG)
    }
}

data class CallSignals(val grokNote: Boolean, val grokCallNote: Boolean, val audioComm: Boolean, val voicePlayback: Boolean, val othersRecording: Int) {
    val audio get() = audioComm || voicePlayback || othersRecording > 0
}

/** Pure call state machine (unit-tested). Times are elapsedRealtime ms. */
class CallTracker(private val pendingMs: Long = 40_000, private val graceMs: Long = 4_000) {
    var since = 0L; private set
    private var launchedAt = -1L; private var lastSeen = -1L; private var on = false
    fun begin(now: Long) { launchedAt = now; lastSeen = -1L; if (!on) since = now }
    fun reset() { launchedAt = -1L; lastSeen = -1L; on = false }
    /** [s] null = no fresh reading (keep timing rules only). */
    fun update(now: Long, s: CallSignals?): Boolean {
        val launched = launchedAt >= 0
        val evidence = s != null && (s.grokCallNote || (s.grokNote && s.audio) || (launched && s.audio))
        if (evidence) { if (!on) since = if (launched) launchedAt else now; lastSeen = now; on = true; return true }
        val pending = launched && lastSeen < 0 && now - launchedAt < pendingMs
        val grace = lastSeen >= 0 && now - lastSeen < graceMs
        on = pending || (on && grace) || (on && s == null)
        if (!on) { launchedAt = -1L; lastSeen = -1L }
        return on
    }
}
