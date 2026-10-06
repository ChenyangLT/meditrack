package com.meditrack

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meditrack.ui.MediTrackTestTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end regression tests for the navigation graph.
 *
 * These exist because of a crash that shipped: the editor destination was declared as
 * `medication_editor?arg={medicationId}` while the app navigated to `medication_editor?arg=0`.
 * Navigation matches the *filled* route against the declared pattern, so the literal `0` never
 * matched the `{medicationId}` placeholder and every tap on "添加药品" threw
 * `IllegalArgumentException: Navigation destination ... cannot be found` and killed the process.
 *
 * A JVM unit test cannot catch that (the graph only exists at runtime), which is why these drive the
 * real Activity.
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class NavigationSmokeTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
        composeRule.waitForIdle()
        dismissOnboardingIfPresent()
    }

    /**
     * The permission walkthrough is shown on a first launch, so skip past it.
     *
     * `onAllNodesWithText(...).onFirst()` is used rather than `onNodeWithText` because a text can
     * legitimately appear more than once (the app has two "添加药品" affordances in the empty state
     * by design, and the deep-link label appears in both the title and the body).
     */
    private fun dismissOnboardingIfPresent() {
        val skip = composeRule.onAllNodesWithText("暂时跳过")
        if (skip.fetchSemanticsNodes().isNotEmpty()) {
            skip.onFirst().performClick()
            composeRule.waitForIdle()
        }
    }

    /**
     * Tapping "添加药品" must open the editor.
     *
     * Before the fix this threw and the process died; now the editor renders its name field.
     */
    @Test
    fun addMedicationButton_opensEditor_withoutCrashing() {
        composeRule.onNodeWithText("药品").performClick()
        composeRule.waitForIdle()

        // Targeted by test tag: the label "添加药品" is merged into the button's semantics node and
        // also appears in the empty-state copy, so text lookup is unreliable here.
        composeRule.onNodeWithTag(MediTrackTestTags.ADD_MEDICATION_FAB).performClick()
        composeRule.waitForIdle()

        // The editor's own content proves the destination was reached. Before the route fix this
        // line was never reached: navigation threw and the process died.
        composeRule.onNodeWithText("药品名称").assertIsDisplayed()
        composeRule.onNodeWithText("保存").assertIsDisplayed()
    }

    /** The today screen's "add medication" must open the editor as well. */
    @Test
    fun addMedicationFromToday_opensEditor() {
        composeRule.onNodeWithText("今日").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(MediTrackTestTags.ADD_MEDICATION_FAB).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("药品名称").assertIsDisplayed()
    }

    /** The history tab must render its calendar and statistics without crashing. */
    @Test
    fun historyTab_rendersCalendarAndStatistics() {
        composeRule.onNodeWithText("历史").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("历史与统计").assertIsDisplayed()
        composeRule.onNodeWithText("依从率").assertIsDisplayed()
    }

    /** The settings tab must render, including the permission section. */
    @Test
    fun settingsTab_renders() {
        composeRule.onNodeWithText("设置").performClick()
        composeRule.waitForIdle()

        // "设置" appears as both the tab label and the screen title.
        composeRule.onAllNodesWithText("权限").onFirst().assertIsDisplayed()
    }

    /** Navigating away from and back to the today tab must not lose the screen. */
    @Test
    fun todayTab_survivesNavigationRoundTrip() {
        composeRule.onNodeWithText("历史").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("今日").performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("今天").onFirst().assertIsDisplayed()
    }
}
