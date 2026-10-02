package com.meditrack.domain.plan

import com.meditrack.core.util.DateTimeUtils
import com.meditrack.core.util.QuantityFormatter

/**
 * How strongly a dose competes for the limited space on the home screen.
 *
 * The ordering is the core product requirement: a dose the user has already missed is more urgent
 * than one that is merely coming up, so it must never be pushed below the fold by later doses.
 */
enum class WidgetPriority(val rank: Int, val label: String) {
    /** Scheduled time already passed and nothing recorded - red. */
    OVERDUE(0, "已过时间"),

    /** Within the next 30 minutes - orange. */
    DUE_SOON(1, "即将服用"),

    /** Later today, still open - neutral/blue. */
    LATER_TODAY(2, "今日稍后"),

    /** Taken, skipped or missed - green/grey, shown last. */
    DONE(3, "已完成"),
}

/**
 * A single widget row: flattened, already-formatted information ready for the Glance composable.
 *
 * Widget rendering happens on the launcher side with a tight budget, so every value here is a
 * primitive or a string that can go straight into a `Text` without further lookups.
 *
 * Note that the planner deliberately produces the *whole* day and leaves "how much of it fits" to
 * [com.meditrack.widget.WidgetSizing]. Row budgets used to live here as three constants for three
 * fixed sizes, which meant the domain layer had an opinion about the home screen's geometry - and
 * that opinion was wrong, because the row count depends on the tile's real height, not on which
 * footprint it was created from.
 */
data class WidgetDoseItem(
    val doseId: Long,
    val medicationId: Long,
    val name: String,
    val timeLabel: String,
    /** "1 / 2 片" progress, or the planned amount when untouched. */
    val quantityLabel: String,
    val statusLabel: String,
    val priority: WidgetPriority,
    val plannedTimeMillis: Long,
    val plannedMinuteOfDay: Int,
    /** Colour tag name of the medication, resolved to an ARGB by the widget theme. */
    val colorTagName: String,
    val iconName: String,
    /** True when the quick +/- actions should be offered for this row. */
    val actionable: Boolean,
    /** Step applied by the widget quick action buttons. */
    val step: Double,
    val allowsFraction: Boolean,
    val epochDay: Long,
)

/** The full payload the widget renders for one refresh. */
data class WidgetContent(
    val epochDay: Long,
    val items: List<WidgetDoseItem>,
    val summary: TodaySummary,
) {
    val allDone: Boolean get() = summary.allDone
    val isEmpty: Boolean get() = summary.isEmpty

    /** Open work only - what the "还有 N 项" badge shows. */
    val outstanding: Int get() = summary.remainingDoses
}

/**
 * Turns stored dose rows into the ordered widget payload.
 *
 * Pure and free of Android types, so the exact ordering the product spec asks for can be unit
 * tested without inflating a Glance host.
 */
object WidgetPlanner {

    /** A dose scheduled within this many minutes is promoted to [WidgetPriority.DUE_SOON]. */
    const val DUE_SOON_WINDOW_MINUTES = 30L

    /**
     * @param rows raw widget rows for the day
     * @param use24Hour reflects the user's time-format preference
     * @param nowMillis injected clock, so the 30 minute window is testable
     */
    fun plan(
        rows: List<WidgetRowSource>,
        use24Hour: Boolean = true,
        nowMillis: Long = System.currentTimeMillis(),
    ): WidgetContent {
        val epochDay = rows.firstOrNull()?.epochDay ?: DateTimeUtils.todayEpochDay()
        val items = rows
            .map { row -> row.toItem(use24Hour, nowMillis) }
            // Sort on the absolute instant rather than the minute-of-day: within one day the two
            // normally agree, but the instant is what the reminder actually fires on, so it is the
            // authoritative ordering key. doseId breaks ties deterministically.
            .sortedWith(compareBy({ it.priority.rank }, { it.plannedTimeMillis }, { it.doseId }))
        return WidgetContent(
            epochDay = epochDay,
            items = items,
            summary = summarize(rows, epochDay),
        )
    }

