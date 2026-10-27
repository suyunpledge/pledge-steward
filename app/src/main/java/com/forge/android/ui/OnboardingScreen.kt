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
 * OnboardingScreen — 首启新手引导：欢迎 → 选厂商 → 一键跳官方控制台取 key
 * → 回填 → 完成 → 主界面。
 *
 * 触发：MainActivity 启动时查 /onboarding/status，needed=true 则先走本流程。
 * 可跳过（skip 标记落盘，不再打扰）；Ollama 本地无需 key 直接过。
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var vendors by remember { mutableStateOf(listOf<Vendor>()) }
    var selected by remember { mutableStateOf<Vendor?>(null) }
    var apiKey by remember { mutableStateOf("") }
    var step by remember { mutableStateOf(0) }   // 0 欢迎 1 选厂商 2 填key 3 完成
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vendors = withContext(Dispatchers.IO) {
            bridgeGet(context, "/onboarding/vendors")
                ?.optJSONArray("vendors")?.let { arr ->
                    (0 until arr.length()).map { Vendor.from(arr.getJSONObject(it)) }
                } ?: emptyList()
        }
    }

    when (step) {
        0 -> WelcomeStep(
            onStart = { step = 1 },
            onSkip = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        bridgePost(context, "/onboarding/skip", JSONObject()) }
                    onDone()
                }
            })
        1 -> VendorPicker(vendors = vendors,
            onPick = { selected = it; apiKey = ""; step = 2 },
            onBack = { step = 0 })
        2 -> if (selected != null) KeyEntryStep(
            vendor = selected!!,
            key = apiKey, onKey = { apiKey = it },
            busy = busy, msg = msg,
            onOpenConsole = { selected!!.console.takeIf { it.isNotBlank() }?.let { u ->
                openConsoleUrl(context, u) } },
            onConfirm = {
                scope.launch {
                    busy = true
                    val r = withContext(Dispatchers.IO) {
                        bridgePost(context, "/onboarding/apply", JSONObject()
                            .put("vendor", selected!!.id)
                            .put("api_key", apiKey))
                    }
                    busy = false
                    if (r?.optBoolean("ok") == true) step = 3
                    else msg = r?.optString("error") ?: "失败"
                }
            },
            onBack = { step = 1 })
        3 -> DoneStep(onEnter = onDone)
    }
}

data class Vendor(val id: String, val name: String, val icon: String,
                  val desc: String, val models: List<String>,
                  val console: String, val keyless: Boolean) {
    companion object {
        fun from(o: JSONObject) = Vendor(
            o.optString("id"), o.optString("name"), o.optString("icon"),
            o.optString("desc"),
            run { val a = o.optJSONArray("models") ?: JSONArray()
                  (0 until a.length()).map { a.optString(it) } },
            o.optString("console"), o.optBoolean("keyless"))
    }
}

@Composable
private fun WelcomeStep(onStart: () -> Unit, onSkip: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text("forge", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text("手机上的 AI 个人助理", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text("配置一个大模型 API Key 即可开始。\n也可以先跳过，之后在助理页再配。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Text("配置模型 API Key")
        }
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text("跳过，直接进入")
        }
    }
}

@Composable
private fun VendorPicker(vendors: List<Vendor>, onPick: (Vendor) -> Unit,
                         onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("选择模型厂商", style = MaterialTheme.typography.titleLarge)
        Text("点一个开始配置——会给出官方控制台的直达链接",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(vendors) { v ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp)
                        .fillMaxWidth()
                        .clickable { onPick(v) },
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(v.icon, style = MaterialTheme.typography.titleLarge)
                        Column(Modifier.weight(1f)) {
                            Text(v.name, style = MaterialTheme.typography.titleSmall)
                            Text(v.desc, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (v.keyless) Text("免Key",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
        }
        TextButton(onClick = onBack) { Text("← 返回") }
    }
}

@Composable
private fun KeyEntryStep(vendor: Vendor, key: String, onKey: (String) -> Unit,
                         busy: Boolean, msg: String,
                         onOpenConsole: () -> Unit, onConfirm: () -> Unit,
                         onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("${vendor.icon} ${vendor.name}", style = MaterialTheme.typography.titleLarge)
        if (vendor.keyless) {
            Text("本地模型无需 API Key，直接完成。",
                color = MaterialTheme.colorScheme.tertiary)
        } else {
            Text("1. 点下面按钮打开官方控制台，创建/复制 API Key",
                style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onOpenConsole, modifier = Modifier.fillMaxWidth(),
                enabled = vendor.console.isNotBlank()) {
                Text("获取 ${vendor.name} API Key →")
            }
            Text("2. 粘贴到此处", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(value = key, onValueChange = onKey,
                label = { Text("API Key（sk-…）") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            Text("默认模型：${vendor.models.first()}（可在设置中更换）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth(),
            enabled = !busy && (vendor.keyless || key.isNotBlank())) {
            Text(if (busy) "保存中…" else "完成配置")
        }
        if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onBack) { Text("← 重选厂商") }
    }
}

@Composable
private fun DoneStep(onEnter: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text("✓", style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.tertiary)
        Spacer(Modifier.height(8.dp))
        Text("配置完成", style = MaterialTheme.typography.titleLarge)
        Text("助理已就绪——去对话页试试，或到 🧩 目录接入更多服务",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onEnter, modifier = Modifier.fillMaxWidth()) {
            Text("进入 forge")
        }
    }
}
