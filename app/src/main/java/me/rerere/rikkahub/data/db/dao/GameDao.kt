package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.GameEventEntity
import me.rerere.rikkahub.data.db.entity.GameSessionEntity
import me.rerere.rikkahub.data.db.entity.WorldBookEntity
import me.rerere.rikkahub.data.db.entity.WorldEntryEntity

@Dao
interface GameSessionDao {

    @Query("SELECT * FROM game_sessions WHERE id = :id")
    suspend fun findById(id: String): GameSessionEntity?

    @Query("SELECT * FROM game_sessions ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<GameSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: GameSessionEntity)

    @Query("SELECT COUNT(*) FROM game_sessions WHERE worldbookId = :worldbookId")
    suspend fun countByWorldbook(worldbookId: String): Int

    @Query("DELETE FROM game_sessions WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface GameEventDao {

    @Query("SELECT * FROM game_events WHERE sessionId = :sessionId ORDER BY seq ASC")
    suspend fun forSession(sessionId: String): List<GameEventEntity>

    @Query("SELECT * FROM game_events WHERE messageId = :messageId")
    suspend fun forMessage(messageId: String): List<GameEventEntity>

    @Query("DELETE FROM game_events WHERE messageId = :messageId")
    suspend fun deleteForMessage(messageId: String)

    @Query("DELETE FROM game_events WHERE nodeId = :nodeId")
    suspend fun deleteForNode(nodeId: String)

    @Query("SELECT COALESCE(MAX(seq), 0) FROM game_events WHERE sessionId = :sessionId")
    suspend fun maxSeq(sessionId: String): Long

    @Query("DELETE FROM game_events WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<GameEventEntity>)
}

@Dao
interface WorldBookDao {

    @Query("SELECT * FROM worldbooks ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<WorldBookEntity>>

    @Query("SELECT * FROM worldbooks ORDER BY updatedAt DESC")
    suspend fun loadAll(): List<WorldBookEntity>

    @Query("SELECT * FROM worldbooks WHERE id = :id")
    suspend fun findById(id: String): WorldBookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: WorldBookEntity)

    @Query("DELETE FROM worldbooks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM world_entries WHERE worldbookId = :worldbookId ORDER BY priority DESC, id ASC")
    suspend fun entriesOf(worldbookId: String): List<WorldEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntry(entry: WorldEntryEntity)

    @Query("DELETE FROM world_entries WHERE worldbookId = :worldbookId")
    suspend fun clearEntries(worldbookId: String)

    @Query("DELETE FROM world_entries WHERE id = :id")
    suspend fun deleteEntry(id: String)
}
