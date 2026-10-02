package com.meditrack.domain.reminder

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.meditrack.MainActivity
import com.meditrack.R
import com.meditrack.data.prefs.SettingsRepository
import com.meditrack.widget.WidgetContentBuilder
import com.meditrack.widget.WidgetRefresh
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the process alive so the reminders scheduled in it actually get delivered.
 *
 * ## Why this exists
 *
 * Every other part of this pipeline is a *scheduled wake-up*: an exact alarm, a rolling heartbeat, a
 * WorkManager job. They are redundant with each other, and they cover every failure mode except one -
 * **the app being cleared out from under them**.
 *
 * A force-stop, or an OEM "deep clean" of background apps, does not delay an app's alarms; it cancels
 * every one of them and unregisters its jobs, and the app then receives *nothing* until the user
 * opens it again. No number of additional alarms helps, because they all die in the same sweep. The
 * symptom is unmistakable once you know it: reminders never arrive while the app is closed, and the
 * moment it is opened every overdue one appears at once.
 *
 * The only mechanism that survives is a **running foreground service**. It keeps the process out of
 * the cached bucket those cleaners target, and because it is visible the user can see that protection
 * is on. This is the same approach Chrono takes, and for the same reason.
 *
 * ## What it does and does not do
 *
 * It does not do any reminding itself. It runs [ReminderEngine.reconcile] on start and periodically
 * after that, and otherwise just stays alive - the pipeline's correctness still rests on the engine
 * being idempotent, not on this service being present. With it switched off the app behaves exactly
 * as before; it simply becomes vulnerable to being cleared.
 *
 * ## Restart behaviour
 *
 * [START_STICKY] asks the system to recreate the service if it is killed for memory, and
 * [onTaskRemoved] covers the very common case of the user swiping the app out of the recents list -
 * which on many ROMs is treated as "the user is done with this app" and used as a trigger to cancel
 * everything it had scheduled.
 */
@AndroidEntryPoint
class ReminderGuardService : Service() {

    @Inject lateinit var engine: ReminderEngine
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var registry: ReminderAlarmRegistry
    @Inject lateinit var notifier: DoseNotifier
    @Inject lateinit var widgetContentBuilder: WidgetContentBuilder

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The widget re-check loop, or null while it is not running. */
    private var widgetTicker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // The notification channel has to exist before the first foreground notification, and the
        // service can be created before the Application has finished starting on some devices.
        notifier.createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requires a foreground service to post its notification within a few seconds of
        // being started, so this happens before any database or DataStore work.
        promoteToForeground(getString(R.string.guard_notification_starting))

