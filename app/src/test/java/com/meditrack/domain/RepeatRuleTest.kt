package com.meditrack.domain

import com.google.common.truth.Truth.assertThat
import com.meditrack.data.local.entity.RepeatRuleType
import com.meditrack.data.local.entity.RepeatingRule
import org.junit.Test

/**
 * The repetition engine is the piece of logic most likely to be subtly wrong, and a wrong schedule
 * means a missed dose. These tests pin the exact semantics of every rule type.
 *
 * Epoch days are used directly rather than real dates so the tests do not depend on the timezone the
 * suite happens to run in.
 */
class RepeatRuleTest {

    // 2024-01-01 is epoch day 19723; 19723 % 7 == 1, i.e. a Monday.
    private val monday = 19723L

    @Test
    fun `daily fires on every day including the start day`() {
        val rule = RepeatingRule(type = RepeatRuleType.DAILY)

        assertThat(rule.matches(monday, monday)).isTrue()
        assertThat(rule.matches(monday + 1, monday)).isTrue()
        assertThat(rule.matches(monday + 365, monday)).isTrue()
    }

    @Test
    fun `daily never fires before the slot starts`() {
        val rule = RepeatingRule(type = RepeatRuleType.DAILY)

        assertThat(rule.matches(monday - 1, monday)).isFalse()
        assertThat(rule.matches(monday - 30, monday)).isFalse()
    }

    @Test
    fun `every other day alternates from the start day`() {
        val rule = RepeatingRule(type = RepeatRuleType.EVERY_OTHER_DAY)

        assertThat(rule.matches(monday, monday)).isTrue()
        assertThat(rule.matches(monday + 1, monday)).isFalse()
        assertThat(rule.matches(monday + 2, monday)).isTrue()
        assertThat(rule.matches(monday + 3, monday)).isFalse()
        assertThat(rule.matches(monday + 10, monday)).isTrue()
    }

    @Test
    fun `every n days respects the interval and the anchor`() {
        val rule = RepeatingRule(type = RepeatRuleType.EVERY_N_DAYS, intervalDays = 3)

        assertThat(rule.matches(monday, monday)).isTrue()
        assertThat(rule.matches(monday + 1, monday)).isFalse()
        assertThat(rule.matches(monday + 2, monday)).isFalse()
        assertThat(rule.matches(monday + 3, monday)).isTrue()
        assertThat(rule.matches(monday + 6, monday)).isTrue()
    }

    @Test
    fun `every n days with a zero interval degrades to daily instead of dividing by zero`() {
        // A corrupted or hand-edited backup could carry intervalDays = 0.
        val rule = RepeatingRule(type = RepeatRuleType.EVERY_N_DAYS, intervalDays = 0)

        assertThat(rule.matches(monday, monday)).isTrue()
        assertThat(rule.matches(monday + 1, monday)).isTrue()
    }

    @Test
    fun `weekly fires only on the selected iso days`() {
        // Monday (iso 1) and Wednesday (iso 3).
        val rule = RepeatingRule(type = RepeatRuleType.WEEKLY, daysOfWeek = setOf(1, 3))

        assertThat(rule.matches(monday, monday)).isTrue()          // Monday
        assertThat(rule.matches(monday + 1, monday)).isFalse()     // Tuesday
        assertThat(rule.matches(monday + 2, monday)).isTrue()      // Wednesday
        assertThat(rule.matches(monday + 3, monday)).isFalse()     // Thursday
        assertThat(rule.matches(monday + 7, monday)).isTrue()      // next Monday
    }

    @Test
    fun `weekly with sunday selected fires on iso day seven`() {
        val rule = RepeatingRule(type = RepeatRuleType.WEEKLY, daysOfWeek = setOf(7))

        // epoch day 19729 is the Sunday of the same week.
        assertThat(rule.matches(monday + 6, monday)).isTrue()
        assertThat(rule.matches(monday, monday)).isFalse()
    }

    @Test
    fun `cycle takes for the on-days then pauses for the off-days`() {
        // 5 days on, 2 days off -> a 7 day period: day 0..4 on, day 5..6 off.
        val rule = RepeatingRule(
            type = RepeatRuleType.CYCLE,
            cycleOnDays = 5,
            cycleOffDays = 2,
        )

        for (offset in 0..4) {
            assertThat(rule.matches(monday + offset, monday)).isTrue()
        }
        assertThat(rule.matches(monday + 5, monday)).isFalse()
        assertThat(rule.matches(monday + 6, monday)).isFalse()
        // The next cycle starts on day 7.
        assertThat(rule.matches(monday + 7, monday)).isTrue()
        assertThat(rule.matches(monday + 11, monday)).isTrue()
        assertThat(rule.matches(monday + 12, monday)).isFalse()
    }

    @Test
    fun `cycle with no off-days behaves like daily`() {
        val rule = RepeatingRule(type = RepeatRuleType.CYCLE, cycleOnDays = 3, cycleOffDays = 0)

        assertThat(rule.matches(monday, monday)).isTrue()
        assertThat(rule.matches(monday + 1, monday)).isTrue()
        assertThat(rule.matches(monday + 99, monday)).isTrue()
    }

    @Test
    fun `explicit anchor overrides the slot start day`() {
        // A phase carried over from an import: the cycle is anchored 1 day before the slot starts,
        // so the very first day is already the second day of the cycle.
        val rule = RepeatingRule(
            type = RepeatRuleType.EVERY_N_DAYS,
            intervalDays = 2,
            anchorEpochDay = monday - 1,
        )

        assertThat(rule.matches(monday, monday)).isFalse()
        assertThat(rule.matches(monday + 1, monday)).isTrue()
    }

    @Test
    fun `describe produces a readable label for every rule type`() {
        assertThat(RepeatingRule(RepeatRuleType.DAILY).describe()).isEqualTo("每天")
        assertThat(RepeatingRule(RepeatRuleType.EVERY_OTHER_DAY).describe()).isEqualTo("隔天")
        assertThat(
            RepeatingRule(RepeatRuleType.EVERY_N_DAYS, intervalDays = 3).describe()
        ).isEqualTo("每 3 天")
        assertThat(
            RepeatingRule(RepeatRuleType.WEEKLY, daysOfWeek = setOf(1, 3, 5)).describe()
        ).isEqualTo("每周一/三/五")
        assertThat(
            RepeatingRule(RepeatRuleType.CYCLE, cycleOnDays = 5, cycleOffDays = 2).describe()
        ).isEqualTo("吃 5 天停 2 天")
    }

    @Test
    fun `weekly with an empty day set is described as unselected rather than as every day`() {
        // Defensive: an empty set must never be silently interpreted as "all days".
        val rule = RepeatingRule(RepeatRuleType.WEEKLY, daysOfWeek = emptySet())

        assertThat(rule.describe()).contains("未选择")
        assertThat(rule.matches(monday, monday)).isFalse()
    }
}
