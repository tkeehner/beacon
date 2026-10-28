package com.tyler.primelauncher

import android.app.Activity
import android.os.Bundle

/** Full-screen alert tap target when Hey Prime fires while the screen is locked / home is not visible. */
class TriggerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Grok.cancelAlert(this); Grok.runNow(this, "alert", this); finish()
    }
}
