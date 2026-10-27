package com.tyler.primelauncher.phonelink.core

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Phone Link wire format v2 — Kotlin port of phonelink_wire.py (byte-compatible).
 *
 * Commands carry end-to-end auth the relay server cannot mint from disk alone:
 *   e2e = HMAC-SHA256(Ke, "cmd|v2|{id}|{tool}|{canon_args}|{seq}|{ts}|{nonce}")
 *   Ke  = HKDF-SHA256(ikm=mcp_token, salt=zeros(32), info="phonelink-cmd-v2", len=32)
 * The MCP bearer lives only on the phone (and in Prime's connector). The relay stores no token/hash.
 * Remaining risk: live compromise of the relay process while Prime sends can capture the bearer until rotation.
 */
object Wire {
    const val V = 2
    val MAGIC = "PHONELINK-AGENT/2\n".toByteArray(Charsets.US_ASCII)
    const val MAX_TO_AGENT = 64 * 1024
    const val MAX_FROM_AGENT = 12 * 1024 * 1024
    const val SKEW_MS = 30_000L
    val E2E_INFO = "phonelink-cmd-v2".toByteArray(Charsets.US_ASCII)
    private val rng = SecureRandom()

    class WireError(msg: String) : Exception(msg)

    fun nonce(): String {
        val b = ByteArray(16); rng.nextBytes(b); return hex(b)
    }
    fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) { val v = x.toInt() and 0xff; sb.append("0123456789abcdef"[v ushr 4]); sb.append("0123456789abcdef"[v and 0xf]) }
        return sb.toString()
    }
    fun mac(key: ByteArray, msg: ByteArray): String {
        val m = Mac.getInstance("HmacSHA256"); m.init(SecretKeySpec(key, "HmacSHA256")); return hex(m.doFinal(msg))
    }
    fun authMac(k: ByteArray, sn: String, cn: String) = mac(k, "auth|$V|$sn|$cn".toByteArray(Charsets.US_ASCII))
    fun welcomeMac(k: ByteArray, sn: String, cn: String) = mac(k, "welcome|$V|$cn|$sn".toByteArray(Charsets.US_ASCII))
    fun sessionKey(k: ByteArray, sn: String, cn: String): ByteArray {
        val m = Mac.getInstance("HmacSHA256"); m.init(SecretKeySpec(k, "HmacSHA256"))
        return m.doFinal("session|$V|$sn|$cn".toByteArray(Charsets.US_ASCII))
    }

    /** HKDF-SHA256 (RFC 5869). Default salt = 32 zero bytes. */
    fun hkdfSha256(ikm: ByteArray, info: ByteArray = E2E_INFO, length: Int = 32, salt: ByteArray? = null): ByteArray {
        val s = salt ?: ByteArray(32)
        val extract = Mac.getInstance("HmacSHA256"); extract.init(SecretKeySpec(s, "HmacSHA256"))
        val prk = extract.doFinal(ikm)
        val out = ByteArray(length); var t = ByteArray(0); var offset = 0; var i = 1
        while (offset < length) {
            val expand = Mac.getInstance("HmacSHA256"); expand.init(SecretKeySpec(prk, "HmacSHA256"))
            expand.update(t); expand.update(info); expand.update(byteArrayOf(i.toByte()))
            t = expand.doFinal()
            val n = minOf(t.size, length - offset); System.arraycopy(t, 0, out, offset, n); offset += n; i++
        }
        return out
    }

    fun e2eKey(token: ByteArray): ByteArray {
        if (token.size < 32) throw WireError("token too short")
        return hkdfSha256(token, E2E_INFO, 32)
    }
    fun e2eKey(token: String): ByteArray = e2eKey(token.toByteArray(Charsets.UTF_8))

    /** canon_args = JSON with sorted keys, separators (",", ":"), ensure_ascii (our Json encoder is ASCII-safe for args). */
    fun canonArgs(args: Json.Obj?): String {
        val m = args?.map ?: linkedMapOf()
        val sorted = m.entries.sortedBy { it.key }
        return sorted.joinToString(",", "{", "}") { Json.Str(it.key).encode() + ":" + it.value.encode() }
    }

    fun e2eMac(ke: ByteArray, id: String, tool: String, args: Json.Obj?, seq: Long, ts: Long, nonce: String): String {
        val msg = "cmd|v2|$id|$tool|${canonArgs(args)}|$seq|$ts|$nonce".toByteArray(Charsets.UTF_8)
        return mac(ke, msg)
    }

    fun verifyCmdE2e(d: Json.Obj, ke: ByteArray) {
        if (d.stringOrNull("type") != "cmd") return
        val got = d.stringOrNull("e2e") ?: throw WireError("missing e2e")
        if (got.length != 64) throw WireError("missing e2e")
        val expect = e2eMac(ke, d.stringOrNull("id") ?: "", d.stringOrNull("tool") ?: "",
            d.objOrNull("args"), d.longOrNull("seq")!!, d.longOrNull("ts")!!, d.stringOrNull("nonce")!!)
        if (!constEq(got, expect)) throw WireError("bad e2e")
    }

    fun constEq(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))

    fun checkHex(v: String?, n: Int = 32): String {
        if (v == null || v.length != n || v.any { it !in "0123456789abcdef" }) throw WireError("bad nonce")
        return v
    }

    fun writeFrame(out: OutputStream, payload: ByteArray) {
        val n = payload.size
        out.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
        out.write(payload); out.flush()
    }
    fun readFrame(ins: InputStream, cap: Int): ByteArray {
        val hdr = readExactly(ins, 4)
        val n = ((hdr[0].toInt() and 0xff) shl 24) or ((hdr[1].toInt() and 0xff) shl 16) or
                ((hdr[2].toInt() and 0xff) shl 8) or (hdr[3].toInt() and 0xff)
        if (n < 0 || n > cap) throw WireError("frame too large")
        return readExactly(ins, n)
    }
    private fun readExactly(ins: InputStream, n: Int): ByteArray {
        val b = ByteArray(n); var off = 0
        while (off < n) { val r = ins.read(b, off, n - off); if (r < 0) throw EOFException("stream closed"); off += r }
        return b
    }
}

