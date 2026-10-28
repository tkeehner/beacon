package com.tyler.primelauncher

import android.app.Activity
import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/** 0.1.20: one call-launch method (0.1.19's "System assist APIs") with a silent trampoline fallback; no picker. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CallLaunchTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val realShow = CallLaunch.showAssist; private val realHidden = CallLaunch.hiddenApis
    private fun drainStarted(): List<Intent> { val l = mutableListOf<Intent>(); while (true) l += shadowOf(app).nextStartedActivity ?: return l }
    private fun trampoline(l: List<Intent>) = l.any { it.component?.className == Grok.TRAMPOLINE }

    @Before fun setUp() { Call.end(); drainStarted(); PrimeApp.homeVisible = false }
    @After fun tearDown() { CallLaunch.showAssist = realShow; CallLaunch.hiddenApis = realHidden; Call.end(); PrimeApp.homeVisible = false }

    @Test fun pickerIsGone() {
        assertTrue(CallLaunch::class.java.declaredFields.none { it.name == "OPTIONS" || it.name == "INTENTS" || it.name == "SHIZUKU" })
        assertTrue(Prefs::class.java.declaredMethods.none { it.name.contains("CallLaunch", ignoreCase = true) })
        assertTrue(runCatching { Class.forName("com.tyler.primelauncher.ShizukuAssist") }.isFailure)
    }
    @Test fun assistIntentTargetsGrokTrampoline() {
        val i = Grok.assistIntent()
        assertEquals(Intent.ACTION_ASSIST, i.action); assertEquals(Grok.PKG, i.component!!.packageName)
        assertEquals(Grok.TRAMPOLINE, i.component!!.className); assertTrue(i.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
    @Test fun orbTapUsesShowAssistAndNothingElse() {
        var calls = 0; CallLaunch.showAssist = { _, b -> calls++; assertEquals(app.packageName, b.getString(Intent.EXTRA_ASSIST_PACKAGE)); true }
        CallLaunch.hiddenApis = { error("hidden APIs must not run when showAssist works") }
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        PrimeApp.homeVisible = true; Grok.fire(act, "orb tap", act)
        assertEquals(1, calls); assertTrue(Call.active); assertTrue(drainStarted().none { it.component?.packageName == Grok.PKG })
        ShadowLooper.idleMainLooper(Grok.HOME_DELAY_MS + 100, TimeUnit.MILLISECONDS)
        assertTrue("no bounce-home after a system-assist call", drainStarted().isEmpty())
    }
    @Test fun wakeWordSkipsShowAssistAndUsesHiddenApis() {
        // Exactly 0.1.19's wake path: no activity → hidden APIs; no HOME switch.
        CallLaunch.showAssist = { _, _ -> error("wake word has no activity") }
        var hidden = 0; CallLaunch.hiddenApis = { hidden++; true }
        PrimeApp.homeVisible = true; Grok.fire(app, "wake word")
        assertEquals(1, hidden); assertTrue(Call.active)
        val started = drainStarted(); assertTrue(started.none { it.hasCategory(Intent.CATEGORY_HOME) }); assertFalse(trampoline(started))
    }
    @Test fun showAssistFailFallsToHiddenThenTrampoline() {
        CallLaunch.showAssist = { _, _ -> false }; var hidden = 0; CallLaunch.hiddenApis = { hidden++; false }
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        CallLaunch.fire(act, "orb tap", act)
        assertEquals(1, hidden); assertTrue(trampoline(drainStarted())); assertTrue(Call.active)
        ShadowLooper.idleMainLooper(Grok.HOME_DELAY_MS + 100, TimeUnit.MILLISECONDS)
        assertTrue("trampoline fallback still bounces home", drainStarted().any { it.hasCategory(Intent.CATEGORY_HOME) })
    }
    @Test fun showAssistThrowingIsSilentFallback() {
        CallLaunch.showAssist = { _, _ -> throw SecurityException("nope") }; CallLaunch.hiddenApis = { true }
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        CallLaunch.fire(act, "flick", act)
        assertTrue(Call.active); assertFalse(trampoline(drainStarted()))
    }
    @Test fun wakeWordOverAnotherAppWithoutOverlayPostsAlert() {
        CallLaunch.hiddenApis = { error("alert path must not launch yet") }
        Grok.fire(app, "wake word")
        val nm = app.getSystemService(android.app.NotificationManager::class.java)
        assertTrue(shadowOf(nm).allNotifications.any { it.extras.getString(android.app.Notification.EXTRA_TITLE) == "Hey Prime" })
        assertTrue(drainStarted().isEmpty())
    }
    @Test fun alertTapFiresShowAssistFromTriggerActivity() {
        var who: Activity? = null; CallLaunch.showAssist = { a, _ -> who = a; true }
        val ctl = Robolectric.buildActivity(TriggerActivity::class.java).create()
        assertTrue(who is TriggerActivity); assertTrue(ctl.get().isFinishing); assertTrue(Call.active)
    }
    @Test fun realSystemPathDoesNotThrow() {
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        runCatching { CallLaunch.viaSystem(app, act); CallLaunch.viaSystem(app, null); CallLaunch.viaHiddenApis(app) }.exceptionOrNull()?.let { throw it }
    }
    @Test fun isCallNoteTreatsCallCategoryAndFgsAsCall() {
        assertTrue(NotifStore.isCallNote(android.app.Notification.FLAG_FOREGROUND_SERVICE, null))
        assertTrue(NotifStore.isCallNote(0, android.app.Notification.CATEGORY_CALL))
        assertFalse(NotifStore.isCallNote(0, android.app.Notification.CATEGORY_MESSAGE))
    }
}
