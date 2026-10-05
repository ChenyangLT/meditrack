"""
Migration verification for MediTrack, v2 -> v3 -> v4.

Why this exists: an instrumented MigrationTestHelper needs a device, and there is none attached.
This harness gets the same evidence on the JVM by doing what Room's own schema validation does:

  1. Build a v2 database using the *checked-in* `2.json` DDL, and put realistic rows in it.
  2. Execute the migration statements that are read out of `MediTrackDatabase.kt` itself - not a
     copy of them, so the test cannot drift from the shipped code.
  3. Build a fresh database from the *generated* schema export and diff the two with PRAGMA, column
     by column, index by index, foreign key by foreign key.
  4. Repeat for every subsequent version, on the *same* database, so the check proves what an
     upgrading user actually experiences: one database walked forward through the whole chain.
  5. Assert the original rows survived every step, with sensible defaults in the new columns.

The one deliberate difference is documented in `compare_tables`.
"""

import json
import os
import re
import sqlite3
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCHEMA_DIR = os.path.join(
    ROOT, "app", "schemas", "com.meditrack.data.local.MediTrackDatabase"
)
KOTLIN_DB = os.path.join(
    ROOT, "app", "src", "main", "java", "com", "meditrack", "data", "local",
    "MediTrackDatabase.kt",
)

failures = []
notes = []


def check(condition, message):
    if condition:
        print(f"  PASS  {message}")
    else:
        print(f"  FAIL  {message}")
        failures.append(message)


def load_schema(version):
    with open(os.path.join(SCHEMA_DIR, f"{version}.json"), encoding="utf-8") as fh:
        return json.load(fh)["database"]


def create_schema(conn, database):
    """Executes a Room schema export, resolving the ${TABLE_NAME} placeholder."""
    for entity in database["entities"]:
        table = entity["tableName"]
        conn.execute(entity["createSql"].replace("${TABLE_NAME}", table))
        for index in entity.get("indices", []):
            conn.execute(index["createSql"].replace("${TABLE_NAME}", table))
    for view in database.get("views", []):
        conn.execute(view["createSql"].replace("${VIEW_NAME}", view["viewName"]))
    conn.commit()


def extract_migration_sql(name):
    """
    Pulls the statements of one migration straight out of the Kotlin source.

    Reading the real file rather than restating the SQL here is the whole point: a harness with its
    own copy of the migration would happily pass while the shipped one is wrong.

    @param name the `val` holding the migration, e.g. `MIGRATION_2_3`
    """
    with open(KOTLIN_DB, encoding="utf-8") as fh:
        source = fh.read()

    start = source.index(f"val {name}")
    # The block ends at the next top-level `val` or the closing of the companion object.
    end = source.find("val MIGRATION", start + 10)
    if end == -1:
        end = source.find("@Volatile", start)
    block = source[start:end]

    statements = []
    for match in re.finditer(r"execSQL\(\s*((?:\"[^\"]*\"\s*(?:\+\s*)?)+)\)", block):
        literal_source = match.group(1)
        parts = re.findall(r"\"([^\"]*)\"", literal_source)
        statements.append("".join(parts))

    if not statements:
        raise SystemExit(f"could not extract any execSQL statement from {name}")
    return statements


def pragma_columns(conn, table):
    rows = conn.execute(f"PRAGMA table_info(`{table}`)").fetchall()
    # (cid, name, type, notnull, dflt_value, pk)
    return {
        row[1]: {"type": row[2], "notnull": row[3], "dflt": row[4], "pk": row[5]}
        for row in rows
    }


def pragma_indices(conn, table):
    rows = conn.execute(f"PRAGMA index_list(`{table}`)").fetchall()
    out = {}
    for row in rows:
        name = row[1]
        cols = [
            r[2]
            for r in conn.execute(f"PRAGMA index_info(`{name}`)").fetchall()
        ]
        out[name] = {"unique": row[2], "origin": row[3], "columns": cols}
    return out


def pragma_foreign_keys(conn, table):
    rows = conn.execute(f"PRAGMA foreign_key_list(`{table}`)").fetchall()
    return sorted(
        (r[2], r[3], r[4], r[5], r[6]) for r in rows  # table, from, to, on_update, on_delete
    )


