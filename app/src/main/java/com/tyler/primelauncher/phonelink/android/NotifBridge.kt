package com.tyler.primelauncher.phonelink.android

/**
 * Bridge so Phone Link can read notifications through the launcher's existing NotificationListenerService
 * (com.tyler.primelauncher.NotifService / NotifStore) without owning a second listener.
 *
 * The launcher wires this once at startup:
 *   NotifBridge.provider = { NotifStore.all().map { NotifBridge.Note(it.pkg, it.title, it.text, it.time) } }
 * Until wired, [all] returns empty.
 */
object NotifBridge {
    data class Note(val pkg: String, val title: String, val text: String, val time: Long)
    @Volatile var provider: (() -> List<Note>)? = null
    fun all(): List<Note> = try { provider?.invoke() ?: emptyList() } catch (_: Exception) { emptyList() }
}
