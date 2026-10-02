package com.meditrack.data.local.entity

import java.time.DayOfWeek

/**
 * Value object describing how a [Schedule] repeats.
 *
 * It is embedded in the schedule row rather than stored as a JSON blob so that queries stay
 * type-safe and Room can index the individual columns if needed later.
 *
 * @param type which repetition strategy applies
 * @param intervalDays N for [RepeatRuleType.EVERY_N_DAYS] (>= 1)
 * @param daysOfWeek ISO day numbers (1 = Monday .. 7 = Sunday) for [RepeatRuleType.WEEKLY]
 * @param daysOfMonth day numbers (1..31) for [RepeatRuleType.MONTHLY_DATES]
 * @param cycleOnDays X for [RepeatRuleType.CYCLE] - consecutive days of taking
 * @param cycleOffDays Y for [RepeatRuleType.CYCLE] - consecutive days of pause
 * @param anchorEpochDay day 0 of the cycle, normally the schedule start date. Required for
 *        EVERY_N_DAYS and CYCLE so that the phase survives app restarts.
 */
data class RepeatingRule(
    val type: RepeatRuleType = RepeatRuleType.DAILY,
    val intervalDays: Int = 1,
    val daysOfWeek: Set<Int> = emptySet(),
    val daysOfMonth: Set<Int> = emptySet(),
    val cycleOnDays: Int = 1,
    val cycleOffDays: Int = 0,
    val anchorEpochDay: Long = 0L,
) {

    /**
     * Pure date predicate: does this rule produce a dose on [epochDay] for a slot that began on
     * [slotStartEpochDay]?
     *
     * The anchor is the *slot's* start day rather than [anchorEpochDay] whenever the caller knows
     * it, so "每 3 天" counts from the day the user created the schedule. [anchorEpochDay] is used
     * as a fallback for imports that carry an explicit phase.
     *
     * No Android or java.time-timezone dependency: epoch days are zone free, so this is unit
     * testable without a device.
     */
    fun matches(epochDay: Long, slotStartEpochDay: Long): Boolean {
        val anchor = if (anchorEpochDay != 0L) anchorEpochDay else slotStartEpochDay
        // Days elapsed since the anchor; negative before the rule starts.
        val elapsed = epochDay - anchor
        if (elapsed < 0) return false
        return when (type) {
            RepeatRuleType.DAILY -> true
            RepeatRuleType.EVERY_OTHER_DAY -> elapsed % 2L == 0L
            RepeatRuleType.EVERY_N_DAYS -> elapsed % intervalDays.coerceAtLeast(1).toLong() == 0L
            RepeatRuleType.WEEKLY -> daysOfWeek.contains(java.time.LocalDate.ofEpochDay(epochDay).dayOfWeek.value)
            RepeatRuleType.MONTHLY_DATES -> matchesDayOfMonth(epochDay)
            RepeatRuleType.CYCLE -> {
                val on = cycleOnDays.coerceAtLeast(1).toLong()
                val off = cycleOffDays.coerceAtLeast(0).toLong()
                val period = on + off
                if (period <= 0L) true else elapsed % period < on
            }
        }
    }

    /**
     * True when [epochDay] falls on one of the selected days of the month.
     *
     * **Clamping rule:** if the month is shorter than the selected day (31 in February, 30 in
     * February, 31 in April), the dose fires on that month's last day instead of being skipped.
     * Silently dropping a monthly prescription in February would be the worst possible behaviour
     * for a medication reminder, so "the 31st" is read as "the last day" in short months.
     *
     * A selection of 31 is therefore distinct from 30 only in months that actually have 31 days.
     */
    private fun matchesDayOfMonth(epochDay: Long): Boolean {
        if (daysOfMonth.isEmpty()) return false
        val date = java.time.LocalDate.ofEpochDay(epochDay)
        val day = date.dayOfMonth
        val lastDay = date.lengthOfMonth()
        return daysOfMonth.any { selected ->
            val clamped = selected.coerceAtMost(lastDay)
            day == clamped
        }
    }

    /** Human readable summary, e.g. "每周一/三/五" or "吃 5 天停 2 天". */
    fun describe(): String = when (type) {
        RepeatRuleType.DAILY -> "每天"
        RepeatRuleType.EVERY_OTHER_DAY -> "隔天"
        RepeatRuleType.EVERY_N_DAYS -> "每 ${intervalDays.coerceAtLeast(1)} 天"
        RepeatRuleType.WEEKLY -> {
            val names = listOf("一", "二", "三", "四", "五", "六", "日")
            val picked = daysOfWeek.sorted().mapNotNull { d -> names.getOrNull(d - 1) }
            if (picked.isEmpty()) "每周（未选择）" else "每周" + picked.joinToString("/")
        }
        RepeatRuleType.MONTHLY_DATES -> {
            val picked = daysOfMonth.sorted()
            if (picked.isEmpty()) {
                "每月（未选择）"
            } else {
                // "每月 1、15 号" reads better than a slash-joined list for day numbers.
                "每月 " + picked.joinToString("、") + " 号"
            }
        }
        RepeatRuleType.CYCLE ->
            "吃 ${cycleOnDays.coerceAtLeast(1)} 天停 ${cycleOffDays.coerceAtLeast(0)} 天"
    }

    companion object {
        fun weekly(days: Set<Int>): RepeatingRule =
            RepeatingRule(type = RepeatRuleType.WEEKLY, daysOfWeek = days)

        fun everyNDays(n: Int, anchorEpochDay: Long): RepeatingRule =
            RepeatingRule(
                type = RepeatRuleType.EVERY_N_DAYS,
                intervalDays = n.coerceAtLeast(1),
                anchorEpochDay = anchorEpochDay,
            )

        fun cycle(onDays: Int, offDays: Int, anchorEpochDay: Long): RepeatingRule =
            RepeatingRule(
                type = RepeatRuleType.CYCLE,
                cycleOnDays = onDays.coerceAtLeast(1),
                cycleOffDays = offDays.coerceAtLeast(0),
                anchorEpochDay = anchorEpochDay,
            )

        fun monthlyDates(days: Set<Int>): RepeatingRule =
            RepeatingRule(type = RepeatRuleType.MONTHLY_DATES, daysOfMonth = days)

        /** ISO day numbers for Monday..Sunday, used to build the weekday picker. */
        val ALL_WEEKDAYS: List<Int> = DayOfWeek.entries.map { it.value }

        /** Selectable day-of-month numbers (1..31) for the monthly picker. */
        val ALL_MONTH_DAYS: List<Int> = (1..31).toList()
    }
}
