package com.isledeer.soullink.game

import org.json.JSONObject

/** 一条待落库事件的中间形态（id / seq 由仓库补齐）。 */
data class GameEventSeed(
    val kind: String,
    val targetId: String?,
    val payload: String,
)

/** 把 GameAction 拆成事件种子。 */
object GameActionEvents {

    fun seeds(action: GameAction): List<GameEventSeed> {
        val seeds = mutableListOf<GameEventSeed>()

        if (action.relationDelta != 0) {
            seeds.add(
                GameEventSeed(
                    kind = GameEventKind.RELATION,
                    targetId = action.relationTarget.ifBlank { "self" },
                    payload = JSONObject()
                        .put("delta", action.relationDelta)
                        .put("reason", action.relationReason)
                        .toString(),
                )
            )
        }

        action.itemsGained.forEach { item ->
            seeds.add(
                GameEventSeed(
                    kind = GameEventKind.ITEM_GAIN,
                    targetId = null,
                    payload = JSONObject()
                        .put("id", item.id)
                        .put("name", item.name)
                        .put("qty", item.quantity)
                        .put("type", item.type)
                        .toString(),
                )
            )
        }

        action.itemsLost.forEach { item ->
            seeds.add(
                GameEventSeed(
                    kind = GameEventKind.ITEM_LOSS,
                    targetId = null,
                    payload = JSONObject()
                        .put("id", item.id)
                        .put("qty", item.quantity)
                        .toString(),
                )
            )
        }

        action.scene?.takeIf { it.isNotBlank() }?.let { scene ->
            seeds.add(
                GameEventSeed(
                    kind = GameEventKind.SCENE,
                    targetId = null,
                    payload = JSONObject().put("scene", scene).toString(),
                )
            )
        }

        action.flags.forEach { (key, value) ->
            seeds.add(
                GameEventSeed(
                    kind = GameEventKind.FLAG,
                    targetId = null,
                    payload = JSONObject().put("key", key).put("value", value).toString(),
                )
            )
        }

        action.stats.forEach { (key, delta) ->
            seeds.add(
                GameEventSeed(
                    kind = GameEventKind.STAT,
                    targetId = null,
                    payload = JSONObject().put("key", key).put("delta", delta).toString(),
                )
            )
        }

        action.time?.takeIf { it.isNotBlank() }?.let { time ->
            seeds.add(
                GameEventSeed(
                    kind = GameEventKind.TIME,
                    targetId = null,
                    payload = JSONObject().put("time", time).toString(),
                )
            )
        }

        return seeds
    }
}

/**
 * 状态折叠（纯函数，可在 JVM 单测里直接跑）。
 * 只处理账本事件，不接触 Room / Android。
 */
object GameStateOps {

    private const val RELATION_MIN = 0
    private const val RELATION_MAX = 100

    /** 沿「当前选中路径」折叠事件；activeMessageIds 之外的事件一律忽略。 */
    fun fold(
        initial: GameState,
        events: List<GameEvent>,
        activeMessageIds: Set<String>,
    ): GameState = events
        .asSequence()
        .filter { it.messageId in activeMessageIds }
        .sortedBy { it.seq }
        .fold(initial) { state, event -> apply(state, event) }

    fun apply(state: GameState, event: GameEvent): GameState =
        runCatching { applyUnsafe(state, event) }.getOrElse { state }

    private fun applyUnsafe(state: GameState, event: GameEvent): GameState {
        val payload = if (event.payload.isBlank()) JSONObject() else JSONObject(event.payload)
        return when (event.kind) {
            GameEventKind.RELATION -> {
                val target = event.targetId?.takeIf { it.isNotBlank() } ?: "self"
                val delta = payload.optInt("delta", 0).coerceIn(-10, 10)
                val current = state.relations[target] ?: 0
                val next = (current + delta).coerceIn(RELATION_MIN, RELATION_MAX)
                state.copy(relations = state.relations + (target to next))
            }

            GameEventKind.ITEM_GAIN -> {
                val id = payload.optString("id").ifBlank { payload.optString("name") }
                if (id.isBlank()) {
                    state
                } else {
                    val name = payload.optString("name").ifBlank { id }
                    val qty = payload.optInt("qty", 1).coerceAtLeast(1)
                    val type = payload.optString("type").ifBlank { "key_item" }
                    val existing = state.inventory[id]
                    val merged = if (existing == null) {
                        InventoryItem(id = id, name = name, quantity = qty, type = type)
                    } else {
                        existing.copy(quantity = existing.quantity + qty)
                    }
                    state.copy(inventory = state.inventory + (id to merged))
                }
            }

            GameEventKind.ITEM_LOSS -> {
                val id = payload.optString("id").ifBlank { payload.optString("name") }
                val existing = state.inventory[id]
                if (existing == null) {
                    state
                } else {
                    val qty = payload.optInt("qty", 1).coerceAtLeast(1)
                    val left = existing.quantity - qty
                    val inventory = if (left <= 0) {
                        state.inventory - id
                    } else {
                        state.inventory + (id to existing.copy(quantity = left))
                    }
                    state.copy(inventory = inventory)
                }
            }

            GameEventKind.SCENE -> {
                val scene = payload.optString("scene")
                if (scene.isBlank()) state else state.copy(scene = scene)
            }

            GameEventKind.FLAG -> {
                val key = payload.optString("key")
                if (key.isBlank()) {
                    state
                } else {
                    val value = payload.opt("value")?.toString().orEmpty()
                    state.copy(flags = state.flags + (key to value))
                }
            }

            GameEventKind.STAT -> {
                val key = payload.optString("key")
                if (key.isBlank()) {
                    state
                } else {
                    val delta = payload.optInt("delta", 0)
                    val current = state.stats[key] ?: 0
                    state.copy(stats = state.stats + (key to (current + delta)))
                }
            }

            GameEventKind.TIME -> {
                val time = payload.optString("time")
                if (time.isBlank()) state else state.copy(worldTime = time)
            }

            else -> state
        }
    }
}
