package com.tyler.primelauncher.phonelink.core

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One authenticated agent session. Pure I/O + crypto; Android wraps it with TLS sockets and a ToolRunner.
 *
 * Commands require end-to-end auth: e2e HMAC with Ke = HKDF(mcp_token). The phone holds the token;
 * The relay only has it transiently in a live MCP request. Agent_key alone is not enough to mint commands.
 */
class Session(
    private val key: ByteArray,
    private val runner: ToolRunner,
    private val tokenProvider: () -> String?,
    private val audit: (Json.Obj) -> Unit = {},
    private val stopRequested: () -> Boolean = { false },
    private val onCommand: (() -> Unit)? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    interface ToolRunner {
        fun run(tool: String, args: Json.Obj): Json.Value
        fun screenTexts(): List<String>
    }
    class HardStop(msg: String) : Exception(msg)
    class AuthFailed(msg: String) : Exception(msg)
    interface UrlBlockAware { fun urlBlock(): List<String> }

    private lateinit var signer: Signer
    private lateinit var verifier: Verifier
    private val closed = AtomicBoolean(false)

    fun handshake(ins: InputStream, out: OutputStream) {
        out.write(Wire.MAGIC); out.flush()
        val cn = Wire.nonce()
        Wire.writeFrame(out, Json.of("type" to "hello", "v" to Wire.V.toLong(), "cn" to cn).encode().toByteArray())
        val ch = Json.parse(String(Wire.readFrame(ins, 1024), Charsets.UTF_8)) as? Json.Obj
            ?: throw AuthFailed("bad challenge")
        if (ch.stringOrNull("type") != "challenge") throw AuthFailed("bad challenge")
        val sn = Wire.checkHex(ch.stringOrNull("sn"))
        Wire.writeFrame(out, Json.of("type" to "auth", "mac" to Wire.authMac(key, sn, cn)).encode().toByteArray())
        val wel = Json.parse(String(Wire.readFrame(ins, 1024), Charsets.UTF_8)) as? Json.Obj
            ?: throw AuthFailed("server rejected the agent key")
        if (wel.stringOrNull("type") != "welcome") throw AuthFailed("server rejected the agent key")
        if (!Wire.constEq(wel.stringOrNull("mac") ?: "", Wire.welcomeMac(key, sn, cn)))
            throw AuthFailed("server failed mutual authentication")
        val ks = Wire.sessionKey(key, sn, cn)
        signer = Signer(ks, clock); verifier = Verifier(ks, Wire.SKEW_MS, clock)
    }

    fun serve(ins: InputStream, out: OutputStream) {
        while (!closed.get() && !stopRequested()) {
            val payload = Wire.readFrame(ins, Wire.MAX_TO_AGENT)
            if (stopRequested()) break
            Wire.writeFrame(out, handle(payload))
        }
    }

    fun handle(payload: ByteArray): ByteArray {
        val d = try { verifier.verify(payload) }
                catch (e: Wire.WireError) { audit(Json.of("event" to "frame-rejected", "why" to e.message)); throw e }
        val typ = d.stringOrNull("type")
        val id = d.stringOrNull("id") ?: ""
        if (typ == "ping") return signer.sign(Json.of("type" to "res", "id" to id, "ok" to true, "result" to Json.of("pong" to true)).encode())
        if (typ != "cmd") throw Wire.WireError("unexpected frame type")

        // End-to-end: phone is the token authority.
        val token = tokenProvider()
        if (token.isNullOrEmpty()) {
            audit(Json.of("event" to "e2e-rejected", "why" to "no-token"))
            return signer.sign(Json.of("type" to "res", "id" to id, "ok" to false, "refused" to true, "auth_fail" to true,
                "error" to "no MCP token on phone — generate one in Settings").encode())
        }
        try { Wire.verifyCmdE2e(d, Wire.e2eKey(token)) }
        catch (e: Wire.WireError) {
            audit(Json.of("event" to "e2e-rejected", "why" to e.message))
            return signer.sign(Json.of("type" to "res", "id" to id, "ok" to false, "refused" to true, "auth_fail" to true,
                "error" to "bad or missing command auth (token)").encode())
        }

        val tool = d.stringOrNull("tool") ?: ""
        val args = d.objOrNull("args") ?: Json.of()
        val t0 = clock()
        val (ok, refused, resultOrErr) = try {
            if (stopRequested()) Triple(false, true, Json.Str("Phone Link is paused (kill switch)."))
            else {
                val a = Policy.validate(tool, args, internalOk = false, urlBlock = runnerUrlBlock())
                if (tool in setOf("tap", "long_press", "swipe", "type_text")) {
                    val texts = runner.screenTexts()
                    if (texts.isEmpty()) throw HardStop("can't read the screen to check for reset/root screens, so input is refused")
                    Policy.hardStopHit(texts)?.let { throw HardStop("hard refusal: this screen mentions '$it' (reset/root/recovery)") }
                }
                Triple(true, false, runner.run(tool, a))
            }
        } catch (e: Policy.PolicyError) { Triple(false, true, Json.Str(e.message ?: "refused")) }
          catch (e: HardStop) { Triple(false, true, Json.Str(e.message ?: "refused")) }
          catch (e: Exception) { Triple(false, false, Json.Str("${e.javaClass.simpleName}: ${(e.message ?: "").take(200)}")) }

        onCommand?.invoke()
        audit(Json.of("event" to "tool", "tool" to tool.take(40),
            "args" to Policy.auditSummary(tool, args), "ok" to ok, "refused" to refused, "ms" to (clock() - t0)))
        val body = if (ok) Json.of("type" to "res", "id" to id, "ok" to true, "result" to resultOrErr).encode()
                   else Json.of("type" to "res", "id" to id, "ok" to false, "refused" to refused, "error" to resultOrErr).encode()
        return signer.sign(body)
    }

    fun close() { closed.set(true) }
    private fun runnerUrlBlock(): List<String> = (runner as? UrlBlockAware)?.urlBlock() ?: emptyList()
}
