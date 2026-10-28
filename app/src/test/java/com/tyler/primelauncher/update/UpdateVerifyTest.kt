package com.tyler.primelauncher.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

class UpdateVerifyTest {
    private val me = setOf("5a".repeat(32))
    private fun apk(pkg: String = UpdateRules.PACKAGE, code: Long = 18, signers: Set<String> = me) = ApkInfo(pkg, code, "0.1.17", signers)
    private fun rejects(code: String, a: ApkInfo?, installed: Long = 17, installedSigners: Set<String> = me, installedPkg: String = UpdateRules.PACKAGE) {
        try { UpdateRules.check(a, installedPkg, installed, installedSigners); fail("expected $code") } catch (e: UpdateError) { assertEquals(code, e.code) }
    }

    @Test fun acceptsNewerSameAppSameKey() { UpdateRules.check(apk(), UpdateRules.PACKAGE, 17, me) }
    @Test fun rejectsWrongPackage() {
        rejects("wrong_package", apk(pkg = "com.tyler.matrixsms"))
        rejects("wrong_package", apk(pkg = "com.evil.primelauncher"))
        rejects("wrong_package", apk(pkg = "com.tyler.matrixsms"), installedPkg = "com.tyler.matrixsms") // only ever updates Beacon itself
    }
    @Test fun rejectsWrongSigner() {
        rejects("wrong_signer", apk(signers = setOf("00".repeat(32))))
        rejects("wrong_signer", apk(signers = emptySet()))
        rejects("wrong_signer", apk(signers = me + "11".repeat(32))) // extra signer is not "the same key"
        rejects("wrong_signer", apk(), installedSigners = emptySet())
    }
    @Test fun rejectsLowerOrSameVersion() {
        rejects("not_newer", apk(code = 16)); rejects("not_newer", apk(code = 17)); rejects("not_newer", apk(code = 1))
    }
    @Test fun rejectsUnreadable() { rejects("bad_apk", null) }

    private fun tmp(): File = Files.createTempDirectory("upd").toFile()
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun receiverResumesVerifiesAndStores() {
        val data = ByteArray(2_500_000).also { SecureRandom().nextBytes(it) }; val h = sha(data)
        var verified = 0
        val rx = UpdateReceiver(tmp()) { f -> verified++; assertEquals(data.size.toLong(), f.length()); apk() }
        assertEquals(0L, rx.begin(h, data.size.toLong()))
        var off = rx.chunk(h, 0, data.copyOfRange(0, 1_000_000))
        assertEquals(1_000_000L, off)
        assertEquals("wrong offset returns where we are", 1_000_000L, rx.chunk(h, 5, data.copyOfRange(0, 10)))
        assertEquals("resume after a dropped link", 1_000_000L, rx.begin(h, data.size.toLong()))
        while (off < data.size) off = rx.chunk(h, off, data.copyOfRange(off.toInt(), minOf(data.size, off.toInt() + UpdateReceiver.CHUNK)))
        val r = rx.finish(h)
        assertEquals(1, verified); assertTrue(r.file.exists()); assertEquals(h, UpdateReceiver.sha256Hex(r.file)); assertNotNull(rx.ready)
    }
    @Test fun receiverDiscardsRejectedApks() {
        val data = ByteArray(4096).also { SecureRandom().nextBytes(it) }; val h = sha(data)
        for (code in listOf("wrong_package", "wrong_signer", "not_newer")) {
            val d = tmp()
            val rx = UpdateReceiver(d) { throw UpdateError(code, code) }
            rx.begin(h, data.size.toLong()); rx.chunk(h, 0, data)
            try { rx.finish(h); fail() } catch (e: UpdateError) { assertEquals(code, e.code) }
            assertEquals(null, rx.ready); assertTrue("nothing left behind", d.listFiles()!!.isEmpty())
        }
    }
    @Test fun receiverRejectsBadHashAndOversize() {
        val data = ByteArray(4096) { 7 }; val wrong = sha(ByteArray(4096) { 8 })
        val rx = UpdateReceiver(tmp()) { apk() }
        rx.begin(wrong, data.size.toLong()); rx.chunk(wrong, 0, data)
        try { rx.finish(wrong); fail() } catch (e: UpdateError) { assertEquals("bad_hash", e.code) }
        val h = sha(data); rx.begin(h, 10)
        try { rx.chunk(h, 0, data); fail() } catch (e: UpdateError) { assertEquals("bad_args", e.code) }
        try { rx.begin(h, UpdateRules.MAX_APK + 1); fail() } catch (e: UpdateError) { assertEquals("bad_args", e.code) }
        try { rx.begin("zz", 10); fail() } catch (e: UpdateError) { assertEquals("bad_args", e.code) }
    }

    @Test fun pairingCodeOnlyOpensWithThisPhonesKey() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val cfg = PairCfg("203.0.113.7", 443, "ab".repeat(32), "t".repeat(24), ByteArray(32) { it.toByte() }, "TestPC")
        val aes = ByteArray(32).also { SecureRandom().nextBytes(it) }; val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val wk = oaep(Cipher.ENCRYPT_MODE, kp.public).doFinal(aes)
        val ct = Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.ENCRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, iv)); doFinal(cfg.toJson().toString().toByteArray()) }
        val code = Pairing.PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(wk + iv + ct)
        assertTrue("fits Phone Link type_text (1000 chars)", code.length < 1000)
        assertEquals(cfg, Pairing.open(code) { oaep(Cipher.DECRYPT_MODE, kp.private).doFinal(it) })
        assertEquals(cfg, Pairing.open(code.chunked(60).joinToString("\n")) { oaep(Cipher.DECRYPT_MODE, kp.private).doFinal(it) })
        for (bad in listOf(code to other.private, code.dropLast(4) + "AAAA" to kp.private, "hello" to kp.private))
            try { Pairing.open(bad.first) { oaep(Cipher.DECRYPT_MODE, bad.second).doFinal(it) }; fail() } catch (e: UpdateError) { assertEquals("bad_pair", e.code) }
    }
    @Test fun sessionKeysDifferPerDirectionAndSession() {
        val k = ByteArray(32) { 1 }; val a = LinkCrypto.keys(k, ByteArray(16), ByteArray(16) { 2 }); val b = LinkCrypto.keys(k, ByteArray(16), ByteArray(16) { 3 })
        assertFalse(a.first.contentEquals(a.second)); assertFalse(a.first.contentEquals(b.first))
        assertEquals(64, LinkCrypto.room(k).length); assertFalse(LinkCrypto.room(k) == LinkCrypto.room(ByteArray(32)))
    }
    companion object {
        fun oaep(mode: Int, k: java.security.Key): Cipher = Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
            init(mode, k, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)) }
    }
}
