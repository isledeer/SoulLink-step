package com.isledeer.soullink.game

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账本核心纯函数单测（JVM，不依赖 Android / Room）。
 *
 * 重点覆盖交接信里的关键 bug：重新生成 / 分支 / 编辑导致的好感度重复计入。
 */
class GameLedgerCoreTest {

    private var seq = 0L

    private fun event(
        messageId: String,
        kind: String,
        payload: String = "{}",
        targetId: String? = null,
    ): GameEvent {
        seq += 1
        return GameEvent(
            id = "e-$seq-$kind-$messageId",
            sessionId = "s1",
            conversationId = "c1",
            messageId = messageId,
            nodeId = "node-$messageId",
            seq = seq,
            kind = kind,
            targetId = targetId,
            payload = payload,
            createdAt = seq,
        )
    }

    private fun relation(messageId: String, delta: Int): GameEvent =
        event(
            messageId = messageId,
            kind = GameEventKind.RELATION,
            payload = JSONObject().put("delta", delta).put("reason", "test").toString(),
            targetId = "self",
        )

    private fun ids(vararg messageIds: String): Set<String> = messageIds.toSet()

    @Test
    fun onlyActiveVariantCounts() {
        // 同一节点的两个变体：v1 给 +3，v2 给 +5
        val events = listOf(relation("v1", 3), relation("v2", 5))
        assertEquals(3, GameStateOps.fold(GameState.EMPTY, events, ids("v1")).relations["self"] ?: -1)
        assertEquals(5, GameStateOps.fold(GameState.EMPTY, events, ids("v2")).relations["self"] ?: -1)
    }

    @Test
    fun regenerationDoesNotDoubleCount() {
        // 模拟「重新生成」：旧变体事件留在库里，但不在选中路径上
        val events = listOf(relation("v1", 3), relation("v2", 3))
        val state = GameStateOps.fold(GameState.EMPTY, events, ids("v2"))
        assertEquals(3, state.relations["self"] ?: -1)
    }

    @Test
    fun branchesAreIndependent() {
        val events = listOf(
            relation("shared", 5),
            relation("left", 3),
            relation("right", 9),
        )
        val left = GameStateOps.fold(GameState.EMPTY, events, ids("shared", "left"))
        val right = GameStateOps.fold(GameState.EMPTY, events, ids("shared", "right"))
        assertEquals(8, left.relations["self"] ?: -1)
        assertEquals(14, right.relations["self"] ?: -1)
    }

    @Test
    fun deletedMessageStopsCounting() {
        val events = listOf(relation("m1", 7))
        assertEquals(7, GameStateOps.fold(GameState.EMPTY, events, ids("m1")).relations["self"] ?: -1)
        assertNull(GameStateOps.fold(GameState.EMPTY, events, ids()).relations["self"])
    }

    @Test
    fun relationClampsAtBothEnds() {
        val events = listOf(relation("m1", 10), relation("m2", 10), relation("m3", -30))
        // 10 + 10 = 20；-30 被 clamp 到 -10 → 10
        val state = GameStateOps.fold(GameState.EMPTY, events, ids("m1", "m2", "m3"))
        assertEquals(10, state.relations["self"] ?: -1)

        val high = (1..12).map { n -> relation("h$n", 10) }
        val all = GameStateOps.fold(GameState.EMPTY, high, (1..12).map { n -> "h$n" }.toSet())
        assertEquals(100, all.relations["self"] ?: -1)

        val low = listOf(relation("l1", -10), relation("l2", -10))
        val zero = GameStateOps.fold(GameState.EMPTY, low, ids("l1", "l2"))
        assertEquals(0, zero.relations["self"] ?: -1)
    }

