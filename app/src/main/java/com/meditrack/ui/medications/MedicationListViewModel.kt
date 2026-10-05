package com.meditrack.ui.medications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meditrack.data.repository.MedicationRepository
import com.meditrack.data.repository.MedicationUiModel
import com.meditrack.data.repository.MedicationReviewSnapshot
import com.meditrack.data.repository.ReviewRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
    private val reviewRepository: ReviewRepository,
) : ViewModel() {

    private val filter = MutableStateFlow(MedicationFilter.ALL)
    private val query = MutableStateFlow("")

    /**
     * Bumped by [refreshReviews].
     *
     * The review count lives in its own table, so recording a dose writes *there* and leaves
     * `medications` untouched - the medication flow would not re-emit and "还差 3 次" would sit at the
     * old number until something unrelated changed a medication. The screen bumps this when it appears,
     * which is exactly when a stale number would be read.
     */
    private val reviewRefresh = MutableStateFlow(0)

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

    /**
     * The «复查» line for every medication, keyed by id.
     *
     * A separate flow rather than a field on [MedicationListUiState] so a slow review read cannot hold
     * up the list itself: the rows render from the medication snapshot immediately and the review line
     * appears when it arrives. An empty map simply means "no review configured", which is also the
     * honest state while the read is in flight - there is nothing to show either way.
     */
    val reviewStates: StateFlow<Map<Long, MedicationReviewSnapshot>> = combine(
        repository.observeMedications(),
        reviewRefresh,
    ) { medications, _ ->
        if (medications.isEmpty()) emptyMap()
        else reviewRepository.snapshots().associateBy { it.medication.id }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = emptyMap(),
    )

    /** Re-reads the review progress; see [reviewRefresh] for why the medication flow is not enough. */
    fun refreshReviews() {
        reviewRefresh.update { it + 1 }
    }

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
