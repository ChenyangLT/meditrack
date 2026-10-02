package com.meditrack

import android.app.Application
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.meditrack.data.prefs.SettingsRepository
import com.meditrack.domain.reminder.ReminderEngine
import com.meditrack.domain.reminder.ReminderGuardService
import com.meditrack.domain.reminder.ReminderTrigger
import com.meditrack.domain.reminder.ReminderWorker
import com.meditrack.domain.reminder.DoseNotifier
import com.meditrack.domain.reminder.UserActivityReceiver
import com.meditrack.widget.WidgetRefresh
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Startup work is deliberately small and defensive: a phone that boots at 3am must end up with
 * correct alarms and an honest history without the user opening the app.
 *
 * ## What changed
 *
 * The three hand-rolled startup jobs (materialise, repair, arm) are gone, replaced by one call to
 * [ReminderEngine.reconcile]. They were a fourth, subtly different copy of the arming logic - it
 * skipped snoozed doses, used a different horizon from the receiver, and never touched the audit
 * trail - and every copy of "what should be armed" is a chance for the copies to disagree.
 *
 * There is now exactly one definition, and app start is simply one of the several triggers that
 * invokes it.
 */
@HiltAndroidApp
class MediTrackApp : Application(), Configuration.Provider {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var reminderEngine: ReminderEngine
    @Inject lateinit var notifier: DoseNotifier
    @Inject lateinit var workerFactory: HiltWorkerFactory

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The runtime registration for device-activity broadcasts, or null while idle deferral is off.
     *
     * Holding it lets [applyActivityMonitoring] unregister cleanly, which is what makes the "off"
     * state genuinely inert rather than merely ignored.
     */
    private var activityReceiver: UserActivityReceiver? = null

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .apply { if (::workerFactory.isInitialized) setWorkerFactory(workerFactory) }
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.WARN)
            .build()

    override fun onCreate() {
        super.onCreate()

        // WorkManager is initialised here rather than by androidx.startup.
        //
        // This is the documented Hilt pattern and the only arrangement that satisfies lint: the
        // "RemoveWorkManagerInitializer" check fires on the *merged* manifest, so Configuration.Provider
        // and the stock initializer cannot coexist. Deleting the initializer without initialising here
        // (an earlier attempt) crashed the process the first time JobScheduler ran a job.
        //
        // Wrapped defensively: background reconciliation is a safety net, and its absence must never
        // stop the app from opening - the alarm path and the heartbeat do not depend on it.
        runCatching { WorkManager.initialize(this, workManagerConfiguration) }
            .onFailure { Log.w(TAG, "WorkManager initialization failed; background reconciliation disabled", it) }

        // The widget's content builder runs outside Hilt's widget entry point in some launchers, so
        // the application context is published for it here.
        com.meditrack.widget.AppContextHolder.install(this)
        notifier.createChannels()
        appScope.launch { bootstrap() }
    }

    /**
     * Registers or unregisters the device-activity receiver to match the user's preference.
     *
     * Called on every start and whenever the setting changes, so toggling it takes effect
     * immediately instead of after the next reboot.
     *
     * When the feature is off this unregisters and returns: the app then observes **nothing** about
     * device usage. That is the point of it being opt-in.
     */
    fun applyActivityMonitoring(enabled: Boolean) {
        if (enabled) {
            if (activityReceiver != null) return
            val receiver = UserActivityReceiver()
            val filter = IntentFilter().apply {
                UserActivityReceiver.ACTIONS.forEach(::addAction)
            }
            // RECEIVER_NOT_EXPORTED: these are protected system broadcasts, so exported is neither
            // needed nor wanted.
            val registered = runCatching {
                ContextCompat.registerReceiver(
                    this,
                    receiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }.isSuccess
            if (registered) {
                activityReceiver = receiver
                Log.i(TAG, "idle deferral on: observing device activity")
            } else {
                Log.w(TAG, "idle deferral requested but the activity receiver could not be registered")
            }
        } else {
            activityReceiver?.let { receiver ->
                runCatching { unregisterReceiver(receiver) }
                activityReceiver = null
                Log.i(TAG, "idle deferral off: device activity is no longer observed")
            }
        }
    }

    /**
     * One pass of the self-healing pipeline, plus the two periodic safety nets.
     *
     * Everything is wrapped so a failure in one part cannot stop the others or crash the process.
     */
    private suspend fun bootstrap() {
        val prefs = runCatching { settingsRepository.current() }.getOrNull() ?: return

        // Only wire up device-activity observation when the user asked for it.
        runCatching { applyActivityMonitoring(prefs.idleDeferralEnabled) }
            .onFailure { Log.w(TAG, "activity monitoring setup failed", it) }

        // The one and only schedule builder. App start is just another trigger for it.
        runCatching { reminderEngine.reconcile(ReminderTrigger.APP_START) }
            .onFailure { Log.w(TAG, "reminder bootstrap failed", it) }

        // Keep the process alive so those alarms actually get delivered. Started here - a foreground
        // context - because from Android 12 a background app is not always allowed to start a
        // foreground service; the boot receiver and START_STICKY cover the other cases.
        if (prefs.guardServiceEnabled) {
            runCatching { ReminderGuardService.ensureRunning(this) }
                .onFailure { Log.w(TAG, "guard service start failed", it) }
        } else {
            runCatching { ReminderGuardService.stop(this) }
        }

        // The independent WorkManager path, applied to match the current setting.
        runCatching { ReminderWorker.apply(prefs.reliabilityWorkerEnabled, WorkManager.getInstance(this)) }
            .onFailure { Log.w(TAG, "reconciliation worker setup failed", it) }

        // The widget's own cadence is driven by the guard service's ticker; this arms the 15-minute
        // floor that keeps the tile honest when that service is switched off.
        runCatching { WidgetRefresh.applyFallback(this) }
            .onFailure { Log.w(TAG, "widget refresh scheduling failed", it) }
    }

    companion object {
        private const val TAG = "MediTrackApp"
    }
}
