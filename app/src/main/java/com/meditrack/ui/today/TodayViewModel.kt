package com.meditrack.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meditrack.core.util.DateTimeUtils
import com.meditrack.data.prefs.SettingsRepository
import com.meditrack.data.prefs.UserPreferences
import com.meditrack.data.repository.DayState
import com.meditrack.data.repository.DoseActionResult
import com.meditrack.data.repository.DoseRepository
import com.meditrack.data.repository.MedicationRepository
import com.meditrack.domain.demo.DemoModeContent
import com.meditrack.domain.plan.DoseView
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI event the today screen has to react to exactly once.
 *
 * One-shot events (a toast, a confirmation dialog) must not be modelled as state: after a
 * configuration change a state-based message would replay. The screen consumes the event and calls
 * [TodayViewModel.onMessageShown].
 */
sealed interface TodayMessage {
    data class Toast(val text: String) : TodayMessage
    data class ConfirmOverDose(
        val dose: DoseView,
        val attemptedQuantity: Double,
        val maxQuantity: Double,
    ) : TodayMessage
}

/**
 * State of the today screen.
 *
 * @param day the doses and the aggregate for the visible day
 * @param preferences drives the 24-hour format, the touch-target size and the und hint text
 * @param isRefreshing true while a manual pull-to-refresh is in flight
 */
data class TodayUiState(
    val day: DayState? = null,
    val preferences: UserPreferences = UserPreferences(),
    val isRefreshing: Boolean = false,
    val focusDoseId: Long = -1L,
) {
    val doses: List<DoseView> get() = day?.doses.orEmpty()
    val summary get() = day?.summary
    val isEmpty: Boolean get() = day?.isEmpty ?: true
}

/**
 * Drives the today screen: the daily plan, the "+" / "-" steppers, skip, snooze and undo.
 *
 * All mutations are delegated to [DoseRepository], which owns the invariants. The ViewModel only
 * decides *which* action to run and turns the repository's result into a one-shot UI event.
 */
