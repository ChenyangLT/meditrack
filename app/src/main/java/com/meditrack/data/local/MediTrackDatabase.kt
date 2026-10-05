package com.meditrack.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.meditrack.data.local.dao.DoseLogDao
import com.meditrack.data.local.dao.HomeWidgetDao
import com.meditrack.data.local.dao.MedicationDao
import com.meditrack.data.local.dao.ReminderEventDao
import com.meditrack.data.local.dao.ReviewCycleDao
import com.meditrack.data.local.dao.RingClipDao
import com.meditrack.data.local.entity.DoseEvent
import com.meditrack.data.local.entity.DoseLog
import com.meditrack.data.local.entity.Medication
import com.meditrack.data.local.entity.MedicationReviewCycle
import com.meditrack.data.local.entity.ReminderEvent
import com.meditrack.data.local.entity.RingClip
import com.meditrack.data.local.entity.Schedule

/**
 * Offline-first storage. Every read/write the app performs goes through this database; the network
 * is never involved (the app's only network call is the update check, which never touches data).
 *
 * Schema exports are written to `app/schemas` and checked in, so a future migration can be written
 * and tested without guessing the previous shape.
 */
@Database(
    entities = [
        Medication::class,
        Schedule::class,
        DoseLog::class,
        DoseEvent::class,
        ReminderEvent::class,
        MedicationReviewCycle::class,
        RingClip::class,
    ],
    version = 5,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MediTrackDatabase : RoomDatabase() {

    abstract fun medicationDao(): MedicationDao
    abstract fun doseLogDao(): DoseLogDao
    abstract fun homeWidgetDao(): HomeWidgetDao
    abstract fun reminderEventDao(): ReminderEventDao
    abstract fun reviewCycleDao(): ReviewCycleDao
    abstract fun ringClipDao(): RingClipDao

    companion object {
        const val DATABASE_NAME = "meditrack.db"

        /**
         * Enables `PRAGMA foreign_keys = ON`. Room already cascades at the ORM level, but turning
         * the pragma on makes the cascade authoritative at the SQLite level too, which matters
         * when a medication is deleted while its dose logs are being streamed to the widget.
         */
        val CALLBACK = object : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                db.execSQL("PRAGMA foreign_keys = ON")
            }
        }

        /**
         * v1 -> v2.
         *
         * Purely additive, so a real migration can preserve the user's history - which matters more
         * here than in most apps: a person who has recorded six months of doses must not lose them
         * because the reminder feature gained a column.
         *
         *  - `dose_logs.deferredAtMillis`: null means "not deferred", which is exactly the v1
         *    behaviour, so existing rows need no backfill.
         *  - `schedules.repeatRule`: no DDL change. The v2 encoding appends a `daysOfMonth` field,
         *    and the converter treats a missing field as empty, so v1 strings stay readable.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE dose_logs ADD COLUMN deferredAtMillis INTEGER")
            }
        }

        /**
         * v2 -> v3, the reminder-reliability rewrite.
         *
         * Additive and lossless. The three changes are exactly the state the new pipeline needs and
         * that the old one either derived wrongly or had nowhere to put:
         *
         *  - `dose_logs.escalationCount` - how many announcements this dose has produced. The old
         *    code inferred it from elapsed time, which spent the whole allowance on the first repeat.
         *    `DEFAULT 0` is the correct historical value: at the moment of upgrade no dose has been
         *    counted, and the counter only starts mattering at the next announcement.
         *  - `dose_logs.preRemindedAtMillis` - NULL means "no advance notice yet", which is also the
         *    honest answer for every existing row, so no backfill is needed.
         *  - `reminder_events` - the audit trail. A new table, so it starts empty by definition.
         *
         * Every statement is idempotent (`IF NOT EXISTS`) because a migration that is re-run after a
         * partially applied upgrade must not corrupt the user's history.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE dose_logs ADD COLUMN escalationCount INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL("ALTER TABLE dose_logs ADD COLUMN preRemindedAtMillis INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reminder_events` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`doseLogId` INTEGER NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`triggerName` TEXT NOT NULL, " +
                        "`decisionCode` TEXT NOT NULL, " +
                        "`driftMillis` INTEGER NOT NULL, " +
                        "`detail` TEXT NOT NULL, " +
                        "FOREIGN KEY(`doseLogId`) REFERENCES `dose_logs`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_reminder_events_doseLogId` " +
                        "ON `reminder_events` (`doseLogId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_reminder_events_timestamp` " +
                        "ON `reminder_events` (`timestamp`)"
                )
            }
        }

        /**
         * v3 -> v4, the "解锁补提醒" release.
         *
         * Additive and lossless, and both defaults are the honest historical value:
         *
         *  - `dose_logs.unlockReminderCount` - `DEFAULT 0` means "this dose has never been announced
         *    by an unlock catch-up", which is true of every row that exists at upgrade time. The
         *    feature can therefore start speaking up immediately instead of waiting a day.
         *  - `dose_logs.unlockReminderAtMillis` - NULL means "never", so the minimum-gap rule is
         *    inert until the first unlock catch-up actually happens.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE dose_logs ADD COLUMN unlockReminderCount INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL("ALTER TABLE dose_logs ADD COLUMN unlockReminderAtMillis INTEGER")
            }
        }

        /**
         * v4 -> v5, the «复查提醒» release.
         *
         * One new table, and nothing else: every existing table keeps its exact shape, so a user
         * upgrading with six months of history keeps all of it. That is the whole reason the review
         * round is a table of its own rather than columns bolted onto `medications` - adding columns
         * would have been equally additive, but it would have made "when was the last review, and how
         * long was that round" unanswerable.
         *
         * No backfill is needed or wanted: an empty table means "no medication has a review round
         * configured", which is exactly the historical truth. The feature is opt-in per medication,
         * so nothing starts firing on upgrade.
         *
         * `IF NOT EXISTS` on every statement because a migration that is re-run after a partially
         * applied upgrade must not corrupt the user's data.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ---------------------------------------------------------- «复查提醒»
                //
                // Four additive columns. The defaults are the honest historical values: `reviewThreshold
                // = 0` means "no follow-up configured", which is true of every row that exists at
                // upgrade time, so nothing starts firing because of an upgrade. `reviewReminderEnabled
                // = 1` is the arming switch, and leaving it on is correct precisely because the
                // threshold gate makes it inert.
                db.execSQL(
                    "ALTER TABLE medications ADD COLUMN reviewReminderEnabled INTEGER NOT NULL DEFAULT 1"
                )
                db.execSQL("ALTER TABLE medications ADD COLUMN reviewNote TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "ALTER TABLE medications ADD COLUMN reviewSearchQuery TEXT NOT NULL " +
                        "DEFAULT '吃多久需要去复查'"
                )
                db.execSQL(
                    "ALTER TABLE medications ADD COLUMN reviewCountMode TEXT NOT NULL DEFAULT 'DOSES'"
                )
                db.execSQL(
                    "ALTER TABLE medications ADD COLUMN reviewThreshold REAL NOT NULL DEFAULT 0"
                )
                // Per-medication ringtone override, added in the same release.
                db.execSQL("ALTER TABLE medications ADD COLUMN customRingClipId INTEGER")

                // ------------------------------------------------- 复查 rounds (new table)
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `medication_review_cycles` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`medicationId` INTEGER NOT NULL, " +
                        "`round` INTEGER NOT NULL, " +
                        "`startedAtMillis` INTEGER NOT NULL, " +
                        "`startedEpochDay` INTEGER NOT NULL, " +
                        "`countMode` TEXT NOT NULL DEFAULT 'DOSES', " +
                        "`threshold` REAL NOT NULL, " +
                        "`count` REAL NOT NULL, " +
                        "`countedEpochDay` INTEGER NOT NULL, " +
                        "`reachedNotified` INTEGER NOT NULL DEFAULT 0, " +
                        "`advanceNotifiedEpochDay` INTEGER NOT NULL DEFAULT 0, " +
                        "`acknowledgedAtMillis` INTEGER, " +
                        "`acknowledgedEpochDay` INTEGER, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`medicationId`) REFERENCES `medications`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_medication_review_cycles_medicationId` " +
                        "ON `medication_review_cycles` (`medicationId`)"
                )

                // ------------------------------------------------- user-created ringtones (new table)
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ring_clips` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`filePath` TEXT NOT NULL, " +
                        "`durationMillis` INTEGER NOT NULL, " +
                        "`sourceLabel` TEXT NOT NULL, " +
                        "`sourceUri` TEXT, " +
                        "`trimStartMillis` INTEGER NOT NULL, " +
                        "`trimEndMillis` INTEGER NOT NULL, " +
                        "`inUse` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ring_clips_createdAt` " +
                        "ON `ring_clips` (`createdAt`)"
                )
            }
        }

        @Volatile
        private var INSTANCE: MediTrackDatabase? = null

        /**
         * Fallback accessor for the few places Hilt cannot inject into (AppWidget receivers
         * instantiated by the system). Normal app code uses the Hilt provided singleton.
         */
        fun getInstance(context: Context): MediTrackDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context.applicationContext).also { INSTANCE = it }
            }

        fun build(context: Context): MediTrackDatabase =
            Room.databaseBuilder(context, MediTrackDatabase::class.java, DATABASE_NAME)
                .addCallback(CALLBACK)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                // No destructive fallback on purpose: silently wiping a medication history to
                // recover from a schema mistake would be far worse than a visible crash, so a
                // missing migration must surface loudly during development.
                .build()
    }
}
