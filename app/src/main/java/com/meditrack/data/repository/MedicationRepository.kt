package com.meditrack.data.repository

import com.meditrack.core.util.DateTimeUtils
import com.meditrack.core.util.QuantityFormatter
import com.meditrack.data.local.dao.MedicationDao
import com.meditrack.data.local.entity.Medication
import com.meditrack.data.local.entity.MedicationWithSchedules
import com.meditrack.data.local.entity.RepeatingRule
import com.meditrack.data.local.entity.Schedule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** One reminder slot of a medication, flattened for display. */
data class ScheduleSummary(
    val id: Long,
    val minuteOfDay: Int,
    /** "08:00" */
    val timeLabel: String,
    val repeatRule: RepeatingRule,
    /** "每天" / "每周一/三/五" */
    val repeatLabel: String,
    val startEpochDay: Long,
    val endEpochDay: Long?,
    val reminderEnabled: Boolean,
    /** False when the slot has not started yet or has already ended. */
    val isCurrentlyActive: Boolean,
)

/** A medication plus its derived display state, as the medication list and detail screen need it. */
data class MedicationUiModel(
    val medication: Medication,
    val schedules: List<ScheduleSummary>,
) {
    val id: Long get() = medication.id
    val name: String get() = medication.name
    val isActive: Boolean get() = medication.isActive
    val doseLabel: String get() = medication.doseLabel
    val subtitle: String get() = medication.subtitle
    val icon: com.meditrack.data.local.entity.MedicationIcon get() = medication.icon
    val colorTag: com.meditrack.data.local.entity.MedicationColorTag get() = medication.colorTag

    /** "08:00 · 12:30 · 18:00" preview used in the list row. */
    val timesLabel: String
        get() = if (schedules.isEmpty()) "未设置时间" else schedules.joinToString(" · ") { it.timeLabel }

    /** Distinct repeat descriptions, so four daily slots do not print "每天" four times. */
    val repeatLabel: String
        get() = schedules.map { it.repeatLabel }.distinct().joinToString(" / ").ifBlank { "无" }

    /** True when the stock is tracked and has fallen to the warning threshold. */
    val isStockLow: Boolean
        get() = medication.stockAmount > 0.0 &&
            medication.stockAlertThreshold > 0.0 &&
            medication.stockAmount <= medication.stockAlertThreshold

    val isOutOfStock: Boolean get() = medication.stockAmount <= 0.0 && medication.stockAlertThreshold > 0.0

    /** "剩 12 片" or "" when stock is not tracked. */
    val stockLabel: String
        get() = if (medication.stockAmount <= 0.0) "" else
            "剩 " + QuantityFormatter.format(medication.stockAmount, medication.unit.label)

    /** Number of slots that fire today, for the "今日 3 次" chip. */
    fun slotsToday(epochDay: Long = DateTimeUtils.todayEpochDay()): Int =
        schedules.count { it.isCurrentlyActive }
}

/**
 * Owns everything about "what medication exists and when should it be taken".
 *
 * All writes are transactional at the DAO level and every mutation returns the affected id so the
 * UI can navigate to the detail screen right after creating something.
 */
