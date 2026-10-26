package com.forge.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
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
 * CatalogScreen — 连接器目录页（一键接入）。
 *
 * 列表按分类分组，卡片显示图标/名称/描述/凭据需求；
 * 点击卡片 → 授权表单（按 auth.kind 渲染：none 直接装；api_key 填
 * Key（带「去获取」console 跳转）；oauth 填 Token）→ POST install。
 */
@Composable
fun CatalogScreen(onInstalled: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(listOf<CatalogItem>()) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<CatalogItem?>(null) }

    LaunchedEffect(Unit) {
        items = withContext(Dispatchers.IO) {
            bridgeGet(context, "/assistant/catalog")
                ?.optJSONArray("catalog")?.let { arr ->
                    (0 until arr.length()).map { CatalogItem(arr.getJSONObject(it)) }
                } ?: emptyList()
        }
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
            val grouped = items.groupBy { it.category }
            LazyColumn(Modifier.padding(pad).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                grouped.forEach { (cat, list) ->
                    item { Text(cat, style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp)) }
                    items(list) { it2 ->
                        CatalogCard(it2) { selected = it2 }
                    }
                }
            }
        }
    }
}

data class CatalogItem(
    val id: String, val name: String, val category: String, val icon: String,
    val desc: String, val authKind: String,
    val fields: List<Pair<String, String>>,   // env → label
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
                        TextButton(onClick = {
                            // 打开控制台取 Key（系统浏览器）
                            try {
                                val i = android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(url))
                                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                LocalContext.current.startActivity(i)
                            } catch (_: Exception) {}
                        }) { Text("去获取 Key →") }
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
