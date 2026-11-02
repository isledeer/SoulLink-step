package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_32_33_Test {
    private val testDb = "migration-32-33-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        RequerySQLiteOpenHelperFactory(),
    )

    @Test
    fun migratesTo33AndKeepsExistingConversationColumns() {
        // 1) 32.json 与历史版本一致：能按 v32 建库
        helper.createDatabase(testDb, 32).apply {
            execSQL(
                "INSERT INTO ConversationEntity (id, assistant_id, title, nodes, create_at, update_at) " +
                    "VALUES ('conv-1', 'assistant-1', '旧会话', '[]', 1000, 2000)"
            )
            close()
        }

        // 2) DDL 与实体定义一致：迁移到 v33 并通过 Room 校验
        val db = helper.runMigrationsAndValidate(testDb, 33, true, Migration_32_33)

        // 3) 既有列原样保留，新列为 null
        db.query(
            "SELECT id, assistant_id, title, create_at, update_at, game_session_id FROM ConversationEntity"
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("conv-1", cursor.getString(0))
            assertEquals("assistant-1", cursor.getString(1))
            assertEquals("旧会话", cursor.getString(2))
            assertEquals(1000L, cursor.getLong(3))
            assertEquals(2000L, cursor.getLong(4))
            assertNull(cursor.getString(5))
        }

        // 4) 新列可写可读
        db.execSQL("UPDATE ConversationEntity SET game_session_id = 'game-1' WHERE id = 'conv-1'")
        db.query("SELECT game_session_id FROM ConversationEntity WHERE id = 'conv-1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("game-1", cursor.getString(0))
        }

        // 5) 新表可用：game_sessions
        db.execSQL(
            "INSERT INTO game_sessions (id, title, worldbookId, characterId, initialStateJson, createdAt, updatedAt) " +
                "VALUES ('game-1', '测试存档', NULL, NULL, '{}', 1, 2)"
        )
        db.query("SELECT title FROM game_sessions WHERE id = 'game-1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("测试存档", cursor.getString(0))
        }

        // 6) 新表可用：game_events（含 messageId 索引）
        db.execSQL(
            "INSERT INTO game_events (id, sessionId, conversationId, messageId, nodeId, seq, kind, targetId, payload, source, createdAt) " +
                "VALUES ('e1', 'game-1', 'conv-1', 'msg-1', 'node-1', 1, 'RELATION', 'self', '{\"delta\":3}', 'BLOCK', 3)"
        )
        db.query("SELECT COUNT(*) FROM game_events WHERE messageId = 'msg-1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }

        db.close()
    }
}
