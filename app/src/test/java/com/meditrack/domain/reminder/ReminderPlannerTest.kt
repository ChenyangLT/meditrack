package com.meditrack.domain.reminder

import com.google.common.truth.Truth.assertThat
import com.meditrack.data.local.entity.DoseLog
import com.meditrack.data.local.entity.DoseStatus
import com.meditrack.data.prefs.UserPreferences
import org.junit.Test

/**
 * The reminder policy, exercised as a pure function.
 *
 * These tests are the actual safety net for the rewrite. Each one pins a way the previous pipeline
 * could silently lose a reminder, because "the notification did not arrive" is not a bug report
 * anyone can act on - it is a family of them, and each has a different fix.
 *
 * The cases are grouped by the guarantee they defend:
 *
 *  - **Never early** - a trigger ahead of its own schedule is a clock error, not a reminder.
 *  - **Never stale** - a very late trigger becomes a silent prompt, never a banner.
 *  - **Bounded** - the escalation budget is a stored counter, not a function of elapsed time.
 *  - **Idempotent** - the same instant always produces the same decision.
 *  - **Quiet-aware** - quiet hours defer rather than mute into the void.
 */
class ReminderPlannerTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute

    /** 08:00 local, which is what the band labels in these tests assume. */
    private val eightAm = now

    private val prefs = UserPreferences()

    private fun dose(
        dueAtMillis: Long = eightAm,
        status: DoseStatus = DoseStatus.DUE,
        takenQuantity: Double = 0.0,
        snoozedUntilMillis: Long? = null,
        notifiedTimeMillis: Long? = null,
        escalationCount: Int = 0,
        preRemindedAtMillis: Long? = null,
        missedNotified: Boolean = false,
        id: Long = 1L,
    ) = DoseLog(
        id = id,
        medicationId = 1L,
        scheduleId = 1L,
        epochDay = 0L,
        plannedMinuteOfDay = 8 * 60,
        plannedTimeMillis = dueAtMillis,
        plannedQuantity = 1.0,
        plannedUnit = "片",
        takenQuantity = takenQuantity,
        status = status,
        snoozedUntilMillis = snoozedUntilMillis,
        notifiedTimeMillis = notifiedTimeMillis,
        escalationCount = escalationCount,
        preRemindedAtMillis = preRemindedAtMillis,
        missedNotified = missedNotified,
    )

    private fun context(
        at: Long = now,
        localMinuteOfDay: Int = 8 * 60,
        quietHoursEndMillis: Long = 0L,
        trigger: ReminderTrigger = ReminderTrigger.DOSE_ALARM,
    ) = ReminderContext(
        nowMillis = at,
        localMinuteOfDay = localMinuteOfDay,
        quietHoursEndMillis = quietHoursEndMillis,
        // These tests express "inside the window" by supplying an end instant, so mirror it into the
        // flag that decides silence. Named arguments, so adding a field can never silently move a
        // value into the wrong slot again.
        inQuietHours = quietHoursEndMillis > 0L,
        trigger = trigger,
    )

    private fun decide(
        dose: DoseLog,
        prefs: UserPreferences = this.prefs,
        context: ReminderContext = context(),
    ) = ReminderPlanner.decide(dose, prefs, context)

    // ------------------------------------------------------------- settled doses

    @Test
    fun `a dose the user already took is never announced again`() {
        // Even though the alarm fired and the clock says it is due.
        assertThat(decide(dose(status = DoseStatus.TAKEN, takenQuantity = 1.0)))
            .isEqualTo(ReminderDecision.Suppressed(ReminderSuppression.RESOLVED))
    }

    @Test
    fun `a skipped dose is never announced again`() {
        assertThat(decide(dose(status = DoseStatus.SKIPPED)))
            .isEqualTo(ReminderDecision.Suppressed(ReminderSuppression.RESOLVED))
    }

    @Test
    fun `the master switch silences everything, including a due dose`() {
        val off = UserPreferences(remindersEnabled = false)

        val decision = decide(dose(), prefs = off)
        assertThat(decision).isEqualTo(ReminderDecision.Suppressed(ReminderSuppression.REMINDERS_OFF))
    }

    // ------------------------------------------------------------ never too early

    @Test
    fun `a trigger hours ahead of its schedule is a clock error, not a reminder`() {
        // A time-zone change, a stale PendingIntent, an OEM replaying an old alarm.
        val decision = decide(dose(), context = context(at = eightAm - 3 * hour))

        assertThat(decision).isInstanceOf(ReminderDecision.TooEarly::class.java)
        // Re-armed for the correct instant rather than dropped.
        assertThat((decision as ReminderDecision.TooEarly).dueAtMillis).isEqualTo(eightAm)
        // And the report says how early it actually was.
        assertThat(decision.earlyByMillis).isEqualTo(3 * hour)
    }

    @Test
    fun `ordinary alarm jitter is not treated as a clock error`() {
        // setExactAndAllowWhileIdle routinely lands a few seconds early; that is not an error.
        val decision = decide(dose(), context = context(at = eightAm - 30_000L))

        assertThat(decision).isNotInstanceOf(ReminderDecision.TooEarly::class.java)
    }

    @Test
    fun `a periodic sweep is never accused of firing early`() {
        // A heartbeat, a boot pass or an app launch legitimately walks over doses that are hours
        // away. Calling that a clock error would fill the audit trail with noise and bury the one
        // signal that matters.
        val decision = decide(
            dose(),
            context = context(at = eightAm - 8 * hour, trigger = ReminderTrigger.HEARTBEAT),
        )

        assertThat(decision).isNotInstanceOf(ReminderDecision.TooEarly::class.java)
        assertThat(decision).isEqualTo(ReminderDecision.Suppressed(ReminderSuppression.WAITING))
    }

    @Test
    fun `the early tolerance boundary is exact`() {
        // Measured without an advance notice, so the only thing standing between "early" and "due"
        // is the jitter tolerance itself.
        val noLead = UserPreferences(preReminderEnabled = false)
        val tolerance = ReminderWindow.DEFAULT.earlyToleranceMillis

        assertThat(
            decide(dose(), prefs = noLead, context = context(at = eightAm - tolerance - 1))
        ).isInstanceOf(ReminderDecision.TooEarly::class.java)

        assertThat(
            decide(dose(), prefs = noLead, context = context(at = eightAm - tolerance))
        ).isNotInstanceOf(ReminderDecision.TooEarly::class.java)
    }

    // --------------------------------------------------------------- the reminder

    @Test
    fun `a dose that has just come due is announced`() {
        val decision = decide(dose())

        assertThat(decision).isInstanceOf(ReminderDecision.Remind::class.java)
        with(decision as ReminderDecision.Remind) {
            assertThat(escalation).isEqualTo(0)
            assertThat(lateMillis).isEqualTo(0L)
            assertThat(quiet).isFalse()
        }
    }

    @Test
    fun `an alarm that lands several minutes late is still simply on time`() {
        // Nothing compares instants for equality, so this is an ordinary reminder, not a "late" one.
        val decision = decide(dose(), context = context(at = eightAm + 7 * minute))

        assertThat(decision).isInstanceOf(ReminderDecision.Remind::class.java)
        assertThat((decision as ReminderDecision.Remind).escalation).isEqualTo(0)
    }

    // ----------------------------------------------------------- never too stale

    @Test
    fun `a reminder discovered far too late becomes a silent catch-up prompt`() {
        // The phone was off; the user turns it on four hours after the dose. Waking them with a
        // banner for a 08:00 dose at noon would be worse than useless.
        val late = UserPreferences(missedReminderEnabled = false, staleReminderMinutes = 180)
        val decision = decide(dose(), prefs = late, context = context(at = eightAm + 4 * hour))

        assertThat(decision).isInstanceOf(ReminderDecision.CatchUp::class.java)
    }

    @Test
    fun `a catch-up prompt is never repeated`() {
        // It records itself by stamping notifiedTimeMillis; the planner recognises an announcement
        // that landed past its own stale threshold and stops there.
        val late = UserPreferences(missedReminderEnabled = false, staleReminderMinutes = 180)
        val alreadyCatchUpNotified = dose(notifiedTimeMillis = eightAm + 4 * hour)

        val decision = decide(
            alreadyCatchUpNotified,
            prefs = late,
            context = context(at = eightAm + 5 * hour),
        )

        assertThat(decision).isEqualTo(ReminderDecision.Suppressed(ReminderSuppression.ALREADY_TOLD))
    }

    @Test
    fun `with catch-up disabled a very late dose is simply dropped`() {
        val strict = UserPreferences(
            missedReminderEnabled = false,
            catchUpReminderEnabled = false,
            staleReminderMinutes = 180,
        )

        assertThat(decide(dose(), prefs = strict, context = context(at = eightAm + 4 * hour)))
            .isEqualTo(ReminderDecision.Suppressed(ReminderSuppression.PAST_GRACE))
    }

    // -------------------------------------------------------------- missed doses

    @Test
    fun `a dose past its grace deadline is reported missed, once`() {
        val decision = decide(dose(), context = context(at = eightAm + 31 * minute))

        assertThat(decision).isInstanceOf(ReminderDecision.Missed::class.java)
    }

    @Test
    fun `the missed notice is not sent twice`() {
        val decision = decide(
            dose(missedNotified = true),
            context = context(at = eightAm + 31 * minute),
        )

        assertThat(decision).isEqualTo(ReminderDecision.Suppressed(ReminderSuppression.ALREADY_TOLD))
    }

    @Test
    fun `a dose already recorded as missed still gets its one notice`() {
        // This is the regression that mattered most: the missed sweep used to claim the
        // "already notified" flag itself, which made the 未服药 reminder unreachable in practice.
        val decision = decide(
            dose(status = DoseStatus.MISSED),
            context = context(at = eightAm + 31 * minute),
        )

        assertThat(decision).isInstanceOf(ReminderDecision.Missed::class.java)
    }

    @Test
    fun `a dose already recorded as missed stays quiet once it has been reported`() {
        val decision = decide(
            dose(status = DoseStatus.MISSED, missedNotified = true),
            context = context(at = eightAm + 31 * minute),
        )

        assertThat(decision).isEqualTo(ReminderDecision.Suppressed(ReminderSuppression.ALREADY_TOLD))
    }

    // ----------------------------------------------------- bounded escalation

    @Test
    fun `the escalation budget is counted from notifications, not from elapsed time`() {
        // THE regression test for the old implementation.
        //
        // The previous code computed the escalation count as `(now - notifiedTime) / repeatMinutes`
        // and clamped it to the maximum. A dose notified ten hours ago therefore reported a "count"
        // of 600, clamped to 2, which equalled the maximum - so the reminder budget was considered
        // spent and every subsequent reminder was silently suppressed, no matter how few had actually
        // been sent. One delivered notification could burn the entire allowance.
        val notifiedTenHoursAgo = dose(notifiedTimeMillis = eightAm - 10 * hour)
        val repeat = UserPreferences(repeatReminderMinutes = 10, maxEscalationsPerDose = 2)

        val decision = decide(
            notifiedTenHoursAgo,
            prefs = repeat,
            context = context(at = eightAm),
        )

        // One notification has been sent, the budget allows two, so exactly one repeat remains.
        assertThat(decision).isInstanceOf(ReminderDecision.Remind::class.java)
        assertThat((decision as ReminderDecision.Remind).escalation).isEqualTo(0)
    }

    @Test
    fun `the budget stops the reminders once it is genuinely spent`() {
        val spent = dose(notifiedTimeMillis = eightAm - hour, escalationCount = 2)
        val repeat = UserPreferences(repeatReminderMinutes = 10, maxEscalationsPerDose = 2)

        val decision = decide(spent, prefs = repeat, context = context(at = eightAm))

        assertThat(decision)
            .isEqualTo(ReminderDecision.Suppressed(ReminderSuppression.ESCALATION_BUDGET_SPENT))
    }

    @Test
    fun `a repeat waits for the configured interval`() {
        val repeat = UserPreferences(repeatReminderMinutes = 10, maxEscalationsPerDose = 3)

        // Nine minutes on: too soon.
        assertThat(
            decide(
                dose(notifiedTimeMillis = eightAm - 9 * minute, escalationCount = 1),
                prefs = repeat,
                context = context(at = eightAm),
            )
        ).isEqualTo(ReminderDecision.Suppressed(ReminderSuppression.TOO_SOON))

        // Exactly ten minutes on: due.
        assertThat(
            decide(
                dose(notifiedTimeMillis = eightAm - 10 * minute, escalationCount = 1),
                prefs = repeat,
                context = context(at = eightAm),
            )
        ).isInstanceOf(ReminderDecision.Remind::class.java)
    }

    @Test
    fun `repeats can be switched off without silencing the first reminder`() {
        val noRepeat = UserPreferences(repeatReminderMinutes = 0)

        assertThat(decide(dose(), prefs = noRepeat, context = context(at = eightAm)))
            .isInstanceOf(ReminderDecision.Remind::class.java)
        assertThat(
            decide(
                dose(notifiedTimeMillis = eightAm - hour, escalationCount = 1),
                prefs = noRepeat,
                context = context(at = eightAm),
            )
        ).isEqualTo(ReminderDecision.Suppressed(ReminderSuppression.REPEATS_OFF))
    }

    @Test
    fun `a partly taken dose is not nagged about loudly`() {
        // The user acted. The chip already reads 部分服用; ringing again would be scolding them.
        val decision = decide(
            dose(
                status = DoseStatus.PARTIAL,
                takenQuantity = 0.5,
                notifiedTimeMillis = eightAm - hour,
                escalationCount = 1,
            ),
            prefs = UserPreferences(repeatReminderMinutes = 10, maxEscalationsPerDose = 3),
            context = context(at = eightAm),
        )

        assertThat(decision)
            .isEqualTo(ReminderDecision.Suppressed(ReminderSuppression.PARTIAL_ACKNOWLEDGED))
    }

    // -------------------------------------------------------- the advance notice

    @Test
    fun `the advance notice goes out inside the lead window, exactly once`() {
        val lead = UserPreferences(preReminderEnabled = true, preReminderLeadMinutes = 15)

        // Sixteen minutes before: not yet.
        assertThat(decide(dose(), prefs = lead, context = context(at = eightAm - 16 * minute)))
            .isEqualTo(ReminderDecision.Suppressed(ReminderSuppression.WAITING))

        // Ten minutes before: the courtesy notice.
        val decision = decide(dose(), prefs = lead, context = context(at = eightAm - 10 * minute))
        assertThat(decision).isInstanceOf(ReminderDecision.PreRemind::class.java)
        assertThat((decision as ReminderDecision.PreRemind).leadMillis).isEqualTo(10 * minute)

        // Already sent once: never again, however many heartbeats run.
        assertThat(
            decide(
                dose(preRemindedAtMillis = eightAm - 12 * minute),
                prefs = lead,
                context = context(at = eightAm - 10 * minute),
            )
        ).isEqualTo(ReminderDecision.Suppressed(ReminderSuppression.WAITING))
    }

    @Test
    fun `the advance notice is off when the user turns it off`() {
        val off = UserPreferences(preReminderEnabled = false)

        assertThat(decide(dose(), prefs = off, context = context(at = eightAm - 1 * minute)))
            .isEqualTo(ReminderDecision.Suppressed(ReminderSuppression.WAITING))
    }

    @Test
    fun `a clock-change trigger never produces a new announcement`() {
        // Moving the phone's clock must not conjure reminders out of nothing.
        val decision = decide(
            dose(),
            context = context(at = eightAm - 10 * minute, trigger = ReminderTrigger.CLOCK_CHANGED),
        )

        assertThat(decision).isEqualTo(ReminderDecision.Suppressed(ReminderSuppression.WAITING))
    }

    @Test
    fun `a snoozed dose never gets an advance notice`() {
        // The user pressed 稍后 precisely to be left alone until the new time. Firing "还有 10 分钟"
        // the instant they dismissed the reminder would be the opposite of what they asked for; the
        // snooze-state notification already tells them when it will be back.
        val snoozed = dose(snoozedUntilMillis = eightAm + 20 * minute)

        assertThat(ReminderPlanner.headsUpAppliesTo(snoozed)).isFalse()

        val decision = decide(snoozed, context = context(at = eightAm + 10 * minute))
        assertThat(decision).isEqualTo(ReminderDecision.Suppressed(ReminderSuppression.WAITING))
    }

    @Test
    fun `the advance notice still applies to an unsnoozed dose`() {
        assertThat(ReminderPlanner.headsUpAppliesTo(dose())).isTrue()
        // A snooze that is already in the past is not an active snooze.
        assertThat(ReminderPlanner.headsUpAppliesTo(dose(snoozedUntilMillis = eightAm - hour))).isTrue()
    }

    @Test
    fun `one draft of the advance notice is armed before the dose, not after it`() {
        // Ordering matters: the heads-up is useless if it lands after the reminder it announces.
        val lead = UserPreferences(preReminderEnabled = true, preReminderLeadMinutes = 15)
        val window = ReminderPlanner.windowFor(lead)
        val due = ReminderPlanner.effectiveDueMillis(dose())

        assertThat(due - window.leadMillis).isLessThan(due)
    }

    // -------------------------------------------------------------- quiet hours

    @Test
    fun `a first reminder inside quiet hours is deferred to the end of them`() {
        val quietEnd = eightAm + 6 * hour
        val decision = decide(dose(), context = context(quietHoursEndMillis = quietEnd))

        assertThat(decision).isInstanceOf(ReminderDecision.Deferred::class.java)
        with(decision as ReminderDecision.Deferred) {
            assertThat(reason).isEqualTo(DeferReason.QUIET_HOURS)
            assertThat(untilMillis).isEqualTo(quietEnd)
        }
    }

    @Test
    fun `a repeat inside quiet hours is posted silently rather than deferred`() {
        // The user has already been told once, so the second one is a nudge, not a wake-up call.
        val decision = decide(
            dose(notifiedTimeMillis = eightAm - hour, escalationCount = 1),
            prefs = UserPreferences(repeatReminderMinutes = 10, maxEscalationsPerDose = 3),
            context = context(quietHoursEndMillis = eightAm + 6 * hour),
        )

        assertThat(decision).isInstanceOf(ReminderDecision.Remind::class.java)
        assertThat((decision as ReminderDecision.Remind).quiet).isTrue()
    }

    // ------------------------------------------------------------------ snooze

    @Test
    fun `a snoozed dose is judged against the time the user moved it to`() {
        val snoozedUntil = eightAm + 30 * minute
        val snoozed = dose(snoozedUntilMillis = snoozedUntil, notifiedTimeMillis = eightAm)

        assertThat(ReminderPlanner.effectiveDueMillis(snoozed)).isEqualTo(snoozedUntil)

        // A sweep one minute after the original time sees nothing to do: the user pushed this out
        // and the new deadline is still ahead.
        val decision = decide(
            snoozed,
            context = context(at = eightAm + minute, trigger = ReminderTrigger.HEARTBEAT),
        )
        assertThat(decision).isEqualTo(ReminderDecision.Suppressed(ReminderSuppression.WAITING))
    }

    @Test
    fun `a stale alarm for a snoozed dose is re-armed to the snooze, not to the original time`() {
        // This is the case that used to lose reminders outright: the maintenance pass filtered on
        // plannedTimeMillis, so a snoozed dose fell out of the arming window the moment it was
        // snoozed, and only the single alarm the snooze itself set kept it alive.
        val snoozedUntil = eightAm + 30 * minute
        val snoozed = dose(snoozedUntilMillis = snoozedUntil, notifiedTimeMillis = eightAm)

        val decision = decide(snoozed, context = context(at = eightAm + minute))

        assertThat(decision).isInstanceOf(ReminderDecision.TooEarly::class.java)
        assertThat((decision as ReminderDecision.TooEarly).dueAtMillis).isEqualTo(snoozedUntil)
    }

    @Test
    fun `a snoozed dose that is now due is announced afresh`() {
        // "稍后提醒" means tell me properly at the new time; the repository reopens the budget, so the
        // planner sees a dose that has never been announced.
        val snoozed = dose(
            snoozedUntilMillis = eightAm + 30 * minute,
            notifiedTimeMillis = null,
            escalationCount = 0,
        )

        val decision = decide(snoozed, context = context(at = eightAm + 30 * minute))
        assertThat(decision).isInstanceOf(ReminderDecision.Remind::class.java)
        assertThat((decision as ReminderDecision.Remind).escalation).isEqualTo(0)
    }

    @Test
    fun `an expired snooze does not push the deadline into the past`() {
        // A snooze in the past must not make the dose look older than it is.
        val expired = dose(snoozedUntilMillis = eightAm - 10 * minute)

        assertThat(ReminderPlanner.effectiveDueMillis(expired)).isEqualTo(eightAm)
    }

    @Test
    fun `the missed deadline follows the snooze`() {
        val snoozed = dose(snoozedUntilMillis = eightAm + 60 * minute)

        assertThat(ReminderPlanner.missedDeadlineMillis(snoozed, prefs))
            .isEqualTo(eightAm + 60 * minute + 30 * minute)
    }

    // ------------------------------------------------------------- idempotence

    @Test
    fun `the same instant always produces the same decision`() {
        // The pipeline has four independent triggers; two of them landing together must not produce
        // two different answers, or the "first one wins" contract breaks.
        val d = dose(notifiedTimeMillis = eightAm - 20 * minute, escalationCount = 1)
        val p = UserPreferences(repeatReminderMinutes = 10, maxEscalationsPerDose = 3)
        val c = context(at = eightAm)

        assertThat(ReminderPlanner.decide(d, p, c)).isEqualTo(ReminderPlanner.decide(d, p, c))
    }

    // ---------------------------------------------------------------- clustering

    @Test
    fun `doses that fall due together are grouped into one announcement`() {
        val window = ReminderWindow.DEFAULT
        val a = dose(dueAtMillis = eightAm)
        val b = dose(dueAtMillis = eightAm + 5 * minute)
        val c = dose(dueAtMillis = eightAm + 10 * minute)
        val later = dose(dueAtMillis = eightAm + 4 * hour)

        val clusters = ReminderPlanner.cluster(
            listOf(a to eightAm, b to eightAm + 5 * minute, c to eightAm + 10 * minute, later to eightAm + 4 * hour),
            window,
        )

        assertThat(clusters).hasSize(2)
        assertThat(clusters[0].map { it.first.id }).containsExactly(1L, 1L, 1L)
        assertThat(clusters[0]).hasSize(3)
        assertThat(clusters[1]).hasSize(1)
    }

    @Test
    fun `clustering can be switched off entirely`() {
        val window = ReminderWindow.DEFAULT.copy(clusterMinutes = 0)
        val a = dose(dueAtMillis = eightAm)
        val b = dose(dueAtMillis = eightAm)

        val clusters = ReminderPlanner.cluster(listOf(a to eightAm, b to eightAm), window)

        assertThat(clusters).hasSize(2)
    }

    @Test
    fun `the cluster window follows the user's setting`() {
        val wide = UserPreferences(digestEnabled = true, clusterWindowMinutes = 60)

        assertThat(ReminderPlanner.windowFor(wide).clusterMinutes).isEqualTo(60)

        val off = UserPreferences(digestEnabled = false, clusterWindowMinutes = 60)
        assertThat(ReminderPlanner.windowFor(off).clusterMinutes).isEqualTo(0)
    }

    // ------------------------------------------------------ window from preferences

    @Test
    fun `the window mirrors the user's settings`() {
        val custom = UserPreferences(
            preReminderEnabled = true,
            preReminderLeadMinutes = 25,
            earlyToleranceMinutes = 3,
            reminderFreshMinutes = 45,
            staleReminderMinutes = 90,
            clusterWindowMinutes = 15,
        )

        val window = ReminderPlanner.windowFor(custom)

        assertThat(window.leadMinutes).isEqualTo(25)
        assertThat(window.earlyToleranceMinutes).isEqualTo(3)
        assertThat(window.freshMinutes).isEqualTo(45)
        assertThat(window.staleAfterMinutes).isEqualTo(90)
        assertThat(window.clusterMinutes).isEqualTo(15)
    }

    @Test
    fun `switching off the advance notice removes the lead from the window`() {
        val off = UserPreferences(preReminderEnabled = false)

        assertThat(ReminderPlanner.windowFor(off).leadMinutes).isEqualTo(0)
    }


    // ------------------------------------------------- never-early, scoped to one dose
    //
    // The alarm intent names the dose it was armed for. Applying "never announce early" to *every*
    // candidate in the pass - which is what the pipeline used to do - produced six bogus
    // "触发早于计划" rows and six bogus "corrected" counts every time a single dose alarm fired, which
    // made the self-check report lie about how much correcting was going on.

    @Test
    fun `a dose alarm for one dose does not accuse another dose of firing early`() {
        val other = dose(dueAtMillis = now + 4 * hour, status = DoseStatus.UPCOMING, id = 2L)

        val decision = ReminderPlanner.decide(
            dose = other,
            prefs = prefs,
            context = ReminderContext(
                nowMillis = now,
                localMinuteOfDay = 8 * 60,
                trigger = ReminderTrigger.DOSE_ALARM,
                claimedDoseId = 1L,
            ),
        )

        assertThat(decision).isNotInstanceOf(ReminderDecision.TooEarly::class.java)
        // ...and it is not announced either: it is simply waiting its turn.
        assertThat(decision).isInstanceOf(ReminderDecision.Suppressed::class.java)
    }

    @Test
    fun `the dose the alarm names is still corrected when it really is early`() {
        val claimed = dose(dueAtMillis = now + 4 * hour, status = DoseStatus.UPCOMING)
        val decision = ReminderPlanner.decide(
            dose = claimed,
            prefs = prefs,
            context = ReminderContext(
                nowMillis = now,
                localMinuteOfDay = 8 * 60,
                trigger = ReminderTrigger.DOSE_ALARM,
                claimedDoseId = claimed.id,
            ),
        )
        assertThat(decision).isInstanceOf(ReminderDecision.TooEarly::class.java)
    }

    @Test
    fun `an alarm that names no dose keeps the old conservative reading`() {
        val other = dose(dueAtMillis = now + 4 * hour, status = DoseStatus.UPCOMING)
        val decision = ReminderPlanner.decide(
            dose = other,
            prefs = prefs,
            context = ReminderContext(
                nowMillis = now,
                localMinuteOfDay = 8 * 60,
                trigger = ReminderTrigger.DOSE_ALARM,
                claimedDoseId = null,
            ),
        )
        assertThat(decision).isInstanceOf(ReminderDecision.TooEarly::class.java)
    }
}
