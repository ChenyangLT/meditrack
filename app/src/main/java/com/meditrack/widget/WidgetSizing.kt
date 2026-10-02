package com.meditrack.widget

/**
 * How the widget lays itself out at one concrete size.
 *
 * Every field is derived from the space actually available, so there is no size - and no system font
 * scale - at which the content silently overflows and gets clipped by the launcher. That was the old
 * widget's bug: it picked a row count from three hard-coded height thresholds (130dp / 200dp) that
 * had no relationship to how tall a row really renders, so a tile that landed just above a threshold
 * asked for more rows than fitted, and a user with large text got a clipped tile at every size.
 */
data class WidgetLayout(
    /** Whether the "今日用药 还有 N 项" title row is drawn. */
    val showHeader: Boolean,
    /**
     * How many dose rows may be drawn.
     *
     * The smaller of "what the tile can hold" and "what the user asked to see", and never below 1 -
     * a medication widget that shows nothing is worse than one that shows a slightly cramped row.
     */
    val rowLimit: Int,
    /**
     * Draw each row as a single dense line instead of two stacked lines.
     *
     * Only for a tile too short for the text it would otherwise have to render - which, with a large
     * system font, includes sizes that are comfortable at the default scale.
     */
    val singleLineRows: Boolean,
    /** Whether each row offers its "+" / "-" buttons. */
    val showQuickActions: Boolean,
    /** Padding between the widget edge and the content. Shrinks with the tile. */
    val outerPaddingDp: Int,
    /** Vertical gap between two dose rows. */
    val rowSpacingDp: Int,
    /** Whether a trailing "还有 N 项…" line is drawn when items were left out. */
    val showOverflowHint: Boolean,
    /**
     * Total height the layout will occupy at minimum.
     *
     * The invariant this whole file exists to guarantee is that this never exceeds the height the
     * layout was computed for - see [minDrawableHeightDp] for the one case where it deliberately
     * does not hold. It is asserted directly in `WidgetSizingTest`.
     */
    val reservedHeightDp: Float,
    /**
     * The shortest tile that can show anything at all at this font scale.
     *
     * Below this the layout still returns one row rather than none: a medication reminder that is
     * clipped by two or three dp is strictly more useful than a blank tile, and a blank medication
     * widget is indistinguishable from a broken one.
     */
    val minDrawableHeightDp: Float,
    /** Height budgeted for one dose row, for diagnostics and tests. */
    val rowBudgetDp: Int,
) {
    /** True when the layout is the dense one-line form. */
    val isStrip: Boolean get() = singleLineRows
}

/**
 * The single definition of "what fits".
 *
 * ## Why this is a pure function
 *
 * A widget is composed by the launcher process with no chance to measure text, so the row count has
 * to be *estimated*. Estimating is fine; estimating in three different places with three different
 * constants is not - which is exactly how the previous layout ended up over-requesting rows. Keeping
 * the arithmetic here means it can be unit tested at every size the widget supports, at every font
 * scale a user can set, and against the one invariant that matters: the content always fits.
 *
 * ## The model
 *
 * A dose row is two lines of text (13sp name, 11sp "08:00 · 1 片") plus 8dp of vertical padding, and
 * a title row is an 18dp glyph beside a 13sp label. Only the *text* scales with the system font
 * setting, so the two are modelled separately rather than by scaling one magic number - otherwise a
 * user who enlarged their fonts would get a widget that reserved the same height for taller text.
 *
 * Reserve slightly too much and the tile simply has a little space at the bottom. Reserve too little
 * and a medication reminder is cut off, which is the failure that actually matters.
 */
object WidgetSizing {

    /** Vertical padding inside a dose row, top and bottom, in dp. Does not scale with fonts. */
    private const val ROW_PADDING_DP = 8f

    /** Height of a dose row's two lines of text at the default font scale. */
    private const val ROW_TEXT_BASE_DP = 30f

    /** Height of the dense single-line row's text at the default font scale. */
    private const val ROW_SINGLE_LINE_TEXT_DP = 15f

    /** Gap between two dose rows. */
    private const val ROW_SPACING_DP = 4f

    /** Gap under a dense single-line row; it is meant to read as one continuous strip. */
    private const val ROW_SPACING_TIGHT_DP = 2f

    /** The title row's tallest element at the default font scale: the 18dp glyph. */
    private const val HEADER_CONTENT_DP = 18f

    /** Gap between the title row and the first dose row. */
    private const val HEADER_GAP_DP = 6f

    /** Reserved for the trailing "还有 N 项…" line when one is drawn. */
    const val OVERFLOW_HINT_DP = 16f

    /**
     * A row must be at least this tall before the "+" / "-" buttons are offered.
     *
     * The buttons are 32dp circles, so this does **not** scale with fonts: the constraint is the
     * fixed tap target, not the text beside it. A mis-tap on a medication quantity is worse than no
     * button at all.
     */
    const val MIN_ACTIONABLE_ROW_DP = 40

    /** Below this width the row's text and buttons cannot coexist. */
    const val MIN_ACTIONABLE_WIDTH_DP = 220f

