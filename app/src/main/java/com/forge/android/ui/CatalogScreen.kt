package com.forge.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * CatalogScreen — 连接器目录页（场景分组 + 折叠 + 置顶）。
 *
 * 结构：常用（pinned，用户点名的核心服务直列）+
 * 场景分组（办公协作/生活日常/开发运维/专业服务，默认折叠，点标题展开）。
 * 联网搜索已内置进 Agent（web_search 工具），不在目录占位。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(onInstalled: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pinned by remember { mutableStateOf(listOf<CatalogItem>()) }
    var scenes by remember { mutableStateOf(listOf<SceneGroup>()) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<CatalogItem?>(null) }

    LaunchedEffect(Unit) {
        val resp = withContext(Dispatchers.IO) {
            bridgeGet(context, "/assistant/catalog") }
        fun parseArr(arr: JSONArray?) = arr?.let {
            (0 until it.length()).map { i -> CatalogItem.from(it.getJSONObject(i)) }
        } ?: emptyList()
        pinned = parseArr(resp?.optJSONArray("pinned"))
        scenes = resp?.optJSONArray("scenes")?.let { sarr ->
            (0 until sarr.length()).map { i ->
                val g = sarr.getJSONObject(i)
                SceneGroup(g.optString("key"), g.optString("label"),
                    g.optString("desc"), parseArr(g.optJSONArray("items")))
            }
        } ?: emptyList()
        loading = false
    }

    if (selected != null) {
        InstallDialog(item = selected!!, onDismiss = { selected = null },
            onInstall = { creds ->
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        bridgePost(context, "/assistant/catalog/install",
                            JSONObject().put("id", selected!!.id)
                                .put("credentials", JSONObject(creds)))
                    }
                    selected = null
                    if (result?.optBoolean("ok") == true) onInstalled()
                }
            })
        return
    }

    Scaffold(topBar = { TopAppBar(title = { Text("连接器市场") }) }) { pad ->
        if (loading) {
            Box(Modifier.padding(pad).fillMaxSize()) {
                CircularProgressIndicator(Modifier.padding(32.dp))
            }
        } else {
            // 每组独立折叠态（rememberSaveable 滚动重建不丢）
            val expandedStates = rememberSaveable {
                mutableStateMapOf<String, Boolean>()
            }
            LazyColumn(Modifier.padding(pad).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (pinned.isNotEmpty()) {
                    item { Text("★ 常用", style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp)) }
                    items(pinned) { CatalogCard(it) { selected = it } }
                }
                scenes.forEach { sg ->
                    item(key = "hdr-${sg.key}") {
                        val expanded = expandedStates[sg.key] ?: false
                        Row(Modifier.fillMaxWidth()
                            .clickable { expandedStates[sg.key] = !expanded }
                            .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (expanded) "▾" else "▸",
                                style = MaterialTheme.typography.titleSmall)
                            Column {
                                Text("${sg.label} · ${sg.items.size}",
                                    style = MaterialTheme.typography.titleSmall)
                                Text(sg.desc,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (expandedStates[sg.key] == true) {
                        items(sg.items, key = { it.id }) {
                            CatalogCard(it) { selected = it }
                        }
                    }
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

data class SceneGroup(val key: String, val label: String, val desc: String,
                      val items: List<CatalogItem>)

data class CatalogItem(
    val id: String, val name: String, val category: String, val icon: String,
    val desc: String, val authKind: String,
    val fields: List<Pair<String, String>>,
    val consoleUrl: String?) {
    companion object {
        fun from(o: JSONObject) = CatalogItem(
            o.optString("id"), o.optString("name"), o.optString("category"),
            o.optString("icon"), o.optString("desc"),
            o.optJSONObject("auth")?.optString("kind") ?: "none",
            run {
                val a = o.optJSONObject("auth")?.optJSONArray("fields") ?: JSONArray()
                (0 until a.length()).map { a.getJSONObject(it).let {
                    it.optString("env") to it.optString("label") } }
            },
            o.optJSONObject("auth")?.optString("console_url")?.takeIf { it.isNotBlank() })
    }
}

@Composable
private fun CatalogCard(item: CatalogItem, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(item.icon, style = MaterialTheme.typography.titleLarge)
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleSmall)
                Text(item.desc, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            Text(when (item.authKind) {
                "none" -> "免费直连"
                "api_key" -> "需 Key"
                else -> "需授权"
            }, style = MaterialTheme.typography.labelSmall,
                color = if (item.authKind == "none") MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InstallDialog(item: CatalogItem, onDismiss: () -> Unit,
                          onInstall: (Map<String, String>) -> Unit) {
    val creds = remember { mutableStateMapOf<String, String>() }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("${item.icon} 接入 ${item.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.desc, style = MaterialTheme.typography.bodySmall)
                if (item.authKind == "none") {
                    Text("该服务无需凭据，直接安装。",
                        color = MaterialTheme.colorScheme.tertiary)
                } else {
                    item.fields.forEach { (env, label) ->
                        OutlinedTextField(
                            value = creds[env] ?: "",
                            onValueChange = { creds[env] = it },
                            label = { Text(label) },
                            singleLine = true)
                    }
                    item.consoleUrl?.let { url ->
                        val ctx = LocalContext.current
                        TextButton(onClick = { openConsoleUrl(ctx, url) }) {
                            Text("去获取 Key →")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onInstall(creds.toMap()) },
                enabled = item.authKind == "none" ||
                    item.fields.all { !creds[it.first].isNullOrBlank() }) {
                Text("安装并连接")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

// 打开控制台 URL（普通函数；context 由调用方在 composable 上下文取好）
private fun openConsoleUrl(context: android.content.Context, url: String) {
    try {
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse(url))
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    } catch (_: Exception) {
    }
}
