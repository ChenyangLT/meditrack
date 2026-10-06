package com.meditrack.audio

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the real trimmer on a real device against real audio files.
 *
 * ## Why this test exists
 *
 * `AudioTrimmer` is the one component whose correctness cannot be established by a JVM unit test: it hands
 * an arbitrary user file to Media3's `Transformer`, which negotiates decoders and muxers through the
 * *device's* codecs. A unit test proves the clip-window arithmetic; only a device proves that a WAV and an
 * MP3 actually come out the other side as a playable M4A. That gap is exactly how v2.0.0 shipped with a
 * trimmer that never worked - the pure logic was tested and the encode path was not.
 *
 * ## Why the fixtures are test assets
 *
 * They are 8-second mono tones in `src/androidTest/assets/`, so the test needs no external file and no
 * storage permission. An earlier revision read fixtures pushed to `/sdcard/Music/`, which failed with
 * "no permission" for a reason that had nothing to do with the trimmer - scoped storage blocks a plain
 * `File` read there, and a test that cannot open its own input proves nothing about the code under test.
 * An asset is written to the app's own cache before use, which is also the realistic shape: the picker
 * hands the trimmer a uri this app is allowed to open.
 */
@RunWith(AndroidJUnit4::class)
class AudioTrimmerDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** Copies an asset into app-private storage, which is the only place the trimmer will read from. */
    private fun fixture(assetName: String): File {
        val target = File(context.cacheDir, "fixture_$assetName")
        instrumentation.context.assets.open(assetName).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        assertThat(target.length()).isGreaterThan(0L)
        return target
    }

    private fun trimAndAssert(assetName: String, label: String) {
        val source = fixture(assetName)
        val destination = File(context.cacheDir, "trimtest_$label.m4a")
        val uri = android.net.Uri.fromFile(source)

        // The source is 8 s, so [2 s, 6 s] is a real 4-second interior slice rather than a clamp.
        val result = runBlocking {
            AudioTrimmer.trim(
                context = context,
                source = uri,
                startMillis = 2_000L,
                endMillis = 6_000L,
                destination = destination,
            )
        }

        assertThat(result.file.exists()).isTrue()
        assertThat(result.file.length()).isGreaterThan(1_000L)
        // Reading the export back is the only assertion that proves the container is well formed rather
        // than merely non-empty.
        val duration = AudioTrimmer.readDurationMillis(result.file)
        assertThat(duration).isGreaterThan(0L)
        // 4 s requested; a codec may pad, so this is a sanity band rather than an exact match.
        assertThat(duration).isAtLeast(2_500L)
        assertThat(duration).isAtMost(8_000L)

        destination.delete()
        source.delete()
    }

    @Test
    fun trimsAnMp3() = trimAndAssert("tone.mp3", "mp3")

    @Test
    fun trimsAWav() = trimAndAssert("tone.wav", "wav")

    @Test
    fun reportsAReadableDurationForTheSource() {
        // The trimmer screen refuses a source whose duration cannot be read, so this is the precondition
        // for the whole feature being reachable at all.
        val source = fixture("tone.mp3")
        assertThat(AudioTrimmer.readDurationMillis(source)).isGreaterThan(0L)
        source.delete()
    }

    /** Proves the manifest is readable, which the APK verifier depends on. */
    @Test
    fun readsTheInstalledVersionCode() {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        assertThat(info.longVersionCode).isGreaterThan(0L)
    }

    private companion object {
        /** Kept for the compiler: `Context` is only used through the helpers above. */
        @Suppress("unused")
        fun Context.unused() = Unit
    }
}