@Singleton
class MedicationRepository @Inject constructor(
    private val medicationDao: MedicationDao,
) {

    fun observeMedications(activeOnly: Boolean = false): Flow<List<MedicationUiModel>> {
        val source = if (activeOnly) medicationDao.observeActiveWithSchedules()
        else medicationDao.observeAllWithSchedules()
        return source.map { list -> list.map { it.toUiModel() } }
    }

    fun observeMedication(id: Long): Flow<Medication?> = medicationDao.observeById(id)

    fun observeSchedules(id: Long): Flow<List<ScheduleSummary>> =
        medicationDao.observeSchedulesFor(id).map { slots ->
            val today = DateTimeUtils.todayEpochDay()
            slots.map { it.toSummary(today) }
        }

    suspend fun getWithSchedules(id: Long): MedicationWithSchedules? =
        medicationDao.getWithSchedulesById(id)

    suspend fun getActiveWithSchedules(): List<MedicationWithSchedules> =
        medicationDao.getActiveWithSchedulesOnce()

    suspend fun getAll(): List<Medication> = medicationDao.getAllOnce()

    /**
     * Creates or updates a medication together with its slots.
     *
     * The medication row and its schedules are written in one transaction by
     * [MedicationDao.replaceSchedules]; slots whose time did not change keep their id so the dose
     * history attached to them survives the edit.
     *
     * @param existingId 0 to insert, otherwise the id being edited
     * @return the medication id
     */
    suspend fun save(
        medication: Medication,
        slotTimes: List<SlotDraft>,
        existingId: Long = medication.id,
    ): Long {
        val now = System.currentTimeMillis()
        val medId = if (existingId == 0L) {
            medicationDao.insert(medication.copy(id = 0L, createdAt = now, updatedAt = now))
        } else {
            medicationDao.update(medication.copy(id = existingId, updatedAt = now))
            existingId
        }

        val slots = slotTimes.map { draft ->
            val start = draft.startEpochDay
            Schedule(
                medicationId = medId,
                minuteOfDay = draft.minuteOfDay,
                repeatRule = draft.repeatRule.copy(anchorEpochDay = start),
                startEpochDay = start,
                endEpochDay = draft.endEpochDay,
                reminderEnabled = draft.reminderEnabled,
            )
        }
        medicationDao.replaceSchedules(medId, slots)
        return medId
    }

    suspend fun setActive(id: Long, active: Boolean) = medicationDao.setActive(id, active)

    suspend fun delete(id: Long) = medicationDao.deleteById(id)

    suspend fun setStock(id: Long, amount: Double) = medicationDao.setStock(id, amount.coerceAtLeast(0.0))

    /** Applies a signed stock change, clamped at zero by the DAO expression. */
    suspend fun adjustStock(id: Long, delta: Double) = medicationDao.adjustStock(id, delta)

    /** Convenience used by the detail screen's "记录一次补货". */
    suspend fun addStock(id: Long, amount: Double) {
        val current = medicationDao.getWithSchedulesById(id)?.medication?.stockAmount ?: 0.0
        medicationDao.setStock(id, current + amount)
    }

    /** Draft of one reminder slot produced by the editor screen. */
    data class SlotDraft(
        val minuteOfDay: Int,
        val repeatRule: RepeatingRule = RepeatingRule(),
        val startEpochDay: Long = DateTimeUtils.todayEpochDay(),
        val endEpochDay: Long? = null,
        val reminderEnabled: Boolean = true,
    )

    private fun MedicationWithSchedules.toUiModel(): MedicationUiModel {
        val today = DateTimeUtils.todayEpochDay()
        return MedicationUiModel(
            medication = medication,
            schedules = schedules.sortedBy { it.minuteOfDay }.map { it.toSummary(today) },
        )
    }

    private fun Schedule.toSummary(today: Long): ScheduleSummary = ScheduleSummary(
        id = id,
        minuteOfDay = minuteOfDay,
        timeLabel = DateTimeUtils.formatMinuteOfDay(minuteOfDay),
        repeatRule = repeatRule,
        repeatLabel = repeatRule.describe(),
        startEpochDay = startEpochDay,
        endEpochDay = endEpochDay,
        reminderEnabled = reminderEnabled,
        isCurrentlyActive = isActiveOn(today),
    )

    companion object {
        /** True when [date] is before the slot's start day. */
        fun startsInFuture(slot: ScheduleSummary, today: Long = DateTimeUtils.todayEpochDay()): Boolean =
            slot.startEpochDay > today

        /** Days remaining until the course ends, or null when it is open ended. */
        fun daysRemaining(slot: ScheduleSummary, today: Long = DateTimeUtils.todayEpochDay()): Long? =
            slot.endEpochDay?.let { it - today }

        /** Builds the anchor used by "每 N 天" and "吃 X 天停 Y 天". */
        fun anchorFor(date: LocalDate): Long = date.toEpochDay()
    }
}
