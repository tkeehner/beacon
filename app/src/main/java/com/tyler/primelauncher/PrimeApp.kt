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
        /** Application context once [onCreate] has run (CallLaunch / Grok helpers). */
        fun ctx(): Context = checkNotNull(instance)
        @Volatile private var instance: PrimeApp? = null
        @Volatile var micLevel = 0f
    }
    /** 0.1.16: publish the updater's phone key, pick up an adb-pushed pairing code, keep the relay waiter running. Off the main thread (Keystore). */
    private fun updaterPoke(c: Context) {
        if (android.os.Build.FINGERPRINT == "robolectric") return
        val app = c.applicationContext
        Thread { runCatching { com.tyler.primelauncher.update.Updater.publishPhoneKey(app); com.tyler.primelauncher.update.Updater.pickUpPushedPair(app); com.tyler.primelauncher.update.Updater.ensure(app) } }.start()
    }
    override fun onCreate() {
        instance = this
        super.onCreate()
        WakeService.createChannels(this)
        // Phone Link reads notifications through the launcher's existing listener (no second NotificationListenerService).
        com.tyler.primelauncher.phonelink.android.NotifBridge.provider = {
            NotifStore.all().map { com.tyler.primelauncher.phonelink.android.NotifBridge.Note(it.pkg, it.title, it.text, it.time) }
        }
        updaterPoke(this)
        HookImport.poke(this) // publish the import public key / pick up a pending import (off the main thread)
        val f = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                WakeService.sync(c) // off/locked -> stop; unlocked -> start if Android allows
                if (i.action == Intent.ACTION_SCREEN_ON) updaterPoke(c)
                if (i.action == Intent.ACTION_SCREEN_ON) HookImport.poke(c) // 0.1.10: pick up a pushed webhook import
                if (i.action == Intent.ACTION_SCREEN_ON) LockOrbActivity.maybeShow(c) // no-op unless the experimental toggle is on
            }
        }, f, Context.RECEIVER_EXPORTED) // 0.1.9: EXPORTED so SystemUI's USER_PRESENT gets through (NOT_EXPORTED was skipped); all three are protected broadcasts
    }
}
