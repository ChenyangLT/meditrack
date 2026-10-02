package com.meditrack.data.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The two decisions that would fail silently: not checking often enough (users stop hearing about
 * releases) and checking at the wrong moment (a prompt every single launch).
 */
class UpdatePolicyTest {

    private val twelveHours = UpdatePolicy.MIN_INTERVAL_MILLIS
    private val now = 1_800_000_000_000L

    @Test
    fun `the first ever check always goes out`() {
        assertThat(
            UpdatePolicy.shouldCheck(autoCheckEnabled = true, force = false, lastCheckAtMillis = 0L, nowMillis = now),
        ).isTrue()
    }

    @Test
    fun `a check inside the interval is skipped, and one after it is allowed`() {
        assertThat(
            UpdatePolicy.shouldCheck(true, false, now - twelveHours + 1, now),
        ).isFalse()
        assertThat(
            UpdatePolicy.shouldCheck(true, false, now - twelveHours, now),
        ).isTrue()
        assertThat(
            UpdatePolicy.shouldCheck(true, false, now - twelveHours - 1, now),
        ).isTrue()
    }

    @Test
    fun `turning the switch off stops automatic checks but not a manual one`() {
        // The stored timestamp is ancient here, so only the switch can be deciding.
        assertThat(UpdatePolicy.shouldCheck(false, false, 0L, now)).isFalse()
        assertThat(UpdatePolicy.shouldCheck(false, true, 0L, now)).isTrue()
    }

    @Test
    fun `a manual check ignores the interval as well`() {
        assertThat(UpdatePolicy.shouldCheck(true, true, now - 1_000L, now)).isTrue()
    }

    @Test
    fun `a clock that jumped backwards skips the check instead of hammering GitHub`() {
        // Time moved back a day: "elapsed" is negative, which must not read as "long enough".
        assertThat(UpdatePolicy.shouldCheck(true, false, now + 24 * 60 * 60 * 1000L, now)).isFalse()
    }

    @Test
    fun `a dismissed version stays quiet until something newer appears`() {
        assertThat(UpdatePolicy.shouldInterrupt(false, "v1.8.0", "v1.8.0")).isFalse()
        assertThat(UpdatePolicy.shouldInterrupt(false, "v1.8.0", "v1.9.0")).isTrue()
        assertThat(UpdatePolicy.shouldInterrupt(false, null, "v1.8.0")).isTrue()
    }

    @Test
    fun `an explicit check shows even a version the user dismissed`() {
        assertThat(UpdatePolicy.shouldInterrupt(true, "v1.8.0", "v1.8.0")).isTrue()
    }
}
