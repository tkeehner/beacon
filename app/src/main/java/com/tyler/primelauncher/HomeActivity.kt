package com.tyler.primelauncher

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sin

/** The home screen. Everything is built in code; the orb and wallpaper live in [StageView]. */
class HomeActivity : androidx.activity.ComponentActivity(), StageView.Host, NotifStore.Listener, CompanionBrain.Hooks {
    lateinit var prefs: Prefs; lateinit var stage: StageView; lateinit var pal: Pal
    /** 0.1.15 companion brain (scheduler, persona lines, home moments). */
    lateinit var brain: CompanionBrain
    private lateinit var root: FrameLayout; private lateinit var overlay: FrameLayout
    private lateinit var tTime: TextView; private lateinit var tDate: TextView; lateinit var wxTiles: WxTiles
    private lateinit var pill: TextView; private lateinit var torchChip: TextView; private lateinit var hint: TextView; private lateinit var ringHintV: TextView
    private lateinit var bubbles: FrameLayout; private lateinit var unread: TextView; private var tray: View? = null
    private lateinit var pbox: LinearLayout; private lateinit var pmsg: EditText; private lateinit var mxChip: TextView
    private lateinit var callTime: TextView
    /** Emit's in-app keyboard for the Message Prime box (see PrimeKeyboard / emitkb). */
    lateinit var keys: PrimeKeyboard; private set
    private var kbH = 0; private var eatTouch = false; private var lastBars = ""
    private var panel: View? = null; private var panelKind = ""
    private val main = Handler(Looper.getMainLooper()); private val bg = Executors.newSingleThreadExecutor()
    private val tiles = LruCache<String, Bitmap>(160)
    private var topIn = 24f; private var botIn = 48f; private var imeIn = 0f
    private var lastSec = -1L; private var moodAt = 0L; private var mood = Logic.Mood("daytime", 1.0, 1.0, 1.0, 0.0, 1f)
    private var typingUntil = 0L; private var thinkUntil = 0L; private var callLvl = 0.0
    private var torchOn = false; private var torchId: String? = null
    private var wx: Wx? = null; private var wxBusy = false

