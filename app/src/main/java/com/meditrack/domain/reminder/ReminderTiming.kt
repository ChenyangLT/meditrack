package com.meditrack.domain.reminder

/**
 * Human time-of-day bands.
 *
 * A medication reminder is read by a half-awake person, so the copy says *when in the day* a dose
 * belongs rather than reciting a timestamp. "早上 8 点左右" is instantly understood; "08:00:00" is
 * a machine talking. The exact time is still shown one line lower down in the expanded detail, so
 * nothing clinically important is lost.
 *
 * Boundaries are deliberately wide and never overlapping; [bandOf] is the single definition.
 */
enum class DayBand(val label: String, val fromMinute: Int) {
    LATE_NIGHT("凌晨", 0),
    EARLY_MORNING("清晨", 5 * 60),
    MORNING("早上", 7 * 60),
    FORENOON("上午", 9 * 60),
    NOON("中午", 11 * 60),
    AFTERNOON("下午", 13 * 60),
    EVENING("傍晚", 17 * 60),
    NIGHT("晚上", 19 * 60),
    LATE_EVENING("深夜", 22 * 60),
}

/**
 * The tolerance envelope a reminder lives in.
 *
 * This is the whole answer to "don't be second-precise". Nothing in the pipeline ever compares two
 * instants for equality or reasons about sub-minute precision. Instead every dose has a *window*:
 *
 *  - it may be announced up to [leadMinutes] **early** (the humanised "还有一会儿" heads-up);
 *  - it becomes actionable at the planned minute and stays fresh for [freshMinutes] afterwards -
 *    an alarm that arrives inside that span is simply "on time", however many seconds late it is;
 *  - past [staleAfterMinutes] the moment has gone, and an audible alarm would be a lie, so the dose
 *    degrades to a silent 补记 prompt;
 *  - an alarm that somehow fires *before* [earlyToleranceMinutes] ahead of schedule is treated as a
 *    clock error and re-armed rather than shown.
 *
 * Every field is in minutes, because minutes are the finest unit this feature ever needs.
 */
data class ReminderWindow(
    /** How far ahead the optional "快到了" heads-up is posted. 0 disables it. */
    val leadMinutes: Int,
    /**
     * How early an alarm may legitimately fire. Beyond this the trigger is assumed to be a clock
     * jump or a stale PendingIntent and is re-armed instead of announced.
     */
    val earlyToleranceMinutes: Int,
    /** Minutes after the due instant during which a reminder still counts as "on time". */
    val freshMinutes: Int,
    /** Beyond this the reminder is too late to be worth a banner; it becomes a silent 补记. */
    val staleAfterMinutes: Int,
    /** Doses falling due within this many minutes of each other are announced together. */
    val clusterMinutes: Int,
) {
    val leadMillis: Long get() = leadMinutes.coerceAtLeast(0) * 60_000L
    val earlyToleranceMillis: Long get() = earlyToleranceMinutes.coerceAtLeast(0) * 60_000L
    val freshMillis: Long get() = freshMinutes.coerceAtLeast(0) * 60_000L
    val staleAfterMillis: Long get() = staleAfterMinutes.coerceAtLeast(1) * 60_000L
    val clusterMillis: Long get() = clusterMinutes.coerceAtLeast(0) * 60_000L

    companion object {
        /**
         * The shipped envelope.
         *
         * `earlyTolerance = 5` rather than 0 is what makes the pipeline forgiving of the ordinary
         * few-seconds/one-minute drift of `setExactAndAllowWhileIdle` without ever ringing early.
         */
        val DEFAULT = ReminderWindow(
            leadMinutes = 15,
            earlyToleranceMinutes = 5,
            freshMinutes = 20,
            staleAfterMinutes = 180,
            clusterMinutes = 20,
        )
    }
}

