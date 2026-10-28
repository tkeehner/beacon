package com.tyler.primelauncher.update

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import com.tyler.primelauncher.R
import org.json.JSONObject
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/** Keystore keys for the updater: an RSA key the PC seals the pairing code to, and an AES key sealing the stored pairing. */
interface UpdateKeys { fun publicDer(): ByteArray; fun unwrap(b: ByteArray): ByteArray; fun seal(raw: ByteArray): Pair<String, String>; fun open(ct: String, iv: String): ByteArray; fun forget() }
object KeystoreUpdateKeys : UpdateKeys {
    private const val RSA = "beacon_update_rsa"; private const val AES = "beacon_update_aes"
    private fun ks() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun rsa() { if (ks().containsAlias(RSA)) return
        KeyPairGenerator.getInstance("RSA", "AndroidKeyStore").apply { initialize(android.security.keystore.KeyGenParameterSpec.Builder(RSA, android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(2048).setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256, android.security.keystore.KeyProperties.DIGEST_SHA1).build()) }.generateKeyPair() }
    private fun aes(create: Boolean): SecretKey { val k = ks()
        if (k.containsAlias(AES)) return (k.getEntry(AES, null) as KeyStore.SecretKeyEntry).secretKey
        require(create) { "keystore key missing" }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply { init(android.security.keystore.KeyGenParameterSpec.Builder(AES,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build()) }.generateKey() }
    override fun publicDer(): ByteArray { rsa(); return ks().getCertificate(RSA).publicKey.encoded }
    override fun unwrap(b: ByteArray): ByteArray = Cipher.getInstance("RSA/ECB/OAEPPadding").run {
        init(Cipher.DECRYPT_MODE, ks().getKey(RSA, null) as PrivateKey, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)); doFinal(b) }
    override fun seal(raw: ByteArray): Pair<String, String> { val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, aes(true))
        return Base64.encodeToString(c.doFinal(raw), Base64.NO_WRAP) to Base64.encodeToString(c.iv, Base64.NO_WRAP) }
    override fun open(ct: String, iv: String): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.DECRYPT_MODE, aes(false), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))); doFinal(Base64.decode(ct, Base64.NO_WRAP)) }
    override fun forget() { runCatching { ks().deleteEntry(AES) } } // the RSA key stays so the phone key shown to the PC is stable
}

/** App-private prefs "beacon_update" (allowBackup=false). Pairing JSON only as Keystore ciphertext. */
class UpdateStore(ctx: Context) {
    companion object { @Volatile var keys: UpdateKeys = KeystoreUpdateKeys }
    private val sp = ctx.applicationContext.getSharedPreferences("beacon_update", Context.MODE_PRIVATE)
    val paired get() = sp.contains("pair_ct")
    var enabled: Boolean get() = sp.getBoolean("enabled", true); set(v) { sp.edit().putBoolean("enabled", v).apply() }
    val pcName: String get() = sp.getString("pc", "PC").orEmpty()
    fun pair(): PairCfg? = runCatching { PairCfg.fromJson(JSONObject(String(keys.open(sp.getString("pair_ct", null)!!, sp.getString("pair_iv", null)!!), Charsets.UTF_8))) }.getOrNull()
    fun save(c: PairCfg) { val (ct, iv) = keys.seal(c.toJson().toString().toByteArray()); sp.edit().putString("pair_ct", ct).putString("pair_iv", iv).putString("pc", c.pc).putBoolean("enabled", true).commit() }
    fun clear() { sp.edit().clear().commit(); keys.forget() }
    var readyJson: String? get() = sp.getString("ready", null); set(v) { sp.edit().putString("ready", v).apply() }
    var last: String get() = sp.getString("last", "").orEmpty(); set(v) { sp.edit().putString("last", v.take(120)).apply() }
    fun phoneKey(): String = Base64.encodeToString(keys.publicDer(), Base64.NO_WRAP)
    fun phoneFingerprint(): String = Pairing.fingerprint(keys.publicDer())
}

