package com.tyler.primelauncher.phonelink.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.tyler.primelauncher.phonelink.core.Json
import com.tyler.primelauncher.phonelink.core.Policy
import com.tyler.primelauncher.phonelink.core.Session
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Prime Control — AccessibilityService used by Phone Link for gestures, screenshots and the UI tree.
 * Distinct from HangupService (which is scoped to ai.x.grok.bot and idle unless the red button arms it).
 *
 * OFF by default. The owner turns it on in Settings > Accessibility > Prime Control. The LinkService refuses every
 * tool except phone_status while this service is not connected OR the device is locked.
 *
 * Owner's decision: no app-based guards. The only hard refusals here are factory-reset / root / recovery screens
 * (HARD_STOP_PHRASES) and the hard URL blocklist. Everything else is allowed; Prime's own policy (per-message
 * approval, no purchases) is not enforced by code.
 */
class ControlService : AccessibilityService(), Session.ToolRunner, Session.UrlBlockAware {
    companion object {
        @Volatile var instance: ControlService? = null
            private set
        fun isEnabled(ctx: Context): Boolean {
            val cn = ComponentName(ctx, ControlService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return enabled.split(':').any { it.equals(cn, ignoreCase = true) }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val store by lazy { LinkStore(this) }
    private val io = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { if (instance === this) instance = null; io.shutdownNow(); super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* we pull on demand; no event stream */ }
    override fun onInterrupt() {}

    override fun urlBlock(): List<String> = store.urlBlocklist

    // ---- ToolRunner --------------------------------------------------------
    override fun run(tool: String, args: Json.Obj): Json.Value = when (tool) {
        "phone_status" -> phoneStatus()
        "screen_info" -> screenInfo()
        "screenshot" -> screenshot()
        "ui_tree" -> uiTree(args.intOrNull("max_nodes") ?: 200)
        "list_apps" -> listApps(args.boolOrNull("include_system") == true)
        "read_notifications" -> readNotifications(args.intOrNull("limit") ?: 20)
        "launch_app" -> launchApp(args.stringOrNull("package")!!)
        "tap" -> gesture(args.intOrNull("x")!!, args.intOrNull("y")!!, args.intOrNull("x")!!, args.intOrNull("y")!!, 50)
        "long_press" -> gesture(args.intOrNull("x")!!, args.intOrNull("y")!!, args.intOrNull("x")!!, args.intOrNull("y")!!, args.intOrNull("duration_ms") ?: 700)
        "swipe" -> gesture(args.intOrNull("x1")!!, args.intOrNull("y1")!!, args.intOrNull("x2")!!, args.intOrNull("y2")!!, args.intOrNull("duration_ms") ?: 300)
        "type_text" -> typeText(args.stringOrNull("text")!!)
        "global_action" -> globalAction(args.stringOrNull("action")!!)
        "open_url" -> openUrl(args.stringOrNull("url")!!)
        else -> throw Policy.PolicyError("tool not allowed: $tool")
    }

    override fun screenTexts(): List<String> {
        if (!deviceUnlocked()) return emptyList()
        val out = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || out.size >= 400) return
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            for (i in 0 until n.childCount) walk(n.getChild(i))
            n.recycle()
        }
        windows?.forEach { w -> walk(w.root) } ?: walk(rootInActiveWindow)
        return out
    }

    // ---- helpers -----------------------------------------------------------
    private fun deviceUnlocked(): Boolean =
        getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked != true

    private fun foregroundPackage(): String? {
        val win = windows?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
            ?: windows?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        return win?.root?.packageName?.toString() ?: rootInActiveWindow?.packageName?.toString()
    }

    private fun sizePx(): Pair<Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds; b.width() to b.height()
        } else {
            @Suppress("DEPRECATION") val m = DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }; m.widthPixels to m.heightPixels
        }
    }

    private fun requireUnlocked() { if (!deviceUnlocked()) throw Policy.PolicyError("phone is locked; only phone_status works") }

    private fun phoneStatus(): Json.Obj {
        val bm = getSystemService(android.os.BatteryManager::class.java)
        val pct = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = bm?.isCharging == true
        val pm = getSystemService(android.os.PowerManager::class.java)
        return Json.of(
            "battery_percent" to if (pct in 0..100) pct.toLong() else null,
            "charging" to charging,
            "screen_on" to (pm?.isInteractive == true),
            "locked" to !deviceUnlocked(),
            "foreground" to foregroundPackage(),
            "control_enabled" to true,
            "link_enabled" to store.enabled,
        )
    }

    private fun screenInfo(): Json.Obj {
        requireUnlocked()
        val (w, h) = sizePx()
        val dens = resources.displayMetrics.densityDpi
        return Json.of("width" to w.toLong(), "height" to h.toLong(), "density" to dens.toLong(), "foreground" to foregroundPackage())
    }

    private fun screenshot(): Json.Obj {
        requireUnlocked()
        if (Build.VERSION.SDK_INT < 30) throw Policy.PolicyError("screenshot needs Android 11+")
        val bmp = AtomicReference<Bitmap?>(); val err = AtomicReference<String?>(); val done = CountDownLatch(1)
        takeScreenshot(Display.DEFAULT_DISPLAY, io, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                try {
                    val src = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        ?: throw IllegalStateException("null buffer")
                    result.hardwareBuffer.close()
                    val maxW = 1080
                    val scaled = if (src.width > maxW)
                        Bitmap.createScaledBitmap(src, maxW, (src.height.toLong() * maxW / src.width).toInt(), true) else src
                    if (scaled !== src) src.recycle()
                    bmp.set(scaled)
                } catch (e: Exception) { err.set(e.message) }
                done.countDown()
            }
            override fun onFailure(errorCode: Int) { err.set("screenshot failed ($errorCode)"); done.countDown() }
        })
        if (!done.await(8, TimeUnit.SECONDS)) throw Policy.PolicyError("screenshot timed out")
        err.get()?.let { throw Policy.PolicyError(it) }
        val b = bmp.get() ?: throw Policy.PolicyError("screenshot empty")
        val w = b.width; val h = b.height
        val baos = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.PNG, 90, baos); b.recycle()
        val bytes = baos.toByteArray()
        if (bytes.size > 10 * 1024 * 1024) throw Policy.PolicyError("screenshot too large")
        return Json.of("png_b64" to Base64.encodeToString(bytes, Base64.NO_WRAP), "width" to w.toLong(), "height" to h.toLong())
    }

    private fun uiTree(maxNodes: Int): Json.Obj {
        requireUnlocked()
        val nodes = ArrayList<Json.Obj>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || nodes.size >= maxNodes) return
            val text = n.text?.toString().orEmpty(); val desc = n.contentDescription?.toString().orEmpty()
            val click = n.isClickable; val edit = n.isEditable
            if (text.isNotBlank() || desc.isNotBlank() || click || edit) {
                val r = Rect(); n.getBoundsInScreen(r)
                val m = LinkedHashMap<String, Json.Value>()
                if (text.isNotBlank()) m["text"] = Json.Str(text.take(200))
                if (desc.isNotBlank()) m["desc"] = Json.Str(desc.take(200))
                n.viewIdResourceName?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { m["id"] = Json.Str(it.take(80)) }
                n.className?.toString()?.substringAfterLast('.')?.let { m["class"] = Json.Str(it) }
                m["bounds"] = Json.Str("[${r.left},${r.top}][${r.right},${r.bottom}]")
                if (click) m["clickable"] = Json.Bool(true)
                if (edit) m["editable"] = Json.Bool(true)
                nodes.add(Json.Obj(m))
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
            n.recycle()
        }
        windows?.forEach { w -> walk(w.root) } ?: walk(rootInActiveWindow)
        return Json.of("foreground" to foregroundPackage(), "nodes" to nodes)
    }

    private fun listApps(includeSystem: Boolean): Json.Obj {
        val pm = packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { includeSystem || (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
            .sortedBy { it.packageName }
            .map { Json.of("package" to it.packageName, "label" to pm.getApplicationLabel(it).toString()) }
        return Json.of("apps" to apps)
    }

    private fun readNotifications(limit: Int): Json.Obj {
        // Wired by the launcher (see INTEGRATION.md): NotifBridge.all() returns List of {pkg,title,text,time}.
        val notes = NotifBridge.all().take(limit).map {
            Json.of("package" to it.pkg, "title" to it.title, "text" to it.text, "time_ms" to it.time)
        }
        return Json.of("count" to notes.size.toLong(), "notifications" to notes)
    }

    private fun launchApp(pkg: String): Json.Obj {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: throw Policy.PolicyError("that app isn't installed or has no launcher entry")
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i)
        return Json.of("launched" to pkg)
    }

    private fun gesture(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Json.Obj {
        requireUnlocked()
        val (w, h) = sizePx()
        if (x1 !in 0 until w || y1 !in 0 until h || x2 !in 0 until w || y2 !in 0 until h)
            throw Policy.PolicyError("point is off the ${w}x${h} screen")
        // Re-check hard-stop immediately before dispatch (narrows TOCTOU).
        Policy.hardStopHit(screenTexts())?.let { throw Session.HardStop("hard refusal: this screen mentions '$it' (reset/root/recovery)") }
        val path = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.toLong().coerceAtLeast(1))
        val ok = AtomicReference(false); val done = CountDownLatch(1)
        val dispatched = main.post {
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { ok.set(true); done.countDown() }
                override fun onCancelled(gestureDescription: GestureDescription?) { done.countDown() }
            }, null)
        }
        if (!dispatched) throw Policy.PolicyError("gesture dispatch failed")
        if (!done.await(5, TimeUnit.SECONDS) || !ok.get()) throw Policy.PolicyError("gesture cancelled")
        return Json.of("ok" to true)
    }

    private fun typeText(text: String): Json.Obj {
        requireUnlocked()
        Policy.hardStopHit(screenTexts())?.let { throw Session.HardStop("hard refusal: this screen mentions '$it' (reset/root/recovery)") }
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: throw Policy.PolicyError("no editable field is focused")
        if (!focused.isEditable) { focused.recycle(); throw Policy.PolicyError("focused node is not editable") }
        val args = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        focused.recycle()
        if (!ok) throw Policy.PolicyError("set text failed")
        return Json.of("typed_chars" to text.length.toLong())
    }

    private fun globalAction(action: String): Json.Obj {
        val id = when (action) {
            "back" -> GLOBAL_ACTION_BACK; "home" -> GLOBAL_ACTION_HOME; "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS; "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> throw Policy.PolicyError("action not allowed")
        }
        if (!performGlobalAction(id)) throw Policy.PolicyError("global action failed")
        return Json.of("done" to action)
    }

    private fun openUrl(url: String): Json.Obj {
        // Policy already validated scheme + blocklists.
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .addCategory(android.content.Intent.CATEGORY_BROWSABLE).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i); return Json.of("opened" to true)
    }
}
