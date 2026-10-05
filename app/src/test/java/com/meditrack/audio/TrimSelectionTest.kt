package com.meditrack.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The trim-window rules.
 *
 * The trimmer's arithmetic is the part of this feature that is invisible when it is wrong: a
 * zero-length clip rings as a click, an over-long one is a ringtone nobody wants, and a window past the
 * end of the file exports with a codec error on the user's phone. All three are cheap to test and
 * expensive to discover.
 */
class TrimSelectionTest {

    private val tenMinutes = 600_000L

    // ----------------------------------------------------------------- clamping

    @Test
    fun `a sensible window is kept as asked`() {
        val window = TrimSelection.clamp(startMillis = 30_000L, endMillis = 45_000L, durationMillis = tenMinutes)

        assertThat(window).isNotNull()
        assertThat(window!!.startMillis).isEqualTo(30_000L)
        assertThat(window.endMillis).isEqualTo(45_000L)
        assertThat(window.lengthMillis).isEqualTo(15_000L)
    }

    @Test
    fun `a window cannot run past the end of the file`() {
        val window = TrimSelection.clamp(
            startMillis = 590_000L,
            endMillis = 700_000L,
            durationMillis = tenMinutes,
        )!!

        assertThat(window.endMillis).isEqualTo(tenMinutes)
    }

    @Test
    fun `dragging the end before the start swaps them rather than erroring`() {
        val window = TrimSelection.clamp(startMillis = 40_000L, endMillis = 20_000L, durationMillis = tenMinutes)!!

        assertThat(window.startMillis).isEqualTo(20_000L)
        assertThat(window.endMillis).isEqualTo(40_000L)
    }

    @Test
    fun `a window longer than the cap is trimmed to the cap`() {
        val window = TrimSelection.clamp(startMillis = 10_000L, endMillis = 500_000L, durationMillis = tenMinutes)!!

        assertThat(window.startMillis).isEqualTo(10_000L)
        assertThat(window.lengthMillis).isEqualTo(TrimSelection.MAX_MILLIS)
    }

    @Test
    fun `a window shorter than the minimum is grown to the minimum`() {
        val window = TrimSelection.clamp(startMillis = 10_000L, endMillis = 10_100L, durationMillis = tenMinutes)!!

        assertThat(window.lengthMillis).isEqualTo(TrimSelection.MIN_MILLIS)
    }

    @Test
    fun `growing to the minimum pulls the start back at the end of the file`() {
        // Asking for the last 100 ms cannot be satisfied by extending past the file, so the start moves.
        val window = TrimSelection.clamp(
            startMillis = tenMinutes - 100L,
            endMillis = tenMinutes,
            durationMillis = tenMinutes,
        )!!

        assertThat(window.endMillis).isEqualTo(tenMinutes)
        assertThat(window.lengthMillis).isEqualTo(TrimSelection.MIN_MILLIS)
    }

    @Test
    fun `a source shorter than the minimum yields the whole source`() {
        // The minimum yields to the file: a 300 ms notification sound is still a usable ringtone, and
        // refusing it would be the app inventing a rule the user cannot satisfy.
        val window = TrimSelection.clamp(startMillis = 0L, endMillis = 300L, durationMillis = 300L)!!

        assertThat(window.startMillis).isEqualTo(0L)
        assertThat(window.endMillis).isEqualTo(300L)
    }

    @Test
    fun `an unreadable duration yields no window instead of a guess`() {
        assertThat(TrimSelection.clamp(0L, 10_000L, durationMillis = 0L)).isNull()
        assertThat(TrimSelection.clamp(0L, 10_000L, durationMillis = -1L)).isNull()
    }

    @Test
    fun `negative inputs collapse to a valid window at the start of the file`() {
        val window = TrimSelection.clamp(startMillis = -5_000L, endMillis = -1_000L, durationMillis = tenMinutes)

        // Both ends clamp to zero, which is a zero-length request - so the minimum-length rule takes over
        // and produces the first half second rather than an empty clip.
        assertThat(window).isNotNull()
        assertThat(window!!.startMillis).isEqualTo(0L)
        assertThat(window.lengthMillis).isEqualTo(TrimSelection.MIN_MILLIS)
    }

