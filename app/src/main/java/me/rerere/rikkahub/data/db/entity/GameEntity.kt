package me.rerere.rikkahub.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 游戏存档 = 一本账的容器。
 * worldbookId / characterId 至少一个非空时才启用游戏层。
 */
@Entity(tableName = "game_sessions")
data class GameSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val worldbookId: String? = null,
    val characterId: String? = null,
    val initialStateJson: String = "{}",
    val createdAt: Long,
    val updatedAt: Long,
)

/** 游戏层开关：有世界书或有角色卡才算游戏存档。 */
val GameSessionEntity.isGameEnabled: Boolean
    get() = !worldbookId.isNullOrBlank() || !characterId.isNullOrBlank()

/**
 * 账本事件（append-only）。
 * messageId 指向「消息变体」；派生状态只认当前选中路径上的消息。
 */
@Entity(
    tableName = "game_events",
    indices = [Index("sessionId"), Index("messageId"), Index("nodeId")],
)
data class GameEventEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val conversationId: String,
    val messageId: String,
    val nodeId: String,
    val seq: Long,
    val kind: String,
    val targetId: String? = null,
    val payload: String = "{}",
    val source: String = "TOOL",
    val createdAt: Long,
)

/**
 * 世界书：规则文档（docJson）+ 关键词条目（world_entries）。
 */
@Entity(tableName = "worldbooks")
data class WorldBookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val template: String = "custom",
    val docJson: String = "{}",
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "world_entries", indices = [Index("worldbookId")])
data class WorldEntryEntity(
    @PrimaryKey val id: String,
    val worldbookId: String,
    val keysJson: String = "[]",
    val content: String,
    val alwaysOn: Boolean = false,
    val priority: Int = 0,
)
