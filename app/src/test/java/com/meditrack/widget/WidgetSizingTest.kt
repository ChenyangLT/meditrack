package com.meditrack.widget

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The widget's "what fits" arithmetic.
 *
 * These tests exist because the old widget's row count came from three hard-coded height thresholds
 * with no relationship to how tall a row actually renders. A tile that landed just above a threshold
 * asked for more rows than fitted and the launcher clipped the overflow - which is what "2x2 尺寸显示
 * 有问题" was.
 *
 * The headline test is [the layout never asks for more height than it has]. Everything else pins the
 * behaviour at the four sizes the picker offers, at the font scales a user can actually set.
 */
class WidgetSizingTest {

    /** The four supported footprints, at the height a launcher typically allocates (70dp per cell). */
    private val footprints = listOf(
        "4x1" to 70f,
        "4x2" to 140f,
        "4x3" to 210f,
        "4x4" to 280f,
    )

    private fun layoutAt(
        heightDp: Float,
        itemCount: Int = 4,
        itemLimit: Int = WidgetSizing.DEFAULT_ITEM_LIMIT,
        fontScale: Float = 1f,
    ) = WidgetSizing.layoutFor(
        heightDp = heightDp,
        itemCount = itemCount,
        itemLimit = itemLimit,
        fontScale = fontScale,
    )

    // ------------------------------------------------------------ the invariant

    /**
     * The bug that started this: content taller than the tile is silently clipped.
     *
     * Swept across the whole plausible range rather than at a handful of points, because the original
     * failure only appeared in the gaps *between* the thresholds somebody had thought to test.
     *
     * The one documented exception is a tile shorter than [WidgetLayout.minDrawableHeightDp], where
     * nothing at all can be drawn at that font scale. There the layout still returns one row on
     * purpose - a reminder clipped by two or three dp beats a blank tile - so the assertion becomes
     * "it asks for no more than the smallest thing it *could* draw".
     */
    @Test
    fun `the layout never asks for more height than it has`() {
        for (height in 40..400) {
            for (scale in listOf(1f, 1.15f, 1.3f, 1.5f, 1.8f, 2f)) {
                for (limit in listOf(1, 2, 4, 6)) {
                    val layout = layoutAt(
                        heightDp = height.toFloat(),
                        itemCount = 8,
                        itemLimit = limit,
                        fontScale = scale,
                    )
                    val budget = maxOf(height.toFloat(), layout.minDrawableHeightDp)
                    assertThat(layout.reservedHeightDp).isAtMost(budget + 0.001f)

                    // ...and below the floor it must still be trying to show something.
                    if (height.toFloat() < layout.minDrawableHeightDp) {
                        assertThat(layout.rowLimit).isEqualTo(1)
                    }
                }
            }
        }
    }

    @Test
    fun `the row budget is never smaller than the rows it was computed for`() {
        for (height in 40..400) {
            val layout = layoutAt(heightDp = height.toFloat())
            val budgetEach = layout.reservedHeightDp / layout.rowLimit
            // Not a hard guarantee for a 1-row tile (which legitimately gets the whole tile), but the
            // per-row figure the action buttons depend on must never be nonsense.
            assertThat(budgetEach).isGreaterThan(0f)
            assertThat(layout.rowBudgetDp).isAtLeast(1)
        }
    }

    // ------------------------------------------------------------ the four sizes

    @Test
    fun `4x1 shows one dose and drops the title to do it`() {
        val layout = layoutAt(heightDp = 70f)

        assertThat(layout.rowLimit).isEqualTo(1)
        // There is no height for a title *and* a dose row, and the dose is the point.
        assertThat(layout.showHeader).isFalse()
        assertThat(layout.singleLineRows).isFalse()
        assertThat(layout.reservedHeightDp).isAtMost(70f)
    }

