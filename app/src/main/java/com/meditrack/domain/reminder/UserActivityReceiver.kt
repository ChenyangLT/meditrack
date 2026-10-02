package com.meditrack.domain.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Observes ordinary device-activity broadcasts for the opt-in idle-deferral feature.
 *
 * Registered at runtime by [com.meditrack.MediTrackApp], never in the manifest, for two reasons:
 *
 *  1. `ACTION_USER_PRESENT` and `ACTION_SCREEN_ON`/_OFF` cannot be received by a manifest-declared
 *     receiver at all - the platform blocks them as an abuse vector.
 *  2. It is the natural way to guarantee the feature is inert when switched off. Nothing is
 *     registered until the app process starts and the user has enabled deferral, so a user who
 *     leaves it off has no device-state observation whatsoever.
 *
 * When the user unlocks the phone the engine runs a full reconcile pass, which releases every
 * withheld reminder and rebuilds the whole schedule - so "stay quiet while nobody is looking, speak
 * up the moment somebody is" is now one code path rather than a special case.
 */
@AndroidEntryPoint
class UserActivityReceiver : BroadcastReceiver() {

    @Inject lateinit var engine: ReminderEngine
    @Inject lateinit var usageMonitor: UsageMonitor

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (action) {
                    // Any of these mean "a human is holding the phone".
                    Intent.ACTION_USER_PRESENT,
                    Intent.ACTION_SCREEN_ON,
                    -> engine.onUserReturn()

                    // Screen off is not an interaction; it only changes what shouldDefer() sees.
                    Intent.ACTION_SCREEN_OFF -> usageMonitor.recordInteraction()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "activity handling failed for $action", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "UserActivityReceiver"

        /**
         * The broadcasts this receiver wants.
         *
         * `SCREEN_ON` and `USER_PRESENT` are both listed: on most devices unlocking fires both, and
         * whichever arrives is enough. Listening to both makes the "remind me the moment I pick it
         * up" behaviour robust across manufacturers that suppress one or the other.
         */
        val ACTIONS = arrayOf(
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_SCREEN_OFF,
        )
    }
}
