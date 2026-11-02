package com.isledeer.soullink.game

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldbookDocOpsTest {

    @Test
    fun `update keeps other structures and replaces four fields`() {
        val blank = WorldbookTemplates.blank(template = "xianxia", title = "旧标题")
        val updated = updateDoc(blank, "新标题", "前提", "设定", listOf("规则一", "规则二"))

        val root = JSONObject(updated)
        assertEquals("新标题", root.getJSONObject("meta").getString("title"))
        assertEquals("前提", root.getJSONObject("meta").getString("premise"))
        assertEquals("设定", root.getJSONObject("world").getString("setting"))
        assertEquals(2, root.getJSONObject("world").getJSONArray("rules").length())
        // 模板字段没被抹掉
        assertEquals("xianxia", root.getString("template"))
        // 能被 WorldDoc 正确解析回来
        val doc = WorldDoc.parse(updated)
        assertEquals("新标题", doc.title)
        assertEquals(listOf("规则一", "规则二"), doc.rules)
    }

    @Test
    fun `blank rules are dropped and broken json degrades to new doc`() {
        val out = updateDoc("{not json", "T", "", "", listOf(" ", ""))
        val doc = WorldDoc.parse(out)
        assertEquals("T", doc.title)
        assertTrue(doc.rules.isEmpty())
    }
}
