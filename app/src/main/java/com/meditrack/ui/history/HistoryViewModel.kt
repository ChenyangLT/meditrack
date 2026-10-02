package com.meditrack.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meditrack.core.util.DateTimeUtils
import com.meditrack.core.util.QuantityFormatter
import com.meditrack.data.local.entity.DoseLog
import com.meditrack.data.local.entity.DoseStatus
import com.meditrack.data.repository.DoseRepository
import com.meditrack.data.repository.MedicationUiModel
import com.meditrack.data.repository.MedicationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

/** How much history the statistics cover. */
enum class HistoryRange(val label: String, val days: Long) {
    WEEK("近 7 天", 7),
    MONTH("近 30 天", 30),
    QUARTER("近 90 天", 90),
}

/**
 * The adherence figure for one day, used to colour a calendar cell.
 *
 * [NONE] is distinct from [MISSED]: a day with no scheduled doses (a "吃 5 天停 2 天" pause, or a
 * medication that had not started yet) must not be counted against the user.
 */
enum class DayAdherence { TAKEN, PARTIAL, MISSED, SKIPPED, NONE }

/** One cell of the month grid. */
data class CalendarDay(
    val epochDay: Long,
    val dayOfMonth: Int,
    val adherence: DayAdherence,
    val totalDoses: Int,
    val completedDoses: Int,
    val takenQuantity: Double,
    val plannedQuantity: Double,
    val unitLabel: String,
    val isToday: Boolean,
    /** Days spilling in from the previous/next month, rendered dimmed. */
    val isCurrentMonth: Boolean,
) {
    val hasRecords: Boolean get() = totalDoses > 0
}

/** Aggregated statistics over the selected range. */
data class HistoryStats(
    val range: HistoryRange,
    val medicationId: Long?,
    val totalDoses: Int = 0,
    val takenDoses: Int = 0,
    val partialDoses: Int = 0,
    val missedDoses: Int = 0,
    val skippedDoses: Int = 0,
    val plannedQuantity: Double = 0.0,
    val takenQuantity: Double = 0.0,
    val unitLabel: String = "",
    /** Days in a row (ending today or yesterday) with at least one recorded intake. */
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
) {
    /**
     * Adherence = doses fully taken / doses that were actually actionable.
     *
     * Skipped doses are excluded from the denominator: the user made a deliberate, recorded
     * decision, and counting it as non-adherence would punish honesty and push people to stop
     * recording.
     */
    val adherencePercent: Int
        get() {
            val denominator = takenDoses + partialDoses + missedDoses
            if (denominator == 0) return 0
            return ((takenDoses.toDouble() / denominator.toDouble()) * 100.0)
                .toInt()
                .coerceIn(0, 100)
        }

    val hasData: Boolean get() = totalDoses > 0

    val takenLabel: String get() = QuantityFormatter.formatProgress(takenQuantity, plannedQuantity, unitLabel)
}

data class HistoryUiState(
    val month: YearMonth = YearMonth.now(),
    val days: List<CalendarDay> = emptyList(),
    val selectedEpochDay: Long = DateTimeUtils.todayEpochDay(),
    val selectedDayDoses: List<DayDoseRow> = emptyList(),
    val stats: HistoryStats = HistoryStats(HistoryRange.MONTH, null),
    val medications: List<MedicationUiModel> = emptyList(),
    val isLoading: Boolean = true,
)

/** One row in the selected-day detail list. */
data class DayDoseRow(
    val doseId: Long,
    val medicationName: String,
    val timeLabel: String,
    val takenQuantity: Double,
    val plannedQuantity: Double,
    val unitLabel: String,
    val status: DoseStatus,
)

