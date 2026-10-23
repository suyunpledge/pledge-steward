package com.forge.android.bridge
import android.content.Context
import java.util.UUID
object BridgeToken {
    @Synchronized fun get(context: Context): String {
        val prefs = context.getSharedPreferences("bridge-private", Context.MODE_PRIVATE)
        return prefs.getString("token", null) ?: (UUID.randomUUID().toString() + UUID.randomUUID()).also {
            check(prefs.edit().putString("token", it).commit())
        }
    }
}