    @Test
    fun `4x2 shows a title and two doses`() {
        val layout = layoutAt(heightDp = 140f)

        assertThat(layout.showHeader).isTrue()
        assertThat(layout.rowLimit).isEqualTo(2)
        assertThat(layout.reservedHeightDp).isAtMost(140f)
    }

    @Test
    fun `4x3 shows a title and three doses`() {
        val layout = layoutAt(heightDp = 210f)

        assertThat(layout.showHeader).isTrue()
        assertThat(layout.rowLimit).isEqualTo(3)
    }

    @Test
    fun `4x4 shows four doses under the shipped item limit`() {
        // Five rows physically fit, but the shipped "最多显示条目数" is 4, and the smaller of the two
        // limits wins. That is what makes the setting mean something instead of being ignored.
        val layout = layoutAt(heightDp = 280f)

        assertThat(layout.rowLimit).isEqualTo(4)
        assertThat(layout.showHeader).isTrue()
    }

    @Test
    fun `a taller tile than the picker offers simply shows more rows`() {
        // The picker stops at 4x4 but nothing stops a user dragging it taller.
        val layout = layoutAt(heightDp = 420f, itemLimit = 6)

        assertThat(layout.rowLimit).isEqualTo(6)
        assertThat(layout.reservedHeightDp).isAtMost(420f)
    }

    // ---------------------------------------------------------------- the limit

    @Test
    fun `the user's item limit caps the rows but never below one`() {
        assertThat(layoutAt(heightDp = 280f, itemLimit = 2).rowLimit).isEqualTo(2)
        // Five rows physically fit at 280dp, so a ceiling of 6 cannot conjure a sixth.
        assertThat(layoutAt(heightDp = 280f, itemLimit = 6).rowLimit).isEqualTo(5)

        // A nonsense ceiling must not produce an empty widget.
        assertThat(layoutAt(heightDp = 280f, itemLimit = 0).rowLimit).isEqualTo(1)
        assertThat(layoutAt(heightDp = 280f, itemLimit = -5).rowLimit).isEqualTo(1)
    }

    @Test
    fun `the limit cannot make a tile show more than fits`() {
        // A generous ceiling must never override the physical constraint.
        val layout = layoutAt(heightDp = 140f, itemLimit = 6)

        assertThat(layout.rowLimit).isEqualTo(2)
    }

    // -------------------------------------------------------------- tiny tiles

    @Test
    fun `even the smallest legal tile shows something`() {
        // 40dp is the platform's own minimum for a one-cell row; at and above it the widget must
        // always draw something and always fit.
        for (height in listOf(40f, 44f, 50f, 60f, 70f)) {
            val layout = layoutAt(heightDp = height)
            assertThat(layout.rowLimit).isAtLeast(1)
            assertThat(layout.reservedHeightDp).isAtMost(height + 0.001f)
        }
    }

    @Test
    fun `a tile too short for two lines collapses to one dense line`() {
        // 40dp of tile leaves 32dp of usable height, and a two-line row needs 42dp.
        assertThat(layoutAt(heightDp = 40f).singleLineRows).isTrue()
        assertThat(layoutAt(heightDp = 44f).singleLineRows).isTrue()

        // At 50dp the two-line row fits exactly (42dp in 42dp), so the comfortable form is used.
        assertThat(layoutAt(heightDp = 50f).singleLineRows).isFalse()
        assertThat(layoutAt(heightDp = 140f).singleLineRows).isFalse()
    }

    @Test
    fun `a zero or negative size does not crash the layout`() {
        for (height in listOf(0f, 1f, -100f)) {
            val layout = layoutAt(heightDp = height)
            assertThat(layout.rowLimit).isAtLeast(1)
        }
    }

    @Test
    fun `the old 2x2 square is no longer a broken shape`() {
        // The tile that used to clip now renders a title and one full dose row, fitting inside 110dp.
        val layout = layoutAt(heightDp = 110f)

        assertThat(layout.rowLimit).isAtLeast(1)
        assertThat(layout.reservedHeightDp).isAtMost(110f)
    }

