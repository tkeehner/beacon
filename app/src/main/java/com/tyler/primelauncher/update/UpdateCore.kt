package com.tyler.primelauncher.update

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 0.1.16 remote updater, pure JVM part (unit-tested without Android).
 *
 * Transport: Emit's existing relay (msms-relay, on a server you run), used exactly as it is. The relay only splices a "phone" and a
 * "pc" socket that present the same 64-hex room id and forwards length-prefixed frames; it never sees plaintext.
 * Beacon uses its OWN room id and keys (labels "beacon-*"), so it can never be confused with Emit's link.
 *
 * Security does not rest on the transport: the phone installs an APK only if it is com.tyler.primelauncher, signed by
 * exactly the same certificate(s) as the installed app, has a higher versionCode, and the owner taps Install on Android's
 * own prompt. The link key (from pairing) only decides who may hand the phone bytes.
 */
class UpdateError(val code: String, msg: String) : Exception(msg)

data class ApkInfo(val packageName: String, val versionCode: Long, val versionName: String, val signers: Set<String>)

object UpdateRules {
    const val PACKAGE = "com.tyler.primelauncher"
    const val MAX_APK = 200L * 1024 * 1024
    /** Throws [UpdateError] unless [apk] is a newer build of this exact app signed with the same key(s). */
    fun check(apk: ApkInfo?, installedPackage: String, installedVersion: Long, installedSigners: Set<String>) {
        if (apk == null) throw UpdateError("bad_apk", "not a readable APK")
        if (installedPackage != PACKAGE || apk.packageName != installedPackage) throw UpdateError("wrong_package", "APK is ${apk.packageName}, not Beacon")
        if (installedSigners.isEmpty() || apk.signers.isEmpty() || apk.signers != installedSigners) throw UpdateError("wrong_signer", "APK is not signed with Beacon's key")
        if (apk.versionCode <= installedVersion) throw UpdateError("not_newer", "APK version ${apk.versionCode} is not newer than installed $installedVersion")
    }
}

/** Chunked, resumable APK receiver (same state machine as Emit's). [verify] reads the finished file and runs [UpdateRules]. */
class UpdateReceiver(private val dir: File, private val verify: (File) -> ApkInfo) {
    private var cur: Pair<String, Long>? = null
    @Volatile var ready: Ready? = null; private set
    data class Ready(val info: ApkInfo, val sha256: String, val size: Long, val file: File)
    init { dir.mkdirs() }
    private fun part(sha: String) = File(dir, "$sha.part")
    @Synchronized fun begin(sha256: String, size: Long): Long {
        val sha = sha256.lowercase()
        if (sha.length != 64 || sha.any { it !in "0123456789abcdef" }) throw UpdateError("bad_args", "bad sha256")
        if (size <= 0 || size > UpdateRules.MAX_APK) throw UpdateError("bad_args", "bad size")
        // Only one transfer at a time: drop any other partial file (and an older verified one).
        dir.listFiles()?.filter { it.name.endsWith(".part") && it.name != "$sha.part" }?.forEach { it.delete() }
        cur = sha to size; val f = part(sha)
        var have = if (f.exists()) f.length() else 0L
        if (have > size) { f.delete(); have = 0 }
        return have
    }
    @Synchronized fun chunk(sha256: String, offset: Long, data: ByteArray): Long {
        val c = cur ?: throw UpdateError("bad_state", "begin first"); if (c.first != sha256.lowercase()) throw UpdateError("bad_state", "begin first")
        val f = part(c.first); val have = if (f.exists()) f.length() else 0L
        if (offset != have) return have
        if (have + data.size > c.second || data.size > 2 * CHUNK) throw UpdateError("bad_args", "chunk exceeds declared size")
        java.io.FileOutputStream(f, true).use { it.write(data) }
        return have + data.size
    }
    @Synchronized fun finish(sha256: String): Ready {
        val c = cur ?: throw UpdateError("bad_state", "begin first"); if (c.first != sha256.lowercase()) throw UpdateError("bad_state", "begin first")
        val f = part(c.first)
        if (!f.exists() || f.length() != c.second) throw UpdateError("incomplete", "transfer incomplete")
        if (sha256Hex(f) != c.first) { f.delete(); cur = null; throw UpdateError("bad_hash", "sha256 mismatch, discarded") }
        val info = try { verify(f) } catch (e: UpdateError) { f.delete(); cur = null; throw e }
        val apk = File(dir, "ready.apk"); apk.delete()
        if (!f.renameTo(apk)) { f.delete(); throw UpdateError("io", "could not store update") }
        cur = null
        return Ready(info, c.first, c.second, apk).also { ready = it }
    }
    fun restore(r: Ready?) { ready = r }
    fun clear() { ready = null; cur = null; dir.listFiles()?.forEach { it.delete() } }
    companion object {
        const val CHUNK = 1024 * 1024
        fun sha256Hex(f: File): String { val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) } }
    }
}

