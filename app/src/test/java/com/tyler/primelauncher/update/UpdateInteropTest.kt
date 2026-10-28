package com.tyler.primelauncher.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher

/**
 * End to end on a dev machine: the REAL unmodified msms-relay (plain TCP here, TLS in production), the REAL PC tool
 * (tools/beacon-pc/beacon_update.py) and the Kotlin phone side. Skipped if python3/cryptography or the relay source (BEACON_RELAY_DIR) aren't present.
 */
class UpdateInteropTest {
    private val tool = File("../tools/beacon-pc/beacon_update.py").absoluteFile
    private val relayDir = System.getenv("BEACON_RELAY_DIR")?.let(::File)
    private fun py(vararg a: String, timeoutS: Long = 120): Pair<Int, String> {
        val p = ProcessBuilder(listOf("python3") + a).redirectErrorStream(false).start()
        val out = p.inputStream.bufferedReader().readText(); p.waitFor(timeoutS, TimeUnit.SECONDS); return p.exitValue() to out
    }
    private fun ok(): Boolean = tool.exists() && relayDir != null && File(relayDir, "msms_relay.py").exists() &&
        runCatching { py("-c", "import cryptography").first == 0 }.getOrDefault(false)

    @Test fun pushThroughRealRelayReachesPhoneVerified() = scenario(reject = null)
    @Test fun phoneRejectionIsReportedToPc() = scenario(reject = "wrong_signer")

    private fun scenario(reject: String?) {
        assumeTrue("python3 + cryptography + msms-relay source needed", ok())
        val dir = Files.createTempDirectory("bup").toFile()
        val port = ServerSocket(0).use { it.localPort }
        val token = "relay-test-token-0123456789"
        val relay = ProcessBuilder("python3", "-c",
            "import asyncio,sys; sys.path.insert(0, sys.argv[1]); import msms_relay as m; c=m.Cfg(); c.token=sys.argv[2].encode(); asyncio.run(m.serve(c,'127.0.0.1',int(sys.argv[3])))",
            relayDir!!.path, token, port.toString()).redirectErrorStream(true).redirectOutput(File(dir, "relay.log")).start()
        try {
            Thread.sleep(800)
            File(dir, "identity.json").writeText(JSONObject().put("relay", JSONObject().put("host", "127.0.0.1").put("port", port).put("fingerprint", "cd".repeat(32)).put("token", token)).toString())
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            File(dir, "phone.pub").writeText(java.util.Base64.getEncoder().encodeToString(kp.public.encoded))
            val home = File(dir, "home").path
            val (pc, pout) = py(tool.path, "--home", home, "--msms-identity", File(dir, "identity.json").path, "pair", "@" + File(dir, "phone.pub").path, "--plain", "--out", File(dir, "code.txt").path, "--name", "TestPC")
            assertEquals(pout, 0, pc)
            val cfg = Pairing.open(File(dir, "code.txt").readText()) { UpdateVerifyTest.oaep(Cipher.DECRYPT_MODE, kp.private).doFinal(it) }
            assertEquals("TestPC", cfg.pc); assertEquals(token, cfg.token); assertEquals(port, cfg.port)

            val apk = File(dir, "fake.apk").apply { writeBytes(byteArrayOf(0x50, 0x4b, 3, 4) + ByteArray(3_300_000).also { SecureRandom().nextBytes(it) }) }
            val rx = UpdateReceiver(File(dir, "phone-updates")) { f ->
                if (reject != null) throw UpdateError(reject, "APK is not signed with Beacon's key")
                assertEquals(apk.length(), f.length()); ApkInfo(UpdateRules.PACKAGE, 18, "0.1.17", setOf("x")) }
            var announced: UpdateReceiver.Ready? = null
            val rpc = UpdateRpc(rx, { ApkInfo(UpdateRules.PACKAGE, 17, "0.1.16", setOf("x")) }) { announced = it }
            val client = RelayClient(cfg, plainForTests = true)
            val phone = Thread {
                runCatching { val s = client.open(); if (client.waitForPc(s) { false }) PhoneSession(cfg.key, rpc::handle).run(s.getInputStream(), s.getOutputStream()) }
                client.close()
            }.apply { isDaemon = true; start() }
            Thread.sleep(500)
            val (code, out) = py(tool.path, "--home", home, "--msms-identity", File(dir, "identity.json").path, "push", apk.path, "--wait", "30")
            phone.join(20_000)
            val res = JSONObject(out.trim().lines().last())
            val sha = MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString("") { "%02x".format(it) }
            if (reject == null) {
                assertEquals(out, 0, code); assertTrue(res.getBoolean("ready")); assertEquals(sha, res.getString("sha256"))
                assertEquals("0.1.16", res.getJSONObject("was").getString("name"))
                assertEquals(sha, announced!!.sha256); assertEquals(sha, UpdateReceiver.sha256Hex(announced!!.file))
            } else {
                assertEquals(4, code); assertTrue(res.getString("error").startsWith(reject)); assertEquals(null, announced)
            }
        } finally { relay.destroy(); relay.waitFor(5, TimeUnit.SECONDS) }
    }
}
