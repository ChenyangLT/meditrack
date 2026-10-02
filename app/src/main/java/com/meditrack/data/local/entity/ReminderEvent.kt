package com.meditrack.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.meditrack.domain.reminder.ReminderSuppression
import com.meditrack.domain.reminder.ReminderTrigger

/**
 * Append-only record of every decision the reminder pipeline made.
 *
 * ## Why this is a table and not a log line
 *
 * The single hardest question about a reminder app is *"why did nothing happen at 08:00?"*. Logcat
 * is gone by the time the user notices, and on a release build nobody is reading it anyway. Chrono
 * solves this with its alarm-event log, and the same reasoning applies here with more force: a
 * missed medication dose is a health event, not a UX complaint.
 *
 * With this table the app can answer, from inside the app, on the user's own device:
 *
 *  - "the alarm never reached us" (no entry at all for the window);
 *  - "we ran and deliberately stayed quiet" (a `SKIP_*` entry, with the reason);
 *  - "we ran too early / too late and corrected it" (`TOO_EARLY`, `CATCH_UP`);
 *  - "the OS delivered the alarm N minutes late" — [driftMillis] records the gap between the
 *    instant a dose was *due* and the instant the pipeline actually looked at it.
 *
 * Entries are pruned to a bounded window (see [com.meditrack.data.local.dao.ReminderEventDao.prune]),
 * so the table can never grow without limit.
 *
 * Both enum-valued columns are stored by `name` and read through a lenient accessor, matching the
 * convention used by [Converters]: a value written by a newer build degrades to `null` rather than
 * crashing an older one.
 */
@Entity(
    tableName = "reminder_events",
    foreignKeys = [
        ForeignKey(
            entity = DoseLog::class,
            parentColumns = ["id"],
            childColumns = ["doseLogId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("doseLogId"), Index("timestamp")],
)
data class ReminderEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,

    /** The dose this decision was about. Cascades away with the dose. */
    val doseLogId: Long,

    val timestamp: Long = System.currentTimeMillis(),

    /** [ReminderTrigger] name: what woke the pipeline up. */
    val triggerName: String,

    /** [com.meditrack.domain.reminder.ReminderDecision.code]: what it decided. */
    val decisionCode: String,

    /**
     * How late (positive) or early (negative) the pipeline was relative to the dose's due instant.
     *
     * This is the number that turns "it was late again" into something actionable: a consistent
     * `+600000` on every entry means the OEM is batch-delaying alarms, which is a different problem
     * from a single dropped one.
     */
    val driftMillis: Long = 0L,

    /** Free-form, human-readable explanation shown in the self-check screen. */
    val detail: String = "",
) {

    val trigger: ReminderTrigger?
        get() = runCatching { ReminderTrigger.valueOf(triggerName) }.getOrNull()

    /** True when this entry represents a notification the user actually saw. */
    val delivered: Boolean
        get() = decisionCode == "REMIND" ||
            decisionCode == "REMIND_REPEAT" ||
            decisionCode == "PRE_REMIND" ||
            decisionCode == "CATCH_UP" ||
            decisionCode == "MISSED"

    /** True when the pipeline ran and deliberately chose to stay silent. */
    val suppressed: Boolean get() = decisionCode.startsWith("SKIP_")

    /** The suppression reason, when this entry is a deliberate silence. */
    val suppression: ReminderSuppression?
        get() = decisionCode.removePrefix("SKIP_")
            .takeIf { decisionCode.startsWith("SKIP_") }
            ?.let { name -> runCatching { ReminderSuppression.valueOf(name) }.getOrNull() }
}
