package com.meditrack.domain.reminder

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The reminder pipeline's durable bookkeeping.
 *
 * ## Why this exists at all
 *
 * The rewrite's central move is that every trigger rebuilds the schedule from the database
 * ("reconcile"). Rebuilding only half works if the previous arming cannot be undone precisely: a
 * dose armed under an older rule, or one that has since been taken or deleted, would leave an
 * orphaned `PendingIntent` behind that still fires and still shows a notification.
 *
 * Deriving the cancellable set from the database is not enough, because the dangerous orphans are
 * exactly the ones the database no longer explains. So armed ids are recorded explicitly, which
 * turns "cancel everything" into an exact operation rather than a guess over a request-code range.
 *
 * ## Expectations: what turns a missing reminder into evidence
 *
 * Recording *which* doses are armed is not quite enough to answer the only question that matters
 * when a reminder does not appear: did we fail to schedule it, or did the system fail to deliver it?
 * So the instant each alarm was armed for is stored alongside it. A later pass can then see "we armed
 * an alarm for 08:00, 08:00 has passed, and this dose has still never been announced" and say so
 * explicitly. Without that, both failures look identical from inside the app - which is precisely why
 * "it only reminds me when I open it" is so hard to diagnose from a bug report.
 *
 * The runtime counters alongside them are what let the app report on itself without logcat: when the
 * self-check last ran, what woke it, whether the guard service is alive, and how late the operating
 * system actually delivered things. All of it is persisted because the process routinely dies between
 * arming an alarm and its firing - in-memory bookkeeping would be forgotten exactly when it is
 * needed.
 *
 * Every read degrades to a safe default rather than throwing: this is consulted from inside broadcast
 * receivers and a service, where an exception would mean a silently dropped reminder.
 */