@HiltViewModel
class TodayViewModel @Inject constructor(
    private val doseRepository: DoseRepository,
    private val medicationRepository: MedicationRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    /** The day currently shown; the today screen exposes previous/next day navigation. */
    private val visibleDay = MutableStateFlow(DateTimeUtils.todayEpochDay())

    private val _messages = MutableStateFlow<TodayMessage?>(null)
    /** One-shot events; the screen clears each one after handling it. */
    val messages: StateFlow<TodayMessage?> = _messages.asStateFlow()

    private val _focusDoseId = MutableStateFlow(-1L)
    val focusDoseId: StateFlow<Long> = _focusDoseId.asStateFlow()

    /**
     * A minute-resolution clock.
     *
     * The relative labels ("还有 12 分钟") and the UPCOMING -> DUE transition depend on the current
     * time, which is not part of any Room query. Rather than polling the database, a lightweight
     * ticker is merged into the stream so the day is re-derived at most once every 30 seconds - and
     * only while the screen is actually subscribed.
     */
    private val clockTick: kotlinx.coroutines.flow.Flow<Long> = kotlinx.coroutines.flow.flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(TICK_INTERVAL_MS)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val dayState = kotlinx.coroutines.flow.combine(visibleDay, clockTick) { day, _ -> day }
        .flatMapLatest { epochDay -> doseRepository.observeDay(epochDay) }

    val uiState: StateFlow<TodayUiState> = kotlinx.coroutines.flow.combine(
        dayState,
        settingsRepository.preferences,
        _focusDoseId,
    ) { day, prefs, focus ->
        TodayUiState(day = day, preferences = prefs, focusDoseId = focus)
    }.stateIn(
        scope = viewModelScope,
        // Keep collecting for five seconds after the screen goes away: rotating the device or
        // stepping into the editor should not tear down and rebuild the database stream.
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = TodayUiState(),
    )

    init {
        viewModelScope.launch {
            // Materialise the visible day first: the schedules live in one table, the recorded
            // amounts in another, and the screen shows their join.
            doseRepository.materializeDay(visibleDay.value)
            // Escalate anything already past its grace period so the user sees the truth on open.
            doseRepository.sweepMissed(visibleDay.value)
        }
    }

    // ------------------------------------------------------------- navigation

    fun showPreviousDay() {
        visibleDay.update { it - 1 }
        viewModelScope.launch { doseRepository.materializeDay(visibleDay.value) }
    }

    fun showNextDay() {
        visibleDay.update { it + 1 }
        viewModelScope.launch { doseRepository.materializeDay(visibleDay.value) }
    }

    fun showToday() {
        visibleDay.value = DateTimeUtils.todayEpochDay()
        viewModelScope.launch { doseRepository.materializeDay(visibleDay.value) }
    }

    /** Called when the screen was opened from a notification or a widget tap. */
    fun focusOn(doseId: Long, epochDay: Long?) {
        if (epochDay != null && epochDay != Long.MIN_VALUE) {
            visibleDay.value = epochDay
            viewModelScope.launch { doseRepository.materializeDay(epochDay) }
        }
        _focusDoseId.value = doseId
    }

    fun clearFocus() {
        _focusDoseId.value = -1L
    }

    // ---------------------------------------------------------------- actions

    /** The "+" button. Asks for an over-dose confirmation when the maximum would be exceeded. */
    fun increase(dose: DoseView, confirmOverDose: Boolean = false) {
        viewModelScope.launch {
            ensurePersisted(dose)?.let { doseId ->
                when (val result = doseRepository.adjustQuantity(doseId, dose.step, confirmOverDose)) {
                    is DoseActionResult.Applied -> Unit
                    is DoseActionResult.NeedsOverDoseConfirmation ->
                        _messages.value = TodayMessage.ConfirmOverDose(
                            dose = result.dose,
                            attemptedQuantity = result.attemptedQuantity,
                            maxQuantity = result.maxQuantity,
                        )
                    DoseActionResult.NotFound -> Unit
                }
            }
        }
    }

    /** The "-" button. */
    fun decrease(dose: DoseView) {
        viewModelScope.launch {
            ensurePersisted(dose)?.let { doseId ->
                doseRepository.adjustQuantity(doseId, -dose.step)
            }
        }
    }

    /** One-tap "已服" from the card or the swipe action. */
    fun markTaken(dose: DoseView) {
        viewModelScope.launch {
            ensurePersisted(dose)?.let { doseId ->
                when (val result = doseRepository.markTaken(doseId)) {
                    is DoseActionResult.Applied ->
                        _messages.value = TodayMessage.Toast("已记录 ${dose.medicationName}")
                    is DoseActionResult.NeedsOverDoseConfirmation ->
                        _messages.value = TodayMessage.ConfirmOverDose(
                            dose = result.dose,
                            attemptedQuantity = result.attemptedQuantity,
                            maxQuantity = result.maxQuantity,
                        )
                    DoseActionResult.NotFound -> Unit
                }
            }
        }
    }

    fun skip(dose: DoseView) {
        viewModelScope.launch {
            ensurePersisted(dose)?.let { doseId ->
                doseRepository.skip(doseId)
                _messages.value = TodayMessage.Toast("已跳过 ${dose.medicationName}")
            }
        }
    }

    fun unskip(dose: DoseView) {
        viewModelScope.launch {
            ensurePersisted(dose)?.let { doseId -> doseRepository.unskip(doseId) }
        }
    }

    /**
     * Pushes a dose's reminder out by [minutes], or by the configured default when null.
     *
     * The duration is an argument rather than always read from settings so the user can pick it per
     * reminder from the card menu.
     */
    fun snooze(dose: DoseView, minutes: Int? = null) {
        viewModelScope.launch {
            ensurePersisted(dose)?.let { doseId ->
                val applied = minutes ?: settingsRepository.current().snoozeMinutes
                doseRepository.snooze(doseId, applied)
                _messages.value = TodayMessage.Toast("$applied 分钟后再提醒")
            }
        }
    }

    /** Undo the most recent change to a dose; the button is shown after every mutation. */
    fun undo(dose: DoseView) {
        viewModelScope.launch {
            if (dose.isPersisted) {
                doseRepository.undoLastChange(dose.doseId)
                _messages.value = TodayMessage.Toast("已撤销")
            }
        }
    }

    fun reset(dose: DoseView) {
        viewModelScope.launch {
            if (dose.isPersisted) {
                doseRepository.reset(dose.doseId)
                _messages.value = TodayMessage.Toast("已重置 ${dose.medicationName}")
            }
        }
    }

    fun toggleMedicationActive(medicationId: Long, active: Boolean) {
        viewModelScope.launch {
            medicationRepository.setActive(medicationId, active)
            if (active) doseRepository.materializeDay(visibleDay.value)
        }
    }

    /** Applies a pending over-dose confirmation. */
    fun confirmOverDose(dose: DoseView, confirm: Boolean) {
        _messages.value = null
        if (!confirm) return
        viewModelScope.launch {
            val doseId = ensurePersisted(dose) ?: return@launch
            val target = dose.plannedQuantity.coerceAtLeast(dose.takenQuantity + dose.step)
            doseRepository.adjustQuantity(doseId, target - dose.takenQuantity, confirmOverDose = true)
        }
    }

    fun onMessageShown() {
        _messages.value = null
    }

    /**
     * Ends 演示模式 from the today screen's banner.
     *
     * The banner's button and the settings switch write the same preference, and that is the point:
     * the user who notices the samples on this screen must not have to remember where they were turned
     * on. Nothing is torn down afterwards - demo doses were never persisted and never armed, so ending
     * the mode is exactly one write, and the today list loses the sample rows because
     * [DoseRepository.observeDay] stops appending them.
     */
    fun exitDemoMode() {
        viewModelScope.launch {
            settingsRepository.setDemoMode(false)
            _messages.value = TodayMessage.Toast("已退出演示模式")
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val day = visibleDay.value
            doseRepository.materializeDay(day)
            doseRepository.sweepMissed(day)
        }
    }

    /**
     * Guarantees a database row before a mutation.
     *
     * A dose that is still only "planned" (the app was opened before the scheduler materialised the
     * day) has no id yet, so the first tap has to create it. Re-reading by schedule and day keeps
     * this idempotent when two taps race.
     *
     * ## 演示模式 doses
     *
     * A sample dose has no row and can never get one - `materializeDay` filters the demo ids out - so
     * without this branch the "+", 已服, 跳过 and 稍后提醒 controls on a sample card would silently do
     * nothing, which reads as a broken screen rather than as an intentional limit. The toast says why.
     * Recognising the dose by its *id* rather than by a flag threaded through the UI is what makes it
     * impossible to miss a call site: every action already funnels through here.
     */
    private suspend fun ensurePersisted(dose: DoseView): Long? {
        if (DemoModeContent.isDemoId(dose.medicationId)) {
            _messages.value = TodayMessage.Toast("演示模式下不能记录示例药品")
            return null
        }
        if (dose.isPersisted) return dose.doseId
        doseRepository.materializeDay(dose.epochDay)
        return doseRepository.getDayRows(dose.epochDay)
            .firstOrNull { it.dose.scheduleId == dose.scheduleId }
            ?.dose
            ?.id
    }

    companion object {
        /** 30s is frequent enough for "还有 N 分钟" labels without burning battery. */
        private const val TICK_INTERVAL_MS = 30_000L
    }
}
