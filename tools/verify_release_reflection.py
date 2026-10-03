#!/usr/bin/env python3
"""Checks that the *release* APK can still map JSON by reflection.

Why this exists: R8 removes @SerializedName annotations and renames fields, because nothing in the code
references them at runtime - Gson does, through reflection. When that happens every parse returns an
object of nulls, no exception is thrown anywhere, and the feature reports a plausible but wrong failure.
It shipped once (v1.8.0: the update check said "network problem" on release builds while the identical
debug build worked), so it now gets a check that reads the actual artifact.

Usage:

    python tools/verify_release_reflection.py [path/to/app-release.apk]

Exits non-zero when a required annotation value or DTO class is missing from the dex.
"""

from __future__ import annotations

import pathlib
import re
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_APK = ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
SOURCE_ROOT = ROOT / "app" / "src" / "main" / "java" / "com" / "meditrack"

# Classes Gson instantiates reflectively, for the update check and for backups.
DTO_CLASSES = [
    "GitHubRelease", "GitHubAsset", "VersionManifest",
    "BackupFile", "MedicationDto", "ScheduleDto", "DoseLogDto", "DoseEventDto", "SettingsDto",
]


def serialized_names() -> list[str]:
    """Every @SerializedName value in the app's JSON DTOs, read straight from the sources."""
    names: set[str] = set()
    pattern = re.compile(r'@SerializedName\("([^"]+)"\)')
    for path in SOURCE_ROOT.rglob("*.kt"):
        for match in pattern.finditer(path.read_text(encoding="utf-8")):
            names.add(match.group(1))
    return sorted(names)


def dex_bytes(apk: pathlib.Path) -> bytes:
    with zipfile.ZipFile(apk) as archive:
        return b"".join(name and archive.read(name) for name in archive.namelist() if name.endswith(".dex"))


def main(argv: list[str]) -> int:
    apk = pathlib.Path(argv[1]) if len(argv) > 1 else DEFAULT_APK
    if not apk.exists():
        print("no APK at " + str(apk) + " - build the release variant first", file=sys.stderr)
        return 2

    dex = dex_bytes(apk)
    names = serialized_names()
    print("checking " + apk.name + " (" + str(len(dex)) + " bytes of dex, " + str(len(names)) + " annotated keys)")

    missing_names = [n for n in names if n.encode() not in dex]
    missing_classes = [c for c in DTO_CLASSES if c.encode() not in dex]

    if missing_names:
        print("")
        print("MISSING @SerializedName values - Gson will map nothing for these:", file=sys.stderr)
        for name in missing_names:
            print("  - " + name, file=sys.stderr)
    if missing_classes:
        print("")
        print("MISSING DTO class names - Gson cannot instantiate them:", file=sys.stderr)
        for name in missing_classes:
            print("  - " + name, file=sys.stderr)

    if missing_names or missing_classes:
        print("")
        print("Fix: keep the DTO packages and the annotation attributes in app/proguard-rules.pro.", file=sys.stderr)
        return 1

    print("ok: every annotated key and all DTO classes survive R8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