    // ---------- lifecycle ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        prefs = Prefs(this); pal = Pal(prefs.neon, prefs.odst); IconArt.load(this)
        root = FrameLayout(this); stage = StageView(this, prefs, this); root.addView(stage, FrameLayout.LayoutParams(MATCH, MATCH))
        brain = CompanionBrain(this, prefs, stage, this); stage.buddy.listener = brain
        overlay = FrameLayout(this); root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        keys = PrimeKeyboard(this).also { it.theme(prefs.theme); it.onChange = { placeBox(); renderKbChip() } }
        root.addView(keys.view, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        keys.view.addOnLayoutChangeListener { v, _, t, _, b, _, ot, _, ob -> if (b - t != ob - ot) { kbH = v.height; placeBox() } }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) { override fun handleOnBackPressed() = back() })
        Call.onChange = { renderCall(); WakeService.sync(this) }
        stage.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or2, ob -> if (r - l != or2 - ol || b - t != ob - ot) main.post { buildOverlay() } }
        root.setOnApplyWindowInsetsListener { _, ins ->
            val sb = ins.getInsets(WindowInsets.Type.systemBars()); val ime = ins.getInsets(WindowInsets.Type.ime())
            // Root cause of 0.1.0's "keyboard never opens": every insets pass (including the IME's own) rebuilt the whole
            // overlay, destroying the focused EditText, so the keyboard closed as soon as it started to open.
            // Now only a system-bar change rebuilds; the IME just moves the box.
            topIn = sb.top / stage.d; botIn = sb.bottom / stage.d; imeIn = ime.bottom / stage.d; keys.navInset(botIn)
            val bars = "${sb.top}/${sb.bottom}"
            if (bars != lastBars) { lastBars = bars; stage.layoutInsets(topIn, botIn); buildOverlay() } else placeBox()
            ins
        }
        wx = Wx.from(prefs.wxCache)
        buildOverlay()
        NotifStore.listener = this
        getSystemService(LauncherApps::class.java).registerCallback(object : LauncherApps.Callback() {
            override fun onPackageRemoved(p: String, u: UserHandle) = reloadApps()
            override fun onPackageAdded(p: String, u: UserHandle) = reloadApps()
            override fun onPackageChanged(p: String, u: UserHandle) = reloadApps()
            override fun onPackagesAvailable(p: Array<out String>, u: UserHandle, r: Boolean) = reloadApps()
            override fun onPackagesUnavailable(p: Array<out String>, u: UserHandle, r: Boolean) = reloadApps()
        }, main)
        initTorch()
        // 0.1.8: EXPORTED on purpose. USER_PRESENT is sent by SystemUI (its own app uid, not the system), and Android skips
        // RECEIVER_NOT_EXPORTED receivers for it - the phone's broadcast history showed ours "Skipped" on every unlock.
        // Both actions are protected broadcasts, so no other app can fake them.
        registerReceiver(lockRx, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT) }, Context.RECEIVER_EXPORTED)
        reloadApps()
        if (!prefs.onboarded) main.postDelayed({ openOnboarding() }, 400)
    }
    override fun onResume() {
        super.onResume(); PrimeApp.homeVisible = true; prefs.homeVisits = prefs.homeVisits + 1; HookImport.poke(this)
        registerReceiver(battRx, IntentFilter().apply { addAction(Intent.ACTION_BATTERY_CHANGED); addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED) })
        stage.start(); val locked = keyguardLocked(); val act = wakeGate.resumed(SystemClock.elapsedRealtime(), locked)
        // Self-heal: the gate says nothing to do and nothing is locked, yet Prime is still asleep (a missed broadcast) -> wake it.
        applyWake(if (act == WakeGate.Act.NONE && !locked && !wakeGate.asleep && stage.ready && stage.orb.sleep.asleep) WakeGate.Act.WAKE else act); WakeService.sync(this); reloadApps(); refreshWx(false, Weather.RESUME_MS); onNotesChanged(); Call.homeBack(this); Call.poll(this); renderCall()
        hint.visibility = if (prefs.homeVisits <= 5) View.VISIBLE else View.GONE
        main.postDelayed({ hint.animate().alpha(0f).setDuration(600).start() }, 7000)
        if (panelKind == "onboard") openOnboarding()
    }
    override fun onPause() {
        super.onPause(); Call.homeLeft(); brain.pausedHome(); wakeGate.paused(); keys.hide(); CallLevel.stop(); PrimeApp.homeVisible = false; runCatching { unregisterReceiver(battRx) }; stage.stop(); stage.closeRing(); WakeService.sync(this)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (keys.open) keys.hide() else if (panel != null) closePanel() else if (tray != null) closeTray() else stage.closeRing()
    }
    /** Back closes the keyboard, then panels; the home screen itself has nowhere to go back to. */
    fun back() { when { keys.open -> keys.hide(); panel != null -> closePanel(); tray != null -> closeTray(); stage.ringOpen -> stage.closeRing(); else -> {} } }

    override fun onDestroy() { runCatching { unregisterReceiver(lockRx) }; super.onDestroy() }

    // ---------- sleep on lock, wake on unlock ----------
    private val wakeGate = WakeGate()
    private fun keyguardLocked() = getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
    /** Unlock straight to Home: focus arrives once the keyguard has gone (after onResume), so wake there. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) Call.homeLeft() else { Call.homeBack(this); renderCall() } // 0.1.21: assist overlay closed -> stop the call orb
        if (hasFocus && wakeGate.asleep) applyWake(wakeGate.arrived(SystemClock.elapsedRealtime(), keyguardLocked()))
    }
    private var gateCheckAt = 0L
    /** Backstop while resumed + asleep (cheap, 5x/s): the moment the keyguard is gone, Home has arrived. */
    private fun pollArrival() {
        if (!wakeGate.asleep) return; val now = SystemClock.elapsedRealtime(); if (now - gateCheckAt < 200) return; gateCheckAt = now
        applyWake(wakeGate.arrived(now, keyguardLocked()))
    }
    private val lockRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> { wakeGate.screenOff(); brain.screenOff(); stage.sleepOrb(); applyReveal() } // nobody is watching: hide now so unlock fades up from black
                Intent.ACTION_USER_PRESENT -> applyWake(wakeGate.unlocked(SystemClock.elapsedRealtime()))
            }
        }
    }
    /** WAKE = an actual unlock-to-home: the slow wake + whole-screen fade. AWAKE_QUIET (stale unlock): instant. NONE: quick return. */
    private fun applyWake(a: WakeGate.Act) {
        when (a) {
            WakeGate.Act.WAKE -> { stage.wakeOrb(); brain.onWake() }
            WakeGate.Act.AWAKE_QUIET -> { if (stage.ready) stage.orb.sleep.copyFrom(SleepState()) else stage.wakeOrb(SleepState.WAKE_S) }
            WakeGate.Act.NONE -> {}
        }
        applyReveal()
    }
    /** 0.1.7: the widgets/tiles fade with the orb's wake ([StageView.reveal]). A hardware layer only while partly transparent. */
    private fun applyReveal() {
        if (!this::overlay.isInitialized) return; val a = stage.reveal; if (overlay.alpha == a) return
        val mid = a > 0f && a < 1f; val lt = if (mid) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE
        if (overlay.layerType != lt) overlay.setLayerType(lt, null)
        overlay.alpha = a
    }
    /** Tests: current widget opacity. */
    val overlayAlpha get() = overlay.alpha

    private val battRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_POWER_CONNECTED && stage.ready) { stage.plugT = stage.orb.t; stage.orb.kick(); brain.onPlug() }
            if (i.action == Intent.ACTION_BATTERY_CHANGED) { val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 80); val s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0; stage.level = l.toFloat() / s.coerceAtLeast(1); stage.charging = Logic.charging(plugged, stage.level) }
        }
    }

    private fun reloadApps() { bg.execute { Apps.refresh(this); main.post { if (this::stage.isInitialized && stage.width > 0) stage.refreshRing(); tiles.evictAll(); if (panelKind == "drawer") renderDrawer() } } }

    // ---------- overlay (home widgets) ----------
    private fun buildOverlay() {
        val keepText = if (this::pmsg.isInitialized) pmsg.text.toString() else ""
        val reopen = this::keys.isInitialized && keys.open && panel == null // a rebuild must never drop an open keyboard
        val keepSel = if (this::pmsg.isInitialized) pmsg.selectionStart else -1
        if (this::pmsg.isInitialized) pmsg.onFocusChangeListener = null // removing the old box must not read as "focus left"
        overlay.removeAllViews(); pal = Pal(prefs.neon, prefs.odst); val ctx = this
        val widget = col().apply { setPadding(dpi(24f), dpi(topIn + 18f), dpi(24f), 0) }
        tTime = text(pal, "", if (prefs.neon || prefs.odst) 64f else 58f, pal.hi, bold = true); tTime.setShadowLayer(dp(10f), 0f, 0f, pal.hi)
        tDate = text(pal, "", 14f, pal.body); tDate.setPadding(0, dpi(4f), 0, dpi(6f))
        wxTiles = WxTiles(this, pal).apply { setOnClickListener { toast("Updating weather…"); refreshWx(true) } } // 0.1.23: seven-hour tiles
        widget.addView(tTime); widget.addView(tDate); widget.addView(wxTiles, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dpi(2f) }) // 0.1.24: same margins as the date
        overlay.addView(widget, FrameLayout.LayoutParams(MATCH, WRAP))
        widget.addOnLayoutChangeListener { v, _, _, _, b, _, _, _, ob -> if (b != ob || stage.headerBottom < 0) stage.headerBottom = v.bottom / stage.d } // 0.1.11: ring stays below this
        torchChip = text(pal, pal.up("flashlight on"), 11f, pal.bg, bold = true).apply { setPadding(dpi(10f), dpi(5f), dpi(10f), dpi(5f)); background = pal.chip(ctx, true); visibility = View.GONE; setOnClickListener { setTorch(false) } }
        overlay.addView(torchChip, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.TOP).apply { topMargin = dpi(topIn + 22f); rightMargin = dpi(18f) })
        pill = text(pal, "", 12f, pal.bg, bold = true).apply { setPadding(dpi(12f), dpi(6f), dpi(12f), dpi(6f)); background = pal.chip(ctx, true); visibility = View.GONE; setOnClickListener { Apps.launchPkg(ctx, Grok.PKG) } }
        overlay.addView(pill, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dpi(topIn + 4f) })
        val orbBottom = stage.cy + stage.orbR()
        hint = text(pal, pal.up("hold to summon · tap to talk · swipe up for apps"), 11f, pal.dim).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        overlay.addView(hint, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP).apply { topMargin = dpi(orbBottom + 30f) })
        // Live call: a quiet timer under the orb (0.1.20: no on-screen hang-up button).
        callTime = text(pal, "", 12f, pal.dim).apply { gravity = Gravity.CENTER; visibility = View.GONE; letterSpacing = 0.12f }
        overlay.addView(callTime, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP).apply { topMargin = dpi(orbBottom + 50f) })
        ringHintV = text(pal, "", 12f, pal.p).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        overlay.addView(ringHintV, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { bottomMargin = dpi(botIn + 100f) })
        bubbles = FrameLayout(this).apply { clipChildren = false }
        overlay.addView(bubbles, FrameLayout.LayoutParams(MATCH, MATCH))
        unread = text(pal, "", 12f, pal.bg, bold = true).apply { gravity = Gravity.CENTER; visibility = View.GONE
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(pal.p2); setStroke(dpi(2f), pal.bg) }; setOnClickListener { if (tray == null) openTray() else closeTray() } }
        overlay.addView(unread, FrameLayout.LayoutParams(dpi(30f), dpi(30f)).apply { leftMargin = dpi(stage.cxOrb() + stage.orbR() * 0.74f - 15f); topMargin = dpi(stage.cy - stage.orbR() * 1.02f - 15f) })
        // Message Prime box
        pmsg = EditText(this).apply { hint = pal.up("Message Prime"); setHintTextColor(pal.dim); setTextColor(pal.body); typeface = pal.font; textSize = 15f; background = null
            setSingleLine(true); imeOptions = EditorInfo.IME_ACTION_SEND; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEND) { sendPrime(); true } else false }
            setText(keepText); addTextChangedListener(typingWatcher { typed(this) }) }
        val sendLabel = if (prefs.neon || prefs.odst) "SEND" else "send"
        keys.theme(prefs.theme); keys.attach(pmsg, sendLabel) { sendPrime() }
        // Emit's [mx] chip: shown while the system keyboard is in use (Emit's ⌨ key); tap to go back to Emit's keys.
        mxChip = text(pal, "[mx]", 13f, pal.p, bold = true).apply { setPadding(dpi(6f), dpi(8f), dpi(8f), dpi(8f)); visibility = View.GONE; setOnClickListener { keys.useMatrix() } }
        val send = text(pal, "\u27A4", 18f, pal.bg, bold = true).apply { gravity = Gravity.CENTER; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(pal.p) }; setOnClickListener { sendPrime() } }
        pbox = row().apply { background = pal.panel(ctx, 24f, 0xD8); setPadding(dpi(16f), dpi(4f), dpi(6f), dpi(4f)) }
        pbox.addView(pmsg, LinearLayout.LayoutParams(0, dpi(46f), 1f)); pbox.addView(mxChip, LinearLayout.LayoutParams(WRAP, WRAP)); pbox.addView(send, LinearLayout.LayoutParams(dpi(38f), dpi(38f)))
        overlay.addView(pbox, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { leftMargin = dpi(16f); rightMargin = dpi(16f); bottomMargin = boxMargin() })
        if (keepSel in 0..pmsg.text.length) pmsg.setSelection(keepSel)
        if (reopen) keys.show(pmsg, sendLabel) { sendPrime() } else if (this::keys.isInitialized && keys.open) keys.hide()
        tickClock(true); renderWx(); onNotesChanged(); renderCall(); torchChip.visibility = if (torchOn) View.VISIBLE else View.GONE
    }
    private fun typingWatcher(f: () -> Unit) = object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { f() }; override fun afterTextChanged(s: Editable?) {} }
    /** Typing reaction: Prime looks at the caret and "listens", then sweeps as if reading. */
    private fun typed(e: EditText) {
        if (prefs.reduce || !stage.ready) return
        val lay = e.layout; val cx = if (lay != null) lay.getPrimaryHorizontal(e.selectionStart.coerceAtLeast(0)) / stage.d else 0f
        val loc = IntArray(2); e.getLocationOnScreen(loc); val sx = loc[0] / stage.d + cx; val sy = loc[1] / stage.d
        stage.orb.lookAt(((sx - stage.cxOrb()) / stage.W * 0.32).toDouble(), ((sy - stage.cy) / stage.H * 0.3).toDouble())
        typingUntil = SystemClock.uptimeMillis() + 1200
    }

    private fun tickClock(force: Boolean) {
        val now = System.currentTimeMillis(); val sec = now / 1000; if (!force && sec == lastSec) return; lastSec = sec
        val is24 = android.text.format.DateFormat.is24HourFormat(this)
        tTime.text = SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.getDefault()).format(Date(now))
        tDate.text = pal.up(SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date(now)))
        if (now - (wx?.fetched ?: 0L) > Weather.REFRESH_MS) refreshWx(false)
        if (sec % 60 == 0L) renderWx() // roll tiles forward on the hour
        renderCall()
    }
    private fun renderCall() {
        if (!this::callTime.isInitialized) return
        pill.visibility = View.GONE
        val on = Call.active && panel == null && !stage.ringOpen
        if (on) { val s = (SystemClock.elapsedRealtime() - Call.startedAt) / 1000; callTime.text = Logic.callClock(s); hint.visibility = View.GONE }
        if (on != (callTime.visibility == View.VISIBLE)) {
            callTime.visibility = if (on) View.VISIBLE else View.GONE
        }
    }

    // ---------- weather ----------
    private fun refreshWx(force: Boolean) {
        refreshWx(force, Weather.REFRESH_MS)
    }
    private var wxTried = 0L
    /** 0.1.23: Home open/resume refreshes location + weather if the last try is older than [Weather.RESUME_MS]. */
    private fun refreshWx(force: Boolean, maxAge: Long) {
        if (wxBusy) return; val now = System.currentTimeMillis(); val w = wx
        if (!force && now - wxTried < 60_000L) return
        if (!force && w != null && w.hours.isNotEmpty() && Weather.locState == Weather.LOC_OK && now - w.fetched < maxAge) return
        wxTried = now; wxBusy = true; renderWx()
        Weather.fetchAsync(this, prefs) { r -> wxBusy = false; if (r != null) wx = r else if (force && Weather.locState == Weather.LOC_OK) toast("Weather is offline"); renderWx() }
    }
    private fun renderWx() {
        if (!this::wxTiles.isInitialized) return; val w = wx; val now = System.currentTimeMillis()
        wxTiles.setPal(pal)
        val loc = if (!prefs.wxGps) Weather.LOC_OK else if (Weather.permState(this) == Weather.LOC_OFF) Weather.LOC_OFF else Weather.locState
        val status = when {
            loc == Weather.LOC_OFF -> "Location off"
            loc == Weather.LOC_NONE -> "Location not gathered"
            w == null || w.upcoming(now).isEmpty() -> if (wxBusy) "Weather…" else "Weather offline"
            else -> null }
        wxTiles.show(if (status == null) w!!.upcoming(now) else emptyList(), status)
    }

    // ---------- per-frame ----------
    override fun onFrame(t: Double) {
        pollArrival(); applyReveal(); tickClock(false); val o = stage.orb; val now = SystemClock.uptimeMillis()
        if (now - moodAt > 1000) { moodAt = now; val c = Calendar.getInstance(); mood = Logic.mood(c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60.0, stage.level, stage.charging); Call.poll(this); stage.dimOverlay = (1f - mood.dim).coerceAtLeast(0f) }
        val k = 0.02; o.pace += (mood.pace * (if (prefs.reduce) 0.5 else 1.0) - o.pace) * k; o.bright += (mood.bright - o.bright) * k; o.lid += (mood.lid - o.lid) * k; o.tired += (mood.tired - o.tired) * k
        // 0.1.13 personality: mood sets how curious Prime is (glances / perk-ups / fidgets) and whether it yawns; Reduce Motion halves it all.
        o.lively += (mood.lively - o.lively) * k; o.yawny = mood.yawn; o.amp = if (prefs.reduce) 0.5 else 1.0
        if (PrimeApp.homeVisible && panel == null) brain.frame()
        if (stage.ringOpen) return
        stage.inCall = Call.active
        if (Call.active && PrimeApp.homeVisible) CallLevel.start() else CallLevel.stop()
        if (Call.active) { val real = CallLevel.read(); callLvl += ((real ?: Logic.callPulse(t, prefs.reduce)) - callLvl) * (if (real != null) 0.35 else 0.12) }
        stage.callLvl = callLvl
        o.mode = when { Call.active -> Mode.TALK; now < thinkUntil -> Mode.THINK; now < typingUntil -> Mode.LISTEN; else -> Mode.IDLE }
        o.lvl = if (Call.active) callLvl else if (o.mode == Mode.LISTEN) 0.25 else 0.0
    }

    // ---------- StageView.Host ----------
    override fun ringApps(): List<AppEntry> = Apps.ring(this, prefs.pins)
    override fun onOrbTap() { stage.orb.kick(); Grok.fire(this, "orb tap", this) }
    override fun onRingPick(a: AppEntry) { brain.recordLaunch(a.pkg); Apps.launch(this, a) }
    override fun nudgeIndex(): Int = brain.nudgeIndex(Apps.ring(this, prefs.pins).map { it.pkg })
    // ---------- CompanionBrain.Hooks ----------
    override fun companionBusy(): Boolean { val now = SystemClock.uptimeMillis(); return keys.open || panel != null || tray != null || sending || now < typingUntil || now < thinkUntil }
    override fun noteBubblesShowing(): Boolean = shown.isNotEmpty()
    /** A tapped orb bubble opens Message Prime with a draft typed in. Nothing is sent until you press send. */
    override fun openPrimeDraft(text: String) {
        if (!this::pmsg.isInitialized || panel != null) return
        stage.closeRing(); pmsg.setText(text); pmsg.setSelection(text.length); keys.show(pmsg, if (prefs.neon || prefs.odst) "SEND" else "send") { sendPrime() }
    }
    override fun onFlick(dir: String) { stage.orb.kick(); quick(prefs.quick(dir)) }
    override fun onSwipeUp() = openDrawer()
    override fun onSwipeDown() = openSearch()
    override fun onLongPressBg() = openSettings()
    override fun ringHint(s: String?) { if (!this::ringHintV.isInitialized) return; ringHintV.text = s?.let { pal.up(it) } ?: ""; ringHintV.visibility = if (s == null) View.GONE else View.VISIBLE; pbox.visibility = if (s == null) View.VISIBLE else View.INVISIBLE; renderCall() }

    fun quick(action: String) {
        when {
            action == Quick.CAMERA -> runCatching { startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { Apps.launchPkg(this, "com.sec.android.app.camera") }
            action == Quick.TORCH -> setTorch(!torchOn)
            action == Quick.PRIME -> Grok.fire(this, "flick", this)
            action == Quick.DRAWER -> openDrawer()
            action == Quick.SEARCH -> openSearch()
            action.startsWith("app:") -> if (!Apps.launchPkg(this, action.removePrefix("app:"))) toast("That app isn't installed")
        }
    }
    fun quickLabel(action: String): String = when {
        action == Quick.CAMERA -> "Camera"; action == Quick.TORCH -> "Flashlight"; action == Quick.PRIME -> "Talk to Prime"; action == Quick.DRAWER -> "App drawer"
        action == Quick.SEARCH -> "Search"; action == Quick.NONE -> "Nothing"; action.startsWith("app:") -> Apps.byPkg(action.removePrefix("app:"))?.label ?: action.removePrefix("app:"); else -> action
    }

    // ---------- torch ----------
    private fun initTorch() {
        val cm = getSystemService(CameraManager::class.java)
        torchId = runCatching { cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } }.getOrNull()
        cm.registerTorchCallback(object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(id: String, enabled: Boolean) { if (id == torchId) { torchOn = enabled; if (this@HomeActivity::torchChip.isInitialized) torchChip.visibility = if (enabled) View.VISIBLE else View.GONE } }
        }, main)
    }
    private fun setTorch(on: Boolean) { val id = torchId ?: return toast("No flashlight found"); runCatching { getSystemService(CameraManager::class.java).setTorchMode(id, on) }.onFailure { toast("Flashlight is busy") } }

    // ---------- Message Prime (straight to the configured agent connector) ----------
    /**
     * 0.1.9: every message typed here is POSTed straight to Prime's webhook ([PrimeHook], same endpoint + JSON the Emit PC
     * companion uses for the Prime chat). No app opens. The text stays in the box until the webhook accepts it, so a
     * failed send is never silent and can be retried with one tap.
     */
    private var sending = false
    /** Tests: last user-visible send outcome. */
    var lastSendNote = ""; private set
    fun sendPrime(textIn: String? = null) {
        val fromBox = textIn == null; val t = (textIn ?: pmsg.text.toString()).trim(); if (t.isEmpty() || sending) return
        sending = true; thinkUntil = SystemClock.uptimeMillis() + 1400; if (stage.ready) stage.orb.kick(); note("Sending to Prime…", quiet = true)
        PrimeHook.send(this, t) { r ->
            sending = false
            when (r) {
                PrimeHook.Result.Sent -> { if (fromBox && pmsg.text.toString().trim() == t) pmsg.setText(""); keys.hide(); hideKeyboard(pmsg); note("Sent to Prime \u2713") }
                PrimeHook.Result.NotSetUp -> { thinkUntil = 0; note("Agent connector isn't set up \u2014 Settings \u2192 Agent connector") }
                is PrimeHook.Result.Failed -> { thinkUntil = 0; if (!fromBox) pmsg.setText(t); note("Not sent: ${r.why}. Tap send to retry.") }
            }
        }
    }
    private fun note(s: String, quiet: Boolean = false) { lastSendNote = s; if (!quiet) toast(s) }

    // ---------- notifications ----------
    private val shown = ArrayList<Pair<Note, View>>(); private val queue = ArrayList<Note>()
    override fun onNote(n: Note, fresh: Boolean) {
        brain.onNote(n, fresh)
        if (!fresh || !PrimeApp.homeVisible || !prefs.bubbles) return
        if (stage.ready) { stage.noteT = stage.orb.t; if (!prefs.companion) { stage.orb.kick(); stage.orb.lookAt(0.0, -0.14) } }
        val cap = noteSlots().size; if (cap == 0) return // no room between tiles and orb: counter + tray only
        if (shown.size >= cap) { queue.add(n); return }
        showBubble(n)
    }
    override fun onNotesChanged() {
        if (!this::unread.isInitialized) return; val c = NotifStore.all().size; brain.onNotesChanged(c)
        unread.text = if (c > 99) "99+" else c.toString(); unread.visibility = if (c > 0 && panel == null) View.VISIBLE else View.GONE
        if (!prefs.reduce && c > 0) unread.animate().translationY(-dp(3f)).setDuration(900).withEndAction { unread.animate().translationY(0f).setDuration(900).start() }.start()
        if (tray != null) { if (c == 0) closeTray() else openTray() }
        shown.toList().forEach { (n, v) -> if (NotifStore.all().none { it.key == n.key }) dropBubble(v, n, 0f) }
    }
    fun noteSlots(): List<Float> = Logic.noteSlots(if (stage.headerBottom > 0) stage.headerBottom else topIn + 210f, stage.cy - stage.orbR() * 1.12f)
    private fun showBubble(n: Note) {
        val ctx = this; val a = Apps.byPkg(n.pkg)
        val ic = ImageView(this).apply { setImageBitmap(tile(n.pkg, a?.label ?: n.pkg, 30f)) }
        val title = text(pal, (a?.label ?: n.pkg) + if (n.title.isNotBlank()) " · ${n.title}" else "", 12f, pal.p, bold = true)
        val body = text(pal, n.text, 12f, pal.body, lines = 2)
        val inner = col(title, body).apply { setPadding(dpi(8f), 0, 0, 0) }
        val v = row(ic, inner).apply { background = pal.panel(ctx, 18f, 0xF0); setPadding(dpi(10f), dpi(8f), dpi(12f), dpi(8f)); elevation = dp(6f) }
        (inner.layoutParams as LinearLayout.LayoutParams).width = 0; (inner.layoutParams as LinearLayout.LayoutParams).weight = 1f
        // 0.1.23: stack downward from under the weather tiles into the first free slot; never over the tiles or the orb.
        val slots = noteSlots(); val used = shown.map { it.second.tag as? Int ?: -1 }.toSet(); val idx = slots.indices.firstOrNull { it !in used } ?: (slots.size - 1)
        v.tag = idx; v.minimumHeight = 0
        bubbles.addView(v, FrameLayout.LayoutParams(dpi(300f), dpi(Logic.NOTE_H), Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dpi(slots[idx]) })
        shown.add(n to v)
        if (!prefs.reduce) { v.pivotX = dp(150f); v.pivotY = dp(60f); v.scaleX = 0.3f; v.scaleY = 0.3f; v.alpha = 0f; v.translationY = -dp(24f)
            v.animate().scaleX(1f).scaleY(1f).alpha(1f).translationY(0f).setInterpolator(OvershootInterpolator(1.6f)).setDuration(520).start() }
        var sx = 0f; var dragging = false
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; dragging = false }
                MotionEvent.ACTION_MOVE -> { val dx = e.rawX - sx; if (abs(dx) > dp(8f)) dragging = true; if (dragging) { view.translationX = dx; view.alpha = 1f - (abs(dx) / dp(220f)).coerceAtMost(0.8f) } }
                MotionEvent.ACTION_UP -> { val dx = e.rawX - sx
                    if (dragging && abs(dx) > dp(90f)) { NotifStore.dismiss(n); dropBubble(view, n, if (dx > 0) 1f else -1f) }
                    else if (dragging) view.animate().translationX(0f).alpha(1f).setDuration(200).start()
                    else { dropBubble(view, n, 0f); NotifStore.open(ctx, n) } }
            }
            true
        }
        main.postDelayed({ if (shown.any { it.second === v }) dropBubble(v, n, 0f, collapse = true) }, 9000)
    }
    private fun dropBubble(v: View, n: Note, dir: Float, collapse: Boolean = false) {
        val i = shown.indexOfFirst { it.second === v }; if (i < 0) return; shown.removeAt(i)
        val end = { bubbles.removeView(v); if (queue.isNotEmpty() && shown.size < noteSlots().size) showBubble(queue.removeAt(0)) }
        if (prefs.reduce) end() else if (collapse) v.animate().scaleX(0.2f).scaleY(0.2f).alpha(0f).translationX(unread.x - v.x - v.width / 2f).translationY(unread.y - v.y).setDuration(380).withEndAction(end).start()
        else v.animate().translationX(dir * dp(400f)).alpha(0f).setDuration(240).withEndAction(end).start()
    }
    private fun openTray() {
        closeTray(false); val ctx = this; val list = NotifStore.counts(); if (list.isEmpty()) return
        val box = col().apply { setPadding(dpi(8f), dpi(8f), dpi(8f), dpi(8f)) }
        for ((pkg, count) in list) { val a = Apps.byPkg(pkg); val latest = NotifStore.all().first { it.pkg == pkg }
            val ic = ImageView(this).apply { setImageBitmap(tile(pkg, a?.label ?: pkg, 28f)) }
            val t1 = text(pal, a?.label ?: pkg, 13f, pal.body, bold = true); val t2 = text(pal, latest.title.ifBlank { latest.text }, 11f, pal.dim)
            val c = text(pal, count.toString(), 12f, pal.bg, bold = true).apply { gravity = Gravity.CENTER; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(pal.p2) } }
            val mid = col(t1, t2).apply { setPadding(dpi(10f), 0, dpi(8f), 0) }
            val r = row(ic, mid, c).apply { setPadding(dpi(6f), dpi(6f), dpi(6f), dpi(6f)); setOnClickListener { closeTray(); NotifStore.open(ctx, latest) } }
            (mid.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f }; (c.layoutParams as LinearLayout.LayoutParams).apply { width = dpi(22f); height = dpi(22f) }
            box.addView(r, LinearLayout.LayoutParams(MATCH, WRAP)) }
        val clear = text(pal, pal.up("Clear all"), 12f, pal.p, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dpi(10f), 0, dpi(6f)); setOnClickListener { NotifStore.dismissAll(); closeTray() } }
        box.addView(clear, LinearLayout.LayoutParams(MATCH, WRAP))
        val sv = android.widget.ScrollView(this).apply { addView(box); background = pal.panel(ctx, 16f); elevation = dp(10f); isVerticalScrollBarEnabled = false }
        val tb = Logic.trayBox(stage.cy, stage.orbR(), topIn)
        val holder = object : FrameLayout(ctx) { override fun onMeasure(w: Int, h: Int) { super.onMeasure(w, MeasureSpec.makeMeasureSpec(minOf(MeasureSpec.getSize(h), dpi(tb[1])), MeasureSpec.AT_MOST)) } }
        holder.addView(sv, FrameLayout.LayoutParams(MATCH, WRAP))
        overlay.addView(holder, FrameLayout.LayoutParams(dpi(260f), WRAP, Gravity.END or Gravity.BOTTOM).apply { rightMargin = dpi(18f); bottomMargin = (root.height - dp(tb[0])).toInt() })
        tray = holder
        if (!prefs.reduce) { holder.pivotX = dp(208f); holder.pivotY = dp(tb[1]); holder.alpha = 0f; holder.scaleX = 0.92f; holder.scaleY = 0.92f; holder.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(OvershootInterpolator(1.3f)).start() }
        if (stage.ready) stage.orb.lookAt(0.12, -0.12)
    }
    private fun closeTray(anim: Boolean = true) { val t = tray ?: return; tray = null; overlay.removeView(t) }

    // ---------- tiles ----------
    fun tile(pkg: String, label: String, sizeDp: Float): Bitmap { val px = dpi(sizeDp); val key = "$pkg|${prefs.icons}|${prefs.theme}|$px"
        return tiles.get(key) ?: IconArt.tile(pkg, label, prefs.icons, prefs.neon, px, stage.d, prefs.odst).also { tiles.put(key, it) } }

    // ---------- panels ----------
    fun showPanel(kind: String, v: View, lpv: FrameLayout.LayoutParams = FrameLayout.LayoutParams(MATCH, MATCH), fromBottom: Boolean = true) {
        keys.hide(); closePanel(false); closeTray(); stage.closeRing(); panel = v; panelKind = kind; root.addView(v, lpv); unread.visibility = View.GONE
        if (!prefs.reduce) { v.translationY = if (fromBottom) dp(80f) else -dp(80f); v.alpha = 0f; v.animate().translationY(0f).alpha(1f).setInterpolator(DecelerateInterpolator()).setDuration(260).start() }
        overlay.visibility = if (kind == "settings") View.INVISIBLE else View.VISIBLE
        if (kind == "drawer" || kind == "search" || kind == "onboard") overlay.visibility = View.INVISIBLE
    }
    fun closePanel(anim: Boolean = true) {
        val v = panel ?: return; panel = null; val k = panelKind; panelKind = ""; Panels.forget(); hideKeyboard(v); overlay.visibility = View.VISIBLE; typingUntil = 0
        if (anim && !prefs.reduce) v.animate().alpha(0f).translationY(dp(40f)).setDuration(180).withEndAction { root.removeView(v) }.start() else root.removeView(v)
        onNotesChanged(); renderCall(); if (k == "onboard") prefs.onboarded = true
    }
    fun panelIs(k: String) = panelKind == k && panel != null
    fun currentPanel(): View? = panel
    fun replacePanel(v: View, lpv: FrameLayout.LayoutParams) { val old = panel ?: return showPanel(panelKind, v, lpv); val i = root.indexOfChild(old); root.removeView(old); root.addView(v, i, lpv); panel = v }
    fun isDefaultHome(): Boolean = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == packageName
    fun openDrawer() = Panels.drawer(this)
    fun renderDrawer() = Panels.renderDrawer(this)
    fun openSearch() = Panels.search(this)
    fun openSettings() = Panels.settings(this)
    fun openOnboarding() = Panels.onboarding(this)
    fun typedInSearch(e: EditText) = typed(e)

    /** Theme / icon / wallpaper changes: rebuild what depends on them. */
    fun applyLook() { pal = Pal(prefs.neon, prefs.odst); keys.theme(prefs.theme); tiles.evictAll(); stage.rebuild(); buildOverlay(); stage.dimOverlay = 0f }

    fun hideKeyboard(v: View) { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0) }
    fun showKeyboard(e: EditText) { e.requestFocus(); main.postDelayed({ getSystemService(InputMethodManager::class.java).showSoftInput(e, InputMethodManager.SHOW_IMPLICIT) }, 120) }
    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------- permissions ----------
    fun requestPerm(p: String) = requestPermissions(arrayOf(p), 9)
    @Deprecated("Activity result API not needed for this one-screen launcher") override fun onRequestPermissionsResult(rc: Int, perms: Array<String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        if (Manifest.permission.RECORD_AUDIO in perms) WakeService.sync(this)
        if (Manifest.permission.ACCESS_COARSE_LOCATION in perms) { Weather.locState = Weather.LOC_OK; refreshWx(true) }
        if (panelKind == "onboard") openOnboarding() else if (panelKind == "settings") openSettings()
    }
    fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    fun openSys(action: String, pkgUri: Boolean = false) = runCatching { startActivity(Intent(action).apply { if (pkgUri) data = Uri.parse("package:$packageName") }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { toast("Couldn't open that settings page") }

    // ---------- Message Prime keyboard ----------
    private fun boxMargin(): Int = maxOf(kbHVisible(), dpi(imeIn)).let { k -> if (k > 0) k + dpi(8f) else dpi(botIn + 14f) }
    private fun kbHVisible() = if (this::keys.isInitialized && keys.custom) kbH else 0
    /** Keep the box just above whichever keyboard is up (Emit's or the system one). */
    fun placeBox() {
        if (!this::pbox.isInitialized) return; val lp = pbox.layoutParams as FrameLayout.LayoutParams; val m = boxMargin()
        if (lp.bottomMargin != m) { lp.bottomMargin = m; pbox.layoutParams = lp }
    }
    private fun renderKbChip() { if (this::mxChip.isInitialized) mxChip.visibility = if (keys.open && !keys.custom) View.VISIBLE else View.GONE }
    /** Tap outside the box/keyboard closes the keyboard and swallows that one gesture (so it doesn't also hit the orb). */
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            eatTouch = false
            if (keys.open && panel == null && !inside(pbox, e) && !inside(keys.view, e)) { keys.hide(); eatTouch = true }
        }
        if (eatTouch) { if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) eatTouch = false; return true }
        return super.dispatchTouchEvent(e)
    }
    private fun inside(v: View, e: MotionEvent): Boolean {
        if (!v.isShown || v.height == 0) return false; val l = IntArray(2); v.getLocationOnScreen(l)
        return e.rawX >= l[0] && e.rawX < l[0] + v.width && e.rawY >= l[1] && e.rawY < l[1] + v.height
    }
    fun dpiPub(v: Float) = dpi(v)
    @Suppress("unused") private fun colorA(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
    @Suppress("unused") private fun vg(v: View) = v as? ViewGroup
}