object Updater {
    const val CHANNEL = "beacon_updates"; private const val NOTIF = 7101
    const val PAIR_FILE = "beacon_update_pair.txt" // optional at-home route: `adb push` the pairing code here (ciphertext only)
    @Volatile var state = "off"; private set
    @Volatile private var thread: Thread? = null
    @Volatile private var stopFlag = false
    @Volatile private var client: RelayClient? = null
    private var rx: UpdateReceiver? = null

    fun receiver(ctx: Context): UpdateReceiver = rx ?: UpdateReceiver(File(ctx.noBackupFilesDir, "updates")) { verify(ctx, it) }.also { r ->
        rx = r; UpdateStore(ctx).readyJson?.let { j -> runCatching { readyFrom(JSONObject(j), r) }.getOrNull() }?.let { r.restore(it) } }

    fun signers(pi: PackageInfo?): Set<String> {
        val si = pi?.signingInfo ?: return emptySet()
        // Current signer(s) only; Beacon has never rotated its key, so this must equal the installed app's set exactly.
        return si.apkContentsSigners.orEmpty().map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }
    fun installedInfo(ctx: Context): ApkInfo { val pi = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        return ApkInfo(pi.packageName, pi.longVersionCode, pi.versionName ?: "?", signers(pi)) }
    /** Reads the APK with PackageManager and applies [UpdateRules] against the installed app. */
    fun verify(ctx: Context, f: File): ApkInfo {
        val pi = ctx.packageManager.getPackageArchiveInfo(f.path, PackageManager.GET_SIGNING_CERTIFICATES)
        val info = pi?.let { ApkInfo(it.packageName, it.longVersionCode, it.versionName ?: "?", signers(it)) }
        val me = installedInfo(ctx)
        UpdateRules.check(info, me.packageName, me.versionCode, me.signers)
        return info!!
    }
    @Suppress("UNUSED_PARAMETER") private fun readyFrom(o: JSONObject, r: UpdateReceiver): UpdateReceiver.Ready? { val f = File(o.getString("path")); if (!f.exists()) return null
        return UpdateReceiver.Ready(ApkInfo(UpdateRules.PACKAGE, o.getLong("code"), o.getString("name"), emptySet()), o.getString("sha"), o.getLong("size"), f) }

    /** Pastes/pushed pairing code -> sealed store. Throws UpdateError with a user-safe message. */
    fun pairWith(ctx: Context, code: String) { val c = Pairing.open(code) { UpdateStore.keys.unwrap(it) }; UpdateStore(ctx).save(c); restart(ctx) }
    /** At-home route: pick up a pairing code pushed into this app's own Android/data folder (other apps can't write there). */
    fun pickUpPushedPair(ctx: Context) {
        val d = ctx.getExternalFilesDir(null) ?: return; val f = File(d, PAIR_FILE); if (!f.exists()) return
        val code = try { if (f.length() > 8192) "" else f.readText() } finally { f.delete() }
        val st = runCatching { pairWith(ctx, code); "ok paired" }.getOrElse { "error: " + (it as? UpdateError)?.message }
        File(d, "beacon_update_pair.status").writeText(st + "\n")
    }
    fun publishPhoneKey(ctx: Context) { val d = ctx.getExternalFilesDir(null) ?: return
        runCatching { File(d, "beacon_update.pub").writeText(UpdateStore(ctx).phoneKey()) } }

    fun ensure(ctx: Context) {
        val app = ctx.applicationContext; val st = UpdateStore(app)
        if (!st.paired || !st.enabled) { stop(); state = if (st.paired) "off" else "not paired"; return }
        if (thread?.isAlive == true) return
        stopFlag = false
        thread = Thread({ loop(app) }, "beacon-updater").apply { isDaemon = true; start() }
    }
    fun stop() { stopFlag = true; client?.close(); thread?.interrupt(); thread = null }
    fun restart(ctx: Context) { stop(); ensure(ctx) }

