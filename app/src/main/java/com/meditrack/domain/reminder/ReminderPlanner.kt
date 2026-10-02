package com.meditrack.domain.reminder

import com.meditrack.core.util.QuantityFormatter
import com.meditrack.data.local.entity.DoseLog
import com.meditrack.data.local.entity.DoseStatus
import com.meditrack.data.prefs.UserPreferences

/**
 * Why the pipeline ran.
 *
 * Recorded on every audit entry, because "the reminder did not appear" is almost always answerable
 * from *which* of these last ran and what it decided. It also lets individual rules be selective:
 * a status-bar refresh has no business announcing anything, while a dose alarm does.
 */
enum class ReminderTrigger(val label: String) {
    /** The exact alarm armed for one dose. */
    DOSE_ALARM("服药闹钟"),

    /** The optional "还有一会儿" heads-up armed ahead of a dose. */
    PRE_ALARM("提前提醒"),

    /** The rolling short-interval self-check. The main defence against a lost alarm. */
    HEARTBEAT("心跳自检"),

    /**
     * The background guard service starting up or re-checking.
     *
     * Distinct from [HEARTBEAT] because it answers a different question when reading the audit trail:
     * a pass from the guard proves the process was alive and protected, while a pass from the
     * heartbeat proves only that an alarm was delivered.
     */
    GUARD_SERVICE("后台守护"),

    /**
     * The once-a-day deep pass: history repair, the full multi-day horizon, audit pruning.
     *
     * No longer load-bearing. It used to be the *only* thing that rebuilt the schedule, which made
     * midnight - the worst moment in the day for alarms to survive - a single point of failure.
     */
    NIGHTLY("每日深度自检"),

    /** Device rebooted; every alarm is gone and must be rebuilt from the database. */
    BOOT("开机自检"),

    /** The process started. */
    APP_START("应用启动"),

    /**
     * The user picked the phone up - either the lock screen was dismissed, or the app was brought
     * back to the foreground.
     *
     * This is the *only* trigger that carries the unlock catch-up (see
     * [ReminderDecision.UnlockCatchUp]): it is the one moment where the app has direct evidence that
     * a human is looking at the screen, and therefore the one moment where re-announcing a dose
     * nobody saw is a service rather than an interruption.
     */
    USER_RETURN("解锁/回到应用"),

    /** A reminder-related setting changed. */
    SETTINGS_CHANGED("设置变更"),

    /** The independent WorkManager path. */
    PERIODIC_WORK("后台定期核对"),

    /** The user pressed "立即自检并修复". */
    USER_REPAIR("手动修复"),

    /** The system clock or time zone moved. */
    CLOCK_CHANGED("时间变更"),
    ;

    /** True for triggers that may produce a *new* audible announcement. */
    val mayAnnounce: Boolean
        get() = this != SETTINGS_CHANGED && this != CLOCK_CHANGED

    /**
     * True for a trigger that claims to *be* one specific dose's alarm.
     *
     * Used by the "never early" rule: only an alarm that asserts "this is your 08:00 dose" can be
     * implausibly early. A sweep, a boot pass or an app launch legitimately walks over doses hours
     * away, and treating that as a clock error would make the audit trail useless.
     */
    val claimsOneDose: Boolean
        get() = this == DOSE_ALARM || this == PRE_ALARM
}

/** Why a dose was deliberately not announced. Every value is a distinct, diagnosable outcome. */
enum class ReminderSuppression(val label: String) {
    REMINDERS_OFF("提醒总开关已关闭"),
    RESOLVED("已服用或已跳过"),
    WAITING("还没到提醒窗口"),
    ALREADY_TOLD("已经提醒过了"),
    PAST_GRACE("已过服药宽限期"),
    PARTIAL_ACKNOWLEDGED("已记录部分剂量，不再重复响铃"),
    REPEATS_OFF("重复提醒已关闭"),
    ESCALATION_BUDGET_SPENT("已达到该剂量的提醒次数上限"),
    TOO_SOON("距离上次提醒还不够久"),
    QUIET_HOURS("免打扰时段，已顺延"),
    IDLE_DEFERRED("手机未在使用，已暂缓"),
    NOT_A_REMINDER_TRIGGER("该触发原因不做提醒"),
}

