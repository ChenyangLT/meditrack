package com.meditrack.ui.ringtone

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meditrack.audio.AudioTrimmer
import com.meditrack.data.local.entity.RingClip
import com.meditrack.data.prefs.SettingsRepository
import com.meditrack.data.repository.MedicationRepository
import com.meditrack.data.repository.RingClipRepository
import com.meditrack.domain.reminder.ReminderSoundPlayer
import com.meditrack.domain.reminder.ReminderTone
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * One entry of the phone's own ringtone list.
 *
 * @param uri the `content://` uri the system would hand to a notification channel
 * @param title what Settings shows ("Morning Glory", "Chime")
 * @param kind which list it came from, so the same file appearing as both an alarm and a ringtone is
 *        still two distinguishable rows - the user is choosing a *sound*, and the list they found it in
 *        is how they find it again
 */
data class SystemRingtone(
    val uri: String,
    val title: String,
    val kind: String,
)

/**
 * Backs 提醒铃声 - both the global sound and, when opened from a medication, that medication's override.
 *
 * ## Why the picker copies instead of pointing at the user's file
 *
 * The reminder fires from a background service at 6am, which is the one place that cannot show a
 * permission dialog. A `content://` grant from the system picker can be gone by then (some ROMs revoke
 * it when the media store is rebuilt) and the source track can be deleted by a music app. Every import
 * therefore *copies* the audio into the app's own directory, and the original is only remembered as
 * metadata for display. That is also why deleting a clip is refused while something still uses it.
 *
 * ## Why imports are bounded
 *
 * `openInputStream` on a media uri reads a whole song; a 200 MB lossless file copied into app storage
 * would be a silent way to fill the user's phone, and the copy would be pointless - a reminder tone is
 * seconds long. [MAX_IMPORT_BYTES] turns that into a sentence instead.
 */
