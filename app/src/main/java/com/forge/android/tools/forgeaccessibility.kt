package com.forge.android.tools

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * ForgeAccessibility — 无障碍服务静态桥（Service 实例由系统回调注入）。
 *
 * 能力：UI 树 dump（xml 近似）、tap/swipe 手势、焦点输入、截图（API 30+）。
 * 未开启时全部返回 null/false → DeviceTools 走 Shizuku 兜底。
 */
class ForgeAccessibility : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "accessibility connected")
    }

    override fun onInterrupt() {}
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    companion object {
        private const val TAG = "ForgeA11y"
        @Volatile private var instance: ForgeAccessibility? = null
        val isEnabled: Boolean get() = instance != null

        /** 近似 uiautomator 的 XML dump（深度限制 30 层 / 每层 120 节点）。 */
        fun dumpXml(maxDepth: Int = 30): String? {
            val svc = instance ?: return null
            val root = svc.rootInActiveWindow ?: return null
            val sb = StringBuilder("<hierarchy rotation=0>")
            fun walk(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > maxDepth) return
                val sb2 = sb
                val cls = node.className?.toString() ?: "android.view.View"
                val r = Rect().also { node.getBoundsInScreen(it) }
                sb2.append("<node class=\"").append(cls).append("\"")
                    .append(" bounds=\"").append(r.toShortString()).append("\"")
                val text = node.text?.toString()?.take(80)
                if (!text.isNullOrBlank()) {
                    sb2.append(" text=\"").append(text.replace("\"", "&quot;")).append("\"")
                }
                val desc = node.contentDescription?.toString()?.take(80)
                if (!desc.isNullOrBlank()) {
                    sb2.append(" content-desc=\"").append(desc.replace("\"", "&quot;")).append("\"")
                }
                val clickable = node.isClickable
                if (clickable) sb2.append(" clickable=\"true\"")
                val children = node.childCount
                if (children == 0) { sb2.append("/>"); return }
                sb2.append(">")
                var n = 0
                for (i in 0 until children) {
                    node.getChild(i)?.let { child ->
                        walk(child, depth + 1); n++
                        if (n >= 120) return@let
                    }
                }
                sb2.append("</node>")
            }
            walk(root, 0)
            sb.append("</hierarchy>")
            return sb.toString()
        }

        /** 点击（手势）。suspend 直至手势派发完成。 */
        suspend fun tap(x: Int, y: Int): Boolean = gesture(Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }, 60L)

        suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durMs: Long): Boolean =
            gesture(Path().apply {
                moveTo(x1.toFloat(), y1.toFloat())
                lineTo(x2.toFloat(), y2.toFloat())
            }, durMs)

        /** 焦点输入框 SET_TEXT（找不到可编辑焦点则 false）。 */
        fun setText(text: String): Boolean {
            val svc = instance ?: return false
            val node = findFocusedEditable(svc) ?: return false
            val args = android.os.Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        /** 截图（API 30+；TakeScreenshotCallback 异步转同步等待）。 */
        fun takeScreenshot(timeoutMs: Long = 3000): Bitmap? {
            val svc = instance ?: return null
            if (Build.VERSION.SDK_INT < 30) return null
            return takeScreenshot30(svc, timeoutMs)
        }

        @androidx.annotation.RequiresApi(30)
        private fun takeScreenshot30(svc: ForgeAccessibility, timeoutMs: Long): Bitmap? {
            var holder: Bitmap? = null
            val latch = java.util.concurrent.CountDownLatch(1)
            svc.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        result.hardwareBuffer.use { buffer ->
                            val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            holder = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                            hardware?.recycle()
                        }
                        latch.countDown()
                    }
                    override fun onFailure(errorCode: Int) { latch.countDown() }
                })
            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            @Suppress("UNCHECKED_CAST") return holder
        }

        // ── 内部 ──────────────────────────────────────────────────
        private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()

        private suspend fun gesture(path: Path, durMs: Long): Boolean {
            val svc = instance ?: return false
            return suspendCancellableCoroutine { cont ->
                val gd = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, durMs))
                    .build()
                val ok = svc.dispatchGesture(gd, object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                    override fun onCancelled(g: GestureDescription?) { if (cont.isActive) cont.resume(false) }
                }, null)
                if (!ok) if (cont.isActive) cont.resume(false)
            }
        }

        private fun findFocusedEditable(svc: AccessibilityService): AccessibilityNodeInfo? {
            var node = svc.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            while (node != null) {
                if (node.isEditable) return node
                node = node.parent
            }
            return null
        }
    }
}
