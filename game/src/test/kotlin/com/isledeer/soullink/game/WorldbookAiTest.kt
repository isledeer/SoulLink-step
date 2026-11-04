package com.isledeer.soullink.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldbookAiTest {

    private val longText: String = buildString {
        repeat(40) { index ->
            appendLine("## 第${index + 1}节")
            repeat(12) { appendLine("这是第${index + 1}节的第${it + 1}句话，用来撑长度。") }
            appendLine()
        }
    }

    @Test
    fun `short text stays in one chunk`() {
        val text = "短文本。只有一点内容。"
        val chunks = chunkDocument(text)
        assertEquals(1, chunks.size)
        assertEquals(text, chunks[0].text)
    }

    @Test
    fun `long text is split and every chunk respects the limit`() {
        val chunks = chunkDocument(longText, maxChars = 1000, overlap = 100)
        assertTrue("应该切出多块", chunks.size > 1)
        chunks.forEach { chunk ->
            // 允许重叠部分导致略超，但不应超过上限 + 重叠
            assertTrue("块 ${chunk.index} 过长: ${chunk.text.length}", chunk.text.length <= 1000 + 100)
        }
    }

    @Test
    fun `chunks overlap so context is not cut`() {
        val chunks = chunkDocument(longText, maxChars = 800, overlap = 120)
        assertTrue(chunks.size >= 2)
        val prevTail = chunks[0].text.takeLast(60)
        assertTrue("第二块应带上上一块的尾部", chunks[1].text.contains(prevTail))
    }

    @Test
    fun `headings start a new chunk`() {
        val text = buildString {
            appendLine("# 卷一")
            repeat(30) { appendLine("卷一的内容第${it}行，凑长度用。") }
            appendLine("# 卷二")
            repeat(30) { appendLine("卷二的内容第${it}行，凑长度用。") }
        }
        val chunks = chunkDocument(text, maxChars = 600, overlap = 0, threshold = 200)
        assertTrue(chunks.any { it.text.trimStart().startsWith("# 卷二") })
    }

    @Test
    fun `parse plain json`() {
        val raw = """{"doc":{"title":"T","openingScene":"雨夜","openingSituation":"刚到"},"entries":[]}"""
        val result = parseAiWorldbook(raw).getOrThrow()
        assertEquals("T", result.doc?.title)
        assertEquals("雨夜", result.doc?.openingScene)
        assertEquals("刚到", result.doc?.openingSituation)
    }

    @Test
    fun `parse json wrapped in fences and prose`() {
        val raw = """
            好的，以下是结果：
            ```json
            {"entries":[{"keys":["剑"],"content":"剑宗在云端","alwaysOn":true,"priority":5}]}
            ```
            希望对你有帮助。
        """.trimIndent()
        val result = parseAiWorldbook(raw).getOrThrow()
        assertEquals(1, result.entries.size)
        assertEquals(listOf("剑"), result.entries[0].keys)
        assertTrue(result.entries[0].alwaysOn)
        assertEquals(5, result.entries[0].priority)
    }

    @Test
    fun `parse failure keeps raw for repair`() {
        val raw = "这不是 JSON"
        val result = parseAiWorldbook(raw)
        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull()?.message.isNullOrBlank())
    }

    @Test
    fun `ai doc json round trips through WorldDoc`() {
        val doc = AiWorldDoc(
            title = "测试世界",
            premise = "前提",
            setting = "设定",
            tone = "冷峻",
            rules = listOf("规则一"),
            openingScene = "雨夜的书店",
            openingSituation = "你在门口",
        )
        val json = aiDocToDocJson(doc)
        val parsed = WorldDoc.parse(json)
        assertEquals("测试世界", parsed.title)
        assertEquals("前提", parsed.premise)
        assertEquals("设定", parsed.setting)
        assertEquals("冷峻", parsed.tone)
        assertEquals(listOf("规则一"), parsed.rules)
        assertEquals("雨夜的书店", parsed.openingScene)
        assertEquals("你在门口", parsed.openingSituation)
    }

    @Test
    fun `request estimate uses threshold and chunk size`() {
        assertEquals(1, estimateAiRequests(1000))
        assertEquals(1, estimateAiRequests(AI_CHUNK_THRESHOLD))
        // 8000 字 → ceil(8000/4000)=2 块 + 1 次全局
        assertEquals(3, estimateAiRequests(8000))
    }

    @Test
    fun `global input keeps head and tail and respects cap`() {
        val chunks = chunkDocument(longText, maxChars = 1000, overlap = 0)
        val input = WorldbookAiPrompt.buildGlobalInput(chunks, longText)
        assertTrue(input.length <= AI_GLOBAL_MAX_CHARS)
        assertTrue("应包含第一节标题", input.contains("第1节"))
        assertTrue("应包含最后一块内容", input.contains("第40节"))
    }
}
