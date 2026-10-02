package com.meditrack.data.repository

import com.meditrack.core.util.DateTimeUtils
import com.meditrack.core.util.QuantityFormatter
import com.meditrack.data.local.dao.DoseLogDao
import com.meditrack.data.local.dao.HomeWidgetDao
import com.meditrack.data.local.dao.MedicationDao
import com.meditrack.data.local.dao.WidgetDoseRow
import com.meditrack.data.local.entity.DoseEvent
import com.meditrack.data.local.entity.DoseEventType
import com.meditrack.data.local.entity.DoseLog
import com.meditrack.data.local.entity.DoseLogWithMedication
import com.meditrack.data.local.entity.DoseStatus
import com.meditrack.data.prefs.SettingsRepository
import com.meditrack.domain.plan.DayPlanner
import com.meditrack.domain.plan.DoseView
import com.meditrack.domain.plan.PlannedDose
import com.meditrack.domain.plan.TodaySummary
import com.meditrack.domain.plan.WidgetContent
import com.meditrack.domain.plan.WidgetPlanner
import com.meditrack.domain.plan.WidgetPriority
import com.meditrack.domain.reminder.ReminderAlarmRegistry
import com.meditrack.domain.reminder.ReminderHeartbeat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The result of a "+" / "-" / skip operation, returned to the caller so the UI can show the right
 * feedback or confirmation prompt.
 */
sealed interface DoseActionResult {
    /** The change was applied and is now persisted. */
    data class Applied(val dose: DoseView) : DoseActionResult

    /**
     * The change would exceed the medication's configured maximum. Nothing was written; the UI
     * must show "是否确认多服？" and call back with `confirmOverDose = true`.
     */
    data class NeedsOverDoseConfirmation(
        val dose: DoseView,
        val attemptedQuantity: Double,
        val maxQuantity: Double,
    ) : DoseActionResult

    /** The dose could not be found (deleted while its notification was on screen). */
    data object NotFound : DoseActionResult
}

/**
 * Every read and write that concerns "did I take it, and how much".
 *
 * This is the only place that mutates [DoseLog] rows, which keeps three invariants true:
 *  1. the status is always derived from the quantity, never set independently of it;
 *  2. every mutation appends a [DoseEvent] so it can be undone and audited;
 *  3. the medication's stock moves by exactly the delta that was recorded.
 */
