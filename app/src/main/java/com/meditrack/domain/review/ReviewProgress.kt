package com.meditrack.domain.review

import com.meditrack.core.util.QuantityFormatter
import com.meditrack.data.local.entity.ReviewCountMode

/**
 * How far a medication is through its current 复查 round.
 *
 * A pure snapshot, computed from a stored round plus "now". Everything the UI and the notifier need -
 * how much is left, whether the advance notice is due, whether the threshold has been reached - is
 * derived here so that the notification and the settings screen can never disagree about whether a
 * review is due.
 *
 * @param count progress so far: taken doses, elapsed days, or accumulated amount depending on [mode]
 * @param threshold the configured limit; 0 or less means "no review reminder configured"
 * @param unitShort the unit the remaining amount is expressed in, from [ReviewCountMode]
 */
data class ReviewProgress(
    val mode: ReviewCountMode,
    val count: Double,
    val threshold: Double,
    val unitShort: String,
) {

    /** True when a limit was actually configured; without one nothing may fire. */
    val isConfigured: Boolean get() = threshold > 0.0

    /**
     * True once the limit is reached. Compared with a small epsilon because [count] accumulates
     * through floating-point additions (0.5 + 0.5 + 0.5 …), and "59.99999999999999 >= 60" being false
     * would leave a review reminder that never arrives.
     */
    val isReached: Boolean get() = isConfigured && count >= threshold - EPSILON

    /** How much is still outstanding, floored at zero. */
    val remaining: Double get() = (threshold - count).coerceAtLeast(0.0)

    /** Progress in 0..1, for a progress bar. */
    val fraction: Double
        get() = if (!isConfigured) 0.0 else (count / threshold).coerceIn(0.0, 1.0)

    /** Whole units remaining, for "还差 3 次". Rounded up so a partial unit still counts as one. */
    val remainingUnits: Int
        get() = if (!isConfigured) 0 else Math.ceil(remaining - EPSILON).toInt().coerceAtLeast(0)

    /**
     * True when the gentle heads-up should appear: [advance] units or fewer are left.
     *
     * Not shown at all once the threshold is reached - the loud reminder replaces it, and seeing both
     * at once would read as the app having lost count.
     */
    fun shouldGiveAdvanceNotice(advance: Int): Boolean =
        isConfigured && !isReached && advance > 0 && remaining <= advance + EPSILON

    /** "还差 3 次" / "还差 2 天" / "还差 30 ml". */
    val remainingLabel: String
        get() = when {
            !isConfigured -> "未设置"
            isReached -> "已到复查时间"
            mode == ReviewCountMode.QUANTITY ->
                "还差 ${QuantityFormatter.format(remaining)} $unitShort"
            else -> "还差 $remainingUnits $unitShort"
        }

    /** "已服用 47 次 / 共 60 次" - the line the editor shows under the progress bar. */
    val progressLabel: String
        get() = when {
            !isConfigured -> "未设置复查提醒"
            mode == ReviewCountMode.QUANTITY ->
                "已累计 ${QuantityFormatter.format(count)} / " +
                    "${QuantityFormatter.format(threshold)} $unitShort"
            else ->
                "已 ${Math.floor(count + EPSILON).toInt()} / ${Math.floor(threshold + EPSILON).toInt()} $unitShort"
        }

    /** The label the review notification shows as its title suffix. */
    val reachedLabel: String
        get() = when (mode) {
            ReviewCountMode.DOSES -> "已服用 ${Math.floor(count + EPSILON).toInt()} 次"
            ReviewCountMode.DAYS -> "已用药 ${Math.floor(count + EPSILON).toInt()} 天"
            ReviewCountMode.QUANTITY ->
                "已累计 ${QuantityFormatter.format(count)} $unitShort"
        }

    companion object {
        /**
         * Tolerance used everywhere a threshold is compared.
         *
         * Large enough to absorb the drift of a few hundred 0.5 additions, far too small to let a
         * genuinely unreached threshold fire.
         */
        const val EPSILON = 1e-6
    }
}

/**
 * The pure decisions behind a 复查 round: what the progress is now, and what it becomes after a dose.
 *
 * Kept free of Room, `Context` and the clock so that every rule the feature promises -
 * "跳过不算次数", "天数模式按自然日算", "到阈值就停" - is a unit test rather than a device experiment.
 */
object ReviewProgressCalculator {

    /**
     * Derives the progress at [nowMillis].
     *
     * [ReviewCountMode.DAYS] is computed here rather than stored, and **inclusively**: the day the round
     * started counts as day 1. A 30-day round that reported "还差 1 天" on its own start date would be
     * off by one in the direction that matters, because the user's doctor said thirty days of
     * medication, not thirty days starting tomorrow.
     *
     * @param storedCount the count written by dose increments (doses or quantity); ignored for DAYS
     * @param startedEpochDay the day the round began
     * @param epochDay today's epoch day
     */
    fun progressOf(
        mode: ReviewCountMode,
        storedCount: Double,
        threshold: Double,
        startedEpochDay: Long,
        epochDay: Long,
    ): ReviewProgress {
        val effectiveCount = when (mode) {
            ReviewCountMode.DAYS -> (epochDay - startedEpochDay + 1).coerceAtLeast(1).toDouble()
            else -> storedCount
        }
        return ReviewProgress(
            mode = mode,
            count = effectiveCount,
            threshold = threshold,
            unitShort = if (mode == ReviewCountMode.QUANTITY) "" else mode.unitShort,
        )
    }

    /** The multiplier appended to a round's unit label when the mode is a quantity. */
    fun quantityUnitFor(medicationUnit: String): String = medicationUnit

    /**
     * What [storedCount] becomes after the user records one fully-taken dose of [takenQuantity].
     *
     * Returns the stored value unchanged in [ReviewCountMode.DAYS], because a day-based round is a
     * function of the calendar and adding to it would double count the moment the same day's second
     * dose was recorded.
     *
     * **Only a completed dose calls this.** A skipped or missed dose is not medication the user took,
     * so it must not move them towards a review - counting it would send them to the doctor early, on
     * a number the app invented. A half-tablet *does* count, because half a tablet is still taking it.
     */
    fun advance(
        mode: ReviewCountMode,
        storedCount: Double,
        takenQuantity: Double,
    ): Double = when (mode) {
        ReviewCountMode.DAYS -> storedCount
        ReviewCountMode.DOSES -> storedCount + 1.0
        ReviewCountMode.QUANTITY -> storedCount + takenQuantity.coerceAtLeast(0.0)
    }

    /**
     * Whether a dose that has just been recorded should be counted.
     *
     * Extracted so the repository hook has a name and a test, rather than being an inline condition
     * that a later refactor can quietly invert.
     */
    fun countsTowardReview(previouslyTaken: Double, nowTaken: Double): Boolean =
        previouslyTaken <= 0.0 && nowTaken > 0.0
}
