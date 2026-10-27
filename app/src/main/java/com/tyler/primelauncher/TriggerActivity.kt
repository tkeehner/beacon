package com.tyler.primelauncher

import android.app.Activity
import android.os.Bundle

/** Full-screen-alert target when the wake word fires over another app without "Display over other apps". */
class TriggerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Grok.cancelAlert(this); Grok.runNow(this, "alert"); finish()
    }
}
