package com.isledeer.soullink.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldbookImportTest {

    // ---------- 酒馆 World Info（entries 是对象）----------
    private val tavernWorldInfo = """
        {
          "name": "剑宗世界",
          "entries": {
            "0": {"keys": ["剑", "御剑"], "content": "剑宗山门在云端", "constant": true, "insertion_order": 10},
            "1": {"key": "灵根", "content": "灵根分五行", "constant": false, "insertion_order": 5},
            "2": {"keys": [], "content": "", "constant": false}
          }
        }
    """.trimIndent()

    @Test
    fun `tavern world info is recognised`() {
        val world = sniffAndImport(tavernWorldInfo)!!
        assertEquals("剑宗世界", world.title)
        // constant 的那条被提升为 premise，已从 entries 移除
        assertEquals(1, world.entries.size)
        assertEquals(listOf("灵根"), world.entries[0].keys)
        // 空内容被丢弃
        assertTrue(world.entries.none { it.content.isBlank() })
        // 第一条 constant 进 premise
        assertTrue(WorldDoc.parse(world.docJson).premise.contains("剑宗山门"))
    }

    @Test
    fun `tavern world info with empty entries is still valid`() {
        val world = sniffAndImport("""{"name":"空的","entries":{}}""")!!
        assertEquals("空的", world.title)
        assertTrue(world.entries.isEmpty())
    }

    @Test
    fun `premise entry is removed from entries`() {
        val world = sniffAndImport(tavernWorldInfo)!!
        val premise = WorldDoc.parse(world.docJson).premise.trim()
        assertTrue(premise.isNotEmpty())
        assertTrue(
            "提升为 premise 的条目不应再出现在 entries 里",
            world.entries.none { it.content.trim() == premise },
        )
    }

    // ---------- 文件头嗅探 ----------
    @Test
    fun `sniffFileKind detects png`() {
        val header = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00)
        assertEquals(FileKind.PNG, sniffFileKind(header))
    }

    @Test
    fun `sniffFileKind detects zip`() {
        val header = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)
        assertEquals(FileKind.ZIP, sniffFileKind(header))
    }

    @Test
    fun `sniffFileKind treats json as text`() {
        assertEquals(FileKind.TEXT, sniffFileKind("""{"a":1}""".toByteArray()))
    }

    @Test
    fun `sniffFileKind treats empty header as unknown`() {
        assertEquals(FileKind.UNKNOWN, sniffFileKind(ByteArray(0)))
    }

    // ---------- 酒馆角色卡 v2 ----------
    private val charaV2 = """
        {
          "spec": "chara_card_v2",
          "data": {
            "name": "猫谜",
            "description": "十九岁的短尾猫娘",
            "scenario": "雨夜的旧书店",
            "system_prompt": "保持角色设定\n不要出戏\n\n用第一人称",
            "personality": "三分傲娇七分温柔",
            "first_mes": "你来了。",
            "character_book": {
              "entries": [
                {"keys": ["书店"], "content": "书店在地下二层", "constant": true, "insertion_order": 7}
              ]
            }
          }
        }
    """.trimIndent()

    @Test
    fun `tavern character v2 maps every field`() {
        val world = sniffAndImport(charaV2)!!
        assertEquals("猫谜", world.title)
        val doc = WorldDoc.parse(world.docJson)
        assertEquals("十九岁的短尾猫娘", doc.premise)
        assertEquals("雨夜的旧书店", doc.setting)
        assertEquals("三分傲娇七分温柔", doc.tone)
        assertEquals(listOf("保持角色设定", "不要出戏", "用第一人称"), doc.rules)
        assertEquals("你来了。", doc.openingScene)
        assertEquals(1, world.entries.size)
        assertEquals("书店", world.entries[0].keys.first())
    }

    @Test
    fun `tavern character without character book has no entries`() {
        val world = sniffAndImport("""{"spec":"chara_card_v3","data":{"name":"无书","description":"只有人设"}}""")!!
        assertEquals("无书", world.title)
        assertTrue(world.entries.isEmpty())
    }

    // ---------- Rikka Lorebook ----------
    @Test
    fun `rikka lorebook uses keywords and constantActive`() {
        val json = """
            {
              "name": "灵界",
              "description": "亡者的世界",
              "entries": [
                {"keywords": ["灵魂"], "content": "灵魂不能久留", "constantActive": true, "priority": 9}
              ]
            }
        """.trimIndent()
        val world = sniffAndImport(json)!!
        assertEquals("灵界", world.title)
        assertEquals("亡者的世界", WorldDoc.parse(world.docJson).premise)
        assertEquals(listOf("灵魂"), world.entries[0].keys)
        assertTrue(world.entries[0].alwaysOn)
        assertEquals(9, world.entries[0].priority)
    }

    @Test
    fun `keys and keywords are both honoured in the same array`() {
        val json = """
            {"entries":[
              {"keywords":["甲"],"content":"第一条"},
              {"keys":["乙"],"content":"第二条"}
            ]}
        """.trimIndent()
        val world = sniffAndImport(json)!!
        assertEquals(listOf("甲"), world.entries[0].keys)
        assertEquals(listOf("乙"), world.entries[1].keys)
    }

    // ---------- SoulLink 自己的 docJson ----------
    @Test
    fun `soullink doc json is passed through`() {
        val doc = updateDoc(null, "自建世界", "前提", "设定", listOf("规则一"))
        val world = sniffAndImport(doc)!!
        assertEquals("自建世界", world.title)
        assertTrue(world.entries.isEmpty())
        assertEquals(listOf("规则一"), WorldDoc.parse(world.docJson).rules)
    }

    // ---------- 负向 ----------
    @Test
    fun `invalid json returns null`() {
        assertNull(sniffAndImport("{not json"))
    }

    @Test
    fun `unknown shape returns null instead of guessing`() {
        assertNull(sniffAndImport("""{"foo":1,"bar":[]}"""))
    }

    // ---------- 护栏 ----------
    @Test
    fun `more than 500 entries are truncated`() {
        val entries = (1..520).map { ImportedEntry(keys = listOf("k$it"), content = "c$it") }
        val (normalized, truncated) = normalizeImported(entries)
        assertTrue(truncated)
        assertEquals(IMPORT_MAX_ENTRIES, normalized.size)
    }

    @Test
    fun `single entry content is capped`() {
        val long = "x".repeat(IMPORT_MAX_CONTENT_CHARS + 100)
        val (normalized, _) = normalizeImported(listOf(ImportedEntry(content = long)))
        assertEquals(IMPORT_MAX_CONTENT_CHARS, normalized[0].content.length)
    }

    @Test
    fun `title alone does not count as content`() {
        val world = sniffAndImport("""{"name":"空世界","entries":{}}""")!!
        assertEquals("空世界", world.title)
        assertTrue("只有书名、没有设定与条目时应判空", world.isEffectivelyEmpty())
    }

    @Test
    fun `empty document is rejected as unusable`() {
        val world = ImportedWorld(title = "", docJson = updateDoc(null, "", "", "", emptyList()))
        assertTrue(world.isEffectivelyEmpty())
        assertNotNull(sniffAndImport(tavernWorldInfo))
    }
}