    private fun online(ctx: Context) = ctx.getSystemService(ConnectivityManager::class.java)?.activeNetwork != null
    private fun loop(ctx: Context) {
        var backoff = 5_000L
        while (!stopFlag) {
            val cfg = UpdateStore(ctx).pair() ?: run { state = "pairing unreadable, re-pair"; return }
            if (!online(ctx)) { state = "waiting for network"; sleep(30_000); continue }
            val c = RelayClient(cfg); client = c
            try {
                state = "connecting"
                val s = c.open(); state = "waiting for PC"
                if (c.waitForPc(s) { stopFlag }) {
                    state = "receiving"; backoff = 5_000L
                    val rpc = UpdateRpc(receiver(ctx), { installedInfo(ctx) }) { r -> announce(ctx, r) }
                    PhoneSession(cfg.key, rpc::handle).run(s.getInputStream(), s.getOutputStream())
                    UpdateStore(ctx).last = "session ok " + java.text.SimpleDateFormat("MMM d h:mm a", java.util.Locale.US).format(java.util.Date())
                } else backoff = 5_000L
            } catch (e: UpdateError) { UpdateStore(ctx).last = e.code; state = "error: ${e.message}"; if (e.code == "relay_auth" || e.code == "relay_pin") backoff = 15 * 60_000L }
            catch (e: Exception) { state = "reconnecting"; UpdateStore(ctx).last = e.javaClass.simpleName }
            finally { c.close() }
            if (!stopFlag) { sleep(backoff); backoff = (backoff * 2).coerceAtMost(5 * 60_000L) }
        }
        state = "off"
    }
    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    private fun announce(ctx: Context, r: UpdateReceiver.Ready) {
        UpdateStore(ctx).readyJson = JSONObject().put("path", r.file.path).put("code", r.info.versionCode).put("name", r.info.versionName).put("sha", r.sha256).put("size", r.size).toString()
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Beacon updates", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, UpdateInstallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching { nm.notify(NOTIF, Notification.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_stat).setContentTitle("Beacon ${r.info.versionName} is ready")
            .setContentText("Verified: same app, same signing key, newer version. Tap to install.").setContentIntent(pi).setAutoCancel(true).build()) }
    }
    fun ready(ctx: Context): UpdateReceiver.Ready? = receiver(ctx).ready

    /** Starts Android's own install prompt for the verified update. Never installs silently. */
    fun install(a: Activity) {
        val r = ready(a) ?: run { status(a, "No update waiting"); return }
        if (!a.packageManager.canRequestPackageInstalls()) {
            status(a, "Allow Beacon to install updates, then tap Install again")
            runCatching { a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName))) }
            return
        }
        try { verify(a, r.file) } catch (e: UpdateError) { receiver(a).clear(); UpdateStore(a).readyJson = null; status(a, "Update rejected: ${e.message}"); return }
        val pi = a.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(a.packageName); setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED) }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("beacon.apk", 0, r.file.length()).use { out -> r.file.inputStream().use { it.copyTo(out, 1 shl 16) }; s.fsync(out) }
            val cb = PendingIntent.getBroadcast(a, id, Intent(a, InstallResultReceiver::class.java).setPackage(a.packageName), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            s.commit(cb.intentSender)
        }
        status(a, "Opening Android's install prompt…")
    }
    fun status(ctx: Context, s: String) { UpdateStore(ctx).last = s; runCatching { android.widget.Toast.makeText(ctx, s, android.widget.Toast.LENGTH_LONG).show() } }
}

/** Gets PackageInstaller's result. PENDING_USER_ACTION -> show Android's own confirm screen; the owner taps Install there. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                runCatching { c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> { Updater.receiver(c).clear(); UpdateStore(c).readyJson = null; UpdateStore(c).last = "updated" }
            else -> UpdateStore(c).last = "install failed: " + (i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "?").take(80)
        }
    }
}

/** Opened from the "update ready" notification or Settings. Hands straight to Android's install prompt. */
class UpdateInstallActivity : Activity() {
    override fun onCreate(b: Bundle?) { super.onCreate(b); runCatching { Updater.install(this) }.onFailure { Updater.status(this, "Install couldn't start") }; finish() }
}
