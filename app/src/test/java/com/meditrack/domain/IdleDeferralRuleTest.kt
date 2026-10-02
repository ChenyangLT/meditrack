package com.meditrack.domain

import com.google.common.truth.Truth.assertThat
import com.meditrack.data.prefs.UserPreferences
import org.junit.Test

/**
 * The rule behind "手机长时间没用了就先别提醒，等开始用的时候再提醒".
 *
 * This is the most behaviour-changing option in the app, because it decides *when* a medication
 * reminder arrives. Two things therefore have to be provably true, and both are pinned here:
 *
 *  1. **Off by default, and genuinely inert when off.** Not merely "ignored" - the app must not
 *     withhold anything, whatever the device state looks like.
 *  2. **When on, the two conditions behave as described** - screen off, or idle past the threshold.
 */
class IdleDeferralRuleTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    /** The shipped defaults, so a change to them fails this test loudly. */
    @Test
    fun `the shipped default is off`() {
        val defaults = UserPreferences()

        assertThat(defaults.idleDeferralEnabled).isFalse()
        assertThat(defaults.idleThresholdMinutes).isEqualTo(30)
        assertThat(defaults.deferWhileScreenOff).isTrue()
    }

    /**
     * The shipped reminder behaviour: banner + vibration, **no tone**.
     *
     * Pinned because each of the three used to default the other way, and the trio is what the user
     * actually experiences. The tone is the one that matters most: a medication reminder fires at a
     * fixed time every day, so a default that rings would wake the household at 6am. Banner and
     * vibration stay on, so the reminder is still impossible to miss.
     */
    @Test
    fun `the shipped reminder is banner and vibration, not a ringing tone`() {
        val defaults = UserPreferences()

        assertThat(defaults.headsUpEnabled).isTrue()
        assertThat(defaults.vibrationEnabled).isTrue()
        assertThat(defaults.soundEnabled).isFalse()
        // Ringing through a silenced ringer is doubly opt-in: it is off, and it only does anything
        // once the tone itself is switched on.
        assertThat(defaults.overrideSilent).isFalse()
    }

    @Test
    fun `when disabled nothing is ever deferred, however idle the phone looks`() {
        val prefs = UserPreferences(idleDeferralEnabled = false)

        // Screen off, not touched for a week.
        assertThat(
            prefs.shouldDeferReminder(
                screenInteractive = false,
                lastInteractionMillis = now - 7 * 24 * 60 * minute,
                nowMillis = now,
            )
        ).isFalse()

        // Screen on but idle for a day.
        assertThat(
            prefs.shouldDeferReminder(
                screenInteractive = true,
                lastInteractionMillis = now - 24 * 60 * minute,
                nowMillis = now,
            )
        ).isFalse()

        // No interaction ever recorded.
        assertThat(
            prefs.shouldDeferReminder(
                screenInteractive = false,
                lastInteractionMillis = null,
                nowMillis = now,
            )
        ).isFalse()
    }

    @Test
    fun `when enabled a dark screen defers immediately`() {
        val prefs = UserPreferences(idleDeferralEnabled = true)

        assertThat(
            prefs.shouldDeferReminder(
                screenInteractive = false,
                // Only a moment ago, so the idle threshold has not been reached.
                lastInteractionMillis = now - minute,
                nowMillis = now,
            )
        ).isTrue()
    }

    @Test
    fun `screen-off deferral can be switched off independently`() {
        val prefs = UserPreferences(idleDeferralEnabled = true, deferWhileScreenOff = false)

        // Screen off, but interacted a moment ago: not idle enough, so no deferral.
        assertThat(
            prefs.shouldDeferReminder(
                screenInteractive = false,
                lastInteractionMillis = now - minute,
                nowMillis = now,
            )
        ).isFalse()
    }

    @Test
    fun `a lit screen defers only once the idle threshold has passed`() {
        val prefs = UserPreferences(idleDeferralEnabled = true, idleThresholdMinutes = 30)

        assertThat(
            prefs.shouldDeferReminder(true, now - 29 * minute, now)
        ).isFalse()
        assertThat(
            prefs.shouldDeferReminder(true, now - 30 * minute, now)
        ).isTrue()
        assertThat(
            prefs.shouldDeferReminder(true, now - 31 * minute, now)
        ).isTrue()
    }

    @Test
    fun `the threshold is honoured as configured`() {
        val tenMinutes = UserPreferences(idleDeferralEnabled = true, idleThresholdMinutes = 10)

        assertThat(tenMinutes.shouldDeferReminder(true, now - 9 * minute, now)).isFalse()
        assertThat(tenMinutes.shouldDeferReminder(true, now - 10 * minute, now)).isTrue()
    }

    @Test
    fun `a nonsense threshold cannot cause an immediate deferral`() {
        val prefs = UserPreferences(idleDeferralEnabled = true, idleThresholdMinutes = 0)

        // Clamped to at least one minute, so "now" is not treated as idle.
        assertThat(prefs.shouldDeferReminder(true, now, now)).isFalse()
        assertThat(prefs.shouldDeferReminder(true, now - minute, now)).isTrue()
    }

    @Test
    fun `a lit screen with no recorded interaction is not treated as idle`() {
        // No history means "unknown", and guessing "idle" would withhold the very first reminder.
        val prefs = UserPreferences(idleDeferralEnabled = true)

        assertThat(prefs.shouldDeferReminder(screenInteractive = true, lastInteractionMillis = null, nowMillis = now))
            .isFalse()
    }
}
