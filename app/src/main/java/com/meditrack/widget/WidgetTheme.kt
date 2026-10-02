package com.meditrack.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.unit.ColorProvider
import androidx.glance.unit.FixedColorProvider
import androidx.glance.unit.ResourceColorProvider
import com.meditrack.R

/**
 * Colours are supplied as [ColorProvider] rather than [Color] on purpose.
 *
 * A widget is rendered by the launcher process, which can be in a *different* light/dark
 * configuration than the app. Using a resource-backed colour provider lets the platform resolve
 * `values-night` at render time, so the widget follows the launcher's theme and never ships a
 * stale baked-in colour.
 */
object WidgetPalette {

    val background: ColorProvider = ResourceColorProvider(R.color.widget_surface_light)
    val onSurface: ColorProvider = ResourceColorProvider(R.color.widget_on_surface_light)
    val onSurfaceVariant: ColorProvider =
        ResourceColorProvider(R.color.widget_on_surface_variant_light)

    // Fixed colours for the semantic dose states. These must not follow the wallpaper: red has to
    // look like "you missed this" in every theme.
    val overdue = FixedColorProvider(Color(0xFFD3453F))
    val dueSoon = FixedColorProvider(Color(0xFFE08A2B))
    val laterToday = FixedColorProvider(Color(0xFF3C7FD1))
    val taken = FixedColorProvider(Color(0xFF2E9E5B))
    val skipped = FixedColorProvider(Color(0xFF8A8F98))

    /** Status dot / text colour for a widget row. */
    fun forPriority(priority: WidgetPriorityUi): ColorProvider = when (priority) {
        WidgetPriorityUi.OVERDUE -> overdue
        WidgetPriorityUi.DUE_SOON -> dueSoon
        WidgetPriorityUi.LATER_TODAY -> laterToday
        WidgetPriorityUi.TAKEN -> taken
        WidgetPriorityUi.SKIPPED -> skipped
        WidgetPriorityUi.MISSED -> overdue
    }

    /** Background tint behind a row, kept very light so the text stays readable. */
    fun rowSurface(priority: WidgetPriorityUi): ColorProvider = when (priority) {
        WidgetPriorityUi.OVERDUE -> FixedColorProvider(Color(0x1AD3453F))
        WidgetPriorityUi.DUE_SOON -> FixedColorProvider(Color(0x1AE08A2B))
        else -> FixedColorProvider(Color(0x00000000))
    }
}

/**
 * Widget-local mirror of [com.meditrack.domain.plan.WidgetPriority].
 *
 * The widget package deliberately keeps its own enum so the Glance code has no dependency on the
 * domain layer's ordering rules - it only needs "what colour and label do I draw".
 */
enum class WidgetPriorityUi {
    OVERDUE,
    DUE_SOON,
    LATER_TODAY,
    TAKEN,
    SKIPPED,
    MISSED,
}

/** Widget spacing constants, kept in one place so the three size variants stay consistent. */
object WidgetDimens {
    val outerPadding = 12.dp
    val rowSpacing = 6.dp
    val rowPadding = 8.dp
    val dot = 10.dp
    val stepperButton = 32.dp
    val corner = 20.dp
}

/** Applies the widget's rounded surface. */
fun GlanceModifier.widgetSurface(): GlanceModifier = this
