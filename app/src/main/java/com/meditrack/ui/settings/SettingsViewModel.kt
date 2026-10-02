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
import androidx.core.content.FileProvider
import com.meditrack.core.util.DateTimeUtils
import com.meditrack.data.backup.BackupRepository
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
import java.io.File
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
    private val app: android.app.Application,
) : ViewModel() {

    /**
     * Registers or releases the device-activity receiver to match the preference.
     *
     * Routed through the Application (which owns the registration) so exactly one registration
     * exists for the process, regardless of how many screens toggle the setting.
     */
    private fun applyActivityMonitoring(enabled: Boolean) {
        (app as? com.meditrack.MediTrackApp)?.applyActivityMonitoring(enabled)
    }

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

    private val _lastExport = MutableStateFlow<File?>(null)
    /** The most recent successful export, offered to the user as "分享" instead of a bare path. */
    val lastExport: StateFlow<File?> = _lastExport.asStateFlow()

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
            if (report.deferred > 0) append("，暂缓 ${report.deferred} 项")
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

    // ------------------------------------------------------------ idle deferral

    fun setIdleDeferralEnabled(enabled: Boolean) = update {
        settingsRepository.setIdleDeferralEnabled(enabled)
        applyActivityMonitoring(enabled)
        if (enabled) {
            // Turning it on counts as the user being present.
            reminderEngine.onUserReturn()
        } else {
            // Leaving withheld reminders stranded would be worse than delivering them late.
            reminderEngine.reconcile(ReminderTrigger.SETTINGS_CHANGED)
        }
    }

    fun setIdleThresholdMinutes(minutes: Int) =
        update { settingsRepository.setIdleThresholdMinutes(minutes) }

    fun setDeferWhileScreenOff(enabled: Boolean) =
        update { settingsRepository.setDeferWhileScreenOff(enabled) }

    // ------------------------------------------------------------------ widget

    fun setWidgetItemLimit(value: Int) = update {
        settingsRepository.setWidgetItemLimit(value)
        doseRepository.notifyWidgetRefresh()
    }
    fun setWidgetRefreshMinutes(value: Int) = update {
        settingsRepository.setWidgetRefreshMinutes(value)
        doseRepository.notifyWidgetRefresh()
    }
    fun setWidgetQuickActions(value: Boolean) = update {
        settingsRepository.setWidgetQuickActions(value)
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
     * Exports a backup into the app's own `files/exports` directory.
     *
     * App-private storage is the reliable target: it needs no permission on any supported Android
     * version, and the caller can then either share the file through FileProvider or copy the path.
     * Writing straight to a user-visible directory would require a SAF flow on every export.
     */
    fun exportBackup(context: Context, csv: Boolean) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val dir = File(context.filesDir, EXPORT_DIR)
                    dir.mkdirs()
                    val stamp = DateTimeUtils.formatDate(DateTimeUtils.todayEpochDay())
                    if (csv) {
                        backupRepository.exportCsvTo(File(dir, "meditrack-$stamp.csv"))
                    } else {
                        backupRepository.exportJsonTo(
                            File(dir, "meditrack-backup-$stamp.json"),
                            appVersion = appVersionName(context),
                        )
                    }
                }
            }.onSuccess { file ->
                _lastExport.value = file
                _message.value = "已导出：${file.name}"
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

    /** Produces a content:// uri for an exported file so it can be handed to a share sheet. */
    fun exportUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private fun appVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    companion object {
        /** Subdirectory of `filesDir` that holds the exports. Mirrors `xml/file_paths.xml`. */
        const val EXPORT_DIR = "exports"

        /** How many audit entries the self-check screen shows. */
        const val AUDIT_LOG_LIMIT = 20

        /** Permission keys used by [permissionIntent]. */
        const val KEY_NOTIFICATIONS = "notifications"
        const val KEY_EXACT_ALARM = "exact_alarm"
        const val KEY_BATTERY = "battery"
    }
}
