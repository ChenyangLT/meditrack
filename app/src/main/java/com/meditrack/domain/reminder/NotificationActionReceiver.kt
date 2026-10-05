package com.meditrack.domain.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.meditrack.R
import com.meditrack.data.prefs.SettingsRepository
import com.meditrack.data.repository.DoseActionResult
import com.meditrack.data.repository.DoseRepository
import com.meditrack.data.repository.MedicationRepository
import com.meditrack.widget.WidgetRefresh
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles the buttons on the reminder notifications: 已服 / 稍后 N 分钟 / 跳过.
 *
 * The receiver is the *only* place that acts on a notification, so every button shares one code path
 * with the in-app stepper: whatever the user taps, the same repository method runs and the same
 * invariants hold (status derived from quantity, event logged, stock adjusted).
 *
 * ## The change that matters to the user
 *
 * 稍后 used to cancel the notification outright and show a four-second toast. From the user's point
 * of view the reminder simply evaporated: nothing on screen said a dose was still outstanding, or
 * when it would come back. That is the single most common way a medication reminder silently fails.
 *
 * Now the reminder is replaced by a quiet, persistent [DoseNotifier.showSnoozeState] note - "已推迟：
 * 阿司匹林 / 将在 08:35 再提醒你" - with a 现在服用 button, so the outstanding dose stays visible and
 * actionable for the whole snooze. The reminder itself then replaces that note when it comes due.
 */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {

    @Inject lateinit var doseRepository: DoseRepository
    @Inject lateinit var medicationRepository: MedicationRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var notifier: DoseNotifier
    @Inject lateinit var engine: ReminderEngine
    @Inject lateinit var ringingController: RingingController

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val doseId = intent.getLongExtra(EXTRA_DOSE_ID, -1L)
        Log.i(TAG, "onReceive action=$action doseId=$doseId")

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // ---------------------------------------------------------- stop the ring FIRST
                //
                // Before anything else, and regardless of which action it was, and even when the
                // action's own work is about to fail. The ring is a `MediaPlayer` looping in this
                // process, so cancelling the notification does not silence it - and a user who has
                // just tapped 已服用 while their phone is still shouting will not accept "the record
                // was written, but the sound is a separate subsystem".
                //
                // This is also the whole point of the feature: 一直响到处理 means the *action* is what
                // stops it, so every action has to.
                ringingController.stopFor(doseId, "通知操作 $action").also {
                    ReminderRingingService.stop(appContext)
                }

                when (action) {
                    ACTION_TAKEN -> handleTaken(appContext, doseId)
                    ACTION_SNOOZE -> handleSnooze(appContext, doseId, intent.getIntExtra(EXTRA_SNOOZE_MINUTES, 0))
                    ACTION_SKIP -> handleSkip(appContext, doseId)
                    ACTION_REVIEW_ACK -> handleReviewAck(appContext, doseId)
                    ACTION_REVIEW_SEARCH -> handleReviewSearch(appContext, intent.getStringExtra(EXTRA_SEARCH_URL))
                }
                // The widget mirrors the same data, so it must refresh immediately.
                WidgetRefresh.requestUpdate(appContext)
            } catch (t: Throwable) {
                Log.e(TAG, "notification action $action failed for dose $doseId", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleTaken(context: Context, doseId: Long) {
        val result = doseRepository.markTaken(doseId)
        if (result is DoseActionResult.NeedsOverDoseConfirmation) {
            // The dose would exceed the configured maximum. A dialog cannot be shown from a
            // receiver, so nothing is recorded and the notification deliberately stays put.
            notifier.showActionConfirmation(context.getString(R.string.toast_over_dose_needs_app))
            return
        }
        notifier.cancel(doseId)
        engine.onDoseStateChanged(doseId)
        dismissUnlockSummaryIfSettled()
        notifier.showActionConfirmation(context.getString(R.string.toast_marked_taken))
    }

    private suspend fun handleSnooze(context: Context, doseId: Long, requestedMinutes: Int) {
        val prefs = settingsRepository.current()
        val minutes = if (requestedMinutes > 0) requestedMinutes else prefs.snoozeMinutes
        val result = doseRepository.snooze(doseId, minutes)
        val until = (result as? DoseActionResult.Applied)?.dose?.snoozedUntilMillis
            ?: (System.currentTimeMillis() + minutes * 60_000L)

        // Replace the reminder with the "已推迟" note rather than leaving nothing behind.
        notifier.cancel(doseId)
        val medication = doseRepository.getDose(doseId)
            ?.let { medicationRepository.getWithSchedules(it.medicationId)?.medication }
        doseRepository.getDose(doseId)?.let { dose ->
            notifier.showSnoozeState(dose, medication, prefs, until)
        }

        // The alarm has already been moved by the repository. This pass recomputes the whole
        // schedule around the new deadline (and audits it) - note that it deliberately does *not*
        // go through onDoseStateChanged, which cancels the alarm the snooze just armed.
        engine.reconcile(ReminderTrigger.SETTINGS_CHANGED)

        notifier.showActionConfirmation(context.getString(R.string.toast_snoozed, minutes))
    }

    private suspend fun handleSkip(context: Context, doseId: Long) {
        doseRepository.skip(doseId)
        notifier.cancel(doseId)
        engine.onDoseStateChanged(doseId)
        dismissUnlockSummaryIfSettled()
        notifier.showActionConfirmation(context.getString(R.string.toast_skipped))
    }

    /**
     * Acknowledges a 复查 reminder.
     *
     * Only the notification is cleared, and the round is *not* closed: the button means "I have seen
     * this", not "I went to the doctor". Closing the round is a deliberate act in the medication editor
     * (「开始新一轮」), because getting it wrong in the direction of "silently reset the counter" would
     * lose the very number the reminder exists to track.
     */
    private suspend fun handleReviewAck(context: Context, medicationId: Long) {
        if (medicationId <= 0L) return
        notifier.cancelReviewReminder(medicationId)
        notifier.showActionConfirmation(context.getString(R.string.toast_saved))
    }

    /**
     * Opens the pre-built 复查 search in the user's browser.
     *
     * A receiver rather than a `PendingIntent.getActivity` on the URL directly, so that a device with no
     * browser gets the confirmation toast instead of a `ActivityNotFoundException` inside the
     * notification manager - which would look to the user like the button silently doing nothing.
     */
    private fun handleReviewSearch(context: Context, url: String?) {
        if (url.isNullOrBlank()) return
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            Log.w(TAG, "no browser to open the review search", it)
            notifier.showActionConfirmation(context.getString(R.string.review_search_no_browser))
        }
    }

    /**
     * Removes the "你还有 N 项没吃" summary once nothing it was counting is outstanding any more.
     *
     * The summary is a count, so leaving it in the shade after the last dose has been dealt with would
     * make the app lie about the user's own record - the one thing a medication log must never do.
     * It is only cancelled when *nothing* overdue and unrecorded is left, so dealing with one of three
     * doses correctly keeps the other two visible.
     */
    private suspend fun dismissUnlockSummaryIfSettled() {
        runCatching {
            val now = System.currentTimeMillis()
            val prefs = settingsRepository.current()
            val floor = now - prefs.staleReminderMinutes.coerceAtLeast(1) * 60_000L
            if (doseRepository.getUnlockCatchUpCandidates(now, floor).isEmpty()) {
                notifier.cancelUnlockCatchUp()
            }
        }
    }

    companion object {
        private const val TAG = "NotificationAction"

        const val ACTION_TAKEN = "com.meditrack.action.NOTIF_TAKEN"
        const val ACTION_SNOOZE = "com.meditrack.action.NOTIF_SNOOZE"
        const val ACTION_SKIP = "com.meditrack.action.NOTIF_SKIP"

        /**
         * Acknowledges a 复查 reminder.
         *
         * Carries the *medication* id in the dose slot: a review belongs to a medication, not to a dose,
         * and giving the extra a second name would mean a second `PendingIntent` request space for no
         * benefit. The receiver dispatches on the action, so the two never mix.
         */
        const val ACTION_REVIEW_ACK = "com.meditrack.action.NOTIF_REVIEW_ACK"

        /** Opens the pre-built 复查 search URL; the url travels in [EXTRA_SEARCH_URL]. */
        const val ACTION_REVIEW_SEARCH = "com.meditrack.action.NOTIF_REVIEW_SEARCH"

        const val EXTRA_DOSE_ID = "extra_dose_id"
        const val EXTRA_SNOOZE_MINUTES = "extra_snooze_minutes"
        const val EXTRA_SEARCH_URL = "extra_search_url"
    }
}
