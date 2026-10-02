package com.meditrack.ui.medications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meditrack.data.repository.MedicationRepository
import com.meditrack.data.repository.MedicationUiModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which subset of the medication list is shown. */
enum class MedicationFilter { ALL, ACTIVE, INACTIVE }

data class MedicationListUiState(
    val medications: List<MedicationUiModel> = emptyList(),
    val filter: MedicationFilter = MedicationFilter.ALL,
    val query: String = "",
) {
    val isEmpty: Boolean get() = medications.isEmpty()

    val activeCount: Int get() = medications.count { it.isActive }
}

@HiltViewModel
class MedicationListViewModel @Inject constructor(
    private val repository: MedicationRepository,
) : ViewModel() {

    private val filter = MutableStateFlow(MedicationFilter.ALL)
    private val query = MutableStateFlow("")

    val uiState: StateFlow<MedicationListUiState> = combine(
        repository.observeMedications(),
        filter,
        query,
    ) { all, currentFilter, currentQuery ->
        val filtered = all
            .filter { med ->
                when (currentFilter) {
                    MedicationFilter.ALL -> true
                    MedicationFilter.ACTIVE -> med.isActive
                    MedicationFilter.INACTIVE -> !med.isActive
                }
            }
            .filter { med ->
                currentQuery.isBlank() ||
                    med.name.contains(currentQuery, ignoreCase = true) ||
                    med.medication.strength.contains(currentQuery, ignoreCase = true)
            }
        MedicationListUiState(medications = filtered, filter = currentFilter, query = currentQuery)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = MedicationListUiState(),
    )

    fun setFilter(value: MedicationFilter) {
        filter.value = value
    }

    fun setQuery(value: String) {
        query.value = value
    }

    /**
     * Enables or disables a medication.
     *
     * Disabling is a soft delete on purpose: the medication keeps its history and can be switched
     * back on, which is what a user expects for a course of treatment that pauses.
     */
    fun setActive(id: Long, active: Boolean) {
        viewModelScope.launch { repository.setActive(id, active) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }
}
