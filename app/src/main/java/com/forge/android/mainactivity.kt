package com.forge.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.forge.android.bridge.Events
import androidx.compose.ui.unit.dp
import com.forge.android.bridge.AgentBridgeService
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/**
 * MainActivity — Compose 对话壳（原生，非 WebView）。
 *
 * 连接状态条、权限入口、消息流、输入和协作取消。
 */
class MainActivity : ComponentActivity() {

    private val chat = MutableStateFlow(listOf<ChatItem>())
    private val bridgeState = Events.bridgeState
    private val running = Events.running
    private val shizukuState = Events.shizukuState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
        collectEvents()
        AgentBridgeService.start(this)
    }

    private fun collectEvents() {
        // 事件流 → UI 状态（chat/bridge/shizuku）
        lifecycleScope.launch { Events.events.collect { obj: JSONObject ->
            when (obj.optString("type")) {
                "service_state" -> { bridgeState.value = obj.optString("state"); if(bridgeState.value!="ready") running.value=false }
                "bridge_connected" -> bridgeState.value = "connecting"
                "run_started" -> running.value = true
                "cancel_requested" -> append(ChatItem("summary", "正在请求取消；等待当前步骤退出"))
                "ready" -> bridgeState.value = "ready"
                "agent.step", "agent.assistant_message" -> {
                    val text = obj.optString("text", obj.optString("content", ""))
                    if (text.isNotBlank()) append(ChatItem("agent", text))
                }
                "run_done" -> {
                    running.value = false
                    val answer = obj.optString("answer")
                    if (answer.isNotBlank()) append(ChatItem("agent", answer))
                    append(ChatItem("summary",
                    "${obj.optString("execution_state", "结束")} · ${obj.optInt("steps")} 步 · ¥${obj.optDouble("cost")}"))
                }
                "run_failed" -> { running.value=false; append(ChatItem("error", obj.optString("error"))) }
                "error" -> append(ChatItem("error", obj.optString("error")))
                "shizuku_state" -> shizukuState.value = obj.optString("state")
                "fatal" -> append(ChatItem("error", obj.optString("error")))
            }
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
        MaterialTheme {
            Column(Modifier.fillMaxSize()) {
                StatusStrip()
                PermissionBar()
                MessageList(Modifier.weight(1f))
                InputBar(onSend = { send(it) })
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
    private fun PermissionBar() {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
            TextButton(onClick={startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))}) { Text("无障碍") }
            TextButton(onClick={startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,android.net.Uri.parse("package:$packageName")))}) { Text("操作确认权限") }
            TextButton(onClick={startService(android.content.Intent(this@MainActivity,AgentBridgeService::class.java).setAction(AgentBridgeService.ACTION_SHIZUKU))}) { Text("Shizuku 授权") }
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
        val bridge by bridgeState.collectAsState()
        Column(Modifier.fillMaxWidth().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("forge · Android", style = MaterialTheme.typography.titleLarge)
            Text(
                if (bridge == "ready") "桥已就绪，输入任务开始。"
                else "首次启动在解包 Linux 环境并拉起 Python Agent（约 1-2 分钟）…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun InputBar(onSend: (String) -> Unit) {
        val bridge by bridgeState.collectAsState()
        val busy by running.collectAsState()
        var text by rememberSaveable { mutableStateOf("") }
        Surface(tonalElevation = 4.dp) {
            Row(Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("给 Agent 派任务…") },
                    maxLines = 4)
                if(busy) TextButton(onClick = { AgentBridgeService.send(this@MainActivity, "{\"type\":\"cancel\"}") }) { Text("取消") }
                Button(onClick = { onSend(text); text = "" },
                    enabled = text.isNotBlank() && bridge == "ready" && !busy) {
                    Text("发送")
                }
            }
        }
    }
}

data class ChatItem(val role: String, val text: String)
