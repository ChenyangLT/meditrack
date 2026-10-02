package com.meditrack.core.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.meditrack.data.prefs.AccentColor
import com.meditrack.data.prefs.UserPreferences

/**
 * Brand seed colours. The full Material 3 scheme is derived from these by
 * [androidx.compose.material3.dynamicLightColorScheme] when the device supports Material You, and by
 * the hand-tuned fallbacks below otherwise.
 *
 * The palette is deliberately desaturated: this is a health app that a person may open four times a
 * day for years, so the colours aim for "calm and trustworthy" rather than "attention grabbing".
 */
object BrandColors {
    val Mint = Color(0xFF3DBFA0)
    val MintDark = Color(0xFF1F6F5C)
    val Teal = Color(0xFF2E9BB5)
    val TealDark = Color(0xFF155F73)
    val Lavender = Color(0xFF8E86D6)
    val LavenderDark = Color(0xFF4B4488)

    val seed: Color
        get() = Mint
}

/**
 * The dose-status palette.
 *
 * This is the one place where colour carries meaning rather than decoration, so the values are
 * fixed rather than derived from the wallpaper. Every value is also distinguishable by *shape* and
 * *text* in the UI (see `DoseStatusChip`), because roughly 8% of men cannot reliably separate the
 * red and green used for 未服药 and 已服用.
 *
 * Contrast ratios against the corresponding background are at least 4.5:1 in both themes; the
 * high-contrast variant pushes the light-mode values darker and the dark-mode values lighter to
 * clear 7:1, which matters for the elderly-user preset.
 */
@Immutable
data class DoseColors(
    /** 已服用 - green. */
    val taken: Color,
    val takenContainer: Color,
    val onTakenContainer: Color,

    /** 部分服用 - amber. */
    val partial: Color,
    val partialContainer: Color,
    val onPartialContainer: Color,

    /** 未服药 - red. */
    val missed: Color,
    val missedContainer: Color,
    val onMissedContainer: Color,

    /** 已跳过 - grey. */
    val skipped: Color,
    val skippedContainer: Color,
    val onSkippedContainer: Color,

    /** 未到时间 - blue/neutral. */
    val upcoming: Color,
    val upcomingContainer: Color,
    val onUpcomingContainer: Color,

    /** 待服用 / 即将服用 - orange, also used by the widget's DUE_SOON tier. */
    val dueSoon: Color,
    val dueSoonContainer: Color,
    val onDueSoonContainer: Color,

    /** True when this variant was built for the accessibility preset. */
    val highContrast: Boolean,
)

private val LightDoseColors = DoseColors(
    taken = Color(0xFF1F7A45),
    takenContainer = Color(0xFFD3F0DE),
    onTakenContainer = Color(0xFF0B3D22),
    partial = Color(0xFF9A5B00),
    partialContainer = Color(0xFFFFE2BE),
    onPartialContainer = Color(0xFF4A2A00),
    missed = Color(0xFFB3261E),
    missedContainer = Color(0xFFFFDAD6),
    onMissedContainer = Color(0xFF5C0F0A),
    skipped = Color(0xFF5A5F68),
    skippedContainer = Color(0xFFE4E6EA),
    onSkippedContainer = Color(0xFF2B2F36),
    upcoming = Color(0xFF1D5FA8),
    upcomingContainer = Color(0xFFD6E5FA),
    onUpcomingContainer = Color(0xFF0A2E52),
    dueSoon = Color(0xFFB4610A),
    dueSoonContainer = Color(0xFFFFE7CC),
    onDueSoonContainer = Color(0xFF4F2900),
    highContrast = false,
)

private val LightDoseColorsHighContrast = LightDoseColors.copy(
    taken = Color(0xFF0E5B2F),
    partial = Color(0xFF6E3F00),
    missed = Color(0xFF8C0009),
    skipped = Color(0xFF33373D),
    upcoming = Color(0xFF0A3F79),
    dueSoon = Color(0xFF7A3F00),
    highContrast = true,
)

