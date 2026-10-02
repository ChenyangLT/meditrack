package com.meditrack.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * One reminder slot for a medication: "08:00, every day, from 2025-01-01 until further notice".
 *
 * A medication with four daily times therefore owns four schedule rows. Keeping them as separate
 * rows (instead of a list column) lets the alarm scheduler query "which slots fire next" with a
 * plain indexed range scan.
 */
@Entity(
    tableName = "schedules",
    foreignKeys = [
        ForeignKey(
            entity = Medication::class,
            parentColumns = ["id"],
            childColumns = ["medicationId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("medicationId"), Index("minuteOfDay")],
)
data class Schedule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,

    val medicationId: Long,

    /** Minutes from local midnight; 08:00 is 480, 21:30 is 1290. */
    @ColumnInfo(name = "minuteOfDay") val minuteOfDay: Int,

    /** How this slot repeats. Stored as a compact string by [Converters]. */
    val repeatRule: RepeatingRule = RepeatingRule(),

    /** First day the slot is active (epoch day). */
    val startEpochDay: Long,

    /** Last day the slot is active, or null for "ongoing". */
    val endEpochDay: Long? = null,

    /** Per-slot reminder switch, independent of [Medication.reminderEnabled]. */
    val reminderEnabled: Boolean = true,

    val createdAt: Long = System.currentTimeMillis(),
) {

    /** True when this slot is due on [epochDay]. Pure date arithmetic, no Android dependency. */
    fun isActiveOn(epochDay: Long): Boolean {
        if (epochDay < startEpochDay) return false
        val end = endEpochDay
        if (end != null && epochDay > end) return false
        return repeatRule.matches(epochDay, startEpochDay)
    }

    /** "08:00" in the supplied format. */
    fun timeLabel(use24Hour: Boolean = true): String =
        com.meditrack.core.util.DateTimeUtils.formatMinuteOfDay(minuteOfDay, use24Hour)
}

/** A medication together with all of its reminder slots. */
data class MedicationWithSchedules(
    @Embedded val medication: Medication,
    @Relation(parentColumn = "id", entityColumn = "medicationId")
    val schedules: List<Schedule>,
) {
    /** Slot times sorted ascending, used by the medication list preview. */
    val sortedSchedules: List<Schedule> get() = schedules.sortedBy { it.minuteOfDay }
}
