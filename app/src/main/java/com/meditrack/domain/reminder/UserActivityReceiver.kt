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
 * Observes ordinary device-activity broadcasts so the app knows when the user is actually present.
 *
 * Registered at runtime by [com.meditrack.MediTrackApp], never in the manifest, because
 * `ACTION_USER_PRESENT` and `ACTION_SCREEN_ON`/_OFF` cannot be received by a manifest-declared
 * receiver at all: the platform blocks them as an abuse vector. A runtime registration that lives as
 * long as the process is the only supported way to hear them.
 *
 * The broadcast is nothing more than a hint - it never carries a dose id and is never trusted to mean
 * anything beyond "the user is here now". The receiver starts one reconciliation pass, and the pass
 * decides from the database whether anything is owed; that keeps this path identical in behaviour to
 * the alarm, heartbeat and boot paths instead of being a special case.
 */
@AndroidEntryPoint
class UserActivityReceiver : BroadcastReceiver() {

    @Inject lateinit var engine: ReminderEngine
    @Inject lateinit var presence: UserPresence

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        // Logged unconditionally, and that is the point: this receiver is the only component in the
        // pipeline whose input is an *implicit* broadcast, and on several OEM ROMs those are simply
        // not delivered to a background app. Without this line, "the unlock catch-up did not fire"
        // and "the ROM never told us the phone was unlocked" look identical from inside the app - and
        // they need completely different fixes. The guard service's presence poller covers the second
        // case; this line is how the two are told apart.
        Log.i(TAG, "activity broadcast: " + action)
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (action) {
                    // The lock screen was dismissed. This is the strongest evidence there is that the
                    // user is holding the phone, and it is the exact moment the unlock catch-up exists
                    // for: anything that came due while the phone sat dark gets a second chance to be
                    // heard, before the user has had a chance to walk away again.
                    Intent.ACTION_USER_PRESENT -> engine.onUserReturn()

                    // The display came on. If the phone was *already* unlocked (someone picked it up
                    // and woke it), that is the same event as far as "tell me what I missed" is
                    // concerned. If the keyguard is showing, it is not: nobody can act on a
                    // notification behind a lock screen they have not dismissed, and the scheduled
                    // reminder is what should speak there.
                    Intent.ACTION_SCREEN_ON ->
                        if (presence.isScreenOnAndUnlocked()) engine.onUserReturn()

                    // The screen going dark says nothing about whether a human is present.
                    Intent.ACTION_SCREEN_OFF -> Unit
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
         * whichever arrives first is enough. Listening to both keeps "remind me the moment I pick it
         * up" working across manufacturers that suppress one or the other.
         *
         * `SCREEN_OFF` is subscribed to only because the filter is shared; the handler ignores it.
         * Nothing in the pipeline withholds a reminder any more, so there is nothing to do when the
         * screen goes dark - the scheduled alarm is what speaks at the scheduled time.
         */
        val ACTIONS = arrayOf(
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_SCREEN_OFF,
        )
    }
}
