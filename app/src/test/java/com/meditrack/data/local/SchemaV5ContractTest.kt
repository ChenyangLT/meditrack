package com.meditrack.data.local

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Test
import java.io.File

/**
 * Pins the v5 schema contract that the v4 -> v5 migration has to satisfy.
 *
 * ## Why this is a schema test rather than a migration test
 *
 * The migration itself is verified by `tools/verify_migration.py`, which walks a real SQLite file from v2
 * to v5 and diffs every table against the exported schema - a far stronger check than anything achievable
 * in a JVM unit test. What that tool cannot do is run inside `gradle test`, which is the gate a developer
 * or CI actually runs. This test is that gate's half: it reads the schema Room *generated* for v5 and
 * asserts the handful of properties whose absence would be a data-loss or silent-behaviour bug, so a
 * careless edit to the migration or the entities is caught in seconds rather than on a user's phone.
 *
 * The properties asserted are chosen for consequence, not for coverage:
 *
 *  - the database is on v5, so a build that forgot to bump the version cannot ship;
 *  - both new tables exist, and `medications` has all six new columns;
 *  - `reviewThreshold` defaults to 0 and `reviewReminderEnabled` to 1, which together are what keep the
 *    feature **inert for an upgrading user** - armed but never firing, because nothing is configured;
 *  - the review table cascades on delete, because the app's own "delete a medication" path relies on it;
 *  - every new nullable column really is nullable, since a NOT NULL column without a default cannot be
 *    added to a populated table at all.
 */
class SchemaV5ContractTest {

    private val schema: JsonObject by lazy {
        val candidates = listOf(
            // Gradle runs module unit tests with the module directory as the working directory.
            File("schemas/com.meditrack.data.local.MediTrackDatabase/5.json"),
            // …and an IDE may use the project directory instead.
            File("app/schemas/com.meditrack.data.local.MediTrackDatabase/5.json"),
        )
        val file = candidates.firstOrNull { it.exists() }
        assertThat(file).isNotNull()
        JsonParser.parseString(file!!.readText()).asJsonObject["database"].asJsonObject
    }

    private fun entity(table: String): JsonObject = schema["entities"].asJsonArray
        .map { it.asJsonObject }
        .first { it["tableName"].asString == table }

    private fun field(table: String, column: String): JsonObject = entity(table)["fields"].asJsonArray
        .map { it.asJsonObject }
        .first { it["columnName"].asString == column }

    private fun columnNames(table: String): List<String> = entity(table)["fields"].asJsonArray
        .map { it.asJsonObject["columnName"].asString }

    private fun default(table: String, column: String): String =
        field(table, column)["defaultValue"]?.takeIf { !it.isJsonNull }?.asString.orEmpty()

    /** The default exactly as the schema records it, i.e. including the SQL quoting. */
    private fun defaultLiteral(table: String, column: String): String = default(table, column)

    /** The default with the SQL quoting stripped, for comparing against a Kotlin string. */
    private fun defaultText(table: String, column: String): String =
        default(table, column).removeSurrounding("'")

    // ------------------------------------------------------------------- version

    @Test
    fun `the database is on schema version five`() {
        assertThat(schema["version"].asInt).isEqualTo(5)
    }

    @Test
    fun `the migration chain reaches five`() {
        assertThat(MediTrackDatabase.MIGRATION_4_5.startVersion).isEqualTo(4)
        assertThat(MediTrackDatabase.MIGRATION_4_5.endVersion).isEqualTo(5)
    }

    // -------------------------------------------------------------- new tables

    @Test
    fun `both new tables are part of the schema`() {
        val tables = schema["entities"].asJsonArray.map { it.asJsonObject["tableName"].asString }

        assertThat(tables).contains("medication_review_cycles")
        assertThat(tables).contains("ring_clips")
    }

    @Test
    fun `medication_review_cycles carries every column the entity declares`() {
        assertThat(columnNames("medication_review_cycles")).containsExactly(
            "id", "medicationId", "round", "startedAtMillis", "startedEpochDay", "countMode",
            "threshold", "count", "countedEpochDay", "reachedNotified", "advanceNotifiedEpochDay",
            "acknowledgedAtMillis", "acknowledgedEpochDay", "createdAt", "updatedAt",
        )
    }

    @Test
    fun `ring_clips carries every column the entity declares`() {
        assertThat(columnNames("ring_clips")).containsExactly(
            "id", "name", "filePath", "durationMillis", "sourceLabel", "sourceUri",
            "trimStartMillis", "trimEndMillis", "inUse", "createdAt",
        )
    }

    // ------------------------------------------------------------ new columns

    @Test
    fun `medications gained the six columns the review and ringtone features need`() {
        assertThat(columnNames("medications")).containsAtLeast(
            "customRingClipId", "reviewReminderEnabled", "reviewNote",
            "reviewSearchQuery", "reviewCountMode", "reviewThreshold",
        )
    }

    // --------------------------------------------------------------- the gate