    @Test
    fun inventoryUsesDeterministicIdAndMergesQuantity() {
        val events = listOf(
            event("m1", GameEventKind.ITEM_GAIN, JSONObject().put("id", "玉佩").put("name", "玉佩").put("qty", 2).toString()),
            event("m2", GameEventKind.ITEM_GAIN, JSONObject().put("id", "玉佩").put("name", "玉佩").put("qty", 1).toString()),
            event("m3", GameEventKind.ITEM_LOSS, JSONObject().put("id", "玉佩").put("qty", 1).toString()),
        )
        val state = GameStateOps.fold(GameState.EMPTY, events, ids("m1", "m2", "m3"))
        assertEquals(2, state.inventory["玉佩"]?.quantity ?: -1)

        val drained = GameStateOps.fold(GameState.EMPTY, events, ids("m1", "m2", "m3"))
            .let { s -> GameStateOps.apply(s, event("m4", GameEventKind.ITEM_LOSS, JSONObject().put("id", "玉佩").put("qty", 5).toString())) }
        assertNull(drained.inventory["玉佩"])
    }

    @Test
    fun sceneFlagStatTimeApply() {
        val events = listOf(
            event("m1", GameEventKind.SCENE, JSONObject().put("scene", "剑宗山门").toString()),
            event("m2", GameEventKind.FLAG, JSONObject().put("key", "met_lin").put("value", true).toString()),
            event("m3", GameEventKind.STAT, JSONObject().put("key", "cultivation").put("delta", 5).toString()),
            event("m4", GameEventKind.TIME, JSONObject().put("time", "第三日 黄昏").toString()),
        )
        val state = GameStateOps.fold(GameState.EMPTY, events, ids("m1", "m2", "m3", "m4"))
        assertEquals("剑宗山门", state.scene)
        assertEquals("true", state.flags["met_lin"])
        assertEquals(5, state.stats["cultivation"] ?: -1)
        assertEquals("第三日 黄昏", state.worldTime)
    }

    @Test
    fun parsesGameBlockAndStripsIt() {
        val content = "她笑了。\n<game>{\"relation\":{\"target\":\"self\",\"delta\":3},\"choices\":[\"A\",\"B\"]}</game>"
        val (clean, action) = GameActionParser.extract(content)
        assertEquals("她笑了。", clean)
        assertEquals(3, action?.relationDelta ?: -1)
        assertEquals(listOf("A", "B"), action?.choices)
    }

    @Test
    fun invalidBlockIsStrippedWithoutAction() {
        val (clean, action) = GameActionParser.extract("正文<game>不是JSON</game>")
        assertEquals("正文", clean)
        assertNull(action)
    }

    @Test
    fun noBlockLeavesContentUntouched() {
        val content = "普通回复，没有块"
        val (clean, action) = GameActionParser.extract(content)
        assertEquals(content, clean)
        assertNull(action)
    }

    @Test
    fun legacyAffinityDeltaStillWorks() {
        val action = GameAction.fromJson(JSONObject().put("affinity_delta", 7).put("reason", "送礼物"))
        assertEquals(7, action.relationDelta)
        assertEquals("self", action.relationTarget)
        assertEquals("送礼物", action.relationReason)
    }

    @Test
    fun deltaIsClampedOnParse() {
        val action = GameAction.fromJson(JSONObject().put("relation", JSONObject().put("delta", 99)))
        assertEquals(10, action.relationDelta)
        val negative = GameAction.fromJson(JSONObject().put("affinity_delta", -99))
        assertEquals(-10, negative.relationDelta)
    }

    @Test
    fun actionParsesItemsAsStringsAndObjects() {
        val array = org.json.JSONArray().put("玉佩").put(JSONObject().put("name", "灵石").put("qty", 5))
        val action = GameAction.fromJson(JSONObject().put("items_gained", array))
        assertEquals(2, action.itemsGained.size)
        assertEquals("玉佩", action.itemsGained[0].id)
        assertEquals("灵石", action.itemsGained[1].id)
        assertEquals(5, action.itemsGained[1].quantity)
    }

    @Test
    fun emptyActionDetected() {
        assertTrue(GameAction.fromJson(JSONObject()).isEmpty)
        assertTrue(GameAction.fromJson(JSONObject().put("relation", JSONObject().put("delta", 0))).isEmpty)
    }
}