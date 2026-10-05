package com.meditrack.audio

/**
 * The rules for a valid trim window.
 *
 * Extracted from the trimmer screen because the arithmetic is the part that has to be right and the
 * part that is invisible when it is wrong: a clip of zero length rings as a click, a clip longer than
 * the cap becomes a ringtone nobody wants, and a window that runs past the end of the file produces an
 * export that fails on the user's device with a message about codecs. None of those are visible in a
 * screenshot, so all of them are tested here.
 *
 * Pure: no Android types, no clock, no I/O.
 */
object TrimSelection {

    /** The shortest clip the trimmer will produce. */
    const val MIN_MILLIS = AudioTrimmer.MIN_CLIP_MILLIS

    /** The longest clip the trimmer will produce. */
    const val MAX_MILLIS = AudioTrimmer.MAX_CLIP_MILLIS

    /** A valid window inside a source of [durationMillis]. */
    data class Window(val startMillis: Long, val endMillis: Long) {
        val lengthMillis: Long get() = endMillis - startMillis
    }

    /**
     * Clamps a requested window into something the exporter will accept.
     *
     * The order of the clamps matters and is the reason this is a named function: the length has to be
     * fitted *inside* the file first, and only then can it be checked against the minimum - a 400 ms
     * source cannot yield a 500 ms clip, so the minimum yields to the file's own length rather than
     * producing a window that runs off the end.
     *
     * @param durationMillis the source's real length; 0 or negative means it could not be read, in which
     *        case no window is valid and null is returned rather than a guess
     */
    fun clamp(
        startMillis: Long,
        endMillis: Long,
        durationMillis: Long,
    ): Window? {
        if (durationMillis <= 0L) return null

        // 1. Fit inside the file.
        var start = startMillis.coerceIn(0L, durationMillis)
        var end = endMillis.coerceIn(0L, durationMillis)
        if (end < start) {
            // A dragged handle that crossed the other one is read as "make it zero length here" rather
            // than as an error, because that is what the gesture meant.
            val swap = start
            start = end
            end = swap
        }

        // 2. Fit inside the length cap, keeping the start where the user put it.
        if (end - start > MAX_MILLIS) end = start + MAX_MILLIS

        // 3. Fit inside the minimum, but only as far as the file allows.
        val shortest = minOf(MIN_MILLIS, durationMillis)
        if (end - start < shortest) {
            end = (start + shortest).coerceAtMost(durationMillis)
            // Pushing the end past the file means the start has to come back instead.
            if (end - start < shortest) start = (end - shortest).coerceAtLeast(0L)
        }

        if (end <= start) return null
        return Window(start, end)
    }

    /**
     * Moves one end of the window by [deltaMillis], keeping it valid.
     *
     * Used by the senior-friendly ±0.5 s nudges: dragging a handle on a phone is fine for a young user
     * and hopeless for the audience this app is built for, so every adjustment is also a button.
     */
    fun nudge(
        window: Window,
        moveStart: Boolean,
        deltaMillis: Long,
        durationMillis: Long,
    ): Window {
        val requestedStart = if (moveStart) window.startMillis + deltaMillis else window.startMillis
        val requestedEnd = if (moveStart) window.endMillis else window.endMillis + deltaMillis
        return clamp(requestedStart, requestedEnd, durationMillis) ?: window
    }

    /** A sensible initial window: the first [defaultLengthMillis] of the file, or the whole file. */
    fun initial(durationMillis: Long, defaultLengthMillis: Long = 15_000L): Window? =
        clamp(0L, minOf(defaultLengthMillis, durationMillis.coerceAtLeast(0L)), durationMillis)

    /** "0:41 - 0:53（12 秒）" - the line shown under the waveform. */
    fun label(window: Window): String {
        val seconds = (window.lengthMillis / 1_000L).coerceAtLeast(1L)
        return "${Waveform.formatMillis(window.startMillis)} - ${Waveform.formatMillis(window.endMillis)}" +
            "（$seconds 秒）"
    }
}
