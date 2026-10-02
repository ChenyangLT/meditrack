package com.meditrack.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meditrack.data.update.UpdateCheckResult
import com.meditrack.data.update.UpdateInfo
import com.meditrack.data.update.UpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The start-up side of the update check: run it once per launch and hold whatever it found.
 *
 * The user-initiated path lives in [com.meditrack.ui.settings.SettingsViewModel] instead, so each
 * screen owns the check it triggers and there is only ever one snackbar and one dialog per screen.
 */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updateRepository: UpdateRepository,
) : ViewModel() {

    /** Non-null while the "a new version is out" dialog is up. */
    private val _available = MutableStateFlow<UpdateInfo?>(null)
    val available: StateFlow<UpdateInfo?> = _available.asStateFlow()

    /**
     * Called once per launch from the app root.
     *
     * Silence is the expected outcome: the repository decides whether the twelve-hour interval has
     * elapsed, whether the switch is on, and whether this version was already waved away.
     */
    fun checkOnStart(currentVersion: String) {
        viewModelScope.launch {
            when (val result = updateRepository.check(currentVersion, force = false)) {
                is UpdateCheckResult.Available -> _available.value = result.info
                else -> Unit
            }
        }
    }

    /** "以后再说" and tapping outside both land here: this version stops being offered. */
    fun dismiss(info: UpdateInfo) {
        viewModelScope.launch { updateRepository.dismiss(info) }
        _available.value = null
    }
}
