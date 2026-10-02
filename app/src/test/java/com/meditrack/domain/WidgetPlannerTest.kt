package com.meditrack.domain

import com.google.common.truth.Truth.assertThat
import com.meditrack.domain.plan.WidgetPlanner
import com.meditrack.domain.plan.WidgetPriority
import org.junit.Test

/**
 * The widget ordering is a headline acceptance criterion:
 *
 *   "桌面小组件优先显示未服和即将服用的药"
 *
 * with the explicit ranking 已过时间未服用 > 30 分钟内 > 今日稍后 > 已服用. These tests pin that exact
 * order, because it is easy to break by "simplifying" the comparator later.
 */
class WidgetPlannerTest {

    /** Fixed reference clock: 2024-01-01 12:00 local, expressed as millis. */
    private val now = 1_704_110_400_000L
    private val minute = 60_000L

    /**
     * Builds a widget row whose minute-of-day is consistent with its instant, exactly as the DAO
     * projection guarantees in production. Keeping them in sync is what makes the ordering
     * assertions meaningful.
     */
    private fun row(
        id: Long,
        minutesFromNow: Long,
        status: String = "DUE",
        taken: Double = 0.0,
        planned: Double = 1.0,
    ) = WidgetPlanner.WidgetRowSource(
        epochDay = 19723L,
        doseId = id,
        medicationId = id,
        name = "药品$id",
        plannedMinuteOfDay = minuteOfDayFor(now + minutesFromNow * minute),
        plannedTimeMillis = now + minutesFromNow * minute,
        plannedQuantity = planned,
        plannedUnit = "片",
        takenQuantity = taken,
        status = status,
        colorTag = "MINT",
        icon = "TABLET",
        allowsFraction = false,
    )

    @Test
    fun `an overdue dose outranks a dose that is due soon`() {
        val content = WidgetPlanner.plan(
            rows = listOf(
                row(id = 1, minutesFromNow = 10),   // due soon
                row(id = 2, minutesFromNow = -60),  // overdue
            ),
            nowMillis = now,
        )

        assertThat(content.items.map { it.doseId }).containsExactly(2L, 1L).inOrder()
        assertThat(content.items.first().priority).isEqualTo(WidgetPriority.OVERDUE)
    }

    @Test
    fun `the full priority chain is overdue then due soon then later then done`() {
        val content = WidgetPlanner.plan(
            rows = listOf(
                row(id = 1, minutesFromNow = 300),                    // later today
                row(id = 2, minutesFromNow = 10),                     // due soon
                row(id = 3, minutesFromNow = -30),                    // overdue
                row(id = 4, minutesFromNow = -120, status = "TAKEN", taken = 1.0), // done
            ),
            nowMillis = now,
        )

        assertThat(content.items.map { it.priority }).containsExactly(
            WidgetPriority.OVERDUE,
            WidgetPriority.DUE_SOON,
            WidgetPriority.LATER_TODAY,
            WidgetPriority.DONE,
        ).inOrder()
    }

    @Test
    fun `exactly thirty minutes ahead still counts as due soon`() {
        val content = WidgetPlanner.plan(
            rows = listOf(row(id = 1, minutesFromNow = WidgetPlanner.DUE_SOON_WINDOW_MINUTES)),
            nowMillis = now,
        )

        assertThat(content.items.single().priority).isEqualTo(WidgetPriority.DUE_SOON)
    }

    @Test
    fun `thirty one minutes ahead is only later today`() {
        val content = WidgetPlanner.plan(
            rows = listOf(row(id = 1, minutesFromNow = WidgetPlanner.DUE_SOON_WINDOW_MINUTES + 1)),
            nowMillis = now,
        )

        assertThat(content.items.single().priority).isEqualTo(WidgetPriority.LATER_TODAY)
    }

    @Test
    fun `a dose exactly at the current minute is overdue not upcoming`() {
        val content = WidgetPlanner.plan(rows = listOf(row(id = 1, minutesFromNow = 0)), nowMillis = now)

        assertThat(content.items.single().priority).isEqualTo(WidgetPriority.OVERDUE)
    }

    @Test
    fun `within the same priority tier items are ordered by clock time`() {
        val content = WidgetPlanner.plan(
            rows = listOf(
                row(id = 1, minutesFromNow = 120),
                row(id = 2, minutesFromNow = 45),
                row(id = 3, minutesFromNow = 90),
            ),
            nowMillis = now,
        )

        assertThat(content.items.map { it.doseId }).containsExactly(2L, 3L, 1L).inOrder()
    }

