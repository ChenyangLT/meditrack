package com.meditrack.domain.reminder

import android.content.Context
import android.util.Log
import com.meditrack.core.util.DateTimeUtils
import com.meditrack.data.local.entity.DoseLog
import com.meditrack.data.local.entity.DoseStatus
import com.meditrack.data.local.entity.Medication
import com.meditrack.data.prefs.SettingsRepository
import com.meditrack.data.prefs.UserPreferences
import com.meditrack.data.repository.DoseRepository
import com.meditrack.data.repository.MedicationRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What one reconcile pass did, for logging and for the self-check screen.
 */
data class ReminderReport(
    val trigger: ReminderTrigger,
    val delivered: Int,
    val armedDoses: Int,
    val suppressed: Int,
    val deferred: Int,
    /** Doses whose schedule the pass had to *correct* rather than simply act on. */
    val corrected: Int,
    val disabled: Boolean,
    val nextSelfCheckAt: Long,
    /** The soonest alarm this pass armed, or 0 when nothing is pending. */
    val nextDoseAt: Long = 0L,
    /** The medication for [nextDoseAt], for the guard service's ongoing notification. */
    val nextDoseName: String? = null,
    /**
     * Reminders this pass found that had been armed but were never delivered by the system.
     *
     * Non-zero is direct evidence that the operating system cancelled or dropped the app's alarms -
     * which is exactly the case the guard service exists to prevent, and the one that is otherwise
     * invisible.
     */
    val lostAlarms: Int = 0,
)

/**
 * The one place that decides when a medication reminder happens.
 *
 * ## The architecture, in one sentence
 *
 * **The database is the source of truth; an alarm is only a hint; every trigger re-derives the
 * entire schedule from scratch and re-arms it, idempotently.**
 *
 * This is the idea the rewrite takes from [Chrono](https://github.com/vicolo-dev/chrono), whose
 * `updateAlarms()` cancels every alarm, walks the persisted alarm list and re-arms each one - on
 * trigger, on boot, on a background fetch and on a foreground-service tick. Chrono can therefore
 * lose any individual alarm and recover without anyone noticing, because no alarm is ever the only
 * record that something is due.
 *
 * The previous MediTrack pipeline did the opposite: it armed one alarm per dose, plus a single
 * maintenance alarm for the next midnight, and then *trusted* them. A `PendingIntent` dropped by an
 * OEM task killer, a force-stop, or a 24-hour Doze stretch was therefore unrecoverable - the dose
 * simply never fired, and the only thing that could ever fix it was the user opening the app.
 *
 * ## The four independent paths that all converge here
 *
 *  1. `ReminderReceiver` on a dose alarm or a heads-up alarm.
 *  2. `ReminderReceiver` on the rolling **self-check heartbeat** (every [UserPreferences.heartbeatMinutes]).
 *  3. `ReminderWorker` - a WorkManager periodic job, a genuinely different OS mechanism.
 *  4. Boot, time/time-zone change, package replace, app start and app resume.
 *
 * Because [reconcile] is idempotent, having four of them is free: whichever runs first does the
 * work, and the others find nothing left to do. That redundancy is the point - it is exactly what
 * makes "the reminder did not appear" become "the reminder appeared at most one interval late".
 *
 * ## Error correction, in one place
 *
 * Every pass repairs the schedule rather than obeying it:
 *
 *  - a trigger far ahead of its own time is treated as a clock error and re-armed, never announced;
 *  - a trigger far behind its time is downgraded from a banner to a silent 补记 prompt;
 *  - doses the user already took or skipped have their alarms cancelled;
 *  - alarms armed for doses the database no longer explains are cancelled by exact id;
 *  - lapsed doses are recorded as 未服药 *without* consuming the 未服药 notification, which the old
 *    sweep did, and which made that notification impossible to ever see;
 *  - a snoozed dose has its reminder budget reopened, so "稍后" means "tell me properly later".
 */
