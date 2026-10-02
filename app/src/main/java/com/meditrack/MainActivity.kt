package com.meditrack

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.meditrack.domain.reminder.ReminderEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The single Activity. All navigation happens inside Compose.
 *
 * It is annotated with [AndroidEntryPoint] so Hilt can inject into the Activity and, more
 * importantly, so the Activity is a valid ViewModelStoreOwner for the `hiltViewModel()` calls made
 * by the composables.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var reminderEngine: ReminderEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MediTrackRoot(
                focusDoseId = intent.getLongExtra(EXTRA_FOCUS_DOSE_ID, -1L),
                focusEpochDay = intent.getLongExtra(EXTRA_FOCUS_EPOCH_DAY, Long.MIN_VALUE),
            )
        }
    }

    override fun onStart() {
        super.onStart()
        // Opening the app is itself proof that the user is here. `onUserReturn` records that and runs
        // one reconciliation pass, which both hands over anything that was withheld while the phone
        // sat idle and repairs the schedule if an alarm was lost while the app was closed.
        //
        // It is idempotent and cheap: if the heartbeat already did this a minute ago, it finds
        // nothing to change. Both effects are no-ops when the opt-in idle-deferral feature is off.
        lifecycleScope.launch {
            runCatching { reminderEngine.onUserReturn() }
        }
    }

    companion object {
        /** Extra the reminder notification uses to scroll the today list to one dose. */
        const val EXTRA_FOCUS_DOSE_ID = "extra_focus_dose_id"

        /** Extra the widget uses to open a past day in the history calendar. */
        const val EXTRA_FOCUS_EPOCH_DAY = "extra_focus_epoch_day"
    }
}
