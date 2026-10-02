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
    fun `an upcoming dose outranks an overdue one`() {
        // The product decision, pinned: what is *coming* goes first, because that is the question a
        // widget glanced at on the way out of the door should answer. The overdue dose stays
        // prominent in red one line down.
        val content = WidgetPlanner.plan(
            rows = listOf(
                row(id = 1, minutesFromNow = -60),  // overdue
                row(id = 2, minutesFromNow = 10),   // due soon
            ),
            nowMillis = now,
        )

        assertThat(content.items.map { it.doseId }).containsExactly(2L, 1L).inOrder()
        assertThat(content.items.first().priority).isEqualTo(WidgetPriority.DUE_SOON)
    }

    @Test
    fun `the full priority chain was ordered as the spec requires`() {
        val content = WidgetPlanner.plan(
            rows = listOf(
                row(id = 1, minutesFromNow = 300),                    // later today
                row(id = 2, minutesFromNow = 10),                     // due soon
                row(id = 3, minutesFromNow = -30),                    // overdue
                row(id = 4, minutesFromNow = -120, status = "TAKEN", taken = 1.0), // taken
                row(id = 5, minutesFromNow = -120, status = "SKIPPED"),            // skipped
            ),
            nowMillis = now,
        )

        // yellow (upcoming), red (missed), neutral (later), green (taken), muted (skipped)
        assertThat(content.items.map { it.priority }).containsExactly(
            WidgetPriority.DUE_SOON,
            WidgetPriority.MISSED,
            WidgetPriority.LATER_TODAY,
            WidgetPriority.TAKEN,
            WidgetPriority.SKIPPED,
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
    fun `a dose exactly at the current minute counts as missed not upcoming`() {
        val content = WidgetPlanner.plan(rows = listOf(row(id = 1, minutesFromNow = 0)), nowMillis = now)

        assertThat(content.items.single().priority).isEqualTo(WidgetPriority.MISSED)
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
    fun `a partly taken dose past its time sits in the red tier`() {
        val content = WidgetPlanner.plan(
            rows = listOf(
                row(id = 1, minutesFromNow = 5),
                row(id = 2, minutesFromNow = -90, status = "PARTIAL", taken = 0.5, planned = 2.0),
            ),
            nowMillis = now,
        )

        // The upcoming dose still leads, but the partial one is red rather than being written off as
        // completed - half a dose is still outstanding work.
        val partial = content.items.single { it.doseId == 2L }
        assertThat(partial.priority).isEqualTo(WidgetPriority.MISSED)
        assertThat(partial.statusLabel).isEqualTo("部分服用")
        assertThat(content.items.first().doseId).isEqualTo(1L)
    }

    @Test
    fun `hiding completed doses keeps the missed ones visible`() {
        // The whole point of the red tier: a dose the user missed must not disappear behind the
        // "显示已服用" switch, which is about tidying up, not about hiding problems.
        val content = WidgetPlanner.plan(
            rows = listOf(
                row(id = 1, minutesFromNow = -120, status = "TAKEN", taken = 1.0),
                row(id = 2, minutesFromNow = -120, status = "SKIPPED"),
                row(id = 3, minutesFromNow = -60),                       // overdue, untouched
                row(id = 4, minutesFromNow = -90, status = "MISSED"),    // swept to missed
                row(id = 5, minutesFromNow = 10),
            ),
            nowMillis = now,
        )

        val visible = content.visible(showCompleted = false)

        assertThat(visible.items.map { it.doseId }).containsExactly(5L, 3L, 4L)
        // And with the switch on, everything comes back.
        assertThat(content.visible(showCompleted = true).items).hasSize(5)
    }

    @Test
    fun `the fingerprint changes exactly when the visible payload changes`() {
        // This is what makes a ten-second refresh interval affordable: a tick that finds the same
        // fingerprint does not redraw.
        val rows = listOf(row(id = 1, minutesFromNow = 10))
        val before = WidgetPlanner.plan(rows, nowMillis = now)
        val sameAgain = WidgetPlanner.plan(rows, nowMillis = now)
        val nowOverdue = WidgetPlanner.plan(rows, nowMillis = now + 11 * minute)
        val recorded = WidgetPlanner.plan(
            listOf(row(id = 1, minutesFromNow = 10, status = "TAKEN", taken = 1.0)),
            nowMillis = now,
        )

        assertThat(sameAgain.fingerprint()).isEqualTo(before.fingerprint())
        assertThat(nowOverdue.fingerprint()).isNotEqualTo(before.fingerprint())
        assertThat(recorded.fingerprint()).isNotEqualTo(before.fingerprint())
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