/** Why a reminder was held back rather than delivered now. */
enum class DeferReason(val label: String) {
    /** The dose fell inside the user's quiet hours; it comes back when they end. */
    QUIET_HOURS("免打扰时段"),
    /**
     * **Retired.** Nobody was looking at the phone.
     *
     * The value survives so that an audit row written by an older build ("DEFER_IDLE") still decodes
     * to something meaningful. Nothing produces it any more: holding a reminder back until the user
     * picked the phone up was replaced by the unlock catch-up, which re-announces the dose instead of
     * withholding it - see [ReminderDecision.UnlockCatchUp].
     */
    @Deprecated("Replaced by the unlock catch-up; kept so old audit rows still decode.")
    IDLE("手机闲置"),
}

/**
 * The single decision the pipeline makes about one dose at one instant.
 *
 * Every branch is a value, never a side effect, so the entire reminder policy is a pure function of
 * (dose, preferences, clock) and can be exhaustively tested on the JVM. The engine's job is only to
 * carry the decision out.
 */
sealed interface ReminderDecision {

    /** Stable identifier written to the audit trail. */
    val code: String

    /** Nothing to do, for a reason worth recording. */
    data class Suppressed(val reason: ReminderSuppression) : ReminderDecision {
        override val code: String get() = "SKIP_" + reason.name
    }

    /**
     * Error correction: the alarm fired **before** its own tolerance window.
     *
     * This is a clock jump, a stale `PendingIntent` or an OEM re-delivering an old alarm. Announcing
     * it would be wrong, so it is quietly re-armed for the correct instant instead.
     *
     * [earlyByMillis] is measured against the dose's own due instant, so it reads as "this arrived
     * three hours early" rather than as a distance to some internal boundary.
     */
    data class TooEarly(val dueAtMillis: Long, val earlyByMillis: Long) : ReminderDecision {
        override val code: String get() = "TOO_EARLY"
    }

    /** The optional heads-up posted shortly before the dose is due. */
    data class PreRemind(
        val dueAtMillis: Long,
        val leadMillis: Long,
        val quiet: Boolean,
    ) : ReminderDecision {
        override val code: String get() = "PRE_REMIND"
    }

    /** The reminder itself. [escalation] is 0 for the first announcement and grows with repeats. */
    data class Remind(
        val dueAtMillis: Long,
        val escalation: Int,
        val lateMillis: Long,
        val quiet: Boolean,
    ) : ReminderDecision {
        override val code: String get() = if (escalation == 0) "REMIND" else "REMIND_REPEAT"
    }

    /**
     * Too late for a banner, but the dose is still unrecorded.
     *
     * Posted silently as "补记" - "if you already took it, tap here" - which is honest about the fact
     * that the moment has passed while still giving the user a one-tap way to correct the record.
     */
    data class CatchUp(val dueAtMillis: Long, val lateMillis: Long) : ReminderDecision {
        override val code: String get() = "CATCH_UP"
    }

    /** The grace period lapsed with nothing recorded. Silent, and sent only once. */
    data class Missed(val dueAtMillis: Long, val lateMillis: Long) : ReminderDecision {
        override val code: String get() = "MISSED"
    }

    /**
     * An overdue, still-unrecorded dose re-announced because the user just picked the phone up.
     *
     * This is the answer to the failure the scheduled pipeline cannot cover on its own: the reminder
     * fired while the phone was locked in a pocket, nobody saw it, and by the time the user looked the
     * dose was past its grace period - where every other branch below, correctly, decides to stay
     * quiet. Announcements of this kind are counted separately ([DoseLog.unlockReminderCount]) so they
     * cannot spend the ordinary escalation budget, and vice versa.
     *
     * [quiet] means the phone was picked up inside the user's quiet hours: the dose is still worth
     * noting, but it must not buzz. [occurrence] is the 1-based number of this catch-up for the dose.
     */
    data class UnlockCatchUp(
        val dueAtMillis: Long,
        val lateMillis: Long,
        val quiet: Boolean,
        val occurrence: Int,
    ) : ReminderDecision {
        override val code: String get() = "UNLOCK_CATCH_UP"
    }

