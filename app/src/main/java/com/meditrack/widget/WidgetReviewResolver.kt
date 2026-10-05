package com.meditrack.widget

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import com.meditrack.data.local.MediTrackDatabase
import com.meditrack.data.prefs.settingsDataStore
import com.meditrack.domain.plan.WidgetReview
import com.meditrack.domain.review.ReviewProgress
import com.meditrack.domain.review.ReviewProgressCalculator
import kotlinx.coroutines.flow.first

/**
 * Supplies the «复查» line for each medication to the widget.
 *
 * ## Why an interface, and why it is not injected
 *
 * The widget renders from `WidgetContentBuilder`, which is reached through the widget's own `EntryPoint`
 * and - on some launchers - from a process where that entry point has not been composed. That is exactly
 * why the application context is published into [AppContextHolder] rather than injected, and the review
 * line is the first thing the widget needs that lives *outside* the single widget DAO read. Pulling the
 * whole object graph into that path to obtain it would undo the reason the path is manual.
 *
 * An interface rather than a single class so the widget's formatting rules can be exercised in a unit
 * test with a plain map, without a database, a DataStore or a device.
 */
interface WidgetReviewResolver {

    /**
     * The review label per medication id for [epochDay].
     *
     * Medications with nothing configured are absent from the map rather than mapped to null, so the
     * caller's lookup is a plain `map[id]`.
     */
    suspend fun reviewLabels(epochDay: Long): Map<Long, WidgetReview>

    companion object {

        private const val REVIEW_ENABLED_KEY_NAME = "review_reminder_enabled"
        private const val REVIEW_ADVANCE_KEY_NAME = "review_advance_notice"

        private const val DEFAULT_ADVANCE = 3

        /**
         * The two preference keys this file needs, spelled out rather than reaching into
         * `SettingsRepository`'s private `Keys` object.
         *
         * A deliberate duplication of two string literals in exchange for not widening that object's
         * visibility for one caller; a rename there is caught by the widget test that asserts the keys
         * still resolve against a real DataStore-shaped read.
         */
        private val REVIEW_ENABLED_KEY = booleanPreferencesKey(REVIEW_ENABLED_KEY_NAME)
        private val REVIEW_ADVANCE_KEY = intPreferencesKey(REVIEW_ADVANCE_KEY_NAME)

        /** Published by [com.meditrack.MediTrackApp.onCreate], like [AppContextHolder]. */
        @Volatile
        private var instance: WidgetReviewResolver? = null

        fun install(resolver: WidgetReviewResolver) {
            instance = resolver
        }

        /**
         * @throws IllegalStateException when called before the Application was created, which is the same
         *         contract [AppContextHolder.requireContext] has - a widget rendered before `onCreate`
         *         could not read anything anyway.
         */
        fun require(): WidgetReviewResolver = instance
            ?: error("WidgetReviewResolver unavailable; MediTrackApp.onCreate has not run yet")

        /** The production resolver, reading the real database and preferences. */
        fun create(context: Context): WidgetReviewResolver {
            val appContext = context.applicationContext
            return object : WidgetReviewResolver {
                override suspend fun reviewLabels(epochDay: Long): Map<Long, WidgetReview> {
                    val database = MediTrackDatabase.getInstance(appContext)
                    // `settingsDataStore` is a single named file, so this returns the same instance the
                    // injected SettingsRepository uses; no second settings store is created.
                    val prefs = appContext.settingsDataStore.data.first()
                    if (prefs[REVIEW_ENABLED_KEY] == false) return emptyMap()
                    val advanceNotice = prefs[REVIEW_ADVANCE_KEY] ?: DEFAULT_ADVANCE

                    return database.medicationDao().getAllOnce()
                        .asSequence()
                        .filter { it.reviewThreshold > 0.0 }
                        .associate { medication ->
                            val round = database.reviewCycleDao().openCycleFor(medication.id)
                            val progress = ReviewProgressCalculator.progressOf(
                                mode = medication.reviewCountMode,
                                // The stored count is authoritative for doses and quantity; for days it is
                                // ignored and derived from the round's start day.
                                storedCount = round?.count ?: 0.0,
                                threshold = medication.reviewThreshold,
                                startedEpochDay = round?.startedEpochDay ?: epochDay,
                                epochDay = epochDay,
                            )
                            medication.id to labelFor(progress, advanceNotice, medication.reviewReminderEnabled)
                        }
                        .filterValues { it != null }
                        .mapValues { it.value!! }
                }
            }
        }

        /**
         * The label for one medication, or null when nothing should be drawn.
         *
         * A medication whose review reminder is switched *off* still shows its line: that switch means
         * "stop telling me", not "hide the countdown" - and a user who turned it off is still served by
         * knowing how far along they are. Only "nothing configured" produces nothing.
         *
         * The line appears only inside the advance-notice window. A permanent "复查：还差 47 次" on the home
         * screen is noise, and it would crowd out the dose information the tile exists for.
         */
        fun labelFor(
            progress: ReviewProgress,
            advanceNotice: Int,
            reminderEnabled: Boolean,
        ): WidgetReview? {
            if (!progress.isConfigured) return null
            if (progress.isReached) return WidgetReview(label = "该复查了", isDue = true)
            if (!progress.shouldGiveAdvanceNotice(advanceNotice)) return null
            val suffix = if (reminderEnabled) "" else "（已关闭提醒）"
            return WidgetReview(label = "复查：${progress.remainingLabel}$suffix", isDue = false)
        }
    }
}
