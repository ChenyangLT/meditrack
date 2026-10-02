package com.meditrack.ui.medications

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meditrack.core.util.DateTimeUtils
import com.meditrack.data.local.entity.DosageForm
import com.meditrack.data.local.entity.DosageUnit
import com.meditrack.data.local.entity.FoodTiming
import com.meditrack.data.local.entity.Medication
import com.meditrack.data.local.entity.MedicationColorTag
import com.meditrack.data.local.entity.MedicationIcon
import com.meditrack.data.local.entity.RepeatRuleType
import com.meditrack.data.local.entity.RepeatingRule
import com.meditrack.data.repository.MedicationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One reminder time being edited, before it is persisted as a schedule row. */
data class SlotForm(
    /** Stable key for the list so removing a middle row does not recompose the wrong one. */
    val key: Long,
    val minuteOfDay: Int,
    val repeatType: RepeatRuleType = RepeatRuleType.DAILY,
    val intervalDays: Int = 2,
    val daysOfWeek: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7),
    /** Day numbers (1..31) for [RepeatRuleType.MONTHLY_DATES]. Defaults to the 1st. */
    val daysOfMonth: Set<Int> = setOf(1),
    val cycleOnDays: Int = 5,
    val cycleOffDays: Int = 2,
    val reminderEnabled: Boolean = true,
)

/**
 * The whole editable state of the medication editor.
 *
 * The form is a plain data class instead of a dozen `mutableStateOf` fields so that:
 *  - validation is a pure function of the state;
 *  - the "unsaved changes" check is a simple equality test against the loaded snapshot;
 *  - a configuration change cannot desynchronise half-updated fields.
 */
data class MedicationForm(
    val id: Long = 0L,
    val name: String = "",
    val icon: MedicationIcon = MedicationIcon.TABLET,
    val colorTag: MedicationColorTag = MedicationColorTag.MINT,
    val dosageForm: DosageForm = DosageForm.TABLET,
    val unit: DosageUnit = DosageUnit.TABLET,
    val strength: String = "",
    val doseAmount: String = "1",
    val maxDoseAmount: String = "0",
    val foodTiming: FoodTiming = FoodTiming.NONE,
    val note: String = "",
    val stockAmount: String = "0",
    val stockAlertThreshold: String = "0",
    val reminderEnabled: Boolean = true,
    val isActive: Boolean = true,
    val startEpochDay: Long = DateTimeUtils.todayEpochDay(),
    val endEpochDay: Long? = null,
    val slots: List<SlotForm> = emptyList(),
) {
    val nameError: Boolean get() = name.isBlank()
    val slotsError: Boolean get() = slots.isEmpty()

    /** Parsed dose per intake; falls back to 1 so the save button never produces a zero dose. */
    val doseValue: Double get() = doseAmount.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 1.0
    val maxDoseValue: Double get() = maxDoseAmount.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
    val stockValue: Double get() = stockAmount.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
    val stockAlertValue: Double get() = stockAlertThreshold.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0

    val isValid: Boolean get() = !nameError && !slotsError

    /** True while the user is still filling in a half-typed dose. */
    val doseInvalid: Boolean get() = doseAmount.toDoubleOrNull() == null

    fun toMedication(now: Long = System.currentTimeMillis()): Medication = Medication(
        id = id,
        name = name.trim(),
        icon = icon,
        colorTag = colorTag,
        dosageForm = dosageForm,
        unit = unit,
        strength = strength.trim(),
        doseAmount = doseValue,
        maxDoseAmount = maxDoseValue,
        foodTiming = foodTiming,
        note = note.trim(),
        stockAmount = stockValue,
        stockAlertThreshold = stockAlertValue,
        reminderEnabled = reminderEnabled,
        isActive = isActive,
        updatedAt = now,
    )

    /** Maps the form slots onto the repository drafts. */
    fun toDrafts(): List<MedicationRepository.SlotDraft> = slots.map { slot ->
        val rule = when (slot.repeatType) {
            RepeatRuleType.DAILY -> RepeatingRule(type = RepeatRuleType.DAILY)
            RepeatRuleType.EVERY_OTHER_DAY -> RepeatingRule(type = RepeatRuleType.EVERY_OTHER_DAY)
            RepeatRuleType.WEEKLY -> RepeatingRule(
                type = RepeatRuleType.WEEKLY,
                daysOfWeek = slot.daysOfWeek,
            )
            RepeatRuleType.EVERY_N_DAYS -> RepeatingRule(
                type = RepeatRuleType.EVERY_N_DAYS,
                intervalDays = slot.intervalDays.coerceAtLeast(1),
                anchorEpochDay = startEpochDay,
            )
            RepeatRuleType.CYCLE -> RepeatingRule(
                type = RepeatRuleType.CYCLE,
                cycleOnDays = slot.cycleOnDays.coerceAtLeast(1),
                cycleOffDays = slot.cycleOffDays.coerceAtLeast(0),
                anchorEpochDay = startEpochDay,
            )
            // Falls back to the 1st so a rule the user switched to but never configured still
            // produces a valid, explainable schedule instead of never firing.
            RepeatRuleType.MONTHLY_DATES -> RepeatingRule(
                type = RepeatRuleType.MONTHLY_DATES,
                daysOfMonth = slot.daysOfMonth.ifEmpty { setOf(1) },
            )
        }
        MedicationRepository.SlotDraft(
            minuteOfDay = slot.minuteOfDay,
            repeatRule = rule,
            startEpochDay = startEpochDay,
            endEpochDay = endEpochDay,
            reminderEnabled = slot.reminderEnabled,
        )
    }
}

