package com.forge.android.bridge

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 前缀缓存纪律的 JVM 侧锚点：tools 面必须按名称有序构建，任何插入顺序都
 * 得到同一工具序列——顺序漂移会让服务商前缀缓存逐轮失效。
 */
class NativeAgentEngineTest {

    private fun names(tools: JSONArray): List<String> =
        (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") }

    @Test
    fun toolDefinitions_areSorted_andOrderStable() {
        val a = NativeAgentEngine.buildToolDefinitions(listOf("android.ui.click", "android.screen.capture", "android.app.launch").sorted())
        val b = NativeAgentEngine.buildToolDefinitions(listOf("android.app.launch", "android.screen.capture", "android.ui.click").sorted())
        assertEquals(listOf("android.app.launch", "android.screen.capture", "android.ui.click"), names(a))
        assertEquals(names(a), names(b))
    }

    @Test
    fun toolDefinitions_wrapEveryToolInFunctionEnvelope() {
        val tools = NativeAgentEngine.buildToolDefinitions(listOf("android.device.info", "android.ui.click"))
        assertEquals(2, tools.length())
        for (i in 0 until tools.length()) {
            val tool = tools.getJSONObject(i)
            assertEquals("function", tool.getString("type"))
            val fn = tool.getJSONObject("function")
            assertEquals("object", fn.getJSONObject("parameters").getString("type"))
            assert(fn.getString("description").isNotBlank())
        }
    }
}
