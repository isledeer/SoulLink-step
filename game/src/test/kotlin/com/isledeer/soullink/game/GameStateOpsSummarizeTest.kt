package com.isledeer.soullink.game

import org.junit.Assert.assertTrue
import org.junit.Test

class GameStateOpsSummarizeTest {

    @Test
    fun `empty state says no state yet`() {
        val text = GameStateOps.summarize(GameState.EMPTY)
        assertTrue(text.contains("尚无状态"))
        assertTrue(text.contains("update_game_state"))
    }

    @Test
    fun `full state lists every section`() {
        val state = GameState(
            relations = mapOf("林清" to 12, "沈墨" to -3),
            inventory = mapOf("sword" to InventoryItem("sword", "青锋剑", 1)),
            scene = "剑宗山门",
            flags = mapOf("入门" to "true"),
            stats = mapOf("修为" to 3),
            worldTime = "第三日 清晨",
        )
        val text = GameStateOps.summarize(state)
        assertTrue(text.contains("场景：剑宗山门"))
        assertTrue(text.contains("时间：第三日 清晨"))
        assertTrue(text.contains("林清 12"))
        assertTrue(text.contains("沈墨 -3"))
        assertTrue(text.contains("青锋剑 ×1"))
        assertTrue(text.contains("修为 3"))
    }

    @Test
    fun `partial state hides empty lines`() {
        val text = GameStateOps.summarize(GameState(scene = "山门"))
        assertTrue(text.contains("场景：山门"))
        assertTrue(!text.contains("好感："))
        assertTrue(!text.contains("背包："))
        assertTrue(!text.contains("时间："))
    }
}
