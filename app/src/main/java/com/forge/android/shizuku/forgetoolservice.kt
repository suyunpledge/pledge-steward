package com.forge.android.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Base64
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ForgeToolService — Shizuku UserService：运行在 ADB shell 身份的独立进程。
 *
 * 职责（仅高权限必需项；无障碍能做的不搬进来）：
 *  - screencap 截图（shell 身份，无需 MediaProjection 授权弹窗）
 *  - uiautomator dump（备选 UI 树）
 *  - pm list / am start / monkey 启动应用
 *  - input tap/swipe/text 兜底通道
 *
 * 绑定：Shizuku.bindUserService → IForgeTool.Stub；断连由 ShizukuGate
 * 触发重绑（指数退避）。本文件是服务端；客户端桩见 ForgeToolClient.kt。
 */
class ForgeToolService : IForgeTool.Stub() {

    override fun shell(command: String, timeoutMs: Int): String? {
        var process: Process?=null
        return try {
            val p=ProcessBuilder("sh","-c",command).redirectErrorStream(true).start()
            process=p
            val output=java.io.ByteArrayOutputStream()
            val reader=Thread {
                runCatching { p.inputStream.use { input ->
                    val buffer=ByteArray(4096)
                    while(true) {val n=input.read(buffer);if(n<0)break
                        if(output.size()<64000) output.write(buffer,0,minOf(n,64000-output.size()))}
                } }
            }.apply {start()}
            if(!p.waitFor(timeoutMs.toLong().coerceIn(1,30000),TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();reader.join(1000);null
            } else {
                reader.join(1000)
                if(reader.isAlive || p.exitValue()!=0) null else output.toString("UTF-8")
            }
        } catch(e:Exception) {null}
        finally {process?.let { if(it.isAlive) it.destroyForcibly();runCatching {it.inputStream.close()} }}
    }

    override fun screencap(maxSide: Int): String? {
        val tmp = File(contextCache(), "cap-${System.currentTimeMillis()}.png")
        var process: Process?=null
        return runCatching {
            // screencap 直出 png；-P 缩放在部分版本不可用，先全图再由端侧缩
            val p = ProcessBuilder("sh", "-c",
                "screencap -p ${tmp.absolutePath}").redirectErrorStream(true).start()
            process=p
            if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0 && tmp.length() > 0) {
                val bytes = shrink(tmp, maxSide.coerceIn(320, 1440))
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            } else null
        }.also {
            process?.let { if(it.isAlive) it.destroyForcibly();runCatching {it.inputStream.close()} }
            tmp.delete()
        }.getOrNull()
    }

    override fun uiDump(): String? = shell("uiautomator dump /dev/tty 2>/dev/null | head -c 64000", 15_000)

    override fun packageList(): String? = shell("pm list packages 2>/dev/null | head -c 32000", 10_000)

    override fun launchApp(pkg: String): Boolean {
        require(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(pkg))
        val r = shell("monkey -p $pkg -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 && echo OK", 10_000)
        return r?.contains("OK") == true
    }

    override fun inputTap(x: Int, y: Int): Boolean =
        shell("input tap $x $y && echo OK", 5_000)?.contains("OK") == true

    override fun inputSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durMs: Int): Boolean =
        shell("input swipe $x1 $y1 $x2 $y2 $durMs && echo OK", 8_000)?.contains("OK") == true

    override fun inputText(text: String): Boolean =
        shell("input text '${text.replace("'", "'\\''")}' && echo OK", 5_000)?.contains("OK") == true

    // ── 内部 ──────────────────────────────────────────────────────
    private fun contextCache(): File = File(
        "/data/local/tmp/forge-tools").apply { mkdirs() }

    private fun shrink(src: File, maxSide: Int): ByteArray {
        // UserService 进程没有 app Context，用 BitmapFactory 直读
        val bmp = android.graphics.BitmapFactory.decodeFile(src.absolutePath) ?: return ByteArray(0)
        val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        val out = if (scale < 1f) {
            android.graphics.Bitmap.createScaledBitmap(
                bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        } else bmp
        val baos = java.io.ByteArrayOutputStream()
        out.compress(android.graphics.Bitmap.CompressFormat.PNG, 80, baos)
        if (out !== bmp) out.recycle()
        bmp.recycle()
        return baos.toByteArray()
    }

    companion object { private const val TAG = "ForgeToolService" }
}
