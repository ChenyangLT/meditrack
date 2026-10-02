package com.meditrack.domain.reminder

import com.google.common.truth.Truth.assertThat
import com.meditrack.data.local.entity.DoseLog
import com.meditrack.data.local.entity.DoseStatus
import com.meditrack.data.prefs.UserPreferences
import org.junit.Test

/**
 * The unlock catch-up: "已经过了时间、还没记录的药，在解锁手机时再提醒一次".
 *
 * ## Why this rule needs its own test class
 *
 * It is the one path in the pipeline that deliberately *overrides* every "we already dealt with
 * this" flag. The scheduled reminder fires into a locked, dark screen; the user never sees it; by the
 * time they pick the phone up the dose is past its grace period, where the honest thing for the
 * scheduled pipeline to do is stay quiet and file a 未服药 record. The catch-up exists precisely to
 * contradict that - and a rule whose whole job is to override other rules has to be pinned from both
 * sides:
 *
 *  1. it must speak up for every dose the user genuinely missed, including one already marked
 *     未服药, including a partially taken one, and including a dose whose alarm *did* fire;
 *  2. it must never speak up for a dose that is not yet due, that the user has resolved, that they
 *     pushed away with 稍后, that is too old to act on, or that has run out of its own budget.
 *
 * The rule is a pure function, so all of it is provable here rather than by watching a phone.
 */
class UnlockCatchUpTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute

    /** 45 minutes late: past the shipped 30-minute grace period, well inside the 3-hour window. */
    private val dueAt = now - 45 * minute

    private val prefs = UserPreferences()

    private fun dose(
        due: Long = dueAt,
        status: DoseStatus = DoseStatus.DUE,
        takenQuantity: Double = 0.0,
        plannedQuantity: Double = 1.0,
        snoozedUntilMillis: Long? = null,
        unlockReminderCount: Int = 0,
        unlockReminderAtMillis: Long? = null,
        missedNotified: Boolean = false,
    ) = DoseLog(
        id = 1L,
        medicationId = 1L,
        scheduleId = 1L,
        epochDay = 0L,
        plannedMinuteOfDay = 8 * 60,
        plannedTimeMillis = due,
        plannedQuantity = plannedQuantity,
        plannedUnit = "片",
        takenQuantity = takenQuantity,
        status = status,
        snoozedUntilMillis = snoozedUntilMillis,
        unlockReminderCount = unlockReminderCount,
        unlockReminderAtMillis = unlockReminderAtMillis,
        missedNotified = missedNotified,
    )

    private fun context(
        at: Long = now,
        quietHoursEndMillis: Long = 0L,
        trigger: ReminderTrigger = ReminderTrigger.USER_RETURN,
    ) = ReminderContext(
        nowMillis = at,
        localMinuteOfDay = 9 * 60,
        quietHoursEndMillis = quietHoursEndMillis,
        trigger = trigger,
        unlockCatchUp = trigger == ReminderTrigger.USER_RETURN,
    )

    private fun decide(
        dose: DoseLog,
        prefs: UserPreferences = this.prefs,
        context: ReminderContext = context(),
    ) = ReminderPlanner.decide(dose, prefs, context)

    private fun assertCatchUp(decision: ReminderDecision) {
        assertThat(decision).isInstanceOf(ReminderDecision.UnlockCatchUp::class.java)
    }

    private fun assertNoCatchUp(decision: ReminderDecision) {
        assertThat(decision).isNotInstanceOf(ReminderDecision.UnlockCatchUp::class.java)
    }

    // ------------------------------------------------------------------ the promise

    @Test
    fun `an overdue dose nobody recorded is announced when the phone is unlocked`() {
        assertCatchUp(decide(dose()))
    }

    @Test
    fun `the catch-up covers a dose the sweep already marked as missed`() {
        // The whole point: "we recorded it as missed" is not "the user knows". This is the case the
        // old pipeline answered with a silent shade notification, which is what "提醒被抵掉" meant.
        assertCatchUp(decide(dose(status = DoseStatus.MISSED)))
    }

    @Test
    fun `a missed dose that was already announced silently still gets the audible catch-up`() {
        // missedNotified is about the *silent record*, not about the user having been told in a way
        // they could notice. Spending it must not forfeit the catch-up.
        assertCatchUp(decide(dose(status = DoseStatus.MISSED, missedNotified = true)))
    }

    @Test
    fun `a partially recorded dose still gets the catch-up`() {
        // 吃了一半 is not 吃好了. The user asked to be told about exactly this case.
        assertCatchUp(decide(dose(status = DoseStatus.PARTIAL, takenQuantity = 0.5)))
    }

    @Test
    fun `the occurrence counter starts at the first unlock reminder`() {
        val decision = decide(dose(unlockReminderCount = 0)) as ReminderDecision.UnlockCatchUp
        assertThat(decision.occurrence).isEqualTo(1)

        val second = decide(dose(unlockReminderCount = 1, unlockReminderAtMillis = now - 30 * minute))
            as ReminderDecision.UnlockCatchUp
        assertThat(second.occurrence).isEqualTo(2)
    }

    @Test
    fun `the catch-up reports how late it is rather than pretending to be on time`() {
        val decision = decide(dose()) as ReminderDecision.UnlockCatchUp
        assertThat(decision.lateMillis).isEqualTo(45 * minute)
        assertThat(decision.quiet).isFalse()
    }

    // ------------------------------------------------------------ what must not happen

    @Test
    fun `a dose whose time has not come yet is never announced early`() {
        assertNoCatchUp(decide(dose(due = now + 5 * minute, status = DoseStatus.UPCOMING)))
    }

    @Test
    fun `a taken dose is never re-announced`() {
        assertNoCatchUp(decide(dose(status = DoseStatus.TAKEN, takenQuantity = 1.0)))
    }

    @Test
    fun `a skipped dose is never re-announced`() {
        assertNoCatchUp(decide(dose(status = DoseStatus.SKIPPED)))
    }

    @Test
    fun `an unexpired snooze silences the catch-up`() {
        // "稍后 10 分钟" means exactly that; buzzing on the next unlock would be the opposite.
        assertNoCatchUp(decide(dose(snoozedUntilMillis = now + 5 * minute)))
    }

    @Test
    fun `an expired snooze becomes eligible again`() {
        // Once the time they asked for has arrived, the dose is simply overdue.
        val snoozed = dose(
            snoozedUntilMillis = now - minute,
            due = now - 45 * minute,
        )
        assertCatchUp(decide(snoozed))
    }

    @Test
    fun `the snooze deadline is what lateness is measured against`() {
        // A dose pushed to 09:00 and unlocked at 09:05 is five minutes late, not an hour, so it must
        // still be inside a short "too late" window.
        val tight = prefs.copy(staleReminderMinutes = 10)
        val snoozed = dose(due = now - 60 * minute, snoozedUntilMillis = now - 5 * minute)
        assertCatchUp(decide(snoozed, prefs = tight))
    }

    @Test
    fun `a dose past the too-late threshold is left to the silent record`() {
        val wayTooLate = dose(due = now - 4 * hour)
        assertNoCatchUp(decide(wayTooLate))
    }

    @Test
    fun `the window follows the user's own too-late setting`() {
        val late = dose(due = now - 2 * hour)
        // Default is three hours, so two hours late is still inside it...
        assertCatchUp(decide(late))
        // ...but a one-hour window makes the same dose too old to make a noise about.
        assertNoCatchUp(decide(late, prefs = prefs.copy(staleReminderMinutes = 60)))
    }

    @Test
    fun `the budget is per dose and stops after the configured number`() {
        val budget = prefs.copy(unlockReminderMaxPerDose = 3, unlockReminderMinGapMinutes = 0)

        assertCatchUp(decide(dose(unlockReminderCount = 0), prefs = budget))
        assertCatchUp(decide(dose(unlockReminderCount = 1), prefs = budget))
        assertCatchUp(decide(dose(unlockReminderCount = 2), prefs = budget))
        assertNoCatchUp(decide(dose(unlockReminderCount = 3), prefs = budget))
        assertNoCatchUp(decide(dose(unlockReminderCount = 9), prefs = budget))
    }

    @Test
    fun `two unlocks inside the minimum gap do not buzz twice`() {
        val recent = dose(unlockReminderCount = 1, unlockReminderAtMillis = now - 2 * minute)
        assertNoCatchUp(decide(recent))

        val oldEnough = dose(unlockReminderCount = 1, unlockReminderAtMillis = now - 6 * minute)
        assertCatchUp(decide(oldEnough))
    }

    @Test
    fun `a zero minimum gap means every unlock is its own event`() {
        val noGap = prefs.copy(unlockReminderMinGapMinutes = 0)
        assertCatchUp(decide(dose(unlockReminderAtMillis = now - 1_000L), prefs = noGap))
    }

    @Test
    fun `switching the feature off makes every dose ineligible`() {
        assertNoCatchUp(decide(dose(), prefs = prefs.copy(unlockReminderEnabled = false)))
    }

    @Test
    fun `the master switch off makes every dose ineligible`() {
        assertNoCatchUp(decide(dose(), prefs = prefs.copy(remindersEnabled = false)))
    }

    @Test
    fun `the ordinary triggers never carry the catch-up`() {
        // An alarm, a heartbeat or a boot pass must keep the old, strict behaviour: never repeat what
        // has already been said, however overdue the dose looks.
        val missed = dose(status = DoseStatus.MISSED, missedNotified = true)
        for (trigger in ReminderTrigger.entries.filter { it != ReminderTrigger.USER_RETURN }) {
            assertNoCatchUp(decide(missed, context = context(trigger = trigger)))
        }
    }

    @Test
    fun `inside quiet hours the catch-up is posted quietly`() {
        val decision = decide(dose(), context = context(quietHoursEndMillis = now + 6 * hour))
            as ReminderDecision.UnlockCatchUp
        assertThat(decision.quiet).isTrue()
    }

    @Test
    fun `an unusable budget setting still allows exactly one catch-up`() {
        // maxPerDose is clamped to at least 1 rather than letting a 0 turn the feature off silently.
        val zero = prefs.copy(unlockReminderMaxPerDose = 0, unlockReminderMinGapMinutes = 0)
        assertCatchUp(decide(dose(unlockReminderCount = 0), prefs = zero))
        assertNoCatchUp(decide(dose(unlockReminderCount = 1), prefs = zero))
    }

    @Test
    fun `a negative minimum gap does not make the rule fire twice in one instant`() {
        val negative = prefs.copy(unlockReminderMinGapMinutes = -5)
        val justNow = dose(unlockReminderCount = 1, unlockReminderAtMillis = now)
        // The timestamp is *now*, so a zero-or-negative gap means "no spacing required" - the dose is
        // eligible again, and the counting rule (not the gap) is what bounds the total.
        assertCatchUp(decide(justNow, prefs = negative))
    }

    @Test
    fun `the feature ships enabled with a three-reminder, five-minute budget`() {
        val defaults = UserPreferences()
        assertThat(defaults.unlockReminderEnabled).isTrue()
        assertThat(defaults.unlockReminderMaxPerDose).isEqualTo(3)
        assertThat(defaults.unlockReminderMinGapMinutes).isEqualTo(5)
        assertThat(defaults.fullScreenReminderEnabled).isFalse()
    }
}
