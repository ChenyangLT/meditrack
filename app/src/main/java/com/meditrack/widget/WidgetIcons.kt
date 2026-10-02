package com.meditrack.widget

import androidx.compose.runtime.Composable
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.layout.ContentScale
import androidx.glance.unit.ColorProvider
import com.meditrack.R

/**
 * Maps a medication's icon family to a drawable the widget can render.
 *
 * Glance cannot host Compose's `ImageVector` set directly, so each family resolves to a vector
 * drawable resource. The mapping is intentionally total: an unknown family falls back to the
 * generic tablet rather than drawing nothing.
 */
object WidgetIcons {

    fun drawableFor(iconName: String): Int = when (iconName) {
        "TABLET" -> R.drawable.ic_widget_tablet
        "CAPSULE" -> R.drawable.ic_widget_capsule
        "BOTTLE", "LIQUID" -> R.drawable.ic_widget_bottle
        "DROPPER" -> R.drawable.ic_widget_dropper
        "SPRAY" -> R.drawable.ic_widget_spray
        "INJECTION" -> R.drawable.ic_widget_injection
        "TUBE" -> R.drawable.ic_widget_tube
        "SACHET" -> R.drawable.ic_widget_sachet
        "HEART" -> R.drawable.ic_widget_heart
        else -> R.drawable.ic_widget_tablet
    }

    /**
     * Small leading glyph for a row, tinted to the row's status colour.
     *
     * Marked `@Composable` because Glance's `Image` emits into the composition, and with **no
     * declared return type**: `androidx.glance.Image` is a composable function, not a type, so
     * annotating the return would not compile.
     */
    @Composable
    fun MedicationGlyph(name: String, tint: ColorProvider, modifier: GlanceModifier) {
        Image(
            provider = ImageProvider(drawableFor(name)),
            contentDescription = null,
            modifier = modifier,
            colorFilter = ColorFilter.tint(tint),
            contentScale = ContentScale.Fit,
        )
    }
}
