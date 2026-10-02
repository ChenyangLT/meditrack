package com.meditrack.domain.plan

import com.meditrack.core.util.DateTimeUtils
import com.meditrack.data.local.entity.DoseLog
import com.meditrack.data.local.entity.DoseStatus
import com.meditrack.data.local.entity.Medication
import com.meditrack.data.local.entity.MedicationWithSchedules
import com.meditrack.data.local.entity.Schedule
import com.meditrack.core.util.QuantityFormatter
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * A single planned dose produced by expanding the schedules for one day.
 *
 * This is a *pure value*: no Room entity, no Android type. [DayPlanner] turns schedules into these,
 * the repository reconciles them against stored [DoseLog] rows, and the UI renders the result.
 * Keeping this pure is what makes the scheduling rules unit testable on the JVM.
 */
data class PlannedDose(
    val medicationId: Long,
    val scheduleId: Long,
    val epochDay: Long,
    val minuteOfDay: Int,
    val plannedTimeMillis: Long,
    val plannedQuantity: Double,
    val unitLabel: String,
) {
    /** Stable natural key of a planned dose, matching the `(scheduleId, epochDay)` unique index. */
    val key: String get() = "$scheduleId@$epochDay"
}

/**
 * Expands "what is scheduled" into "what should happen today".
 *
 * All arithmetic is epoch-day based (see [DateTimeUtils]) so DST transitions and time-zone changes
 * cannot shift a dose onto the wrong day.
 */
object DayPlanner {

    /**
     * Builds every planned dose for [epochDay], sorted by time then name.
     *
     * @param medications active medications with their slots
     * @param epochDay the day to expand
     */
    fun plan(medications: List<MedicationWithSchedules>, epochDay: Long): List<PlannedDose> {
        if (medications.isEmpty()) return emptyList()
        val out = ArrayList<PlannedDose>(medications.size * 2)
        for (mws in medications) {
            val med = mws.medication
            // A medication created with a future start date must not appear before that day.
            for (slot in mws.schedules) {
                if (!slot.isActiveOn(epochDay)) continue
                out += PlannedDose(
                    medicationId = med.id,
                    scheduleId = slot.id,
                    epochDay = epochDay,
                    minuteOfDay = slot.minuteOfDay,
                    plannedTimeMillis = DateTimeUtils.millisAt(epochDay, slot.minuteOfDay),
                    plannedQuantity = med.plannedQuantity,
                    unitLabel = med.unit.label,
                )
            }
        }
        return out.sortedWith(
            compareBy<PlannedDose> { it.minuteOfDay }.thenBy { it.medicationId }
        )
    }

    /**
     * Default status for a dose that has no stored row yet.
     *
     * Note the deliberate choice to return [DoseStatus.DUE] rather than MISSED for a long-past dose:
     * the missed escalation is owned by [com.meditrack.data.repository.DoseRepository], which knows
     * whether the lapsed reminder has already fired. The planner stays a pure clock function.
     */
    fun deriveStatus(
        plannedTimeMillis: Long,
        takenQuantity: Double,
        plannedQuantity: Double,
        isSkipped: Boolean = false,
        snoozedUntilMillis: Long? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): DoseStatus {
        if (isSkipped) return DoseStatus.SKIPPED
        // Order matters here, and it is driven by what the user has already recorded:
        //  1. a completed amount is TAKEN regardless of the clock - taking it early is still taking it;
        //  2. any recorded amount short of the target is PARTIAL, including before the scheduled
        //     time (a user who logs half a tablet at 07:30 for an 08:00 dose must not see "未到时间");
        //  3. an active snooze pins the dose to DUE so it stays visibly actionable;
        //  4. only then does the clock decide between UPCOMING and DUE.
        if (QuantityFormatter.isComplete(takenQuantity, plannedQuantity)) return DoseStatus.TAKEN
        if (!QuantityFormatter.isZero(takenQuantity)) return DoseStatus.PARTIAL
        if (snoozedUntilMillis != null && nowMillis < snoozedUntilMillis) return DoseStatus.DUE
        if (nowMillis < plannedTimeMillis) return DoseStatus.UPCOMING
        return DoseStatus.DUE
    }

