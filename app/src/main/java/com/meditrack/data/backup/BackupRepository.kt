package com.meditrack.data.backup

import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import com.meditrack.core.util.DateTimeUtils
import com.meditrack.core.util.QuantityFormatter
import com.meditrack.data.local.MediTrackDatabase
import com.meditrack.data.local.entity.DosageForm
import com.meditrack.data.local.entity.DosageUnit
import com.meditrack.data.local.entity.DoseEvent
import com.meditrack.data.local.entity.DoseEventType
import com.meditrack.data.local.entity.DoseLog
import com.meditrack.data.local.entity.DoseStatus
import com.meditrack.data.local.entity.FoodTiming
import com.meditrack.data.local.entity.Medication
import com.meditrack.data.local.entity.MedicationColorTag
import com.meditrack.data.local.entity.MedicationIcon
import com.meditrack.data.local.entity.MedicationReviewCycle
import com.meditrack.data.local.entity.RepeatRuleType
import com.meditrack.data.local.entity.RepeatingRule
import com.meditrack.data.local.entity.ReviewCountMode
import com.meditrack.data.local.entity.RingClip
import com.meditrack.data.local.entity.Schedule
import com.meditrack.data.prefs.AccentColor
import com.meditrack.data.prefs.FontScale
import com.meditrack.data.prefs.SettingsRepository
import com.meditrack.data.prefs.ThemeMode
import com.meditrack.data.prefs.UserPreferences
import com.meditrack.data.prefs.WeekStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A complete, self-describing snapshot of the user's data.
 *
 * `schemaVersion` is present from the very first release so a future importer can migrate an old
 * file instead of failing on it. Gson ignores unknown fields, which makes the format
 * forward-tolerant: an older build can read a newer file and keep the fields it understands.
 */
data class BackupFile(
    @SerializedName("schemaVersion") val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    @SerializedName("appVersion") val appVersion: String = "",
    @SerializedName("exportedAtMillis") val exportedAtMillis: Long = System.currentTimeMillis(),
    @SerializedName("exportedAtLabel") val exportedAtLabel: String = "",
    @SerializedName("medications") val medications: List<MedicationDto> = emptyList(),
    @SerializedName("schedules") val schedules: List<ScheduleDto> = emptyList(),
    @SerializedName("doseLogs") val doseLogs: List<DoseLogDto> = emptyList(),
    @SerializedName("doseEvents") val doseEvents: List<DoseEventDto> = emptyList(),
    @SerializedName("reviewCycles") val reviewCycles: List<ReviewCycleDto> = emptyList(),
    @SerializedName("ringClips") val ringClips: List<RingClipDto> = emptyList(),
    @SerializedName("settings") val settings: SettingsDto? = null,
) {
    companion object {
        /**
         * Bumped whenever the shape changes in a way an older importer must know about.
         *
         * v2 added `schedules.daysOfMonth`, `doseLogs.deferredAtMillis` and the reminder
         * preferences (heads-up banner, idle deferral). All of them are optional on import, so a v2
         * file still loads in a v1 build - but the version tells a v2 build that the extra fields
         * are worth reading, and lets a future v3 importer migrate deliberately.
         *
         * v3 added the «复查提醒» fields on `medications`, the `reviewCycles` and `ringClips` collections,
         * and the ring / review preferences. Every one of them is nullable or defaulted, so a v3 file still
         * imports cleanly into a v2 build (which ignores what it does not know) and a v2 file still imports
         * into a v3 build (where it reads as "nothing configured").
         */
        const val CURRENT_SCHEMA_VERSION = 3
    }
}

/** Enums are exported by name (never ordinal) so the file survives reordering a constant. */
data class MedicationDto(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("icon") val icon: String,
    @SerializedName("colorTag") val colorTag: String,
    @SerializedName("dosageForm") val dosageForm: String,
    @SerializedName("unit") val unit: String,
    @SerializedName("strength") val strength: String,
    @SerializedName("doseAmount") val doseAmount: Double,
    @SerializedName("maxDoseAmount") val maxDoseAmount: Double,
    @SerializedName("foodTiming") val foodTiming: String,
    @SerializedName("note") val note: String,
    @SerializedName("stockAmount") val stockAmount: Double,
    @SerializedName("stockAlertThreshold") val stockAlertThreshold: Double,
    @SerializedName("reminderEnabled") val reminderEnabled: Boolean,
    @SerializedName("isActive") val isActive: Boolean,
    @SerializedName("createdAt") val createdAt: Long,
    @SerializedName("updatedAt") val updatedAt: Long,
    /**
     * Per-medication ringtone override, as a `ring_clips` row id.
     *
     * Nullable and defaulted for the same reason as every optional field here: `MedicationDto` has no
     * all-default constructor, so Gson allocates it with `Unsafe` and an absent field becomes the JVM zero
     * value rather than the Kotlin default. A nullable field is therefore the only shape that can tell
     * "older backup" apart from a real value.
     */
    @SerializedName("customRingClipId") val customRingClipId: Long? = null,
    /**
     * The «复查提醒» settings.
     *
     * All nullable for the same reason. `reviewThreshold` being null is what makes every medication in a
     * pre-2.0 backup import as "no review configured" rather than as "a review at zero doses" - which the
     * mapper below turns into the correct historical answer.
     */
    @SerializedName("reviewReminderEnabled") val reviewReminderEnabled: Boolean? = null,
    @SerializedName("reviewNote") val reviewNote: String? = null,
    @SerializedName("reviewSearchQuery") val reviewSearchQuery: String? = null,
    @SerializedName("reviewCountMode") val reviewCountMode: String? = null,
    @SerializedName("reviewThreshold") val reviewThreshold: Double? = null,
)

