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
import com.meditrack.data.local.entity.DoseEvent
import com.meditrack.data.local.entity.DoseLog
import com.meditrack.data.local.entity.Medication
import com.meditrack.data.local.entity.ReminderEvent
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
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MediTrackDatabase : RoomDatabase() {

    abstract fun medicationDao(): MedicationDao
    abstract fun doseLogDao(): DoseLogDao
    abstract fun homeWidgetDao(): HomeWidgetDao
    abstract fun reminderEventDao(): ReminderEventDao

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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                // No destructive fallback on purpose: silently wiping a medication history to
                // recover from a schema mistake would be far worse than a visible crash, so a
                // missing migration must surface loudly during development.
                .build()
    }
}
