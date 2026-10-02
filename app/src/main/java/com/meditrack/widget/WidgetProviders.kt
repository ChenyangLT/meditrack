package com.meditrack.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The four widget footprints offered in the launcher's picker.
 *
 * All of them are **four columns wide**; only the default height differs. A medication list is read
 * top to bottom, so what a user actually wants to choose between is "how many doses do I want to see
 * without opening the app", and four columns is the narrowest that still fits a medication name, a
 * time, a quantity and a status side by side.
 *
 * | Entry | Default | Resizable down to |
 * | --- | --- | --- |
 * | [MediTrackWidgetCompact] | 4x1 | 4x1 |
 * | [MediTrackWidgetShort] | 4x2 | 4x1 |
 * | [MediTrackWidgetMedium] | 4x3 | 4x1 |
 * | [MediTrackWidgetTall] | 4x4 | 4x1 |
 *
 * Every entry can be resized freely in both directions, but never below the 4x1 floor declared in
 * its `appwidget-provider` metadata - which is what makes it impossible to create a tile small
 * enough to clip its own content, the way the old 2x2 default could.
 */
abstract class MediTrackWidgetBase : GlanceAppWidget() {

    /**
     * Compose per size, not once per widget.
     *
     * This is load-bearing, not a nicety. Glance's default ([SizeMode.Single]) composes the widget
     * once and keeps that composition when the user drags the tile to a different size, so a tile
     * created at one size renders that layout forever. Because the layout here is computed from
     * [androidx.glance.LocalSize], that would mean a resized tile showing the wrong row count - the
     * exact class of "显示错乱" this family of sizes exists to remove.
     */
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val builder = runCatching { entryPoint(context).contentBuilder() }.getOrNull()
        val content = runCatching { builder?.build() }.getOrNull() ?: WidgetContentBuilder.EMPTY
        val options = runCatching { builder?.displayOptions() }.getOrNull() ?: WidgetDisplayOptions()

        provideContent {
            MediTrackWidgetContent(content, options)
        }
    }

    private fun entryPoint(context: Context): WidgetEntryPoint =
        EntryPoints.get(context.applicationContext, WidgetEntryPoint::class.java)

    /** The slice of the object graph the widget needs; nothing else is reachable from here. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun contentBuilder(): WidgetContentBuilder
    }
}

/** 4x1 - the minimum: one dose, no title. */
class MediTrackWidgetCompact : MediTrackWidgetBase()

/** 4x2 - the everyday default. */
class MediTrackWidgetShort : MediTrackWidgetBase()

/** 4x3 - a morning-and-evening schedule at a glance. */
class MediTrackWidgetMedium : MediTrackWidgetBase()

/** 4x4 - a full day. */
class MediTrackWidgetTall : MediTrackWidgetBase()

/**
 * Shared receiver behaviour.
 *
 * Glance renders the layout; the receiver exists to react to the system broadcasts. The refresh is
 * handed to WorkManager rather than done inline because `onUpdate` runs on the main thread and the
 * payload requires a database read.
 */
abstract class MediTrackWidgetReceiverBase : GlanceAppWidgetReceiver() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: android.appwidget.AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // Glance renders on the super call; the enqueued work guarantees a second pass once any write
        // that raced with this render has landed.
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetRefresh.requestUpdate(context)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetRefresh.requestUpdate(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // No tile of *this* footprint is placed any more. The periodic refresher is only stopped when
        // the last of the four has gone, otherwise removing one size would silently freeze the others.
        if (!WidgetRefresh.anyPlaced(context)) WidgetRefresh.stopPeriodic(context)
    }
}

class MediTrackWidgetCompactReceiver : MediTrackWidgetReceiverBase() {
    override val glanceAppWidget: GlanceAppWidget = MediTrackWidgetCompact()
}

class MediTrackWidgetShortReceiver : MediTrackWidgetReceiverBase() {
    override val glanceAppWidget: GlanceAppWidget = MediTrackWidgetShort()
}

class MediTrackWidgetMediumReceiver : MediTrackWidgetReceiverBase() {
    override val glanceAppWidget: GlanceAppWidget = MediTrackWidgetMedium()
}

class MediTrackWidgetTallReceiver : MediTrackWidgetReceiverBase() {
    override val glanceAppWidget: GlanceAppWidget = MediTrackWidgetTall()
}

/**
 * Refreshes every footprint together.
 *
 * Each [GlanceAppWidget] subclass owns its own receiver, and `updateAll` only reaches the widget ids
 * belonging to the receiver it resolves from the class. With four variants that means a single
 * `updateAll` call would update exactly one of them and leave three showing yesterday's doses - so
 * every refresh in the app goes through this object instead.
 */
object WidgetRefresh {

    /** The four variants, constructed on demand. Cheap: [GlanceAppWidget] is stateless. */
    private fun allWidgets(): List<GlanceAppWidget> = listOf(
        MediTrackWidgetCompact(),
        MediTrackWidgetShort(),
        MediTrackWidgetMedium(),
        MediTrackWidgetTall(),
    )

    /** The four receiver classes, used to ask the system whether anything is placed. */
    private fun allReceivers(): List<Class<out MediTrackWidgetReceiverBase>> = listOf(
        MediTrackWidgetCompactReceiver::class.java,
        MediTrackWidgetShortReceiver::class.java,
        MediTrackWidgetMediumReceiver::class.java,
        MediTrackWidgetTallReceiver::class.java,
    )

    /** True while at least one tile of any footprint is on the home screen. */
    fun anyPlaced(context: Context): Boolean = runCatching {
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        allReceivers().any { receiver ->
            manager.getAppWidgetIds(android.content.ComponentName(context, receiver)).isNotEmpty()
        }
    }.getOrDefault(true)

    /** Stops the periodic refresher; used once the last tile has been removed. */
    fun stopPeriodic(context: Context) {
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(WidgetUpdateWorker.UNIQUE_NAME)
        }
    }

    /**
     * Schedules an immediate refresh of every footprint.
     *
     * Safe to call from anywhere (repository writes, notification actions, receivers). Uses KEEP so a
     * burst of writes collapses into a single refresh instead of queueing dozens.
     */
    fun requestUpdate(context: Context) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<WidgetUpdateWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WidgetUpdateWorker.UNIQUE_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }.onFailure {
            // WorkManager may not be ready during a very early broadcast; fall back to a direct
            // refresh so a widget placed at boot still populates.
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                refreshNow(context)
            }
        }
    }

    /** Direct refresh used by the worker and by anything already on a background dispatcher. */
    suspend fun refreshNow(context: Context) {
        for (widget in allWidgets()) {
            // One variant failing must not stop the other three from updating.
            runCatching { widget.updateAll(context) }
        }
    }
}
