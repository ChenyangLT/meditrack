package com.meditrack.domain.reminder

import android.app.AlarmManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.meditrack.core.util.DateTimeUtils
import com.meditrack.data.prefs.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** One line of the self-check: a fact about the pipeline, and how to fix it if it is wrong. */
data class ReminderHealthItem(
    val key: String,
    val label: String,
    val ok: Boolean,
    val detail: String,
) {
    /** True when the item is informational rather than a problem. */
    val actionable: Boolean get() = !ok
}

/**
 * The complete answer to "are my reminders actually going to work?".
 *
 * ## Why this screen exists
 *
 * Everything that makes a reminder reliable lives outside the app: a notification permission, the
 * exact-alarm capability, a battery-optimisation exemption, and an OEM autostart allow-list that no
 * API can even read. The app cannot grant any of them, and the user cannot be expected to guess
 * which one is missing.
 *
 * So the app measures instead: it reports what it can observe, and it reports what it has *done* -
 * how many reminders were delivered, how many were deliberately suppressed and why, how late the
 * operating system actually delivered them, and when the next self-check will run. That turns "it
 * doesn't work" from a complaint into a diagnosis, which is the whole point of the audit trail.
 */
data class ReminderHealth(
    val items: List<ReminderHealthItem>,
    val deliveredLast24h: Int,
    val suppressedLast24h: Int,
    val lastReconcileAt: Long,
    val lastTrigger: ReminderTrigger?,
    val nextSelfCheckAt: Long,
    val nextDoseAt: Long,
    val armedCount: Int,
    val worstDriftMillis: Long,
    /** Armed alarms the system has failed to deliver, ever. Non-zero means the app is being cleared. */
    val lostAlarms: Long,
    /** When the background guard service last checked in, or 0 if it is not running. */
    val guardLastSeenAt: Long,
    val recommendations: List<String>,
) {
    val allOk: Boolean get() = items.all { it.ok }
    val problemCount: Int get() = items.count { it.actionable }
    val hasHistory: Boolean get() = lastReconcileAt > 0L
}

/**
 * Builds the self-check report and applies the repairs the app *can* perform.
 *
 * The split is deliberate and honest: [check] only observes, [repair] only does things that are
 * entirely within the app's own control (rebuild the schedule, reset the drift history, re-arm the
 * heartbeat, post a test notification). Anything needing a human - a permission, an OEM allow-list -
 * is surfaced as a recommendation with the exact settings screen to open, never silently attempted.
 */
