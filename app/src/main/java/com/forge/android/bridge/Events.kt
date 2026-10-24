package com.forge.android.bridge
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONObject
object Events {
    val bridgeState = kotlinx.coroutines.flow.MutableStateFlow("idle")
    val running = kotlinx.coroutines.flow.MutableStateFlow(false)
    val shizukuState = kotlinx.coroutines.flow.MutableStateFlow("offline")
    val events = MutableSharedFlow<JSONObject>(replay = 1, extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST)
}
