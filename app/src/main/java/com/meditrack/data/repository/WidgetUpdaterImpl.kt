package com.meditrack.data.repository

import android.content.Context
import com.meditrack.widget.WidgetRefresh
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The implementation of [WidgetUpdater].
 *
 * Refreshes **all four** widget footprints, not just one: they are separate `GlanceAppWidget`
 * subclasses with separate receivers, and Glance's `updateAll` only reaches the widget ids belonging
 * to the receiver it resolves from the class it is called on.
 *
 * Wrapped defensively: a widget refresh must never be able to fail a user's dose recording. If the
 * launcher is not responding the exception is swallowed and the widget simply refreshes on its next
 * scheduled pass.
 */
@Singleton
class WidgetUpdaterImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : WidgetUpdater {

    override suspend fun requestUpdate() {
        runCatching { WidgetRefresh.refreshNow(context) }
    }
}