    @Test
    fun `the review feature is armed but inert for an upgrading user`() {
        // These two defaults together are the whole safety property of this migration. `reviewReminderEnabled
        // = 1` means the reminder is on, and `reviewThreshold = 0` means there is nothing to remind about -
        // so a user who upgrades without ever configuring a review is never told to go to the hospital by an
        // app that invented a number.
        assertThat(default("medications", "reviewReminderEnabled")).isEqualTo("1")
        assertThat(default("medications", "reviewThreshold")).isEqualTo("0")
    }

    @Test
    fun `the review search query defaults to the question the feature was specified with`() {
        // Room records a TEXT default as a SQL literal, so the schema carries the quotes; the entity's
        // Kotlin default is the unquoted string, and both have to agree or the migration and the entity
        // describe different tables.
        assertThat(defaultLiteral("medications", "reviewSearchQuery")).isEqualTo("'吃多久需要去复查'")
        assertThat(defaultText("medications", "reviewSearchQuery"))
            .isEqualTo(com.meditrack.data.local.entity.ReviewSearchQuery.DEFAULT_QUESTION)
    }

    @Test
    fun `the review count mode defaults to counting doses`() {
        assertThat(defaultLiteral("medications", "reviewCountMode")).isEqualTo("'DOSES'")
        assertThat(defaultLiteral("medication_review_cycles", "countMode")).isEqualTo("'DOSES'")
        assertThat(defaultText("medications", "reviewCountMode"))
            .isEqualTo(com.meditrack.data.local.entity.ReviewCountMode.DOSES.name)
    }

    @Test
    fun `the empty note default is the quoted empty string`() {
        assertThat(defaultLiteral("medications", "reviewNote")).isEqualTo("''")
        assertThat(defaultText("medications", "reviewNote")).isEmpty()
    }

    @Test
    fun `the per-round notification flags default to not-yet-sent`() {
        // A round that started life claiming it had already posted its notice would never post one, which
        // is the quietest possible way for a reminder feature to fail.
        assertThat(default("medication_review_cycles", "reachedNotified")).isEqualTo("0")
        assertThat(default("medication_review_cycles", "advanceNotifiedEpochDay")).isEqualTo("0")
    }

    @Test
    fun `every column that had to be nullable is nullable`() {
        // A NOT NULL column cannot be added to a populated table without a default, and these have none -
        // so if any of them were declared non-null the migration could not run on a real database at all.
        assertThat(field("medications", "customRingClipId")["notNull"].asBoolean).isFalse()
        assertThat(field("ring_clips", "sourceUri")["notNull"].asBoolean).isFalse()
        assertThat(field("medication_review_cycles", "acknowledgedAtMillis")["notNull"].asBoolean).isFalse()
        assertThat(field("medication_review_cycles", "acknowledgedEpochDay")["notNull"].asBoolean).isFalse()
    }

    // ------------------------------------------------------------ constraints

    @Test
    fun `deleting a medication cascades to its review rounds`() {
        // The app deletes a medication with a single DAO call and relies on the cascade; without it the
        // delete would fail on a foreign key violation at the one moment the user is trying to remove it.
        val statement = entity("medication_review_cycles")["createSql"].asString

        assertThat(statement).contains("FOREIGN KEY(`medicationId`)")
        assertThat(statement).contains("ON DELETE CASCADE")
    }

    @Test
    fun `the new tables are indexed on the columns the queries filter by`() {
        val reviewIndices = entity("medication_review_cycles")["indices"].asJsonArray
            .map { it.asJsonObject["name"].asString }
        val clipIndices = entity("ring_clips")["indices"].asJsonArray
            .map { it.asJsonObject["name"].asString }

        // Every read of the review table filters by medicationId, and the picker orders by createdAt.
        assertThat(reviewIndices).contains("index_medication_review_cycles_medicationId")
        assertThat(clipIndices).contains("index_ring_clips_createdAt")
    }

    // ------------------------------------------------------------ older tables

    @Test
    fun `the existing tables were not reshaped`() {
        // The migration is additive on purpose: a user with six months of history must keep all of it, so
        // this asserts that nothing was renamed or dropped in the process.
        assertThat(columnNames("dose_logs")).containsAtLeast(
            "id", "medicationId", "scheduleId", "epochDay", "plannedMinuteOfDay", "plannedTimeMillis",
            "plannedQuantity", "plannedUnit", "takenQuantity", "takenTimeMillis", "notifiedTimeMillis",
            "escalationCount", "preRemindedAtMillis", "snoozeCount", "snoozedUntilMillis",
            "missedNotified", "deferredAtMillis", "unlockReminderCount", "unlockReminderAtMillis",
            "overDoseConfirmed", "status", "note", "createdAt", "updatedAt",
        )
        assertThat(columnNames("dose_events")).containsAtLeast(
            "id", "doseLogId", "type", "delta", "resultingQuantity", "resultingStatus", "timestamp", "note",
        )
        assertThat(columnNames("reminder_events")).containsAtLeast(
            "id", "doseLogId", "timestamp", "triggerName", "decisionCode", "driftMillis", "detail",
        )
        assertThat(columnNames("schedules")).containsAtLeast(
            "id", "medicationId", "minuteOfDay", "repeatRule", "startEpochDay", "endEpochDay",
            "reminderEnabled",
        )
    }
}
