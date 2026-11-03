package me.rerere.rikkahub.data.repository

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.db.dao.GameEventDao
import me.rerere.rikkahub.data.db.dao.GameSessionDao
import androidx.room.withTransaction
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.WorldBookDao
import me.rerere.rikkahub.data.db.entity.WorldEntryEntity
import org.json.JSONArray
import me.rerere.rikkahub.data.db.entity.GameEventEntity
import me.rerere.rikkahub.data.db.entity.GameSessionEntity
import me.rerere.rikkahub.data.db.entity.WorldBookEntity
import me.rerere.rikkahub.data.model.Conversation
import com.isledeer.soullink.game.GameAction
import com.isledeer.soullink.game.GameActionEvents
import com.isledeer.soullink.game.GameEvent
import com.isledeer.soullink.game.GameState
import com.isledeer.soullink.game.GameStateOps
import com.isledeer.soullink.game.CompiledInjection
import com.isledeer.soullink.game.EntryRow
import com.isledeer.soullink.game.WorldDoc
import com.isledeer.soullink.game.WorldbookTemplates
import com.isledeer.soullink.game.ImportedWorld
import com.isledeer.soullink.game.compileWorldbook
import com.isledeer.soullink.game.normalizeImported
import com.isledeer.soullink.game.updateDoc

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
    private val database: AppDatabase,
) {

    private val mutex = Mutex()

    /**
     * 导入一本世界书：一个事务里写 worldbooks + world_entries。
     * id 全部重新生成，不复用导入文件里的 id；标题重复不合并。
     */
    suspend fun insertImported(world: ImportedWorld): String = database.withTransaction {
        val bookId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        worldBookDao.upsert(
            WorldBookEntity(
                id = bookId,
                title = world.title.ifBlank { "导入的世界书" },
                template = "imported",
                docJson = world.docJson,
                createdAt = now,
                updatedAt = now,
            ),
        )
        val (entries, _) = normalizeImported(world.entries)
        entries.forEach { entry ->
            worldBookDao.upsertEntry(
                WorldEntryEntity(
                    id = UUID.randomUUID().toString(),
                    worldbookId = bookId,
                    keysJson = JSONArray(entry.keys).toString(),
                    content = entry.content,
                    alwaysOn = entry.alwaysOn,
                    priority = entry.priority,
                ),
            )
        }
        bookId
    }

    /** 为某本世界书新建一个存档，返回 sessionId。 */
    suspend fun createSession(
        worldbookId: String,
        title: String,
        initialStateJson: String,
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        sessionDao.upsert(
            GameSessionEntity(
                id = id,
                title = title,
                worldbookId = worldbookId,
                characterId = null,
                initialStateJson = initialStateJson,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return id
    }

    // ---- 世界书（M2-B）----

    fun observeWorldbooks(): Flow<List<WorldBookEntity>> = worldBookDao.observeAll()

    suspend fun worldbook(id: String): WorldBookEntity? = worldBookDao.findById(id)

    /** 新建一本空世界书，返回 id。 */
    suspend fun createWorldbook(title: String): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val name = title.trim().ifBlank { "未命名世界" }
        worldBookDao.upsert(
            WorldBookEntity(
                id = id,
                title = name,
                template = "blank",
                docJson = WorldbookTemplates.blank(template = "blank", title = name),
                createdAt = now,
                updatedAt = now,
            ),
        )
        return id
    }

    /** 保存四个基础字段；docJson 的其余结构不动。 */
    suspend fun saveWorldDoc(
        id: String,
        title: String,
        premise: String,
        setting: String,
        rules: List<String>,
    ) {
        val book = worldBookDao.findById(id) ?: return
        val name = title.trim().ifBlank { "未命名世界" }
        worldBookDao.upsert(
            book.copy(
                title = name,
                docJson = updateDoc(book.docJson, name, premise, setting, rules),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** 编译结果 + 它来自哪本世界书（注入项的稳定 id 要用真正的 worldbookId）。 */
    data class CompiledWorldbook(
        val worldbookId: String,
        val injections: List<CompiledInjection>,
    )

    /**
     * 当前存档编译好的世界书注入项。
     *
     * 一轮请求只调一次（在 ChatRequestFactory.createContext 里），不要放进 buildStep：
     * buildStep 每一步工具调用都会执行。
     */
    suspend fun loadedCompiledInjections(sessionId: String): CompiledWorldbook? {
        val session = sessionDao.findById(sessionId) ?: return null
        val worldbookId = session.worldbookId?.takeIf { it.isNotBlank() } ?: return null
        val book = worldBookDao.findById(worldbookId) ?: return null
        val rows = worldBookDao.entriesOf(worldbookId).map { entry ->
            EntryRow(
                id = entry.id,
                keysJson = entry.keysJson,
                content = entry.content,
                alwaysOn = entry.alwaysOn,
                priority = entry.priority,
            )
        }
        val injections = compileWorldbook(WorldDoc.parse(book.docJson), rows)
        return if (injections.isEmpty()) null else CompiledWorldbook(worldbookId, injections)
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
