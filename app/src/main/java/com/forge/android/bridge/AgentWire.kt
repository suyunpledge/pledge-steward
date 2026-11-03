package com.forge.android.bridge

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure helpers for what the engine sends to and accepts from the model. JVM-testable.
 */
object AgentWire {
    /**
     * OpenAI-compatible APIs (OpenAI, DeepSeek, most gateways) only accept function names
     * matching ^[a-zA-Z0-9_-]{1,64}$. Internal names keep their dots; the wire uses underscores.
     */
    fun wireName(name: String): String = name.replace('.', '_')

    /** Maps a model-returned name back to an internal tool; accepts either spelling. */
    fun resolveToolName(raw: String, known: Collection<String>): String {
        val name = raw.trim()
        if (name in known) return name
        return known.firstOrNull { wireName(it) == name } ?: name
    }

    /** Keys whose values are binary payloads: never sent to a text model. */
    private val BINARY_KEY = Regex("(?i)(^|_)(base64|png|jpeg|jpg|image_data|bytes)($|_)")
    private const val MAX_FIELD_CHARS = 64_000

    /**
     * The copy of a device receipt that goes into the model conversation. Pixels and other
     * binary payloads are replaced by a note with their size: base64 text is unreadable to
     * a text model, costs hundreds of thousands of tokens and breaks the prefix cache.
     */
    fun modelReceipt(receipt: JSONObject): JSONObject {
        val out = JSONObject()
        val keys = receipt.keys().asSequence().toList()
        for (key in keys) {
            val value = receipt.opt(key)
            when {
                value is String && BINARY_KEY.containsMatchIn(key) ->
                    out.put(key + "_omitted", "${value.length} chars of image data kept on the device, not sent to the model")
                value is JSONObject -> out.put(key, modelReceipt(value))
                value is JSONArray -> out.put(key, value)
                value is String && value.length > MAX_FIELD_CHARS ->
                    out.put(key, value.take(MAX_FIELD_CHARS)).put(key + "_truncated", true)
                else -> out.put(key, value)
            }
        }
        return out
    }

    /** Tools whose effect can change the device; a lost receipt means the outcome is unknown. */
    fun isWrite(tool: String) = tool.startsWith("android.ui.") || tool == "android.app.launch"

    /** State for a receipt that never arrived: reads did not happen, writes may have. */
    fun timeoutState(tool: String) = if (isWrite(tool)) "unknown" else "not_started"
}
