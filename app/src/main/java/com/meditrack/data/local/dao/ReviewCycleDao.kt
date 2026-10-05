package com.meditrack.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.meditrack.data.local.entity.MedicationReviewCycle
import com.meditrack.data.local.entity.ReviewCountMode

/**
 * Storage for the per-medication «复查» rounds.
 *
 * Every read here is one-shot rather than a `Flow`, deliberately: the only stream that needs to react
 * to a review round ending is the medication list, and that already re-reads on the same triggers
 * that change it (a dose being taken). A second observable stream would double the re-composition of
 * the today screen for information that changes at most once per round.
 */
@Dao
interface ReviewCycleDao {

    @Query("SELECT * FROM medication_review_cycles WHERE id = :id")
    suspend fun byId(id: Long): MedicationReviewCycle?

    /** The open round for a medication, or null when it has none. */
    @Query(
        "SELECT * FROM medication_review_cycles " +
            "WHERE medicationId = :medicationId AND acknowledgedAtMillis IS NULL " +
            "ORDER BY round DESC LIMIT 1"
    )
    suspend fun openCycleFor(medicationId: Long): MedicationReviewCycle?

    /** Every medication's open round, keyed by medication id. */
    @Query("SELECT * FROM medication_review_cycles WHERE acknowledgedAtMillis IS NULL")
    suspend fun allOpenCycles(): List<MedicationReviewCycle>

    /** Every open round whose threshold has been reached, for the "该复查了" list. */
    @Query(
        "SELECT * FROM medication_review_cycles " +
            "WHERE acknowledgedAtMillis IS NULL AND threshold > 0 AND count >= threshold"
    )
    suspend fun reachedCycles(): List<MedicationReviewCycle>

    /** The closed rounds of one medication, newest first - the history shown in the editor. */
    @Query(
        "SELECT * FROM medication_review_cycles WHERE medicationId = :medicationId " +
            "AND acknowledgedAtMillis IS NOT NULL ORDER BY acknowledgedAtMillis DESC"
    )
    suspend fun closedCyclesFor(medicationId: Long): List<MedicationReviewCycle>

    /** The highest round number used so far, so the next round can be numbered without guessing. */
    @Query("SELECT MAX(round) FROM medication_review_cycles WHERE medicationId = :medicationId")
    suspend fun highestRound(medicationId: Long): Int?

    @Query("SELECT COUNT(*) FROM medication_review_cycles WHERE acknowledgedAtMillis IS NOT NULL")
    suspend fun acknowledgedCount(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(cycle: MedicationReviewCycle): Long

    @Update
    suspend fun update(cycle: MedicationReviewCycle)

    @Query(
        "UPDATE medication_review_cycles SET count = :count, countedEpochDay = :countedEpochDay, " +
            "updatedAt = :now WHERE id = :id"
    )
    suspend fun setProgress(id: Long, count: Double, countedEpochDay: Long, now: Long = System.currentTimeMillis())

    /** Adds [delta] to the stored progress. Used for taken doses and accumulated quantity. */
    @Query(
        "UPDATE medication_review_cycles SET count = count + :delta, countedEpochDay = :countedEpochDay, " +
            "updatedAt = :now WHERE id = :id"
    )
    suspend fun addProgress(id: Long, delta: Double, countedEpochDay: Long, now: Long = System.currentTimeMillis())

    /**
     * Records that the loud review notice has been posted for this round.
     *
     * Written in the same pass that posts it, so a heartbeat racing the notification cannot post it twice.
     */
    @Query("UPDATE medication_review_cycles SET reachedNotified = 1, updatedAt = :now WHERE id = :id")
    suspend fun markReachedNotified(id: Long, now: Long = System.currentTimeMillis())

    /** Records the day the gentle advance notice went out. */
    @Query(
        "UPDATE medication_review_cycles SET advanceNotifiedEpochDay = :epochDay, updatedAt = :now " +
            "WHERE id = :id"
    )
    suspend fun markAdvanceNotified(id: Long, epochDay: Long, now: Long = System.currentTimeMillis())

    /**
     * Closes a round. Only the acknowledgement is written here - the next round is a separate row, so
     * "when did I last go" keeps its answer.
     */
    @Query(
        "UPDATE medication_review_cycles SET acknowledgedAtMillis = :at, acknowledgedEpochDay = :epochDay, " +
            "updatedAt = :at WHERE id = :id"
    )
    suspend fun acknowledge(id: Long, at: Long, epochDay: Long)

    @Query("DELETE FROM medication_review_cycles WHERE medicationId = :medicationId")
    suspend fun deleteFor(medicationId: Long)

    @Query("DELETE FROM medication_review_cycles")
    suspend fun deleteAll()

    /**
     * Opens a round, closing any round that is still open first.
     *
     * The "only one open round per medication" invariant is enforced here rather than by a database
     * constraint because SQLite cannot express a partial unique index through Room's annotations
     * without raw DDL. Keeping it in one transaction on the DAO means no caller can forget it.
     */
    @Transaction
    suspend fun startRound(
        medicationId: Long,
        mode: ReviewCountMode,
        threshold: Double,
        startedAtMillis: Long,
        startedEpochDay: Long,
        now: Long = System.currentTimeMillis(),
    ): Long {
        openCycleFor(medicationId)?.let { open ->
            // Whatever was collected is closed out rather than carried over: the user asked for a new
            // round, and a round that inherits the previous countdown would fire immediately.
            acknowledge(open.id, now, startedEpochDay)
        }
        val next = (highestRound(medicationId) ?: 0) + 1
        return insert(
            MedicationReviewCycle(
                medicationId = medicationId,
                round = next,
                startedAtMillis = startedAtMillis,
                startedEpochDay = startedEpochDay,
                countMode = mode,
                threshold = threshold,
                count = 0.0,
                countedEpochDay = startedEpochDay,
                createdAt = now,
                updatedAt = now,
            )
        )
    }
}
