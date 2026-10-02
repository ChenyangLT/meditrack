package com.meditrack

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
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
 * phone-usage monitoring is **off by default** and genuinely inert until switched on.
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
     * The usage-monitoring master switch must exist, be described as opt-in, and start OFF.
     *
     * This is the headline requirement: the feature must not be imposed on anyone.
     */
    @Test
    fun usageMonitoring_isOffByDefault() {
        scrollTo("手机未使用时暂缓提醒")
        composeRule.onAllNodesWithText("手机未使用时暂缓提醒").onFirst().assertIsDisplayed()

        scrollTo("启用该功能")
        composeRule.onNodeWithText("启用该功能").assertIsDisplayed()
        // The switch is a sibling of its label, so it is addressed by tag rather than by text.
        composeRule.onNodeWithTag(MediTrackTestTags.IDLE_DEFERRAL_SWITCH).assertIsOff()
    }

    /** The explanatory copy must state the behaviour and the default. */
    @Test
    fun usageMonitoring_explainsItselfAndTheDefault() {
        scrollTo("启用该功能")

        composeRule.onNodeWithText("默认关闭。关闭时应用不会读取任何手机使用状态").assertIsDisplayed()
    }

    /**
     * While the feature is off its sub-options stay hidden, so the screen does not offer knobs for
     * something that is not running.
     */
    @Test
    fun usageMonitoring_subOptionsHiddenWhileOff() {
        scrollTo("启用该功能")

        composeRule.onAllNodesWithText("多久算「没人用」").assertCountEquals(0)
        composeRule.onAllNodesWithText("息屏时也暂缓").assertCountEquals(0)
    }

    /** Turning it on must reveal the threshold and screen-off controls. */
    @Test
    fun enablingUsageMonitoring_revealsItsOptions() {
        scrollTo("启用该功能")
        composeRule.onNodeWithTag(MediTrackTestTags.IDLE_DEFERRAL_SWITCH).performClick()
        composeRule.waitForIdle()

        scrollTo("多久算「没人用」")
        composeRule.onAllNodesWithText("多久算「没人用」").onFirst().assertIsDisplayed()

        scrollTo("息屏时也暂缓")
        composeRule.onAllNodesWithText("息屏时也暂缓").onFirst().assertIsDisplayed()

        // Leave the device as we found it so other tests are unaffected.
        scrollTo("启用该功能")
        composeRule.onNodeWithTag(MediTrackTestTags.IDLE_DEFERRAL_SWITCH).performClick()
        composeRule.waitForIdle()
    }
}
