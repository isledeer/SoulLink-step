package me.rerere.rikkahub.data.repository

import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.db.dao.GameEventDao
import me.rerere.rikkahub.data.db.dao.GameSessionDao
import me.rerere.rikkahub.data.db.dao.WorldBookDao
import me.rerere.rikkahub.data.db.entity.GameEventEntity
import me.rerere.rikkahub.data.db.entity.GameSessionEntity
import me.rerere.rikkahub.data.model.Conversation
import com.isledeer.soullink.game.GameAction
import com.isledeer.soullink.game.GameActionEvents
import com.isledeer.soullink.game.GameEvent
import com.isledeer.soullink.game.GameState
import com.isledeer.soullink.game.GameStateOps
import com.isledeer.soullink.game.CompiledInjection
import com.isledeer.soullink.game.EntryRow
import com.isledeer.soullink.game.WorldDoc
import com.isledeer.soullink.game.compileWorldbook

/**
 * 账本仓库：追加事件、按当前路径派生状态。
 *
 * 状态永远不写回数据库；「当前状态」= 初始状态 + 选中路径上的事件折叠。
 * 因此重新生成 / 分支 / 编辑天然不会重复计入。
 *
 * 纯逻辑在 `:game` 模块（GameStateOps.fold），这里只负责 Room 读写与实体映射。
 */
class GameRepository(
    private val sessionDao: GameSessionDao,
    private val eventDao: GameEventDao,
    private val worldBookDao: WorldBookDao,
) {

    private val mutex = Mutex()

    /**
     * 当前存档编译好的世界书注入项。
     *
     * 一轮请求只调一次（在 ChatRequestFactory.createContext 里），不要放进 buildStep：
     * buildStep 每一步工具调用都会执行。
     */
    suspend fun loadedCompiledInjections(sessionId: String): List<CompiledInjection> {
        val session = sessionDao.findById(sessionId) ?: return emptyList()
        val worldbookId = session.worldbookId?.takeIf { it.isNotBlank() } ?: return emptyList()
        val book = worldBookDao.findById(worldbookId) ?: return emptyList()
        val rows = worldBookDao.entriesOf(worldbookId).map { entry ->
            EntryRow(
                id = entry.id,
                keysJson = entry.keysJson,
                content = entry.content,
                alwaysOn = entry.alwaysOn,
                priority = entry.priority,
            )
        }
        return compileWorldbook(WorldDoc.parse(book.docJson), rows)
    }

    /** 会话绑定的游戏存档；普通聊天返回 null。 */
    suspend fun sessionOf(conversation: Conversation): GameSessionEntity? {
        val sessionId = conversation.gameSessionId ?: return null
        return sessionDao.findById(sessionId)
    }

    /** 当前会话的「选中路径」上的消息 id —— 重新生成/分支后自动变化。 */
    fun activeMessageIds(conversation: Conversation): Set<String> =
        conversation.messageNodes
            .mapNotNull { node -> node.messages.getOrNull(node.selectIndex)?.id?.toString() }
            .toSet()

    /** 按当前选中路径派生状态。 */
    suspend fun derive(session: GameSessionEntity, activeMessageIds: Set<String>): GameState {
        val events = eventDao.forSession(session.id).map { it.toModel() }
        return GameStateOps.fold(GameState.fromInitialJson(session.initialStateJson), events, activeMessageIds)
    }

    /**
     * 用一组动作替换某条消息的全部事件（编辑 / 重新生成共用）。
     * 先删旧事件再写新事件，保证同一条消息不会累积两份。
     */
    suspend fun replaceActions(
        session: GameSessionEntity,
        conversationId: String,
        messageId: String,
        nodeId: String,
        actions: List<GameAction>,
        source: String,
        activeMessageIds: Set<String>,
    ): GameState = mutex.withLock {
        eventDao.deleteForMessage(messageId)
        val seeds = actions.flatMap { GameActionEvents.seeds(it) }
        if (seeds.isNotEmpty()) {
            var seq = eventDao.maxSeq(session.id)
            val now = System.currentTimeMillis()
            eventDao.insertAll(
                seeds.map { seed ->
                    seq += 1
                    GameEventEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = session.id,
                        conversationId = conversationId,
                        messageId = messageId,
                        nodeId = nodeId,
                        seq = seq,
                        kind = seed.kind,
                        targetId = seed.targetId,
                        payload = seed.payload,
                        source = source,
                        createdAt = now,
                    )
                }
            )
        }
        derive(session, activeMessageIds)
    }

    /** 删除某条消息的事件（消息被删除时调用）。 */
    suspend fun deleteForMessage(messageId: String) = eventDao.deleteForMessage(messageId)

    /** 删除整条会话的事件（会话被删除时调用）。 */
    suspend fun deleteForConversation(conversationId: String) =
        eventDao.deleteForConversation(conversationId)

    private fun GameEventEntity.toModel(): GameEvent = GameEvent(
        id = id,
        sessionId = sessionId,
        conversationId = conversationId,
        messageId = messageId,
        nodeId = nodeId,
        seq = seq,
        kind = kind,
        targetId = targetId,
        payload = payload,
        source = source,
        createdAt = createdAt,
    )
}
