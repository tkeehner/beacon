package com.tyler.primelauncher.phonelink.core

/**
 * Phone Link policy — the Kotlin mirror of phonelink_policy.py. Keep the two in sync.
 *
 * Owner's decision: no app-based guards. Prime may see and operate ANY app. What stays enforced in code:
 *  * a fixed tool-type allowlist (no raw shell, no install/uninstall/settings-write/SMS-send/call tools)
 *  * hard refusals: factory reset / wipe / remote erase, root / su, reboot to recovery / bootloader / download mode
 *    (HARD_STOP_PHRASES refuse ALL gestures/typing while that screen is showing; back/home still work)
 *  * open_url accepts only http/https (no sms:, tel:, intent:, file:, content:); HARD_URL_BLOCK + a user-editable list
 * Configurable in launcher Settings: url_blocklist. The hard-stop phrase list and HARD_URL_BLOCK are NOT configurable.
 * Prime's own policy (not code) still requires the owner's approval for every outgoing message, and no purchases.
 */
object Policy {
    class PolicyError(msg: String) : Exception(msg)

    const val MAX_COORD = 4000
    const val MAX_TEXT = 1000
    const val MAX_URL = 2000
    private val PKG = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    val GLOBAL_ACTIONS = setOf("back", "home", "recents", "notifications", "quick_settings")
    val INTERNAL = setOf("_ping")

    /** Remote-erase pages are a factory-reset path, so they are a HARD refusal (not configurable off). */
    val HARD_URL_BLOCK = listOf(
        "findmymobile.samsung.com", "smartthingsfind.samsung.com", "google.com/android/find",
        "android.com/find", "myaccount.google.com/find-your-phone")

    /** Screens showing any of these (case-insensitive) refuse ALL gestures/typing; back/home still work. */
    val HARD_STOP_PHRASES = listOf(
        "factory data reset", "factory reset", "erase all data", "erase all content", "delete all data", "reset phone",
        "reset device", "reset to factory", "wipe data", "wipe device", "erase device", "erase data", "master clear",
        "format device", "oem unlocking", "unlock bootloader", "reboot to recovery", "reboot to bootloader", "reboot recovery",
        "reboot bootloader", "recovery mode", "download mode", "fastboot", "superuser", "su binary", "magisk", "grant root",
        "root access")

    data class Tool(val name: String, val readOnly: Boolean, val timeoutSec: Int, val required: Set<String>, val props: Set<String>)
    val TOOLS = listOf(
        Tool("phone_status", true, 15, emptySet(), emptySet()),
        Tool("screen_info", true, 15, emptySet(), emptySet()),
        Tool("screenshot", true, 25, emptySet(), emptySet()),
        Tool("ui_tree", true, 20, emptySet(), setOf("max_nodes")),
        Tool("list_apps", true, 20, emptySet(), setOf("include_system")),
        Tool("read_notifications", true, 20, emptySet(), setOf("limit")),
        Tool("launch_app", false, 15, setOf("package"), setOf("package")),
        Tool("tap", false, 15, setOf("x", "y"), setOf("x", "y")),
        Tool("long_press", false, 15, setOf("x", "y"), setOf("x", "y", "duration_ms")),
        Tool("swipe", false, 15, setOf("x1", "y1", "x2", "y2"), setOf("x1", "y1", "x2", "y2", "duration_ms")),
        Tool("type_text", false, 15, setOf("text"), setOf("text")),
        Tool("global_action", false, 15, setOf("action"), setOf("action")),
        Tool("open_url", false, 15, setOf("url"), setOf("url")),
    ).associateBy { it.name }

    fun hardStopHit(screenTexts: Iterable<String>): String? {
        val blob = screenTexts.joinToString(" \n ") { it.lowercase() }
        return HARD_STOP_PHRASES.firstOrNull { it in blob }
    }

    private fun hostMatch(host: String, path: String, entry: String): Boolean {
        val e = entry.lowercase(); val slash = e.indexOf('/'); val bh = if (slash < 0) e else e.substring(0, slash)
        val bp = if (slash < 0) "" else e.substring(slash + 1)
        return (host == bh || host.endsWith(".$bh")) && path.startsWith("/$bp")
    }

    fun checkUrl(u: String, extraBlock: List<String> = emptyList()): String {
        if (u.isEmpty() || u.length > MAX_URL) throw PolicyError("invalid url")
        if (u.any { it.code <= 32 || it.code == 127 || it in "\"\\`<>" }) throw PolicyError("url contains forbidden characters")
        val schemeEnd = u.indexOf("://"); if (schemeEnd < 0) throw PolicyError("only http(s) URLs can be opened")
        val scheme = u.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") throw PolicyError("only http(s) URLs can be opened")
        val rest = u.substring(schemeEnd + 3)
        val hostEnd = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
        var host = rest.substring(0, hostEnd).lowercase()
        if (':' in host) host = host.substringBefore(':')
        if (host.isEmpty()) throw PolicyError("only http(s) URLs can be opened")
        val path = (if (hostEnd < rest.length && rest[hostEnd] == '/') rest.substring(hostEnd).substringBefore('?').substringBefore('#') else "/").lowercase()
        if (HARD_URL_BLOCK.any { hostMatch(host, path, it) }) throw PolicyError("remote-erase pages are blocked (hard refusal)")
        if (extraBlock.any { hostMatch(host, path, it) }) throw PolicyError("that site is on the owner's blocklist")
        return u
    }

