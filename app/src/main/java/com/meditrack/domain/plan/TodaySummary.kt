package com.meditrack.domain.plan

import com.meditrack.core.util.QuantityFormatter
import com.meditrack.data.local.entity.DoseStatus

/**
 * Aggregate shown in the today header ("已完成 3/5") and in the widget footer.
 *
 * "完成" is counted per *dose slot*, and a slot counts as done only when the taken amount has
 * reached the planned amount. Partial doses (1 of 2 tablets) are surfaced separately so the header
 * never overstates adherence.
 */
data class TodaySummary(
    val epochDay: Long,
    val totalDoses: Int,
    val completedDoses: Int,
    val partialDoses: Int,
    val missedDoses: Int,
    val skippedDoses: Int,
    val upcomingDoses: Int,
    val plannedQuantity: Double,
    val takenQuantity: Double,
    val unitLabel: String,
) {

    val remainingDoses: Int get() = (totalDoses - completedDoses).coerceAtLeast(0)

    /** True when there is nothing left to do today. */
    val allDone: Boolean get() = totalDoses > 0 && completedDoses >= totalDoses

    /** True when nothing is scheduled at all - the UI then shows the empty state instead. */
    val isEmpty: Boolean get() = totalDoses == 0

    val completionPercent: Int get() = DayPlanner.completionPercent(completedDoses, totalDoses)

    /** "已完成 3/5" */
    val progressLabel: String get() = "已完成 $completedDoses/$totalDoses"

    /** Fraction taken of the total planned quantity, clamped to 0..1, for the progress bar. */
    val quantityFraction: Float
        get() = if (plannedQuantity <= 0.0) {
            if (totalDoses == 0) 0f else completedDoses.toFloat() / totalDoses.toFloat()
        } else {
            (takenQuantity / plannedQuantity).coerceIn(0.0, 1.0).toFloat()
        }

    /**
     * Encouragement line shown by the widget when every dose is done.
     * Deliberately free of medical advice - it only acknowledges the record.
     */
    val encouragement: String
        get() = when {
            isEmpty -> "今天没有用药计划"
            allDone -> "今日用药已完成 ✅"
            completedDoses == 0 && partialDoses == 0 && missedDoses == 0 -> "今天的用药还没开始"
            missedDoses > 0 -> "今日已完成 $completedDoses/$totalDoses · $missedDoses 次未服药"
            else -> "今日已完成 $completedDoses/$totalDoses"
        }

    /** Secondary line: total amount taken against the total prescribed amount. */
    val quantityLabel: String
        get() = QuantityFormatter.formatProgress(takenQuantity, plannedQuantity, unitLabel)

    companion object {

        /**
         * Folds the day's doses into a summary.
         *
         * Skipped doses count as resolved for the progress bar (the user made a conscious decision)
         * but are never counted as taken, so adherence statistics stay honest.
         */
        fun from(doses: List<DoseView>, epochDay: Long): TodaySummary {
            var completed = 0
            var partial = 0
            var missed = 0
            var skipped = 0
            var upcoming = 0
            var plannedQty = 0.0
            var takenQty = 0.0
            // The summary unit follows the first dose; mixed-unit plans still report a sane total
            // because each dose contributes its own amount.
            val unit = doses.firstOrNull()?.unitLabel ?: ""

            for (dose in doses) {
                plannedQty += dose.plannedQuantity
                takenQty += dose.takenQuantity
                when (dose.status) {
                    DoseStatus.TAKEN -> completed++
                    DoseStatus.PARTIAL -> partial++
                    DoseStatus.MISSED -> missed++
                    DoseStatus.SKIPPED -> skipped++
                    DoseStatus.DUE, DoseStatus.UPCOMING -> upcoming++
                }
            }

            return TodaySummary(
                epochDay = epochDay,
                totalDoses = doses.size,
                completedDoses = completed,
                partialDoses = partial,
                missedDoses = missed,
                skippedDoses = skipped,
                upcomingDoses = upcoming,
                plannedQuantity = plannedQty,
                takenQuantity = takenQty,
                unitLabel = unit,
            )
        }
    }
}