    /** Classifies one dose; visible for unit tests, the widget path goes through [plan]. */
    fun priorityOf(
        statusLabel: String,
        plannedTimeMillis: Long,
        nowMillis: Long,
    ): WidgetPriority = when (statusLabel) {
        "TAKEN", "SKIPPED", "MISSED" -> WidgetPriority.DONE
        else -> when {
            // Already past its time with nothing recorded: highest priority.
            plannedTimeMillis <= nowMillis -> WidgetPriority.OVERDUE
            DateTimeUtils.minutesUntil(plannedTimeMillis, nowMillis) <= DUE_SOON_WINDOW_MINUTES ->
                WidgetPriority.DUE_SOON
            else -> WidgetPriority.LATER_TODAY
        }
    }

    /** True when the medication has been taken today (used by the history dot on the widget). */
    fun isResolved(statusLabel: String): Boolean =
        statusLabel == "TAKEN" || statusLabel == "SKIPPED" || statusLabel == "MISSED"

    private fun summarize(rows: List<WidgetRowSource>, epochDay: Long): TodaySummary {
        var completed = 0
        var partial = 0
        var missed = 0
        var skipped = 0
        var upcoming = 0
        var plannedQty = 0.0
        var takenQty = 0.0
        for (row in rows) {
            plannedQty += row.plannedQuantity
            takenQty += row.takenQuantity
            when (row.status) {
                "TAKEN" -> completed++
                "PARTIAL" -> partial++
                "MISSED" -> missed++
                "SKIPPED" -> skipped++
                else -> upcoming++
            }
        }
        return TodaySummary(
            epochDay = epochDay,
            totalDoses = rows.size,
            completedDoses = completed,
            partialDoses = partial,
            missedDoses = missed,
            skippedDoses = skipped,
            upcomingDoses = upcoming,
            plannedQuantity = plannedQty,
            takenQuantity = takenQty,
            unitLabel = rows.firstOrNull()?.plannedUnit ?: "",
        )
    }

    /**
     * Source row the planner consumes. The repository maps
     * [com.meditrack.data.local.dao.WidgetDoseRow] to this so the domain stays Room-free.
     */
    data class WidgetRowSource(
        val epochDay: Long,
        val doseId: Long,
        val medicationId: Long,
        val name: String,
        val plannedMinuteOfDay: Int,
        val plannedTimeMillis: Long,
        val plannedQuantity: Double,
        val plannedUnit: String,
        val takenQuantity: Double,
        val status: String,
        val colorTag: String,
        val icon: String,
        val allowsFraction: Boolean,
    ) {
        fun toItem(use24Hour: Boolean, nowMillis: Long): WidgetDoseItem {
            val priority = priorityOf(status, plannedTimeMillis, nowMillis)
            val resolved = isResolved(status)
            return WidgetDoseItem(
                doseId = doseId,
                medicationId = medicationId,
                name = name,
                timeLabel = DateTimeUtils.formatMinuteOfDay(plannedMinuteOfDay, use24Hour),
                quantityLabel = QuantityFormatter.formatProgress(
                    takenQuantity, plannedQuantity, plannedUnit
                ),
                statusLabel = when (priority) {
                    // An overdue, untouched dose is the "到了时间没吃" case the widget must
                    // shout about, so it uses the same wording as the app.
                    WidgetPriority.OVERDUE -> if (status == "PARTIAL") "部分服用" else "未服药"
                    WidgetPriority.DUE_SOON -> "即将服用"
                    WidgetPriority.LATER_TODAY -> "稍后"
                    WidgetPriority.DONE -> when (status) {
                        "TAKEN" -> "已服用"
                        "SKIPPED" -> "已跳过"
                        else -> "未服药"
                    }
                },
                priority = priority,
                plannedTimeMillis = plannedTimeMillis,
                plannedMinuteOfDay = plannedMinuteOfDay,
                colorTagName = colorTag,
                iconName = icon,
                actionable = !resolved,
                step = QuantityFormatter.stepFor(allowsFraction),
                allowsFraction = allowsFraction,
                epochDay = epochDay,
            )
        }
    }
}
