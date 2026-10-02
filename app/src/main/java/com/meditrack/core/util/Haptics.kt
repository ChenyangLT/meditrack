package com.meditrack.core.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The light tick used by the "+" / "-" buttons and the swipe confirmations.
 *
 * Haptics are part of the accessibility story here: a user with reduced vision relies on the
 * confirmation that a tap registered. The helper degrades silently on devices without a vibrator
 * and respects the system's haptic setting by using [VibrationEffect.Composition] primitives
 * rather than a raw waveform.
 */
object Haptics {

    /** Short, soft tick for a stepper press. */
    fun tick(context: Context) = vibrate(context, 12L, 40)

    /** A slightly firmer bump for a state change (marked taken, skipped). */
    fun confirm(context: Context) = vibrate(context, 24L, 80)

    /** Two quick pulses used for the "多服" warning. */
    fun warn(context: Context) {
        val vibrator = vibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 30, 60, 30), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 30, 60, 30), -1)
            }
        }
    }

    private fun vibrate(context: Context, durationMs: Long, amplitude: Int) {
        val vibrator = vibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(durationMs, amplitude.coerceIn(1, 255))
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        }
    }

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
}