@Singleton
class ReminderEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settingsRepository: SettingsRepository,
    private val doseRepository: DoseRepository,
    private val medicationRepository: MedicationRepository,
    private val heartbeat: ReminderHeartbeat,
    private val registry: ReminderAlarmRegistry,
    private val notifier: DoseNotifier,
    private val usageMonitor: UsageMonitor,
    private val audit: ReminderAudit,
) {

    /**
     * Serialises passes.
     *
     * Four independent triggers can land at once - the heartbeat, the worker, a dose alarm and an
     * app resume - and two concurrent passes would both cancel, both re-arm, and each believe it
     * owned the registry. Serialising costs nothing (a pass is milliseconds of database work) and
     * removes an entire class of duplicate-notification race.
     */
    private val gate = Mutex()

    /** What is armed for one dose after a decision. */
    private data class ArmPlan(val preAtMillis: Long?, val doseAtMillis: Long?)

    // ------------------------------------------------------------------ public

    /**
     * Runs the full self-healing pass. Idempotent, and safe to call from anywhere at any time.
     */
    suspend fun reconcile(trigger: ReminderTrigger): ReminderReport = gate.withLock {
        val now = System.currentTimeMillis()
        val prefs = prefsOr()
        val today = DateTimeUtils.todayEpochDay()

        if (!prefs.remindersEnabled) {
            // The master switch is off: tear the schedule down rather than leaving stale alarms
            // behind that would fire the moment it is switched back on.
            heartbeat.cancelAll(registry.armed())
            registry.clear()
            notifier.cancelAll()
            return@withLock ReminderReport(
                trigger = trigger,
                delivered = 0,
                armedDoses = 0,
                suppressed = 0,
                deferred = 0,
                corrected = 0,
                disabled = true,
                nextSelfCheckAt = 0L,
            )
        }

        val materialised = materialise(today, trigger)

        // 1. Repair days the device was switched off for, so the history stays honest.
        val repaired = runCatching { doseRepository.repairHistory(today) }
            .onFailure { Log.w(TAG, "history repair failed", it) }
            .getOrDefault(0)

        // 2. Derive 未服药 for doses whose grace period has lapsed today. Status only - claiming the
        //    notification here is what used to make 未服药提醒 unreachable.
        runCatching { doseRepository.sweepMissed(today) }
            .onFailure { Log.w(TAG, "missed sweep failed", it) }

        // 3. Cancel every *dose* alarm the previous pass armed. From this point nothing per-dose is
        //    scheduled, and everything below is derived from the database rather than from what used
        //    to be armed.
        //
        //    The rolling heartbeat is deliberately left alone: it is the one alarm that must never
        //    be absent, because it is what recovers from every other alarm being lost. Cancelling it
        //    here and re-arming it at the end would open a window where the process could die with
        //    nothing scheduled at all - the exact state this rewrite exists to make impossible.
        val previouslyArmed = registry.armed()
        val expectations = registry.armedExpectations()
        heartbeat.cancelDoseAlarms(previouslyArmed)
        registry.clear()

        val window = ReminderPlanner.windowFor(prefs)
        val quietEnd = quietHoursEndMillis(prefs, now)
        val reminderContext = ReminderContext(
            nowMillis = now,
            localMinuteOfDay = localMinuteOfDay(now),
            quietHoursEndMillis = quietEnd,
            trigger = trigger,
        )

        val candidates = collectCandidates(now, prefs, today)
        val dueAnnouncements = mutableListOf<Triple<DoseLog, Medication?, Long>>()
        val headsUpAnnouncements = mutableListOf<Triple<DoseLog, Medication?, Long>>()
        val armedIds = mutableSetOf<Long>()
        val armedExpectations = mutableMapOf<Long, Long>()
        var suppressedCount = 0
        var deferredCount = 0
        var correctedCount = 0
        var lostAlarms = 0
        var nextDoseAt = 0L
        var nextDoseName: String? = null

        for (dose in candidates) {
            val due = ReminderPlanner.effectiveDueMillis(dose)

            // Did the system fail to deliver an alarm we know we set?
            //
            // The rule lives in the planner so it can be unit tested; this only supplies the state.
            // Recording it is the difference between "the app forgot" and "the operating system threw
            // the alarm away", and only the second one is fixed by the guard service.
            if (ReminderPlanner.alarmWasLost(expectations[dose.id], dose.notifiedTimeMillis, now)) {
                lostAlarms++
                val expectedAt = expectations.getValue(dose.id)
                audit.record(
                    dose.id, trigger, "ALARM_LOST",
                    driftMillis = now - expectedAt,
                    detail = "已排定的闹钟未被系统触发",
                )
            }

            var decision = ReminderPlanner.decide(dose, prefs, reminderContext)

            // Idle deferral depends on device state, which the pure planner must not touch.
            if (isNoisyFirstAnnouncement(decision) && usageMonitor.shouldDefer(prefs, now)) {
                decision = ReminderDecision.Deferred(Long.MAX_VALUE, DeferReason.IDLE)
            }

            when (decision) {
                is ReminderDecision.Suppressed -> {
                    suppressedCount++
                    audit.record(dose.id, trigger, decision.code, detail = decision.reason.label)
                }

                is ReminderDecision.TooEarly -> {
                    correctedCount++
                    audit.record(
                        dose.id, trigger, decision.code,
                        driftMillis = -decision.earlyByMillis,
                        detail = "触发早于计划，已重新排定",
                    )
                }

                is ReminderDecision.Deferred -> {
                    deferredCount++
                    if (decision.reason == DeferReason.IDLE) doseRepository.deferReminder(dose.id, now)
                    audit.record(dose.id, trigger, decision.code, detail = decision.reason.label)
                }

                is ReminderDecision.PreRemind -> {
                    val medication = medicationOf(dose)
                    notifier.showPreReminder(dose, medication, prefs, decision.leadMillis)
                    doseRepository.markPreReminded(dose.id, now)
                    headsUpAnnouncements += Triple(dose, medication, due)
                    audit.record(dose.id, trigger, decision.code, detail = "提前 ${decision.leadMillis / 60_000} 分钟")
                }

                is ReminderDecision.Remind -> {
                    val medication = medicationOf(dose)
                    notifier.showDoseReminder(
                        dose = dose,
                        medication = medication,
                        prefs = prefs,
                        quiet = decision.quiet,
                        lateMillis = decision.lateMillis,
                    )
                    doseRepository.markNotified(dose.id, now)
                    // Only the first announcement of a dose measures OS lateness; repeats are late
                    // by design, and averaging them in would hide the signal.
                    if (decision.escalation == 0 && decision.lateMillis > DRIFT_NOISE_MILLIS) {
                        registry.noteDrift(decision.lateMillis)
                    }
                    dueAnnouncements += Triple(dose, medication, due)
                    audit.record(
                        dose.id, trigger, decision.code,
                        driftMillis = if (decision.escalation == 0) decision.lateMillis else 0L,
                        detail = if (decision.escalation == 0) {
                            ReminderTiming.latenessLabel(decision.lateMillis)
                        } else {
                            "第 ${decision.escalation + 1} 次提醒"
                        },
                    )
                }

                is ReminderDecision.CatchUp -> {
                    val medication = medicationOf(dose)
                    notifier.showCatchUp(dose, medication, prefs, decision.lateMillis)
                    // Recorded as announced so the dose is not re-announced; the planner recognises
                    // an announcement that landed past its own stale threshold and never repeats it.
                    doseRepository.markNotified(dose.id, now)
                    dueAnnouncements += Triple(dose, medication, due)
                    audit.record(
                        dose.id, trigger, decision.code,
                        driftMillis = decision.lateMillis,
                        detail = ReminderTiming.latenessLabel(decision.lateMillis),
                    )
                }

                is ReminderDecision.Missed -> {
                    val medication = medicationOf(dose)
                    notifier.showMissedReminder(dose, medication, prefs)
                    doseRepository.markMissedNotified(dose.id)
                    doseRepository.markNotified(dose.id, now)
                    dueAnnouncements += Triple(dose, medication, due)
                    audit.record(
                        dose.id, trigger, decision.code,
                        driftMillis = decision.lateMillis,
                        detail = ReminderTiming.latenessLabel(decision.lateMillis),
                    )
                }
            }

            // The dose has now been dealt with, so any pending deferral flag has served its purpose.
            if (decision is ReminderDecision.Remind ||
                decision is ReminderDecision.CatchUp ||
                decision is ReminderDecision.Missed ||
                decision is ReminderDecision.PreRemind
            ) {
                doseRepository.clearDeferredReminders(listOf(dose.id))
            }

            armPlanFor(decision, dose, prefs, window, now)?.let { plan ->
                plan.preAtMillis?.let { heartbeat.armPreReminder(dose.id, it) }
                plan.doseAtMillis?.let { instant ->
                    heartbeat.armDose(dose.id, instant, prefs.alarmClockAlarms)
                    // The soonest outstanding alarm is what the guard notification counts down to.
                    if (instant > now && (nextDoseAt == 0L || instant < nextDoseAt)) {
                        nextDoseAt = instant
                        nextDoseName = medicationNameOf(dose)
                    }
                }
                armedIds += dose.id
                // The expectation is the reminder itself, not the heads-up: that is the delivery whose
                // absence matters.
                plan.doseAtMillis?.let { armedExpectations[dose.id] = it }
            }
        }

        registry.replaceArmed(armedIds, armedExpectations)
        publishDigest(dueAnnouncements, headsUpAnnouncements, prefs)
        // 4. Re-arm both rolling alarms. Doing this at the *end* of every pass is what makes the
        //    chain self-healing: whichever path got here, the next heartbeat is now guaranteed.
        val nextSelfCheck = heartbeat.armSelfCheck(
            nowMillis = now,
            intervalMinutes = prefs.heartbeatMinutes,
            // The heartbeat never uses alarm-clock mode: it is background housekeeping, and it has no
            // business putting an alarm icon in the user's status bar every fifteen minutes.
            alarmClockMode = false,
        )
        heartbeat.armNightlyPass(now)

        audit.prune()

        // Anything owed to a user who is holding the phone right now is delivered above; if the
        // state changed at all, the widget should say so too.
        if (dueAnnouncements.isNotEmpty() || headsUpAnnouncements.isNotEmpty() || repaired > 0 || materialised > 0) {
            runCatching { doseRepository.notifyWidgetRefresh() }
        }

        val report = ReminderReport(
            trigger = trigger,
            delivered = dueAnnouncements.size + headsUpAnnouncements.size,
            armedDoses = armedIds.size,
            suppressed = suppressedCount,
            deferred = deferredCount,
            corrected = correctedCount,
            disabled = false,
            nextSelfCheckAt = nextSelfCheck,
            nextDoseAt = nextDoseAt,
            nextDoseName = nextDoseName,
            lostAlarms = lostAlarms,
        )
        registry.recordReconcile(report)

        // Keep the guard service alive from inside the pipeline. Any path that gets this far proves
        // the process is running, and if the guard was killed along with the rest of the app this is
        // the cheapest possible moment to ask for it back. Failures are expected and ignored: from
        // Android 12 a background app is not always allowed to start a foreground service.
        if (prefs.guardServiceEnabled) {
            ReminderGuardService.ensureRunning(appContext)
        }

        Log.i(
            TAG,
            "reconcile(${trigger.name}): delivered=${report.delivered} armed=${report.armedDoses} " +
                "suppressed=${report.suppressed} deferred=${report.deferred} " +
                "corrected=${report.corrected} lost=${report.lostAlarms}",
        )
        report
    }

    /** Called when the user picks the phone up: releases anything held for idleness. */
    suspend fun onUserReturn(): ReminderReport {
        usageMonitor.recordInteraction()
        return reconcile(ReminderTrigger.USER_RETURN)
    }

    /**
     * Called after an action on a notification so the schedule matches the new state.
     *
     * Only for doses that have *settled* (taken or skipped). A snooze deliberately does not come
     * through here: it moves the alarm rather than removing it, and cancelling the alarm the
     * repository just armed would leave the reminder with nothing to wake it.
     */
    suspend fun onDoseStateChanged(doseId: Long) {
        heartbeat.cancelDose(doseId)
        registry.remove(doseId)
    }

    // --------------------------------------------------------------- internals

    /**
     * Every dose worth reasoning about right now.
     *
     * Four sources, merged by id. The union is deliberate: "what is due soon" is not enough, because
     * the failure this rewrite exists to fix is precisely the dose that fell due while nothing was
     * running and therefore never entered anyone's "due soon" list.
     */
    private suspend fun collectCandidates(
        now: Long,
        prefs: UserPreferences,
        today: Long,
    ): List<DoseLog> {
        val merged = LinkedHashMap<Long, DoseLog>()

        // (a) Missed their moment while nothing was running, but still recent enough to act on.
        val floor = now -
            (prefs.staleReminderMinutes.coerceAtLeast(1) + prefs.missedGraceMinutes.coerceAtLeast(0)) * 60_000L
        runCatching { doseRepository.getOverdueOpen(now, floor) }
            .onFailure { Log.w(TAG, "overdue query failed", it) }
            .getOrDefault(emptyList())
            .forEach { merged[it.id] = it }

        // (b) Everything still ahead inside the horizon, which is what gets armed.
        val horizonEnd = DateTimeUtils.startOfDayMillis(today + HORIZON_DAYS + 1)
        runCatching { doseRepository.getArmableBetween(now, horizonEnd) }
            .onFailure { Log.w(TAG, "armable query failed", it) }
            .getOrDefault(emptyList())
            .forEach { merged[it.id] = it }

        // (c) Reminders deliberately withheld earlier are still owed, however old they are.
        runCatching { doseRepository.getDeferredDoses() }
            .onFailure { Log.w(TAG, "deferred query failed", it) }
            .getOrDefault(emptyList())
            .forEach { merged[it.dose.id] = it.dose }

        // (d) Doses recorded as 未服药 today whose notice has not been shown yet.
        runCatching { doseRepository.getRawDoses(today) }
            .onFailure { Log.w(TAG, "today query failed", it) }
            .getOrDefault(emptyList())
            .filter { it.status == DoseStatus.MISSED && !it.missedNotified }
            .forEach { merged[it.id] = it }

        return merged.values.sortedBy { ReminderPlanner.effectiveDueMillis(it) }
    }

    /**
     * When (if ever) to arm an alarm for this dose, given what was just decided.
     *
     * Deriving the instant from the *decision* rather than from a re-read of the dose is what keeps
     * the pass a single consistent snapshot: the decision already encodes both the pre-action state
     * and the action taken.
     */
    private fun armPlanFor(
        decision: ReminderDecision,
        dose: DoseLog,
        prefs: UserPreferences,
        window: ReminderWindow,
        now: Long,
    ): ArmPlan? {
        if (dose.status == DoseStatus.MISSED) return null

        val due = ReminderPlanner.effectiveDueMillis(dose)
        val repeatMillis = prefs.repeatReminderMinutes.coerceAtLeast(1) * 60_000L

        // The heads-up is armed whenever it is still ahead and has not been shown. It belongs to the
        // original schedule, so a dose the user has snoozed never gets one.
        val preAt = if (window.leadMinutes > 0 &&
            dose.preRemindedAtMillis == null &&
            ReminderPlanner.headsUpAppliesTo(dose)
        ) {
            (due - window.leadMillis).takeIf { it > now }
        } else {
            null
        }

        val doseAt: Long? = when (decision) {
            is ReminderDecision.TooEarly -> decision.dueAtMillis
            is ReminderDecision.Deferred ->
                decision.untilMillis.takeIf { it != Long.MAX_VALUE }?.coerceAtLeast(now + 1_000L)
            // The heads-up just went out; the reminder itself is what comes next.
            is ReminderDecision.PreRemind -> decision.dueAtMillis.coerceAtLeast(now + 1_000L)
            is ReminderDecision.Remind -> {
                val nextEscalation = decision.escalation + 1
                val budgetLeft = nextEscalation < prefs.maxEscalationsPerDose.coerceAtLeast(1)
                if (prefs.repeatReminderMinutes > 0 && budgetLeft) now + repeatMillis else null
            }
            // Both of these settle the dose for good.
            is ReminderDecision.CatchUp, is ReminderDecision.Missed -> null
            is ReminderDecision.Suppressed -> when (decision.reason) {
                ReminderSuppression.WAITING -> due
                ReminderSuppression.TOO_SOON ->
                    (dose.notifiedTimeMillis ?: now) + repeatMillis
                else -> null
            }
        }

        if (preAt == null && doseAt == null) return null
        return ArmPlan(preAtMillis = preAt, doseAtMillis = doseAt)
    }

    /**
     * Posts a digest for each group of doses announced together.
     *
     * Two medications scheduled five minutes apart are one event in a person's morning. Announcing
     * them as two separate buzzes is the machine behaviour this rewrite removes; the individual
     * notifications still exist as group children, so each dose keeps its own 已服 / 稍后 / 跳过
     * buttons.
     *
     * The advance notices are summarised **separately** from the reminders themselves. Mixing them
     * would produce a single row that lists "还有 15 分钟" items next to items that are due right now,
     * which is exactly the kind of ambiguous summary the digest exists to avoid.
     */
    private suspend fun publishDigest(
        dueAnnouncements: List<Triple<DoseLog, Medication?, Long>>,
        headsUpAnnouncements: List<Triple<DoseLog, Medication?, Long>>,
        prefs: UserPreferences,
    ) {
        if (!prefs.digestEnabled) return
        val window = ReminderPlanner.windowFor(prefs)

        for (group in listOf(dueAnnouncements, headsUpAnnouncements)) {
            if (group.size < 2) continue

            // Only cluster what belongs to the same moment; an 07:45 heads-up and a 15:00 reminder
            // are not one event even if both were computed during the same pass.
            val clusters = ReminderPlanner.cluster(group.map { it.first to it.third }, window)
            val byId = group.associateBy { it.first.id }

            for (cluster in clusters) {
                if (cluster.size < 2) continue
                val items = cluster.mapNotNull { (dose, _) -> byId[dose.id]?.let { it.first to it.second } }
                if (items.size < 2) continue
                notifier.showDigest(items, prefs)
            }
        }
    }

    /**
     * Materialises the dose rows this pass needs.
     *
     * The horizon is trimmed for the high-frequency heartbeat: there is no reason to rebuild three
     * days of rows every quarter of an hour, but there is every reason for the cheap triggers to
     * keep today and tomorrow alive, because on a phone nobody opens, the heartbeat may be the only
     * thing running for days.
     */
    private suspend fun materialise(today: Long, trigger: ReminderTrigger): Int {
        val days = if (trigger == ReminderTrigger.HEARTBEAT) 1L else HORIZON_DAYS
        var count = 0
        for (day in today..(today + days)) {
            count += runCatching { doseRepository.materializeDay(day).size }
                .onFailure { Log.w(TAG, "materialise day $day failed", it) }
                .getOrDefault(0)
        }
        return count
    }

    private suspend fun medicationOf(dose: DoseLog): Medication? =
        runCatching { medicationRepository.getWithSchedules(dose.medicationId)?.medication }.getOrNull()

    /** The medication's name for the ongoing guard notification, or null when it cannot be read. */
    private suspend fun medicationNameOf(dose: DoseLog): String? = medicationOf(dose)?.name

    private suspend fun prefsOr(): UserPreferences =
        runCatching { settingsRepository.current() }.getOrDefault(UserPreferences())

    /** True for the decisions that would interrupt the user, which is what idle deferral holds back. */
    private fun isNoisyFirstAnnouncement(decision: ReminderDecision): Boolean = when (decision) {
        is ReminderDecision.PreRemind -> true
        is ReminderDecision.Remind -> decision.escalation == 0 && !decision.quiet
        else -> false
    }

    private fun localMinuteOfDay(now: Long): Int {
        val time = Instant.ofEpochMilli(now).atZone(DateTimeUtils.zone()).toLocalTime()
        return time.hour * 60 + time.minute
    }

    /**
     * The instant quiet hours end, if [now] is inside them **and** the user asked for deferral.
     *
     * Returning 0 rather than a sentinel keeps this free of null handling at the call site, and 0 can
     * never be a legitimate future instant.
     */
    private fun quietHoursEndMillis(prefs: UserPreferences, now: Long): Long {
        if (!prefs.quietHoursEnabled || !prefs.quietHoursDeferEnabled) return 0L
        if (!prefs.isWithinQuietHours(localMinuteOfDay(now))) return 0L

        val zone = DateTimeUtils.zone()
        val endMinute = prefs.quietHoursEndMinute.coerceIn(0, ReminderTiming.MINUTES_PER_DAY - 1)
        val localDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        var end = localDate.atTime(endMinute / 60, endMinute % 60).atZone(zone).toInstant().toEpochMilli()
        // Inside a window that wraps past midnight, "today's" end has already passed.
        if (end <= now) {
            end = DateTimeUtils.millisAt(
                DateTimeUtils.epochDayOf(localDate) + 1,
                endMinute,
            )
        }
        return end
    }

    companion object {
        private const val TAG = "ReminderEngine"

        /**
         * How many days ahead are materialised and armed.
         *
         * Two days past today covers a long weekend of the phone being off, and is what the old
         * maintenance pass used. The heartbeat needs less, so it trims to one.
         */
        const val HORIZON_DAYS = 2L

        /**
         * Lateness below this is ordinary scheduling jitter, not drift worth reporting.
         *
         * `setExactAndAllowWhileIdle` routinely lands a second or two late, and a self-check that
         * cried wolf about that would train the user to ignore it.
         */
        const val DRIFT_NOISE_MILLIS = 60_000L
    }
}
