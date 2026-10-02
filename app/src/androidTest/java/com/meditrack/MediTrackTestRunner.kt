package com.meditrack

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * JUnit runner for the instrumented tests.
 *
 * Hilt's instrumented tests need an [Application] that Hilt generates (`HiltTestApplication`)
 * instead of the production [MediTrackApp], because the test component must be able to replace
 * production bindings. Swapping the application class here is the documented way to do that without
 * duplicating the whole manifest into `src/androidTest`.
 *
 * The runner is wired up through `testInstrumentationRunner` in `app/build.gradle.kts`.
 *
 * ## Only use this for `@HiltAndroidTest` classes
 *
 * `HiltTestApplication` has no component until a `HiltAndroidRule` builds one, so this runner also
 * *disables* the app's own background machinery: a real alarm firing mid-run reaches
 * `ReminderReceiver`, which is `@AndroidEntryPoint`, fails to inject, and crashes the whole
 * instrumentation process - aborting every test that had not run yet.
 *
 * Tests that do not need injection must use [MediTrackPlainTestRunner] instead.
 */
class MediTrackTestRunner : AndroidJUnitRunner() {

    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
