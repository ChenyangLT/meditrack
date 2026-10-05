package com.meditrack.ui.ringtone

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meditrack.audio.AudioTrimmer
import com.meditrack.audio.Waveform
import com.meditrack.core.theme.prefs
import com.meditrack.ui.components.AdaptiveButtonRow
import kotlin.math.abs

/** Which part of the waveform a drag that started at this x should move. */
private enum class TrimDragTarget { START, END, WINDOW }

/**
 * 裁剪铃声 - pick a slice out of an audio file and save it as a ringtone.
 *
 * ## Why a drawn waveform and not two sliders
 *
 * "Which twelve seconds?" is a question about *content*, and the only clue to content in an audio file
 * is its shape: the quiet intro, the loud chorus. A pair of sliders on a timeline asks the user to
 * count seconds blind, which is exactly the task a 60-year-old user fails at. So the amplitude is drawn
 * and the selection is a window over it.
 *
 * ## Why dragging is never the only way
 *
 * The handles are grabbable at a comfortable size, but a drag is still a precision gesture - hard with
 * shaky hands, impossible with a screen protector and dry fingers. Every handle therefore also has
 * 「−0.5 秒」/「+0.5 秒」 buttons underneath, and the exact start, end and length are printed as text
 * rather than only shown graphically.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TrimScreen(
    sourceUri: Uri,
    sourceLabel: String,
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
    viewModel: TrimViewModel = hiltViewModel(),
) {
    val waveform by viewModel.waveform.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val startMillis by viewModel.startMillis.collectAsStateWithLifecycle()
    val endMillis by viewModel.endMillis.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val playheadMillis by viewModel.playheadMillis.collectAsStateWithLifecycle()
    val exportProgress by viewModel.exportProgress.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val savedClipId by viewModel.savedClipId.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val minTarget = MaterialTheme.prefs.minTouchTargetDp.dp
    val isExporting = exportProgress != null

    // Saveable so a rotation does not discard a name the user just typed - the trimmer is a screen
    // where the keyboard comes up over the waveform, which is exactly when a stray rotation happens.
    var name by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(sourceUri, sourceLabel) {
        // Decoded once per source: a rotation recreates this effect, and re-running it would throw away
        // the selection the user had just made.
        if (viewModel.waveform.value == null && !viewModel.isLoading.value) {
            viewModel.load(sourceUri, sourceLabel)
        }
    }

    // The clip name defaults to the file name without its extension: it is almost always what the user
    // would have typed, and a pre-filled field is one less thing to do on a screen full of controls.
    // Only filled when empty, so the same rotation does not overwrite a name the user edited.
    LaunchedEffect(waveform?.uri) {
        if (name.isBlank()) {
            waveform?.let { name = it.label.substringBeforeLast('.', it.label) }
        }
    }

    LaunchedEffect(savedClipId) { savedClipId?.let(onSaved) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onMessageShown()
        }
    }

    // Auditioning must not outlive the screen; a preview still playing after "back" reads as the
    // reminder itself firing.
    DisposableEffect(Unit) {
        onDispose { viewModel.stopPreview() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("裁剪铃声") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.save(name) },
                        enabled = waveform != null && !isExporting,
                    ) {
                        Text(
                            text = "保存",
                            fontWeight = FontWeight.SemiBold,
                            color = if (waveform != null && !isExporting) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // A plain scrolling column rather than a lazy list: the whole screen is one form, and the
                // controls below the waveform must stay attached to it while it is being dragged.
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                isLoading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("正在读取音频…", style = MaterialTheme.typography.bodyLarge)
                    }
                }

                waveform == null -> {
                    Text(
                        text = "这段音频无法读取。可以换一个文件，或改用「选择本地音频」整段使用。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(
                        onClick = onBack,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = minTarget),
                    ) { Text("返回") }
                }

                else -> {
                    val loaded = waveform!!
                    WaveformTrimmer(
                        peaks = loaded.peaks,
                        durationMillis = loaded.durationMillis,
                        startMillis = startMillis,
                        endMillis = endMillis,
                        playheadMillis = playheadMillis,
                        enabled = !isExporting,
                        selectionStart = { viewModel.startMillis.value },
                        selectionEnd = { viewModel.endMillis.value },
                        onNudgeStart = viewModel::nudgeStart,
                        onNudgeEnd = viewModel::nudgeEnd,
                        onMoveWindow = viewModel::moveWindowBy,
                    )

                    Text(
                        text = "用手指拖动两条竖线选择范围，按住中间可以整体移动。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text(
                        text = selectionLabel(startMillis, endMillis),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )

                    // The nudge buttons are the accessible half of the trimmer: four taps of a labelled
                    // button beat a 3-pixel drag on a 40-degree tilted screen.
                    AdaptiveButtonRow(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "起点",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = { viewModel.nudgeStart(-NUDGE_MILLIS) },
                            enabled = !isExporting,
                            modifier = Modifier.heightIn(min = minTarget),
                        ) { Text("−0.5 秒") }
                        TextButton(
                            onClick = { viewModel.nudgeStart(NUDGE_MILLIS) },
                            enabled = !isExporting,
                            modifier = Modifier.heightIn(min = minTarget),
                        ) { Text("+0.5 秒") }
                    }
                    AdaptiveButtonRow(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "终点",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = { viewModel.nudgeEnd(-NUDGE_MILLIS) },
                            enabled = !isExporting,
                            modifier = Modifier.heightIn(min = minTarget),
                        ) { Text("−0.5 秒") }
                        TextButton(
                            onClick = { viewModel.nudgeEnd(NUDGE_MILLIS) },
                            enabled = !isExporting,
                            modifier = Modifier.heightIn(min = minTarget),
                        ) { Text("+0.5 秒") }
                    }

                    Button(
                        onClick = {
                            if (isPlaying) viewModel.stopPreview() else viewModel.previewSelection()
                        },
                        enabled = !isExporting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = minTarget),
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isPlaying) "停止" else "试听选段")
                    }

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("铃声名称") },
                        placeholder = { Text(TrimViewModel.DEFAULT_CLIP_NAME) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                    )

                    Button(
                        onClick = { viewModel.save(name) },
                        enabled = !isExporting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = minTarget),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isExporting) "正在生成…" else "保存这个片段")
                    }

                    if (isExporting) {
                        LinearProgressIndicator(
                            progress = { (exportProgress ?: 0) / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "正在生成铃声文件，请不要退出…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.ContentCut,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "只会保存你选中的这一段（最长 " +
                                "${AudioTrimmer.MAX_CLIP_MILLIS / 1000} 秒，最短 " +
                                "${AudioTrimmer.MIN_CLIP_MILLIS / 1000} 秒），" +
                                "原来的音频文件不会被改动或删除。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "来源：${waveform?.label ?: sourceLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The waveform, its selection and its playhead, plus the gestures that move them.
 *
 * Drawn with a single [Canvas] rather than per-bar composables: a 1,200-bucket waveform would be 1,200
 * layout nodes, and every drag frame would re-layout all of them. Drawing is one pass over an array.
 *
 * The gesture is one pointer region over the whole canvas whose *down position* decides what is being
 * dragged. Three separate regions would be the obvious alternative, but one of them would sit half
 * outside the canvas whenever a handle is at 0 ms or at the very end of the file.
 *
 * @param startMillis the selection as the last frame was drawn - see [selectionStart] for the live value
 * @param selectionStart reads the selection at the moment of a gesture. The pointer block outlives
 *        recompositions (its keys do not change when the selection does, deliberately: changing them
 *        would cancel a drag in progress), so a captured `Long` would still be the value from when the
 *        screen opened.
 */
