package com.meditrack.domain

import com.google.common.truth.Truth.assertThat
import com.meditrack.core.util.DateTimeUtils
import com.meditrack.data.local.entity.DosageForm
import com.meditrack.data.local.entity.DosageUnit
import com.meditrack.data.local.entity.DoseLog
import com.meditrack.data.local.entity.DoseStatus
import com.meditrack.data.local.entity.Medication
import com.meditrack.domain.plan.DoseView
import com.meditrack.domain.plan.PlannedDose
import org.junit.Test

/**
 * "到了时间没吃的显示红色未服药".
 *
 * The subtle requirement is *when* the red state appears. Waiting for the grace-period sweep to
 * relabel the dose MISSED would leave a dose that is already 20 minutes late still showing amber
 * "待服用", which reads as "you still have time" when the truth is that the moment has passed.
 * So [DoseView.isOverdue] leads the stored status - and these tests pin that, plus the cases where
 * it must NOT fire.
 */
class DoseOverdueTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    private val medication = Medication(
        id = 1L,
        name = "阿司匹林",
        dosageForm = DosageForm.TABLET,
        unit = DosageUnit.TABLET,
        doseAmount = 1.0,
    )

    private fun view(
        plannedTimeMillis: Long,
        status: DoseStatus,
        takenQuantity: Double = 0.0,
    ): DoseView = DoseView.of(
        planned = PlannedDose(
            medicationId = 1L,
            scheduleId = 1L,
            epochDay = DateTimeUtils.todayEpochDay(),
            minuteOfDay = 480,
            plannedTimeMillis = plannedTimeMillis,
            plannedQuantity = 1.0,
            unitLabel = "片",
        ),
        medication = medication,
        schedule = null,
        stored = DoseLog(
            id = 1L,
            medicationId = 1L,
            scheduleId = 1L,
            epochDay = DateTimeUtils.todayEpochDay(),
            plannedMinuteOfDay = 480,
            plannedTimeMillis = plannedTimeMillis,
            plannedQuantity = 1.0,
            plannedUnit = "片",
            takenQuantity = takenQuantity,
            status = status,
        ),
        nowMillis = now,
    )

    @Test
    fun `a due dose whose time has passed is overdue`() {
        assertThat(view(now - minute, DoseStatus.DUE).isOverdue).isTrue()
    }

    @Test
    fun `a dose still in the future is not overdue`() {
        assertThat(view(now + 60 * minute, DoseStatus.UPCOMING).isOverdue).isFalse()
    }

    @Test
    fun `a taken dose is never overdue, however late it was`() {
        val v = view(now - 120 * minute, DoseStatus.TAKEN, takenQuantity = 1.0)

        assertThat(v.isOverdue).isFalse()
        assertThat(v.isComplete).isTrue()
    }

    @Test
    fun `a partially taken dose is not overdue - the user did something`() {
        val v = view(now - 120 * minute, DoseStatus.PARTIAL, takenQuantity = 0.5)

        assertThat(v.isOverdue).isFalse()
        assertThat(v.isPartial).isTrue()
    }

    @Test
    fun `a skipped dose is not overdue`() {
        assertThat(view(now - 120 * minute, DoseStatus.SKIPPED).isOverdue).isFalse()
    }

    /**
     * A swept MISSED dose is already red through its own status, so the flag stays false - the UI
     * must not need two different reasons to paint the same thing.
     */
    @Test
    fun `an already-recorded missed dose reports overdue false because its status says so`() {
        val v = view(now - 120 * minute, DoseStatus.MISSED)

        assertThat(v.isOverdue).isFalse()
        assertThat(v.isSkippedOrMissed).isTrue()
    }

    @Test
    fun `the timing hint carries the time, not a restatement of the chip`() {
        // The status chip already says 未服药 / 已服用 in a colour and with an icon. Repeating it in
        // the hint cost the line the width that kept the unit word on one line at the largest text
        // scale, where the only break opportunity left was inside "分钟".
        val missed = view(now - 45 * minute, DoseStatus.MISSED)

        assertThat(missed.timingHint).isEqualTo("已过 45\u00A0分\u2060钟")
        assertThat(missed.timingHint).doesNotContain("未服药")
        assertThat(missed.status).isEqualTo(DoseStatus.MISSED)
    }

    @Test
    fun `a count can never be separated from its unit by a line break`() {
        // U+00A0 keeps "120" and "分钟" together; U+2060 (word joiner) keeps 分 and 钟 together,
        // because Chinese text would otherwise break between any two ideographs.
        val label = DateTimeUtils.relativeLabel(now - 45 * minute, now)

        assertThat(label).contains("45 分⁠钟")
        assertThat(label).doesNotContain("45 分钟")   // a plain space here would be breakable
        assertThat(label).doesNotContain("分钟")       // ...and the joiner is actually present
    }

    @Test
    fun `a long gap is told in hours, not in hundreds of minutes`() {
        // "已过 1005 分钟" is a number the reader has to divide before it means anything.
        // Written with escapes rather than literal characters: an invisible WORD JOINER in a source
        // file is a trap for the next person to edit this.
        assertThat(DateTimeUtils.relativeLabel(now - 151 * minute, now))
            .isEqualTo("已过 2\u00A0小\u2060时 31\u00A0分\u2060钟")
        assertThat(DateTimeUtils.relativeLabel(now - 16 * 60 * minute, now))
            .isEqualTo("已过 16\u00A0小\u2060时")
        assertThat(DateTimeUtils.relativeLabel(now - 3 * 24 * 60 * minute, now))
            .isEqualTo("已过 3\u00A0\u2060天")
    }

    @Test
    fun `an upcoming dose says how long is left, in one unbreakable phrase`() {
        val label = DateTimeUtils.relativeLabel(now + 20 * minute, now)
        assertThat(label).contains("还有 20 分⁠钟")
    }

    // ------------------------------------------- the stale-stored-status regression
    //
    // Measured on a real device: at 22:54 a dose due at 22:49 - whose reminder had already been
    // posted - still rendered as 「未到时间」, directly beside that card's own "已过 5 分钟". Rows are
    // written with the status the clock implied when they were *created*, and nothing rewrites them
    // until the grace-period sweep runs, so the card was lying for the entire window in between.

    @Test
    fun `a row materialised before its time is overdue once the clock passes it`() {
        val v = view(now - 5 * minute, DoseStatus.UPCOMING)

        assertThat(v.status).isEqualTo(DoseStatus.DUE)
        assertThat(v.isOverdue).isTrue()
        assertThat(v.timingHint).doesNotContain("未到时间")
    }

    @Test
    fun `a row still in the future keeps reading as not yet due`() {
        val v = view(now + 30 * minute, DoseStatus.UPCOMING)

        assertThat(v.status).isEqualTo(DoseStatus.UPCOMING)
        assertThat(v.isOverdue).isFalse()
    }

    @Test
    fun `the clock never overrules what the user recorded`() {
        // Every one of these is a statement about the user, and must survive any clock.
        assertThat(view(now + 60 * minute, DoseStatus.TAKEN, takenQuantity = 1.0).status)
            .isEqualTo(DoseStatus.TAKEN)
        assertThat(view(now - 60 * minute, DoseStatus.PARTIAL, takenQuantity = 0.5).status)
            .isEqualTo(DoseStatus.PARTIAL)
        assertThat(view(now - 60 * minute, DoseStatus.SKIPPED).status)
            .isEqualTo(DoseStatus.SKIPPED)
        assertThat(view(now - 60 * minute, DoseStatus.MISSED).status)
            .isEqualTo(DoseStatus.MISSED)
    }

    @Test
    fun `an early-recorded dose stays recorded, never "not yet due"`() {
        // Taking a dose before its time is still taking it: 07:30 for an 08:00 dose reads 已服用.
        val v = view(now + 30 * minute, DoseStatus.PARTIAL, takenQuantity = 0.5)
        assertThat(v.status).isEqualTo(DoseStatus.PARTIAL)
        assertThat(v.isPartial).isTrue()
    }
}
