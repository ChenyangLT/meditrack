package com.meditrack.data.repository

import com.meditrack.core.util.DateTimeUtils
import com.meditrack.data.local.dao.MedicationDao
import com.meditrack.data.local.dao.ReviewCycleDao
import com.meditrack.data.local.entity.Medication
import com.meditrack.data.local.entity.MedicationReviewCycle
import com.meditrack.data.local.entity.ReviewCountMode
import com.meditrack.domain.review.ReviewProgress
import com.meditrack.domain.review.ReviewProgressCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One medication and where it stands in its current 复查 round.
 *
 * @param progress null when nothing is configured, so "not set up" and "0 of 30" cannot be confused
 */
data class MedicationReviewSnapshot(
    val medication: Medication,
    val progress: ReviewProgress?,
    val cycle: MedicationReviewCycle?,
) {
    /** The line the medication list and the widget show: "复查：还差 3 次". */
    val subtitle: String?
        get() = progress?.takeIf { it.isConfigured }?.remainingLabel?.let { "复查：$it" }

    /** True when this round has reached its threshold and is waiting to be acknowledged. */
    val isDue: Boolean get() = progress?.isReached == true
}

/**
 * The «复查提醒» rules, in one place.
 *
 * ## Why the counting lives here rather than in the dose write path
 *
 * A dose being marked taken is a *dose* event and is written by `DoseRepository`; whether that advances a
 * medication's review countdown is a *review* decision. Keeping the second one here means the dose
 * repository does not need to know what a review round is beyond "tell the review side a dose was taken",
 * and it means all six rules the feature promises are in a single readable file:
 *
 *  1. only a dose that became **taken** counts (a skip, a miss and a reset do not);
 *  2. a partial dose counts once, when it first leaves zero;
 *  3. day-based rounds count the calendar, not the doses;
 *  4. a new round starts at zero rather than inheriting the previous count;
 *  5. reaching the threshold turns the medication's review reminder off;
 *  6. starting a new round turns it back on.
 *
 * ## Why it does not depend on the reminder layer
 *
 * So it can be called from `DoseRepository` - which is injected into the reminder layer - without a
 * dependency cycle. The notification side is driven by [com.meditrack.domain.review.ReviewReminderService],
 * which depends on this, never the other way round.
 */
