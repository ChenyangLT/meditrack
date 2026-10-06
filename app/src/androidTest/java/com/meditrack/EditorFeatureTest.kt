package com.meditrack

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meditrack.ui.MediTrackTestTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device tests for the interaction changes that a JVM test cannot reach: the wheel time picker, the
 * "每月几号" repeat option, and the opt-in usage-monitoring settings.
 *
 * These exist because "it compiles" says nothing about whether a wheel actually renders, snaps, and
 * reports a value - the previous circular picker compiled perfectly too.
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class EditorFeatureTest {

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

    private fun dismissOnboardingIfPresent() {
        val skip = composeRule.onAllNodesWithText("暂时跳过")
        if (skip.fetchSemanticsNodes().isNotEmpty()) {
            skip.onFirst().performClick()
            composeRule.waitForIdle()
        }
    }

    /** Opens the add-medication editor from the medication list. */
    private fun openEditor() {
        composeRule.onNodeWithText("药品").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(MediTrackTestTags.ADD_MEDICATION_FAB).performClick()
        composeRule.waitForIdle()
    }

    /**
     * Tapping the time chip must open the time dialog with its confirm and cancel affordances.
     *
     * A custom wheel picker used to live here and was removed: it reported the wrong row as selected,
     * and the fix made the hour snap back to its initial value while the minute was being set. This
     * test now guards the dialog contract rather than any particular picker implementation.
     */
    @Test
    fun timeChip_opensTheTimeDialog() {
        openEditor()

        // The seeded slot is 08:00; tapping its chip opens the picker.
        composeRule.onAllNodesWithText("08:00").onFirst().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("选择服药时间").assertIsDisplayed()
        composeRule.onNodeWithText("确定").assertIsDisplayed()
        composeRule.onNodeWithText("取消").assertIsDisplayed()
    }

    /** Cancelling must leave the slot untouched. */
    @Test
    fun timeDialogCancel_keepsTheOriginalTime() {
        openEditor()

        composeRule.onAllNodesWithText("08:00").onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("取消").performClick()
        composeRule.waitForIdle()

        // Still 08:00 on the slot chip.
        composeRule.onAllNodesWithText("08:00").onFirst().assertIsDisplayed()
    }

    /**
     * Confirming without touching anything must save the time the dialog opened with.
     *
     * This is the regression that motivated removing the wheel: its two columns were bound through
     * the parent, and moving one could rewrite the other. Whatever implements the picker, a
     * no-op confirm has to be a no-op.
     */
    @Test
    fun timeDialogConfirmWithoutChanges_keepsTheTime() {
        openEditor()

        composeRule.onAllNodesWithText("08:00").onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("确定").performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("08:00").onFirst().assertIsDisplayed()
    }

    /**
     * The "每月几号" repeat option must exist and reveal both the day grid and the calendar shortcut.
     *
     * Scrolling uses the tagged editor list: the editor is a LazyColumn, so the repeat section is
     * not composed until it is scrolled to, and `performScrollTo()` needs an existing node.
     */
    @Test
    fun monthlyDatesOption_revealsDayGridAndCalendarShortcut() {
        openEditor()

        // The repeat-rule chips live in the schedule section, further down the page.
        scrollEditorTo("每月几号")
        composeRule.onAllNodesWithText("每月几号").onFirst().performClick()
        composeRule.waitForIdle()

        scrollEditorTo("选择每月的哪几天服药（可多选）")
        composeRule.onAllNodesWithText("选择每月的哪几天服药（可多选）").onFirst().assertIsDisplayed()

        scrollEditorTo("从日历选择日期")
        composeRule.onAllNodesWithText("从日历选择日期").onFirst().assertIsDisplayed()

        // The clamping behaviour must be explained to the user, not merely implemented.
        scrollEditorTo("例如选了 31 号，2 月没有 31 号时会在 2 月最后一天提醒。")
        composeRule.onAllNodesWithText("例如选了 31 号，2 月没有 31 号时会在 2 月最后一天提醒。")
            .onFirst()
            .assertIsDisplayed()
    }

    /** Scrolls the editor list until [text] is composed and visible. */
    private fun scrollEditorTo(text: String) {
        composeRule.onNodeWithTag(MediTrackTestTags.EDITOR_LIST)
            .performScrollToNode(hasText(text))
        composeRule.waitForIdle()
    }

    /**
     * Every repeat rule the user can pick must be present.
     *
     * A missing branch here would silently produce a schedule that never fires.
     */
    @Test
    fun allRepeatRuleOptions_areOffered() {
        openEditor()

        listOf("每天", "隔天", "每周指定", "每 N 天", "吃 X 天停 Y 天", "每月几号").forEach { label ->
            scrollEditorTo(label)
            composeRule.onAllNodesWithText(label).onFirst().assertIsDisplayed()
        }
    }
}