class Signer(private val ks: ByteArray, private val clock: () -> Long = { System.currentTimeMillis() }) {
    private var seq = 0L
    fun sign(body: String): ByteArray {
        seq += 1
        val full = Json.addEnvelope(body, seq, clock(), Wire.nonce()).toByteArray(Charsets.UTF_8)
        return Wire.mac(ks, full).toByteArray(Charsets.US_ASCII) + full
    }
}

class Verifier(private val ks: ByteArray, private val skewMs: Long = Wire.SKEW_MS,
               private val clock: () -> Long = { System.currentTimeMillis() }) {
    private var lastSeq = 0L
    private val seen = HashMap<String, Long>()
    fun verify(payload: ByteArray): Json.Obj {
        if (payload.size < 66) throw Wire.WireError("unsigned frame")
        val tag = String(payload, 0, 64, Charsets.US_ASCII)
        val body = payload.copyOfRange(64, payload.size)
        if (!Wire.constEq(tag, Wire.mac(ks, body))) throw Wire.WireError("bad mac")
        val d = try { Json.parse(String(body, Charsets.UTF_8)) as? Json.Obj ?: throw Wire.WireError("bad frame") }
                catch (e: Json.JsonError) { throw Wire.WireError("bad json") }
        val seq = d.longOrNull("seq") ?: throw Wire.WireError("replayed or out-of-order frame")
        if (d.raw("seq") !is Long || seq <= lastSeq) throw Wire.WireError("replayed or out-of-order frame")
        val ts = d.raw("ts")
        if (ts !is Long || Math.abs(clock() - ts) > skewMs) throw Wire.WireError("stale frame")
        val nn = Wire.checkHex(d.stringOrNull("nonce"))
        val now = clock()
        if (seen.containsKey(nn)) throw Wire.WireError("replayed nonce")
        seen.entries.removeAll { now - it.value >= 2 * skewMs }
        seen[nn] = now; lastSeq = seq
        return d
    }
}
