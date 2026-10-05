package com.meditrack.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.meditrack.data.local.entity.Medication
import com.meditrack.data.local.entity.MedicationWithSchedules
import com.meditrack.data.local.entity.Schedule
import kotlinx.coroutines.flow.Flow

@Dao
interface MedicationDao {

    @Transaction
    @Query("SELECT * FROM medications ORDER BY isActive DESC, name COLLATE NOCASE ASC")
    fun observeAllWithSchedules(): Flow<List<MedicationWithSchedules>>

    @Transaction
    @Query("SELECT * FROM medications WHERE isActive = 1 ORDER BY name COLLATE NOCASE ASC")
    fun observeActiveWithSchedules(): Flow<List<MedicationWithSchedules>>

    /** One-shot read used by the planner and the reminder scheduler. */
    @Transaction
    @Query("SELECT * FROM medications WHERE isActive = 1")
    suspend fun getActiveWithSchedulesOnce(): List<MedicationWithSchedules>

    @Transaction
    @Query("SELECT * FROM medications WHERE id = :id")
    suspend fun getWithSchedulesById(id: Long): MedicationWithSchedules?

    @Query("SELECT * FROM medications WHERE id = :id")
    fun observeById(id: Long): Flow<Medication?>

    @Query("SELECT * FROM medications")
    suspend fun getAllOnce(): List<Medication>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(medication: Medication): Long

    @Update
    suspend fun update(medication: Medication)

    @Delete
    suspend fun delete(medication: Medication)

    @Query("DELETE FROM medications WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE medications SET isActive = :active, updatedAt = :now WHERE id = :id")
    suspend fun setActive(id: Long, active: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE medications SET stockAmount = :amount, updatedAt = :now WHERE id = :id")
    suspend fun setStock(id: Long, amount: Double, now: Long = System.currentTimeMillis())

    @Query("UPDATE medications SET stockAmount = stockAmount + :delta, updatedAt = :now WHERE id = :id")
    suspend fun adjustStock(id: Long, delta: Double, now: Long = System.currentTimeMillis())

    // ------------------------------------------------------------- schedules

    @Query("SELECT * FROM schedules WHERE medicationId = :medicationId ORDER BY minuteOfDay ASC")
    suspend fun getSchedulesFor(medicationId: Long): List<Schedule>

    @Query("SELECT * FROM schedules WHERE medicationId = :medicationId ORDER BY minuteOfDay ASC")
    fun observeSchedulesFor(medicationId: Long): Flow<List<Schedule>>

    @Query("SELECT * FROM schedules")
    suspend fun getAllSchedulesOnce(): List<Schedule>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSchedule(schedule: Schedule): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSchedules(schedules: List<Schedule>): List<Long>

    @Update
    suspend fun updateSchedule(schedule: Schedule)

    @Query("DELETE FROM schedules WHERE id = :id")
    suspend fun deleteScheduleById(id: Long)

    @Query("DELETE FROM schedules WHERE medicationId = :medicationId")
    suspend fun deleteSchedulesFor(medicationId: Long)

    /** Wipes the medication table; the schedules cascade away with it. Used by "clear all data". */
    @Query("DELETE FROM medications")
    suspend fun deleteAllMedications()

    /**
     * Every per-medication ringtone override in use.
     *
     * Exists so [com.meditrack.data.repository.RingClipRepository] can work out which clip files are
     * still referenced without depending on the medication repository - which depends on *it*, and that
     * cycle would be a Dagger error surfaced at the worst possible time.
     */
    @Query("SELECT DISTINCT customRingClipId FROM medications WHERE customRingClipId IS NOT NULL")
    suspend fun distinctCustomRingClipIds(): List<Long>

    /**
     * Arms or disarms one medication's «复查提醒».
     *
     * Called automatically when a round reaches its threshold (off) and when the user starts a new round
     * (on), which is what "到了后自动关闭当前药品提醒，用户可手动重新开启" means in storage.
     */
    @Query("UPDATE medications SET reviewReminderEnabled = :enabled, updatedAt = :now WHERE id = :id")
    suspend fun setReviewReminderEnabled(id: Long, enabled: Boolean, now: Long = System.currentTimeMillis())

    /**
     * Replaces every slot of a medication in one transaction. Slots whose time is unchanged keep
     * their row id, so existing [com.meditrack.data.local.entity.DoseLog] rows stay attached to
     * the same schedule instead of being cascade-deleted when the user edits a medication.
     */
    @Transaction
    suspend fun replaceSchedules(medicationId: Long, desired: List<Schedule>) {
        val existing = getSchedulesFor(medicationId)
        val byMinute = existing.associateBy { it.minuteOfDay }
        val keepIds = mutableSetOf<Long>()

        for (slot in desired) {
            val current = byMinute[slot.minuteOfDay]
            if (current != null) {
                keepIds += current.id
                updateSchedule(slot.copy(id = current.id, medicationId = medicationId))
            } else {
                keepIds += insertSchedule(slot.copy(id = 0L, medicationId = medicationId))
            }
        }
        for (stale in existing) {
            if (stale.id !in keepIds) deleteScheduleById(stale.id)
        }
    }
}
