package com.meditrack.ui.settings

import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meditrack.data.prefs.AccentColor
import com.meditrack.data.prefs.FontScale
import com.meditrack.data.prefs.ThemeMode
import com.meditrack.data.prefs.WeekStart
import com.meditrack.core.theme.prefs
import com.meditrack.core.util.DateTimeUtils
import com.meditrack.data.backup.BackupEntry
import com.meditrack.data.local.entity.ReminderEvent
import com.meditrack.domain.reminder.ReminderHealth
import com.meditrack.ui.MediTrackTestTags
import com.meditrack.ui.components.TimePickerDialog

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
    val backupFolder by viewModel.backupFolder.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // A listed backup is imported only after an explicit confirmation: it replaces everything.
    var pendingImport by remember { mutableStateOf<BackupEntry?>(null) }
    // Which end of the do-not-disturb window the time dialog is editing: 0 = start, 1 = end.
    var quietEditing by remember { mutableStateOf<Int?>(null) }

    // The self-check is a snapshot of system state, so it is read when the screen appears and
    // refreshed on every resume - the same treatment the permission rows already get.
    LaunchedEffect(Unit) {
        viewModel.refreshHealth()
        // The folder is the source of truth for the backup list, so re-read it rather than caching.
        viewModel.refreshBackups()
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
                SettingsSection("权限", Icons.Filled.NotificationsActive) {
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
                SettingsSection("提醒", Icons.Filled.Alarm) {
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
                        subtitle = "默认关闭。开启后会播放系统默认闹钟铃声，到点会出声",
                        checked = preferences.soundEnabled,
                        onCheckedChange = viewModel::setSoundEnabled,
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
                SettingsSection("提醒方式", Icons.Filled.Schedule) {
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
                }
            }

            // -------------------------------------------------------- quiet hours
            //
            // "Don't remind me between these hours." A medication reminder that wakes the household at
            // 3am gets the app uninstalled, so this matters more than it looks - but so does not losing
            // the dose, which is why the default is to hold the reminder until the window ends rather
            // than to drop it.
            item {
                SettingsSection("免打扰时段", Icons.Filled.Bedtime) {
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
                SettingsSection("提醒可靠性", Icons.Filled.HealthAndSafety) {
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
                SettingsSection("最近的提醒决策", Icons.Filled.Info) {
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
                SettingsSection("解锁补提醒", Icons.Filled.PhoneAndroid) {
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

            // ----------------------------------------------------------- appearance
            item {
                SettingsSection("外观", Icons.Filled.Palette) {
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
                SettingsSection("适老与无障碍", Icons.Filled.Accessibility) {
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
                SettingsSection("桌面小组件", Icons.Filled.Widgets) {
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

            // ------------------------------------------------------------------ data
            item {
                SettingsSection("数据", Icons.Filled.Download) {
                    Text(
                        text = "所有数据都保存在本机，不会上传到任何服务器。",
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
                SettingsSection("备份文件夹", Icons.Filled.Folder) {
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
                SettingsSection("本地备份（${backups.size}）", Icons.Filled.Restore) {
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

            // ----------------------------------------------------------------- other
            item {
                SettingsSection("其他", Icons.Filled.Lock) {
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
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
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

/** A tappable action with an icon and a chevron. */
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
        IconButton(onClick = onClick) {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = "打开 $title",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
