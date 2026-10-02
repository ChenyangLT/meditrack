package com.meditrack.domain

import com.google.common.truth.Truth.assertThat
import com.meditrack.core.util.QuantityFormatter
import org.junit.Test

/**
 * Quantity handling is where floating point bugs turn into a wrongly recorded dose. The acceptance
 * criteria are explicit ("达到计划数量后自动判断为已吃；低于计划显示部分；为 0 显示未吃"), so these
 * tests encode them directly.
 */
class QuantityFormatterTest {

    @Test
    fun `format strips trailing zeros so a whole tablet reads as one not one point zero`() {
        assertThat(QuantityFormatter.format(1.0)).isEqualTo("1")
        assertThat(QuantityFormatter.format(2.0)).isEqualTo("2")
        assertThat(QuantityFormatter.format(10.0)).isEqualTo("10")
    }

    @Test
    fun `format keeps the significant decimals of a liquid dose`() {
        assertThat(QuantityFormatter.format(0.5)).isEqualTo("0.5")
        assertThat(QuantityFormatter.format(1.5)).isEqualTo("1.5")
        assertThat(QuantityFormatter.format(0.25)).isEqualTo("0.25")
        assertThat(QuantityFormatter.format(2.50)).isEqualTo("2.5")
    }

    @Test
    fun `format appends the unit only when one is given`() {
        assertThat(QuantityFormatter.format(1.0, "片")).isEqualTo("1 片")
        assertThat(QuantityFormatter.format(0.5, "ml")).isEqualTo("0.5 ml")
        assertThat(QuantityFormatter.format(3.0, "")).isEqualTo("3")
    }

    @Test
    fun `isZero treats floating point noise as zero`() {
        assertThat(QuantityFormatter.isZero(0.0)).isTrue()
        // 0.1 + 0.2 - 0.3 is not exactly 0 in binary floating point.
        assertThat(QuantityFormatter.isZero(0.1 + 0.2 - 0.3)).isTrue()
        assertThat(QuantityFormatter.isZero(0.00001)).isTrue()
        assertThat(QuantityFormatter.isZero(0.5)).isFalse()
    }

    @Test
    fun `isComplete is true when taken reaches or exceeds planned`() {
        assertThat(QuantityFormatter.isComplete(1.0, 1.0)).isTrue()
        assertThat(QuantityFormatter.isComplete(2.0, 1.0)).isTrue()
        assertThat(QuantityFormatter.isComplete(0.5, 1.0)).isFalse()
        assertThat(QuantityFormatter.isComplete(0.0, 1.0)).isFalse()
    }

    @Test
    fun `isComplete is immune to floating point drift`() {
        // Three 0.1 ml steps must count as complete for a 0.3 ml dose.
        val taken = 0.1 + 0.1 + 0.1
        assertThat(QuantityFormatter.isComplete(taken, 0.3)).isTrue()
    }

    @Test
    fun `isPartial is only true strictly between zero and the planned amount`() {
        assertThat(QuantityFormatter.isPartial(0.5, 1.0)).isTrue()
        assertThat(QuantityFormatter.isPartial(0.0, 1.0)).isFalse()
        assertThat(QuantityFormatter.isPartial(1.0, 1.0)).isFalse()
        assertThat(QuantityFormatter.isPartial(2.0, 1.0)).isFalse()
    }

    @Test
    fun `formatProgress renders the taken over planned pair`() {
        assertThat(QuantityFormatter.formatProgress(1.0, 2.0, "片")).isEqualTo("1 / 2 片")
        assertThat(QuantityFormatter.formatProgress(0.0, 1.0, "ml")).isEqualTo("0 / 1 ml")
        assertThat(QuantityFormatter.formatProgress(1.5, 1.5, "ml")).isEqualTo("1.5 / 1.5 ml")
    }

    @Test
    fun `step is one for countable forms and a half for liquids`() {
        assertThat(QuantityFormatter.stepFor(allowFraction = false)).isEqualTo(1.0)
        assertThat(QuantityFormatter.stepFor(allowFraction = true)).isEqualTo(0.5)
    }

    @Test
    fun `sanitize clamps negative zero and rounds to three decimals`() {
        assertThat(QuantityFormatter.sanitize(-0.0)).isEqualTo(0.0)
        assertThat(QuantityFormatter.sanitize(1.00004)).isEqualTo(1.0)
        assertThat(QuantityFormatter.sanitize(1.23456)).isEqualTo(1.235)
    }

    @Test
    fun `format never throws on a non finite value`() {
        assertThat(QuantityFormatter.format(Double.NaN)).isEqualTo("0")
        assertThat(QuantityFormatter.format(Double.POSITIVE_INFINITY)).isEqualTo("0")
    }
}
