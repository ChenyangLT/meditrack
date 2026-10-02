package com.meditrack.data.backup

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException

/** Thrown when a file is not a MediTrack backup at all. */
class BackupParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The backup format: how a file is read, and what makes it acceptable.
 *
 * Parsing lives here, away from the database, for a reason that is worth stating plainly: **a
 * rejected file must be rejected before anything is deleted.** The importer used to clear every table
 * first and validate afterwards, so a file that turned out to describe no medications at all left the
 * user with an empty database *and* an error message. Keeping validation pure makes that ordering
 * testable on the JVM - [BackupFormatTest] pins it - instead of hiding it behind a Room instance no
 * unit test can construct.
 */
object BackupFormat {

    /**
     * Gson configured once, for both reading and writing.
     *
     * Pretty-printed because a backup a human can open and read is a backup a human can trust.
     */
    val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * Parses and validates [text], or throws [BackupParseException] explaining why it cannot be used.
     *
     * The checks are ordered from "is this even a file we wrote?" to "is it usable?":
     *
     *  1. not blank;
     *  2. valid JSON that maps onto the backup shape;
     *  3. not from a newer app version (whose extra fields this build cannot honour);
     *  4. **contains at least one medication**. Nothing else in the file can stand on its own -
     *     schedules, dose logs and events all reference a medication - so a medication-less file is
     *     not an empty backup, it is a file we cannot restore. Reading it as "an empty backup" is
     *     exactly what once wiped a real database.
     */
    fun parse(text: String): BackupFile {
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
        if (parsed.medications.isEmpty()) {
            throw BackupParseException("备份里没有药品，已取消导入（原数据未改动）")
        }
        return parsed
    }
}