/**
 * A «复查» round, as it travels in a backup.
 *
 * Rounds are worth carrying even though the progress is derived: the *history* is the part a user would
 * miss ("when did I last go for a review?"), and the start day is what the day-based countdown is computed
 * from - so dropping it would silently restart every open round at zero, which would postpone a real
 * review reminder by however long the round had already run.
 */
data class ReviewCycleDto(
    @SerializedName("id") val id: Long,
    @SerializedName("medicationId") val medicationId: Long,
    @SerializedName("round") val round: Int,
    @SerializedName("startedAtMillis") val startedAtMillis: Long,
    @SerializedName("startedEpochDay") val startedEpochDay: Long,
    @SerializedName("countMode") val countMode: String,
    @SerializedName("threshold") val threshold: Double,
    @SerializedName("count") val count: Double,
    @SerializedName("countedEpochDay") val countedEpochDay: Long,
    @SerializedName("reachedNotified") val reachedNotified: Boolean = false,
    @SerializedName("advanceNotifiedEpochDay") val advanceNotifiedEpochDay: Long = 0L,
    @SerializedName("acknowledgedAtMillis") val acknowledgedAtMillis: Long? = null,
    @SerializedName("acknowledgedEpochDay") val acknowledgedEpochDay: Long? = null,
    @SerializedName("createdAt") val createdAt: Long,
    @SerializedName("updatedAt") val updatedAt: Long,
)

/**
 * A user-created ringtone, as it travels in a backup.
 *
 * **The audio itself is deliberately not carried.** A clip folder can be tens of megabytes of arbitrary
 * user media, which would turn a 200 KB JSON backup into a file nobody can email to themselves - and the
 * file is regenerable from the source the user still has on their phone. What is carried is the metadata,
 * including `filePath`, so the picker can list the clip after an import and tell the user it needs
 * re-rendering rather than pretending the sound works.
 */
data class RingClipDto(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("filePath") val filePath: String,
    @SerializedName("durationMillis") val durationMillis: Long,
    @SerializedName("sourceLabel") val sourceLabel: String,
    @SerializedName("sourceUri") val sourceUri: String? = null,
    @SerializedName("trimStartMillis") val trimStartMillis: Long = 0L,
    @SerializedName("trimEndMillis") val trimEndMillis: Long = 0L,
    @SerializedName("inUse") val inUse: Boolean = false,
    @SerializedName("createdAt") val createdAt: Long,
)

data class ScheduleDto(
    @SerializedName("id") val id: Long,
    /** Original id, so dose logs can be re-linked during import. */
    @SerializedName("medicationId") val medicationId: Long,
    @SerializedName("minuteOfDay") val minuteOfDay: Int,
    @SerializedName("repeatType") val repeatType: String,
    @SerializedName("intervalDays") val intervalDays: Int,
    @SerializedName("daysOfWeek") val daysOfWeek: List<Int>,
    @SerializedName("cycleOnDays") val cycleOnDays: Int,
    @SerializedName("cycleOffDays") val cycleOffDays: Int,
    @SerializedName("anchorEpochDay") val anchorEpochDay: Long,
    @SerializedName("startEpochDay") val startEpochDay: Long,
    @SerializedName("endEpochDay") val endEpochDay: Long?,
    @SerializedName("reminderEnabled") val reminderEnabled: Boolean,
    /**
     * Day numbers for the "每月几号" rule.
     *
     * A default keeps backups written before this feature importable: Gson leaves the field null
     * when the key is absent, and [orEmpty] turns that into "no monthly days".
     */
    @SerializedName("daysOfMonth") val daysOfMonth: List<Int>? = null,
)

data class DoseLogDto(
    @SerializedName("id") val id: Long,
    @SerializedName("medicationId") val medicationId: Long,
    @SerializedName("scheduleId") val scheduleId: Long,
    @SerializedName("epochDay") val epochDay: Long,
    /** Redundant but human readable: makes the file useful without a decoder. */
    @SerializedName("date") val date: String,
    @SerializedName("plannedMinuteOfDay") val plannedMinuteOfDay: Int,
    @SerializedName("plannedTimeMillis") val plannedTimeMillis: Long,
    @SerializedName("plannedQuantity") val plannedQuantity: Double,
    @SerializedName("plannedUnit") val plannedUnit: String,
    @SerializedName("takenQuantity") val takenQuantity: Double,
    @SerializedName("takenTimeMillis") val takenTimeMillis: Long?,
    @SerializedName("status") val status: String,
    @SerializedName("snoozeCount") val snoozeCount: Int,
    @SerializedName("note") val note: String,
    /** Pending idle-deferral flag; null in older backups and in the normal (feature-off) case. */
    @SerializedName("deferredAtMillis") val deferredAtMillis: Long? = null,
)

