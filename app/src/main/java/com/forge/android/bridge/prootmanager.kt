package com.forge.android.bridge

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ProotManager — PRoot Ubuntu 24.04 用户态生命周期（免 root 主路径）。
 *
 * 与 ChrootManager 的关系：本类是 v0.2 主路径（用户架构定稿：PRoot；
 * Shizuku 是 ADB shell 身份，不能做 mount/chroot）。root 设备可选真
 * chroot 加速——走 ChrootManager，同一套 rootfs。
 *
 * proot 二进制（arm64）随 APK jniLibs 分发，安装时 PackageManager
 * 自动落到 nativeLibraryDir 并带执行位。
 */
class ProotManager(private val context: Context) {

    val rootfsDir: File get() = File(context.filesDir, "rootfs")
    private val assetsTar = "forge-rootfs-arm64.tar"

    private fun prootBin(): String =
        File(context.applicationInfo.nativeLibraryDir, "libproot.so").absolutePath

    private fun busybox(): String =
        File(context.applicationInfo.nativeLibraryDir, "libbusybox.so").absolutePath

    /** 首启解包（幂等）。tar 在 assets，流式解到 filesDir/rootfs。 */
    fun extractIfNeeded(onProgress: (Int) -> Unit = {}): Boolean {
        val mark = File(rootfsDir, ".forge-extracted")
        if (mark.exists()) return true
        rootfsDir.mkdirs()
        return try {
            context.assets.open(assetsTar).use { input ->
                // busybox tar 处理 assets 流（-O 读 stdin 用 -）
                val proc = ProcessBuilder(
                    busybox(), "tar", "-xf", "-", "-C", rootfsDir.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                Thread {
                    runCatching {
                        val buf = ByteArray(1 shl 16)
                        var read: Int
                        var total = 0L
                        val size = context.assets.openFd(assetsTar).length.let {
                            if (it > 0) it else 1L }
                        while (input.read(buf).also { read = it } > 0) {
                            proc.outputStream.write(buf, 0, read)
                            total += read
                            onProgress((total * 100 / size).toInt().coerceIn(0, 99))
                        }
                        proc.outputStream.close()
                    }
                }.start()
                val ok = proc.waitFor(300, TimeUnit.SECONDS) && proc.exitValue() == 0
                if (ok) mark.writeText("ok")
                else Log.e(TAG, proc.inputStream.bufferedReader().readText().take(1500))
                onProgress(100)
                ok
            }
        } catch (e: Exception) {
            Log.e(TAG, "extract failed: ${e.message}")
            false
        }
    }

    /**
     * 在 PRoot 内执行命令。
     *
     * 绑定策略：/proc /sys /dev 必绑；$APP_DATA ↔ /sdcard 之外的
     * 工作区用 rootfs 内部目录（/opt/forge/workspace）——Android 存储
     * 隔离由 Kotlin 层的 ContentResolver 桥接（v0.3），首版不绑共享存储。
     */
    fun exec(command: List<String>, extraBinds: List<String> = emptyList()): Process {
        val binds = mutableListOf(
            "-b", "/proc", "-b", "/sys", "-b", "/dev",
            "-b", "${context.filesDir.absolutePath}:/host-data",
        )
        binds.addAll(extraBinds)
        return ProcessBuilder(
            prootBin(),
            "-r", rootfsDir.absolutePath,
            *binds.toTypedArray(),
            "-w", "/opt/forge",
            "/usr/bin/env",
            "HOME=/root",
            "PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin",
            "TMPDIR=/tmp",
            "FORGE_ROOT=/opt/forge",
            *command.toTypedArray()
        ).apply {
            environment()["FORGE_BRIDGE_TOKEN"] = BridgeToken.get(context)
        }.start()
    }

    /** 拉起桥服务（uvicorn），由 PythonSupervisor watchdog 持有进程。 */
    fun startBridge(): Process = exec(listOf(
        "/usr/bin/python3", "-m", "uvicorn",
        "forge_bridge:app", "--host", "127.0.0.1", "--port", "8000",
        "--app-dir", "/opt/forge", "--loop", "asyncio", "--log-level", "info"))

    fun stop() {
        // PRoot 无 mount 残留；进程由 Supervisor destroy
    }

    companion object { private const val TAG = "ProotManager" }
}
