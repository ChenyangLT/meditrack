package com.meditrack.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meditrack.data.prefs.AccentColor
import com.meditrack.data.prefs.FontScale
import com.meditrack.data.prefs.SettingsRepository
import com.meditrack.data.prefs.ThemeMode
import com.meditrack.data.prefs.UserPreferences
import com.meditrack.data.prefs.WeekStart
import com.meditrack.data.repository.DoseRepository
import com.meditrack.core.util.DateTimeUtils
import com.meditrack.data.backup.BackupEntry
import com.meditrack.data.backup.BackupFolder
import com.meditrack.data.backup.BackupRepository
import com.meditrack.data.backup.BackupStore
import com.meditrack.data.update.UpdateCheckResult
import com.meditrack.data.update.UpdateInfo
import com.meditrack.data.update.UpdateRepository
import com.meditrack.data.local.entity.ReminderEvent
import com.meditrack.domain.reminder.ReminderAudit
import com.meditrack.domain.reminder.ReminderEngine
import com.meditrack.domain.reminder.ReminderGuardService
import com.meditrack.domain.reminder.ReminderHealth
import com.meditrack.domain.reminder.ReminderHealthChecker
import com.meditrack.domain.reminder.ReminderHeartbeat
import com.meditrack.domain.reminder.ReminderReport
import com.meditrack.domain.reminder.ReminderTrigger
import com.meditrack.domain.reminder.ReminderWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Whether a permission the reminder pipeline depends on is currently granted.
 *
 * The settings screen shows all three together because a reminder that works needs *all* of them:
 * notifications to be seen, the exact-alarm capability to be on time, and the battery-optimisation
 * exemption to survive Doze overnight.
 */
enum class PermissionStatus { GRANTED, DENIED, NOT_APPLICABLE }

