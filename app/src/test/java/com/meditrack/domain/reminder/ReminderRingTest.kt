package com.meditrack.domain.reminder

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The «持续响铃» rules.
 *
 * These tests exist because the arithmetic here is what stands between "the reminder cannot be slept
 * through" and "the app rang all night and got uninstalled". Every clamp below is a decision a user
 * would eventually hit, so each one is pinned rather than left to a device experiment.
 */
class ReminderRingTest {

    // ------------------------------------------------------------------ policy

    @Test
    fun `once is exactly one chime and never a session`() {
        val policy = RingPolicy.of(ReminderRingMode.ONCE, chimeCount = 7, intervalSeconds = 20, maxMinutes = 5)

        assertThat(policy.chimeCount).isEqualTo(1)
        assertThat(policy.isSingleChime).isTrue()
        // No cap is needed: there is nothing to cap.
        assertThat(policy.maxDurationMillis).isEqualTo(0L)
    }

    @Test
    fun `until action is unbounded by count but capped by minutes`() {
        val policy = RingPolicy.of(
            ReminderRingMode.UNTIL_ACTION,
            chimeCount = 0,
            intervalSeconds = 20,
            maxMinutes = 5,
        )

        assertThat(policy.chimeCount).isEqualTo(0)
        assertThat(policy.isSingleChime).isFalse()
        assertThat(policy.maxDurationMillis).isEqualTo(5 * 60_000L)
        assertThat(policy.isBoundedByCount).isFalse()
    }

    @Test
    fun `fixed times keeps the requested count`() {
        val policy = RingPolicy.of(
            ReminderRingMode.FIXED_TIMES,
            chimeCount = 3,
            intervalSeconds = 30,
            maxMinutes = 10,
        )

        assertThat(policy.chimeCount).isEqualTo(3)
        assertThat(policy.intervalMillis).isEqualTo(30_000L)
        assertThat(policy.isBoundedByCount).isTrue()
    }

    @Test
    fun `a zero minute cap is raised to one minute rather than meaning forever`() {
        // 0 would be "ring until the battery dies", which is not a behaviour a medication app may offer.
        val policy = RingPolicy.of(ReminderRingMode.UNTIL_ACTION, 0, 20, maxMinutes = 0)

        assertThat(policy.maxDurationMillis).isEqualTo(60_000L)
    }

    @Test
    fun `a negative or zero interval cannot produce a tight loop`() {
        val zero = RingPolicy.of(ReminderRingMode.UNTIL_ACTION, 0, intervalSeconds = 0, maxMinutes = 5)
        val negative = RingPolicy.of(ReminderRingMode.UNTIL_ACTION, 0, intervalSeconds = -5, maxMinutes = 5)

        assertThat(zero.intervalMillis).isEqualTo(1_000L)
        assertThat(negative.intervalMillis).isEqualTo(1_000L)
    }

    @Test
    fun `an absurd interval is clamped to the ceiling`() {
        val policy = RingPolicy.of(
            ReminderRingMode.FIXED_TIMES,
            chimeCount = 2,
            intervalSeconds = 100_000,
            maxMinutes = 10,
        )

        assertThat(policy.intervalMillis)
            .isEqualTo(RingPolicy.MAX_INTERVAL_SECONDS * 1_000L)
    }

    @Test
    fun `fixed times cannot stretch past its own minute cap`() {
        // 60 chimes every 5 minutes is five hours. The cap is what stops "响 3 次" becoming an alarm clock.
        val policy = RingPolicy.of(
            ReminderRingMode.FIXED_TIMES,
            chimeCount = 60,
            intervalSeconds = 300,
            maxMinutes = 5,
        )

        assertThat(policy.maxDurationMillis).isEqualTo(5 * 60_000L)
    }

    @Test
    fun `fixed times spans exactly its own chimes when that is shorter than the cap`() {
        // 3 chimes at 30 s = 3 * 30 s of window, which is well inside a 10-minute cap.
        val policy = RingPolicy.of(ReminderRingMode.FIXED_TIMES, 3, 30, maxMinutes = 10)

        assertThat(policy.maxDurationMillis).isEqualTo(90_000L)
    }

    @Test
    fun `an oversized chime count is clamped`() {
        val policy = RingPolicy.of(ReminderRingMode.FIXED_TIMES, 9_999, 5, maxMinutes = 30)

        assertThat(policy.chimeCount).isEqualTo(RingPolicy.MAX_CHIMES)
    }

    // ----------------------------------------------------------------- session

    @Test
    fun `fixed times stops after exactly its chime count`() {
        val session = RingSession(
            doseId = 1L,
            startedAtMillis = 0L,
            policy = RingPolicy.of(ReminderRingMode.FIXED_TIMES, 3, 20, maxMinutes = 10),
        )

        assertThat(session.shouldChime(completedChimes = 0)).isTrue()
        assertThat(session.shouldChime(completedChimes = 2)).isTrue()
        // The third chime has played; there is no fourth.
        assertThat(session.shouldChime(completedChimes = 3)).isFalse()
    }

