package com.tyler.primelauncher

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.PowerManager
import android.os.SystemClock
import java.util.Calendar

/**
 * 0.1.15 companion brain (Home side): decides WHEN the orb does something on its own, using [Scheduler] with the active
 * [Persona]'s pool, and turns home events into moments (welcome home, heads-up, inbox zero, snack time, bedtime, app nudge).
 * Everything is local: no network, no new permissions. State lives in SharedPreferences "prime".
 */
class CompanionBrain(private val ctx: Context, private val prefs: Prefs, private val stage: StageView, private val home: Hooks) : Buddy.Listener {
    interface Hooks {
        /** Keyboard / Message Prime / a panel / sending: the user is busy with Home itself. */
        fun companionBusy(): Boolean
        fun noteBubblesShowing(): Boolean
        /** Open Message Prime with [text] typed in (not sent). */
        fun openPrimeDraft(text: String)
    }
    private var st = SchedState.decode(prefs.sp.getString("sched", null))
    private fun save() = prefs.sp.edit().putString("sched", st.encode()).apply()
    private val rnd = java.util.Random()
    private val persona get() = Personas.of(prefs.theme)
    private var visMs = 0L; private var lastFrameMs = 0L; private var lastCheck = 0L
    private var greetDue = 0L; private var offApp = 0L; private var prevNotes = -1

