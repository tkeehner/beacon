package com.tyler.primelauncher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The home "message" box POSTs straight to the user's configured agent connector ([Connectors]): any agentic system
 * that accepts an HTTPS webhook. No app is opened, nothing touches the clipboard.
 *
 * Config (set once by the owner in Settings -> Agent connector; no URL, key or agent ships with the app): url (https only),
 * auth header name (default Authorization), scheme (default Bearer; empty = raw key), key, payload template. The key is sealed with an Android Keystore AES-GCM key (same pattern
 * as Phone Link's LinkStore) and is never logged, shown back, or put in an error message.
 */
object PrimeHook {
    const val MAX = 4000

    /** Request body for [text], rendered through the connector's payload template (default: Generic JSON). */
    fun body(text: String, msgId: String, ts: Long, template: String = Connectors.DEFAULT.template): String = Connectors.render(template, text, msgId, ts)

    data class Cfg(val url: String, val header: String, val scheme: String, val template: String = Connectors.DEFAULT.template)
    /** Auth header: `<header>: <scheme> <key>` (or the raw key when scheme is blank). */
    fun authHeaders(c: Cfg, key: String?): Map<String, String> =
        if (key.isNullOrEmpty()) emptyMap() else mapOf(c.header.ifBlank { "Authorization" } to (if (c.scheme.isBlank()) key else "${c.scheme} $key"))

    fun validUrl(u: String) = u.startsWith("https://") && u.length > 10 && !u.any { it.isWhitespace() }

    /** One HTTP POST; returns the status code (or throws on no network). Swappable for tests. */
    fun interface Transport { fun post(url: String, headers: Map<String, String>, body: String): Int }
    val https = Transport { url, headers, body ->
        val c = (URL(url).openConnection() as HttpURLConnection)
        try {
            c.requestMethod = "POST"; c.connectTimeout = 10_000; c.readTimeout = 15_000; c.doOutput = true; c.instanceFollowRedirects = false
            c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("User-Agent", "Beacon")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            c.responseCode
        } finally { c.disconnect() }
    }
    @Volatile var transport: Transport = https
    /** Tests: run on the calling thread. */
    @Volatile var syncForTest = false

    sealed class Result { object Sent : Result(); object NotSetUp : Result(); data class Failed(val why: String) : Result() }

    /** Background POST; [done] runs on the main thread. Never includes the key or URL in the failure text. */
    private val bg = Executors.newSingleThreadExecutor()
    /** [direct]: run the POST on the caller's thread (already off the main thread, e.g. [HookImport]). */
    fun send(ctx: Context, textIn: String, direct: Boolean = false, done: (Result) -> Unit) {
        val t = textIn.trim().take(MAX); if (t.isEmpty()) return
        val store = HookStore(ctx); val cfg = store.cfg(); val key = if (cfg != null) store.key() else null
        if (cfg == null || key == null) { done(Result.NotSetUp); return }
        val payload = try { body(t, java.util.UUID.randomUUID().toString(), System.currentTimeMillis(), cfg.template) } catch (e: IllegalArgumentException) { done(Result.Failed("payload template is invalid")); return }
        val headers = authHeaders(cfg, key); val tr = transport
        val job = {
            val r = try { val code = tr.post(cfg.url, headers, payload)
                if (code in 200..299) Result.Sent else Result.Failed(if (code == 401 || code == 403) "your agent refused the key (HTTP $code)" else "the connector answered HTTP $code")
            } catch (e: java.io.IOException) { Result.Failed("No connection to your agent") } catch (e: Exception) { Result.Failed("Couldn't send (${e.javaClass.simpleName})") }
            r
        }
        if (syncForTest || direct) { done(job()); return }
        val main = Handler(Looper.getMainLooper()); bg.execute { val r = job(); main.post { done(r) } }
    }
}

/** Seals/unseals the webhook key. Android Keystore in the app; a fake in Robolectric (which has no AndroidKeyStore). */
interface Sealer { fun seal(raw: ByteArray): Pair<String, String>; fun open(ct: String, iv: String): ByteArray; fun forget() }
object KeystoreSealer : Sealer {
    private const val ALIAS = "prime_hook_key"
    private fun key(create: Boolean): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null)
        if (ks.containsAlias(ALIAS)) return (ks.getEntry(ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        require(create) { "keystore key missing" }
        val gen = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        gen.init(android.security.keystore.KeyGenParameterSpec.Builder(ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return gen.generateKey()
    }
    override fun seal(raw: ByteArray): Pair<String, String> { val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key(true))
        return Base64.encodeToString(c.doFinal(raw), Base64.NO_WRAP) to Base64.encodeToString(c.iv, Base64.NO_WRAP) }
    override fun open(ct: String, iv: String): ByteArray { val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))); return c.doFinal(Base64.decode(ct, Base64.NO_WRAP)) }
    override fun forget() { runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) } }
}

/** App-private prefs "prime_hook" (allowBackup=false): url/header/scheme/template in the clear, key only as Keystore ciphertext. */
class HookStore(ctx: Context) {
    companion object { @Volatile var sealer: Sealer = KeystoreSealer }
    private val sp = ctx.applicationContext.getSharedPreferences("prime_hook", Context.MODE_PRIVATE)
    val configured get() = sp.getString("url", "").orEmpty().isNotEmpty() && sp.contains("key_ct")
    val url: String get() = sp.getString("url", "").orEmpty()
    fun cfg(): PrimeHook.Cfg? { val u = url; if (!PrimeHook.validUrl(u)) return null
        return PrimeHook.Cfg(u, sp.getString("header", "Authorization").orEmpty(), sp.getString("scheme", "Bearer").orEmpty(), template) }
    val template: String get() = sp.getString("template", null)?.takeIf { it.isNotBlank() } ?: Connectors.DEFAULT.template
    val connectorId: String get() = Connectors.idFor(template)
    fun key(): String? = runCatching { val ct = sp.getString("key_ct", null) ?: return null; val iv = sp.getString("key_iv", null) ?: return null
        String(sealer.open(ct, iv), Charsets.UTF_8) }.getOrNull()
    /** [key] null = keep the stored one. Throws IllegalArgumentException on a bad URL/key (message never contains the key). */
    fun save(url: String, key: String?, header: String = "Authorization", scheme: String = "Bearer", template: String? = null) {
        val u = url.trim(); require(PrimeHook.validUrl(u)) { "Webhook URL must start with https://" }
        val k = key?.trim(); if (k != null) require(k.isNotEmpty() && k.none { it.isWhitespace() }) { "Key must be one value with no spaces" }
        require(k != null || sp.contains("key_ct")) { "Key is required" }
        val tpl = template?.trim()?.ifEmpty { null }; if (tpl != null) require(Connectors.validTemplate(tpl)) { "Payload template must be valid JSON and contain {{text}}" }
        val e = sp.edit().putString("url", u).putString("header", header.trim().ifEmpty { "Authorization" }).putString("scheme", scheme.trim())
        if (tpl != null) e.putString("template", tpl)
        if (k != null) { val (ct, iv) = sealer.seal(k.toByteArray(Charsets.UTF_8)); e.putString("key_ct", ct).putString("key_iv", iv) }
        e.commit()
    }
    fun clear() { sp.edit().clear().commit(); sealer.forget() }
    /** For Settings: never the key itself. */
    fun summary(): String = if (!configured) "not set" else runCatching { "set · " + (Connectors.preset(connectorId)?.label ?: "Custom") + " · " + java.net.URI(url).host }.getOrDefault("set")
}
