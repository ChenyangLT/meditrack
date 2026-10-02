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
}
