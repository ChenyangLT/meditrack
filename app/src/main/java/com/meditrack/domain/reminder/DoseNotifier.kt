package com.meditrack.domain.reminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.meditrack.MainActivity
import com.meditrack.R
import com.meditrack.core.util.DateTimeUtils
import com.meditrack.core.util.QuantityFormatter
import com.meditrack.data.local.entity.DoseLog
import com.meditrack.data.local.entity.Medication
import com.meditrack.data.prefs.UserPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds and posts every medication notification.
 *
 * ## What "humanised" means here
 *
 * The previous notifications were accurate and unfriendly. They said `计划 08:00`, they arrived once
 * at 08:00 to the second, and they were the only thing that ever appeared - so a lost alarm meant a
 * lost dose, and a person who was in the shower at 08:00 never learned that anything had happened.
 *
 * Five changes replace that:
 *
 *  1. **A heads-up before the moment.** [showPreReminder] says "还有 15 分钟：阿司匹林", so the user
 *     can finish what they are doing and fetch a glass of water. It is also a second, independent
 *     delivery attempt for the same dose.
 *  2. **Time in the language people use.** The headline reads 早上 8 点左右 rather than 08:00; the
 *     exact stored minute is still one line below, in the expanded detail, so nothing clinical is
 *     lost to the friendly phrasing.
 *  3. **One notification per moment, not per dose.** Doses that fall due together become a single
 *     [showDigest] summary with the individual items as group children - one entry in the shade
 *     instead of three buzzes five minutes apart.
 *  4. **Snoozing leaves a trail.** [showSnoozeState] keeps "已推迟到 8:35" visible with a 现在服用
 *     button, so pressing 稍后 no longer means the reminder simply vanishes.
 *  5. **Honesty about lateness.** A reminder that could not be delivered at the right time says so,
 *     and [showCatchUp] offers a one-tap way to correct the record instead of pretending the moment
 *     never happened.
 *
 * ## Why there are several channels
 *
 * A channel's importance, sound and vibration are frozen by the system at creation, so the only
 * supported way to let the user change banner behaviour later is to own several and route between
 * them. Each channel below therefore corresponds to one *intent*, not to one tone:
 *
 *  - [CHANNEL_REMINDER_ALERT] - banner + alarm tone + vibration.
 *  - [CHANNEL_REMINDER_VIBRATE] - banner + vibration, no tone. The shipped default.
 *  - [CHANNEL_REMINDER_QUIET] - shade only.
 *  - [CHANNEL_REMINDER_SILENT] - shade only and completely silent: quiet hours and catch-up prompts.
 *  - [CHANNEL_REMINDER_UPCOMING] - the gentle advance notice; shade only, never interrupts.
 *  - [CHANNEL_SNOOZE_STATE] - the "already snoozed" state note.
 *  - [CHANNEL_MISSED] - the 未服药 record.
 *  - [CHANNEL_STATUS] - four-second action confirmations.
 */
