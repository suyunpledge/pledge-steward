package com.forge.android.bridge

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * always 式分级记忆（对话模式）：历史对话不整包塞上下文，而是把过往问答存入
 * 本地库（带嵌入向量），每轮按当前任务检索最相关的几条注入到尾部。
 *
 * 嵌入服务=手机端分级检索桥（127.0.0.1:8101，bge-m3/llama.cpp）。桥不在时
 * 静默降级为普通对话——本特性永远不阻塞聊天。
 *
 * 缓存纪律：检索块注入在「稳定历史之后、任务之前」的尾部，只追加不回写，
 * system+history 前缀不受影响。
 */
object ChatMemory {
    /** 手机端嵌入桥（hierarchical-retrieval / bge-m3）。 */
    const val EMBED_URL = "http://127.0.0.1:8101/v1/embeddings"
    const val TOP_K = 3
    const val MIN_SIM = 0.42
    const val BLOCK_MAX_CHARS = 1200
    const val STORE_CAP = 120

    data class Entry(val text: String, val vec: List<Double>, val t: Long)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS).build()
    }

    // ---------- 纯函数（JVM 可测） ----------

    fun cosine(a: List<Double>, b: List<Double>): Double {
        if (a.isEmpty() || a.size != b.size) return 0.0
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0.0
        return dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))
    }

    fun topK(task: List<Double>, entries: List<Entry>, k: Int = TOP_K, minSim: Double = MIN_SIM): List<Pair<Entry, Double>> =
        entries.map { it to cosine(task, it.vec) }
            .filter { it.second >= minSim }
            .sortedByDescending { it.second }
            .take(k)

    /** 渲染注入块；无命中返回 null（不注入）。 */
    fun renderBlock(picks: List<Pair<Entry, Double>>, maxChars: Int = BLOCK_MAX_CHARS): String? {
        if (picks.isEmpty()) return null
        val sb = StringBuilder("[相关历史]（由本地分级记忆自动检索，仅供参考，非本轮对话内容）\n")
        var budget = maxChars
        for ((e, sim) in picks) {
            if (budget - e.text.length < 0) break
            budget -= e.text.length
            sb.append("- ").append(e.text.replace("\n", " ")).append("\n")
        }
        return sb.toString()
    }

    fun buildEmbedRequest(text: String): String =
        JSONObject().put("model", "bge-m3").put("input", JSONArray().put(text.take(2000))).toString()

    fun parseEmbedding(body: String): List<Double>? = runCatching {
        val data = JSONObject(body).optJSONArray("data") ?: return null
        val first = data.optJSONObject(0) ?: return null
        val emb = first.optJSONArray("embedding") ?: return null
        (0 until emb.length()).map { emb.getDouble(it) }
    }.getOrNull()

    // ---------- IO ----------

    fun load(file: File): List<Entry> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONObject(file.readText()).optJSONArray("entries") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o ->
                val v = o.optJSONArray("vec") ?: return@mapNotNull null
                Entry(o.optString("text"), (0 until v.length()).map { v.getDouble(it) }, o.optLong("t"))
            }
        }
    }.getOrDefault(emptyList())

    fun append(file: File, entry: Entry, cap: Int = STORE_CAP) {
        runCatching {
            val all = (load(file) + entry).takeLast(cap)   // 满则淘汰最旧
            file.parentFile?.mkdirs()
            file.writeText(JSONObject().put("entries", JSONArray().apply {
                for (e in all) put(JSONObject()
                    .put("text", e.text).put("t", e.t)
                    .put("vec", JSONArray(e.vec.map { Math.round(it * 10000.0) / 10000.0 })))
            }).toString())
        }
    }

    /** 嵌入当前文本；桥不在/失败返回 null（静默降级）。 */
    fun embed(text: String, url: String = EMBED_URL): List<Double>? = runCatching {
        val req = Request.Builder().url(url)
            .post(buildEmbedRequest(text).toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            parseEmbedding(resp.body?.string() ?: return null)
        }
    }.getOrNull()
}
