package com.meditrack.domain.plan

import com.meditrack.core.util.DateTimeUtils
import com.meditrack.core.util.QuantityFormatter

/**
 * How strongly a dose competes for the limited space on the home screen.
 *
 * ## The ordering is a product decision, not an urgency ranking
 *
 * The order is **即将服用 → 未服药 → 今日稍后 → 已服用 → 已跳过**, carried by three colours:
 * yellow, red, green.
 *
 * Note that this deliberately puts *upcoming* doses above *overdue* ones. A widget glanced at on the
 * way out of the door is most useful when it answers "what do I need to take soon"; an overdue dose
 * has already lost its moment, so it stays prominent in red one line down rather than occupying the
 * top slot.
 */
enum class WidgetPriority(val rank: Int, val label: String) {
    /** Within [WidgetPlanner.DUE_SOON_WINDOW_MINUTES] of its time - yellow, first on the tile. */
    DUE_SOON(0, "即将服用"),

    /** Its time has passed with nothing (or only part) recorded - red. */
    MISSED(1, "未服药"),

    /** Later today and still open - neutral, after the two that need attention. */
    LATER_TODAY(2, "今日稍后"),

    /** Recorded as taken - green. */
    TAKEN(3, "已服用"),

    /** Deliberately skipped - muted, and never worth space above anything else. */
    SKIPPED(4, "已跳过"),
    ;

    /** True for the doses the "显示已服用" switch hides: actioned, nothing left to do. */
    val isCompleted: Boolean get() = this == TAKEN || this == SKIPPED
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

    /**
     * The payload minus the doses the user asked not to see.
     *
     * Lives here rather than in each caller because two of them build this payload independently -
     * the widget's own content builder and the repository - and they must agree exactly about what
     * "hide completed" means, or the tile would flicker between two different lists.
     */
    fun visible(showCompleted: Boolean): WidgetContent =
        if (showCompleted) this else copy(items = items.filterNot { it.priority.isCompleted })

    /**
     * A cheap value that changes exactly when what the widget *shows* changes.
     *
     * The refresh interval can be as short as ten seconds, and redrawing a RemoteViews into the
     * launcher's process that often would be wasteful for no visible benefit: nothing on this tile
     * ticks. Comparing this fingerprint first means a short interval costs one small database read
     * and buys promptness, while the expensive redraw still happens only when a dose actually moves
     * between states.
     */
    fun fingerprint(): String = buildString {
        append(epochDay).append('|')
        append(summary.completedDoses).append('/').append(summary.totalDoses).append('|')
        for (item in items) {
            append(item.doseId).append(':')
                .append(item.priority.rank).append(':')
                .append(item.statusLabel).append(':')
                .append(item.timeLabel).append(':')
                .append(item.quantityLabel).append(':')
                .append(item.name).append(';')
        }
    }
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

    /**
     * Classifies one dose; visible for unit tests, the widget path goes through [plan].
     *
     * @param statusLabel the stored [com.meditrack.data.local.entity.DoseStatus] name
     */
    fun priorityOf(
        statusLabel: String,
        plannedTimeMillis: Long,
        nowMillis: Long,
    ): WidgetPriority = when (statusLabel) {
        "TAKEN" -> WidgetPriority.TAKEN
        "SKIPPED" -> WidgetPriority.SKIPPED
        // Already recorded as a miss: nothing left to do, but it must stay visible in red rather
        // than being swept into the "completed" bucket where the hide-completed switch would eat it.
        "MISSED" -> WidgetPriority.MISSED
        else -> when {
            // Its time has passed with nothing (or only part) recorded - red.
            plannedTimeMillis <= nowMillis -> WidgetPriority.MISSED
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
            return WidgetDoseItem(
                doseId = doseId,
                medicationId = medicationId,
                name = name,
                timeLabel = DateTimeUtils.formatMinuteOfDay(plannedMinuteOfDay, use24Hour),
                quantityLabel = QuantityFormatter.formatProgress(
                    takenQuantity, plannedQuantity, plannedUnit
                ),
                statusLabel = when (status) {
                    // The stored status is the most specific answer, so it wins where it exists.
                    "TAKEN" -> "已服用"
                    "SKIPPED" -> "已跳过"
                    "MISSED" -> "未服药"
                    // A partly-taken dose keeps its own wording: "未服药" would be a lie, and it is
                    // the one label that tells the user something is still outstanding.
                    "PARTIAL" -> "部分服用"
                    else -> when (priority) {
                        // Past its time with nothing recorded - the "到了时间没吃" case the widget
                        // must shout about, using the same wording as the app.
                        WidgetPriority.MISSED -> "未服药"
                        WidgetPriority.DUE_SOON -> "即将服用"
                        WidgetPriority.LATER_TODAY -> "稍后"
                        // Unreachable: the branches above cover every completed status.
                        WidgetPriority.TAKEN -> "已服用"
                        WidgetPriority.SKIPPED -> "已跳过"
                    }
                },
                priority = priority,
                plannedTimeMillis = plannedTimeMillis,
                plannedMinuteOfDay = plannedMinuteOfDay,
                colorTagName = colorTag,
                iconName = icon,
                allowsFraction = allowsFraction,
                epochDay = epochDay,
            )
        }
    }
}
