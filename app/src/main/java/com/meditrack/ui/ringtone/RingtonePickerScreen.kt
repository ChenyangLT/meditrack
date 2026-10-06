package com.meditrack.ui.ringtone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RingVolume
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meditrack.core.theme.prefs
import com.meditrack.data.local.entity.RingClip
import com.meditrack.domain.reminder.ReminderTone

/** What the user is picking a file *for*, decided when they tap a button. */
private enum class PickerAction { IMPORT, TRIM }

/**
 * 提醒铃声 - the built-in tones, the clips the user has made, and the three ways to add another.
 *
 * ## Why the selection is the whole screen
 *
 * The previous design put the tone in a dropdown next to a dozen other switches. A user who cannot
 * hear their reminder has exactly one question ("which sound will it play?") and one lever, so the
 * answer is a screen of its own: every option is visible, every option can be auditioned before it is
 * chosen, and the choice is applied the moment it is tapped rather than behind a Save button the user
 * may never find.
 *
 * ## Why a per-medication note appears
 *
 * When this screen is opened for one medication, that medication's override wins over everything
 * selected here. Saying so is the difference between "the picker is broken" and "this one pill has its
 * own sound".
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RingtonePickerScreen(
    onBack: () -> Unit,
    onOpenTrimmer: (Uri, String) -> Unit,
    /**
     * The medication whose override is being edited, or null for the global sound.
     *
     * Defaulted so the plain two-argument call from 设置 keeps working; the parameter exists so the
     * medication path (which the repository and the ViewModel already support) is reachable from a
     * caller that knows which pill it is configuring.
     */
    medicationId: Long? = null,
    viewModel: RingtonePickerViewModel = hiltViewModel(),
) {
    val clips by viewModel.clips.collectAsStateWithLifecycle()
    val selectedClipId by viewModel.selectedClipId.collectAsStateWithLifecycle()
    val selectedTone by viewModel.selectedTone.collectAsStateWithLifecycle()
    val customRingClipId by viewModel.customRingClipId.collectAsStateWithLifecycle()
    val medicationName by viewModel.medicationName.collectAsStateWithLifecycle()
    val systemRingtones by viewModel.systemRingtones.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val minTarget = MaterialTheme.prefs.minTouchTargetDp.dp

    var renameTarget by remember { mutableStateOf<RingClip?>(null) }
    var deleteTarget by remember { mutableStateOf<RingClip?>(null) }
    var showSystemList by remember { mutableStateOf(false) }
    // Which of the two picker buttons opened the system picker; the result is routed accordingly.
    var pendingAction by remember { mutableStateOf(PickerAction.IMPORT) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val displayName = displayNameOf(context, uri)
        when (pendingAction) {
            PickerAction.IMPORT -> viewModel.importPickedAudio(uri, displayName)
            PickerAction.TRIM -> {
                // The trimmer reads the source twice (waveform, then the export) and may outlive this
                // activity, so the read grant is taken persistently rather than relying on the
                // temporary one the picker attached to this result.
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                viewModel.stopPreview()
                onOpenTrimmer(uri, displayName)
            }
        }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onMessageShown()
        }
    }

    // Safe to re-run: loadFor only reads, and the ViewModel holds the result, so a recomposition or a
    // rotation cannot make the notice flip back to the global sound.
    //
    // [RingtonePickerViewModel.refresh] is re-run for the same reason the effect runs again at all: this
    // screen is left and re-entered when the user goes 裁剪一个片段 and comes back, and the ViewModel
    // outlives both visits. Without the re-read, the list would still be the snapshot from before the clip
    // existed - so a clip that was just created and made the reminder sound would be listed as
    // 「还没有自定义铃声」, which reads as "the save did nothing".
    LaunchedEffect(medicationId) {
        viewModel.loadFor(medicationId)
        viewModel.refresh()
    }

    // Leaving the screen must silence the audition: a ringtone preview that keeps playing after the
    // user has navigated away is indistinguishable from the reminder itself going off.
    DisposableEffect(Unit) {
        onDispose { viewModel.stopPreview() }
    }

    renameTarget?.let { target ->
        RenameClipDialog(
            clip = target,
            onConfirm = { name ->
                viewModel.renameClip(target.id, name)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除「${target.name}」？") },
            text = { Text("只会删掉应用里复制的那一份，你手机上的原文件不受影响。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteClip(target.id)
                        deleteTarget = null
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("提醒铃声") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The override notice comes first: it changes what everything below means.
            medicationName?.let { name ->
                item {
                    PickerCard(title = "为「$name」选择铃声") {
                        Text(
                            text = if (customRingClipId != null) {
                                "这个药品单独指定了铃声，它会盖过下面选中的全局铃声。" +
                                    "要改回全局铃声，请到药品编辑页里清除这个药品的铃声设置。"
                            } else {
                                "这个药品目前使用全局铃声。在下面选择的铃声会成为全局默认，" +
                                    "已经单独设置过铃声的其他药品不受影响。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                PickerCard(title = "内置铃声") {
                    Text(
                        text = "五段内置铃声，装在应用里，不受系统铃声设置影响。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ReminderTone.entries.forEach { tone ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = tone == selectedTone && selectedClipId == null,
                                    onClick = { viewModel.selectTone(tone) },
                                    role = Role.RadioButton,
                                )
                                .heightIn(min = minTarget)
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = tone == selectedTone && selectedClipId == null,
                                onClick = null,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = tone.label,
                                style = MaterialTheme.typography.bodyLarge,
                                // Weighted so the trailing试听 button keeps its size at any font scale.
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = { viewModel.previewTone(tone) },
                                modifier = Modifier.size(minTarget),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = "试听 ${tone.label}",
                                )
                            }
                        }
                    }
                }
            }

            item {
                PickerCard(title = "自定义铃声") {
                    if (clips.isEmpty()) {
                        Text(
                            text = "还没有自定义铃声。用下面的「选择本地音频」或「裁剪一个片段」添加一个。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    clips.forEachIndexed { index, clip ->
                        if (index > 0) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                        }
                        ClipRow(
                            clip = clip,
                            selected = clip.id == selectedClipId,
                            minTarget = minTarget,
                            onSelect = { viewModel.selectClip(clip.id) },
                            onPreview = { viewModel.previewClip(clip) },
                            onRename = { renameTarget = clip },
                            onDelete = { deleteTarget = clip },
                        )
                    }
                }
            }

            item {
                PickerCard(title = "添加自定义铃声") {
                    Button(
                        onClick = {
                            pendingAction = PickerAction.IMPORT
                            filePicker.launch(arrayOf("audio/*"))
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = minTarget),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("选择本地音频")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            pendingAction = PickerAction.TRIM
                            filePicker.launch(arrayOf("audio/*"))
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = minTarget),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentCut,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("裁剪一个片段")
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "「选择本地音频」直接整段使用；「裁剪一个片段」会打开波形图，" +
                            "让你只保留其中一段。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { showSystemList = !showSystemList },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = minTarget),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.RingVolume,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (showSystemList) "收起系统铃声" else "从系统铃声选择")
                    }
                    if (showSystemList) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "系统铃声不支持裁剪，会整首使用。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (systemRingtones.isEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "没有读取到系统铃声，可能是这台手机没有设置任何铃声。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        systemRingtones.forEach { ringtone ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = minTarget)
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.LibraryMusic,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = ringtone.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        text = ringtone.kind,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(
                                    onClick = { viewModel.importSystemRingtone(ringtone) },
                                    modifier = Modifier.heightIn(min = minTarget),
                                ) { Text("使用") }
                            }
                        }
                    }
                }
            }

            item {
                PickerCard(title = "为什么要复制一份") {
                    Text(
                        text = "选中的铃声会被复制到应用自己的目录里。这样即使你以后删掉原来的歌曲、" +
                            "换了音乐应用，或者手机重启，提醒仍然会准时响。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** One clip of 「自定义铃声」: the sound, where it came from, and everything that can be done to it. */
@Composable
private fun ClipRow(
    clip: RingClip,
    selected: Boolean,
    minTarget: Dp,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
                .heightIn(min = minTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                // The name is unweighted and wrapping: a drug-free label like "起床铃（副歌）" is
                // short, but a user who names a clip after a whole sentence must still read it.
                Text(text = clip.name, style = MaterialTheme.typography.bodyLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = clip.durationLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = clip.sourceLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (clip.inUse) {
                        Text(
                            text = "正在使用",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            IconButton(onClick = onPreview, modifier = Modifier.size(minTarget)) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "试听 ${clip.name}")
            }
        }
        // The two edits sit on their own line rather than as two more icon buttons in the row: three
        // icons plus the radio button and a wrapping name do not fit a phone at the elderly font scale.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onRename, modifier = Modifier.heightIn(min = minTarget)) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("改名")
            }
            TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = minTarget)) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun RenameClipDialog(
    clip: RingClip,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(clip.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("给这个铃声改个名字") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("铃声名称") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** A titled card; every section of this screen uses it so the page has one visual rhythm. */
@Composable
private fun PickerCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MaterialTheme.prefs.cardCornerDp.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

/**
 * The picked file's display name, or a readable fallback.
 *
 * Queried rather than derived from the uri: a document uri is an opaque id, so the only place the real
 * file name exists is the provider's own metadata - and a provider that refuses the query (a cloud
 * provider that is not logged in, a scan-in-progress media store) must still leave the picker usable.
 */
private fun displayNameOf(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && cursor.columnCount > 0) cursor.getString(0) else null
            }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "音频片段"
