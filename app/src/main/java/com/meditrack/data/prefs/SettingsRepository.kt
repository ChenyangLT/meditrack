package com.meditrack.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** DataStore instance bound to the application context. */
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "meditrack_settings")

/**
 * Persists [UserPreferences] in a single-key-per-field DataStore.
 *
 * Reads are exposed as a [Flow] so the theme, the font scale and the reminder switches all react
 * immediately; a corrupted or partially written file falls back to defaults rather than crashing
 * the app on launch.
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val ACCENT = stringPreferencesKey("accent_color")
        val FONT_SCALE = stringPreferencesKey("font_scale")
        val HIGH_CONTRAST = booleanPreferencesKey("high_contrast")
        val SIMPLIFIED = booleanPreferencesKey("simplified_mode")
        val USE_24H = booleanPreferencesKey("use_24_hour")
        val WEEK_START = stringPreferencesKey("week_start")

        val REMINDERS_ENABLED = booleanPreferencesKey("reminders_enabled")
        val EXACT_ALARMS = booleanPreferencesKey("exact_alarms")
        val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        val VIBRATION_ENABLED = booleanPreferencesKey("vibration_enabled")
        val HEADS_UP_ENABLED = booleanPreferencesKey("heads_up_enabled")
        val OVERRIDE_SILENT = booleanPreferencesKey("override_silent")
        val SOUND_URI = stringPreferencesKey("sound_uri")
        val REPEAT_MINUTES = intPreferencesKey("repeat_minutes")
        val SNOOZE_MINUTES = intPreferencesKey("snooze_minutes")
        val MISSED_GRACE_MINUTES = intPreferencesKey("missed_grace_minutes")
        val MISSED_REMINDER = booleanPreferencesKey("missed_reminder")
        val MAX_ESCALATIONS = intPreferencesKey("max_escalations")
        val QUIET_ENABLED = booleanPreferencesKey("quiet_enabled")
        val QUIET_START = intPreferencesKey("quiet_start")
        val QUIET_END = intPreferencesKey("quiet_end")

        val IDLE_DEFERRAL = booleanPreferencesKey("idle_deferral")
        val IDLE_THRESHOLD = intPreferencesKey("idle_threshold")
        val DEFER_SCREEN_OFF = booleanPreferencesKey("defer_screen_off")

        // Reminder reliability / humanised timing (see UserPreferences for what each one does).
        val PRE_REMINDER = booleanPreferencesKey("pre_reminder")
        val PRE_REMINDER_LEAD = intPreferencesKey("pre_reminder_lead")
        val FRESH_MINUTES = intPreferencesKey("reminder_fresh_minutes")
        val STALE_MINUTES = intPreferencesKey("stale_reminder_minutes")
        val EARLY_TOLERANCE = intPreferencesKey("early_tolerance_minutes")
        val CLUSTER_MINUTES = intPreferencesKey("cluster_window_minutes")
        val DIGEST_ENABLED = booleanPreferencesKey("digest_enabled")
        val SNOOZE_STATE_NOTIFICATION = booleanPreferencesKey("snooze_state_notification")
        val QUIET_DEFER = booleanPreferencesKey("quiet_hours_defer")
        val CATCH_UP = booleanPreferencesKey("catch_up_reminder")
        val HEARTBEAT_MINUTES = intPreferencesKey("heartbeat_minutes")
        val RELIABILITY_WORKER = booleanPreferencesKey("reliability_worker")
        val ALARM_CLOCK_ALARMS = booleanPreferencesKey("alarm_clock_alarms")
        val GUARD_SERVICE = booleanPreferencesKey("guard_service")

        /**
         * Wall-clock time of the last observed user interaction.
         *
         * Held in DataStore rather than memory so an idle period survives a process death - which is
         * the normal case, since the app is usually not running while the phone sits on a table.
         */
        val LAST_INTERACTION = longPreferencesKey("last_interaction")

        val WIDGET_LIMIT = intPreferencesKey("widget_limit")
        val WIDGET_REFRESH = intPreferencesKey("widget_refresh")
        val WIDGET_QUICK_ACTIONS = booleanPreferencesKey("widget_quick_actions")
        val WIDGET_SHOW_COMPLETED = booleanPreferencesKey("widget_show_completed")

        val APP_LOCK = booleanPreferencesKey("app_lock")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val CONFIRM_OVERDOSE = booleanPreferencesKey("confirm_overdose")
        val HISTORY_BACKFILL = intPreferencesKey("history_backfill")
    }

    val preferences: Flow<UserPreferences> = dataStore.data
        .catch { throwable ->
            // A corrupted preferences file must never take the app down; fall back to defaults.
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { p -> p.toUserPreferences() }

    /** Convenience for one-shot reads (boot receiver, widget refresh). */
    suspend fun current(): UserPreferences = preferences.first()

    // ------------------------------------------------------------ writers

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME_MODE] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = edit { it[Keys.DYNAMIC_COLOR] = enabled }
    suspend fun setAccentColor(accent: AccentColor) = edit { it[Keys.ACCENT] = accent.name }
    suspend fun setFontScale(scale: FontScale) = edit { it[Keys.FONT_SCALE] = scale.name }
    suspend fun setHighContrast(enabled: Boolean) = edit { it[Keys.HIGH_CONTRAST] = enabled }
    suspend fun setSimplifiedMode(enabled: Boolean) = edit { it[Keys.SIMPLIFIED] = enabled }
    suspend fun setUse24Hour(enabled: Boolean) = edit { it[Keys.USE_24H] = enabled }
    suspend fun setWeekStart(start: WeekStart) = edit { it[Keys.WEEK_START] = start.name }

    suspend fun setRemindersEnabled(enabled: Boolean) = edit { it[Keys.REMINDERS_ENABLED] = enabled }
    suspend fun setExactAlarms(enabled: Boolean) = edit { it[Keys.EXACT_ALARMS] = enabled }
    suspend fun setSoundEnabled(enabled: Boolean) = edit { it[Keys.SOUND_ENABLED] = enabled }
    suspend fun setVibrationEnabled(enabled: Boolean) = edit { it[Keys.VIBRATION_ENABLED] = enabled }
    suspend fun setHeadsUpEnabled(enabled: Boolean) = edit { it[Keys.HEADS_UP_ENABLED] = enabled }
    suspend fun setOverrideSilent(enabled: Boolean) = edit { it[Keys.OVERRIDE_SILENT] = enabled }
    suspend fun setSoundUri(uri: String?) = edit { p ->
        if (uri == null) p.remove(Keys.SOUND_URI) else p[Keys.SOUND_URI] = uri
    }
    suspend fun setRepeatMinutes(minutes: Int) = edit { it[Keys.REPEAT_MINUTES] = minutes.coerceIn(0, 120) }
    suspend fun setSnoozeMinutes(minutes: Int) = edit { it[Keys.SNOOZE_MINUTES] = minutes.coerceIn(5, 120) }
    suspend fun setMissedGraceMinutes(minutes: Int) =
        edit { it[Keys.MISSED_GRACE_MINUTES] = minutes.coerceIn(5, 240) }
    suspend fun setMissedReminderEnabled(enabled: Boolean) = edit { it[Keys.MISSED_REMINDER] = enabled }
    suspend fun setMaxEscalations(count: Int) = edit { it[Keys.MAX_ESCALATIONS] = count.coerceIn(0, 10) }
    suspend fun setQuietHoursEnabled(enabled: Boolean) = edit { it[Keys.QUIET_ENABLED] = enabled }
    suspend fun setQuietHours(startMinute: Int, endMinute: Int) = edit { p ->
        p[Keys.QUIET_START] = startMinute.coerceIn(0, 1439)
        p[Keys.QUIET_END] = endMinute.coerceIn(0, 1439)
    }

    /** Turns the opt-in idle-deferral feature on or off. Off means "never inspect device state". */
    suspend fun setIdleDeferralEnabled(enabled: Boolean) = edit { it[Keys.IDLE_DEFERRAL] = enabled }
    suspend fun setIdleThresholdMinutes(minutes: Int) =
        edit { it[Keys.IDLE_THRESHOLD] = minutes.coerceIn(5, 720) }
    suspend fun setDeferWhileScreenOff(enabled: Boolean) = edit { it[Keys.DEFER_SCREEN_OFF] = enabled }

    // ------------------------------- reminder reliability / humanised timing

    suspend fun setPreReminderEnabled(enabled: Boolean) = edit { it[Keys.PRE_REMINDER] = enabled }
    suspend fun setPreReminderLeadMinutes(minutes: Int) =
        edit { it[Keys.PRE_REMINDER_LEAD] = minutes.coerceIn(1, 120) }
    suspend fun setReminderFreshMinutes(minutes: Int) =
        edit { it[Keys.FRESH_MINUTES] = minutes.coerceIn(1, 240) }
    suspend fun setStaleReminderMinutes(minutes: Int) =
        edit { it[Keys.STALE_MINUTES] = minutes.coerceIn(5, 1440) }
    suspend fun setEarlyToleranceMinutes(minutes: Int) =
        edit { it[Keys.EARLY_TOLERANCE] = minutes.coerceIn(0, 60) }
    suspend fun setClusterWindowMinutes(minutes: Int) =
        edit { it[Keys.CLUSTER_MINUTES] = minutes.coerceIn(0, 120) }
    suspend fun setDigestEnabled(enabled: Boolean) = edit { it[Keys.DIGEST_ENABLED] = enabled }
    suspend fun setSnoozeStateNotificationEnabled(enabled: Boolean) =
        edit { it[Keys.SNOOZE_STATE_NOTIFICATION] = enabled }
    suspend fun setQuietHoursDeferEnabled(enabled: Boolean) = edit { it[Keys.QUIET_DEFER] = enabled }
    suspend fun setCatchUpReminderEnabled(enabled: Boolean) = edit { it[Keys.CATCH_UP] = enabled }

    /** Changes the self-check cadence. Callers must re-arm the heartbeat afterwards. */
    suspend fun setHeartbeatMinutes(minutes: Int) =
        edit { it[Keys.HEARTBEAT_MINUTES] = minutes.coerceIn(MIN_HEARTBEAT_MINUTES, 180) }

    suspend fun setReliabilityWorkerEnabled(enabled: Boolean) =
        edit { it[Keys.RELIABILITY_WORKER] = enabled }

    suspend fun setAlarmClockAlarms(enabled: Boolean) = edit { it[Keys.ALARM_CLOCK_ALARMS] = enabled }

    /** Turns the background guard service on or off; callers must start/stop it accordingly. */
    suspend fun setGuardServiceEnabled(enabled: Boolean) = edit { it[Keys.GUARD_SERVICE] = enabled }

    /** Records that the user just interacted with the device. */
    suspend fun recordInteraction(at: Long = System.currentTimeMillis()) =
        edit { it[Keys.LAST_INTERACTION] = at }

    /** When the user was last seen interacting, or null if never recorded. */
    suspend fun lastInteraction(): Long? = dataStore.data.first()[Keys.LAST_INTERACTION]

    suspend fun setWidgetItemLimit(limit: Int) = edit { it[Keys.WIDGET_LIMIT] = limit.coerceIn(1, 8) }
    suspend fun setWidgetRefreshMinutes(minutes: Int) = edit { it[Keys.WIDGET_REFRESH] = minutes.coerceIn(15, 240) }
    suspend fun setWidgetQuickActions(enabled: Boolean) = edit { it[Keys.WIDGET_QUICK_ACTIONS] = enabled }
    suspend fun setWidgetShowCompleted(enabled: Boolean) = edit { it[Keys.WIDGET_SHOW_COMPLETED] = enabled }

    suspend fun setAppLockEnabled(enabled: Boolean) = edit { it[Keys.APP_LOCK] = enabled }
    suspend fun setOnboardingCompleted(done: Boolean) = edit { it[Keys.ONBOARDING_DONE] = done }
    suspend fun setConfirmOverDose(enabled: Boolean) = edit { it[Keys.CONFIRM_OVERDOSE] = enabled }
    suspend fun setHistoryBackfillDays(days: Int) = edit { it[Keys.HISTORY_BACKFILL] = days.coerceIn(0, 365) }

    /** Applies the accessibility bundle in one write (used by the 适老 setup card). */
    suspend fun applyElderlyPreset() = edit { p ->
        p[Keys.FONT_SCALE] = FontScale.EXTRA_LARGE.name
        p[Keys.HIGH_CONTRAST] = true
        p[Keys.SIMPLIFIED] = true
        p[Keys.CONFIRM_OVERDOSE] = true
        p[Keys.REMINDERS_ENABLED] = true
        p[Keys.EXACT_ALARMS] = true
        p[Keys.VIBRATION_ENABLED] = true
        p[Keys.SOUND_ENABLED] = true
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    private fun Preferences.toUserPreferences(): UserPreferences {
        val defaults = UserPreferences()
        return UserPreferences(
            themeMode = enumOrDefault(this[Keys.THEME_MODE], defaults.themeMode),
            useDynamicColor = this[Keys.DYNAMIC_COLOR] ?: defaults.useDynamicColor,
            accentColor = enumOrDefault(this[Keys.ACCENT], defaults.accentColor),
            fontScale = enumOrDefault(this[Keys.FONT_SCALE], defaults.fontScale),
            highContrast = this[Keys.HIGH_CONTRAST] ?: defaults.highContrast,
            simplifiedMode = this[Keys.SIMPLIFIED] ?: defaults.simplifiedMode,
            use24HourFormat = this[Keys.USE_24H] ?: defaults.use24HourFormat,
            weekStart = enumOrDefault(this[Keys.WEEK_START], defaults.weekStart),
            remindersEnabled = this[Keys.REMINDERS_ENABLED] ?: defaults.remindersEnabled,
            exactAlarms = this[Keys.EXACT_ALARMS] ?: defaults.exactAlarms,
            soundEnabled = this[Keys.SOUND_ENABLED] ?: defaults.soundEnabled,
            vibrationEnabled = this[Keys.VIBRATION_ENABLED] ?: defaults.vibrationEnabled,
            headsUpEnabled = this[Keys.HEADS_UP_ENABLED] ?: defaults.headsUpEnabled,
            overrideSilent = this[Keys.OVERRIDE_SILENT] ?: defaults.overrideSilent,
            soundUri = this[Keys.SOUND_URI],
            repeatReminderMinutes = this[Keys.REPEAT_MINUTES] ?: defaults.repeatReminderMinutes,
            snoozeMinutes = this[Keys.SNOOZE_MINUTES] ?: defaults.snoozeMinutes,
            missedGraceMinutes = this[Keys.MISSED_GRACE_MINUTES] ?: defaults.missedGraceMinutes,
            missedReminderEnabled = this[Keys.MISSED_REMINDER] ?: defaults.missedReminderEnabled,
            maxEscalationsPerDose = this[Keys.MAX_ESCALATIONS] ?: defaults.maxEscalationsPerDose,
            quietHoursEnabled = this[Keys.QUIET_ENABLED] ?: defaults.quietHoursEnabled,
            quietHoursStartMinute = this[Keys.QUIET_START] ?: defaults.quietHoursStartMinute,
            quietHoursEndMinute = this[Keys.QUIET_END] ?: defaults.quietHoursEndMinute,
            idleDeferralEnabled = this[Keys.IDLE_DEFERRAL] ?: defaults.idleDeferralEnabled,
            idleThresholdMinutes = this[Keys.IDLE_THRESHOLD] ?: defaults.idleThresholdMinutes,
            deferWhileScreenOff = this[Keys.DEFER_SCREEN_OFF] ?: defaults.deferWhileScreenOff,
            preReminderEnabled = this[Keys.PRE_REMINDER] ?: defaults.preReminderEnabled,
            preReminderLeadMinutes = this[Keys.PRE_REMINDER_LEAD] ?: defaults.preReminderLeadMinutes,
            reminderFreshMinutes = this[Keys.FRESH_MINUTES] ?: defaults.reminderFreshMinutes,
            staleReminderMinutes = this[Keys.STALE_MINUTES] ?: defaults.staleReminderMinutes,
            earlyToleranceMinutes = this[Keys.EARLY_TOLERANCE] ?: defaults.earlyToleranceMinutes,
            clusterWindowMinutes = this[Keys.CLUSTER_MINUTES] ?: defaults.clusterWindowMinutes,
            digestEnabled = this[Keys.DIGEST_ENABLED] ?: defaults.digestEnabled,
            snoozeStateNotificationEnabled =
                this[Keys.SNOOZE_STATE_NOTIFICATION] ?: defaults.snoozeStateNotificationEnabled,
            quietHoursDeferEnabled = this[Keys.QUIET_DEFER] ?: defaults.quietHoursDeferEnabled,
            catchUpReminderEnabled = this[Keys.CATCH_UP] ?: defaults.catchUpReminderEnabled,
            heartbeatMinutes = this[Keys.HEARTBEAT_MINUTES] ?: defaults.heartbeatMinutes,
            reliabilityWorkerEnabled =
                this[Keys.RELIABILITY_WORKER] ?: defaults.reliabilityWorkerEnabled,
            alarmClockAlarms = this[Keys.ALARM_CLOCK_ALARMS] ?: defaults.alarmClockAlarms,
            guardServiceEnabled = this[Keys.GUARD_SERVICE] ?: defaults.guardServiceEnabled,
            widgetItemLimit = this[Keys.WIDGET_LIMIT] ?: defaults.widgetItemLimit,
            widgetRefreshMinutes = this[Keys.WIDGET_REFRESH] ?: defaults.widgetRefreshMinutes,
            widgetQuickActions = this[Keys.WIDGET_QUICK_ACTIONS] ?: defaults.widgetQuickActions,
            widgetShowCompleted = this[Keys.WIDGET_SHOW_COMPLETED] ?: defaults.widgetShowCompleted,
            appLockEnabled = this[Keys.APP_LOCK] ?: defaults.appLockEnabled,
            onboardingCompleted = this[Keys.ONBOARDING_DONE] ?: defaults.onboardingCompleted,
            confirmOverDose = this[Keys.CONFIRM_OVERDOSE] ?: defaults.confirmOverDose,
            historyBackfillDays = this[Keys.HISTORY_BACKFILL] ?: defaults.historyBackfillDays,
        )
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, fallback: T): T =
        if (name == null) fallback else runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)

    companion object {
        /**
         * The fastest self-check cadence the UI will offer.
         *
         * Doze rate-limits `setExactAndAllowWhileIdle` to roughly one delivery per 9 minutes per app,
         * so offering anything below 10 minutes would be a promise the platform cannot keep. 15 is
         * the shipped default: frequent enough to bound a lost alarm to a quarter of an hour, cheap
         * enough that it does not itself become a battery complaint.
         */
        const val MIN_HEARTBEAT_MINUTES = 10
    }
}
