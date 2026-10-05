package com.meditrack.domain.reminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the app alive while a reminder is ringing, and shows one obvious way to stop it.
 *
 * ## Why a foreground service rather than just a `MediaPlayer`
 *
 * A `MediaPlayer` playing a user clip for up to five minutes is exactly the kind of work Android
 * expects a foreground service to declare: without one, the process is a cached background process and
 * is a prime candidate for being frozen mid-ring, which would leave the tone cut off and - worse - the
 * `RingingController` state inconsistent with what the user hears. Owning a foreground service for the
 * duration makes the ring part of the app's declared, visible activity.
 *
 * ## Why it stops itself
 *
 * The service is not a scheduler. It exists only for as long as [RingingController] reports a session,
 * and it tears itself down the moment that session ends - including when the user answers the
 * notification, when the cap is reached, or when the user presses 停止响铃. Subscribing to the
 * controller rather than polling means there is exactly one source of truth for "is it ringing", and
 * the notification cannot outlive the sound.
 *
 * ## What it deliberately does not do
 *
 * It does not loop the audio, does not decide how many times to ring, and does not cancel the dose
 * notification. Splitting those out keeps the one thing that must never go wrong - "the sound stops when
 * the user answers" - in a single place ([RingingController]).
 */
@AndroidEntryPoint
class ReminderRingingService : Service() {

    @Inject lateinit var ringingController: RingingController
    @Inject lateinit var doseNotifier: DoseNotifier

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var countdownJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // The controller notifies on every change, so the notification and the service lifetime follow
        // the ring rather than the other way round.
        ringingController.onSessionChanged = { session ->
            if (session == null) finish() else publish(session)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // 停止响铃 is an explicit, unambiguous instruction: silence everything, do not try to
                // work out which dose the user meant.
                scope.launch {
                    ringingController.stopAll("用户点了停止响铃")
                    doseNotifier.showActionConfirmation(getString(R.string.ringing_stopped))
                    finish()
                }
                return START_NOT_STICKY
            }

            else -> {
                val existing = ringingController.session
                if (existing == null) {
                    // Started but nothing is ringing - a stale start, or the ring already ended. Never
                    // sit in the foreground claiming otherwise.
                    finish()
                    return START_NOT_STICKY
                }
                startForegroundSafely()
                publish(existing)
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Enters the foreground, tolerating the platform refusing it.
     *
     * From Android 12 a background app is not always allowed to start a foreground service, and from 14
     * the type must be granted. A refusal must not crash the app mid-ring: the tone is already playing
     * from the controller, so the service is a bonus, not the mechanism.
     */
    private fun startForegroundSafely() {
        val notification = buildNotification(ringingController.session)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { Log.w(TAG, "could not enter the foreground for the ring", it) }
    }

    private fun publish(session: RingSession) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(session)) }
            .onFailure { Log.w(TAG, "ringing notification failed", it) }

        // The countdown is refreshed once a second while it is meaningful, and the ring's own cap does
        // the actual stopping. One notification update per second for at most five minutes is cheap, and
        // it is the difference between "how long will this go on?" and a number.
        if (session.policy.mode == ReminderRingMode.UNTIL_ACTION) {
            countdownJob?.cancel()
            countdownJob = scope.launch {
                while (true) {
                    delay(1_000L)
                    val active = ringingController.session ?: return@launch
                    runCatching { manager.notify(NOTIFICATION_ID, buildNotification(active)) }
                }
            }
        }
    }

    private fun buildNotification(session: RingSession?): Notification {
        val title = getString(
            R.string.ringing_title,
            session?.medicationName?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name),
        )

        val text = session?.countdownLabel(System.currentTimeMillis())
            ?.takeIf { it.isNotBlank() }
            ?.let { getString(R.string.ringing_countdown, it) }
            ?: getString(R.string.ringing_body)

        return NotificationCompat.Builder(this, CHANNEL_RINGING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    text + "\n" + getString(R.string.ringing_body) + "\n" +
                        getString(R.string.ring_note_stopped)
                )
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // Secret: the reminder notification is the one that belongs on the lock screen. This one is a
            // control surface, and duplicating the dose detail there would be noise.
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(
                R.drawable.ic_notification,
                getString(R.string.action_stop_ringing),
                stopIntent(),
            )
            .build()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            REQUEST_CONTENT,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stopIntent(): PendingIntent {
        val intent = Intent(this, ReminderRingingService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getService(
            this,
            REQUEST_STOP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_RINGING,
            getString(R.string.notification_channel_ringing),
            // MIN because this one exists to be findable and to carry the 停止响铃 button, not to be
            // noticed: the audible part comes from the app's own player on the alarm stream.
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.notification_channel_ringing_desc)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        manager.createNotificationChannel(channel)
    }

    private fun finish() {
        countdownJob?.cancel()
        countdownJob = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        countdownJob?.cancel()
        // Clearing the hook matters: the controller is a singleton that outlives this service, and a
        // stale callback would keep a destroyed service's lambda alive and calling into it.
        if (ringingController.onSessionChanged != null) ringingController.onSessionChanged = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RingingService"

        const val ACTION_STOP = "com.meditrack.action.STOP_RINGING"

        /** Its own channel so the control surface can be silenced without touching dose reminders. */
        const val CHANNEL_RINGING = "meditrack_ringing"

        const val NOTIFICATION_ID = 999_993

        private const val REQUEST_CONTENT = 700_001
        private const val REQUEST_STOP = 700_002

        /** Brings the service up for an already-running ring. */
        fun ensureRunning(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, ReminderRingingService::class.java))
            }.onFailure { Log.w(TAG, "could not start the ringing service", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ReminderRingingService::class.java)) }
        }
    }
}
