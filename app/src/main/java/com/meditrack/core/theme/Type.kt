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
/**
 * Keeps the total text scale inside a range the layout can actually survive.
 *
 * ## Why this exists
 *
 * Two independent multipliers stack: the *system* font scale (Android's own accessibility setting)
 * and the app's own preset (标准 / 大 / 特大 / 超大). Compose multiplies a sp value by the ambient
 * density, so a user who sets the system to 1.3x and then picks 超大 (1.5x) gets 1.95x - at which
 * point option chips, time chips and card headers start colliding with each other.
 *
 * The fix has two halves, and both are needed. This object supplies the first: a ceiling on the
 * product, so the *text* cannot grow without bound. The screens supply the second: flexible layouts
 * that wrap instead of squeezing when the text does grow.
 *
 * The ceiling deliberately applies to the product rather than to the app's own multiplier, because
 * 1.6x *absolute* is the number that describes whether a line of Chinese text still fits a 4-inch
 * card. It only ever reduces the app's multiplier, never the system's - the system scale is applied
 * by the platform outside this code - so a large system scale can push the app's own contribution
 * below 1.0. That is intentional: the user asked for big text, and honouring the cap while keeping
 * every option readable is better than overflowing the card.
 */
object FontScaling {

    /**
     * Ceiling on the product of the system and app scales.
     *
     * Chosen as the largest value at which the widest real row in the app - five chips such as
     * "不重复 / 5 分钟 / 10 分钟 / 15 分钟 / 30 分钟" - still lays out legibly once it is allowed to
     * wrap. Above it, cards begin to look broken rather than merely large.
     */
    const val MAX_TOTAL_SCALE = 1.6f

    /**
     * Floor on the app's own multiplier.
     *
     * Reached only at absurd system scales (roughly 2.0x and beyond). Below this the app would be
     * quietly shrinking text the user explicitly asked to enlarge, which is worse than a slightly
     * overflowing layout - and the wrapping layouts mean overflowing is no longer what happens.
     */
    const val MIN_APP_SCALE = 0.8f

    /**
     * The multiplier to apply to the app's type scale so the *product* stays within [cap].
     *
     * Pure, and total: garbage input (NaN, zero, negative) degrades to 1.0 rather than propagating
     * into every text style in the app.
     */
    fun effectiveAppScale(
        appScale: Float,
        systemScale: Float,
        cap: Float = MAX_TOTAL_SCALE,
        floor: Float = MIN_APP_SCALE,
    ): Float {
        val safeApp = if (appScale.isFinite() && appScale > 0f) appScale else 1f
        val safeSystem = if (systemScale.isFinite() && systemScale > 0f) systemScale else 1f
        val allowed = cap / safeSystem
        return safeApp.coerceAtMost(allowed).coerceAtLeast(floor)
    }
}

object MediTrackTypography {

    /** The unit used for every numeric/clock style. */
    val NumericFamily: FontFamily = FontFamily.Monospace

    /**
     * Builds the Material 3 type scale for a given user preference.
     *
     * Line heights are scaled alongside the sizes so a larger font does not end up cramped; the
     * resulting text stays comfortably inside its card at every preset.
     */
    fun forScale(fontScale: FontScale, systemScale: Float = 1f): Typography {
        // Capped so the system scale and the app preset cannot multiply into a broken layout; see
        // [FontScaling].
        val s = FontScaling.effectiveAppScale(fontScale.scale, systemScale)
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
    fun heroStyle(fontScale: FontScale, systemScale: Float = 1f): TextStyle {
        val s = FontScaling.effectiveAppScale(fontScale.scale, systemScale)
        return TextStyle(
            fontFamily = NumericFamily,
            fontWeight = FontWeight.Bold,
            fontSize = (34 * s).sp,
            lineHeight = (40 * s).sp,
        )
    }
}

/** Multiplies a [TextUnit] when it carries a sp value; leaves Em and Unspecified untouched. */
private fun TextUnit.scaledBy(factor: Float): TextUnit =
    if (type == TextUnitType.Sp) (value * factor).sp else this

/** Line height presets: generous but not loose, tuned for mixed CJK + Latin lines. */
internal val ComfortableLineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)