private val DarkDoseColors = DoseColors(
    taken = Color(0xFF7FD9A3),
    takenContainer = Color(0xFF16351F),
    onTakenContainer = Color(0xFFB6F0CC),
    partial = Color(0xFFF0B76A),
    partialContainer = Color(0xFF3A2708),
    onPartialContainer = Color(0xFFFFDCB0),
    missed = Color(0xFFFFB4AB),
    missedContainer = Color(0xFF4A1512),
    onMissedContainer = Color(0xFFFFDAD6),
    skipped = Color(0xFFB8BDC6),
    skippedContainer = Color(0xFF2A2D33),
    onSkippedContainer = Color(0xFFDDE0E6),
    upcoming = Color(0xFFA7C8F5),
    upcomingContainer = Color(0xFF12304F),
    onUpcomingContainer = Color(0xFFD3E4FF),
    dueSoon = Color(0xFFFFC48A),
    dueSoonContainer = Color(0xFF3D2400),
    onDueSoonContainer = Color(0xFFFFE0BD),
    highContrast = false,
)

private val DarkDoseColorsHighContrast = DarkDoseColors.copy(
    taken = Color(0xFF9BF0BC),
    partial = Color(0xFFFFD08F),
    missed = Color(0xFFFFC9C3),
    skipped = Color(0xFFD5D9E0),
    upcoming = Color(0xFFC5DCFF),
    dueSoon = Color(0xFFFFD9A8),
    highContrast = true,
)

/** Provides [DoseColors] down the tree; read it through [MaterialTheme.doseColors]. */
val LocalDoseColors = staticCompositionLocalOf { LightDoseColors }

/** Ergonomic accessor mirroring the Material 3 colour accessors. */
val MaterialTheme.doseColors: DoseColors
    @Composable
    @ReadOnlyComposable
    get() = LocalDoseColors.current

/** Picks the right dose palette for the current theme and accessibility settings. */
internal fun doseColorsFor(darkTheme: Boolean, highContrast: Boolean): DoseColors = when {
    darkTheme && highContrast -> DarkDoseColorsHighContrast
    darkTheme -> DarkDoseColors
    highContrast -> LightDoseColorsHighContrast
    else -> LightDoseColors
}

/** The brand accent selected in settings, resolved to a light/dark seed pair. */
internal fun accentSeeds(accent: AccentColor, darkTheme: Boolean): Pair<Color, Color> = when (accent) {
    AccentColor.MINT -> BrandColors.Mint to BrandColors.MintDark
    AccentColor.TEAL -> BrandColors.Teal to BrandColors.TealDark
    AccentColor.LAVENDER -> BrandColors.Lavender to BrandColors.LavenderDark
}.let { (light, dark) -> if (darkTheme) dark to light else light to dark }

/**
 * Fallback colour scheme used when Material You is unavailable or switched off.
 *
 * Rather than a full hand-written palette we let Material 3 generate a tonal scheme from the seed
 * and only override the surfaces: the brief asks for a warm off-white in light mode and #121212 in
 * dark mode, which the default scheme does not produce.
 */
internal fun lightScheme(seed: Color): ColorScheme = lightColorScheme(
    primary = seed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC9EFE3),
    onPrimaryContainer = Color(0xFF00382A),
    secondary = Color(0xFF4A635B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDE8DE),
    onSecondaryContainer = Color(0xFF072019),
    tertiary = BrandColors.Teal,
    onTertiary = Color.White,
    background = Color(0xFFFBF7F2),
    onBackground = Color(0xFF1B1C1E),
    surface = Color(0xFFFBF7F2),
    onSurface = Color(0xFF1B1C1E),
    surfaceVariant = Color(0xFFEDE4DC),
    onSurfaceVariant = Color(0xFF4E453E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    outline = Color(0xFF80756C),
    outlineVariant = Color(0xFFD2C4BA),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

internal fun darkScheme(seed: Color): ColorScheme = darkColorScheme(
    primary = seed,
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF00513E),
    onPrimaryContainer = Color(0xFFC9EFE3),
    secondary = Color(0xFFB1CCC2),
    onSecondary = Color(0xFF1D352D),
    secondaryContainer = Color(0xFF334B43),
    onSecondaryContainer = Color(0xFFCDE8DE),
    tertiary = Color(0xFF8FD3E0),
    onTertiary = Color(0xFF00363F),
    // Material's baseline dark surface, per the visual spec.
    background = Color(0xFF121212),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF4E453E),
    onSurfaceVariant = Color(0xFFD2C4BA),
    surfaceContainerLowest = Color(0xFF0D0D0D),
    outline = Color(0xFF9A8F86),
    outlineVariant = Color(0xFF4E453E),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
)

/** Convenience for previews and the widget preview screen. */
@Composable
internal fun previewPreferences(
    darkTheme: Boolean = false,
    highContrast: Boolean = false,
): UserPreferences = UserPreferences(highContrast = highContrast, themeMode = com.meditrack.data.prefs.ThemeMode.SYSTEM)