@Composable
private fun WaveformTrimmer(
    peaks: FloatArray,
    durationMillis: Long,
    startMillis: Long,
    endMillis: Long,
    playheadMillis: Long,
    enabled: Boolean,
    selectionStart: () -> Long,
    selectionEnd: () -> Long,
    onNudgeStart: (Long) -> Unit,
    onNudgeEnd: (Long) -> Unit,
    onMoveWindow: (Long) -> Unit,
) {
    val density = LocalDensity.current
    val handleTouchPx = with(density) { HANDLE_TOUCH_DP.dp.toPx() }
    val barColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    val handleColor = MaterialTheme.colorScheme.primary
    val scrimColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
    val selectionTint = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val playheadColor = MaterialTheme.colorScheme.error
    val duration = durationMillis.coerceAtLeast(1L)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(WAVEFORM_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .then(
                if (!enabled) {
                    Modifier
                } else {
                    Modifier.pointerInput(durationMillis) {
                        val widthPx = size.width.toFloat()
                        var target = TrimDragTarget.WINDOW
                        detectDragGestures(
                            onDragStart = { offset ->
                                target = trimDragTargetFor(
                                    x = offset.x,
                                    widthPx = widthPx,
                                    startX = widthPx * fractionOf(selectionStart(), duration),
                                    endX = widthPx * fractionOf(selectionEnd(), duration),
                                    touchRadiusPx = handleTouchPx,
                                )
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                if (widthPx <= 0f) return@detectDragGestures
                                val deltaMillis = (dragAmount.x / widthPx * duration).toLong()
                                if (deltaMillis == 0L) return@detectDragGestures
                                // A drag is expressed as a *change*, never as an absolute position: it
                                // is the ViewModel that knows the current value, and it is also the only
                                // place that clamps the result to the file and to the length limits.
                                when (target) {
                                    TrimDragTarget.START -> onNudgeStart(deltaMillis)
                                    TrimDragTarget.END -> onNudgeEnd(deltaMillis)
                                    TrimDragTarget.WINDOW -> onMoveWindow(deltaMillis)
                                }
                            },
                        )
                    }
                }
            ),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val centerY = size.height / 2f

            if (peaks.isNotEmpty() && width > 0f) {
                val step = width / peaks.size
                val stroke = step.coerceAtLeast(1f)
                peaks.forEachIndexed { index, peak ->
                    val x = index * step + step / 2f
                    // 0.42 rather than 0.5 leaves a visible centre line and keeps the bars from touching
                    // the top and bottom edges, which is where the handles' knobs are drawn.
                    val half = (peak.coerceIn(0f, 1f) * size.height * 0.42f).coerceAtLeast(0.5f)
                    drawLine(
                        color = barColor,
                        start = Offset(x, centerY - half),
                        end = Offset(x, centerY + half),
                        strokeWidth = stroke,
                    )
                }
            }

            val startX = width * fractionOf(startMillis, duration)
            val endX = width * fractionOf(endMillis, duration)

            // The kept region is tinted, then the discarded regions are veiled: two rects communicate
            // "this is what you get" and "this is what you lose" without any legend.
            drawRect(
                color = selectionTint,
                topLeft = Offset(startX, 0f),
                size = Size((endX - startX).coerceAtLeast(0f), size.height),
            )
            if (startX > 0f) {
                drawRect(color = scrimColor, size = Size(startX, size.height))
            }
            if (endX < width) {
                drawRect(
                    color = scrimColor,
                    topLeft = Offset(endX, 0f),
                    size = Size(width - endX, size.height),
                )
            }

            val handleWidth = 3.dp.toPx()
            val knobWidth = 10.dp.toPx()
            val knobHeight = 28.dp.toPx()
            val corner = CornerRadius(4.dp.toPx())
            drawLine(
                color = handleColor,
                start = Offset(startX, 0f),
                end = Offset(startX, size.height),
                strokeWidth = handleWidth,
            )
            drawLine(
                color = handleColor,
                start = Offset(endX, 0f),
                end = Offset(endX, size.height),
                strokeWidth = handleWidth,
            )
            // A visible knob at the vertical centre of each handle: the line alone is thin enough to be
            // mistaken for a gridline, and the knob is also the honest hint that this is grabbable.
            drawRoundRect(
                color = handleColor,
                topLeft = Offset(startX - knobWidth / 2f, centerY - knobHeight / 2f),
                size = Size(knobWidth, knobHeight),
                cornerRadius = corner,
            )
            drawRoundRect(
                color = handleColor,
                topLeft = Offset(endX - knobWidth / 2f, centerY - knobHeight / 2f),
                size = Size(knobWidth, knobHeight),
                cornerRadius = corner,
            )

            val playheadX = width * fractionOf(playheadMillis, duration)
            drawLine(
                color = playheadColor,
                start = Offset(playheadX, 0f),
                end = Offset(playheadX, size.height),
                strokeWidth = 2.dp.toPx(),
            )
        }
    }
}

