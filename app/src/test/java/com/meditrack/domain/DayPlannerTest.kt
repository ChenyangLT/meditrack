package com.meditrack.domain

import com.google.common.truth.Truth.assertThat
import com.meditrack.data.local.entity.DoseLog
import com.meditrack.data.local.entity.DoseStatus
import com.meditrack.domain.plan.DayPlanner
import org.junit.Test

/**
 * Status derivation is the rule the acceptance criteria spell out:
 *
 *   "达到计划数量后，自动判断今天已吃；低于计划显示部分；为 0 显示未吃"
 *
 * plus the time dimension (未到时间 / 待服用 / 漏服). These tests cover the whole matrix.
 */
class DayPlannerTest {

    private val now = 1_704_110_400_000L
    private val minute = 60_000L

    @Test
    fun `a dose in the future is upcoming`() {
        val status = DayPlanner.deriveStatus(
            plannedTimeMillis = now + 60 * minute,
            takenQuantity = 0.0,
            plannedQuantity = 1.0,
            nowMillis = now,
        )

        assertThat(status).isEqualTo(DoseStatus.UPCOMING)
    }

    @Test
    fun `a dose whose time has passed with nothing recorded is due`() {
        val status = DayPlanner.deriveStatus(
            plannedTimeMillis = now - minute,
            takenQuantity = 0.0,
            plannedQuantity = 1.0,
            nowMillis = now,
        )

        assertThat(status).isEqualTo(DoseStatus.DUE)
    }

    @Test
    fun `a partially taken dose is partial even before its scheduled time`() {
        val status = DayPlanner.deriveStatus(
            plannedTimeMillis = now + 60 * minute,
            takenQuantity = 0.5,
            plannedQuantity = 1.0,
            nowMillis = now,
        )

        assertThat(status).isEqualTo(DoseStatus.PARTIAL)
    }

    @Test
    fun `reaching the planned amount is taken even before the scheduled time`() {
        val status = DayPlanner.deriveStatus(
            plannedTimeMillis = now + 60 * minute,
            takenQuantity = 1.0,
            plannedQuantity = 1.0,
            nowMillis = now,
        )

        assertThat(status).isEqualTo(DoseStatus.TAKEN)
    }

    @Test
    fun `exceeding the planned amount is still taken`() {
        val status = DayPlanner.deriveStatus(
            plannedTimeMillis = now - minute,
            takenQuantity = 3.0,
            plannedQuantity = 1.0,
            nowMillis = now,
        )

        assertThat(status).isEqualTo(DoseStatus.TAKEN)
    }

    @Test
    fun `an explicit skip wins over everything else`() {
        val status = DayPlanner.deriveStatus(
            plannedTimeMillis = now - minute,
            takenQuantity = 0.0,
            plannedQuantity = 1.0,
            isSkipped = true,
            nowMillis = now,
        )

        assertThat(status).isEqualTo(DoseStatus.SKIPPED)
    }

    @Test
    fun `an active snooze keeps a past-time dose as due rather than missed`() {
        val status = DayPlanner.deriveStatus(
            plannedTimeMillis = now - 60 * minute,
            takenQuantity = 0.0,
            plannedQuantity = 1.0,
            snoozedUntilMillis = now + 5 * minute,
            nowMillis = now,
        )

        assertThat(status).isEqualTo(DoseStatus.DUE)
    }

    @Test
    fun `an expired snooze no longer holds the dose in the due state`() {
        val status = DayPlanner.deriveStatus(
            plannedTimeMillis = now - 60 * minute,
            takenQuantity = 0.0,
            plannedQuantity = 1.0,
            snoozedUntilMillis = now - minute,
            nowMillis = now,
        )

        assertThat(status).isEqualTo(DoseStatus.DUE)
    }

    // ------------------------------------------------------------ missed escalation

