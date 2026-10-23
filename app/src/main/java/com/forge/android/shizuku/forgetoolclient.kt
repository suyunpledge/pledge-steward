package com.forge.android.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ForgeToolClient — UserService 客户端桩：绑定/重绑/调用转发。
 */
object ForgeToolClient {

    @Volatile private var tool: IForgeTool? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var bindAttempts = 0

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            tool = IForgeTool.Stub.asInterface(service)
            bindAttempts = 0
            Log.i(TAG, "userService connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            tool = null
            Log.w(TAG, "userService disconnected")
        }
    }

    fun bind(context: Context) {
        if (tool != null) return
        val args = Shizuku.UserServiceArgs(
            ComponentName(context, ForgeToolService::class.java))
            .daemon(false)
            .processNameSuffix("forge_tools")
            .debuggable(android.os.BuildConfig.DEBUG)
            .version(1)
        runCatching { Shizuku.bindUserService(args, connection) }
            .onFailure { Log.w(TAG, "bind failed: ${it.message}") }
    }

    fun unbind(context: Context) {
        val args = Shizuku.UserServiceArgs(
            ComponentName(context, ForgeToolService::class.java))
            .daemon(false).processNameSuffix("forge_tools").version(1)
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        tool = null
    }

    val isReady: Boolean get() = tool != null

    fun shell(cmd: String, timeoutMs: Int = 10_000): String? =
        tool?.shell(cmd, timeoutMs)

    fun screencap(maxSide: Int = 1080): String? = tool?.screencap(maxSide)

    fun uiDump(): String? = tool?.uiDump()

    fun packageList(): String? = tool?.packageList()

    fun launchApp(pkg: String): Boolean = tool?.launchApp(pkg) ?: false

    fun inputTap(x: Int, y: Int): Boolean = tool?.inputTap(x, y) ?: false

    fun inputSwipe(x1: Int, y1: Int, x2: Int, y2: Int, dur: Int = 300): Boolean =
        tool?.inputSwipe(x1, y1, x2, y2, dur) ?: false

    fun inputText(text: String): Boolean = tool?.inputText(text) ?: false

    private const val TAG = "ForgeToolClient"
}
