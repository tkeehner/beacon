package com.tyler.primelauncher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LogicTest {
    @Test fun flickDirections() {
        assertEquals("up", Logic.flickDir(3f, -80f, 150)); assertEquals("down", Logic.flickDir(-5f, 90f, 200))
        assertEquals("left", Logic.flickDir(-70f, 10f, 180)); assertEquals("right", Logic.flickDir(120f, -30f, 250))
    }
    @Test fun slowOrShortDragIsNotAFlick() { assertNull(Logic.flickDir(0f, -90f, 900)); assertNull(Logic.flickDir(10f, 10f, 100)) }

    @Test fun ringIsTopEightByUsageThenPins() {
        val apps = (1..12).map { "p$it" to "App $it" }; val usage = apps.mapIndexed { i, a -> a.first to (i * 1000L) }.toMap()
        val r = Logic.ringPkgs(apps, usage, listOf("p1", "gone"))
        assertEquals(9, r.size); assertEquals("p12", r[0]); assertEquals(listOf("p12", "p11", "p10", "p9", "p8", "p7", "p6", "p5"), r.take(8)); assertEquals("p1", r.last())
    }
    @Test fun ringIsPinsOnlyInPinOrder() {
        val have = (1..20).map { "p$it" }
        assertEquals(listOf("p3", "p1"), Logic.pinnedRing(have, listOf("p3", "gone", "p1", "p3")))
        assertEquals(emptyList<String>(), Logic.pinnedRing(have, emptyList()))
        assertEquals(Logic.MAX_PINS, Logic.pinnedRing(have, have).size)
    }
    @Test fun ringFallsBackToAlphabeticalWithoutUsage() {
        val r = Logic.ringPkgs(listOf("z" to "Zoom", "a" to "Audible", "m" to "Maps"), emptyMap(), emptyList())
        assertEquals(listOf("a", "m", "z"), r)
    }
    @Test fun moods() {
        assertEquals("bright morning", Logic.mood(7.0, 0.8f, false).name); assertEquals("calm night", Logic.mood(23.5, 0.8f, false).name)
        val tired = Logic.mood(12.0, 0.1f, false); assertEquals(1.0, tired.tired, 0.0); assertTrue(tired.lid < 0.7)
        assertTrue(Logic.mood(12.0, 0.5f, true).name.endsWith("charging")); assertFalse(Logic.mood(12.0, 1f, true).name.endsWith("charging"))
    }
    @Test fun chargingNeedsPluggedAndNotFull() { assertTrue(Logic.charging(true, 0.5f)); assertFalse(Logic.charging(true, 1f)); assertFalse(Logic.charging(false, 0.5f)) }
    @Test fun trayNeverCoversTheOrb() {
        for (r in listOf(96f, 102f)) { val cy = 454f; val b = Logic.trayBox(cy, r, 24f); assertTrue("bottom ${b[0]} above orb top ${cy - r}", b[0] < cy - r * 1.02f); assertTrue(b[1] >= 120f) }
    }
    @Test fun weatherCodes() { assertEquals("sun", Logic.cond(0).second); assertEquals("rain", Logic.cond(63).second); assertEquals("storm", Logic.cond(95).second); assertEquals("snow", Logic.cond(73).second) }
    @Test fun parsesOpenMeteo() {
        val j = JSONObject("""{"current":{"temperature_2m":74.6,"weather_code":2,"is_day":1},"daily":{"temperature_2m_max":[76.2],"temperature_2m_min":[51.7]}}""")
        val w = Wx.parseForecast(j, "Springfield, IL", 39.8, -89.6, true, 5L); assertEquals(75, w.temp); assertEquals(76, w.hi); assertEquals(52, w.lo); assertTrue(w.day)
        assertEquals(w, Wx.from(w.toJson()))
        assertEquals("Denver, CO", Wx.parsePlace(JSONObject("""{"city":"Denver","principalSubdivisionCode":"US-CO"}""")))
    }
    @Test fun notificationFilter() {
        assertTrue(NotifStore.keep("com.google.android.gm", "me", 0, false, "Bob", "hi"))
        assertFalse(NotifStore.keep("me", "me", 0, false, "x", "y"))
        assertFalse(NotifStore.keep("a", "me", android.app.Notification.FLAG_ONGOING_EVENT, false, "x", "y"))
        assertFalse(NotifStore.keep("a", "me", 0, true, "x", "y")); assertFalse(NotifStore.keep("a", "me", 0, false, "", ""))
    }
    @Test fun letterAndTag() { assertEquals("G", IconMath.letter("Gmail")); assertEquals("1", IconMath.letter(" 1Password")); assertEquals(2, IconMath.codeTag("com.x", false).length) }
    @Test fun grokIntentsAreMethodC() {
        val i = Grok.assistIntent(); assertEquals(android.content.Intent.ACTION_ASSIST, i.action); assertEquals("ai.x.grok.bot", i.component?.packageName)
        assertEquals("expo.modules.nativehelpers.AssistTrampolineActivity", i.component?.className); assertEquals(2000L, Grok.HOME_DELAY_MS)
        assertEquals(0.80, WakeService.THRESHOLD, 0.0)
    }
}
