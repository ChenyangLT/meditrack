package com.meditrack.ui.settings

import android.content.Intent
import android.provider.Settings
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meditrack.R
import com.meditrack.data.prefs.AccentColor
import com.meditrack.data.prefs.FontScale
import com.meditrack.data.prefs.SettingsRepository
import com.meditrack.data.prefs.ThemeMode
import com.meditrack.data.prefs.UserPreferences
import com.meditrack.data.prefs.WeekStart
import com.meditrack.core.theme.prefs
import com.meditrack.core.util.DateTimeUtils
import com.meditrack.BuildConfig
import com.meditrack.data.backup.BackupEntry
import com.meditrack.data.local.entity.ReminderEvent
import com.meditrack.data.local.entity.ReviewSearchEngine
import com.meditrack.data.local.entity.RingClip
import com.meditrack.data.repository.MedicationReviewSnapshot
import com.meditrack.domain.reminder.ReminderHealth
import com.meditrack.domain.reminder.ReminderRingMode
import com.meditrack.ui.MediTrackTestTags
import com.meditrack.ui.components.AdaptiveButtonRow
import com.meditrack.ui.components.AdaptiveChipRow
import com.meditrack.ui.components.TimePickerDialog
import com.meditrack.ui.ringtone.RingtonePickerScreen
import com.meditrack.ui.ringtone.TrimScreen
import com.meditrack.domain.reminder.AlertChannel
import com.meditrack.domain.reminder.ReminderTone
import com.meditrack.ui.update.UpdateDialog

/**
 * Ready-made do-not-disturb windows.
 *
 * Most people want roughly the same one, and picking two dials to say "the night" is busywork. The
 * custom dials are still there for anyone who wants a different window.
 */
private val QUIET_PRESETS = listOf(
    21 * 60 to 9 * 60,
    22 * 60 to 7 * 60,
    23 * 60 to 6 * 60,
)

/**
 * How long an expandable section takes to open or close, in milliseconds.
 *
 * Short on purpose: this is a settings list, and the movement exists to stop the list jumping under
 * the user's thumb - not to be watched. Anything slower turns "let me check that value" into a
 * sequence of small waits.
 */
private const val SETTINGS_EXPAND_MILLIS = 180

