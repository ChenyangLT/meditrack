package com.meditrack.domain.demo

import com.meditrack.data.local.entity.DosageForm
import com.meditrack.data.local.entity.DosageUnit
import com.meditrack.data.local.entity.FoodTiming
import com.meditrack.data.local.entity.Medication
import com.meditrack.data.local.entity.MedicationColorTag
import com.meditrack.data.local.entity.MedicationIcon
import com.meditrack.data.local.entity.MedicationReviewConfig
import com.meditrack.data.local.entity.ReviewCountMode

/**
 * One example medication, plus how it should be repeated.
 *
 * Kept as its own type rather than a `Medication` so the sample data cannot accidentally be inserted into
 * the user's table: converting one to a `Medication` happens in exactly one place, and that place is only
 * reached while demo mode is active.
 */
data class DemoMedication(
    val name: String,
    val icon: MedicationIcon,
    val colorTag: MedicationColorTag,
    val dosageForm: DosageForm,
    val unit: DosageUnit,
    val strength: String,
    val doseAmount: Double,
    val foodTiming: FoodTiming,
    val note: String,
    val timesOfDay: List<Int>,
    val review: MedicationReviewConfig? = null,
)

/**
 * The example prescription shown in demo mode.
 *
 * ## Why these particular drugs, and why they are not real advice
 *
 * The set is chosen to exercise every branch of the app rather than to be a plausible prescription for
 * anyone: a countable tablet, a capsule, a liquid with a half-unit step, a bedtime-only drug, and one
 * with a follow-up review configured. That is what a person demonstrating the app needs to be able to
 * show, and it is why the names are the most ordinary, least alarming ones available - the goal is that
 * nobody reading the screen mistakes the examples for a recommendation.
 *
 * Every entry is deliberately generic: no doses that could be acted on, no brand names, no condition
 * attached to a drug in a way that reads as "this is what you should take for that". The demo data exists
 * to show the *interface*, and the screen that enables it says so.
 *
 * The times are spread across a day so that whatever time it is, at least one entry is in the past, one is
 * soon and one is later - which is what makes the today screen's ordering, the overdue colouring and the
 * widget's priority sort all visible in one screenshot.
 */
object DemoModeContent {

    /** The example prescription. */
    val medications: List<DemoMedication> = listOf(
        DemoMedication(
            name = "示例降压药",
            icon = MedicationIcon.HEART,
            colorTag = MedicationColorTag.CORAL,
            dosageForm = DosageForm.TABLET,
            unit = DosageUnit.TABLET,
            strength = "5mg/片",
            doseAmount = 1.0,
            foodTiming = FoodTiming.AFTER_MEAL,
            note = "示例数据，用于演示，请勿据此服药",
            // Two times: one to be missed in the morning, one still to come.
            timesOfDay = listOf(8 * 60, 20 * 60),
            review = MedicationReviewConfig(
                reminderEnabled = true,
                note = "示例：3 个月后复查肝功能",
                countMode = ReviewCountMode.DOSES,
                threshold = 60.0,
            ),
        ),
        DemoMedication(
            name = "示例维生素",
            icon = MedicationIcon.VITAMIN,
            colorTag = MedicationColorTag.AMBER,
            dosageForm = DosageForm.CAPSULE,
            unit = DosageUnit.CAPSULE,
            strength = "400IU/粒",
            doseAmount = 1.0,
            foodTiming = FoodTiming.WITH_MEAL,
            note = "示例数据，用于演示，请勿据此服药",
            timesOfDay = listOf(12 * 60 + 30),
        ),
        DemoMedication(
            name = "示例止咳糖浆",
            icon = MedicationIcon.BOTTLE,
            colorTag = MedicationColorTag.TEAL,
            dosageForm = DosageForm.LIQUID,
            unit = DosageUnit.MILLILITER,
            strength = "100ml/瓶",
            // A fractional dose, so the half-step stepper is exercised.
            doseAmount = 10.0,
            foodTiming = FoodTiming.NONE,
            note = "示例数据，用于演示，请勿据此服药",
            timesOfDay = listOf(9 * 60, 15 * 60, 21 * 60),
            review = MedicationReviewConfig(
                reminderEnabled = true,
                note = "示例：吃满 3 天仍咳嗽请就医",
                countMode = ReviewCountMode.DAYS,
                threshold = 3.0,
            ),
        ),
        DemoMedication(
            name = "示例助眠药",
            icon = MedicationIcon.SLEEP,
            colorTag = MedicationColorTag.LAVENDER,
            dosageForm = DosageForm.TABLET,
            unit = DosageUnit.TABLET,
            strength = "10mg/片",
            doseAmount = 1.0,
            foodTiming = FoodTiming.BEDTIME,
            note = "示例数据，用于演示，请勿据此服药",
            timesOfDay = listOf(22 * 60 + 30),
        ),
    )

    /**
     * A stable, obviously-fake id space for the demo entries.
     *
     * Negative on purpose: real rows are auto-incrementing positives, so a demo dose can be told apart
     * from a real one by its id alone - which is what stops a demo row from ever being written to, or
     * confused with, the user's own data if a code path forgets to check the demo flag.
     */
    const val DEMO_ID_BASE = -1_000L

    /** The id of the [index]-th demo medication. */
    fun medicationIdAt(index: Int): Long = DEMO_ID_BASE - index

    /** True when [medicationId] belongs to the demo set rather than the user's own data. */
    fun isDemoId(medicationId: Long): Boolean = medicationId <= DEMO_ID_BASE

    /**
     * Demo medications as entities, for the read paths that render them.
     *
     * `reminderEnabled` is true so the pipeline treats them like anything else, and `isActive` is true for
     * the same reason; the *only* thing keeping them out of the database is that nothing here is ever
     * passed to a DAO `insert`. The demo flag is the single switch, and it is checked before this list is
     * consulted at all.
     */
    fun asMedications(): List<Medication> = medications.mapIndexed { index, demo ->
        Medication(
            id = medicationIdAt(index),
            name = demo.name,
            icon = demo.icon,
            colorTag = demo.colorTag,
            dosageForm = demo.dosageForm,
            unit = demo.unit,
            strength = demo.strength,
            doseAmount = demo.doseAmount,
            foodTiming = demo.foodTiming,
            note = demo.note,
            customSoundUri = null,
            reviewReminderEnabled = demo.review?.reminderEnabled ?: false,
            reviewNote = demo.review?.note.orEmpty(),
            reviewCountMode = demo.review?.countMode ?: ReviewCountMode.DOSES,
            reviewThreshold = demo.review?.threshold ?: 0.0,
        )
    }
}