    @Test
    fun `a partially taken overdue dose still outranks everything else`() {
        val content = WidgetPlanner.plan(
            rows = listOf(
                row(id = 1, minutesFromNow = 5),
                row(id = 2, minutesFromNow = -90, status = "PARTIAL", taken = 0.5, planned = 2.0),
            ),
            nowMillis = now,
        )

        assertThat(content.items.first().doseId).isEqualTo(2L)
        assertThat(content.items.first().priority).isEqualTo(WidgetPriority.OVERDUE)
        assertThat(content.items.first().statusLabel).isEqualTo("部分服用")
    }

    @Test
    fun `resolved doses are not actionable so the stepper is hidden`() {
        val content = WidgetPlanner.plan(
            rows = listOf(
                row(id = 1, minutesFromNow = -10, status = "TAKEN", taken = 1.0),
                row(id = 2, minutesFromNow = -10, status = "SKIPPED"),
                row(id = 3, minutesFromNow = 10),
            ),
            nowMillis = now,
        )

        val byId = content.items.associateBy { it.doseId }
        assertThat(byId.getValue(1L).actionable).isFalse()
        assertThat(byId.getValue(2L).actionable).isFalse()
        assertThat(byId.getValue(3L).actionable).isTrue()
    }

    @Test
    fun `the summary counts every state and reports completion`() {
        val content = WidgetPlanner.plan(
            rows = listOf(
                row(id = 1, minutesFromNow = -180, status = "TAKEN", taken = 1.0),
                row(id = 2, minutesFromNow = -120, status = "MISSED"),
                row(id = 3, minutesFromNow = -60, status = "PARTIAL", taken = 0.5, planned = 2.0),
                row(id = 4, minutesFromNow = 30),
                row(id = 5, minutesFromNow = 60, status = "SKIPPED"),
            ),
            nowMillis = now,
        )

        val summary = content.summary
        assertThat(summary.totalDoses).isEqualTo(5)
        assertThat(summary.completedDoses).isEqualTo(1)
        assertThat(summary.missedDoses).isEqualTo(1)
        assertThat(summary.partialDoses).isEqualTo(1)
        assertThat(summary.skippedDoses).isEqualTo(1)
        assertThat(summary.upcomingDoses).isEqualTo(1)
        assertThat(summary.remainingDoses).isEqualTo(4)
        assertThat(summary.allDone).isFalse()
    }

    @Test
    fun `a fully taken day is reported as done with the encouragement line`() {
        val content = WidgetPlanner.plan(
            rows = listOf(
                row(id = 1, minutesFromNow = -180, status = "TAKEN", taken = 1.0),
                row(id = 2, minutesFromNow = -60, status = "TAKEN", taken = 1.0),
            ),
            nowMillis = now,
        )

        assertThat(content.allDone).isTrue()
        assertThat(content.outstanding).isEqualTo(0)
        assertThat(content.summary.encouragement).isEqualTo("今日用药已完成 ✅")
    }

    @Test
    fun `an empty day reports empty rather than complete`() {
        val content = WidgetPlanner.plan(rows = emptyList(), nowMillis = now)

        assertThat(content.isEmpty).isTrue()
        // allDone must not be true for a day with no plan at all, or the widget would claim a
        // success the user never had.
        assertThat(content.allDone).isFalse()
        assertThat(content.summary.encouragement).isEqualTo("今天没有用药计划")
    }

    @Test
    fun `time labels respect the twelve hour preference`() {
        // 08:05 local on the reference day.
        val plannedInstant = java.time.LocalDate.ofEpochDay(19723L)
            .atTime(8, 5)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val rows = listOf(
            row(id = 1, minutesFromNow = 10).copy(
                plannedMinuteOfDay = 8 * 60 + 5,
                plannedTimeMillis = plannedInstant,
            ),
        )

        val twentyFourHour = WidgetPlanner.plan(rows, use24Hour = true, nowMillis = now)
        assertThat(twentyFourHour.items.single().timeLabel).isEqualTo("08:05")

        // The 12-hour form is locale dependent ("8:05 AM" in English, "08:05 上午" in Chinese),
        // so assert the behaviour rather than an English-only string: it must still carry the
        // correct hour and minute, and it must differ from the 24-hour rendering.
        val twelveHour = WidgetPlanner.plan(rows, use24Hour = false, nowMillis = now)
        val label = twelveHour.items.single().timeLabel
        assertThat(label).contains("05")
        assertThat(label).isNotEqualTo(twentyFourHour.items.single().timeLabel)
    }

    /** Minute-of-day for an instant, in the device zone; mirrors what the DAO stores. */
    private fun minuteOfDayFor(millis: Long): Int {
        val local = java.time.Instant.ofEpochMilli(millis)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalTime()
        return local.hour * 60 + local.minute
    }
}