@Singleton
class ReviewRepository @Inject constructor(
    private val reviewCycleDao: ReviewCycleDao,
    private val medicationDao: MedicationDao,
) {

    /**
     * Records that one dose of [medicationId] has just been taken.
     *
     * Called from the single place a dose transitions to taken. Everything else - the threshold check, the
     * notification, the auto-off - happens on the next reminder pass, because the user has just taken a
     * pill and does not need the app to interrupt them about a follow-up appointment in the same instant.
     *
     * @param takenQuantity how much was taken, for the accumulated-amount mode
     * @param previouslyTaken the dose's amount before this write, so a repeated tap cannot count twice
     */
    suspend fun onDoseTaken(
        medicationId: Long,
        takenQuantity: Double,
        previouslyTaken: Double,
        epochDay: Long = DateTimeUtils.todayEpochDay(),
    ) = withContext(Dispatchers.IO) {
        if (medicationId <= 0L) return@withContext
        if (!ReviewProgressCalculator.countsTowardReview(previouslyTaken, takenQuantity)) {
            return@withContext
        }

        val medication = medicationDao.getWithSchedulesById(medicationId)?.medication ?: return@withContext
        val config = medication.reviewConfig
        if (!config.isActive) return@withContext

        val cycle = ensureCycle(medication, epochDay) ?: return@withContext
        val mode = cycle.countMode
        val delta = ReviewProgressCalculator.advance(
            mode = mode,
            storedCount = cycle.count,
            takenQuantity = takenQuantity,
        ) - cycle.count

        // A day-based round advances by existing, not by being incremented: writing +0 here would be a
        // pointless row update on every dose, and worse, it would look like the count was being
        // maintained when it is in fact derived.
        if (mode == ReviewCountMode.DAYS) return@withContext
        if (delta <= 0.0) return@withContext
        reviewCycleDao.addProgress(cycle.id, delta, epochDay)
    }

    /**
     * Refreshes a round's stored day count, and returns the round.
     *
     * Day-based progress is computed on read, so nothing needs writing - but the *derived* count has to
     * be visible to the SQL that looks for reached rounds, which compares `count >= threshold`. This
     * writes that derived value back, which is the one place the stored count is not authoritative.
     */
    suspend fun syncDayProgress(epochDay: Long = DateTimeUtils.todayEpochDay()) = withContext(Dispatchers.IO) {
        for (cycle in reviewCycleDao.allOpenCycles()) {
            if (cycle.countMode != ReviewCountMode.DAYS) continue
            val elapsed = (epochDay - cycle.startedEpochDay + 1).coerceAtLeast(1).toDouble()
            if (elapsed != cycle.count) {
                reviewCycleDao.setProgress(cycle.id, elapsed, epochDay)
            }
        }
    }

    /** The current state of every medication that has a review configured. */
    suspend fun snapshots(epochDay: Long = DateTimeUtils.todayEpochDay()): List<MedicationReviewSnapshot> =
        withContext(Dispatchers.IO) {
            val medications = medicationDao.getAllOnce()
            if (medications.isEmpty()) return@withContext emptyList()
            val cycles = reviewCycleDao.allOpenCycles().associateBy { it.medicationId }
            medications.map { medication -> snapshotOf(medication, cycles[medication.id], epochDay) }
        }

    /** One medication's state, resolving the open round if it was not already loaded. */
    suspend fun snapshotFor(
        medicationId: Long,
        epochDay: Long = DateTimeUtils.todayEpochDay(),
    ): MedicationReviewSnapshot? = withContext(Dispatchers.IO) {
        val medication = medicationDao.getWithSchedulesById(medicationId)?.medication
            ?: return@withContext null
        val cycle = reviewCycleDao.openCycleFor(medicationId)
        snapshotOf(medication, cycle, epochDay)
    }

    /** The rounds that have reached their threshold, for the settings screen's "该复查了" list. */
    suspend fun due(): List<MedicationReviewSnapshot> =
        snapshots().filter { it.isDue }

    /** Every closed round of one medication, newest first - the history the editor shows. */
    suspend fun history(medicationId: Long): List<MedicationReviewCycle> =
        withContext(Dispatchers.IO) { reviewCycleDao.closedCyclesFor(medicationId) }

    /**
     * Starts a new round for a medication and re-arms its review reminder.
     *
     * This is the other half of "到了后自动关闭当前药品提醒": the reminder is off once the threshold is
     * reached, and this is the deliberate act that turns it back on with a fresh countdown. Closing and
     * opening happen in one transaction on the DAO, so an interruption cannot leave two open rounds.
     */
    suspend fun startNewRound(
        medicationId: Long,
        epochDay: Long = DateTimeUtils.todayEpochDay(),
    ) = withContext(Dispatchers.IO) {
        val medication = medicationDao.getWithSchedulesById(medicationId)?.medication ?: return@withContext
        val config = medication.reviewConfig
        if (!config.isConfigured) return@withContext

        reviewCycleDao.startRound(
            medicationId = medicationId,
            mode = config.countMode,
            threshold = config.threshold,
            startedAtMillis = System.currentTimeMillis(),
            startedEpochDay = epochDay,
        )
        medicationDao.setReviewReminderEnabled(medicationId, true)
    }

    /**
     * Turns a medication's review reminder off without closing the round.
     *
     * Used when the user switches the per-medication reminder off by hand. Kept distinct from
     * [startNewRound] because the two mean different things: off is "stop telling me", a new round is
     * "start counting again".
     */
    suspend fun setReminderEnabled(medicationId: Long, enabled: Boolean) =
        withContext(Dispatchers.IO) { medicationDao.setReviewReminderEnabled(medicationId, enabled) }

    /** Records that the loud notice was posted for [cycleId], so it is not posted again. */
    suspend fun markReachedNotified(cycleId: Long) =
        withContext(Dispatchers.IO) { reviewCycleDao.markReachedNotified(cycleId) }

    /** Records that the gentle advance notice was posted on [epochDay]. */
    suspend fun markAdvanceNotified(cycleId: Long, epochDay: Long) =
        withContext(Dispatchers.IO) { reviewCycleDao.markAdvanceNotified(cycleId, epochDay) }

    /** Discards a medication's rounds; used when its review is switched off entirely. */
    suspend fun clearFor(medicationId: Long) =
        withContext(Dispatchers.IO) { reviewCycleDao.deleteFor(medicationId) }

    // ---------------------------------------------------------------- internals

    /**
     * Ensures a round exists for a medication whose config was set up but which never got one.
     *
     * A round is normally created when the user saves the review setting. This exists for the two cases
     * that can leave a medication configured with no round: a backup restored from a build that stored the
     * setting without the round, and an upgrade whose migration added the columns to a row the user had
     * already configured. Both would otherwise be silently uncounted forever.
     */
    private suspend fun ensureCycle(
        medication: Medication,
        epochDay: Long,
    ): MedicationReviewCycle? {
        reviewCycleDao.openCycleFor(medication.id)?.let { existing ->
            // The config may have moved on since the round started. The round wins for its own counting,
            // but a threshold raised mid-round has to be picked up or the reminder would fire early.
            val config = medication.reviewConfig
            if (existing.threshold == config.threshold && existing.countMode == config.countMode) {
                return existing
            }
            val corrected = existing.copy(
                threshold = config.threshold,
                countMode = config.countMode,
            )
            reviewCycleDao.update(corrected)
            return corrected
        }

        val config = medication.reviewConfig
        if (!config.isConfigured) return null
        val id = reviewCycleDao.startRound(
            medicationId = medication.id,
            mode = config.countMode,
            threshold = config.threshold,
            startedAtMillis = System.currentTimeMillis(),
            startedEpochDay = epochDay,
        )
        return reviewCycleDao.byId(id)
    }

    private fun snapshotOf(
        medication: Medication,
        cycle: MedicationReviewCycle?,
        epochDay: Long,
    ): MedicationReviewSnapshot {
        val config = medication.reviewConfig
        if (cycle == null || !config.isConfigured) {
            // A configured medication with no round yet still reports a progress of 0: the editor and the
            // list must not read "未设置" for something the user has just typed a threshold into.
            val progress = if (config.isConfigured) {
                ReviewProgressCalculator.progressOf(
                    mode = config.countMode,
                    storedCount = 0.0,
                    threshold = config.threshold,
                    startedEpochDay = epochDay,
                    epochDay = epochDay,
                )
            } else {
                null
            }
            return MedicationReviewSnapshot(medication, progress, cycle)
        }

        val progress = ReviewProgressCalculator.progressOf(
            mode = cycle.countMode,
            storedCount = cycle.count,
            threshold = cycle.threshold,
            startedEpochDay = cycle.startedEpochDay,
            epochDay = epochDay,
        )
        return MedicationReviewSnapshot(medication, progress, cycle)
    }
}
