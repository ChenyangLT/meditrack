package com.meditrack.domain.reminder

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The edge rule behind the guard service's unlock poller.
 *
 * The poller exists because `ACTION_USER_PRESENT` is an implicit broadcast that an OEM ROM may simply
 * withhold from a background app - measured on the target device, where the process was alive with the
 * guard in the foreground and the broadcast never arrived.
 *
 * A poller that acted on *state* rather than on an *edge* would be a disaster: it would run a
 * reconciliation pass every few seconds for as long as the user held their phone, and every pass writes
 * audit rows. So the rule is evaluated here, on the JVM, exactly as the coroutine loop evaluates it.
 */
class PresenceEdgeTest {

    /** A realistic wall-clock instant; the poller's minimum-gap check is absolute, not relative. */
    private val base = 1_700_000_000_000L

    /** Mirrors ReminderGuardService's presence loop, one tick at a time. */
    private class Poller {
        var wasPresent = false
        var lastLookAt = 0L
        var lastPassAt = 0L

        fun tick(now: Long, present: Boolean, memoryMillis: Long = 30_000L, minGapMillis: Long = 60_000L): Boolean {
            if (now - lastLookAt > memoryMillis) wasPresent = false
            lastLookAt = now
            val fires = present && !wasPresent && now - lastPassAt > minGapMillis
            if (fires) lastPassAt = now
            wasPresent = present
            return fires
        }
    }

    @Test
    fun `unlocking fires exactly once, however long the phone is then held`() {
        val poller = Poller()
        assertThat(poller.tick(base, present = false)).isFalse()
        assertThat(poller.tick(base + 5_000, present = true)).isTrue()   // the unlock edge
        // Ten more ticks with the phone still unlocked and in use: not one extra pass.
        var extra = 0
        for (t in 1..10) if (poller.tick(base + 5_000L + t * 5_000, present = true)) extra++
        assertThat(extra).isEqualTo(0)
    }

    @Test
    fun `a locked screen is never a presence edge`() {
        val poller = Poller()
        for (t in 1..10) assertThat(poller.tick(base + t * 5_000L, present = false)).isFalse()
    }

    @Test
    fun `lock then unlock again is a new edge`() {
        val poller = Poller()
        assertThat(poller.tick(base, present = false)).isFalse()
        assertThat(poller.tick(base + 5_000, present = true)).isTrue()
        assertThat(poller.tick(base + 10_000, present = false)).isFalse()      // locked again
        assertThat(poller.tick(base + 120_000, present = true)).isTrue()       // unlocked again, past the gap
    }

    @Test
    fun `a device that slept remembers nothing, so the next unlock still fires`() {
        val poller = Poller()
        assertThat(poller.tick(base, present = false)).isFalse()
        assertThat(poller.tick(base + 5_000, present = true)).isTrue()
        // The phone sleeps for an hour: a coroutine `delay` does not run while the device is
        // suspended, so the poller's previous observation is stale and must not mask the next unlock.
        assertThat(poller.tick(base + 5_000 + 3_600_000, present = true)).isTrue()
    }

    @Test
    fun `the poller never runs a second pass within the minimum gap`() {
        val poller = Poller()
        assertThat(poller.tick(base, present = false)).isFalse()
        assertThat(poller.tick(base + 1_000, present = true)).isTrue()
        // The broadcast path may already have fired; a quick lock/unlock pair must not double up.
        assertThat(poller.tick(base + 2_000, present = false)).isFalse()
        assertThat(poller.tick(base + 3_000, present = true)).isFalse()
    }
}
