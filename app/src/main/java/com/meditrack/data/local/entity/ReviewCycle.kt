package com.meditrack.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * How a medication's «复查提醒» counts towards its threshold.
 *
 * The three modes exist because "吃够多少就该去复查" is genuinely different per prescription: a
 * course of antibiotics is counted in *doses taken*, a blood-pressure pill is counted in *days*, and
 * a syrup is counted in *millilitres*. Asking the user to squeeze their doctor's instruction into
 * one of them would have produced a reminder that fires at the wrong time, which is worse than no
 * reminder at all.
 */
enum class ReviewCountMode(val label: String, val unitShort: String) {
    /** Count every dose the user actually recorded as taken. A half-tablet counts as one. */
    DOSES("按已服用次数", "次"),

    /** Count calendar days since the round started, whatever the schedule was. */
    DAYS("按天数", "天"),

    /** Count the accumulated amount taken, in the medication's own unit (片 / ml / 喷 …). */
    QUANTITY("按累计剂量", ""),
    ;

    companion object {
        fun fromName(name: String?): ReviewCountMode =
            entries.firstOrNull { it.name == name } ?: DOSES
    }
}

/**
 * One «复查» round for one medication.
 *
 * ## Why this is a table and not three columns on `medications`
 *
 * The requirement is "start a new round after the review", and a round that is merely *reset* leaves
 * the user unable to answer the obvious question - when was the last one, and how long did it take?
 * Keeping each round as a row means the settings screen can show real history ("上一轮：60 次，9 月 3 日
 * 完成") instead of reconstructing it, and it means the discard-on-reread bug that plagues
 * counter-reset designs cannot happen: closing a round and opening the next is one transaction.
 *
 * ## Why [count] is stored rather than derived
 *
 * [ReviewCountMode.DAYS] could be derived from [startedAtMillis], and it is - but only for *display*.
 * For the threshold comparison the stored [count] is authoritative, because a day counter that
 * derives from "now" would fire the reminder while the phone was in a drawer and then be unable to
 * record that it had fired. Storing it makes the decision reproducible, which is what the audit trail
 * needs.
 */
@Entity(
    tableName = "medication_review_cycles",
    foreignKeys = [
        ForeignKey(
            entity = Medication::class,
            parentColumns = ["id"],
            childColumns = ["medicationId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["medicationId"])],
)
data class MedicationReviewCycle(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,

    val medicationId: Long,

    /** Which round this is, counting from 1. Shown to the user so "第 2 轮" is meaningful. */
    val round: Int = 1,

    /** When this round began - the first dose of the round, or the moment it was started by hand. */
    val startedAtMillis: Long = System.currentTimeMillis(),

    /** Epoch day the round began, for the day-based mode and for display. */
    val startedEpochDay: Long = 0L,

    /** The mode this round counts in, snapshotted so editing the setting cannot rewrite history. */
    @ColumnInfo(defaultValue = "DOSES")
    val countMode: ReviewCountMode = ReviewCountMode.DOSES,

    /** The threshold this round is measured against, also snapshotted. */
    val threshold: Double = 0.0,

    /** Progress towards [threshold]: taken doses, elapsed days, or accumulated amount. */
    val count: Double = 0.0,

    /** Epoch day of [count] for the day-based mode, so elapsed days survive a killed process. */
    val countedEpochDay: Long = 0L,

    /**
     * Whether the loud 复查提醒 has already been posted for this round.
     *
     * Stored rather than derived for the same reason the escalation counter is stored: the reminder
     * pipeline re-derives everything on every heartbeat, boot and app start, so a decision that is
     * recomputed rather than remembered would re-post the notice every fifteen minutes forever.
     */
    @ColumnInfo(defaultValue = "0")
    val reachedNotified: Boolean = false,

    /**
     * Epoch day on which the gentle advance notice was posted for this round; 0 when it has not been.
     *
     * A day rather than a boolean because the notice is a courtesy aimed at a specific moment ("还有 3
     * 次"), and the day it was sent is the useful thing to keep for the editor's history line. It also
     * cannot collide with a real value: epoch day 0 is 1970-01-01.
     */
    @ColumnInfo(defaultValue = "0")
    val advanceNotifiedEpochDay: Long = 0L,

    /** The day on which the user acknowledged the review, or null while the round is open. */
    val acknowledgedAtMillis: Long? = null,

    /** Epoch day of the acknowledgement, for "上次复查：9 月 3 日". */
    val acknowledgedEpochDay: Long? = null,

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {

    /** True while this round is still being counted towards its threshold. */
    val isOpen: Boolean get() = acknowledgedAtMillis == null

    /** True once [count] has reached [threshold]; a threshold of 0 or less never triggers. */
    val isReached: Boolean
        get() = threshold > 0.0 && count >= threshold - 1e-9
}

/**
 * A medication together with its currently open review round, if it has one.
 *
 * Returned by the DAO as a joined read so the medication list, the editor and the widget can all
 * render "还差 N 次" without a second query per medication.
 */
data class MedicationReviewState(
    val medicationId: Long,
    /** Null when the medication has never had a review round configured. */
    val cycle: MedicationReviewCycle?,
) {
    /** Remaining progress before the threshold, floored at zero. Null when nothing is configured. */
    val remaining: Double?
        get() = cycle?.takeIf { it.threshold > 0.0 }?.let { (it.threshold - it.count).coerceAtLeast(0.0) }

    /** True when this medication's review is due right now. */
    val isDue: Boolean get() = cycle?.isReached == true
}