    /** Held back deliberately; [untilMillis] is when it becomes due again. */
    data class Deferred(
        val untilMillis: Long,
        val reason: DeferReason,
    ) : ReminderDecision {
        override val code: String get() = "DEFER_" + reason.name
    }
}

/**
 * Everything about "now" that the decision needs, pre-computed by the caller.
 *
 * Separating this out is what keeps the planner free of `LocalTime.now()` and therefore testable:
 * a test can put the clock anywhere, including inside quiet hours that wrap past midnight.
 */
data class ReminderContext(
    val nowMillis: Long,
    /** Local wall-clock minute, used only for quiet hours and human labels. */
    val localMinuteOfDay: Int,
    /**
     * When quiet hours end, if the current moment is inside them; otherwise 0.
     *
     * Computed by the caller because resolving it needs a time zone, which the planner must not
     * touch.
     */
    val quietHoursEndMillis: Long = 0L,
    val trigger: ReminderTrigger,
    /**
     * True for the "用户回到了手机" triggers, which are allowed to re-announce a dose that every other
     * trigger would leave alone.
     *
     * Explicit rather than derived from [trigger] so a test can exercise the unlock rule without
     * pretending to be an alarm, and so a future trigger can opt in deliberately.
     */
    val unlockCatchUp: Boolean = false,
    /**
     * The dose the fired alarm named, when the trigger is an alarm.
     *
     * The alarm intent carries the dose it was armed for, and this is where that fact enters the
     * decision. It matters because "never announce a dose early" is a rule about *one* dose's alarm,
     * not about the pass as a whole: a single dose alarm also sweeps every other candidate, and
     * accusing those of firing hours early was pure noise - it filled the audit trail with
     * "触发早于计划" rows and made the self-check report claim it had "corrected" six doses every time
     * an alarm fired.
     */
    val claimedDoseId: Long? = null,
) {
    /**
     * True when the trigger is asserting that *this* dose's alarm fired.
     *
     * An alarm that named no dose (or a dose we cannot see) keeps the old, conservative reading:
     * everything in the pass is treated as if it were the claimed dose.
     */
    fun claims(dose: DoseLog): Boolean {
        if (!trigger.claimsOneDose) return false
        val claimed = claimedDoseId ?: return true
        return claimed == dose.id
    }
}

/**
 * The reminder policy, as one pure function.
 *
 * ## Why this exists
 *
 * The previous implementation asked the alarm "did you fire?" and acted on the answer. That makes
 * the operating system the source of truth, and an operating system that is allowed to be late,
 * early, or to silently drop a `PendingIntent` is not a source of truth. Chrono's lesson is the
 * opposite: the *database* is the truth, the alarm is a hint, and every trigger re-derives what
 * should happen from scratch.
 *
 * This function is the "what should happen" half. Everything it returns is decided from the stored
 * dose plus the preferences plus the clock - never from which alarm happened to fire.
 *
 * ## The guarantees it encodes
 *
 *  1. **Never early.** A trigger more than the tolerance ahead of schedule is a clock error.
 *  2. **Never stale.** Past [ReminderWindow.staleAfterMillis] there is no banner, only a silent
 *     catch-up prompt, so a rebooted phone cannot produce a 3am buzz for a 08:00 dose.
 *  3. **Bounded.** The escalation budget is a stored counter, not a derived guess, so a phone that
 *     was off for a day cannot evaporate it or spend it twice.
 *  4. **Idempotent.** Two triggers in the same minute produce the same decision, and the first one
 *     to run spends the state that stops the second from announcing again.
 *  5. **Quiet-aware.** A dose that comes due inside the user's quiet hours is *held and delivered
 *     when they end*, rather than being posted silently at 23:40 where nobody will ever see it.
 */
object ReminderPlanner {

    /** The envelope derived from the user's settings. */
    fun windowFor(prefs: UserPreferences): ReminderWindow = ReminderWindow(
        leadMinutes = if (prefs.preReminderEnabled) prefs.preReminderLeadMinutes else 0,
        earlyToleranceMinutes = prefs.earlyToleranceMinutes,
        freshMinutes = prefs.reminderFreshMinutes,
        staleAfterMinutes = prefs.staleReminderMinutes,
        clusterMinutes = if (prefs.digestEnabled) prefs.clusterWindowMinutes else 0,
    )