/**
 * 设置.
 *
 * The screen is ordered by "how likely is this to break my reminders?", not alphabetically:
 * permissions first (because a missing one silently degrades the core feature), then reminders,
 * then appearance, then the accessibility preset, then data.
 *
 * The accessibility options are **opt-in presets, not defaults**: the brief asks for an app that
 * suits elderly users without forcing a locked-down experience on everyone. The single
 * "一键开启适老模式" button applies the whole bundle for a carer setting up the phone for someone else.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenAbout: () -> Unit,
    onOpenOnboarding: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val health by viewModel.health.collectAsStateWithLifecycle()
    val auditLog by viewModel.auditLog.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val lastExport by viewModel.lastExport.collectAsStateWithLifecycle()
    val backups by viewModel.backups.collectAsStateWithLifecycle()
    val updateFound by viewModel.updateFound.collectAsStateWithLifecycle()
    val backupFolder by viewModel.backupFolder.collectAsStateWithLifecycle()
    val cacheUsage by viewModel.cacheUsage.collectAsStateWithLifecycle()
    val ringClips by viewModel.ringClips.collectAsStateWithLifecycle()
    val reviewSnapshots by viewModel.reviewSnapshots.collectAsStateWithLifecycle()
    // The sound source as one phrase: the chosen clip if there is one, the bundled tone otherwise - and
    // "静音" when the tone is switched off, because in that case neither of the others is what the user
    // will actually hear. Computed once here rather than per section: two call sites need it (the
    // 提醒方式 summary and the ringtone entrance row), and letting them compute it separately is how the
    // two lines end up disagreeing about what the reminder sounds like.
    val soundLabel = when {
        !preferences.soundEnabled -> "静音"
        else -> ringClips.firstOrNull { it.id == preferences.ringClipId }?.name
            ?: com.meditrack.domain.reminder.ReminderTone.fromName(preferences.reminderTone).label
    }
    val snackbarHostState = remember { SnackbarHostState() }
    // A listed backup is imported only after an explicit confirmation: it replaces everything.
    var pendingImport by remember { mutableStateOf<BackupEntry?>(null) }
    // Which end of the do-not-disturb window the time dialog is editing: 0 = start, 1 = end.
    var quietEditing by remember { mutableStateOf<Int?>(null) }
    // 清除缓存 deletes files, so it asks first and says exactly what it will and will not take.
    var confirmingCacheClear by remember { mutableStateOf(false) }
    // The ringtone picker and the trimmer are shown *instead of* the settings list rather than pushed
    // onto a navigation graph: they are steps in one decision ("what will my reminder sound like?"),
    // and owning the state here makes backing out a single assignment with nothing to unwind.
    var showRingtonePicker by remember { mutableStateOf(false) }
    // null = showing settings; a pair = trimming that source down into a clip.
    var trimSource by remember { mutableStateOf<Pair<android.net.Uri, String>?>(null) }

    // The system back button has to close the picker before it leaves 设置, or a user three taps deep
    // in the trimmer ends up outside the app with no idea what happened.
    BackHandler(enabled = showRingtonePicker || trimSource != null) {
        trimSource = null
        showRingtonePicker = false
    }

    // The self-check is a snapshot of system state, so it is read when the screen appears and
    // refreshed on every resume - the same treatment the permission rows already get.
    LaunchedEffect(Unit) {
        viewModel.refreshHealth()
        // The folder is the source of truth for the backup list, so re-read it rather than caching.
        viewModel.refreshBackups()
        viewModel.refreshCacheUsage()
    }

    // Permission state lives in the system, not in our state; re-read it on every resume so
    // coming back from the Settings app updates the screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions(context)
                viewModel.refreshHealth()
                viewModel.refreshBackups()
                viewModel.refreshCacheUsage()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { viewModel.refreshPermissions(context) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importBackup(context, it) } }

    // ACTION_OPEN_DOCUMENT_TREE: lets the user put backups anywhere they can see - Downloads, a cloud
    // provider, an SD card - with a grant that survives reboots.
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let { viewModel.chooseBackupFolder(it) } }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onMessageShown()
        }
    }

    // The ringtone screens own their own feedback - they report a saved clip or a failed save through
    // their own snackbar, before they hand control back. There is deliberately nothing queued here:
    // this screen's SnackbarHost is not composed while they are on top, so a message left pending
    // would wait for a host that is not there.

    updateFound?.let { info ->
        UpdateDialog(info = info, onDismiss = { viewModel.dismissUpdate(info) })
    }

    pendingImport?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("导入这份备份？") },
            text = {
                Text(
                    "将用 ${entry.name} 里的内容替换当前全部药品与服药记录。" +
                        "此操作不可撤销，建议先把当前数据导出一份。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.importListedBackup(entry)
                        pendingImport = null
                    },
                ) { Text("导入并覆盖") }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("取消") } },
        )
    }

    quietEditing?.let { which ->
        TimePickerDialog(
            title = if (which == 0) "免打扰开始时间" else "免打扰结束时间",
            initialMinuteOfDay = if (which == 0) {
                preferences.quietHoursStartMinute
            } else {
                preferences.quietHoursEndMinute
            },
            onConfirm = { minute ->
                if (which == 0) {
                    viewModel.setQuietHours(minute, preferences.quietHoursEndMinute)
                } else {
                    viewModel.setQuietHours(preferences.quietHoursStartMinute, minute)
                }
                quietEditing = null
            },
            onDismiss = { quietEditing = null },
        )
    }

    // The dialog names both halves explicitly. "清除缓存" on its own is a phrase users have learned
    // to distrust, and rightly so: what it deletes is usually invisible until something is missing.
    // Here the invisible things are the point, so the copy spends its space on what *survives*.
    if (confirmingCacheClear) {
        AlertDialog(
            onDismissRequest = { confirmingCacheClear = false },
            title = { Text("清除缓存？") },
            text = {
                Text(
                    "会删除：\n" +
                        "· 没有被使用的自定义铃声片段（${cacheUsage.clipCount - cacheUsage.inUseCount} 个）\n" +
                        "· 提醒决策日志（${cacheUsage.auditEntries} 条，只用于排查「为什么没提醒」）\n\n" +
                        "不会删除：\n" +
                        "· 药品、服药时间与全部服药记录\n" +
                        "· 正在使用的铃声（${cacheUsage.inUseCount} 个）与所有设置\n" +
                        "· 已经导出的备份文件\n\n" +
                        "铃声片段只是音频文件，铃声列表里的条目会保留，随时可以重新生成。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearCache()
                        confirmingCacheClear = false
                    },
                ) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingCacheClear = false }) { Text("取消") }
            },
        )
    }

    if (showRingtonePicker) {
        // The picker asks the ViewModel for the clip list and applies a selection immediately; coming
        // back just re-reads the settings screen's own state, which already observed the change.
        RingtonePickerScreen(
            onBack = { showRingtonePicker = false },
            onOpenTrimmer = { uri, label -> trimSource = uri to label },
        )
    } else if (trimSource != null) {
        val (uri, label) = trimSource!!
        TrimScreen(
            sourceUri = uri,
            sourceLabel = label,
            onBack = { trimSource = null },
            onSaved = { _ ->
                // Saving a clip both creates the row and selects it, but "which clip is in use" is
                // denormalised - so the in-use flags and the usage figures are re-read rather than
                // assumed, exactly as the ring-clip repository documents.
                trimSource = null
                viewModel.refreshCacheUsage()
            },
        )
    } else {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = { Text("设置") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Tagged so tests can drive the list itself via performScrollToNode, rather than
                // calling performScrollTo on an item that may not be composed yet.
                .testTag(com.meditrack.ui.MediTrackTestTags.SETTINGS_LIST),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------------------------------------------------------- permissions
            item {
                ExpandableSettingsSection(
                    title = "权限",
                    icon = Icons.Filled.NotificationsActive,
                    // The collapsed line is the whole point of this section: a permission that is
                    // missing degrades the core feature silently, so it has to be visible without
                    // asking the user to open anything.
                    summary = when {
                        permissions.allGranted -> "已授权"
                        else -> "还差 ${permissions.pendingCount} 项"
                    },
                ) {
                    PermissionRow(
                        title = "通知权限",
                        subtitle = "没有通知权限就收不到任何提醒",
                        granted = permissions.notifications == PermissionStatus.GRANTED,
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermissionLauncher.launch(
                                    android.Manifest.permission.POST_NOTIFICATIONS
                                )
                            } else {
                                viewModel.permissionIntent(context, SettingsViewModel.KEY_NOTIFICATIONS)
                                    ?.let { context.startActivity(it) }
                            }
                        },
                    )
                    PermissionRow(
                        title = "精确闹钟",
                        subtitle = "关闭后提醒可能延迟几分钟",
                        granted = permissions.exactAlarm != PermissionStatus.DENIED,
                        notApplicable = permissions.exactAlarm == PermissionStatus.NOT_APPLICABLE,
                        onClick = {
                            viewModel.permissionIntent(context, SettingsViewModel.KEY_EXACT_ALARM)
                                ?.let { runCatching { context.startActivity(it) } }
                            viewModel.refreshPermissions(context)
                        },
                    )
                    PermissionRow(
                        title = "电池优化白名单",
                        subtitle = "加入白名单后，长时间待机也能准时提醒",
                        granted = permissions.batteryOptimization != PermissionStatus.DENIED,
                        notApplicable = permissions.batteryOptimization == PermissionStatus.NOT_APPLICABLE,
                        onClick = {
                            viewModel.permissionIntent(context, SettingsViewModel.KEY_BATTERY)
                                ?.let { intent ->
                                    // The system dialog can reject the request; fall back to the
                                    // battery-optimisation list so the user is never stuck.
                                    runCatching { context.startActivity(intent) }.onFailure {
                                        runCatching {
                                            context.startActivity(
                                                Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                            )
                                        }
                                    }
                                }
                        },
                    )
                    if (!permissions.allGranted) {
                        Spacer(modifier = Modifier.height(6.dp))
                        TextButton(onClick = onOpenOnboarding) {
                            Text("查看权限引导（推荐首次使用）")
                        }
                    }
                }
            }

            // ------------------------------------------------------------ reminders
            item {
                ExpandableSettingsSection(
                    title = "提醒",
                    icon = Icons.Filled.Alarm,
                    // Says what the master switch is doing *and* how punctual the alarm class is:
                    // "提醒已开启" alone would be true even when every reminder is minutes late.
                    summary = if (!preferences.remindersEnabled) {
                        "提醒已关闭"
                    } else {
                        "提醒已开启 · " + if (preferences.exactAlarms) "精确闹钟" else "非精确闹钟"
                    },
                ) {
                    SwitchRow(
                        title = "开启用药提醒",
                        subtitle = "总开关；关闭后不再发送任何通知",
                        checked = preferences.remindersEnabled,
                        onCheckedChange = viewModel::setRemindersEnabled,
                    )
                    SwitchRow(
                        title = "精确闹钟",
                        subtitle = "使用系统精确闹钟，到点立即提醒",
                        checked = preferences.exactAlarms,
                        onCheckedChange = viewModel::setExactAlarms,
                    )
                    SwitchRow(
                        title = "顶栏横幅弹出",
                        subtitle = "到点时在屏幕顶部弹出横幅，并在锁屏上显示（推荐保持开启）",
                        checked = preferences.headsUpEnabled,
                        onCheckedChange = viewModel::setHeadsUpEnabled,
                    )
                    SwitchRow(
                        title = "震动",
                        subtitle = "提醒时同时震动（不响铃也能察觉）",
                        checked = preferences.vibrationEnabled,
                        onCheckedChange = viewModel::setVibrationEnabled,
                    )
                    SwitchRow(
                        title = "提醒铃声",
                        subtitle = "默认关闭。开启后播放应用内置铃声（不需要系统铃声，任何品牌手机都能出声）",
                        checked = preferences.soundEnabled,
                        onCheckedChange = viewModel::setSoundEnabled,
                    )
                    if (preferences.soundEnabled) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text("铃声", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "五种内置铃声，点一下即可试听；重装或换手机后依然是同一个声音。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            val selectedTone = ReminderTone.fromName(preferences.reminderTone)
                            ReminderTone.entries.forEach { tone ->
                                FilterChip(
                                    selected = tone == selectedTone,
                                    onClick = {
                                        viewModel.setReminderTone(tone)
                                        viewModel.previewTone(tone)
                                    },
                                    label = { Text(tone.label, style = MaterialTheme.typography.labelMedium) },
                                )
                            }
                            TextButton(onClick = { viewModel.previewTone(selectedTone) }) {
                                Text("试听")
                            }
                        }
                        TextButton(
                            onClick = { openAlertChannelSettings(context, preferences) },
                        ) { Text("打开系统的通知设置（声音被系统改掉时用这里）") }
                    }

                    // The custom-ringtone entrance, and it is deliberately outside the
                    // `soundEnabled` branch: a user who wants to make a ringtone should not have to
                    // find and flip a second switch first to be allowed to open the picker.
                    ActionRow(
                        icon = Icons.Filled.MusicNote,
                        title = "选择 / 裁剪铃声",
                        subtitle = "当前：$soundLabel",
                        onClick = { showRingtonePicker = true },
                    )
                    Text(
                        text = "可以从手机里选一首歌，裁出十几秒当提醒音。裁好的片段会复制一份存进药准时，" +
                            "所以以后删掉原歌、换手机，提醒的声音都不会变哑。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    SwitchRow(
                        title = "静音时仍然响铃",
                        subtitle = "把提醒当作闹钟处理，谨慎开启",
                        checked = preferences.overrideSilent,
                        onCheckedChange = viewModel::setOverrideSilent,
                    )
                    SwitchRow(
                        title = "未服药时提醒我",
                        subtitle = "超过计划时间仍未记录时再提醒一次",
                        checked = preferences.missedReminderEnabled,
                        onCheckedChange = viewModel::setMissedReminderEnabled,
                    )

                    NumberOptionRow(
                        title = "重复提醒间隔",
                        options = listOf(0, 5, 10, 15, 30),
                        selected = preferences.repeatReminderMinutes,
                        labelOf = { if (it == 0) "不重复" else "$it 分钟" },
                        onSelect = viewModel::setRepeatMinutes,
                    )
                    NumberOptionRow(
                        title = "「稍后提醒」时长",
                        options = listOf(5, 10, 15, 30),
                        selected = preferences.snoozeMinutes,
                        labelOf = { "$it 分钟" },
                        onSelect = viewModel::setSnoozeMinutes,
                    )
                    NumberOptionRow(
                        title = "未服药判定时间",
                        options = listOf(15, 30, 60, 120),
                        selected = preferences.missedGraceMinutes,
                        labelOf = { "$it 分钟" },
                        onSelect = viewModel::setMissedGraceMinutes,
                    )
                }
            }

            // ------------------------------------------------------- reminder timing
            //
            // The humanised half of the reminder pipeline. Everything here changes *how* a reminder
            // is delivered rather than whether it is, which is why it sits next to the reliability
            // card rather than inside the reminder card.
            item {
                // The sound label is computed once at the top of the screen - see `soundLabel` there.
                val ringMode = ReminderRingMode.fromName(preferences.ringMode)
                ExpandableSettingsSection(
                    title = "提醒方式",
                    icon = Icons.Filled.Schedule,
                    summary = "${ringMode.label} · $soundLabel",
                ) {
                    Text(
                        text = "提醒不追求精确到秒，而是尽量在你方便的时候、用你容易接受的方式告诉你。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    SwitchRow(
                        title = "提前预告",
                        subtitle = "服药时间前先提醒一次，让你有时间准备；它同时也是一次独立的提醒机会",
                        checked = preferences.preReminderEnabled,
                        onCheckedChange = viewModel::setPreReminderEnabled,
                    )
                    if (preferences.preReminderEnabled) {
                        NumberOptionRow(
                            title = "提前多久",
                            options = listOf(5, 10, 15, 30, 60),
                            selected = preferences.preReminderLeadMinutes,
                            labelOf = { "$it 分钟" },
                            onSelect = viewModel::setPreReminderLeadMinutes,
                        )
                    }
                    SwitchRow(
                        title = "合并同一时段的提醒",
                        subtitle = "几分钟内到期的多种药合并成一条通知，避免连续弹窗",
                        checked = preferences.digestEnabled,
                        onCheckedChange = viewModel::setDigestEnabled,
                    )
                    if (preferences.digestEnabled) {
                        NumberOptionRow(
                            title = "合并的时间范围",
                            options = listOf(10, 20, 30, 60),
                            selected = preferences.clusterWindowMinutes,
                            labelOf = { "$it 分钟内" },
                            onSelect = viewModel::setClusterWindowMinutes,
                        )
                    }
                    SwitchRow(
                        title = "「稍后提醒」后保留状态",
                        subtitle = "推迟后在通知栏留下「已推迟到几点」和「现在服用」按钮，不会直接消失",
                        checked = preferences.snoozeStateNotificationEnabled,
                        onCheckedChange = viewModel::setSnoozeStateNotificationEnabled,
                    )
                    SwitchRow(
                        title = "错过时间后提示补记",
                        subtitle = "迟到太久时不再响铃，改为安静地提示「如果已经吃过，点一下补记」",
                        checked = preferences.catchUpReminderEnabled,
                        onCheckedChange = viewModel::setCatchUpReminderEnabled,
                    )
                    if (preferences.catchUpReminderEnabled) {
                        NumberOptionRow(
                            title = "多久之后算「太晚了」",
                            options = listOf(60, 120, 180, 360),
                            selected = preferences.staleReminderMinutes,
                            labelOf = { if (it >= 60) "${it / 60} 小时" else "$it 分钟" },
                            onSelect = viewModel::setStaleReminderMinutes,
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    // ------------------------------------------------- «持续响铃»
                    //
                    // How a reminder behaves *after* it has been announced. It lives with 提醒方式
                    // rather than in 提醒可靠性 because it changes how the reminder sounds, not
                    // whether it arrives - and its whole purpose is the case where the user was not
                    // in the room for the first chime.
                    Text(
                        text = "响铃",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "只响一声很容易被错过。让提醒一直响到有人处理，是「没听见」这类漏服最直接的解法。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    ChipRow(
                        title = "响铃方式",
                        options = ReminderRingMode.entries,
                        selected = ringMode,
                        labelOf = { it.label },
                        onSelect = viewModel::setRingMode,
                    )
                    // The description is the reason each mode exists; a chip labelled "一直响到处理"
                    // does not by itself tell the user that answering the notification is what makes
                    // it stop.
                    Text(
                        text = ringMode.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Only the mode that uses them is shown: an option that would be ignored is worse
                    // than no option, because the user believes they set it.
                    if (ringMode == ReminderRingMode.UNTIL_ACTION) {
                        NumberOptionRow(
                            title = "最多响多久（分钟）",
                            options = listOf(1, 2, 3, 5, 10, 15),
                            selected = preferences.ringMaxMinutes,
                            labelOf = { "$it 分钟" },
                            onSelect = viewModel::setRingMaxMinutes,
                        )
                        Text(
                            text = "到点仍未处理就安静下来，通知会留在通知栏——手机放在包里时，这一条防止它响到没电。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (ringMode == ReminderRingMode.FIXED_TIMES) {
                        NumberOptionRow(
                            title = "响铃次数",
                            options = listOf(2, 3, 5, 10, 20),
                            selected = preferences.ringTimes,
                            labelOf = { "$it 次" },
                            onSelect = viewModel::setRingTimes,
                        )
                        NumberOptionRow(
                            title = "间隔（秒）",
                            options = listOf(5, 10, 20, 30, 60, 120),
                            selected = preferences.ringIntervalSeconds,
                            labelOf = { "$it 秒" },
                            onSelect = viewModel::setRingIntervalSeconds,
                        )
                    }
                }
            }

            // -------------------------------------------------------- quiet hours
            //
            // "Don't remind me between these hours." A medication reminder that wakes the household at
            // 3am gets the app uninstalled, so this matters more than it looks - but so does not losing
            // the dose, which is why the default is to hold the reminder until the window ends rather
            // than to drop it.
            item {
                ExpandableSettingsSection(
                    title = "免打扰时段",
                    icon = Icons.Filled.Bedtime,
                    summary = quietHoursSummary(preferences),
                ) {
                    Text(
                        text = "设一个时间段，这段时间内不会有声音、震动，也不会在锁屏上弹出——适合睡觉时间。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SwitchRow(
                        title = "开启免打扰时段",
                        subtitle = "默认关闭。关闭时提醒在任何时间都正常发出",
                        checked = preferences.quietHoursEnabled,
                        onCheckedChange = viewModel::setQuietHoursEnabled,
                        switchModifier = Modifier.testTag(
                            com.meditrack.ui.MediTrackTestTags.QUIET_HOURS_SWITCH
                        ),
                    )

                    if (preferences.quietHoursEnabled) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("时段", style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.height(6.dp))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            FilterChip(
                                selected = false,
                                onClick = { quietEditing = 0 },
                                label = {
                                    Text(
                                        "开始 " + DateTimeUtils.formatMinuteOfDay(
                                            preferences.quietHoursStartMinute,
                                            preferences.use24HourFormat,
                                        )
                                    )
                                },
                            )
                            FilterChip(
                                selected = false,
                                onClick = { quietEditing = 1 },
                                label = {
                                    Text(
                                        "结束 " + DateTimeUtils.formatMinuteOfDay(
                                            preferences.quietHoursEndMinute,
                                            preferences.use24HourFormat,
                                        )
                                    )
                                },
                            )
                        }
                        Text(
                            text = "跨午夜也可以：22:00 → 07:00 表示当晚到第二天早上。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        ChipRow(
                            title = "常用时段",
                            options = QUIET_PRESETS,
                            selected = preferences.quietHoursStartMinute to
                                preferences.quietHoursEndMinute,
                            labelOf = { (start, end) ->
                                DateTimeUtils.formatMinuteOfDay(start, true) + " – " +
                                    DateTimeUtils.formatMinuteOfDay(end, true)
                            },
                            onSelect = { (start, end) -> viewModel.setQuietHours(start, end) },
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        SwitchRow(
                            title = "时段内到点的提醒，顺延到结束后再发",
                            subtitle = if (preferences.quietHoursDeferEnabled) {
                                "开启（推荐）：时段内完全不响，等时段结束再提醒你一次，不会漏药"
                            } else {
                                "关闭：时段内到点的提醒只在通知栏静默显示一条，不响不震"
                            },
                            checked = preferences.quietHoursDeferEnabled,
                            onCheckedChange = viewModel::setQuietHoursDeferEnabled,
                        )
                        Text(
                            text = "两种方式在时段内都不会发出声音或震动。解锁补提醒同样只留一条静默通知，" +
                                "并且不消耗「提醒次数」。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ----------------------------------------------------- reminder reliability
            item {
                ExpandableSettingsSection(
                    title = "提醒可靠性",
                    icon = Icons.Filled.HealthAndSafety,
                    // The two facts that decide whether a reminder survives an aggressive ROM, plus
                    // the cadence that bounds a lost alarm.
                    summary = "自检 ${preferences.heartbeatMinutes} 分钟 · " +
                        if (preferences.guardServiceEnabled) "守护服务已开" else "守护服务已关",
                ) {
                    Text(
                        text = "提醒是否准时，取决于下面这些系统状态。应用会自己核对，并给出可执行的建议。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    val report = health
                    if (report == null) {
                        Text(
                            text = "正在自检…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        report.items.forEach { item ->
                            HealthRow(
                                label = item.label,
                                detail = item.detail,
                                ok = item.ok,
                                fixLabel = if (item.key == "heartbeat") null else "处理",
                                onFix = {
                                    viewModel.fixIntentFor(context, item.key)?.let {
                                        runCatching { context.startActivity(it) }
                                    }
                                    viewModel.refreshPermissions(context)
                                    viewModel.refreshHealth()
                                },
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "近 24 小时：投递 ${report.deliveredLast24h} 次，主动跳过 ${report.suppressedLast24h} 次",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = if (report.nextSelfCheckAt > 0) {
                                "下次自检：${DateTimeUtils.formatDateTime(report.nextSelfCheckAt)}"
                            } else {
                                "尚未运行过自检"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (report.worstDriftMillis > 0) {
                            Text(
                                text = "系统最大延迟：约 ${report.worstDriftMillis / 60_000} 分钟",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        report.recommendations.forEach { line ->
                            Text(
                                text = "· $line",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            TextButton(onClick = viewModel::repairReminders) {
                                Text("立即自检并修复")
                            }
                            TextButton(onClick = viewModel::sendTestNotification) {
                                Text("发送测试通知")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    // ------------------------------------------------- background survival
                    Text(
                        text = "如果提醒总是「打开应用才出现」，说明系统在后台把药准时清理掉了——" +
                            "它连同已排好的闹钟一起被取消，在再次打开应用前收不到任何提醒。下面的开关正是针对这种情况。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    SwitchRow(
                        title = "后台守护服务",
                        subtitle = "保持常驻通知，让系统不清理药准时；这是防止「打开应用才提醒」最有效的手段",
                        checked = preferences.guardServiceEnabled,
                        onCheckedChange = viewModel::setGuardServiceEnabled,
                    )
                    TextButton(onClick = { viewModel.openAutostartSettings(context) }) {
                        Text("打开系统「自启动 / 后台运行」设置")
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    SwitchRow(
                        title = "闹钟级提醒",
                        subtitle = "使用系统最高优先级的闹钟通道，不受省电模式影响；状态栏会显示一个闹钟图标",
                        checked = preferences.alarmClockAlarms,
                        onCheckedChange = viewModel::setAlarmClockAlarms,
                    )
                    SwitchRow(
                        title = "后台定期核对",
                        subtitle = "除闹钟自检外再用一份系统任务定期核对，两条路径互为备份",
                        checked = preferences.reliabilityWorkerEnabled,
                        onCheckedChange = viewModel::setReliabilityWorkerEnabled,
                    )
                    NumberOptionRow(
                        title = "自检频率",
                        options = listOf(10, 15, 30, 60),
                        selected = preferences.heartbeatMinutes,
                        labelOf = { "每 $it 分钟" },
                        onSelect = viewModel::setHeartbeatMinutes,
                    )
                    Text(
                        text = "每隔这么久，应用会重新核对全部提醒并补齐丢失的闹钟。" +
                            "数值越小越不容易漏提醒，耗电略高。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // -------------------------------------------------- reminder decision log
            item {
                ExpandableSettingsSection(
                    title = "最近的提醒决策",
                    icon = Icons.Filled.Info,
                    summary = if (auditLog.isEmpty()) "暂无记录" else "最近 ${auditLog.size} 条",
                ) {
                    if (auditLog.isEmpty()) {
                        Text(
                            text = "还没有记录。发生过一次提醒后，这里会显示每次提醒做了什么决定、" +
                                "以及为什么没有提醒。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        auditLog.forEach { event -> AuditRow(event) }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "记录只保留最近若干条，全部保存在本机。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ------------------------------------------------- unlock catch-up
            item {
                ExpandableSettingsSection(
                    title = "解锁补提醒",
                    icon = Icons.Filled.PhoneAndroid,
                    // Names the second chance out loud: this is the one mechanism that catches a dose
                    // whose alarm fired while nobody was looking.
                    summary = if (preferences.unlockReminderEnabled) {
                        "已开启 · 每个药最多 ${preferences.unlockReminderMaxPerDose} 次"
                    } else {
                        "已关闭"
                    },
                ) {
                    Text(
                        text = "提醒有可能正好落在手机锁屏、装在口袋里的那段时间：闹钟到点了，但没有人看见。" +
                            "开启后，只要你解锁手机（或重新打开药准时），应用会立刻检查所有" +
                            "「已经过了时间、还没有记录」的药，并合并成一条提醒告诉你——和系统闹钟是一个道理。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SwitchRow(
                        title = "解锁时补提醒没吃的药",
                        subtitle = "默认开启。已吃掉、已跳过、点了「稍后」且还没到时间的都不会打扰",
                        checked = preferences.unlockReminderEnabled,
                        onCheckedChange = viewModel::setUnlockReminderEnabled,
                        switchModifier = Modifier.testTag(
                            com.meditrack.ui.MediTrackTestTags.UNLOCK_REMINDER_SWITCH
                        ),
                    )

                    // The remaining controls are meaningless while the feature is off, so they are
                    // only shown once it is on - fewer knobs for anyone who does not want them.
                    if (preferences.unlockReminderEnabled) {
                        NumberOptionRow(
                            title = "每个药提醒多少次",
                            options = listOf(1, 2, 3, 5, 10),
                            selected = preferences.unlockReminderMaxPerDose,
                            labelOf = { "$it 次" },
                            onSelect = viewModel::setUnlockReminderMaxPerDose,
                        )
                        Text(
                            text = "次数从第一次解锁提醒开始算，每解锁一次最多用掉一次；每个药、每天各算各的。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        NumberOptionRow(
                            title = "两次补提醒之间至少间隔",
                            options = listOf(0, 5, 10, 15, 30),
                            selected = preferences.unlockReminderMinGapMinutes,
                            labelOf = { if (it == 0) "不限制" else "$it 分钟" },
                            onSelect = viewModel::setUnlockReminderMinGapMinutes,
                        )
                        val staleLabel = if (preferences.staleReminderMinutes >= 60) {
                            "${preferences.staleReminderMinutes / 60} 小时"
                        } else {
                            "${preferences.staleReminderMinutes} 分钟"
                        }
                        Text(
                            text = "只补提醒「太晚了」范围之内的药（当前：$staleLabel，可在「提醒方式」中修改）；" +
                                "超过这个范围只会安静地记为未服药。免打扰时段内只留一条静默通知，不会响。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        SwitchRow(
                            title = "全屏提醒（像闹钟一样）",
                            subtitle = "提醒时直接点亮屏幕并显示在锁屏之上；关闭时为横幅 + 震动",
                            checked = preferences.fullScreenReminderEnabled,
                            onCheckedChange = {
                                viewModel.setFullScreenReminderEnabled(context, it)
                            },
                        )
                        if (preferences.fullScreenReminderEnabled) {
                            val allowed = viewModel.canUseFullScreenIntent(context)
                            Text(
                                text = if (allowed) {
                                    "系统已允许全屏提醒。"
                                } else {
                                    "系统尚未允许全屏提醒，此时会退化为横幅 + 震动。" +
                                        "Android 14 及以上需要手动允许一次。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (allowed) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                            if (!allowed) {
                                TextButton(onClick = { viewModel.openFullScreenIntentSettings(context) }) {
                                    Text("去系统设置允许全屏提醒")
                                }
                            }
                        }
                    }
                }
            }

            // ---------------------------------------------------------- 复查提醒
            //
            // A review is not a dose: the answer to "吃多久要去复查" is not something the app can know
            // offline, so this section configures *when to speak up* and hands the actual question to
            // the browser or to the user's doctor. The app's job stops at not letting them forget.
            item {
                val reviewEngine = preferences.reviewEngine
                val configuredReviews = reviewSnapshots.count { it.progress?.isConfigured == true }
                val dueReviews = reviewSnapshots.count { it.isDue }
                ExpandableSettingsSection(
                    title = "复查提醒",
                    icon = Icons.Filled.EventAvailable,
                    summary = if (!preferences.reviewReminderEnabled) {
                        "已关闭"
                    } else {
                        buildString {
                            append("已开启")
                            if (preferences.reviewAdvanceNotice > 0) {
                                append(" · 提前 ${preferences.reviewAdvanceNotice} 次预告")
                            }
                            if (configuredReviews > 0) append(" · $configuredReviews 个在跟踪")
                        }
                    },
                    // The due list is a database read, so it is taken when the section is actually
                    // opened rather than paid for on every visit to the settings tab.
                    onExpandChanged = { expanded -> if (expanded) viewModel.refreshCacheUsage() },
                ) {
                    SwitchRow(
                        title = "复查提醒",
                        subtitle = "某种药吃到设定次数或天数时，提醒你该去复查了；没设置过的药不会打扰",
                        checked = preferences.reviewReminderEnabled,
                        onCheckedChange = viewModel::setReviewReminderEnabled,
                    )
                    if (preferences.reviewReminderEnabled) {
                        NumberOptionRow(
                            title = "提前预告",
                            options = listOf(0, 1, 3, 5, 7, 10),
                            selected = preferences.reviewAdvanceNotice,
                            labelOf = { if (it == 0) "关闭预告" else "还差 $it 次" },
                            onSelect = viewModel::setReviewAdvanceNotice,
                        )
                        Text(
                            text = "复查要提前挂号，所以只在那一天提醒没有用。提前预告会在还差几次时就轻声说一句；" +
                                "到点后才是那条需要点「我知道了」的红色提醒。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        ChipRow(
                            title = "搜索用哪个引擎",
                            options = ReviewSearchEngine.entries,
                            selected = reviewEngine,
                            labelOf = { it.label },
                            onSelect = viewModel::setReviewSearchEngine,
                        )
                        // The search stays in the user's own browser: nothing about their
                        // prescriptions is sent anywhere by this app.
                        Text(
                            text = "点「去搜索」时会用这台手机上已安装的浏览器打开，药准时本身不联网，也不会上传你吃了什么药。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        ReviewSearchSuffixField(
                            initial = preferences.reviewSearchSuffix,
                            onCommit = viewModel::setReviewSearchSuffix,
                        )
                        Text(
                            text = "会附加在搜索词后面，例如填「高血压」，搜索就变成" +
                                "「阿司匹林 吃多久需要去复查 高血压」。最多 40 个字。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "搜索结果是网上的内容，不能代替医生。是否该复查、复查什么项目，" +
                                "请以开药的医生说的为准——可以把结论填进药品的「复查」里。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(modifier = Modifier.height(4.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))

                        // Read-only by design. Starting a new round or changing a threshold belongs
                        // next to the medication it describes, where the dose history is; a second
                        // place to edit it would be a second place to disagree with itself.
                        Text(
                            text = "正在跟踪的复查（$configuredReviews 个）",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = if (dueReviews > 0) {
                                "其中 $dueReviews 个已经到复查时间了。"
                            } else {
                                "还没有到复查时间的药。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (dueReviews > 0) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        if (reviewSnapshots.isEmpty()) {
                            Text(
                                text = "还没有药品设置复查提醒。可以到「药品 → 该药 → 复查提醒」里填写：" +
                                    "吃多久、吃几次，由医生决定，药准时只负责数着。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            reviewSnapshots.forEach { snapshot ->
                                ReviewSnapshotRow(snapshot)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "开始新一轮、修改次数或关闭某个药的提醒，都在那个药的编辑页里操作。" +
                                    "这里只是把结果列出来，方便一眼看到谁快到了。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // ----------------------------------------------------------- appearance
            item {
                ExpandableSettingsSection(
                    title = "外观",
                    icon = Icons.Filled.Palette,
                    summary = "${preferences.themeMode.label} · ${preferences.fontScale.label}字号",
                ) {
                    ChipRow(
                        title = "主题",
                        options = ThemeMode.entries,
                        selected = preferences.themeMode,
                        labelOf = { it.label },
                        onSelect = viewModel::setThemeMode,
                    )
                    SwitchRow(
                        title = "动态取色（Material You）",
                        subtitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            "使用壁纸颜色作为主题色"
                        } else {
                            "需要 Android 12 及以上"
                        },
                        checked = preferences.useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                        enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                        onCheckedChange = viewModel::setDynamicColor,
                    )
                    ChipRow(
                        title = "主题色",
                        options = AccentColor.entries,
                        selected = preferences.accentColor,
                        labelOf = { it.label },
                        onSelect = viewModel::setAccent,
                    )
                    ChipRow(
                        title = "每周起始日",
                        options = WeekStart.entries,
                        selected = preferences.weekStart,
                        labelOf = { it.label },
                        onSelect = viewModel::setWeekStart,
                    )
                    SwitchRow(
                        title = "24 小时制",
                        subtitle = "关闭后使用上午/下午格式",
                        checked = preferences.use24HourFormat,
                        onCheckedChange = viewModel::setUse24Hour,
                    )
                }
            }

            // -------------------------------------------------------- accessibility
            item {
                ExpandableSettingsSection(
                    title = "适老与无障碍",
                    icon = Icons.Filled.Accessibility,
                    // "适老已开启" when the bundle is in place, otherwise the thing that actually
                    // decides how readable the app is - the font size.
                    summary = if (preferences.highContrast && preferences.simplifiedMode) {
                        "适老已开启"
                    } else {
                        "标准 · ${preferences.fontScale.label}字号"
                    },
                ) {
                    ChipRow(
                        title = "字体大小",
                        options = FontScale.entries,
                        selected = preferences.fontScale,
                        labelOf = { it.label },
                        onSelect = viewModel::setFontScale,
                    )
                    SwitchRow(
                        title = "高对比度",
                        subtitle = "加粗描边与状态文字，弱视用户更容易分辨",
                        checked = preferences.highContrast,
                        onCheckedChange = viewModel::setHighContrast,
                    )
                    SwitchRow(
                        title = "简化模式",
                        subtitle = "隐藏统计与次要信息，只保留今日用药",
                        checked = preferences.simplifiedMode,
                        onCheckedChange = viewModel::setSimplified,
                    )
                    SwitchRow(
                        title = "多服时弹窗确认",
                        subtitle = "单次剂量超过设定上限时先确认",
                        checked = preferences.confirmOverDose,
                        onCheckedChange = viewModel::setConfirmOverDose,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Card(
                        onClick = viewModel::applyElderlyPreset,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "一键开启适老模式",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                text = "大字体 + 高对比 + 简化模式 + 提醒全开",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }

            // ---------------------------------------------------------------- widget
            item {
                ExpandableSettingsSection(
                    title = "桌面小组件",
                    icon = Icons.Filled.Widgets,
                    summary = "${preferences.widgetItemLimit} 条 · " +
                        widgetRefreshLabel(preferences.widgetRefreshSeconds) + "刷新",
                ) {
                    NumberOptionRow(
                        title = "最多显示条目数",
                        options = listOf(2, 3, 4, 6),
                        selected = preferences.widgetItemLimit,
                        labelOf = { "$it 条" },
                        onSelect = viewModel::setWidgetItemLimit,
                    )
                    Text(
                        text = "小组件能显示几条，由它在桌面上的实际高度决定；这里设置的是上限。" +
                            "把小组件拖得更高，就能显示更多条。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    NumberOptionRow(
                        title = "刷新间隔",
                        options = listOf(10, 30, 60, 300, 600),
                        selected = preferences.widgetRefreshSeconds,
                        labelOf = { seconds ->
                            when {
                                seconds < 60 -> "$seconds 秒"
                                else -> "${seconds / 60} 分钟"
                            }
                        },
                        onSelect = viewModel::setWidgetRefreshSeconds,
                    )
                    Text(
                        text = "间隔越短，小组件越能及时反映变化（进入 30 分钟窗口、变成未服药、记录已服）。" +
                            "内容没有变化时不会重绘，所以短间隔并不会一直耗电。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!preferences.guardServiceEnabled) {
                        Text(
                            text = "⚠️ 后台守护服务已关闭，小组件只能每 15 分钟刷新一次。要使用 10 秒～10 分钟" +
                                "的间隔，请在「提醒可靠性」中开启后台守护服务。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    SwitchRow(
                        title = "显示已服用",
                        subtitle = "关闭后只显示还没吃的药（未服药仍会保留）",
                        checked = preferences.widgetShowCompleted,
                        onCheckedChange = viewModel::setWidgetShowCompleted,
                    )
                }
            }

            // ------------------------------------------------------- storage & cache
            //
            // Sits immediately above 数据与备份 because it is the safe counterpart of it: everything
            // here can be regenerated, and the section says so, which is what makes it reasonable to
            // offer a delete button next to the user's actual records.
            item {
                ExpandableSettingsSection(
                    title = "存储与缓存",
                    icon = Icons.Filled.CleaningServices,
                    summary = cacheSummary(cacheUsage),
                ) {
                    CacheUsageRow(
                        title = "自定义铃声缓存",
                        value = cacheUsageValue(cacheUsage),
                        detail = cacheUsageDetail(cacheUsage),
                    )
                    CacheUsageRow(
                        title = "提醒决策日志",
                        value = "${cacheUsage.auditEntries} 条",
                        detail = "记录每次提醒做了什么决定、以及为什么没有提醒。" +
                            "它只用于排查问题，清空不影响任何提醒或记录。",
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    CacheUsageRow(
                        title = "清空提醒日志",
                        value = "",
                        detail = "只删日志，不动铃声，也不动任何用药数据。",
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    AdaptiveButtonRow(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { confirmingCacheClear = true },
                            // Tagged because the label is generic and the button is the one control
                            // the cache tests have to reach reliably.
                            modifier = Modifier.testTag(MediTrackTestTags.CLEAR_CACHE_BUTTON),
                        ) { Text("清除缓存") }
                        TextButton(onClick = viewModel::clearAuditLog) { Text("只清空日志") }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "只会删除可以重新生成的东西：没有被使用的铃声文件，以及提醒日志。" +
                            "药品、服药时间、服药记录和所有设置都不会被删除。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ------------------------------------------------------------------ data
            item {
                ExpandableSettingsSection(
                    title = "数据与备份",
                    icon = Icons.Filled.Download,
                    summary = if (backups.isEmpty()) "还没有备份" else "已备份 ${backups.size} 份",
                ) {
                    Text(
                        text = "所有数据都保存在本机，不会上传到任何服务器。" +
                            "全应用只有一处联网：检查新版本（见下方「更新」）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ActionRow(
                        icon = Icons.Filled.Download,
                        title = "导出 JSON 备份",
                        subtitle = "包含药品、时间和全部服药记录",
                        onClick = { viewModel.exportBackup(csv = false) },
                    )
                    ActionRow(
                        icon = Icons.Filled.Download,
                        title = "导出 CSV 记录",
                        subtitle = "表格格式，方便交给医生查看",
                        onClick = { viewModel.exportBackup(csv = true) },
                    )
                    ActionRow(
                        icon = Icons.Filled.Upload,
                        title = "从文件导入",
                        subtitle = "从任意位置选一个备份文件导入",
                        onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                    )
                    lastExport?.let { entry ->
                        Spacer(modifier = Modifier.height(4.dp))
                        TextButton(onClick = { shareBackup(context, viewModel, entry) }) {
                            Text("分享刚导出的文件（${entry.name}）")
                        }
                    }
                }
            }

            // ------------------------------------------------------------ backup folder & list
            item {
                ExpandableSettingsSection(
                    title = "备份文件夹",
                    icon = Icons.Filled.Folder,
                    summary = backupFolder,
                ) {
                    Text(
                        text = "备份默认存在应用内部，其它应用看不到。选一个你能打开的文件夹" +
                            "（比如「下载」），备份就会直接出现在文件管理器里，也能直接导回来。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("当前位置", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = backupFolder,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Button(
                            onClick = { folderLauncher.launch(null) },
                            modifier = Modifier.testTag(MediTrackTestTags.BACKUP_FOLDER_BUTTON),
                        ) { Text("选择文件夹") }
                        if (backupFolder != "应用内部存储（默认）") {
                            TextButton(onClick = { viewModel.resetBackupFolder() }) {
                                Text("恢复默认位置")
                            }
                        }
                    }
                    Text(
                        text = "换文件夹时，已有的备份会自动复制过去，不会丢。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                ExpandableSettingsSection(
                    title = "本地备份",
                    icon = Icons.Filled.Restore,
                    // The count is the useful half of the old title; the position of the folder is
                    // already named one card above, so it is not repeated here.
                    summary = if (backups.isEmpty()) "暂无备份" else "${backups.size} 份",
                ) {
                    if (backups.isEmpty()) {
                        Text(
                            text = "这个文件夹里还没有备份。点上面的「导出 JSON 备份」生成一份。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = "按时间排序，最新在上面。点一条即可导入。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        backups.forEachIndexed { index, entry ->
                            BackupRow(
                                entry = entry,
                                isNewest = index == 0,
                                onImport = { pendingImport = entry },
                                onShare = { shareBackup(context, viewModel, entry) },
                            )
                        }
                    }
                }
            }

            // ----------------------------------------------------------------- update
            item {
                ExpandableSettingsSection(
                    title = "更新",
                    icon = Icons.Filled.SystemUpdate,
                    summary = if (preferences.autoUpdateCheck) "启动时自动检查" else "已关闭自动检查",
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("当前版本", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = BuildConfig.VERSION_NAME,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    SwitchRow(
                        title = "自动检查更新",
                        subtitle = "每 12 小时向 GitHub 查一次最新版本号。" +
                            "这是全应用唯一的联网行为：不带任何个人信息，也不会上传任何数据",
                        checked = preferences.autoUpdateCheck,
                        onCheckedChange = viewModel::setAutoUpdateCheck,
                    )
                    ActionRow(
                        icon = Icons.Filled.SystemUpdate,
                        title = "立即检查更新",
                        subtitle = "发现新版本时会提示，但不会强制更新",
                        onClick = { viewModel.checkForUpdatesNow(BuildConfig.VERSION_NAME) },
                    )
                }
            }

            // ----------------------------------------------------------------- other
            item {
                ExpandableSettingsSection(
                    title = "其他",
                    icon = Icons.Filled.Lock,
                    summary = if (preferences.appLockEnabled) "应用锁已开" else "应用锁已关",
                ) {
                    SwitchRow(
                        title = "应用锁",
                        subtitle = "打开应用时需要指纹或锁屏密码",
                        checked = preferences.appLockEnabled,
                        onCheckedChange = viewModel::setAppLock,
                    )
                    ActionRow(
                        icon = Icons.Filled.Info,
                        title = "关于与免责声明",
                        subtitle = "版本信息、使用范围与隐私说明",
                        onClick = onOpenAbout,
                    )
                }
            }
        }
    }
    }
}

/** A titled card; sections share one visual rhythm across the whole screen. */
@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MaterialTheme.prefs.cardCornerDp.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

