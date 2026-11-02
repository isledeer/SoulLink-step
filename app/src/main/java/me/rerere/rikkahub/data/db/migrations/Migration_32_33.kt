package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v32 → v33：SoulLink 游戏层。
 *
 * 只做两件事，不重建表、不改既有列：
 * 1. conversationentity 增加可空列 game_session_id
 * 2. 新建 4 张表：game_sessions / game_events / worldbooks / world_entries
 */
val Migration_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `conversationentity` ADD COLUMN `game_session_id` TEXT")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `game_sessions` (
                `id` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `worldbookId` TEXT,
                `characterId` TEXT,
                `initialStateJson` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `game_events` (
                `id` TEXT NOT NULL,
                `sessionId` TEXT NOT NULL,
                `conversationId` TEXT NOT NULL,
                `messageId` TEXT NOT NULL,
                `nodeId` TEXT NOT NULL,
                `seq` INTEGER NOT NULL,
                `kind` TEXT NOT NULL,
                `targetId` TEXT,
                `payload` TEXT NOT NULL,
                `source` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_events_sessionId` ON `game_events` (`sessionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_events_messageId` ON `game_events` (`messageId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_events_nodeId` ON `game_events` (`nodeId`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `worldbooks` (
                `id` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `template` TEXT NOT NULL,
                `docJson` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `world_entries` (
                `id` TEXT NOT NULL,
                `worldbookId` TEXT NOT NULL,
                `keysJson` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `alwaysOn` INTEGER NOT NULL,
                `priority` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_world_entries_worldbookId` ON `world_entries` (`worldbookId`)")
    }
}
