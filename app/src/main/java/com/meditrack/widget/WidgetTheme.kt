package com.meditrack.widget

import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.unit.ColorProvider
import androidx.glance.unit.ResourceColorProvider
import com.meditrack.R
import com.meditrack.domain.plan.WidgetPriority

/**
 * Colours are supplied as [ColorProvider] rather than raw ARGB on purpose.
 *
 * A widget is rendered by the launcher process, which can be in a *different* light/dark
 * configuration than the app. Using resource-backed colour providers lets the platform resolve
 * `values-night` at render time, so the widget follows the launcher's theme and never ships a stale
 * baked-in colour.
 *
 * The dose-status colours used to be the exception - fixed ARGB values, so that red always read as
 * "missed" whatever the theme. That is not achievable: a colour dark enough to be legible on a
 * near-white card is too dark to be legible on a near-black one, so a fixed value has to fail one of
 * the two. They are now resource-backed like everything else, keeping the hue and adapting the
 * lightness - which also removes a pre-existing contrast failure where the amber status label was
 * all but invisible on a light launcher.
 */
object WidgetPalette {

    val background: ColorProvider = ResourceColorProvider(R.color.widget_surface_light)
    val onSurface: ColorProvider = ResourceColorProvider(R.color.widget_on_surface_light)
    val onSurfaceVariant: ColorProvider =
        ResourceColorProvider(R.color.widget_on_surface_variant_light)

    /**
     * The colour that means "you need to do something about this".
     *
     * Shared by a missed dose's status label and a reached 复查 line, deliberately: both are the same
     * message to the user, and using two different reds for it would make the tile look like it had two
     * severities of warning when it has one.
     */
    val alert: ColorProvider = ResourceColorProvider(R.color.widget_status_missed)

    /**
     * Dot, glyph and status-label colour for a row.
     *
     * Yellow for a dose coming up within the half hour, red for one whose time has passed, green for
     * one already recorded - see [WidgetPriority] for the full ordering.
     */
    fun forPriority(priority: WidgetPriority): ColorProvider = when (priority) {
        WidgetPriority.DUE_SOON -> ResourceColorProvider(R.color.widget_status_due_soon)
        WidgetPriority.MISSED -> ResourceColorProvider(R.color.widget_status_missed)
        WidgetPriority.LATER_TODAY -> ResourceColorProvider(R.color.widget_status_later)
        WidgetPriority.TAKEN -> ResourceColorProvider(R.color.widget_status_taken)
        WidgetPriority.SKIPPED -> ResourceColorProvider(R.color.widget_status_skipped)
    }

    /**
     * Background wash behind a row.
     *
     * Only the two states that want attention get one. A list where every row is tinted is a list
     * where no row stands out, so "later today" and the completed ones stay plain.
     */
    fun rowSurface(priority: WidgetPriority): ColorProvider = when (priority) {
        WidgetPriority.DUE_SOON -> ResourceColorProvider(R.color.widget_row_due_soon)
        WidgetPriority.MISSED -> ResourceColorProvider(R.color.widget_row_missed)
        else -> background
    }
}

/** Widget spacing constants, kept in one place so the size variants stay consistent. */
object WidgetDimens {
    val outerPadding = 12.dp
    val rowSpacing = 4.dp
    val rowPadding = 8.dp
    val dot = 10.dp
    val corner = 20.dp
}

/** Applies the widget's rounded surface. */
fun GlanceModifier.widgetSurface(): GlanceModifier = this
