package com.meditrack.domain.reminder

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Answers one question: **is a human actually looking at this phone right now?**
 *
 * ## What it is for
 *
 * The unlock catch-up ([ReminderPlanner.unlockCatchUpEligible]) is only honest if it fires when
 * somebody is there to read it. This class supplies the device half of that judgement, so the rule
 * itself can stay a pure function of (dose, preferences, clock) and be tested on the JVM.
 *
 * ## What it deliberately does not do
 *
 * It does **not** read usage statistics. `PACKAGE_USAGE_STATS` would let the app enumerate every
 * other app the user opens, which is an absurd amount of access to grant a medication reminder. It
 * reads two ordinary, public pieces of device state instead - screen interactivity and whether the
 * keyguard is up - both of which need no permission at all.
 *
 * ## What replaced the old idle-deferral logic
 *
 * The previous design asked "has the phone been idle for long enough?" and *withheld* reminders on
 * that basis, releasing them later. That was actively harmful: a dose withheld across its grace
 * period came back as a silent 未服药 record rather than as a reminder, which is exactly the
 * "提醒被抵掉" symptom. Nothing is withheld any more. The scheduled reminder always fires, and this
 * class only ever adds a second, earlier chance to be heard.
 */
@Singleton
class UserPresence @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val powerManager: PowerManager?
        get() = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    /** True while the screen is on and the device is not dozing. */
    fun isInteractive(): Boolean = powerManager?.isInteractive ?: true

    /**
     * True when the screen is on **and** the keyguard is already dismissed.
     *
     * The distinction matters on a locked phone: waking the display to show the clock, or glancing at
     * the lock screen to check the time, puts the screen on without anybody being able to read or act
     * on a notification. Only a phone that is awake *and* unlocked counts as "the user is here".
     *
     * A device with no keyguard service at all (rare, but it exists on some ROMs) is treated as
     * unlocked, because refusing to place a catch-up there would silently disable the feature.
     */
    fun isScreenOnAndUnlocked(): Boolean {
        if (!isInteractive()) return false
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return keyguard == null || !keyguard.isKeyguardLocked
    }
}
