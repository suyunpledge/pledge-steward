package com.forge.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.forge.android.bridge.AgentBridgeService
import com.forge.android.ui.AssistantScreen
import com.forge.android.ui.CatalogScreen
import com.forge.android.shizuku.ShizukuGate
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/**
 * MainActivity — Compose 对话壳（原生，非 WebView）。
 *
 * 首屏即产品：连接状态条 + 消息流 + 输入框；右侧抽屉是服务日志与
 * 权限向导（Shizuku/无障碍）。不做空泛营销页。
 */
class MainActivity : ComponentActivity() {

    private val chat = MutableStateFlow(listOf<ChatItem>())
    private val bridgeState = MutableStateFlow("idle")
    private val shizukuState = MutableStateFlow("offline")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ForgeTheme { App() } }
        AgentBridgeService.start(this)
        collectEvents()
    }

    private fun collectEvents() {
        // 事件流 → UI 状态（chat/bridge/shizuku）
        Events.bind(this) { obj: JSONObject ->
            when (obj.optString("type")) {
                "service_state" -> bridgeState.value = obj.optString("state")
                "bridge_connected" -> bridgeState.value = "ready"
                "ready" -> bridgeState.value = "ready"
                "agent.step", "agent.assistant_message" -> {
                    val text = obj.optString("text", obj.optString("content", ""))
                    if (text.isNotBlank()) append(ChatItem("agent", text))
                }
                "run_done" -> append(ChatItem("summary",
                    "完成 · ${obj.optInt("steps")} 步 · ¥${obj.optDouble("cost")}"))
                "run_failed" -> append(ChatItem("error", obj.optString("error")))
                "shizuku_state" -> shizukuState.value = obj.optString("state")
                "fatal" -> append(ChatItem("error", obj.optString("error")))
            }
        }
    }

    private fun append(item: ChatItem) {
        chat.value = chat.value + item
    }

    private fun send(text: String) {
        if (text.isBlank()) return
        append(ChatItem("user", text))
        val payload = JSONObject().put("type", "run").put("task", text)
        AgentBridgeService.send(this, payload.toString())
    }

    @Composable
    private fun App() {
        var tab by remember { mutableIntStateOf(0) }   // 0 对话 / 1 助理 / 2 目录
        MaterialTheme {
            Column(Modifier.fillMaxSize()) {
                StatusStrip()
                when (tab) {
                    0 -> MessageList(Modifier.weight(1f))
                    1 -> Box(Modifier.weight(1f)) {
                        AssistantScreen(onAsk = { text ->
                            append(ChatItem("user", text))
                            val payload = JSONObject()
                                .put("type", "run")
                                .put("session", "assistant-default")
                                .put("task", text)
                            AgentBridgeService.send(this@MainActivity, payload.toString())
                            tab = 0
                        }, onOpenCatalog = { tab = 2 })
                    }
                    else -> Box(Modifier.weight(1f)) {
                        CatalogScreen(onInstalled = { tab = 1 })
                    }
                }
                NavigationBar {
                    NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                        icon = { Text("💬") }, label = { Text("对话") })
                    NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                        icon = { Text("🤝") }, label = { Text("助理") })
                    NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                        icon = { Text("🧩") }, label = { Text("目录") })
                }
                if (tab == 0) InputBar(onSend = { send(it) })
            }
        }
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

    @Composable
    private fun MessageList(modifier: Modifier = Modifier) {
        val items by chat.collectAsState()
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
                    EmptyState()
                }
            }
        }
    }

    @Composable
    private fun EmptyState() {
        Column(Modifier.fillMaxWidth().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("forge · Android", style = MaterialTheme.typography.titleLarge)
            Text(
                if (bridgeState.value == "ready") "桥已就绪，输入任务开始。"
                else "首次启动在解包 Linux 环境并拉起 Python Agent（约 1-2 分钟）…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun InputBar(onSend: (String) -> Unit) {
        var text by remember { mutableStateOf("") }
        Surface(tonalElevation = 4.dp) {
            Row(Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("给 Agent 派任务…") },
                    maxLines = 4)
                Button(onClick = { onSend(text); text = "" },
                    enabled = text.isNotBlank() && bridgeState.value == "ready") {
                    Text("发送")
                }
            }
        }
    }
}

data class ChatItem(val role: String, val text: String)