/** What pairing gives the phone: where the relay is, how to prove it (cert pin + token), and the Beacon link key. */
data class PairCfg(val host: String, val port: Int, val pin: String, val token: String, val key: ByteArray, val pc: String) {
    fun toJson(): JSONObject = JSONObject().put("h", host).put("p", port).put("f", pin).put("t", token).put("k", B64.enc(key)).put("pc", pc)
    override fun equals(other: Any?) = other is PairCfg && other.host == host && other.port == port && other.pin == pin && other.token == token && other.key.contentEquals(key) && other.pc == pc
    override fun hashCode() = host.hashCode() * 31 + port
    companion object {
        fun fromJson(o: JSONObject): PairCfg {
            val c = PairCfg(o.getString("h"), o.optInt("p", 443), o.getString("f").replace(":", "").lowercase(), o.getString("t"), B64.dec(o.getString("k")), o.optString("pc", "PC").take(40))
            if (c.host.isBlank() || c.host.any { it.isWhitespace() }) throw UpdateError("bad_pair", "bad relay host")
            if (c.port !in 1..65535) throw UpdateError("bad_pair", "bad relay port")
            if (c.pin.length != 64 || c.pin.any { it !in "0123456789abcdef" }) throw UpdateError("bad_pair", "bad relay pin")
            if (c.token.length < 16 || c.token.any { it.isWhitespace() }) throw UpdateError("bad_pair", "bad relay token")
            if (c.key.size != 32) throw UpdateError("bad_pair", "bad link key")
            return c
        }
    }
}

object B64 {
    fun enc(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun dec(s: String): ByteArray = Base64.getDecoder().decode(s.trim())
    fun dec64url(s: String): ByteArray = Base64.getUrlDecoder().decode(s.trim().trimEnd('='))
}

object Pairing {
    const val PREFIX = "BCN1."
    /** Opens the PC's pairing code: "BCN1." + base64url( RSA-OAEP(aesKey) [256] || iv [12] || AES-GCM(json) ). Only this phone's
     *  Keystore RSA key ([unwrap]) can open it, so the code may travel through Phone Link or adb as ciphertext only. */
    fun open(code: String, unwrap: (ByteArray) -> ByteArray): PairCfg {
        val s = code.trim().replace("\\s".toRegex(), "")
        if (!s.startsWith(PREFIX)) throw UpdateError("bad_pair", "not a Beacon pairing code")
        val raw = try { B64.dec64url(s.removePrefix(PREFIX)) } catch (e: IllegalArgumentException) { throw UpdateError("bad_pair", "pairing code is damaged") }
        if (raw.size < 256 + 12 + 16 + 2 || raw.size > 4096) throw UpdateError("bad_pair", "pairing code is damaged")
        val aes = try { unwrap(raw.copyOfRange(0, 256)) } catch (e: Exception) { throw UpdateError("bad_pair", "pairing code is for a different phone key") }
        val json = try { Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, raw.copyOfRange(256, 268))); doFinal(raw.copyOfRange(268, raw.size)) } }
            catch (e: Exception) { throw UpdateError("bad_pair", "pairing code is damaged") }
        return PairCfg.fromJson(JSONObject(String(json, Charsets.UTF_8)))
    }
    /** Short fingerprint of the phone key, shown on both sides so the owner can compare. */
    fun fingerprint(pubDer: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(pubDer).take(6).joinToString(":") { "%02X".format(it) }
}