@Singleton
class ReminderAlarmRegistry @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    /** The dose ids currently armed with the OS, both the reminder and its heads-up. */
    suspend fun armed(): Set<Long> =
        runCatching { dataStore.data.first()[KEY_ARMED].orEmpty() }
            .getOrDefault(emptySet())
            .mapNotNull { it.toLongOrNull() }
            .toSet()

    /**
     * The instant each armed dose's reminder was scheduled for.
     *
     * Used to tell "the OS never delivered the alarm we set" apart from "we never set one".
     */
    suspend fun armedExpectations(): Map<Long, Long> =
        ArmedExpectations.decode(
            runCatching { dataStore.data.first()[KEY_EXPECTATIONS] }.getOrNull().orEmpty()
        )

    /** Replaces the whole armed set and its expectations in one write. */
    suspend fun replaceArmed(ids: Set<Long>, expectations: Map<Long, Long>) {
        runCatching {
            dataStore.edit { prefs ->
                prefs[KEY_ARMED] = ids.map(Long::toString).toSet()
                prefs[KEY_EXPECTATIONS] = ArmedExpectations.encode(expectations)
            }
        }
    }

    suspend fun add(doseId: Long) = mutateArmed { it + doseId.toString() }

    suspend fun remove(doseId: Long) = mutateArmed { it - doseId.toString() }

    suspend fun clear() {
        runCatching {
            dataStore.edit { prefs ->
                prefs.remove(KEY_ARMED)
                prefs.remove(KEY_EXPECTATIONS)
            }
        }
    }

    // ---------------------------------------------------------- runtime state

    /** When the pipeline last completed a reconcile pass. */
    suspend fun lastReconcileAt(): Long =
        runCatching { dataStore.data.first()[KEY_LAST_RECONCILE] }.getOrNull() ?: 0L

    /** What triggered that pass, as a [ReminderTrigger] name. */
    suspend fun lastReconcileTrigger(): String? =
        runCatching { dataStore.data.first()[KEY_LAST_TRIGGER] }.getOrNull()

    /** When the rolling self-check alarm is next due, as armed with the OS. */
    suspend fun nextSelfCheckAt(): Long =
        runCatching { dataStore.data.first()[KEY_NEXT_SELF_CHECK] }.getOrNull() ?: 0L

    /** The soonest dose alarm the last pass armed, or 0. */
    suspend fun nextDoseAt(): Long =
        runCatching { dataStore.data.first()[KEY_NEXT_DOSE_AT] }.getOrNull() ?: 0L

    /** Records the outcome of one reconcile pass in a single write. */
    suspend fun recordReconcile(report: ReminderReport) {
        runCatching {
            dataStore.edit { prefs ->
                prefs[KEY_LAST_RECONCILE] = System.currentTimeMillis()
                prefs[KEY_LAST_TRIGGER] = report.trigger.name
                prefs[KEY_NEXT_SELF_CHECK] = report.nextSelfCheckAt
                prefs[KEY_NEXT_DOSE_AT] = report.nextDoseAt
                prefs[KEY_ARMED_COUNT] = report.armedDoses.toLong()
                if (report.lostAlarms > 0) {
                    prefs[KEY_LOST_ALARMS] = (prefs[KEY_LOST_ALARMS] ?: 0L) + report.lostAlarms
                }
            }
        }
    }

    /** How many alarms the last pass armed. */
    suspend fun armedCount(): Int =
        runCatching { dataStore.data.first()[KEY_ARMED_COUNT] }.getOrNull()?.toInt() ?: 0

    /**
     * How many armed alarms the system has failed to deliver, ever.
     *
     * A non-zero value is the app's own evidence that it is being cleared from the background.
     */
    suspend fun lostAlarms(): Long =
        runCatching { dataStore.data.first()[KEY_LOST_ALARMS] }.getOrNull() ?: 0L

    // ------------------------------------------------------- guard service

    /** Called by the guard service on every pass so the self-check can see it is alive. */
    suspend fun noteGuardAlive(at: Long) {
        runCatching { dataStore.edit { it[KEY_GUARD_SEEN] = at } }
    }

    suspend fun markGuardStopped() {
        runCatching { dataStore.edit { it.remove(KEY_GUARD_SEEN) } }
    }

    /** When the guard service last checked in, or 0 if it is not running. */
    suspend fun guardLastSeenAt(): Long =
        runCatching { dataStore.data.first()[KEY_GUARD_SEEN] }.getOrNull() ?: 0L

    /**
     * True when the guard service has not checked in recently enough to trust that it is running.
     *
     * The rule itself lives in [GuardLiveness] so it can be tested without a device.
     */
    suspend fun guardIsStale(nowMillis: Long): Boolean =
        GuardLiveness.needsRestart(guardLastSeenAt(), nowMillis)

    /**
     * The largest lateness the OS has inflicted on a delivery, in milliseconds.
     *
     * This is the measurement that turns "it was late again" into something actionable: a persistent
     * multi-minute value means the OEM is batch-deferring alarms.
     */
    suspend fun worstDriftMillis(): Long =
        runCatching { dataStore.data.first()[KEY_WORST_DRIFT] }.getOrNull() ?: 0L

    /** Raises the recorded worst drift; never lowers it, so one bad night stays visible. */
    suspend fun noteDrift(driftMillis: Long) {
        if (driftMillis <= 0L) return
        runCatching {
            dataStore.edit { prefs ->
                if (driftMillis > (prefs[KEY_WORST_DRIFT] ?: 0L)) {
                    prefs[KEY_WORST_DRIFT] = driftMillis
                }
            }
        }
    }

    /** Forgets the drift and lost-alarm history; used by the manual repair action. */
    suspend fun resetDiagnostics() {
        runCatching {
            dataStore.edit { prefs ->
                prefs.remove(KEY_WORST_DRIFT)
                prefs.remove(KEY_LOST_ALARMS)
            }
        }
    }

    private suspend fun mutateArmed(block: (Set<String>) -> Set<String>) {
        runCatching {
            dataStore.edit { prefs -> prefs[KEY_ARMED] = block(prefs[KEY_ARMED].orEmpty()) }
        }
    }

    private companion object {
        val KEY_ARMED = stringSetPreferencesKey("armed_dose_alarm_ids")
        val KEY_EXPECTATIONS = stringSetPreferencesKey("armed_dose_expectations")
        val KEY_LAST_RECONCILE = longPreferencesKey("reminder_last_reconcile_at")
        val KEY_LAST_TRIGGER = stringPreferencesKey("reminder_last_trigger")
        val KEY_NEXT_SELF_CHECK = longPreferencesKey("reminder_next_self_check_at")
        val KEY_NEXT_DOSE_AT = longPreferencesKey("reminder_next_dose_at")
        val KEY_ARMED_COUNT = longPreferencesKey("reminder_armed_count")
        val KEY_LOST_ALARMS = longPreferencesKey("reminder_lost_alarms")
        val KEY_GUARD_SEEN = longPreferencesKey("reminder_guard_seen_at")
        val KEY_WORST_DRIFT = longPreferencesKey("reminder_worst_drift_millis")
    }
}

