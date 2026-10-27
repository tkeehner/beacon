package com.tyler.primelauncher

import android.content.ClipboardManager
import android.content.ComponentName
import android.os.Process
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

/** Message box POSTs straight to the configured agent connector (template-driven body). No app opens, no Emit, no clipboard. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MessagePrimeTest {
    /** Robolectric has no AndroidKeyStore: a reversible stand-in so the store logic is exercised. */
    class FakeSealer : Sealer {
        override fun seal(raw: ByteArray) = java.util.Base64.getEncoder().encodeToString(raw.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()) to "iv"
        override fun open(ct: String, iv: String) = java.util.Base64.getDecoder().decode(ct).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun forget() {}
    }
    class Rec(var code: Int = 200, var boom: Exception? = null) : PrimeHook.Transport {
        val calls = ArrayList<Triple<String, Map<String, String>, String>>()
        override fun post(url: String, headers: Map<String, String>, body: String): Int { calls += Triple(url, headers, body); boom?.let { throw it }; return code }
    }
    companion object {
        const val URL = "https://hook.example.test/prime"; const val KEY = "test-key-not-real-123"
        /** Configure a fake webhook (used by KeyboardTest too). */
        fun fakeHook(ctx: android.content.Context, code: Int = 200): Rec { HookStore.sealer = FakeSealer(); HookStore(ctx).save(URL, KEY); PrimeHook.syncForTest = true; return Rec(code).also { PrimeHook.transport = it } }
    }
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    @Before fun setUp() { HookStore.sealer = FakeSealer(); PrimeHook.syncForTest = true }
    @After fun tearDown() { PrimeHook.transport = PrimeHook.https; PrimeHook.syncForTest = false; HookStore.sealer = KeystoreSealer }

    private fun home(): Pair<org.robolectric.android.controller.ActivityController<HomeActivity>, HomeActivity> {
        Apps.setForTest(listOf(AppEntry("com.android.chrome", "Chrome", ComponentName("com.android.chrome", "x.Main"), Process.myUserHandle())), emptyMap())
        app.getSharedPreferences("prime", 0).edit().putBoolean("onboarded", true).commit()
        val ctl = Robolectric.buildActivity(HomeActivity::class.java).create(); val h = ctl.get(); h.stage.manualFrames = true; ctl.start().resume().visible()
        val root = h.window.decorView; root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 1080, 2400)
        ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS); while (shadowOf(h).nextStartedActivity != null) {}
        return ctl to h
    }
    private fun clip() = app.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()
    private fun box(h: HomeActivity) = (h.window.decorView as android.view.ViewGroup).let { v -> find(v) }!!
    private fun find(v: View): android.widget.EditText? { if (v is android.widget.EditText && v.hint?.toString()?.contains("prime", true) == true) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }; return null }
    private fun noAppOpened(h: HomeActivity) = assertNull("no app opened (no Grok Bot, no Emit)", shadowOf(h).nextStartedActivity)

    @Test fun boxSendsTrimmedTextStraightToWebhookOnly() {
        val (ctl, h) = home(); val rec = fakeHook(app)
        val e = box(h); e.setText("  call mom  "); h.sendPrime(); ShadowLooper.idleMainLooper(800, TimeUnit.MILLISECONDS)
        assertEquals("exactly one POST", 1, rec.calls.size); val (url, hdr, body) = rec.calls[0]
        assertEquals(URL, url); assertEquals(mapOf("Authorization" to "Bearer $KEY"), hdr)
        val j = JSONObject(body); assertEquals("call mom", j.getString("text"))
        assertEquals("default connector is Generic JSON", "beacon", j.getString("source")); assertEquals(Connectors.VIA, j.getString("via"))
        assertTrue(j.getString("msg_id").length >= 32); assertTrue(j.getLong("ts") > 0); assertFalse("no agent-specific fields by default", j.has("reply_with"))
        noAppOpened(h); assertNull("no clipboard", clip())
        assertEquals("box cleared after success", "", e.text.toString()); assertTrue(h.lastSendNote.startsWith("Sent to Prime"))
        ctl.pause().stop().destroy()
    }
    @Test fun everyMessageGoesToWebhookIncludingSearchPanelOnes() {
        val (ctl, h) = home(); val rec = fakeHook(app)
        h.sendPrime("text Sarah I'm late"); h.sendPrime("what's on my calendar"); ShadowLooper.idleMainLooper(800, TimeUnit.MILLISECONDS)
        assertEquals(listOf("text Sarah I'm late", "what's on my calendar"), rec.calls.map { JSONObject(it.third).getString("text") })
        assertEquals("unique ids", 2, rec.calls.map { JSONObject(it.third).getString("msg_id") }.toSet().size); noAppOpened(h)
        ctl.pause().stop().destroy()
    }
    @Test fun httpFailureIsVisibleKeepsTextAndNeverLeaksKey() {
        val (ctl, h) = home(); val rec = fakeHook(app, code = 500)
        val e = box(h); e.setText("call mom"); h.sendPrime(); ShadowLooper.idleMainLooper(800, TimeUnit.MILLISECONDS)
        assertEquals(1, rec.calls.size); assertEquals("text kept for retry", "call mom", e.text.toString())
        assertTrue(h.lastSendNote, h.lastSendNote.startsWith("Not sent") && h.lastSendNote.contains("500"))
        assertFalse(h.lastSendNote.contains(KEY)); assertFalse(h.lastSendNote.contains("hook.example")); noAppOpened(h)
        rec.code = 401; h.sendPrime(); assertTrue(h.lastSendNote, h.lastSendNote.contains("refused the key"))
        rec.code = 202; h.sendPrime(); assertTrue("retry works", h.lastSendNote.startsWith("Sent to Prime")); assertEquals("", e.text.toString())
        ctl.pause().stop().destroy()
    }
    @Test fun noNetworkIsVisible() {
        val (ctl, h) = home(); val rec = fakeHook(app); rec.boom = java.net.UnknownHostException("no dns")
        val e = box(h); e.setText("call mom"); h.sendPrime()
        assertTrue(h.lastSendNote, h.lastSendNote.contains("No connection")); assertEquals("call mom", e.text.toString()); noAppOpened(h)
        ctl.pause().stop().destroy()
    }
    @Test fun failedSearchPanelSendLandsInTheBoxForRetry() {
        val (ctl, h) = home(); fakeHook(app, code = 503)
        h.sendPrime("remind me at 5"); assertEquals("remind me at 5", box(h).text.toString()); noAppOpened(h)
        ctl.pause().stop().destroy()
    }
    @Test fun notSetUpSaysSoAndSendsNothing() {
        val (ctl, h) = home(); val rec = Rec(); PrimeHook.transport = rec
        val e = box(h); e.setText("call mom"); h.sendPrime(); ShadowLooper.idleMainLooper(800, TimeUnit.MILLISECONDS)
        assertTrue(rec.calls.isEmpty()); assertTrue(h.lastSendNote, h.lastSendNote.contains("isn't set up")); assertEquals("call mom", e.text.toString())
        noAppOpened(h); assertNull(clip())
        ctl.pause().stop().destroy()
    }
    @Test fun emptyTextDoesNothing() {
        val (ctl, h) = home(); val rec = fakeHook(app)
        h.sendPrime("   "); box(h).setText(" "); h.sendPrime(); assertTrue(rec.calls.isEmpty()); noAppOpened(h)
        ctl.pause().stop().destroy()
    }
    @Test fun customHeaderAndRawScheme() {
        HookStore(app).save(URL, KEY, header = "X-Api-Key", scheme = ""); val rec = Rec(); PrimeHook.transport = rec; var r: PrimeHook.Result? = null
        PrimeHook.send(app, "hi") { r = it }; assertEquals(PrimeHook.Result.Sent, r); assertEquals(mapOf("X-Api-Key" to KEY), rec.calls[0].second)
    }
    @Test fun storeSealsKeyNeverPlainAndRejectsBadValues() {
        val s = HookStore(app); assertFalse(s.configured); assertEquals("not set", s.summary())
        try { s.save("http://insecure.example/x", KEY); throw AssertionError("http accepted") } catch (e: IllegalArgumentException) { assertFalse(e.message!!.contains(KEY)) }
        try { s.save(URL, "has space"); throw AssertionError("bad key accepted") } catch (_: IllegalArgumentException) {}
        try { s.save(URL, null); throw AssertionError("missing key accepted") } catch (_: IllegalArgumentException) {}
        s.save(URL, KEY); assertTrue(s.configured); assertEquals(KEY, s.key()); assertEquals("set · Generic JSON · hook.example.test", s.summary())
        val xml = File(app.applicationInfo.dataDir, "shared_prefs/prime_hook.xml").readText(); assertFalse("key never stored in the clear", xml.contains(KEY))
        s.save("https://hook.example.test/other", null); assertEquals("blank key keeps the old one", KEY, s.key())
        s.clear(); assertFalse(s.configured); assertNull(s.key())
    }
    @Test fun grokEmitExampleConnectorMirrorsEmitPcCompanion() {
        val j = JSONObject(PrimeHook.body("x", "id1", 5L, Connectors.preset(Connectors.GROK_EMIT)!!.template))
        for (k in listOf("source", "type", "kind", "from_self", "msg_id", "text", "ts", "reply_with")) assertTrue("has $k", j.has(k))
        assertEquals("id1", j.getString("msg_id")); assertEquals(5L, j.getLong("ts")); assertEquals("prime_chat", j.getString("type"))
    }
    @Test fun everyPresetRendersValidJsonWithEscapedText() {
        val tricky = "he said \"hi\"\n{{text}} {{msg_id}} {{ts}} \\ done"
        for (p in Connectors.presets) { assertTrue(p.id, Connectors.validTemplate(p.template))
            val out = PrimeHook.body(tricky, "id", 1L, p.template); val v = org.json.JSONTokener(out).nextValue(); assertTrue(p.id, out.contains("he said")); assertTrue(v is JSONObject) }
        assertEquals(tricky, JSONObject(PrimeHook.body(tricky, "id", 1L, Connectors.preset(Connectors.TEXT_ONLY)!!.template)).getString("text"))
    }
    @Test fun customTemplateIsUsedAndBadTemplatesAreRejected() {
        val custom = """{"input":{"prompt":{{text}}},"id":{{msg_id}}}"""
        HookStore(app).save(URL, KEY, template = custom); val rec = Rec(); PrimeHook.transport = rec; var r: PrimeHook.Result? = null
        PrimeHook.send(app, "hello agent") { r = it }; assertEquals(PrimeHook.Result.Sent, r)
        assertEquals("hello agent", JSONObject(rec.calls[0].third).getJSONObject("input").getString("prompt")); assertEquals(Connectors.CUSTOM, HookStore(app).connectorId)
        for (bad in listOf("""{"text": "no placeholder"}""", """{"text": {{text}}""", "not json {{text}}"))
            try { HookStore(app).save(URL, null, template = bad); throw AssertionError("accepted $bad") } catch (e: IllegalArgumentException) { assertFalse(e.message!!.contains(KEY)) }
        assertEquals("bad template didn't overwrite", custom, HookStore(app).template)
    }
}
