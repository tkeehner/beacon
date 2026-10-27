package com.tyler.primelauncher.phonelink.android

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Pairing + MCP token management.
 *
 * 1) Paste agent-client.json from your relay server installer (agent channel key). Sealed in Keystore.
 * 2) The phone GENERATES the MCP bearer token. Show it once for the owner to paste into your agent's
 *    connector form (never into chat). The relay never stores the token or a hash of it.
 * 3) Rotate invalidates the old token immediately.
 */
class PairActivity : Activity() {
    private var revealedToken: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = LinkStore(this)
        val tip = TextView(this).apply {
            text = "1) Paste agent-client.json from your relay server installer (pairs the phone↔relay channel).\n" +
                "2) The MCP token is created on this phone — the relay never has it. Paste it into your agent's MCP connector settings yourself.\n" +
                "3) Rotate if you ever worry the token was seen on the relay server."
            setPadding(32, 32, 32, 16)
        }
        val box = EditText(this).apply {
            hint = "{\"host\":\"YOUR_RELAY_HOST\",\"port\":443,\"fingerprint\":\"…\",\"agent_key\":\"…\"}"
            minLines = 5; setTextIsSelectable(true)
            intent?.data?.getQueryParameter("data")?.let { setText(it) }
            if (intent?.hasExtra("json") == true) setText(intent.getStringExtra("json"))
        }
        val status = TextView(this).apply {
            text = statusText(store); setPadding(32, 16, 32, 16)
        }
        val tokenView = TextView(this).apply {
            text = if (store.hasToken) "MCP token: •••••••••• (tap Reveal once / Rotate to mint a new one)" else "MCP token: not created yet"
            setPadding(32, 8, 32, 8); setTextIsSelectable(true)
        }
        val save = Button(this).apply {
            text = "Seal agent key & enable"
            setOnClickListener {
                try {
                    store.importConfig(box.text.toString())
                    box.text.clear()
                    revealedToken = store.loadToken()  // importConfig generates one if missing
                    tokenView.text = "MCP token (copy into your agent's MCP connector NOW — shown once):\n$revealedToken"
                    status.text = statusText(store)
                    Toast.makeText(this@PairActivity, "Paired. Copy the MCP token into the connector.", Toast.LENGTH_LONG).show()
                    LinkService.start(this@PairActivity)
                } catch (e: Exception) {
                    Toast.makeText(this@PairActivity, "Bad config: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        val reveal = Button(this).apply {
            text = "Reveal token once"
            setOnClickListener {
                val t = store.loadToken()
                if (t == null) { Toast.makeText(this@PairActivity, "No token — pair or rotate first", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                revealedToken = t
                tokenView.text = "MCP token (copy into your agent's MCP connector):\n$t"
            }
        }
        val copy = Button(this).apply {
            text = "Copy revealed token"
            setOnClickListener {
                val t = revealedToken
                if (t == null) { Toast.makeText(this@PairActivity, "Reveal or rotate first", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("phone-link-token", t))
                Toast.makeText(this@PairActivity, "Copied. Paste into the connector form, then clear clipboard.", Toast.LENGTH_LONG).show()
            }
        }
        val rotate = Button(this).apply {
            text = "Rotate MCP token (old dies now)"
            setOnClickListener {
                val t = store.rotateToken()
                revealedToken = t
                tokenView.text = "NEW MCP token (old is invalid — paste into connector NOW):\n$t"
                status.text = statusText(store)
                Toast.makeText(this@PairActivity, "Rotated. Update your agent's MCP connector.", Toast.LENGTH_LONG).show()
            }
        }
        val clear = Button(this).apply {
            text = "Unpair (wipe keys)"
            setOnClickListener {
                LinkService.stop(this@PairActivity); store.clear(); revealedToken = null
                status.text = statusText(store); tokenView.text = "MCP token: not created yet"
                Toast.makeText(this@PairActivity, "Unpaired", Toast.LENGTH_SHORT).show()
            }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tip); addView(box); addView(status); addView(tokenView)
            addView(save); addView(reveal); addView(copy); addView(rotate); addView(clear)
        }
        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun statusText(store: LinkStore) = buildString {
        if (store.configured) append("Agent channel: paired with ${store.host}:${store.port}.\n")
        else append("Agent channel: not paired.\n")
        append(if (store.hasToken) "MCP token: present (phone is the authority; the relay has no copy)." else "MCP token: missing.")
    }

    companion object {
        fun intent(ctx: android.content.Context, json: String? = null) =
            Intent(ctx, PairActivity::class.java).apply { if (json != null) putExtra("json", json) }
    }
}
