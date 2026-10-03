package com.meditrack.domain.reminder

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays the reminder tone from the app itself.
 *
 * ## Why the app plays its own audio
 *
 * The obvious design is to hand a sound URI to the notification channel and let the system play it. On
 * a Redmi running MIUI V140 that is silently ignored: the channel carries
 * `android.resource://com.meditrack/…` with importance HIGH and vibration on, yet posting a reminder
 * creates **no audio track at all** (`dumpsys media.audio_flinger`: "N Tracks of which 0 are active"),
 * while the same tone played by this class produces "1 are active" and a MediaPlayer in our own uid.
 * Notification sound policy on that ROM belongs to the vendor's notification manager, and nothing the
 * app declares changes it.
 *
 * So the tone is played by the app, on the alarm stream, and the channel is left silent. That makes the
 * audible reminder independent of every vendor's ringtone resolver - vivo, Xiaomi, OPPO, AOSP - which is
 * the whole point of shipping the tones inside the APK.
 *
 * ## Behaviour
 *
 *  - **Alarm usage**, so it is audible at the alarm volume and not squelched by Do Not Disturb the way a
 *    chat notification would be. This matches what the reminder is for.
 *  - **Transient focus**, requested politely and abandoned on completion; if focus is refused the tone
 *    still plays, because a missed dose is worse than a slightly rude interruption.
 *  - **Self-terminating**: the tones are a couple of seconds long and the player releases itself.
 */
@Singleton
class ReminderSoundPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null

    /**
     * Plays [tone] (or [customSoundUri] when the user picked a file), replacing whatever was playing.
     *
     * Safe to call from a broadcast receiver or a service: it returns immediately and never throws.
     */
    fun play(tone: ReminderTone, customSoundUri: String? = null) {
        runCatching {
            stop()
            val attributes = alarmAttributes()
            val mediaPlayer = MediaPlayer().apply { setAudioAttributes(attributes) }
            val prepared = when {
                !customSoundUri.isNullOrBlank() ->
                    runCatching {
                        mediaPlayer.setDataSource(context, Uri.parse(customSoundUri))
                        true
                    }.getOrDefault(false)

                else -> false
            }
            if (!prepared) {
                val descriptor = context.resources.openRawResourceFd(tone.rawRes)
                    ?: throw IllegalStateException("找不到铃声资源")
                descriptor.use {
                    mediaPlayer.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
            }
            mediaPlayer.setOnCompletionListener { finished ->
                finished.release()
                if (player === finished) player = null
                abandonFocus()
            }
            mediaPlayer.setOnErrorListener { failed, _, _ ->
                Log.w(TAG, "tone playback failed")
                failed.release()
                if (player === failed) player = null
                abandonFocus()
                true
            }
            requestFocus()
            mediaPlayer.prepare()
            mediaPlayer.start()
            player = mediaPlayer
            Log.i(TAG, "playing reminder tone ${tone.name}")
        }.onFailure { Log.w(TAG, "could not play reminder tone", it) }
    }

    /** Stops playback, e.g. when the user acts on the notification. */
    fun stop() {
        player?.let { current ->
            runCatching { if (current.isPlaying) current.stop() }
            runCatching { current.release() }
        }
        player = null
        abandonFocus()
    }

    private fun alarmAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /**
     * True when the phone is in a state where a sound would be unwelcome.
     *
     * Used to honour the 「静音时仍然响铃」 switch: with it off, a silent ringer or an active
     * Do-Not-Disturb filter means the reminder stays quiet but still appears.
     */
    fun isSilenced(): Boolean {
        val manager = audioManager ?: return false
        if (manager.ringerMode != AudioManager.RINGER_MODE_NORMAL) return true
        val notifications = context.getSystemService(android.app.NotificationManager::class.java)
        return notifications?.currentInterruptionFilter?.let {
            it != android.app.NotificationManager.INTERRUPTION_FILTER_ALL
        } ?: false
    }

    private fun requestFocus() {
        val manager = audioManager ?: return
        runCatching {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(alarmAttributes())
                .setOnAudioFocusChangeListener { }
                .build()
            focusRequest = request
            manager.requestAudioFocus(request)
        }
    }

    private fun abandonFocus() {
        val manager = audioManager ?: return
        val request = focusRequest ?: return
        focusRequest = null
        runCatching { manager.abandonAudioFocusRequest(request) }
    }

    private companion object {
        const val TAG = "ReminderSoundPlayer"
    }
}
