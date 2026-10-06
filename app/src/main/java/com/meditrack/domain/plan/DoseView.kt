package com.meditrack.domain.plan

import com.meditrack.core.util.DateTimeUtils
import com.meditrack.core.util.QuantityFormatter
import com.meditrack.data.local.entity.DoseStatus
import com.meditrack.data.local.entity.Medication
import com.meditrack.data.local.entity.Schedule

/**
 * Everything the today list, the detail sheet and the widget need in order to render one dose.
 *
 * The UI never touches Room entities directly: it reads this flattened, already-formatted view
 * model. That keeps Compose recomposition cheap (no lazy formatting inside `@Composable`) and makes
 * the widget able to reuse the exact same ordering and labels as the app.
 */
data class DoseView(
    /** `dose_logs.id` once the row exists, 0 while it is still only planned. */
    val doseId: Long,
    val medicationId: Long,
    val scheduleId: Long,
    val epochDay: Long,

    val medicationName: String,
    val medication: Medication,
    val schedule: Schedule?,

    val plannedMinuteOfDay: Int,
    val plannedTimeMillis: Long,
    val plannedQuantity: Double,
    val unitLabel: String,
    val takenQuantity: Double,

    val status: DoseStatus,
    val snoozedUntilMillis: Long?,
    val snoozeCount: Int,
    val overDoseConfirmed: Boolean,

    /** Wall-clock label, e.g. "08:00". */
    val timeLabel: String,
    /** "1 / 2 片". */
    val progressLabel: String,
    /** "1 片" - the prescribed amount, shown when nothing has been taken yet. */
    val plannedLabel: String,
    /** Contextual hint under the title, e.g. "还有 20 分钟" / "已过 45 分钟". */
    val timingHint: String,

    /** The clock this view was built against; [isOverdue] is derived from it. */
    val nowMillis: Long,
) {

    val remainingQuantity: Double get() = (plannedQuantity - takenQuantity).coerceAtLeast(0.0)

    /** True once the stored row exists, i.e. the dose can be persisted against. */
    val isPersisted: Boolean get() = doseId != 0L

    val isComplete: Boolean get() = status == DoseStatus.TAKEN
    val isPartial: Boolean get() = status == DoseStatus.PARTIAL
    val isSkippedOrMissed: Boolean get() = status == DoseStatus.SKIPPED || status == DoseStatus.MISSED

    /**
     * True when the scheduled time has passed and nothing has been recorded.
     *
     * This is the "该吃但没吃" state the UI paints red as 未服药. It deliberately *leads* the stored
     * status: a dose looks late the moment its time passes, rather than waiting for the grace-period
     * sweep to relabel it MISSED. A partial dose is not overdue - the user did something, and the
     * chip already reads 部分服用.
     */
    val isOverdue: Boolean
        get() = status == DoseStatus.DUE && plannedTimeMillis < nowMillis

    /** Step used by the "+" / "-" buttons: 0.5 for liquids, 1 for countable forms. */
    val step: Double get() = QuantityFormatter.stepFor(medication.unit.allowsFraction)

    /** True when the next "+" would exceed the configured maximum and must be confirmed. */
    val requiresOverDoseConfirmation: Boolean
        get() {
            val max = medication.maxDoseAmount
            if (max <= 0.0 || overDoseConfirmed) return false
            return takenQuantity + step > max + QuantityFormatter.EPSILON
        }

    companion object {
        /**
         * Flattens a planned dose (and its stored row, when present) into a render ready model.
         *
         * @param nowMillis reference clock; injected so previews and tests are deterministic
         */
        fun of(
            planned: PlannedDose,
            medication: Medication,
            schedule: Schedule?,
            stored: com.meditrack.data.local.entity.DoseLog?,
            nowMillis: Long = System.currentTimeMillis(),
            use24Hour: Boolean = true,
        ): DoseView {
            val taken = stored?.takenQuantity ?: 0.0
            val snoozedUntil = stored?.snoozedUntilMillis
            // Not `stored.status`: a row materialised before its time still says UPCOMING, and
            // trusting it made an already-reminded, overdue dose render as 「未到时间」. See
            // [DayPlanner.displayStatus].
            val status = DayPlanner.displayStatus(
                stored = stored?.status,
                plannedTimeMillis = planned.plannedTimeMillis,
                takenQuantity = taken,
                plannedQuantity = planned.plannedQuantity,
                isSkipped = stored?.status == DoseStatus.SKIPPED,
                snoozedUntilMillis = snoozedUntil,
                nowMillis = nowMillis,
            )
            return DoseView(
                doseId = stored?.id ?: 0L,
                medicationId = planned.medicationId,
                scheduleId = planned.scheduleId,
                epochDay = planned.epochDay,
                medicationName = medication.name,
                medication = medication,
                schedule = schedule,
                plannedMinuteOfDay = planned.minuteOfDay,
                plannedTimeMillis = planned.plannedTimeMillis,
                plannedQuantity = planned.plannedQuantity,
                unitLabel = planned.unitLabel,
                takenQuantity = taken,
                status = status,
                snoozedUntilMillis = snoozedUntil,
                snoozeCount = stored?.snoozeCount ?: 0,
                overDoseConfirmed = stored?.overDoseConfirmed ?: false,
                timeLabel = DateTimeUtils.formatMinuteOfDay(planned.minuteOfDay, use24Hour),
                progressLabel = QuantityFormatter.formatProgress(
                    taken, planned.plannedQuantity, planned.unitLabel
                ),
                plannedLabel = QuantityFormatter.format(planned.plannedQuantity, planned.unitLabel),
                nowMillis = nowMillis,
                timingHint = hintFor(
                    plannedTimeMillis = planned.plannedTimeMillis,
                    plannedQuantity = planned.plannedQuantity,
                    takenQuantity = taken,
                    unitLabel = planned.unitLabel,
                    status = status,
                    snoozedUntilMillis = snoozedUntil,
                    nowMillis = nowMillis,
                ),
            )
        }

        /**
         * One short line under the medication name. Kept here (rather than in the composable) so
         * the today list, the detail sheet and the widget all phrase the same state identically.
         */
        /**
         * One short line under the medication name.
         *
         * ## It says only what the chip does not
         *
         * An earlier version prefixed the state - "已完成 · 已过 149 分钟", "未服药 · 已过 149 分钟" -
         * which the status chip two lines below already states, in a colour and with an icon. The
         * repetition cost the line its width: at the largest text scale the phrase no longer fitted,
         * and the only place it *could* break was inside the unit word, producing "已过 149 分" /
         * "钟". Dropping the prefix leaves the fact the user actually came for - how long ago - and
         * fits on one line at every scale the app offers.
         */
        private fun hintFor(
            plannedTimeMillis: Long,
            plannedQuantity: Double,
            takenQuantity: Double,
            unitLabel: String,
            status: DoseStatus,
            snoozedUntilMillis: Long?,
            nowMillis: Long,
        ): String = when {
            status == DoseStatus.SKIPPED -> "已跳过"
            snoozedUntilMillis != null && nowMillis < snoozedUntilMillis ->
                "稍后提醒至 " + DateTimeUtils.formatDateTime(snoozedUntilMillis).takeLast(5)
            status == DoseStatus.PARTIAL -> {
                val remaining = (plannedQuantity - takenQuantity).coerceAtLeast(0.0)
                // Non-breaking again: "还差 1 片" must not become "还差 1" / "片".
                "还差 " + QuantityFormatter.format(remaining, unitLabel)
                    .replace(" ", DateTimeUtils.NBSP)
            }
            // Taken, missed, due and not-yet-due all want the same sentence: how far the scheduled
            // time is from now. The chip supplies the state.
            else -> DateTimeUtils.relativeLabel(plannedTimeMillis, nowMillis)
        }
    }
}
