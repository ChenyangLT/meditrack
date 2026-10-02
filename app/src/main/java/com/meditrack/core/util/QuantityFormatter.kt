package com.meditrack.core.util

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/**
 * Quantities can be fractional for liquids (0.5 ml, 1.5 ml) but must stay exact for countable
 * forms (1 tablet). Doubles are only used as a transport type; every presentation and comparison
 * goes through [BigDecimal] so "0.1 + 0.2" style drift can never mark a dose as *not* taken.
 */
object QuantityFormatter {

    /** Anything below this is treated as zero (handles float drift from the UI stepper). */
    const val EPSILON = 0.0001

    fun isZero(value: Double): Boolean = abs(value) < EPSILON

    /** True when the taken amount has reached (or exceeded) the planned amount. */
    fun isComplete(taken: Double, planned: Double): Boolean =
        BigDecimal.valueOf(taken).compareTo(BigDecimal.valueOf(planned)) >= 0

    fun isPartial(taken: Double, planned: Double): Boolean =
        !isZero(taken) && !isComplete(taken, planned)

    /**
     * Renders a quantity without trailing zeros: 1.0 -> "1", 0.50 -> "0.5", 10.0 -> "10".
     */
    fun format(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "0"
        val bd = BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros()
        return bd.toPlainString()
    }

    /** "1 片" / "0.5 ml". */
    fun format(value: Double, unitLabel: String): String {
        val number = format(value)
        return if (unitLabel.isBlank()) number else "$number $unitLabel"
    }

    /** "1 / 2 片" style progress used on the today card and the widget. */
    fun formatProgress(taken: Double, planned: Double, unitLabel: String): String =
        format(taken) + " / " + format(planned) + if (unitLabel.isBlank()) "" else " $unitLabel"

    /**
     * The smallest sensible step for a given dosage form: liquids move in 0.5, countable forms in 1.
     */
    fun stepFor(allowFraction: Boolean): Double = if (allowFraction) 0.5 else 1.0

    /** Clamps at zero and snaps to [EPSILON] so a stepper can never produce "-0.0". */
    fun sanitize(value: Double): Double =
        if (isZero(value)) 0.0 else BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).toDouble()
}
