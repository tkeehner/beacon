package com.tyler.primelauncher.update

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Phone side of msms-relay's existing line protocol (unchanged server):
 *   -> "MSMS-RELAY/1 <token> <room> phone\n";  <- "OK wait\n" (PING/PONG keeps it open) ... "OK paired\n";  -> "GO\n"; then frames.
 * TLS 1.3, trusting exactly one certificate by SHA-256 pin (no CA, no hostname), like Emit.
 */
class RelayClient(private val cfg: PairCfg, private val plainForTests: Boolean = false) {
    var pingEveryMs = 240_000L
    @Volatile private var sock: Socket? = null

    fun open(timeoutMs: Int = 15_000): Socket {
        val raw = Socket(); raw.connect(InetSocketAddress(cfg.host, cfg.port), timeoutMs); raw.tcpNoDelay = true; raw.keepAlive = true
        if (plainForTests) return raw.also { sock = it }
        val ctx = SSLContext.getInstance("TLSv1.3")
        ctx.init(null, arrayOf(PinTrust(cfg.pin)), null)
        val s = ctx.socketFactory.createSocket(raw, cfg.host, cfg.port, true) as SSLSocket
        s.enabledProtocols = arrayOf("TLSv1.3"); s.soTimeout = timeoutMs; s.startHandshake()
        val leaf = s.session.peerCertificates.firstOrNull() as? X509Certificate ?: run { s.close(); throw UpdateError("relay_pin", "no relay certificate") }
        if (!MessageDigest.isEqual(sha256(leaf.encoded).toByteArray(), cfg.pin.toByteArray())) { s.close(); throw UpdateError("relay_pin", "relay certificate pin mismatch") }
        return s.also { sock = it }
    }

    /** Joins the Beacon room and blocks until the PC arrives (returns true) or the wait ends (false). */
    fun waitForPc(s: Socket, stop: () -> Boolean): Boolean {
        val out = s.getOutputStream(); val inp = s.getInputStream()
        out.write("MSMS-RELAY/1 ${cfg.token} ${LinkCrypto.room(cfg.key)} phone\n".toByteArray()); out.flush()
        s.soTimeout = 5_000; var lastPing = System.currentTimeMillis()
        while (!stop()) {
            val l = try { line(inp) } catch (e: java.net.SocketTimeoutException) {
                if (System.currentTimeMillis() - lastPing >= pingEveryMs) { out.write("PING\n".toByteArray()); out.flush(); lastPing = System.currentTimeMillis() }
                continue
            }
            when (l) {
                "OK wait", "PONG" -> continue
                "OK paired" -> { out.write("GO\n".toByteArray()); out.flush(); s.soTimeout = 120_000; return true }
                "ERR auth" -> throw UpdateError("relay_auth", "relay token rejected")
                else -> return false // ERR idle / full / nopeer etc: reconnect
            }
        }
        return false
    }

    fun close() { runCatching { sock?.close() } }

    private val buf = StringBuilder()
    private fun line(i: InputStream): String {
        buf.setLength(0)
        while (true) { val c = i.read(); if (c < 0) throw java.io.EOFException("relay closed"); if (c == '\n'.code) return buf.toString(); buf.append(c.toChar()); if (buf.length > 64) throw UpdateError("proto", "relay protocol error") }
    }

    private class PinTrust(val pin: String) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw java.security.cert.CertificateException("no client certs")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("empty chain")
            if (sha256(leaf.encoded) != pin) throw java.security.cert.CertificateException("relay certificate pin mismatch")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    companion object {
        fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
        fun streams(s: Socket): Pair<InputStream, OutputStream> = s.getInputStream() to s.getOutputStream()
    }
}
