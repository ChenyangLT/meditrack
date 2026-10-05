package com.meditrack.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A reusable reminder sound the user created inside the app.
 *
 * ## Why the audio is copied rather than referenced
 *
 * The user picks a song from their library and trims a slice out of it. Keeping a reference to the
 * original would be cheaper by a few kilobytes and wrong in three ways that all end with a silent
 * reminder:
 *
 *  - a `content://` grant from the system picker is not guaranteed to outlive the process, and on
 *    several ROMs it is revoked the next time the media store is rebuilt;
 *  - the user may delete or move the source track, which turns the clip into a dangling uri;
 *  - the reminder is played by the app on the alarm stream, at 6am, from a service - the one place
 *    that cannot show a permission dialog to recover a missing grant.
 *
 * So [filePath] always points at a small file the app owns. The source is remembered purely as
 * metadata, so the trimmer can say where a clip came from and offer to re-trim it.
 */
@Entity(
    tableName = "ring_clips",
    indices = [Index(value = ["createdAt"])],
)
data class RingClip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,

    /** Name the user sees in the picker, e.g. "起床铃（副歌）". */
    val name: String,

    /**
     * Absolute path of the rendered clip inside the app's own files directory.
     *
     * A plain path rather than a uri on purpose: the file lives in app-private storage that only this
     * process can read, and a path can be checked for existence and deleted by the cache cleaner
     * without going through a ContentResolver.
     */
    val filePath: String,

    /** Length of the rendered clip in milliseconds, for the picker's "0:12" label. */
    val durationMillis: Long,

    /** Where the slice came from, for display only: "周杰伦 - 晴天.mp3" or "系统铃声·闹钟". */
    val sourceLabel: String,

    /**
     * The original uri, kept so the trimmer can be reopened on the same source.
     *
     * Null for a system ringtone, which is copied whole and never re-trimmed: the system ringtone
     * list is not a file the user can scrub through, so offering to trim it would be a dead end.
     */
    val sourceUri: String? = null,

    /** The slice the user selected within the source, or 0 / 0 for a whole-file import. */
    val trimStartMillis: Long = 0L,
    val trimEndMillis: Long = 0L,

    /**
     * True while the clip is referenced by any setting or medication.
     *
     * Denormalised rather than computed because the cache cleaner needs to decide file deletion
     * without loading every medication, and because a clip that the user deletes from the list must
     * survive on disk until the next cache clear if it is still selected - otherwise removing a
     * picker entry would silence every reminder.
     */
    val inUse: Boolean = false,

    val createdAt: Long = System.currentTimeMillis(),
) {

    /** "0:12" - the label shown next to a clip in the picker. */
    val durationLabel: String
        get() {
            val totalSeconds = (durationMillis / 1000L).coerceAtLeast(0L)
            return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
        }
}
