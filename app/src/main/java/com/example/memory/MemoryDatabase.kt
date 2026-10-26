package com.example.memory

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "agent_memories")
data class MemoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val memoryKey: String,
    val category: String, // "CORE_FACT", "PREFERENCE", "EPISODIC", "SYSTEM_RULE", "TASK_STATE"
    val content: String,
    val importance: Int = 4, // 1..5
    val updatedByAgent: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String, // "user", "assistant", "proactive"
    val content: String,
    val reasoningTrace: String = "",
    val toolCallsSummary: String = "",
    val searchCitations: String = "",
    val attachmentPath: String? = null,
    val attachmentName: String? = null,
    val attachmentMime: String? = null,
    val modelUsed: String = "",
    val latencyMs: Long = 0L,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "memory_audit_logs")
data class MemoryAuditLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val operation: String, // "WRITE", "EDIT", "DELETE", "WORKSPACE_OP", "DEVICE_CMD"
    val targetKey: String,
    val details: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface AgentDao {
    @Query("SELECT * FROM agent_memories ORDER BY importance DESC, updatedAt DESC")
    fun observeMemories(): Flow<List<MemoryEntry>>

    @Query("SELECT * FROM agent_memories ORDER BY importance DESC, updatedAt DESC")
    suspend fun getAllMemoriesNow(): List<MemoryEntry>

    @Query("SELECT * FROM agent_memories WHERE LOWER(memoryKey) = LOWER(:key) LIMIT 1")
    suspend fun getMemoryByKey(key: String): MemoryEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(entry: MemoryEntry): Long

    @Update
    suspend fun updateMemory(entry: MemoryEntry)

    @Query("DELETE FROM agent_memories WHERE id = :id")
    suspend fun deleteMemoryById(id: Long)

    @Query("DELETE FROM agent_memories WHERE LOWER(memoryKey) = LOWER(:key)")
    suspend fun deleteMemoryByKey(key: String): Int

    @Query("DELETE FROM agent_memories")
    suspend fun clearAllMemories()

    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    fun observeMessages(): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessages(limit: Int): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(msg: ChatMessageEntity): Long

    @Query("DELETE FROM chat_messages")
    suspend fun clearMessages()

    @Query("SELECT * FROM memory_audit_logs ORDER BY timestamp DESC LIMIT 80")
    fun observeAuditLogs(): Flow<List<MemoryAuditLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAuditLog(log: MemoryAuditLog)
}

@Database(
    entities = [MemoryEntry::class, ChatMessageEntity::class, MemoryAuditLog::class],
    version = 1,
    exportSchema = false
)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun agentDao(): AgentDao
}
