package com.isledeer.soullink.game

import org.json.JSONArray
import org.json.JSONObject

/**
 * 账本派生出的游戏状态（内存模型，不落库）。
 * 好感度统一放在 relations 里：模式 A 用 "self"，世界书模式用 NPC id。
 */
data class GameState(
    val relations: Map<String, Int> = emptyMap(),
    val inventory: Map<String, InventoryItem> = emptyMap(),
    val scene: String = "",
    val flags: Map<String, String> = emptyMap(),
    val stats: Map<String, Int> = emptyMap(),
    val worldTime: String? = null,
) {
    companion object {
        val EMPTY = GameState()

        /** 解析 GameSessionEntity.initialStateJson */
        fun fromInitialJson(raw: String?): GameState {
            if (raw.isNullOrBlank()) return EMPTY
            return runCatching {
                val obj = JSONObject(raw)
                val relations = mutableMapOf<String, Int>()
                obj.optJSONObject("relations")?.let { rel ->
                    rel.keys().forEach { key -> relations[key] = rel.optInt(key, 0) }
                }
                val inventory = mutableMapOf<String, InventoryItem>()
                obj.optJSONArray("inventory")?.let { array ->
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val id = item.optString("id").ifBlank { item.optString("name") }
                        if (id.isBlank()) continue
                        inventory[id] = InventoryItem(
                            id = id,
                            name = item.optString("name").ifBlank { id },
                            quantity = item.optInt("quantity", 1).coerceAtLeast(1),
                            type = item.optString("type").ifBlank { "key_item" },
                        )
                    }
                }
                val flags = mutableMapOf<String, String>()
                obj.optJSONObject("flags")?.let { map ->
                    map.keys().forEach { key -> flags[key] = map.opt(key).toString() }
                }
                val stats = mutableMapOf<String, Int>()
                obj.optJSONObject("stats")?.let { map ->
                    map.keys().forEach { key -> stats[key] = map.optInt(key, 0) }
                }
                GameState(
                    relations = relations,
                    inventory = inventory,
                    scene = obj.optString("scene"),
                    flags = flags,
                    stats = stats,
                    worldTime = obj.optString("worldTime").takeIf { it.isNotBlank() },
                )
            }.getOrElse { EMPTY }
        }
    }
}

data class InventoryItem(
    val id: String,
    val name: String,
    val quantity: Int = 1,
    val type: String = "key_item",
)

/** GameAction 里的物品变化 */
data class ItemChange(
    val id: String,
    val name: String,
    val quantity: Int = 1,
    val type: String = "key_item",
)

/**
 * 一轮回复产出的状态变化。
 * tool 通道与 <game> 块通道共用同一份结构。
 */
data class GameAction(
    val relationTarget: String = "self",
    val relationDelta: Int = 0,
    val relationReason: String = "",
    val itemsGained: List<ItemChange> = emptyList(),
    val itemsLost: List<ItemChange> = emptyList(),
    val scene: String? = null,
    val flags: Map<String, String> = emptyMap(),
    val stats: Map<String, Int> = emptyMap(),
    val time: String? = null,
    val choices: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = relationDelta == 0 &&
            itemsGained.isEmpty() &&
            itemsLost.isEmpty() &&
            scene.isNullOrBlank() &&
            flags.isEmpty() &&
            stats.isEmpty() &&
            time.isNullOrBlank() &&
            choices.isEmpty()

    companion object {

        /** 兼容两种产出：update_game_state 的 args，以及 <game>{json}</game> 的内容 */
        fun fromJson(obj: JSONObject): GameAction {
            val relation = obj.optJSONObject("relation")
            var target = relation?.optString("target").orEmpty()
            var delta = relation?.optInt("delta", 0) ?: 0
            var reason = relation?.optString("reason").orEmpty()
            // 兼容旧 React 引擎的 affinity_delta 协议
            if (relation == null && obj.has("affinity_delta")) {
                delta = obj.optInt("affinity_delta", 0)
                reason = obj.optString("reason")
            }
            if (target.isBlank()) target = "self"

            val choices = mutableListOf<String>()
            obj.optJSONArray("choices")?.let { array ->
                for (i in 0 until array.length()) {
                    val text = array.optString(i)
                    if (text.isNotBlank()) choices.add(text)
                }
            }

            return GameAction(
                relationTarget = target,
                relationDelta = delta.coerceIn(-10, 10),
                relationReason = reason,
                itemsGained = parseItems(obj.optJSONArray("items_gained")),
                itemsLost = parseItems(obj.optJSONArray("items_lost")),
                scene = obj.optString("scene").takeIf { it.isNotBlank() },
                flags = parseStringMap(obj.optJSONObject("flags")),
                stats = parseIntMap(obj.optJSONObject("stats")),
                time = obj.optString("time").takeIf { it.isNotBlank() },
                choices = choices,
            )
        }

        private fun parseItems(array: JSONArray?): List<ItemChange> {
            if (array == null) return emptyList()
            val list = mutableListOf<ItemChange>()
            for (i in 0 until array.length()) {
                when (val value = array.opt(i)) {
                    is String -> {
                        if (value.isNotBlank()) {
                            list.add(ItemChange(id = value, name = value))
                        }
                    }
                    is JSONObject -> {
                        val name = value.optString("name").ifBlank { value.optString("id") }
                        if (name.isBlank()) continue
                        val id = value.optString("id").ifBlank { name }
                        val qty = value.optInt("qty", value.optInt("quantity", 1)).coerceAtLeast(1)
                        list.add(
                            ItemChange(
                                id = id,
                                name = name,
                                quantity = qty,
                                type = value.optString("type").ifBlank { "key_item" },
                            )
                        )
                    }
                }
            }
            return list
        }

        private fun parseStringMap(obj: JSONObject?): Map<String, String> {
            if (obj == null) return emptyMap()
            val map = mutableMapOf<String, String>()
            obj.keys().forEach { key ->
                val value = obj.opt(key)
                if (value != null && value != JSONObject.NULL) map[key] = value.toString()
            }
            return map
        }

        private fun parseIntMap(obj: JSONObject?): Map<String, Int> {
            if (obj == null) return emptyMap()
            val map = mutableMapOf<String, Int>()
            obj.keys().forEach { key -> map[key] = obj.optInt(key, 0) }
            return map
        }
    }
}

/** 事件种类（game_events.kind） */
object GameEventKind {
    const val RELATION = "RELATION"
    const val ITEM_GAIN = "ITEM_GAIN"
    const val ITEM_LOSS = "ITEM_LOSS"
    const val SCENE = "SCENE"
    const val FLAG = "FLAG"
    const val STAT = "STAT"
    const val TIME = "TIME"
}
