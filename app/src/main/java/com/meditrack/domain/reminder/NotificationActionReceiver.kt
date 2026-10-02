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

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val doseId = intent.getLongExtra(EXTRA_DOSE_ID, -1L)
        if (doseId <= 0L) return
        Log.i(TAG, "onReceive action=$action doseId=$doseId")

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_TAKEN -> handleTaken(appContext, doseId)
                    ACTION_SNOOZE -> handleSnooze(appContext, doseId, intent.getIntExtra(EXTRA_SNOOZE_MINUTES, 0))
                    ACTION_SKIP -> handleSkip(appContext, doseId)
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

        const val EXTRA_DOSE_ID = "extra_dose_id"
        const val EXTRA_SNOOZE_MINUTES = "extra_snooze_minutes"
    }
}
