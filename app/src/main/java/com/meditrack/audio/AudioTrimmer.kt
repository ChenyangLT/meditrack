package com.meditrack.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * What the trimmer produced.
 *
 * @param file the rendered clip, inside the app's own storage
 * @param durationMillis its real length, read back from the file rather than trusted from the slider
 */
data class TrimResult(val file: File, val durationMillis: Long)

/** Why a trim failed, in words the user can act on. */
class TrimException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Renders the slice the user chose into a small file of its own.
 *
 * ## Why re-encode instead of remembering an offset
 *
 * Storing "play 0:41 to 0:53 of this song" and seeking at ring time is the cheaper design, and it is
 * wrong for the one situation the reminder exists in: a `content://` grant from the system picker can
 * be gone by the next morning, the source track can be moved by a music app, and the reminder fires
 * from a background service that cannot ask for permission to recover any of that. The clip is
 * therefore materialised as a file this app owns, after which nothing else on the phone can break the
 * reminder sound.
 *
 * ## Why Media3 does the encoding
 *
 * Decoding arbitrary user media and re-encoding it by hand means negotiating `MediaCodec`'s output
 * format, keyframe placement and codec-specific data per vendor - details that differ per phone, so a
 * mistake fails only on the user's device. Media3 owns that negotiation. It must run on a thread with a
 * `Looper` (its listeners are `Handler`-based), which is why the operation is marshalled to the main
 * dispatcher and bridged back into a coroutine.
 */
object AudioTrimmer {

    private const val TAG = "AudioTrimmer"

    /** Shortest clip the trimmer will produce; below this the ring is a click, not a sound. */
    const val MIN_CLIP_MILLIS = 500L

    /** Longest clip the trimmer will produce, so a "ringtone" cannot become a five-minute song. */
    const val MAX_CLIP_MILLIS = 60_000L

    /**
     * Renders [source] between [startMillis] and [endMillis] into [destination].
     *
     * @param onProgress called with 0..100 while exporting, so the screen can show a determinate bar
     *        instead of a spinner on a five-second encode
     * @throws TrimException with a user-facing reason
     */
    suspend fun trim(
        context: Context,
        source: Uri,
        startMillis: Long,
        endMillis: Long,
        destination: File,
        onProgress: (Int) -> Unit = {},
    ): TrimResult {
        val start = startMillis.coerceAtLeast(0L)
        val end = endMillis.coerceAtLeast(start + MIN_CLIP_MILLIS)
        if (end - start > MAX_CLIP_MILLIS) {
            throw TrimException("片段最长 ${MAX_CLIP_MILLIS / 1000} 秒，请缩短一点")
        }

        destination.parentFile?.mkdirs()
        // Media3 refuses to overwrite, and a half-written file from a cancelled attempt would make the
        // next one fail for a reason that has nothing to do with the user's input.
        if (destination.exists()) destination.delete()

        return withContext(Dispatchers.Main) {
            runCatching { render(context, source, start, end, destination, onProgress) }
                .getOrElse { failure ->
                    destination.delete()
                    Log.w(TAG, "trim failed for $source [$start, $end]", failure)
                    throw when (failure) {
                        is TrimException -> failure
                        is ExportException -> TrimException(explain(failure), failure)
                        else -> TrimException("裁剪失败：${failure.message ?: "未知原因"}", failure)
                    }
                }
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun render(
        context: Context,
        source: Uri,
        startMillis: Long,
        endMillis: Long,
        destination: File,
        onProgress: (Int) -> Unit,
    ): TrimResult = withContext(Dispatchers.Main) {
        // The clipping window belongs to the *media item*: Media3 applies it while reading the source, so
        // only the chosen slice is ever decoded. Building it this way - rather than through a setter on
        // the edited item - is what this Media3 version supports.
        val clippedSource = MediaItem.Builder()
            .setUri(source)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMillis)
                    .setEndPositionMs(endMillis)
                    .build()
            )
            .build()

        val item = EditedMediaItem.Builder(clippedSource)
            .setRemoveVideo(true)
            .build()

        // A composition with a single sequence rather than a bare item, so the muxer is explicitly asked
        // for an audio-only result. Audio is deliberately *not* transmuxed: re-encoding keeps the clip
        // small and makes the container predictable whatever the source was.
        val composition = Composition.Builder(EditedMediaItemSequence(listOf(item)))
            .setTransmuxVideo(false)
            .build()

        // Progress is polled rather than pushed: Transformer exposes it only through a holder, and a
        // fifth-of-a-second poll is far below what a progress bar can show anyway.
        val progressScope = CoroutineScope(coroutineContext)
        val progressHolder = ProgressHolder()
        var progressJob: Job? = null

        val result = suspendCancellableCoroutine { continuation ->
            val transformer = Transformer.Builder(context)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (continuation.isActive) {
                            val duration = readDurationMillis(destination)
                                .coerceAtLeast(MIN_CLIP_MILLIS)
                            continuation.resume(TrimResult(destination, duration))
                        }
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exception: ExportException,
                    ) {
                        if (continuation.isActive) continuation.resumeWithException(exception)
                    }
                })
                .build()

            transformer.start(composition, destination.absolutePath)

            progressJob = progressScope.launch {
                while (true) {
                    val state = runCatching { transformer.getProgress(progressHolder) }.getOrNull()
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(progressHolder.progress)
                    delay(PROGRESS_POLL_MILLIS)
                }
            }

            continuation.invokeOnCancellation {
                runCatching { transformer.cancel() }
                progressJob?.cancel()
                destination.delete()
            }
        }

        progressJob?.cancel()
        result
    }

    /** Reads a rendered clip's own length, so the picker's "0:12" is measured rather than assumed. */
    fun readDurationMillis(file: File): Long {
        if (!file.exists()) return 0L
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (t: Throwable) {
            Log.w(TAG, "could not read the duration of ${file.name}", t)
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Turns an export failure into something a person can act on.
     *
     * The raw codes are useless to a user and actively misleading
     * ("ERROR_CODE_DECODING_FORMAT_UNSUPPORTED" does not tell them to pick a different file), and the
     * handful of cases that actually happen deserve different advice.
     */
    private fun explain(exception: ExportException): String = when (exception.errorCode) {
        ExportException.ERROR_CODE_IO_FILE_NOT_FOUND -> "找不到这个音频文件，可能已被删除"
        ExportException.ERROR_CODE_IO_NO_PERMISSION -> "没有读取这个文件的权限，请重新选择一次"
        ExportException.ERROR_CODE_IO_UNSPECIFIED,
        ExportException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        -> "读取音频失败，如果是网盘里的文件请先下载到手机"
        ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        ExportException.ERROR_CODE_DECODER_INIT_FAILED,
        -> "这个格式无法解码，请换一个音频文件（支持 MP3 / M4A / AAC / WAV / FLAC）"
        ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
        ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
        -> "这台手机无法生成铃声文件，请换一个音频文件"
        else -> "裁剪失败：${exception.message ?: "未知原因"}"
    }

    private const val PROGRESS_POLL_MILLIS = 200L
}
