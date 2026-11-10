package com.forge.android.bridge

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderPolicyTest {
    @Test fun keylessAndLegacyLocalProvidersWorkWithoutSecrets() {
        assertTrue(ProviderPolicy.usable(JSONObject().put("baseURL", "http://127.0.0.1:8101/v1"), ""))
        assertTrue(ProviderPolicy.usable(JSONObject().put("baseURL", "http://localhost:11434/v1"), ""))
        assertTrue(ProviderPolicy.usable(JSONObject().put("baseURL", "http://192.168.1.10:11434/v1").put("keyless", true), ""))
    }

    @Test fun remoteProvidersNeedAUsableSecretOrExplicitKeylessFlag() {
        val config = JSONObject().put("baseURL", "https://example.com/v1")
        assertFalse(ProviderPolicy.usable(config, ""))
        assertTrue(ProviderPolicy.usable(config, "test-key"))
    }

    @Test fun keylessDoesNotMakeMissingOrInvalidEndpointsUsable() {
        for (url in listOf("", "not a url", "file:///tmp/model", "https:///v1")) {
            assertFalse(ProviderPolicy.usable(JSONObject().put("baseURL", url).put("keyless", true), "key"))
        }
    }
}
