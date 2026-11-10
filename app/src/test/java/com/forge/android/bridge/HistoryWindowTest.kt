package com.forge.android.bridge

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 台阶窗的前缀缓存纪律锚点：窗口起点必须在两个台阶之间跨轮不动（头部边界
 * 字节稳定是服务商前缀命中的前提），任何长度下保留条数不超上限，裁剪点
 * 对齐到完整问答组、不产生孤儿 assistant 消息。
 */
class HistoryWindowTest {

    private fun history(items: Int): JSONArray {
        val array = JSONArray()
        for (i in 0 until items) {
            val role = if (i % 2 == 0) "user" else "assistant"
            array.put(JSONObject().put("role", role).put("content", "m$i"))
        }
        return array
    }

    @Test
    fun window_isStableBetweenSteps_andJumpsInBlocks() {
        assertEquals(0, NativeAgentEngine.historyWindow(history(50)))
        assertEquals(0, NativeAgentEngine.historyWindow(history(100)))
        // 首次超限跳到 20，并在 101..120 内保持不动——期间相邻两轮的前缀
        // 头部逐字节一致；一轮一滑的旧滑窗在这里每轮都变。
        for (len in 101..120) assertEquals(20, NativeAgentEngine.historyWindow(history(len)))
        for (len in 121..140) assertEquals(40, NativeAgentEngine.historyWindow(history(len)))
    }

    @Test
    fun window_neverExceedsTheCap() {
        for (len in intArrayOf(101, 118, 120, 121, 139, 300)) {
            val h = history(len)
            val kept = h.length() - NativeAgentEngine.historyWindow(h)
            assertTrue("len=$len kept=$kept", kept <= 100)
        }
    }

    @Test
    fun window_alignsToUserTurn_noOrphanAssistant() {
        val h = history(105)  // 第一台阶带（101..120）：起点 20
        // 台阶点 20 恰是 assistant：对齐后跳到 21（user），不产生孤儿回答回放
        h.getJSONObject(20).put("role", "assistant")
        h.getJSONObject(21).put("role", "user")
        assertEquals(21, NativeAgentEngine.historyWindow(h))
    }
}
