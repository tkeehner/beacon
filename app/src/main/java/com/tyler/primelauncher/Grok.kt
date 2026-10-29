package com.tyler.primelauncher

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Entry for every way the owner asks for Prime (orb tap, flick, Hey Prime, lock-alert tap). 0.1.20: everything goes through
 * the system assist path ([CallLaunch.fire]), like long-pressing Home. The trampoline + bounce-home below is only the
 * silent last-resort fallback.
 */
object Grok {
    const val PKG = "ai.x.grok.bot"
    const val TRAMPOLINE = "expo.modules.nativehelpers.AssistTrampolineActivity"
    const val TRIGGER_CHANNEL = "trigger"
    private const val TRIGGER_ID = 42
    const val HOME_DELAY_MS = 2000L
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var homeToken = 0L

    fun assistIntent(): Intent = Intent(Intent.ACTION_ASSIST).setComponent(ComponentName(PKG, TRAMPOLINE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    fun homeIntent(ctx: Context): Intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(ctx.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION)

    /** From the orb tap, wake word, or flick. [activity] lets the system assist path use [Activity.showAssist]. */
    fun fire(ctx: Context, source: String, activity: Activity? = null) {
        if (PrimeApp.homeVisible || Settings.canDrawOverlays(ctx)) runNow(ctx, source, activity) else postAlert(ctx)
    }
    fun runNow(ctx: Context, source: String, activity: Activity? = null) = CallLaunch.fire(ctx, source, activity)

    /** Schedule the bounce-home; cancelled / replaced by [onGrokCallNote] when the Voice calls notification lands. */
    fun homeSoon(ctx: Context) {
        val token = ++homeToken; val app = ctx.applicationContext
        CallLaunch.log("homeSoon: scheduled in ${HOME_DELAY_MS}ms token=$token")
        main.postDelayed({ if (token == homeToken) goHome(app, "timeout") }, HOME_DELAY_MS)
    }
    /** NotifService: a Grok Bot call-category / FGS notification just posted → bounce now (trampoline fallback only). */
    fun onGrokCallNote(ctx: Context) {
        if (homeToken == 0L) return
        CallLaunch.log("homeSoon: Voice-calls notification posted → bounce now")
        goHome(ctx.applicationContext, "call-note")
    }
    private fun goHome(app: Context, why: String) {
        homeToken = 0L
        CallLaunch.log("goHome why=$why")
        runCatching { app.startActivity(homeIntent(app), android.app.ActivityOptions.makeCustomAnimation(app, 0, 0).toBundle()) }
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
    /** Grok Bot's ongoing notifications by key (from NotifService): is-call flag + tap target for [Call.openCallScreen]. */
    private val grokNotes = java.util.concurrent.ConcurrentHashMap<String, Pair<Boolean, PendingIntent?>>()
    private val tracker = CallTracker()
    var onChange: (() -> Unit)? = null

    fun begin(source: String) { synchronized(this) { tracker.begin(SystemClock.elapsedRealtime()); refresh(SystemClock.elapsedRealtime(), null) } }
    fun end() { synchronized(this) { tracker.reset(); set(false) } }
    /** 0.1.21: Home lost focus/resume to the assist session (or Grok's call screen) since the call started. */
    @Volatile private var awayFromHome = false
    fun homeLeft() { if (active) awayFromHome = true }
    /**
     * 0.1.21: Home has focus again. With the 0.1.20 hang-up button gone, the overlay closing is the only "call ended"
     * moment Beacon sees, so from here the call needs live call audio (comm mode / voice playback) to stay lit.
     */
    fun homeBack(ctx: Context) {
        if (!awayFromHome) return; awayFromHome = false
        synchronized(this) { tracker.homeBack(); refresh(SystemClock.elapsedRealtime(), signals(ctx)) }
    }

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

    /** Bring Grok Bot's call screen forward so the owner can end it there (an app can't end another app's call). */
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
class CallTracker(private val pendingMs: Long = 40_000, private val graceMs: Long = 1_000, private val weakMaxMs: Long = 90_000) {
    var since = 0L; private set
    private var launchedAt = -1L; private var lastSeen = -1L; private var on = false
    private var strict = false; private var lastStrong = -1L
    fun begin(now: Long) { launchedAt = now; lastSeen = -1L; lastStrong = now; strict = false; if (!on) since = now }
    fun reset() { launchedAt = -1L; lastSeen = -1L; lastStrong = -1L; strict = false; on = false }
    /** Home is back on top after the assist session: drop launch pending, require real call audio. */
    fun homeBack() { if (on) { strict = true; launchedAt = -1L } }
    /** [s] null = no fresh reading (keep timing rules only). */
    fun update(now: Long, s: CallSignals?): Boolean {
        val launched = launchedAt >= 0
        val strong = s != null && (s.audioComm || s.voicePlayback)
        if (strong && (on || launched)) lastStrong = now
        // Weak evidence (a lingering Grok foreground note, Grok's own hotword mic) can't hold a call forever: 0.1.21
        // ends it once there's been no real call audio for [weakMaxMs], and ignores it entirely once Home is back.
        val weakOk = !strict && (lastStrong < 0 || now - lastStrong < weakMaxMs)
        val evidence = s != null && ((strong && (on || launched || s.grokNote)) || (weakOk && (s.grokCallNote || (s.grokNote && s.audio) || (launched && s.audio))))
        if (evidence) { if (!on) since = if (launched) launchedAt else now; lastSeen = now; on = true; if (lastStrong < 0) lastStrong = now; return true }
        val pending = launched && lastSeen < 0 && now - launchedAt < pendingMs
        // 0.1.22: 1 s grace (was 4 s); once Home is back, a fresh reading without real call audio (comm mode / voice
        // playback) ends it at once. Mic use is never a signal here: the wake word keeps the mic open.
        val grace = lastSeen >= 0 && now - lastSeen < graceMs && !(strict && s != null)
        val stale = lastStrong >= 0 && now - lastStrong >= weakMaxMs && !pending
        on = pending || (on && grace) || (on && s == null && !stale)
        if (!on) { launchedAt = -1L; lastSeen = -1L; lastStrong = -1L; strict = false }
        return on
    }
}