@Singleton
class DoseNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val manager = NotificationManagerCompat.from(context)

    init {
        createChannels()
    }

    /** Creates the channels and their group; safe to call repeatedly (the OS ignores existing ids). */
    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val system = context.getSystemService(NotificationManager::class.java) ?: return

        system.createNotificationChannelGroup(
            NotificationChannelGroup(GROUP_REMINDERS, context.getString(R.string.notification_group_reminders))
        )

        val alert = NotificationChannel(
            CHANNEL_REMINDER_ALERT,
            context.getString(R.string.notification_channel_reminder_alert),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_channel_reminder_alert_desc)
            setGroup(GROUP_REMINDERS)
            enableVibration(true)
            vibrationPattern = VIBRATION_PATTERN
            enableLights(true)
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(defaultAlarmSoundUri(), alarmAudioAttributes())
        }

        val vibrate = NotificationChannel(
            CHANNEL_REMINDER_VIBRATE,
            context.getString(R.string.notification_channel_reminder_vibrate),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_channel_reminder_vibrate_desc)
            setGroup(GROUP_REMINDERS)
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = VIBRATION_PATTERN
            enableLights(true)
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val quiet = NotificationChannel(
            CHANNEL_REMINDER_QUIET,
            context.getString(R.string.notification_channel_reminder_quiet),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_reminder_quiet_desc)
            setGroup(GROUP_REMINDERS)
            enableVibration(false)
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }

        val silent = NotificationChannel(
            CHANNEL_REMINDER_SILENT,
            context.getString(R.string.notification_channel_reminder_silent),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_reminder_silent_desc)
            setGroup(GROUP_REMINDERS)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }

        /**
         * The advance notice.
         *
         * IMPORTANCE_DEFAULT rather than HIGH on purpose: this one is a courtesy, not an alarm. It
         * appears in the shade and raises the app's dot, and it does not take over the screen fifteen
         * minutes before a dose is due.
         */
        val upcoming = NotificationChannel(
            CHANNEL_REMINDER_UPCOMING,
            context.getString(R.string.notification_channel_reminder_upcoming),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_reminder_upcoming_desc)
            setGroup(GROUP_REMINDERS)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }

        /**
         * "已推迟到 …".
         *
         * IMPORTANCE_LOW so it never buzzes: the user made this happen by pressing 稍后, and the last
         * thing they need is a notification about the notification they just deferred.
         */
        val snoozeState = NotificationChannel(
            CHANNEL_SNOOZE_STATE,
            context.getString(R.string.notification_channel_snooze_state),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_snooze_state_desc)
            setGroup(GROUP_REMINDERS)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }

        val missed = NotificationChannel(
            CHANNEL_MISSED,
            context.getString(R.string.notification_channel_missed),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_missed_desc)
            setGroup(GROUP_REMINDERS)
            enableVibration(false)
            setShowBadge(true)
        }

        val status = NotificationChannel(
            CHANNEL_STATUS,
            context.getString(R.string.notification_channel_status),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_status_desc)
            enableVibration(false)
            setShowBadge(false)
        }

        /**
         * The guard service's ongoing notification.
         *
         * IMPORTANCE_MIN rather than LOW: this one exists to keep the process alive and to be
         * *findable*, not to be noticed. It is silent, badge-less, and sits at the bottom of the
         * shade - the user should be able to confirm protection is on, and never be told about it.
         */
        val guard = NotificationChannel(
            CHANNEL_GUARD,
            context.getString(R.string.notification_channel_guard),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = context.getString(R.string.notification_channel_guard_desc)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }

        system.createNotificationChannels(
            listOf(alert, vibrate, quiet, silent, upcoming, snoozeState, missed, status, guard)
        )
    }

    // ------------------------------------------------------------- reminders

    /**
     * Posts "还有 N 分钟：<name>".
     *
     * Shares the dose's notification id, so the actual reminder replaces this one rather than
     * stacking next to it: one entry per dose that evolves as the moment approaches.
     */
    fun showPreReminder(
        dose: DoseLog,
        medication: Medication?,
        prefs: UserPreferences,
        leadMillis: Long,
    ) {
        val name = medication?.name ?: context.getString(R.string.unknown_medication)
        val leadMinutes = (leadMillis / 60_000L).coerceAtLeast(1L)
        val when_ = ReminderTiming.approximateLabel(dose.plannedMinuteOfDay)
        val quantity = QuantityFormatter.format(dose.plannedQuantity, dose.plannedUnit)

        notify(
            dose = dose,
            channel = CHANNEL_REMINDER_UPCOMING,
            title = context.getString(R.string.notification_title_pre, leadMinutes, name),
            body = "$when_ · $quantity",
            detail = listOfNotNull(
                context.getString(R.string.notification_line_pre),
                context.getString(R.string.notification_line_exact_time, DateTimeUtils.formatMinuteOfDay(dose.plannedMinuteOfDay, prefs.use24HourFormat)),
                context.getString(R.string.notification_line_dose, quantity),
                medication?.foodTiming?.takeIf { it.shortLabel.isNotBlank() }?.let {
                    context.getString(R.string.notification_line_food, it.label)
                },
                medication?.note?.takeIf { it.isNotBlank() }?.let {
                    context.getString(R.string.notification_line_note, it)
                },
            ).joinToString("\n"),
            prefs = prefs,
            silent = true,
            actions = listOf(Action.TAKEN, Action.SKIP),
            priority = NotificationCompat.PRIORITY_DEFAULT,
        )
    }

    /**
     * Posts "该吃药了：<name>".
     *
     * @param quiet inside the user's quiet hours (the reminder is already a repeat, or the user asked
     *        to be told without being interrupted)
     * @param lateMillis how long after the due instant this reminder actually fired, 0 when on time
     */
    fun showDoseReminder(
        dose: DoseLog,
        medication: Medication?,
        prefs: UserPreferences,
        quiet: Boolean = false,
        lateMillis: Long = 0L,
    ) {
        val name = medication?.name ?: context.getString(R.string.unknown_medication)
        val quantity = QuantityFormatter.format(dose.plannedQuantity, dose.plannedUnit)
        val approximate = ReminderTiming.approximateLabel(dose.plannedMinuteOfDay)
        val bodyParts = mutableListOf(approximate, quantity)
        medication?.foodTiming?.takeIf { it.shortLabel.isNotBlank() }?.let { bodyParts += it.label }

        // A state note about a snooze that has now come due is stale by definition.
        cancelSnoozeState(dose.id)

        notify(
            dose = dose,
            channel = null,
            title = context.getString(R.string.notification_title_dose_human, name),
            body = bodyParts.joinToString(" · "),
            detail = buildDoseDetail(dose, medication, prefs) +
                latenessLine(lateMillis),
            prefs = prefs,
            silent = quiet,
            actions = listOf(Action.TAKEN, Action.SNOOZE, Action.SKIP),
        )
    }

    /**
     * Posts the silent "还没记录" prompt for a dose whose moment has passed.
     *
     * Always silent and always on the silent channel: the user has already missed the window, and
     * waking them up about it afterwards would be strictly worse than saying nothing. What it *does*
     * offer is the one-tap correction - "如果已经吃过，点一下补记" - which is the only part of a late
     * reminder that has any value left.
     */
    fun showCatchUp(
        dose: DoseLog,
        medication: Medication?,
        prefs: UserPreferences,
        lateMillis: Long,
    ) {
        val name = medication?.name ?: context.getString(R.string.unknown_medication)
        val approximate = ReminderTiming.approximateLabel(dose.plannedMinuteOfDay)

        notify(
            dose = dose,
            channel = CHANNEL_REMINDER_SILENT,
            title = context.getString(R.string.notification_title_catchup, name),
            body = context.getString(R.string.notification_body_catchup, approximate),
            detail = buildDoseDetail(dose, medication, prefs) +
                "\n" + context.getString(R.string.notification_line_late, ReminderTiming.latenessLabel(lateMillis)),
            prefs = prefs,
            silent = true,
            actions = listOf(Action.TAKEN, Action.SKIP),
            priority = NotificationCompat.PRIORITY_LOW,
        )
    }

    /**
     * Posts "未服药提醒：<name>" once the grace period has lapsed.
     *
     * Silent, and sent exactly once per dose. The record also exists on the today screen and in
     * history; this notification is the nudge, not the ledger.
     */
    fun showMissedReminder(dose: DoseLog, medication: Medication?, prefs: UserPreferences) {
        val name = medication?.name ?: context.getString(R.string.unknown_medication)
        val approximate = ReminderTiming.approximateLabel(dose.plannedMinuteOfDay)

        notify(
            dose = dose,
            channel = CHANNEL_MISSED,
            title = context.getString(R.string.notification_title_missed, name),
            body = context.getString(R.string.notification_body_missed, approximate, dose.progressLabel),
            detail = buildDoseDetail(dose, medication, prefs) +
                "\n" + context.getString(R.string.notification_line_missed_recorded, dose.progressLabel),
            prefs = prefs,
            silent = true,
            actions = listOf(Action.TAKEN, Action.SKIP),
            priority = NotificationCompat.PRIORITY_DEFAULT,
        )
    }

    /**
     * One collapsed summary for doses that were announced together.
     *
     * The individual notifications remain as group children, each with its own 已服 / 稍后 / 跳过, so
     * the digest costs the user nothing in the way of actions - it only removes the repeated buzzes.
     */
    fun showDigest(items: List<Pair<DoseLog, Medication?>>, prefs: UserPreferences) {
        if (items.size < 2 || !manager.areNotificationsEnabled()) return

        val names = items.take(4).joinToString("、") { (_, med) ->
            med?.name ?: context.getString(R.string.unknown_medication)
        }
        val body = if (items.size > 4) "$names 等" else names
        val moment = ReminderTiming.approximateLabel(items.first().first.plannedMinuteOfDay)

        val notification = NotificationCompat.Builder(context, channelFor(prefs, silent = false))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_title_digest, items.size))
            .setContentText("$moment · $body")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$moment · $body"))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setGroup(GROUP_REMINDERS)
            .setGroupSummary(true)
            // The children are what buzz; a summary that alerting too would double every buzz.
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setContentIntent(summaryContentIntent())
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

        runCatching { manager.notify(DIGEST_ID, notification) }
            .onFailure { Log.e(TAG, "digest notify() failed", it) }
    }

    /**
     * Posts "已推迟：<name> / 将在 08:35 再提醒你".
     *
     * This is the answer to the oldest complaint about snooze buttons: pressing one makes the
     * reminder disappear with no evidence that anything is still scheduled. Here it is replaced by a
     * quiet, silent note that says when it will be back and offers to skip the wait.
     */
    fun showSnoozeState(
        dose: DoseLog,
        medication: Medication?,
        prefs: UserPreferences,
        untilMillis: Long,
    ) {
        if (!prefs.snoozeStateNotificationEnabled || !manager.areNotificationsEnabled()) return

        val name = medication?.name ?: context.getString(R.string.unknown_medication)
        val until = DateTimeUtils.formatDateTime(untilMillis).takeLast(5)

        val notification = NotificationCompat.Builder(context, CHANNEL_SNOOZE_STATE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_title_snooze_state, name))
            .setContentText(context.getString(R.string.notification_body_snooze_state, until))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    context.getString(R.string.notification_body_snooze_state, until) +
                        "\n" + context.getString(R.string.notification_line_snooze_how)
                )
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setGroup(GROUP_REMINDERS)
            .setContentIntent(contentIntent(dose))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .addAction(
                R.drawable.ic_notification,
                context.getString(R.string.notification_action_take_now),
                actionIntent(NotificationActionReceiver.ACTION_TAKEN, dose.id),
            )
            .build()

        runCatching { manager.notify(snoozeNotificationId(dose.id), notification) }
            .onFailure { Log.e(TAG, "snooze-state notify() failed for dose ${dose.id}", it) }
    }

    // ------------------------------------------------------------ lifecycle

    /** Removes every notification belonging to a dose; called whenever it becomes resolved. */
    fun cancel(doseId: Long) {
        manager.cancel(doseNotificationId(doseId))
        manager.cancel(snoozeNotificationId(doseId))
    }

    /** Removes just the "已推迟" note, which is stale the moment the reminder comes back. */
    fun cancelSnoozeState(doseId: Long) {
        manager.cancel(snoozeNotificationId(doseId))
    }

    fun cancelDigest() {
        manager.cancel(DIGEST_ID)
    }

    fun cancelAll() {
        manager.cancelAll()
    }

    /**
     * Posts the guard service's ongoing notification.
     *
     * Split out from the service so the channel and the notification id live in one place with every
     * other notification the app owns - a foreground service whose notification silently failed to
     * post is a foreground service the system will kill.
     */
    fun notifyGuard(notification: Notification) {
        runCatching { manager.notify(ReminderGuardService.NOTIFICATION_ID, notification) }
            .onFailure { Log.e(TAG, "guard notification failed", it) }
    }

    /**
     * Posts a real notification on the real reminder channel, so the user can verify the whole chain.
     *
     * Deliberately not a fake "test mode" notification: it uses the same channel routing, the same
     * sound and vibration resolution and the same permission path as an actual reminder, because the
     * question the user is asking is exactly "will the real thing look like this?".
     */
    fun showSelfTest() {
        val prefs = UserPreferences()
        val notification = NotificationCompat.Builder(context, channelFor(prefs, silent = false))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.self_test_title))
            .setContentText(context.getString(R.string.self_test_body))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(context.getString(R.string.self_test_detail))
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setTimeoutAfter(SELF_TEST_TIMEOUT_MILLIS)
            .build()
        runCatching { manager.notify(SELF_TEST_ID, notification) }
            .onFailure { Log.e(TAG, "self-test notify() failed", it) }
    }

    /**
     * Low-priority confirmation shown after an action on the notification, so the user gets visible
     * feedback without leaving the lock screen.
     */
    fun showActionConfirmation(text: String) {
        if (!manager.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setTimeoutAfter(4_000L)
            .build()
        runCatching { manager.notify(FEEDBACK_ID, notification) }
    }

    // -------------------------------------------------------------- internals

    /** The action buttons a given reminder offers. */
    private enum class Action { TAKEN, SNOOZE, SKIP }

    private fun notify(
        dose: DoseLog,
        channel: String?,
        title: String,
        body: String,
        detail: String,
        prefs: UserPreferences,
        silent: Boolean,
        actions: List<Action>,
        priority: Int = NotificationCompat.PRIORITY_HIGH,
    ) {
        // Logged rather than silently returning: "the alarm fired and nothing appeared" is the single
        // hardest symptom to diagnose, and this guard plus a throwing notify() are its only causes.
        if (!manager.areNotificationsEnabled()) {
            Log.w(TAG, "not posting reminder for dose ${dose.id}: notifications are disabled for the app")
            return
        }

        val effectiveSilent = silent || !prefs.remindersEnabled
        val resolvedChannel = channel ?: channelFor(prefs, effectiveSilent)

        val builder = NotificationCompat.Builder(context, resolvedChannel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(if (resolvedChannel == CHANNEL_REMINDER_ALERT) NotificationCompat.PRIORITY_HIGH else priority)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            // PUBLIC so the detail is readable on the lock screen without unlocking, which is exactly
            // what a medication reminder is for. The content is the user's own prescription.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setGroup(GROUP_REMINDERS)
            .setContentIntent(contentIntent(dose))
            .setAutoCancel(true)
            // Each escalation is meant to be noticed again, so alerting is deliberately not limited
            // to the first post.
            .setOnlyAlertOnce(false)
            .setShowWhen(true)
            .setWhen(dose.plannedTimeMillis)
            .applySoundAndVibration(prefs, effectiveSilent)

        for (action in actions) {
            builder.addAction(
                R.drawable.ic_notification,
                actionLabel(action, prefs),
                actionIntent(action.key, dose.id),
            )
        }

        val id = doseNotificationId(dose.id)
        runCatching { manager.notify(id, builder.build()) }
            .onSuccess { Log.i(TAG, "posted id=$id channel=$resolvedChannel silent=$effectiveSilent title=$title") }
            .onFailure { Log.e(TAG, "notify() failed for dose ${dose.id} on channel $resolvedChannel", it) }
    }

    private fun actionLabel(action: Action, prefs: UserPreferences): String = when (action) {
        Action.TAKEN -> context.getString(R.string.action_taken)
        // The resource has a %1$d placeholder and MUST be formatted here; passing the bare id renders
        // the literal "稍后 %1$d 分钟" on the button, which is what once shipped.
        Action.SNOOZE -> context.getString(R.string.action_snooze, prefs.snoozeMinutes)
        Action.SKIP -> context.getString(R.string.action_skip)
    }

    private val Action.key: String
        get() = when (this) {
            Action.TAKEN -> NotificationActionReceiver.ACTION_TAKEN
            Action.SNOOZE -> NotificationActionReceiver.ACTION_SNOOZE
            Action.SKIP -> NotificationActionReceiver.ACTION_SKIP
        }

    /**
     * Picks the channel, which is what actually decides sound, vibration and banner behaviour.
     *
     * Routing is used instead of per-notification overrides because Android freezes a channel's
     * sound/vibration/importance at creation and the channel wins over the notification. An earlier
     * revision called `setSound(null)` on every notification, which silently muted the alert
     * channel - the reminder then arrived with no sound and no vibration at all.
     */
    private fun channelFor(prefs: UserPreferences, silent: Boolean): String = when {
        silent -> CHANNEL_REMINDER_SILENT
        !prefs.soundEnabled -> if (prefs.headsUpEnabled) CHANNEL_REMINDER_VIBRATE else CHANNEL_REMINDER_QUIET
        prefs.headsUpEnabled -> CHANNEL_REMINDER_ALERT
        else -> CHANNEL_REMINDER_QUIET
    }

    /**
     * Applies per-notification sound and vibration.
     *
     * `sound = null` means "inherit the channel", which is what we want on API 26+: the channel is
     * created with the user's chosen tone and an alarm-stream usage, and re-declaring it here is what
     * previously broke the alert. The exception is a **custom** user-selected tone, which is set
     * explicitly because it may differ from the channel's default and the channel cannot be changed
     * retroactively. Below API 26 there are no channels, so everything has to be set here.
     */
    private fun NotificationCompat.Builder.applySoundAndVibration(
        prefs: UserPreferences,
        silent: Boolean,
    ): NotificationCompat.Builder {
        if (silent) {
            setSilent(true)
            return this
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            if (prefs.soundEnabled) {
                @Suppress("DEPRECATION")
                setDefaults(NotificationCompat.DEFAULT_ALL)
            }
            if (prefs.vibrationEnabled) setVibrate(VIBRATION_PATTERN)
            return this
        }

        if (prefs.soundEnabled) {
            prefs.soundUri?.let(Uri::parse)?.let { setSound(it) }
        }
        if (prefs.vibrationEnabled) setVibrate(VIBRATION_PATTERN)

        if (prefs.overrideSilent) {
            // Lets the reminder behave like an alarm rather than a chat ping.
            setCategory(NotificationCompat.CATEGORY_ALARM)
        }
        return this
    }

    private fun alarmAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .setUsage(AudioAttributes.USAGE_ALARM)
        .build()

    private fun defaultAlarmSoundUri(): Uri? =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    private fun latenessLine(lateMillis: Long): String =
        if (lateMillis < LATE_DISCLOSURE_MILLIS) {
            ""
        } else {
            "\n" + context.getString(
                R.string.notification_line_late,
                ReminderTiming.latenessLabel(lateMillis),
            )
        }

    /**
     * The full detail, revealed when the notification is expanded (or shown on the lock screen,
     * where Android renders the big text).
     *
     * One labelled field per line rather than one long "·" chain: a medication reminder is read in a
     * hurry, often half-awake, and the question is usually a single one - *how much*, or *when*. The
     * exact planned time lives here rather than in the headline, so the friendly phrasing costs the
     * user nothing clinically.
     */
    private fun buildDoseDetail(
        dose: DoseLog,
        medication: Medication?,
        prefs: UserPreferences,
    ): String {
        val lines = mutableListOf<String>()
        lines += context.getString(
            R.string.notification_line_exact_time,
            DateTimeUtils.formatMinuteOfDay(dose.plannedMinuteOfDay, prefs.use24HourFormat),
        )
        lines += context.getString(
            R.string.notification_line_dose,
            QuantityFormatter.format(dose.plannedQuantity, dose.plannedUnit),
        )
        medication?.strength?.takeIf { it.isNotBlank() }?.let {
            lines += context.getString(R.string.notification_line_strength, it)
        }
        medication?.foodTiming?.takeIf { it.shortLabel.isNotBlank() }?.let {
            lines += context.getString(R.string.notification_line_food, it.label)
        }
        medication?.note?.takeIf { it.isNotBlank() }?.let {
            lines += context.getString(R.string.notification_line_note, it)
        }
        medication?.takeIf { it.stockAmount > 0.0 }?.let {
            lines += context.getString(
                R.string.notification_line_stock,
                QuantityFormatter.format(it.stockAmount, dose.plannedUnit),
            )
        }
        return lines.joinToString("\n")
    }

    private fun contentIntent(dose: DoseLog): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_FOCUS_DOSE_ID, dose.id)
            putExtra(MainActivity.EXTRA_FOCUS_EPOCH_DAY, dose.epochDay)
        }
        return PendingIntent.getActivity(
            context,
            (dose.id % 10_000L).toInt() + CONTENT_REQUEST_BASE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun summaryContentIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            CONTENT_REQUEST_BASE + 1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionIntent(action: String, doseId: Long): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(NotificationActionReceiver.EXTRA_DOSE_ID, doseId)
        }
        return PendingIntent.getBroadcast(
            context,
            (doseId % 10_000L).toInt() + action.hashCode().and(0x0FFF) + ACTION_REQUEST_BASE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "DoseNotifier"

        /** High-importance: shows a heads-up banner and appears on the lock screen, with a tone. */
        const val CHANNEL_REMINDER_ALERT = "meditrack_reminder_alert"

        /** High importance with no tone. The shipped default: be told, not startled. */
        const val CHANNEL_REMINDER_VIBRATE = "meditrack_reminder_vibrate"

        /** Normal importance: visible in the shade only, never interrupts. */
        const val CHANNEL_REMINDER_QUIET = "meditrack_reminder_quiet"

        /** Low importance and genuinely silent: quiet hours, catch-up prompts. */
        const val CHANNEL_REMINDER_SILENT = "meditrack_reminder_silent"

        /** Shade-only advance notice posted before a dose is due. */
        const val CHANNEL_REMINDER_UPCOMING = "meditrack_reminder_upcoming"

        /** The "已推迟到 …" state note. */
        const val CHANNEL_SNOOZE_STATE = "meditrack_snooze_state"

        const val CHANNEL_MISSED = "meditrack_missed"
        const val CHANNEL_STATUS = "meditrack_status"

        /** The guard service's ongoing notification. */
        const val CHANNEL_GUARD = "meditrack_guard"

        /** Notification-channel group shown in Android's settings. */
        const val GROUP_REMINDERS = "meditrack_reminders"

        /** Matches the alert channel's own pattern, applied when the user enables vibration. */
        private val VIBRATION_PATTERN = longArrayOf(0, 400, 200, 400)

        /** Lateness below two minutes is not worth disclosing; it is ordinary scheduling jitter. */
        private const val LATE_DISCLOSURE_MILLIS = 120_000L

        private const val CONTENT_REQUEST_BASE = 500_000
        private const val ACTION_REQUEST_BASE = 600_000
        private const val FEEDBACK_ID = 999_999
        private const val DIGEST_ID = 999_998
        private const val SELF_TEST_ID = 999_997

        /** Long enough to be noticed and heard, short enough not to become clutter. */
        private const val SELF_TEST_TIMEOUT_MILLIS = 30_000L

        /** Ids are namespaced so the three notification kinds for one dose never collide. */
        private const val SNOOZE_STATE_OFFSET = 1_000_000

        /** One notification per dose, reused by the heads-up, the reminder, the catch-up and the miss. */
        fun doseNotificationId(doseId: Long): Int = (doseId % 1_000_000L).toInt() + 1

        /** The "已推迟" state note, which coexists with the reminder rather than replacing it. */
        fun snoozeNotificationId(doseId: Long): Int = doseNotificationId(doseId) + SNOOZE_STATE_OFFSET
    }
}