data class PermissionSnapshot(
    val notifications: PermissionStatus = PermissionStatus.DENIED,
    val exactAlarm: PermissionStatus = PermissionStatus.DENIED,
    val batteryOptimization: PermissionStatus = PermissionStatus.DENIED,
) {
    /** True when everything the app can ask for is already granted. */
    val allGranted: Boolean
        get() = notifications == PermissionStatus.GRANTED &&
            exactAlarm != PermissionStatus.DENIED &&
            batteryOptimization != PermissionStatus.DENIED

    /** Number of items still needing the user's attention; drives the badge on the tab. */
    val pendingCount: Int
        get() = listOf(notifications, exactAlarm, batteryOptimization)
            .count { it == PermissionStatus.DENIED }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val reminderEngine: ReminderEngine,
    private val healthChecker: ReminderHealthChecker,
    private val heartbeat: ReminderHeartbeat,
    private val reminderAudit: ReminderAudit,
    private val doseRepository: DoseRepository,
    private val backupRepository: BackupRepository,
    private val backupStore: BackupStore,
    private val updateRepository: UpdateRepository,
    private val app: android.app.Application,
) : ViewModel() {

    val preferences: StateFlow<UserPreferences> = settingsRepository.preferences
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = UserPreferences(),
        )

    private val _permissions = MutableStateFlow(PermissionSnapshot())
    val permissions: StateFlow<PermissionSnapshot> = _permissions.asStateFlow()

    /**
     * The self-check report, or null until it is first requested.
     *
     * Loaded on demand rather than continuously: it reads DataStore and the audit table, and it is
     * only ever looked at while the settings screen is open.
     */
    private val _health = MutableStateFlow<ReminderHealth?>(null)
    val health: StateFlow<ReminderHealth?> = _health.asStateFlow()

    private val _auditLog = MutableStateFlow<List<ReminderEvent>>(emptyList())
    val auditLog: StateFlow<List<ReminderEvent>> = _auditLog.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Set when a user-initiated check finds a newer release, so the screen can show the dialog. */
    private val _updateFound = MutableStateFlow<UpdateInfo?>(null)
    val updateFound: StateFlow<UpdateInfo?> = _updateFound.asStateFlow()

    private val _lastExport = MutableStateFlow<BackupEntry?>(null)
    /** The most recent successful export, offered to the user as "分享" instead of a bare path. */
    val lastExport: StateFlow<BackupEntry?> = _lastExport.asStateFlow()

    /** Where exports go, in words: the picked folder's name, or "应用内部存储（默认）". */
    private val _backupFolder = MutableStateFlow("应用内部存储（默认）")
    val backupFolder: StateFlow<String> = _backupFolder.asStateFlow()

    /**
     * Backups the app can see right now, newest first.
     *
     * Listed from the folder itself rather than remembered in the database: the folder is the source
     * of truth, so a file the user copied in by hand shows up too.
     */
    private val _backups = MutableStateFlow<List<BackupEntry>>(emptyList())
    val backups: StateFlow<List<BackupEntry>> = _backups.asStateFlow()

    /** Re-reads the folder and its contents. Safe to call on every screen resume. */
    fun refreshBackups() {
        viewModelScope.launch {
            runCatching {
                _backupFolder.value = backupStore.folderLabel()
                _backups.value = backupStore.list()
            }
        }
    }

    /**
     * Points backups at [uri] and moves everything already backed up into it.
     *
     * Both the folder that was in use and the app-private default are copied from, so choosing a
     * folder never strands the backups that came before it.
     */
    fun chooseBackupFolder(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val previous = backupStore.folder()
                backupStore.setFolder(uri)
                val moved = backupStore.migrate(
                    from = listOf(previous, BackupFolder.AppPrivate),
                    to = BackupFolder.Tree(uri),
                )
                _message.value = when {
                    moved.copied > 0 -> "已转移 ${moved.copied} 个备份文件到新文件夹"
                    else -> "备份文件夹已更新"
                }
            }.onFailure {
                _message.value = "无法使用该文件夹：${it.message ?: "未知错误"}"
            }
            refreshBackups()
        }
    }

    /** Back to app-private storage; the files in the old folder are left where they are. */
    fun resetBackupFolder() {
        viewModelScope.launch {
            runCatching { backupStore.clearFolder() }
            _message.value = "已恢复为应用内部存储"
            refreshBackups()
        }
    }

    /** Imports one of the listed backups, no file picker needed. */
    fun importListedBackup(entry: BackupEntry) {
        viewModelScope.launch {
            runCatching {
                val backup = withContext(Dispatchers.IO) {
                    backupStore.read(entry).use { backupRepository.parseBackup(it) }
                }
                backupRepository.importBackup(backup)
            }.onSuccess { result ->
                rescheduleEverything()
                doseRepository.notifyWidgetRefresh()
                _message.value = "已从 ${entry.name} 导入 ${result.medications} 条药品、${result.doseLogs} 条记录"
            }.onFailure {
                _message.value = "导入失败：${it.message ?: "未知错误"}"
            }
        }
    }

    /** A shareable uri for a listed backup, so it can be sent off the device. */
    fun shareUri(entry: BackupEntry): Uri = backupStore.shareUri(entry)

    // ------------------------------------------------------------------ updates

    fun setAutoUpdateCheck(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoUpdateCheck(enabled)
            _message.value = if (enabled) "已开启自动检查更新" else "已关闭自动检查更新"
        }
    }

    /**
     * A check the user asked for.
     *
     * [force] means the twelve-hour interval and the "以后再说" memory are both bypassed: asking
     * explicitly is a request to be told.
     */
    fun checkForUpdatesNow(currentVersion: String) {
        viewModelScope.launch {
            when (val result = updateRepository.check(currentVersion, force = true)) {
                is UpdateCheckResult.Available -> _updateFound.value = result.info
                is UpdateCheckResult.Dismissed -> _updateFound.value = result.info
                UpdateCheckResult.UpToDate -> _message.value = "已是最新版本（$currentVersion）"
                is UpdateCheckResult.Failed -> _message.value = "检查失败：${result.reason}"
            }
        }
    }

    fun dismissUpdate(info: UpdateInfo) {
        viewModelScope.launch { updateRepository.dismiss(info) }
        _updateFound.value = null
    }

    /** Reads the live permission state; call from `ON_RESUME` so returning from Settings refreshes. */
    fun refreshPermissions(context: Context) {
        val notifications = if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            PermissionStatus.GRANTED
        } else {
            PermissionStatus.DENIED
        }

        val exactAlarm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (heartbeat.canScheduleExactAlarms()) PermissionStatus.GRANTED else PermissionStatus.DENIED
        } else {
            PermissionStatus.NOT_APPLICABLE
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val battery = when {
            powerManager == null -> PermissionStatus.NOT_APPLICABLE
            powerManager.isIgnoringBatteryOptimizations(context.packageName) -> PermissionStatus.GRANTED
            else -> PermissionStatus.DENIED
        }

        _permissions.value = PermissionSnapshot(
            notifications = notifications,
            exactAlarm = exactAlarm,
            batteryOptimization = battery,
        )
    }

    /** Loads the full self-check report and the recent audit log. */
    fun refreshHealth() {
        viewModelScope.launch {
            runCatching { _health.value = healthChecker.check() }
            runCatching { _auditLog.value = reminderAudit.recent(AUDIT_LOG_LIMIT) }
        }
    }

    /**
     * Rebuilds the entire schedule and reports what it did.
     *
     * This is the "纠错" button: it cancels every alarm the app has armed, re-derives the schedule
     * from the database, resets the measured drift, and tells the user in plain language what it
     * fixed - which is far more useful than a spinner that says "修复中".
     */
    fun repairReminders() {
        viewModelScope.launch {
            runCatching { healthChecker.repair() }
                .onSuccess { report -> _message.value = describeReport(report) }
                .onFailure { _message.value = "修复失败：${it.message ?: "未知错误"}" }
            refreshHealth()
        }
    }

    /** Posts a real notification so the user can confirm the whole chain works on this device. */
    fun sendTestNotification() {
        val enabled = healthChecker.sendTestNotification()
        _message.value = if (enabled) {
            "已发送测试通知，请查看通知栏"
        } else {
            "系统通知权限未开启，无法发送测试通知"
        }
    }

    /** Opens the system screen that can fix one self-check item, if there is one. */
    fun fixIntentFor(context: Context, key: String): Intent? = healthChecker.fixIntent(key)

    private fun describeReport(report: ReminderReport): String = when {
        report.disabled -> "提醒总开关已关闭，没有需要修复的闹钟"
        else -> buildString {
            append("已重建提醒：")
            append("投递 ${report.delivered} 条，")
            append("重排 ${report.armedDoses} 项")
            if (report.corrected > 0) append("，纠正 ${report.corrected} 项异常时间")
            if (report.deferred > 0) append("，顺延 ${report.deferred} 项免打扰提醒")
            if (report.unlocked > 0) append("，解锁补提醒 ${report.unlocked} 项未服药")
        }
    }

    /**
     * Opens the relevant system screen for a permission.
     *
     * Each of these is a *request* the app cannot fulfil itself, so the UI deep-links into the
     * right Settings page rather than showing a dead "not granted" label.
     */
    fun permissionIntent(context: Context, which: String): Intent? = when (which) {
        KEY_NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        KEY_EXACT_ALARM -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        } else {
            null
        }
        KEY_BATTERY -> @Suppress("BatteryLife")
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        else -> null
    }

    // ------------------------------------------------------------ preferences

    fun setThemeMode(value: ThemeMode) = update { settingsRepository.setThemeMode(value) }
    fun setDynamicColor(value: Boolean) = update { settingsRepository.setDynamicColor(value) }
    fun setAccent(value: AccentColor) = update { settingsRepository.setAccentColor(value) }
    fun setFontScale(value: FontScale) = update { settingsRepository.setFontScale(value) }
    fun setHighContrast(value: Boolean) = update { settingsRepository.setHighContrast(value) }
    fun setSimplified(value: Boolean) = update { settingsRepository.setSimplifiedMode(value) }
    fun setUse24Hour(value: Boolean) = update { settingsRepository.setUse24Hour(value) }
    fun setWeekStart(value: WeekStart) = update { settingsRepository.setWeekStart(value) }

    fun setRemindersEnabled(value: Boolean) = update {
        settingsRepository.setRemindersEnabled(value)
        // reconcile() also tears the schedule down when the switch is off, so one call covers both
        // directions - the old code needed a separate cancelEverything() path that could drift.
        reminderEngine.reconcile(ReminderTrigger.SETTINGS_CHANGED)
        refreshHealthInternal()
    }

    fun setExactAlarms(value: Boolean) = update {
        settingsRepository.setExactAlarms(value)
        rescheduleEverything()
    }

    fun setSoundEnabled(value: Boolean) = update { settingsRepository.setSoundEnabled(value) }
    fun setVibrationEnabled(value: Boolean) = update { settingsRepository.setVibrationEnabled(value) }
    fun setHeadsUpEnabled(value: Boolean) = update { settingsRepository.setHeadsUpEnabled(value) }
    fun setOverrideSilent(value: Boolean) = update { settingsRepository.setOverrideSilent(value) }
    fun setRepeatMinutes(value: Int) = update {
        settingsRepository.setRepeatMinutes(value)
        rescheduleEverything()
    }
    fun setSnoozeMinutes(value: Int) = update { settingsRepository.setSnoozeMinutes(value) }
    fun setMissedGraceMinutes(value: Int) = update {
        settingsRepository.setMissedGraceMinutes(value)
        rescheduleEverything()
    }
    fun setMissedReminderEnabled(value: Boolean) = update { settingsRepository.setMissedReminderEnabled(value) }
    fun setQuietHoursEnabled(value: Boolean) = update {
        settingsRepository.setQuietHoursEnabled(value)
        rescheduleEverything()
    }
    fun setQuietHours(start: Int, end: Int) = update {
        settingsRepository.setQuietHours(start, end)
        rescheduleEverything()
    }

    // ------------------------------- reliability / humanised timing

    fun setPreReminderEnabled(value: Boolean) = update {
        settingsRepository.setPreReminderEnabled(value)
        rescheduleEverything()
    }

    fun setPreReminderLeadMinutes(value: Int) = update {
        settingsRepository.setPreReminderLeadMinutes(value)
        rescheduleEverything()
    }

    fun setClusterWindowMinutes(value: Int) = update {
        settingsRepository.setClusterWindowMinutes(value)
        rescheduleEverything()
    }

    fun setDigestEnabled(value: Boolean) = update {
        settingsRepository.setDigestEnabled(value)
        rescheduleEverything()
    }

    fun setSnoozeStateNotificationEnabled(value: Boolean) =
        update { settingsRepository.setSnoozeStateNotificationEnabled(value) }

    fun setQuietHoursDeferEnabled(value: Boolean) = update {
        settingsRepository.setQuietHoursDeferEnabled(value)
        rescheduleEverything()
    }

    fun setCatchUpReminderEnabled(value: Boolean) = update {
        settingsRepository.setCatchUpReminderEnabled(value)
        rescheduleEverything()
    }

    fun setStaleReminderMinutes(value: Int) = update {
        settingsRepository.setStaleReminderMinutes(value)
        rescheduleEverything()
    }

    fun setHeartbeatMinutes(value: Int) = update {
        settingsRepository.setHeartbeatMinutes(value)
        rescheduleEverything()
        _message.value = "自检频率已改为每 $value 分钟一次"
    }

    fun setAlarmClockAlarms(value: Boolean) = update {
        settingsRepository.setAlarmClockAlarms(value)
        rescheduleEverything()
        _message.value = if (value) "已开启闹钟级提醒（状态栏会显示闹钟图标）" else "已关闭闹钟级提醒"
    }

    fun setReliabilityWorkerEnabled(value: Boolean) = update {
        settingsRepository.setReliabilityWorkerEnabled(value)
        ReminderWorker.apply(value, androidx.work.WorkManager.getInstance(app))
    }

    /**
     * Turns the background guard service on or off.
     *
     * Applied immediately rather than at the next launch: a user who has just been told their
     * reminders are being cleared expects switching this on to fix it now, not tomorrow.
     */
    fun setGuardServiceEnabled(value: Boolean) = update {
        settingsRepository.setGuardServiceEnabled(value)
        if (value) {
            ReminderGuardService.ensureRunning(app)
        } else {
            ReminderGuardService.stop(app)
        }
        _message.value = if (value) {
            "已开启后台守护，请同时允许「自启动」以获得最佳效果"
        } else {
            "已关闭后台守护；系统清理后台后将无法提醒"
        }
        refreshHealthInternal()
    }

    /** Opens the vendor's autostart screen, falling back to this app's settings page. */
    fun openAutostartSettings(context: Context) {
        val target = healthChecker.autostartIntent()
            ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null))
        runCatching { context.startActivity(target) }
            .onFailure { _message.value = "无法打开系统设置，请手动在「设置 → 应用管理」中允许自启动" }
    }

    // -------------------------------------------------------- unlock catch-up

    /**
     * Turns the "speak up when the phone is picked up" path on or off.
     *
     * Switching it on immediately runs the pass, because the user has just demonstrated that they are
     * present - anything already overdue is exactly what they are asking to be told about, and making
     * them lock and unlock the phone to see it would be silly. Switching it off re-derives the
     * schedule so nothing that was armed on its behalf is left behind.
     */
    fun setUnlockReminderEnabled(value: Boolean) = update {
        settingsRepository.setUnlockReminderEnabled(value)
        if (value) {
            reminderEngine.onUserReturn()
        } else {
            rescheduleEverything()
        }
        _message.value = if (value) {
            "已开启解锁补提醒：解锁或回到应用时，会补提醒未吃的药"
        } else {
            "已关闭解锁补提醒"
        }
    }

    fun setUnlockReminderMaxPerDose(count: Int) = update {
        settingsRepository.setUnlockReminderMaxPerDose(count)
    }

    fun setUnlockReminderMinGapMinutes(minutes: Int) = update {
        settingsRepository.setUnlockReminderMinGapMinutes(minutes)
    }

    /**
     * Whether a reminder may currently take over the screen.
     *
     * Android 14+ gates `USE_FULL_SCREEN_INTENT` on the app being a genuine alarm clock, so the
     * capability is a *user* decision on that screen's system page. Below 14 it is granted with the
     * permission itself.
     */
    fun canUseFullScreenIntent(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as? android.app.NotificationManager
            manager?.canUseFullScreenIntent() ?: false
        } else {
            true
        }

    /**
     * Turns the full-screen take-over on or off.
     *
     * When it is switched on and the system has not granted the capability, the system page that can
     * grant it is opened straight away. A toggle that silently does nothing is worse than no toggle.
     */
    fun setFullScreenReminderEnabled(context: Context, value: Boolean) = update {
        settingsRepository.setFullScreenReminderEnabled(value)
        if (value && !canUseFullScreenIntent(context)) {
            _message.value = "全屏提醒需要系统允许，已为你打开设置页"
            openFullScreenIntentSettings(context)
        }
    }

    /** Opens the system page that grants (or revokes) the full-screen-intent capability. */
    fun openFullScreenIntentSettings(context: Context) {
        val target = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                .setData(Uri.fromParts("package", context.packageName, null))
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        runCatching { context.startActivity(target) }
            .onFailure { _message.value = "无法打开系统设置，请在系统「通知」中允许全屏提醒" }
    }

    // ------------------------------------------------------------------ widget

    fun setWidgetItemLimit(value: Int) = update {
        settingsRepository.setWidgetItemLimit(value)
        doseRepository.notifyWidgetRefresh()
    }
    /**
     * Changes the widget's re-check cadence.
     *
     * Nothing has to be rescheduled: the guard service's ticker re-reads the preference on every
     * iteration, so the new interval takes effect on the next tick without restarting the service.
     */
    fun setWidgetRefreshSeconds(value: Int) = update {
        settingsRepository.setWidgetRefreshSeconds(value)
        doseRepository.notifyWidgetRefresh()
    }

    fun setWidgetShowCompleted(value: Boolean) = update {
        settingsRepository.setWidgetShowCompleted(value)
        doseRepository.notifyWidgetRefresh()
    }

    fun setAppLock(value: Boolean) = update { settingsRepository.setAppLockEnabled(value) }

    fun setOnboardingCompleted(completed: Boolean) =
        update { settingsRepository.setOnboardingCompleted(completed) }
    fun setConfirmOverDose(value: Boolean) = update { settingsRepository.setConfirmOverDose(value) }

    /** The one-tap accessibility bundle: big text, high contrast, fewer layers, reminders on. */
    fun applyElderlyPreset() = update {
        settingsRepository.applyElderlyPreset()
        rescheduleEverything()
        _message.value = "已开启适老模式"
    }

    fun onMessageShown() {
        _message.value = null
    }

    // ------------------------------------------------------------------ data

    /**
     * Re-arms every future reminder after a setting that affects timing changed.
     *
     * A single reconcile call replaces the hand-rolled loop the old code used. That loop had already
     * drifted from the receiver's version - it used a two-day horizon instead of three, and it read
     * `snoozedUntilMillis` without checking that the snooze was still in the future - which is
     * exactly the kind of divergence that having one implementation prevents.
     */
    private suspend fun rescheduleEverything() {
        reminderEngine.reconcile(ReminderTrigger.SETTINGS_CHANGED)
        refreshHealthInternal()
    }

    private suspend fun refreshHealthInternal() {
        runCatching { _health.value = healthChecker.check() }
    }

    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch { runCatching { block() }.onFailure { _message.value = it.message } }
    }

    /**
     * Exports a backup into the current backup folder.
     *
     * Defaults to the app's own `files/exports` (no permission needed on any Android version), but the
     * user can point it at a folder they can actually open - which is what makes a backup usable
     * without going through a share sheet first.
     */
    fun exportBackup(csv: Boolean) {
        viewModelScope.launch {
            runCatching {
                val folder = backupStore.folder()
                val stamp = DateTimeUtils.formatDate(DateTimeUtils.todayEpochDay())
                val entry = withContext(Dispatchers.IO) {
                    if (csv) {
                        backupStore.writeText(
                            folder = folder,
                            name = "meditrack-$stamp.csv",
                            mimeType = "text/csv",
                            text = backupRepository.csvText(),
                        )
                    } else {
                        backupStore.writeText(
                            folder = folder,
                            name = "meditrack-backup-$stamp.json",
                            mimeType = "application/json",
                            text = backupRepository.backupJsonText(appVersionName(app)),
                        )
                    }
                }
                _lastExport.value = entry
                _message.value = "已导出：${entry.name}"
                refreshBackups()
            }.onFailure {
                _message.value = "导出失败：${it.message ?: "未知错误"}"
            }
        }
    }

    /** Imports a backup chosen through the system file picker. */
    fun importBackup(context: Context, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val backup = withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("无法读取所选文件")
                    stream.use { backupRepository.parseBackup(it) }
                }
                backupRepository.importBackup(backup)
            }.onSuccess { result ->
                // The imported data has its own schedules; re-arm everything for the new contents.
                rescheduleEverything()
                doseRepository.notifyWidgetRefresh()
                _message.value = "已导入 ${result.medications} 条药品、${result.doseLogs} 条记录"
            }.onFailure {
                _message.value = "导入失败：${it.message ?: "未知错误"}"
            }
        }
    }

    private fun appVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    companion object {
        /** How many audit entries the self-check screen shows. */
        const val AUDIT_LOG_LIMIT = 20

        /** Permission keys used by [permissionIntent]. */
        const val KEY_NOTIFICATIONS = "notifications"
        const val KEY_EXACT_ALARM = "exact_alarm"
        const val KEY_BATTERY = "battery"
    }
}
