package com.forge.android.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * MCP 连接器的 HTTP 传输客户端（streamable HTTP + SSE 应答解析）。
 * stdio 连接器（command+env）在 Android 应用沙箱内不可执行——装载时只认
 * url 型连接器；stdio 型在目录里保持"已配置"状态但不进 chat 工具面。
 */
object McpClient {
    data class Connector(val name: String, val url: String)

    private val sessionIds = ConcurrentHashMap<String, String>()
    private var nextId = 1

    fun loadConnectors(file: File): List<Connector> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONObject(file.readText()).optJSONArray("connectors") ?: return emptyList()
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .filter { it.optBoolean("enabled", true) }
            .mapNotNull { c ->
                val u = c.optString("url", "").trim().trimEnd('/')
                if (u.startsWith("http://") || u.startsWith("https://"))
                    Connector(c.optString("name"), u) else null
            }
    }.getOrDefault(emptyList())

    fun buildRequest(id: Int, method: String, params: JSONObject?): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method)
            .apply { params?.let { put("params", it) } }

    /** JSON 或 SSE 应答体 → 含目标 id 的最后一个 JSON 对象。 */
    fun parseResponse(body: String, id: Int): JSONObject? {
        val trimmed = body.trimStart()
        val candidates: List<String> = if (trimmed.startsWith("{")) listOf(body)
        else body.lineSequence().filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .filter { it.startsWith("{") }.toList()
        return candidates.asReversed()
            .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .firstOrNull { it.opt("id") == id }
    }

    /** MCP 工具描述 → 模型工具信封；内部名 mcp.<连接器>.<工具>（wire 侧自动下划线化）。 */
    fun envelope(connector: String, tool: JSONObject): JSONObject? {
        val rawName = tool.optString("name").trim()
        if (rawName.isEmpty() || !rawName.matches(Regex("[A-Za-z0-9._-]{1,64}"))) return null
        val params = tool.optJSONObject("inputSchema")
            ?: JSONObject().put("type", "object").put("properties", JSONObject())
        return JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", "mcp.$connector.$rawName")
                .put("description", tool.optString("description", "MCP tool $rawName from connector $connector").take(400))
                .put("parameters", params))
    }

    private fun post(client: okhttp3.OkHttpClient, url: String, payload: JSONObject): Pair<String, String?> {
        val body = payload.toString()
            .toRequestBody("application/json".toMediaType())
        val builder = okhttp3.Request.Builder().url(url).post(body)
            .header("Accept", "application/json, text/event-stream")
        sessionIds[url]?.let { builder.header("Mcp-Session-Id", it) }
        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("MCP HTTP ${resp.code}: ${text.take(200)}")
            return Pair(text, resp.header("Mcp-Session-Id"))
        }
    }

    private suspend fun rpc(client: okhttp3.OkHttpClient, url: String, method: String, params: JSONObject?): JSONObject =
        withContext(Dispatchers.IO) {
            val id = nextId++
            val payload = buildRequest(id, method, params)
            val (text, sid) = post(client, url, payload)
            sid?.let { sessionIds[url] = it }
            parseResponse(text, id)?.optJSONObject("result")
                ?: throw IllegalStateException("MCP $method no result for $url")
        }

    suspend fun initialize(client: okhttp3.OkHttpClient, url: String) {
        rpc(client, url, "initialize", JSONObject()
            .put("protocolVersion", "2025-03-26")
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "steward-chat").put("version", "1.0")))
        // notifications/initialized（无 id，应答忽略）
        runCatching {
            post(client, url, JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"))
        }
    }

    suspend fun listTools(client: okhttp3.OkHttpClient, url: String): JSONArray {
        runCatching { initialize(client, url) }  // 幂等：会话已建则忽略失败
        return rpc(client, url, "tools/list", null)?.optJSONArray("tools") ?: JSONArray()
    }

    suspend fun callTool(client: okhttp3.OkHttpClient, url: String, tool: String, args: JSONObject): JSONObject =
        rpc(client, url, "tools/call", JSONObject().put("name", tool).put("arguments", args))

    fun client(): okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS).build()
}