/** Decides what a drag started at [x] should move; see [WaveformTrimmer]. */
private fun trimDragTargetFor(
    x: Float,
    widthPx: Float,
    startX: Float,
    endX: Float,
    touchRadiusPx: Float,
): TrimDragTarget {
    if (widthPx <= 0f) return TrimDragTarget.WINDOW
    val toStart = abs(x - startX)
    val toEnd = abs(x - endX)
    // The nearer handle wins a tie, which is what happens with a minimum-length selection where the two
    // touch regions overlap.
    return when {
        toStart <= touchRadiusPx && toStart <= toEnd -> TrimDragTarget.START
        toEnd <= touchRadiusPx -> TrimDragTarget.END
        else -> TrimDragTarget.WINDOW
    }
}

/** A position in 0..1 of the file, safe for a zero or unknown duration. */
private fun fractionOf(millis: Long, durationMillis: Long): Float =
    if (durationMillis <= 0L) 0f else (millis.toFloat() / durationMillis).coerceIn(0f, 1f)

/** "0:41 - 0:53（12 秒）" - the selection as text, so it can be read without reading the drawing. */
private fun selectionLabel(startMillis: Long, endMillis: Long): String {
    val length = (endMillis - startMillis).coerceAtLeast(0L)
    val seconds = length / 1000.0
    val lengthText = if (seconds % 1.0 == 0.0) {
        "${seconds.toInt()} 秒"
    } else {
        "%.1f 秒".format(seconds)
    }
    return "${Waveform.formatMillis(startMillis)} - ${Waveform.formatMillis(endMillis)}（$lengthText）"
}

/** The half-second the nudge buttons move a handle. */
private const val NUDGE_MILLIS = 500L

/** Keep in sync with [WaveformTrimmer]'s grab radius; 24dp each side is the 48dp touch target. */
private const val HANDLE_TOUCH_DP = 24

private const val WAVEFORM_HEIGHT_DP = 160
