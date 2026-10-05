package com.meditrack.domain.reminder

/**
 * What a reminder does after its first announcement.
 *
 * ## Why this replaced the old "escalation budget"
 *
 * The previous design re-posted the *notification* every [UserPreferences.repeatReminderMinutes]
 * until a per-dose budget ran out. In practice that produced the complaint this feature exists to
 * answer: the phone chimed once, the user was in another room, and nothing ever happened again - or,
 * worse, it chimed every ten minutes for four hours while they were asleep. Both are failures of the
 * same missing idea: "keep telling me **now**, and stop the moment I answer".
 *
 * The three modes below are that idea. The important property they share is that [UNTIL_ACTION] and
 * [FIXED_TIMES] both stop *immediately* on any notification action, which is enforced by
 * [RingingController.stopFor] rather than by a notification being cancelled - a cancelled notification
 * cannot silence a `MediaPlayer` that is already looping.
 */
enum class ReminderRingMode(val label: String, val description: String) {
    /**
     * One chime per notification. The pre-2.0 behaviour, kept for anyone who found the alarm-like
     * ringing too much.
     */
    ONCE("只响一次", "响一声就停下，和以前一样。"),

    /**
     * Keep ringing until the user answers.
     *
     * The shipped default, because a medication reminder that can be slept through is not a reminder.
     * Bounded by [UserPreferences.ringMaxMinutes] so that a phone left in a drawer does not ring all
     * night, and by the quiet-hours rules so that it does not ring at 3am.
     */
    UNTIL_ACTION("一直响到处理", "持续响铃，直到你点「已服用」「稍后」或「跳过」。点任意一个都会立刻安静。"),

    /** Ring a fixed number of times, evenly spaced. */
    FIXED_TIMES("响几次就停", "按设定次数响铃，每次之间间隔一段时间。"),
    ;

