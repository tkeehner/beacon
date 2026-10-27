package com.tyler.primelauncher

import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent connector: how the home "message" box reaches YOUR agent. Beacon is agent-agnostic. A connector is just
 *   endpoint URL (https) + auth (header name, scheme, sealed key) + a payload template,
 * all user-configured in Settings -> Agent connector (or imported once via [HookImport]). Nothing about any
 * particular agent is hardcoded; the presets below are editable starting points.
 *
 * Template: a JSON document with placeholders that are replaced by JSON-encoded values (so write them UNQUOTED):
 *   {{text}}    the message (JSON string)        {{msg_id}}  random UUID (JSON string)
 *   {{ts}}      epoch millis (JSON number)       {{via}}     where it was typed, e.g. "beacon-home" (JSON string)
 * The rendered result must be a JSON object or array.
 */
object Connectors {
    data class Preset(val id: String, val label: String, val template: String, val note: String)

    const val GENERIC = "generic"; const val TEXT_ONLY = "text"; const val CHAT = "chat-messages"; const val GROK_EMIT = "grok-emit"; const val CUSTOM = "custom"
    const val VIA = "beacon-home"
    private val PLACEHOLDER = Regex("""\{\{(text|msg_id|ts|via)\}\}""")

    val presets: List<Preset> = listOf(
        Preset(GENERIC, "Generic JSON", """{"text":{{text}},"msg_id":{{msg_id}},"ts":{{ts}},"source":"beacon","via":{{via}}}""",
            "Works with most webhook-triggered agents and automation tools (n8n, Zapier, Make, custom servers)."),
        Preset(TEXT_ONLY, "Text only", """{"text":{{text}}}""",
            "Minimal body for chat-style incoming webhooks that only read a \"text\" field."),
        Preset(CHAT, "Chat messages", """{"messages":[{"role":"user","content":{{text}}}],"metadata":{"msg_id":{{msg_id}},"via":{{via}}}}""",
            "Role/content message list for agent gateways that take a chat-style request."),
        Preset(GROK_EMIT, "Grok Bot via Emit companion (example)",
            """{"source":"matrix-sms","type":"prime_chat","kind":"prime_chat","from_self":true,"msg_id":{{msg_id}},"text":{{text}},"ts":{{ts}},"reply_with":"prime-reply \"<text>\" via your companion","via":{{via}}}""",
            "Example: the body shape the Emit PC companion posts to a Grok Bot webhook routine."),
    )
    fun preset(id: String?): Preset? = presets.firstOrNull { it.id == id }
    val DEFAULT: Preset get() = presets.first()

    /** Fills the template. Throws IllegalArgumentException (never echoing the text) if the result isn't valid JSON. */
    fun render(template: String, text: String, msgId: String, ts: Long, via: String = VIA): String {
        // Single pass, so placeholder-looking text inside the message is never re-substituted.
        val values = mapOf("text" to JSONObject.quote(text), "msg_id" to JSONObject.quote(msgId), "ts" to ts.toString(), "via" to JSONObject.quote(via))
        val out = PLACEHOLDER.replace(template) { m -> values[m.groupValues[1]] ?: m.value }
        val t = out.trim()
        val ok = runCatching { if (t.startsWith("[")) JSONArray(t) else JSONObject(t); true }.getOrDefault(false)
        require(ok) { "Payload template must render to a JSON object or array" }
        return t
    }
    fun validTemplate(template: String): Boolean = template.contains("{{text}}") && runCatching { render(template, "x", "id", 0L) }.isSuccess

    /** The preset id whose template matches exactly, else [CUSTOM]. */
    fun idFor(template: String): String = presets.firstOrNull { it.template == template.trim() }?.id ?: CUSTOM
}
