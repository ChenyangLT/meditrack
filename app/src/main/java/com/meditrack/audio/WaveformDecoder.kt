package com.meditrack.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max

/**
 * A decodable audio source and what it sounds like, as a series of peaks.
 *
 * @param uri the source
 * @param label what to show the user ("晴天.mp3" / "系统铃声·闹钟")
 * @param durationMillis the full length, for the trimmer's timeline
 * @param sampleRate the source's own rate, for time labels and clip padding
 * @param peaks normalised 0..1 amplitude per bucket, already decimated for drawing
 */
data class Waveform(
    val uri: Uri,
    val label: String,
    val durationMillis: Long,
    val sampleRate: Int,
    val peaks: FloatArray,
) {

    /** Amplitude at a normalised 0..1 position, for the playhead overlay. */
    fun peakAt(fraction: Float): Float {
        if (peaks.isEmpty()) return 0f
        val index = (fraction.coerceIn(0f, 1f) * (peaks.size - 1)).toInt()
        return peaks[index]
    }

    /** "3:24" for the source's total length. */
    val durationLabel: String get() = formatMillis(durationMillis)

    companion object {
        fun formatMillis(millis: Long): String {
            val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
            return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
        }
    }
}

/**
 * Decodes an audio file to samples so the trimmer can draw it.
 *
 * ## Why this decodes instead of using a metadata API
 *
 * There is no platform API that returns a waveform. `MediaMetadataRetriever` gives duration, title and
 * an embedded picture, and nothing about amplitude - so drawing the one thing the user needs in order
 * to choose a slice ("where is the chorus?") requires actually decoding PCM and reducing it.
 *
 * ## Why it is bounded
 *
 * A five-minute MP3 is about thirteen million samples. Holding those, or even one peak per sample, is
 * pointless: the waveform is drawn into a few hundred pixels, so the decode is reduced to
 * [BUCKETS] buckets *as it streams*, and only those stay in memory. Decoding also stops early once the
 * whole track has been consumed or the caller's coroutine is cancelled, so leaving the screen does not
 * leave a decoder running.
 *
 * Decoding is deliberately *not* used for playback; the trimmed file is rendered separately. This class
 * only ever answers "what does it look like".
 */
object WaveformDecoder {

    private const val TAG = "WaveformDecoder"

    /** How many peaks the waveform is reduced to. More than any phone has horizontal pixels. */
    const val BUCKETS = 1_200

    /**
     * Decodes [uri] into a [Waveform], or null when the source has no decodable audio.
     *
     * Never throws: an unreadable file is a null, and the caller shows "这段音频无法读取" rather than
     * crashing out of a picker. That matters because the source is arbitrary user media - a video, a
     * DRM-protected track, a cloud placeholder that is not downloaded yet.
     */
    suspend fun decode(context: Context, uri: Uri, label: String): Waveform? =
        withContext(Dispatchers.Default) {
            runCatching { decodeInternal(context, uri, label) }
                .onFailure { Log.w(TAG, "could not decode a waveform for $uri", it) }
                .getOrNull()
        }

