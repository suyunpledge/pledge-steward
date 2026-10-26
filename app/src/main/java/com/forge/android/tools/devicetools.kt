package com.forge.android.tools

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.forge.android.bridge.AgentBridgeService
import com.forge.android.shizuku.ForgeToolClient
import com.forge.android.shizuku.ShizukuGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * DeviceTools — android.* 工具分发，读取可降级，输入不自动重放。
 *
 * 输入：WS tool_call 事件（tool/arguments/call_id）
 * 输出：AgentBridgeService.postToolResult（→ /tool/call → Python Agent）
 *
 * 分级确认（PROTOCOL §5）：点击/输入弹悬浮窗确认气泡；inspect/capture 只读放行；
 * 未注册任意 Android shell；UI 输入依赖无障碍与单次确认。
 */
class DeviceTools(
    private val context: Context,
    private val service: AgentBridgeService,
    private val shizukuGate: ShizukuGate,
) {
    private val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val approvals = DeviceApprovals(context)
    private val seen = LinkedHashSet<String>()
    private val writes = setOf("android.ui.click","android.ui.swipe","android.ui.input","android.app.launch")
    private val inputLock = kotlinx.coroutines.sync.Mutex()
    private val jobs = java.util.concurrent.ConcurrentHashMap<String,kotlinx.coroutines.Job>()
    fun cancel(callId: String) { jobs[callId]?.cancel() }
    fun cancelAll() { jobs.values.forEach { it.cancel() } }
    fun approvalAvailable() = approvals.available()
    fun close() { approvals.close();scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }


    /** 内部审计环形日志，尚无图形导出入口。 */
    private val audit = ArrayDeque<JSONObject>()
    val auditSnapshot: List<JSONObject> get() = synchronized(audit) { audit.toList() }

    fun handle(callId: String, tool: String, args: JSONObject) {
        synchronized(seen) {
            if(callId.isBlank() || !seen.add(callId)) return
            if(seen.size>1024) seen.remove(seen.first())
        }
        val parameters=JSONObject(args.toString())
        val state = DeviceCallState(tool in writes)
        fun report(result: JSONObject) {
            if (!state.claimReceipt()) return
            audit(tool, parameters, result)
            val error = if (result.has("error") && !result.isNull("error")) result.optString("error") else null
            service.postToolResult(callId, result.optBoolean("ok"), result.optJSONObject("result"),
                error, result.optString("execution_state", if(result.optBoolean("ok")) "completed" else state.failureState()))
        }
        val job = scope.launch(start=kotlinx.coroutines.CoroutineStart.LAZY) {
            val result = try { dispatch(tool, parameters, state) }
                catch (e: kotlinx.coroutines.CancellationException) { fail("Device call cancelled; observe before retry", state.failureState()) }
                catch (e: Exception) { fail("dispatch error: ${e.message}", state.failureState()) }
            report(result)
        }
        jobs[callId]=job
        job.invokeOnCompletion {
            jobs.remove(callId,job)
            // Lazy jobs cancelled before their body starts must also settle the bridge.
            report(fail("Device call ended without a receipt", state.failureState()))
        }
        job.start()
    }

    private suspend fun dispatch(tool: String, args: JSONObject, state: DeviceCallState): JSONObject {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (!tool.startsWith("android.")) {
            return fail("not an android.* tool: $tool")
        }
        if (!registry().contains(tool)) {
            return fail("tool not registered in current capability set: $tool")
        }
        if(tool in writes) {
            if(!inputLock.tryLock()) return fail("Another input is pending", "not_started")
            try {
                validateInput(tool,args)
                val before=if(tool!="android.app.launch") ForgeAccessibility.dumpXml() else null
                val target=ForgeAccessibility.foregroundPackage()
                if(tool!="android.app.launch" && (before==null || target==null || target==context.packageName))
                    return fail("A visible external accessibility target is required","not_started")
                if(!approvals.request(tool,args.toString())) return fail("Not approved or approval expired","not_started")
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                // Wait only for our dismissed dialog to leave the accessibility cache.
                for(attempt in 0 until 10) {
                    if(ForgeAccessibility.foregroundPackage()!=context.packageName) break
                    kotlinx.coroutines.delay(50)
                }
                if(before!=null && (target!=ForgeAccessibility.foregroundPackage() || before!=ForgeAccessibility.dumpXml()))
                    return fail("Target changed during approval; observe again","not_started")
                // One backend only: a failed receipt is not permission to replay.
                return performWrite(tool,args,state)
            } finally {inputLock.unlock()}
        }
        return when (tool) {
            "android.screen.inspect" -> uiInspect()
            "android.screen.capture" -> screenCapture(args.optInt("max_side", 1080).coerceIn(320,1440))
            "android.app.list" -> appList()
            "android.device.info" -> deviceInfo()
            else -> fail("unknown tool: $tool")
        }
    }

    private fun validateInput(tool: String,args: JSONObject) {
        fun integer(key: String,max: Int): Int {
            val value=args.get(key)
            require(value is Int && value in 0 until max) {"Invalid $key"}
            return value
        }
        val dm=screenMetrics()
        when(tool) {
            "android.ui.click" -> {integer("x",dm.widthPixels);integer("y",dm.heightPixels)}
            "android.ui.swipe" -> {integer("x1",dm.widthPixels);integer("x2",dm.widthPixels);integer("y1",dm.heightPixels);integer("y2",dm.heightPixels)
                if(args.has("duration_ms")) integer("duration_ms",2001).also {require(it>=50)}}
            "android.ui.input" -> require(args.get("text") is String && args.getString("text").length<=4000 && !args.getString("text").contains('\u0000'))
            "android.app.launch" -> require(args.getString("package").matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))
        }
    }

    private suspend fun performWrite(tool: String,args: JSONObject,state: DeviceCallState): JSONObject {
        var dispatched=false
        return try {
            validateInput(tool,args) // Screen rotation/size can change during approval.
            check(tool=="android.app.launch" || ForgeAccessibility.isEnabled) {"Accessibility capability lost"}
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if(tool=="android.ui.input" && !ForgeAccessibility.hasEditableInput())
                return fail("Focus an enabled non-password input before setting text", "not_started")
            if(tool=="android.app.launch" && context.packageManager.getLaunchIntentForPackage(args.getString("package"))==null)
                return fail("Package has no launchable activity", "not_started")
            dispatched=true
            state.markDispatched()
            val success=when(tool) {
                "android.ui.click" -> ForgeAccessibility.tap(args.getInt("x"),args.getInt("y"))
                "android.ui.swipe" -> ForgeAccessibility.swipe(args.getInt("x1"),args.getInt("y1"),args.getInt("x2"),args.getInt("y2"),args.optInt("duration_ms",300).toLong())
                "android.ui.input" -> ForgeAccessibility.setText(args.getString("text"))
                else -> {val intent=context.packageManager.getLaunchIntentForPackage(args.getString("package"));if(intent!=null) {context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));true} else false}
            }
            if(success) ok(JSONObject().put("dispatched",true).put("verification","backend_receipt")
                .put("next_step","android.screen.inspect").put("foreground_package",ForgeAccessibility.foregroundPackage()))
            else fail("Input receipt failed; inspect screen before retry","unknown")
        } catch(e:Exception) {fail("Input error: ${e.message}",if(dispatched) "unknown" else "not_started")}
    }

    // ── 各工具实现：先无障碍，后 Shizuku，双降级 ───────────────────
    private suspend fun uiInspect(): JSONObject {
        val xml = ForgeAccessibility.dumpXml()
        if (xml != null) return ok(screenInfo().put("xml", xml).put("source","accessibility"))
        val sh = runCatching { ForgeToolClient.uiDump() }.getOrNull()
        if (sh != null) return ok(screenInfo().put("xml", sh.take(64_000)).put("truncated",sh.length>64_000).put("source","shizuku"))
        return fail("no ui tree source (accessibility off, shizuku offline)")
    }

    private suspend fun screenCapture(maxSide: Int): JSONObject {
        // 首选 Shizuku screencap（无弹窗）；无障碍 takeScreenshot 备选
        if (shizukuGate.state.value == "online" && ForgeToolClient.isReady) {
            val b64 = runCatching { ForgeToolClient.screencap(maxSide) }.getOrNull()
            if (b64 != null) {
                val captured=runCatching { captureResult(b64, "shizuku") }.getOrNull()
                if(captured?.optBoolean("ok")==true) return captured
            }
        }
        val shot = ForgeAccessibility.takeScreenshot()
        if (shot != null) {
            val scaled = scale(shot, maxSide)
            val b64 = Base64.encodeToString(scaled, Base64.NO_WRAP)
            shot.recycle()
            return captureResult(b64, "accessibility")
        }
        return fail("no capture source")
    }

    private suspend fun appList(): JSONObject {
        val sh = runCatching { ForgeToolClient.packageList() }.getOrNull()
        if (sh != null) {
            val packages=DevicePackages.parse(sh)
            return ok(JSONObject().put("packages",
                JSONArray(packages.take(200))).put("truncated",packages.size>200))
        }
        val pm = context.packageManager.getInstalledApplications(PackageManagerGET_INSTALLED)
        val arr = JSONArray()
        pm.take(200).forEach { arr.put(it.packageName) }
        return ok(JSONObject().put("packages", arr).put("truncated", pm.size > 200))
    }

    private suspend fun deviceInfo(): JSONObject {
        val dm = screenMetrics()
        return ok(JSONObject()
            .put("model", android.os.Build.MODEL)
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("screen", "${dm.widthPixels}x${dm.heightPixels}")
            .put("dpi", dm.densityDpi)
            .put("shizuku", shizukuGate.state.value))
    }

    // ── 能力注册表（随 Shizuku 状态动态）──────────────────────────
    fun registry(): Set<String> {
        val ready=ForgeToolClient.isReady
        var tools=setOf("android.device.info", "android.app.list")
        if(ForgeAccessibility.isEnabled || ready) tools=tools+"android.screen.inspect"
        if((android.os.Build.VERSION.SDK_INT>=30 && ForgeAccessibility.isEnabled) || ready) tools=tools+"android.screen.capture"
        if(approvalAvailable()) {
            tools=tools+"android.app.launch"
            if(ForgeAccessibility.isEnabled) tools=tools+setOf("android.ui.click","android.ui.swipe","android.ui.input")
        }
        return tools
    }

    // ── helpers ───────────────────────────────────────────────────
    private fun screenInfo(): JSONObject {
        val dm=screenMetrics()
        return JSONObject().put("screen_width",dm.widthPixels).put("screen_height",dm.heightPixels)
            .put("coordinate_space","physical_screen_pixels")
            .put("foreground_package",ForgeAccessibility.foregroundPackage())
    }
    private fun captureResult(b64: String, source: String): JSONObject {
        val bytes=Base64.decode(b64,Base64.DEFAULT)
        val options=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
        android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
        if(options.outWidth<=0 || options.outHeight<=0) return fail("Invalid screenshot payload")
        return ok(screenInfo().put("source",source)
            .put("image_width",options.outWidth).put("image_height",options.outHeight)
            .put("coordinate_note","Image may be scaled; inspect current screen before input. Rotation invalidates earlier coordinates.")
            .put("base64_png",b64))
    }
    private fun screenMetrics(): android.util.DisplayMetrics {
        val dm=android.util.DisplayMetrics()
        val wm=context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        if(android.os.Build.VERSION.SDK_INT>=30) {
            dm.setTo(context.resources.displayMetrics)
            val bounds=wm.maximumWindowMetrics.bounds
            dm.widthPixels=bounds.width();dm.heightPixels=bounds.height()
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
        }
        return dm
    }
    private fun ok(result: JSONObject) = JSONObject().put("ok", true).put("result", result)
    private fun fail(err: String, state: String="not_started") = JSONObject().put("ok", false).put("error", err).put("execution_state",state)

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
