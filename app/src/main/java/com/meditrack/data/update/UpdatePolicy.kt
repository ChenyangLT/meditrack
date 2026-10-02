package com.meditrack.data.update

/**
 * When the app is allowed to check, and when it is allowed to interrupt.
 *
 * Split out of [UpdateRepository] because both questions are pure and both are the kind of thing that
 * fails silently: an interval compared the wrong way means updates stop being offered, and a
 * dismissed-version check compared the wrong way means the same prompt comes back every launch.
 */
object UpdatePolicy {

    /** Twice a day is plenty for a release cadence measured in weeks. */
    const val MIN_INTERVAL_MILLIS = 12L * 60L * 60L * 1000L

    /**
     * Whether a check should go out now.
     *
     * A manual check ([force]) overrides both the switch and the interval: the user asking is the
     * strongest possible signal. A clock that jumped backwards leaves a negative elapsed time, which
     * reads as "too soon" and simply skips the check rather than hammering the API.
     */
    fun shouldCheck(
        autoCheckEnabled: Boolean,
        force: Boolean,
        lastCheckAtMillis: Long,
        nowMillis: Long,
        intervalMillis: Long = MIN_INTERVAL_MILLIS,
    ): Boolean {
        if (force) return true
        if (!autoCheckEnabled) return false
        return nowMillis - lastCheckAtMillis >= intervalMillis
    }

    /**
     * Whether finding [tag] should raise a dialog.
     *
     * A version the user already waved away stays quiet until something *newer* appears - which is why
     * the comparison is against the tag they dismissed, not against "anything newer than installed".
     */
    fun shouldInterrupt(force: Boolean, dismissedTag: String?, tag: String): Boolean =
        force || dismissedTag != tag
}
