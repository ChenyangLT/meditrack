package com.meditrack.domain.reminder

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The label rules behind "提醒不要精确到秒".
 *
 * Two separate promises are pinned here:
 *
 *  1. **The headline is approximate.** It rounds to five minutes and names the part of the day, so a
 *     reminder reads "早上 8 点左右" rather than "08:00:00" - and two doses three minutes apart do not
 *     read as if the difference mattered.
 *  2. **Nothing is ever *lost* to that rounding.** Rounding is only ever applied to the copy. The
 *     exact stored minute is still available for the detail line, and every scheduling comparison
 *     goes through [ReminderTiming.toMinute], which truncates rather than rounds so an instant can
 *     never be nudged across a boundary.
 */
class ReminderTimingTest {

    // ------------------------------------------------------------------ bands

    @Test
    fun `bands cover the whole day without gaps`() {
        // Every minute of the day must belong to exactly one band; the label is what the user reads.
        for (minute in 0 until ReminderTiming.MINUTES_PER_DAY) {
            assertThat(ReminderTiming.bandOf(minute)).isNotNull()
        }
    }

    @Test
    fun `band boundaries land where the labels say they do`() {
        assertThat(ReminderTiming.bandOf(0)).isEqualTo(DayBand.LATE_NIGHT)
        assertThat(ReminderTiming.bandOf(4 * 60 + 59)).isEqualTo(DayBand.LATE_NIGHT)
        assertThat(ReminderTiming.bandOf(5 * 60)).isEqualTo(DayBand.EARLY_MORNING)
        assertThat(ReminderTiming.bandOf(7 * 60)).isEqualTo(DayBand.MORNING)
        assertThat(ReminderTiming.bandOf(9 * 60)).isEqualTo(DayBand.FORENOON)
        assertThat(ReminderTiming.bandOf(11 * 60)).isEqualTo(DayBand.NOON)
        assertThat(ReminderTiming.bandOf(13 * 60)).isEqualTo(DayBand.AFTERNOON)
        assertThat(ReminderTiming.bandOf(17 * 60)).isEqualTo(DayBand.EVENING)
        assertThat(ReminderTiming.bandOf(19 * 60)).isEqualTo(DayBand.NIGHT)
        assertThat(ReminderTiming.bandOf(22 * 60)).isEqualTo(DayBand.LATE_EVENING)
        assertThat(ReminderTiming.bandOf(23 * 60 + 59)).isEqualTo(DayBand.LATE_EVENING)
    }

    @Test
    fun `an out of range minute is clamped rather than throwing`() {
        // The band lookup runs inside a broadcast receiver; an exception here would drop a reminder.
        assertThat(ReminderTiming.bandOf(-1)).isEqualTo(DayBand.LATE_NIGHT)
        assertThat(ReminderTiming.bandOf(99_999)).isEqualTo(DayBand.LATE_EVENING)
    }

    // ------------------------------------------------------ approximate labels

    @Test
    fun `a round hour reads as a friendly approximate time`() {
        assertThat(ReminderTiming.approximateLabel(8 * 60)).isEqualTo("早上 8 点左右")
        assertThat(ReminderTiming.approximateLabel(12 * 60)).isEqualTo("中午 12 点左右")
        assertThat(ReminderTiming.approximateLabel(19 * 60)).isEqualTo("晚上 7 点左右")
    }

    @Test
    fun `afternoon hours use the twelve hour clock a person would say out loud`() {
        assertThat(ReminderTiming.approximateLabel(13 * 60 + 30)).isEqualTo("下午 1 点半左右")
        assertThat(ReminderTiming.approximateLabel(17 * 60 + 45)).isEqualTo("傍晚 5 点 45 分左右")
    }

    @Test
    fun `midnight is twelve, not zero`() {
        assertThat(ReminderTiming.approximateLabel(0)).isEqualTo("凌晨 12 点左右")
        assertThat(ReminderTiming.approximateLabel(30)).isEqualTo("凌晨 12 点半左右")
    }

    @Test
    fun `odd minutes are rounded to five so the schedule does not look like an instrument`() {
        // 08:03 and 08:00 are the same moment to a person, and the copy should say so.
        assertThat(ReminderTiming.approximateLabel(8 * 60 + 2)).isEqualTo("早上 8 点左右")
        assertThat(ReminderTiming.approximateLabel(8 * 60 + 3)).isEqualTo("早上 8 点左右")
        assertThat(ReminderTiming.approximateLabel(8 * 60 + 4)).isEqualTo("早上 8 点左右")
        assertThat(ReminderTiming.approximateLabel(8 * 60 + 7)).isEqualTo("早上 8 点 5 分左右")
    }

