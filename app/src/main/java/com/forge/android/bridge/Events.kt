package com.forge.android.bridge
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONObject
object Events {
    val events = MutableSharedFlow<JSONObject>(replay = 1, extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST)
}
