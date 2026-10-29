package com.tyler.primelauncher

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Process
import android.view.View
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

/** 0.1.23: seven-hour weather tiles — parsing, cache compatibility, location states, and no overlap with notes/orb/chat. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WeatherTilesTest {
    private val hourly = """{"utc_offset_seconds":-18000,"current":{"temperature_2m":54.2,"weather_code":3,"is_day":1,"time":"2026-10-29T08:15"},
        "daily":{"temperature_2m_max":[66.0],"temperature_2m_min":[48.0]},
        "hourly":{"time":["2026-10-29T06:00","2026-10-29T07:00","2026-10-29T08:00","2026-10-29T09:00","2026-10-29T10:00","2026-10-29T11:00","2026-10-29T12:00","2026-10-29T13:00","2026-10-29T14:00","2026-10-29T15:00"],
        "temperature_2m":[50,52,54.4,57,61,64,66,65,62,60],"weather_code":[3,3,3,3,0,0,1,61,63,95],"is_day":[0,1,1,1,1,1,1,1,1,1]}}"""
    // 2026-10-29T08:15 at UTC-5 = 13:15 UTC
    private val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse("2026-10-29T13:15")!!.time

    @Test fun parsesNextSevenHoursCurrentFirst() {
        val w = Wx.parseForecast(JSONObject(hourly), "Here", 0.0, 0.0, true, now)
        assertEquals(7, w.hours.size); assertEquals("8 AM", w.hours[0].label); assertEquals(54, w.hours[0].temp); assertEquals("2 PM", w.hours[6].label)
        assertEquals("rain", WxTiles.kind(w.hours[5])); assertEquals("Rainy", WxTiles.word(w.hours[6])); assertEquals("Sunny", WxTiles.word(w.hours[2]))
        assertEquals(w, Wx.from(w.toJson()))
        // an hour later the first tile has rolled off
        assertEquals("9 AM", w.upcoming(now + 3_600_000L)[0].label)
        assertEquals("12 PM", WxHour(0, 12, 0, 0, true).label); assertEquals("12 AM", WxHour(0, 0, 0, 0, true).label)
    }
    @Test fun oldCacheStillParses() {
        val old = """{"temp":70,"code":2,"day":true,"hi":75,"lo":50,"place":"X","fetched":5,"lat":1.0,"lon":2.0,"gps":true}"""
        val w = Wx.from(old); assertNotNull(w); assertTrue(w!!.hours.isEmpty()); assertEquals(70, w.temp)
    }
    @Test fun noteSlotsStackDownAndStopAboveOrb() {
        val s = Logic.noteSlots(220f, 340f); assertTrue(s.isNotEmpty()); assertEquals(228f, s[0], 0.01f)
        s.forEach { assertTrue(it + Logic.NOTE_H <= 340f - 4f) }
        assertEquals(3, Logic.noteSlots(200f, 600f).size); assertEquals(0, Logic.noteSlots(178f, 186f).size)
    }
    @Test fun noFallbackWhenPermissionOff() {
        val ctx = RuntimeEnvironment.getApplication(); val p = Prefs(ctx); p.wxGps = true
        val e = runCatching { Weather.fetch(ctx, p) }.exceptionOrNull()
        assertTrue("expected NoLocation, got $e", e is Weather.NoLocation); assertEquals(Weather.LOC_OFF, Weather.locState)
        assertEquals("", p.wxCache) // nothing fetched for a fallback spot
        Weather.locState = Weather.LOC_OK
    }

    private val pkgs = listOf("com.google.android.gm" to "Gmail", "com.twitter.android" to "X", "com.android.chrome" to "Chrome")
    private var clock = 1_000_000_000L
    private fun frames(h: HomeActivity, n: Int) { repeat(n) { clock += 16_666_667L; h.stage.doFrame(clock); ShadowLooper.idleMainLooper(17L, TimeUnit.MILLISECONDS) } }
    private fun rectOf(v: View, root: View): RectF { val a = IntArray(2); val b = IntArray(2); v.getLocationInWindow(a); root.getLocationInWindow(b)
        return RectF((a[0] - b[0]).toFloat(), (a[1] - b[1]).toFloat(), (a[0] - b[0] + v.width).toFloat(), (a[1] - b[1] + v.height).toFloat()) }
    private fun overlaps(a: RectF, b: RectF) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    private fun noOverlap(wPx: Int, hPx: Int, theme: String, tag: String, minNotes: Int = 1, fontScale: Float = 1.5f) {
        RuntimeEnvironment.setFontScale(fontScale)
        Apps.setForTest(pkgs.map { AppEntry(it.first, it.second, ComponentName(it.first, "${it.first}.Main"), Process.myUserHandle()) }, emptyMap())
        val app = RuntimeEnvironment.getApplication(); app.getSharedPreferences("prime", 0).edit().putBoolean("onboarded", true).putString("theme", theme).putBoolean("companion", true).commit()
        val w = Wx.parseForecast(JSONObject(hourly), "Here", 0.0, 0.0, true, now) // fixed clock: the fixture only covers 06:00-15:00
        val shifted = w.copy(fetched = System.currentTimeMillis(), hours = (0 until 7).map { i -> WxHour(System.currentTimeMillis() - 600_000L + i * 3_600_000L, (8 + i) % 24, 54 + i, w.hours[i].code, true) })
        Prefs(app).wxCache = shifted.toJson(); Prefs(app).wxGps = false // explicit saved spot: no location prompt in the test
        val ctl = Robolectric.buildActivity(HomeActivity::class.java).create(); val h = ctl.get(); h.stage.manualFrames = true; ctl.start().resume().visible()
        val root = h.window.decorView; fun lay() { root.measure(View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(hPx, View.MeasureSpec.EXACTLY)); root.layout(0, 0, wPx, hPx) }
        lay(); ShadowLooper.idleMainLooper(600L, TimeUnit.MILLISECONDS); h.stage.stop(); h.stage.start(); lay(); frames(h, 30); lay()
        val d = h.stage.d
        assertEquals(7, h.wxTiles.hours.size)
        val tiles = rectOf(h.wxTiles, root)
        assertTrue("headerBottom ${h.stage.headerBottom * d} >= tiles bottom ${tiles.bottom}", h.stage.headerBottom * d >= tiles.bottom - 1)
        val orbR = h.stage.orbR() * 1.12f * d; val orb = RectF(h.stage.cxOrb() * d - orbR, h.stage.cy * d - orbR, h.stage.cxOrb() * d + orbR, h.stage.cy * d + orbR)
        assertFalse("tiles vs orb", overlaps(tiles, orb))
        NotifStore.reset(emptyList()); ShadowLooper.idleMainLooper(50L, TimeUnit.MILLISECONDS)
        repeat(4) { i -> NotifStore.posted(Note("$tag$theme$i", pkgs[i % 2].first, "Sender $i", "A fairly long message body number $i that wraps onto a second line", 1000L + i, null, true, true), true) }
        ShadowLooper.idleMainLooper(700L, TimeUnit.MILLISECONDS); frames(h, 10); root.requestLayout(); lay()
        val bubbles = (h.window.decorView.findViewById<View>(android.R.id.content) as android.view.ViewGroup).let { all(it) }.filter { it.tag is Int && it.parent is android.widget.FrameLayout && it.width in 1..((300 * d).toInt() + 2) }
        val bf = HomeActivity::class.java.getDeclaredField("bubbles").apply { isAccessible = true }.get(h) as android.view.ViewGroup
        assertTrue("1..3 notes visible (${bubbles.size}) kids=${bf.childCount} q=${(HomeActivity::class.java.getDeclaredField("queue").apply { isAccessible = true }.get(h) as List<*>).size} sh=${(HomeActivity::class.java.getDeclaredField("shown").apply { isAccessible = true }.get(h) as List<*>).size} vis=${PrimeApp.homeVisible} store=${NotifStore.all().size} l=${NotifStore.listener === h}", bubbles.size in minNotes..3)
        val rs = bubbles.map { rectOf(it, root) }
        rs.forEachIndexed { i, r -> assertFalse("note $i vs tiles", overlaps(r, tiles)); assertFalse("note $i $r vs orb $orb tiles $tiles", overlaps(r, orb))
            rs.forEachIndexed { j, o -> if (i != j) assertFalse("note $i vs note $j", overlaps(r, o)) } }
        // the orb's own chat bubble goes below the orb while notes are up, and stays under the header otherwise
        val buddy = h.stage.buddy; assertTrue(buddy.say(ChatBubble("Hey there, just checking in on you today", Cat.MUSING, null), noteBubblesShowing = true))
        val cv = Canvas(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)); buddy.drawBubble(cv)
        val cb = RectF(buddy.bubble!!.rect.left * d, buddy.bubble!!.rect.top * d, buddy.bubble!!.rect.right * d, buddy.bubble!!.rect.bottom * d)
        assertFalse("chat vs tiles", overlaps(cb, tiles)); rs.forEachIndexed { i, r -> assertFalse("chat vs note $i", overlaps(cb, r)) }
        assertTrue("chat below header", cb.top >= tiles.bottom)
        val out = File("build/wxmock").apply { mkdirs() }
        val bm = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888); root.draw(Canvas(bm)); File(out, "$tag-$theme.png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 90, it) }
        NotifStore.reset(emptyList()); ctl.pause().stop().destroy(); RuntimeEnvironment.setFontScale(1f)
    }
    private fun all(g: android.view.ViewGroup): List<View> = (0 until g.childCount).flatMap { val c = g.getChildAt(it); listOf(c) + if (c is android.view.ViewGroup) all(c) else emptyList() }

    private fun tilesInMargins(fontScale: Float, theme: String, snap: String?) {
        RuntimeEnvironment.setFontScale(fontScale)
        Apps.setForTest(pkgs.map { AppEntry(it.first, it.second, ComponentName(it.first, "${it.first}.Main"), Process.myUserHandle()) }, emptyMap())
        val app = RuntimeEnvironment.getApplication(); app.getSharedPreferences("prime", 0).edit().putBoolean("onboarded", true).putString("theme", theme).putBoolean("companion", true).commit()
        val w = Wx.parseForecast(JSONObject(hourly), "Here", 0.0, 0.0, true, now) // fixed clock: the fixture only covers 06:00-15:00
        Prefs(app).wxCache = w.copy(fetched = System.currentTimeMillis(), hours = (0 until 7).map { i -> WxHour(System.currentTimeMillis() - 600_000L + i * 3_600_000L, (8 + i) % 24, 46 + i * 5, w.hours[i].code, true) }).toJson(); Prefs(app).wxGps = false
        val ctl = Robolectric.buildActivity(HomeActivity::class.java).create(); val h = ctl.get(); h.stage.manualFrames = true; ctl.start().resume().visible()
        val root = h.window.decorView; fun lay() { root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 1080, 2400) }
        lay(); ShadowLooper.idleMainLooper(600L, TimeUnit.MILLISECONDS); frames(h, 10); lay()
        assertEquals(7, h.wxTiles.hours.size)
        val date = rectOf(h.findViewById<View>(android.R.id.content).let { all(it as android.view.ViewGroup) }.first { it is android.widget.TextView && it.text.toString().contains(",") && it.text.toString().any { c -> c.isDigit() } }, root)
        val v = rectOf(h.wxTiles, root); val left = date.left; val right = 1080f - left
        h.wxTiles.tileRects().forEachIndexed { i, t -> val r = RectF(t.left + v.left, t.top + v.top, t.right + v.left, t.bottom + v.top)
            assertTrue("tile $i $r off screen @${fontScale}x", r.left >= 0f && r.right <= 1080f)
            assertTrue("tile $i $r outside date margins [$left,$right] @${fontScale}x", r.left >= left - 1f && r.right <= right + 1f) }
        assertEquals("first tile aligned with date", left, h.wxTiles.tileRects()[0].left + v.left, 1.5f)
        assertEquals("last tile symmetric", right, h.wxTiles.tileRects()[6].right + v.left, 3f) // Robolectric screen is 411dp = 1078px
        val bmT = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888); root.draw(Canvas(bmT))
        val wt = h.wxTiles; assertEquals(7, wt.lastTemp.size); assertEquals(7, wt.lastIcon.size)
        wt.tileRects().forEachIndexed { i, t ->
            val tr = wt.lastTemp[i]; val ir = wt.lastIcon[i]
            assertFalse("temp $tr over icon $ir tile $i $theme @${fontScale}x", overlaps(tr, ir))
            assertTrue("temp above icon", tr.bottom <= ir.top); assertTrue("temp inside tile", tr.left >= t.left && tr.right <= t.right && tr.top >= 0f)
            assertTrue("temp largest text", wt.lastTempSize[i] > wt.lastOther[i]) }
        if (snap != null) { val f = File(snap).apply { parentFile!!.mkdirs() }; val bm = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888); root.draw(Canvas(bm)); f.outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 90, it) } }
        ctl.pause().stop().destroy(); RuntimeEnvironment.setFontScale(1f)
    }
    @Test fun tilesFitAt15xNeon() = tilesInMargins(1.5f, "neon", "build/wxmock/neon15.png")
    @Test fun tilesFitAt15xMatrix() = tilesInMargins(1.5f, "matrix", "build/wxmock/matrix15.png")
    @Test fun tilesFitAt15xOdst() = tilesInMargins(1.5f, "odst", "build/wxmock/odst15.png")
    @Test fun tilesFitAt10x() = tilesInMargins(1.0f, "matrix", null)
    @Test fun tilesFitAt10xNeon() = tilesInMargins(1.0f, "neon", null)
    @Test fun tilesFitAt10xOdst() = tilesInMargins(1.0f, "odst", null)
    @Test fun skinPerTheme() { assertEquals(TileSkin.MATRIX, WxTiles.skinOf(Pal(false))); assertEquals(TileSkin.NEON, WxTiles.skinOf(Pal(true))); assertEquals(TileSkin.ODST, WxTiles.skinOf(Pal(false, true))) }
    @Test fun s20feMatrix() = noOverlap(1080, 2400, "matrix", "s20fe")
    @Test fun s20feNeon() = noOverlap(1080, 2400, "neon", "s20fe")
    @Test fun s20feOdst() = noOverlap(1080, 2400, "odst", "s20fe")
    @Test fun s20feMatrix10() = noOverlap(1080, 2400, "matrix", "s20fe10", fontScale = 1.0f)
    @Test fun s20feNeon10() = noOverlap(1080, 2400, "neon", "s20fe10", fontScale = 1.0f)
    @Test fun s20feOdst10() = noOverlap(1080, 2400, "odst", "s20fe10", fontScale = 1.0f)
    @Test @Config(qualifiers = "w360dp-h640dp-320dpi") fun smallScreen() = noOverlap(720, 1280, "matrix", "small", minNotes = 0, fontScale = 1.0f)
}
