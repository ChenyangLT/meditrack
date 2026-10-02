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
    fun `the timing hint names the state in the user's words`() {
        // Recorded miss.
        assertThat(view(now - 120 * minute, DoseStatus.MISSED).timingHint).contains("未服药")
    }
}
