package com.meditrack.ui.settings

import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Widgets
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
import androidx.compose.runtime.remember
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
import com.meditrack.data.local.entity.ReminderEvent
import com.meditrack.domain.reminder.ReminderHealth

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
    val snackbarHostState = remember { SnackbarHostState() }

    // The self-check is a snapshot of system state, so it is read when the screen appears and
    // refreshed on every resume - the same treatment the permission rows already get.
    LaunchedEffect(Unit) { viewModel.refreshHealth() }

    // Permission state lives in the system, not in our state; re-read it on every resume so
    // coming back from the Settings app updates the screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions(context)
                viewModel.refreshHealth()
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

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onMessageShown()
        }
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
                    SwitchRow(
                        title = "免打扰时段内顺延提醒",
                        subtitle = "免打扰时段内到点的提醒等到时段结束再发，而不是静默地发一条没人看得到的通知",
                        checked = preferences.quietHoursDeferEnabled,
                        onCheckedChange = viewModel::setQuietHoursDeferEnabled,
                    )
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

            // ------------------------------------------------- usage monitoring
            item {
                SettingsSection("手机未使用时暂缓提醒", Icons.Filled.PhoneAndroid) {
                    Text(
                        text = "开启后：手机长时间没人用时，到点的提醒不会响，而是先记下来；" +
                            "等你重新拿起手机（解锁或亮屏）的那一刻，立刻补发通知。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SwitchRow(
                        title = "启用该功能",
                        subtitle = "默认关闭。关闭时应用不会读取任何手机使用状态",
                        checked = preferences.idleDeferralEnabled,
                        onCheckedChange = viewModel::setIdleDeferralEnabled,
                        switchModifier = Modifier.testTag(
                            com.meditrack.ui.MediTrackTestTags.IDLE_DEFERRAL_SWITCH
                        ),
                    )

                    // The remaining controls are meaningless while the feature is off, so they are
                    // only shown once it is on - fewer knobs for the people who never turn it on.
                    if (preferences.idleDeferralEnabled) {
                        NumberOptionRow(
                            title = "多久算「没人用」",
                            options = listOf(10, 15, 30, 60, 120),
                            selected = preferences.idleThresholdMinutes,
                            labelOf = { "$it 分钟" },
                            onSelect = viewModel::setIdleThresholdMinutes,
                        )
                        SwitchRow(
                            title = "息屏时也暂缓",
                            subtitle = "即使还没到上面的时间，只要屏幕是黑的就先不发",
                            checked = preferences.deferWhileScreenOff,
                            onCheckedChange = viewModel::setDeferWhileScreenOff,
                        )
                        Text(
                            text = "补发时会合并成一条「N 项用药提醒」，点开就能看到具体是哪些药；" +
                                "如果已经超过上面的判定时间，会按「未服药」记录。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                            "例如选「4 条」时，4×2 的小组件仍然只显示 2 条。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    NumberOptionRow(
                        title = "刷新间隔",
                        options = listOf(15, 30, 60),
                        selected = preferences.widgetRefreshMinutes,
                        labelOf = { "$it 分钟" },
                        onSelect = viewModel::setWidgetRefreshMinutes,
                    )
                    SwitchRow(
                        title = "显示加减按钮",
                        subtitle = "可以直接在桌面上记录服药数量",
                        checked = preferences.widgetQuickActions,
                        onCheckedChange = viewModel::setWidgetQuickActions,
                    )
                    SwitchRow(
                        title = "显示已服用",
                        subtitle = "关闭后只显示还没吃的药",
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
                        onClick = { viewModel.exportBackup(context, csv = false) },
                    )
                    ActionRow(
                        icon = Icons.Filled.Download,
                        title = "导出 CSV 记录",
                        subtitle = "表格格式，方便交给医生查看",
                        onClick = { viewModel.exportBackup(context, csv = true) },
                    )
                    ActionRow(
                        icon = Icons.Filled.Upload,
                        title = "从文件导入",
                        subtitle = "会覆盖当前所有数据，请先导出备份",
                        onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                    )
                    lastExport?.let { file ->
                        Spacer(modifier = Modifier.height(4.dp))
                        TextButton(
                            onClick = {
                                runCatching {
                                    val uri = viewModel.exportUri(context, file)
                                    val share = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/octet-stream"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(share, "分享备份文件")
                                    )
                                }
                            },
                        ) { Text("分享刚导出的文件（${file.name}）") }
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

/** A row of chips for a small enum choice. */
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
