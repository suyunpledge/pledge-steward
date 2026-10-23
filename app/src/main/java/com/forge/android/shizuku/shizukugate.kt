package com.forge.android.shizuku

import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku

/**
 * ShizukuGate — Shizuku 状态机 + 权限请求 + 断线降级。
 *
 * 铁律（用户架构定稿）：Shizuku 离线 ≠ App 不可用。
 *  状态：online（binder 通 + 权限授予）/ offline（未启动或断开，含重启后
 *  未重新激活）/ denied（用户拒绝）。
 *  状态流转 → DeviceTools 工具面变化 → POST /capabilities 刷新 Python 侧。
 */
class ShizukuGate(private val onStateChanged: (String) -> Unit) {

    private val _state = MutableStateFlow("offline")
    val state: StateFlow<String> get() = _state

    private val listener = Shizuku.OnBinderReceivedListener { refresh() }
    private val deadListener = Shizuku.OnBinderDeadListener {
        setState("offline")
    }
    private val permissionListener = object : Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
            setState(if (grantResult == PackageManager.PERMISSION_GRANTED) "online" else "denied")
        }
    }

    fun start() {
        runCatching {
            Shizuku.addBinderReceivedListener(listener)
            Shizuku.addBinderDeadListener(deadListener)
            Shizuku.addRequestPermissionResultListener(permissionListener)
        }
        refresh()
    }

    fun stop() {
        runCatching {
            Shizuku.removeBinderReceivedListener(listener)
            Shizuku.removeBinderDeadListener(deadListener)
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        }
    }

    fun refresh() {
        if (!isAvailable()) { setState("offline"); return }
        if (!hasPermission()) { setState("denied"); return }
        setState("online")
    }

    fun requestPermissionIfPossible() {
        if (isAvailable() && !hasPermission()) {
            runCatching { Shizuku.requestPermission(REQ_CODE) }
        }
    }

    fun isAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun setState(s: String) {
        if (_state.value != s) {
            Log.i(TAG, "shizuku: ${_state.value} -> $s")
            _state.value = s
            onStateChanged(s)
        }
    }

    companion object {
        private const val TAG = "ShizukuGate"
        const val REQ_CODE = 1001
    }
}
