package com.meditrack.domain.reminder

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one thing that actually makes a noise for a reminder.
 *
 * ## Why this is not [ReminderSoundPlayer]
 *
 * [ReminderSoundPlayer] plays a tone *once* and releases itself - correct for a preview and for the
 * self-test, and exactly wrong for "keep ringing until I answer". A continuous ring has a different
 * lifecycle (start, hold, stop on demand, outlive the caller's return) and a different set of failure
 * modes (a second ring arriving while the first is going), so it gets its own owner rather than a flag
 * threaded through the existing one.
 *
 * ## The guarantee this class exists to provide
 *
 * **Any sign of the user having answered silences the ring, immediately.** The sources are a
 * notification action (已服用 / 稍后 / 跳过), the in-app controls, a new ring replacing this one, and
 * the configured time cap. Because the ring is a `MediaPlayer` looping inside this process,
 * cancelling the notification is *not* enough to silence it - which is why all of those paths call
 * [stop], not just `NotificationManager.cancel`.
 *
 * ## Deliberate limits
 *
 *  - **It does not resume.** A ring dies with the process, as specified; the notification - which the
 *    user can still act on - is what survives a restart.
 *  - **It respects the ringer.** With 「静音时仍然响铃」 off, a silent phone stays silent; the banner,
 *    the lock-screen entry and the vibration all still happen.
 *  - **It is a singleton.** Two rings cannot overlap, so a second dose arriving mid-ring replaces the
 *    first instead of layering a second audio stream on top of it.
 */
@Singleton
class RingingController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val soundPlayer: ReminderSoundPlayer,
) {

    private val audioManager = context.getSystemService(AudioManager::class.java)

    /**
     * `Main.immediate` because every [start] and [stop] arrives from a background path (a receiver, a
     * worker, a service) and the state it mutates is read by the UI; hopping to the main thread here
     * keeps `session` coherent for everyone with a single lock rather than a lock plus a dispatcher.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Serialises start/stop so a stop racing a start cannot leave a player running with no owner. */
    private val gate = Mutex()

    private var player: MediaPlayer? = null
    private var loopJob: Job? = null
    private var current: RingSession? = null

    /**
     * Whether the user asked to ring through a silent ringer.
     *
     * Pushed in by the settings layer whenever preferences change, so this class never reads DataStore
     * from a broadcast receiver - a suspend call in a place that must not block.
     */
    @Volatile
    var overrideSilent: Boolean = false

    /** Notified whenever the set of ringing doses changes, so a foreground service can follow suit. */
    @Volatile
    var onSessionChanged: ((RingSession?) -> Unit)? = null

    /** The session ringing right now, or null. Read by the settings screen and the service. */
    val session: RingSession? get() = current

    /** True while a ring is looping or chime-counting. */
    val isRinging: Boolean get() = current != null

    /**
     * Starts (or restarts) the ring for one announcement.
     *
     * Restarting is intentional: a second announcement of the same dose is a *fresh* ring, because the
     * user demonstrably did not answer the first one.
     *
     * @param doseId the dose to silence when it is answered, or null for an aggregate announcement (the
     *        unlock catch-up summary), which has no single dose to hook and can only be silenced by the
     *        cap or by the user's explicit stop.
     * @param customUri an absolute file path or `content://` uri for a user clip; null uses [tone]
     * @return the session now ringing, or null when nothing audible was started
     */
    suspend fun start(
        doseId: Long?,
        policy: RingPolicy,
        tone: ReminderTone,
        customUri: String? = null,
        medicationName: String = "",
        nowMillis: Long = System.currentTimeMillis(),
    ): RingSession? {
        // A policy of "one chime" is not a ring; the notifier has already played that tone itself.
        // Starting a session for it would only create an ongoing notification for no reason.
        if (policy.isSingleChime) return null

        if (!isAudibleAllowed()) {
            Log.i(TAG, "not ringing for dose $doseId: the phone is silenced and 静音时仍然响铃 is off")
            return null
        }

        return gate.withLock {
            stopLocked()
            val session = RingSession(
                doseId = doseId,
                startedAtMillis = nowMillis,
                policy = policy,
                medicationName = medicationName,
            )
            current = session
            playSource(tone, customUri, loop = policy.mode == ReminderRingMode.UNTIL_ACTION)
            when (policy.mode) {
                // The loop is the player's own `isLooping`; this job only enforces the cap.
                ReminderRingMode.UNTIL_ACTION -> loopJob = scope.launch { holdUntilCapped(session) }
                ReminderRingMode.FIXED_TIMES -> loopJob = scope.launch { chimeFixedTimes(session, tone, customUri) }
                ReminderRingMode.ONCE -> Unit
            }
            onSessionChanged?.invoke(session)
            Log.i(TAG, "ringing dose=$doseId mode=${policy.mode} chimes=${policy.chimeCount}")
            session
        }
    }

    /**
     * Silences everything, whoever asked.
     *
     * Safe to call from a broadcast receiver: it never blocks on IO and never throws. Called on every
     * notification action, on every in-app action, and when the cap is reached.
     */
    suspend fun stop(reason: String) = gate.withLock {
        if (current == null && player == null) return@withLock
        Log.i(TAG, "stopping ring: $reason")
        stopLocked()
        onSessionChanged?.invoke(null)
    }

    /**
     * [stop] for the notification-action path, which only knows the dose it acted on.
     *
     * An aggregate ring (null dose) deliberately keeps going: answering one of three overdue doses says
     * nothing about the other two, and silencing them would hide the ones still outstanding.
     */
    suspend fun stopFor(doseId: Long, reason: String) {
        val active = current ?: return
        if (active.doseId == null) return
        if (active.doseId != doseId) return
        stop(reason)
    }

    /** Stops whatever is ringing. Used by the 停止响铃 button and when reminders are switched off. */
    suspend fun stopAll(reason: String) = stop(reason)

    /** Applies a freshly-read preference snapshot without an injection cycle. */
    fun applyPreferences(prefs: com.meditrack.data.prefs.UserPreferences) {
        overrideSilent = prefs.overrideSilent
        soundPlayer.setOverrideSilent(prefs.overrideSilent)
    }

    // ---------------------------------------------------------------- internals

    private suspend fun holdUntilCapped(session: RingSession) {
        val cap = session.policy.maxDurationMillis
        if (cap <= 0L) return
        // One delayed stop rather than a polling loop: a phone in doze should not be woken every second
        // just to be asked whether it is still ringing. The end instant cannot move, so a single timer is
        // exactly as correct as polling and two orders of magnitude cheaper.
        delay(cap)
        if (current?.startedAtMillis == session.startedAtMillis) {
            stop("已响满 ${cap / 60_000} 分钟，通知保留")
        }
    }

    private suspend fun CoroutineScope.chimeFixedTimes(
        session: RingSession,
        tone: ReminderTone,
        customUri: String?,
    ) {
        var played = 1
        // `isActive` on the scope, not on an implicit receiver: the loop must stop if the process is
        // being torn down, but must NOT stop merely because a caller's scope was cancelled.
        while (isActive && played < session.policy.chimeCount) {
            delay(session.policy.intervalMillis)
            if (current?.startedAtMillis != session.startedAtMillis) return
            if (session.isExpired(System.currentTimeMillis())) {
                stop("时间到了，通知保留")
                return
            }
            playSource(tone, customUri, loop = false)
            played++
            current = current?.copy(completedChimes = played)
            onSessionChanged?.invoke(current)
        }
        // Hold for one more interval before releasing, so the count sequence is not cut off by the stop
        // and a short policy still leaves the ongoing notification visible for a moment.
        delay(session.policy.intervalMillis)
        if (current?.startedAtMillis == session.startedAtMillis) {
            stop("已响满 ${session.policy.chimeCount} 次，通知保留")
        }
    }

    private fun playSource(tone: ReminderTone, customUri: String?, loop: Boolean) {
        runCatching {
            releasePlayer()
            val mediaPlayer = MediaPlayer().apply { setAudioAttributes(alarmAttributes()) }
            val prepared = if (!customUri.isNullOrBlank()) {
                runCatching {
                    mediaPlayer.setDataSource(context, dataSourceOf(customUri))
                    true
                }.getOrDefault(false)
            } else {
                false
            }
            if (!prepared) {
                // A clip that has been deleted (cache clear, uninstall of the source app) must degrade to
                // the bundled tone rather than to silence: the user still has to be told to take a pill.
                val descriptor = context.resources.openRawResourceFd(tone.rawRes)
                    ?: throw IllegalStateException("找不到铃声资源")
                descriptor.use {
                    mediaPlayer.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
            }
            mediaPlayer.isLooping = loop
            mediaPlayer.setOnErrorListener { failed, _, _ ->
                Log.w(TAG, "ringing source failed to play")
                runCatching { failed.release() }
                if (player === failed) player = null
                true
            }
            mediaPlayer.prepare()
            mediaPlayer.start()
            player = mediaPlayer
        }.onFailure { Log.w(TAG, "could not start the ringing source", it) }
    }

    /**
     * Resolves a stored clip reference to something `MediaPlayer` accepts.
     *
     * Clips are stored as absolute paths inside app-private storage, but a `content://` uri is also
     * accepted so a system ringtone copied whole keeps working.
     */
    private fun dataSourceOf(reference: String): Uri =
        if (reference.startsWith("content://") || reference.startsWith("file://")) Uri.parse(reference)
        else Uri.fromFile(java.io.File(reference))

    /**
     * Whether an audible ring may start.
     *
     * Mirrors the notifier's rule exactly, including the 「静音时仍然响铃」 escape hatch, so a silent phone
     * neither starts a ring the notifier would have suppressed nor leaves an ongoing notification
     * claiming it is ringing.
     */
    private fun isAudibleAllowed(): Boolean {
        val manager = audioManager ?: return true
        val ringerSilent = manager.ringerMode != AudioManager.RINGER_MODE_NORMAL
        val dndActive = context.getSystemService(android.app.NotificationManager::class.java)
            ?.currentInterruptionFilter
            ?.let { it != android.app.NotificationManager.INTERRUPTION_FILTER_ALL }
            ?: false
        return !(ringerSilent || dndActive) || overrideSilent
    }

    private fun alarmAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun releasePlayer() {
        player?.let { active ->
            runCatching { if (active.isPlaying) active.stop() }
            runCatching { active.release() }
        }
        player = null
    }

    /** Caller must hold [gate]. */
    private fun stopLocked() {
        loopJob?.cancel()
        loopJob = null
        releasePlayer()
        current = null
    }

    private companion object {
        const val TAG = "RingingController"
    }
}
