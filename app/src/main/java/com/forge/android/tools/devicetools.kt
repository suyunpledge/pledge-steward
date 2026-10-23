package com.forge.android.tools

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.forge.android.bridge.AgentBridgeService
import com.forge.android.shizuku.ForgeToolClient
import com.forge.android.shizuku.ShizukuGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * DeviceTools — android.* 工具分发（Accessibility 优先 + Shizuku 兜底双通道）。
 *
 * 输入：WS tool_call 事件（tool/arguments/call_id）
 * 输出：AgentBridgeService.postToolResult（→ /tool/call → Python Agent）
 *
 * 分级确认（PROTOCOL §5）：点击/输入弹悬浮窗确认气泡；inspect/capture 只读放行；
 * shell 系只在 Shizuku online 时注册。
 */
class DeviceTools(
    private val context: Context,
    private val service: AgentBridgeService,
    private val shizukuGate: ShizukuGate,
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    /** 审计环形日志（UI 可查）。 */
    private val audit = ArrayDeque<JSONObject>()
    val auditSnapshot: List<JSONObject> get() = synchronized(audit) { audit.toList() }

    fun handle(callId: String, tool: String, args: JSONObject) {
        scope.launch {
            val result = runCatching { dispatch(tool, args) }
                .fold({ it }, { e -> fail("dispatch error: ${e.message}") })
            audit(tool, args, result)
            val errMsg = if (result.has("error") && !result.isNull("error"))
                result.optString("error") else null
            service.postToolResult(
                callId, result.optBoolean("ok"), result.optJSONObject("result"),
                errMsg)
        }
    }

    private suspend fun dispatch(tool: String, args: JSONObject): JSONObject {
        if (!tool.startsWith("android.")) {
            return fail("not an android.* tool: $tool")
        }
        if (!registry().contains(tool)) {
            return fail("tool not registered in current capability set: $tool")
        }
        return when (tool) {
            "android.screen.inspect" -> uiInspect()
            "android.screen.capture" -> screenCapture(args.optInt("max_side", 1080))
            "android.ui.click" -> click(args.getInt("x"), args.getInt("y"))
            "android.ui.swipe" -> swipe(
                args.getInt("x1"), args.getInt("y1"),
                args.getInt("x2"), args.getInt("y2"),
                args.optInt("duration_ms", 300))
            "android.ui.input" -> inputText(args.getString("text"))
            "android.app.list" -> appList()
            "android.app.launch" -> launch(args.getString("package"))
            "android.device.info" -> deviceInfo()
            else -> fail("unknown tool: $tool")
        }
    }

    // ── 各工具实现：先无障碍，后 Shizuku，双降级 ───────────────────
    private suspend fun uiInspect(): JSONObject {
        val xml = ForgeAccessibility.dumpXml()
        if (xml != null) return ok(JSONObject().put("xml", xml.take(64_000)))
        val sh = ForgeToolClient.uiDump()
        if (sh != null) return ok(JSONObject().put("xml", sh))
        return fail("no ui tree source (accessibility off, shizuku offline)")
    }

    private suspend fun screenCapture(maxSide: Int): JSONObject {
        // 首选 Shizuku screencap（无弹窗）；无障碍 takeScreenshot 备选
        if (shizukuGate.state.value == "online" && ForgeToolClient.isReady) {
            val b64 = ForgeToolClient.screencap(maxSide)
            if (b64 != null) {
                return ok(JSONObject().put("base64_png", b64)
                    .put("source", "shizuku"))
            }
        }
        val shot = ForgeAccessibility.takeScreenshot()
        if (shot != null) {
            val scaled = scale(shot, maxSide)
            val b64 = Base64.encodeToString(scaled, Base64.NO_WRAP)
            return ok(JSONObject().put("base64_png", b64).put("source", "accessibility"))
        }
        return fail("no capture source")
    }

    private suspend fun click(x: Int, y: Int): JSONObject {
        // 确认气泡（悬浮窗）：见 ConfirmBubble；本骨架直接执行并留审计
        val byA11y = ForgeAccessibility.tap(x, y)
        if (byA11y) return ok(JSONObject().put("performed", true).put("backend", "accessibility"))
        if (ForgeToolClient.isReady && ForgeToolClient.inputTap(x, y)) {
            return ok(JSONObject().put("performed", true).put("backend", "shizuku"))
        }
        return fail("click failed on both backends")
    }

    private suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, dur: Int): JSONObject {
        val byA11y = ForgeAccessibility.swipe(x1, y1, x2, y2, dur.toLong())
        if (byA11y) return ok(JSONObject().put("performed", true).put("backend", "accessibility"))
        if (ForgeToolClient.isReady && ForgeToolClient.inputSwipe(x1, y1, x2, y2, dur)) {
            return ok(JSONObject().put("performed", true).put("backend", "shizuku"))
        }
        return fail("swipe failed on both backends")
    }

    private suspend fun inputText(text: String): JSONObject {
        val byA11y = ForgeAccessibility.setText(text)
        if (byA11y) return ok(JSONObject().put("set", true).put("backend", "accessibility"))
        if (ForgeToolClient.isReady && ForgeToolClient.inputText(text)) {
            return ok(JSONObject().put("set", true).put("backend", "shizuku"))
        }
        return fail("input failed on both backends")
    }

    private suspend fun appList(): JSONObject {
        val sh = ForgeToolClient.packageList()
        if (sh != null) {
            return ok(JSONObject().put("packages",
                JSONArray(sh.lineSequence().take(200).toList())))
        }
        val pm = context.packageManager.getInstalledApplications(PackageManagerGET_INSTALLED)
        val arr = JSONArray()
        pm.take(200).forEach { arr.put(it.packageName) }
        return ok(JSONObject().put("packages", arr).put("truncated", pm.size > 200))
    }

    private suspend fun launch(pkg: String): JSONObject {
        if (ForgeToolClient.isReady && ForgeToolClient.launchApp(pkg)) {
            return ok(JSONObject().put("started", true).put("backend", "shizuku"))
        }
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            return ok(JSONObject().put("started", true).put("backend", "intent"))
        }
        return fail("cannot launch $pkg")
    }

    private suspend fun deviceInfo(): JSONObject {
        val dm = context.resources.displayMetrics
        return ok(JSONObject()
            .put("model", android.os.Build.MODEL)
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("screen", "${dm.widthPixels}x${dm.heightPixels}")
            .put("dpi", dm.densityDpi)
            .put("shizuku", shizukuGate.state.value))
    }

    // ── 能力注册表（随 Shizuku 状态动态）──────────────────────────
    fun registry(): Set<String> {
        val base = setOf(
            "android.screen.inspect", "android.ui.click", "android.ui.swipe",
            "android.ui.input", "android.device.info")
        return if (shizukuGate.state.value == "online" && ForgeToolClient.isReady) {
            base + setOf("android.screen.capture", "android.app.list", "android.app.launch")
        } else base
    }

    // ── helpers ───────────────────────────────────────────────────
    private fun ok(result: JSONObject) = JSONObject().put("ok", true).put("result", result)
    private fun fail(err: String) = JSONObject().put("ok", false).put("error", err)

    private fun audit(tool: String, args: JSONObject, result: JSONObject) {
        synchronized(audit) {
            if (audit.size >= 200) audit.removeFirst()
            audit.addLast(JSONObject()
                .put("ts", System.currentTimeMillis())
                .put("tool", tool)
                .put("args", args.toString().take(200))
                .put("ok", result.optBoolean("ok")))
        }
        Log.d(TAG, "$tool -> ok=${result.optBoolean("ok")}")
    }

    private fun scale(bmp: Bitmap, maxSide: Int): ByteArray {
        val s = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        val out = if (s < 1f) Bitmap.createScaledBitmap(
            bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true) else bmp
        val baos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.PNG, 80, baos)
        if (out !== bmp) out.recycle()
        return baos.toByteArray()
    }

    private val PackageManagerGET_INSTALLED = 0x0000

    companion object { private const val TAG = "DeviceTools" }
}
