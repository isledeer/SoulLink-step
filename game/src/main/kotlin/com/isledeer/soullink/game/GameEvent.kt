package com.isledeer.soullink.game

/**
 * 账本事件（纯 JVM 模型，append-only）。
 *
 * 与 Room 实体 `GameEventEntity` 字段一一对应；app 层负责两者之间的映射。
 * 关键：`messageId` 指向「消息变体」；派生状态只认当前选中路径上的消息。
 */
data class GameEvent(
    val id: String,
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
 * 世界书条目（纯 JVM 模型）。
 *
 * 与 Room 实体 `WorldEntryEntity` 字段一一对应。
 */
data class WorldEntry(
    val id: String,
    val worldbookId: String,
    val keysJson: String = "[]",
    val content: String,
    val alwaysOn: Boolean = false,
    val priority: Int = 0,
)