data class DoseEventDto(
    @SerializedName("id") val id: Long,
    @SerializedName("doseLogId") val doseLogId: Long,
    @SerializedName("type") val type: String,
    @SerializedName("delta") val delta: Double,
    @SerializedName("resultingQuantity") val resultingQuantity: Double,
    @SerializedName("resultingStatus") val resultingStatus: String,
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("note") val note: String,
)

data class SettingsDto(
    @SerializedName("themeMode") val themeMode: String,
    @SerializedName("useDynamicColor") val useDynamicColor: Boolean,
    @SerializedName("accentColor") val accentColor: String,
    @SerializedName("fontScale") val fontScale: String,
    @SerializedName("highContrast") val highContrast: Boolean,
    @SerializedName("simplifiedMode") val simplifiedMode: Boolean,
    @SerializedName("use24HourFormat") val use24HourFormat: Boolean,
    @SerializedName("weekStart") val weekStart: String,
    @SerializedName("remindersEnabled") val remindersEnabled: Boolean,
    @SerializedName("snoozeMinutes") val snoozeMinutes: Int,
    @SerializedName("missedGraceMinutes") val missedGraceMinutes: Int,
    @SerializedName("widgetItemLimit") val widgetItemLimit: Int,
    /**
     * Retired: the widget's "+" / "-" buttons were removed, so nothing reads this any more.
     *
     * Kept as a nullable field rather than deleted so that a backup written by an older build - which
     * still contains the property - continues to parse without a missing-field surprise.
     */
    @SerializedName("widgetQuickActions") val widgetQuickActions: Boolean? = null,
    @SerializedName("widgetShowCompleted") val widgetShowCompleted: Boolean,
    /**
     * Widget re-check cadence in seconds. Absent from backups written before the widget rework, in
     * which case the stored value is kept rather than being reset to a default.
     */
    @SerializedName("widgetRefreshSeconds") val widgetRefreshSeconds: Int? = null,
    // Added in the reminder-configuration release. All nullable-with-default so a backup written by
    // an earlier build still imports cleanly.
    @SerializedName("headsUpEnabled") val headsUpEnabled: Boolean? = null,
    @SerializedName("soundEnabled") val soundEnabled: Boolean? = null,
    @SerializedName("vibrationEnabled") val vibrationEnabled: Boolean? = null,
    /**
     * Which bundled tone to use; absent in backups written before the tones shipped, in which case the
     * device keeps its own choice rather than being reset to the default.
     */
    @SerializedName("reminderTone") val reminderTone: String? = null,
    @SerializedName("idleDeferralEnabled") val idleDeferralEnabled: Boolean? = null,
    @SerializedName("idleThresholdMinutes") val idleThresholdMinutes: Int? = null,
    @SerializedName("deferWhileScreenOff") val deferWhileScreenOff: Boolean? = null,
    // Added in the unlock-catch-up release. Nullable like every other field, so a backup written by an
    // older build imports cleanly and simply leaves these at their shipped defaults.
    @SerializedName("unlockReminderEnabled") val unlockReminderEnabled: Boolean? = null,
    @SerializedName("unlockReminderMaxPerDose") val unlockReminderMaxPerDose: Int? = null,
    @SerializedName("unlockReminderMinGapMinutes") val unlockReminderMinGapMinutes: Int? = null,
    @SerializedName("fullScreenReminderEnabled") val fullScreenReminderEnabled: Boolean? = null,
    // Added in the 2.0 «持续响铃» / «复查提醒» release. Nullable like every other optional field, so a
    // backup written by an older build imports cleanly and leaves these at their shipped defaults - which
    // for `ringMode` is the loud one the feature was requested with.
    @SerializedName("ringMode") val ringMode: String? = null,
    @SerializedName("ringMaxMinutes") val ringMaxMinutes: Int? = null,
    @SerializedName("ringTimes") val ringTimes: Int? = null,
    @SerializedName("ringIntervalSeconds") val ringIntervalSeconds: Int? = null,
    /**
     * Which custom ringtone is selected.
     *
     * Named "clip" while [ringClips] carry the metadata, so an import can tell whether the selected id
     * actually survived: a backup with a selected id but no matching clip entry would have left the user
     * with a silent reminder, which is why the import clears it in that case.
     */
    @SerializedName("ringClipId") val ringClipId: Long? = null,
    @SerializedName("reviewReminderEnabled") val reviewReminderEnabled: Boolean? = null,
    @SerializedName("reviewAdvanceNotice") val reviewAdvanceNotice: Int? = null,
    @SerializedName("reviewSearchEngine") val reviewSearchEngine: String? = null,
    @SerializedName("reviewSearchSuffix") val reviewSearchSuffix: String? = null,
)

/** Outcome of an import, surfaced to the user as a toast. */
data class ImportResult(
    val medications: Int,
    val schedules: Int,
    val doseLogs: Int,
    /** «复查» rounds restored; 0 for a backup written before the feature existed. */
    val reviewCycles: Int = 0,
    /** Ringtone entries restored. Their audio is not carried in the backup - see [RingClipDto]. */
    val ringClips: Int = 0,
)

