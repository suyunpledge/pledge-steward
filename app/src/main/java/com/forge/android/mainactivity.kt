package com.forge.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.forge.android.bridge.Events
import androidx.compose.ui.unit.dp
import com.forge.android.bridge.AgentBridgeService
import com.forge.android.ui.CatalogScreen
import com.forge.android.ui.RemoteModeScreen
import com.forge.android.ui.OnboardingScreen
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBar
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File

/**
 * MainActivity — Compose 对话壳（原生，非 WebView）。
 *
 * 四页签：对话（Work，全能力）/ Chat（轻聊：仅联网搜索+MCP+工作台）/ 目录 / 遥控。
 * Chat 自带工作台（方案草稿，代理可经 workbench.write 更新），一键导出到 Work
 * 输入框派发执行。两个会话各自独立消息流，按事件 mode 字段分流。
 */
class MainActivity : ComponentActivity() {

    private val chat = MutableStateFlow(listOf<ChatItem>())          // Work（对话）
    private val chatModeList = MutableStateFlow(listOf<ChatItem>())  // Chat（轻聊）
    /** 最近一次上屏的 agent 文本：run_done 的终答与最后一条 assistant_message 同文，靠它去重。 */
    private var lastAgentText = ""
    private var lastAgentTextChat = ""
    private val bridgeState = Events.bridgeState
    private val running = Events.running
    private val shizukuState = Events.shizukuState
    /** Chat 工作台草稿（与引擎 workbench.txt 同文件）；workInput 供 Chat→Work 导出预填。 */
    private val workbench = MutableStateFlow("")
    private val workInput = MutableStateFlow("")
    private val workbenchFile: File get() = File(File(filesDir, "forge-home"), "workbench.txt")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
        collectEvents()
        lifecycleScope.launch(Dispatchers.IO) {
            val saved = runCatching { if (workbenchFile.exists()) workbenchFile.readText() else "" }.getOrDefault("")
            if (workbench.value.isBlank() && saved.isNotBlank()) workbench.value = saved
        }
        AgentBridgeService.start(this)
    }

    private fun collectEvents() {
        // 事件流 → UI 状态（按 mode 分流到 Work/Chat 两个消息列表）
        lifecycleScope.launch { Events.events.collect { obj: JSONObject ->
            val chatMode = obj.optString("mode") == "chat"
            when (obj.optString("type")) {
                "service_state" -> { bridgeState.value = obj.optString("state"); if(bridgeState.value!="ready") running.value=false }
                "bridge_connected" -> bridgeState.value = "connecting"
                "run_started" -> running.value = true
                "cancel_requested" -> append(ChatItem("summary", "正在请求取消；等待当前步骤退出"), chatMode)
                "ready" -> bridgeState.value = "ready"
                "agent.step", "agent.assistant_message" -> {
                    val text = obj.optString("text", obj.optString("content", ""))
                    if (text.isNotBlank()) {
                        if (chatMode) { lastAgentTextChat = text; append(ChatItem("agent", text), true) }
                        else { lastAgentText = text; append(ChatItem("agent", text), false) }
                    }
                }
                "run_done" -> {
                    running.value = false
                    val answer = obj.optString("answer")
                    val last = if (chatMode) lastAgentTextChat else lastAgentText
                    // 引擎终答恒等于最后一条非空 assistant_message；无条件 append 会双写。
                    if (answer.isNotBlank() && answer != last) append(ChatItem("agent", answer), chatMode)
                    val cost = obj.optDouble("cost").takeIf { obj.has("cost") && !it.isNaN() }?.let { " · ¥$it" }.orEmpty()
                    append(ChatItem("summary",
                    "${obj.optString("execution_state", "结束")} · ${obj.optInt("steps")} 步$cost"), chatMode)
                }
                "run_failed" -> { running.value=false; append(ChatItem("error", obj.optString("error")), chatMode) }
                "tool_call" -> append(ChatItem("summary", toolCallLine(obj)), chatMode)
                "agent.tool_started" -> append(ChatItem("summary", toolStartedLine(obj)), chatMode)
                "agent.tool_completed" -> {
                    val state=obj.optString("execution_state")
                    val status=if(state=="unknown") "结果未知，请检查屏幕" else if(obj.optBoolean("ok")) "已返回，需核对实际效果" else "未完成"
                    append(ChatItem("summary", "${obj.optString("tool")}：$status"), chatMode)
                }
                "workbench_updated" -> workbench.value = obj.optString("content", "")
                "tool_delivery_failed" -> append(ChatItem("error",obj.optString("error")), chatMode)
                "error" -> append(ChatItem("error", obj.optString("error")), chatMode)
                "shizuku_state" -> shizukuState.value = obj.optString("state")
                "fatal" -> append(ChatItem("error", obj.optString("error")), chatMode)
            }
        }
    }

    }

    private fun toolCallLine(obj: JSONObject): String {
        val tool = obj.optString("tool")
        return if (obj.optString("mode") == "chat") "正在调用 $tool…"
        else "正在调用 $tool；设备输入需要单次确认"
    }

    private fun toolStartedLine(obj: JSONObject): String {
        val tool = obj.optString("tool")
        return when {
            tool == "web_search" -> "正在联网搜索…"
            tool == "fetch_url" -> "正在读取网页…"
            tool.startsWith("mcp.") -> "正在调用连接器 ${tool.removePrefix("mcp.").substringBefore(".")}…"
            tool.startsWith("workbench") -> "正在更新工作台…"
            else -> "正在读取网页…"
        }
    }

    private fun append(item: ChatItem, chatMode: Boolean = false) {
        if (chatMode) chatModeList.value = chatModeList.value + item
        else chat.value = chat.value + item
    }

    private fun send(text: String, chatMode: Boolean = false) {
        if (text.isBlank()) return
        // 多轮历史（chat）：先装配再追加本轮 user——历史成为请求的可缓存前缀，
        // 命中率随轮次抬升（append-only，详见 ChatMode.buildHistory）。
        val history = if (chatMode)
            com.forge.android.bridge.ChatMode.buildHistory(
                chatModeList.value.map { it.role to it.text })
        else null
        append(ChatItem("user", text), chatMode)
        val payload = JSONObject().put("type", "run")
            .put("session", if (chatMode) "chat" else "assistant-default")
            .put("task", text)
        if (chatMode) {
            payload.put("mode", "chat")
            history?.let { payload.put("history", it) }
        }
        AgentBridgeService.send(this, payload.toString())
    }

    private fun saveWorkbench(s: String) {
        workbench.value = s
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { workbenchFile.parentFile?.mkdirs(); workbenchFile.writeText(s.take(32_000)) }
        }
    }

    @Composable
    private fun App() {
        var tab by remember { mutableIntStateOf(0) }
        var onboarded by remember { mutableStateOf<Boolean?>(null) }
        val ctx = androidx.compose.ui.platform.LocalContext.current
        LaunchedEffect(Unit) {
            // The bridge starts with the service, often after this first frame. An
            // unreachable bridge is not "onboarding done": retry for ~15 s, then show
            // onboarding (it reports its own connection errors) rather than an unusable chat.
            var st: org.json.JSONObject? = null
            for (attempt in 0 until 30) {
                st = withContext(Dispatchers.IO) { bridgeGetStatus(ctx) }
                if (st != null) break
                kotlinx.coroutines.delay(500)
            }
            onboarded = st != null && !st.optBoolean("needed")
        }
        if (onboarded == null) {
            Box(Modifier.fillMaxSize()) {
                CircularProgressIndicator(Modifier.align(androidx.compose.ui.Alignment.Center))
            }
        } else if (onboarded == false) {
            OnboardingScreen(onDone = { onboarded = true })
        } else {
        MaterialTheme {
            Column(Modifier.fillMaxSize()) {
                StatusStrip()
                when (tab) {
                    0 -> MessageList(items = chat.collectAsState().value, modifier = Modifier.weight(1f),
                        emptyTitle = "forge",
                        emptyBody = if (bridgeState.value == "ready")
                            "直接问任何事——已内置联网搜索。\n进阶：🧩 目录接入服务，🖥️ 遥控电脑执行任务。"
                        else "首次启动正在准备 Kotlin Agent 和设备工具…")
                    1 -> ChatTab(onExport = { draft ->
                        workInput.value = if (draft.isNotBlank()) "【从 Chat 工作台导出的方案】\n$draft" else ""
                        append(ChatItem("summary", "已导入方案到 Work 输入框，点发送即可派发"))
                        tab = 0
                    }, modifier = Modifier.weight(1f))
                    2 -> Box(Modifier.weight(1f)) {
                        CatalogScreen(onInstalled = { tab = 0 })
                    }
                    else -> Box(Modifier.weight(1f)) {
                        RemoteModeScreen()
                    }
                }
                NavigationBar {
                    NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                        icon = { Text("💬") }, label = { Text("对话") })
                    NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                        icon = { Text("🗨️") }, label = { Text("Chat") })
                    NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                        icon = { Text("🧩") }, label = { Text("目录") })
                    NavigationBarItem(selected = tab == 3, onClick = { tab = 3 },
                        icon = { Text("🖥️") }, label = { Text("遥控") })
                }
                when (tab) {
                    0 -> {
                        val work by workInput.collectAsState()
                        InputBar(value = work, onValueChange = { workInput.value = it },
                            onSend = { send(it) }, placeholder = "给 Agent 派任务…")
                    }
                    1 -> InputBar(value = "", onValueChange = {}, onSend = { send(it, chatMode = true) },
                        placeholder = "轻聊：联网搜索 / MCP / 工作台起草…")
                }
            }
        }
        }   // else(onboarded) 闭合
    }

    @Composable
    private fun StatusStrip() {
        val bridge by bridgeState.collectAsState()
        val shizuku by shizukuState.collectAsState()
        Surface(tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().padding(12.dp, 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AssistChip(onClick = {}, label = { Text("桥: $bridge") },
                    colors = when (bridge) {
                        "ready" -> AssistChipDefaults.assistChipColors()
                        else -> AssistChipDefaults.assistChipColors(
                            labelColor = MaterialTheme.colorScheme.error)
                    })
                AssistChip(onClick = {}, label = { Text("Shizuku: $shizuku") })
            }
        }
    }

    /** Chat 页：消息流 + 工作台（草稿持久化，代理经 workbench.write 同步更新）。 */
    @Composable
    private fun ChatTab(onExport: (String) -> Unit, modifier: Modifier = Modifier) {
        val items by chatModeList.collectAsState()
        val draft by workbench.collectAsState()
        Column(modifier.fillMaxSize()) {
            MessageList(items = items, modifier = Modifier.weight(1f),
                emptyTitle = "Chat",
                emptyBody = "轻聊模式：只有联网搜索与 MCP 连接器，不碰设备。\n让我在工作台起草方案，一键导出到 Work 执行。")
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(12.dp, 8.dp)) {
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("📋 工作台", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { onExport(draft) }, enabled = draft.isNotBlank()) {
                            Text("📤 导出到 Work")
                        }
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { saveWorkbench(it) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 160.dp),
                        placeholder = { Text("方案草稿…（可手改；Chat 里的助手也会写入这里）") },
                        textStyle = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    @Composable
    private fun MessageList(items: List<ChatItem>, modifier: Modifier = Modifier,
                            emptyTitle: String = "forge", emptyBody: String = "") {
        val listState = rememberLazyListState()
        LaunchedEffect(items.size) {
            if (items.isNotEmpty()) listState.animateScrollToItem(items.lastIndex)
        }
        LazyColumn(state = listState, modifier = modifier.padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(items) { item ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = when (item.role) {
                        "user" -> MaterialTheme.colorScheme.primaryContainer
                        "agent" -> MaterialTheme.colorScheme.surfaceVariant
                        "summary" -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.errorContainer
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(item.text, Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (items.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(emptyTitle, style = MaterialTheme.typography.titleLarge)
                        Text(emptyBody,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    @Composable
    private fun InputBar(value: String, onValueChange: (String) -> Unit,
                         onSend: (String) -> Unit, placeholder: String) {
        var localText by remember { mutableStateOf("") }
        val text = if (value.isNotEmpty()) value else localText
        Surface(tonalElevation = 4.dp) {
            Row(Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text, onValueChange = { localText = it; onValueChange(it) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(placeholder) },
                    maxLines = 4)
                Button(onClick = { onSend(text); localText = ""; onValueChange("") },
                    enabled = text.isNotBlank() && bridgeState.value == "ready" && !running.value) {
                    Text("发送")
                }
            }
        }
    }
}

data class ChatItem(val role: String, val text: String)

internal fun bridgeGetStatus(context: android.content.Context): org.json.JSONObject? =
    runCatching {
        val req = okhttp3.Request.Builder()
            .url("http://127.0.0.1:8000/onboarding/status")
            .header("Authorization", "Bearer " + com.forge.android.bridge.BridgeToken.get(context))
            .build()
        okhttp3.OkHttpClient().newCall(req).execute().use {
            if (it.isSuccessful) org.json.JSONObject(it.body?.string() ?: "{}") else null
        }
    }.getOrNull()
