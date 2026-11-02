package me.rerere.rikkahub.service

import android.util.Log
import com.isledeer.soullink.game.GameActionParser
import kotlin.uuid.Uuid
import me.rerere.ai.agent.AgentFinishReason
import me.rerere.rikkahub.data.repository.GameRepository

/**
 * 游戏层唯一的侵入点：一轮生成结束后把动作落成事件。
 *
 * 契约（M1 起生效）：
 * - `conversation.gameSessionId == null` 时直接 return，普通聊天零开销
 * - 只有正常完成才落账；取消 / 报错 / 等待审批一律不写
 * - 整段 try/catch，任何异常只写日志，绝不上抛
 */
object GameLedgerHooks {

    @Volatile
    private var repository: GameRepository? = null

    /** 由 Koin 在应用启动时注入一次。 */
    fun install(repository: GameRepository) {
        this.repository = repository
    }

    suspend fun onTurnFinished(
        session: ConversationSession,
        replyNodeId: Uuid,
        finishReason: AgentFinishReason?,
    ) {
        val repo = repository ?: return
        val conversation = runCatching { session.state.value }.getOrNull() ?: return
        if (conversation.gameSessionId == null) return
        if (finishReason == null || finishReason is AgentFinishReason.AwaitingApproval) return

        try {
            val node = conversation.messageNodes.lastOrNull { it.id == replyNodeId } ?: return
            val message = node.messages.getOrNull(node.selectIndex) ?: return
            val gameSession = repo.sessionOf(conversation) ?: return

            val action = GameActionParser.extract(message.toText()).second ?: return
            repo.replaceActions(
                session = gameSession,
                conversationId = conversation.id.toString(),
                messageId = message.id.toString(),
                nodeId = node.id.toString(),
                actions = listOf(action),
                source = "BLOCK",
                activeMessageIds = repo.activeMessageIds(conversation),
            )
        } catch (t: Throwable) {
            Log.e("GameLedgerHooks", "ledger write failed, ignored", t)
        }
    }
}