/**
 * A section that shows its header and a one-line summary of what is currently set, and reveals its
 * controls only when tapped.
 *
 * ## Why the default is collapsed
 *
 * The screen grew to eighteen sections, most of which a given user never changes - and the ones that
 * *do* matter (a permission that was revoked, an alarm class that slipped to inexact) were buried
 * under forty switches. Collapsing everything inverts that: the first screenful becomes a table of
 * contents of the user's own configuration, and every line of it is a fact rather than a control.
 *
 * ## Why the summary is mandatory rather than optional
 *
 * A collapsed section that says nothing is a section the user has to open to find out whether it is
 * even relevant, which puts the cost straight back where collapsing was supposed to remove it. The
 * parameter is therefore not nullable: a caller has to decide what "what is set right now" means for
 * its own controls, and the answer is almost always short.
 *
 * @param title the section label, and the key its expanded state is remembered under
 * @param icon the leading glyph, matching [SettingsSection] so the two read as the same component
 * @param summary one short line describing the live values, e.g. "自检 15 分钟 · 守护服务已开"
 * @param initiallyExpanded only for a section that is useless closed; everything ships collapsed
 * @param onExpandChanged invoked with the new expanded state, so a caller can lazily load a detail
 *        that is too expensive to read for every visit to the screen
 */