        scope.launch {
            val prefs = runCatching { settingsRepository.current() }.getOrNull()
            if (prefs == null || !prefs.remindersEnabled || !prefs.guardServiceEnabled) {
                Log.i(TAG, "guard no longer wanted; stopping")
                stopSelf()
                return@launch
            }
            val report = runCatching { engine.reconcile(ReminderTrigger.GUARD_SERVICE) }.getOrNull()
            registry.noteGuardAlive(System.currentTimeMillis())
            updateNotification(report)
        }
        startWidgetTicker()
        // Recreated after being killed for memory, with a null intent.
        return START_STICKY
    }

    /**
     * Drives the widget's refresh cadence.
     *
     * The user can ask for the widget to be re-checked as often as every ten seconds. Neither
     * mechanism that normally refreshes a widget can express that - `WorkManager`'s floor for
     * periodic work is fifteen minutes and `AppWidgetProviderInfo.updatePeriodMillis` bottoms out at
     * thirty - so a running foreground service is the only thing that can, which is another reason
     * the guard is on by default.
     *
     * Two honest caveats, both deliberate rather than oversights:
     *
     *  - **A sleeping device does not tick.** Coroutine delays run on the monotonic clock, which stops
     *    while the phone is suspended. That costs nothing in practice: the widget is only *visible*
     *    when the screen is on, and the loop resumes within one interval of the phone waking.
     *  - **A tick is not a redraw.** The payload is fingerprinted first, so a ten-second interval
     *    costs one small database read and only disturbs the launcher when a dose actually changes
     *    state. That is what makes a short interval honest rather than wasteful.
     */
    private fun startWidgetTicker() {
        if (widgetTicker?.isActive == true) return
        widgetTicker = scope.launch {
            while (isActive) {
                val seconds = runCatching { settingsRepository.current().widgetRefreshSeconds }
                    .getOrDefault(DEFAULT_WIDGET_REFRESH_SECONDS)
                    .coerceIn(SettingsRepository.MIN_WIDGET_REFRESH_SECONDS, SettingsRepository.MAX_WIDGET_REFRESH_SECONDS)
                delay(seconds * 1_000L)

                // Nothing placed means nothing to keep current.
                if (!WidgetRefresh.isPlaced(this@ReminderGuardService)) continue
                runCatching {
                    WidgetRefresh.refreshIfChanged(this@ReminderGuardService, widgetContentBuilder)
                }.onFailure { Log.w(TAG, "widget refresh tick failed", it) }
            }
        }
    }

    /**
     * The user swiped the app out of recents.
     *
     * On stock Android this is harmless. On several OEM ROMs it is the signal to cancel everything
     * the app had scheduled, so the service is deliberately not stopped here - it is restarted a
     * moment later if the system took it down with the task, and it stays up otherwise.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "task removed; keeping the guard alive")
        scheduleRestart()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Last-gasp bookkeeping on a detached scope, because this scope is about to be cancelled.
        // If the process dies before the write lands it does not matter: the health check also treats
        // a stale timestamp as "not running", so the worst case is a delayed answer, never a wrong
        // "protected" one.
        widgetTicker?.cancel()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { registry.markGuardStopped() }
        scope.cancel()
        super.onDestroy()
    }

    private fun promoteToForeground(text: String) {
        val notification = buildNotification(text)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
            }
        }.onFailure { Log.e(TAG, "could not enter the foreground", it) }
    }

    private fun updateNotification(report: ReminderReport?) {
        val text = report?.nextDoseLabel(this) ?: getString(R.string.guard_notification_running)
        runCatching { notifier.notifyGuard(buildNotification(text)) }
            .onFailure { Log.w(TAG, "guard notification update failed", it) }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, DoseNotifier.CHANNEL_GUARD)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.guard_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            // IMPORTANCE_LOW plus this: present and visible, but never buzzing about itself.
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openAppIntent())
            .build()

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Asks the platform to bring the service back shortly.
     *
     * A plain restart is not always permitted, so this is best-effort: the alarm is set and any
     * refusal is logged. [START_STICKY] is the mechanism that actually matters.
     */
    private fun scheduleRestart() {
        runCatching {
            val manager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(this, ReminderGuardService::class.java)
            val pending = PendingIntent.getService(
                this,
                RESTART_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            manager.set(
                android.app.AlarmManager.RTC,
                System.currentTimeMillis() + RESTART_DELAY_MILLIS,
                pending,
            )
        }.onFailure { Log.w(TAG, "could not schedule a guard restart", it) }
    }

    companion object {
        private const val TAG = "ReminderGuardService"

        /** Id of the ongoing notification; distinct from every dose notification id. */
        const val NOTIFICATION_ID = 999_996

        private const val RESTART_REQUEST_CODE = 3
        private const val RESTART_DELAY_MILLIS = 2_000L

        /** Mirrors the shipped default, used only when the preference cannot be read. */
        private const val DEFAULT_WIDGET_REFRESH_SECONDS = 30

        /**
         * Starts the guard if it is wanted.
         *
         * Deliberately tolerant: from Android 12 a background app may not be allowed to start a
         * foreground service, and that refusal is not a reason to break whatever else was happening.
         * The cases that matter - the app being opened, and the boot broadcast - are both permitted.
         */
        fun ensureRunning(context: Context) {
            runCatching {
                val intent = Intent(context, ReminderGuardService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Log.i(TAG, "guard start refused by the platform: ${it.message}") }
        }

        /** Stops the guard; used when reminders or the guard itself are switched off. */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ReminderGuardService::class.java)) }
        }
    }
}

/**
 * "下一个提醒：12:30 阿司匹林" for the ongoing notification.
 *
 * Falls back to null when nothing is pending, so the caller can show a plainer line rather than an
 * empty promise. Only the time is shown, not a countdown: the notification is not updated often
 * enough for a countdown to stay honest.
 */
private fun ReminderReport.nextDoseLabel(context: Context): String? {
    if (nextDoseAt <= 0L) return null
    val time = com.meditrack.core.util.DateTimeUtils.formatDateTime(nextDoseAt).takeLast(5)
    val name = nextDoseName?.takeIf { it.isNotBlank() }
    return if (name == null) {
        context.getString(R.string.guard_notification_next_at, time)
    } else {
        context.getString(R.string.guard_notification_next_at_named, time, name)
    }
}
