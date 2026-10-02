package com.meditrack.core.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meditrack.data.prefs.ThemeMode
import com.meditrack.data.prefs.UserPreferences

/**
 * The single theme wrapper for the whole app.
 *
 * It resolves, in order:
 *  1. the light/dark decision ([ThemeMode] preference, falling back to the system setting);
 *  2. the colour scheme - Material You when the device supports it (API 31+) and the user has not
 *     opted out, otherwise a hand-tuned scheme derived from the chosen accent;
 *  3. the dose-status palette, with the high-contrast variant when the accessibility preset is on;
 *  4. the type scale for the chosen font size.
 *
 * Everything downstream reads the result through `MaterialTheme.colorScheme`,
 * `MaterialTheme.doseColors` and `MaterialTheme.typography`, so no screen needs to know about
 * preferences.
 */
@Composable
fun MediTrackTheme(
    preferences: UserPreferences,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (preferences.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current
    val accent = preferences.accentColor

    val colorScheme = remember(darkTheme, preferences.useDynamicColor, accent, context) {
        val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        when {
            preferences.useDynamicColor && dynamicAvailable && darkTheme ->
                dynamicDarkColorScheme(context)
            preferences.useDynamicColor && dynamicAvailable ->
                dynamicLightColorScheme(context)
            else -> {
                val (lightSeed, darkSeed) = accentSeeds(accent, darkTheme)
                if (darkTheme) darkScheme(darkSeed) else lightScheme(lightSeed)
            }
        }
    }

    val doseColors = remember(darkTheme, preferences.highContrast) {
        doseColorsFor(darkTheme, preferences.highContrast)
    }
    // The system's own accessibility font scale is read here and folded into the app's preset, so
    // the two cannot multiply into text that no layout can hold. See [FontScaling].
    val systemFontScale = LocalDensity.current.fontScale
    val typography = remember(preferences.fontScale, systemFontScale) {
        MediTrackTypography.forScale(preferences.fontScale, systemFontScale)
    }

    CompositionLocalProvider(
        LocalDoseColors provides doseColors,
        LocalUserPreferences provides preferences,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = MediTrackShapes,
            content = content,
        )
    }
}

/**
 * Card and control shapes.
 *
 * The brief asks for 16-24dp corners with generous whitespace. Sizes grow slightly in the
 * simplified/elderly preset, where a softer outline reads as more approachable.
 */
val MediTrackShapes
    @Composable get() = androidx.compose.material3.Shapes(
        extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        small = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        medium = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
    )

/**
 * Exposes the preference snapshot to any composable without threading it through parameters.
 *
 * Screens need the font scale (for the hero number), the 24-hour flag (for every time label) and the
 * touch-target size (for the stepper buttons). Passing all of that down every call chain would be
 * noise; a composition local keeps the signatures honest.
 */
val LocalUserPreferences = androidx.compose.runtime.staticCompositionLocalOf { UserPreferences() }

/** Convenience accessor: `MaterialTheme.prefs.use24HourFormat`. */
val MaterialTheme.prefs: UserPreferences
    @Composable
    @androidx.compose.runtime.ReadOnlyComposable
    get() = LocalUserPreferences.current

/** Text alignment helper used by the hero number, kept here so all screens agree. */
internal val HeroTextAlign = TextAlign.Start
