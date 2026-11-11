package com.forge.android.bridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatMemoryTest {

    @Test
    fun cosine_basics() {
        assertEquals(1.0, ChatMemory.cosine(listOf(1.0, 0.0), listOf(2.0, 0.0)), 1e-9)
        assertEquals(0.0, ChatMemory.cosine(listOf(1.0, 0.0), listOf(0.0, 3.0)), 1e-9)
        assertEquals(0.0, ChatMemory.cosine(listOf(1.0), listOf(1.0, 2.0)), 1e-9)  // 维度不匹配
    }

    @Test
    fun topK_ordersAndFilters() {
        val task = listOf(1.0, 0.0)
        val entries = listOf(
            ChatMemory.Entry("无关", listOf(0.0, 1.0), 1),
            ChatMemory.Entry("弱相关", listOf(0.9, 0.1), 2),
            ChatMemory.Entry("强相关", listOf(0.999, 0.04), 3))
        val picks = ChatMemory.topK(task, entries, k = 2, minSim = 0.5)
        assertEquals(listOf("强相关", "弱相关"), picks.map { it.first.text })
        assertTrue(picks.all { it.second >= 0.5 })
    }

    @Test
    fun renderBlock_formatAndNull() {
        assertNull(ChatMemory.renderBlock(emptyList()))
        val block = ChatMemory.renderBlock(listOf(
            ChatMemory.Entry("Q: 咖啡\nA: 拿铁", listOf(1.0), 1) to 0.9))
        assertTrue(block!!.startsWith("[相关历史]"))
        assertTrue(block.contains("Q: 咖啡 A: 拿铁"))   // 换行压平
        assertTrue(block.contains("仅供参考"))
        // 预算封顶：超长条目被截断在块内
        val long = ChatMemory.renderBlock(listOf(
            ChatMemory.Entry("x".repeat(2000), listOf(1.0), 1) to 0.9), maxChars = 100)
        assertTrue(long!!.length <= 200)
    }

    @Test
    fun embedRequestAndParse_roundTrip() {
        val body = ChatMemory.buildEmbedRequest("你好")
        assertTrue(body.contains("bge-m3"))
        assertTrue(body.contains("你好"))
        val parsed = ChatMemory.parseEmbedding(
            "{\"data\":[{\"embedding\":[0.1,0.2,0.3]}]}")
        assertEquals(listOf(0.1, 0.2, 0.3), parsed)
        assertNull(ChatMemory.parseEmbedding("{}"))
        assertNull(ChatMemory.parseEmbedding("not json"))
    }

    @Test
    fun store_appendLoadAndEviction() {
        val f = File.createTempFile("chatmem", ".json")
        try {
            ChatMemory.append(f, ChatMemory.Entry("e1", listOf(1.0, 0.0), 1), cap = 3)
            ChatMemory.append(f, ChatMemory.Entry("e2", listOf(0.0, 1.0), 2), cap = 3)
            ChatMemory.append(f, ChatMemory.Entry("e3", listOf(1.0, 1.0), 3), cap = 3)
            ChatMemory.append(f, ChatMemory.Entry("e4", listOf(1.0, 2.0), 4), cap = 3)
            val loaded = ChatMemory.load(f)
            assertEquals(listOf("e2", "e3", "e4"), loaded.map { it.text })  // e1 被淘汰
            assertEquals(listOf(0.0, 1.0), loaded[0].vec)
        } finally { f.delete() }
    }
}
