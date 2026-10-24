package com.forge.android.bridge

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * PythonSupervisor — chroot 内 uvicorn 子进程的 watchdog。
 *
 * 崩溃自动重启（指数退避 1s→2s→…→30s 封顶），stdout/stderr 进环形
 * 缓冲（最近 500 行）供 UI 日志页查看。重启计数通过回调上报。
 */
class PythonSupervisor(
    private val context: Context,
    private val chroot: ProotManager,
) {
    private val monitor = Executors.newSingleThreadExecutor()
    private val processRef = AtomicReference<Process?>(null)
    @Volatile private var shouldRun = false
    @Volatile var restartCount = 0
        private set

    // 环形日志（UI 拉取）
    private val logBuffer = ArrayDeque<String>(500)
    val logsSnapshot: List<String> get() = synchronized(logBuffer) { logBuffer.toList() }

    fun start() {
        if (shouldRun) return
        shouldRun = true
        monitor.execute { superviseLoop() }
    }

    fun stop() {
        shouldRun = false
        processRef.getAndSet(null)?.destroyForcibly()
        monitor.shutdownNow()
    }

    private fun superviseLoop() {
        var backoffMs = 1000L
        while (shouldRun) {
            val t0 = System.currentTimeMillis()
            try {
                val proc = chroot.startBridge()
                processRef.set(proc)
                if(!shouldRun) {proc.destroyForcibly();processRef.compareAndSet(proc,null);break}
                appendLog("[supervisor] uvicorn started")

                // 泵 stdout 到环形缓冲（uvicorn 日志在这里）
                Thread { pump(proc.inputStream, "out") }.start()
                Thread { pump(proc.errorStream, "err") }.start()

                val exited = proc.waitFor()
                processRef.set(null)
                if(System.currentTimeMillis()-t0>30000) backoffMs=1000L
                appendLog("[supervisor] uvicorn exited code=$exited after ${(System.currentTimeMillis()-t0)/1000}s")
            } catch (e: Exception) {
                appendLog("[supervisor] spawn failed: ${e.message}")
            }
            if (!shouldRun) break
            restartCount++
            Log.w(TAG, "python bridge died; restart #$restartCount in ${backoffMs}ms")
            appendLog("[supervisor] restart #$restartCount in ${backoffMs}ms")
            try { Thread.sleep(backoffMs) } catch (_: InterruptedException) { return }
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    private fun pump(stream: java.io.InputStream, tag: String) {
        runCatching {
            BufferedReader(InputStreamReader(stream)).forEachLine { line ->
                appendLog("[$tag] $line")
            }
        }
    }

    private fun appendLog(line: String) {
        synchronized(logBuffer) {
            if (logBuffer.size >= 500) logBuffer.removeFirst()
            logBuffer.addLast("${System.currentTimeMillis() % 100000}: $line")
        }
        Log.d(TAG, line.take(200))
    }

    companion object { private const val TAG = "PythonSupervisor" }
}