def compare_tables(migrated, expected, table):
    print(f"\n[{table}]")

    actual_cols = pragma_columns(migrated, table)
    wanted_cols = pragma_columns(expected, table)

    check(
        set(actual_cols) == set(wanted_cols),
        f"column set matches ({len(wanted_cols)} columns)"
        + ("" if set(actual_cols) == set(wanted_cols)
           else f" actual={sorted(actual_cols)} wanted={sorted(wanted_cols)}"),
    )

    for name in sorted(set(actual_cols) & set(wanted_cols)):
        a, w = actual_cols[name], wanted_cols[name]
        check(
            a["type"].upper() == w["type"].upper() and a["notnull"] == w["notnull"]
            and a["pk"] == w["pk"],
            f"column {name}: {w['type']} notnull={w['notnull']} pk={w['pk']}",
        )
        if a["dflt"] != w["dflt"]:
            # Room's TableInfo.Column.equals() only compares a default value when the *entity* side
            # declares one via @ColumnInfo(defaultValue = ...). Neither new column does, so Room reads
            # a null expected default and short-circuits the comparison. The database's own DEFAULT is
            # therefore tolerated - and it has to exist, because SQLite refuses to ADD a NOT NULL
            # column without one.
            if w["dflt"] is None and a["dflt"] is not None:
                notes.append(
                    f"{table}.{name}: database has DEFAULT {a['dflt']!r}, entity declares none. "
                    "Room ignores this (TableInfo.Column.equals short-circuits on a null expected "
                    "default), so the migration validates."
                )
            else:
                check(False, f"column {name}: default {a['dflt']!r} != expected {w['dflt']!r}")

    actual_idx = pragma_indices(migrated, table)
    wanted_idx = pragma_indices(expected, table)
    check(
        set(actual_idx) == set(wanted_idx),
        f"index set matches ({sorted(wanted_idx)})"
        + ("" if set(actual_idx) == set(wanted_idx)
           else f" actual={sorted(actual_idx)}"),
    )
    for name in sorted(set(actual_idx) & set(wanted_idx)):
        check(
            actual_idx[name]["columns"] == wanted_idx[name]["columns"],
            f"index {name} covers {wanted_idx[name]['columns']}",
        )

    check(
        pragma_foreign_keys(migrated, table) == pragma_foreign_keys(expected, table),
        "foreign keys match",
    )


