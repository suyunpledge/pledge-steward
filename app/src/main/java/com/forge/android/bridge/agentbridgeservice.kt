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
import okio.ByteString
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
    private lateinit var chroot: ChrootManager
    private lateinit var supervisor: PythonSupervisor

    /** UI 订阅的事件总线（WS 收到的所有 JSON + 服务状态）。 */
    private val _events = MutableSharedFlow<JSONObject>(
        extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<JSONObject> get() = _events
    var serviceState: String = "idle"      // idle/extracting/booting/ready/restarting
        private set

    private var ws: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)      // WS 长连接
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        chroot = ChrootManager(this)
        supervisor = PythonSupervisor(this, chroot)
        startForeground(NOTIF_ID, buildNotification("forge bridge 启动中"))
        scope.launch { bootstrap() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action.let { act ->
            when (act) {
                ACTION_SEND -> intent.getStringExtra("payload")?.let { sendRaw(it) }
                ACTION_STOP -> stopSelf()
            }
        }
        return START_STICKY
    }

    private suspend fun bootstrap() {
        setState("extracting")
        if (!chroot.extractIfNeeded()) {
            emit(JSONObject().put("type", "fatal").put("error", "rootfs 解包失败"))
            return
        }
        setState("booting")
        supervisor.start()
        // 等 /health（最多 60s：首次 uvicorn 冷启动 + forge import 都在里面）
        val ok = waitForHealth(timeoutSec = 60)
        if (!ok) {
            emit(JSONObject().put("type", "fatal").put("error", "Python 桥未就绪（60s）"))
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
        setState("ready")
        val req = Request.Builder().url("ws://127.0.0.1:8000/agent/ws?session=phone").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                emit(JSONObject().put("type", "bridge_connected"))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { JSONObject(text) }.getOrNull()?.let { _events.tryEmit(it) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure: ${t.message}")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scheduleReconnect()
            }
        })
    }

    private var reconnectAttempt = 0
    private fun scheduleReconnect() {
        if (serviceState == "idle") return
        scope.launch {
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
            } else {
                scheduleReconnect()
            }
        }
    }

    /** UI → Python：发原始 JSON（type=run/ping/reset）。 */
    fun sendRaw(payload: String) {
        ws?.send(payload)
    }

    // ── 设备工具回调：tools/DeviceTools 执行后调这里 ──────────────
    fun postToolResult(callId: String, ok: Boolean, result: JSONObject?, error: String?) {
        scope.launch {
            val body = JSONObject().apply {
                put("call_id", callId)
                put("ok", ok)
                result?.let { put("result", it) }
                error?.let { put("error", it) }
            }
            runCatching {
                client.newCall(Request.Builder()
                    .url("http://127.0.0.1:8000/tool/call")
                    .post(RequestBody.create("application/json".toMediaTypeOrNull(), body.toString()))
                    .build()).execute().use { Log.d(TAG, "tool result posted: ${it.code}") }
            }
        }
    }

    private fun setState(s: String) {
        serviceState = s
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

        fun start(ctx: Context) = ctx.startService(Intent(ctx, AgentBridgeService::class.java))
        fun send(ctx: Context, payload: String) =
            ctx.startService(Intent(ctx, AgentBridgeService::class.java)
                .setAction(ACTION_SEND).putExtra("payload", payload))
    }
}
