package com.meditrack.widget

import android.content.Context
import com.meditrack.core.util.DateTimeUtils
import com.meditrack.data.local.MediTrackDatabase
import com.meditrack.data.prefs.SettingsRepository
import com.meditrack.domain.plan.TodaySummary
import com.meditrack.domain.plan.WidgetContent
import com.meditrack.domain.plan.WidgetPlanner
import com.meditrack.domain.plan.WidgetPriority
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How the user wants the widget to look, as opposed to what it should contain.
 *
 * Kept apart from [WidgetContent] because these two change for different reasons and at different
 * rates: the content changes every time a dose is recorded, while these change only when the user
 * opens settings. Bundling them means the widget reads the preferences once instead of once per
 * option.
 */
data class WidgetDisplayOptions(
    val showCompleted: Boolean = true,
    /**
     * The user's ceiling on how many doses a tile may show.
     *
     * This setting existed and was persisted and backed up for the life of the app while nothing ever
     * read it - the row count came from a hard-coded table in the composable instead. It is now the
     * second of the two limits the layout honours, the first being how many rows actually fit.
     */
    val itemLimit: Int = WidgetSizing.DEFAULT_ITEM_LIMIT,
)

/**
 * Builds the widget payload.
 *
 * Separated from the Glance class so the same payload can be produced by the refreshers and by unit
 * tests, and so the widget's `EntryPoint` only has to expose one object.
 *
 * It also owns the "what is currently on screen" fingerprint, because it is the one component both
 * the render path and the refresh tick go through - and the comparison between them is what lets the
 * refresh interval be as short as ten seconds without redrawing ten times a minute for nothing.
 */
@Singleton
class WidgetContentBuilder @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {

    /** Fingerprint of the payload most recently handed to the launcher. */
    @Volatile
    private var renderedFingerprint: String? = null

    /** Records what the widget is now showing. Called from the render path. */
    fun markRendered(content: WidgetContent) {
        renderedFingerprint = content.fingerprint()
    }

    /** True when [content] differs from what the widget is already showing. */
    fun hasChangedSinceRender(content: WidgetContent): Boolean =
        content.fingerprint() != renderedFingerprint

    suspend fun build(): WidgetContent {
        val prefs = settingsRepository.current()
        val today = DateTimeUtils.todayEpochDay()
        // Read through the singleton accessor rather than an injected DAO: this also runs from the
        // widget's EntryPoint lookup, where assembling the full graph is unnecessary.
        val context = AppContextHolder.requireContext()
        val rows = MediTrackDatabase.getInstance(context)
            .homeWidgetDao()
            .getWidgetRowsOnce(today)
            .map { row ->
                WidgetPlanner.WidgetRowSource(
                    epochDay = today,
                    doseId = row.doseId,
                    medicationId = row.medicationId,
                    name = row.name,
                    plannedMinuteOfDay = row.plannedMinuteOfDay,
                    plannedTimeMillis = row.plannedTimeMillis,
                    plannedQuantity = row.plannedQuantity,
                    plannedUnit = row.plannedUnit,
                    takenQuantity = row.takenQuantity,
                    status = row.status,
                    colorTag = row.colorTag,
                    icon = row.icon,
                    allowsFraction = row.allowsFraction,
                )
            }
        val content = WidgetPlanner.plan(rows, prefs.use24HourFormat)
        // The hide-completed rule lives on WidgetContent so this path and the repository's cannot
        // drift apart and make the tile flicker between two different lists.
        return content.visible(showCompleted = prefs.widgetShowCompleted)
    }

    /** The user's display preferences, read once per render. */
    suspend fun displayOptions(): WidgetDisplayOptions {
        val prefs = settingsRepository.current()
        return WidgetDisplayOptions(
            showCompleted = prefs.widgetShowCompleted,
            itemLimit = prefs.widgetItemLimit,
        )
    }

    companion object {
        /** Rendered when the database read fails; keeps the widget on screen and honest. */
        val EMPTY = WidgetContent(
            epochDay = 0L,
            items = emptyList(),
            summary = TodaySummary(
                epochDay = 0L,
                totalDoses = 0,
                completedDoses = 0,
                partialDoses = 0,
                missedDoses = 0,
                skippedDoses = 0,
                upcomingDoses = 0,
                plannedQuantity = 0.0,
                takenQuantity = 0.0,
                unitLabel = "",
            ),
        )
    }
}

/**
 * Holds the application context for the widget's non-injected code paths.
 *
 * A widget can be rendered in a process where Hilt has not composed a widget entry point, and the
 * content builder still needs a context to open the database. Publishing the application context
 * from [com.meditrack.MediTrackApp] is cheaper and more predictable than threading a Context
 * through every call site.
 */
object AppContextHolder {
    @Volatile
    private var applicationContext: Context? = null

    fun install(application: Context) {
        applicationContext = application.applicationContext
    }

    /** @throws IllegalStateException when called before the Application was created. */
    fun requireContext(): Context = applicationContext
        ?: error("Application context unavailable; MediTrackApp.onCreate has not run yet")
}
