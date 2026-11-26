/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.schedule

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.coroutines.flow.Flow

/**
 * Lifecycle of a scheduled message (see `docs/scheduled-messages-plan.md`). A job that was [SENDING] when the
 * process died becomes [UNCERTAIN] and is never retried on its own: a message must not be sent twice.
 */
enum class ScheduleStatus { SCHEDULED, DUE, SENDING, SENT, FAILED, UNCERTAIN, CANCELLED }

/**
 * One message the user scheduled. Stored on the device only; the text never goes to the AIChat backend.
 * [packageName] and [contactLabel] are what the chat looked like when the message was scheduled: the sender must
 * find exactly that chat again before it sends anything.
 */
@Entity(tableName = "scheduled_messages")
data class ScheduledMessage(
    @PrimaryKey val id: String,
    val packageName: String,
    val contactLabel: String,
    val text: String,
    val sendAtEpochMs: Long,
    val status: ScheduleStatus,
    /** A reason code, never message content. */
    val failureReason: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Dao
interface ScheduledMessageDao {
    @Insert
    suspend fun insert(message: ScheduledMessage)

    @Query("SELECT * FROM scheduled_messages ORDER BY sendAtEpochMs ASC")
    fun observeAll(): Flow<List<ScheduledMessage>>

    @Query("SELECT * FROM scheduled_messages WHERE status = 'SCHEDULED' ORDER BY sendAtEpochMs ASC")
    suspend fun pending(): List<ScheduledMessage>

    @Query("UPDATE scheduled_messages SET status = 'CANCELLED', updatedAtEpochMs = :now WHERE id = :id AND status = 'SCHEDULED'")
    suspend fun cancel(id: String, now: Long): Int
}

internal class ScheduleConverters {
    @TypeConverter
    fun fromStatus(status: ScheduleStatus): String = status.name

    @TypeConverter
    fun toStatus(name: String): ScheduleStatus = ScheduleStatus.valueOf(name)
}

@Database(entities = [ScheduledMessage::class], version = 1)
@TypeConverters(ScheduleConverters::class)
abstract class ScheduleDatabase : RoomDatabase() {
    abstract fun messages(): ScheduledMessageDao

    companion object {
        @Volatile
        private var instance: ScheduleDatabase? = null

        fun get(context: Context): ScheduleDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ScheduleDatabase::class.java,
                    "aichat_scheduled_messages",
                ).build().also { instance = it }
            }
    }
}
