package com.meditrack.domain.reminder

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.meditrack.data.prefs.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * The independent background reconciliation path.
 *
 * ## Why a second mechanism at all
 *
 * `AlarmManager` and `JobScheduler`/`WorkManager` fail in *different* ways. An OEM task killer that
 * reaps the app's alarms frequently leaves scheduled jobs alone, and vice versa; a device in deep
 * Doze may defer both, but on different schedules. Chrono runs exactly this redundancy - an exact
 * alarm per item, plus a `background_fetch` headless task, plus an optional foreground service tick -
 * and all three simply call the same `updateAlarms()`.
 *
 * Here the same idea costs one small worker, because [ReminderEngine.reconcile] is idempotent:
 * whichever path arrives first does the work and the others find nothing to do. That is what turns
 * "the reminder never appeared" into "the reminder appeared at most one interval late".
 *
 * ## Constraints
 *
 * Deliberately none. Requiring a network or charging would defeat the purpose: a medication reminder
 * matters most on an idle, unplugged phone overnight, which is exactly when those constraints would
 * block it. The work itself is a few database reads.
 */
@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val engine: ReminderEngine,
    private val settingsRepository: SettingsRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (runAttemptCount > MAX_ATTEMPTS) {
            Log.w(TAG, "giving up after $runAttemptCount attempts")
            return Result.failure()
        }
        return try {
            engine.reconcile(ReminderTrigger.PERIODIC_WORK)
            Result.success()
        } catch (t: Throwable) {
            // Retry rather than fail: a reminder pipeline that stops retrying is a pipeline that has
            // silently given up, which is the exact failure mode this class exists to remove.
            Log.w(TAG, "periodic reconcile failed", t)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ReminderWorker"

        const val UNIQUE_NAME = "meditrack_reminder_reconcile"

        /** WorkManager's own floor for periodic work, and the right cadence for a safety net. */
        const val INTERVAL_MINUTES = 15L

        private const val MAX_ATTEMPTS = 3

        /**
         * Arms or disarms the periodic path to match the user's preference.
         *
         * `UPDATE` rather than `REPLACE`/`KEEP` so that toggling the setting takes effect
         * immediately without resetting the interval on every app launch.
         */
        fun apply(enabled: Boolean, workManager: WorkManager) {
            runCatching {
                if (!enabled) {
                    workManager.cancelUniqueWork(UNIQUE_NAME)
                    Log.i(TAG, "background reconciliation disabled")
                    return@runCatching
                }
                val request = PeriodicWorkRequestBuilder<ReminderWorker>(
                    INTERVAL_MINUTES,
                    TimeUnit.MINUTES,
                )
                    // A short initial delay keeps this from racing the bootstrap pass that runs at
                    // the same moment, and lets the process finish starting up first.
                    .setInitialDelay(2, TimeUnit.MINUTES)
                    .setConstraints(Constraints.NONE)
                    .addTag(UNIQUE_NAME)
                    .build()

                workManager.enqueueUniquePeriodicWork(
                    UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
                Log.i(TAG, "background reconciliation armed every ${INTERVAL_MINUTES}m")
            }.onFailure { Log.w(TAG, "could not apply the reconciliation schedule", it) }
        }
    }
}
