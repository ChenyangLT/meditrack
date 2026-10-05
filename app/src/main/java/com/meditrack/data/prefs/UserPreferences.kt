package com.meditrack.data.prefs

/** How the app picks between the light and dark colour scheme. */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

/** Accent choices offered when Material You dynamic colour is unavailable or switched off. */
enum class AccentColor(val label: String) {
    MINT("薄荷绿"),
    TEAL("青蓝"),
    LAVENDER("淡紫"),
}

/**
 * Text scaling presets. Instead of a raw slider we expose presets, because the accessibility
 * requirement is "make it readable", not "give me a float".
 */
enum class FontScale(val label: String, val scale: Float) {
    NORMAL("标准", 1.0f),
    LARGE("大", 1.15f),
    EXTRA_LARGE("特大", 1.3f),
    HUGE("超大（适老）", 1.5f),
}

/** Which day the history calendar starts its week on. */
enum class WeekStart(val label: String, val isoDay: Int) {
    MONDAY("周一", 1),
    SUNDAY("周日", 7),
    SATURDAY("周六", 6),
}

/**
 * Everything the user can configure, in one immutable snapshot.
 *
 * The reminder-related fields are **fail-safe defaults**: reminders are on, the alarm is exact,
 * vibration is on and the missed sweep runs. An elderly user who never opens settings still gets a
 * reliable reminder.
 */