    /** Padding steps, so a small tile keeps its text off the launcher's own rounded corner. */
    private const val PADDING_ROOMY_DP = 12
    private const val PADDING_TIGHT_DP = 8
    private const val PADDING_MINIMAL_DP = 4
    private const val ROOMY_HEIGHT_DP = 140
    private const val TIGHT_HEIGHT_DP = 100

    /** Font scales outside this range are clamped; beyond 2x the tile is unusable at any size. */
    private const val MIN_FONT_SCALE = 1f
    private const val MAX_FONT_SCALE = 2f

    /** Mirrors `WidgetPlannerDefaults.ITEM_LIMIT`, kept local so the widget needs no prefs import. */
    const val DEFAULT_ITEM_LIMIT = 4

    fun layoutFor(
        widthDp: Float,
        heightDp: Float,
        quickActionsEnabled: Boolean,
        itemCount: Int,
        /**
         * The user's own ceiling on how many doses a tile may show.
         *
         * Two different limits apply and both are honoured: the *tile* decides how many rows
         * physically fit, and this decides how many the user wants to see even when more would fit.
         * They used to be one and the same, which is why the "最多显示条目数" setting in the app
         * silently did nothing - nothing ever read it.
         */
        itemLimit: Int = DEFAULT_ITEM_LIMIT,
        /**
         * The system font scale, from `Configuration.fontScale`.
         *
         * Passed in rather than read here so the function stays pure. It matters more for this app
         * than for most: the accessibility preset deliberately pushes the system's text size up, and
         * a widget that ignored it would clip exactly for the users who most need to read it.
         */
        fontScale: Float = 1f,
    ): WidgetLayout {
        val scale = fontScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
        val twoLineUnit = ROW_PADDING_DP + ROW_TEXT_BASE_DP * scale + ROW_SPACING_DP
        val oneLineUnit = ROW_PADDING_DP + ROW_SINGLE_LINE_TEXT_DP * scale + ROW_SPACING_TIGHT_DP
        val headerBlock = maxOf(HEADER_CONTENT_DP, HEADER_CONTENT_DP * scale) + HEADER_GAP_DP
        val ceiling = itemLimit.coerceAtLeast(1)

        val padding = when {
            heightDp >= ROOMY_HEIGHT_DP -> PADDING_ROOMY_DP
            heightDp >= TIGHT_HEIGHT_DP -> PADDING_TIGHT_DP
            else -> PADDING_MINIMAL_DP
        }
        val available = (heightDp - 2 * padding).coerceAtLeast(0f)
        val spaceAfterHeader = available - headerBlock

        // The title is a nicety; a dose row is the point. Drop the title before dropping a dose.
        val showHeader = (spaceAfterHeader / twoLineUnit).toInt() >= 1
        val usable = if (showHeader) spaceAfterHeader else available

        // Prefer the comfortable two-line row; fall back to the dense one only when two lines
        // genuinely do not fit, rather than letting the second line be the thing that gets clipped.
        // Deriving this from the row height instead of a hard-coded threshold is what makes it behave
        // correctly at a large system font scale too.
        val singleLineRows = !showHeader && (usable / twoLineUnit).toInt() < 1
        val rowUnit = if (singleLineRows) oneLineUnit else twoLineUnit

        // The overflow hint is drawn only when it fits in space the rows were not going to use
        // anyway. Reserving a line for it unconditionally would cost a whole dose row on a 4x3 tile,
        // which is the wrong trade: the hint says "there are more", the row shows one of them.
        val rowsWithoutHint = (usable / rowUnit).toInt().coerceAtLeast(1)
        val slack = usable - rowsWithoutHint * rowUnit
        val showOverflowHint = showHeader &&
            !singleLineRows &&
            slack >= OVERFLOW_HINT_DP &&
            itemCount > rowsWithoutHint.coerceAtMost(ceiling)

        val reservedForHint = if (showOverflowHint) OVERFLOW_HINT_DP else 0f
        val fitRows = ((usable - reservedForHint) / rowUnit).toInt().coerceAtLeast(1)
        val rowLimit = fitRows.coerceAtMost(ceiling)

        val rowBudget = ((usable - reservedForHint) / rowLimit).toInt()
        val showQuickActions = quickActionsEnabled &&
            !singleLineRows &&
            widthDp >= MIN_ACTIONABLE_WIDTH_DP &&
            rowBudget >= MIN_ACTIONABLE_ROW_DP

        val reservedHeader = if (showHeader) headerBlock else 0f
        val reserved = 2 * padding + reservedHeader + rowLimit * rowUnit + reservedForHint

        return WidgetLayout(
            showHeader = showHeader,
            rowLimit = rowLimit,
            singleLineRows = singleLineRows,
            showQuickActions = showQuickActions,
            outerPaddingDp = padding,
            rowSpacingDp = if (singleLineRows) ROW_SPACING_TIGHT_DP.toInt() else ROW_SPACING_DP.toInt(),
            showOverflowHint = showOverflowHint,
            reservedHeightDp = reserved,
            minDrawableHeightDp = 2 * padding + rowUnit,
            rowBudgetDp = rowBudget,
        )
    }
}
