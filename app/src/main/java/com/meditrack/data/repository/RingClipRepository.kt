package com.meditrack.data.repository

import android.content.Context
import android.util.Log
import com.meditrack.data.local.dao.MedicationDao
import com.meditrack.data.local.dao.RingClipDao
import com.meditrack.data.local.entity.RingClip
import com.meditrack.data.prefs.SettingsRepository
import com.meditrack.data.prefs.UserPreferences
import com.meditrack.domain.reminder.ReminderTone
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The sound a reminder should actually play, already resolved.
 *
 * @param tone the bundled tone when [customPath] is null
 * @param customPath an absolute file path or `content://` uri, or null for the bundled tone
 * @param label what to show the user: "清铃" or the clip's name
 * @param clipId the row that produced [customPath], if any - kept so "which clip is selected" survives
 *        without a second lookup
 */
data class RingSource(
    val tone: ReminderTone,
    val customPath: String?,
    val label: String,
    val clipId: Long? = null,
)

/**
 * Everything about "what does the reminder sound like".
 *
 * ## Why a clip's file is separate from its row
 *
 * The row is the *reference* (name, duration, which source it came from, whether anything still uses
 * it) and the file is the *audio*. [selected] wins immediately; every other file is a cache candidate.
 * Keeping those two facts apart is what makes 「清除缓存」 safe: it can delete files whose rows nothing
 * references, while a clip that is merely deselected but still listed survives until the user says
 * otherwise.
 *
 * ## Why resolution is a single function
 *
 * Because there are three ways to have configured a sound - a bundled tone, a clip, and the legacy
 * `soundUri` an older build could store - and the notifier, the preview and the self-test must all
 * agree on which one wins. Any disagreement produces the worst possible bug in this app: the preview
 * rings and the reminder does not.
 */
