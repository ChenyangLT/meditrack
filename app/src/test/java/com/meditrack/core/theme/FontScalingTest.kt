package com.meditrack.core.theme

import com.google.common.truth.Truth.assertThat
import com.meditrack.data.prefs.FontScale
import org.junit.Test

/**
 * The ceiling on total text scale.
 *
 * Two multipliers stack in this app: Android's own font scale and the in-app preset. Nothing stopped
 * them from multiplying, so 特大 (1.3x) on a phone set to 1.3x produced 1.69x text - and the settings
 * screen's option chips, which lay five "30 分钟" labels side by side, were the first thing to break.
 *
 * The cap is half the fix (flexible layouts are the other half), and it is pure arithmetic, so it is
 * pinned here in full - including the awkward inputs, because this calculation runs on every text
 * style in the app and a NaN leaking into it would be spectacular.
 */
class FontScalingTest {

    private val epsilon = 0.0001f

    @Test
    fun `the shipped presets are untouched on a phone with a normal font scale`() {
        for (preset in FontScale.entries) {
            val effective = FontScaling.effectiveAppScale(preset.scale, systemScale = 1f)
            assertThat(effective).isWithin(epsilon).of(preset.scale)
        }
    }

    @Test
    fun `a large system scale is absorbed so the product lands on the ceiling`() {
        // 超大 (1.5x) on a phone already at 1.3x would be 1.95x; the app contributes 1.6/1.3 instead.
        val effective = FontScaling.effectiveAppScale(
            appScale = FontScale.HUGE.scale,
            systemScale = 1.3f,
        )
        assertThat(effective * 1.3f).isWithin(epsilon).of(FontScaling.MAX_TOTAL_SCALE)
        assertThat(effective).isLessThan(FontScale.HUGE.scale)
    }

    @Test
    fun `the product never exceeds the ceiling at any realistic system scale`() {
        var systemScale = 1.0f
        while (systemScale <= 2.0f + epsilon) {
            for (preset in FontScale.entries) {
                val product = FontScaling.effectiveAppScale(preset.scale, systemScale) * systemScale
                assertThat(product).isAtMost(FontScaling.MAX_TOTAL_SCALE + epsilon)
            }
            systemScale += 0.1f
        }
    }

    @Test
    fun `the app never enlarges text beyond what the user chose`() {
        for (preset in FontScale.entries) {
            for (systemScale in listOf(0.85f, 1.0f, 1.15f, 1.3f, 1.5f, 2.0f)) {
                val effective = FontScaling.effectiveAppScale(preset.scale, systemScale)
                assertThat(effective).isAtMost(preset.scale + epsilon)
            }
        }
    }

    @Test
    fun `an extreme system scale keeps the text legible instead of shrinking forever`() {
        // 3x system scale wants the app to contribute 0.53x, which would undo the user's own choice.
        // The floor wins; the wrapping layouts are what keep the screen usable at that point.
        val effective = FontScaling.effectiveAppScale(FontScale.NORMAL.scale, systemScale = 3f)
        assertThat(effective).isEqualTo(FontScaling.MIN_APP_SCALE)
    }

    @Test
    fun `garbage input degrades to one rather than propagating`() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f)) {
            assertThat(FontScaling.effectiveAppScale(bad, 1f)).isEqualTo(1f)
            assertThat(FontScaling.effectiveAppScale(1f, bad)).isEqualTo(1f)
        }
    }

    @Test
    fun `a readable default is never below the floor`() {
        assertThat(FontScaling.effectiveAppScale(0f, 0f)).isAtLeast(FontScaling.MIN_APP_SCALE)
        assertThat(FontScaling.MIN_APP_SCALE).isGreaterThan(0f)
    }
}
