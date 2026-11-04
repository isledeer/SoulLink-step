package me.rerere.rikkahub.data.ai.tools

import com.isledeer.soullink.game.GameAction
import com.isledeer.soullink.game.GameState
import com.isledeer.soullink.game.GameStateOps
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.json.JSONObject

/**
 * 把「本回合的状态变化」做成一次工具调用。
 *
 * 关键约束：
 * 1. 参数就是 [GameAction] 的字段，解析直接复用 `GameAction.fromJson`，不另立 schema；
 * 2. [Tool.execute] **绝不写数据库**——落账统一由 `GameLedgerHooks.onTurnFinished` 负责，
 *    只有那里知道 messageId / nodeId，也只有那里能守住"取消不写、只认当前分支"；
 * 3. 返回值只是一句给模型看的确认，可以基于构造时的 [snapshot] 推算"现在多少"。
 */
fun buildGameStateTool(
    sessionId: String,
    conversationId: String,
    snapshot: GameState,
): Tool = Tool(
    name = "update_game_state",
    description = "记录本回合剧情中的状态变化（好感、物品、场景、标记、属性、时间）。没有变化就不要调用。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("relation_target", buildJsonObject {
                    put("type", "string")
                    put("description", "好感变化的对象，默认 self")
                })
                put("relation_delta", buildJsonObject {
                    put("type", "integer")
                    put("description", "好感增减，-10 到 10")
                })
                put("relation_reason", buildJsonObject {
                    put("type", "string")
                    put("description", "一句话说明原因")
                })
                put("scene", buildJsonObject {
                    put("type", "string")
                    put("description", "本回合结束时的场景")
                })
                put("time", buildJsonObject {
                    put("type", "string")
                    put("description", "世界内时间，例如「第三日 清晨」")
                })
                put("items_gained", buildJsonObject { put("type", "array"); put("description", "获得的物品") })
                put("items_lost", buildJsonObject { put("type", "array"); put("description", "失去的物品") })
                put("flags", buildJsonObject { put("type", "object"); put("description", "剧情标记键值对") })
                put("stats", buildJsonObject { put("type", "object"); put("description", "属性增减，键为属性名") })
            }
        )
    },
    systemPrompt = { _, _ -> GameStateOps.summarize(snapshot) },
    execute = { args: JsonElement ->
        val action = runCatching { GameAction.fromJson(JSONObject(args.toString())) }.getOrNull()
        val text = when {
            action == null -> "参数无法解析，本次变化未记录。"
            action.isEmpty -> "没有需要记录的变化。"
            else -> describe(action, snapshot, sessionId)
        }
        listOf(UIMessagePart.Text(text))
    },
)

/** 只做展示：基于快照推算"现多少"，不落库。 */
private fun describe(action: GameAction, snapshot: GameState, sessionId: String): String = buildString {
    append("已记录：")
    val parts = mutableListOf<String>()
    if (action.relationDelta != 0) {
        val target = action.relationTarget.ifBlank { "self" }
        val before = snapshot.relations[target] ?: 0
        val after = (before + action.relationDelta).coerceIn(0, 100)
        parts += "$target 好感 ${if (action.relationDelta > 0) "+" else ""}${action.relationDelta}（现 $after）"
    }
    action.itemsGained.forEach { parts += "获得 ${it.name} ×${it.quantity}" }
    action.itemsLost.forEach { parts += "失去 ${it.name} ×${it.quantity}" }
    action.scene?.takeIf { it.isNotBlank() }?.let { parts += "场景 → $it" }
    action.time?.takeIf { it.isNotBlank() }?.let { parts += "时间 → $it" }
    if (action.flags.isNotEmpty()) parts += "标记 ${action.flags.size} 项"
    if (action.stats.isNotEmpty()) parts += "属性 ${action.stats.size} 项"
    append(if (parts.isEmpty()) "（无实际变化）" else parts.joinToString("；"))
    append("｜存档 $sessionId")
}