/**
 * JSON backup, CSV export and restore.
 *
 * Restore is **destructive by design and confirmed by the caller**: it clears the tables before
 * inserting, because merging two histories would silently double-count doses and corrupt the
 * adherence statistics. The UI warns the user and suggests exporting first.
 */
@Singleton
class BackupRepository @Inject constructor(
    private val database: MediTrackDatabase,
    private val settingsRepository: SettingsRepository,
    /**
     * Needed for one thing only: re-pointing an imported ringtone entry at *this* device's own clip
     * directory, since a backup's paths are absolute and belong to the machine that wrote it.
     */
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) {

    /**
     * The one Gson configuration in the app, shared with [BackupFormat] so that what we write and
     * what we accept can never drift apart.
     */
    private val gson: Gson get() = BackupFormat.gson

    // ----------------------------------------------------------------- export

    /** Serialises everything into a [BackupFile]. */
    suspend fun buildBackup(appVersion: String): BackupFile = withContext(Dispatchers.IO) {
        val medications = database.medicationDao().getAllOnce()
        val schedules = database.medicationDao().getAllSchedulesOnce()
        val doses = database.doseLogDao().getBetween(Long.MIN_VALUE, Long.MAX_VALUE)
        val events = doses.flatMap { database.doseLogDao().getEventsFor(it.id) }
        // Every round, not just the open ones: the closed rounds are the answer to "when did I last go for
        // a review", which is the one piece of review state that cannot be reconstructed from anything else.
        val cycles = medications.flatMap { medication ->
            val open = database.reviewCycleDao().openCycleFor(medication.id)
            val closed = database.reviewCycleDao().closedCyclesFor(medication.id)
            (listOfNotNull(open) + closed).map { it.toDto() }
        }
        val clips = database.ringClipDao().all().map { it.toDto() }
        val prefs = settingsRepository.current()
        val now = System.currentTimeMillis()

        BackupFile(
            schemaVersion = BackupFile.CURRENT_SCHEMA_VERSION,
            appVersion = appVersion,
            exportedAtMillis = now,
            exportedAtLabel = DateTimeUtils.formatDateTime(now),
            medications = medications.map { it.toDto() },
            schedules = schedules.map { it.toDto() },
            doseLogs = doses.map { it.toDto() },
            doseEvents = events.map { it.toDto() },
            reviewCycles = cycles,
            ringClips = clips,
            settings = prefs.toDto(),
        )
    }

    /**
     * Writes the JSON backup to [target].
     *
     * Written to a temporary sibling first and then renamed, so a crash mid-write cannot leave a
     * truncated file that looks like a valid backup.
     */
    suspend fun exportJsonTo(target: File, appVersion: String): File = withContext(Dispatchers.IO) {
        writeAtomically(target, backupJsonText(appVersion))
    }

    /**
     * The backup body itself.
     *
     * Split out because a backup can also be written to a folder the user picked, which is reached
     * through a document uri rather than a [File].
     */
    suspend fun backupJsonText(appVersion: String): String = withContext(Dispatchers.IO) {
        gson.toJson(buildBackup(appVersion))
    }

    /**
     * Writes a flat CSV of the dose history, one row per dose.
     *
     * Shaped for a spreadsheet a doctor might actually read: date and time first, then the
     * medication, then planned vs taken vs status.
     */
    suspend fun exportCsvTo(target: File): File = withContext(Dispatchers.IO) {
        writeAtomically(target, csvText())
    }

    /** The CSV body, separate from where it lands. See [backupJsonText]. */
    suspend fun csvText(): String = withContext(Dispatchers.IO) {
        val medications = database.medicationDao().getAllOnce().associateBy { it.id }
        val doses = database.doseLogDao().getBetween(Long.MIN_VALUE, Long.MAX_VALUE)
            .sortedWith(compareBy({ it.epochDay }, { it.plannedMinuteOfDay }))

        val builder = StringBuilder()
        // UTF-8 BOM: without it Excel on Windows guesses a legacy code page and mangles every
        // Chinese character in the file.
        builder.append('\uFEFF')
        builder.append(
            listOf(
                "日期", "计划时间", "药品", "规格", "剂型",
                "计划数量", "单位", "实际数量", "状态", "实际服药时间", "备注",
            ).joinToString(",")
        ).append('\n')

        for (dose in doses) {
            val med = medications[dose.medicationId]
            builder.append(
                listOf(
                    DateTimeUtils.formatDate(dose.epochDay),
                    DateTimeUtils.formatMinuteOfDay(dose.plannedMinuteOfDay),
                    med?.name ?: "已删除药品",
                    med?.strength ?: "",
                    med?.dosageForm?.label ?: "",
                    QuantityFormatter.format(dose.plannedQuantity),
                    dose.plannedUnit,
                    QuantityFormatter.format(dose.takenQuantity),
                    dose.status.label,
                    dose.takenTimeMillis?.let { DateTimeUtils.formatDateTime(it) } ?: "",
                    dose.note,
                ).joinToString(",") { csvCell(it) }
            ).append('\n')
        }

        builder.toString()
    }

    // ----------------------------------------------------------------- import

    /** Reads and validates a backup without touching the database. */
    suspend fun parseBackup(input: InputStream): BackupFile = withContext(Dispatchers.IO) {
        parseBackupText(input.bufferedReader().use { it.readText() })
    }

    /** Visible for testing: parses and validates the JSON body. */
    fun parseBackupText(text: String): BackupFile = BackupFormat.parse(text)

    /**
     * Replaces the database contents with [backup] inside a single transaction.
     *
     * Ids are preserved so the schedule -> dose-log links survive. Rows referencing a missing
     * parent are skipped rather than aborting the whole import: a partially valid backup is more
     * useful than a hard failure, and the FK constraints would otherwise reject everything.
     */
    suspend fun importBackup(backup: BackupFile): ImportResult = withContext(Dispatchers.IO) {
        // ---------------------------------------------------------------- validate FIRST
        //
        // Nothing destructive may happen before the import is known to be meaningful. The previous
        // order cleared every table inside the transaction, let it commit, and only then threw
        // "备份里没有可导入的药品" for a backup that turned out to contain no medications - so the user
        // got an error message *and* an empty database. For a medication record that is the worst
        // possible outcome, and it is not recoverable by retrying.
        val medicationIds = backup.medications.map { it.id }.toSet()
        if (medicationIds.isEmpty()) {
            throw BackupParseException("备份里没有药品，已取消导入（原数据未改动）")
        }

        val result = database.withTransaction {
            // clearAllTables() respects foreign keys and is far more reliable than deleting table
            // by table in a hand-picked order. It runs inside the transaction, so a failure during
            // any insert below rolls the whole thing back instead of leaving an empty database.
            database.clearAllTables()

            for (dto in backup.medications) {
                database.medicationDao().insert(dto.toEntity())
            }

            val scheduleIds = backup.schedules
                .filter { it.medicationId in medicationIds }
                .map { it.id }
                .toSet()
            for (dto in backup.schedules) {
                if (dto.medicationId !in medicationIds) continue
                database.medicationDao().insertSchedule(dto.toEntity())
            }

            val logIds = mutableSetOf<Long>()
            for (dto in backup.doseLogs) {
                if (dto.medicationId !in medicationIds) continue
                if (dto.scheduleId !in scheduleIds) continue
                database.doseLogDao().insert(dto.toEntity())
                logIds += dto.id
            }

            for (dto in backup.doseEvents) {
                if (dto.doseLogId !in logIds) continue
                database.doseLogDao().insertEvent(dto.toEntity())
            }

            // Rounds come after medications because they carry a foreign key, and a round for a medication
            // that is not in this backup is skipped rather than failing the whole import. Ids are preserved,
            // so a round keeps pointing at the medication it always belonged to.
            var cycleCount = 0
            for (dto in backup.reviewCycles) {
                if (dto.medicationId !in medicationIds) continue
                database.reviewCycleDao().insert(dto.toEntity())
                cycleCount++
            }

            // Clip *metadata* only: the audio is not in the backup (see RingClipDto). Importing the rows
            // keeps the picker honest - it lists the user's ringtones and can say their sound needs
            // re-rendering - instead of silently pretending they were never made.
            //
            // The stored path is absolute and device-specific, so it is re-pointed at *this* device's clip
            // directory using the same file name. On the same device that is the file that already exists;
            // on a new device it will not exist, which RingClipRepository already handles by falling back
            // to the bundled tone and logging the fact rather than ringing silently.
            val clipDirectory = java.io.File(appContext.filesDir, "ringtones")
            for (dto in backup.ringClips) {
                val localFile = java.io.File(clipDirectory, java.io.File(dto.filePath).name)
                database.ringClipDao().insert(
                    dto.toEntity().copy(filePath = localFile.absolutePath)
                )
            }

            ImportResult(
                medications = backup.medications.size,
                schedules = scheduleIds.size,
                doseLogs = logIds.size,
                reviewCycles = cycleCount,
                ringClips = backup.ringClips.size,
            )
        }

        // Settings live in DataStore, outside the SQL transaction; applying them afterwards is the
        // closest we can get to atomic across the two stores.
        //
        // Deliberately swallowed: the rows are already committed and correct, so a preferences write
        // that fails must not be reported to the user as "导入失败" - and must certainly not suggest
        // that the import did not happen.
        runCatching { backup.settings?.let { settingsRepository.applyImportedSettings(it) } }
        // Runs after the settings are in, because it inspects the setting they just wrote.
        runCatching { verifyImportedRingtone(backup) }
        // The clip table was rewritten, so which of its rows are still referenced has changed.
        runCatching {
            val global = settingsRepository.current().ringClipId
            val perMedication = database.medicationDao().distinctCustomRingClipIds().toSet()
            for (clip in database.ringClipDao().all()) {
                database.ringClipDao().setInUse(clip.id, clip.id == global || clip.id in perMedication)
            }
        }

        result
    }

    /** Deletes every row; used by "清空数据" in settings. */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        database.withTransaction { database.clearAllTables() }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Writes [content] to [target] via a temporary file.
     *
     * @return the final file
     */
    private fun writeAtomically(target: File, content: String): File {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(content, Charsets.UTF_8)
        if (target.exists() && !target.delete()) {
            temp.delete()
            throw java.io.IOException("无法覆盖已有文件：${target.name}")
        }
        if (!temp.renameTo(target)) {
            // Some devices refuse a rename across mount points; a direct copy still works.
            target.writeText(content, Charsets.UTF_8)
            temp.delete()
        }
        return target
    }

    /** Renders a quantity for CSV without trailing zeros. */
    private fun csvNumber(value: Double): String = QuantityFormatter.format(value)

    /**
     * Quotes a CSV cell when it contains a comma, a quote or a newline, doubling embedded quotes as
     * RFC 4180 requires. Chinese medication notes routinely contain commas.
     */
    private fun csvCell(value: String): String {
        val needsQuoting = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuoting) return value
        return '"' + value.replace("\"", "\"\"") + '"'
    }

    private suspend fun SettingsRepository.applyImportedSettings(dto: SettingsDto) {
        setThemeMode(enumOrDefault(dto.themeMode, ThemeMode.SYSTEM))
        setDynamicColor(dto.useDynamicColor)
        setAccentColor(enumOrDefault(dto.accentColor, AccentColor.MINT))
        setFontScale(enumOrDefault(dto.fontScale, FontScale.NORMAL))
        setHighContrast(dto.highContrast)
        setSimplifiedMode(dto.simplifiedMode)
        setUse24Hour(dto.use24HourFormat)
        setWeekStart(enumOrDefault(dto.weekStart, WeekStart.MONDAY))
        setRemindersEnabled(dto.remindersEnabled)
        setSnoozeMinutes(dto.snoozeMinutes)
        setMissedGraceMinutes(dto.missedGraceMinutes)
        setWidgetItemLimit(dto.widgetItemLimit)
        setWidgetShowCompleted(dto.widgetShowCompleted)
        // Added later: each falls back to the stored value when the backup predates the option, so
        // importing an old file never silently switches a reminder behaviour off.
        dto.widgetRefreshSeconds?.let { setWidgetRefreshSeconds(it) }
        dto.soundEnabled?.let { setSoundEnabled(it) }
        dto.vibrationEnabled?.let { setVibrationEnabled(it) }
        dto.reminderTone?.let { name ->
            // Only accept a tone this build actually has: a backup from a future version must not leave
            // the app pointing at a name it cannot resolve.
            if (com.meditrack.domain.reminder.ReminderTone.entries.any { it.name == name }) {
                setReminderTone(name)
            }
        }
        dto.headsUpEnabled?.let { setHeadsUpEnabled(it) }
        dto.idleDeferralEnabled?.let { setIdleDeferralEnabled(it) }
        dto.idleThresholdMinutes?.let { setIdleThresholdMinutes(it) }
        dto.deferWhileScreenOff?.let { setDeferWhileScreenOff(it) }
        // The unlock catch-up and its budget. Present in backups from this version onwards; older
        // backups leave the shipped defaults (enabled, three reminders, five-minute spacing).
        dto.unlockReminderEnabled?.let { setUnlockReminderEnabled(it) }
        dto.unlockReminderMaxPerDose?.let { setUnlockReminderMaxPerDose(it) }
        dto.unlockReminderMinGapMinutes?.let { setUnlockReminderMinGapMinutes(it) }
        dto.fullScreenReminderEnabled?.let { setFullScreenReminderEnabled(it) }
        // The 2.0 ring and review options. `ringMode` is validated against this build's enum for the same
        // reason the tone is: a backup from a future version must not leave the app pointing at a mode it
        // cannot resolve, and an unresolvable mode would fall back to the loud default without anyone
        // being told.
        dto.ringMode?.let { name ->
            if (com.meditrack.domain.reminder.ReminderRingMode.entries.any { it.name == name }) {
                setRingMode(com.meditrack.domain.reminder.ReminderRingMode.fromName(name))
            }
        }
        dto.ringMaxMinutes?.let { setRingMaxMinutes(it) }
        dto.ringTimes?.let { setRingTimes(it) }
        dto.ringIntervalSeconds?.let { setRingIntervalSeconds(it) }
        dto.reviewReminderEnabled?.let { setReviewReminderEnabled(it) }
        dto.reviewAdvanceNotice?.let { setReviewAdvanceNotice(it) }
        dto.reviewSearchEngine?.let { name ->
            if (com.meditrack.data.local.entity.ReviewSearchEngine.entries.any { it.name == name }) {
                setReviewSearchEngine(com.meditrack.data.local.entity.ReviewSearchEngine.fromName(name))
            }
        }
        dto.reviewSearchSuffix?.let { setReviewSearchSuffix(it) }
        // The selected ringtone is written here and *verified* after the clips are imported, because
        // whether it survived depends on whether its clip entry came with the backup - see
        // verifyImportedRingtone.
        dto.ringClipId?.let { setRingClipId(it) }
    }

    /**
     * Clears the selected ringtone when the clip it points at did not come with the backup.
     *
     * This is a real failure mode rather than a theoretical one: a backup holds clip *metadata* but not the
     * audio, and a user who exports, uninstalls and reinstalls restores rows that point at files which no
     * longer exist. RingClipRepository already falls back to the bundled tone in that case and logs it -
     * but the *setting* would still name a dead clip, which is exactly the kind of state that makes a user
     * believe the feature is broken rather than that their sound is missing. Clearing it makes the fallback
     * explicit and visible.
     */
    private suspend fun verifyImportedRingtone(backup: BackupFile) {
        val selected = settingsRepository.current().ringClipId ?: return
        val present = backup.ringClips.any { it.id == selected }
        if (!present) settingsRepository.setRingClipId(null)
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, fallback: T): T =
        if (name == null) fallback else runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)

    // ------------------------------------------------------------ entity <-> dto

    private fun Medication.toDto() = MedicationDto(
        id = id, name = name, icon = icon.name, colorTag = colorTag.name,
        dosageForm = dosageForm.name, unit = unit.name, strength = strength,
        doseAmount = doseAmount, maxDoseAmount = maxDoseAmount, foodTiming = foodTiming.name,
        note = note, stockAmount = stockAmount, stockAlertThreshold = stockAlertThreshold,
        reminderEnabled = reminderEnabled, isActive = isActive,
        createdAt = createdAt, updatedAt = updatedAt,
        customRingClipId = customRingClipId,
        reviewReminderEnabled = reviewReminderEnabled,
        reviewNote = reviewNote,
        reviewSearchQuery = reviewSearchQuery,
        reviewCountMode = reviewCountMode.name,
        reviewThreshold = reviewThreshold,
    )

    private fun MedicationDto.toEntity() = Medication(
        id = id, name = name,
        icon = enumOrDefault(icon, MedicationIcon.TABLET),
        colorTag = enumOrDefault(colorTag, MedicationColorTag.MINT),
        dosageForm = enumOrDefault(dosageForm, DosageForm.TABLET),
        unit = enumOrDefault(unit, DosageUnit.TABLET),
        strength = strength, doseAmount = doseAmount, maxDoseAmount = maxDoseAmount,
        foodTiming = enumOrDefault(foodTiming, FoodTiming.NONE),
        note = note, stockAmount = stockAmount, stockAlertThreshold = stockAlertThreshold,
        reminderEnabled = reminderEnabled, isActive = isActive,
        createdAt = createdAt, updatedAt = updatedAt,
        customRingClipId = customRingClipId,
        // A backup from before 2.0 carries none of these, and "no review configured" is the correct
        // historical answer: the user never set one, so nothing may start firing because of an import.
        // The arming flag defaults to true here for the same reason it does in the entity - it is inert
        // while the threshold is zero.
        reviewReminderEnabled = reviewReminderEnabled ?: true,
        reviewNote = reviewNote.orEmpty(),
        reviewSearchQuery = reviewSearchQuery
            ?: com.meditrack.data.local.entity.ReviewSearchQuery.DEFAULT_QUESTION,
        reviewCountMode = enumOrDefault(reviewCountMode, ReviewCountMode.DOSES),
        reviewThreshold = reviewThreshold ?: 0.0,
    )

    private fun Schedule.toDto() = ScheduleDto(
        id = id, medicationId = medicationId, minuteOfDay = minuteOfDay,
        repeatType = repeatRule.type.name, intervalDays = repeatRule.intervalDays,
        daysOfWeek = repeatRule.daysOfWeek.sorted(), cycleOnDays = repeatRule.cycleOnDays,
        cycleOffDays = repeatRule.cycleOffDays, anchorEpochDay = repeatRule.anchorEpochDay,
        startEpochDay = startEpochDay, endEpochDay = endEpochDay,
        reminderEnabled = reminderEnabled,
        daysOfMonth = repeatRule.daysOfMonth.sorted().ifEmpty { null },
    )

    private fun ScheduleDto.toEntity() = Schedule(
        id = id, medicationId = medicationId, minuteOfDay = minuteOfDay,
        repeatRule = RepeatingRule(
            type = enumOrDefault(repeatType, RepeatRuleType.DAILY),
            intervalDays = intervalDays.coerceAtLeast(1),
            daysOfWeek = daysOfWeek.filter { it in 1..7 }.toSet(),
            daysOfMonth = daysOfMonth.orEmpty().filter { it in 1..31 }.toSet(),
            cycleOnDays = cycleOnDays.coerceAtLeast(1),
            cycleOffDays = cycleOffDays.coerceAtLeast(0),
            anchorEpochDay = anchorEpochDay,
        ),
        startEpochDay = startEpochDay, endEpochDay = endEpochDay,
        reminderEnabled = reminderEnabled,
    )

    private fun DoseLog.toDto() = DoseLogDto(
        id = id, medicationId = medicationId, scheduleId = scheduleId, epochDay = epochDay,
        date = DateTimeUtils.formatDate(epochDay), plannedMinuteOfDay = plannedMinuteOfDay,
        plannedTimeMillis = plannedTimeMillis, plannedQuantity = plannedQuantity,
        plannedUnit = plannedUnit, takenQuantity = takenQuantity,
        takenTimeMillis = takenTimeMillis, status = status.name,
        snoozeCount = snoozeCount, note = note,
        deferredAtMillis = deferredAtMillis,
    )

    private fun DoseLogDto.toEntity() = DoseLog(
        id = id, medicationId = medicationId, scheduleId = scheduleId, epochDay = epochDay,
        plannedMinuteOfDay = plannedMinuteOfDay, plannedTimeMillis = plannedTimeMillis,
        plannedQuantity = plannedQuantity, plannedUnit = plannedUnit,
        takenQuantity = takenQuantity, takenTimeMillis = takenTimeMillis,
        status = enumOrDefault(status, DoseStatus.UPCOMING),
        snoozeCount = snoozeCount, note = note,
        deferredAtMillis = deferredAtMillis,
    )

    private fun DoseEvent.toDto() = DoseEventDto(
        id = id, doseLogId = doseLogId, type = type.name, delta = delta,
        resultingQuantity = resultingQuantity, resultingStatus = resultingStatus.name,
        timestamp = timestamp, note = note,
    )

    private fun DoseEventDto.toEntity() = DoseEvent(
        id = id, doseLogId = doseLogId,
        type = enumOrDefault(type, DoseEventType.EDIT),
        delta = delta, resultingQuantity = resultingQuantity,
        resultingStatus = enumOrDefault(resultingStatus, DoseStatus.UPCOMING),
        timestamp = timestamp, note = note,
    )

    private fun MedicationReviewCycle.toDto() = ReviewCycleDto(
        id = id, medicationId = medicationId, round = round,
        startedAtMillis = startedAtMillis, startedEpochDay = startedEpochDay,
        countMode = countMode.name, threshold = threshold, count = count,
        countedEpochDay = countedEpochDay,
        reachedNotified = reachedNotified,
        advanceNotifiedEpochDay = advanceNotifiedEpochDay,
        acknowledgedAtMillis = acknowledgedAtMillis,
        acknowledgedEpochDay = acknowledgedEpochDay,
        createdAt = createdAt, updatedAt = updatedAt,
    )

    private fun ReviewCycleDto.toEntity() = MedicationReviewCycle(
        id = id, medicationId = medicationId,
        // A round number below 1 would break the "最高轮次 + 1" arithmetic that numbers the next round,
        // so a malformed backup is corrected rather than trusted.
        round = round.coerceAtLeast(1),
        startedAtMillis = startedAtMillis,
        startedEpochDay = startedEpochDay,
        countMode = enumOrDefault(countMode, ReviewCountMode.DOSES),
        threshold = threshold.coerceAtLeast(0.0),
        count = count.coerceAtLeast(0.0),
        countedEpochDay = countedEpochDay,
        reachedNotified = reachedNotified,
        advanceNotifiedEpochDay = advanceNotifiedEpochDay,
        acknowledgedAtMillis = acknowledgedAtMillis,
        acknowledgedEpochDay = acknowledgedEpochDay,
        createdAt = createdAt, updatedAt = updatedAt,
    )

    private fun RingClip.toDto() = RingClipDto(
        id = id, name = name, filePath = filePath, durationMillis = durationMillis,
        sourceLabel = sourceLabel, sourceUri = sourceUri,
        trimStartMillis = trimStartMillis, trimEndMillis = trimEndMillis,
        inUse = inUse, createdAt = createdAt,
    )

    private fun RingClipDto.toEntity() = RingClip(
        id = id, name = name, filePath = filePath,
        durationMillis = durationMillis.coerceAtLeast(0L),
        sourceLabel = sourceLabel, sourceUri = sourceUri,
        trimStartMillis = trimStartMillis.coerceAtLeast(0L),
        trimEndMillis = trimEndMillis.coerceAtLeast(0L),
        // Recomputed from the restored medications and preferences the next time the app starts, so a
        // backup cannot claim a clip is in use when nothing references it.
        inUse = false,
        createdAt = createdAt,
    )

    private fun UserPreferences.toDto() = SettingsDto(
        themeMode = themeMode.name, useDynamicColor = useDynamicColor,
        accentColor = accentColor.name, fontScale = fontScale.name,
        highContrast = highContrast, simplifiedMode = simplifiedMode,
        use24HourFormat = use24HourFormat, weekStart = weekStart.name,
        remindersEnabled = remindersEnabled, snoozeMinutes = snoozeMinutes,
        missedGraceMinutes = missedGraceMinutes, widgetItemLimit = widgetItemLimit,
        widgetShowCompleted = widgetShowCompleted, widgetRefreshSeconds = widgetRefreshSeconds,
        headsUpEnabled = headsUpEnabled,
        soundEnabled = soundEnabled,
        vibrationEnabled = vibrationEnabled,
        reminderTone = reminderTone,
        idleDeferralEnabled = idleDeferralEnabled,
        idleThresholdMinutes = idleThresholdMinutes,
        deferWhileScreenOff = deferWhileScreenOff,
        unlockReminderEnabled = unlockReminderEnabled,
        unlockReminderMaxPerDose = unlockReminderMaxPerDose,
        unlockReminderMinGapMinutes = unlockReminderMinGapMinutes,
        fullScreenReminderEnabled = fullScreenReminderEnabled,
        ringMode = ringMode,
        ringMaxMinutes = ringMaxMinutes,
        ringTimes = ringTimes,
        ringIntervalSeconds = ringIntervalSeconds,
        ringClipId = ringClipId,
        reviewReminderEnabled = reviewReminderEnabled,
        reviewAdvanceNotice = reviewAdvanceNotice,
        reviewSearchEngine = reviewSearchEngine,
        reviewSearchSuffix = reviewSearchSuffix,
    )
}
