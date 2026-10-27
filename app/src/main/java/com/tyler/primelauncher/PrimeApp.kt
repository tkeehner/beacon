package com.tyler.primelauncher

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/** Process-wide state + screen/lock watching for the wake word. */
class PrimeApp : Application() {
    companion object {
        @Volatile var homeVisible = false
        @Volatile var micLevel = 0f
    }
    override fun onCreate() {
        super.onCreate()
        WakeService.createChannels(this)
        // Phone Link reads notifications through the launcher's existing listener (no second NotificationListenerService).
        com.tyler.primelauncher.phonelink.android.NotifBridge.provider = {
            NotifStore.all().map { com.tyler.primelauncher.phonelink.android.NotifBridge.Note(it.pkg, it.title, it.text, it.time) }
        }
        HookImport.poke(this) // publish the import public key / pick up a pending import (off the main thread)
        val f = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                WakeService.sync(c) // off/locked -> stop; unlocked -> start if Android allows
                if (i.action == Intent.ACTION_SCREEN_ON) HookImport.poke(c) // 0.1.10: pick up a pushed webhook import
                if (i.action == Intent.ACTION_SCREEN_ON) LockOrbActivity.maybeShow(c) // no-op unless the experimental toggle is on
            }
        }, f, Context.RECEIVER_EXPORTED) // 0.1.9: EXPORTED so SystemUI's USER_PRESENT gets through (NOT_EXPORTED was skipped); all three are protected broadcasts
    }
}