/** Link crypto (must match tools/beacon-pc/beacon_update.py byte for byte). */
object LinkCrypto {
    const val MAGIC = "BUP1"
    fun room(key: ByteArray): String = hmac(key, "beacon-room-v1".toByteArray()).joinToString("") { "%02x".format(it) }
    fun hmac(k: ByteArray, d: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(k, "HmacSHA256")); doFinal(d) }
    /** RFC 5869 HKDF-SHA256, one 32-byte block. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String): ByteArray { val prk = hmac(salt, ikm); return hmac(prk, info.toByteArray() + byteArrayOf(1)) }
    fun keys(k: ByteArray, nc: ByteArray, np: ByteArray): Pair<ByteArray, ByteArray> { val salt = nc + np
        return hkdf(k, salt, "beacon-upd-v1 pc>ph") to hkdf(k, salt, "beacon-upd-v1 ph>pc") }
    class Dir(private val key: ByteArray) {
        private var ctr = 0L
        private fun nonce(): ByteArray { val n = ByteArray(12); var c = ctr++; for (i in 11 downTo 4) { n[i] = (c and 0xff).toByte(); c = c ushr 8 }; return n }
        fun seal(p: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce())); doFinal(p) }
        fun open(c: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce())); doFinal(c) }
    }
}

object Frames {
    const val MAX = 8 * 1024 * 1024
    fun read(i: DataInputStream): ByteArray { val n = i.readInt(); if (n < 0 || n > MAX) throw UpdateError("proto", "frame too large"); return ByteArray(n).also { i.readFully(it) } }
    fun write(o: DataOutputStream, b: ByteArray) { o.writeInt(b.size); o.write(b); o.flush() }
}

/** One phone-side session after the relay said "OK paired"/GO. PC speaks first. Returns when the PC says bye or the link drops. */
class PhoneSession(private val key: ByteArray, private val handler: (String, JSONObject) -> JSONObject, private val rnd: SecureRandom = SecureRandom()) {
    fun run(input: InputStream, output: OutputStream) {
        val i = DataInputStream(input); val o = DataOutputStream(output)
        val hello = Frames.read(i)
        if (hello.size != 20 || String(hello, 0, 4, Charsets.US_ASCII) != LinkCrypto.MAGIC) throw UpdateError("proto", "bad hello")
        val nc = hello.copyOfRange(4, 20); val np = ByteArray(16).also { rnd.nextBytes(it) }
        Frames.write(o, LinkCrypto.MAGIC.toByteArray(Charsets.US_ASCII) + np)
        val (kIn, kOut) = LinkCrypto.keys(key, nc, np); val rx = LinkCrypto.Dir(kIn); val tx = LinkCrypto.Dir(kOut)
        while (true) {
            val req = try { JSONObject(String(rx.open(Frames.read(i)), Charsets.UTF_8)) } catch (e: javax.crypto.AEADBadTagException) { throw UpdateError("auth", "link key mismatch") }
            val id = req.optLong("id"); val m = req.optString("m")
            val resp = JSONObject().put("id", id)
            try { resp.put("ok", handler(m, req.optJSONObject("p") ?: JSONObject())) }
            catch (e: UpdateError) { resp.put("err", JSONObject().put("code", e.code).put("msg", e.message ?: e.code)) }
            catch (e: Exception) { resp.put("err", JSONObject().put("code", "internal").put("msg", e.javaClass.simpleName)) }
            Frames.write(o, tx.seal(resp.toString().toByteArray(Charsets.UTF_8)))
            if (m == "bye") return
        }
    }
}

/** RPC methods the PC may call. Nothing here installs; [onReady] only announces a verified update for the owner to tap. */
class UpdateRpc(private val rx: UpdateReceiver, private val installed: () -> ApkInfo, private val onReady: (UpdateReceiver.Ready) -> Unit) {
    fun handle(m: String, p: JSONObject): JSONObject = when (m) {
        "info" -> installed().let { JSONObject().put("package", it.packageName).put("code", it.versionCode).put("name", it.versionName) }
        "begin" -> JSONObject().put("offset", rx.begin(p.getString("sha256"), p.getLong("size")))
        "chunk" -> JSONObject().put("offset", rx.chunk(p.getString("sha256"), p.getLong("offset"), B64.dec(p.getString("d"))))
        "finish" -> rx.finish(p.getString("sha256")).let { r -> onReady(r)
            JSONObject().put("ready", true).put("code", r.info.versionCode).put("name", r.info.versionName).put("note", "waiting for the owner to tap Install on the phone") }
        "bye" -> JSONObject()
        else -> throw UpdateError("unknown_method", "unknown method")
    }
}