    // ---------- context ----------
    fun schedCtx(): SchedCtx {
        val c = Calendar.getInstance(); val h = c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60.0
        val dow = c.get(Calendar.DAY_OF_WEEK)
        return SchedCtx(System.currentTimeMillis() / 60000, h, dayKey(c), dow == Calendar.SATURDAY || dow == Calendar.SUNDAY, busy(), stage.level < Rules.LOW_BATT && !stage.charging, raining())
    }
    /** 0.1.17: rain / drizzle / showers / storms in the cached forecast (<= 6 h old). Only gates ODST's Rain Watch. */
    private fun raining(): Boolean { val w = Wx.from(prefs.wxCache) ?: return false
        return System.currentTimeMillis() - w.fetched < 6 * 3600_000L && isRainCode(w.code) }
    private fun dayKey(c: Calendar = Calendar.getInstance()) = c.get(Calendar.YEAR) * 1000L + c.get(Calendar.DAY_OF_YEAR)
    /** Quiet inputs: Do Not Disturb, any call, Hey Prime / Message Prime / typing (orb not idle), battery saver, ring or panel open. */
    fun busy(): Boolean {
        if (home.companionBusy() || Call.active || prefs.saver || stage.ringOpen) return true
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm != null && nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL) return true
        val am = ctx.getSystemService(AudioManager::class.java)
        if (am != null && (am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION || am.mode == AudioManager.MODE_RINGTONE)) return true
        if (ctx.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true) return true
        if (stage.ready && (stage.orb.mode != Mode.IDLE || stage.orb.sleep.k > 0.2)) return true
        return false
    }

    // ---------- per frame (home visible) ----------
    fun frame() {
        if (!prefs.companion || !stage.ready) { lastFrameMs = 0; return }
        val now = SystemClock.uptimeMillis(); if (lastFrameMs == 0L) { lastFrameMs = now; return }
        visMs += (now - lastFrameMs).coerceIn(0, 1000); lastFrameMs = now
        if (now - lastCheck < 1000) return; lastCheck = now
        if (greetDue != 0L && now >= greetDue) { greetDue = 0; greet() }
        // 0.1.18: idle beats keep the orb busy for a few seconds at a time, so the 5-min roll waits for the next free moment
        // (checked every second) instead of being thrown away; the bubble cadence stays what it was.
        if (visMs >= Rules.TICK_MIN * 60_000 && stage.buddy.bubble == null && (stage.buddy.playing == null || stage.buddy.playingKind == AK.QUIRK)) { visMs = 0; tick() }
        vergilCheck()
        // Bedtime Settle: once a night, late, after the home screen sits idle a while.
        val c = schedCtx(); val late = c.hour >= 22.5 || c.hour < 3
        if (late && stage.buddy.idleS > 40 && stage.buddy.playing == null && stage.buddy.bubble == null && !c.busy) {
            val night = if (c.hour < 3) c.day - 1 else c.day
            if (prefs.sp.getLong("bedNight", 0) != night && stage.buddy.play(Antics.BEDTIME)) prefs.sp.edit().putLong("bedNight", night).apply() }
    }
    fun pausedHome() { lastFrameMs = 0 }

    private fun pool(): List<PoolItem> = persona.pool.filter { prefs.chat || it.kind == Kind.ACT }
    /** One scheduler roll (every 5 min of home-on-screen time). Skips without spending a turn if the orb is mid-moment. */
    fun tick(): PoolItem? {
        if (stage.buddy.bubble != null) return null
        if (stage.buddy.playing != null) { if (stage.buddy.playingKind == AK.QUIRK) stage.buddy.stopAntic() else return null }
        val c = schedCtx(); val pick = Scheduler.tick(st, pool(), c) { rnd.nextDouble() } ?: return null; save()
        if (pick.kind == Kind.ACT) stage.buddy.play(pick.id) else PoolIds.cat(pick.id)?.let { show(it) }
        return pick
    }

    // ---------- lines ----------
    private fun bagNext(key: String, n: Int, seed: Long): Int {
        val bag = LineBag.decode(prefs.sp.getString(key, null), n, seed); val i = bag.next(); prefs.sp.edit().putString(key, bag.encode()).apply(); return i
    }
    private fun filled(cat: Cat, keep: (String) -> Boolean = { true }): List<String> =
        (persona.lines[cat] ?: emptyList()).filter(keep).mapNotNull { Personas.fill(it, prefs.buddyName, prefs.studyCourse, prefs.studyModule) }
    fun bubbleFor(cat: Cat, keep: (String) -> Boolean = { true }): ChatBubble? {
        val per = persona; val seed = per.id.hashCode().toLong() * 31 + cat.ordinal
        if (cat == Cat.JOKE) { val j = per.jokes; if (j.isEmpty()) return null; val (q, a) = j[bagNext("bag.${per.id}.JOKE", j.size, seed)]
            val (qa, qb) = Personas.splitBadge(q); val (pa, pb) = Personas.splitBadge(a)
            return ChatBubble(per.cased(qa), cat, if (per.upper) qb ?: per.badges[cat] else null, hold = 1.6, then = ChatBubble(per.cased(pa), cat, if (per.upper) pb ?: "♪" else null, laugh = true)) }
        val ls = filled(cat, keep); if (ls.isEmpty()) return null
        val key = "bag.${per.id}.${cat.name}" + if (cat == Cat.REACT) "." + ls.size else ""
        val (txt, badge) = Personas.splitBadge(ls[bagNext(key, ls.size, seed)])
        return ChatBubble(per.cased(txt), cat, if (per.upper) badge ?: per.badges[cat] else null)
    }
    private fun show(cat: Cat, keep: (String) -> Boolean = { true }): Boolean { val b = bubbleFor(cat, keep) ?: return false; return stage.buddy.say(b, home.noteBubblesShowing()) }
    private fun idFor(cat: Cat) = when (cat) { Cat.STUDY -> PoolIds.STUDY; Cat.CLEAN -> PoolIds.CLEAN; Cat.CAR -> PoolIds.CAR; Cat.JOKE -> PoolIds.JOKE
        Cat.MUSING -> PoolIds.MUSING; Cat.GREETING -> GREET; Cat.NOTIFY -> NOTIFY; Cat.REACT -> REACT }
    /** Event-driven bubble through the same caps / quiet hours / mutes as the scheduled ones. */
    private fun event(cat: Cat, cooldownMin: Int, keep: (String) -> Boolean = { true }): Boolean {
        if (!prefs.companion || !prefs.chat || stage.buddy.bubble != null) return false
        val c = schedCtx(); val id = idFor(cat); if (!Scheduler.canBubble(st, c, id, cooldownMin)) return false
        if (!show(cat, keep)) return false
        Scheduler.record(st, id, Kind.BUB, c); save(); return true
    }

    // ---------- home events ----------
    fun screenOff() { offApp = SystemClock.elapsedRealtime() }
    /** An actual unlock-to-home (slow wake). Away > 30 min: welcome hop. First unlock of the day: a greeting. */
    fun onWake() {
        wakeAt = SystemClock.elapsedRealtime()
        if (!prefs.companion) return; val away = if (offApp == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - offApp
        val greeting = prefs.sp.getLong("greetDay", 0) != dayKey() && Calendar.getInstance().get(Calendar.HOUR_OF_DAY) >= 5
        // ODST: Pod Drop on unlock (the quiet cut skips its own caption + chime when the morning notice is about to post).
        if (away > 30 * 60_000L) { if (prefs.odst) stage.buddy.playLater(0.15, if (greeting) OdstAntics.PODDROP_QUIET else OdstAntics.PODDROP, 1.1) else stage.buddy.playLater(1.2, Antics.WELCOME, 1.05) }
        if (greeting) greetDue = SystemClock.uptimeMillis() + 3800
    }
    private fun greet() { if (prefs.sp.getLong("greetDay", 0) == dayKey()) return; if (event(Cat.GREETING, 0)) prefs.sp.edit().putLong("greetDay", dayKey()).apply() }
    /** Heads-up on a fresh notification; a new message also gets an offer to read it (never shows the sender or text). */
    fun onNote(n: Note, fresh: Boolean) {
        if (!fresh || !prefs.companion || !PrimeApp.homeVisible || !stage.ready || Call.active) return
        if (n.important) stage.buddy.play(Antics.HEADSUP, 2.9) else stage.buddy.play(Antics.HEADSUP, 0.55, 2.85)
        if (n.messaging) stage.postDelayed({ if (PrimeApp.homeVisible) event(Cat.NOTIFY, 20) }, 1600)
    }
    fun onNotesChanged(count: Int) {
        if (prevNotes >= 2 && count == 0 && PrimeApp.homeVisible && prefs.companion && stage.ready) { stage.buddy.play(Antics.LITTLEWIN); onReact("win") }
        prevNotes = count
    }
    fun onPlug() { if (prefs.companion && PrimeApp.homeVisible) stage.buddy.play(Antics.SNACK, 1.2) }

    // ---------- ODST Vergil (rare) ----------
    private var wakeAt = 0L
    /**
     * Late (21:30-23:30), the screen has been on 45+ min since the last unlock, once a night at most and only on 1 night in 3:
     * the face flickers blue and a soft lowercase line posts. Goes through canAct + canBubble (caps, quiet hours, mutes, busy).
     */
    private fun vergilCheck() {
        val per = persona; if (!prefs.odst || !prefs.chat || per.vergil.isEmpty() || wakeAt == 0L) return
        if (SystemClock.elapsedRealtime() - wakeAt < 45 * 60_000L) return
        val c = schedCtx(); if (c.hour < 21.5 || c.hour >= 23.5) return
        if (prefs.sp.getLong("vergilNight", 0) == c.day) return
        if (prefs.sp.getLong("vergilRollDay", 0) != c.day) { prefs.sp.edit().putLong("vergilRollDay", c.day).putBoolean("vergilRoll", rnd.nextInt(3) == 0).apply() }
        if (!prefs.sp.getBoolean("vergilRoll", false)) return
        if (stage.buddy.playing != null || stage.buddy.bubble != null || stage.buddy.idleS < 20) return
        if (!Scheduler.canAct(st, c) || !Scheduler.canBubble(st, c, VERGIL_ID, 0)) return
        if (!stage.buddy.play(OdstAntics.VERGIL)) return
        prefs.sp.edit().putLong("vergilNight", c.day).apply(); Scheduler.record(st, OdstAntics.VERGIL, Kind.ACT, c); Scheduler.record(st, VERGIL_ID, Kind.BUB, c); save()
        val line = per.vergil[bagNext("bag.odst.VERGIL", per.vergil.size, 77L)]
        stage.postDelayed({ if (PrimeApp.homeVisible && prefs.odst) stage.buddy.say(ChatBubble(line.lowercase(), Cat.MUSING, null, hold = 3.6, vergil = true), home.noteBubblesShowing()) }, 2400)
    }

    // ---------- Buddy.Listener ----------
    override fun onBubbleTap(b: ChatBubble) { home.openPrimeDraft(draftFor(b.cat)) }
    override fun onBubbleSwipe(b: ChatBubble) { Scheduler.dismiss(st, if (b.vergil) VERGIL_ID else idFor(b.cat), schedCtx()); save() }
    override fun allowIdleAct(id: String): Boolean {
        if (!prefs.companion) return false; val c = schedCtx(); if (!Scheduler.canAct(st, c)) return false
        val last = st.last[id] ?: SchedState.NEVER; if (last != SchedState.NEVER && c.nowMin - last < 60) return false
        Scheduler.record(st, id, Kind.ACT, c); save(); return true
    }
    override fun beatActs(): List<PoolItem> = if (!prefs.companion) emptyList() else Scheduler.beatActs(persona.pool, schedCtx())
    override fun onReact(kind: String) {
        if (kind != "win" && rnd.nextDouble() > 0.3) return
        val win = kind == "win"; stage.postDelayed({ event(Cat.REACT, 10) { (it.contains("inbox", ignoreCase = true)) == win } }, 700)
    }

    /** Message Prime draft for a tapped bubble: typed in, never sent on its own. */
    fun draftFor(cat: Cat) = when (cat) {
        Cat.GREETING -> "Good morning! What's on my plate today?"; Cat.STUDY -> "Help me plan my next study session."
        Cat.CLEAN -> "Give me a quick 10-minute plan to tidy my room."; Cat.CAR -> "What car maintenance should I check on?"
        Cat.JOKE -> "Tell me another joke."; Cat.NOTIFY -> "Read me my new message."; Cat.MUSING, Cat.REACT -> "" }

    // ---------- App Nudge: local per-time-of-day launch counts ----------
    private fun bucket() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) / 2
    private fun stats(): MutableMap<String, Int> = (prefs.sp.getString("launchStats", "") ?: "").split(',').filter { '=' in it }
        .associate { it.substringBeforeLast('=') to (it.substringAfterLast('=').toIntOrNull() ?: 0) }.toMutableMap()
    fun recordLaunch(pkg: String) {
        val m = stats(); val k = "${bucket()}:$pkg"; m[k] = (m[k] ?: 0) + 1
        val keep = m.entries.sortedByDescending { it.value }.take(120) // bounded
        prefs.sp.edit().putString("launchStats", keep.joinToString(",") { "${it.key}=${it.value}" }).apply()
    }
    /** Ring index of the app opened most at this time of day (>= 3 launches and >= 40% of them), else -1. */
    fun nudgeIndex(ring: List<String>): Int {
        if (!prefs.companion) return -1; val b = "${bucket()}:"; val m = stats().filterKeys { it.startsWith(b) }; val total = m.values.sum()
        val best = m.maxByOrNull { it.value } ?: return -1; if (best.value < 3 || best.value < total * 0.4) return -1
        return ring.indexOf(best.key.removePrefix(b))
    }

    /** Tests / debugging. */
    val state get() = st

    companion object { const val GREET = "bub.greet"; const val NOTIFY = "bub.notify"; const val REACT = "bub.react"; const val VERGIL_ID = "bub.vergil" }
}

/** Open-Meteo WMO codes for drizzle / rain / freezing rain (51-67), showers (80-82) and thunderstorms (95-99). */
fun isRainCode(code: Int) = code in 51..67 || code in 80..82 || code in 95..99
