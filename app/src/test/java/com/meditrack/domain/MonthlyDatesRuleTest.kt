package com.meditrack.domain

import com.google.common.truth.Truth.assertThat
import com.meditrack.data.local.entity.RepeatRuleType
import com.meditrack.data.local.entity.RepeatingRule
import org.junit.Test
import java.time.LocalDate

/**
 * "每月几号" - the monthly repeat rule the user asked for ("规定几号几号吃").
 *
 * The interesting part is what happens in months that are shorter than the selected day. Silently
 * skipping a dose in February would be the worst possible behaviour for a medication reminder, so
 * the rule clamps to the last day of the month instead. These tests pin that decision.
 */
class MonthlyDatesRuleTest {

    private fun epochDay(year: Int, month: Int, day: Int) = LocalDate.of(year, month, day).toEpochDay()

    private fun monthly(vararg days: Int) =
        RepeatingRule(type = RepeatRuleType.MONTHLY_DATES, daysOfMonth = days.toSet())

    @Test
    fun `fires on the selected days and only those`() {
        val rule = monthly(1, 15)

        assertThat(rule.matches(epochDay(2026, 3, 1), 0L)).isTrue()
        assertThat(rule.matches(epochDay(2026, 3, 15), 0L)).isTrue()
        assertThat(rule.matches(epochDay(2026, 3, 2), 0L)).isFalse()
        assertThat(rule.matches(epochDay(2026, 3, 14), 0L)).isFalse()
        assertThat(rule.matches(epochDay(2026, 3, 16), 0L)).isFalse()
    }

    @Test
    fun `repeats every month in the same year`() {
        val rule = monthly(10)

        for (month in 1..12) {
            assertThat(rule.matches(epochDay(2026, month, 10), 0L)).isTrue()
        }
    }

    @Test
    fun `day 31 clamps to the last day of a short month`() {
        val rule = monthly(31)

        // Months with 31 days fire on the 31st.
        assertThat(rule.matches(epochDay(2026, 1, 31), 0L)).isTrue()
        assertThat(rule.matches(epochDay(2026, 3, 31), 0L)).isTrue()
        // April has 30: the 30th stands in for the 31st rather than the dose being lost.
        assertThat(rule.matches(epochDay(2026, 4, 30), 0L)).isTrue()
        assertThat(rule.matches(epochDay(2026, 4, 29), 0L)).isFalse()
    }

    @Test
    fun `day 31 lands on the 28th in a non-leap February`() {
        val rule = monthly(31)

        // 2026 is not a leap year.
        assertThat(rule.matches(epochDay(2026, 2, 28), 0L)).isTrue()
        assertThat(rule.matches(epochDay(2026, 2, 27), 0L)).isFalse()
    }

    @Test
    fun `day 31 lands on the 29th in a leap February`() {
        val rule = monthly(31)

        // 2028 is a leap year, so the "last day" is the 29th.
        assertThat(rule.matches(epochDay(2028, 2, 29), 0L)).isTrue()
        assertThat(rule.matches(epochDay(2028, 2, 28), 0L)).isFalse()
    }

    @Test
    fun `day 30 also clamps in February`() {
        val rule = monthly(30)

        assertThat(rule.matches(epochDay(2026, 2, 28), 0L)).isTrue()
        assertThat(rule.matches(epochDay(2026, 4, 30), 0L)).isTrue()
    }

    @Test
    fun `a full month day and the last day can both be selected without double firing`() {
        // 30 and 31 collapse into a single day in February; the predicate must still return true
        // exactly once for that day (it is a boolean, so the real requirement is that it does not
        // error and does report the day).
        val rule = monthly(30, 31)

        assertThat(rule.matches(epochDay(2026, 2, 28), 0L)).isTrue()
        assertThat(rule.matches(epochDay(2026, 4, 30), 0L)).isTrue()
        assertThat(rule.matches(epochDay(2026, 5, 30), 0L)).isTrue()
        assertThat(rule.matches(epochDay(2026, 5, 31), 0L)).isTrue()
    }

    @Test
    fun `an empty selection never fires`() {
        val rule = RepeatingRule(type = RepeatRuleType.MONTHLY_DATES, daysOfMonth = emptySet())

        assertThat(rule.matches(epochDay(2026, 3, 1), 0L)).isFalse()
        assertThat(rule.matches(epochDay(2026, 3, 15), 0L)).isFalse()
    }

    @Test
    fun `describe lists the chosen days`() {
        assertThat(monthly(1).describe()).isEqualTo("每月 1 号")
        assertThat(monthly(1, 15).describe()).isEqualTo("每月 1、15 号")
        assertThat(monthly(31).describe()).isEqualTo("每月 31 号")
    }

    @Test
    fun `describe reports an unconfigured selection instead of claiming every day`() {
        val rule = RepeatingRule(type = RepeatRuleType.MONTHLY_DATES, daysOfMonth = emptySet())

        assertThat(rule.describe()).contains("未选择")
    }

    @Test
    fun `the monthly rule ignores the slot start day that the interval rules depend on`() {
        // A monthly rule is absolute (it is about the calendar), so a slot that starts mid-month
        // must still fire on its chosen day. The screen's start-date filter handles "not yet".
        val rule = monthly(5)
        val startDay = epochDay(2026, 3, 20)

        assertThat(rule.matches(epochDay(2026, 4, 5), startDay)).isTrue()
    }
}