@Singleton
class RingClipRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ringClipDao: RingClipDao,
    private val medicationDao: MedicationDao,
    private val settingsRepository: SettingsRepository,
) {

    /** The directory every rendered clip lives in, inside app-private storage. */
    val clipDirectory: File
        get() = File(context.filesDir, CLIP_DIRECTORY).apply { if (!exists()) mkdirs() }

    suspend fun all(): List<RingClip> = withContext(Dispatchers.IO) { ringClipDao.all() }

    suspend fun byId(id: Long): RingClip? = withContext(Dispatchers.IO) { ringClipDao.byId(id) }

    /**
     * The bundled tone plus the user's custom clip, in the order that resolves them.
     *
     * @param medicationClipId a per-medication override, which wins over the global choice - the same
     *        precedence [Medication.customSoundUri] always had.
     */
    suspend fun resolve(prefs: UserPreferences, medicationClipId: Long? = null): RingSource {
        val tone = ReminderTone.fromName(prefs.reminderTone)

        // A per-medication clip wins, then the global one, then the legacy uri, then the bundled tone.
        val clip = medicationClipId?.let { byId(it) } ?: prefs.ringClipId?.let { byId(it) }
        if (clip != null && File(clip.filePath).exists()) {
            return RingSource(tone = tone, customPath = clip.filePath, label = clip.name, clipId = clip.id)
        }
        if (clip != null) {
            // The row survived but its file did not: fall through rather than play nothing, and say so
            // in the log, because a missing clip file is the one way this feature fails silently.
            Log.w(TAG, "clip ${clip.id} (${clip.name}) has no file at ${clip.filePath}; using ${tone.label}")
        }
        if (!prefs.soundUri.isNullOrBlank()) {
            return RingSource(tone = tone, customPath = prefs.soundUri, label = "已选择的声音")
        }
        return RingSource(tone = tone, customPath = null, label = tone.label)
    }

    /**
     * Registers a freshly rendered clip and marks it in use.
     *
     * The previous selection stays in use only if a medication references it; the global switch moves to
     * the new clip. Files are never deleted here - that is [clearUnusedCache]'s job, so a mis-tap in the
     * picker cannot destroy the user's work before they have confirmed the new one works.
     */
    suspend fun addClip(clip: RingClip, makeGlobal: Boolean): Long = withContext(Dispatchers.IO) {
        val id = ringClipDao.insert(clip)
        if (makeGlobal) selectGlobal(id)
        id
    }

    /** Makes [id] the global reminder sound, keeping its file. */
    suspend fun selectGlobal(id: Long?) = withContext(Dispatchers.IO) {
        settingsRepository.setRingClipId(id)
        refreshInUse()
    }

    /** Makes [id] the sound for one medication. */
    suspend fun refreshInUse() = withContext(Dispatchers.IO) {
        val prefs = settingsRepository.current()
        val globalId = prefs.ringClipId
        val perMedication = perMedicationClipIds()
        ringClipDao.all().forEach { clip ->
            val used = clip.id == globalId || clip.id in perMedication
            ringClipDao.setInUse(clip.id, used)
        }
    }

    /** Renames a clip; the file is untouched. */
    suspend fun rename(id: Long, name: String) = withContext(Dispatchers.IO) { ringClipDao.rename(id, name) }

    /**
     * Deletes a clip row and its file.
     *
     * Refuses while the clip is still selected: deleting the sound a reminder is using would turn the
     * next dose silent, which the user cannot be expected to connect to "I tidied up my ringtones".
     * The settings screen surfaces the refusal as a message rather than hiding the button.
     */
    suspend fun delete(id: Long): Boolean = withContext(Dispatchers.IO) {
        val clip = ringClipDao.byId(id) ?: return@withContext false
        if (clip.inUse) return@withContext false
        ringClipDao.delete(id)
        runCatching { File(clip.filePath).delete() }
        true
    }

    /**
     * Deletes the audio of every clip nothing references any more, and returns what it freed.
     *
     * This is the "缓存" half of 清除缓存. Two things are deliberately *not* deleted: the source file the
     * user trimmed from (it is theirs, and it is not in our storage), and the row itself - so the picker
     * still lists it and the trimmer can re-render it from the remembered source in one tap.
     *
     * A clip whose row nothing references but which the user is actively auditioning would be deleted
     * here. That is accepted: the settings screen refreshes usage before offering the action, and the
     * re-render is one tap away.
     */
    suspend fun clearUnusedCache(): CacheClearResult = withContext(Dispatchers.IO) {
        refreshInUse()
        var files = 0
        var bytes = 0L
        for (clip in ringClipDao.unused()) {
            val file = File(clip.filePath)
            if (!file.exists()) continue
            val size = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) {
                files++
                bytes += size
            }
        }
        CacheClearResult(clipFiles = files, bytesFreed = bytes)
    }

    /**
     * Total size of every clip file currently on disk, for the "缓存占用" line.
     *
     * Reported in full rather than "unused only" because that is the number a user means by cache: they
     * want to know how much space this feature is costing them, not how much of it is rubbish.
     */
    suspend fun cacheSize(): Long = withContext(Dispatchers.IO) {
        ringClipDao.all().sumOf { clip -> File(clip.filePath).takeIf { it.exists() }?.length() ?: 0L }
    }

    /** How many clips exist, and how many are still referenced - the two numbers the cache row shows. */
    suspend fun counts(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val all = ringClipDao.all()
        all.size to all.count { it.inUse }
    }

    /**
     * The per-medication overrides.
     *
     * A narrow DAO query rather than `MedicationRepository`: that repository depends on this class to
     * resolve which sound to play, so depending on it back would be a cycle.
     */
    private suspend fun perMedicationClipIds(): Set<Long> =
        medicationDao.distinctCustomRingClipIds().filterNotNull().toSet()

    /** What a cache clear freed, for the confirmation message. */
    data class CacheClearResult(val clipFiles: Int, val bytesFreed: Long) {
        val isEmpty: Boolean get() = clipFiles == 0
    }

    private companion object {
        const val TAG = "RingClipRepository"
        const val CLIP_DIRECTORY = "ringtones"
    }
}
