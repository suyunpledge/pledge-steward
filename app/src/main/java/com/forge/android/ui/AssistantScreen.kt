package com.forge.android.ui

// v0.7 收敛：AssistantScreen 屏体已并入主对话页（tab 0 即个人助理）；
// 本文件保留为共享 HTTP 工具库（bridgeGet/bridgePost 供目录/遥控/引导屏用）。

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private val client = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(35, TimeUnit.SECONDS)
    .build()

internal fun bridgeGet(context: Context, path: String): JSONObject? = runCatching {
    val req = Request.Builder()
        .url("http://127.0.0.1:8000$path")
        .header("Authorization", "***" + com.forge.android.bridge.BridgeToken.get(context))
        .build()
    client.newCall(req).execute().use { resp ->
        if (resp.isSuccessful) JSONObject(resp.body?.string() ?: "{}") else null
    }
}.getOrNull()

internal fun bridgePost(context: Context, path: String, body: JSONObject): JSONObject? =
    runCatching {
        val req = Request.Builder()
            .url("http://127.0.0.1:8000$path")
            .header("Authorization", "***" + com.forge.android.bridge.BridgeToken.get(context))
            .post(body.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        client.newCall(req).execute().use { resp ->
            JSONObject(resp.body?.string() ?: "{}")
        }
    }.getOrNull()