@Composable
private fun ExpandableSettingsSection(
    title: String,
    icon: ImageVector,
    summary: String,
    initiallyExpanded: Boolean = false,
    onExpandChanged: (Boolean) -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    // Keyed by title and saveable, so rotating the phone - or coming back from the system Settings
    // app to grant a permission - does not fold away the section the user just opened. Keying by
    // title rather than by index matters for the same reason: a section can be added or reordered
    // without every other section's state moving with it.
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    // ~180ms: long enough to read as movement rather than a jump cut, short enough that tapping four
    // sections in a row does not leave the user waiting for the list to settle.
    val animationSpec = tween<IntSize>(durationMillis = SETTINGS_EXPAND_MILLIS)
    // The two spoken states of the header. Only the *state* is dynamic; the title comes from the
    // header's own text, so a screen reader reads "提醒方式，一直响到处理 · 清铃，已展开".
    val collapsedLabel = stringResource(R.string.settings_section_expand, title)
    val expandedLabel = stringResource(R.string.settings_section_collapse, title)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MaterialTheme.prefs.cardCornerDp.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    expanded = !expanded
                    onExpandChanged(expanded)
                }
                .padding(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics {
                    stateDescription = if (expanded) expandedLabel else collapsedLabel
                },
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    // One line, ellipsised rather than wrapped: the summary is a glance, and a
                    // two-line summary would make the collapsed list as tall as the old expanded one.
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // The chevron points where the content will go, which is the only affordance a
                // collapsed card needs. It is decorative here: the whole header is the target, and
                // the row's own text is what a screen reader announces.
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .defaultMinSize(
                            minWidth = MaterialTheme.prefs.minTouchTargetDp.dp,
                            minHeight = MaterialTheme.prefs.minTouchTargetDp.dp,
                        ),
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = animationSpec) + fadeIn(),
                exit = shrinkVertically(animationSpec = animationSpec) + fadeOut(),
            ) {
                // The gap and the padding live *inside* the animated block, so collapsing leaves no
                // trailing whitespace behind in the list.
                Column(modifier = Modifier.padding(top = 10.dp)) { content() }
            }
        }
    }
}

