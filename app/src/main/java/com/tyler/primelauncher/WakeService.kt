package com.tyler.primelauncher

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer

/**
 * "Hey Prime" listener: a microphone foreground service that exists only while listening.
 * Runs only while the screen is on and unlocked (PrimeApp stops it on SCREEN_OFF / lock).
 * Final results only, threshold 0.80, 10 s cooldown, then waits for Grok Bot's call to release the mic.
 */
class WakeService : Service() {
    companion object {
        private const val ACTION_STOP = "com.tyler.primelauncher.STOP_WAKE"
        private const val CHANNEL = "listening"; private const val NOTIF_ID = 1; private const val RATE = 16000
        const val THRESHOLD = 0.80; const val COOLDOWN_MS = 10_000L
        @Volatile var running = false; private set
        @Volatile var micOpen = false; private set
        @Volatile var status = "off"; private set
        /** One listener per process, across service instances (0.1.12). */
        private val worker = SingleWorker("prime-wake")

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Listening for Hey Prime", NotificationManager.IMPORTANCE_MIN))
            nm.createNotificationChannel(NotificationChannel(Grok.TRIGGER_CHANNEL, "Hey Prime heard", NotificationManager.IMPORTANCE_HIGH))
        }
        fun screenUsable(ctx: Context): Boolean = ctx.getSystemService(PowerManager::class.java).isInteractive && !ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        fun allowed(ctx: Context, p: Prefs = Prefs(ctx)): Boolean = p.wake && ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            screenUsable(ctx) && (!p.saver || PrimeApp.homeVisible)
        /** Start if allowed; Android may refuse a background start, in which case the home screen retries on resume. */
        fun sync(ctx: Context) {
            if (allowed(ctx)) { if (!running) runCatching { ctx.startForegroundService(Intent(ctx, WakeService::class.java)) }.onFailure { status = "waiting for home screen" } }
            else if (running) runCatching { ctx.startService(Intent(ctx, WakeService::class.java).setAction(ACTION_STOP)) }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { shutdown(); return START_NOT_STICKY }
        createChannels(this)
        try { startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) } catch (e: Exception) { status = "mic blocked (open home)"; stopSelf(); return START_NOT_STICKY }
        running = true
        worker.start { tok -> loop(tok) }
        return START_NOT_STICKY
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Grok.homeIntent(this), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, WakeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat).setContentTitle("Hey Prime is on")
            .setContentText("On-device only. Stops when the screen locks.").setOngoing(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
    }
    private fun shutdown() { worker.stop(); running = false; micOpen = false; status = "off"; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { worker.stop(); running = false; micOpen = false; super.onDestroy() }

    @SuppressLint("MissingPermission")
    private fun openMic(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val ar = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE)) }.getOrNull() ?: return null
        if (ar.state != AudioRecord.STATE_INITIALIZED) { ar.release(); return null }
        ar.startRecording(); micOpen = true; return ar
    }
    private fun others(am: AudioManager, mine: Boolean) = (am.activeRecordingConfigurations.size - if (mine) 1 else 0).coerceAtLeast(0)

    private fun loop(tok: SingleWorker.Token) {
        var model: Model? = null; var rec: Recognizer? = null; var ar: AudioRecord? = null; val am = getSystemService(AudioManager::class.java)
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS); status = "preparing model"
            model = Model(ModelInstaller.ensure(this).absolutePath)
            rec = Recognizer(model, RATE.toFloat(), ModelInstaller.grammar(this)).apply { setWords(true) }
            val buf = ShortArray(RATE / 10); var gate = TriggerGate(COOLDOWN_MS); var pausedUntil = 0L; var baseline = 0; var paused = false
            while (!tok.stopped) {
                if (Call.active) { Call.poll(this) } // keep the call state fresh while home isn't on screen
                if (Call.active) { // live Grok Bot call: give it the mic until the call ends
                    if (ar != null) { runCatching { ar.stop(); ar.release() }; ar = null; micOpen = false }
                    status = "paused (call)"; PrimeApp.micLevel = 0f; paused = false; Thread.sleep(400); continue
                }
                if (paused) {
                    if (ResumePolicy.shouldResume(SystemClock.elapsedRealtime(), pausedUntil, others(am, false), baseline)) { paused = false; rec.reset(); gate = TriggerGate(COOLDOWN_MS) }
                    else { status = "paused (call)"; Thread.sleep(300); continue }
                }
                if (ar == null) { ar = openMic() ?: run { status = "mic busy"; Thread.sleep(1000); null } ?: continue; status = "listening" }
                val n = ar.read(buf, 0, buf.size); if (n <= 0) continue
                PrimeApp.micLevel = level(buf, n)
                if (!rec.acceptWaveForm(buf, n)) continue
                val m = WakeWordMatcher.match(rec.result, THRESHOLD)
                if (m is MatchResult.Hit && gate.tryFire(SystemClock.elapsedRealtime())) {
                    baseline = others(am, true); ar.stop(); ar.release(); ar = null; micOpen = false // hand the mic to Grok Bot
                    paused = true; pausedUntil = SystemClock.elapsedRealtime() + COOLDOWN_MS
                    Grok.fire(this, "wake word")
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Throwable) { status = "error: ${e.javaClass.simpleName}"
        } finally {
            runCatching { ar?.stop(); ar?.release() }; micOpen = false; runCatching { rec?.close() }; runCatching { model?.close() }
            if (!tok.stopped) { running = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
    }
    private fun level(b: ShortArray, n: Int): Float { var s = 0.0; for (i in 0 until n) s += b[i] * b[i].toDouble(); return (Math.sqrt(s / n) / 4000.0).toFloat().coerceIn(0f, 1f) }
}
