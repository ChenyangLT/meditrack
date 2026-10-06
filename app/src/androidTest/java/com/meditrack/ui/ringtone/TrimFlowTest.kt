package com.meditrack.ui.ringtone

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.meditrack.audio.AudioTrimmer
import com.meditrack.audio.Waveform
import com.meditrack.data.prefs.SettingsRepository
import com.meditrack.data.repository.RingClipRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.inject.Inject

/**
 * The 裁剪片段 flow, from "a uri arrives from the system picker" to "a clip is registered and is now the
 * reminder sound".
 *
 * ## Why this test exists
 *
 * v2.0.0 shipped a trimmer that was unusable on a real phone. Everything below the screen was in fact
 * correct - `AudioTrimmerDeviceTest` proves the encoder, and the waveform decoder is all platform codecs
 * that a JVM test cannot reach - so the fault was in the *flow*: which screen renders, and what the
 * trimmer shows once it does. Neither an encoder test nor a unit test can see any of that, which is
 * exactly how the feature shipped broken.
 *
 * ## Why a content:// uri and not a file path
 *
 * A user's file *always* arrives from the system picker as a `content://` uri. An earlier generation of
 * this feature was only ever exercised with `file://`, which is the one shape the picker never produces -
 * so a decoder that cannot open a content uri would have passed every test and failed every user. The
 * fixtures here go through FileProvider, which is the same ContentResolver-mediated read the picker's own
 * uri needs.
 *
 * ## What is deliberately *not* scripted here
 *
 * The system file picker itself. It is another app's UI, and driving it from an instrumentation test means
 * driving DocumentsUI rather than this app. The step before it (设置 renders the trimmer when a source
 * arrives) and every step after it are covered here; the picker's own hand-off is covered by hand on the
 * emulator, with screenshots and logcat, in the fix's evidence.
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class TrimFlowTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Inject
    lateinit var ringClips: RingClipRepository

    @Inject
    lateinit var settings: SettingsRepository

    /** Clip ids this test registered, so a run does not leave sounds behind in the picker. */
    private val createdClipIds = mutableListOf<Long>()

    /** The selection the device had before the test, restored by [cleanUp]. */
    private var previousRingClipId: Long? = null

    @Before
    fun setUp() {
        hiltRule.inject()
        previousRingClipId = runBlocking { runCatching { settings.current().ringClipId }.getOrNull() }
    }

    @After
    fun cleanUp() {
        runBlocking {
            // Saving a clip makes it the global reminder sound; the device's own selection is put back so a
            // test run does not silently change what the next person to use this emulator will hear.
            runCatching { settings.setRingClipId(previousRingClipId) }
            createdClipIds.forEach { id ->
                runCatching {
                    ringClips.refreshInUse()
                    ringClips.delete(id)
                }
            }
        }
    }

    /**
     * Copies a fixture into `cache/shared/` and hands back the `content://` uri for it.
     *
     * `cache/shared/` is the one cache subdirectory the app's FileProvider exports, so this produces the
     * same kind of uri the system picker produces for the user's own file.
     */
    private fun fixtureUri(assetName: String): Uri {
        val directory = File(context.cacheDir, "shared").apply { mkdirs() }
        val target = File(directory, "fixture_$assetName")
        instrumentation.context.assets.open(assetName).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        assertThat(target.length()).isGreaterThan(0L)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
    }

    private fun newViewModel() = TrimViewModel(context, ringClips, settings)

    /** Waits for a decode, on the instrumentation thread, while the ViewModel's own dispatcher works. */
    private fun awaitWaveform(viewModel: TrimViewModel): Waveform = runBlocking {
        withTimeout(DECODE_TIMEOUT_MILLIS) { viewModel.waveform.filterNotNull().first() }
    }

    /**
     * The precondition for everything else: a `content://` uri from a picker decodes into a waveform with a
     * usable duration. Before this test, the decoder had never been run against a content uri at all.
     */
    @Test
    fun load_decodesAPickerContentUri_intoAWaveformWithASaneDuration() {
        val uri = fixtureUri("tone.mp3")
        assertThat(uri.scheme).isEqualTo("content")

        val viewModel = newViewModel()
        viewModel.load(uri, "tone.mp3")
        val waveform = awaitWaveform(viewModel)

        assertThat(waveform.uri).isEqualTo(uri)
        assertThat(waveform.peaks.size).isGreaterThan(1)
        // The fixture is an 8-second tone; the exact figure depends on the decoder, so this is a band.
        assertThat(waveform.durationMillis).isAtLeast(5_000L)
        assertThat(waveform.durationMillis).isAtMost(12_000L)
        // Opening the trimmer selects a window inside the file rather than an empty selection.
        assertThat(viewModel.endMillis.value).isGreaterThan(viewModel.startMillis.value)
        assertThat(viewModel.endMillis.value - viewModel.startMillis.value)
            .isAtLeast(AudioTrimmer.MIN_CLIP_MILLIS)
        viewModel.stopPreview()
    }

    /** A waveform means the trimmer draws: the screen the user is supposed to land on, for a content uri. */
    @Test
    fun trimScreen_showsTheWaveformAndTheControls_forAPickerContentUri() {
        val uri = fixtureUri("tone.mp3")
        val viewModel = newViewModel()

        composeRule.setContent {
            TrimScreen(
                sourceUri = uri,
                sourceLabel = "tone.mp3",
                onBack = {},
                onSaved = {},
                viewModel = viewModel,
            )
        }

        // The audition button only exists on the branch that has a waveform; 「正在读取音频…」 and
        // 「这段音频无法读取」 are the two failure branches it proves we are not on.
        composeRule.waitUntil(DECODE_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText("试听选段").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("来源：tone.mp3").assertIsDisplayed()
        viewModel.stopPreview()
    }

    /**
     * Saving registers the clip, renders its file, and makes it the reminder sound.
     *
     * This is the end of the flow: 保存 on the trimmer has to leave a row the picker lists and a file the
     * notifier can play, or the user has trimmed a clip that will never ring.
     */
    @Test
    fun save_registersTheClip_rendersItsFile_andSelectsIt() {
        val uri = fixtureUri("tone.mp3")
        val viewModel = newViewModel()
        viewModel.load(uri, "tone.mp3")
        awaitWaveform(viewModel)

        // A real interior slice rather than a clamp: the fixture is 8 s.
        viewModel.setStart(2_000L)
        viewModel.setEnd(6_000L)
        viewModel.save("自动化测试铃声")

        val id = runBlocking {
            withTimeout(ENCODE_TIMEOUT_MILLIS) { viewModel.savedClipId.filterNotNull().first() }
        }
        createdClipIds += id

        val clip = runBlocking { ringClips.byId(id) }
        assertThat(clip).isNotNull()
        requireNotNull(clip)
        assertThat(clip.name).isEqualTo("自动化测试铃声")
        assertThat(clip.sourceLabel).isEqualTo("tone.mp3")
        assertThat(clip.sourceUri).isEqualTo(uri.toString())
        assertThat(clip.trimStartMillis).isEqualTo(2_000L)
        assertThat(clip.trimEndMillis).isEqualTo(6_000L)
        assertThat(File(clip.filePath).length()).isGreaterThan(1_000L)
        // The rendered slice really is a playable audio file, and really is the length that was asked for.
        assertThat(clip.durationMillis).isAtLeast(2_500L)
        assertThat(clip.durationMillis).isAtMost(8_000L)
        // Registered *and* in use: the trimmer's save is also the selection.
        assertThat(clip.inUse).isTrue()
        assertThat(runBlocking { settings.current().ringClipId }).isEqualTo(id)
    }

    /**
     * A second visit with a *different* file decodes that file.
     *
     * The trimmer's ViewModel is scoped to the 设置 destination, not to the trimmer, so it survives the
     * trip back to the ringtone list. The old guard asked it "is a waveform already loaded?" and therefore
     * never decoded a second file: the user saw the previous song's waveform and saved a clip cut out of
     * the previous song.
     */
    @Test
    fun aSecondVisit_decodesTheSecondFile() {
        val first = fixtureUri("tone.mp3")
        val second = fixtureUri("tone.wav")
        val viewModel = newViewModel()

        // One ViewModel for both visits, exactly as the real screen reuses the one it is given.
        var source by mutableStateOf(first to "tone.mp3")
        composeRule.setContent {
            TrimScreen(
                sourceUri = source.first,
                sourceLabel = source.second,
                onBack = {},
                onSaved = {},
                viewModel = viewModel,
            )
        }
        composeRule.waitUntil(DECODE_TIMEOUT_MILLIS) { viewModel.waveform.value?.uri == first }

        // 返回 to the ringtone list, then 裁剪一个片段 on another file.
        composeRule.runOnUiThread { source = second to "tone.wav" }

        composeRule.waitUntil(DECODE_TIMEOUT_MILLIS) { viewModel.waveform.value?.uri == second }
        composeRule.onNodeWithText("来源：tone.wav").assertIsDisplayed()
        viewModel.stopPreview()
    }

    /**
     * A clip saved on an earlier visit does not close the next one.
     *
     * `savedClipId` is a one-shot event, but it lives in the ViewModel that outlives the screen - so
     * without the entry guard the leftover id fired `onSaved` the moment the trimmer reopened and bounced
     * the user straight back to the ringtone list. Every 裁剪 attempt after the first one was affected.
     */
    @Test
    fun aClipSavedOnAnEarlierVisit_doesNotCloseTheNextVisit() {
        val first = fixtureUri("tone.mp3")
        val second = fixtureUri("tone.wav")
        val viewModel = newViewModel()

        // Visit 1: trim and save, which is what leaves the event behind.
        viewModel.load(first, "tone.mp3")
        awaitWaveform(viewModel)
        viewModel.save("上一次的铃声")
        createdClipIds += runBlocking {
            withTimeout(ENCODE_TIMEOUT_MILLIS) { viewModel.savedClipId.filterNotNull().first() }
        }

        // Visit 2: the same ViewModel, a different source.
        var savedCallbacks = 0
        composeRule.setContent {
            TrimScreen(
                sourceUri = second,
                sourceLabel = "tone.wav",
                onBack = {},
                onSaved = { savedCallbacks++ },
                viewModel = viewModel,
            )
        }
        composeRule.waitUntil(DECODE_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText("来源：tone.wav").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        assertThat(savedCallbacks).isEqualTo(0)
        viewModel.stopPreview()
    }

    /**
     * The selection can never leave the encoder's limits - checked here because [TrimViewModel.save] would
     * otherwise discover it at the end of a slow export, and because the screen has four nudge buttons and
     * three drag surfaces that all funnel into it.
     */
    @Test
    fun clampSelection_keepsTheWindowInsideTheFileAndTheEncoderLimits() {
        // A five-minute song asked to keep everything: capped at the maximum clip length.
        val (longStart, longEnd) = TrimViewModel.clampSelection(0L, 5 * 60_000L, 5 * 60_000L)
        assertThat(longEnd - longStart).isAtMost(AudioTrimmer.MAX_CLIP_MILLIS)
        assertThat(longEnd).isAtMost(5 * 60_000L)

        // A four-second file: the whole file, which is shorter than the minimum - the encoder still gets a
        // valid, non-reversed window rather than a crash.
        val (shortStart, shortEnd) = TrimViewModel.clampSelection(0L, 4_000L, 4_000L)
        assertThat(shortStart).isAtLeast(0L)
        assertThat(shortEnd).isGreaterThan(shortStart)

        // A reversed window is repaired rather than passed through.
        val (reversedStart, reversedEnd) = TrimViewModel.clampSelection(9_000L, 2_000L, 30_000L)
        assertThat(reversedEnd).isGreaterThan(reversedStart)
    }

    private companion object {
        /** Generous: a cold codec negotiation on a loaded emulator is slow, and a flaky test is worse. */
        const val DECODE_TIMEOUT_MILLIS = 60_000L
        const val ENCODE_TIMEOUT_MILLIS = 120_000L
    }
}