    @Test
    fun `rounding never names a time later than the dose`() {
        // Rounding to nearest would turn an 08:58 dose into "上午 9 点左右" - both a later time than
        // the user set, and a different band. For a medication reminder that is the wrong direction
        // to be wrong in, so the rounding only ever goes down.
        assertThat(ReminderTiming.approximateLabel(8 * 60 + 58)).isEqualTo("早上 8 点 55 分左右")
        assertThat(ReminderTiming.bandOf(ReminderTiming.snap(8 * 60 + 58))).isEqualTo(DayBand.MORNING)
    }

    @Test
    fun `rounding never runs off the end of the day`() {
        // 23:58 must not roll over into the next day, and must still land on a five-minute mark.
        assertThat(ReminderTiming.snap(23 * 60 + 58)).isEqualTo(23 * 60 + 55)
        assertThat(ReminderTiming.snap(23 * 60 + 59)).isEqualTo(23 * 60 + 55)
        assertThat(ReminderTiming.approximateLabel(23 * 60 + 58)).isEqualTo("深夜 11 点 55 分左右")
    }

    @Test
    fun `the band is chosen from the snapped minute so the label agrees with itself`() {
        // 06:58 rounds down to 06:55, which is still 清晨 - the label must not say "早上" while
        // printing a 6 o'clock number.
        val label = ReminderTiming.approximateLabel(6 * 60 + 58)
        assertThat(label).isEqualTo("清晨 6 点 55 分左右")
        assertThat(ReminderTiming.bandOf(ReminderTiming.snap(6 * 60 + 58))).isEqualTo(DayBand.EARLY_MORNING)
    }

    @Test
    fun `the exact label keeps the precision the approximate one drops`() {
        assertThat(ReminderTiming.exactLabel(8 * 60 + 3, "08:03")).isEqualTo("早上 08:03")
    }

    // ----------------------------------------------------------- minute precision

    @Test
    fun `instants inside the same minute compare equal`() {
        val base = 1_700_000_000_000L
        assertThat(ReminderTiming.sameMinute(base, base + 999L)).isTrue()
        assertThat(ReminderTiming.sameMinute(base, base + 60_000L)).isFalse()
    }

    @Test
    fun `truncating to a minute never moves an instant forward`() {
        // Truncation, not rounding: a dose must never be treated as due earlier than it is.
        val base = 1_700_000_059_999L
        assertThat(ReminderTiming.toMinute(base)).isEqualTo(1_700_000_040_000L)
        assertThat(ReminderTiming.toMinute(base)).isAtMost(base)
    }

    @Test
    fun `truncation works before the epoch too`() {
        assertThat(ReminderTiming.toMinute(-1L)).isEqualTo(-60_000L)
        assertThat(ReminderTiming.toMinute(-60_000L)).isEqualTo(-60_000L)
    }

    // -------------------------------------------------------------- lateness copy

    @Test
    fun `lateness is described in whole minutes and hours, never in seconds`() {
        assertThat(ReminderTiming.latenessLabel(0L)).isEqualTo("刚刚")
        assertThat(ReminderTiming.latenessLabel(59_000L)).isEqualTo("刚刚")
        assertThat(ReminderTiming.latenessLabel(12 * 60_000L)).isEqualTo("晚了 12 分钟")
        assertThat(ReminderTiming.latenessLabel(125 * 60_000L)).isEqualTo("晚了 2 小时 5 分钟")
    }

    // ------------------------------------------------------------------ window

    @Test
    fun `the shipped window is forgiving at both ends`() {
        val window = ReminderWindow.DEFAULT

        // A reminder may fire a little late without being "late"...
        assertThat(window.freshMinutes).isAtLeast(10)
        // ...and a long way late before it stops being worth a banner at all.
        assertThat(window.staleAfterMillis).isGreaterThan(window.freshMillis)
        // The advance notice exists, and is short enough to be useful.
        assertThat(window.leadMinutes).isIn(1..60)
        // Alarm jitter never costs the user a reminder.
        assertThat(window.earlyToleranceMinutes).isAtLeast(1)
    }

    @Test
    fun `a zero or negative window component degrades safely`() {
        // The user can set these to arbitrary values; none of them may produce a negative duration.
        val window = ReminderWindow(
            leadMinutes = -5,
            earlyToleranceMinutes = -5,
            freshMinutes = -5,
            staleAfterMinutes = -5,
            clusterMinutes = -5,
        )

        assertThat(window.leadMillis).isEqualTo(0L)
        assertThat(window.earlyToleranceMillis).isEqualTo(0L)
        assertThat(window.freshMillis).isEqualTo(0L)
        assertThat(window.staleAfterMillis).isAtLeast(60_000L)
        assertThat(window.clusterMillis).isEqualTo(0L)
    }
}
