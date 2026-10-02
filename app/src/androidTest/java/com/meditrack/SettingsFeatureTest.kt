package com.meditrack

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
 * Verifies the reminder options the user asked to be configurable, and - most importantly - that the
 * unlock catch-up («解锁补提醒») is **on by default**, since it is the mechanism that turns a
 * reminder nobody saw into a second chance.
 *
 * Scrolling is done with [performScrollToNode] against the tagged settings list. `performScrollTo()`
 * cannot be used here: the settings page is a LazyColumn, so an item far down the page is not
 * composed until it is scrolled into view, and a non-existent node has nothing to scroll.
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class SettingsFeatureTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
        composeRule.waitForIdle()
        dismissOnboardingIfPresent()
        composeRule.onNodeWithText("设置").performClick()
        composeRule.waitForIdle()
    }

    private fun dismissOnboardingIfPresent() {
        val skip = composeRule.onAllNodesWithText("稍后再说")
        if (skip.fetchSemanticsNodes().isNotEmpty()) {
            skip.onFirst().performClick()
            composeRule.waitForIdle()
        }
    }

    /** Scrolls the settings list until [text] is composed and visible. */
    private fun scrollTo(text: String) {
        composeRule.onNodeWithTag(MediTrackTestTags.SETTINGS_LIST)
            .performScrollToNode(hasText(text))
        composeRule.waitForIdle()
    }

    /** The three notification behaviours must each be separately switchable. */
    @Test
    fun notificationOptions_areIndividuallyConfigurable() {
        listOf("提醒铃声", "震动", "顶栏横幅弹出").forEach { title ->
            scrollTo(title)
            composeRule.onAllNodesWithText(title).onFirst().assertIsDisplayed()
        }
    }

    /**
     * The unlock catch-up must exist, explain itself, and start **ON**.
     *
     * This is the headline requirement of the release: a reminder that fired while the phone sat in a
     * pocket has to get a second chance the moment the user picks it up. It ships enabled because the
     * alternative - a missed dose nobody was ever told about - is precisely the failure it removes.
     */
    @Test
    fun unlockCatchUp_isOnByDefault() {
        scrollTo("解锁补提醒")
        composeRule.onAllNodesWithText("解锁补提醒").onFirst().assertIsDisplayed()

        scrollTo("解锁时补提醒没吃的药")
        composeRule.onNodeWithText("解锁时补提醒没吃的药").assertIsDisplayed()
        // The switch is a sibling of its label, so it is addressed by tag rather than by text.
        composeRule.onNodeWithTag(MediTrackTestTags.UNLOCK_REMINDER_SWITCH).assertIsOn()
    }

    /** The explanatory copy must state what the feature does and who it stays quiet for. */
    @Test
    fun unlockCatchUp_explainsItself() {
        scrollTo("解锁时补提醒没吃的药")

        composeRule.onNodeWithText("默认开启。已吃掉、已跳过、点了「稍后」且还没到时间的都不会打扰")
            .assertIsDisplayed()
    }

    /** While the feature is on its budget, spacing and full-screen controls are all offered. */
    @Test
    fun unlockCatchUp_revealsItsOptions() {
        scrollTo("每个药提醒多少次")

        scrollTo("每个药提醒多少次")
        composeRule.onAllNodesWithText("每个药提醒多少次").onFirst().assertIsDisplayed()

        scrollTo("两次补提醒之间至少间隔")
        composeRule.onAllNodesWithText("两次补提醒之间至少间隔").onFirst().assertIsDisplayed()

        scrollTo("全屏提醒（像闹钟一样）")
        composeRule.onAllNodesWithText("全屏提醒（像闹钟一样）").onFirst().assertIsDisplayed()
    }

    /**
     * Switching it off hides its sub-options, so the screen does not offer knobs for something that is
     * not running.
     */
    @Test
    fun disablingUnlockCatchUp_hidesItsSubOptions() {
        scrollTo("解锁时补提醒没吃的药")
        composeRule.onNodeWithTag(MediTrackTestTags.UNLOCK_REMINDER_SWITCH).performClick()
        composeRule.waitForIdle()

        scrollTo("解锁时补提醒没吃的药")
        composeRule.onAllNodesWithText("每个药提醒多少次").assertCountEquals(0)
        composeRule.onAllNodesWithText("全屏提醒（像闹钟一样）").assertCountEquals(0)

        // Leave the device as we found it so other tests are unaffected.
        composeRule.onNodeWithTag(MediTrackTestTags.UNLOCK_REMINDER_SWITCH).performClick()
        composeRule.waitForIdle()
    }
}