    @Test
    fun `a zero-length request inside the file still yields the minimum`() {
        val window = TrimSelection.clamp(
            startMillis = 20_000L,
            endMillis = 20_000L,
            durationMillis = tenMinutes,
        )!!

        assertThat(window.startMillis).isEqualTo(20_000L)
        assertThat(window.lengthMillis).isEqualTo(TrimSelection.MIN_MILLIS)
    }

    // -------------------------------------------------------------------- nudge

    @Test
    fun `nudging the start moves only the start`() {
        val window = TrimSelection.Window(10_000L, 25_000L)

        val nudged = TrimSelection.nudge(window, moveStart = true, deltaMillis = 500L, durationMillis = tenMinutes)

        assertThat(nudged.startMillis).isEqualTo(10_500L)
        assertThat(nudged.endMillis).isEqualTo(25_000L)
    }

    @Test
    fun `nudging the end moves only the end`() {
        val window = TrimSelection.Window(10_000L, 25_000L)

        val nudged = TrimSelection.nudge(window, moveStart = false, deltaMillis = -500L, durationMillis = tenMinutes)

        assertThat(nudged.startMillis).isEqualTo(10_000L)
        assertThat(nudged.endMillis).isEqualTo(24_500L)
    }

    @Test
    fun `a nudge that would cross the other handle stops at the minimum length`() {
        val window = TrimSelection.Window(10_000L, 10_600L)

        // Dragging the start forward past the end cannot produce a negative window.
        val nudged = TrimSelection.nudge(window, moveStart = true, deltaMillis = 5_000L, durationMillis = tenMinutes)

        assertThat(nudged.lengthMillis).isGreaterThan(0L)
        assertThat(nudged.endMillis).isAtLeast(nudged.startMillis)
    }

    @Test
    fun `a nudge past the end of the file is clamped`() {
        val window = TrimSelection.Window(tenMinutes - 20_000L, tenMinutes - 1_000L)

        val nudged = TrimSelection.nudge(window, moveStart = false, deltaMillis = 60_000L, durationMillis = tenMinutes)

        assertThat(nudged.endMillis).isEqualTo(tenMinutes)
    }

    // ------------------------------------------------------------------ initial

    @Test
    fun `the initial window is the first fifteen seconds`() {
        val window = TrimSelection.initial(tenMinutes)!!

        assertThat(window.startMillis).isEqualTo(0L)
        assertThat(window.endMillis).isEqualTo(15_000L)
    }

    @Test
    fun `the initial window never exceeds a short file`() {
        val window = TrimSelection.initial(durationMillis = 4_000L)!!

        assertThat(window.endMillis).isEqualTo(4_000L)
    }

    @Test
    fun `an unknown duration has no initial window`() {
        assertThat(TrimSelection.initial(durationMillis = 0L)).isNull()
    }

    // -------------------------------------------------------------------- label

    @Test
    fun `the label shows the window and its length`() {
        val window = TrimSelection.Window(41_000L, 53_000L)

        assertThat(TrimSelection.label(window)).isEqualTo("0:41 - 0:53（12 秒）")
    }

    @Test
    fun `the label rounds a sub-second window up to one second`() {
        // "（0 秒）" would read as a broken clip; it is short, not empty.
        val window = TrimSelection.Window(0L, 500L)

        assertThat(TrimSelection.label(window)).contains("（1 秒）")
    }

    // ------------------------------------------------------------------ helpers

    @Test
    fun `durations format as minutes and padded seconds`() {
        assertThat(Waveform.formatMillis(0L)).isEqualTo("0:00")
        assertThat(Waveform.formatMillis(9_000L)).isEqualTo("0:09")
        assertThat(Waveform.formatMillis(65_000L)).isEqualTo("1:05")
        assertThat(Waveform.formatMillis(3_600_000L)).isEqualTo("60:00")
    }

    @Test
    fun `a negative duration formats as zero rather than as a minus`() {
        assertThat(Waveform.formatMillis(-1_000L)).isEqualTo("0:00")
    }
}