    /**
     * The instant the dose is due, honouring an active snooze.
     *
     * Identical to [com.meditrack.domain.plan.DayPlanner.missedDeadlineMillis]'s base, which matters:
     * a snoozed dose must be judged against the deadline the user moved it to, not the original one.
     */
    fun effectiveDueMillis(dose: DoseLog): Long =
        dose.snoozedUntilMillis?.takeIf { it > dose.plannedTimeMillis } ?: dose.plannedTimeMillis

    /** The instant after which an untouched dose is recorded as 未服药. */
    fun missedDeadlineMillis(dose: DoseLog, prefs: UserPreferences): Long =
        effectiveDueMillis(dose) + prefs.missedGraceMinutes.coerceAtLeast(0) * 60_000L

    /**
     * Whether the advance notice still belongs to this dose.
     *
     * The heads-up exists to prepare the user for the **original** schedule. Once they have pressed
     * 稍后 and pushed the dose out, they have explicitly asked to be left alone until the new time -
     * so firing a "还有 10 分钟" the instant they dismissed the reminder would be the opposite of what
     * they asked for. The snooze-state notification already tells them when it will be back.
     */
    fun headsUpAppliesTo(dose: DoseLog): Boolean =
        dose.snoozedUntilMillis == null || dose.snoozedUntilMillis <= dose.plannedTimeMillis

    /**
     * Whether an **unlock catch-up** may announce this dose right now.
     *
     * The whole rule, as one pure function, because it is the product promise of the feature and it
     * deserves to be exercised on the JVM rather than reasoned about at a call site. It says yes only
     * when all of the following hold:
     *
     *  1. the feature is on, and the dose is neither taken nor skipped - the user has not resolved it;
     *  2. the dose is genuinely overdue (its *effective* due instant, so a snooze moves the deadline);
     *  3. no snooze is still running - "稍后" means "leave me alone until then", and once that time
     *     arrives the dose becomes eligible again like any other;
     *  4. the lateness is still inside the user's own "太晚了" threshold ([UserPreferences.staleReminderMinutes]).
     *     Past it the moment has gone, and the pipeline's silent 补记 prompt is the honest answer;
     *  5. the dose still has unlock reminders left in its own budget, and enough time has passed since
     *     the last one that a fresh unlock is a new event rather than a repeat of the same glance.
     *
     * A **partially** recorded dose is eligible: "吃了一半" is not "吃好了", and the user asked to be
     * told about exactly that case.
     */
    fun unlockCatchUpEligible(
        dose: DoseLog,
        prefs: UserPreferences,
        nowMillis: Long,
    ): Boolean {
        if (!prefs.remindersEnabled || !prefs.unlockReminderEnabled) return false
        if (dose.status == DoseStatus.TAKEN || dose.status == DoseStatus.SKIPPED) return false

        val due = effectiveDueMillis(dose)
        if (nowMillis < due) return false

        // A snooze that has not expired is an explicit "not now". Once it has expired the dose is
        // simply overdue again, and rule 3 no longer applies to it.
        val snoozedUntil = dose.snoozedUntilMillis
        if (snoozedUntil != null && nowMillis < snoozedUntil) return false

        val late = nowMillis - due
        if (late > prefs.staleReminderMinutes.coerceAtLeast(1) * 60_000L) return false

        if (dose.unlockReminderCount >= prefs.unlockReminderMaxPerDose.coerceAtLeast(1)) return false

        val lastAt = dose.unlockReminderAtMillis
        if (lastAt != null && nowMillis - lastAt < prefs.unlockReminderMinGapMinutes.coerceAtLeast(0) * 60_000L) {
            return false
        }
        return true
    }

    /**
     * Lateness beyond which an armed alarm counts as never delivered.
     *
     * Generous on purpose. Claiming an alarm was "lost" is an accusation against the operating system,
     * and it should only be made when the gap is far larger than any plausible deferral.
     */
    const val LOST_ALARM_TOLERANCE_MILLIS = 5 * 60_000L