data class UserPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val useDynamicColor: Boolean = true,
    val accentColor: AccentColor = AccentColor.MINT,
    val fontScale: FontScale = FontScale.NORMAL,
    /**
     * High-contrast / low-vision mode: thicker outlines on status chips and status text, and a
     * larger minimum touch target. Off by default - it is an opt-in accessibility aid.
     */
    val highContrast: Boolean = false,
    /** "Simplified mode": hides statistics, the calendar and secondary metadata. */
    val simplifiedMode: Boolean = false,
    val use24HourFormat: Boolean = true,
    val weekStart: WeekStart = WeekStart.MONDAY,

    // ------------------------------------------------------------- reminders
    /** Master switch for every notification the app posts. */
    val remindersEnabled: Boolean = true,
    /** Use setExactAndAllowWhileIdle instead of an inexact alarm. */
    val exactAlarms: Boolean = true,
    /**
     * Play the reminder tone.
     *
     * **Off by default.** The reminder should tell the user, not startle them or wake a sleeping
     * household - and a medication reminder fires at a fixed time every day, including at 6am. The
     * banner, the lock screen entry and the vibration are all kept, so the reminder is still
     * impossible to miss; only the tone is opt-in.
     */
    val soundEnabled: Boolean = false,
    /** Vibrate on a reminder. */
    val vibrationEnabled: Boolean = true,
    /**
     * Show the reminder as a heads-up banner over whatever is on screen ("顶栏弹出"), and on the
     * lock screen.
     *
     * Implemented by routing the notification to a high-importance channel, because Android freezes
     * a channel's importance after creation - the only way to let the user change this later is to
     * own two channels and pick between them.
     */
    val headsUpEnabled: Boolean = true,
    /** Ring even when the ringer is in silent mode. Off by default. */
    val overrideSilent: Boolean = false,
    /** Custom notification sound, or null for the system default alarm tone. */
    val soundUri: String? = null,
    /** How long the notification waits before it may sound again. */
    val repeatReminderMinutes: Int = 10,
    /** Push the reminder again if the dose is still untouched after this many minutes. */
    val snoozeMinutes: Int = 10,
    /** Minutes after the planned time before an untouched dose is recorded as "未服药". */
    val missedGraceMinutes: Int = 30,
    /** Escalate a still-untouched dose to a missed reminder. */
    val missedReminderEnabled: Boolean = true,
    /**
     * How many notifications one dose may produce in total, **including the first**.
     *
     * The old implementation derived this from elapsed time and spent the entire allowance on the
     * clock rather than on notifications, so the cap silently collapsed to "one reminder". It is now
     * a stored counter, which is why the number finally means what it says.
     */
    val maxEscalationsPerDose: Int = 2,
    /** Quiet hours - notifications inside the window are silenced (still recorded). */
    val quietHoursEnabled: Boolean = false,
    val quietHoursStartMinute: Int = 22 * 60,
    val quietHoursEndMinute: Int = 7 * 60,

    // ------------------------------------- reminder reliability / humanised timing

    /**
     * Post a "还有一会儿" heads-up shortly before each dose.
     *
     * This is the humanised half of the rewrite. A single alarm at the exact minute asks the user to
     * be ready at that minute; a heads-up that says "早上 8 点左右该吃药了，还有 15 分钟" lets them
     * finish what they are doing and get a glass of water first. It is also a second, independent
     * delivery attempt for the same dose - if the main alarm is dropped by the OS, the user has
     * still been told.
     */
    val preReminderEnabled: Boolean = true,
    /** How far ahead that heads-up is posted. */
    val preReminderLeadMinutes: Int = 15,
    /**
     * Minutes after the due instant during which a reminder still counts as "on time".
     *
     * Nothing in the pipeline compares instants for equality, so an alarm that lands 40 seconds or
     * 4 minutes late is simply on time rather than "late".
     */
    val reminderFreshMinutes: Int = 20,
    /** Beyond this, a missed trigger degrades to a silent 补记 prompt instead of a banner. */
    val staleReminderMinutes: Int = 180,
    /**
     * How early an alarm may legitimately fire before the pipeline calls it a clock error.
     *
     * Error correction: a trigger outside this window is re-armed instead of announced, so a
     * time-zone change or a stale `PendingIntent` cannot produce a reminder at the wrong hour.
     */
    val earlyToleranceMinutes: Int = 5,
    /** Doses falling due within this many minutes of each other are announced as one digest. */
    val clusterWindowMinutes: Int = 20,
    /** Collapse doses that fall due together into a single summary notification. */
    val digestEnabled: Boolean = true,
    /** Keep a "已推迟到 …" notification visible while a dose is snoozed. */
    val snoozeStateNotificationEnabled: Boolean = true,
    /**
     * Hold a reminder that falls inside quiet hours and deliver it when they end.
     *
     * The old behaviour posted it silently at, say, 23:40 and never mentioned it again - a reminder
     * nobody sees is a reminder that did not happen. Deferring it to 07:00 is both quieter and more
     * likely to work.
     */
    val quietHoursDeferEnabled: Boolean = true,
    /**
     * Folder backups are written to, as a persisted SAF tree uri; null means the app's own storage.
     *
     * Device-local on purpose: it is deliberately not part of the backup DTO, so importing a backup
     * never repoints someone else's folder setting at a folder that does not exist here.
     */
    val backupFolderUri: String? = null,
    /**
     * Which bundled tone the audible reminder uses (see [com.meditrack.domain.reminder.ReminderTone]).
     *
     * Stored as the enum's name rather than a resource id, so the value survives any rebuild that
     * renumbers resources.
     */
    val reminderTone: String = com.meditrack.domain.reminder.ReminderTone.DEFAULT.name,

    // ------------------------------------------------------- ring (持续响铃)

    /**
     * What the reminder does after its first announcement.
     *
     * **On by default** ("keep ringing until you answer"), because a medication reminder the user can
     * sleep through is not doing its job. Users who find it too much can pick 只响一次, which restores
     * the pre-2.0 behaviour exactly.
     */
    val ringMode: String = com.meditrack.domain.reminder.ReminderRingMode.DEFAULT.name,
    /**
     * Safety cap on a continuous ring, in minutes.
     *
     * Without this, "一直响" on a phone left in a drawer would ring until the battery died - and an app
     * that does that gets uninstalled, taking the reminders with it. The notification stays put after
     * the cap, so nothing is lost but the noise.
     */
    val ringMaxMinutes: Int = 5,
    /** How many times [com.meditrack.domain.reminder.ReminderRingMode.FIXED_TIMES] chimes. */
    val ringTimes: Int = 3,
    /** Seconds of silence between two chimes. */
    val ringIntervalSeconds: Int = com.meditrack.domain.reminder.RingPolicy.DEFAULT_INTERVAL_SECONDS,

    /**
     * The [com.meditrack.data.local.entity.RingClip] row id chosen as the reminder sound, if any.
     *
     * A row id rather than a uri: the clip's audio lives in app-private storage and deleting the row
     * is what makes the file a cache-clear candidate, so the id is the single thing that has to stay
     * consistent. Null means "use [reminderTone]".
     */
    val ringClipId: Long? = null,

    // --------------------------------------------- «复查提醒» / follow-up review

    /**
     * Show a red, must-acknowledge reminder once a medication reaches its review threshold.
     *
     * **On by default**, but inert until the user actually configures a threshold on a medication:
     * nothing here can fire for a prescription nobody described.
     */
    val reviewReminderEnabled: Boolean = true,
    /**
     * Days before the threshold at which the gentle "还有几次就该复查了" heads-up appears.
     *
     * A review is a thing you have to book an appointment for, so being told only on the day is
     * useless. 0 disables the advance notice.
     */
    val reviewAdvanceNotice: Int = 3,
    /**
     * Reach 复查 and the app opens this search for you, instead of the built-in knowledge base.
     *
     * The question "这个药吃多久要去复查" has no honest offline answer - it depends on the drug, the
     * indication and the patient - so the two offered answers are "ask your doctor and write it down"
     * (the note, below) and "look it up". Baidu is the default because that is what the user asked
     * for; Bing is offered because it is the same feature with a different opinion.
     */
    val reviewSearchEngine: String = com.meditrack.data.local.entity.ReviewSearchEngine.DEFAULT.name,
    /** Extra words appended to the search query, so a user can pin down their own situation. */
    val reviewSearchSuffix: String = "",
    /**
     * Whether the app may ask GitHub for the latest release version.
     *
     * On by default because an app that never tells you it is outdated is worse, but this is the only
     * switch in the app that governs a network request, so it is explicit and documented.
     */
    val autoUpdateCheck: Boolean = true,
    val lastUpdateCheckAtMillis: Long = 0L,
    /** A release tag the user waved away; not offered again until something newer appears. */
    val dismissedUpdateVersion: String? = null,
    /** Show the silent "如果已经吃过，请点一下" prompt when a reminder is discovered too late. */
    val catchUpReminderEnabled: Boolean = true,
    /**
     * How often the rolling self-check alarm re-derives the entire schedule.
     *
     * This is the single biggest reliability change. The old pipeline re-armed everything once a
     * day, at midnight, and trusted the OS for the other 23 hours and 59 minutes. A lost
     * `PendingIntent` - the ordinary failure mode on aggressively managed Chinese OEM ROMs - was
     * therefore permanent until the user happened to open the app. A short-interval heartbeat turns
     * any lost alarm into a bounded delay of at most this many minutes.
     */
    val heartbeatMinutes: Int = 15,
    /** Run the independent WorkManager reconciliation path alongside the alarm heartbeat. */
    val reliabilityWorkerEnabled: Boolean = true,
    /**
     * Use alarm-clock-grade alarms (`setAlarmClock`) for dose reminders.
     *
     * **On by default.** This is the only alarm class Android exempts from Doze *and* that the
     * system itself tracks as a user-visible alarm, which is what makes it survive the background
     * cleanup that silently cancels ordinary alarms on aggressively managed ROMs. A medication
     * reminder that does not arrive is worthless, so punctuality wins over the small alarm icon this
     * puts in the status bar - and that icon doubles as visible proof that the reminder is armed.
     */
    val alarmClockAlarms: Boolean = true,
    /**
     * Keep a foreground service running so the process - and therefore its alarms - survive.
     *
     * **On by default.** Every other mechanism in this app is a *scheduled* wake-up, and a scheduled
     * wake-up is exactly what a force-stop or an OEM "deep clean" cancels: when that happens, the app
     * receives nothing at all until the user opens it, no matter how many alarms or jobs it had
     * queued. A running foreground service is the one thing that keeps the app out of the cached
     * bucket that those cleaners target.
     *
     * The cost is one low-priority, silent, ongoing notification. The user can switch it off in
     * 设置 → 提醒可靠性.
     */
    val guardServiceEnabled: Boolean = true,

    // ------------------------------------- unlock catch-up («解锁补提醒»)

    /**
     * Speak up about an overdue, still-unrecorded dose the moment the user picks the phone up.
     *
     * **On by default**, and it is the answer to the one failure a scheduled alarm cannot cover: the
     * reminder fired while the phone sat locked in a pocket, nobody saw it, and by the time the user
     * looked the dose had already passed its grace period - where the pipeline, quite correctly,
     * stops making noise and only files a silent 未服药 record. Turning "我没看见" into a second
     * chance is worth defaulting on, because the alternative is a missed dose nobody was told about.
     *
     * The check runs on unlock and whenever the app is brought back to the foreground. It does not
     * read the phone's usage history: it only reacts to the two moments the user is provably present.
     */
    val unlockReminderEnabled: Boolean = true,
    /**
     * How many audible unlock catch-ups one dose may produce during its day.
     *
     * Counted from the first unlock catch-up, not from the scheduled time, so a dose the user sees on
     * the lock screen still has its whole allowance left when they finally pick the phone up. A dose
     * row is one day, so the budget resets every day by construction.
     */
    val unlockReminderMaxPerDose: Int = 3,
    /**
     * Minimum spacing between two unlock catch-ups for the same dose.
     *
     * Unlocking a phone is not a rare event - it happens dozens of times a day - so without a floor
     * the whole budget would be gone before breakfast. Five minutes lets a genuinely unattended phone
     * produce a handful of real nudges instead of a buzz every time the screen lights up.
     */
    val unlockReminderMinGapMinutes: Int = 5,
    /**
     * Take over the screen, like a system alarm, when a reminder or a catch-up fires.
     *
     * **Off by default.** Android 14+ grants this only to genuine alarm-clock apps, so it has to be
     * allowed explicitly in the system settings - and a reminder that seizes the whole screen is a
     * strong thing to impose. The heads-up banner plus vibration is the default experience.
     */
    val fullScreenReminderEnabled: Boolean = false,

    // ------------------------ legacy idle deferral (retired, kept for backup round-trips)

    /**
     * Retired: idle deferral was replaced by the unlock catch-up above.
     *
     * The old feature held a reminder back while nobody was looking and released it later, which
     * meant the dose frequently crossed its grace period *while withheld* - so the release arrived as
     * a silent 未服药 note instead of a reminder. That is precisely the "提醒被抵掉" failure it was
     * meant to prevent, which is why the behaviour is gone rather than merely switched off.
     *
     * The fields remain so an existing installation, and an old JSON backup, still read back
     * losslessly; nothing consults them any more.
     */
    @Deprecated("Idle deferral was replaced by the unlock catch-up; kept for backup compatibility.")
    val idleDeferralEnabled: Boolean = false,
    @Deprecated("Idle deferral was replaced by the unlock catch-up; kept for backup compatibility.")
    val idleThresholdMinutes: Int = 30,
    @Deprecated("Idle deferral was replaced by the unlock catch-up; kept for backup compatibility.")
    val deferWhileScreenOff: Boolean = true,

    // --------------------------------------------------------------- widget
    /** The user's ceiling on how many dose rows the widget shows. */
    val widgetItemLimit: Int = WidgetPlannerDefaults.ITEM_LIMIT,
    /**
     * How often the widget is re-checked, in **seconds**.
     *
     * The offered range is 10 seconds to 10 minutes. That is far below anything WorkManager or
     * `AppWidgetProviderInfo.updatePeriodMillis` can express - their floors are 15 and 30 minutes -
     * so a short interval is driven by the background guard service's ticker, and this setting only
     * has effect while that service is running.
     *
     * A short interval does not mean a redraw every few seconds: the payload is fingerprinted first,
     * so the launcher is only disturbed when a dose actually changes state. What the interval buys is
     * *promptness*, which is the thing a medication widget is judged on.
     */
    val widgetRefreshSeconds: Int = 30,
    /** Show taken doses at the bottom of the widget list. */
    val widgetShowCompleted: Boolean = true,

    // ---------------------------------------------------------------- other
    /** Require biometric/device credential authentication to open the app. */
    val appLockEnabled: Boolean = false,
    /** Show the onboarding / permission walkthrough on next launch. */
    val onboardingCompleted: Boolean = false,
    /** Show the "多服" confirmation dialog when a dose exceeds its maximum. */
    val confirmOverDose: Boolean = true,
    /** How many days back the missed sweep should repair on app start. */
    val historyBackfillDays: Int = 30,
    /**
     * Whether the first-run 《用户协议》 and 《使用说明》 gate has been passed.
     *
     * Deliberately its own flag rather than reusing [onboardingCompleted], which gates the *permission*
     * walkthrough: the two are shown once each, they are allowed to be dismissed independently, and a
     * user who skipped the permissions should not be asked to sign the agreement again on the next
     * launch. Installing this version over an older one sets it only after it has been shown once, so
     * an existing user sees it exactly one time and never again - including after later updates.
     */
    val agreementAcceptedVersion: Int = 0,
) {

    /** True when [minuteOfDay] falls inside the configured quiet hours (handles wrap past midnight). */
    fun isWithinQuietHours(minuteOfDay: Int): Boolean {
        if (!quietHoursEnabled) return false
        val start = quietHoursStartMinute
        val end = quietHoursEndMinute
        return if (start <= end) {
            minuteOfDay in start until end
        } else {
            minuteOfDay >= start || minuteOfDay < end
        }
    }

    /** Radius used by every rounded card, scaled up in simplified/high-contrast mode. */
    val cardCornerDp: Int get() = if (simplifiedMode) 24 else 20

    /**
     * The ringing behaviour these settings describe, as a pure value.
     *
     * A computed property rather than a stored field so that changing one slider cannot leave the
     * three of them describing different things, and so the arithmetic lives in one tested place.
     */
    val ringPolicy: com.meditrack.domain.reminder.RingPolicy
        get() = com.meditrack.domain.reminder.RingPolicy.of(
            mode = com.meditrack.domain.reminder.ReminderRingMode.fromName(ringMode),
            chimeCount = ringTimes,
            intervalSeconds = ringIntervalSeconds,
            maxMinutes = ringMaxMinutes,
        )

    /** The search engine the «复查» button opens. */
    val reviewEngine: com.meditrack.data.local.entity.ReviewSearchEngine
        get() = com.meditrack.data.local.entity.ReviewSearchEngine.fromName(reviewSearchEngine)

    /** True while the first-run agreement gate still has to be shown. */
    val needsAgreement: Boolean get() = agreementAcceptedVersion < AGREEMENT_VERSION

    /** Minimum touch target, enlarged for the accessibility preset. */
    val minTouchTargetDp: Int
        get() = when {
            highContrast || fontScale == FontScale.HUGE -> 60
            fontScale == FontScale.EXTRA_LARGE -> 56
            else -> 48
        }
}

/** Defaults referenced from [UserPreferences] without creating a companion-object cycle. */
object WidgetPlannerDefaults {
    const val ITEM_LIMIT = 4
}

/**
 * The agreement revision the user has to accept.
 *
 * A number rather than a boolean so that a *materially* changed agreement can be shown again by
 * bumping it - while every ordinary feature release, which is what actually happens most of the time,
 * shows nothing. The user's request was explicit: sign once, never again, including across updates.
 */
const val AGREEMENT_VERSION = 1