@HiltViewModel
class RingtonePickerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ringClips: RingClipRepository,
    private val settings: SettingsRepository,
    private val soundPlayer: ReminderSoundPlayer,
    private val medications: MedicationRepository,
) : ViewModel() {

    private val _clips = MutableStateFlow<List<RingClip>>(emptyList())
    val clips: StateFlow<List<RingClip>> = _clips.asStateFlow()

    private val _selectedClipId = MutableStateFlow<Long?>(null)
    val selectedClipId: StateFlow<Long?> = _selectedClipId.asStateFlow()

    private val _selectedTone = MutableStateFlow(ReminderTone.DEFAULT)
    val selectedTone: StateFlow<ReminderTone> = _selectedTone.asStateFlow()

    /** The medication override, non-null only after [loadFor] found one. */
    private val _customRingClipId = MutableStateFlow<Long?>(null)
    val customRingClipId: StateFlow<Long?> = _customRingClipId.asStateFlow()

    private val _medicationName = MutableStateFlow<String?>(null)
    val medicationName: StateFlow<String?> = _medicationName.asStateFlow()

    private val _systemRingtones = MutableStateFlow<List<SystemRingtone>>(emptyList())
    val systemRingtones: StateFlow<List<SystemRingtone>> = _systemRingtones.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refresh()
    }

    override fun onCleared() {
        soundPlayer.stop()
        super.onCleared()
    }

    /**
     * Re-reads the selection and the clip list.
     *
     * The per-medication override is *not* re-read here on purpose: it is read once by [loadFor] and
     * then held, so a `refresh()` triggered by an import cannot make the row flip back to the global
     * sound while the user is still deciding.
     */
    fun refresh() {
        viewModelScope.launch {
            runCatching {
                val prefs = settings.current()
                _selectedTone.value = ReminderTone.fromName(prefs.reminderTone)
                _selectedClipId.value = prefs.ringClipId
                // Recomputed before the list is read, so the "正在使用" badge reflects the selection
                // this screen is about to show rather than the one from the last time it was opened.
                ringClips.refreshInUse()
                _clips.value = ringClips.all()
            }.onFailure { _message.value = "读取铃声失败，请稍后再试" }

            if (_systemRingtones.value.isEmpty()) {
                // Enumerating the phone's ringtones touches MediaStore once per entry, so it is done off
                // the main thread and only once per screen; an empty result (a phone with no ringtones at
                // all) simply hides the section.
                _systemRingtones.value = withContext(Dispatchers.IO) { enumerateSystemRingtones() }
            }
        }
    }

    /**
     * Points the picker at one medication's override, or at the global sound when [medicationId] is null.
     *
     * Callable from `LaunchedEffect` after the ViewModel exists, which is why the target is a function
     * rather than a constructor argument: the same screen serves 设置 (global) and a medication.
     */
    fun loadFor(medicationId: Long?) {
        if (medicationId == null || medicationId <= 0L) {
            _customRingClipId.value = null
            _medicationName.value = null
            return
        }
        viewModelScope.launch {
            val medication = runCatching { medications.getWithSchedules(medicationId)?.medication }
                .getOrNull()
            _customRingClipId.value = medication?.customRingClipId
            _medicationName.value = medication?.name
        }
    }

    // ------------------------------------------------------------- preview

    /** Auditions a clip through the same player the reminder uses, so it sounds like the real thing. */
    fun previewClip(clip: RingClip) {
        if (!File(clip.filePath).exists()) {
            _message.value = "找不到这个铃声的文件，可能已被清理"
            return
        }
        soundPlayer.play(_selectedTone.value, clip.filePath)
    }

    fun previewTone(tone: ReminderTone) {
        soundPlayer.play(tone, null)
    }

    fun stopPreview() {
        soundPlayer.stop()
    }

    // ------------------------------------------------------------ selection

    /**
     * Selects a clip as the global reminder sound.
     *
     * The bundled tone is deliberately left alone: it is only consulted when there is no clip, so
     * clearing it here would lose the user's tone choice for the day they delete the clip again.
     */
    fun selectClip(id: Long?) {
        viewModelScope.launch {
            stopPreview()
            runCatching { ringClips.selectGlobal(id) }
                .onFailure { _message.value = "保存选择失败，请重试" }
            _selectedClipId.value = id
            refresh()
        }
    }

    /**
     * Selects one of the five bundled tones.
     *
     * Clearing the global clip is not optional: resolution prefers any clip over the tone, so leaving
     * one selected would make this row look selected while the reminder kept playing the old clip.
     */
    fun selectTone(tone: ReminderTone) {
        viewModelScope.launch {
            stopPreview()
            runCatching {
                settings.setReminderTone(tone.name)
                ringClips.selectGlobal(null)
            }.onFailure { _message.value = "保存选择失败，请重试" }
            _selectedTone.value = tone
            _selectedClipId.value = null
            refresh()
        }
    }

    fun renameClip(id: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _message.value = "名字不能为空"
            return
        }
        viewModelScope.launch {
            runCatching { ringClips.rename(id, trimmed) }
                .onSuccess { refresh() }
                .onFailure { _message.value = "改名失败，请重试" }
        }
    }

    /**
     * Deletes a clip.
     *
     * The repository refuses while the clip is still selected, and that refusal is reported instead of
     * hidden: "I tidied up my ringtones and the reminder stopped ringing" is a bug the user cannot
     * trace, so the app says why it will not delete it.
     */
    fun deleteClip(id: Long) {
        viewModelScope.launch {
            stopPreview()
            val deleted = runCatching { ringClips.delete(id) }.getOrDefault(false)
            if (deleted) {
                refresh()
            } else {
                _message.value = "这个铃声正在使用，先换一个再删除"
            }
        }
    }

    // --------------------------------------------------------------- import

    /**
     * Imports a file the user picked, exactly as it is - no trimming.
     *
     * This is the "就用这一段" path, and it exists so that a user who already has the right 12 seconds
     * in a voice memo never has to open the trimmer.
     */
    fun importPickedAudio(uri: Uri, displayName: String) {
        viewModelScope.launch {
            when (val outcome = copyIntoClipDirectory(uri)) {
                is ImportOutcome.Copied -> registerClip(
                    file = outcome.file,
                    name = displayName.substringBeforeLast('.', displayName),
                    sourceLabel = displayName,
                    sourceUri = uri.toString(),
                )
                ImportOutcome.TooLarge ->
                    _message.value = "文件太大（超过 ${MAX_IMPORT_BYTES / 1024 / 1024} MB），请选一段短一点的音频"
                ImportOutcome.Failed ->
                    _message.value = "读不到这个文件，请重新选择一次"
            }
        }
    }

    /**
     * Imports one of the phone's own ringtones.
     *
     * `sourceUri` is null and the label says where it came from, because a system ringtone cannot be
     * re-opened in the trimmer: it is not a file the user can scrub through, and offering a trim button
     * on it would be a dead end. It is copied whole by design.
     */
    fun importSystemRingtone(ringtone: SystemRingtone) {
        viewModelScope.launch {
            when (val outcome = copyIntoClipDirectory(Uri.parse(ringtone.uri))) {
                is ImportOutcome.Copied -> registerClip(
                    file = outcome.file,
                    name = ringtone.title,
                    sourceLabel = "系统铃声·${ringtone.title}",
                    sourceUri = null,
                )
                ImportOutcome.TooLarge ->
                    _message.value = "这个铃声太大（超过 ${MAX_IMPORT_BYTES / 1024 / 1024} MB），换一个试试"
                ImportOutcome.Failed ->
                    _message.value = "读不到这个系统铃声，换一个试试"
            }
        }
    }

    /** Registers a copied file as a clip and makes it the global sound. */
    private suspend fun registerClip(
        file: File,
        name: String,
        sourceLabel: String,
        sourceUri: String?,
    ) {
        // The real length is read back from the file rather than trusted from the caller: a media uri
        // whose metadata lies is common enough that "0:00" next to a working ringtone would look broken.
        val duration = withContext(Dispatchers.IO) { AudioTrimmer.readDurationMillis(file) }
        if (duration <= 0L) {
            withContext(Dispatchers.IO) { file.delete() }
            _message.value = "这个音频读不出声音，换一个试试（支持 MP3 / M4A / AAC / WAV / FLAC）"
            return
        }
        val clip = RingClip(
            name = name.trim().ifBlank { DEFAULT_CLIP_NAME },
            filePath = file.absolutePath,
            durationMillis = duration,
            sourceLabel = sourceLabel,
            sourceUri = sourceUri,
            trimStartMillis = 0L,
            trimEndMillis = duration,
        )
        runCatching { ringClips.addClip(clip, makeGlobal = true) }
            .onSuccess {
                _message.value = "已添加「${clip.name}」并设为提醒铃声"
                refresh()
            }
            .onFailure { failure ->
                withContext(Dispatchers.IO) { file.delete() }
                _message.value = failure.message ?: "保存铃声失败"
            }
    }

    // ------------------------------------------------------------ internals

    private sealed interface ImportOutcome {
        class Copied(val file: File) : ImportOutcome
        object TooLarge : ImportOutcome
        object Failed : ImportOutcome
    }

    /**
     * Copies the whole stream into the clip directory.
     *
     * The suffix is always `.m4a` whatever the source container was: both `MediaPlayer` and Media3
     * sniff the actual container from the bytes, so the extension is a hint for the file browser rather
     * than something playback depends on, and guessing it from a uri (`content://media/.../1234`) is
     * not possible in general.
     */
    private suspend fun copyIntoClipDirectory(uri: Uri): ImportOutcome = withContext(Dispatchers.IO) {
        val destination = File(ringClips.clipDirectory, "clip_${System.currentTimeMillis()}$CLIP_SUFFIX")
        runCatching {
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext ImportOutcome.Failed
            input.use { source ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_IMPORT_BYTES) {
                            destination.delete()
                            return@withContext ImportOutcome.TooLarge
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (destination.length() <= 0L) {
                destination.delete()
                ImportOutcome.Failed
            } else {
                ImportOutcome.Copied(destination)
            }
        }.getOrElse {
            runCatching { destination.delete() }
            ImportOutcome.Failed
        }
    }

    /**
     * Enumerates the phone's alarm, notification and ringtone sounds.
     *
     * Three lists rather than one because a user looking for "the beep my phone makes" knows whether it
     * is the alarm or the message sound, and the same file can legitimately appear in more than one.
     * Wrapped in `runCatching` throughout: a vendor ROM with a malformed MediaStore entry must not be
     * able to take down the settings screen.
     */
    private fun enumerateSystemRingtones(): List<SystemRingtone> = runCatching {
        val manager = RingtoneManager(context)
        val found = LinkedHashMap<String, SystemRingtone>()
        for ((type, kind) in SYSTEM_RINGTONE_TYPES) {
            // setType, not a `ringtoneType` property: the platform class exposes only the setter, so the
            // synthetic property does not resolve in Kotlin.
            manager.setType(type)
            val cursor = manager.cursor ?: continue
            cursor.use { entries ->
                for (position in 0 until entries.count) {
                    if (!entries.moveToPosition(position)) continue
                    val uri = runCatching { manager.getRingtoneUri(position) }.getOrNull() ?: continue
                    val key = "$uri|$kind"
                    if (found.containsKey(key)) continue
                    val title = runCatching {
                        RingtoneManager.getRingtone(context, uri)?.getTitle(context)
                    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "未命名铃声"
                    found[key] = SystemRingtone(uri = uri.toString(), title = title, kind = kind)
                }
            }
        }
        found.values.toList()
    }.getOrDefault(emptyList())

    /** Clears the one-shot snackbar text once it has been shown. */
    fun onMessageShown() {
        _message.value = null
    }

    private companion object {
        const val CLIP_SUFFIX = ".m4a"
        const val DEFAULT_CLIP_NAME = "自定义铃声"

        /**
         * Ceiling on an import.
         *
         * Twenty megabytes is roughly twenty minutes of AAC - far more than any ringtone needs, and
         * small enough that a mis-tap on a lossless album track wastes a second rather than the user's
         * storage. The check happens while copying, so an oversized file never lands on disk in full.
         */
        const val MAX_IMPORT_BYTES = 20L * 1024L * 1024L

        /** The three system lists that can contain a sound worth using as a reminder. */
        val SYSTEM_RINGTONE_TYPES = listOf(
            RingtoneManager.TYPE_ALARM to "闹钟",
            RingtoneManager.TYPE_NOTIFICATION to "通知",
            RingtoneManager.TYPE_RINGTONE to "来电",
        )
    }
}
