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

    @Test
    fun buildHistory_keepsOrderBudgetAndTurns() {
        val entries = listOf(
            "user" to "第一问", "summary" to "噪音", "agent" to "第一答",
            "error" to "噪音2", "user" to "第二问", "agent" to "第二答").map { it }
        val hist = ChatMode.buildHistory(entries)
        // 保序、只留对话轮
        val out = (0 until hist.length()).map { hist.getJSONObject(it) }
        assertEquals(listOf("user", "assistant", "user", "assistant"), out.map { it.getString("role") })
        assertEquals("第一问", out[0].getString("content"))
        assertEquals("第二答", out[3].getString("content"))
    }

    @Test
    fun buildHistory_dropsOldestWhenOverBudget() {
        val entries = (1..8).flatMap { i -> listOf("user" to "问".repeat(1000), "agent" to "答".repeat(1000)) }
        val hist = ChatMode.buildHistory(entries, maxItems = 20, maxChars = 5000)
        // 预算 5000：从最新往回收，超预算即停——保留最近 ~2 轮且保序
        val total = (0 until hist.length()).sumOf { hist.getJSONObject(it).getString("content").length }
        assertTrue(hist.length() in 2..6)   // 5000 字符预算恰好收 5 条（1000 字/条）
        assertTrue(total <= 5000)
        // 从最新往回收，最旧保留条可能是助手轮（合法：OpenAI 格式允许；引擎台阶窗二次裁剪）
        val roles = (0 until hist.length()).map { hist.getJSONObject(it).getString("role") }
        assertTrue(roles.all { it == "user" || it == "assistant" })
        assertEquals("assistant", roles.last())  // 最新一轮的答案必在
    }

    @Test
    fun sortedEnvelopes_deterministicOrder() {
        val a = "mcp.zeta.tool" to JSONObject().put("x", 1)
        val b = "mcp.alpha.tool" to JSONObject().put("x", 2)
        val s1 = ChatMode.sortedEnvelopes(listOf(a, b))
        val s2 = ChatMode.sortedEnvelopes(listOf(b, a))
        assertEquals(s1.map { it.first }, s2.map { it.first })
        assertEquals("mcp.alpha.tool", s1.first().first)
    }


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
