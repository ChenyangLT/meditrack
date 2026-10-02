package com.meditrack.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.meditrack.data.local.entity.ReminderEvent
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes for the reminder audit trail.
 *
 * The table is append-only from the pipeline's point of view; the only deletion is the bounded
 * [prune], which keeps the newest [keep] rows. That is enough to answer "what happened overnight"
 * without turning a medication reminder into an unbounded log sink.
 */
@Dao
interface ReminderEventDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: ReminderEvent): Long

    /** Newest first, for the self-check screen. */
    @Query("SELECT * FROM reminder_events ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int = 50): List<ReminderEvent>

    @Query("SELECT * FROM reminder_events ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<ReminderEvent>>

    /** Everything recorded for one dose, oldest first - the story of a single reminder. */
    @Query("SELECT * FROM reminder_events WHERE doseLogId = :doseLogId ORDER BY timestamp ASC, id ASC")
    suspend fun forDose(doseLogId: Long): List<ReminderEvent>

    /**
     * How many *audible* announcements happened since [sinceMillis].
     *
     * Used by the self-check summary ("近 24 小时：提醒 4 次，静默跳过 1 次") and by the repair pass to
     * notice a dose that keeps being suppressed.
     */
    @Query(
        """
        SELECT COUNT(*) FROM reminder_events
         WHERE timestamp >= :sinceMillis
           AND decisionCode IN (
                 'REMIND', 'REMIND_REPEAT', 'PRE_REMIND', 'CATCH_UP', 'MISSED', 'UNLOCK_CATCH_UP'
               )
        """
    )
    suspend fun countDeliveredSince(sinceMillis: Long): Int

    @Query("SELECT COUNT(*) FROM reminder_events WHERE timestamp >= :sinceMillis AND decisionCode LIKE 'SKIP_%'")
    suspend fun countSuppressedSince(sinceMillis: Long): Int

    /** The most recent entry of any kind, used to prove the pipeline is still alive. */
    @Query("SELECT * FROM reminder_events ORDER BY timestamp DESC, id DESC LIMIT 1")
    suspend fun latest(): ReminderEvent?

    /**
     * Keeps the newest [keep] rows.
     *
     * Bounded by construction: `id` is a monotonically increasing primary key, so this is a single
     * indexed delete with no scan of the retained rows.
     */
    @Query(
        """
        DELETE FROM reminder_events
         WHERE id NOT IN (
             SELECT id FROM reminder_events ORDER BY timestamp DESC, id DESC LIMIT :keep
         )
        """
    )
    suspend fun prune(keep: Int = 500): Int

    @Query("DELETE FROM reminder_events")
    suspend fun deleteAll()
}
