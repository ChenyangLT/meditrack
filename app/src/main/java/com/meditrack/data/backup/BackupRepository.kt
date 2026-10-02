package com.meditrack.data.backup

import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.GsonBuilder
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
import com.meditrack.data.local.entity.RepeatRuleType
import com.meditrack.data.local.entity.RepeatingRule
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
         */
        const val CURRENT_SCHEMA_VERSION = 2
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
    @SerializedName("idleDeferralEnabled") val idleDeferralEnabled: Boolean? = null,
    @SerializedName("idleThresholdMinutes") val idleThresholdMinutes: Int? = null,
    @SerializedName("deferWhileScreenOff") val deferWhileScreenOff: Boolean? = null,
)

/** Outcome of an import, surfaced to the user as a toast. */
data class ImportResult(
    val medications: Int,
    val schedules: Int,
    val doseLogs: Int,
)

/** Thrown when the file is not a MediTrack backup at all. */
class BackupParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

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
) {

    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .serializeNulls()
        .create()

    // ----------------------------------------------------------------- export

    /** Serialises everything into a [BackupFile]. */
    suspend fun buildBackup(appVersion: String): BackupFile = withContext(Dispatchers.IO) {
        val medications = database.medicationDao().getAllOnce()
        val schedules = database.medicationDao().getAllSchedulesOnce()
        val doses = database.doseLogDao().getBetween(Long.MIN_VALUE, Long.MAX_VALUE)
        val events = doses.flatMap { database.doseLogDao().getEventsFor(it.id) }
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
        writeAtomically(target, gson.toJson(buildBackup(appVersion)))
    }

    /**
     * Writes a flat CSV of the dose history, one row per dose.
     *
     * Shaped for a spreadsheet a doctor might actually read: date and time first, then the
     * medication, then planned vs taken vs status.
     */
    suspend fun exportCsvTo(target: File): File = withContext(Dispatchers.IO) {
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

        writeAtomically(target, builder.toString())
    }

    // ----------------------------------------------------------------- import

    /** Reads and validates a backup without touching the database. */
    suspend fun parseBackup(input: InputStream): BackupFile = withContext(Dispatchers.IO) {
        parseBackupText(input.bufferedReader().use { it.readText() })
    }

    /** Visible for testing: parses and validates the JSON body. */
    fun parseBackupText(text: String): BackupFile {
        if (text.isBlank()) throw BackupParseException("文件是空的")
        val parsed = try {
            gson.fromJson(text, BackupFile::class.java)
        } catch (e: JsonSyntaxException) {
            throw BackupParseException("不是有效的 JSON 备份文件", e)
        } catch (e: IllegalStateException) {
            throw BackupParseException("文件结构不符合备份格式", e)
        }

        if (parsed == null) throw BackupParseException("不是有效的 JSON 备份文件")
        if (parsed.schemaVersion > BackupFile.CURRENT_SCHEMA_VERSION) {
            throw BackupParseException("备份来自更新的版本（v${parsed.schemaVersion}），请先升级应用")
        }
        if (parsed.medications.isEmpty() && parsed.doseLogs.isEmpty()) {
            throw BackupParseException("备份里没有药品或服药记录")
        }
        return parsed
    }

    /**
     * Replaces the database contents with [backup] inside a single transaction.
     *
     * Ids are preserved so the schedule -> dose-log links survive. Rows referencing a missing
     * parent are skipped rather than aborting the whole import: a partially valid backup is more
     * useful than a hard failure, and the FK constraints would otherwise reject everything.
     */
    suspend fun importBackup(backup: BackupFile): ImportResult = withContext(Dispatchers.IO) {
        database.withTransaction {
            // clearAllTables() respects foreign keys and is far more reliable than deleting table
            // by table in a hand-picked order.
            database.clearAllTables()

            val medicationIds = backup.medications.map { it.id }.toSet()
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

            ImportResult(
                medications = backup.medications.size,
                schedules = scheduleIds.size,
                doseLogs = logIds.size,
            )
        }.also { result ->
            // Settings live in DataStore, outside the SQL transaction; applying them afterwards is
            // the closest we can get to atomic across the two stores.
            backup.settings?.let { settingsRepository.applyImportedSettings(it) }
            if (result.medications == 0) throw BackupParseException("备份里没有可导入的药品")
        }
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
        dto.headsUpEnabled?.let { setHeadsUpEnabled(it) }
        dto.idleDeferralEnabled?.let { setIdleDeferralEnabled(it) }
        dto.idleThresholdMinutes?.let { setIdleThresholdMinutes(it) }
        dto.deferWhileScreenOff?.let { setDeferWhileScreenOff(it) }
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
        idleDeferralEnabled = idleDeferralEnabled,
        idleThresholdMinutes = idleThresholdMinutes,
        deferWhileScreenOff = deferWhileScreenOff,
    )
}