    /**
     * The status to **show** for a stored dose row, which may be ahead of what is stored.
     *
     * ## Why the stored status is not enough
     *
     * A dose row is created before its time - by the reminder pass materialising several days of
     * schedule, or by the today screen opening in the morning - and it is written with the status the
     * clock implied *at that moment*: `UPCOMING`. Nothing goes back and rewrites it when the clock
     * catches up, because the only thing that does so is the missed sweep, and that runs a whole
     * grace period later.
     *
     * The result was visible on a real device: at 22:54, a dose due at 22:49 - for which a reminder
     * had already been posted - still read 「未到时间」 on the today screen, next to the very same
     * card's "已过 5 分钟". The overdue red 未服药 state, which `DoseView.isOverdue` exists to
     * produce, could never appear in that window either, because it requires `DUE`.
     *
     * So `UPCOMING` is treated as "not yet decided" and re-derived from the clock. Every other value
     * is a statement about what the *user* did, and the clock is never allowed to overrule it.
     */
    fun displayStatus(
        stored: DoseStatus?,
        plannedTimeMillis: Long,
        takenQuantity: Double,
        plannedQuantity: Double,
        isSkipped: Boolean = false,
        snoozedUntilMillis: Long? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): DoseStatus {
        val clock = deriveStatus(
            plannedTimeMillis = plannedTimeMillis,
            takenQuantity = takenQuantity,
            plannedQuantity = plannedQuantity,
            isSkipped = isSkipped,
            snoozedUntilMillis = snoozedUntilMillis,
            nowMillis = nowMillis,
        )
        return if (stored == null || stored == DoseStatus.UPCOMING) clock else stored
    }

    /**
     * Milliseconds at which a dose stops being "待服用" and is recorded as "未服药".
     *
     * @param plannedTimeMillis the scheduled instant
     * @param snoozedUntilMillis active snooze deadline, if any
     * @param graceMinutes how long after the scheduled time the user still gets a pass
     */
    fun missedDeadlineMillis(
        plannedTimeMillis: Long,
        snoozedUntilMillis: Long?,
        graceMinutes: Int,
    ): Long {
        val base = maxOf(plannedTimeMillis, snoozedUntilMillis ?: Long.MIN_VALUE)
        return base + graceMinutes.coerceAtLeast(0) * 60_000L
    }

    /** True when an untouched dose should now be escalated to [DoseStatus.MISSED]. */
    fun shouldEscalateToMissed(
        dose: DoseLog,
        graceMinutes: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        if (dose.status == DoseStatus.MISSED || dose.status == DoseStatus.SKIPPED) return false
        if (dose.status == DoseStatus.TAKEN) return false
        if (!QuantityFormatter.isZero(dose.takenQuantity)) return false
        return nowMillis > missedDeadlineMillis(dose.plannedTimeMillis, dose.snoozedUntilMillis, graceMinutes)
    }

    /** End of the day, used as the inclusive upper bound when sweeping a day. */
    fun endOfDayMillis(epochDay: Long): Long =
        DateTimeUtils.startOfDayMillis(epochDay + 1) - 1L

    /** Difference in whole days between two epoch days, positive when [a] is later. */
    fun daysBetween(a: Long, b: Long): Long = a - b

    /** Convenience for the "tomorrow" tab and the reminder pre-arming. */
    fun tomorrow(epochDay: Long): Long = epochDay + 1L

    fun dateOf(epochDay: Long): LocalDate = DateTimeUtils.dateOf(epochDay)

    /** Whole-day percentage used by the progress ring: completed / total, clamped to 0..100. */
    fun completionPercent(completed: Int, total: Int): Int =
        if (total <= 0) 0 else ((completed.toDouble() / total.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
}
