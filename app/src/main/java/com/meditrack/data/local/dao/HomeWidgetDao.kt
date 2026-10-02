package com.meditrack.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Row shape consumed by the Glance widget.
 *
 * A dedicated projection keeps the widget decoupled from the Room entities: Glance renders on a
 * background thread with a tight time budget, so we never want the widget to pull a full
 * [com.meditrack.data.local.entity.Medication] graph.
 */
data class WidgetDoseRow(
    val doseId: Long,
    val medicationId: Long,
    val name: String,
    /** Snapshot of the planned wall-clock time, minutes from midnight. */
    val plannedMinuteOfDay: Int,
    val plannedTimeMillis: Long,
    val plannedQuantity: Double,
    val plannedUnit: String,
    val takenQuantity: Double,
    val status: String,
    val colorTag: String,
    val icon: String,
    val doseAmount: Double,
    val allowsFraction: Boolean,
)

@Dao
interface HomeWidgetDao {

    /**
     * Every dose of [epochDay], open ones first, then ordered by time.
     *
     * The final priority ranking (overdue > due soon > later today > taken) is applied in
     * [com.meditrack.domain.plan.WidgetPlanner] where the "now" reference and the 30 minute
     * window live, because SQL cannot express "within 30 minutes of the current time" without
     * pinning a timestamp into the query and invalidating the Flow.
     */
    @Query(
        """
        SELECT d.id              AS doseId,
               d.medicationId    AS medicationId,
               m.name            AS name,
               d.plannedMinuteOfDay AS plannedMinuteOfDay,
               d.plannedTimeMillis  AS plannedTimeMillis,
               d.plannedQuantity    AS plannedQuantity,
               d.plannedUnit        AS plannedUnit,
               d.takenQuantity      AS takenQuantity,
               d.status             AS status,
               m.colorTag           AS colorTag,
               m.icon               AS icon,
               m.doseAmount         AS doseAmount,
               CASE m.unit WHEN 'MILLILITER' THEN 1 ELSE 0 END AS allowsFraction
          FROM dose_logs d
          JOIN medications m ON m.id = d.medicationId
         WHERE d.epochDay = :epochDay
         ORDER BY d.plannedMinuteOfDay ASC
        """
    )
    fun observeWidgetRows(epochDay: Long): Flow<List<WidgetDoseRow>>

    /** One-shot variant used when a reminder fires and the widget must refresh immediately. */
    @Query(
        """
        SELECT d.id              AS doseId,
               d.medicationId    AS medicationId,
               m.name            AS name,
               d.plannedMinuteOfDay AS plannedMinuteOfDay,
               d.plannedTimeMillis  AS plannedTimeMillis,
               d.plannedQuantity    AS plannedQuantity,
               d.plannedUnit        AS plannedUnit,
               d.takenQuantity      AS takenQuantity,
               d.status             AS status,
               m.colorTag           AS colorTag,
               m.icon               AS icon,
               m.doseAmount         AS doseAmount,
               CASE m.unit WHEN 'MILLILITER' THEN 1 ELSE 0 END AS allowsFraction
          FROM dose_logs d
          JOIN medications m ON m.id = d.medicationId
         WHERE d.epochDay = :epochDay
         ORDER BY d.plannedMinuteOfDay ASC
        """
    )
    suspend fun getWidgetRowsOnce(epochDay: Long): List<WidgetDoseRow>
}
