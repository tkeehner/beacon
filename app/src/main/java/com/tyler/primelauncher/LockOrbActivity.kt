package com.tyler.primelauncher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController

/**
 * EXPERIMENTAL, OFF by default (Settings > Prime > "Sleeping orb on lock screen").
 * Shows Prime asleep over the lock screen. 0.1.5: ~[ARM_DELAY_MS] after it appears it AUTO-ARMS Android's own unlock prompt
 * (requestDismissKeyguard), so one touch of the fingerprint sensor goes straight home; no tap on the orb needed first.
 * Android never lets an app read the fingerprint or skip the secure keyguard; the system prompt does the unlocking.
 * Safety rules, all of which only ever remove this screen or hand off to the system unlock:
 *  - any touch also (re-)asks for the unlock prompt; if no answer comes within [TOUCH_FALLBACK_MS] the overlay leaves;
 *  - prompt cancelled (you backed out): the orb stays, and the prompt is NOT re-shown until you touch again (no loop);
 *  - prompt error / API failure: the overlay leaves at once;
 *  - Back, unlock (USER_PRESENT), screen off, leaving the screen, or [MAX_SHOW_MS] on screen finishes it;
 *  - it never holds the screen on, never disables the keyguard, and the secure keyguard stays underneath the whole time.
 */
class LockOrbActivity : Activity() {
    companion object {
        const val ARM_DELAY_MS = 400L; const val TOUCH_FALLBACK_MS = 1500L; const val MAX_SHOW_MS = 20_000L
        /** Tests only: Robolectric's Choreographer reposts frames with no delay, so looper idles would never return. */
        @Volatile var noFramesForTest = false
        /** Called from PrimeApp on SCREEN_ON. No-op unless the toggle is on and the phone is actually locked. */
        fun maybeShow(ctx: Context) {
            if (!Prefs(ctx).lockOrb || PrimeApp.homeVisible || Call.active) return
            if (ctx.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true) return
            runCatching { ctx.startActivity(Intent(ctx, LockOrbActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)) }
        }
    }
    private lateinit var v: SleepView
    private var done = false
    /** An unlock prompt request is in flight (waiting for its callback). */
    var armed = false; private set
    /** How many times the unlock prompt was requested (tests + audit). */
    var armCount = 0; private set
    private var autoArmed = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val autoArm = Runnable { if (!done && !autoArmed) { autoArmed = true; arm(fromTouch = false) } }
    private val touchFallback = Runnable { leave() }
    private val maxShow = Runnable { leave() }

    private val rx = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { leave() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true); setTurnScreenOn(true)
        v = SleepView(this) { arm(fromTouch = true) }
        setContentView(v)
        window.insetsController?.let { it.hide(WindowInsets.Type.systemBars()); it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        // 0.1.9: EXPORTED so SystemUI's USER_PRESENT reaches us (NOT_EXPORTED receivers are skipped for it); both actions are protected.
        registerReceiver(rx, IntentFilter().apply { addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_SCREEN_OFF) }, Context.RECEIVER_EXPORTED)
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true) { leave(); return }
        main.postDelayed(maxShow, MAX_SHOW_MS)
    }
    override fun onResume() {
        super.onResume(); v.start()
        if (!autoArmed && !done) { main.removeCallbacks(autoArm); main.postDelayed(autoArm, ARM_DELAY_MS) } // orb is seen waking, then the prompt arms
    }
    override fun onPause() { super.onPause(); v.stop() }
    /** Anything that takes this screen away (notification shade, camera, a call) ends it, so the next unlock is normal. */
    override fun onStop() { super.onStop(); leave() }
    override fun onDestroy() { main.removeCallbacksAndMessages(null); runCatching { unregisterReceiver(rx) }; v.stop(); super.onDestroy() }
    @Deprecated("Back simply removes the overlay") override fun onBackPressed() { leave() }

    /** Ask Android for its real unlock prompt (fingerprint / PIN / face). Never more than one request in flight. */
    private fun arm(fromTouch: Boolean) {
        if (done) return
        if (fromTouch) { main.removeCallbacks(touchFallback); main.postDelayed(touchFallback, TOUCH_FALLBACK_MS) } // a touch never leaves you stuck
        if (armed) return
        armed = true; armCount++
        val kg = getSystemService(KeyguardManager::class.java) ?: return leave()
        runCatching { kg.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { armed = false; leave() }
            // Backed out of the prompt: keep the sleeping orb, don't re-prompt until the next touch.
            override fun onDismissCancelled() { armed = false; main.removeCallbacks(touchFallback) }
            override fun onDismissError() { armed = false; leave() }
        }) }.onFailure { armed = false; leave() }
    }
    private fun leave() { if (done) return; done = true; main.removeCallbacksAndMessages(null); if (this::v.isInitialized) v.stop(); finish(); overridePendingTransition(0, 0) }

    /** Just the sleeping orb on black. */
    @SuppressLint("ViewConstructor")
    private class SleepView(ctx: Context, val onTouch: () -> Unit) : View(ctx), Choreographer.FrameCallback {
        private val d = resources.displayMetrics.density; private var orb: Orb? = null; private var on = false; private var last = 0L; private var skip = false
        private val prefs = Prefs(ctx)
        init { setBackgroundColor(Color.BLACK) }
        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            orb?.recycle(); orb = Orb(w / d / 2, h / d * 0.42f, if (prefs.neon) 96f else 102f, if (prefs.neon) Skin.NEON else Skin.MATRIX, d, 0.55, null, false).also { it.sleep.sleepNow(); it.still = true }
        }
        fun start() { if (noFramesForTest) return; if (!on) { on = true; last = 0; Choreographer.getInstance().postFrameCallback(this) } }
        fun stop() { on = false; Choreographer.getInstance().removeFrameCallback(this) }
        override fun doFrame(ns: Long) {
            if (!on) return; Choreographer.getInstance().postFrameCallback(this)
            skip = !skip; if (skip) return // 30 fps: a sleeping, slowly breathing orb doesn't need 60
            val dt = if (last == 0L) 1 / 30.0 else ((ns - last) / 1e9).coerceIn(0.0, 0.1); last = ns
            orb?.step(dt * 0.6); invalidate()
        }
        override fun onDraw(cv: Canvas) { val o = orb ?: return; cv.save(); cv.scale(d, d); o.draw(cv); cv.restore() }
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean { if (e.actionMasked == MotionEvent.ACTION_DOWN) onTouch(); return true }
        override fun onDetachedFromWindow() { stop(); orb?.recycle(); orb = null; super.onDetachedFromWindow() }
    }
}