/**
 * One "what is this costing me" line inside 存储与缓存.
 *
 * Deliberately not a [SwitchRow]: there is nothing to toggle, and reusing a row that looks togglable
 * for a read-only figure is how a user learns to ignore both.
 */
@Composable
private fun CacheUsageRow(
    title: String,
    value: String,
    detail: String,
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The free-text suffix appended to every 复查 search.
 *
 * Kept as local draft state and committed on every change rather than on a "保存" button: the
 * repository clamps to [SettingsRepository.MAX_SEARCH_SUFFIX_LENGTH], and a field that silently
 * refuses the 41st character is less surprising than one that saves a value the user cannot see.
 */
@Composable
private fun ReviewSearchSuffixField(
    initial: String,
    onCommit: (String) -> Unit,
) {
    // Keyed on the stored value so an edit made elsewhere (an imported backup, say) is reflected, while
    // typing does not reset the cursor: the key only changes when the *stored* text does.
    var draft by rememberSaveable(initial) { mutableStateOf(initial) }
    OutlinedTextField(
        value = draft,
        onValueChange = { text ->
            // Hard-stopped at the same length the repository clamps to, so the user is never shown a
            // value that will not survive the write.
            draft = text.take(SettingsRepository.MAX_SEARCH_SUFFIX_LENGTH)
            onCommit(draft)
        },
        label = { Text("搜索附加词") },
        placeholder = { Text("例如：高血压") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * One medication's line in the read-only 复查 list.
 *
 * Read-only on purpose: a round is started and a threshold is changed next to the dose history they
 * describe, and a second place to edit them would be a second place for the two to disagree.
 */
@Composable
private fun ReviewSnapshotRow(snapshot: MedicationReviewSnapshot) {
    val progress = snapshot.progress
    Column(modifier = Modifier.padding(vertical = 5.dp)) {
        Text(
            text = snapshot.medication.name + " · " + (progress?.reachedLabel ?: "已服用 0 次"),
            style = MaterialTheme.typography.bodyMedium,
            color = if (snapshot.isDue) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Text(
            text = progress?.progressLabel ?: "未设置复查提醒",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The 免打扰时段 summary: the window in words, or the fact that there is no window.
 *
 * The times are formatted with the user's own 24-hour preference, because a summary that disagreed
 * with the chips one tap below it would read as a bug.
 */
private fun quietHoursSummary(preferences: UserPreferences): String {
    if (!preferences.quietHoursEnabled) return "已关闭"
    val start = DateTimeUtils.formatMinuteOfDay(
        preferences.quietHoursStartMinute,
        preferences.use24HourFormat,
    )
    val end = DateTimeUtils.formatMinuteOfDay(
        preferences.quietHoursEndMinute,
        preferences.use24HourFormat,
    )
    return "$start - $end · " + if (preferences.quietHoursDeferEnabled) "顺延" else "静默"
}

/** "30 秒" / "5 分钟" - the widget summary's cadence, matching the chips in that section. */
private fun widgetRefreshLabel(seconds: Int): String =
    if (seconds < 60) "$seconds 秒" else "${seconds / 60} 分钟"

/** How many 存储与缓存 bytes show in the collapsed header. */
private fun cacheSummary(usage: CacheUsage): String = buildString {
    append(formatBytes(usage.bytes))
    append(" · ${usage.clipCount} 个片段")
    if (usage.auditEntries > 0) append(" · 日志 ${usage.auditEntries} 条")
}

/** The clip line's headline: "1.2 MB（3 个片段）". */
private fun cacheUsageValue(usage: CacheUsage): String =
    "${formatBytes(usage.bytes)}（${usage.clipCount} 个片段）"

/**
 * The clip line's detail.
 *
 * The in-use count is the number that decides whether pressing 清除缓存 does anything at all, so it is
 * spelled out rather than left to the confirmation dialog.
 */
private fun cacheUsageDetail(usage: CacheUsage): String {
    if (usage.clipCount == 0) return "还没有自定义铃声；清除缓存不会影响内置铃声。"
    val unused = usage.clipCount - usage.inUseCount
    return if (unused == 0) {
        "其中 ${usage.inUseCount} 个仍在使用，没有可清除的片段。"
    } else {
        "其中 ${usage.inUseCount} 个仍在使用，$unused 个可清除。"
    }
}

/** A permission that must be granted in the system UI; tapping opens the right screen. */
@Composable
private fun PermissionRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    onClick: () -> Unit,
    notApplicable: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (granted) Icons.Filled.NotificationsActive else Icons.Filled.BatteryAlert,
            contentDescription = null,
            tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(end = 10.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = when {
                    notApplicable -> "此系统版本无需授权"
                    granted -> "已授权"
                    else -> subtitle
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
            )
        }
        if (!granted && !notApplicable) {
            TextButton(onClick = onClick) { Text("授权") }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    switchModifier: Modifier = Modifier,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Tapping the label must toggle too: it is what people try first, and half the row being
            // dead space is a target nobody can see.
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // switchModifier exists so a test can address this specific control by tag. The switch is a
        // sibling of the label, so it cannot be found from the label's text alone.
        //
        // onCheckedChange is null on purpose: the row owns the toggle, so a tap on the switch does not
        // fire both handlers and flip the value twice.
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            modifier = switchModifier,
        )
    }
}

/**
 * A row of chips for a small enum choice.
 *
 * Wraps with [FlowRow] instead of laying the options out in a single [Row]. At the larger font
 * presets - and especially once the system font scale is added on top - five options no longer fit on
 * one line, and a plain Row responds by squeezing them until the labels are unreadable. That is the
 * exact failure this layout exists to prevent: the people most affected are the ones who enlarged the
 * text precisely because they could not read it.
 */
@Composable
private fun <T> ChipRow(
    title: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(labelOf(option), style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
    }
}

/** A row of chips for a numeric choice such as "10 分钟". */
@Composable
private fun NumberOptionRow(
    title: String,
    options: List<Int>,
    selected: Int,
    labelOf: (Int) -> String,
    onSelect: (Int) -> Unit,
) = ChipRow(title, options, selected, labelOf, onSelect)

/**
 * A tappable action with an icon and a chevron.
 *
 * The click lives on the **row**, not on the chevron. It used to be an [IconButton] wrapping only the
 * chevron, which made a 96px square the entire target: tapping the row's title - the obvious thing to
 * do, and what the whole row's appearance invites - did nothing at all. Reported from a real device as
 * "检查更新点了没反应".
 */
@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 10.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = "打开 $title",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One line of the self-check.
 *
 * [onFix] is only offered when the platform actually has a settings screen that can resolve the
 * item. A row that says "需要处理" with no way to act on it is worse than no row at all, so the
 * button is hidden rather than shown disabled.
 */
@Composable
private fun HealthRow(
    label: String,
    detail: String,
    ok: Boolean,
    fixLabel: String?,
    onFix: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(end = 10.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
            )
        }
        if (!ok && fixLabel != null) {
            TextButton(onClick = onFix) { Text(fixLabel) }
        }
    }
}

/**
 * One entry from the reminder audit trail, phrased for a person rather than for a log file.
 *
 * This is the answer to "why didn't it remind me?": a suppressed entry names the reason
 * ("距离上次提醒还不够久"), a delivered one names the trigger and how late it was.
 */
/**
 * One backup file in the list: when, how big, and the two things you can do with it.
 *
 * The newest entry is labelled rather than merely sorted first, because "which one do I want" is the
 * question the list exists to answer, and the answer is almost always the top one.
 */
@Composable
private fun BackupRow(
    entry: BackupEntry,
    isNewest: Boolean,
    onImport: () -> Unit,
    onShare: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onImport)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (entry.name.endsWith(".csv", true)) "CSV 记录" else "JSON 备份",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (isNewest) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "最新",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = buildString {
                    append(
                        if (entry.modifiedAtMillis > 0L) {
                            DateTimeUtils.formatDateTime(entry.modifiedAtMillis)
                        } else {
                            entry.name
                        }
                    )
                    if (entry.sizeBytes > 0L) append(" · " + formatBytes(entry.sizeBytes))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onShare) {
            Icon(
                imageVector = Icons.Filled.Upload,
                contentDescription = "分享 ${entry.name}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Human sizes for the backup list; a 200 KB file should not read as "204800 字节". */
internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/**
 * Opens the system page for the app's audible channel.
 *
 * The last word on whether a reminder rings belongs to the system: MIUI, ColorOS and others can
 * override or mute a channel's sound after the app sets it. Sending the user straight to the exact
 * page (rather than the app's notification list) is the difference between a fixable problem and a
 * mystery.
 */
private fun openAlertChannelSettings(context: android.content.Context, preferences: UserPreferences) {
    runCatching {
        val channel = AlertChannel.id(
            ReminderTone.fromName(preferences.reminderTone),
            preferences.vibrationEnabled,
            preferences.soundUri,
        )
        context.startActivity(
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, channel)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** Hands a backup to the share sheet, wherever it lives. */
private fun shareBackup(
    context: android.content.Context,
    viewModel: SettingsViewModel,
    entry: BackupEntry,
) {
    runCatching {
        val uri = viewModel.shareUri(entry)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(share, "分享备份文件"))
    }
}

@Composable
private fun AuditRow(event: ReminderEvent) {
    val delivered = event.delivered
    Column(modifier = Modifier.padding(vertical = 5.dp)) {
        Text(
            text = DateTimeUtils.formatDateTime(event.timestamp) + " · " +
                (event.trigger?.label ?: event.triggerName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = buildString {
                append(if (delivered) "已提醒" else "未提醒")
                event.suppression?.let { append(" · " + it.label) } ?: run {
                    if (event.detail.isNotBlank()) append(" · " + event.detail)
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (delivered) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
