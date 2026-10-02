package com.meditrack.domain.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.meditrack.core.util.DateTimeUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Arms every alarm the reminder pipeline uses.
 *
 * ## What changed, and why
 *
 * The previous scheduler armed one alarm per dose plus **one** nightly maintenance alarm, and then
 * trusted the operating system for the next 24 hours. If any of those alarms was dropped - the
 * ordinary outcome on ROMs that aggressively manage background apps, and a routine consequence of a
 * force-stop - nothing noticed, nothing recovered, and the dose was silently never announced. The
 * midnight tick was also the worst possible moment to depend on: it is exactly when Doze is deepest
 * and when OEM "cleanup" jobs run.
 *
 * This scheduler keeps the exact per-dose alarm, but adds a **rolling self-check** that fires every
 * few minutes, re-derives the entire schedule from the database, and re-arms both itself and every
 * outstanding dose. A dropped alarm therefore degrades from "never" to "at most one interval late".
 * That single change is the difference between a pipeline that hopes and one that heals.
 *
 * ## Reliability rules encoded here
 *
 *  - **Exact by default.** `setExactAndAllowWhileIdle` whenever the OS permits it.
 *  - **Graceful degradation.** Without `SCHEDULE_EXACT_ALARM` (Android 12+ can revoke it) it falls
 *    back to `setAndAllowWhileIdle` and *also* keeps the heartbeat running, so the worst case is
 *    bounded drift rather than silence. The dose row is durable, so nothing is ever lost.
 *  - **Alarm-clock grade on request.** `setAlarmClock` is the only API that is entirely exempt from
 *    Doze and that the system itself protects from being reaped. It costs a status-bar alarm icon,
 *    so it is opt-in - and the self-check surfaces it as a concrete recommendation when it measures
 *    the drift that makes it worthwhile.
 *  - **Self-healing.** The heartbeat re-arms itself at the end of every reconcile pass, so boot,
 *    timezone change, app update and app launch all rebuild the chain.
 *  - **Idempotent.** Request codes are derived from the dose id (and from the alarm's role), so
 *    re-arming replaces instead of stacking, and cancelling is exact.
 */
interface ReminderHeartbeat {

    /** True when the OS currently allows exact alarms (Android 12+). */
    fun canScheduleExactAlarms(): Boolean

    /** True when the app currently holds `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM`. */
    fun hasExactAlarmPermission(): Boolean = canScheduleExactAlarms()

    /** Arms (or re-arms) the reminder for one dose. Idempotent by dose id. */
    fun armDose(doseId: Long, triggerAtMillis: Long, alarmClockMode: Boolean = false)

    /** Arms (or re-arms) the optional "还有一会儿" heads-up for one dose. */
    fun armPreReminder(doseId: Long, triggerAtMillis: Long)

    /** Cancels both the dose alarm and its heads-up. Safe to call for an unarmed dose. */
    fun cancelDose(doseId: Long)

    /**
     * Arms the rolling self-check.
     *
     * @return the instant it was armed for, so callers can log and display it.
     */
    fun armSelfCheck(nowMillis: Long, intervalMinutes: Int, alarmClockMode: Boolean): Long

    fun cancelSelfCheck()

    /**
     * Arms the once-a-day heavy pass (history repair, multi-day materialisation, audit pruning).
     *
     * Deliberately placed in the small hours *and* backed by the heartbeat: this alarm being lost is
     * now an inconvenience rather than a failure, because the heartbeat reconciles anyway.
     */
    fun armNightlyPass(nowMillis: Long): Long

    fun cancelNightlyPass()

    /**
     * Cancels only the per-dose alarms.
     *
     * This is what a reconcile pass uses, and the distinction matters: cancelling the self-check
     * alarm at the *start* of a pass and re-arming it at the end leaves a window - however small -
     * in which the process could be killed with nothing scheduled at all, and a reminder app that
     * can end up with a completely empty alarm list is exactly the failure being fixed. The rolling
     * heartbeat is therefore never cancelled by a routine pass; it is only ever rolled forward.
     */
    fun cancelDoseAlarms(armedDoseIds: Set<Long>)

    /** Cancels every alarm this app has armed, including the two rolling ones. */
    fun cancelAll(armedDoseIds: Set<Long>)

    companion object {
        /** Self-check heartbeat. */
        const val REQUEST_SELF_CHECK = 1

        /** Nightly history/audit pass. */
        const val REQUEST_NIGHTLY = 2

        /** Dose alarms live in `[DOSE_BASE, DOSE_BASE + NAMESPACE)`. */
        const val DOSE_BASE = 100_000

        /** Heads-up alarms live in a separate namespace so the two can never collide. */
        const val PRE_BASE = 300_000

        /**
         * Size of each request-code namespace.
         *
         * 100 000 is far beyond any realistic number of dose rows per install, and keeps dose and
         * heads-up codes in disjoint ranges so `cancelDose` can cancel both without bookkeeping.
         */
        const val NAMESPACE = 100_000

        fun doseRequestCode(doseId: Long): Int = DOSE_BASE + (doseId % NAMESPACE).toInt()

        fun preRequestCode(doseId: Long): Int = PRE_BASE + (doseId % NAMESPACE).toInt()

        /**
         * The smallest interval the heartbeat will honour.
         *
         * Doze rate-limits `setExactAndAllowWhileIdle` to about one delivery per 9 minutes, so a
         * shorter interval would be a promise the platform silently breaks - and a heartbeat that
         * lies is worse than none, because it hides the failure it was added to catch.
         */
        const val MIN_INTERVAL_MINUTES = 10
    }
}

@Singleton
class ReminderHeartbeatImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : ReminderHeartbeat {

    private val alarmManager: AlarmManager
        get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override fun canScheduleExactAlarms(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

    override fun armDose(doseId: Long, triggerAtMillis: Long, alarmClockMode: Boolean) {
        if (triggerAtMillis <= 0L) return
        arm(Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_DOSE_ALARM
            putExtra(ReminderReceiver.EXTRA_DOSE_ID, doseId)
        }, ReminderHeartbeat.doseRequestCode(doseId), triggerAtMillis, alarmClockMode = alarmClockMode, label = "dose/$doseId")
    }

    override fun armPreReminder(doseId: Long, triggerAtMillis: Long) {
        if (triggerAtMillis <= 0L) return
        arm(Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_PRE_ALARM
            putExtra(ReminderReceiver.EXTRA_DOSE_ID, doseId)
        }, ReminderHeartbeat.preRequestCode(doseId), triggerAtMillis, alarmClockMode = false, label = "pre/$doseId")
    }

    override fun cancelDose(doseId: Long) {
        cancel(ReminderReceiver::class.java, ReminderReceiver.ACTION_DOSE_ALARM, ReminderHeartbeat.doseRequestCode(doseId), doseId)
        cancel(ReminderReceiver::class.java, ReminderReceiver.ACTION_PRE_ALARM, ReminderHeartbeat.preRequestCode(doseId), doseId)
    }

    override fun armSelfCheck(nowMillis: Long, intervalMinutes: Int, alarmClockMode: Boolean): Long {
        val interval = intervalMinutes.coerceAtLeast(ReminderHeartbeat.MIN_INTERVAL_MINUTES)
        val trigger = nowMillis + interval * 60_000L
        arm(
            Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_SELF_CHECK
            },
            ReminderHeartbeat.REQUEST_SELF_CHECK,
            trigger,
            alarmClockMode = alarmClockMode,
            label = "self-check(+${interval}m)",
        )
        return trigger
    }

    override fun cancelSelfCheck() {
        cancel(ReminderReceiver::class.java, ReminderReceiver.ACTION_SELF_CHECK, ReminderHeartbeat.REQUEST_SELF_CHECK, 0L)
    }

    override fun armNightlyPass(nowMillis: Long): Long {
        // Small hours, not midnight: at 03:30 the phone is idle, the user is asleep, and the OEM
        // cleanup jobs that race midnight have long finished.
        val today = DateTimeUtils.todayEpochDay()
        var trigger = nextNightlyInstant(nowMillis, today)
        if (trigger <= nowMillis) trigger = nextNightlyInstant(nowMillis, today + 1)
        arm(
            Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_NIGHTLY_PASS
            },
            ReminderHeartbeat.REQUEST_NIGHTLY,
            trigger,
            alarmClockMode = false,
            label = "nightly",
        )
        return trigger
    }

    override fun cancelNightlyPass() {
        cancel(ReminderReceiver::class.java, ReminderReceiver.ACTION_NIGHTLY_PASS, ReminderHeartbeat.REQUEST_NIGHTLY, 0L)
    }

    override fun cancelDoseAlarms(armedDoseIds: Set<Long>) {
        for (id in armedDoseIds) cancelDose(id)
    }

    override fun cancelAll(armedDoseIds: Set<Long>) {
        cancelDoseAlarms(armedDoseIds)
        cancelSelfCheck()
        cancelNightlyPass()
    }

    /** 03:30 on [epochDay], as an instant. */
    private fun nextNightlyInstant(nowMillis: Long, epochDay: Long): Long {
        val at = DateTimeUtils.millisAt(epochDay, NIGHTLY_MINUTE_OF_DAY)
        return if (at <= 0L) nowMillis else at
    }

    /**
     * The single arming path.
     *
     * Keeping one function for every alarm is what guarantees they all share the same degradation
     * ladder; the previous code had three near-copies that had already drifted apart.
     */
    private fun arm(
        intent: Intent,
        requestCode: Int,
        triggerAtMillis: Long,
        alarmClockMode: Boolean,
        label: String,
    ) {
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // A trigger in the past means "as soon as possible": the reconcile pass always recomputes the
        // real instant, so firing immediately is strictly better than dropping the alarm.
        val trigger = if (triggerAtMillis < System.currentTimeMillis()) {
            System.currentTimeMillis() + 1_000L
        } else {
            triggerAtMillis
        }

        val exact = canScheduleExactAlarms()
        try {
            when {
                // The strongest guarantee Android offers, and the only one Doze cannot defer.
                alarmClockMode && exact ->
                    alarmManager.setAlarmClock(
                        AlarmManager.AlarmClockInfo(trigger, null),
                        pending,
                    )
                exact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
                exact ->
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, trigger, pending)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
                else ->
                    alarmManager.set(AlarmManager.RTC_WAKEUP, trigger, pending)
            }
        } catch (security: SecurityException) {
            // The permission was revoked between the check and the call. Degrade instead of crashing
            // inside a receiver - the heartbeat will notice and reconcile later either way.
            Log.w(TAG, "exact alarm denied for $label; degrading to inexact", security)
            runCatching { alarmManager.set(AlarmManager.RTC_WAKEUP, trigger, pending) }
        }
    }

    private fun cancel(receiver: Class<*>, action: String, requestCode: Int, doseId: Long) {
        val intent = Intent(context, receiver).apply {
            this.action = action
            if (doseId > 0L) putExtra(ReminderReceiver.EXTRA_DOSE_ID, doseId)
        }
        // FLAG_NO_CREATE keeps the cancellation itself from *creating* a PendingIntent when nothing
        // was armed, which would otherwise leave an inert entry in the system's alarm list.
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pending != null) {
            alarmManager.cancel(pending)
            pending.cancel()
        }
    }

    private companion object {
        const val TAG = "ReminderHeartbeat"

        /** 03:30 - see [armNightlyPass]. */
        const val NIGHTLY_MINUTE_OF_DAY = 3 * 60 + 30
    }
}
