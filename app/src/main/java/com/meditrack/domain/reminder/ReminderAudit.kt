package com.meditrack.domain.reminder

import com.meditrack.data.local.dao.ReminderEventDao
import com.meditrack.data.local.entity.ReminderEvent
import javax.inject.Inject
import javax.inject.Singleton

/** A short, human-readable roll-up of what the pipeline has been doing. */
data class ReminderAuditSummary(
    val delivered: Int,
    val suppressed: Int,
    val latestAt: Long?,
) {
    val hasActivity: Boolean get() = delivered > 0 || suppressed > 0
}

/**
 * Writes and reads the reminder audit trail.
 *
 * A thin wrapper over [ReminderEventDao] whose only job is to make recording a decision a one-liner
 * at every call site - because the value of an audit trail is entirely determined by whether it is
 * actually written on *every* branch, including the boring ones. A pipeline that only logs the
 * interesting outcomes cannot answer "why was there no notification?", since the answer is usually
 * one of the uninteresting ones.
 */
@Singleton
class ReminderAudit @Inject constructor(
    private val dao: ReminderEventDao,
) {

    suspend fun record(
        doseId: Long,
        trigger: ReminderTrigger,
        decisionCode: String,
        driftMillis: Long = 0L,
        detail: String = "",
    ) {
        // Never let bookkeeping break delivery: an audit write that fails must not cost the user
        // their reminder.
        runCatching {
            dao.insert(
                ReminderEvent(
                    doseLogId = doseId,
                    timestamp = System.currentTimeMillis(),
                    triggerName = trigger.name,
                    decisionCode = decisionCode,
                    driftMillis = driftMillis,
                    detail = detail,
                )
            )
        }
    }

    suspend fun recent(limit: Int = 50): List<ReminderEvent> = runCatching { dao.recent(limit) }.getOrDefault(emptyList())

    suspend fun forDose(doseId: Long): List<ReminderEvent> =
        runCatching { dao.forDose(doseId) }.getOrDefault(emptyList())

    suspend fun latest(): ReminderEvent? = runCatching { dao.latest() }.getOrNull()

    /** Counts everything recorded since [sinceMillis] and when the newest entry was written. */
    suspend fun summary(sinceMillis: Long): ReminderAuditSummary = runCatching {
        ReminderAuditSummary(
            delivered = dao.countDeliveredSince(sinceMillis),
            suppressed = dao.countSuppressedSince(sinceMillis),
            latestAt = dao.latest()?.timestamp,
        )
    }.getOrDefault(ReminderAuditSummary(0, 0, null))

    /** Bounds the table. Cheap enough to run on every reconcile pass. */
    suspend fun prune(keep: Int = DEFAULT_RETENTION) {
        runCatching { dao.prune(keep) }
    }

    companion object {
        /**
         * Rows retained.
         *
         * Enough to cover several days of a realistic schedule at a glance, small enough that the
         * table never becomes a meaningful share of the database.
         */
        const val DEFAULT_RETENTION = 500
    }
}
