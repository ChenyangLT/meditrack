package com.meditrack.data.backup

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * The rules behind the backup list and the "move my backups" step.
 *
 * Both are easy to get subtly wrong in I/O code, so they live in [BackupListing] and are pinned here:
 * which files count, what order they appear in, and - the one that can actually lose data - when a
 * file counts as already copied.
 */
class BackupListingTest {

    private fun entry(name: String, size: Long, modified: Long) =
        BackupEntry(name, size, modified, BackupHandle.Local(File(name)))

    @Test
    fun `only the app's own backup names are listed`() {
        assertThat(BackupListing.isJsonBackup("meditrack-backup-2026-10-03.json")).isTrue()
        assertThat(BackupListing.isJsonBackup("meditrack-backup-2026-10-03.JSON")).isTrue()
        assertThat(BackupListing.isJsonBackup("meditrack-2026-10-03.csv")).isFalse()
        assertThat(BackupListing.isJsonBackup("holiday-photo.json")).isFalse()
        assertThat(BackupListing.isJsonBackup("meditrack-backup-2026-10-03.json.bak")).isFalse()
    }

    @Test
    fun `csv exports are listed but never mistaken for a restorable backup`() {
        assertThat(BackupListing.isCsvExport("meditrack-2026-10-03.csv")).isTrue()
        assertThat(BackupListing.isCsvExport("meditrack-backup-2026-10-03.json")).isFalse()
        assertThat(BackupListing.isBackupOrExport("meditrack-2026-10-03.csv")).isTrue()
        assertThat(BackupListing.isBackupOrExport("meditrack-backup-2026-10-03.json")).isTrue()
        assertThat(BackupListing.isBackupOrExport("notes.txt")).isFalse()
        assertThat(BackupListing.isBackupOrExport("meditrack-notes.md")).isFalse()
    }

    @Test
    fun `the list is newest first`() {
        val sorted = BackupListing.newestFirst(
            listOf(
                entry("meditrack-backup-2026-10-01.json", 100, 1_000),
                entry("meditrack-backup-2026-10-03.json", 100, 3_000),
                entry("meditrack-backup-2026-10-02.json", 100, 2_000),
            ),
        )
        assertThat(sorted.map { it.name }).containsExactly(
            "meditrack-backup-2026-10-03.json",
            "meditrack-backup-2026-10-02.json",
            "meditrack-backup-2026-10-01.json",
        ).inOrder()
    }

    @Test
    fun `entries with no timestamp sort last and never jump around`() {
        // Some document providers report no LAST_MODIFIED; such a file must not masquerade as newest.
        val sorted = BackupListing.newestFirst(
            listOf(
                entry("meditrack-backup-unknown.json", 100, 0),
                entry("meditrack-backup-2026-10-02.json", 100, 2_000),
                entry("meditrack-backup-2026-10-01.json", 100, 1_000),
            ),
        )
        assertThat(sorted.first().name).isEqualTo("meditrack-backup-2026-10-02.json")
        assertThat(sorted.last().name).isEqualTo("meditrack-backup-unknown.json")
    }

    @Test
    fun `a file already in the target folder is not copied again`() {
        val target = listOf(entry("meditrack-backup-2026-10-03.json", 5_000, 1))
        assertThat(BackupListing.isPresent(target, entry("meditrack-backup-2026-10-03.json", 5_000, 9))).isTrue()
    }

    @Test
    fun `a same-named file with different contents is copied over`() {
        // Same day, different length: the older copy in the target must not shadow the newer source.
        val target = listOf(entry("meditrack-backup-2026-10-03.json", 4_000, 1))
        assertThat(BackupListing.isPresent(target, entry("meditrack-backup-2026-10-03.json", 5_000, 2))).isFalse()
    }

    @Test
    fun `an unknown size counts as absent, so a backup is never skipped by accident`() {
        // Providers that report no COLUMN_SIZE come back as 0. Re-copying is harmless; skipping a
        // backup the user just made is not.
        val target = listOf(entry("meditrack-backup-2026-10-03.json", 0, 1))
        assertThat(BackupListing.isPresent(target, entry("meditrack-backup-2026-10-03.json", 5_000, 2))).isFalse()
        assertThat(BackupListing.isPresent(target, entry("meditrack-backup-2026-10-03.json", 0, 2))).isFalse()
    }

    @Test
    fun `migration copies what is missing and skips what is already there`() {
        // The decision the migrate step makes, expressed with the pure helper it actually uses.
        val source = listOf(
            entry("meditrack-backup-2026-10-01.json", 100, 1_000),
            entry("meditrack-backup-2026-10-02.json", 200, 2_000),
            entry("meditrack-2026-10-02.csv", 50, 2_000),
        )
        val target = listOf(entry("meditrack-backup-2026-10-01.json", 100, 1_000))
        val toCopy = source.filterNot { BackupListing.isPresent(target, it) }
        assertThat(toCopy.map { it.name }).containsExactly(
            "meditrack-backup-2026-10-02.json",
            "meditrack-2026-10-02.csv",
        ).inOrder()
    }
}