@Singleton
class ReminderHealthChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val registry: ReminderAlarmRegistry,
    private val audit: ReminderAudit,
    private val heartbeat: ReminderHeartbeat,
    private val engine: ReminderEngine,
    private val notifier: DoseNotifier,
) {

    /** Observes the current state. Reads nothing destructive and changes nothing. */
    suspend fun check(): ReminderHealth {
        val prefs = settingsRepository.current()
        val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val exactAlarms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            heartbeat.canScheduleExactAlarms()
        } else {
            true
        }
        val batteryExempt = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.isIgnoringBatteryOptimizations(context.packageName) ?: true

        val since = System.currentTimeMillis() - DAY_MILLIS
        val summary = audit.summary(since)
        val lastReconcile = registry.lastReconcileAt()
        val nextSelfCheck = registry.nextSelfCheckAt()
        val worstDrift = registry.worstDriftMillis()
        val trigger = registry.lastReconcileTrigger()
            ?.let { runCatching { ReminderTrigger.valueOf(it) }.getOrNull() }
        val armedCount = registry.armedCount()
        val nextDoseAt = registry.nextDoseAt()
        val lostAlarms = registry.lostAlarms()
        val guardSeen = registry.guardLastSeenAt()

        val heartbeatStale = lastReconcile > 0L &&
            System.currentTimeMillis() - lastReconcile > STALE_HEARTBEAT_MULTIPLIER * prefs.heartbeatMinutes * 60_000L

        // The guard checks in on every pass, so a stale (or absent) timestamp means it is not running.
        // The freshness window is generous: the service only re-reports when something wakes it.
        val guardRunning = prefs.guardServiceEnabled &&
            guardSeen > 0L &&
            System.currentTimeMillis() - guardSeen < GUARD_STALE_MILLIS

        val items = listOf(
            ReminderHealthItem(
                key = "master",
                label = "提醒总开关",
                ok = prefs.remindersEnabled,
                detail = if (prefs.remindersEnabled) "已开启" else "已关闭，不会产生任何提醒",
            ),
            ReminderHealthItem(
                key = "notifications",
                label = "通知权限",
                ok = notificationsEnabled,
                detail = if (notificationsEnabled) "已允许" else "被系统关闭，提醒无法显示",
            ),
            ReminderHealthItem(
                key = "exact_alarm",
                label = "精确闹钟",
                ok = exactAlarms,
                detail = if (exactAlarms) {
                    "已允许，提醒可以准点"
                } else {
                    "未允许，系统会推迟提醒；目前已由 ${prefs.heartbeatMinutes} 分钟自检兜底"
                },
            ),
            ReminderHealthItem(
                key = "battery",
                label = "电池优化白名单",
                ok = batteryExempt,
                detail = if (batteryExempt) "已加入" else "未加入，长时间待机时提醒可能被系统冻结",
            ),
            ReminderHealthItem(
                key = "guard",
                label = "后台守护服务",
                ok = guardRunning,
                detail = when {
                    !prefs.guardServiceEnabled -> "已关闭：应用被系统清理后将收不到任何提醒，直到手动打开应用"
                    guardSeen <= 0L -> "尚未启动，打开一次应用即可建立"
                    else -> "运行中，上次自检 " + DateTimeUtils.formatDateTime(guardSeen)
                },
            ),
            ReminderHealthItem(
                key = "heartbeat",
                label = "自检心跳",
                ok = !heartbeatStale,
                detail = when {
                    lastReconcile <= 0L -> "尚未运行过，打开应用后会立即建立"
                    heartbeatStale -> "已超过 ${STALE_HEARTBEAT_MULTIPLIER} 个周期没有运行，请点击下方修复"
                    else -> "上次自检 ${DateTimeUtils.formatDateTime(lastReconcile)}" +
                        "（${trigger?.label ?: "未知"}），已排定 $armedCount 个闹钟"
                },
            ),
            ReminderHealthItem(
                key = "lost_alarms",
                label = "被系统丢弃的闹钟",
                ok = lostAlarms == 0L,
                detail = if (lostAlarms == 0L) {
                    "没有发现"
                } else {
                    "已有 $lostAlarms 次已排定的闹钟没有被系统触发；" +
                        "这是后台被清理的典型表现，请开启后台守护并允许自启动"
                },
            ),
        )

        val recommendations = buildRecommendations(
            prefs = prefs,
            notificationsEnabled = notificationsEnabled,
            exactAlarms = exactAlarms,
            batteryExempt = batteryExempt,
            heartbeatStale = heartbeatStale,
            guardRunning = guardRunning,
            lostAlarms = lostAlarms,
            worstDrift = worstDrift,
            suppressed = summary.suppressed,
        )

        return ReminderHealth(
            items = items,
            deliveredLast24h = summary.delivered,
            suppressedLast24h = summary.suppressed,
            lastReconcileAt = lastReconcile,
            lastTrigger = trigger,
            nextSelfCheckAt = nextSelfCheck,
            nextDoseAt = nextDoseAt,
            armedCount = armedCount,
            worstDriftMillis = worstDrift,
            lostAlarms = lostAlarms,
            guardLastSeenAt = guardSeen,
            recommendations = recommendations,
        )
    }

    /**
     * Rebuilds everything the app owns and reports what it did.
     *
     * Resetting the diagnostics is part of it: the point of a repair is to start measuring again from
     * a known-good state, otherwise one bad night would keep recommending the alarm-clock upgrade
     * forever and train the user to ignore it.
     */
    suspend fun repair(): ReminderReport {
        registry.resetDiagnostics()
        ReminderGuardService.ensureRunning(context)
        return engine.reconcile(ReminderTrigger.USER_REPAIR)
    }

    /** Posts a real notification on the real channel so the user can see and hear it working. */
    fun sendTestNotification(): Boolean {
        notifier.showSelfTest()
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** The system settings screen that can fix a given [ReminderHealthItem.key], or null. */
    fun fixIntent(key: String): android.content.Intent? = when (key) {
        "notifications" -> android.content.Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        "exact_alarm" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            android.content.Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.fromParts("package", context.packageName, null))
        } else {
            null
        }
        "battery" -> @Suppress("BatteryLife")
        android.content.Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.fromParts("package", context.packageName, null))
        "master" -> android.content.Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
        else -> null
    }

    /**
     * Turns the observed state into concrete advice.
     *
     * Ordered by what actually helps: a missing notification permission makes everything else moot,
     * and a *measured* dropped alarm outranks every guess, because it is proof.
     */
    private fun buildRecommendations(
        prefs: com.meditrack.data.prefs.UserPreferences,
        notificationsEnabled: Boolean,
        exactAlarms: Boolean,
        batteryExempt: Boolean,
        heartbeatStale: Boolean,
        guardRunning: Boolean,
        lostAlarms: Long,
        worstDrift: Long,
        suppressed: Int,
    ): List<String> {
        val out = mutableListOf<String>()
        if (!notificationsEnabled) out += "通知权限被关闭，请在系统设置中允许「药准时」发送通知。"
        if (!prefs.remindersEnabled) out += "提醒总开关处于关闭状态。"

        // Highest priority: the app has *evidence* that its alarms were thrown away.
        if (lostAlarms > 0L) {
            out += "检测到 $lostAlarms 次已排定的闹钟没有被系统触发——这是应用在后台被清理的典型表现。" +
                "请开启「后台守护服务」，并在系统设置中允许「药准时」自启动。"
        }
        if (!prefs.guardServiceEnabled) {
            out += "「后台守护服务」已关闭。关闭后一旦系统清理后台，应用将收不到任何提醒，" +
                "直到手动打开应用；建议开启。"
        } else if (!guardRunning) {
            out += "后台守护服务尚未运行，打开一次应用即可建立。"
        }

        if (!exactAlarms) out += "允许「精确闹钟」可以让提醒准点到达，而不是被系统推迟。"
        if (!batteryExempt) out += "把「药准时」加入电池优化白名单，避免长时间待机时被冻结。"
        if (heartbeatStale) out += "自检心跳已经停止，点击「立即自检并修复」重建全部闹钟。"
        if (worstDrift > ALARM_CLOCK_RECOMMENDATION_MILLIS && !prefs.alarmClockAlarms) {
            out += "系统最近把提醒推迟了 ${worstDrift / 60_000} 分钟才送达，建议开启「闹钟级提醒」。"
        }
        if (isAggressivelyManagedVendor()) {
            out += "检测到 ${Build.MANUFACTURER} 设备：请在系统设置的「自启动」/「后台运行」中允许「药准时」，" +
                "并把省电策略设为「无限制」。"
        }
        if (suppressed > 0 && out.isEmpty()) {
            out += "近 24 小时有 $suppressed 次提醒被主动跳过，可在下方日志中查看原因。"
        }
        if (out.isEmpty()) out += "一切正常：权限齐全，后台守护运行中。"
        return out
    }

    /**
     * A best-effort deep link into the vendor's "autostart" / "protected apps" screen.
     *
     * These screens have no public API - the app cannot read or request the setting - but the
     * activities are stable in practice, and on the ROMs where reminders die this is the *only*
     * remaining fix. Candidates are tried in order and the first one that resolves is returned;
     * when none does, the caller falls back to the app's own settings page rather than showing a
     * button that does nothing.
     */
    fun autostartIntent(): android.content.Intent? {
        for (candidate in AUTOSTART_CANDIDATES) {
            val intent = android.content.Intent().apply {
                setClassName(candidate.first, candidate.second)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { context.packageManager.resolveActivity(intent, 0) }.getOrNull() != null) {
                return intent
            }
        }
        return null
    }

    /**
     * Whether this device is from a vendor known to reap background alarms.
     *
     * Used only to phrase a recommendation, never to change behaviour - the app does not need to know
     * who made the phone in order to keep its own schedule honest.
     */
    private fun isAggressivelyManagedVendor(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return VENDORS.any { manufacturer.contains(it) }
    }

    private companion object {
        const val DAY_MILLIS = 24 * 60 * 60 * 1000L

        /** How many missed heartbeats before the pipeline is considered stalled. */
        const val STALE_HEARTBEAT_MULTIPLIER = 3

        /**
         * How long a guard-service check-in stays fresh.
         *
         * The service reports on start and on every pass it runs, so this only has to absorb the gap
         * between them; anything much longer would keep claiming the guard is alive long after the
         * system killed it.
         */
        const val GUARD_STALE_MILLIS = 45 * 60 * 1000L

        /**
         * The measured lateness above which the alarm-clock upgrade is worth recommending.
         *
         * Five minutes is well past the jitter of a healthy `setExactAndAllowWhileIdle` and squarely
         * inside the range where a medication reminder stops being useful.
         */
        const val ALARM_CLOCK_RECOMMENDATION_MILLIS = 5 * 60_000L

        val VENDORS = listOf("xiaomi", "redmi", "huawei", "honor", "oppo", "vivo", "oneplus", "meizu", "realme", "samsung")

        /**
         * Known "autostart" / "protected apps" activities, most aggressive ROMs first.
         *
         * There is no public API for this setting and the app cannot read or request it, so a deep
         * link is the only help it can offer - which makes it worth hard-coding the component names
         * that are stable in practice across these ROMs.
         */
        val AUTOSTART_CANDIDATES = listOf(
            // Xiaomi / Redmi (MIUI, HyperOS)
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            // Huawei / Honor (EMUI, MagicOS)
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            // Oppo / Realme (ColorOS)
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            // Vivo / iQOO (OriginOS, FuntouchOS)
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            // Samsung
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
            // Meizu
            "com.meizu.safe" to "com.meizu.safe.permission.SmartBGActivity",
        )
    }
}
