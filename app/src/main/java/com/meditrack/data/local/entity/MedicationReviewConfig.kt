package com.meditrack.data.local.entity

/**
 * The «复查提醒» configured for one medication - the whole feature, as a value.
 *
 * ## Why this lives on the medication row rather than in a table of its own
 *
 * A review *setting* is one row per medication by definition, and the user edits it in the same form as
 * the dose and the schedule. Storing it beside them means the medication editor saves one entity, the
 * backup carries it for free, and the medication list can render "还差 3 次" without a join.
 *
 * What lives in its own table is the *progress* ([MedicationReviewCycle]): rounds accumulate, and a
 * round has a start date, a mode and a completion that the settings screen shows as history. The
 * split is "configuration here, history there".
 *
 * @param reminderEnabled the master switch for this medication's follow-up reminder. Set to false
 *        automatically the moment the threshold is reached - that is the user's "到了后自动关闭当前
 *        药品提醒" - and set back to true when they start a new round.
 * @param note what the doctor actually said, shown verbatim on the lock screen and in the red dialog.
 * @param searchQuery the question the search button opens. Defaults to the requirements' own phrasing.
 * @param countMode how the threshold is counted. A new round snapshots it, so changing this later
 *        cannot rewrite a round that is already in progress.
 * @param threshold how much is allowed before the review: doses, days, or accumulated amount.
 */
data class MedicationReviewConfig(
    val reminderEnabled: Boolean = false,
    val note: String = "",
    val searchQuery: String = ReviewSearchQuery.DEFAULT_QUESTION,
    val countMode: ReviewCountMode = ReviewCountMode.DOSES,
    val threshold: Double = 0.0,
) {

    /** True when the user has described a review at all; without one nothing may fire. */
    val isConfigured: Boolean get() = threshold > 0.0

    /** True when the app should be counting and reminding for this medication. */
    val isActive: Boolean get() = reminderEnabled && isConfigured

    /** "每 60 次复查" / "每 30 天复查" - the one-line summary the settings list shows. */
    fun thresholdLabel(unit: String): String = when (countMode) {
        ReviewCountMode.DAYS -> "每 ${threshold.toInt()} 天复查"
        ReviewCountMode.QUANTITY ->
            "每 ${com.meditrack.core.util.QuantityFormatter.format(threshold)} $unit 复查"
        ReviewCountMode.DOSES -> "每 ${threshold.toInt()} 次复查"
    }

    companion object {
        /** Offered thresholds, so the common prescriptions are one tap instead of typing a number. */
        val DOSE_PRESETS = listOf(3, 7, 14, 30, 60, 90, 180)
        val DAY_PRESETS = listOf(7, 14, 30, 60, 90, 180, 365)
    }
}
