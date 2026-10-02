package com.meditrack.widget

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.meditrack.core.util.DateTimeUtils
import com.meditrack.data.repository.DoseRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Refreshes the home-screen widget.
 *
 * The widget is normally updated by data writes and by alarm delivery, but neither of those fires
 * when the phone simply sits on a desk. This worker is the periodic safety net that keeps the
 * "即将服用" ordering and the countdown honest, and it also runs on demand right after a write so the
 * launcher never shows a stale row.
 *
 * It performs three steps in order, because each one can change what the next should render:
 *  1. materialise today's doses (a day may have rolled over since the last run);
 *  2. sweep anything that has now lapsed into MISSED;
 *  3. push the new payload to every placed widget.
 *
 * Failures are reported as [Result.retry] while attempts remain: a widget that silently stops
 * updating is exactly the kind of failure a medication reminder cannot afford.
 */
@HiltWorker
class WidgetUpdateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val doseRepository: DoseRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (runAttemptCount > MAX_ATTEMPTS) {
            Log.w(TAG, "giving up after $runAttemptCount attempts")
            return Result.failure()
        }
        return try {
            val today = DateTimeUtils.todayEpochDay()
            // Today and tomorrow: a widget left on screen past midnight must already show the new
            // day rather than yesterday's completed list.
            doseRepository.materializeDay(today)
            doseRepository.materializeDay(today + 1)
            doseRepository.sweepMissed(today)
            WidgetRefresh.refreshNow(applicationContext)
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "widget refresh failed", t)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "WidgetUpdateWorker"

        /** Unique names used for the immediate and the periodic refresh. */
        const val UNIQUE_NAME = "meditrack_widget_refresh"
        const val UNIQUE_PERIODIC_NAME = "meditrack_widget_periodic"

        /** WorkManager tag applied to the periodic request, for diagnostics and cancellation. */
        const val WORK_TAG = "widget_refresh"

        private const val MAX_ATTEMPTS = 3
    }
}
