package com.tyler.primelauncher

import android.util.Base64
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** 0.1.10 secure webhook import: ciphertext in, key sealed, file deleted, status has no secrets, at most one test POST. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HookImportTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private class SoftBox(val kp: KeyPair) : HookImport.Box {
        override fun publicKeyDer(): ByteArray = kp.public.encoded
        override fun unwrap(wrapped: ByteArray): ByteArray = HookImport.oaep(Cipher.DECRYPT_MODE, kp.private).doFinal(wrapped)
    }
    private lateinit var kp: KeyPair
    private val url = MessagePrimeTest.URL; private val key = MessagePrimeTest.KEY
    @Before fun setUp() { kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair(); HookImport.box = SoftBox(kp); HookStore.sealer = MessagePrimeTest.FakeSealer() }
    @After fun tearDown() { HookImport.box = HookImport.KeystoreBox; HookStore.sealer = KeystoreSealer; PrimeHook.transport = PrimeHook.https; PrimeHook.syncForTest = false }

    /** Same construction as the PC's hook_seal.seal(). */
    private fun seal(pub: java.security.PublicKey, payload: JSONObject): ByteArray {
        val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey(); val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, aes, GCMParameterSpec(128, iv))
        val ct = c.doFinal(payload.toString().toByteArray()); val wk = HookImport.oaep(Cipher.ENCRYPT_MODE, pub).doFinal(aes.encoded)
        fun b(x: ByteArray) = Base64.encodeToString(x, Base64.NO_WRAP)
        return JSONObject().put("v", 1).put("wk", b(wk)).put("iv", b(iv)).put("ct", b(ct)).toString().toByteArray()
    }
    private fun dir() = HookImport.dir(app)!!
    private fun push(bytes: ByteArray) = File(dir(), HookImport.IN).writeBytes(bytes)
    private fun payload(test: Boolean = false) = JSONObject().put("url", url).put("header", "Authorization").put("scheme", "Bearer").put("key", key).put("test", test)
    private fun statusFile() = File(dir(), HookImport.STATUS)

    @Test fun publishesOnlyThePublicKey() {
        HookImport.prepare(app); val pub = File(dir(), HookImport.PUB).readText()
        assertTrue(Base64.decode(pub, Base64.DEFAULT).contentEquals(kp.public.encoded))
    }
    @Test fun importsSealsDeletesAndReportsWithoutSecrets() {
        push(seal(kp.public, payload())); val st = HookImport.run(app)!!
        assertTrue(st, st.startsWith("ok configured=true")); assertFalse("ciphertext deleted", File(dir(), HookImport.IN).exists())
        val s = HookStore(app); assertTrue(s.configured); assertEquals(key, s.key()); assertEquals(url, s.url)
        val txt = statusFile().readText(); assertFalse(txt.contains(key)); assertFalse(txt.contains("hook.example")); assertFalse("no test unless asked", txt.contains("test="))
        assertNull("nothing pending -> no-op", HookImport.run(app))
    }
    @Test fun optionalTestSendsExactlyOneMessage() {
        val rec = MessagePrimeTest.Rec(); PrimeHook.transport = rec
        push(seal(kp.public, payload(test = true))); val st = HookImport.run(app)!!
        assertTrue(st, st.endsWith("test=sent")); assertEquals(1, rec.calls.size)
        val j = JSONObject(rec.calls[0].third); assertEquals(HookImport.TEST_TEXT, j.getString("text")); assertEquals(mapOf("Authorization" to "Bearer $key"), rec.calls[0].second)
        rec.code = 500; push(seal(kp.public, payload(test = true))); val st2 = HookImport.run(app)!!
        assertTrue(st2, st2.contains("test=failed") && st2.contains("500") && !st2.contains(key))
    }
    @Test fun tamperedOrForeignCiphertextIsRejectedAndDeleted() {
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
        push(seal(other.public, payload())); val st = HookImport.run(app)!!
        assertTrue(st, st.startsWith("error:")); assertFalse(File(dir(), HookImport.IN).exists()); assertFalse(HookStore(app).configured)
        push("not json".toByteArray()); assertTrue(HookImport.run(app)!!.startsWith("error:")); assertFalse(File(dir(), HookImport.IN).exists())
    }
    @Test fun badValuesInsidePayloadAreRejected() {
        push(seal(kp.public, payload().put("url", "http://plain.example/x"))); val st = HookImport.run(app)!!
        assertTrue(st, st.startsWith("error:") && !st.contains(key)); assertFalse(HookStore(app).configured)
    }
}