    // --------------------------------------------------------------- font scale

    @Test
    fun `a larger system font reserves more room and shows fewer rows`() {
        val normal = layoutAt(heightDp = 280f, fontScale = 1f)
        val large = layoutAt(heightDp = 280f, fontScale = 1.5f)
        val huge = layoutAt(heightDp = 280f, fontScale = 2f)

        assertThat(large.rowLimit).isLessThan(normal.rowLimit)
        assertThat(huge.rowLimit).isLessThan(large.rowLimit)
        // And the invariant still holds with much taller text.
        assertThat(large.reservedHeightDp).isAtMost(280f)
        assertThat(huge.reservedHeightDp).isAtMost(280f)
    }

    @Test
    fun `an accessibility-sized font does not make a 4x2 clip`() {
        // This app ships an elderly preset that pushes the system text size up; the widget must scale
        // its budget with it rather than reserving the same height for taller text.
        for (scale in listOf(1.3f, 1.5f, 2f)) {
            val layout = layoutAt(heightDp = 140f, fontScale = scale)
            assertThat(layout.reservedHeightDp).isAtMost(140f)
            assertThat(layout.rowLimit).isAtLeast(1)
        }
    }

    @Test
    fun `nonsensical font scales are clamped rather than trusted`() {
        for (scale in listOf(0f, 0.1f, 5f, 20f)) {
            val layout = layoutAt(heightDp = 200f, fontScale = scale)
            assertThat(layout.reservedHeightDp).isAtMost(200f)
            assertThat(layout.rowLimit).isAtLeast(1)
        }
    }

    // ------------------------------------------------------------ overflow hint

    @Test
    fun `the overflow hint only appears when it fits in leftover space`() {
        // 4x2 has 8dp of slack - not enough for a hint line, so the second row keeps its room.
        val tight = layoutAt(heightDp = 140f, itemCount = 9)
        assertThat(tight.showOverflowHint).isFalse()
        assertThat(tight.rowLimit).isEqualTo(2)

        // 4x3 has room to spare, so telling the user there is more costs nothing.
        val roomy = layoutAt(heightDp = 210f, itemCount = 9)
        assertThat(roomy.showOverflowHint).isTrue()
        assertThat(roomy.rowLimit).isEqualTo(3)
    }

    @Test
    fun `no hint is drawn when everything fits`() {
        // Nothing was left out, so there is nothing to say.
        assertThat(layoutAt(heightDp = 210f, itemCount = 2).showOverflowHint).isFalse()
    }

    @Test
    fun `the hint never costs a dose row`() {
        for (count in 1..20) {
            for (height in 100..400) {
                val withHint = layoutAt(heightDp = height.toFloat(), itemCount = count)
                val withoutHint = layoutAt(heightDp = height.toFloat(), itemCount = 1)
                // The row count is a function of the tile and the limit, never of how many items
                // happened to be present.
                assertThat(withHint.rowLimit).isEqualTo(withoutHint.rowLimit)
            }
        }
    }

    // --------------------------------------------------------------------- misc

    @Test
    fun `the shipped item limit matches the app default`() {
        // WidgetSizing cannot import the prefs module, so the two constants are kept in step by hand.
        // This is the test that notices if one of them moves.
        assertThat(WidgetSizing.DEFAULT_ITEM_LIMIT)
            .isEqualTo(com.meditrack.data.prefs.WidgetPlannerDefaults.ITEM_LIMIT)
    }

    @Test
    fun `every offered footprint produces a usable layout`() {
        for ((name, height) in footprints) {
            val layout = layoutAt(heightDp = height, itemCount = 6)
            assertThat(layout.rowLimit).isAtLeast(1)
            assertThat(layout.reservedHeightDp).isAtMost(height + 0.001f)
            // The title is dropped only when it genuinely does not fit.
            if (height >= 140f) {
                assertThat(layout.showHeader).isTrue()
            }
            assertThat(name).isNotEmpty()
        }
    }
}
