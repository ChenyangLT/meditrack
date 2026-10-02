package com.meditrack.widget

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.meditrack.core.util.Haptics
import com.meditrack.data.local.MediTrackDatabase
import com.meditrack.data.local.entity.DoseEvent
import com.meditrack.data.local.entity.DoseEventType
import com.meditrack.data.local.entity.DoseLog
import com.meditrack.data.local.entity.DoseStatus
import com.meditrack.core.util.QuantityFormatter
import com.meditrack.domain.plan.DayPlanner

/**
 * Server-side handlers for the widget's quick actions.
 *
 * Glance actions cannot carry closures, so each callback receives only primitives through
 * [ActionParameters]. The callbacks therefore talk to the database directly rather than going
 * through the Hilt-injected repository: a widget callback may run when the app process was not
 * alive, and spinning up the whole object graph for a single row update would be wasteful.
 *
 * The write path still respects the same invariants as the in-app stepper - status derived from the
 * recorded amount, one audit event per change - because it reuses [DayPlanner.deriveStatus] and the
 * same event log.
 */
object WidgetActionCallbacks {

    val KEY_DOSE_ID = ActionParameters.Key<Long>("widget_dose_id")
    val KEY_DELTA = ActionParameters.Key<Double>("widget_delta")

    /**
     * Applies "+" / "-" from the widget.
     *
     * Deliberately conservative: a dose can never be pushed below zero here, and the widget only
     * ever moves by the medication's step (0.5 for liquids, 1 otherwise). The "多服" confirmation
     * is *not* raised from the widget - an over-maximum tap there simply stops at the planned
     * amount, and the app is opened for anything beyond it. Silently recording an over-dose from a
     * home-screen tap would be the wrong default for a medical record.
     */
    class AdjustQuantityCallback : ActionCallback {
        override suspend fun onAction(
            context: Context,
            glanceId: GlanceId,
            parameters: ActionParameters,
        ) {
            val doseId = parameters[KEY_DOSE_ID] ?: return
            val delta = parameters[KEY_DELTA] ?: return
            try {
                val db = MediTrackDatabase.getInstance(context)
                val dose = db.doseLogDao().getById(doseId) ?: return
                val medication = db.medicationDao().getWithSchedulesById(dose.medicationId)?.medication

                val max = medication?.maxDoseAmount ?: 0.0
                val requested = QuantityFormatter.sanitize((dose.takenQuantity + delta).coerceAtLeast(0.0))
                // Clamp an over-dose attempt to the planned amount instead of asking for a
                // confirmation we cannot render from a widget.
                val capped = if (delta > 0 && max > 0.0 && requested > max + QuantityFormatter.EPSILON) {
                    max
                } else {
                    requested
                }
                if (QuantityFormatter.isZero(capped - dose.takenQuantity)) {
                    Haptics.tick(context)
                    WidgetRefresh.refreshNow(context)
                    return
                }

                val now = System.currentTimeMillis()
                val status = when {
                    QuantityFormatter.isComplete(capped, dose.plannedQuantity) -> DoseStatus.TAKEN
                    QuantityFormatter.isZero(capped) -> DayPlanner.deriveStatus(
                        plannedTimeMillis = dose.plannedTimeMillis,
                        takenQuantity = 0.0,
                        plannedQuantity = dose.plannedQuantity,
                        snoozedUntilMillis = dose.snoozedUntilMillis,
                        nowMillis = now,
                    )
                    else -> DoseStatus.PARTIAL
                }

                db.doseLogDao().updateQuantity(
                    id = dose.id,
                    quantity = capped,
                    status = status,
                    takenTimeMillis = if (QuantityFormatter.isZero(capped)) null
                    else (dose.takenTimeMillis ?: now),
                    overDoseConfirmed = dose.overDoseConfirmed,
                    now = now,
                )

                if (medication != null && medication.stockAmount > 0.0) {
                    db.medicationDao().adjustStock(
                        medication.id,
                        -(capped - dose.takenQuantity),
                        now,
                    )
                }

                db.doseLogDao().insertEvent(
                    DoseEvent(
                        doseLogId = dose.id,
                        type = if (capped >= dose.takenQuantity) DoseEventType.INCREMENT
                        else DoseEventType.DECREMENT,
                        delta = capped - dose.takenQuantity,
                        resultingQuantity = capped,
                        resultingStatus = status,
                        timestamp = now,
                        note = "桌面小组件",
                    )
                )

                Haptics.confirm(context)
                WidgetRefresh.refreshNow(context)
            } catch (t: Throwable) {
                Log.e(TAG, "widget adjust failed for dose $doseId", t)
            }
        }
    }

    private const val TAG = "WidgetAction"
}

/**
 * Read model helpers shared between the widget and its worker.
 *
 * Kept out of the composable so the row-building logic can be unit tested without Glance.
 */
object WidgetRowMapper {

    /** Statuses considered "no longer needs attention". */
    fun isResolved(status: DoseStatus): Boolean = status == DoseStatus.TAKEN || status == DoseStatus.SKIPPED

    /** Convenience for tests and previews. */
    fun emptyLog(doseId: Long = 0L): DoseLog = DoseLog(
        id = doseId,
        medicationId = 0L,
        scheduleId = 0L,
        epochDay = 0L,
        plannedMinuteOfDay = 0,
        plannedTimeMillis = 0L,
        plannedQuantity = 1.0,
        plannedUnit = "片",
    )
}