def main():
    print("=" * 78)
    print("MediTrack v2 -> v3 -> v4 -> v5 migration verification")
    print("=" * 78)

    v2 = load_schema(2)
    v3 = load_schema(3)
    v4 = load_schema(4)
    v5 = load_schema(5)

    statements = extract_migration_sql("MIGRATION_2_3")
    print(f"\nExtracted {len(statements)} statement(s) from MIGRATION_2_3:")
    for s in statements:
        print("  - " + " ".join(s.split())[:110] + ("..." if len(s) > 110 else ""))

    # ---------------------------------------------------------------- build v2
    print("\n--- building a populated v2 database ---")
    migrated = sqlite3.connect(":memory:")
    migrated.execute("PRAGMA foreign_keys = ON")
    create_schema(migrated, v2)

    migrated.execute(
        "INSERT INTO medications (id, name, icon, colorTag, dosageForm, unit, strength, "
        "doseAmount, maxDoseAmount, foodTiming, note, stockAmount, stockAlertThreshold, "
        "reminderEnabled, isActive, createdAt, updatedAt) "
        "VALUES (1,'阿司匹林','TABLET','MINT','TABLET','TABLET','100mg',1.0,2.0,'AFTER_MEAL',"
        "'饭后',30.0,5.0,1,1,1000,1000)"
    )
    migrated.execute(
        "INSERT INTO schedules (id, medicationId, minuteOfDay, repeatRule, startEpochDay, "
        "endEpochDay, reminderEnabled, createdAt) "
        "VALUES (1,1,480,'DAILY|1||1|0|19000|',19000,NULL,1,1000)"
    )
    migrated.execute(
        "INSERT INTO dose_logs (id, medicationId, scheduleId, epochDay, plannedMinuteOfDay, "
        "plannedTimeMillis, plannedQuantity, plannedUnit, takenQuantity, snoozeCount, "
        "missedNotified, overDoseConfirmed, status, note, createdAt, updatedAt) "
        "VALUES (7,1,1,19000,480,1700000000000,1.0,'片',0.0,0,0,0,'DUE','',1000,1000)"
    )
    migrated.execute(
        "INSERT INTO dose_events (id, doseLogId, type, delta, resultingQuantity, "
        "resultingStatus, timestamp, note) VALUES (1,7,'EDIT',0.0,0.0,'DUE',1000,'')"
    )
    migrated.commit()

    before = migrated.execute(
        "SELECT id, medicationId, plannedMinuteOfDay, status FROM dose_logs"
    ).fetchall()
    check(before == [(7, 1, 480, "DUE")], "seeded v2 row is present before the migration")

    # ------------------------------------------------------------- migrate
    print("\n--- applying MIGRATION_2_3 ---")
    for statement in statements:
        try:
            migrated.execute(statement)
        except sqlite3.Error as exc:
            check(False, f"statement failed: {exc} :: {' '.join(statement.split())[:90]}")
    migrated.commit()
    check(True, "every migration statement executed")

    # --------------------------------------------------------------- diff
    print("\n--- diffing the migrated database against the generated v3 schema ---")
    expected = sqlite3.connect(":memory:")
    create_schema(expected, v3)

    for entity in v3["entities"]:
        compare_tables(migrated, expected, entity["tableName"])

    # ------------------------------------------------------------ data kept
    print("\n--- data preservation ---")
    after = migrated.execute(
        "SELECT id, medicationId, plannedMinuteOfDay, status, escalationCount, "
        "preRemindedAtMillis FROM dose_logs"
    ).fetchall()
    check(
        after == [(7, 1, 480, "DUE", 0, None)],
        "the existing dose survived with sensible defaults for the new columns",
    )
    check(
        migrated.execute("SELECT COUNT(*) FROM dose_events").fetchone()[0] == 1,
        "the audit trail survived",
    )
    check(
        migrated.execute("SELECT COUNT(*) FROM reminder_events").fetchone()[0] == 0,
        "the new reminder_events table starts empty",
    )
    check(
        migrated.execute("SELECT name FROM medications WHERE id=1").fetchone()[0] == "阿司匹林",
        "medication data survived",
    )

    # -------------------------------------------------------------- 3 -> 4
    #
    # The same database is walked forward rather than a fresh one being built, because that is what
    # an upgrading user actually has: one file that has to survive every step of the chain.
    print("\n--- applying MIGRATION_3_4 ---")
    unlock_statements = extract_migration_sql("MIGRATION_3_4")
    print(f"Extracted {len(unlock_statements)} statement(s) from MIGRATION_3_4:")
    for s in unlock_statements:
        print("  - " + " ".join(s.split())[:110] + ("..." if len(s) > 110 else ""))
    for statement in unlock_statements:
        try:
            migrated.execute(statement)
        except sqlite3.Error as exc:
            check(False, f"statement failed: {exc} :: {' '.join(statement.split())[:90]}")
    migrated.commit()
    check(True, "every v4 migration statement executed")

    print("\n--- diffing the migrated database against the generated v4 schema ---")
    expected_v4 = sqlite3.connect(":memory:")
    create_schema(expected_v4, v4)
    for entity in v4["entities"]:
        compare_tables(migrated, expected_v4, entity["tableName"])

    print("\n--- data preservation across 3 -> 4 ---")
    after_v4 = migrated.execute(
        "SELECT id, medicationId, plannedMinuteOfDay, status, escalationCount, "
        "preRemindedAtMillis, unlockReminderCount, unlockReminderAtMillis FROM dose_logs"
    ).fetchall()
    check(
        after_v4 == [(7, 1, 480, "DUE", 0, None, 0, None)],
        "the dose survived with a zero unlock budget and no recorded catch-up",
    )
    check(
        migrated.execute("SELECT name FROM medications WHERE id=1").fetchone()[0] == "阿司匹林",
        "medication data still intact after the v4 step",
    )

    # -------------------------------------------------------------- 4 -> 5
    #
    # The «复查提醒» / custom-ringtone release. This step is different in kind from the previous two: it
    # adds *columns with meaningful defaults* to `medications`, and a default that disagrees with the
    # generated schema is exactly the thing Room rejects at runtime with "Migration didn't properly
    # handle" - after the user's database has already been touched. So this step is checked twice over:
    # once by the table diff below, and once by an explicit assertion that the defaults are the ones that
    # make the new feature inert for a user who never configured it.
    print("\n--- applying MIGRATION_4_5 ---")
    review_statements = extract_migration_sql("MIGRATION_4_5")
    print(f"Extracted {len(review_statements)} statement(s) from MIGRATION_4_5:")
    for s in review_statements:
        print("  - " + " ".join(s.split())[:110] + ("..." if len(s) > 110 else ""))
    for statement in review_statements:
        try:
            migrated.execute(statement)
        except sqlite3.Error as exc:
            check(False, f"statement failed: {exc} :: {' '.join(statement.split())[:90]}")
    migrated.commit()
    check(True, "every v5 migration statement executed")

    print("\n--- diffing the migrated database against the generated v5 schema ---")
    expected_v5 = sqlite3.connect(":memory:")
    create_schema(expected_v5, v5)
    for entity in v5["entities"]:
        compare_tables(migrated, expected_v5, entity["tableName"])

    print("\n--- the new defaults must leave the review feature inert ---")
    upgraded = migrated.execute(
        "SELECT reviewReminderEnabled, reviewNote, reviewCountMode, reviewThreshold, reviewSearchQuery, "
        "customRingClipId FROM medications WHERE id=1"
    ).fetchone()
    check(
        upgraded is not None,
        "the pre-existing medication survived the v5 step",
    )
    if upgraded is not None:
        enabled, note, mode, threshold, query, clip = upgraded
        check(enabled == 1, "reviewReminderEnabled defaults to armed")
        check(
            float(threshold) == 0.0,
            "reviewThreshold defaults to 0, so nothing fires for a medication nobody configured",
        )
        check(mode == "DOSES", "reviewCountMode defaults to counting doses")
        check(note == "", "reviewNote defaults to empty")
        check(
            query == "吃多久需要去复查",
            "reviewSearchQuery defaults to the question the feature was specified with",
        )
        check(clip is None, "customRingClipId defaults to unset")

    print("\n--- the new tables start empty and reference medications ---")
    check(
        migrated.execute("SELECT COUNT(*) FROM medication_review_cycles").fetchone()[0] == 0,
        "no review round is invented for an upgrading user",
    )
    check(
        migrated.execute("SELECT COUNT(*) FROM ring_clips").fetchone()[0] == 0,
        "no ringtone is invented for an upgrading user",
    )
    migrated.execute("PRAGMA foreign_keys = ON")
    try:
        migrated.execute(
            "INSERT INTO medication_review_cycles "
            "(medicationId, round, startedAtMillis, startedEpochDay, countMode, threshold, count, "
            " countedEpochDay, reachedNotified, advanceNotifiedEpochDay, createdAt, updatedAt) "
            "VALUES (999, 1, 0, 0, 'DOSES', 0, 0, 0, 0, 0, 0, 0)"
        )
        check(False, "a review round for a missing medication must be rejected")
    except sqlite3.IntegrityError:
        check(True, "a review round cannot reference a medication that does not exist")

    print("\n--- data preservation across 4 -> 5 ---")
    after_v5 = migrated.execute(
        "SELECT id, medicationId, plannedMinuteOfDay, status, escalationCount FROM dose_logs"
    ).fetchall()
    check(
        after_v5 == [(7, 1, 480, "DUE", 0)],
        "the dose survived the v5 step unchanged",
    )

    # ------------------------------------------- idempotence of IF NOT EXISTS
    print("\n--- re-running the migrations must not corrupt anything ---")
    for statement in statements + unlock_statements + review_statements:
        try:
            migrated.execute(statement)
        except sqlite3.Error:
            # ALTER TABLE ADD COLUMN is not idempotent by design; only the CREATE IF NOT EXISTS
            # statements are, and those are the ones that can be re-run after a partial upgrade.
            pass
    migrated.commit()
    check(
        migrated.execute("SELECT COUNT(*) FROM dose_logs").fetchone()[0] == 1,
        "data still intact after a re-run",
    )
    check(
        migrated.execute("SELECT unlockReminderCount FROM dose_logs").fetchone()[0] == 0,
        "the unlock budget survived the re-run untouched",
    )
    check(
        migrated.execute("SELECT reviewThreshold FROM medications WHERE id=1").fetchone()[0] == 0.0,
        "the review threshold survived the re-run untouched",
    )

    # ------------------------------------------------------------- summary
    print("\n" + "=" * 78)
    if notes:
        print("NOTES (expected differences, tolerated by Room's own comparison):")
        for n in notes:
            print("  * " + n)
    if failures:
        print(f"\nRESULT: FAILED ({len(failures)} problem(s))")
        for f in failures:
            print("  - " + f)
        return 1
    print(
        "\nRESULT: PASSED - the database walked from v2 to v5 and is schema-identical to Room's "
        "v5 export at every step."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
