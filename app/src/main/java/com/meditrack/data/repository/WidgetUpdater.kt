package com.meditrack.data.repository

/**
 * Narrow port for "tell the launcher the widget data changed".
 *
 * The repository layer must not depend on Glance or on the app widget manager directly: the widget
 * is a presentation concern, and unit tests of the repository should not need a launcher. The
 * implementation lives in the widget package and is bound in [com.meditrack.di.BindingsModule].
 */
interface WidgetUpdater {

    /**
     * Requests a refresh of every placed widget. Implementations must be safe to call from any
     * thread and must not throw when no widget is placed.
     */
    suspend fun requestUpdate()
}
