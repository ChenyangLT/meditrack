package com.meditrack.domain.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The single entry point for every alarm and system event the reminder pipeline reacts to.
 *
 * ## The design decision that matters
 *
 * Every branch below does exactly the same thing: run one full, idempotent reconciliation pass.
 * None of them "handles" their own dose, and none of them trusts the intent that woke them.
 *
 * That is deliberate, and it is the whole lesson taken from
 * [Chrono](https://github.com/vicolo-dev/chrono), whose `updateAlarms()` cancels every scheduled
 * alarm and re-derives the entire list from persisted state on *every* trigger - a dose firing, the
 * device booting, a background fetch, or the app coming back. A receiver that acts only on its own
 * payload makes the alarm the source of truth, and an alarm that arrived late, early or not at all
 * then propagates straight into the user's day.
 *
 * Here the payload is only a hint. The dose that fired is re-read from the database, re-evaluated
 * against the clock, and either announced, corrected, or deliberately suppressed - and in every
 * case the whole schedule is rebuilt afterwards, so the next alarm is guaranteed to exist whichever
 * path got us here. If this receiver is never called at all, the heartbeat and the WorkManager path
 * produce the same outcome within minutes.
 *
 * `goAsync()` is used because the pass touches Room, which must not run on the main thread; the
 * receiver stays alive until the coroutine completes.
 */
@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {

    @Inject lateinit var engine: ReminderEngine

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        // Entry log: this is the only way to tell "the alarm never reached us" apart from "we ran and
        // deliberately stayed quiet" when diagnosing a reminder that did not appear.
        val doseId = intent.getLongExtra(EXTRA_DOSE_ID, -1L)
        Log.i(TAG, "onReceive action=$action doseId=$doseId")

        val trigger = triggerFor(action) ?: run {
            Log.w(TAG, "ignoring unknown action $action")
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // The payload is a hint, but this one hint is worth passing on: it is what lets the
                // planner apply "never announce early" to the dose that actually fired instead of to
                // every dose in the pass.
                engine.reconcile(trigger, claimedDoseId = doseId.takeIf { it > 0L })
            } catch (t: Throwable) {
                Log.e(TAG, "reconcile failed for $action", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun triggerFor(action: String): ReminderTrigger? = when (action) {
        ACTION_DOSE_ALARM -> ReminderTrigger.DOSE_ALARM
        ACTION_PRE_ALARM -> ReminderTrigger.PRE_ALARM
        ACTION_SELF_CHECK -> ReminderTrigger.HEARTBEAT
        ACTION_NIGHTLY_PASS -> ReminderTrigger.NIGHTLY
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_LOCKED_BOOT_COMPLETED,
        Intent.ACTION_MY_PACKAGE_REPLACED,
        -> ReminderTrigger.BOOT
        Intent.ACTION_TIME_CHANGED,
        Intent.ACTION_TIMEZONE_CHANGED,
        -> ReminderTrigger.CLOCK_CHANGED
        else -> null
    }

    companion object {
        private const val TAG = "ReminderReceiver"

        /** Armed by [ReminderHeartbeat] for one dose. */
        const val ACTION_DOSE_ALARM = "com.meditrack.action.DOSE_ALARM"

        /** The optional advance notice for one dose. */
        const val ACTION_PRE_ALARM = "com.meditrack.action.PRE_ALARM"

        /** The rolling short-interval self-check. */
        const val ACTION_SELF_CHECK = "com.meditrack.action.SELF_CHECK"

        /** The once-a-day deep pass. */
        const val ACTION_NIGHTLY_PASS = "com.meditrack.action.NIGHTLY_PASS"

        const val EXTRA_DOSE_ID = "extra_dose_id"
    }
}
