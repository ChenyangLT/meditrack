package com.meditrack.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * One planned dose instance on one day.
 *
 * The row is created by [com.meditrack.domain.plan.DayPlanner] the first time a day is opened, or
 * by [com.meditrack.domain.reminder.ReminderScheduler] when it arms an alarm for the day, whichever
 * happens first. From then on it is the durable record the user manipulates with the "+" / "-"
 * steppers, so closing the app never loses the "half a tablet taken" state.
 *
 * The planned amount and unit are *snapshotted* here. If the user later edits the medication from
 * "1 tablet" to "2 tablets", yesterday's history still reads 1 tablet - that is intentional.
 */
@Entity(
    tableName = "dose_logs",
    foreignKeys = [
        ForeignKey(
            entity = Medication::class,
            parentColumns = ["id"],
            childColumns = ["medicationId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Schedule::class,
            parentColumns = ["id"],
            childColumns = ["scheduleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["medicationId"]),
        Index(value = ["scheduleId"]),
        Index(value = ["epochDay"]),
        Index(value = ["status"]),
    ],
)
data class DoseLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,

    val medicationId: Long,
    val scheduleId: Long,

    /** The day this dose belongs to, as an epoch day. */
    val epochDay: Long,

    /** Planned wall-clock time as minutes from midnight (snapshot of [Schedule.minuteOfDay]). */
    val plannedMinuteOfDay: Int,

    /** The instant the reminder was scheduled for. */
    val plannedTimeMillis: Long,

    /** Planned amount snapshot, e.g. 1.0. */
    val plannedQuantity: Double,

    /** Unit label snapshot, e.g. "片". */
    val plannedUnit: String,

    /** What the user has actually recorded so far. Fractional for liquids. */
    val takenQuantity: Double = 0.0,

    /** When the dose first became "taken" (any amount > 0). Null while untouched. */
    val takenTimeMillis: Long? = null,

    /** When the last successful notification was posted, used for escalation/de-duplication. */
    val notifiedTimeMillis: Long? = null,

    /**
     * How many announcements this dose has produced, including the first one.
     *
     * Counted explicitly rather than inferred from elapsed time. An earlier implementation derived
     * the escalation count as `(now - notifiedTimeMillis) / repeatMinutes`, which meant the budget
     * was spent by the *clock* rather than by the *notifications*: with the shipped defaults a
     * single repeat consumed the whole allowance, and a phone that was asleep for an hour silently
     * burned every remaining reminder without ever showing one.
     *
     * A stored counter cannot drift, survives a reboot, and makes the cap mean what it says.
     */
    val escalationCount: Int = 0,

    /**
     * When the "还有一会儿" heads-up was posted, or null if it has not been.
     *
     * Recorded so that re-deriving the schedule - which happens on every heartbeat, boot and app
     * start - can never post the same advance notice twice.
     */
    val preRemindedAtMillis: Long? = null,

    /** How many snoozes have been used for this dose. */
    val snoozeCount: Int = 0,

    /** When the current snooze expires, or null. */
    val snoozedUntilMillis: Long? = null,

    /** Set once the lapsed reminder has fired so it never fires twice. */
    val missedNotified: Boolean = false,

    /**
     * When the reminder was withheld because the phone was idle (see
     * [com.meditrack.domain.reminder.UsageMonitor]) and has not been shown yet.
     *
     * Non-null means "the user still owes themselves this notification": the moment they pick the
     * phone up again, every deferred dose is delivered in one batch. Storing it on the row rather
     * than in memory is what makes that promise survive a process death or a reboot.
     *
     * Only ever written when the user explicitly turns idle deferral on. With the default settings
     * it stays null and reminders behave exactly as they always did.
     */
    val deferredAtMillis: Long? = null,

    /** True when the user confirmed the "是否确认多服？" dialog. */
    val overDoseConfirmed: Boolean = false,

    val status: DoseStatus = DoseStatus.UPCOMING,

    /** Free-form note attached to this particular dose, e.g. "忘带了，回家补服". */
    val note: String = "",

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {

    /** "1 / 2 片". */
    val progressLabel: String
        get() = com.meditrack.core.util.QuantityFormatter.formatProgress(
            takenQuantity, plannedQuantity, plannedUnit
        )
}

/** A dose together with its medication and schedule, for list rendering. */
data class DoseLogWithMedication(
    @Embedded val dose: DoseLog,
    @Relation(parentColumn = "medicationId", entityColumn = "id")
    val medication: Medication,
    @Relation(parentColumn = "scheduleId", entityColumn = "id")
    val schedule: Schedule?,
)

/**
 * Append-only audit trail. Every tap of "+" / "-" / "跳过" / "稍后" writes a row here so the user
 * can see exactly what happened, and so an accidental tap can be undone with full information.
 */
@Entity(
    tableName = "dose_events",
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
data class DoseEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val doseLogId: Long,
    val type: DoseEventType,
    /** Signed amount, e.g. +1.0 for an increment, -0.5 for a decrement. */
    val delta: Double = 0.0,
    /** Resulting taken quantity after the operation, captured for undo. */
    val resultingQuantity: Double = 0.0,
    /** Resulting status after the operation, captured for undo. */
    val resultingStatus: DoseStatus = DoseStatus.UPCOMING,
    val timestamp: Long = System.currentTimeMillis(),
    val note: String = "",
)

enum class DoseEventType(val label: String) {
    INCREMENT("加量"),
    DECREMENT("减量"),
    MARK_TAKEN("标记已服"),
    SKIP("跳过"),
    UNSKIP("取消跳过"),
    SNOOZE("稍后提醒"),
    MISSED("标记未服药"),
    RESET("重置"),
    UNDO("撤销"),
    EDIT("编辑"),
    RESTORE("恢复"),
}
