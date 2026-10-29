package com.tyler.primelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallTest {
    private val none = CallSignals(false, false, false, false, 0)
    private val audio = CallSignals(false, false, true, false, 0)
    private val grokCall = CallSignals(true, true, false, false, 0)
    private val grokOngoingOnly = CallSignals(true, false, false, false, 0)

    @Test fun launchShowsCallWhileWaitingForSignals() { val t = CallTracker(); t.begin(0); assertTrue(t.update(1000, none)); assertTrue(t.update(39_000, none)); assertFalse(t.update(41_000, none)) }
    @Test fun endsOneSecondAfterSignalsDrop() { val t = CallTracker(); t.begin(0); assertTrue(t.update(2000, audio)); assertTrue(t.update(2800, none)); assertFalse(t.update(3100, none)) }
    @Test fun homeBackWithoutCallAudioEndsAtOnce() { val t = CallTracker(); t.begin(0); assertTrue(t.update(1000, audio)); t.homeBack(); assertFalse(t.update(1100, none)) }
    @Test fun micInUseNeverHoldsTheCallAfterHomeBack() { val t = CallTracker(); t.begin(0); t.update(1000, audio); t.homeBack(); assertFalse(t.update(1100, none.copy(othersRecording = 2))) }
    @Test fun safetyTimeoutStill90s() { val t = CallTracker(); t.begin(0); assertTrue(t.update(1000, grokCall.copy(audioComm = true))); assertTrue(t.update(80_000, grokCall)); assertFalse(t.update(92_000, grokCall)) }
    @Test fun grokCallNotificationAloneIsACall() { val t = CallTracker(); assertTrue(t.update(0, grokCall)); assertEquals(0L, t.since) }
    @Test fun otherAppsAudioIsNotPrimeCall() { val t = CallTracker(); assertFalse(t.update(0, audio)) }
    @Test fun plainOngoingGrokNoteNeedsAudio() { val t = CallTracker(); assertFalse(t.update(0, grokOngoingOnly)); assertTrue(t.update(10, grokOngoingOnly.copy(othersRecording = 1))) }
    @Test fun afterCallEndsStrayAudioDoesNotRelight() { val t = CallTracker(); t.begin(0); t.update(1000, audio); assertFalse(t.update(9000, none)); assertFalse(t.update(9500, audio)) }
    @Test fun timerStartsAtLaunch() { val t = CallTracker(); t.begin(500); t.update(3000, audio); assertEquals(500L, t.since) }
    private val weak = CallSignals(true, true, false, false, 1)
    @Test fun homeBackDropsWeakEvidence() { val t = CallTracker(); t.begin(0); assertTrue(t.update(1000, weak)); t.homeBack(); assertTrue(t.update(1500, null)); assertFalse(t.update(6000, weak)) }
    @Test fun homeBackKeepsRealCall() { val t = CallTracker(); t.begin(0); t.update(1000, audio); t.homeBack(); assertTrue(t.update(9000, audio)); assertFalse(t.update(14_000, none)) }
    @Test fun homeBackDropsLaunchPending() { val t = CallTracker(); t.begin(0); assertTrue(t.update(500, none)); t.homeBack(); assertFalse(t.update(1000, none)) }
    @Test fun weakOnlyTimesOut() { val t = CallTracker(); t.begin(0); assertTrue(t.update(1000, weak)); assertTrue(t.update(80_000, weak)); assertFalse(t.update(95_000, weak)) }
    @Test fun nullReadingsTimeOut() { val t = CallTracker(); t.begin(0); t.update(1000, audio); assertTrue(t.update(50_000, null)); assertFalse(t.update(92_000, null)) }
    @Test fun beginResetsStrict() { val t = CallTracker(); t.begin(0); t.update(1000, audio); t.homeBack(); assertFalse(t.update(10_000, none)); t.begin(20_000); assertTrue(t.update(21_000, weak)) }
    @Test fun clock() { assertEquals("0:07", Logic.callClock(7)); assertEquals("12:05", Logic.callClock(725)); assertEquals("1:00:01", Logic.callClock(3601)) }
    @Test fun pulseInRange() { for (i in 0..500) { val v = Logic.callPulse(i * 0.05, false); assertTrue(v in 0.0..1.0) }; assertTrue(Logic.callPulse(3.0, true) in 0.19..0.41) }
    @Test fun visualizerCurve() { assertEquals(0.0, CallLevel.rmsToLevel(-9600), 1e-9); assertEquals(1.0, CallLevel.rmsToLevel(0), 1e-9); assertTrue(CallLevel.rmsToLevel(-3000) in 0.3..0.8) }
    @Test fun callNotificationKinds() {
        assertTrue(NotifStore.isCallNote(android.app.Notification.FLAG_FOREGROUND_SERVICE, null))
        assertTrue(NotifStore.isCallNote(0, android.app.Notification.CATEGORY_CALL))
        assertFalse(NotifStore.isCallNote(android.app.Notification.FLAG_ONGOING_EVENT, "msg"))
    }
    @Test fun hangupMatcher() {
        assertTrue(HangupService.isHangup(null, "End call", null)); assertTrue(HangupService.isHangup("Hang up", null, null)); assertTrue(HangupService.isHangup(null, "End", null))
        assertTrue(HangupService.isHangup(null, null, "ai.x.grok.bot:id/end_call_button")); assertTrue(HangupService.isHangup(null, "hang-up", "x:id/hangUp"))
        assertFalse(HangupService.isHangup("Weekend plans", null, null)); assertFalse(HangupService.isHangup("Send", "Mute", "x:id/mute")); assertFalse(HangupService.isHangup(null, "End of a very long message that mentions end call somewhere", null))
        HangupService.disarm(); assertFalse(HangupService.armed()); HangupService.arm(); assertTrue(HangupService.armed()); HangupService.disarm()
    }
}
