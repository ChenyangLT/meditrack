package com.meditrack.domain.reminder

import com.google.common.truth.Truth.assertThat
import com.meditrack.data.local.entity.DoseLog
import com.meditrack.data.local.entity.DoseStatus
import com.meditrack.data.prefs.UserPreferences
import org.junit.Test

/**
 * "这段时间内不要提醒我".
 *
 * Two questions get answered separately here, and keeping them apart is the whole point:
 *
 *  1. **May this make a sound?** - decided by the window alone. A do-not-disturb window that can ring
 *     is not a do-not-disturb window.
 *  2. **If not, is the reminder held until the window ends or posted silently right away?** - the
 *     user's choice, and either answer is fine as long as nothing buzzes.
 *
 * The implementation used to conflate them: "not deferring" was read as "not quiet", so switching
 * 顺延 off made the app ring at 3am. These tests pin the correct reading.
 */
class QuietHoursTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    /** 22:00 → 07:00, the shipped shape, and the one that wraps past midnight. */
    private val quiet = UserPreferences(
        quietHoursEnabled = true,
        quietHoursStartMinute = 22 * 60,
        quietHoursEndMinute = 7 * 60,
    )

    private fun dose(
        dueAtMillis: Long = now - 5 * minute,
        notifiedTimeMillis: Long? = null,
        escalationCount: Int = 0,
        status: DoseStatus = DoseStatus.DUE,
    ) = DoseLog(
        id = 1L, medicationId = 1L, scheduleId = 1L, epochDay = 0L, plannedMinuteOfDay = 8 * 60,
        plannedTimeMillis = dueAtMillis, plannedQuantity = 1.0, plannedUnit = "片",
        takenQuantity = 0.0, status = status, notifiedTimeMillis = notifiedTimeMillis,
        escalationCount = escalationCount,
    )

    /** 03:00, comfortably inside 22:00-07:00. */
    private fun insideWindow(
        prefs: UserPreferences,
        at: Long = now,
        trigger: ReminderTrigger = ReminderTrigger.HEARTBEAT,
        deferUntil: Long = at + 4 * 60 * minute,
    ) = ReminderContext(
        nowMillis = at,
        localMinuteOfDay = 3 * 60,
        quietHoursEndMillis = if (prefs.quietHoursEnabled && prefs.quietHoursDeferEnabled) deferUntil else 0L,
        inQuietHours = prefs.isWithinQuietHours(3 * 60),
        trigger = trigger,
        unlockCatchUp = trigger == ReminderTrigger.USER_RETURN,
    )

    private fun outsideWindow(prefs: UserPreferences, at: Long = now) = ReminderContext(
        nowMillis = at,
        localMinuteOfDay = 12 * 60,
        quietHoursEndMillis = 0L,
        inQuietHours = prefs.isWithinQuietHours(12 * 60),
        trigger = ReminderTrigger.HEARTBEAT,
    )

    private fun decide(dose: DoseLog, prefs: UserPreferences, context: ReminderContext) =
        ReminderPlanner.decide(dose, prefs, context)

    // ------------------------------------------------------------ the window itself

    @Test
    fun `the window wraps past midnight, and only covers what it should`() {
        assertThat(quiet.isWithinQuietHours(23 * 60 + 30)).isTrue()
        assertThat(quiet.isWithinQuietHours(3 * 60)).isTrue()
        assertThat(quiet.isWithinQuietHours(6 * 60 + 59)).isTrue()
        assertThat(quiet.isWithinQuietHours(7 * 60)).isFalse()
        assertThat(quiet.isWithinQuietHours(12 * 60)).isFalse()
        assertThat(quiet.isWithinQuietHours(21 * 60 + 59)).isFalse()
    }

    @Test
    fun `a disabled window covers nothing`() {
        val off = quiet.copy(quietHoursEnabled = false)
        for (minuteOfDay in listOf(0, 3 * 60, 12 * 60, 23 * 60)) {
            assertThat(off.isWithinQuietHours(minuteOfDay)).isFalse()
        }
    }

    @Test
    fun `identical start and end silence nothing`() {
        // Fail-safe: an empty window is treated as "no window" rather than as "all day", because the
        // dangerous mistake for this feature is silencing reminders the user wanted to receive.
        val degenerate = quiet.copy(quietHoursStartMinute = 8 * 60, quietHoursEndMinute = 8 * 60)
        for (minuteOfDay in listOf(0, 8 * 60, 12 * 60, 23 * 60)) {
            assertThat(degenerate.isWithinQuietHours(minuteOfDay)).isFalse()
        }
    }

    // ------------------------------------------------- nothing may make a sound

    @Test
    fun `inside the window a first reminder is silent, whichever delivery mode is chosen`() {
        // Held for later (the default).
        val held = decide(dose(), quiet, insideWindow(quiet))
        assertThat(held).isInstanceOf(ReminderDecision.Deferred::class.java)
        with(held as ReminderDecision.Deferred) {
            assertThat(reason).isEqualTo(DeferReason.QUIET_HOURS)
            assertThat(untilMillis).isGreaterThan(now)
        }

        // Posted silently instead. This is the case that used to ring: turning 顺延 off must not turn
        // the do-not-disturb window off.
        val silent = decide(dose(), quiet.copy(quietHoursDeferEnabled = false), insideWindow(quiet, deferUntil = 0L))
        assertThat(silent).isInstanceOf(ReminderDecision.Remind::class.java)
        assertThat((silent as ReminderDecision.Remind).quiet).isTrue()
    }

    @Test
    fun `a repeat inside the window is posted silently rather than held`() {
        val repeat = decide(
            dose(notifiedTimeMillis = now - 20 * minute, escalationCount = 1),
            quiet,
            insideWindow(quiet),
        )
        assertThat(repeat).isInstanceOf(ReminderDecision.Remind::class.java)
        assertThat((repeat as ReminderDecision.Remind).quiet).isTrue()
    }

    @Test
    fun `the heads-up inside the window is held, or posted silently when deferral is off`() {
        val upcoming = dose(dueAtMillis = now + 10 * minute, status = DoseStatus.UPCOMING)

        val held = decide(upcoming, quiet, insideWindow(quiet))
        assertThat(held).isInstanceOf(ReminderDecision.Deferred::class.java)

        val silent = decide(
            upcoming,
            quiet.copy(quietHoursDeferEnabled = false),
            insideWindow(quiet, deferUntil = 0L),
        )
        assertThat(silent).isInstanceOf(ReminderDecision.PreRemind::class.java)
        assertThat((silent as ReminderDecision.PreRemind).quiet).isTrue()
    }

    @Test
    fun `outside the window reminders are audible as usual`() {
        val normal = decide(dose(), quiet, outsideWindow(quiet))
        assertThat(normal).isInstanceOf(ReminderDecision.Remind::class.java)
        assertThat((normal as ReminderDecision.Remind).quiet).isFalse()
    }

    @Test
    fun `an overdue dose picked up at 3am is announced silently`() {
        // The unlock catch-up is the one path that deliberately re-announces a dose. Picking the phone
        // up in the middle of the night must not buzz - and it must stay quiet even when the user
        // chose "post silently" instead of "hold", which is exactly where the old code rang.
        for (defer in listOf(true, false)) {
            val decision = decide(
                dose(),
                quiet.copy(quietHoursDeferEnabled = defer),
                insideWindow(quiet, trigger = ReminderTrigger.USER_RETURN),
            )
            assertThat(decision).isInstanceOf(ReminderDecision.UnlockCatchUp::class.java)
            assertThat((decision as ReminderDecision.UnlockCatchUp).quiet).isTrue()
        }
    }

    @Test
    fun `an overdue dose picked up during the day is audible`() {
        val decision = decide(
            dose(),
            quiet,
            ReminderContext(
                nowMillis = now,
                localMinuteOfDay = 12 * 60,
                inQuietHours = false,
                trigger = ReminderTrigger.USER_RETURN,
                unlockCatchUp = true,
            ),
        )
        assertThat((decision as ReminderDecision.UnlockCatchUp).quiet).isFalse()
    }

    @Test
    fun `the shipped default is off, over the night most people sleep`() {
        val defaults = UserPreferences()
        assertThat(defaults.quietHoursEnabled).isFalse()
        assertThat(defaults.quietHoursStartMinute).isEqualTo(22 * 60)
        assertThat(defaults.quietHoursEndMinute).isEqualTo(7 * 60)
        assertThat(defaults.quietHoursDeferEnabled).isTrue()
    }
}