    companion object {
        val DEFAULT = UNTIL_ACTION

        fun fromName(name: String?): ReminderRingMode =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * The ringing behaviour the stored settings describe, as a pure value.
 *
 * Split out of [UserPreferences] so the arithmetic - how many chimes, how long between them, when to
 * give up - is unit testable without a device, an `AudioManager`, or a clock. Every field here is
 * derived; nothing in this file touches Android.
 *
 * @param mode what the reminder does after the first announcement
 * @param chimeCount how many times the tone is played in [ReminderRingMode.FIXED_TIMES]; 1 elsewhere
 * @param intervalMillis silence between two chimes
 * @param maxDurationMillis give up this long after the first chime (0 = never, only reachable for
 *        [ReminderRingMode.FIXED_TIMES] with an explicitly unset bound)
 */
data class RingPolicy(
    val mode: ReminderRingMode,
    val chimeCount: Int,
    val intervalMillis: Long,
    val maxDurationMillis: Long,
) {

    /** True when this policy produces exactly one chime and then stops. */
    val isSingleChime: Boolean
        get() = mode == ReminderRingMode.ONCE || (mode == ReminderRingMode.FIXED_TIMES && chimeCount <= 1)

    /** True when the ring stops when the cap is reached rather than when the user answers. */
    val isBoundedByCount: Boolean get() = mode == ReminderRingMode.FIXED_TIMES

    companion object {

        /** The gap between two chimes when the user has not chosen one. */
        const val DEFAULT_INTERVAL_SECONDS = 20

        /** Hard ceiling on the interval, so "响 3 次" cannot stretch over an hour. */
        const val MAX_INTERVAL_SECONDS = 300

        /** Hard ceiling on the number of chimes. Beyond this, "一直响" is the honest setting. */
        const val MAX_CHIMES = 60

        /**
         * Builds the policy from stored values.
         *
         * Every input is clamped rather than trusted: these values round-trip through a JSON backup
         * that a user can hand-edit, and a ring interval of zero would otherwise produce a tight
         * infinite loop that drains the battery and never stops.
         */
        fun of(
            mode: ReminderRingMode,
            chimeCount: Int,
            intervalSeconds: Int,
            maxMinutes: Int,
        ): RingPolicy {
            val interval = intervalSeconds
                .coerceIn(1, MAX_INTERVAL_SECONDS)
                .toLong() * 1_000L
            return when (mode) {
                ReminderRingMode.ONCE -> RingPolicy(mode, chimeCount = 1, interval, 0L)

                // The cap is what keeps "永远响下去" from meaning "until the battery dies". A user who
                // sets 0 minutes gets the shipped floor rather than an unbounded loop, because an
                // unbounded alarm on a phone in a handbag is indistinguishable from a stuck app.
                ReminderRingMode.UNTIL_ACTION -> RingPolicy(
                    mode = mode,
                    chimeCount = 0,
                    intervalMillis = interval,
                    maxDurationMillis = maxMinutes.coerceAtLeast(1).toLong() * 60_000L,
                )

                ReminderRingMode.FIXED_TIMES -> {
                    val count = chimeCount.coerceIn(1, MAX_CHIMES)
                    // A bounded run still needs an outer limit: 60 chimes every 5 minutes is five
                    // hours, which is long past the point where the reminder means anything.
                    val span = (count - 1).toLong() * interval + interval
                    val cap = maxMinutes.coerceAtLeast(1).toLong() * 60_000L
                    RingPolicy(mode, count, interval, minOf(span, cap))
                }
            }
        }
    }
}

/**
 * One active ringing session.
 *
 * A session is created when a reminder is actually announced and destroyed the moment the user
 * answers it. It is deliberately **not** persisted: the requirement is that a ring dies with the
 * process, so a session that survived a restart would be a bug rather than a feature. What survives a
 * restart is the notification, which is what the user can still act on.
 *
 * @param doseId the dose being announced, or null for a summary/aggregate announcement. A null dose
 *        can only be silenced by the cap, because there is no per-dose action to hook.
 * @param startedAtMillis when the first chime played
 * @param completedChimes how many chimes have played so far, including the first
 * @param maxDurationMillis when the session gives up, measured from [startedAtMillis]
 */
data class RingSession(
    val doseId: Long?,
    val startedAtMillis: Long,
    val policy: RingPolicy,
    /**
     * The medication name, so the ongoing notification can say *which* pill is ringing.
     *
     * Carried on the session rather than looked up by the service: the service may be started from a
     * cold process with nothing else loaded, and a database read there would be the one thing standing
     * between the user and a way to stop the noise.
     */
    val medicationName: String = "",
    val completedChimes: Int = 0,
) {

    /**
     * Whether another chime is due [elapsedMillis] after the session started.
     *
     * Pure so the loop's stop condition can be tested exhaustively; the service only supplies the
     * elapsed time and the current wall clock.
     */
    fun shouldChime(completedChimes: Int): Boolean = when (policy.mode) {
        ReminderRingMode.ONCE -> completedChimes < 1
        ReminderRingMode.UNTIL_ACTION -> true
        ReminderRingMode.FIXED_TIMES -> completedChimes < policy.chimeCount
    }

    /** True once the cap has passed and the session must end on its own. */
    fun isExpired(nowMillis: Long): Boolean =
        policy.maxDurationMillis > 0L && nowMillis - startedAtMillis >= policy.maxDurationMillis

    /**
     * How long until the session must stop regardless of the user, or 0 when it is unbounded.
     *
     * The ringing service arms one delayed stop with this value instead of polling, so a phone that is
     * dozing is not woken every second just to ask whether it is still ringing.
     */
    fun remainingMillis(nowMillis: Long): Long =
        if (policy.maxDurationMillis <= 0L) 0L
        else (policy.maxDurationMillis - (nowMillis - startedAtMillis)).coerceAtLeast(0L)

    /** The label the ongoing "正在响铃" notification shows while this session is active. */
    fun countdownLabel(nowMillis: Long): String {
        if (policy.mode == ReminderRingMode.UNTIL_ACTION) {
            val secondsLeft = (remainingMillis(nowMillis) / 1_000L).coerceAtLeast(0L)
            val minutes = secondsLeft / 60
            val seconds = secondsLeft % 60
            // Seconds only below a minute: "将再响 30 秒" reads as a countdown, while "将再响 0 分 30 秒"
            // reads as a machine reading out two fields. The last minute of a ring is exactly when the user
            // is most likely to be looking at this line.
            return if (minutes > 0) {
                "将再响 $minutes 分 ${seconds.toString().padStart(2, '0')} 秒"
            } else {
                "将再响 $seconds 秒"
            }
        }
        if (policy.mode == ReminderRingMode.FIXED_TIMES) {
            return "第 ${(completedChimes + 1).coerceAtMost(policy.chimeCount)} / ${policy.chimeCount} 次"
        }
        return ""
    }
}
