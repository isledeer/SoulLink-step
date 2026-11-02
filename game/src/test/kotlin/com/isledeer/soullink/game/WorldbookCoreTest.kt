package com.isledeer.soullink.game

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 世界书核心纯函数单测：文档解析 / 条目激活 / 初始状态 / 表单更新。
 */
class WorldbookCoreTest {

    private val docJson = """
        {
          "version": 1,
          "template": "cultivation",
          "meta": {"title": "末法剑歌", "premise": "末法剑修"},
          "world": {
            "setting": "灵气枯竭的末法时代",
            "rules": ["不可弑师", "灵力折寿"],
            "tone": "冷峻简练",
            "opening": {"scene": "剑宗山门", "situation": "山门大雪"}
          },
          "entities": {
            "npcs": [
              {"id": "npc_lin", "name": "林清雪", "role": "大师姐", "brief": "沉默寡言", "initialRelation": 10},
              {"id": "npc_mo", "name": "墨长老", "role": "执事", "brief": "老谋深算", "initialRelation": -5}
            ],
            "items": [{"id": "item_jade", "name": "玉佩", "type": "key_item", "brief": ""}]
          }
        }
    """.trimIndent()

    private fun entry(
        id: String,
        keys: String,
        content: String,
        alwaysOn: Boolean = false,
        priority: Int = 0,
    ): WorldEntry = WorldEntry(
        id = id,
        worldbookId = "wb1",
        keysJson = keys,
        content = content,
        alwaysOn = alwaysOn,
        priority = priority,
    )

    @Test
    fun parsesDocFields() {
        val doc = WorldDoc.parse(docJson)
        assertEquals("末法剑歌", doc.title)
        assertEquals("末法剑修", doc.premise)
        assertEquals(2, doc.rules.size)
        assertEquals("剑宗山门", doc.openingScene)
        assertEquals(2, doc.npcs.size)
        assertEquals("林清雪", doc.npcs[0].name)
        assertEquals(10, doc.npcs[0].initialRelation)
        assertEquals(1, doc.items.size)
    }

    @Test
    fun invalidDocFallsBackToEmpty() {
        val doc = WorldDoc.parse("{not json")
        assertEquals("", doc.title)
        assertTrue(doc.npcs.isEmpty())
    }

    @Test
    fun activationPrefersAlwaysOnAndKeywords() {
        val entries = listOf(
            entry("e1", "[\"剑宗\"]", "剑宗是天下第一大宗", priority = 5),
            entry("e2", "[\"御剑\"]", "御剑术以气驭剑", priority = 1),
            entry("e3", "[]", "常驻设定：世界处于末法", alwaysOn = true, priority = 0),
            entry("e4", "[\"魔教\"]", "魔教盘踞北境", priority = 9),
        )
        val active = WorldbookPrompt.activate(entries, "我来到剑宗山门，想学御剑")
        val ids = active.map { it.id }
        assertTrue(ids.contains("e3"))
        assertTrue(ids.contains("e1"))
        assertTrue(ids.contains("e2"))
        assertTrue(!ids.contains("e4"))
    }

    @Test
    fun activationRespectsBudget() {
        val longText = "长".repeat(600)
        val entries = listOf(
            entry("a", "[\"剑\"]", longText, alwaysOn = true),
            entry("b", "[\"剑\"]", longText),
            entry("c", "[\"剑\"]", longText),
        )
        val active = WorldbookPrompt.activate(entries, "剑", budgetChars = 700)
        // 常驻必选 + 预算内最多再放一条
        assertTrue(active.size <= 2)
        assertEquals("a", active.first().id)
    }

    @Test
    fun initialStateCarriesNpcRelationsAndScene() {
        val doc = WorldDoc.parse(docJson)
        val state = GameState.fromInitialJson(WorldbookTemplates.buildInitialState(doc))
        assertEquals(10, state.relations["npc_lin"] ?: -1)
        assertEquals(-5, state.relations["npc_mo"] ?: -99)
        assertEquals("剑宗山门", state.scene)
    }

    @Test
    fun updateDocKeepsEntities() {
        val updated = WorldbookTemplates.updateDoc(
            original = docJson,
            title = "新名字",
            premise = "新前提",
            setting = "新设定",
            rules = listOf("新规则"),
            tone = "新基调",
            openingScene = "新场景",
            openingSituation = "新事件",
        )
        val doc = WorldDoc.parse(updated)
        assertEquals("新名字", doc.title)
        assertEquals("新设定", doc.setting)
        assertEquals(listOf("新规则"), doc.rules)
        assertEquals(2, doc.npcs.size)

        val root = JSONObject(updated)
        assertNotNull(root.optJSONObject("entities"))
    }

    @Test
    fun blankTemplateIsValidJson() {
        val blank = WorldbookTemplates.blank("custom", "空白世界")
        val doc = WorldDoc.parse(blank)
        assertEquals("空白世界", doc.title)
        val root = JSONObject(blank)
        assertNotNull(root.optJSONObject("world"))
    }
}