package com.meditrack.core.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * All date/time maths goes through this object.
 *
 * Design notes:
 *  - A "dose day" is a [java.time.LocalDate] on the device's current time zone. It is persisted as an
 *    epoch-day (days since 1970-01-01) so that midnight, DST shifts and month boundaries can never
 *    produce an off-by-one error, which a naive `(millis / 86_400_000)` calculation would.
 *  - Schedule times are stored as "minutes from local midnight" for the same reason.
 *  - The zone is always read from [ZoneId.systemDefault] at call time, so a traveller or a DST change
 *    re-interprets stored wall-clock times the way a user expects ("08:00 is still breakfast").
 */
object DateTimeUtils {

    /** Formatter used for the 24h time shown in lists, widgets and notifications. */
    private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

    /** 12h formatter used when the user prefers AM/PM. */
    private val HH_MM_12: DateTimeFormatter = DateTimeFormatter.ofPattern("hh:mm a", Locale.getDefault())

    fun zone(): ZoneId = ZoneId.systemDefault()

    fun today(): LocalDate = LocalDate.now(zone())

    fun todayEpochDay(): Long = today().toEpochDay()

    fun epochDayOf(date: LocalDate): Long = date.toEpochDay()

    fun dateOf(epochDay: Long): LocalDate = LocalDate.ofEpochDay(epochDay)

    /** Start of the given day as an epoch-millisecond instant. */
    fun startOfDayMillis(epochDay: Long): Long =
        dateOf(epochDay).atStartOfDay(zone()).toInstant().toEpochMilli()

    /** Epoch-millisecond instant for a "minutes from midnight" wall clock on a given epoch-day. */
    fun millisAt(epochDay: Long, minuteOfDay: Int): Long =
        dateOf(epochDay).atTime(minutesToLocalTime(minuteOfDay)).atZone(zone()).toInstant().toEpochMilli()

    fun minutesToLocalTime(minuteOfDay: Int): LocalTime =
        LocalTime.of((minuteOfDay / 60).coerceIn(0, 23), (minuteOfDay % 60).coerceIn(0, 59))

    fun localTimeToMinutes(time: LocalTime): Int = time.hour * 60 + time.minute

    /** Splits a stored minute-of-day into an hour/minute pair. */
    fun hourOf(minuteOfDay: Int): Int = (minuteOfDay / 60).coerceIn(0, 23)

    fun minuteOf(minuteOfDay: Int): Int = (minuteOfDay % 60).coerceIn(0, 59)

    fun formatMinuteOfDay(minuteOfDay: Int, use24Hour: Boolean = true): String =
        minutesToLocalTime(minuteOfDay).format(if (use24Hour) HH_MM else HH_MM_12)

    fun formatLocalTime(time: LocalTime, use24Hour: Boolean = true): String =
        time.format(if (use24Hour) HH_MM else HH_MM_12)

    /** "今天 / 明天 / 昨天 / 3月5日" style label used by the today screen header. */
    fun friendlyDateLabel(epochDay: Long): String {
        val today = todayEpochDay()
        return when (epochDay - today) {
            0L -> "今天"
            1L -> "明天"
            -1L -> "昨天"
            else -> {
                val d = dateOf(epochDay)
                "${d.monthValue}月${d.dayOfMonth}日"
            }
        }
    }

    fun formatDate(epochDay: Long): String {
        val d = dateOf(epochDay)
        return "%04d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)
    }

    /** Locale aware medium date, e.g. "2025年3月5日" / "Mar 5, 2025". */
    fun formatDateLong(epochDay: Long): String =
        dateOf(epochDay).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

    /** Timestamp used in logs and exports. */
    fun formatDateTime(millis: Long): String {
        val ldt = Instant.ofEpochMilli(millis).atZone(zone()).toLocalDateTime()
        return "%04d-%02d-%02d %02d:%02d".format(
            ldt.year, ldt.monthValue, ldt.dayOfMonth, ldt.hour, ldt.minute
        )
    }

    /** Minutes between now and an instant, negative when the instant is in the past. */
    fun minutesUntil(targetMillis: Long, nowMillis: Long = System.currentTimeMillis()): Long =
        (targetMillis - nowMillis) / 60_000L

    /**
     * A space that never breaks a line.
     *
     * Chinese has no inter-word spaces, so the space between a count and its unit is the *only*
     * break opportunity in "已过 109 分钟" - which means a narrow card breaks it there and produces
     * "已过 109 分" / "分钟". That reads as a layout bug, and on a medication card it is worse than
     * cosmetic: the unit is the part that says whether the number means tablets, minutes or ml.
     */
    const val NBSP = "\u00A0"

    /**
     * A word joiner: UAX #14 class WJ, which forbids a line break at that exact position.
     *
     * [NBSP] is not enough on its own for Chinese. A CJK string has a legitimate break opportunity
     * between *any* two ideographs, so "已过 151 分钟" happily breaks as "已过 151 分" / "钟" no matter
     * what the space does - the unit word itself has to be made unbreakable.
     */
    const val WORD_JOINER = "\u2060"

    /** 分钟 as one unbreakable word, so the unit can never be split down the middle. */
    private const val MINUTES_WORD = "分${WORD_JOINER}钟"

    /** 小时, welded the same way. */
    private const val HOURS_WORD = "小${WORD_JOINER}时"

    /** 天, welded the same way. */
    private const val DAYS_WORD = "${WORD_JOINER}天"

    /**
     * "还有 12 分钟" / "已过 35 分钟" helper for the today list.
     *
     * The count and its unit are joined by [NBSP] (so the number never separates from what it
     * measures) and the unit itself is welded together with [WORD_JOINER]. The phrase therefore moves
     * to the next line whole, or not at all.
     */
    fun relativeLabel(targetMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val diff = minutesUntil(targetMillis, nowMillis)
        return when {
            diff == 0L -> "就是现在"
            diff > 0 -> "还有 " + durationWords(diff)
            else -> "已过 " + durationWords(-diff)
        }
    }

    /**
     * A span of minutes, in the largest unit that stays readable.
     *
     * Minutes alone stop working quickly on a medication card: a dose planned for this morning, looked
     * at in the evening, reads "已过 1005 分钟" - a number the reader has to divide in their head to
     * learn anything. People say "已过 16 小时"; the card should too.
     */
    private fun durationWords(minutes: Long): String = when {
        minutes < 60L -> "$minutes${NBSP}$MINUTES_WORD"
        minutes < 24L * 60L -> {
            val hours = minutes / 60L
            val rest = minutes % 60L
            val head = "$hours${NBSP}$HOURS_WORD"
            if (rest == 0L) head else "$head $rest${NBSP}$MINUTES_WORD"
        }
        else -> "${minutes / (24L * 60L)}${NBSP}$DAYS_WORD"
    }
}
