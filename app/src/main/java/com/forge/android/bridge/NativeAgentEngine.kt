package com.forge.android.bridge

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Android-native Forge Agent core.
 *
 * This is the mobile execution kernel: provider config, OpenAI-compatible
 * model routing, bounded tool loop, cancellation, device tool receipts and a
 * JSONL session journal. Desktop-only Forge modules never enter the APK.
 */
class NativeAgentEngine(
    private val context: Context,
    private val emitLine: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build()
    private val home = File(context.filesDir, "forge-home").apply { mkdirs() }
    private val capabilities = ConcurrentHashMap.newKeySet<String>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    @Volatile private var approvalAvailable = false
    @Volatile private var busy = false
    @Volatile private var activeJob: Job? = null
    @Volatile private var activeSession = "phone"

    fun start() { emit(JSONObject().put("type", "agent_ready")) }

    fun stop() {
        activeJob?.cancel()
        pending.values.forEach { it.cancel() }
        pending.clear()
        scope.cancel()
    }

    /** Accepts the bridge command envelope used by the Android WebSocket. */
    fun send(payload: String): Boolean {
        val message = runCatching { JSONObject(payload) }.getOrNull() ?: return false
        when (message.optString("type")) {
            "capabilities" -> {
                capabilities.clear()
                val tools = message.optJSONArray("tools") ?: JSONArray()
                for (i in 0 until tools.length()) capabilities.add(tools.optString(i))
                approvalAvailable = message.optBoolean("approval", false)
            }
            "tool_result" -> {
                val callId = message.optString("call_id")
                val state = message.optString("execution_state", "")
                val valid = message.opt("ok") is Boolean &&
                    (state.isBlank() || state in setOf("completed", "not_started", "unknown"))
                pending.remove(callId)?.complete(if (valid) message else JSONObject()
                    .put("ok", false).put("execution_state", "unknown")
                    .put("error", "invalid tool receipt"))
            }
            "cancel", "cancel_tools" -> cancelActive()
            "reset" -> if (!busy) emit(JSONObject().put("type", "reset_ok"))
                else emit(JSONObject().put("type", "error").put("error", "session busy"))
            "ping" -> emit(JSONObject().put("type", "pong").put("t", System.currentTimeMillis() / 1000.0))
            "run" -> {
                val task = message.optString("task", "")
                val history = message.optJSONArray("history") ?: JSONArray()
                val session = message.optString("session", "phone")
                if (task.isBlank() || task.length > 32000) {
                    emit(JSONObject().put("type", "error").put("error", "invalid task/history"))
                } else if (busy) {
                    emit(JSONObject().put("type", "error").put("error", "session busy"))
                } else {
                    activeSession = session
                    busy = true
                    activeJob = scope.launch { runTask(task, history, session) }
                }
            }
            else -> emit(JSONObject().put("type", "error").put("error", "unknown agent message"))
        }
        return true
    }

    private fun cancelActive() {
        pending.forEach { (callId, deferred) ->
            emit(JSONObject().put("type", "cancel_tool").put("call_id", callId))
            if (!deferred.isCompleted) deferred.complete(JSONObject()
                .put("type", "tool_result").put("ok", false)
                .put("execution_state", "unknown").put("error", "cancelled"))
        }
        activeJob?.cancel(CancellationException("cancelled"))
    }

    private suspend fun runTask(task: String, history: JSONArray, session: String) {
        val runId = UUID.randomUUID().toString().replace("-", "")
        val taskId = UUID.randomUUID().toString().replace("-", "")
        val identity = JSONObject().put("run_id", runId).put("task_id", taskId)
        val journal = File(home, "sessions/phone-$session.jsonl").apply { parentFile?.mkdirs() }
        try {
            emit(JSONObject().put("type", "run_started").put("entry", "android").put("protocol_version", 1).put("run_id", runId).put("task_id", taskId))
            val provider = loadProvider()
            if (provider == null) {
                finishFailure(identity, "未配置模型 API Key，请先完成引导", "provider_missing")
                return
            }
            val messages = mutableListOf<JSONObject>()
            messages += JSONObject().put("role", "system").put("content", SYSTEM_PROMPT)
            for (i in 0 until history.length()) {
                val row = history.optJSONObject(i) ?: continue
                val role = row.optString("role")
                val content = row.optString("content", row.optString("text"))
                if (role in setOf("user", "assistant", "system") && content.isNotBlank()) {
                    messages += JSONObject().put("role", role).put("content", content.take(32000))
                }
            }
            messages += JSONObject().put("role", "user").put("content", task)
            journal.appendText(JSONObject().put("type", "run_started").put("run_id", runId).put("task_id", taskId).put("task", task).toString() + "\n")

            var finalAnswer = ""
            var completed = false
            for (step in 0 until MAX_STEPS) {
                val response = requestModel(provider, messages, toolDefinitions())
                if (response.first == null) {
                    finishFailure(identity, response.second ?: "模型请求失败", "model_request_failed")
                    return
                }
                val message = response.first!!
                val content = textContent(message)
                if (content.isNotBlank()) {
                    finalAnswer = content
                    emit(JSONObject().put("type", "agent.assistant_message").put("text", content).put("step", step).put("run_id", runId).put("task_id", taskId))
                }
                val calls = toolCalls(message)
                messages += message
                if (calls.isEmpty()) {
                    completed = true
                    break
                }
                for (call in calls) {
                    val validationError = validateTool(call.first, call.third)
                    if (validationError != null) {
                        messages += JSONObject().put("role", "tool").put("tool_call_id", call.second).put("content", JSONObject().put("ok", false).put("execution_state", "not_started").put("error", validationError).toString())
                        emit(JSONObject().put("type", "agent.tool_completed").put("tool", call.first).put("call_id", call.second).put("ok", false).put("execution_state", "not_started").put("run_id", runId).put("task_id", taskId))
                        continue
                    }
                    val args = call.third
                    val callId = call.second.ifBlank { "tc-${System.nanoTime()}" }
                    val result = CompletableDeferred<JSONObject>()
                    pending[callId] = result
                    emit(JSONObject().put("type", "tool_call").put("call_id", callId).put("tool", call.first).put("arguments", args).put("run_id", runId).put("task_id", taskId))
                    journal.appendText(JSONObject().put("type", "tool_call").put("call_id", callId).put("tool", call.first).put("arguments", args).toString() + "\n")
                    val receipt = try {
                        withTimeout(35_000) { result.await() }
                    } catch (_: CancellationException) {
                        JSONObject().put("ok", false).put("execution_state", "unknown").put("error", "cancelled")
                    } catch (_: Throwable) {
                        JSONObject().put("ok", false).put("execution_state", if (call.first.startsWith("android.ui.") || call.first == "android.app.launch") "unknown" else "not_started").put("error", "device tool timeout")
                    } finally { pending.remove(callId) }
                    val state = receipt.optString("execution_state", if (receipt.optBoolean("ok")) "completed" else "unknown")
                    emit(JSONObject().put("type", "agent.tool_completed").put("tool", call.first).put("call_id", callId).put("ok", receipt.optBoolean("ok") && state != "unknown").put("execution_state", state).put("run_id", runId).put("task_id", taskId))
                    messages += JSONObject().put("role", "tool").put("tool_call_id", callId).put("content", receipt.toString())
                    if (state == "unknown") {
                        finishDone(identity, finalAnswer, step + 1, "unknown")
                        return
                    }
                }
            }
            finishDone(identity, finalAnswer, MAX_STEPS, if (completed) "final" else "max_steps")
        } catch (_: CancellationException) {
            emit(JSONObject().put("type", "run_failed").put("execution_state", "cancelled").put("error", "任务已取消").put("run_id", runId).put("task_id", taskId))
        } catch (t: Throwable) {
            Log.e(TAG, "native agent run failed", t)
            finishFailure(identity, t.message ?: "Agent 执行失败", "agent_failed")
        } finally {
            busy = false
            activeJob = null
        }
    }

    private fun loadProvider(): Provider? {
        val file = File(home, "forge.patch.json")
        val rows = runCatching { JSONObject(if (file.exists()) file.readText() else "{}").optJSONArray("rows") }.getOrNull() ?: return null
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val config = row.optJSONObject("config") ?: continue
            val base = config.optString("baseURL").trim()
            val key = config.optString("apiKey").trim()
            val model = config.optJSONObject("model")?.optString("primary").orEmpty()
            if (base.isNotBlank() && (key.isNotBlank() || base.startsWith("http://127.0.0.1")))
                return Provider(base, key, model.ifBlank { "gpt-4o-mini" }, config.optString("wire", "openai"))
        }
        return null
    }

    private suspend fun requestModel(provider: Provider, messages: List<JSONObject>, tools: JSONArray): Pair<JSONObject?, String?> = withContext(Dispatchers.IO) {
        val url = provider.baseUrl.trimEnd('/') + if (provider.baseUrl.endsWith("/chat/completions")) "" else "/chat/completions"
        val body = JSONObject().put("model", provider.model).put("messages", JSONArray(messages)).put("stream", false)
        if (tools.length() > 0) body.put("tools", tools).put("tool_choice", "auto")
        val request = Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply { if (provider.key.isNotBlank()) header("Authorization", "Bearer ${provider.key}") }.build()
        runCatching {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@use Pair<JSONObject?, String?>(null, "模型 HTTP ${response.code}: ${raw.take(300)}")
                val choices = JSONObject(raw).optJSONArray("choices") ?: return@use Pair<JSONObject?, String?>(null, "模型响应缺少 choices")
                Pair(choices.optJSONObject(0)?.optJSONObject("message"), null)
            }
        }.getOrElse { Pair(null, "模型连接失败: ${it.message}") }
    }

    private fun toolDefinitions(): JSONArray {
        val out = JSONArray()
        for (name in capabilities) {
            val schema = when (name) {
                "android.ui.click" -> JSONObject().put("type", "object").put("properties", JSONObject().put("x", integerSchema()).put("y", integerSchema())).put("required", JSONArray(listOf("x", "y")))
                "android.ui.swipe" -> JSONObject().put("type", "object").put("properties", JSONObject().put("x1", integerSchema()).put("y1", integerSchema()).put("x2", integerSchema()).put("y2", integerSchema()).put("duration_ms", integerSchema(50, 2000))).put("required", JSONArray(listOf("x1", "y1", "x2", "y2")))
                "android.ui.input" -> JSONObject().put("type", "object").put("properties", JSONObject().put("text", JSONObject().put("type", "string").put("maxLength", 4000))).put("required", JSONArray(listOf("text")))
                "android.app.launch" -> JSONObject().put("type", "object").put("properties", JSONObject().put("package", JSONObject().put("type", "string"))).put("required", JSONArray(listOf("package")))
                "android.screen.capture" -> JSONObject().put("type", "object").put("properties", JSONObject().put("max_side", integerSchema(320, 1440)))
                else -> JSONObject().put("type", "object").put("properties", JSONObject())
            }
            out.put(JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", description(name)).put("parameters", schema)))
        }
        return out
    }

    private fun integerSchema(min: Int = 0, max: Int = 100000) = JSONObject().put("type", "integer").put("minimum", min).put("maximum", max)

    private fun validateTool(name: String, args: JSONObject): String? {
        if (!capabilities.contains(name)) return "capability unavailable"
        if (name in setOf("android.ui.click", "android.ui.swipe", "android.ui.input", "android.app.launch") && !approvalAvailable)
            return "device input approval is unavailable"
        fun intArg(key: String, min: Int = 0, max: Int = 100000): Boolean {
            val value = args.opt(key)
            return value is Int && value in min..max
        }
        return when (name) {
            "android.ui.click" -> if (intArg("x") && intArg("y") && args.length() == 2) null else "invalid click arguments"
            "android.ui.swipe" -> if (intArg("x1") && intArg("y1") && intArg("x2") && intArg("y2") && (!args.has("duration_ms") || intArg("duration_ms", 50, 2000))) null else "invalid swipe arguments"
            "android.ui.input" -> if (args.length() == 1 && args.optString("text", "").length <= 4000 && !args.optString("text").contains('\u0000')) null else "invalid input arguments"
            "android.app.launch" -> if (args.length() == 1 && args.optString("package").matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) null else "invalid package name"
            "android.screen.capture" -> if (args.length() == 0 || (args.length() == 1 && intArg("max_side", 320, 1440))) null else "invalid capture arguments"
            else -> if (args.length() == 0) null else "unexpected tool arguments"
        }
    }
    private fun description(name: String) = when (name) {
        "android.screen.inspect" -> "Inspect the current Android UI tree before acting."
        "android.screen.capture" -> "Capture the current screen; pixels are evidence, not click coordinates."
        "android.device.info" -> "Read Android device model, SDK and screen dimensions."
        "android.app.list" -> "List launchable installed Android packages."
        "android.ui.input" -> "Replace the currently focused non-password input after approval."
        else -> "Android device operation requiring one-time approval and post-action inspection."
    }

    private fun textContent(message: JSONObject): String {
        val content = message.opt("content") ?: return ""
        return when (content) {
            is String -> content
            is JSONArray -> buildString { for (i in 0 until content.length()) append(content.optJSONObject(i)?.optString("text").orEmpty()) }
            else -> ""
        }
    }

    private fun toolCalls(message: JSONObject): List<Triple<String, String, JSONObject>> {
        val out = mutableListOf<Triple<String, String, JSONObject>>()
        val calls = message.optJSONArray("tool_calls") ?: return out
        for (i in 0 until calls.length()) {
            val call = calls.optJSONObject(i) ?: continue
            val fn = call.optJSONObject("function") ?: continue
            val args = runCatching { JSONObject(fn.optString("arguments", "{}")) }.getOrDefault(JSONObject())
            out += Triple(fn.optString("name"), call.optString("id"), args)
        }
        return out
    }

    private fun finishDone(identity: JSONObject, answer: String, steps: Int, state: String) {
        emit(JSONObject(identity.toString()).put("type", "run_done").put("execution_state", state).put("answer", answer).put("steps", steps).put("cost", 0.0))
    }

    private fun finishFailure(identity: JSONObject, error: String, code: String) {
        emit(JSONObject(identity.toString()).put("type", "run_failed").put("execution_state", "failed").put("error", error).put("error_code", code))
    }

    private fun emit(obj: JSONObject) { emitLine(obj.toString()) }

    private data class Provider(val baseUrl: String, val key: String, val model: String, val wire: String)

    companion object {
        private const val TAG = "NativeAgentEngine"
        private const val MAX_STEPS = 12
        private const val SYSTEM_PROMPT = "You are Steward, a careful Android personal assistant. Use device tools only when available, ask for confirmation through the host for writes, inspect before and after actions, and never claim a business outcome from a backend receipt alone. Keep answers concise for a phone screen."
    }
}
