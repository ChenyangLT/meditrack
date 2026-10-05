package com.meditrack.widget

import com.google.common.truth.Truth.assertThat
import com.meditrack.data.local.entity.ReviewCountMode
import com.meditrack.domain.plan.WidgetPlanner
import com.meditrack.domain.review.ReviewProgress
import org.junit.Test

/**
 * The two rules the widget's «复查» line follows.
 *
 * The widget is the one surface a user sees without opening anything, so it is also the one where an
 * unhelpful line is most expensive: a permanent "复查：还差 47 次" would crowd out the dose information
 * the tile exists for, and a missing "该复查了" would hide the one thing the feature was built to say.
 * Both rules are pure, so both are pinned here rather than checked by looking at a home screen.
 */
class WidgetReviewResolverTest {

    private fun progress(
        count: Double,
        threshold: Double,
        mode: ReviewCountMode = ReviewCountMode.DOSES,
        unit: String = "次",
    ) = ReviewProgress(mode = mode, count = count, threshold = threshold, unitShort = unit)

    // ------------------------------------------------------------- what is hidden

    @Test
    fun `nothing is drawn when no review is configured`() {
        val label = WidgetReviewResolver.labelFor(
            progress = progress(count = 0.0, threshold = 0.0),
            advanceNotice = 3,
            reminderEnabled = true,
        )

        assertThat(label).isNull()
    }

    @Test
    fun `nothing is drawn far from the threshold`() {
        // 47 doses to go is not news, and a permanent countdown on the home screen is noise.
        val label = WidgetReviewResolver.labelFor(
            progress = progress(count = 13.0, threshold = 60.0),
            advanceNotice = 3,
            reminderEnabled = true,
        )

        assertThat(label).isNull()
    }

    @Test
    fun `nothing is drawn when the advance notice is switched off`() {
        val label = WidgetReviewResolver.labelFor(
            progress = progress(count = 59.0, threshold = 60.0),
            advanceNotice = 0,
            reminderEnabled = true,
        )

        assertThat(label).isNull()
    }

    // -------------------------------------------------------------- what is drawn

    @Test
    fun `the countdown appears inside the advance window`() {
        val label = WidgetReviewResolver.labelFor(
            progress = progress(count = 57.0, threshold = 60.0),
            advanceNotice = 3,
            reminderEnabled = true,
        )

        assertThat(label).isNotNull()
        assertThat(label!!.label).isEqualTo("复查：还差 3 次")
        assertThat(label.isDue).isFalse()
    }

    @Test
    fun `the due state replaces the countdown and is marked as a warning`() {
        val label = WidgetReviewResolver.labelFor(
            progress = progress(count = 60.0, threshold = 60.0),
            advanceNotice = 3,
            reminderEnabled = true,
        )

        // Never both: seeing "还差 0 次" next to "该复查了" reads as the app having lost count.
        assertThat(label).isNotNull()
        assertThat(label!!.label).isEqualTo("该复查了")
        assertThat(label.isDue).isTrue()
    }

    @Test
    fun `a switched-off reminder still shows its countdown`() {
        // Off means "stop telling me", not "hide the number": a user who silenced the review notice is still
        // served by knowing how far along they are.
        val label = WidgetReviewResolver.labelFor(
            progress = progress(count = 58.0, threshold = 60.0),
            advanceNotice = 3,
            reminderEnabled = false,
        )

        assertThat(label).isNotNull()
        assertThat(label!!.label).isEqualTo("复查：还差 2 次（已关闭提醒）")
        assertThat(label.isDue).isFalse()
    }

    @Test
    fun `a day-based round labels itself in days`() {
        val label = WidgetReviewResolver.labelFor(
            progress = progress(
                mode = ReviewCountMode.DAYS,
                count = 88.0,
                threshold = 90.0,
                unit = "天",
            ),
            advanceNotice = 3,
            reminderEnabled = true,
        )

        assertThat(label!!.label).isEqualTo("复查：还差 2 天")
    }

    // ------------------------------------------------------------- planner plumbing

    @Test
    fun `the review line reaches the row model`() {
        val row = WidgetPlanner.WidgetRowSource(
            epochDay = 20_000L,
            doseId = 7L,
            medicationId = 3L,
            name = "阿司匹林",
            plannedMinuteOfDay = 8 * 60,
            plannedTimeMillis = 0L,
            plannedQuantity = 1.0,
            plannedUnit = "片",
            takenQuantity = 0.0,
            status = "UPCOMING",
            colorTag = "MINT",
            icon = "TABLET",
            allowsFraction = false,
            review = com.meditrack.domain.plan.WidgetReview(label = "复查：还差 3 次", isDue = false),
        )

        val item = row.toItem(use24Hour = true, nowMillis = 0L)

        assertThat(item.reviewLabel).isEqualTo("复查：还差 3 次")
        assertThat(item.reviewDue).isFalse()
    }

    @Test
    fun `a row with no review carries no review line`() {
        val row = WidgetPlanner.WidgetRowSource(
            epochDay = 20_000L,
            doseId = 7L,
            medicationId = 3L,
            name = "阿司匹林",
            plannedMinuteOfDay = 8 * 60,
            plannedTimeMillis = 0L,
            plannedQuantity = 1.0,
            plannedUnit = "片",
            takenQuantity = 0.0,
            status = "UPCOMING",
            colorTag = "MINT",
            icon = "TABLET",
            allowsFraction = false,
        )

        val item = row.toItem(use24Hour = true, nowMillis = 0L)

        assertThat(item.reviewLabel).isNull()
        assertThat(item.reviewDue).isFalse()
    }

    @Test
    fun `the review line is part of the widget fingerprint`() {
        // Without this the guard-service ticker would not repaint the tile when a round crosses its
        // threshold, so "该复查了" could be missing from the home screen indefinitely.
        val content = WidgetPlanner.plan(
            rows = listOf(
                WidgetPlanner.WidgetRowSource(
                    epochDay = 20_000L,
                    doseId = 7L,
                    medicationId = 3L,
                    name = "阿司匹林",
                    plannedMinuteOfDay = 8 * 60,
                    plannedTimeMillis = 0L,
                    plannedQuantity = 1.0,
                    plannedUnit = "片",
                    takenQuantity = 0.0,
                    status = "UPCOMING",
                    colorTag = "MINT",
                    icon = "TABLET",
                    allowsFraction = false,
                    review = com.meditrack.domain.plan.WidgetReview("复查：还差 1 次", isDue = false),
                )
            ),
            use24Hour = true,
            nowMillis = 0L,
        )

        val due = content.copy(
            items = content.items.map {
                it.copy(
                    reviewLabel = "该复查了",
                    reviewDue = true,
                )
            }
        )

        assertThat(content.fingerprint()).isNotEqualTo(due.fingerprint())
    }
}
