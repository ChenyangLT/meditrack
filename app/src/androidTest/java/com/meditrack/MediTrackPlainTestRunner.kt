package com.meditrack

import androidx.test.runner.AndroidJUnitRunner

/**
 * Runner for instrumented tests that do **not** use Hilt.
 *
 * [MediTrackTestRunner] swaps in `HiltTestApplication`, which has no component until a
 * `HiltAndroidRule` builds one. That is fine while a `@HiltAndroidTest` is in charge, but it breaks
 * the moment a *production* component runs during the test: the app's real alarms keep firing, and
 * `ReminderReceiver` - which is `@AndroidEntryPoint` - then fails to inject and takes the whole
 * instrumentation process down with
 *
 *     IllegalStateException: The component was not created. Check that you have added the HiltAndroidRule.
 *
 * which aborts every remaining test in the run.
 *
 * Tests that only need a Compose host (no injected dependencies) should therefore run under this
 * runner, with the production [MediTrackApp] in place, so background components keep working while
 * they execute.
 *
 * Usage:
 *
 *     adb shell am instrument -w \
 *       -e class com.meditrack.WheelPickerContractTest \
 *       com.meditrack.test/com.meditrack.MediTrackPlainTestRunner
 */
class MediTrackPlainTestRunner : AndroidJUnitRunner()
