package com.meditrack.core.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.sp
import com.meditrack.data.prefs.FontScale

/**
 * Typography with a user-controlled scale factor.
 *
 * Two product decisions are encoded here:
 *
 *  1. **Font scaling is applied to the type scale, not to the density.** Wrapping the app in a
 *     scaled `LocalDensity` would also enlarge icons, paddings and touch targets, which quickly
 *     pushes a four-item list off the screen. Scaling the text styles instead keeps the layout
 *     intact and only makes the words bigger - which is what "make it readable" actually means.
 *
 *  2. **Digits are monospaced.** The today list, the widget and the notification all show
 *     "1 / 2 片" and "08:00". A proportional font makes those numbers jitter as they change, and
 *     timestamps stop lining up in a column. `FontFamily.Monospace` is the platform's own
 *     monospace face, so no font file has to be bundled and CJK glyphs still fall back correctly.
 */
object MediTrackTypography {

    /** The unit used for every numeric/clock style. */
    val NumericFamily: FontFamily = FontFamily.Monospace

    /**
     * Builds the Material 3 type scale for a given user preference.
     *
     * Line heights are scaled alongside the sizes so a larger font does not end up cramped; the
     * resulting text stays comfortably inside its card at every preset.
     */
    fun forScale(fontScale: FontScale): Typography {
        val s = fontScale.scale
        val base = Typography()

        fun TextStyle.scaled(
            family: FontFamily? = null,
            weight: FontWeight? = null,
        ): TextStyle = copy(
            fontSize = fontSize.scaledBy(s),
            lineHeight = lineHeight.scaledBy(s),
            letterSpacing = letterSpacing, // Tracking stays constant; scaling it hurts legibility.
            fontFamily = family ?: this.fontFamily,
            fontWeight = weight ?: this.fontWeight,
        )

        val default = base.copy(
            displayLarge = base.displayLarge.scaled(),
            displayMedium = base.displayMedium.scaled(),
            displaySmall = base.displaySmall.scaled(),
            headlineLarge = base.headlineLarge.scaled(),
            headlineMedium = base.headlineMedium.scaled(),
            headlineSmall = base.headlineSmall.scaled(),
            titleLarge = base.titleLarge.scaled(weight = FontWeight.SemiBold),
            titleMedium = base.titleMedium.scaled(weight = FontWeight.SemiBold),
            titleSmall = base.titleSmall.scaled(weight = FontWeight.Medium),
            bodyLarge = base.bodyLarge.scaled(),
            bodyMedium = base.bodyMedium.scaled(),
            bodySmall = base.bodySmall.scaled(),
            labelLarge = base.labelLarge.scaled(weight = FontWeight.Medium),
            labelMedium = base.labelMedium.scaled(weight = FontWeight.Medium),
            labelSmall = base.labelSmall.scaled(weight = FontWeight.Medium),
        )

        return default.copy(
            // Numeric slots: the clock on a dose card and the big "3/5" progress figure.
            displaySmall = default.displaySmall.copy(fontFamily = NumericFamily, fontWeight = FontWeight.Bold),
            headlineMedium = default.headlineMedium.copy(fontFamily = NumericFamily, fontWeight = FontWeight.Bold),
            headlineSmall = default.headlineSmall.copy(fontFamily = NumericFamily, fontWeight = FontWeight.SemiBold),
            titleLarge = default.titleLarge.copy(fontFamily = NumericFamily),
            labelLarge = default.labelLarge.copy(fontFamily = NumericFamily),
        )
    }

    /**
     * Extra-large style used by the elderly preset for the single most important string on screen
     * (the progress count and the next-dose time).
     */
    fun heroStyle(fontScale: FontScale): TextStyle =
        TextStyle(
            fontFamily = NumericFamily,
            fontWeight = FontWeight.Bold,
            fontSize = (34 * fontScale.scale).sp,
            lineHeight = (40 * fontScale.scale).sp,
        )
}

/** Multiplies a [TextUnit] when it carries a sp value; leaves Em and Unspecified untouched. */
private fun TextUnit.scaledBy(factor: Float): TextUnit =
    if (type == TextUnitType.Sp) (value * factor).sp else this

/** Line height presets: generous but not loose, tuned for mixed CJK + Latin lines. */
internal val ComfortableLineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)