    private fun dose(
        plannedTimeMillis: Long,
        takenQuantity: Double = 0.0,
        status: DoseStatus = DoseStatus.DUE,
        snoozedUntilMillis: Long? = null,
    ) = DoseLog(
        id = 1L,
        medicationId = 1L,
        scheduleId = 1L,
        epochDay = 19723L,
        plannedMinuteOfDay = 480,
        plannedTimeMillis = plannedTimeMillis,
        plannedQuantity = 1.0,
        plannedUnit = "片",
        takenQuantity = takenQuantity,
        status = status,
        snoozedUntilMillis = snoozedUntilMillis,
    )

    @Test
    fun `an untouched dose past its grace period escalates to missed`() {
        val shouldEscalate = DayPlanner.shouldEscalateToMissed(
            dose = dose(plannedTimeMillis = now - 45 * minute),
            graceMinutes = 30,
            nowMillis = now,
        )

        assertThat(shouldEscalate).isTrue()
    }

    @Test
    fun `an untouched dose inside its grace period is not yet missed`() {
        val shouldEscalate = DayPlanner.shouldEscalateToMissed(
            dose = dose(plannedTimeMillis = now - 10 * minute),
            graceMinutes = 30,
            nowMillis = now,
        )

        assertThat(shouldEscalate).isFalse()
    }

    @Test
    fun `a partially taken dose is never escalated to missed`() {
        // The user did something; that is not a miss.
        val shouldEscalate = DayPlanner.shouldEscalateToMissed(
            dose = dose(plannedTimeMillis = now - 120 * minute, takenQuantity = 0.5),
            graceMinutes = 30,
            nowMillis = now,
        )

        assertThat(shouldEscalate).isFalse()
    }

    @Test
    fun `an already taken dose is never re-escalated`() {
        val shouldEscalate = DayPlanner.shouldEscalateToMissed(
            dose = dose(
                plannedTimeMillis = now - 120 * minute,
                takenQuantity = 1.0,
                status = DoseStatus.TAKEN,
            ),
            graceMinutes = 30,
            nowMillis = now,
        )

        assertThat(shouldEscalate).isFalse()
    }

    @Test
    fun `an already missed dose is not escalated twice`() {
        val shouldEscalate = DayPlanner.shouldEscalateToMissed(
            dose = dose(plannedTimeMillis = now - 120 * minute, status = DoseStatus.MISSED),
            graceMinutes = 30,
            nowMillis = now,
        )

        assertThat(shouldEscalate).isFalse()
    }

    @Test
    fun `an active snooze extends the deadline before a dose counts as missed`() {
        // Planned two hours ago, but the user snoozed until five minutes ago, so with a 30 minute
        // grace period there are still 25 minutes left.
        val deadline = DayPlanner.missedDeadlineMillis(
            plannedTimeMillis = now - 120 * minute,
            snoozedUntilMillis = now - 5 * minute,
            graceMinutes = 30,
        )

        assertThat(deadline).isEqualTo(now - 5 * minute + 30 * minute)
        assertThat(
            DayPlanner.shouldEscalateToMissed(
                dose = dose(
                    plannedTimeMillis = now - 120 * minute,
                    snoozedUntilMillis = now - 5 * minute,
                ),
                graceMinutes = 30,
                nowMillis = now,
            )
        ).isFalse()
    }

    // ------------------------------------------------------------- progress maths

    @Test
    fun `completion percent is rounded and clamped`() {
        assertThat(DayPlanner.completionPercent(3, 5)).isEqualTo(60)
        assertThat(DayPlanner.completionPercent(0, 5)).isEqualTo(0)
        assertThat(DayPlanner.completionPercent(5, 5)).isEqualTo(100)
        assertThat(DayPlanner.completionPercent(9, 5)).isEqualTo(100)
        // A day with no plan must not divide by zero.
        assertThat(DayPlanner.completionPercent(0, 0)).isEqualTo(0)
    }

    @Test
    fun `planning an empty medication list yields no doses`() {
        val planned = DayPlanner.plan(medications = emptyList(), epochDay = 19723L)

        assertThat(planned).isEmpty()
    }
}