/**
 * When to ask the platform for the background guard service.
 *
 * ## The bug this exists to prevent
 *
 * "Ask for the guard service on every reconcile pass" sounds harmless and is not:
 * `reconcile()` → `ensureRunning()` → `Service.onStartCommand()` → `reconcile()` → `ensureRunning()` …
 * `startForegroundService` on an already-running service simply delivers another
 * `onStartCommand`, so the cycle had no natural end. On a real device it produced **491 reconcile
 * passes in 86 seconds** (median gap 22 ms), each one cancelling and re-arming every alarm, writing
 * audit rows, and re-posting the ongoing notification.
 *
 * The consequences were not cosmetic: the audit trail was overwritten every ~90 seconds (so
 * 「最近的提醒决策」 could never show anything older), the app burned CPU continuously, and every dose
 * alarm was cancelled and re-armed dozens of times a second until the moment it fired - which is
 * exactly the kind of behaviour an aggressively-managed ROM reacts to by freezing the app.
 *
 * The fix is to ask only when there is a *reason* to: the guard is missing, or its check-in has gone
 * stale. Because the service stamps its liveness before it does any work, the pass it triggers can see
 * that fresh stamp and stops asking - the cycle closes after exactly one extra pass.
 */
object GuardLiveness {

    /**
     * How long a guard check-in is trusted.
     *
     * Long enough that ordinary passes never re-ask (which is what closes the loop), short enough that
     * a guard which died without running `onDestroy` is still noticed within a few minutes.
     */
    const val FRESH_MILLIS = 3 * 60_000L

    /**
     * True when the guard should be (re)requested from the platform.
     *
     * A timestamp in the future (a clock jump) counts as fresh rather than stale, because asking again
     * cannot help and the loop is the thing being defended against.
     */
    fun needsRestart(
        lastSeenAt: Long,
        nowMillis: Long,
        freshMillis: Long = FRESH_MILLIS,
    ): Boolean {
        if (lastSeenAt <= 0L) return true
        return nowMillis - lastSeenAt > freshMillis
    }
}

/**
 * Encoding for the "which dose, armed for when" pairs.
 *
 * Kept as flat `doseId:millis` strings because DataStore Preferences has no map type, and kept in its
 * own object because a bug in this codec would not crash anything - it would silently discard the
 * very evidence that tells the user their reminders are being cleared. That is exactly the kind of
 * failure worth testing directly.
 *
 * Decoding is total: a malformed entry is dropped rather than thrown, because the alternative is an
 * exception inside a broadcast receiver, which would cost the user a reminder.
 */
object ArmedExpectations {

    private const val SEPARATOR = ':'

    fun encode(expectations: Map<Long, Long>): Set<String> =
        expectations.map { (doseId, at) -> "$doseId$SEPARATOR$at" }.toSet()

    fun decode(entries: Set<String>): Map<Long, Long> =
        entries.mapNotNull { entry ->
            val separator = entry.indexOf(SEPARATOR)
            // A leading separator means an empty id; a missing one means the wrong format entirely.
            if (separator <= 0) return@mapNotNull null
            val doseId = entry.substring(0, separator).toLongOrNull() ?: return@mapNotNull null
            val at = entry.substring(separator + 1).toLongOrNull() ?: return@mapNotNull null
            doseId to at
        }.toMap()
}
