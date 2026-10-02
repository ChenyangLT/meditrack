package com.meditrack.data

import com.google.common.truth.Truth.assertThat
import com.meditrack.data.local.Converters
import com.meditrack.data.local.entity.RepeatRuleType
import com.meditrack.data.local.entity.RepeatingRule
import org.junit.Test

/**
 * The repeat rule is stored as a compact pipe-delimited string. That makes the schedules table
 * readable in any SQLite browser, but it also means the encoding is a **data format** with rows
 * already on users' phones - so both directions and, critically, backward compatibility with rows
 * written by the previous release, have to be pinned down.
 */
class ConvertersTest {

    private val converters = Converters()

    @Test
    fun `a daily rule round-trips`() {
        val rule = RepeatingRule(type = RepeatRuleType.DAILY)

        assertThat(converters.stringToRule(converters.ruleToString(rule))).isEqualTo(rule)
    }

    @Test
    fun `a weekly rule round-trips with its days sorted`() {
        val rule = RepeatingRule(type = RepeatRuleType.WEEKLY, daysOfWeek = setOf(5, 1, 3))

        val encoded = converters.ruleToString(rule)
        assertThat(encoded).contains("1,3,5")
        assertThat(converters.stringToRule(encoded)).isEqualTo(rule)
    }

    @Test
    fun `a monthly rule round-trips`() {
        val rule = RepeatingRule(
            type = RepeatRuleType.MONTHLY_DATES,
            daysOfMonth = setOf(15, 1, 31),
        )

        val encoded = converters.ruleToString(rule)
        assertThat(encoded).contains("1,15,31")
        assertThat(converters.stringToRule(encoded)).isEqualTo(rule)
    }

    @Test
    fun `a cycle rule round-trips with its anchor`() {
        val rule = RepeatingRule(
            type = RepeatRuleType.CYCLE,
            cycleOnDays = 5,
            cycleOffDays = 2,
            anchorEpochDay = 19723L,
        )

        assertThat(converters.stringToRule(converters.ruleToString(rule))).isEqualTo(rule)
    }

    /**
     * The format gained a trailing `daysOfMonth` field after the first release. Rows written before
     * that have six fields and must still parse, with the new field defaulting to "no monthly days".
     */
    @Test
    fun `a version 1 row with six fields still parses`() {
        // Exactly what the previous release wrote for a weekly Monday/Wednesday/Friday rule.
        val v1 = "WEEKLY|1|1,3,5|1|0|19723"

        val rule = converters.stringToRule(v1)

        assertThat(rule.type).isEqualTo(RepeatRuleType.WEEKLY)
        assertThat(rule.daysOfWeek).containsExactly(1, 3, 5)
        assertThat(rule.anchorEpochDay).isEqualTo(19723L)
        // The field that did not exist yet degrades to empty rather than throwing or inventing data.
        assertThat(rule.daysOfMonth).isEmpty()
    }

    @Test
    fun `a version 2 row carries the monthly days`() {
        val v2 = "MONTHLY_DATES|1||1|0|0|1,15"

        val rule = converters.stringToRule(v2)

        assertThat(rule.type).isEqualTo(RepeatRuleType.MONTHLY_DATES)
        assertThat(rule.daysOfMonth).containsExactly(1, 15)
    }

    @Test
    fun `out of range day numbers are discarded rather than stored`() {
        // A hand-edited or corrupted backup must not produce a rule that can never match.
        val rule = converters.stringToRule("MONTHLY_DATES|1||1|0|0|0,5,32,99,-1")

        assertThat(rule.daysOfMonth).containsExactly(5)
    }

    @Test
    fun `garbage input degrades to a safe default instead of crashing`() {
        assertThat(converters.stringToRule("").type).isEqualTo(RepeatRuleType.DAILY)
        assertThat(converters.stringToRule("nonsense").type).isEqualTo(RepeatRuleType.DAILY)
        assertThat(converters.stringToRule("NOT_A_TYPE|1||1|0|0").type).isEqualTo(RepeatRuleType.DAILY)
    }

    @Test
    fun `an unknown day-of-week number is ignored`() {
        // 0 and 8 are outside the ISO 1..7 range.
        val rule = converters.stringToRule("WEEKLY|1|0,3,8|1|0|0")

        assertThat(rule.daysOfWeek).containsExactly(3)
    }
}
