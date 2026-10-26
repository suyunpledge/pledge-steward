package com.forge.android.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import com.forge.android.shizuku.ShizukuGate
import com.forge.android.tools.DeviceTools
import com.forge.android.shizuku.ForgeToolClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * AgentBridgeService — 前台服务：chroot + Python 子进程 + WS 双向流。
 *
 * 生命周期：
 *   startService → ChrootManager.extractIfNeeded → PythonSupervisor.start
 *   → /health 就绪 → WS connect 127.0.0.1:8000/agent/ws → 发 ready
 *
 * 崩溃隔离（Operit Sidecar 思想）：Python 是子进程，崩了只影响当轮对话，
 *   Supervisor 指数退避自动重启；WS 断线自动重连（1s→2s→4s…30s 封顶）。
 */
class AgentBridgeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var chroot: ProotManager
    private lateinit var supervisor: PythonSupervisor
    private lateinit var shizukuGate: ShizukuGate
    lateinit var deviceTools: DeviceTools

    /** UI 订阅的事件总线（WS 收到的所有 JSON + 服务状态）。 */
    private val _events = Events.events
    val events: SharedFlow<JSONObject> get() = _events
    @Volatile var serviceState: String = "idle"
        private set

    private var ws: WebSocket? = null
    private val client by lazy { OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            require(request.url.host == "127.0.0.1")
            chain.proceed(request.newBuilder().header("Authorization", "Bearer " + BridgeToken.get(this)).build())
        }
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build() }

    override fun onCreate() {
        super.onCreate()
        chroot = ProotManager(this)
        supervisor = PythonSupervisor(this, chroot)
        // 灰度版接线（审计遗留 #2）：设备工具 + Shizuku 状态机
        shizukuGate = ShizukuGate { state ->
            Events.shizukuState.value=state
            emit(JSONObject().put("type", "shizuku_state").put("state", state))
            if (state == "online") ForgeToolClient.bind(this) { reportCapabilities() }
            else ForgeToolClient.unbind(this)
            reportCapabilities()
        }
        deviceTools = DeviceTools(this, this, shizukuGate)
        shizukuGate.start()
        startForeground(NOTIF_ID, buildNotification("forge bridge 启动中"))
        scope.launch { bootstrap() }
        scope.launch {
            while (isActive) {
                delay(3000)
                shizukuGate.refresh()
                if (shizukuGate.state.value == "online") ForgeToolClient.bind(this@AgentBridgeService) { reportCapabilities() }
                reportCapabilities()
            }
        }
    }

    private val capabilityLock = Any()
    private var lastCapabilities: String? = null
    private var reportingCapabilities = false
    private fun reportCapabilities() {
        if (!::deviceTools.isInitialized) return
        scope.launch {
            val body = JSONObject().apply {
                put("tools", JSONArray(deviceTools.registry()))
                put("shizuku", shizukuGate.state.value)
                put("approval", deviceTools.approvalAvailable())
            }
            val payload=body.toString()
            synchronized(capabilityLock) {
                if (reportingCapabilities || payload==lastCapabilities) return@launch
                reportingCapabilities=true
            }
            try { runCatching {
                client.newCall(Request.Builder()
                    .url("http://127.0.0.1:8000/capabilities")
                    .post(body.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()).execute().use {
                        if (it.isSuccessful) synchronized(capabilityLock) { lastCapabilities=payload }
                        Log.d(TAG, "capabilities reported: ${it.code} (${shizukuGate.state.value})")
                    }
            } } finally { synchronized(capabilityLock) { reportingCapabilities=false } }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action.let { act ->
            when (act) {
                ACTION_SEND -> intent?.getStringExtra("payload")?.let { sendRaw(it) }
                ACTION_STOP -> stopSelf()
                ACTION_SHIZUKU -> shizukuGate.requestPermissionIfPossible()
                else -> Unit
            }
        }
        return START_STICKY
    }

    private suspend fun bootstrap() {
        setState("extracting")
        if (!chroot.extractIfNeeded()) {
            setState("failed")
            emit(JSONObject().put("type", "fatal").put("error", "rootfs 解包失败"))
            return
        }
        setState("booting")
        supervisor.start()
        // 等 /health（最多 60s：首次 uvicorn 冷启动 + forge import 都在里面）
        val ok = waitForHealth(timeoutSec = 60)
        if (!ok) {
            emit(JSONObject().put("type", "fatal").put("error", "Python 桥未就绪（60s）"))
            scheduleReconnect()
            return
        }
        connectWs()
    }

    private suspend fun waitForHealth(timeoutSec: Int): Boolean {
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            val ok = runCatching {
                client.newCall(Request.Builder()
                    .url("http://127.0.0.1:8000/health").build())
                    .execute().use { it.isSuccessful }
            }.getOrDefault(false)
            if (ok) return true
            delay(1000)
        }
        return false
    }

    private fun connectWs() {
        setState("connecting")
        val req = Request.Builder().url("ws://127.0.0.1:8000/agent/ws?session=phone").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(capabilityLock) { lastCapabilities=null }
                emit(JSONObject().put("type", "bridge_connected"))
                reportCapabilities()   // 每次连接同步真实工具面
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== ws || serviceState == "idle") return
                runCatching { JSONObject(text) }.getOrNull()?.let { obj ->
                    when (obj.optString("type")) {
                        "ready" -> { reconnectAttempt=0; setState("ready") }
                        "run_started" -> Events.running.value=true
                        "run_done", "run_failed" -> Events.running.value=false
                        "cancel_tool" -> deviceTools.cancel(obj.optString("call_id"))
                        "cancel_tools" -> deviceTools.cancelAll()
                    }
                    // 灰度版接线：桥的设备工具请求 → Kotlin 双通道执行 →
                    // postToolResult 回传（type=tool_call 的消费点）
                    if (obj.optString("type") == "tool_call") {
                        deviceTools.handle(
                            obj.optString("call_id"),
                            obj.optString("tool"),
                            obj.optJSONObject("arguments") ?: JSONObject())
                    }
                    _events.tryEmit(obj)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure: ${t.message}")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scheduleReconnect()
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code,reason)
            }
        })
    }

    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    @Synchronized private fun scheduleReconnect() {
        if (serviceState == "idle") return
        deviceTools.cancelAll()
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
          while (isActive && serviceState != "idle") {
            val backoff = (1000L shl reconnectAttempt.coerceAtMost(5)).coerceAtMost(30_000L)
            reconnectAttempt++
            setState("restarting")
            delay(backoff)
            // Python 也挂了的话 Supervisor 已在重启；这里只重连 WS
            if (runCatching {
                    client.newCall(Request.Builder()
                        .url("http://127.0.0.1:8000/health").build())
                        .execute().use { it.isSuccessful }
                }.getOrDefault(false)) {
                connectWs()
                return@launch
            }
          }
        }
    }

    /** UI → Python：发原始 JSON（type=run/ping/reset）。 */
    fun sendRaw(payload: String) {
        if (serviceState != "ready" || ws?.send(payload) != true)
            emit(JSONObject().put("type","error").put("error","桥未就绪或发送失败，请等待连接恢复；任务未排队"))
    }

    // ── 设备工具回调：tools/DeviceTools 执行后调这里 ──────────────
    fun postToolResult(callId: String, ok: Boolean, result: JSONObject?, error: String?, executionState: String) {
        scope.launch {
            val body = JSONObject().apply {
                put("call_id", callId)
                put("ok", ok)
                put("execution_state", executionState)
                result?.let { put("result", it) }
                error?.let { put("error", it) }
            }
            var failure = ""
            for (attempt in 0..1) {
                ensureActive()
                try { val code=client.newCall(Request.Builder()
                    .url("http://127.0.0.1:8000/tool/call")
                    .post(body.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()).execute().use { it.code }
                    if(code in 200..299) return@launch
                    failure="HTTP $code"
                    if(code in 400..499) break
                } catch(e:java.io.IOException) { failure=e.message ?: "connection lost" }
                if(attempt==0) delay(250) // Retry the receipt only, never the device operation.
            }
            emit(JSONObject().put("type","tool_delivery_failed").put("call_id",callId)
                .put("error","工具回执未确认（$failure）；操作不会自动重试，请检查目标状态"))
        }
    }

    private fun setState(s: String) {
        serviceState = s
        Events.bridgeState.value=s
        if(s!="ready") Events.running.value=false
        emit(JSONObject().put("type", "service_state").put("state", s))
        updateNotification(
            when (s) {
                "ready" -> "forge 桥就绪"
                "restarting" -> "桥重启中…"
                "extracting" -> "首次解包 rootfs（约 1-2 分钟）"
                else -> "forge bridge $s"
            })
    }

    private fun emit(obj: JSONObject) { _events.tryEmit(obj) }

    override fun onDestroy() {
        setState("idle")
        ws?.close(1000, "service destroyed")
        deviceTools.close()
        shizukuGate.stop()
        ForgeToolClient.unbind(this)
        scope.cancel()
        supervisor.stop()
        chroot.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── 通知 ──────────────────────────────────────────────────────
    private fun buildNotification(text: String): Notification {
        val ch = NotificationChannel(CHANNEL_ID, "forge bridge", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("forge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "AgentBridgeService"
        private const val CHANNEL_ID = "forge_bridge"
        private const val NOTIF_ID = 8300
        const val ACTION_SEND = "com.forge.android.SEND"
        const val ACTION_STOP = "com.forge.android.STOP"
        const val ACTION_SHIZUKU = "com.forge.android.SHIZUKU"

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, AgentBridgeService::class.java))
        fun send(ctx: Context, payload: String) =
            ctx.startService(Intent(ctx, AgentBridgeService::class.java)
                .setAction(ACTION_SEND).putExtra("payload", payload))
    }
}
