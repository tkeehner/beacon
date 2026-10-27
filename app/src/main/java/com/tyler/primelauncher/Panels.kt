package com.tyler.primelauncher

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView

/** Drawer, search, settings and first-run screens for [HomeActivity]. */
object Panels {
    private var drawerList: LinearLayout? = null; private var drawerScroll: ScrollView? = null; private var drawerQ = ""
    private val sections = LinkedHashMap<String, View>()

    fun forget() { drawerList = null; drawerScroll = null; sections.clear() }

    // ---------- app drawer ----------
    fun drawer(h: HomeActivity) {
        val pal = h.pal
        val q = EditText(h).apply { hint = pal.up("Search apps"); setHintTextColor(pal.dim); setTextColor(pal.body); typeface = pal.font; textSize = 15f; setSingleLine(true)
            background = pal.panel(h, 14f); setPadding(h.dpi(14f), h.dpi(10f), h.dpi(14f), h.dpi(10f)); imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_GO) { filtered().firstOrNull()?.let { h.closePanel(); Apps.launch(h, it) }; true } else false } }
        q.addTextChangedListener(object : android.text.TextWatcher { override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { drawerQ = s?.toString().orEmpty(); renderDrawer(h); h.typedInSearch(q) }; override fun afterTextChanged(s: android.text.Editable?) {} })
        drawerQ = ""
        val list = LinearLayout(h).apply { orientation = LinearLayout.VERTICAL; setPadding(h.dpi(10f), h.dpi(6f), h.dpi(28f), h.dpi(40f)) }
        val sv = ScrollView(h).apply { addView(list); isVerticalScrollBarEnabled = false }
        var startY = 0f
        sv.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) startY = e.rawY
            if (e.actionMasked == MotionEvent.ACTION_UP && sv.scrollY == 0 && e.rawY - startY > h.dp(140f)) h.closePanel(); false }
        val rail = LinearLayout(h).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        for (c in "#ABCDEFGHIJKLMNOPQRSTUVWXYZ") rail.addView(h.text(pal, c.toString(), 10f, pal.dim, bold = true).apply { gravity = Gravity.CENTER; setPadding(h.dpi(6f), h.dpi(1.5f), h.dpi(6f), h.dpi(1.5f)) })
        rail.setOnTouchListener { v, e -> val i = ((e.y / v.height) * 27).toInt().coerceIn(0, 26); val key = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ"[i].toString()
            val target = sections.entries.firstOrNull { it.key >= key }?.value ?: sections.values.lastOrNull(); target?.let { sv.scrollTo(0, it.top) }; true }
        val top = FrameLayout(h); top.addView(q, FrameLayout.LayoutParams(MATCH, WRAP).apply { leftMargin = h.dpi(14f); rightMargin = h.dpi(14f) })
        val body = FrameLayout(h)
        body.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH)); body.addView(rail, FrameLayout.LayoutParams(WRAP, MATCH, Gravity.END))
        val panel = LinearLayout(h).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.argb(0xE6, Color.red(pal.bg), Color.green(pal.bg), Color.blue(pal.bg)))
            setPadding(0, h.dpiPub(h.stage.topInset + 14f), 0, h.dpiPub(h.stage.bottomInset)) }
        val gear = h.text(pal, pal.up("Beacon settings"), 12f, pal.p, bold = true).apply { setPadding(h.dpi(18f), h.dpi(12f), h.dpi(18f), h.dpi(4f)); setOnClickListener { settings(h) } }
        panel.addView(top, LinearLayout.LayoutParams(MATCH, WRAP)); panel.addView(gear, LinearLayout.LayoutParams(MATCH, WRAP)); panel.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))
        h.showPanel("drawer", panel); drawerList = list; drawerScroll = sv; renderDrawer(h)
    }
    private fun filtered(): List<AppEntry> = Apps.list.filter { drawerQ.isBlank() || it.label.contains(drawerQ.trim(), ignoreCase = true) }

    fun renderDrawer(h: HomeActivity) {
        val list = drawerList ?: return; list.removeAllViews(); sections.clear(); val pal = h.pal
        val apps = filtered(); val groups = apps.groupBy { val c = it.label.firstOrNull()?.uppercaseChar() ?: '#'; if (c in 'A'..'Z') c.toString() else "#" }.toSortedMap()
        if (apps.isEmpty()) list.addView(h.text(pal, pal.up("No apps match"), 13f, pal.dim).apply { setPadding(h.dpi(10f), h.dpi(20f), 0, 0) })
        for ((letter, items) in groups) {
            val head = h.text(pal, letter, 13f, pal.p, bold = true).apply { setPadding(h.dpi(8f), h.dpi(14f), 0, h.dpi(6f)) }
            list.addView(head); sections[letter] = head
            for (rowApps in items.chunked(4)) {
                val row = LinearLayout(h).apply { orientation = LinearLayout.HORIZONTAL }
                for (i in 0 until 4) row.addView(rowApps.getOrNull(i)?.let { appCell(h, it) } ?: View(h), LinearLayout.LayoutParams(0, WRAP, 1f))
                list.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            }
        }
    }
    fun appCell(h: HomeActivity, a: AppEntry, size: Float = 54f): View {
        val pal = h.pal; val ic = ImageView(h).apply { setImageBitmap(h.tile(a.pkg, a.label, size)) }
        val pin = a.pkg in h.prefs.pins
        val lbl = h.text(pal, (if (pin) "\u2022 " else "") + a.label, 11f, pal.body).apply { gravity = Gravity.CENTER; setPadding(h.dpi(2f), h.dpi(5f), h.dpi(2f), 0) }
        return LinearLayout(h).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, h.dpi(6f), 0, h.dpi(10f))
            addView(ic, LinearLayout.LayoutParams(h.dpi(size), h.dpi(size))); addView(lbl, LinearLayout.LayoutParams(MATCH, WRAP))
            setOnClickListener { h.closePanel(); Apps.launch(h, a) }
            setOnLongClickListener { menu(h, this, a); true } }
    }
    fun menu(h: HomeActivity, anchor: View, a: AppEntry) {
        val pm = PopupMenu(h, anchor); val pinned = a.pkg in h.prefs.pins
        pm.menu.add(0, 1, 0, if (pinned) "Unpin from ring" else "Pin to ring"); pm.menu.add(0, 2, 1, "App info"); pm.menu.add(0, 3, 2, "Uninstall")
        pm.setOnMenuItemClickListener { when (it.itemId) {
            1 -> { val p = h.prefs.pins.toMutableList(); if (pinned) p.remove(a.pkg) else if (p.size >= Logic.MAX_PINS) h.toast("Up to ${Logic.MAX_PINS} pins") else p.add(a.pkg); h.prefs.pins = p; h.stage.refreshRing(); renderDrawer(h) }
            2 -> Apps.appInfo(h, a)
            3 -> Apps.uninstall(h, a.pkg)
        }; true }
        pm.show()
    }

    // ---------- swipe-down search ----------
    private class Hit(val label: String, val sub: String, val pkg: String?, val run: () -> Unit)
    fun search(h: HomeActivity) {
        val pal = h.pal
        val q = EditText(h).apply { hint = pal.up("Search apps, settings, or ask Prime"); setHintTextColor(pal.dim); setTextColor(pal.body); typeface = pal.font; textSize = 16f; setSingleLine(true)
            background = pal.panel(h, 16f); setPadding(h.dpi(16f), h.dpi(12f), h.dpi(16f), h.dpi(12f)); imeOptions = EditorInfo.IME_ACTION_GO; inputType = InputType.TYPE_CLASS_TEXT }
        val res = LinearLayout(h).apply { orientation = LinearLayout.VERTICAL; setPadding(h.dpi(14f), h.dpi(10f), h.dpi(14f), h.dpi(20f)) }
        val sv = ScrollView(h).apply { addView(res); isVerticalScrollBarEnabled = false }
        var hits: List<Hit> = emptyList()
        fun render() {
            res.removeAllViews(); val s = q.text.toString().trim(); hits = results(h, s)
            for (x in hits) { val r = LinearLayout(h).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(h.dpi(6f), h.dpi(7f), h.dpi(6f), h.dpi(7f)); setOnClickListener { h.closePanel(); x.run() } }
                if (x.pkg != null) r.addView(ImageView(h).apply { setImageBitmap(h.tile(x.pkg, x.label, 36f)) }, LinearLayout.LayoutParams(h.dpi(36f), h.dpi(36f)))
                else r.addView(h.text(pal, "\u203A", 22f, pal.p, bold = true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(h.dpi(36f), h.dpi(36f)))
                r.addView(h.col(h.text(pal, x.label, 14f, pal.body, bold = true), h.text(pal, x.sub, 11f, pal.dim)).apply { setPadding(h.dpi(12f), 0, 0, 0) })
                res.addView(r, LinearLayout.LayoutParams(MATCH, WRAP)) }
            h.typedInSearch(q)
        }
        q.addTextChangedListener(object : android.text.TextWatcher { override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { render() }; override fun afterTextChanged(s: android.text.Editable?) {} })
        q.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_GO) { hits.firstOrNull()?.let { h.closePanel(); it.run() }; true } else false }
        val panel = LinearLayout(h).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.argb(0xD0, Color.red(pal.bg), Color.green(pal.bg), Color.blue(pal.bg)))
            setPadding(h.dpi(14f), h.dpiPub(h.stage.topInset + 12f), h.dpi(14f), 0) }
        panel.addView(q, LinearLayout.LayoutParams(MATCH, WRAP)); panel.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        h.showPanel("search", panel, fromBottom = false); render(); h.showKeyboard(q)
    }
    private fun results(h: HomeActivity, s: String): List<Hit> {
        val out = ArrayList<Hit>(); if (s.isEmpty()) { Apps.suggest(h, h.prefs.pins).take(8).forEach { a -> out.add(Hit(a.label, "app", a.pkg) { Apps.launch(h, a) }) }; return out }
        Apps.list.filter { it.label.contains(s, true) }.sortedBy { if (it.label.startsWith(s, true)) 0 else 1 }.take(12).forEach { a -> out.add(Hit(a.label, "app", a.pkg) { Apps.launch(h, a) }) }
        val sys = listOf("Wi-Fi" to Settings.ACTION_WIFI_SETTINGS, "Bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS, "Display" to Settings.ACTION_DISPLAY_SETTINGS, "Sound" to Settings.ACTION_SOUND_SETTINGS,
            "Battery" to Intent.ACTION_POWER_USAGE_SUMMARY, "Apps" to Settings.ACTION_APPLICATION_SETTINGS, "Default home app" to Settings.ACTION_HOME_SETTINGS, "Location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        sys.filter { it.first.contains(s, true) }.forEach { (l, act) -> out.add(Hit(l, "phone settings", null) { h.openSys(act) }) }
        listOf("Theme", "App icons", "Wallpaper", "Hey Prime", "Quick launch", "Weather", "Bubbles", "Reduce motion", "Battery saver", "Permissions").filter { it.contains(s, true) }
            .forEach { l -> out.add(Hit(l, "Beacon settings", null) { settings(h) }) }
        out.add(Hit("Message Prime: \u201c$s\u201d", "sends it straight to Prime", null) { h.sendPrime(s) })
        out.add(Hit("Search the web for \u201c$s\u201d", "web", null) { runCatching { h.startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, s).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } })
        return out
    }

    // ---------- settings ----------
    fun settings(h: HomeActivity) {
        val pal = h.pal; val p = h.prefs
        val box = LinearLayout(h).apply { orientation = LinearLayout.VERTICAL; setPadding(h.dpi(18f), h.dpi(14f), h.dpi(18f), h.dpi(30f)) }
        fun section(t: String) = box.addView(h.text(pal, pal.up(t), 12f, pal.p, bold = true).apply { setPadding(0, h.dpi(18f), 0, h.dpi(6f)) })
        fun seg(label: String, opts: List<Pair<String, String>>, cur: String, small: String? = null, pick: (String) -> Unit) {
            val r = LinearLayout(h).apply { orientation = LinearLayout.VERTICAL; setPadding(0, h.dpi(6f), 0, h.dpi(6f)) }
            r.addView(h.text(pal, label, 14f, pal.body, bold = true)); if (small != null) r.addView(h.text(pal, small, 11f, pal.dim, lines = 3).apply { setPadding(0, h.dpi(2f), 0, 0) })
            val chips = LinearLayout(h).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, h.dpi(6f), 0, 0) }
            for ((v, l) in opts) chips.addView(h.text(pal, pal.up(l), 12f, if (v == cur) pal.bg else pal.body, bold = v == cur).apply { gravity = Gravity.CENTER
                setPadding(h.dpi(8f), h.dpi(7f), h.dpi(8f), h.dpi(7f)); background = pal.chip(h, v == cur); setOnClickListener { pick(v); settings(h) } }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = h.dpi(6f) })
            r.addView(chips); box.addView(r)
        }
        fun onOff(label: String, cur: Boolean, small: String? = null, set: (Boolean) -> Unit) = seg(label, listOf("1" to "On", "0" to "Off"), if (cur) "1" else "0", small) { set(it == "1") }
        fun button(label: String, state: String, ok: Boolean, run: () -> Unit) {
            val r = LinearLayout(h).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, h.dpi(8f), 0, h.dpi(8f)); setOnClickListener { run() } }
            r.addView(h.text(pal, label, 14f, pal.body, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
            r.addView(h.text(pal, pal.up(state), 11f, if (ok) pal.bg else pal.p, bold = true).apply { setPadding(h.dpi(10f), h.dpi(5f), h.dpi(10f), h.dpi(5f)); background = pal.chip(h, ok) })
            box.addView(r)
        }
        section("Look")
        seg("Theme", listOf("matrix" to "Matrix", "neon" to "Neon City"), p.theme) { p.theme = it; h.applyLook() }
        seg("App icons", listOf("ABSTRACT" to "Abstract", "LETTERS" to "Letters"), p.icons.name, "Abstract: original motifs in this theme's colors. Letters: plain tiles.") { p.icons = IconStyle.valueOf(it); h.applyLook() }
        if (p.neon) onOff("Glitch", p.glitch) { p.glitch = it; h.applyLook() }
        section("Wallpaper (preview above)")
        seg("Style", listOf("auto" to "Auto", "glyphs" to "Rain", "streaks" to "Streaks", "solid" to "Solid", "off" to "Off"), p.wpStyle) { p.wpStyle = it; h.stage.rebuild() }
        seg("Glyphs", listOf("binary" to "0 1", "kana" to "Kana-ish", "hex" to "Hex"), p.wpGlyphs) { p.wpGlyphs = it; h.stage.rebuild() }
        seg("Density", listOf("0.6" to "Low", "1.0" to "Medium", "1.5" to "High"), "%.1f".format(java.util.Locale.US, p.wpDensity)) { p.wpDensity = it.toFloat(); h.stage.rebuild() }
        seg("Speed", listOf("0.5" to "Slow", "1.0" to "Normal", "1.8" to "Fast"), "%.1f".format(java.util.Locale.US, p.wpSpeed)) { p.wpSpeed = it.toFloat(); h.stage.rebuild() }
        seg("Color", listOf("-1" to "Theme", "0" to "Red", "40" to "Amber", "190" to "Cyan", "220" to "Blue", "280" to "Violet", "320" to "Pink"), p.wpHue.toString()) { p.wpHue = it.toInt(); h.stage.rebuild() }
        section("Home")
        onOff("Notification bubbles", p.bubbles, "New notifications pop out of the orb. Tap opens, swipe clears.") { p.bubbles = it }
        onOff("Flick the orb", p.quickOn, "Flick from the orb to quick-launch. Holding still opens the ring.") { p.quickOn = it }
        if (p.quickOn) for (dir in Quick.DIRS) button("Flick ${dir}", h.quickLabel(p.quick(dir)), false) { pickQuick(h, dir) }
        seg("Weather location", listOf("1" to "Where I am", "0" to "Default location"), if (p.wxGps) "1" else "0") { p.wxGps = it == "1"; if (it == "1" && !h.has(Manifest.permission.ACCESS_COARSE_LOCATION)) h.requestPerm(Manifest.permission.ACCESS_COARSE_LOCATION); p.wxCache = "" }
        section("Prime")
        onOff("Hey Prime", p.wake, "Offline wake word. Listens only while the screen is on and unlocked.") { p.wake = it; WakeService.sync(h) }
        onOff("Battery saver", p.saver, "Lighter orb, 30 fps, and Hey Prime listens only while the home screen is showing.") { p.saver = it; h.applyLook(); WakeService.sync(h) }
        onOff("Reduce motion", p.reduce, "Calmer animations, no particles.") { p.reduce = it; h.applyLook() }
        val hook = HookStore(h)
        button("Agent connector", hook.summary(), hook.configured) { primeHookDialog(h, hook) { settings(h) } }
        onOff("Sleeping orb on lock screen", p.lockOrb, "Experimental. Prime sleeps over the lock screen; any tap brings up your normal unlock. Try it once while you know your PIN.") { p.lockOrb = it }
        section("Permissions")
        permRows(h, ::button)
        section("Phone Link")
        phoneLinkRows(h, pal, box, ::button) { settings(h) }
        section("System")
        button("Default home app", "open", false) { h.openSys(Settings.ACTION_HOME_SETTINGS) }
        button("Restart onboarding", "show", false) { onboarding(h) }
        box.addView(h.text(pal, "Beacon 0.1.14 · open-source build (original abstract icons only).", 10f, pal.dim, lines = 4).apply { setPadding(0, h.dpi(18f), 0, 0) })
        val sv = ScrollView(h).apply { addView(box); isVerticalScrollBarEnabled = false; background = GradientDrawable().apply { setColor(Color.argb(0xF4, Color.red(pal.s1), Color.green(pal.s1), Color.blue(pal.s1))); cornerRadii = FloatArray(8) { if (it < 4) h.dp(22f) else 0f }; setStroke(h.dpi(1f), pal.line) } }
        val wasOpen = h.panelIs("settings"); val y = (h.currentPanel() as? ScrollView)?.scrollY ?: 0
        val lpv = FrameLayout.LayoutParams(MATCH, (h.stage.height * 0.62f).toInt(), Gravity.BOTTOM)
        if (wasOpen) h.replacePanel(sv, lpv) else h.showPanel("settings", sv, lpv)
        if (wasOpen) sv.post { sv.scrollTo(0, y) }
    }
    // ---------- Phone Link (off + unpaired until the owner pairs it) ----------
    private fun phoneLinkRows(h: HomeActivity, pal: Pal, box: LinearLayout, button: (String, String, Boolean, () -> Unit) -> Unit, refresh: () -> Unit) {
        val pl = com.tyler.primelauncher.phonelink.android.LinkStore(h)
        val svc = com.tyler.primelauncher.phonelink.android.LinkService
        val ctl = com.tyler.primelauncher.phonelink.android.ControlService.isEnabled(h)
        val status = when {
            !pl.configured -> "Not paired"
            !pl.enabled -> "Off"
            else -> svc.state
        } + " · Prime Control " + (if (ctl) "on" else "off")
        box.addView(h.text(pal, "Status: $status", 12f, pal.dim, lines = 2).apply { setPadding(0, h.dpi(4f), 0, h.dpi(4f)) })
        if (pl.configured && !ctl) box.addView(h.text(pal, "Prime Control is off (Samsung sometimes turns it off after updates). Open Accessibility below to turn it on.", 11f, pal.p, lines = 3))
        val on = pl.configured && pl.enabled
        button("Phone Link", if (on) "on" else "off", on) {
            if (!pl.configured) { h.toast("Pair Phone Link first"); return@button }
            if (on) { pl.enabled = false; svc.stop(h) } else { pl.enabled = true; svc.start(h) }
            box.postDelayed({ refresh() }, 400)
        }
        button(if (pl.configured) "Re-pair" else "Pair", "open", false) {
            runCatching { h.startActivity(com.tyler.primelauncher.phonelink.android.PairActivity.intent(h)) }.onFailure { h.toast("Couldn't open pairing") }
        }
        if (pl.configured || pl.hasToken) button("Unpair", "unpair", false) {
            AlertDialog.Builder(h).setTitle("Unpair Phone Link?").setMessage("Disconnects and erases the link keys and MCP token on this phone.")
                .setPositiveButton("Unpair") { _, _ -> svc.stop(h); pl.clear(); refresh() }.setNegativeButton("Cancel", null).show()
        }
        fun showToken(t: String) {
            AlertDialog.Builder(h).setTitle("MCP token").setMessage("Paste this into your agent's MCP connector settings yourself. Never send it in chat.\n\n$t")
                .setPositiveButton("Copy") { _, _ ->
                    val cm = h.getSystemService(android.content.ClipboardManager::class.java)
                    val clip = android.content.ClipData.newPlainText("MCP token", t)
                    clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                    cm?.setPrimaryClip(clip); h.toast("Token copied")
                }.setNegativeButton("Close", null).show()
        }
        if (pl.hasToken) button("MCP token", "reveal", false) {
            runCatching { pl.loadToken() }.getOrNull()?.let { showToken(it) } ?: h.toast("Couldn't read token")
        }
        button("Rotate MCP token", if (pl.hasToken) "rotate" else "create", false) {
            AlertDialog.Builder(h).setTitle("New MCP token?").setMessage("The old token stops working immediately. You'll need to paste the new one into your agent's MCP connector settings.")
                .setPositiveButton("Rotate") { _, _ -> runCatching { pl.rotateToken() }.onSuccess { showToken(it); refresh() }.onFailure { h.toast("Rotate failed") } }
                .setNegativeButton("Cancel", null).show()
        }
        button("Prime Control (Accessibility)", if (ctl) "on" else "open", ctl) { h.openSys(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
        button("Audit log", "view", false) {
            val lines = pl.auditLines()
            AlertDialog.Builder(h).setTitle("Phone Link audit log").setMessage(if (lines.isEmpty()) "No entries yet." else lines.take(200).joinToString("\n"))
                .setPositiveButton("Close", null)
                .setNeutralButton("Clear") { _, _ -> pl.clearAudit(); h.toast("Audit log cleared") }.show()
        }
    }
    /** Agent connector (any agent with an HTTPS webhook): URL, auth and payload template. The key is write-only: never shown back. */
    private fun primeHookDialog(h: HomeActivity, hook: HookStore, refresh: () -> Unit) {
        val pal = h.pal
        fun field(hint: String, v: String, secret: Boolean = false) = EditText(h).apply { this.hint = hint; setText(v); setSingleLine(true); textSize = 14f
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            if (secret) importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO }
        val c = hook.cfg()
        val url = field("https://… your agent's webhook URL", hook.url); val key = field(if (hook.configured) "key (leave blank to keep)" else "webhook key", "", secret = true)
        val header = field("auth header (Authorization)", c?.header ?: "Authorization"); val scheme = field("scheme (Bearer; blank = raw key)", c?.scheme ?: "Bearer")
        val tpl = EditText(h).apply { hint = "payload template (JSON with {{text}})"; setText(hook.template); textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS; minLines = 2; maxLines = 6 }
        val labels = Connectors.presets.map { it.label } + "Custom"
        val pick = android.widget.Spinner(h).apply {
            adapter = android.widget.ArrayAdapter(h, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(Connectors.presets.indexOfFirst { it.id == hook.connectorId }.let { if (it < 0) labels.size - 1 else it })
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) { Connectors.presets.getOrNull(pos)?.let { if (tpl.text.toString().trim() != it.template) tpl.setText(it.template) } }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            } }
        val col = LinearLayout(h).apply { orientation = LinearLayout.VERTICAL; setPadding(h.dpi(20f), h.dpi(8f), h.dpi(20f), 0)
            addView(h.text(pal, "Works with any agent that accepts an HTTPS webhook. Pick a payload preset or write your own template: {{text}}, {{msg_id}}, {{ts}}, {{via}}. The key is sealed on this phone and never shown again.", 12f, pal.dim, lines = 6))
            addView(pick); addView(url); addView(key); addView(header); addView(scheme); addView(tpl) }
        val d = AlertDialog.Builder(h).setTitle("Agent connector").setView(android.widget.ScrollView(h).apply { addView(col) })
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null)
        if (hook.configured) d.setNeutralButton("Clear") { _, _ -> hook.clear(); h.toast("Agent connector cleared"); refresh() }
        val dlg = d.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching { hook.save(url.text.toString(), key.text.toString().trim().ifEmpty { null }, header.text.toString(), scheme.text.toString(), tpl.text.toString()) }
                .onSuccess { key.setText(""); dlg.dismiss(); h.toast("Agent connector saved"); refresh() }
                .onFailure { h.toast(if (it is IllegalArgumentException) it.message ?: "Check the fields" else "Couldn't save") }
        }
    }
    private fun pickQuick(h: HomeActivity, dir: String) {
        val fixed = listOf(Quick.CAMERA, Quick.TORCH, Quick.PRIME, Quick.DRAWER, Quick.SEARCH, Quick.NONE)
        val all = fixed + Apps.list.map { "app:" + it.pkg }.distinct()
        AlertDialog.Builder(h).setTitle("Flick $dir").setItems(all.map { h.quickLabel(it) }.toTypedArray()) { _, i -> h.prefs.setQuick(dir, all[i]); settings(h) }.show()
    }
    private fun permRows(h: HomeActivity, button: (String, String, Boolean, () -> Unit) -> Unit) {
        val usage = Apps.hasUsageAccess(h); button("Usage access (ring = most-used apps)", if (usage) "on" else "grant", usage) { Apps.usageSettings(h) }
        val nl = NotifStore.hasAccess(h); button("Notification access (bubbles)", if (nl) "on" else "grant", nl) { h.openSys(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
        val mic = h.has(Manifest.permission.RECORD_AUDIO); button("Microphone (Hey Prime)", if (mic) "on" else "grant", mic) { if (!mic) h.requestPerm(Manifest.permission.RECORD_AUDIO) else h.openSys(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }
        val post = h.has(Manifest.permission.POST_NOTIFICATIONS); button("Notifications (listening notice)", if (post) "on" else "grant", post) { h.requestPerm(Manifest.permission.POST_NOTIFICATIONS) }
        val loc = h.has(Manifest.permission.ACCESS_COARSE_LOCATION); button("Location (weather)", if (loc) "on" else "grant", loc) { h.requestPerm(Manifest.permission.ACCESS_COARSE_LOCATION) }
        val ov = Settings.canDrawOverlays(h); button("Display over other apps (optional)", if (ov) "on" else "grant", ov) { h.openSys(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true) }
        val hu = HangupService.enabled(h); button("Hang-up button (Accessibility, Grok Bot only)", if (hu) "on" else "grant", hu) { h.openSys(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
    }

    // ---------- first run ----------
    fun onboarding(h: HomeActivity) {
        val pal = h.pal
        val box = LinearLayout(h).apply { orientation = LinearLayout.VERTICAL; setPadding(h.dpi(22f), h.dpiPub(h.stage.topInset + 30f), h.dpi(22f), h.dpi(30f)) }
        box.addView(h.text(pal, pal.up("Welcome to Beacon"), 22f, pal.hi, bold = true, lines = 2))
        box.addView(h.text(pal, "A few switches make Prime work. Each opens the right Android screen; come back here after.", 13f, pal.body, lines = 4).apply { setPadding(0, h.dpi(8f), 0, h.dpi(12f)) })
        fun step(label: String, why: String, ok: Boolean, run: () -> Unit) {
            val r = LinearLayout(h).apply { orientation = LinearLayout.VERTICAL; setPadding(h.dpi(14f), h.dpi(12f), h.dpi(14f), h.dpi(12f)); background = pal.panel(h, 14f); setOnClickListener { run() } }
            r.addView(h.row(h.text(pal, (if (ok) "\u2713 " else "\u25CB ") + label, 15f, if (ok) pal.p else pal.body, bold = true)))
            r.addView(h.text(pal, why, 11f, pal.dim, lines = 3).apply { setPadding(0, h.dpi(4f), 0, 0) })
            box.addView(r, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = h.dpi(10f) })
        }
        step("Usage access", "Lets the ring show your most-used apps. Settings > Usage data access > Beacon > Allow.", Apps.hasUsageAccess(h)) { Apps.usageSettings(h) }
        step("Notification access", "Lets notifications pop out of the orb. Allow Beacon in the list.", NotifStore.hasAccess(h)) { h.openSys(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
        step("Microphone", "For the offline \u201cHey Prime\u201d wake word. Choose \u201cWhile using the app\u201d.", h.has(Manifest.permission.RECORD_AUDIO)) { h.requestPerm(Manifest.permission.RECORD_AUDIO) }
        step("Notifications", "Android shows a small notice while the wake word is on.", h.has(Manifest.permission.POST_NOTIFICATIONS)) { h.requestPerm(Manifest.permission.POST_NOTIFICATIONS) }
        step("Location", "Weather where you are. Without it, the bar shows a default location.", h.has(Manifest.permission.ACCESS_COARSE_LOCATION)) { h.requestPerm(Manifest.permission.ACCESS_COARSE_LOCATION) }
        step("Display over other apps (optional)", "Lets \u201cHey Prime\u201d open Grok Bot while another app is in front. Without it you get a tap-to-talk alert.", Settings.canDrawOverlays(h)) { h.openSys(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true) }
        step("Hang-up button (optional)", "Lets the red button end your Grok Bot call. Accessibility > Installed apps > Prime hang-up button > On. It only sees Grok Bot and only taps its End call button.", HangupService.enabled(h)) { h.openSys(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
        step("Make it your Home app", "Settings > Apps > Default apps > Home app > Beacon. Switch back the same way.", h.isDefaultHome()) { h.openSys(Settings.ACTION_HOME_SETTINGS) }
        box.addView(h.text(pal, pal.up("Done"), 15f, pal.bg, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, h.dpi(14f), 0, h.dpi(14f)); background = pal.chip(h, true); setOnClickListener { h.prefs.onboarded = true; h.closePanel() } },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = h.dpi(8f) })
        val sv = ScrollView(h).apply { addView(box); setBackgroundColor(Color.argb(0xEE, Color.red(pal.bg), Color.green(pal.bg), Color.blue(pal.bg))) }
        if (h.panelIs("onboard")) h.replacePanel(sv, FrameLayout.LayoutParams(MATCH, MATCH)) else h.showPanel("onboard", sv)
    }
    @Suppress("unused") private fun tv(v: View) = v as? TextView
    @Suppress("unused") private fun uri(s: String) = Uri.parse(s)
}
