#!/usr/bin/env python3
"""Writes docs/version.json - the update manifest the app reads first.

Why this file exists: api.github.com is an unreliable destination on some networks (commonly
throttled or reset), while GitHub Pages answers. The app therefore tries this manifest first and
falls back to the GitHub API, so the manifest must always describe the newest published release.

Run it after building a release, before pushing:

    python tools/update_version_manifest.py

It reads the version from app/build.gradle.kts, the name of the archived APK from releases/apk/,
and the release notes from releases/docs/, so the three cannot drift apart.
"""

from __future__ import annotations

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
GRADLE = ROOT / "app" / "build.gradle.kts"
APK_DIR = ROOT / "releases" / "apk"
NOTES_DIR = ROOT / "releases" / "docs"
OUT = ROOT / "docs" / "version.json"
REPO = "https://github.com/ChenyangLT/meditrack"


def version_name() -> str:
    text = GRADLE.read_text(encoding="utf-8")
    match = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    if not match:
        raise SystemExit("versionName not found in app/build.gradle.kts")
    return match.group(1)


def main() -> int:
    version = version_name()
    tag = f"v{version}"

    apks = sorted(APK_DIR.glob(f"MediTrack-release-{version}-*.apk"))
    apk_url = None
    if apks:
        apk_url = f"{REPO}/releases/download/{tag}/{apks[-1].name}"
    else:
        print(f"warning: no archived APK for {version}; apkUrl will be null", file=sys.stderr)

    notes = NOTES_DIR / f"药准时-{version}-发布说明.md"
    body = notes.read_text(encoding="utf-8") if notes.exists() else ""

    manifest = {
        "schemaVersion": 1,
        "version": version,
        "tagName": tag,
        "releaseUrl": f"{REPO}/releases/tag/{tag}",
        "apkUrl": apk_url,
        "body": body,
    }
    OUT.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {OUT.relative_to(ROOT)} for {tag} (apk: {apks[-1].name if apks else 'none'})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
