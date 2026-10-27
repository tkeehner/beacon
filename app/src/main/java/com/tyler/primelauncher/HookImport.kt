package com.tyler.primelauncher

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * 0.1.10: one-time secure import of the agent-connector config from the owner's PC (url, key, header, scheme, optional connector/template), so the key is never
 * typed, passed as a command argument, or logged.
 *
 *  1. The app keeps an RSA-2048 key pair in Android Keystore (private key never leaves the phone) and writes only the
 *     PUBLIC key to its own app folder: Android/data/com.tyler.primelauncher/files/prime_hook_import.pub
 *  2. The PC encrypts {url, header, scheme, key[, test]} to that public key (RSA-OAEP SHA-256 wraps a fresh AES-256 key;
 *     AES-GCM encrypts the JSON) and `adb push`es the ciphertext as prime_hook_import.bin. Plain adb-over-TCP only ever
 *     carries ciphertext, and only this phone can open it.
 *  3. On app start, screen on, or Home resume the app reads + DELETES the .bin, decrypts it, seals the key with its own
 *     Keystore AES key ([HookStore]) and writes a status file with no secrets in it ("ok configured=true ...").
 *  4. If the payload asks for it (test=true), it sends exactly ONE test message and records only the outcome.
 * Other apps can't write into this app's Android/data folder (scoped storage), so only adb/the app itself can feed it.
 */
object HookImport {
    const val PUB = "prime_hook_import.pub"; const val IN = "prime_hook_import.bin"; const val STATUS = "prime_hook_import.status"
    const val TEST_TEXT = "test from home box"
    private const val MAX_BYTES = 16 * 1024

    interface Box { fun publicKeyDer(): ByteArray; fun unwrap(wrapped: ByteArray): ByteArray }
    object KeystoreBox : Box {
        private const val ALIAS = "prime_hook_import_rsa"
        private fun ks() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        private fun ensure() {
            if (ks().containsAlias(ALIAS)) return
            val g = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore")
            g.initialize(android.security.keystore.KeyGenParameterSpec.Builder(ALIAS, android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(2048).setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256, android.security.keystore.KeyProperties.DIGEST_SHA1).build())
            g.generateKeyPair()
        }
        override fun publicKeyDer(): ByteArray { ensure(); return ks().getCertificate(ALIAS).publicKey.encoded }
        override fun unwrap(wrapped: ByteArray): ByteArray {
            val k = ks().getKey(ALIAS, null) as PrivateKey
            return oaep(Cipher.DECRYPT_MODE, k).doFinal(wrapped)
        }
    }
    /** RSA-OAEP, SHA-256 digest, MGF1-SHA1 (the combination Android Keystore supports on every API level). */
    fun oaep(mode: Int, k: java.security.Key): Cipher = Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
        init(mode, k, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)) }
    @Volatile var box: Box = KeystoreBox

    private val bg = Executors.newSingleThreadExecutor()
    /** Safe to call often: cheap file check; the work runs off the main thread. */
    fun poke(ctx: Context) {
        if (box === KeystoreBox && android.os.Build.FINGERPRINT == "robolectric") return // unit tests drive run() directly
        val app = ctx.applicationContext; bg.execute { runCatching { prepare(app); run(app) } }
    }

    fun dir(ctx: Context): File? = ctx.getExternalFilesDir(null)?.also { it.mkdirs() }
    /** Publish the public key (once). */
    fun prepare(ctx: Context) { val d = dir(ctx) ?: return; val f = File(d, PUB)
        if (!f.exists()) f.writeText(Base64.encodeToString(box.publicKeyDer(), Base64.NO_WRAP)) }

    /** Import if a payload is waiting. Returns the status line written (never contains the URL or key), or null if none. */
    @Synchronized fun run(ctx: Context): String? {
        val d = dir(ctx) ?: return null; val f = File(d, IN); if (!f.exists()) return null
        val raw = try { if (f.length() > MAX_BYTES) null else f.readBytes() } finally { f.delete() } // never leave the ciphertext behind
        val status = try {
            if (raw == null) error("payload too large")
            val o = JSONObject(String(raw, Charsets.UTF_8))
            val aes = box.unwrap(Base64.decode(o.getString("wk"), Base64.DEFAULT))
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, Base64.decode(o.getString("iv"), Base64.DEFAULT)))
            val p = JSONObject(String(c.doFinal(Base64.decode(o.getString("ct"), Base64.DEFAULT)), Charsets.UTF_8))
            val store = HookStore(ctx)
            store.save(p.getString("url"), p.getString("key"), p.optString("header", "Authorization"), p.optString("scheme", "Bearer"),
                p.optString("template").ifBlank { Connectors.preset(p.optString("connector"))?.template })
            var line = "ok configured=${store.configured} header_default=${store.cfg()?.header == "Authorization"} scheme_bearer=${store.cfg()?.scheme == "Bearer"} connector=${store.connectorId}"
            if (p.optBoolean("test", false)) line += " " + testSend(ctx)
            line
        } catch (e: IllegalArgumentException) { "error: ${e.message}" } // HookStore messages never contain the key
        catch (e: Exception) { "error: ${e.javaClass.simpleName}" }
        File(d, STATUS).writeText(status + "\n")
        return status
    }
    /** Exactly one test POST, run synchronously on the import thread; only the outcome is recorded. */
    private fun testSend(ctx: Context): String {
        val lock = Object(); var r: PrimeHook.Result? = null
        PrimeHook.send(ctx, TEST_TEXT, direct = true) { synchronized(lock) { r = it; lock.notifyAll() } }
        synchronized(lock) { val end = System.currentTimeMillis() + 30_000; while (r == null && System.currentTimeMillis() < end) lock.wait(1000) }
        return when (val x = r) { PrimeHook.Result.Sent -> "test=sent"; PrimeHook.Result.NotSetUp -> "test=not_set_up"; is PrimeHook.Result.Failed -> "test=failed(${x.why})"; null -> "test=timeout" }
    }
}
