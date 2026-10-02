package com.meditrack.domain.reminder

import com.google.common.truth.Truth.assertThat
import com.meditrack.data.prefs.UserPreferences
import org.junit.Test

/**
 * The two rules behind "为什么每次都要我点开软件才提醒".
 *
 * That symptom has exactly two possible causes and they need opposite fixes:
 *
 *  1. **the app never scheduled the alarm** - a bug in this pipeline;
 *  2. **the operating system threw the alarm away** - the app was cleared from the background, and
 *     no amount of extra scheduling helps; only the guard service and an autostart allowance do.
 *
 * From inside the app both look the same unless the pipeline *remembers what it armed*. These tests
 * cover the rule that draws the distinction, and the storage format that carries the memory.
 */
class LostAlarmDetectionTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L
    private val tolerance = ReminderPlanner.LOST_ALARM_TOLERANCE_MILLIS

    // ----------------------------------------------------------- the detection rule

    @Test
    fun `an alarm that was armed and never delivered is reported as lost`() {
        val armedFor = now - 30 * minute

        assertThat(
            ReminderPlanner.alarmWasLost(
                expectedAtMillis = armedFor,
                notifiedTimeMillis = null,
                nowMillis = now,
            )
        ).isTrue()
    }

    @Test
    fun `a dose we never armed is not a lost alarm, just an unscheduled one`() {
        // No expectation means the previous pass did not arm this dose. That is a different - and
        // entirely internal - failure, so it must not be blamed on the operating system.
        assertThat(
            ReminderPlanner.alarmWasLost(
                expectedAtMillis = null,
                notifiedTimeMillis = null,
                nowMillis = now,
            )
        ).isFalse()
    }

    @Test
    fun `a dose that was delivered late is not a lost alarm`() {
        // The alarm may well have been dropped and the heartbeat caught it - but the user *was*
        // reminded, so accusing the system would be noise in the one signal that matters.
        assertThat(
            ReminderPlanner.alarmWasLost(
                expectedAtMillis = now - 30 * minute,
                notifiedTimeMillis = now - 20 * minute,
                nowMillis = now,
            )
        ).isFalse()
    }

    @Test
    fun `ordinary jitter is not mistaken for a lost alarm`() {
        // A second late, a minute late, five minutes late... all normal for while-idle alarms.
        for (lateBy in listOf(0L, 1_000L, 30_000L, 2 * minute, 4 * minute)) {
            assertThat(
                ReminderPlanner.alarmWasLost(now - lateBy, null, now)
            ).isFalse()
        }
    }

    @Test
    fun `the tolerance boundary is exact`() {
        assertThat(ReminderPlanner.alarmWasLost(now - tolerance, null, now)).isFalse()
        assertThat(ReminderPlanner.alarmWasLost(now - tolerance - 1, null, now)).isTrue()
    }

    @Test
    fun `an alarm armed for the future is not lost yet`() {
        // A forward-dated expectation simply has not come due.
        assertThat(ReminderPlanner.alarmWasLost(now + 10 * minute, null, now)).isFalse()
    }

    @Test
    fun `a very long outage is still reported, however large the gap`() {
        // The phone was off overnight; when it comes back the alarms that never fired are exactly
        // what the user needs to be told about.
        assertThat(
            ReminderPlanner.alarmWasLost(
                expectedAtMillis = now - 14 * 60 * minute,
                notifiedTimeMillis = null,
                nowMillis = now,
            )
        ).isTrue()
    }

    // ------------------------------------------------------------ the memory format

    @Test
    fun `expectations survive a round trip`() {
        val expectations = mapOf(1L to 1_700_000_000_000L, 42L to 1_700_000_600_000L)

        val decoded = ArmedExpectations.decode(ArmedExpectations.encode(expectations))

        assertThat(decoded).isEqualTo(expectations)
    }

    @Test
    fun `an empty set decodes to an empty map`() {
        assertThat(ArmedExpectations.decode(emptySet())).isEmpty()
        assertThat(ArmedExpectations.encode(emptyMap())).isEmpty()
    }

    @Test
    fun `malformed entries are dropped rather than thrown`() {
        // This runs inside a broadcast receiver; an exception here would cost the user a reminder,
        // and a lost expectation only costs a diagnostic.
        val decoded = ArmedExpectations.decode(
            setOf(
                "7:1700000000000",   // valid
                "no-separator",      // wrong format
                ":1700000000000",    // empty id
                "7:",                // empty instant
                "abc:123",           // non-numeric id
                "7:not-a-number",    // non-numeric instant
                "",                  // empty string
            )
        )

        assertThat(decoded).containsExactly(7L, 1_700_000_000_000L)
    }

    @Test
    fun `only the last entry for a dose survives, since the key is the dose`() {
        val decoded = ArmedExpectations.decode(setOf("7:100", "7:200"))

        assertThat(decoded).containsExactly(7L, 200L)
    }

    @Test
    fun `a dose id containing no separator cannot shadow a real one`() {
        val decoded = ArmedExpectations.decode(setOf("12345", "9:1700000000000"))

        assertThat(decoded).containsExactly(9L, 1_700_000_000_000L)
    }

    // ------------------------------------------------------- the shipped defaults

    /**
     * The settings that decide whether reminders survive the app being cleared.
     *
     * Pinned deliberately: the whole complaint is "it only tells me when I open it", and the two
     * options below are the only ones that change that outcome. If either is ever flipped back to a
     * cautious default, this test fails and forces the trade-off to be reconsidered rather than
     * silently regressing.
     */
    @Test
    fun `the reliability options default to the ones that actually work in the background`() {
        val defaults = UserPreferences()

        assertThat(defaults.guardServiceEnabled).isTrue()
        assertThat(defaults.alarmClockAlarms).isTrue()
        // And the multi-path redundancy those defaults complement.
        assertThat(defaults.reliabilityWorkerEnabled).isTrue()
        assertThat(defaults.heartbeatMinutes).isAtMost(30)
    }
}
