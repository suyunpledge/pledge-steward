package com.forge.android.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.forge.android.bridge.AgentBridgeService
import com.forge.android.bridge.BridgeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * AssistantScreen — 个人助理模式页（Compose）。
 *
 * 三块：连接器卡片列表（状态/工具数/启停）+ 添加连接器表单 +
 * 助理对话入口（复用主对话，session=assistant-default）。
 * MCP 接入：任何 stdio MCP server（npx/uvx/python -m …），
 * 通过桥的 /assistant/connectors 管理，工具自动并入 Agent。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(onAsk: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var connectors by remember { mutableStateOf listOf<ConnectorSnap>() }
    var loading by remember { mutableStateOf(true) }
    var showAdd by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            loading = true
            connectors = withContext(Dispatchers.IO) {
                bridgeGet(context, "/assistant/connectors")
                    ?.optJSONArray("connectors")?.let { arr ->
                        (0 until arr.length()).map { i ->
                            ConnectorSnap.from(arr.getJSONObject(i))
                        }
                    } ?: emptyList()
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("个人助理") },
                actions = {
                    TextButton(onClick = { showAdd = true }) { Text("添加连接器") }
                })
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("接入其他应用的 MCP 连接器，工具将自动并入 Agent 能力面",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else if (connectors.isEmpty()) {
                EmptyConnectors { showAdd = true }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(connectors) { c ->
                        ConnectorCard(
                            c,
                            onToggle = { enabled ->
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        bridgePost(context, "/assistant/connectors",
                                            JSONObject().put("name", c.name)
                                                .put("command", JSONArray(c.command))
                                                .put("enabled", enabled))
                                    }
                                    refresh()
                                }
                            },
                            onEnsure = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        bridgePost(context, "/assistant/connectors/ensure",
                                            JSONObject().put("name", c.name))
                                    }
                                    refresh()
                                }
                            },
                            onDelete = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        bridgePost(context, "/assistant/connectors/delete",
                                            JSONObject().put("name", c.name))
                                    }
                                    refresh()
                                }
                            })
                    }
                }
            }

            Spacer(Modifier.weight(1f))
            AssistantAskBar(onAsk = onAsk)
        }
    }

    if (showAdd) {
        AddConnectorDialog(
            onDismiss = { showAdd = false },
            onSubmit = { name, commandStr ->
                scope.launch {
                    val cmd = JSONArray().apply {
                        commandStr.trim().split(Regex("\\s+")).forEach { put(it) }
                    }
                    withContext(Dispatchers.IO) {
                        bridgePost(context, "/assistant/connectors",
                            JSONObject().put("name", name).put("command", cmd))
                    }
                    showAdd = false
                    refresh()
                }
            })
    }
}

data class ConnectorSnap(
    val name: String, val status: String, val enabled: Boolean,
    val command: List<String>, val toolCount: Int, val error: String?) {
    companion object {
        fun from(o: JSONObject) = ConnectorSnap(
            o.optString("name"), o.optString("status"),
            o.optBoolean("enabled", true),
            run { val a = o.optJSONArray("command") ?: JSONArray()
                  (0 until a.length()).map { a.optString(it) } },
            o.optJSONArray("tools")?.length() ?: 0,
            if (o.isNull("error")) null else o.optString("error"))
    }
}

@Composable
private fun EmptyConnectors(onAdd: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("还没有连接器", style = MaterialTheme.typography.titleMedium)
            Text("接入示例：\n• 日历：npx -y @modelcontextprotocol/server-memory\n" +
                "• 笔记：uvx mcp-server-obsidian\n" +
                "• 任意 stdio MCP server（命令行启动）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onAdd) { Text("添加第一个连接器") }
        }
    }
}

@Composable
private fun ConnectorCard(c: ConnectorSnap, onToggle: (Boolean) -> Unit,
                          onEnsure: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(c.name, style = MaterialTheme.typography.titleSmall)
                Text("${c.command.joinToString(" ").take(48)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                val statusText = when (c.status) {
                    "ready" -> "就绪 · ${c.toolCount} 工具"
                    "failed" -> "失败：${c.error?.take(40)}"
                    else -> c.status
                }
                Text(statusText, style = MaterialTheme.typography.labelSmall,
                    color = if (c.status == "ready") MaterialTheme.colorScheme.tertiary
                            else if (c.status == "failed") MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Switch(checked = c.enabled, onCheckedChange = onToggle)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = onEnsure) { Text("启动") }
                    TextButton(onClick = onDelete) { Text("删除",
                        color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@Composable
private fun AssistantAskBar(onAsk: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Surface(tonalElevation = 4.dp) {
        Row(Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = text, onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("问助理…（已接入的连接器工具可用）") },
                maxLines = 3)
            Button(onClick = { onAsk(text); text = "" }, enabled = text.isNotBlank()) {
                Text("问")
            }
        }
    }
}

@Composable
private fun AddConnectorDialog(onDismiss: () -> Unit, onSubmit: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("添加 MCP 连接器") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text("名称（英文/数字）") })
                OutlinedTextField(value = command, onValueChange = { command = it },
                    label = { Text("启动命令（空格分隔）") },
                    placeholder = { Text("npx -y @modelcontextprotocol/server-memory") },
                    minLines = 2)
                Text("server 以 stdio JSON-RPC 运行于手机 Linux 环境",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(name, command) },
                enabled = name.isNotBlank() && command.isNotBlank()) { Text("添加并启动") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

// ── 桥 HTTP（带 token；loopback only）────────────────────────────
private val client = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(35, TimeUnit.SECONDS)
    .build()

private fun bridgeGet(context: Context, path: String): JSONObject? = runCatching {
    val req = Request.Builder()
        .url("http://127.0.0.1:8000$path")
        .header("Authorization", "Bearer " + BridgeToken.get(context))
        .build()
    client.newCall(req).execute().use { resp ->
        if (resp.isSuccessful) JSONObject(resp.body?.string() ?: "{}") else null
    }
}.getOrNull()

internal fun bridgePost(context: Context, path: String, body: JSONObject): JSONObject? =
    runCatching {
        val req = Request.Builder()
            .url("http://127.0.0.1:8000$path")
            .header("Authorization", "Bearer " + BridgeToken.get(context))
            .post(body.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        client.newCall(req).execute().use { resp ->
            JSONObject(resp.body?.string() ?: "{}")
        }
    }.getOrNull()