/**
 * Statistics and the calendar.
 *
 * Statistics are computed in Kotlin from a single range query rather than in SQL. The queries are
 * small (a quarter is at most a few hundred rows), and keeping the maths in one readable place
 * makes the adherence definition testable and easy to change - which matters, because "依从率" is
 * the number a user may show their doctor.
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val doseRepository: DoseRepository,
    private val medicationRepository: MedicationRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    private var range: HistoryRange = HistoryRange.MONTH
    private var medicationFilter: Long? = null

    init {
        viewModelScope.launch {
            medicationRepository.observeMedications().collect { meds ->
                _uiState.update { it.copy(medications = meds) }
            }
        }
        viewModelScope.launch { refresh() }
    }

    // ------------------------------------------------------------- navigation

    fun showPreviousMonth() {
        _uiState.update { it.copy(month = it.month.minusMonths(1)) }
        viewModelScope.launch { refresh() }
    }

    fun showNextMonth() {
        _uiState.update { it.copy(month = it.month.plusMonths(1)) }
        viewModelScope.launch { refresh() }
    }

    fun showCurrentMonth() {
        _uiState.update { it.copy(month = YearMonth.now()) }
        viewModelScope.launch { refresh() }
    }

    fun selectDay(epochDay: Long) {
        _uiState.update { it.copy(selectedEpochDay = epochDay) }
        viewModelScope.launch { loadDayDetail(epochDay) }
    }

    fun setRange(value: HistoryRange) {
        range = value
        viewModelScope.launch { refresh() }
    }

    fun setMedicationFilter(medicationId: Long?) {
        medicationFilter = medicationId
        viewModelScope.launch { refresh() }
    }

    // ----------------------------------------------------------------- loading

    private suspend fun refresh() {
        _uiState.update { it.copy(isLoading = true) }
        val month = _uiState.value.month
        val days = buildCalendar(month)
        val stats = computeStats()
        _uiState.update { it.copy(days = days, stats = stats, isLoading = false) }
        loadDayDetail(_uiState.value.selectedEpochDay)
    }

    /**
     * Builds the month grid.
     *
     * The grid is padded to whole weeks so the weekday header always lines up, and the pad days are
     * marked [CalendarDay.isCurrentMonth] = false so they render dimmed but remain tappable.
     */
    private suspend fun buildCalendar(month: YearMonth): List<CalendarDay> {
        val firstOfMonth = month.atDay(1)
        val today = DateTimeUtils.todayEpochDay()

        // Pad to the start of the week (Monday-based; the settings screen can change this later
        // and only this offset needs to change).
        val leadingPad = (firstOfMonth.dayOfWeek.value - 1)
        val gridStart = firstOfMonth.minusDays(leadingPad.toLong())
        val totalCells = leadingPad + month.lengthOfMonth()
        // Round up to a whole number of weeks.
        val trailingPad = (7 - (totalCells % 7)) % 7
        val cellCount = totalCells + trailingPad

        val rangeStart = gridStart.toEpochDay()
        val rangeEnd = gridStart.plusDays((cellCount - 1).toLong()).toEpochDay()
        val doses = doseRepository.getDosesBetween(rangeStart, rangeEnd)
            .filter { medicationFilter == null || it.medicationId == medicationFilter }

        val byDay = doses.groupBy { it.epochDay }

        return (0 until cellCount).map { index ->
            val date: LocalDate = gridStart.plusDays(index.toLong())
            val epochDay = date.toEpochDay()
            val dayDoses = byDay[epochDay].orEmpty()
            CalendarDay(
                epochDay = epochDay,
                dayOfMonth = date.dayOfMonth,
                adherence = classify(dayDoses),
                totalDoses = dayDoses.size,
                completedDoses = dayDoses.count { it.status == DoseStatus.TAKEN },
                takenQuantity = dayDoses.sumOf { it.takenQuantity },
                plannedQuantity = dayDoses.sumOf { it.plannedQuantity },
                unitLabel = dayDoses.firstOrNull()?.plannedUnit ?: "",
                isToday = epochDay == today,
                isCurrentMonth = date.month == month.month,
            )
        }
    }

    /**
     * Reduces a day's doses to one colour.
     *
     * Priority: any miss makes the day red (a miss is the thing worth noticing), otherwise any
     * partial makes it amber, otherwise all-taken is green. A day with only skips is grey.
     */
    private fun classify(doses: List<DoseLog>): DayAdherence {
        if (doses.isEmpty()) return DayAdherence.NONE
        if (doses.any { it.status == DoseStatus.MISSED }) return DayAdherence.MISSED
        if (doses.any { it.status == DoseStatus.PARTIAL }) return DayAdherence.PARTIAL
        if (doses.all { it.status == DoseStatus.SKIPPED }) return DayAdherence.SKIPPED
        if (doses.any { it.status == DoseStatus.TAKEN }) {
            val allResolved = doses.all {
                it.status == DoseStatus.TAKEN || it.status == DoseStatus.SKIPPED
            }
            return if (allResolved) DayAdherence.TAKEN else DayAdherence.PARTIAL
        }
        return DayAdherence.NONE
    }

    private suspend fun loadDayDetail(epochDay: Long) {
        val rows = doseRepository.getDayRows(epochDay).map { row ->
            DayDoseRow(
                doseId = row.dose.id,
                medicationName = row.medication.name,
                timeLabel = DateTimeUtils.formatMinuteOfDay(row.dose.plannedMinuteOfDay),
                takenQuantity = row.dose.takenQuantity,
                plannedQuantity = row.dose.plannedQuantity,
                unitLabel = row.dose.plannedUnit,
                status = row.dose.status,
            )
        }
        _uiState.update { it.copy(selectedDayDoses = rows) }
    }

    private suspend fun computeStats(): HistoryStats {
        val today = DateTimeUtils.todayEpochDay()
        val from = today - (range.days - 1)
        val doses = doseRepository.getDosesBetween(from, today)
            .filter { medicationFilter == null || it.medicationId == medicationFilter }

        val taken = doses.count { it.status == DoseStatus.TAKEN }
        val partial = doses.count { it.status == DoseStatus.PARTIAL }
        val missed = doses.count { it.status == DoseStatus.MISSED }
        val skipped = doses.count { it.status == DoseStatus.SKIPPED }

        val streak = computeStreaks()

        return HistoryStats(
            range = range,
            medicationId = medicationFilter,
            totalDoses = doses.size,
            takenDoses = taken,
            partialDoses = partial,
            missedDoses = missed,
            skippedDoses = skipped,
            plannedQuantity = doses.sumOf { it.plannedQuantity },
            takenQuantity = doses.sumOf { it.takenQuantity },
            unitLabel = doses.firstOrNull()?.plannedUnit ?: "",
            currentStreak = streak.first,
            longestStreak = streak.second,
        )
    }

    /**
     * Current and longest streak of days with at least one recorded intake.
     *
     * The current streak tolerates today having no record yet - otherwise the number would visibly
     * drop to zero every midnight and look like a bug.
     */
    private suspend fun computeStreaks(): Pair<Int, Int> {
        // Sorted ascending, distinct days with a recorded intake. The DAO does the projection so
        // this never loads the full dose history.
        val days = doseRepository.getDaysWithIntake(medicationFilter).sorted()

        if (days.isEmpty()) return 0 to 0

        var longest = 1
        var run = 1
        for (index in 1 until days.size) {
            if (days[index] == days[index - 1] + 1) {
                run++
                if (run > longest) longest = run
            } else {
                run = 1
            }
        }

        val today = DateTimeUtils.todayEpochDay()
        val last = days.last()
        var current = 0
        if (last == today || last == today - 1) {
            current = 1
            var cursor = last
            for (index in days.size - 2 downTo 0) {
                if (days[index] == cursor - 1) {
                    current++
                    cursor = days[index]
                } else {
                    break
                }
            }
        }
        return current to maxOf(longest, current)
    }

    /** Corrections from the history screen: fix a record the user mis-tapped days ago. */
    fun markTaken(doseId: Long) {
        viewModelScope.launch {
            doseRepository.markTaken(doseId)
            refresh()
        }
    }

    fun reset(doseId: Long) {
        viewModelScope.launch {
            doseRepository.reset(doseId)
            refresh()
        }
    }
}
