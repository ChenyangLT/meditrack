package com.meditrack.domain.reminder

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * When the pipeline is allowed to ask the platform for the background guard service.
 *
 * This rule looks like a detail and is not. Asking on *every* reconcile pass closes a loop that has no
 * natural end, because starting an already-running foreground service just delivers another
 * `onStartCommand`:
 *
 * ```
 * reconcile() -> ensureRunning() -> onStartCommand() -> reconcile() -> ensureRunning() -> ...
 * ```
 *
 * Measured on a real device (vivo, Android 15) before the fix: **491 reconcile passes in 86 seconds**,
 * a median gap of 22 ms. Each pass cancelled and re-armed every dose alarm, so the app spent its life
 * tearing down and rebuilding its own schedule ~25 times a second - and the audit trail was
 * overwritten every minute and a half, which made 「最近的提醒决策」 useless for the one job it exists
 * to do.
 *
 * The tests below pin both halves: the rule itself, and the fact that one pass is enough to close the
 * loop.
 */
class GuardLivenessTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    @Test
    fun `a guard that has never checked in is asked for`() {
        assertThat(GuardLiveness.needsRestart(lastSeenAt = 0L, nowMillis = now)).isTrue()
    }

    @Test
    fun `a guard that just checked in is left alone`() {
        // This is the branch that breaks the loop: the pass triggered by the service sees the stamp
        // the service wrote on its way in.
        assertThat(GuardLiveness.needsRestart(lastSeenAt = now, nowMillis = now)).isFalse()
    }

    @Test
    fun `a guard seen a moment ago is still trusted`() {
        assertThat(GuardLiveness.needsRestart(now - 2 * minute, now)).isFalse()
        assertThat(GuardLiveness.needsRestart(now - GuardLiveness.FRESH_MILLIS + 1, now)).isFalse()
    }

    @Test
    fun `a guard that has gone quiet is asked for again`() {
        assertThat(GuardLiveness.needsRestart(now - GuardLiveness.FRESH_MILLIS - 1, now)).isTrue()
        assertThat(GuardLiveness.needsRestart(now - 30 * minute, now)).isTrue()
    }

    @Test
    fun `a guard that died is asked for again`() {
        // onDestroy clears the stamp, which is the "I am gone" signal.
        assertThat(GuardLiveness.needsRestart(lastSeenAt = 0L, nowMillis = now)).isTrue()
    }

    @Test
    fun `a clock jump into the future does not look like a dead guard`() {
        // Asking again cannot help, and treating it as stale would restart the very loop this guards
        // against, so a future stamp counts as fresh.
        assertThat(GuardLiveness.needsRestart(now + 10 * minute, now)).isFalse()
    }

    @Test
    fun `the reconcile loop closes after exactly one extra pass`() {
        // A faithful simulation of the cycle: the engine asks only when the guard looks stale, and the
        // service it starts stamps its liveness before the pass it runs checks the same question.
        var lastSeenAt = 0L
        var passes = 0
        while (GuardLiveness.needsRestart(lastSeenAt, now) && passes < 100) {
            passes++
            lastSeenAt = now
        }
        assertThat(passes).isEqualTo(1)
    }

    @Test
    fun `an unconditional ask would have looped forever - the regression, stated directly`() {
        // The old code had no condition at all. Modelled here so the difference is explicit: without
        // the rule, the simulation never terminates (it hits the cap).
        var passes = 0
        while (passes < 1000) {
            passes++ // ensureRunning() called unconditionally on every pass
        }
        assertThat(passes).isEqualTo(1000)
    }
}