    /**
     * True when an alarm the app *knows* it armed was never delivered.
     *
     * This is the one measurement that separates the two failures which otherwise look identical from
     * inside the app:
     *
     *  - **we never scheduled it** - a bug in this pipeline, fixed by reasoning about the planner;
     *  - **the system threw it away** - the app was cleared from the background, fixed only by the
     *    guard service and by the user allowing autostart.
     *
     * The rule is deliberately narrow. It fires only when all three hold:
     *
     *  1. there *was* an expectation, i.e. the previous pass really did arm this dose;
     *  2. the dose has still never been announced - a dose that arrived late via the heartbeat was
     *     delivered, even if not by the alarm we set;
     *  3. the armed instant is further in the past than [LOST_ALARM_TOLERANCE_MILLIS], so ordinary
     *     jitter or a single deferred delivery cannot be mistaken for a lost one.
     *
     * @param expectedAtMillis when the previous pass armed this dose, or null if it did not
     * @param notifiedTimeMillis when the dose was first announced, or null if never
     */
    fun alarmWasLost(
        expectedAtMillis: Long?,
        notifiedTimeMillis: Long?,
        nowMillis: Long,
    ): Boolean {
        if (expectedAtMillis == null) return false
        if (notifiedTimeMillis != null) return false
        return nowMillis > expectedAtMillis + LOST_ALARM_TOLERANCE_MILLIS
    }

    fun decide(
        dose: DoseLog,
        prefs: UserPreferences,
        context: ReminderContext,
    ): ReminderDecision {
        val window = windowFor(prefs)
        val now = context.nowMillis

        // 1. The master switch. Nothing below this point may ever fire while it is off.
        if (!prefs.remindersEnabled) return suppressed(ReminderSuppression.REMINDERS_OFF)

        // 2. Already settled by the user. A resolved dose must never ring again, whatever fired.
        if (dose.status == DoseStatus.TAKEN || dose.status == DoseStatus.SKIPPED) {
            return suppressed(ReminderSuppression.RESOLVED)
        }

        val due = effectiveDueMillis(dose)
        val untouched = QuantityFormatter.isZero(dose.takenQuantity)

        // 2b. The user just picked the phone up. This sits deliberately **before** every
        //     "already told" and "past the grace period" branch below, because those branches are
        //     exactly what turned a reminder nobody saw into permanent silence: the first
        //     announcement had already been spent on a locked, dark screen, and the lapsed dose could
        //     only ever produce one silent 未服药 note afterwards. Re-announcing is the point here,
        //     and it is safe to do because the unlock path carries its own budget and minimum gap.
        if (context.unlockCatchUp && unlockCatchUpEligible(dose, prefs, now)) {
            return ReminderDecision.UnlockCatchUp(
                dueAtMillis = due,
                lateMillis = now - due,
                quiet = isQuiet(context),
                occurrence = dose.unlockReminderCount + 1,
            )
        }

        // 3. Error correction, part one: a dose whose only announcement was itself a catch-up is
        //    settled. The moment is long gone and nothing further should ever be said about it, so
        //    this has to be checked before every other branch - otherwise the catch-up it just sent
        //    would be re-sent on every subsequent heartbeat, forever.
        val notifiedAt = dose.notifiedTimeMillis
        if (notifiedAt != null && notifiedAt - due > window.staleAfterMillis) {
            return suppressed(ReminderSuppression.ALREADY_TOLD)
        }

        // 4. Error correction, part two: a trigger that is *implausibly* early is a clock error.
        //
        //    The earliest instant the pipeline may legitimately look at a dose is the opening of its
        //    advance-notice window, plus a little jitter tolerance. Anything before that is a stale
        //    alarm, a time-zone change, or an OEM replaying an old `PendingIntent`.
        //
        //    This only applies to a trigger that claims to *be* this dose's alarm. A periodic sweep,
        //    a boot pass or an app launch legitimately walks over doses that are hours away, and
        //    calling that an error would fill the audit trail with noise and make the one signal that
        //    matters impossible to see.
        if (context.claims(dose)) {
            val earliest = due - window.leadMillis - window.earlyToleranceMillis
            if (now < earliest) return ReminderDecision.TooEarly(due, due - now)
        }

        val late = now - due

        // 5. Past the grace deadline, or already recorded as missed by the sweep.
        if (now > missedDeadlineMillis(dose, prefs) || dose.status == DoseStatus.MISSED) {
            if (dose.missedNotified) return suppressed(ReminderSuppression.ALREADY_TOLD)
            val tooLateForNoise = late > window.staleAfterMillis
            return when {
                // A missed notification is only honest while the miss is still recent.
                prefs.missedReminderEnabled && !tooLateForNoise ->
                    ReminderDecision.Missed(due, late)
                // Otherwise degrade to the silent "if you already took it, say so" prompt.
                prefs.catchUpReminderEnabled -> ReminderDecision.CatchUp(due, late)
                else -> suppressed(ReminderSuppression.PAST_GRACE)
            }
        }

        // 6. Still ahead of the dose: this is where the heads-up lives.
        if (now < due) {
            val leadAt = due - window.leadMillis
            val inLeadWindow = window.leadMinutes > 0 && now >= leadAt
            val eligible = inLeadWindow &&
                dose.preRemindedAtMillis == null &&
                headsUpAppliesTo(dose) &&
                context.trigger.mayAnnounce
            if (eligible) {
                if (isQuiet(context)) {
                    // Hold it: at the end of quiet hours the dose itself will be due anyway.
                    return ReminderDecision.Deferred(context.quietHoursEndMillis, DeferReason.QUIET_HOURS)
                }
                return ReminderDecision.PreRemind(due, due - now, quiet = false)
            }
            return suppressed(ReminderSuppression.WAITING)
        }

        // 7. Inside the fresh window and never announced.
        if (dose.notifiedTimeMillis == null) {
            if (late > window.staleAfterMillis) return ReminderDecision.CatchUp(due, late)
            if (isQuiet(context)) {
                // The first announcement is held rather than muted: a reminder nobody sees is a
                // reminder that did not happen, and quiet hours end at a known instant.
                return ReminderDecision.Deferred(context.quietHoursEndMillis, DeferReason.QUIET_HOURS)
            }
            return ReminderDecision.Remind(due, escalation = 0, lateMillis = late, quiet = false)
        }

        // 8. Announced at least once; decide whether to repeat.
        if (!untouched) return suppressed(ReminderSuppression.PARTIAL_ACKNOWLEDGED)
        if (prefs.repeatReminderMinutes <= 0) return suppressed(ReminderSuppression.REPEATS_OFF)
        if (dose.escalationCount >= prefs.maxEscalationsPerDose.coerceAtLeast(1)) {
            return suppressed(ReminderSuppression.ESCALATION_BUDGET_SPENT)
        }
        val nextAt = dose.notifiedTimeMillis + prefs.repeatReminderMinutes.coerceAtLeast(1) * 60_000L
        if (now < nextAt) return suppressed(ReminderSuppression.TOO_SOON)

        // Repeats are posted silently during quiet hours instead of being deferred: the user has
        // already been told once, so the second one is a nudge, not a wake-up call.
        return ReminderDecision.Remind(due, dose.escalationCount, late, quiet = isQuiet(context))
    }

