package com.forge.android.bridge

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Android-native replacement for bridge/*.py's FastAPI surface.
 *
 * It deliberately uses only the JDK socket APIs so the APK does not need a
 * second HTTP runtime.  The loopback server is authenticated with the same
 * installation token as the old bridge and forwards agent messages to the
 * in-process NativeAgentEngine.
 */
class NativeBridgeServer(private val context: Context) {
    private val token = BridgeToken.get(context)
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()
    private val clients = CopyOnWriteArraySet<WsClient>()
    private val capabilities = Collections.synchronizedSet(linkedSetOf<String>())
    private var approval = false
    private var shizuku = "offline"
    private var server: ServerSocket? = null
    @Volatile private var agentSender: ((String) -> Boolean)? = null
    @Volatile private var agentReady = false
    private val home = File(context.filesDir, "forge-home").apply { mkdirs() }
    private val onboardingSkip = File(home, ".onboarding_skipped")
    private val patchFile = File(home, "forge.patch.json")
    private val connectorsFile = File(home, "mcp_connectors.json")
    private val remoteFile = File(home, "remote_pair.json")

    fun start() {
        if (!running.compareAndSet(false, true)) return
        pool.execute {
            try {
                server = ServerSocket(8000, 32, InetAddress.getByName("127.0.0.1"))
                while (running.get()) {
                    val socket = server!!.accept()
                    pool.execute { handle(socket) }
                }
            } catch (_: SocketException) {
                // Expected when stop() closes the listening socket.
            } catch (t: Throwable) {
                Log.e(TAG, "native bridge stopped", t)
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        clients.toList().forEach { it.close() }
        clients.clear()
        runCatching { server?.close() }
        server = null
        agentSender = null
        agentReady = false
        pool.shutdownNow()
    }

    fun attachAgent(send: (String) -> Boolean) { agentSender = send }

    fun onAgentLine(line: String) {
        val obj = runCatching { JSONObject(line) }.getOrNull() ?: return
        if (obj.optString("type") == "agent_ready") {
            agentReady = true
            agentSender?.invoke(JSONObject().put("type", "capabilities")
                .put("tools", JSONArray(capabilities.toList())).put("approval", approval).toString())
        }
        broadcast(obj.toString())
    }

    fun onAgentStopped() {
        agentReady = false
        broadcast(JSONObject().put("type", "agent_unavailable")
            .put("error", "Native Agent stopped").toString())
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 45_000
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val request = readRequest(input) ?: return
            if (request.headers["upgrade"]?.equals("websocket", true) == true && request.path == "/agent/ws") {
                if (!authorized(request.headers["authorization"])) {
                    writeJson(output, 401, JSONObject().put("detail", "unauthorized")); return
                }
                handleWebSocket(socket, input, output, request)
                return
            }
            if (request.path != "/health" && !authorized(request.headers["authorization"])) {
                writeJson(output, 401, JSONObject().put("detail", "unauthorized")); return
            }
            val result = route(request)
            writeJson(output, result.first, result.second)
        } catch (_: Throwable) {
            // Client disconnects and malformed requests are isolated to one socket.
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun handleWebSocket(socket: Socket, input: BufferedInputStream,
                                output: BufferedOutputStream, request: HttpRequest) {
        val key = request.headers["sec-websocket-key"] ?: return
        val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
            .digest((key + WS_GUID).toByteArray(StandardCharsets.ISO_8859_1)))
        output.write(("HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n")
            .toByteArray(StandardCharsets.ISO_8859_1))
        output.flush()
        val client = WsClient(output)
        clients.add(client)
        val session = query(request.query, "session") ?: "default"
        client.send(JSONObject().put("type", "ready").put("session", session)
            .put("tools", JSONArray(capabilities.toList())).toString())
        try {
            while (running.get()) {
                val frame = readFrame(input) ?: break
                when (frame.first) {
                    1 -> handleClientMessage(frame.second)
                    8 -> break
                    9 -> client.sendFrame(10, frame.second)
                }
            }
        } finally {
            // A closed app socket is cooperative cancellation for the active
            // Agent run; never leave a device call running headlessly.
            agentSender?.invoke(JSONObject().put("type", "cancel").toString())
            clients.remove(client)
            client.close()
        }
    }

    private fun handleClientMessage(text: String) {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        val kind = obj.optString("type")
        when (kind) {
            "run", "cancel", "cancel_tools", "reset", "ping" -> {
                if (agentSender?.invoke(obj.toString()) != true) {
                    broadcast(JSONObject().put("type", "error").put("error", "Native Agent unavailable").toString())
                }
            }
            "refresh_tools" -> broadcast(JSONObject().put("type", "capabilities")
                .put("tools", JSONArray(capabilities.toList())).toString())
            else -> broadcast(JSONObject().put("type", "error").put("error", "unknown type").toString())
        }
    }

    private fun broadcast(text: String) { clients.toList().forEach { runCatching { it.send(text) } } }

    private fun route(request: HttpRequest): Pair<Int, JSONObject> {
        val body = request.body.takeIf { it.isNotEmpty() }?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() }
        return when {
            request.path == "/health" && request.method == "GET" -> 200 to JSONObject()
            .put("status", if (agentReady) "ok" else "starting").put("native", true).put("agent_ready", agentReady)
                .put("shizuku_tools", JSONArray(capabilities.filter { it in setOf("android.screen.capture", "android.app.list", "android.app.launch") }))
            request.path == "/capabilities" && request.method == "POST" -> {
                capabilities.clear(); body?.optJSONArray("tools")?.let { a -> for (i in 0 until a.length()) capabilities.add(a.optString(i)) }
                approval = body?.optBoolean("approval", false) ?: false
                shizuku = body?.optString("shizuku", "offline") ?: "offline"
                agentSender?.invoke(JSONObject().put("type", "capabilities")
                    .put("tools", JSONArray(capabilities.toList())).put("approval", approval).toString())
                200 to JSONObject().put("registered", JSONArray(capabilities.toList())).put("shizuku", shizuku)
            }
            request.path == "/tool/call" && request.method == "POST" -> {
                val callId = body?.optString("call_id", "") ?: ""
                val state = body?.optString("execution_state", "") ?: ""
                val okValue = body?.opt("ok")
                if (callId.isBlank() || okValue !is Boolean || (state.isNotBlank() && state !in setOf("completed", "not_started", "unknown")))
                    422 to JSONObject().put("detail", "invalid tool receipt")
                else { agentSender?.invoke(body!!.put("type", "tool_result").toString()); 200 to JSONObject().put("accepted", true) }
            }
            request.path == "/onboarding/status" && request.method == "GET" -> 200 to onboardingStatus()
            request.path == "/onboarding/vendors" && request.method == "GET" -> 200 to JSONObject().put("vendors", vendors())
            request.path == "/onboarding/skip" && request.method == "POST" -> { onboardingSkip.parentFile?.mkdirs(); onboardingSkip.writeText("skipped"); 200 to JSONObject().put("ok", true) }
            request.path == "/onboarding/apply" && request.method == "POST" -> 200 to applyOnboarding(body)
            request.path == "/assistant/catalog" && request.method == "GET" -> 200 to catalog()
            request.path == "/assistant/catalog/install" && request.method == "POST" -> 200 to installCatalog(body)
            request.path == "/assistant/connectors" && request.method == "GET" -> 200 to JSONObject().put("connectors", loadConnectors())
            request.path == "/assistant/connectors" && request.method == "POST" -> 200 to upsertConnector(body)
            request.path == "/assistant/connectors/delete" && request.method == "POST" -> 200 to deleteConnector(body)
            request.path == "/assistant/connectors/ensure" && request.method == "POST" -> 200 to JSONObject().put("ok", true)
            request.path == "/assistant/ask" && request.method == "POST" -> {
                val task = body?.optString("task", "") ?: ""
                if (task.isBlank() || task.length > 32000) 422 to JSONObject().put("detail", "invalid task")
                else { agentSender?.invoke((body ?: JSONObject()).put("type", "run").put("session", "assistant-default").toString()); 202 to JSONObject().put("accepted", true) }
            }
            request.path == "/remote/status" && request.method == "GET" -> 200 to remoteStatus()
            request.path == "/remote/pair" && request.method == "POST" -> 200 to pairRemote(body)
            request.path == "/remote/unpair" && request.method == "POST" -> 200 to unpairRemote()
            request.path == "/remote/mode" && request.method == "POST" -> 200 to modeRemote(body)
            request.path == "/remote/approve" && request.method == "POST" -> 200 to JSONObject().put("ok", true)
            request.path == "/agent/run" && request.method == "POST" -> {
                val task = body?.optString("task", "") ?: ""
                if (task.isBlank() || task.length > 32000) 422 to JSONObject().put("detail", "invalid task")
                else { agentSender?.invoke((body ?: JSONObject()).put("type", "run").put("session", "http").toString()); 202 to JSONObject().put("accepted", true) }
            }
            else -> 404 to JSONObject().put("detail", "not found")
        }
    }

    private fun onboardingStatus(): JSONObject {
        val has = runCatching { JSONObject(patchFile.readText()).optJSONArray("rows") }
            .getOrNull()?.let { a -> (0 until a.length()).any { a.optJSONObject(it)?.optJSONObject("config")?.optString("apiKey").orEmpty().isNotBlank() } } ?: false
        return JSONObject().put("needed", !onboardingSkip.exists() && !has).put("has_provider", has).put("skipped", onboardingSkip.exists())
    }

    private fun applyOnboarding(body: JSONObject?): JSONObject {
        val id = body?.optString("vendor", "") ?: ""
        val v = vendorList().firstOrNull { it.optString("id") == id } ?: return JSONObject().put("ok", false).put("error", "unknown vendor")
        val key = body?.optString("api_key", "")?.trim().orEmpty()
        if (!v.optBoolean("keyless") && key.isBlank()) return JSONObject().put("ok", false).put("error", "empty api key")
        val data = runCatching { JSONObject(if (patchFile.exists()) patchFile.readText() else "{}") }.getOrDefault(JSONObject())
        val rows = data.optJSONArray("rows") ?: JSONArray().also { data.put("rows", it) }
        val config = JSONObject().put("wire", "openai").put("baseURL", v.optString("baseURL"))
            .put("apiKey", key).put("model", JSONObject().put("primary", v.optJSONArray("models")?.optString(0)).put("options", v.optJSONArray("models")))
        var replaced = false
        for (i in 0 until rows.length()) if (rows.optJSONObject(i)?.optString("id") == id) { rows.put(i, JSONObject().put("id", id).put("name", "provider:$id").put("config", config)); replaced = true }
        if (!replaced) rows.put(JSONObject().put("id", id).put("name", "provider:$id").put("config", config))
        patchFile.parentFile?.mkdirs(); patchFile.writeText(data.toString(1)); onboardingSkip.writeText("done")
        return JSONObject().put("ok", true).put("vendor", v.optString("name")).put("model", v.optJSONArray("models")?.optString(0))
    }

    private fun vendors(): JSONArray = JSONArray(vendorList().map {
        JSONObject(it.toString()).apply { remove("baseURL"); remove("wire") }
    })

    private fun vendorList(): List<JSONObject> = listOf(
        JSONObject().put("id", "deepseek").put("name", "DeepSeek").put("icon", "🐳").put("desc", "性价比最高，国内直连").put("baseURL", "https://api.deepseek.com").put("models", JSONArray(listOf("deepseek-chat", "deepseek-reasoner"))).put("console", "https://platform.deepseek.com/api_keys"),
        JSONObject().put("id", "zhipu").put("name", "智谱 GLM").put("icon", "🧠").put("desc", "GLM 系列，国内直连").put("baseURL", "https://open.bigmodel.cn/api/paas/v4").put("models", JSONArray(listOf("glm-4.7", "glm-4.6-air"))).put("console", "https://open.bigmodel.cn/usercenter/apikeys"),
        JSONObject().put("id", "moonshot").put("name", "Kimi（月之暗面）").put("icon", "🌙").put("desc", "kimi 系列模型").put("baseURL", "https://api.moonshot.cn/v1").put("models", JSONArray(listOf("kimi-k2", "kimi-k2-turbo"))).put("console", "https://platform.moonshot.cn/console/api-keys"),
        JSONObject().put("id", "openai").put("name", "OpenAI").put("icon", "🤖").put("desc", "GPT 系列").put("baseURL", "https://api.openai.com/v1").put("models", JSONArray(listOf("gpt-5-mini", "gpt-5.2"))).put("console", "https://platform.openai.com/api-keys"),
        JSONObject().put("id", "ollama").put("name", "Ollama 本地").put("icon", "🦙").put("desc", "本地模型，无需 key").put("baseURL", "http://127.0.0.1:8101/v1").put("models", JSONArray(listOf("qwen3:8b"))).put("console", "").put("keyless", true)
    ).map { it.put("keyless", it.optBoolean("keyless", false)) }

    private fun catalog(): JSONObject {
        val all = runCatching {
            context.assets.open("connector_catalog.json").bufferedReader().use { JSONArray(it.readText()) }
        }.getOrDefault(JSONArray())
        val pinned = JSONArray()
        val groups = linkedMapOf<String, JSONArray>()
        for (i in 0 until all.length()) {
            val item = all.optJSONObject(i) ?: continue
            if (item.optBoolean("pinned")) pinned.put(item)
            val scene = item.optString("scene", "other")
            groups.getOrPut(scene) { JSONArray() }.put(item)
        }
        val labels = mapOf("life" to "生活日常", "work" to "办公协作", "dev" to "开发运维", "global" to "通用服务", "pro" to "专业服务", "other" to "其他")
        val scenes = JSONArray()
        groups.forEach { (key, items) -> scenes.put(JSONObject().put("key", key)
            .put("label", labels[key] ?: key).put("desc", "可选连接器").put("items", items)) }
        return JSONObject().put("pinned", pinned).put("scenes", scenes).put("catalog", all)
    }

    private fun installCatalog(body: JSONObject?): JSONObject {
        val id = body?.optString("id", "") ?: ""
        val item = runCatching { catalog().getJSONArray("catalog") }.getOrNull()?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("id") == id } }
            ?: return JSONObject().put("ok", false).put("error", "unknown catalog id")
        val auth = item.optJSONObject("auth") ?: JSONObject().put("kind", "none")
        if (auth.optString("kind") != "none") {
            val credentials = body?.optJSONObject("credentials") ?: JSONObject()
            val fields = auth.optJSONArray("fields") ?: JSONArray()
            for (i in 0 until fields.length()) {
                val env = fields.optJSONObject(i)?.optString("env").orEmpty()
                if (env.isNotBlank() && credentials.optString(env).isBlank())
                    return JSONObject().put("ok", false).put("error", "missing credentials")
            }
        }
        val credentials = body?.optJSONObject("credentials") ?: JSONObject()
        val env = JSONObject()
        val fields = auth.optJSONArray("fields") ?: JSONArray()
        for (i in 0 until fields.length()) {
            val name = fields.optJSONObject(i)?.optString("env").orEmpty()
            if (name.isNotBlank()) env.put(name, credentials.optString(name, ""))
        }
        return upsertConnector(JSONObject().put("name", id)
            .put("command", item.optJSONArray("command") ?: JSONArray())
            .put("env", env).put("url", item.optString("url", "")).put("enabled", true))
            .put("name", item.optString("name"))
    }

    private fun loadConnectors(): JSONArray = runCatching { JSONObject(if (connectorsFile.exists()) connectorsFile.readText() else "{}").optJSONArray("connectors") ?: JSONArray() }.getOrDefault(JSONArray())
    private fun saveConnectors(a: JSONArray) { connectorsFile.parentFile?.mkdirs(); connectorsFile.writeText(JSONObject().put("connectors", a).toString(1)) }
    private fun upsertConnector(body: JSONObject?): JSONObject {
        val name = body?.optString("name", "") ?: ""
        if (!name.matches(Regex("[A-Za-z0-9_.-]+"))) return JSONObject().put("ok", false).put("error", "invalid connector name")
        val a = loadConnectors(); var row: JSONObject? = null
        for (i in 0 until a.length()) if (a.optJSONObject(i)?.optString("name") == name) row = a.getJSONObject(i)
        if (row == null) { row = JSONObject().put("name", name); a.put(row) }
        row!!.put("enabled", body?.optBoolean("enabled", true) ?: true)
            .put("status", "configured").put("tools", JSONArray())
            .put("command", body?.optJSONArray("command") ?: JSONArray())
            .put("env", body?.optJSONObject("env") ?: JSONObject())
            .put("url", body?.optString("url", "") ?: "")
        saveConnectors(a); return JSONObject().put("ok", true).put("connector", row)
    }
    private fun deleteConnector(body: JSONObject?): JSONObject { val name = body?.optString("name", "") ?: ""; val old = loadConnectors(); val out = JSONArray(); for (i in 0 until old.length()) if (old.optJSONObject(i)?.optString("name") != name) out.put(old.getJSONObject(i)); saveConnectors(out); return JSONObject().put("ok", true) }

    private fun remoteStatus(): JSONObject = runCatching {
        val data = JSONObject(if (remoteFile.exists()) remoteFile.readText() else "{}")
        if (!data.has("mode")) data.put("mode", "local")
        data
    }.getOrDefault(JSONObject().put("mode", "local").put("paired", false))
    private fun pairRemote(body: JSONObject?): JSONObject {
        // Do not persist a six-digit code as if it authenticated a desktop. The
        // native bridge has no remote-host handshake yet, so fail closed.
        return JSONObject().put("ok", false)
            .put("error", "遥控电脑的原生 WebSocket 配对尚未接入；当前仅支持本地模式")
    }
    private fun unpairRemote(): JSONObject { remoteFile.delete(); return JSONObject().put("ok", true) }
    private fun modeRemote(body: JSONObject?): JSONObject {
        val mode = body?.optString("mode", "") ?: ""
        if (mode == "local") { remoteFile.delete(); return JSONObject().put("ok", true).put("mode", "local") }
        return JSONObject().put("ok", false).put("error", "遥控电脑的原生 WebSocket 配对尚未接入")
    }

    private fun readRequest(input: BufferedInputStream): HttpRequest? {
        val header = readUntil(input, byteArrayOf(13, 10, 13, 10)) ?: return null
        val lines = String(header, Charsets.ISO_8859_1).split("\r\n")
        if (lines.isEmpty()) return null
        val first = lines.first().split(" ", limit = 3); if (first.size < 2) return null
        val headers = linkedMapOf<String, String>(); lines.drop(1).filter { it.contains(":") }.forEach { val i = it.indexOf(':'); headers[it.substring(0, i).trim().lowercase()] = it.substring(i + 1).trim() }
        val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 2_000_000) ?: 0
        val body = ByteArray(length); var off = 0; while (off < length) { val n = input.read(body, off, length - off); if (n < 0) return null; off += n }
        val target = first[1]; val q = target.indexOf('?'); return HttpRequest(first[0], target.substring(0, if (q < 0) target.length else q), if (q < 0) "" else target.substring(q + 1), headers, body)
    }
    private fun readUntil(input: BufferedInputStream, marker: ByteArray): ByteArray? { val out = ByteArrayOutputStream(); var matched = 0; while (out.size() < 32_768) { val b = input.read(); if (b < 0) return null; out.write(b); if (b == marker[matched].toInt()) { matched++; if (matched == marker.size) return out.toByteArray().copyOf(out.size() - marker.size) } else matched = if (b == marker[0].toInt()) 1 else 0 }; return null }
    private fun readFrame(input: BufferedInputStream): Pair<Int, ByteArray>? {
        val first = input.read(); val second = input.read()
        if (first < 0 || second < 0) return null
        val opcode = first and 0x0f
        var len = second and 0x7f
        if (len == 126) len = (input.read() shl 8) or input.read()
        else if (len == 127) {
            // The Agent protocol never sends >2 MiB frames; consume the
            // high four bytes and retain the low 32-bit length.
            repeat(4) { input.read() }
            len = (input.read() shl 24) or (input.read() shl 16) or (input.read() shl 8) or input.read()
        }
        val masked = (second and 0x80) != 0
        val mask = ByteArray(4)
        if (masked) input.readFully(mask)
        val data = ByteArray(len.coerceIn(0, 2_000_000))
        input.readFully(data)
        if (masked) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
        return opcode to data
    }
    private fun BufferedInputStream.readFully(data: ByteArray) { var off = 0; while (off < data.size) { val n = read(data, off, data.size - off); if (n < 0) throw SocketException("closed"); off += n } }
    private fun writeJson(output: BufferedOutputStream, code: Int, obj: JSONObject) { val bytes = obj.toString().toByteArray(Charsets.UTF_8); val status = when (code) { 200 -> "OK"; 202 -> "Accepted"; 401 -> "Unauthorized"; 404 -> "Not Found"; 422 -> "Unprocessable Entity"; else -> "Error" }; output.write("HTTP/1.1 $code $status\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); output.write(bytes); output.flush() }
    private fun authorized(value: String?): Boolean { val expected = "Bearer $token".toByteArray(); val actual = (value ?: "").toByteArray(); return MessageDigest.isEqual(expected, actual) }
    private fun query(query: String, key: String): String? = query.split('&').mapNotNull { val p = it.split('=', limit = 2); if (p.firstOrNull() == key) p.getOrNull(1) else null }.firstOrNull()

    private data class HttpRequest(val method: String, val path: String, val query: String, val headers: Map<String, String>, val body: ByteArray)
    private class WsClient(private val out: BufferedOutputStream) {
        @Synchronized fun send(text: String) = sendFrame(1, text.toByteArray(Charsets.UTF_8))
        @Synchronized fun sendFrame(opcode: Int, payload: ByteArray) { val n = payload.size; out.write(0x80 or opcode); when { n < 126 -> out.write(n); n <= 65535 -> { out.write(126); out.write(n shr 8); out.write(n) }; else -> { out.write(127); repeat(4) { out.write(0) }; out.write(n shr 24); out.write(n shr 16); out.write(n shr 8); out.write(n) } }; out.write(payload); out.flush() }
        fun close() = runCatching { out.flush() }
    }
    companion object { private const val TAG = "NativeBridge"; private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11" }
}