/**
 * Backs the add/edit medication screen.
 *
 * The id is read from [SavedStateHandle], which is populated from the navigation argument, so the
 * screen can be deep-linked from the today list, the medication list or a notification without any
 * extra plumbing.
 */
@HiltViewModel
class MedicationEditorViewModel @Inject constructor(
    private val repository: MedicationRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val medicationId: Long = savedStateHandle.get<Long>(ARG_MEDICATION_ID) ?: 0L

    private val _form = MutableStateFlow(MedicationForm())
    val form: StateFlow<MedicationForm> = _form.asStateFlow()

    private val _saved = MutableStateFlow(false)
    /** Set once the write succeeded; the screen observes this to navigate back. */
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Monotonic counter for slot keys; never reused so list animations stay stable. */
    private var nextSlotKey = 1L

    val isEditing: Boolean get() = medicationId > 0L

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        if (medicationId <= 0L) {
            // New medication: seed one 08:00 slot so the screen never opens completely empty and
            // the first-time user has an obvious thing to adjust.
            _form.value = MedicationForm(
                slots = listOf(newSlot(minuteOfDay = 8 * 60)),
            )
            _loaded.value = true
            return
        }

        val existing = repository.getWithSchedules(medicationId)
        if (existing == null) {
            _error.value = "找不到这个药品，可能已被删除"
            _loaded.value = true
            return
        }

        val med = existing.medication
        val slots = existing.schedules.sortedBy { it.minuteOfDay }.map { schedule ->
            val rule = schedule.repeatRule
            SlotForm(
                key = nextSlotKey++,
                minuteOfDay = schedule.minuteOfDay,
                repeatType = rule.type,
                intervalDays = rule.intervalDays,
                daysOfWeek = rule.daysOfWeek.ifEmpty { setOf(1, 2, 3, 4, 5, 6, 7) },
                daysOfMonth = rule.daysOfMonth.ifEmpty { setOf(1) },
                cycleOnDays = rule.cycleOnDays,
                cycleOffDays = rule.cycleOffDays,
                reminderEnabled = schedule.reminderEnabled,
            )
        }

        _form.value = MedicationForm(
            id = med.id,
            name = med.name,
            icon = med.icon,
            colorTag = med.colorTag,
            dosageForm = med.dosageForm,
            unit = med.unit,
            strength = med.strength,
            doseAmount = com.meditrack.core.util.QuantityFormatter.format(med.doseAmount),
            maxDoseAmount = com.meditrack.core.util.QuantityFormatter.format(med.maxDoseAmount),
            foodTiming = med.foodTiming,
            note = med.note,
            stockAmount = com.meditrack.core.util.QuantityFormatter.format(med.stockAmount),
            stockAlertThreshold = com.meditrack.core.util.QuantityFormatter.format(med.stockAlertThreshold),
            reminderEnabled = med.reminderEnabled,
            isActive = med.isActive,
            startEpochDay = slots.firstOrNull()?.let { _ ->
                existing.schedules.minOfOrNull { it.startEpochDay }
            } ?: DateTimeUtils.todayEpochDay(),
            endEpochDay = existing.schedules.mapNotNull { it.endEpochDay }.minOrNull(),
            slots = slots,
        )
        _loaded.value = true
    }

    // ------------------------------------------------------------- mutations

    fun setName(value: String) = _form.update { it.copy(name = value) }
    fun setIcon(value: MedicationIcon) = _form.update { it.copy(icon = value) }
    fun setColorTag(value: MedicationColorTag) = _form.update { it.copy(colorTag = value) }
    fun setNote(value: String) = _form.update { it.copy(note = value) }
    fun setStrength(value: String) = _form.update { it.copy(strength = value) }
    fun setDoseAmount(value: String) = _form.update { it.copy(doseAmount = value) }
    fun setMaxDoseAmount(value: String) = _form.update { it.copy(maxDoseAmount = value) }
    fun setStockAmount(value: String) = _form.update { it.copy(stockAmount = value) }
    fun setStockAlertThreshold(value: String) = _form.update { it.copy(stockAlertThreshold = value) }
    fun setFoodTiming(value: FoodTiming) = _form.update { it.copy(foodTiming = value) }
    fun setReminderEnabled(value: Boolean) = _form.update { it.copy(reminderEnabled = value) }
    fun setIsActive(value: Boolean) = _form.update { it.copy(isActive = value) }
    fun setStartEpochDay(value: Long) = _form.update { it.copy(startEpochDay = value) }
    fun setEndEpochDay(value: Long?) = _form.update { it.copy(endEpochDay = value) }

    /**
     * Changes the dosage form and follows the unit along with it.
     *
     * Switching to "liquid" and being left on the "片" unit is the single most likely data-entry
     * mistake in this screen, so the unit is defaulted from the form. The user can still override it.
     */
    fun setDosageForm(value: DosageForm) = _form.update {
        it.copy(
            dosageForm = value,
            unit = value.defaultUnit,
            icon = com.meditrack.core.util.MedicationVisuals.iconFor(value),
        )
    }

    fun setUnit(value: DosageUnit) = _form.update { it.copy(unit = value) }

    fun addSlot() {
        // Default the new slot one hour after the last one, wrapping inside the day.
        val last = _form.value.slots.maxOfOrNull { it.minuteOfDay }
        val minute = if (last == null) 8 * 60 else ((last + 60) % (24 * 60))
        _form.update { it.copy(slots = it.slots + newSlot(minute)) }
    }

    fun removeSlot(key: Long) = _form.update { form ->
        form.copy(slots = form.slots.filterNot { it.key == key })
    }

    fun updateSlot(key: Long, transform: (SlotForm) -> SlotForm) = _form.update { form ->
        form.copy(slots = form.slots.map { if (it.key == key) transform(it) else it })
    }

    fun setSlotTime(key: Long, minuteOfDay: Int) = updateSlot(key) { it.copy(minuteOfDay = minuteOfDay) }

    fun setSlotRepeatType(key: Long, type: RepeatRuleType) =
        updateSlot(key) { it.copy(repeatType = type) }

    fun setSlotInterval(key: Long, days: Int) =
        updateSlot(key) { it.copy(intervalDays = days.coerceIn(1, 30)) }

    fun toggleSlotWeekday(key: Long, isoDay: Int) = updateSlot(key) { slot ->
        val next = if (slot.daysOfWeek.contains(isoDay)) slot.daysOfWeek - isoDay
        else slot.daysOfWeek + isoDay
        // A weekly rule with no day selected would never fire; refuse to empty the set.
        slot.copy(daysOfWeek = next.ifEmpty { slot.daysOfWeek })
    }

    fun setSlotCycle(key: Long, onDays: Int, offDays: Int) = updateSlot(key) {
        it.copy(cycleOnDays = onDays.coerceAtLeast(1), cycleOffDays = offDays.coerceAtLeast(0))
    }

    /**
     * Adds or removes one day of the month.
     *
     * Refuses to empty the set for the same reason the weekday picker does: a monthly rule with no
     * day selected would never fire, and silently producing a dead schedule is the worst outcome
     * for a medication reminder.
     */
    fun toggleSlotMonthDay(key: Long, day: Int) = updateSlot(key) { slot ->
        if (day !in 1..31) return@updateSlot slot
        val next = if (slot.daysOfMonth.contains(day)) slot.daysOfMonth - day
        else slot.daysOfMonth + day
        slot.copy(daysOfMonth = next.ifEmpty { slot.daysOfMonth })
    }

    /** Selects a whole calendar date by picking its day-of-month (the month itself repeats). */
    fun selectSlotMonthDate(key: Long, date: java.time.LocalDate) =
        toggleSlotMonthDay(key, date.dayOfMonth)

    fun setSlotReminderEnabled(key: Long, enabled: Boolean) =
        updateSlot(key) { it.copy(reminderEnabled = enabled) }

    // ----------------------------------------------------------------- save

    fun save() {
        val current = _form.value
        if (!current.isValid) {
            _error.value = when {
                current.nameError -> "请填写药品名称"
                else -> "请至少添加一个服药时间"
            }
            return
        }
        viewModelScope.launch {
            runCatching {
                repository.save(
                    medication = current.toMedication(),
                    slotTimes = current.toDrafts(),
                    existingId = current.id,
                )
            }.onSuccess {
                _saved.value = true
            }.onFailure {
                _error.value = it.message ?: "保存失败"
            }
        }
    }

    fun delete() {
        if (medicationId <= 0L) return
        viewModelScope.launch {
            runCatching { repository.delete(medicationId) }
                .onSuccess { _saved.value = true }
                .onFailure { _error.value = it.message ?: "删除失败" }
        }
    }

    fun onErrorShown() {
        _error.value = null
    }

    private fun newSlot(minuteOfDay: Int) = SlotForm(
        key = nextSlotKey++,
        minuteOfDay = minuteOfDay.coerceIn(0, 23 * 60 + 59),
    )

    companion object {
        /**
         * SavedStateHandle key for the medication id, shared with
         * [com.meditrack.Routes.ARG_MEDICATION_ID]. It must equal the query-parameter placeholder
         * name used in the destination route, otherwise the value never arrives and the screen
         * would silently behave as "add new".
         */
        const val ARG_MEDICATION_ID = com.meditrack.Routes.ARG_MEDICATION_ID
    }
}
