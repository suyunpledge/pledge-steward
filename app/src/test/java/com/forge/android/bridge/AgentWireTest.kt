package com.forge.android.bridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWireTest {
    private val known = setOf("android.ui.click", "android.device.info", "web_search")

    @Test
    fun everyDeclaredName_isAValidOpenAiFunctionName() {
        val tools = NativeAgentEngine.buildToolDefinitions((known + "android.screen.capture" + "fetch_url").sorted())
        for (i in 0 until tools.length()) {
            val name = tools.getJSONObject(i).getJSONObject("function").getString("name")
            assertTrue(name, name.matches(Regex("^[a-zA-Z0-9_-]{1,64}$")))
        }
    }

    @Test
    fun modelNames_mapBackToInternalTools_inEitherSpelling() {
        assertEquals("android.ui.click", AgentWire.resolveToolName("android_ui_click", known))
        assertEquals("android.ui.click", AgentWire.resolveToolName(" android.ui.click ", known))
        assertEquals("web_search", AgentWire.resolveToolName("web_search", known))
        assertEquals("rm_rf", AgentWire.resolveToolName("rm_rf", known))  // unknown stays unknown -> rejected later
    }

    @Test
    fun modelReceipt_dropsPixels_keepsEverythingElse() {
        val pixels = "A".repeat(500_000)
        val receipt = JSONObject().put("ok", true).put("execution_state", "completed")
            .put("result", JSONObject().put("image_width", 720).put("base64_png", pixels).put("source", "shizuku"))
            .put("screenshot_base64", pixels)
        val model = AgentWire.modelReceipt(receipt).toString()
        assertFalse(model.contains("AAAA"))
        assertTrue(model.length < 1_000)
        val inner = AgentWire.modelReceipt(receipt).getJSONObject("result")
        assertEquals(720, inner.getInt("image_width"))
        assertEquals("shizuku", inner.getString("source"))
        assertTrue(inner.getString("base64_png_omitted").startsWith("500000 chars"))
    }

    @Test
    fun hugeTextFields_areCapped_butMarked() {
        val model = AgentWire.modelReceipt(JSONObject().put("xml", "x".repeat(100_000)))
        assertEquals(64_000, model.getString("xml").length)
        assertTrue(model.getBoolean("xml_truncated"))
    }

    @Test
    fun lostReceipts_readsDidNotHappen_writesMayHave() {
        assertEquals("not_started", AgentWire.timeoutState("android.screen.inspect"))
        assertEquals("not_started", AgentWire.timeoutState("android.screen.capture"))
        assertEquals("unknown", AgentWire.timeoutState("android.ui.click"))
        assertEquals("unknown", AgentWire.timeoutState("android.app.launch"))
    }
}
