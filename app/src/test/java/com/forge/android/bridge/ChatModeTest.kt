package com.forge.android.bridge

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatModeTest {

    private fun names(tools: JSONArray) =
        (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") }

    @Test
    fun chatTools_containOnlyWebWorkbenchAndMcp() {
        val mcp = McpClient.envelope("weather", JSONObject().put("name", "get_forecast")
            .put("description", "weather forecast"))
        assertNotNull(mcp)
        val tools = ChatMode.chatToolDefinitions(listOf(mcp!!))
        // 信封上是 wire 名（下划线）——OpenAI 兼容接口只认 ^[a-zA-Z0-9_-]+$
        assertEquals(listOf("web_search", "fetch_url", "workbench_write", "workbench_read",
            "mcp_weather_get_forecast"), names(tools))
        assertFalse(names(tools).any { it.startsWith("android") })
        assertFalse(names(tools).any { "." in it })
    }

    @Test
    fun wireNames_resolveBackToInternal() {
        val known = ChatMode.chatToolNames(listOf("mcp.echo.hello"))
        // 模型回传 wire 名 → 解析回内部名（带点），才能过 mcp. 前缀门禁与分发
        assertEquals("mcp.echo.hello", AgentWire.resolveToolName("mcp_echo_hello", known))
        assertEquals("workbench.write", AgentWire.resolveToolName("workbench_write", known))
        assertTrue(ChatMode.isChatTool(AgentWire.resolveToolName("mcp_echo_hello", known)))
    }

    @Test
    fun chatGate_rejectsDeviceTools() {
        assertTrue(ChatMode.isChatTool("web_search"))
        assertTrue(ChatMode.isChatTool("mcp.a.b"))
        assertFalse(ChatMode.isChatTool("android.ui.click"))
        assertFalse(ChatMode.isChatTool("android.device.info"))
    }

    @Test
    fun mcpEnvelope_nameValidation() {
        assertNull(McpClient.envelope("c", JSONObject().put("name", "")))
        assertNull(McpClient.envelope("c", JSONObject().put("name", "bad name!")))
        assertNotNull(McpClient.envelope("c", JSONObject().put("name", "ok_tool-1")))
    }

    @Test
    fun sseParsing_takesLastIdMatch() {
        val body = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"a\":1}}\n\n" +
            "data: {\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"a\":2}}\n"
        val parsed = McpClient.parseResponse(body, 7)
        assertEquals(2, parsed?.getJSONObject("result")?.getInt("a"))
        assertNull(McpClient.parseResponse(body, 8))
    }

    @Test
    fun jsonParsing_plainBody() {
        val parsed = McpClient.parseResponse("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{}}", 3)
        assertNotNull(parsed)
    }

    @Test
    fun mcpReceipts_areUntrusted() {
        val receipt = JSONObject().put("result", JSONObject().put("text", "INJECT ME"))
        val content = AgentWire.toolMessageContent("mcp.weather.get_forecast", receipt)
        assertTrue(content.startsWith("<external-data source="))
        assertTrue(content.contains("untrusted third-party data"))
        // 工作台是用户/助理自己的草稿，不在不可信集
        val wb = AgentWire.toolMessageContent("workbench.read", JSONObject().put("content", "plan"))
        assertFalse(wb.contains("<external-data"))
    }

    @Test
    fun connectors_onlyHttpEnabled() {
        val json = JSONObject().put("connectors", JSONArray(listOf(
            JSONObject().put("name", "a").put("url", "https://x.example/mcp").put("enabled", true),
            JSONObject().put("name", "b").put("url", "https://y.example/mcp").put("enabled", false),
            JSONObject().put("name", "c").put("command", JSONArray(listOf("npx", "server"))).put("enabled", true)
        )))
        val tmp = kotlin.io.path.createTempFile("conn", ".json").toFile()
        tmp.writeText(json.toString())
        val loaded = McpClient.loadConnectors(tmp)
        assertEquals(listOf("a"), loaded.map { it.name })
        tmp.delete()
    }
}