    @Test
    fun `until action keeps chiming for as long as it is asked`() {
        val session = RingSession(
            doseId = 1L,
            startedAtMillis = 0L,
            policy = RingPolicy.of(ReminderRingMode.UNTIL_ACTION, 0, 20, maxMinutes = 5),
        )

        assertThat(session.shouldChime(completedChimes = 0)).isTrue()
        assertThat(session.shouldChime(completedChimes = 1_000)).isTrue()
    }

    @Test
    fun `once stops after the first chime`() {
        val session = RingSession(
            doseId = 1L,
            startedAtMillis = 0L,
            policy = RingPolicy.of(ReminderRingMode.ONCE, 1, 20, maxMinutes = 5),
        )

        assertThat(session.shouldChime(completedChimes = 0)).isTrue()
        assertThat(session.shouldChime(completedChimes = 1)).isFalse()
    }

    @Test
    fun `a session expires at its cap and not before`() {
        val session = RingSession(
            doseId = 1L,
            startedAtMillis = 1_000L,
            policy = RingPolicy.of(ReminderRingMode.UNTIL_ACTION, 0, 20, maxMinutes = 5),
        )

        assertThat(session.isExpired(1_000L)).isFalse()
        assertThat(session.isExpired(1_000L + 299_999L)).isFalse()
        assertThat(session.isExpired(1_000L + 300_000L)).isTrue()
        assertThat(session.isExpired(1_000L + 900_000L)).isTrue()
    }

    @Test
    fun `an uncapped session never expires`() {
        val session = RingSession(
            doseId = 1L,
            startedAtMillis = 0L,
            policy = RingPolicy(mode = ReminderRingMode.FIXED_TIMES, chimeCount = 1, intervalMillis = 0L, maxDurationMillis = 0L),
        )

        assertThat(session.isExpired(Long.MAX_VALUE / 2)).isFalse()
        assertThat(session.remainingMillis(12_345L)).isEqualTo(0L)
    }

    @Test
    fun `the remaining time counts down and floors at zero`() {
        val session = RingSession(
            doseId = 1L,
            startedAtMillis = 0L,
            policy = RingPolicy.of(ReminderRingMode.UNTIL_ACTION, 0, 20, maxMinutes = 5),
        )

        assertThat(session.remainingMillis(0L)).isEqualTo(300_000L)
        assertThat(session.remainingMillis(120_000L)).isEqualTo(180_000L)
        assertThat(session.remainingMillis(400_000L)).isEqualTo(0L)
    }

    @Test
    fun `the countdown label is human for the continuous mode`() {
        val session = RingSession(
            doseId = 1L,
            startedAtMillis = 0L,
            policy = RingPolicy.of(ReminderRingMode.UNTIL_ACTION, 0, 20, maxMinutes = 5),
        )

        assertThat(session.countdownLabel(nowMillis = 0L)).isEqualTo("将再响 5 分 00 秒")
        // Below a minute the label drops the minutes field rather than reading out "0 分 30 秒".
        assertThat(session.countdownLabel(nowMillis = 4 * 60_000L + 30_000L))
            .isEqualTo("将再响 30 秒")
        assertThat(session.countdownLabel(nowMillis = 5 * 60_000L + 10_000L))
            .isEqualTo("将再响 0 秒")
    }

    @Test
    fun `the countdown label counts chimes for the fixed mode`() {
        val session = RingSession(
            doseId = 1L,
            startedAtMillis = 0L,
            policy = RingPolicy.of(ReminderRingMode.FIXED_TIMES, 3, 20, maxMinutes = 10),
            completedChimes = 1,
        )

        assertThat(session.countdownLabel(nowMillis = 0L)).isEqualTo("第 2 / 3 次")
    }

    @Test
    fun `the countdown label never claims a chime beyond the last one`() {
        val session = RingSession(
            doseId = 1L,
            startedAtMillis = 0L,
            policy = RingPolicy.of(ReminderRingMode.FIXED_TIMES, 3, 20, maxMinutes = 10),
            completedChimes = 3,
        )

        assertThat(session.countdownLabel(nowMillis = 0L)).isEqualTo("第 3 / 3 次")
    }

    @Test
    fun `once has no countdown to show`() {
        val session = RingSession(
            doseId = null,
            startedAtMillis = 0L,
            policy = RingPolicy.of(ReminderRingMode.ONCE, 1, 20, maxMinutes = 5),
        )

        assertThat(session.countdownLabel(nowMillis = 0L)).isEmpty()
    }

    // -------------------------------------------------------------------- enum

    @Test
    fun `an unknown stored mode falls back to the shipped default`() {
        assertThat(ReminderRingMode.fromName("NOT_A_MODE")).isEqualTo(ReminderRingMode.DEFAULT)
        assertThat(ReminderRingMode.fromName(null)).isEqualTo(ReminderRingMode.DEFAULT)
        // The default has to be the loud one: a silent default would make the feature absent for a user
        // who never opens settings, which is the exact audience it exists for.
        assertThat(ReminderRingMode.DEFAULT).isEqualTo(ReminderRingMode.UNTIL_ACTION)
    }

    @Test
    fun `a stored mode round trips`() {
        for (mode in ReminderRingMode.entries) {
            assertThat(ReminderRingMode.fromName(mode.name)).isEqualTo(mode)
        }
    }
}