    private fun intArg(args: Json.Obj, k: String, lo: Int, hi: Int, default: Int? = null): Int {
        val raw = args.raw(k) ?: default ?: throw PolicyError("missing argument: $k")
        if (raw !is Long && raw !is Int) throw PolicyError("$k must be an integer")
        val v = (raw as Number).toInt()
        if (v.toLong() != (raw as Number).toLong() || v !in lo..hi) throw PolicyError("$k out of range $lo..$hi")
        return v
    }

    /**
     * Validate + normalize. Returns a new Obj of only the allowed, typed arguments.
     * @param internalOk true only on the signed server->agent channel (lets _ping through).
     */
    fun validate(name: String?, args: Json.Obj?, internalOk: Boolean = false, urlBlock: List<String> = emptyList()): Json.Obj {
        if (internalOk && name in INTERNAL && (args == null || args.map.isEmpty())) return Json.of()
        if (name == null || name !in TOOLS) throw PolicyError("tool not allowed: ${name?.take(40)}")
        val tool = TOOLS[name]!!; val a = args ?: Json.of()
        val extra = a.map.keys - tool.props
        if (extra.isNotEmpty()) throw PolicyError("unexpected argument(s): " + extra.sorted().joinToString(", ").take(120))
        for (r in tool.required) if (r !in a.map) throw PolicyError("missing argument: $r")
        return when (name) {
            "list_apps" -> {
                val v = a.raw("include_system") ?: false
                if (v !is Boolean) throw PolicyError("include_system must be boolean")
                Json.of("include_system" to v)
            }
            "read_notifications" -> Json.of("limit" to intArg(a, "limit", 1, 50, 20))
            "ui_tree" -> Json.of("max_nodes" to intArg(a, "max_nodes", 10, 400, 200))
            "launch_app" -> {
                val p = a.stringOrNull("package") ?: throw PolicyError("invalid package name")
                if (p.length > 200 || !PKG.matches(p)) throw PolicyError("invalid package name")
                Json.of("package" to p)
            }
            "tap" -> Json.of("x" to intArg(a, "x", 0, MAX_COORD), "y" to intArg(a, "y", 0, MAX_COORD))
            "long_press" -> Json.of("x" to intArg(a, "x", 0, MAX_COORD), "y" to intArg(a, "y", 0, MAX_COORD),
                                    "duration_ms" to intArg(a, "duration_ms", 300, 3000, 700))
            "swipe" -> Json.of("x1" to intArg(a, "x1", 0, MAX_COORD), "y1" to intArg(a, "y1", 0, MAX_COORD),
                               "x2" to intArg(a, "x2", 0, MAX_COORD), "y2" to intArg(a, "y2", 0, MAX_COORD),
                               "duration_ms" to intArg(a, "duration_ms", 50, 3000, 300))
            "type_text" -> {
                val t = a.stringOrNull("text") ?: throw PolicyError("text must be a non-empty string")
                if (t.isEmpty()) throw PolicyError("text must be a non-empty string")
                if (t.length > MAX_TEXT) throw PolicyError("text longer than $MAX_TEXT")
                if (t.any { (it.code < 32 && it !in "\n\t") || it.code == 127 }) throw PolicyError("control characters can't be typed")
                Json.of("text" to t)
            }
            "global_action" -> {
                val v = a.stringOrNull("action") ?: throw PolicyError("action not allowed")
                if (v !in GLOBAL_ACTIONS) throw PolicyError("action not allowed")
                Json.of("action" to v)
            }
            "open_url" -> Json.of("url" to checkUrl(a.stringOrNull("url") ?: "", urlBlock))
            else -> Json.of()
        }
    }

    fun auditSummary(name: String, args: Json.Obj?): Json.Obj {
        val a = args ?: return Json.of()
        return when (name) {
            "type_text" -> Json.of("chars" to (a.stringOrNull("text")?.length ?: 0))
            "open_url" -> {
                val u = a.stringOrNull("url") ?: return Json.of("host" to "?")
                val host = try {
                    val rest = u.substringAfter("://"); rest.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
                } catch (_: Exception) { "?" }
                Json.of("host" to host.take(80))
            }
            else -> Json.of(*a.map.entries.mapNotNull { (k, v) ->
                when {
                    v is Json.Num || v is Json.Bool -> k to v
                    k in setOf("package", "action") && v is Json.Str && v.v.length <= 200 -> k to v
                    else -> null
                }
            }.toTypedArray())
        }
    }
}
