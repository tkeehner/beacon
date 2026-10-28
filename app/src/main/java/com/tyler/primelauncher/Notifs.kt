package com.tyler.primelauncher

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

data class Note(val key: String, val pkg: String, val title: String, val text: String, val time: Long, val intent: PendingIntent?, val clearable: Boolean, val autoCancel: Boolean,
                /** 0.1.15: for the orb's heads-up (calls / alarms / high priority jump) and the "read it?" offer on new messages. */
                val category: String? = null, val important: Boolean = false) {
    val messaging get() = category == Notification.CATEGORY_MESSAGE || pkg in MESSAGING
    companion object { val MESSAGING = setOf("com.tyler.matrixsms", "com.google.android.apps.messaging", "com.samsung.android.messaging") }
}

/** In-process store fed by [NotifService]; the home screen listens for changes. */
object NotifStore {
    private val main = Handler(Looper.getMainLooper())
    private val notes = LinkedHashMap<String, Note>()
    interface Listener { fun onNote(n: Note, fresh: Boolean); fun onNotesChanged() }
    var listener: Listener? = null
    @Volatile var service: NotifService? = null

    fun all(): List<Note> = synchronized(notes) { notes.values.sortedByDescending { it.time } }
    fun counts(): List<Pair<String, Int>> = all().groupBy { it.pkg }.map { it.key to it.value.size }.sortedByDescending { it.second }

    /** Pure filter (unit-tested): what deserves a bubble/unread slot. */
    fun keep(pkg: String, self: String, flags: Int, isGroupSummary: Boolean, title: String, text: String): Boolean =
        pkg != self && flags and Notification.FLAG_ONGOING_EVENT == 0 && flags and Notification.FLAG_FOREGROUND_SERVICE == 0 && !isGroupSummary && (title.isNotBlank() || text.isNotBlank())

    /** A Grok Bot notification that by itself means "call in progress": a foreground-service (mic) or call-category one. */
    fun isCallNote(flags: Int, category: String?): Boolean = flags and Notification.FLAG_FOREGROUND_SERVICE != 0 || category == Notification.CATEGORY_CALL

    fun fromSbn(sbn: StatusBarNotification, self: String): Note? {
        val n = sbn.notification; val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (!keep(sbn.packageName, self, n.flags, n.flags and Notification.FLAG_GROUP_SUMMARY != 0, title, text)) return null
        @Suppress("DEPRECATION") val important = n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_ALARM || n.priority >= Notification.PRIORITY_HIGH
        return Note(sbn.key, sbn.packageName, title, text, sbn.postTime, n.contentIntent, sbn.isClearable, n.flags and Notification.FLAG_AUTO_CANCEL != 0, n.category, important)
    }
    fun posted(n: Note, alerting: Boolean) {
        val fresh: Boolean
        synchronized(notes) { val old = notes[n.key]; fresh = old == null || (alerting && old.text != n.text); notes[n.key] = n }
        main.post { listener?.onNote(n, fresh); listener?.onNotesChanged() }
    }
    fun removed(key: String) { synchronized(notes) { notes.remove(key) }; main.post { listener?.onNotesChanged() } }
    fun reset(list: List<Note>) { synchronized(notes) { notes.clear(); list.forEach { notes[it.key] = it } }; main.post { listener?.onNotesChanged() } }

    fun open(ctx: Context, n: Note): Boolean {
        val ok = runCatching { n.intent?.send(); n.intent != null }.getOrDefault(false) || Apps.launchPkg(ctx, n.pkg)
        if (n.autoCancel) dismiss(n)
        return ok
    }
    /** Swipe = dismiss the real notification (same as swiping it in the shade). */
    fun dismiss(n: Note) { if (n.clearable) runCatching { service?.cancelNotification(n.key) }; removed(n.key) }
    fun dismissAll() { all().forEach { dismiss(it) } }

    fun hasAccess(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(ctx, NotifService::class.java).flattenToString()
        return flat.split(':').any { it == me }
    }
}

class NotifService : NotificationListenerService() {
    override fun onListenerConnected() {
        NotifStore.service = this
        Call.grokReset(); runCatching { activeNotifications.orEmpty().forEach { grok(it) } }
        NotifStore.reset(runCatching { activeNotifications.orEmpty().mapNotNull { NotifStore.fromSbn(it, packageName) } }.getOrDefault(emptyList()))
    }
    override fun onListenerDisconnected() { NotifStore.service = null }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        grok(sbn)
        val n = NotifStore.fromSbn(sbn, packageName) ?: return
        NotifStore.posted(n, sbn.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE == 0)
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { NotifStore.removed(sbn.key); if (sbn.packageName == Grok.PKG) { Call.grokRemoved(sbn.key); Call.poll(this); WakeService.sync(this) } }
    /** Grok Bot's ongoing notifications drive the live-call state (see [Call]). */
    private fun grok(sbn: StatusBarNotification) {
        if (sbn.packageName != Grok.PKG) return
        val n = sbn.notification; val ongoing = n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0
        if (!ongoing) { Call.grokRemoved(sbn.key); return }
        val isCall = NotifStore.isCallNote(n.flags, n.category); Call.grokPosted(sbn.key, isCall, n.contentIntent)
        if (isCall) Grok.onGrokCallNote(this)
        Call.poll(this); WakeService.sync(this)
    }
}
