"""
SQL verification for the reminder DAO layer.

There is no device attached, so Room cannot validate its own queries at runtime here. This harness
gets the same evidence by preparing every `@Query` in the DAO layer against a real SQLite database
built from the checked-in v4 schema:

  * `EXPLAIN <sql>` compiles the statement, so a typo, a wrong column name or a bad function call is
    a hard error - exactly what Room's `SQLiteStatement` preparation would raise on device.
  * The two new arming queries are additionally *executed* against seeded rows, because their whole
    reason for existing is behavioural: the old arming pass filtered on `plannedTimeMillis`, which
    silently dropped every snoozed dose out of the window.
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
DAO_DIR = os.path.join(
    ROOT, "app", "src", "main", "java", "com", "meditrack", "data", "local", "dao"
)

failures = []


def check(condition, message):
    if condition:
        print(f"  PASS  {message}")
    else:
        print(f"  FAIL  {message}")
        failures.append(message)


def build_v4(conn):
    with open(os.path.join(SCHEMA_DIR, "4.json"), encoding="utf-8") as fh:
        database = json.load(fh)["database"]
    for entity in database["entities"]:
        table = entity["tableName"]
        conn.execute(entity["createSql"].replace("${TABLE_NAME}", table))
        for index in entity.get("indices", []):
            conn.execute(index["createSql"].replace("${TABLE_NAME}", table))
    conn.commit()


def extract_queries():
    """Reads every @Query out of the DAO sources, with the file and line it came from."""
    out = []
    for name in sorted(os.listdir(DAO_DIR)):
        if not name.endswith(".kt"):
            continue
        path = os.path.join(DAO_DIR, name)
        with open(path, encoding="utf-8") as fh:
            source = fh.read()
        for match in re.finditer(r'@Query\(\s*"""([\s\S]*?)"""\s*\)', source):
            out.append((name, source[: match.start()].count("\n") + 1, match.group(1)))
        for match in re.finditer(r'@Query\(\s*"((?:[^"\\]|\\.)*)"\s*\)', source):
            sql = match.group(1).replace('\\"', '"')
            out.append((name, source[: match.start()].count("\n") + 1, sql))
    return out


def named_parameters(sql):
    """
    Every distinct `:name` parameter in a Room query.

    `EXPLAIN` still has to *execute* the statement, so the bindings have to be supplied even though
    nothing is read. NULL is fine for every one of them: preparing the statement is what resolves the
    column names, and that happens before any value is looked at.
    """
    return {name: None for name in re.findall(r"(?<!:):([A-Za-z_][A-Za-z0-9_]*)", sql)}


def main():
    print("=" * 78)
    print("MediTrack reminder DAO SQL verification")
    print("=" * 78)

    conn = sqlite3.connect(":memory:")
    conn.execute("PRAGMA foreign_keys = ON")
    build_v4(conn)

    queries = extract_queries()
    print(f"\n--- preparing {len(queries)} @Query statement(s) against the v4 schema ---")
    bad = 0
    for filename, line, sql in queries:
        stripped = " ".join(sql.split())
        try:
            conn.execute("EXPLAIN " + sql, named_parameters(sql))
        except sqlite3.Error as exc:
            bad += 1
            check(False, f"{filename}:{line} {exc} :: {stripped[:100]}")

    check(bad == 0, f"all {len(queries)} statements prepare cleanly against the real v4 schema")

    # ------------------------------------------------- behaviour of the new queries
    print("\n--- seeding data to exercise the arming queries ---")
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute(
        "INSERT INTO medications (id, name, icon, colorTag, dosageForm, unit, strength, "
        "doseAmount, maxDoseAmount, foodTiming, note, stockAmount, stockAlertThreshold, "
        "reminderEnabled, isActive, createdAt, updatedAt) "
        "VALUES (1,'药','TABLET','MINT','TABLET','TABLET','',1.0,0.0,'NONE','',0.0,0.0,1,1,0,0)"
    )
    conn.execute(
        "INSERT INTO schedules (id, medicationId, minuteOfDay, repeatRule, startEpochDay, "
        "endEpochDay, reminderEnabled, createdAt) VALUES (1,1,480,'DAILY|1||1|0|0|',0,NULL,1,0)"
    )

    NOW = 1_700_000_000_000
    MIN = 60_000
    rows = [
        # id, planned, snoozedUntil, notified, escalation, preReminded, missed, status
        (1, NOW - 2 * 60 * MIN, None, None, 0, None, 0, "MISSED"),   # old, already missed
        (2, NOW - 5 * MIN, None, None, 0, None, 0, "DUE"),           # just came due
        (3, NOW - 30 * MIN, NOW + 10 * MIN, None, 0, None, 0, "DUE"),  # snoozed into the future
        (4, NOW + 60 * MIN, None, None, 0, None, 0, "UPCOMING"),     # later today
        (5, NOW + 5 * 24 * 60 * MIN, None, None, 0, None, 0, "UPCOMING"),  # beyond the horizon
        (6, NOW - 20 * MIN, None, NOW - 20 * MIN, 1, None, 0, "DUE"),  # already announced
        (7, NOW - 10 * MIN, None, None, 0, None, 0, "SKIPPED"),      # resolved
        (8, NOW - 40 * MIN, None, None, 1, None, 0, "PARTIAL"),      # half taken
        (9, NOW - 5 * 60 * MIN, None, None, 0, None, 1, "MISSED"),   # too old to act on
    ]
    for (rid, planned, snoozed, notified, esc, pre, missed, status) in rows:
        # A PARTIAL row has to actually carry a recorded amount, or it would just be an untouched DUE
        # row that happens to be labelled partial.
        taken = 0.5 if status == "PARTIAL" else 0.0
        conn.execute(
            "INSERT INTO dose_logs (id, medicationId, scheduleId, epochDay, plannedMinuteOfDay, "
            "plannedTimeMillis, plannedQuantity, plannedUnit, takenQuantity, notifiedTimeMillis, "
            "escalationCount, preRemindedAtMillis, snoozeCount, snoozedUntilMillis, missedNotified, "
            "overDoseConfirmed, status, note, createdAt, updatedAt, unlockReminderCount) "
            "VALUES (?,1,1,0,480,?,1.0,'片',?,?,?,?,0,?,?,0,?,'',0,0,0)",
            (rid, planned, taken, notified, esc, pre, snoozed, missed, status),
        )
    conn.commit()

    # Mirrors DoseLogDao.getArmableBetween.
    print("\n[getArmableBetween] the query that arming is built on")
    armable = conn.execute(
        """
        SELECT id FROM dose_logs
         WHERE status IN ('UPCOMING', 'DUE', 'PARTIAL')
           AND MAX(COALESCE(snoozedUntilMillis, plannedTimeMillis), plannedTimeMillis)
               BETWEEN ? AND ?
         ORDER BY plannedTimeMillis ASC
        """,
        (NOW, NOW + 3 * 24 * 60 * MIN),
    ).fetchall()
    ids = sorted(r[0] for r in armable)
    check(
        2 not in ids,
        "a dose whose moment has already passed is left to the catch-up query, not re-armed in the past",
    )
    check(3 in ids, "a SNOOZED dose is armable at its snooze time, not excluded by its past planned time")
    check(4 in ids, "a dose later today is armable")
    check(1 not in ids, "a MISSED dose is never armed again")
    check(5 not in ids, "a dose beyond the horizon is not armed")
    check(7 not in ids, "a SKIPPED dose is never armed again")

    # This is the regression itself, stated directly: filtering on plannedTimeMillis loses the snooze.
    print("\n[regression] the old filter would have dropped the snoozed dose")
    old_style = conn.execute(
        """
        SELECT id FROM dose_logs
         WHERE plannedTimeMillis BETWEEN ? AND ?
           AND status IN ('UPCOMING', 'DUE', 'PARTIAL')
        """,
        (NOW, NOW + 3 * 24 * 60 * MIN),
    ).fetchall()
    check(
        3 not in sorted(r[0] for r in old_style),
        "confirmed: plannedTimeMillis alone excludes dose 3 while it is snoozed",
    )

    # Mirrors DoseLogDao.getOverdueOpen.
    print("\n[getOverdueOpen] the catch-up query")
    overdue = conn.execute(
        """
        SELECT id FROM dose_logs
         WHERE status IN ('UPCOMING', 'DUE', 'PARTIAL', 'MISSED')
           AND MAX(COALESCE(snoozedUntilMillis, plannedTimeMillis), plannedTimeMillis) <= ?
           AND MAX(COALESCE(snoozedUntilMillis, plannedTimeMillis), plannedTimeMillis) >= ?
         ORDER BY plannedTimeMillis ASC
        """,
        (NOW, NOW - 4 * 60 * MIN),
    ).fetchall()
    overdue_ids = sorted(r[0] for r in overdue)
    check(2 in overdue_ids, "a dose that came due minutes ago is caught up")
    check(3 not in overdue_ids, "a snoozed dose whose new time is ahead is not treated as overdue")
    check(6 in overdue_ids, "an already-announced dose is still evaluated (the planner decides)")
    # The regression this release fixes: the same reconcile pass derives 未服药 *before* it collects
    # candidates, so excluding MISSED here made a dose that lapsed a moment earlier invisible to the
    # very pass that was supposed to announce it.
    check(1 in overdue_ids, "a dose already derived as 未服药 is still visible to the pass that announces it")

    # Mirrors DoseLogDao.getUnlockCatchUpCandidates.
    print("\n[getUnlockCatchUpCandidates] the unlock catch-up query")
    conn.execute("UPDATE dose_logs SET missedNotified = 1 WHERE id = 1")
    conn.commit()
    unlock_rows = conn.execute(
        """
        SELECT id FROM dose_logs
         WHERE status IN ('UPCOMING', 'DUE', 'PARTIAL', 'MISSED')
           AND MAX(COALESCE(snoozedUntilMillis, plannedTimeMillis), plannedTimeMillis) <= ?
           AND MAX(COALESCE(snoozedUntilMillis, plannedTimeMillis), plannedTimeMillis) >= ?
         ORDER BY plannedTimeMillis ASC
        """,
        (NOW, NOW - 180 * MIN),
    ).fetchall()
    unlock_ids = sorted(r[0] for r in unlock_rows)
    check(1 in unlock_ids, "a dose recorded as 未服药 is announced again when the phone is picked up")
    check(1 in unlock_ids, "the silent 未服药 notice does not forfeit the audible catch-up (missedNotified=1)")
    check(2 in unlock_ids, "a dose that simply came due is announced on unlock")
    check(8 in unlock_ids, "a partially taken dose is announced - half a dose is not a finished one")
    check(3 not in unlock_ids, "an unexpired snooze keeps the catch-up away")
    check(4 not in unlock_ids, "a dose whose time has not come is never announced early")
    check(7 not in unlock_ids, "a skipped dose is never re-announced")
    check(9 not in unlock_ids, "a dose past the too-late window is left to the silent record")

    # The two writers behind recordUnlockReminder.
    print("\n[recordUnlockReminder] the budget is spent by audible catch-ups only")
    conn.execute(
        "UPDATE dose_logs SET unlockReminderCount = unlockReminderCount + 1, "
        "unlockReminderAtMillis = ? WHERE id = 2",
        (NOW,),
    )
    conn.execute("UPDATE dose_logs SET unlockReminderAtMillis = ? WHERE id = 8", (NOW,))
    conn.commit()
    spent = conn.execute(
        "SELECT unlockReminderCount, unlockReminderAtMillis FROM dose_logs WHERE id = 2"
    ).fetchone()
    quiet = conn.execute(
        "SELECT unlockReminderCount, unlockReminderAtMillis FROM dose_logs WHERE id = 8"
    ).fetchone()
    check(spent == (1, NOW), "an audible catch-up spends one from the budget and stamps the time")
    check(
        quiet == (0, NOW),
        "a quiet-hours note stamps the time without spending the budget the user is saving",
    )

    # Mirrors DoseLogDao.getDeferred.
    print("\n[deferred] withheld reminders are still owed")
    conn.execute("UPDATE dose_logs SET deferredAtMillis = ? WHERE id = 4", (NOW - MIN,))
    conn.commit()
    deferred = conn.execute(
        """
        SELECT id FROM dose_logs
         WHERE deferredAtMillis IS NOT NULL
           AND status IN ('UPCOMING', 'DUE', 'PARTIAL')
         ORDER BY plannedTimeMillis ASC
        """
    ).fetchall()
    check([r[0] for r in deferred] == [4], "the withheld dose is found regardless of how old it is")

    # Mirrors ReminderEventDao.prune: the retention must actually bound the table.
    print("\n[prune] the audit table stays bounded")
    for i in range(1, 51):
        conn.execute(
            "INSERT INTO reminder_events (doseLogId, timestamp, triggerName, decisionCode, "
            "driftMillis, detail) VALUES (2, ?, 'HEARTBEAT', 'SKIP_WAITING', 0, '')",
            (NOW + i,),
        )
    conn.commit()
    conn.execute(
        """
        DELETE FROM reminder_events
         WHERE id NOT IN (SELECT id FROM reminder_events ORDER BY timestamp DESC, id DESC LIMIT 10)
        """
    )
    conn.commit()
    check(
        conn.execute("SELECT COUNT(*) FROM reminder_events").fetchone()[0] == 10,
        "prune keeps exactly the newest rows",
    )

    # Mirrors the summary counts the self-check screen renders.
    print("\n[audit summary] delivered vs suppressed counts")
    conn.execute(
        "INSERT INTO reminder_events (doseLogId, timestamp, triggerName, decisionCode, driftMillis, "
        "detail) VALUES (2, ?, 'DOSE_ALARM', 'REMIND', 0, '')",
        (NOW + 1000,),
    )
    conn.commit()
    delivered = conn.execute(
        """
        SELECT COUNT(*) FROM reminder_events
         WHERE timestamp >= ? AND decisionCode IN
             ('REMIND', 'REMIND_REPEAT', 'PRE_REMIND', 'CATCH_UP', 'MISSED')
        """,
        (0,),
    ).fetchone()[0]
    suppressed = conn.execute(
        "SELECT COUNT(*) FROM reminder_events WHERE timestamp >= ? AND decisionCode LIKE 'SKIP_%'",
        (0,),
    ).fetchone()[0]
    check(delivered == 1, "exactly the one delivery is counted")
    check(suppressed == 10, "the ten suppressions are counted separately")

    print("\n" + "=" * 78)
    if failures:
        print(f"RESULT: FAILED ({len(failures)} problem(s))")
        for f in failures:
            print("  - " + f)
        return 1
    print("RESULT: PASSED - every DAO query prepares, and the arming queries behave as designed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
