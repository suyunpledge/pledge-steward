package com.forge.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * RemoteModeScreen — 遥控电脑配置页（本地/遥控双模式切换）。
 *
 * 流程：输入电脑 IP:端口 + 6 位配对码（电脑侧 `python remote-host.py` 显示）
 * → POST /remote/pair → 模式切换 local/remote → 对话页的任务按模式分流
 * （remote 时桥转发桌面 forge 执行，审批弹回手机）。
 */
@Composable
fun RemoteModeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(JSONObject()) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8765") }
    var code by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            status = withContext(Dispatchers.IO) {
                bridgeGet(context, "/remote/status") ?: JSONObject()
            } ?: JSONObject()
        }
    }
    LaunchedEffect(Unit) { refresh() }

    val mode = status.optString("mode", "local")
    val paired = status.optBoolean("paired", false)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("遥控电脑", style = MaterialTheme.typography.titleLarge)
        Text("本地模式：任务跑在手机。遥控模式：任务转发电脑 forge 执行"
            + "（可用电脑屏幕工具 computer_observe/act，操作审批弹回手机）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        // 当前状态
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("当前模式：${if (mode == "remote") "遥控电脑" else "本地"}",
                    style = MaterialTheme.typography.titleSmall)
                if (paired) {
                    Text("已配对：${status.optString("host")}:${status.optInt("port")}",
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("未配对", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // 模式切换
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    busy = true
                    val r = withContext(Dispatchers.IO) {
                        bridgePost(context, "/remote/mode",
                            JSONObject().put("mode", "local"))
                    }
                    msg = if (r?.optBoolean("ok") == true) "已切回本地" else (r?.optString("error") ?: "失败")
                    refresh(); busy = false
                }
            }, enabled = mode != "local" && !busy) { Text("用本地") }
            Button(onClick = {
                scope.launch {
                    busy = true
                    val r = withContext(Dispatchers.IO) {
                        bridgePost(context, "/remote/mode",
                            JSONObject().put("mode", "remote"))
                    }
                    msg = if (r?.optBoolean("ok") == true) "已切遥控——对话页任务将转发电脑"
                        else (r?.optString("error") ?: "失败（先配对）")
                    refresh(); busy = false
                }
            }, enabled = mode != "remote" && paired && !busy) { Text("遥控电脑") }
        }

        HorizontalDivider()

        // 配对表单
        Text("配对电脑", style = MaterialTheme.typography.titleSmall)
        Text("电脑侧运行：python forge-android/bridge/remote-host.py --lan（显示 6 位配对码）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = host, onValueChange = { host = it },
            label = { Text("电脑 IP（如 192.168.1.100）") }, singleLine = true)
        OutlinedTextField(value = port, onValueChange = { port = it },
            label = { Text("端口（默认 8765）") }, singleLine = true)
        OutlinedTextField(value = code, onValueChange = { code = it },
            label = { Text("配对码（6 位）") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    busy = true
                    val r = withContext(Dispatchers.IO) {
                        bridgePost(context, "/remote/pair", JSONObject()
                            .put("host", host).put("port", port.toIntOrNull() ?: 8765)
                            .put("code", code))
                    }
                    msg = if (r?.optBoolean("ok") == true) "配对成功，已切遥控模式"
                        else (r?.optString("error") ?: "失败")
                    refresh(); busy = false
                }
            }, enabled = host.isNotBlank() && code.length == 6 && !busy) {
                Text("配对并遥控")
            }
            if (paired) {
                TextButton(onClick = {
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            bridgePost(context, "/remote/unpair", JSONObject())
                        }
                        msg = "已解除配对"; refresh()
                    }
                }) { Text("解除配对", color = MaterialTheme.colorScheme.error) }
            }
        }

        if (msg.isNotEmpty()) {
            Text(msg, color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
