package com.tyler.primelauncher.phonelink.android

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Config + secrets for Phone Link.
 *
 * - agent_key: from the relay handoff (pairs the phone↔relay channel). Sealed with Android Keystore AES-GCM.
 * - mcp_token: GENERATED ON THE PHONE. The relay stores no copy and no hash. Prime's connector gets it when
 *   the owner pastes it (Settings → show once). Commands are e2e-authenticated with Ke = HKDF(token).
 *   Rotating invalidates the old token immediately.
 */
class LinkStore(ctx: Context) {
    private val app = ctx.applicationContext
    private val sp = app.getSharedPreferences("phone_link", Context.MODE_PRIVATE)
    companion object {
        private const val ALIAS = "phone_link_seal"
        private const val PREF_AGENT_CIPHER = "agent_key_cipher"
        private const val PREF_AGENT_IV = "agent_key_iv"
        private const val PREF_TOKEN_CIPHER = "mcp_token_cipher"
        private const val PREF_TOKEN_IV = "mcp_token_iv"
        private const val PREF_TOKEN_HASH = "mcp_token_sha256" // for constant-time compare without decrypting every time? Actually we need Ke=HKDF(token), so we must decrypt. Hash alone is insufficient for e2e verify. Store sealed token only.
    }

    var host: String get() = sp.getString("host", "") ?: ""; set(v) = sp.edit().putString("host", v).apply()
    var port: Int get() = sp.getInt("port", 443); set(v) = sp.edit().putInt("port", v).apply()
    var fingerprint: String get() = (sp.getString("fingerprint", "") ?: "").replace(":", "").lowercase()
        set(v) = sp.edit().putString("fingerprint", v.replace(":", "").lowercase()).apply()
    var enabled: Boolean get() = sp.getBoolean("enabled", false); set(v) = sp.edit().putBoolean("enabled", v).apply()
    var urlBlocklist: List<String>
        get() = try { JSONArray(sp.getString("url_blocklist", "[]")).let { (0 until it.length()).map { i -> it.getString(i) } } }
                catch (_: Exception) { emptyList() }
        set(v) = sp.edit().putString("url_blocklist", JSONArray(v).toString()).apply()
    /** Idle gap (ms) before the next command counts as a new MCP session for the alert notification. */
    var sessionIdleMs: Long get() = sp.getLong("session_idle_ms", 5 * 60_000L); set(v) = sp.edit().putLong("session_idle_ms", v).apply()

    val configured: Boolean get() = host.isNotEmpty() && fingerprint.length == 64 && sp.contains(PREF_AGENT_CIPHER)
    val hasToken: Boolean get() = sp.contains(PREF_TOKEN_CIPHER)

    /** Import agent channel config from the relay handoff. Does NOT set the MCP token (phone generates that). */
    fun importConfig(json: String) {
        val o = JSONObject(json)
        val keyHex = o.getString("agent_key").lowercase()
        require(keyHex.length == 64 && keyHex.all { it in "0123456789abcdef" }) { "agent_key must be 32 bytes hex" }
        val fp = o.getString("fingerprint").replace(":", "").lowercase()
        require(fp.length == 64) { "fingerprint must be a SHA-256 hex digest" }
        storeSecret(PREF_AGENT_CIPHER, PREF_AGENT_IV, keyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        host = o.getString("host"); port = o.optInt("port", 443); fingerprint = fp
        if (o.has("url_blocklist")) urlBlocklist = o.getJSONArray("url_blocklist").let { (0 until it.length()).map { i -> it.getString(i) } }
        enabled = true
        // If no MCP token yet, generate one — the owner must paste it into your agent's MCP connector.
        if (!hasToken) rotateToken()
    }

    fun clear() {
        sp.edit().clear().apply()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }

    fun loadAgentKey(): ByteArray = loadSecret(PREF_AGENT_CIPHER, PREF_AGENT_IV)
    fun loadToken(): String? =
        if (!hasToken) null else String(loadSecret(PREF_TOKEN_CIPHER, PREF_TOKEN_IV), Charsets.UTF_8)

    /**
     * Generate a new MCP bearer token, seal it, invalidate the previous one immediately.
     * @return the plaintext token ONCE for the owner to paste into your agent's MCP connector settings (never log/chat it).
     */
    fun rotateToken(): String {
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        // url-safe-ish base64 without padding, 43 chars (>= 32 bytes entropy
        val token = Base64.encodeToString(raw, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
        require(token.length >= 32)
        storeSecret(PREF_TOKEN_CIPHER, PREF_TOKEN_IV, token.toByteArray(Charsets.UTF_8))
        raw.fill(0)
        audit(JSONObject().put("event", "token-rotated"))
        return token
    }

    private fun storeSecret(cipherKey: String, ivKey: String, raw: ByteArray) {
        val key = masterKey(create = true)
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key)
        val ct = c.doFinal(raw)
        sp.edit().putString(cipherKey, Base64.encodeToString(ct, Base64.NO_WRAP))
            .putString(ivKey, Base64.encodeToString(c.iv, Base64.NO_WRAP)).apply()
        raw.fill(0)
    }
    private fun loadSecret(cipherKey: String, ivKey: String): ByteArray {
        val cipherB64 = sp.getString(cipherKey, null) ?: error("not configured")
        val ivB64 = sp.getString(ivKey, null) ?: error("not configured")
        val key = masterKey(create = false)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
        return c.doFinal(Base64.decode(cipherB64, Base64.NO_WRAP))
    }
    private fun masterKey(create: Boolean): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null)
        if (ks.containsAlias(ALIAS)) return (ks.getEntry(ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        require(create) { "keystore key missing" }
        val gen = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).setUserAuthenticationRequired(false).build()
        gen.init(spec); return gen.generateKey()
    }

    fun audit(entry: JSONObject) {
        entry.put("ts", System.currentTimeMillis())
        val lines = (sp.getString("audit", "") ?: "").lineSequence().filter { it.isNotBlank() }.toList()
        sp.edit().putString("audit", (lines + entry.toString()).takeLast(2000).joinToString("\n")).apply()
    }
    fun auditLines(): List<String> = (sp.getString("audit", "") ?: "").lineSequence().filter { it.isNotBlank() }.toList().asReversed()
    fun clearAudit() = sp.edit().remove("audit").apply()

    fun fingerprintMatches(der: ByteArray): Boolean {
        val got = MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }
        return MessageDigest.isEqual(got.toByteArray(), fingerprint.toByteArray())
    }
}
