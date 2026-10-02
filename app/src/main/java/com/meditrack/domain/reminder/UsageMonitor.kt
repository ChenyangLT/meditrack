package com.meditrack.domain.reminder

import android.content.Context
import android.os.PowerManager
import com.meditrack.data.prefs.SettingsRepository
import com.meditrack.data.prefs.UserPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reports whether anyone is actually using the phone, for the opt-in idle-deferral feature.
 *
 * ## This feature is off by default
 *
 * Everything here is gated behind [UserPreferences.idleDeferralEnabled]. With the default settings
 * [shouldDefer] returns false immediately and no device state is inspected at all. It is an explicit
 * opt-in because it changes *when* notifications arrive, which is not something to impose.
 *
 * ## What "in use" means
 *
 * The app deliberately does **not** request `PACKAGE_USAGE_STATS`. That permission would let it
 * enumerate every other app the user opens, which is a wildly disproportionate amount of access for
 * "don't buzz me while I'm asleep". Instead it observes ordinary lifecycle broadcasts - unlock and
 * screen state - registered at runtime by [com.meditrack.MediTrackApp] only while the feature is on.
 *
 * ## What this class no longer does
 *
 * It used to own delivery of withheld reminders as well. That responsibility now belongs to
 * [ReminderEngine], which reconciles *every* dose from the database on every pass. Keeping the
 * deferred queue separate meant a withheld reminder could only ever be released by one specific
 * broadcast - so a withheld dose that missed the unlock, or that was withheld by a process which
 * then died, was stranded. The engine re-examines deferred doses unconditionally, so a withheld
 * reminder is now just another dose with a state, not a parallel delivery mechanism.
 */
@Singleton
class UsageMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {

    private val powerManager: PowerManager?
        get() = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    /** True while the screen is on and the device is not dozing. */
    fun isInteractive(): Boolean = powerManager?.isInteractive ?: true

    /** Records "the user is here right now"; called from the observed broadcasts and app start. */
    suspend fun recordInteraction(at: Long = System.currentTimeMillis()) {
        settingsRepository.recordInteraction(at)
    }

    /**
     * Decides whether a reminder should be withheld right now.
     *
     * The rule itself lives in [UserPreferences.shouldDeferReminder] so it can be unit tested
     * without a device; this method only supplies the current device state.
     */
    suspend fun shouldDefer(prefs: UserPreferences, now: Long = System.currentTimeMillis()): Boolean {
        if (!prefs.idleDeferralEnabled) return false
        return prefs.shouldDeferReminder(
            screenInteractive = isInteractive(),
            lastInteractionMillis = settingsRepository.lastInteraction(),
            nowMillis = now,
        )
    }
}
