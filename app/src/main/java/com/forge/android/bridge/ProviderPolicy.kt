package com.forge.android.bridge

import org.json.JSONObject
import java.net.URI

/** Shared by onboarding and the execution engine; key is already decrypted. */
internal object ProviderPolicy {
    fun usable(config: JSONObject, key: String): Boolean {
        val uri = runCatching { URI(config.optString("baseURL").trim()) }.getOrNull() ?: return false
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return false
        val local = uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost")
        return key.isNotBlank() || config.optBoolean("keyless", false) || local
    }
}
