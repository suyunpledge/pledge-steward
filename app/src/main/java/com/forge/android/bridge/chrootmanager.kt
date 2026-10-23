package com.forge.android.bridge

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ChrootManager — rootfs 生命周期：解包、挂载、chroot 执行。
 *
 * 优先级：
 *  1) root（su 可用）：bind mount /proc /sys /dev + chroot（用户架构定稿）
 *  2) proot（无 root 降级）：同一套 rootfs tar，proot -r rootfs ...，
 *     无需 mount，性能略低但任何机器可用
 *
 * rootfs tar 位于 app assets（forge-rootfs-arm64.tar），首次启动解包到
 * filesDir/rootfs；forge 仓与 forge_bridge.py 预置在镜像内 /opt/forge。
 */
class ChrootManager(private val context: Context) {

    val rootfsDir: File get() = File(context.filesDir, "rootfs")
    private val assetsTar = "forge-rootfs-arm64.tar"

    val isRootAvailable: Boolean by lazy {
        try {
            val p = ProcessBuilder("su", "-c", "id").start()
            p.waitFor(5, TimeUnit.SECONDS)
            p.inputStream.bufferedReader().readText().contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    /** 首次启动：assets → rootfs 目录（幂等，带 .complete 标记）。 */
    fun extractIfNeeded(): Boolean {
        val mark = File(rootfsDir, ".forge-extracted")
        if (mark.exists()) return true
        rootfsDir.mkdirs()
        return try {
            context.assets.open(assetsTar).use { input ->
                // tar 解包：root 下用系统 tar；无 root 也需要 tar 二进制——
                // 由 proot 方案自带的 busybox 提供（见 prootBin()）
                val tarBin = if (isRootAvailable) "tar" else prootTar()
                val proc = ProcessBuilder(tarBin, "-xf", "-", "-C", rootfsDir.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                input.copyTo(proc.outputStream)
                proc.outputStream.close()
                val ok = proc.waitFor(120, TimeUnit.SECONDS) && proc.exitValue() == 0
                if (ok) mark.writeText("ok") else Log.e(TAG, readLog(proc))
                ok
            }
        } catch (e: Exception) {
            Log.e(TAG, "extract failed: ${e.message}")
            false
        }
    }

    /** 启动 chroot 内命令；root 路径挂 proc/sys/dev，proot 路径直接跑。 */
    fun exec(command: List<String>): Process {
        return if (isRootAvailable) {
            val script = buildString {
                append("mountpoint -q ${rootfsDir.absolutePath}/proc || busybox mount -t proc proc ${rootfsDir.absolutePath}/proc; ")
                append("mountpoint -q ${rootfsDir.absolutePath}/sys || busybox mount -t sysfs sys ${rootfsDir.absolutePath}/sys; ")
                append("mountpoint -q ${rootfsDir.absolutePath}/dev || busybox mount -o bind /dev ${rootfsDir.absolutePath}/dev; ")
                append("chroot ${rootfsDir.absolutePath} ")
                append(command.joinToString(" ") { "'$it'" })
            }
            ProcessBuilder("su", "-c", script).start()
        } else {
            ProcessBuilder(prootBin(), "-r", rootfsDir.absolutePath,
                "-b", "/proc", "-b", "/sys", "-b", "/dev",
                "-w", "/opt/forge", "/usr/bin/env",
                "HOME=/root", "PATH=/usr/local/bin:/usr/bin:/bin",
                *command.toTypedArray()).start()
        }
    }

    fun stop() {
        if (isRootAvailable) {
            runCatching {
                ProcessBuilder("su", "-c",
                    "umount ${rootfsDir.absolutePath}/dev ${rootfsDir.absolutePath}/proc ${rootfsDir.absolutePath}/sys")
                    .start().waitFor(5, TimeUnit.SECONDS)
            }
        }
    }

    // proot/busybox 二进制随 APK 分发（jniLibs 或 filesDir 落盘 + chmod）
    private fun prootBin(): String = File(context.filesDir, "bin/proot").absolutePath
    private fun prootTar(): String = File(context.filesDir, "bin/busybox").absolutePath

    private fun readLog(p: Process): String =
        p.inputStream.bufferedReader().readText().take(2000)

    companion object {
        private const val TAG = "ChrootManager"
    }
}
