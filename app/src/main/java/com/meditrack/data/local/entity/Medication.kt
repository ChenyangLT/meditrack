package com.meditrack.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A medication the user has added.
 *
 * This row is the single source of truth for "what should be taken at each dose". The per-dose
 * plan for a given day is materialised into [DoseLog] rows, which snapshot the planned amount so
 * that editing this table later never rewrites history.
 */
@Entity(
    tableName = "medications",
    indices = [Index(value = ["name"]), Index(value = ["isActive"])],
)
data class Medication(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,

    /** Free text, e.g. "阿司匹林肠溶片". */
    val name: String,

    /** Icon family; the UI maps it to a Material Symbol. */
    val icon: MedicationIcon = MedicationIcon.TABLET,

    /** Colour tag used by the today list and the widget. */
    val colorTag: MedicationColorTag = MedicationColorTag.MINT,

    /** Physical form: tablet, capsule, liquid, ... */
    val dosageForm: DosageForm = DosageForm.TABLET,

    /** Counting unit, normally derived from [dosageForm] but user overridable. */
    val unit: DosageUnit = DosageUnit.TABLET,

    /** Human readable strength such as "100mg/片". Not parsed, only displayed. */
    val strength: String = "",

    /** Amount taken per dose, e.g. 1 or 0.5. */
    val doseAmount: Double = 1.0,

    /**
     * Upper bound for one dose. When the user taps "+" past this, the UI asks
     * "是否确认多服？" before recording. 0 disables the extra confirmation.
     */
    val maxDoseAmount: Double = 0.0,

    /** Food / sleep relation, shown as a chip on the today card. */
    val foodTiming: FoodTiming = FoodTiming.NONE,

    /** Free-form note, e.g. "温水送服，不要空腹". */
    val note: String = "",

    /**
     * Remaining stock in [unit]. 0 means "not tracked". One [doseAmount] is subtracted on every
     * TAKEN/stepper adjustment and added back when the user steps the quantity down again.
     */
    val stockAmount: Double = 0.0,

    /** Warn (once per day) when the remaining stock drops to or below this. */
    val stockAlertThreshold: Double = 0.0,

    /** Whether the lapsed/upcoming doses for this medication raise notifications. */
    val reminderEnabled: Boolean = true,

    /** Disabled medications stay in history and the medication list but are not scheduled. */
    val isActive: Boolean = true,

    /** Optional per-medication reminder tone override. */
    val customSoundUri: String? = null,

    /**
     * Optional per-medication [RingClip] id, overriding the global reminder sound.
     *
     * A row id into `ring_clips` rather than a uri, so the clip's file is known to be one the app owns
     * and the cache cleaner can tell which files are still referenced.
     */
    val customRingClipId: Long? = null,

    // ------------------------------------------------------------ «复查提醒»

    /**
     * Whether this medication's follow-up (`复查`) reminder is armed.
     *
     * Defaults to true so that a medication which already carries a threshold starts being counted the
     * moment the user sets one - asking "do you also want to be reminded" a second time would be a
     * dialog whose only sensible answer is yes. It is switched to false automatically when the
     * threshold is reached, and back to true when the user starts a new round.
     */
    @ColumnInfo(defaultValue = "1")
    val reviewReminderEnabled: Boolean = true,

    /**
     * What the doctor said about the follow-up - "3 个月后复查肝功能" - shown verbatim.
     *
     * This is the *first* of the two answers the feature offers, and the one it trusts: the app cannot
     * know when a particular prescription needs a review, so it asks the person who was told.
     */
    @ColumnInfo(defaultValue = "''")
    val reviewNote: String = "",

    /** The question the search button opens; the drug name is prefixed automatically. */
    @ColumnInfo(defaultValue = "'吃多久需要去复查'")
    val reviewSearchQuery: String = "吃多久需要去复查",

    /** How the threshold is counted. Snapshotted into each round when it starts. */
    @ColumnInfo(defaultValue = "DOSES")
    val reviewCountMode: ReviewCountMode = ReviewCountMode.DOSES,

    /**
     * How much is allowed before the follow-up: doses taken, calendar days, or accumulated amount.
     *
     * 0 means "no review reminder configured", which is why the feature is inert by default even though
     * [reviewReminderEnabled] is true.
     */
    @ColumnInfo(defaultValue = "0")
    val reviewThreshold: Double = 0.0,

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {

    /**
     * The amount that must be reached for a dose to count as "已服用".
     * Countable forms are rounded up so a half-tablet prescription still resolves to one whole unit.
     */
    val plannedQuantity: Double
        get() = if (unit.allowsFraction) doseAmount else Math.ceil(doseAmount - 1e-9).coerceAtLeast(1.0)

    /** "1 片" / "0.5 ml". */
    val doseLabel: String
        get() = com.meditrack.core.util.QuantityFormatter.format(doseAmount, unit.label)

    /** "100mg/片 · 1 片" summary line. */
    val subtitle: String
        get() = listOf(strength, doseLabel).filter { it.isNotBlank() }.joinToString(" · ")

    /** The whole 复查 setting as a value, for the editor, the notifier and the widget. */
    val reviewConfig: MedicationReviewConfig
        get() = MedicationReviewConfig(
            reminderEnabled = reviewReminderEnabled,
            note = reviewNote,
            searchQuery = reviewSearchQuery,
            countMode = reviewCountMode,
            threshold = reviewThreshold,
        )
}
