package com.meditrack.ui.ringtone

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meditrack.audio.AudioTrimmer
import com.meditrack.audio.TrimException
import com.meditrack.audio.Waveform
import com.meditrack.audio.WaveformDecoder
import com.meditrack.data.local.entity.RingClip
import com.meditrack.data.prefs.SettingsRepository
import com.meditrack.data.repository.RingClipRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Backs 裁剪铃声 - the waveform trimmer.
 *
 * ## What "trimming" means here
 *
 * The slice is *rendered* into a new file by [AudioTrimmer]; the source is never modified, and the
 * remembered start/end are metadata for display and for re-trimming later. So the whole screen is a
 * selection problem: show the audio, let the user mark two points, tell them exactly how long the
 * result will be.
 *
 * ## Why the selection is clamped in one place
 *
 * A clip shorter than [AudioTrimmer.MIN_CLIP_MILLIS] is a click rather than a ring, and one longer than
 * [AudioTrimmer.MAX_CLIP_MILLIS] is a song rather than a reminder - both are refused by the encoder, at
 * the end of a possibly slow export. [clampSelection] enforces both up front and is a pure function so
 * the rule itself is testable without a device.
 */
@HiltViewModel
class TrimViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ringClips: RingClipRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _waveform = MutableStateFlow<Waveform?>(null)
    val waveform: StateFlow<Waveform?> = _waveform.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _startMillis = MutableStateFlow(0L)
    val startMillis: StateFlow<Long> = _startMillis.asStateFlow()

    private val _endMillis = MutableStateFlow(0L)
    val endMillis: StateFlow<Long> = _endMillis.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playheadMillis = MutableStateFlow(0L)
    val playheadMillis: StateFlow<Long> = _playheadMillis.asStateFlow()

    /** 0..100 while exporting, null when idle - null is what disables the Save button. */
    private val _exportProgress = MutableStateFlow<Int?>(null)
    val exportProgress: StateFlow<Int?> = _exportProgress.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _savedClipId = MutableStateFlow<Long?>(null)
    val savedClipId: StateFlow<Long?> = _savedClipId.asStateFlow()

    private var previewPlayer: MediaPlayer? = null
    private var playheadJob: Job? = null

    override fun onCleared() {
        stopPreview()
        super.onCleared()
    }

    /**
     * Decodes [uri] into a drawable waveform and puts the selection at the start of the file.
     *
     * The window opens at [DEFAULT_WINDOW_MILLIS] rather than at the whole track: most sources are songs,
     * a whole song is far past the length cap, and "the first thirty seconds" is at least a working
     * starting point the user can drag. A file shorter than that is selected in full.
     */
    fun load(uri: Uri, label: String) {
        _isLoading.value = true
        _waveform.value = null
        // Loading a source starts a new visit to the trimmer: the previous visit's "saved" event belongs
        // to the previous visit and must not fire into this one.
        _savedClipId.value = null
        viewModelScope.launch {
            val decoded = WaveformDecoder.decode(context, uri, label)
            if (decoded == null || decoded.durationMillis <= 0L) {
                _message.value = "这段音频无法读取，请换一个文件（支持 MP3 / M4A / AAC / WAV / FLAC）"
                _isLoading.value = false
                return@launch
            }
            _waveform.value = decoded
            val (start, end) = clampSelection(
                startMillis = 0L,
                endMillis = minOf(decoded.durationMillis, DEFAULT_WINDOW_MILLIS),
                durationMillis = decoded.durationMillis,
            )
            _startMillis.value = start
            _endMillis.value = end
            _playheadMillis.value = start
            _isLoading.value = false

            // A trimmed ring only matters if the reminder is allowed to make a sound at all; saying so
            // here saves the user from wondering why a perfect clip never plays.
            runCatching { settings.current().soundEnabled }.getOrNull()?.let { enabled ->
                if (!enabled) {
                    _message.value = "提醒铃声的总开关目前是关闭的，保存后请到「设置 → 提醒铃声」里打开"
                }
            }
        }
    }

    // ------------------------------------------------------------ selection

    /**
     * Moves the start handle.
     *
     * The end is pinned first and the *range* is clamped second, so dragging the left handle left stops
     * at the length cap instead of dragging the right handle along with it - a handle under the finger
     * must stay under the finger.
     */
    fun setStart(millis: Long) {
        val end = _endMillis.value
        val lower = (end - AudioTrimmer.MAX_CLIP_MILLIS).coerceAtLeast(0L)
        val upper = (end - AudioTrimmer.MIN_CLIP_MILLIS).coerceAtLeast(0L)
        setSelection(millis.coerceIn(lower, upper), end)
    }

    /** Moves the end handle; symmetric with [setStart]. */
    fun setEnd(millis: Long) {
        val start = _startMillis.value
        val duration = _waveform.value?.durationMillis ?: return
        val lower = start + AudioTrimmer.MIN_CLIP_MILLIS
        val upper = (start + AudioTrimmer.MAX_CLIP_MILLIS).coerceAtMost(duration)
        setSelection(start, millis.coerceIn(lower, upper.coerceAtLeast(lower)))
    }

    /** Moves the start handle by [deltaMillis]; the senior-friendly equivalent of a precise drag. */
    fun nudgeStart(deltaMillis: Long) = setStart(_startMillis.value + deltaMillis)

    /** Moves the end handle by [deltaMillis]. */
    fun nudgeEnd(deltaMillis: Long) = setEnd(_endMillis.value + deltaMillis)

    /**
     * Slides the whole window, keeping its length.
     *
     * Used by a drag in the middle of the waveform. The window is pushed back inside the file rather
     * than clamped at the edge, so a drag that runs past the end does not shorten the selection the
     * user had just made.
     */
    fun moveWindowBy(deltaMillis: Long) {
        val duration = _waveform.value?.durationMillis ?: return
        val length = _endMillis.value - _startMillis.value
        val start = (_startMillis.value + deltaMillis).coerceIn(0L, (duration - length).coerceAtLeast(0L))
        setSelection(start, start + length)
    }

    private fun setSelection(start: Long, end: Long) {
        val duration = _waveform.value?.durationMillis ?: return
        val (safeStart, safeEnd) = clampSelection(start, end, duration)
        if (safeStart == _startMillis.value && safeEnd == _endMillis.value) return
        _startMillis.value = safeStart
        _endMillis.value = safeEnd
        // The playhead belongs to the selection: leaving it outside the new window would draw a line in
        // the dimmed area and read as "this part will be saved".
        if (_playheadMillis.value !in safeStart..safeEnd && !_isPlaying.value) {
            _playheadMillis.value = safeStart
        }
    }

    // ------------------------------------------------------------- preview

    /**
     * Plays *only* the selected slice.
     *
     * A short preview rather than a full player: the user is choosing twelve seconds out of a song, and
     * hearing the slice in place is the whole decision. Playback is stopped by a coroutine that watches
     * the playhead, because `MediaPlayer` has no "play from A to B" mode - seeking is the start, and the
     * end has to be enforced.
     *
     * Everything is created on the main dispatcher: the uri has already been decoded once for the
     * waveform, so it is a local, readable file and `prepare()` returns immediately; a player built on a
     * worker thread would push its completion callbacks onto a different looper than the UI.
     */
    fun previewSelection() {
        val waveform = _waveform.value ?: return
        stopPreview()
        val start = _startMillis.value
        val end = _endMillis.value
        runCatching {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, waveform.uri)
                prepare()
                seekTo(start.toInt())
            }
            player.start()
            previewPlayer = player
            _isPlaying.value = true
            _playheadMillis.value = start
            playheadJob = viewModelScope.launch {
                while (isActive) {
                    val position = runCatching { player.currentPosition.toLong() }.getOrDefault(end)
                    _playheadMillis.value = position.coerceIn(start, end)
                    if (!runCatching { player.isPlaying }.getOrDefault(false) || position >= end) break
                    delay(PLAYHEAD_POLL_MILLIS)
                }
                stopPreview()
            }
        }.onFailure {
            stopPreview()
            _message.value = "无法试听这段音频，可以跳过试听直接保存"
        }
    }

    fun stopPreview() {
        playheadJob?.cancel()
        playheadJob = null
        previewPlayer?.let { player ->
            runCatching { if (player.isPlaying) player.stop() }
            runCatching { player.release() }
        }
        previewPlayer = null
        if (_isPlaying.value) {
            _isPlaying.value = false
            _playheadMillis.value = _startMillis.value
        }
    }

    // ---------------------------------------------------------------- save

    /**
     * Renders the selection into the app's clip directory and registers it.
     *
     * The start/end are captured before the export starts, so a handle that is dragged mid-encode cannot
     * make the saved clip and the recorded `trimStartMillis` disagree about what was rendered. The new
     * clip becomes the global reminder sound, which is what the user was doing here in the first place.
     */
    fun save(name: String) {
        val waveform = _waveform.value ?: return
        if (_exportProgress.value != null) return
        val start = _startMillis.value
        val end = _endMillis.value
        stopPreview()
        viewModelScope.launch {
            _exportProgress.value = 0
            val destination = File(
                ringClips.clipDirectory,
                "clip_${System.currentTimeMillis()}$CLIP_SUFFIX",
            )
            runCatching {
                AudioTrimmer.trim(context, waveform.uri, start, end, destination) { percent ->
                    _exportProgress.value = percent
                }
            }.onSuccess { result ->
                val clip = RingClip(
                    name = name.trim().ifBlank { DEFAULT_CLIP_NAME },
                    filePath = result.file.absolutePath,
                    durationMillis = result.durationMillis,
                    sourceLabel = waveform.label,
                    sourceUri = waveform.uri.toString(),
                    trimStartMillis = start,
                    trimEndMillis = end,
                )
                runCatching { ringClips.addClip(clip, makeGlobal = true) }
                    .onSuccess { id ->
                        _message.value = "已保存「${clip.name}」并设为提醒铃声"
                        _savedClipId.value = id
                    }
                    .onFailure { failure ->
                        _message.value = failure.message ?: "保存铃声失败"
                    }
            }.onFailure { failure ->
                _message.value = when (failure) {
                    is TrimException -> failure.message ?: "裁剪失败"
                    else -> failure.message ?: "裁剪失败，请换一段音频试试"
                }
            }
            _exportProgress.value = null
        }
    }

    /** Clears the one-shot snackbar text once it has been shown. */
    fun onMessageShown() {
        _message.value = null
    }

    companion object {
        /**
         * Length of the window a freshly opened file starts with.
         *
         * Thirty seconds is longer than any ring needs and short enough to be a plausible selection, so
         * the user either keeps it or drags one handle - rather than being handed a five-minute window
         * that the encoder would refuse.
         */
        const val DEFAULT_WINDOW_MILLIS = 30_000L

        const val CLIP_SUFFIX = ".m4a"
        const val DEFAULT_CLIP_NAME = "自定义铃声"

        private const val PLAYHEAD_POLL_MILLIS = 50L

        /**
         * Forces a selection inside the file and inside the encoder's limits.
         *
         * Pure and total: every input, including a zero-length file or a reversed window, produces a
         * selection the trimmer accepts. That is the point - the UI has three drag surfaces and four
         * nudge buttons, and none of them should be able to produce a window the encoder rejects at the
         * end of a long export.
         *
         * @return the start and end, in milliseconds, with
         *         `0 <= start < end <= duration` and `end - start` inside
         *         `[MIN_CLIP_MILLIS, MAX_CLIP_MILLIS]`
         */
        fun clampSelection(startMillis: Long, endMillis: Long, durationMillis: Long): Pair<Long, Long> {
            val duration = durationMillis.coerceAtLeast(AudioTrimmer.MIN_CLIP_MILLIS)
            var start = startMillis.coerceIn(0L, (duration - AudioTrimmer.MIN_CLIP_MILLIS).coerceAtLeast(0L))
            var end = endMillis.coerceIn(start + AudioTrimmer.MIN_CLIP_MILLIS, duration)
            if (end - start > AudioTrimmer.MAX_CLIP_MILLIS) end = start + AudioTrimmer.MAX_CLIP_MILLIS
            if (end > duration) {
                // Only reachable when the file itself is shorter than the cap, in which case the whole
                // file is the selection.
                end = duration
                start = (end - AudioTrimmer.MAX_CLIP_MILLIS).coerceAtLeast(0L)
            }
            return start to end
        }
    }
}
