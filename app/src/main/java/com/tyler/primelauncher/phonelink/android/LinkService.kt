package com.tyler.primelauncher.phonelink.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import android.os.PowerManager
import com.tyler.primelauncher.phonelink.core.Json
import com.tyler.primelauncher.phonelink.core.Policy
import com.tyler.primelauncher.phonelink.core.Session
import com.tyler.primelauncher.phonelink.core.Wire
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import kotlin.math.min

/**
 * Phone Link foreground service. Dials OUT to your relay server (host/port from pairing) over TLS 1.3 with the server cert pinned
 * (SHA-256 of the DER equals [LinkStore.fingerprint]), then runs a [Session]. Nothing listens on the phone.
 *
 * Reconnects with exponential backoff (2 s .. 5 min) + jitter. A ConnectivityManager callback wakes it on network
 * change. A PARTIAL_WAKE_LOCK is held only while a socket is open (dropped between reconnects) so Doze can sleep
 * the radio between attempts. Idle keepalive is the server's signed _ping every ~30 s.
 *
 * Soft kill: [LinkStore.enabled] = false (Settings toggle, QS tile, or the Disconnect action on the persistent
 * notification). Hard kill: uninstall / revoke Accessibility, or the relay-side kill file.
 */
class LinkService : Service() {
    companion object {
        /** 0.1.4: new IDs so the quieter importance applies even if the old channels were customised (Android never raises/lowers an existing channel the user touched). */
        const val CHANNEL = "phone_link_min"
        const val SESSION_CHANNEL = "phone_link_session_quiet"
        private val OLD_CHANNELS = listOf("phone_link", "phone_link_session")
        const val NOTIF_ID = 7101
        const val ACTION_DISCONNECT = "com.tyler.primelauncher.phonelink.DISCONNECT"
        const val ACTION_CONNECT = "com.tyler.primelauncher.phonelink.CONNECT"
        @Volatile var running = false; private set
        @Volatile var state: String = "off"; private set
        fun start(ctx: Context) { ctx.startForegroundService(Intent(ctx, LinkService::class.java).setAction(ACTION_CONNECT)) }
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, LinkService::class.java).setAction(ACTION_DISCONNECT)) }
    }

    private lateinit var store: LinkStore
    private val stop = AtomicBoolean(false)
    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null
    private var networkCb: ConnectivityManager.NetworkCallback? = null
    private val wakeLock = Object()   // notify to cut a sleep short on network change / resume

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = LinkStore(this)
        val nm = getSystemService(NotificationManager::class.java)
        // Retire the 0.1.3 channels (no foreground notification uses them yet in this process, so delete is allowed).
        for (id in OLD_CHANNELS) runCatching { nm.deleteNotificationChannel(id) }
        // MIN: no sound, no heads-up, no status-bar icon, collapsed at the bottom of the shade. Still a valid FGS notification.
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Prime link", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Prime can control this phone while the link is connected"
            setShowBadge(false); setSound(null, null); enableVibration(false); enableLights(false)
        })
        registerReceiver(disconnectRx, IntentFilter(ACTION_DISCONNECT), RECEIVER_NOT_EXPORTED)
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { synchronized(wakeLock) { wakeLock.notifyAll() } }
            override fun onLost(network: Network) { /* next send fails; loop reconnects */ }
        }
        cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), cb)
        networkCb = cb
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                store.enabled = false; stop.set(true); dropSocket(); stopSelf(); return START_NOT_STICKY
            }
            else -> {
                if (!store.configured) { stopSelf(); return START_NOT_STICKY }
                store.enabled = true; stop.set(false)
                startForeground(NOTIF_ID, buildNotif("Connecting…"))
                if (worker?.isAlive != true) {
                    running = true; state = "connecting"
                    worker = thread(name = "phone-link", isDaemon = true) { loop() }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stop.set(true); dropSocket(); worker = null; running = false; state = "off"
        runCatching { unregisterReceiver(disconnectRx) }
        networkCb?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        wake?.let { if (it.isHeld) it.release() }; super.onDestroy()
    }

    private val disconnectRx = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            store.enabled = false; stop.set(true); dropSocket(); stopSelf()
        }
    }

    @Volatile private var liveSocket: SSLSocket? = null
    private fun dropSocket() { try { liveSocket?.close() } catch (_: Exception) {}; liveSocket = null; synchronized(wakeLock) { wakeLock.notifyAll() } }

    private fun loop() {
        var backoff = 2000L
        while (!stop.get() && store.enabled) {
            if (!ControlService.isEnabled(this) && ControlService.instance == null) {
                state = "need-control"; notify("Turn on Prime Control in Accessibility settings"); sleep(backoff); continue
            }
            try {
                state = "connecting"; notify("Connecting…")
                val sock = connectPinned(store.host, store.port, store.fingerprint)
                liveSocket = sock
                holdWake(true)
                val key = store.loadAgentKey()
                val runner = object : Session.ToolRunner, Session.UrlBlockAware {
                    private fun delegate(): Session.ToolRunner =
                        ControlService.instance ?: StatusOnlyRunner(this@LinkService, store)
                    override fun run(tool: String, args: Json.Obj): Json.Value {
                        val kg = getSystemService(android.app.KeyguardManager::class.java)
                        if (kg?.isKeyguardLocked == true && tool != "phone_status")
                            throw Policy.PolicyError("phone is locked; only phone_status works")
                        return delegate().run(tool, args)
                    }
                    override fun screenTexts(): List<String> = ControlService.instance?.screenTexts() ?: emptyList()
                    override fun urlBlock(): List<String> = store.urlBlocklist
                }
                val sessionCounter = intArrayOf(0)
                val session = Session(key, runner,
                    tokenProvider = { store.loadToken() },
                    audit = { e -> store.audit(org.json.JSONObject(e.encode())) },
                    stopRequested = { stop.get() || !store.enabled },
                    onCommand = {
                        val now = System.currentTimeMillis()
                        val last = spSessionLast()
                        sessionCounter[0] += 1
                        if (now - last >= store.sessionIdleMs) {
                            sessionCounter[0] = 1
                            alertSession(now)
                        }
                        spSessionLast(now)
                        // Refresh ongoing notif with tool count for this session burst
                        notify("Prime link active · ${sessionCounter[0]} action(s) this session")
                    })
                session.handshake(sock.inputStream, sock.outputStream)
                state = "connected"; notify("Prime link active"); backoff = 2000L
                store.audit(org.json.JSONObject().put("event", "connected"))
                session.serve(sock.inputStream, sock.outputStream)
            } catch (e: Session.AuthFailed) {
                state = "auth-failed"; notify("Auth failed — re-pair Phone Link")
                store.audit(org.json.JSONObject().put("event", "auth-failed").put("why", e.message))
                backoff = 300_000L   // don't hammer a wrong key
            } catch (e: Exception) {
                state = "reconnecting"; notify("Reconnecting…")
                store.audit(org.json.JSONObject().put("event", "disconnected").put("why", e.javaClass.simpleName))
            } finally {
                dropSocket(); holdWake(false); keyZero()
            }
            if (stop.get() || !store.enabled) break
            sleep(backoff); backoff = min(backoff * 2, 300_000L)
        }
        running = false; state = "off"; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    private fun sleep(ms: Long) {
        val jitter = (ms * (0.8 + 0.4 * SecureRandom().nextDouble())).toLong()
        synchronized(wakeLock) { try { wakeLock.wait(jitter) } catch (_: InterruptedException) {} }
    }

    private fun holdWake(on: Boolean) {
        if (on) {
            if (wake == null)
                wake = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "primelauncher:phonelink").apply { setReferenceCounted(false) }
            if (wake?.isHeld != true) wake?.acquire(60 * 60 * 1000L)   // refreshed every reconnect; max 1 h
        } else if (wake?.isHeld == true) wake?.release()
    }

    private fun keyZero() { /* best-effort; ByteArray from loadKey falls out of scope */ }

    private fun spSessionLast(): Long = getSharedPreferences("phone_link", MODE_PRIVATE).getLong("session_last_cmd", 0L)
    private fun spSessionLast(v: Long) = getSharedPreferences("phone_link", MODE_PRIVATE).edit().putLong("session_last_cmd", v).apply()

    /** Notify the owner that Prime just started a new control burst (first command after idle). */
    private fun alertSession(whenMs: Long) {
        val nm = getSystemService(NotificationManager::class.java)
        // LOW: still listed in the shade (security: you can see a session started and tap Disconnect) but silent, no heads-up banner.
        nm.createNotificationChannel(NotificationChannel(SESSION_CHANNEL, "Prime session alerts", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown when Prime starts using this phone"
            setShowBadge(false); setSound(null, null); enableVibration(false); enableLights(false)
        })
        val disc = PendingIntent.getBroadcast(this, 2, Intent(ACTION_DISCONNECT).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val time = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date(whenMs))
        val n = Notification.Builder(this, SESSION_CHANNEL)
            .setContentTitle("Prime is using this phone")
            .setContentText("Session started $time · Disconnect to cut the link")
            .setSmallIcon(com.tyler.primelauncher.R.drawable.ic_stat)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .addAction(Notification.Action.Builder(null, "Disconnect", disc).build())
            .build()
        nm.notify(NOTIF_ID + 1, n)
        store.audit(org.json.JSONObject().put("event", "session-alert").put("at", whenMs))
    }

    private fun buildNotif(text: String): Notification {
        val disc = PendingIntent.getBroadcast(this, 0, Intent(ACTION_DISCONNECT).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Tap opens the launcher's Settings page for Phone Link (wired in INTEGRATION.md).
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Prime link active")
            .setContentText(text)
            .setSmallIcon(com.tyler.primelauncher.R.drawable.ic_stat)   // replace with a launcher drawable on integrate
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Disconnect", disc).build())
            .build()
    }
    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(text))
    }

    /** TLS 1.3 client that trusts exactly one cert by SHA-256 fingerprint (no CA, no hostname). */
    private fun connectPinned(host: String, port: Int, fingerprint: String): SSLSocket {
        val tm = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val cert = chain?.firstOrNull() ?: throw javax.net.ssl.SSLException("empty chain")
                val got = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                    .joinToString("") { "%02x".format(it) }
                if (!MessageDigest.isEqual(got.toByteArray(), fingerprint.toByteArray()))
                    throw javax.net.ssl.SSLException("server certificate fingerprint mismatch (got ${got.take(16)}..)")
            }
        }
        val ctx = SSLContext.getInstance("TLS"); ctx.init(null, arrayOf(tm), SecureRandom())
        val sock = ctx.socketFactory.createSocket() as SSLSocket
        sock.connect(InetSocketAddress(host, port), 10_000)
        sock.enabledProtocols = arrayOf("TLSv1.3")
        sock.soTimeout = 120_000
        sock.startHandshake()
        // Pin was checked inside checkServerTrusted during the handshake — before any app byte is written.
        return sock
    }
}

/** Fallback runner used when ControlService isn't connected: only phone_status works. */
private class StatusOnlyRunner(private val ctx: Context, private val store: LinkStore) : Session.ToolRunner {
    override fun run(tool: String, args: Json.Obj): Json.Value {
        if (tool != "phone_status") throw Policy.PolicyError("Prime Control (Accessibility) is off")
        val bm = ctx.getSystemService(android.os.BatteryManager::class.java)
        val pm = ctx.getSystemService(android.os.PowerManager::class.java)
        val kg = ctx.getSystemService(android.app.KeyguardManager::class.java)
        return Json.of(
            "battery_percent" to (bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)?.toLong() ?: -1L),
            "charging" to (bm?.isCharging == true),
            "screen_on" to (pm?.isInteractive == true),
            "locked" to (kg?.isKeyguardLocked == true),
            "foreground" to null,
            "control_enabled" to false,
            "link_enabled" to store.enabled,
        )
    }
    override fun screenTexts(): List<String> = emptyList()
}