    private fun isQuiet(context: ReminderContext): Boolean = context.quietHoursEndMillis > 0L

    private fun suppressed(reason: ReminderSuppression) = ReminderDecision.Suppressed(reason)

    /**
     * Groups doses that fall due close together.
     *
     * Three medications scheduled at 08:00, 08:05 and 08:10 are one event in a person's morning, not
     * three. Announcing them as three separate buzzes four minutes apart is exactly the kind of
     * machine behaviour this rewrite exists to remove, so callers collapse any run of doses whose
     * due instants are within [ReminderWindow.clusterMillis] into a single digest.
     *
     * Input order is preserved; the caller supplies doses sorted by due time.
     */
    fun cluster(doses: List<Pair<DoseLog, Long>>, window: ReminderWindow): List<List<Pair<DoseLog, Long>>> {
        if (doses.isEmpty()) return emptyList()
        if (window.clusterMillis <= 0L) return doses.map { listOf(it) }

        val clusters = mutableListOf<MutableList<Pair<DoseLog, Long>>>()
        var current = mutableListOf(doses.first())
        for (item in doses.drop(1)) {
            val anchor = current.first().second
            if (item.second - anchor <= window.clusterMillis) {
                current += item
            } else {
                clusters += current
                current = mutableListOf(item)
            }
        }
        clusters += current
        return clusters
    }
}