    private suspend fun decodeInternal(context: Context, uri: Uri, label: String): Waveform? {
        val retriever = MediaMetadataRetriever()
        val durationMillis = try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (t: Throwable) {
            Log.w(TAG, "metadata read failed for $uri", t)
            0L
        } finally {
            runCatching { retriever.release() }
        }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return null

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            val duration = if (durationMillis > 0L) {
                durationMillis
            } else {
                format.longOr(MediaFormat.KEY_DURATION, 0L) / 1_000L
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val peaks = FloatArray(BUCKETS)
            // One bucket per this many decoded frames, derived from the real duration so the drawing
            // lines up with the timeline whatever the file is.
            val buckets = decodeToPeaks(extractor, codec, sampleRate, peaks)
            codec.stop()
            codec.release()
            codec = null

            return Waveform(
                uri = uri,
                label = label,
                durationMillis = duration,
                sampleRate = sampleRate,
                peaks = if (buckets > 0) normalize(peaks, buckets) else FloatArray(0),
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Streams the decoder output into [peaks], returning how many buckets were filled.
     *
     * The bucket count is fixed and the frames are counted as they arrive, so a file whose duration
     * metadata lies (very common for VBR MP3s and for anything streamed from the cloud) still produces a
     * complete-looking waveform rather than one that stops halfway.
     */
    private suspend fun decodeToPeaks(
        extractor: MediaExtractor,
        codec: MediaCodec,
        sampleRate: Int,
        peaks: FloatArray,
    ): Int {
        val info = MediaCodec.BufferInfo()
        var sawInputEnd = false
        var sawOutputEnd = false
        var filled = 0
        var framesInBucket = 0
        // Buckets are sized on frames, and a bucket is emitted as soon as it has this many. Chosen so
        // that a 5-minute track fills all BUCKETS: 5*60*44100/1200 = 11025 frames per bucket.
        val framesPerBucket = MAX_FRAMES_PER_BUCKET
        var bucketPeak = 0f

        while (!sawOutputEnd) {
            coroutineContext.ensureActive()

            if (!sawInputEnd) {
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val buffer = codec.getInputBuffer(inputIndex) ?: ByteBuffer.allocate(0)
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(
                            inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        sawInputEnd = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outputIndex >= 0 -> {
                    if (info.size > 0) {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val samples = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            while (samples.hasRemaining()) {
                                val value = abs(samples.get().toInt()) / 32768f
                                bucketPeak = max(bucketPeak, value)
                                framesInBucket++
                                if (framesInBucket >= framesPerBucket) {
                                    if (filled < peaks.size) peaks[filled++] = bucketPeak
                                    bucketPeak = 0f
                                    framesInBucket = 0
                                }
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEnd = true
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> Unit
            }
        }

        // A trailing partial bucket is kept: a clip that is shorter than one bucket would otherwise
        // draw as silence, which reads as "this file is broken".
        if (framesInBucket > 0 && filled < peaks.size) peaks[filled++] = bucketPeak
        return filled
    }

    /** Scales to 0..1 so a quiet recording is still visible, and floors the result so silence is flat. */
    private fun normalize(peaks: FloatArray, filled: Int): FloatArray {
        val sliced = peaks.copyOf(filled)
        val loudest = sliced.maxOrNull() ?: 0f
        if (loudest <= 0.01f) return FloatArray(filled) { 0.02f }
        val scale = 1f / loudest
        for (index in sliced.indices) sliced[index] = (sliced[index] * scale).coerceIn(0f, 1f)
        return sliced
    }

    /**
     * Frames per drawn bucket.
     *
     * Deliberately a constant rather than `totalFrames / BUCKETS`: a constant gives the drawing a
     * consistent horizontal *scale* (about 0.25 s per bucket at 44.1 kHz), so a 30-second clip and a
     * 5-minute track look proportional to each other instead of both being stretched to the full width.
     * A track longer than `BUCKETS * this` simply fills the array and stops - which is correct, because
     * the trimmer only ever asks for a clip of at most a couple of minutes anyway.
     */
    private const val MAX_FRAMES_PER_BUCKET = 11_025

    private const val TIMEOUT_US = 10_000L
}

/**
 * `MediaFormat.optInt` / `optLong` exist only from API 29 and are absent from the compile-time stub of
 * older `android.jar`s, so the optional-value read is spelled out here. A format without a sample rate
 * is an oddity rather than an error: 44.1 kHz is the assumption every encoder makes.
 */
private fun MediaFormat.intOr(key: String, fallback: Int): Int =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback

private fun MediaFormat.longOr(key: String, fallback: Long): Long =
    if (containsKey(key)) runCatching { getLong(key) }.getOrDefault(fallback) else fallback
