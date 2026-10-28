package com.tyler.primelauncher

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Process
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/** 0.1.15 companion on the real StageView: two-finger poke / pet vs tap-to-talk, bubble tap / swipe, every antic draws. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BuddyTest {
    private class FakeHost(val apps: List<AppEntry>) : StageView.Host {
        val log = ArrayList<String>()
        override fun ringApps() = apps
        override fun onOrbTap() { log += "voice" }
        override fun onRingPick(a: AppEntry) { log += "pick:${a.pkg}" }
        override fun onFlick(dir: String) { log += "flick:$dir" }
        override fun onSwipeUp() { log += "up" }
        override fun onSwipeDown() { log += "down" }
        override fun onLongPressBg() { log += "settings" }
        override fun onFrame(t: Double) {}
        override fun ringHint(s: String?) {}
    }
    private class L : Buddy.Listener { val log = ArrayList<String>(); var acts: List<PoolItem> = emptyList()
        override fun beatActs() = acts
        override fun onBubbleTap(b: ChatBubble) { log += "tap:${b.cat}" }; override fun onBubbleSwipe(b: ChatBubble) { log += "swipe:${b.cat}" } }
    private lateinit var host: FakeHost; private lateinit var stage: StageView; private val lis = L()
    private var now = 0L

    @Before fun setUp() {
        val apps = listOf("com.google.android.gm" to "Gmail", "com.android.chrome" to "Chrome").map { AppEntry(it.first, it.second, ComponentName(it.first, "${it.first}.Main"), Process.myUserHandle()) }
        host = FakeHost(apps)
        val act = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        stage = StageView(act, Prefs(act), host); act.setContentView(stage)
        stage.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)); stage.layout(0, 0, 1080, 2400)
        stage.buddy.listener = lis; assertTrue(stage.ready)
    }
    private fun ev(action: Int, xDp: Float, yDp: Float) { val e = MotionEvent.obtain(0, now, action, xDp * stage.d, yDp * stage.d, 0); stage.onTouchEvent(e); e.recycle() }
    private fun ev2(action: Int, a: Pair<Float, Float>, b: Pair<Float, Float>) {
        val pp = Array(2) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val pc = arrayOf(a, b).map { MotionEvent.PointerCoords().apply { x = it.first * stage.d; y = it.second * stage.d; pressure = 1f; size = 1f } }.toTypedArray()
        val e = MotionEvent.obtain(0, now, action, 2, pp, pc, 0, 0, 1f, 1f, 0, 0, 0, 0); stage.onTouchEvent(e); e.recycle()
    }
    private fun frames(n: Int) = repeat(n) { stage.orb.step(1 / 60.0); stage.buddy.step(1 / 60.0) }
    private val P1 = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
    private val U1 = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
    private fun twoTap() {
        val a = stage.cxOrb() - 20 to stage.cy; val b = stage.cxOrb() + 25 to stage.cy + 10
        ev(MotionEvent.ACTION_DOWN, a.first, a.second); now += 30; ev2(P1, a, b); now += 90; ev2(U1, a, b); now += 20; ev(MotionEvent.ACTION_UP, a.first, a.second); now += 200
    }

    @Test fun twoFingerTapPokesWithoutOpeningVoice() {
        twoTap(); assertEquals(Antics.POKE, stage.buddy.playing); assertEquals(emptyList<String>(), host.log)
        frames(30); twoTap(); frames(10); twoTap()
        assertEquals("third poke in a row: dizzy", Antics.DIZZY, stage.buddy.playing); assertEquals(emptyList<String>(), host.log)
    }
    @Test fun singleTapIsStillInstantTapToTalk() {
        ev(MotionEvent.ACTION_DOWN, stage.cxOrb(), stage.cy); now += 60; ev(MotionEvent.ACTION_UP, stage.cxOrb(), stage.cy)
        assertEquals(listOf("voice"), host.log); assertNull(stage.buddy.playing)
    }
    @Test fun holdStillSummonsTheRing() {
        ev(MotionEvent.ACTION_DOWN, stage.cxOrb(), stage.cy); ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS); now += 400
        assertTrue(stage.ringOpen); ev(MotionEvent.ACTION_UP, stage.cxOrb(), stage.cy)
    }
    @Test fun twoFingerRubPets() {
        val a0 = stage.cxOrb() - 20 to stage.cy; ev(MotionEvent.ACTION_DOWN, a0.first, a0.second); now += 20; ev2(P1, a0, stage.cxOrb() + 20 to stage.cy)
        for (i in 0 until 30) { now += 33; val ang = i * 0.35; ev2(MotionEvent.ACTION_MOVE, (stage.cxOrb() - 20 + 22 * kotlin.math.cos(ang).toFloat()) to (stage.cy + 18 * kotlin.math.sin(ang).toFloat()), (stage.cxOrb() + 20 + 22 * kotlin.math.cos(ang).toFloat()) to (stage.cy + 18 * kotlin.math.sin(ang).toFloat())); frames(2) }
        assertTrue("purring", stage.orb.pose.happy > 0.3)
        ev2(U1, a0, stage.cxOrb() + 20 to stage.cy); ev(MotionEvent.ACTION_UP, a0.first, a0.second)
        assertEquals(Antics.PETAFTER, stage.buddy.playing); assertEquals(emptyList<String>(), host.log)
    }
    @Test fun anyTouchEndsAnActivity() {
        assertTrue(stage.buddy.play(Antics.FLASHCARDS)); frames(60)
        ev(MotionEvent.ACTION_DOWN, 40f, stage.H - 80); assertEquals(Antics.CAUGHT, stage.buddy.playing); ev(MotionEvent.ACTION_UP, 40f, stage.H - 80)
    }
    @Test fun bubbleTapAndSwipe() {
        assertTrue(stage.buddy.say(ChatBubble("there is no spoon. i checked again.", Cat.MUSING, null))); frames(20)
        val r = stage.buddy.bubble!!.rect; assertTrue("bubble stays below the clock header", r.top >= (if (stage.headerBottom > 0) stage.headerBottom else 0f))
        ev(MotionEvent.ACTION_DOWN, r.centerX(), r.centerY()); now += 60; ev(MotionEvent.ACTION_UP, r.centerX(), r.centerY())
        assertEquals(listOf("tap:MUSING"), lis.log); assertEquals("tap on a bubble is not tap-to-talk", emptyList<String>(), host.log); assertNull(stage.buddy.bubble)
        stage.buddy.say(ChatBubble("WHY DID THE PHONE WEAR GLASSES?", Cat.JOKE, "?", then = ChatBubble("IT LOST ITS CONTACTS!", Cat.JOKE, null, laugh = true))); frames(10)
        val r2 = stage.buddy.bubble!!.rect; ev(MotionEvent.ACTION_DOWN, r2.centerX(), r2.centerY())
        for (i in 1..6) { now += 16; ev(MotionEvent.ACTION_MOVE, r2.centerX() + i * 25, r2.centerY()) }; ev(MotionEvent.ACTION_UP, r2.centerX() + 150, r2.centerY())
        assertEquals("swipe:JOKE", lis.log.last()); frames(60); assertNull("swiped joke drops its punchline too", stage.buddy.bubble)
    }
    @Test fun everyAnticDrawsBothSkins() {
        val bmp = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888); val cv = Canvas(bmp)
        for (theme in listOf("matrix", "neon", "odst")) { Prefs(stage.context).theme = theme; stage.rebuild()
            for ((id, a) in Antics.all) { stage.buddy.stopAntic(); assertTrue(id, stage.buddy.play(id))
                var s = 0.0; while (s < minOf(a.len, 10.0)) { frames(6); stage.draw(cv); s += 0.1 }
                val p = stage.orb.pose; assertFalse(id, p.dx.isNaN() || p.dy.isNaN() || p.sx.isNaN() || p.open.isNaN()) }
            stage.buddy.say(when (theme) { "neon" -> ChatBubble("GREETINGS, PROGRAM! NEW DAY LOADED.", Cat.GREETING, "⚡")
                "odst" -> ChatBubble("GOOD MORNING, RESIDENT. CITY SERVICES ARE ONLINE.", Cat.GREETING, "\u25A3 MUNICIPAL NOTICE"); else -> ChatBubble("a new day loads. 100%.", Cat.GREETING, null) })
            repeat(40) { frames(3); stage.draw(cv) }; assertNotNull(stage.buddy.bubble) }
        bmp.recycle()
    }
    /** 0.1.17: the app nudge draws nothing over the ring in any theme: no glyph trail, no highlight box. */
    @Test fun ringNudgeDrawsNoTrailOrHighlight() {
        for (theme in listOf("matrix", "neon", "odst")) { Prefs(stage.context).theme = theme; stage.rebuild()
            ev(MotionEvent.ACTION_DOWN, stage.cxOrb(), stage.cy); ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS); now += 400
            assertTrue(stage.ringOpen); ev(MotionEvent.ACTION_UP, stage.cxOrb(), stage.cy); stage.buddy.stopAntic(); stage.buddy.clearBubble()
            stage.buddy.nudge(0); frames(70); assertTrue("ring out", stage.orb.sp > 0.55); assertEquals(-1, stage.orb.hot)
            val bmp = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888); val cv = Canvas(bmp); cv.scale(stage.d, stage.d)
            stage.buddy.drawOver(cv); val px = IntArray(1080 * 2400); bmp.getPixels(px, 0, 1080, 0, 0, 1080, 2400)
            assertEquals("$theme: nothing drawn for the nudge", 0, px.count { (it ushr 24) != 0 }); bmp.recycle(); stage.closeRing() }
    }
    /** Beat starts (orb time, id) while frames run for [sec] seconds. */
    private fun watchBeats(sec: Double, each: () -> Unit = {}): List<Pair<Double, String>> {
        val out = ArrayList<Pair<Double, String>>(); var prev: String? = null
        repeat((sec * 60).toInt()) { each(); frames(1); val p = stage.buddy.playing; if (p != null && p != prev) out += stage.orb.t to p; prev = p }
        return out
    }
    @Test fun idleBeatEveryFourToSixSecondsInEveryTheme() {
        for (theme in listOf("matrix", "neon", "odst")) { Prefs(stage.context).theme = theme; stage.rebuild(); stage.buddy.stopAntic(); frames(5)
            val b = watchBeats(60.0); assertTrue("$theme beats ${b.size}", b.size >= 8)
            val per = Personas.of(theme)
            for (i in 1 until b.size) { val (t0, id0) = b[i - 1]; val (t1, id1) = b[i]; assertNotEquals("$theme repeat", id0, id1)
                val gap = t1 - t0 - Antics[id0]!!.len; assertTrue("$theme gap $gap after $id0", gap in 3.95..6.1) }
            for ((_, id) in b) assertTrue("$theme: $id from its micro pool", id in per.micros) }
    }
    @Test fun beatsMixInFullActivities() {
        Prefs(stage.context).theme = "odst"; stage.rebuild(); lis.acts = OdstPersona.pool.filter { it.kind == Kind.ACT && !it.rainOrNight && it.from == 0.0 }
        val b = watchBeats(400.0); val acts = b.count { Antics[it.second]!!.kind == AK.ACT }
        assertTrue("beats ${b.size}", b.size >= 30); val share = acts.toDouble() / b.size; assertTrue("activity share $share", share in 0.15..0.45)
        assertEquals(stage.buddy.beatActs, acts)
    }
    @Test fun beatsPauseWhenTouchedSleepingOrBubbling() {
        Prefs(stage.context).theme = "matrix"; stage.rebuild(); frames(5)
        ev(MotionEvent.ACTION_DOWN, 40f, stage.H - 140f); assertTrue("finger down", watchBeats(15.0).isEmpty()); ev(MotionEvent.ACTION_UP, 40f, stage.H - 140f)
        stage.buddy.say(ChatBubble("there is no spoon.", Cat.MUSING, null, hold = 20.0)); assertTrue("bubble", watchBeats(7.0).isEmpty()); stage.buddy.clearBubble()
        stage.orb.sleep.sleepNow(); assertTrue("asleep", watchBeats(15.0).isEmpty())
    }
    @Test fun touchingAMicroJustStopsIt() {
        Prefs(stage.context).theme = "neon"; stage.rebuild(); assertTrue(stage.buddy.play(Micros.FLICKER))
        ev(MotionEvent.ACTION_DOWN, 40f, stage.H - 140f); assertNull("no caught reaction for a micro", stage.buddy.playing); ev(MotionEvent.ACTION_UP, 40f, stage.H - 140f)
    }
    @Test fun odstFaceIsOrbSizedAndFitsUnderTheHeader() {
        for (theme in listOf("matrix", "neon", "odst")) { Prefs(stage.context).theme = theme; stage.rebuild() }
        assertTrue(Skin.radius("odst") >= 0.9f * Skin.radius("neon")); assertEquals(Skin.radius("odst"), stage.orbR(), 0f)
        // the tap target follows the bigger face
        ev(MotionEvent.ACTION_DOWN, stage.cxOrb() + stage.orbR() * 0.9f, stage.cy); now += 60; ev(MotionEvent.ACTION_UP, stage.cxOrb() + stage.orbR() * 0.9f, stage.cy)
        assertTrue(host.log.contains("voice"))
        // HUD + bubble stay below the clock header and inside the screen
        stage.buddy.say(ChatBubble("ATTENTION RESIDENT: THIS ROOM IS NOT IN COMPLIANCE.", Cat.CLEAN, "\u25A3 SANITATION")); frames(30)
        val bmp = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888); stage.buddy.drawBubble(Canvas(bmp).apply { scale(stage.d, stage.d) }); bmp.recycle()
        val r = stage.buddy.bubble!!.rect; assertTrue("bubble below header ${r.top}", r.top >= stage.buddy.geo.top - 1f || stage.buddy.bubble!!.below)
        assertTrue(r.left >= 0f && r.right <= stage.W)
    }
    @Test fun companionOffMeansNoAntics() {
        Prefs(stage.context).companion = false; assertFalse(stage.buddy.play(Antics.POKE)); twoTap(); assertNull(stage.buddy.playing)
    }
}
