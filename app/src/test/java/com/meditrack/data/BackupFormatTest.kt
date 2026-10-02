package com.meditrack.data.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The backup format's acceptance rules.
 *
 * These exist because of a real data-loss incident: the importer validated the file *after* it had
 * already cleared every table, so a file that described no medications wiped a user's database and
 * then reported "import failed". With parsing pure, the rule that prevents it - **a backup with no
 * medications is not a restorable backup** - is pinned here, on the JVM, before any database is
 * involved.
 */
class BackupFormatTest {

    private val medicationJson = """
        {
          "id": 3, "name": "阿司匹林", "icon": "TABLET", "colorTag": "MINT",
          "dosageForm": "TABLET", "unit": "TABLET", "strength": "100mg",
          "doseAmount": 1.0, "maxDoseAmount": 2.0, "foodTiming": "AFTER_MEAL", "note": "",
          "stockAmount": 0.0, "stockAlertThreshold": 0.0,
          "reminderEnabled": true, "isActive": true, "createdAt": 0, "updatedAt": 0
        }
    """.trimIndent()

    @Test
    fun `an empty file is rejected`() {
        val error = runCatching { BackupFormat.parse("   ") }.exceptionOrNull()
        assertThat(error).isInstanceOf(BackupParseException::class.java)
    }

    @Test
    fun `a file that is not json is rejected`() {
        val error = runCatching { BackupFormat.parse("这不是备份") }.exceptionOrNull()
        assertThat(error).isInstanceOf(BackupParseException::class.java)
    }

    @Test
    fun `valid json that is no backup at all is rejected, not read as an empty backup`() {
        // This is the shape that used to get through: Gson fills every missing list with its default,
        // so an unrelated JSON document looked like "a backup containing nothing".
        val error = runCatching { BackupFormat.parse("""{"foo": 1, "bar": [2, 3]}""") }.exceptionOrNull()
        assertThat(error).isInstanceOf(BackupParseException::class.java)
        assertThat(error).hasMessageThat().contains("没有药品")
    }

    @Test
    fun `a backup with dose logs but no medications is rejected`() {
        // Schedules, logs and events all reference a medication, so this file cannot be restored
        // either - and treating it as valid is what emptied a database once.
        val orphanLogs = """
            {
              "schemaVersion": 2,
              "medications": [],
              "doseLogs": [
                {"id": 1, "medicationId": 3, "scheduleId": 6, "epochDay": 20728,
                 "plannedMinuteOfDay": 480, "plannedTimeMillis": 0, "plannedQuantity": 1.0,
                 "plannedUnit": "片", "takenQuantity": 0.0, "snoozeCount": 0,
                 "missedNotified": false, "overDoseConfirmed": false, "status": "DUE",
                 "note": "", "createdAt": 0, "updatedAt": 0}
              ]
            }
        """.trimIndent()
        val error = runCatching { BackupFormat.parse(orphanLogs) }.exceptionOrNull()
        assertThat(error).isInstanceOf(BackupParseException::class.java)
    }

    @Test
    fun `a backup from a newer app version is refused with a readable reason`() {
        val future = """{"schemaVersion": """ + (BackupFile.CURRENT_SCHEMA_VERSION + 1) +
            """, "medications": [""" + medicationJson + """]}"""
        val error = runCatching { BackupFormat.parse(future) }.exceptionOrNull()
        assertThat(error).isInstanceOf(BackupParseException::class.java)
        assertThat(error).hasMessageThat().contains("更新的版本")
    }

    @Test
    fun `a backup with one medication is accepted`() {
        val backup = BackupFormat.parse("""{"schemaVersion": 2, "medications": [""" + medicationJson + """]}""")
        assertThat(backup.medications).hasSize(1)
        assertThat(backup.medications.first().name).isEqualTo("阿司匹林")
    }
}
