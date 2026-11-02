package com.isledeer.soullink.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldbookCompileTest {

    private fun doc(
        premise: String = "",
        setting: String = "",
        tone: String = "",
        rules: List<String> = emptyList(),
        npcs: List<WorldNpc> = emptyList(),
        items: List<WorldItem> = emptyList(),
    ) = WorldDoc(
        title = "测试世界",
        premise = premise,
        setting = setting,
        tone = tone,
        rules = rules,
        npcs = npcs,
        items = items,
    )

    private fun row(
        id: String,
        content: String,
        priority: Int = 0,
        alwaysOn: Boolean = false,
        keys: String = "[]",
    ) = EntryRow(id = id, keysJson = keys, content = content, alwaysOn = alwaysOn, priority = priority)

    @Test
    fun `rules segment is merged and stays first`() {
        val out = compileWorldbook(
            doc(premise = "剑与魔法", setting = "大陆东侧", tone = "冷峻", rules = listOf("不得飞天", "货币是灵石")),
            listOf(row("e1", "条目一", priority = 5)),
        )
        assertEquals(RULES_ENTRY_NAME, out.first().name)
        assertTrue(out.first().alwaysOn)
        val text = out.first().content
        assertTrue(text.contains("剑与魔法"))
        assertTrue(text.contains("大陆东侧"))
        assertTrue(text.contains("冷峻"))
        assertTrue(text.contains("- 不得飞天"))
        assertTrue(text.contains("- 货币是灵石"))
    }

    @Test
    fun `budget cuts entries but never the rules`() {
        val entries = (1..5).map { row("e$it", "x".repeat(40), priority = 10 - it) }
        val out = compileWorldbook(doc(premise = "规则"), entries, budgetChars = 100)
        assertEquals(RULES_ENTRY_NAME, out.first().name)
        // 100 字预算 → 只能放下 2 条 40 字
        assertEquals(3, out.size)
    }

    @Test
    fun `zero budget keeps only pinned segments`() {
        val out = compileWorldbook(doc(premise = "规则"), listOf(row("e1", "任意内容")), budgetChars = 0)
        assertEquals(1, out.size)
        assertEquals(RULES_ENTRY_NAME, out[0].name)
    }

    @Test
    fun `blank entries are skipped`() {
        val out = compileWorldbook(
            doc(),
            listOf(row("e1", ""), row("e2", "   "), row("e3", "有效")),
        )
        assertEquals(1, out.size)
        assertEquals("e3", out[0].name)
    }

    @Test
    fun `entries are ordered by priority descending`() {
        val out = compileWorldbook(
            doc(),
            listOf(
                row("low", "低", priority = 1),
                row("high", "高", priority = 9),
                row("mid", "中", priority = 5),
            ),
        )
        assertEquals(listOf("high", "mid", "low"), out.map { it.name })
    }

    @Test
    fun `npcs and items become always-on injections`() {
        val out = compileWorldbook(
            doc(
                npcs = listOf(WorldNpc(id = "n1", name = "师姐", brief = "温柔但嘴硬")),
                items = listOf(WorldItem(id = "i1", name = "玉佩", brief = "祖传信物")),
            ),
            emptyList(),
        )
        val npc = out.first { it.name == "npc:师姐" }
        val item = out.first { it.name == "item:玉佩" }
        assertTrue(npc.alwaysOn)
        assertTrue(item.alwaysOn)
        assertEquals("温柔但嘴硬", npc.content)
        assertEquals("祖传信物", item.content)
    }

    @Test
    fun `bad keys json degrades without dropping entry`() {
        val out = compileWorldbook(doc(), listOf(row("e1", "内容", alwaysOn = true, keys = "{not json")))
        assertEquals(1, out.size)
        val only = out[0]
        assertTrue(only.name.endsWith(BAD_KEYS_SUFFIX))
        assertEquals("内容", only.content)
        assertFalse(only.alwaysOn)
        assertTrue(only.keywords.isEmpty())
    }

    @Test
    fun `keys are parsed into keywords`() {
        val out = compileWorldbook(doc(), listOf(row("e1", "内容", keys = """["剑","御剑"]""")))
        assertEquals(listOf("剑", "御剑"), out[0].keywords)
        assertTrue(out[0].name == "e1")
    }

    @Test
    fun `exact budget is kept`() {
        val out = compileWorldbook(doc(), listOf(row("e1", "x".repeat(20))), budgetChars = 20)
        assertEquals(1, out.size)
    }

    @Test
    fun `empty doc and entries produce empty list`() {
        assertTrue(compileWorldbook(doc(), emptyList()).isEmpty())
    }
}
