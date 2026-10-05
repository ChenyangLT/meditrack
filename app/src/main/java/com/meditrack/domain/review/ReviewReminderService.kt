package com.meditrack.domain.review

import android.util.Log
import com.meditrack.data.prefs.SettingsRepository
import com.meditrack.data.repository.ReviewRepository
import com.meditrack.domain.reminder.DoseNotifier
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides — once per reminder pass — whether any medication has reached its 复查 threshold.
 *
 * ## Why a service class rather than logic inside `ReminderEngine`
 *
 * `ReminderEngine` is already the longest, most load-bearing file in the app, and it is about *doses*:
 * which dose is due, whether it was announced, whether an alarm was lost. A review belongs to a
 * *medication* and to a *calendar*, and folding the two together would mean the dose pipeline grew a
 * second, unrelated notion of "due" — which is exactly the kind of coupling that makes a reminder app
 * quietly stop reminding.
 *
 * The engine therefore calls [evaluate] once at the end of a pass and ignores the result. Everything
 * below is independently testable and independently skippable.
 *
 * ## The three things this guarantees
 *
 *  - **The notice fires once per round, not once per pass.** The reminder pipeline re-derives everything
 *    on every heartbeat, and a notice that were recomputed would arrive every fifteen minutes forever.
 *    `reachedNotified` is written in the same pass that posts it.
 *  - **Reaching the threshold switches the medication's reminder off.** That is the requested
 *    "到了后自动关闭当前药品提醒", and it is what makes the notice a decision point rather than nagging.
 *  - **The round is not closed by the notice.** Acknowledging the notification is not the same as going to
 *    the doctor, so only 「开始新一轮」 resets the count.
 */
@Singleton
class ReviewReminderService @Inject constructor(
    private val reviewRepository: ReviewRepository,
    private val settingsRepository: SettingsRepository,
    private val notifier: DoseNotifier,
) {

    /**
     * Posts whatever review notices are owed right now.
     *
     * Never throws: it is called from the reminder pipeline, where a failure in a follow-up feature must
     * not be able to stop a dose reminder from going out.
     *
     * @param epochDay the day to evaluate "days since the round started" against, so a test can pin it
     * @return how many notices were posted, for the audit log
     */
    suspend fun evaluate(epochDay: Long = com.meditrack.core.util.DateTimeUtils.todayEpochDay()): Int {
        var posted = 0
        runCatching {
            val prefs = settingsRepository.current()
            if (!prefs.reviewReminderEnabled) return 0

            // Day-based rounds compare `count >= threshold` in SQL, so the derived day count has to be
            // written before anything is looked up. Done first, in one pass over the open rounds.
            reviewRepository.syncDayProgress(epochDay)

            for (snapshot in reviewRepository.snapshots(epochDay)) {
                val medication = snapshot.medication
                val progress = snapshot.progress ?: continue
                val cycle = snapshot.cycle ?: continue
                val config = medication.reviewConfig

                if (!config.isActive) {
                    // The reminder is off - either the user switched it off, or the threshold was reached
                    // and this very feature switched it off. Either way, nothing to say.
                    continue
                }

                if (progress.isReached) {
                    if (cycle.reachedNotified) continue
                    notifier.showReviewReminder(medication, progress, prefs)
                    reviewRepository.markReachedNotified(cycle.id)
                    // The requested automatic stop. Written *after* the notice, so a crash between the two
                    // leaves a notice that will be re-posted rather than a reminder that went silent
                    // without ever telling anyone why.
                    reviewRepository.setReminderEnabled(medication.id, false)
                    Log.i(TAG, "review reached for medication ${medication.id}; reminder switched off")
                    posted++
                    continue
                }

                // The gentle advance notice: once per round, and only while it is genuinely close.
                val advance = prefs.reviewAdvanceNotice
                if (progress.shouldGiveAdvanceNotice(advance) &&
                    cycle.advanceNotifiedEpochDay == 0L
                ) {
                    notifier.showReviewAdvance(medication, progress, prefs)
                    reviewRepository.markAdvanceNotified(cycle.id, epochDay)
                    posted++
                }
            }
        }.onFailure { Log.w(TAG, "review evaluation failed", it) }
        return posted
    }

    private companion object {
        const val TAG = "ReviewReminder"
    }
}
