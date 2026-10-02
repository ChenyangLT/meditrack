package com.meditrack.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.meditrack.data.local.entity.DoseEvent
import com.meditrack.data.local.entity.DoseLog
import com.meditrack.data.local.entity.DoseLogWithMedication
import com.meditrack.data.local.entity.DoseStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface DoseLogDao {

    // ------------------------------------------------------------ day queries

    @Transaction
    @Query("SELECT * FROM dose_logs WHERE epochDay = :epochDay ORDER BY plannedMinuteOfDay ASC, id ASC")
    fun observeForDay(epochDay: Long): Flow<List<DoseLogWithMedication>>

    @Transaction
    @Query("SELECT * FROM dose_logs WHERE epochDay = :epochDay ORDER BY plannedMinuteOfDay ASC, id ASC")
    suspend fun getForDay(epochDay: Long): List<DoseLogWithMedication>

    @Query("SELECT * FROM dose_logs WHERE epochDay BETWEEN :fromEpochDay AND :toEpochDay")
    suspend fun getBetween(fromEpochDay: Long, toEpochDay: Long): List<DoseLog>

    @Query("SELECT * FROM dose_logs WHERE id = :id")
    suspend fun getById(id: Long): DoseLog?

    @Transaction
    @Query("SELECT * FROM dose_logs WHERE id = :id")
    suspend fun getWithMedicationById(id: Long): DoseLogWithMedication?

    @Query("SELECT * FROM dose_logs WHERE scheduleId = :scheduleId AND epochDay = :epochDay LIMIT 1")
    suspend fun findByScheduleAndDay(scheduleId: Long, epochDay: Long): DoseLog?

    @Query("SELECT * FROM dose_logs WHERE epochDay = :epochDay")
    suspend fun getRawForDay(epochDay: Long): List<DoseLog>

    // --------------------------------------------------------------- writes

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(dose: DoseLog): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(doses: List<DoseLog>): List<Long>

    @Update
    suspend fun update(dose: DoseLog)

    @Query("DELETE FROM dose_logs WHERE epochDay > :keepUntilEpochDay")
    suspend fun deleteFutureBeyond(keepUntilEpochDay: Long)

    @Query("DELETE FROM dose_logs WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        """
        UPDATE dose_logs
           SET takenQuantity = :quantity,
               status = :status,
               takenTimeMillis = :takenTimeMillis,
               overDoseConfirmed = :overDoseConfirmed,
               updatedAt = :now
         WHERE id = :id
        """
    )
    suspend fun updateQuantity(
        id: Long,
        quantity: Double,
        status: DoseStatus,
        takenTimeMillis: Long?,
        overDoseConfirmed: Boolean,
        now: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE dose_logs SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun updateStatus(id: Long, status: DoseStatus, now: Long = System.currentTimeMillis())

    /**
     * Pushes the dose out and re-opens its reminder budget.
     *
     * Clearing `notifiedTimeMillis`, `escalationCount` and `preRemindedAtMillis` is the point:
     * "稍后提醒" means *tell me properly at the new time*, not "show me the third repeat of the
     * reminder you already gave up on". Without this reset the snoozed dose would come back with the
     * escalation counter already spent and be silently suppressed - which is exactly the kind of
     * quiet failure this rewrite removes.
     */
    @Query(
        """
        UPDATE dose_logs
           SET snoozedUntilMillis = :until,
               snoozeCount = snoozeCount + 1,
               notifiedTimeMillis = NULL,
               escalationCount = 0,
               preRemindedAtMillis = NULL,
               unlockReminderCount = 0,
               unlockReminderAtMillis = NULL,
               updatedAt = :now
         WHERE id = :id
        """
    )
    suspend fun snooze(id: Long, until: Long, now: Long = System.currentTimeMillis())

    /** Records one announcement and advances the escalation budget by one. */
    @Query(
        """
        UPDATE dose_logs
           SET notifiedTimeMillis = :at,
               escalationCount = escalationCount + 1,
               updatedAt = :now
         WHERE id = :id
        """
    )
    suspend fun markNotified(id: Long, at: Long, now: Long = System.currentTimeMillis())

    /** Records that the "还有一会儿" heads-up was shown, so it is never shown twice. */
    @Query("UPDATE dose_logs SET preRemindedAtMillis = :at, updatedAt = :now WHERE id = :id")
    suspend fun markPreReminded(id: Long, at: Long, now: Long = System.currentTimeMillis())

    /**
     * Derives the 未服药 status without claiming the user has been told about it.
     *
     * The two used to be one UPDATE. Because the missed sweep runs at app start, on every heartbeat
     * and at boot, it always won the race against the notification and set the "already notified"
     * flag first - so the 未服药 reminder could never actually be posted. Deriving the status and
     * announcing it are separate facts and now have separate queries.
     */
    @Query("UPDATE dose_logs SET status = 'MISSED', updatedAt = :now WHERE id = :id")
    suspend fun deriveMissed(id: Long, now: Long = System.currentTimeMillis())

    /** Claims the one-and-only 未服药 notification for this dose. */
    @Query("UPDATE dose_logs SET missedNotified = 1, updatedAt = :now WHERE id = :id")
    suspend fun markMissedNotified(id: Long, now: Long = System.currentTimeMillis())

    /** Re-opens the reminder budget; used when a settled dose is reset back to untouched. */
    @Query(
        """
        UPDATE dose_logs
           SET notifiedTimeMillis = NULL,
               escalationCount = 0,
               preRemindedAtMillis = NULL,
               missedNotified = 0,
               unlockReminderCount = 0,
               unlockReminderAtMillis = NULL,
               updatedAt = :now
         WHERE id = :id
        """
    )
    suspend fun clearReminderState(id: Long, now: Long = System.currentTimeMillis())

    // ------------------------------------------------- deferred (idle) reminders

    /**
     * Records that a reminder was withheld because the phone was idle.
     *
     * `notifiedTimeMillis` is deliberately left untouched: the dose has *not* been announced yet, so
     * the escalation budget must not be spent on it.
     */
    @Query("UPDATE dose_logs SET deferredAtMillis = :at, updatedAt = :now WHERE id = :id")
    suspend fun markDeferred(id: Long, at: Long, now: Long = System.currentTimeMillis())

    /**
     * Every dose whose reminder is still owed to the user, oldest first.
     *
     * Ordered by planned time so the batch delivered when the user picks the phone up reads
     * chronologically, matching the order the doses actually fell due.
     */
    @Transaction
    @Query(
        """
        SELECT * FROM dose_logs
         WHERE deferredAtMillis IS NOT NULL
           AND status IN ('UPCOMING', 'DUE', 'PARTIAL')
         ORDER BY plannedTimeMillis ASC
        """
    )
    suspend fun getDeferred(): List<DoseLogWithMedication>

    /** Same rows without the joins; used when only the ids are needed. */
    @Query(
        """
        SELECT * FROM dose_logs
         WHERE deferredAtMillis IS NOT NULL
           AND status IN ('UPCOMING', 'DUE', 'PARTIAL')
         ORDER BY plannedTimeMillis ASC
        """
    )
    suspend fun getDeferredRaw(): List<DoseLog>

    /** Clears the flag once the withheld reminders have actually been delivered. */
    @Query("UPDATE dose_logs SET deferredAtMillis = NULL, updatedAt = :now WHERE id IN (:ids)")
    suspend fun clearDeferred(ids: List<Long>, now: Long = System.currentTimeMillis())

    /**
     * Drops stale deferrals.
     *
     * A dose taken or skipped while its notification was withheld must never be announced
     * afterwards, so the flag is cleared for anything that is no longer open.
     */
    @Query(
        """
        UPDATE dose_logs SET deferredAtMillis = NULL
         WHERE deferredAtMillis IS NOT NULL
           AND status NOT IN ('UPCOMING', 'DUE', 'PARTIAL')
        """
    )
    suspend fun clearResolvedDeferred()

    /**
     * Marks untouched doses from earlier days as missed.
     *
     * Called on app start and after a boot so that a phone that was switched off for two days
     * shows an honest history instead of a wall of "待服用". Doses the user never saw are still
     * reported as missed - which is what a doctor would want to know - but they are never counted
     * as taken.
     */
    @Query(
        """
        UPDATE dose_logs
           SET status = 'MISSED', missedNotified = 1, updatedAt = :now
         WHERE epochDay < :todayEpochDay
           AND status IN ('UPCOMING', 'DUE', 'PARTIAL')
           AND takenQuantity <= 0
        """
    )
    suspend fun sweepMissedBefore(todayEpochDay: Long, now: Long = System.currentTimeMillis()): Int

    // --------------------------------------------------------------- events

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(event: DoseEvent): Long

    @Query("SELECT * FROM dose_events WHERE doseLogId = :doseLogId ORDER BY timestamp DESC, id DESC")
    suspend fun getEventsFor(doseLogId: Long): List<DoseEvent>

    @Query("SELECT * FROM dose_events WHERE doseLogId = :doseLogId ORDER BY timestamp DESC, id DESC LIMIT 1")
    suspend fun getLastEvent(doseLogId: Long): DoseEvent?

    @Query("SELECT * FROM dose_events ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun observeRecentEvents(limit: Int = 100): Flow<List<DoseEvent>>

    @Query("DELETE FROM dose_events WHERE doseLogId = :doseLogId")
    suspend fun deleteEventsFor(doseLogId: Long)

    // ----------------------------------------------------------- statistics

    @Query(
        """
        SELECT * FROM dose_logs
         WHERE epochDay BETWEEN :fromEpochDay AND :toEpochDay
           AND (:medicationId IS NULL OR medicationId = :medicationId)
         ORDER BY epochDay DESC, plannedMinuteOfDay DESC
        """
    )
    suspend fun getRangeFiltered(fromEpochDay: Long, toEpochDay: Long, medicationId: Long?): List<DoseLog>

    @Query(
        """
        SELECT DISTINCT epochDay FROM dose_logs
         WHERE takenQuantity > 0 AND (:medicationId IS NULL OR medicationId = :medicationId)
         ORDER BY epochDay DESC
        """
    )
    suspend fun getDaysWithIntake(medicationId: Long?): List<Long>

    @Query(
        """
        SELECT COUNT(*) FROM dose_logs
         WHERE status = 'MISSED' AND epochDay BETWEEN :fromEpochDay AND :toEpochDay
           AND (:medicationId IS NULL OR medicationId = :medicationId)
        """
    )
    suspend fun countMissed(fromEpochDay: Long, toEpochDay: Long, medicationId: Long?): Int

    @Query(
        """
        SELECT COUNT(*) FROM dose_logs
         WHERE status = 'SKIPPED' AND epochDay BETWEEN :fromEpochDay AND :toEpochDay
           AND (:medicationId IS NULL OR medicationId = :medicationId)
        """
    )
    suspend fun countSkipped(fromEpochDay: Long, toEpochDay: Long, medicationId: Long?): Int

    // ------------------------------------------------------------- widget / alarm

    /**
     * Doses the home-screen widget needs: everything still open today, plus anything already
     * actioned (so it can show "今日用药已完成 ✅" and the taken tail).
     */
    @Transaction
    @Query("SELECT * FROM dose_logs WHERE epochDay = :epochDay ORDER BY plannedMinuteOfDay ASC")
    fun observeForWidget(epochDay: Long): Flow<List<DoseLogWithMedication>>

    /** Doses due in a window, used to arm the exact alarms for the next day. */
    @Query(
        """
        SELECT * FROM dose_logs
         WHERE plannedTimeMillis BETWEEN :fromMillis AND :toMillis
           AND status IN ('UPCOMING', 'DUE', 'PARTIAL')
         ORDER BY plannedTimeMillis ASC
        """
    )
    suspend fun getPendingInWindow(fromMillis: Long, toMillis: Long): List<DoseLog>

    /**
     * Every dose that may still need an alarm inside a window, judged by its **effective** due time.
     *
     * `MAX(COALESCE(snoozedUntilMillis, plannedTimeMillis), plannedTimeMillis)` is
     * [com.meditrack.domain.reminder.ReminderPlanner.effectiveDueMillis] expressed in SQL, and it
     * matters: the previous arming pass filtered on `plannedTimeMillis` alone, so the moment a user
     * pressed 稍后 the dose fell out of the window (its planned time was now in the past) and the
     * maintenance pass would never re-arm it. The only thing keeping that reminder alive was the
     * single alarm the snooze itself had set - one dropped `PendingIntent` and it was gone for good.
     *
     * `MISSED` is excluded on purpose: a missed dose is delivered by the reconciliation pass, not by
     * an alarm, and arming one would make it ring again.
     */
    @Query(
        """
        SELECT * FROM dose_logs
         WHERE status IN ('UPCOMING', 'DUE', 'PARTIAL')
           AND MAX(COALESCE(snoozedUntilMillis, plannedTimeMillis), plannedTimeMillis)
               BETWEEN :fromMillis AND :toMillis
         ORDER BY plannedTimeMillis ASC
        """
    )
    suspend fun getArmableBetween(fromMillis: Long, toMillis: Long): List<DoseLog>

    /**
     * Doses whose moment has already passed but which have not been settled.
     *
     * This is the query that makes a late wake-up harmless: after a reboot, a Doze exit or a long
     * power-off, everything that came due while nothing was running is found here in one statement
     * and reconciled, rather than being lost because its one alarm fired into the void.
     *
     * `MISSED` is included, and that inclusion is load-bearing: the same reconcile pass derives the
     * missed status *before* it collects candidates, so excluding it here would make a dose that
     * lapsed a moment earlier invisible to the very pass that is supposed to announce it. (It used to
     * be reachable only through a separate "missed today, not yet announced" query, which by
     * construction cannot see yesterday's rows or a dose that was derived missed on a previous pass.)
     */
    @Query(
        """
        SELECT * FROM dose_logs
         WHERE status IN ('UPCOMING', 'DUE', 'PARTIAL', 'MISSED')
           AND MAX(COALESCE(snoozedUntilMillis, plannedTimeMillis), plannedTimeMillis) <= :nowMillis
           AND MAX(COALESCE(snoozedUntilMillis, plannedTimeMillis), plannedTimeMillis) >= :notBeforeMillis
         ORDER BY plannedTimeMillis ASC
        """
    )
    suspend fun getOverdueOpen(nowMillis: Long, notBeforeMillis: Long): List<DoseLog>

    /**
     * Doses an **unlock catch-up** may speak up about.
     *
     * The rule is "the user just picked the phone up, and this dose is overdue and still not
     * recorded". Deliberately broader than [getOverdueOpen]:
     *
     *  - `MISSED` counts. "The app already decided this was a miss" is exactly the situation that
     *    needs a voice, because the miss was decided while nobody was looking at the screen.
     *  - `notBeforeMillis` is the user's own "太晚了" boundary; beyond it the moment has gone and a
     *    notification would be a lie, so the caller stops there.
     *  - A snooze that is still in the future is excluded through the effective-due expression: the
     *    user explicitly asked to be left alone until then.
     *  - `TAKEN` / `SKIPPED` never appear: the user already resolved them.
     */
    @Query(
        """
        SELECT * FROM dose_logs
         WHERE status IN ('UPCOMING', 'DUE', 'PARTIAL', 'MISSED')
           AND MAX(COALESCE(snoozedUntilMillis, plannedTimeMillis), plannedTimeMillis) <= :nowMillis
           AND MAX(COALESCE(snoozedUntilMillis, plannedTimeMillis), plannedTimeMillis) >= :notBeforeMillis
         ORDER BY plannedTimeMillis ASC
        """
    )
    suspend fun getUnlockCatchUpCandidates(nowMillis: Long, notBeforeMillis: Long): List<DoseLog>

    /**
     * Spends one unlock catch-up from the dose's budget.
     *
     * Separate from [markNotified] on purpose: the ordinary escalation counter and the unlock counter
     * answer two different questions ("has the schedule spoken up?" vs "has the *person* been told
     * since they picked the phone up?"), and sharing one counter is what made the old behaviour
     * silence one of them.
     */
    @Query(
        """
        UPDATE dose_logs
           SET unlockReminderCount = unlockReminderCount + 1,
               unlockReminderAtMillis = :at,
               updatedAt = :now
         WHERE id = :id
        """
    )
    suspend fun markUnlockReminded(id: Long, at: Long, now: Long = System.currentTimeMillis())

    /**
     * Records an unlock catch-up that does **not** spend the budget.
     *
     * Used inside quiet hours, where the user asked for a note rather than a buzz: the timestamp
     * still moves so the minimum-gap rule keeps repeated unlocks from re-posting, but the limited
     * audible budget is preserved for the hours when the user is willing to be interrupted.
     */
    @Query("UPDATE dose_logs SET unlockReminderAtMillis = :at, updatedAt = :now WHERE id = :id")
    suspend fun touchUnlockReminder(id: Long, at: Long, now: Long = System.currentTimeMillis())

    /**
     * Sweeps the missed flag for doses that have already been told to the user.
     *
     * Only used to repair the audit state after an import; ordinary delivery goes through
     * [markMissedNotified].
     */
    @Query("UPDATE dose_logs SET missedNotified = 1 WHERE epochDay < :todayEpochDay AND status = 'MISSED'")
    suspend fun claimAllMissedBefore(todayEpochDay: Long): Int

    /**
     * Reads the doses that need attention right now: due today and not yet resolved, or already
     * flagged missed today.
     */
    @Transaction
    @Query(
        """
        SELECT * FROM dose_logs
         WHERE epochDay = :epochDay AND status IN ('UPCOMING', 'DUE', 'PARTIAL', 'MISSED')
         ORDER BY plannedMinuteOfDay ASC
        """
    )
    suspend fun getOpenForDay(epochDay: Long): List<DoseLogWithMedication>

    /** Removes every generated row inside a range; used by the "clear data" action. */
    @Query("DELETE FROM dose_logs")
    suspend fun deleteAll()
}