/**
 * All the clock reasoning the reminder pipeline needs, as pure functions.
 *
 * Kept free of Android types and of "now" reads so the rules can be exercised on the JVM with an
 * injected clock - which is the only way to test DST edges, clock jumps and long idle periods.
 */
object ReminderTiming {

    /** Nearest multiple of 5 minutes, clamped inside the day. */
    private const val SNAP_MINUTES = 5

    /** The band [minuteOfDay] falls into. Never throws: out-of-range values are clamped. */
    fun bandOf(minuteOfDay: Int): DayBand {
        val m = minuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
        // Bands are declared in ascending order, so the last one whose start we have passed wins.
        return DayBand.entries.last { m >= it.fromMinute }
    }

    /**
     * "早上 8 点左右" - the label used as the headline of a reminder notification.
     *
     * Deliberately rounded down to five minutes: a reminder that announces "08:03" invites the reader
     * to treat the schedule as a laboratory instrument, which it is not. Rounding down (rather than
     * to nearest) guarantees the label never names a time later than the real one, and keeps it
     * inside the same time band as the dose.
     */
    fun approximateLabel(minuteOfDay: Int): String {
        val snapped = snap(minuteOfDay)
        val band = bandOf(snapped)
        val hour24 = snapped / 60
        val minute = snapped % 60
        val hour12 = when {
            hour24 == 0 -> 12
            hour24 > 12 -> hour24 - 12
            else -> hour24
        }
        return when (minute) {
            0 -> "${band.label} $hour12 点左右"
            30 -> "${band.label} $hour12 点半左右"
            else -> "${band.label} $hour12 点 $minute 分左右"
        }
    }

    /**
     * "早上 8:00" - the band plus the *exact* stored minute, used one line below the headline.
     *
     * This is the clinical escape hatch: the friendly phrasing is for reading, this is for obeying.
     */
    fun exactLabel(minuteOfDay: Int, formattedTime: String): String =
        "${bandOf(minuteOfDay).label} $formattedTime"

    /**
     * Rounds down to the previous five-minute mark.
     *
     * Two deliberate choices, both of which matter for a medication reminder:
     *
     *  - **Down, not to nearest.** The label must never name a time *later* than the user's actual
     *    schedule. Rounding 08:03 up to 08:05 would quietly imply the dose is due two minutes after
     *    it is; rounding down can only ever be conservative.
     *  - **Five minutes, not one.** A reminder that announces "08:03" invites the reader to treat
     *    the schedule as a laboratory instrument. It is not one, and the exact minute is still shown
     *    in the detail line for anyone who needs it.
     *
     * Rounding down also keeps a label inside its own band: an 08:58 dose reads "早上 8 点 55 分左右"
     * rather than jumping to "上午 9 点" for a dose the user set at eight.
     */
    fun snap(minuteOfDay: Int): Int {
        val clamped = minuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
        return (clamped / SNAP_MINUTES) * SNAP_MINUTES
    }

    /**
     * Truncates an instant to whole minutes.
     *
     * Every comparison in the pipeline goes through this, which is what "not second-precise"
     * actually means in code: two instants inside the same minute are the same instant.
     */
    fun toMinute(millis: Long): Long = millis - Math.floorMod(millis, 60_000L)

    /** True when [a] and [b] land in the same wall-clock minute. */
    fun sameMinute(a: Long, b: Long): Boolean = toMinute(a) == toMinute(b)

    /**
     * Human phrasing for how late a reminder was when it finally reached the user.
     *
     * Used only in the expanded detail of a catch-up notification, where honesty about lateness
     * matters more than elegance - but it is still never expressed in seconds.
     */
    fun latenessLabel(lateMillis: Long): String {
        if (lateMillis < 60_000L) return "刚刚"
        val minutes = lateMillis / 60_000L
        return when {
            minutes < 60 -> "晚了 $minutes 分钟"
            else -> "晚了 ${minutes / 60} 小时 ${minutes % 60} 分钟"
        }
    }

    const val MINUTES_PER_DAY = 24 * 60
}