@Singleton
class DoseRepository @Inject constructor(
    private val doseLogDao: DoseLogDao,
    private val medicationDao: MedicationDao,
    private val homeWidgetDao: HomeWidgetDao,
    private val medicationRepository: MedicationRepository,
    private val settingsRepository: SettingsRepository,
    /**
     * Lazy on purpose.
     *
     * The reminder layer needs this repository in order to read the doses it must announce, so the
     * dependency has to be broken somewhere. Holding it lazily means neither side is constructed
     * inside the other's constructor, and no Dagger cycle exists to explain away.
     */
    private val heartbeat: dagger.Lazy<ReminderHeartbeat>,
    private val alarmRegistry: ReminderAlarmRegistry,
    private val widgetUpdater: dagger.Lazy<WidgetUpdater>,
) {

    // --------------------------------------------------------------- reads

    /**
     * The today screen's single source of truth.
     *
     * It combines the stored dose rows with the medication definitions, expands the schedules into
     * planned doses for the day, and re-derives every status from the current clock. Because each
     * underlying Room query is a Flow, the list updates the instant a notification action or a
     * widget tap writes to the database.
     */
    fun observeDay(epochDay: Long = DateTimeUtils.todayEpochDay()): Flow<DayState> {
        return combine(
            doseLogDao.observeForDay(epochDay),
            medicationRepository.observeMedications(),
            settingsRepository.preferences,
        ) { stored, medications, prefs ->
            val storedByKey = stored.associateBy { it.dose.scheduleId to it.dose.epochDay }
            val medById = medications.associateBy { it.medication.id }
            val now = System.currentTimeMillis()

            val planned = DayPlanner.plan(
                medications = medications.map { mws ->
                    com.meditrack.data.local.entity.MedicationWithSchedules(
                        medication = mws.medication,
                        schedules = mws.schedules.map { s ->
                            com.meditrack.data.local.entity.Schedule(
                                id = s.id,
                                medicationId = mws.medication.id,
                                minuteOfDay = s.minuteOfDay,
                                repeatRule = s.repeatRule,
                                startEpochDay = s.startEpochDay,
                                endEpochDay = s.endEpochDay,
                                reminderEnabled = s.reminderEnabled,
                            )
                        },
                    )
                },
                epochDay = epochDay,
            )

            val views = ArrayList<DoseView>(planned.size)
            for (p in planned) {
                val storedRow = storedByKey[p.scheduleId to p.epochDay]
                val med = medById[p.medicationId]?.medication ?: storedRow?.medication ?: continue
                views += DoseView.of(
                    planned = p,
                    medication = med,
                    schedule = storedRow?.schedule,
                    stored = storedRow?.dose,
                    nowMillis = now,
                    use24Hour = prefs.use24HourFormat,
                )
            }

            // Doses whose schedule was deleted after the fact (or that were recorded ad hoc) would
            // otherwise vanish from the list even though they hold real history.
            val plannedKeys = planned.map { it.scheduleId to it.epochDay }.toSet()
            for (row in stored) {
                if ((row.dose.scheduleId to row.dose.epochDay) in plannedKeys) continue
                views += DoseView.of(
                    planned = row.dose.toPlannedDose(),
                    medication = row.medication,
                    schedule = row.schedule,
                    stored = row.dose,
                    nowMillis = now,
                    use24Hour = prefs.use24HourFormat,
                )
            }

            val sorted = views.sortedWith(
                compareBy<DoseView> { it.plannedMinuteOfDay }.thenBy { it.medicationName }
            )
            DayState(
                epochDay = epochDay,
                doses = sorted,
                summary = TodaySummary.from(sorted, epochDay),
                nowMillis = now,
            )
        }
        // Note: no distinctUntilChanged() here. Two emissions built a minute apart carry different
        // `nowMillis` and therefore different statuses and relative labels; suppressing them would
        // freeze the "还有 N 分钟" countdown.
    }

    /** A single dose, for the detail sheet and for notification actions that only know the id. */
    suspend fun getDoseView(doseId: Long): DoseView? {
        val row = doseLogDao.getWithMedicationById(doseId) ?: return null
        return DoseView.of(
            planned = row.dose.toPlannedDose(),
            medication = row.medication,
            schedule = row.schedule,
            stored = row.dose,
            use24Hour = settingsRepository.current().use24HourFormat,
        )
    }

    suspend fun getDose(doseId: Long): DoseLog? = doseLogDao.getById(doseId)

    /** A day's doses straight from storage; used by the history screens. */
    suspend fun getDayRows(epochDay: Long): List<DoseLogWithMedication> = doseLogDao.getForDay(epochDay)

    /** Raw rows for a day, without the medication join; used by the statistics builder. */
    suspend fun getRawDoses(epochDay: Long): List<DoseLog> = doseLogDao.getRawForDay(epochDay)

    suspend fun getDosesBetween(fromEpochDay: Long, toEpochDay: Long): List<DoseLog> =
        doseLogDao.getBetween(fromEpochDay, toEpochDay)

    /**
     * Days on which at least one intake was recorded, newest first.
     *
     * Used by the streak calculation, which only needs the dates - loading every dose row of the
     * user's entire history just to derive them would be wasteful.
     */
    suspend fun getDaysWithIntake(medicationId: Long? = null): List<Long> =
        doseLogDao.getDaysWithIntake(medicationId)

    fun observeRecentEvents(limit: Int = 100): Flow<List<DoseEvent>> = doseLogDao.observeRecentEvents(limit)

    suspend fun getEventsFor(doseId: Long): List<DoseEvent> = doseLogDao.getEventsFor(doseId)

    // ------------------------------------------------------------ materialisation

    /**
     * Ensures a [DoseLog] row exists for every planned dose of [epochDay] and returns them.
     *
     * Called when the today screen opens and when the reminder scheduler arms a day, so a
     * notification always has a durable row to write against - even on a day the user never opened
     * the app.
     */
    suspend fun materializeDay(epochDay: Long): List<DoseLog> {
        val medications = medicationRepository.getActiveWithSchedules()
        val planned = DayPlanner.plan(medications, epochDay)
        if (planned.isEmpty()) return emptyList()

        val existing = doseLogDao.getRawForDay(epochDay).associateBy { it.scheduleId }
        val toInsert = ArrayList<DoseLog>(planned.size)

        for (p in planned) {
            if (existing.containsKey(p.scheduleId)) continue
            toInsert += DoseLog(
                medicationId = p.medicationId,
                scheduleId = p.scheduleId,
                epochDay = p.epochDay,
                plannedMinuteOfDay = p.minuteOfDay,
                plannedTimeMillis = p.plannedTimeMillis,
                plannedQuantity = p.plannedQuantity,
                plannedUnit = p.unitLabel,
                status = DayPlanner.deriveStatus(
                    plannedTimeMillis = p.plannedTimeMillis,
                    takenQuantity = 0.0,
                    plannedQuantity = p.plannedQuantity,
                ),
            )
        }

        if (toInsert.isNotEmpty()) {
            val ids = doseLogDao.insertAll(toInsert)
            // insertAll uses IGNORE, so a concurrent writer may already own a row. Re-read in that
            // case rather than trusting the returned ids.
            if (ids.any { it == -1L }) return doseLogDao.getRawForDay(epochDay)
        }
        return doseLogDao.getRawForDay(epochDay).sortedBy { it.plannedMinuteOfDay }
    }

    // ---------------------------------------------------------------- writes

    /**
     * Applies a signed change to the taken amount - the "+" and "-" buttons.
     *
     * Rules enforced here (all covered by unit tests):
     *  - the result can never go below zero;
     *  - reaching the planned amount flips the status to TAKEN and stamps the time;
     *  - dropping below the planned amount returns PARTIAL, and at exactly zero returns to the
     *    clock-derived status;
     *  - exceeding the medication maximum asks for confirmation first;
     *  - the medication's stock moves by the same delta;
     *  - the change is appended to the audit trail so [undoLastChange] can reverse it.
     */
    suspend fun adjustQuantity(
        doseId: Long,
        delta: Double,
        confirmOverDose: Boolean = false,
    ): DoseActionResult {
        val dose = doseLogDao.getById(doseId) ?: return DoseActionResult.NotFound
        val medication = medicationDao.getWithSchedulesById(dose.medicationId)?.medication
            ?: return DoseActionResult.NotFound
        val prefs = settingsRepository.current()
        val newQuantity = QuantityFormatter.sanitize((dose.takenQuantity + delta).coerceAtLeast(0.0))

        // "多服" guard: only for positive changes, only when a maximum is configured.
        val max = medication.maxDoseAmount
        val exceedsMax = delta > 0 && max > 0.0 && !confirmOverDose && !dose.overDoseConfirmed &&
            newQuantity > max + QuantityFormatter.EPSILON
        if (exceedsMax) {
            val current = getDoseView(doseId) ?: return DoseActionResult.NotFound
            return if (prefs.confirmOverDose) {
                DoseActionResult.NeedsOverDoseConfirmation(
                    dose = current,
                    attemptedQuantity = newQuantity,
                    maxQuantity = max,
                )
            } else {
                applyQuantity(dose, newQuantity, confirmedOverDose = true)
            }
        }

        return applyQuantity(dose, newQuantity, confirmedOverDose = confirmOverDose)
    }

    /** Single writer for the taken amount; keeps status, stock and audit trail consistent. */
    private suspend fun applyQuantity(
        dose: DoseLog,
        newQuantity: Double,
        confirmedOverDose: Boolean,
    ): DoseActionResult {
        val now = System.currentTimeMillis()
        val status = statusFor(
            quantity = newQuantity,
            plannedQuantity = dose.plannedQuantity,
            plannedTimeMillis = dose.plannedTimeMillis,
            snoozedUntilMillis = dose.snoozedUntilMillis,
            now = now,
        )
        val overDose = confirmedOverDose || dose.overDoseConfirmed
        val takenTime = if (QuantityFormatter.isZero(newQuantity)) null else (dose.takenTimeMillis ?: now)

        doseLogDao.updateQuantity(
            id = dose.id,
            quantity = newQuantity,
            status = status,
            takenTimeMillis = takenTime,
            overDoseConfirmed = overDose,
            now = now,
        )

        applyStockDelta(dose.medicationId, newQuantity - dose.takenQuantity, now)

        doseLogDao.insertEvent(
            DoseEvent(
                doseLogId = dose.id,
                type = if (newQuantity >= dose.takenQuantity) DoseEventType.INCREMENT
                else DoseEventType.DECREMENT,
                delta = newQuantity - dose.takenQuantity,
                resultingQuantity = newQuantity,
                resultingStatus = status,
                timestamp = now,
            )
        )

        onDoseSettled(dose.id, status)
        return getDoseView(dose.id)?.let { DoseActionResult.Applied(it) } ?: DoseActionResult.NotFound
    }

    /** Marks the dose as fully taken in one tap (the notification's "已服" action). */
    suspend fun markTaken(doseId: Long, confirmOverDose: Boolean = false): DoseActionResult {
        val dose = doseLogDao.getById(doseId) ?: return DoseActionResult.NotFound
        val remaining = (dose.plannedQuantity - dose.takenQuantity).coerceAtLeast(0.0)
        val result: DoseActionResult = if (QuantityFormatter.isZero(remaining)) {
            // Already at the planned amount (reached via the stepper) - just confirm the status.
            // applyStatus returns Unit, so the resulting view is fetched explicitly here.
            applyStatus(dose, DoseStatus.TAKEN)
            getDoseView(doseId)?.let { DoseActionResult.Applied(it) } ?: DoseActionResult.NotFound
        } else {
            adjustQuantity(doseId, remaining, confirmOverDose)
        }
        if (result is DoseActionResult.Applied) {
            doseLogDao.insertEvent(
                DoseEvent(
                    doseLogId = doseId,
                    type = DoseEventType.MARK_TAKEN,
                    delta = remaining,
                    resultingQuantity = result.dose.takenQuantity,
                    resultingStatus = result.dose.status,
                )
            )
        }
        return result
    }

    /** Skips a dose. Recorded distinctly from a miss so adherence maths can tell them apart. */
    suspend fun skip(doseId: Long): DoseActionResult {
        val dose = doseLogDao.getById(doseId) ?: return DoseActionResult.NotFound
        val now = System.currentTimeMillis()
        if (dose.takenQuantity > 0.0) applyStockDelta(dose.medicationId, -dose.takenQuantity, now)

        doseLogDao.updateQuantity(
            id = doseId,
            quantity = 0.0,
            status = DoseStatus.SKIPPED,
            takenTimeMillis = null,
            overDoseConfirmed = dose.overDoseConfirmed,
            now = now,
        )
        doseLogDao.insertEvent(
            DoseEvent(
                doseLogId = doseId,
                type = DoseEventType.SKIP,
                delta = -dose.takenQuantity,
                resultingQuantity = 0.0,
                resultingStatus = DoseStatus.SKIPPED,
                timestamp = now,
            )
        )
        onDoseSettled(doseId, DoseStatus.SKIPPED)
        return getDoseView(doseId)?.let { DoseActionResult.Applied(it) } ?: DoseActionResult.NotFound
    }

    /** Reverses a skip, returning the dose to its quantity-derived status. */
    suspend fun unskip(doseId: Long): DoseActionResult {
        val dose = doseLogDao.getById(doseId) ?: return DoseActionResult.NotFound
        val status = statusFor(
            quantity = dose.takenQuantity,
            plannedQuantity = dose.plannedQuantity,
            plannedTimeMillis = dose.plannedTimeMillis,
            snoozedUntilMillis = dose.snoozedUntilMillis,
        )
        applyStatus(dose, status, DoseEventType.UNSKIP)
        if (status != DoseStatus.TAKEN) armDoseReminder(dose, dose.plannedTimeMillis)
        return getDoseView(doseId)?.let { DoseActionResult.Applied(it) } ?: DoseActionResult.NotFound
    }

    /**
     * "稍后提醒" - pushes the reminder out and re-arms the alarm.
     *
     * The delay is measured from the dose's **scheduled** time, not from the moment the button was
     * tapped. That distinction matters whenever the reminder is actioned early: a 17:55 dose snoozed
     * for 10 minutes at 17:06 must come back at 18:05, not at 17:16. Measuring from "now" made the
     * snooze button move a reminder *earlier* than its own schedule, which is never what the user
     * meant by "remind me later".
     *
     * The base is clamped forward to the current time so that:
     *  - a dose whose time has already passed comes back in [minutes] from now (there is nothing
     *    sensible to add to a time in the past), and
     *  - snoozing twice in a row advances rather than stacking behind the previous deadline.
     */
    suspend fun snooze(
        doseId: Long,
        /** null means "use the user's configured snooze length". */
        minutes: Int? = null,
    ): DoseActionResult {
        val dose = doseLogDao.getById(doseId) ?: return DoseActionResult.NotFound
        val now = System.currentTimeMillis()
        // Resolved here rather than as a default argument: a default value cannot be suspend, and
        // reading the preference is a suspending DataStore call.
        val clamped = (minutes ?: settingsRepository.current().snoozeMinutes).coerceIn(1, 240)

        // Scheduled time, or the deadline set by a previous snooze if that is later.
        val scheduledBase = maxOf(dose.plannedTimeMillis, dose.snoozedUntilMillis ?: Long.MIN_VALUE)
        val until = maxOf(scheduledBase, now) + clamped * 60_000L

        doseLogDao.snooze(doseId, until, now)
        doseLogDao.insertEvent(
            DoseEvent(
                doseLogId = doseId,
                type = DoseEventType.SNOOZE,
                delta = 0.0,
                resultingQuantity = dose.takenQuantity,
                resultingStatus = DoseStatus.DUE,
                timestamp = now,
                note = "推迟 ${clamped} 分钟，改为 ${DateTimeUtils.formatDateTime(until).takeLast(5)}",
            )
        )
        doseLogDao.getById(doseId)?.let { armDoseReminder(it, until) }
        notifyWidgetRefresh()
        return getDoseView(doseId)?.let { DoseActionResult.Applied(it) } ?: DoseActionResult.NotFound
    }

    /** Restores a dose to "untouched"; used by the history screen's correction action. */
    suspend fun reset(doseId: Long): DoseActionResult {
        val dose = doseLogDao.getById(doseId) ?: return DoseActionResult.NotFound
        val now = System.currentTimeMillis()
        if (dose.takenQuantity > 0.0) applyStockDelta(dose.medicationId, -dose.takenQuantity, now)

        val status = DayPlanner.deriveStatus(
            plannedTimeMillis = dose.plannedTimeMillis,
            takenQuantity = 0.0,
            plannedQuantity = dose.plannedQuantity,
            snoozedUntilMillis = dose.snoozedUntilMillis,
            nowMillis = now,
        )
        doseLogDao.updateQuantity(
            id = doseId,
            quantity = 0.0,
            status = status,
            takenTimeMillis = null,
            overDoseConfirmed = false,
            now = now,
        )
        doseLogDao.insertEvent(
            DoseEvent(
                doseLogId = doseId,
                type = DoseEventType.RESET,
                delta = -dose.takenQuantity,
                resultingQuantity = 0.0,
                resultingStatus = status,
                timestamp = now,
            )
        )
        // A missed dose that the user is now correcting deserves its reminder back - with a full
        // budget, otherwise "重置" would hand back a dose whose escalation counter was already spent
        // and whose reminder would therefore be suppressed on arrival.
        doseLogDao.clearReminderState(doseId, now)
        if (status != DoseStatus.TAKEN) armDoseReminder(dose, dose.plannedTimeMillis)
        notifyWidgetRefresh()
        return getDoseView(doseId)?.let { DoseActionResult.Applied(it) } ?: DoseActionResult.NotFound
    }

    /**
     * Undo for the last "+" / "-" / skip.
     *
     * The previous quantity is reconstructed from the audit trail rather than recomputed, so
     * undoing an over-dose, a manual edit and a notification action all behave identically.
     */
    suspend fun undoLastChange(doseId: Long): DoseActionResult {
        val last = doseLogDao.getLastEvent(doseId) ?: return DoseActionResult.NotFound
        if (last.type == DoseEventType.UNDO) return DoseActionResult.NotFound
        val dose = doseLogDao.getById(doseId) ?: return DoseActionResult.NotFound
        val now = System.currentTimeMillis()

        val restoredQuantity = QuantityFormatter.sanitize(
            (dose.takenQuantity - last.delta).coerceAtLeast(0.0)
        )
        val restoredStatus = statusFor(
            quantity = restoredQuantity,
            plannedQuantity = dose.plannedQuantity,
            plannedTimeMillis = dose.plannedTimeMillis,
            snoozedUntilMillis = dose.snoozedUntilMillis,
            now = now,
        )

        doseLogDao.updateQuantity(
            id = doseId,
            quantity = restoredQuantity,
            status = restoredStatus,
            takenTimeMillis = if (QuantityFormatter.isZero(restoredQuantity)) null
            else (dose.takenTimeMillis ?: now),
            overDoseConfirmed = dose.overDoseConfirmed,
            now = now,
        )
        applyStockDelta(dose.medicationId, restoredQuantity - dose.takenQuantity, now)

        doseLogDao.insertEvent(
            DoseEvent(
                doseLogId = doseId,
                type = DoseEventType.UNDO,
                delta = restoredQuantity - dose.takenQuantity,
                resultingQuantity = restoredQuantity,
                resultingStatus = restoredStatus,
                timestamp = now,
                note = "撤销「${last.type.label}」",
            )
        )
        onDoseSettled(doseId, restoredStatus)
        return getDoseView(doseId)?.let { DoseActionResult.Applied(it) } ?: DoseActionResult.NotFound
    }

    /**
     * Escalates untouched doses whose grace period has lapsed to MISSED, and returns them.
     *
     * Safe to call often: idempotent, and it never touches a dose with any recorded amount or an
     * active snooze.
     *
     * **It deliberately no longer sets `missedNotified`.** Those two facts used to be written by one
     * UPDATE, which meant this sweep - running at app start, on every heartbeat and at boot - always
     * won the race against the notification and claimed the "already told them" flag first. The
     * 未服药 reminder was therefore unreachable in practice. Deriving the status and announcing it
     * are separate facts and are now separate writes.
     */
    suspend fun sweepMissed(epochDay: Long = DateTimeUtils.todayEpochDay()): List<DoseLog> {
        val prefs = settingsRepository.current()
        val missed = doseLogDao.getRawForDay(epochDay).filter { dose ->
            DayPlanner.shouldEscalateToMissed(dose, prefs.missedGraceMinutes)
        }
        for (dose in missed) {
            doseLogDao.deriveMissed(dose.id)
            doseLogDao.insertEvent(
                DoseEvent(
                    doseLogId = dose.id,
                    type = DoseEventType.MISSED,
                    delta = 0.0,
                    resultingQuantity = dose.takenQuantity,
                    resultingStatus = DoseStatus.MISSED,
                    note = "超过计划时间 ${prefs.missedGraceMinutes} 分钟未记录",
                )
            )
        }
        if (missed.isNotEmpty()) notifyWidgetRefresh()
        return missed
    }

    /**
     * Repairs history for days the device was off, so statistics are not silently optimistic.
     *
     * Unlike the same-day sweep this *does* claim `missedNotified`, because a dose from a previous
     * day must never produce a notification - the user would be told about a miss they can no longer
     * act on.
     */
    suspend fun repairHistory(todayEpochDay: Long = DateTimeUtils.todayEpochDay()): Int =
        doseLogDao.sweepMissedBefore(todayEpochDay)

    suspend fun markNotified(doseId: Long, at: Long = System.currentTimeMillis()) =
        doseLogDao.markNotified(doseId, at)

    /** Records that the "还有一会儿" heads-up was posted, so it is never posted twice. */
    suspend fun markPreReminded(doseId: Long, at: Long = System.currentTimeMillis()) =
        doseLogDao.markPreReminded(doseId, at)

    suspend fun markMissedNotified(doseId: Long) = doseLogDao.markMissedNotified(doseId)

    // -------------------------------------------------- reminder engine queries

    /** Open doses whose effective due instant has passed but which are still recent enough to act on. */
    suspend fun getOverdueOpen(nowMillis: Long, notBeforeMillis: Long): List<DoseLog> =
        doseLogDao.getOverdueOpen(nowMillis, notBeforeMillis)

    /** Open doses whose effective due instant falls inside a window; these are what get armed. */
    suspend fun getArmableBetween(fromMillis: Long, toMillis: Long): List<DoseLog> =
        doseLogDao.getArmableBetween(fromMillis, toMillis)

    // ------------------------------------------------------ reminder arming

    /**
     * Arms one dose's reminder and records it in the registry.
     *
     * Both halves matter. The alarm alone is not enough, because the reconcile pass cancels *every*
     * alarm it previously armed and an unregistered one would be missed - leaving an orphaned
     * `PendingIntent` that fires for a dose the user has since taken.
     */
    private suspend fun armDoseReminder(dose: DoseLog, at: Long) {
        val prefs = settingsRepository.current()
        heartbeat.get().armDose(dose.id, at, prefs.alarmClockAlarms)
        alarmRegistry.add(dose.id)
    }

    private suspend fun cancelDoseReminder(doseId: Long) {
        heartbeat.get().cancelDose(doseId)
        alarmRegistry.remove(doseId)
    }

    // ------------------------------------------------ deferred (idle) reminders

    /**
     * Withholds a dose's reminder because the user was not using the phone.
     *
     * Only reachable when the user has switched idle deferral on; the caller (the reminder
     * receiver) checks the preference first.
     */
    suspend fun deferReminder(doseId: Long, at: Long = System.currentTimeMillis()) =
        doseLogDao.markDeferred(doseId, at)

    /** Doses whose reminder is still owed to the user, oldest first. */
    suspend fun getDeferredDoses(): List<DoseLogWithMedication> {
        // Anything taken or skipped while deferred is no longer owed.
        doseLogDao.clearResolvedDeferred()
        return doseLogDao.getDeferred()
    }

    suspend fun clearDeferredReminders(ids: List<Long>) {
        if (ids.isEmpty()) return
        doseLogDao.clearDeferred(ids)
    }

    // ---------------------------------------------------------------- widget

    /** Raw widget rows for a day; the widget layer maps them into [WidgetContent]. */
    fun observeWidgetRows(epochDay: Long = DateTimeUtils.todayEpochDay()): Flow<List<WidgetDoseRow>> =
        homeWidgetDao.observeWidgetRows(epochDay)

    /** Builds the ordered widget payload; the single entry point used by the Glance receiver. */
    suspend fun buildWidgetContent(epochDay: Long = DateTimeUtils.todayEpochDay()): WidgetContent {
        val prefs = settingsRepository.current()
        val rows = homeWidgetDao.getWidgetRowsOnce(epochDay).map { it.toSource(epochDay) }
        // The hide-completed rule lives on WidgetContent so this path and the widget's own content
        // builder cannot drift apart and make the tile flicker between two different lists.
        return WidgetPlanner.plan(rows, prefs.use24HourFormat)
            .visible(showCompleted = prefs.widgetShowCompleted)
    }

    /** Signals the launcher that today's data changed. No-op when no widget is placed. */
    suspend fun notifyWidgetRefresh() {
        widgetUpdater.get().requestUpdate()
    }

    // --------------------------------------------------------------- helpers

    /**
     * Derives the persisted status purely from the recorded amount and the clock.
     *
     * This is the single definition of "已吃 / 部分吃 / 未吃" that the acceptance criteria describe,
     * shared by the stepper, the notification actions, the widget and the undo path.
     */
    private fun statusFor(
        quantity: Double,
        plannedQuantity: Double,
        plannedTimeMillis: Long,
        snoozedUntilMillis: Long?,
        now: Long = System.currentTimeMillis(),
    ): DoseStatus = when {
        QuantityFormatter.isComplete(quantity, plannedQuantity) -> DoseStatus.TAKEN
        QuantityFormatter.isZero(quantity) -> DayPlanner.deriveStatus(
            plannedTimeMillis = plannedTimeMillis,
            takenQuantity = 0.0,
            plannedQuantity = plannedQuantity,
            snoozedUntilMillis = snoozedUntilMillis,
            nowMillis = now,
        )
        else -> DoseStatus.PARTIAL
    }

    /** Applies a signed stock change, ignoring medications that do not track stock. */
    private suspend fun applyStockDelta(medicationId: Long, delta: Double, now: Long) {
        if (medicationId == 0L || QuantityFormatter.isZero(delta)) return
        val tracked = medicationDao.getWithSchedulesById(medicationId)?.medication?.stockAmount ?: return
        medicationDao.adjustStock(medicationId, delta, now)
    }

    private suspend fun applyStatus(
        dose: DoseLog,
        status: DoseStatus,
        eventType: DoseEventType = DoseEventType.EDIT,
    ) {
        val now = System.currentTimeMillis()
        if (status == DoseStatus.TAKEN && !QuantityFormatter.isComplete(dose.takenQuantity, dose.plannedQuantity)) {
            // Marking as taken must also record the amount, otherwise the statistics would show a
            // completed dose with nothing taken.
            doseLogDao.updateQuantity(
                id = dose.id,
                quantity = dose.plannedQuantity,
                status = DoseStatus.TAKEN,
                takenTimeMillis = dose.takenTimeMillis ?: now,
                overDoseConfirmed = dose.overDoseConfirmed,
                now = now,
            )
            applyStockDelta(dose.medicationId, dose.plannedQuantity - dose.takenQuantity, now)
        } else {
            doseLogDao.updateStatus(dose.id, status, now)
        }
        doseLogDao.insertEvent(
            DoseEvent(
                doseLogId = dose.id,
                type = eventType,
                delta = 0.0,
                resultingQuantity = dose.takenQuantity,
                resultingStatus = status,
                timestamp = now,
            )
        )
        onDoseSettled(dose.id, status)
    }

    /** Common post-write bookkeeping: stop or restore the reminder, then refresh the widget. */
    private suspend fun onDoseSettled(doseId: Long, status: DoseStatus) {
        when (status) {
            DoseStatus.TAKEN, DoseStatus.SKIPPED -> cancelDoseReminder(doseId)
            else -> Unit
        }
        notifyWidgetRefresh()
    }

    private fun DoseLog.toPlannedDose() = PlannedDose(
        medicationId = medicationId,
        scheduleId = scheduleId,
        epochDay = epochDay,
        minuteOfDay = plannedMinuteOfDay,
        plannedTimeMillis = plannedTimeMillis,
        plannedQuantity = plannedQuantity,
        unitLabel = plannedUnit,
    )

    private fun WidgetDoseRow.toSource(epochDay: Long) = WidgetPlanner.WidgetRowSource(
        epochDay = epochDay,
        doseId = doseId,
        medicationId = medicationId,
        name = name,
        plannedMinuteOfDay = plannedMinuteOfDay,
        plannedTimeMillis = plannedTimeMillis,
        plannedQuantity = plannedQuantity,
        plannedUnit = plannedUnit,
        takenQuantity = takenQuantity,
        status = status,
        colorTag = colorTag,
        icon = icon,
        allowsFraction = allowsFraction,
    )
}

/** Snapshot the today screen renders. */
data class DayState(
    val epochDay: Long,
    val doses: List<DoseView>,
    val summary: TodaySummary,
    val nowMillis: Long,
) {
    val isEmpty: Boolean get() = doses.isEmpty()
}
