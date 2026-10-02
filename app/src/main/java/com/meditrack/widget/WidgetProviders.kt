package com.meditrack.widget

import android.content.ComponentName
import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * The home-screen widget.
 *
 * There is exactly one of these in the launcher's picker. An earlier revision shipped a family of
 * four fixed footprints (4x1 … 4x4); that was more choice than the feature warrants, since every one
 * of them was resizable anyway and the layout is derived from the tile's real size. The user now
 * picks "药准时" once and drags it to the shape they want.
 */
class MediTrackWidget : GlanceAppWidget() {

    /**
     * Compose per size, not once per widget.
     *
     * This is load-bearing, not a nicety. Glance's default ([SizeMode.Single]) composes the widget
     * once and keeps that composition when the user drags the tile to a different size, so a tile
     * created small renders that small layout forever. Because the layout here is computed from
     * [androidx.glance.LocalSize], that would mean a dragged-out tile showing the wrong row count -
     * the exact class of "显示错乱" this design exists to avoid.
     */
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val builder = runCatching { entryPoint(context).contentBuilder() }.getOrNull()
        val content = runCatching { builder?.build() }.getOrNull() ?: WidgetContentBuilder.EMPTY
        val options = runCatching { builder?.displayOptions() }.getOrNull() ?: WidgetDisplayOptions()

        // Remember what is on screen, so the frequent refresh tick can tell whether a redraw would
        // actually change anything.
        builder?.markRendered(content)

        provideContent {
            MediTrackWidgetContent(content, options)
        }
    }

    private fun entryPoint(context: Context): MediTrackWidgetEntryPoint =
        EntryPoints.get(context.applicationContext, MediTrackWidgetEntryPoint::class.java)

    /** The slice of the object graph the widget needs; nothing else is reachable from here. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface MediTrackWidgetEntryPoint {
        fun contentBuilder(): WidgetContentBuilder
    }
}

/**
 * AppWidgetProvider entry point.
 *
 * Glance renders the layout; the receiver exists to react to the system broadcasts. The refresh is
 * handed to WorkManager rather than done inline because `onUpdate` runs on the main thread and the
 * payload requires a database read.
 */
class MediTrackWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = MediTrackWidget()

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
        // Nothing is placed any more; stop waking up for it.
        WidgetRefresh.stopPeriodic(context)
    }
}

/**
 * Everything that refreshes the widget goes through here.
 *
 * ## Why not simply redraw on a timer
 *
 * The refresh interval can be as short as ten seconds. Redrawing a RemoteViews into the launcher's
 * process that often would be pure waste: nothing on this tile ticks, so nine redraws in ten would
 * paint exactly what is already there. [refreshIfChanged] therefore compares a fingerprint of the
 * payload first, which turns a short interval into a cheap poll that makes the tile *prompt* rather
 * than *busy*.
 */
object WidgetRefresh {

    /** Rebuilds and redraws unconditionally; used after a write, where the change is already known. */
    suspend fun refreshNow(context: Context) {
        runCatching { MediTrackWidget().updateAll(context) }
    }

    /**
     * Redraws only if the visible payload differs from what is already on screen.
     *
     * @return true when a redraw was issued
     */
    suspend fun refreshIfChanged(context: Context, builder: WidgetContentBuilder): Boolean {
        val content = runCatching { builder.build() }.getOrNull() ?: return false
        if (!builder.hasChangedSinceRender(content)) return false
        builder.markRendered(content)
        refreshNow(context)
        return true
    }

    /** True while a tile is actually on the home screen; the ticker polls only if so. */
    fun isPlaced(context: Context): Boolean = runCatching {
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        manager.getAppWidgetIds(ComponentName(context, MediTrackWidgetReceiver::class.java)).isNotEmpty()
    }.getOrDefault(false)

    /** Stops the fallback refresher. */
    fun stopPeriodic(context: Context) {
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(WidgetUpdateWorker.UNIQUE_NAME)
        }
    }

    /**
     * Schedules an immediate refresh.
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

    /**
     * Arms the 15-minute fallback refresher.
     *
     * This is **not** what honours the user's chosen interval: WorkManager's floor for periodic work
     * is fifteen minutes, so it cannot serve any of the intervals on offer. It exists so the tile
     * still updates when the background guard service - the only thing that can drive a sub-minute to
     * ten-minute cadence - is switched off.
     */
    fun applyFallback(context: Context) {
        runCatching {
            val request = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(
                FALLBACK_INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            )
                .setInitialDelay(1, TimeUnit.MINUTES)
                .addTag(WidgetUpdateWorker.WORK_TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WidgetUpdateWorker.UNIQUE_NAME,
                // UPDATE keeps the existing schedule rather than resetting it on every launch.
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }

    /** WorkManager's own floor for periodic work; the real interval comes from the guard service. */
    const val FALLBACK_INTERVAL_MINUTES = 15L
}
