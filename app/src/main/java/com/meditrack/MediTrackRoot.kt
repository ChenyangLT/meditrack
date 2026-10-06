package com.meditrack

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.meditrack.BuildConfig
import com.meditrack.core.theme.MediTrackTheme
import com.meditrack.data.prefs.UserPreferences
import com.meditrack.ui.MediTrackTestTags
import com.meditrack.ui.history.HistoryScreen
import com.meditrack.ui.knowledge.KnowledgeScreen
import com.meditrack.ui.medications.MedicationEditorScreen
import com.meditrack.ui.medications.MedicationListScreen
import com.meditrack.ui.settings.AboutScreen
import com.meditrack.ui.settings.AgreementGate
import com.meditrack.ui.settings.OnboardingScreen
import com.meditrack.ui.settings.SettingsScreen
import com.meditrack.ui.settings.SettingsViewModel
import com.meditrack.ui.today.TodayScreen
import com.meditrack.ui.update.UpdateDialog
import com.meditrack.ui.update.UpdateViewModel

/**
 * Navigation routes.
 *
 * String routes with explicit arguments rather than a serialisable route object: the arguments are
 * read back through `backStackEntry.arguments`, which keeps every ViewModel constructor free of
 * `SavedStateHandle` and therefore trivially unit testable.
 */
object Routes {
    const val TODAY = "today"
    const val MEDICATIONS = "medications"
    const val HISTORY = "history"
    const val KNOWLEDGE = "knowledge"
    const val SETTINGS = "settings"

    const val MEDICATION_EDITOR = "medication_editor"

    /**
     * SavedStateHandle key the editor reads its id from.
     *
     * It must be the *same* string used as the placeholder name in [MEDICATION_EDITOR_ROUTE],
     * because Navigation extracts the value of `arg` in the query string into the argument
     * registered under this name.
     */
    const val ARG_MEDICATION_ID = "arg"

    /**
     * Declared pattern for the editor destination.
     *
     * The query parameter is **optional** thanks to the default value on the `navArgument`, which
     * lets "add" be expressed as id 0.
     *
     * IMPORTANT: the placeholder name here must match what [medicationEditor] puts in the query
     * string. An earlier revision declared `arg={medicationId}` while navigating to `arg=0`; the
     * literal never matched the `{medicationId}` placeholder, so every tap on "添加药品" threw
     * `IllegalArgumentException: Navigation destination ... cannot be found` and killed the app.
     * Keep the two in sync.
     */
    const val MEDICATION_EDITOR_ROUTE = "$MEDICATION_EDITOR?$ARG_MEDICATION_ID={$ARG_MEDICATION_ID}"

    /** Builds a concrete navigation target; 0 means "create a new medication". */
    fun medicationEditor(medicationId: Long = 0L): String =
        "$MEDICATION_EDITOR?$ARG_MEDICATION_ID=${medicationId.coerceAtLeast(0L)}"

    const val ONBOARDING = "onboarding"
    const val ABOUT = "about"
}

/** A bottom-bar destination. */
private data class TopLevelDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

/**
 * Root of the app.
 *
 * Responsibilities:
 *  - read the preference snapshot once and hand it to [MediTrackTheme], so every screen inherits
 *    the theme, the font scale and the semantic dose colours;
 *  - own the bottom navigation (5 tabs, per the product brief);
 *  - decide whether the permission walkthrough is shown on first launch.
 *
 * The five tabs match the mental model the brief describes: what do I take today, what am I taking,
 * what have I taken, what is worth knowing about taking it, and how is the app configured.
 */
@Composable
fun MediTrackRoot(
    focusDoseId: Long = -1L,
    focusEpochDay: Long = Long.MIN_VALUE,
    focusMedicationId: Long = -1L,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    updateViewModel: UpdateViewModel = hiltViewModel(),
) {
    val preferences by settingsViewModel.preferences.collectAsStateWithLifecycle()
    val availableUpdate by updateViewModel.available.collectAsStateWithLifecycle()

    // Launched at the root rather than in a screen: the check is the app's business, not a tab's, and
    // the policy inside UpdateRepository decides whether it actually goes out.
    LaunchedEffect(Unit) {
        updateViewModel.checkOnStart(BuildConfig.VERSION_NAME)
    }

    MediTrackTheme(preferences = preferences) {
        MediTrackNavHost(
            preferences = preferences,
            focusDoseId = focusDoseId,
            focusEpochDay = focusEpochDay,
            focusMedicationId = focusMedicationId,
            // The ViewModel is resolved here, where it is in scope, and handed down so the
            // onboarding flow can persist "seen" without a second injection point.
            onOnboardingFinished = {
                settingsViewModel.setOnboardingCompleted(true)
            },
        )

        availableUpdate?.let { info ->
            // The dialog owns the whole download/verify/install flow now, so it is handed the ViewModel
            // rather than a dismiss callback: the flow outlives a single recomposition and has to show
            // progress, a choice of download host, and failures - none of which fit in a callback.
            UpdateDialog(info = info, viewModel = updateViewModel)
        }

        // The first-run agreement gate. Rendered *outside* the NavHost on purpose: it is not a
        // destination the user can navigate away from, it is a condition on using the app at all.
        // See AgreementGate for why it is a full-screen overlay rather than a dialog.
        if (preferences.needsAgreement) {
            AgreementGate(onAccepted = { settingsViewModel.acceptAgreement() })
        }
    }
}

@Composable
private fun MediTrackNavHost(
    preferences: UserPreferences,
    focusDoseId: Long,
    focusEpochDay: Long,
    focusMedicationId: Long,
    onOnboardingFinished: () -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    // A 复查 reminder opens the medication it is about. Done once, keyed on the id, so re-composition
    // cannot navigate twice - and so the back stack behaves: opening the editor from a notification and
    // pressing back lands on the medication list, not on a duplicate editor.
    LaunchedEffect(focusMedicationId) {
        if (focusMedicationId > 0L && preferences.agreementAcceptedVersion > 0) {
            navController.navigate(Routes.medicationEditor(focusMedicationId))
        }
    }

    val destinations = remember {
        listOf(
            TopLevelDestination(
                route = Routes.TODAY,
                label = "今日",
                selectedIcon = Icons.Filled.Today,
                unselectedIcon = Icons.Outlined.Today,
            ),
            TopLevelDestination(
                route = Routes.MEDICATIONS,
                label = "药品",
                selectedIcon = Icons.Filled.Medication,
                unselectedIcon = Icons.Outlined.Medication,
            ),
            TopLevelDestination(
                route = Routes.HISTORY,
                label = "历史",
                selectedIcon = Icons.Filled.CalendarMonth,
                unselectedIcon = Icons.Outlined.CalendarMonth,
            ),
            TopLevelDestination(
                route = Routes.KNOWLEDGE,
                label = "知识",
                selectedIcon = Icons.Filled.MenuBook,
                unselectedIcon = Icons.Outlined.MenuBook,
            ),
            TopLevelDestination(
                route = Routes.SETTINGS,
                label = "设置",
                selectedIcon = Icons.Filled.Settings,
                unselectedIcon = Icons.Outlined.Settings,
            ),
        )
    }

    // Hide the bottom bar on the full-screen flows so the editor gets the whole canvas.
    val showBottomBar = currentDestination?.route in destinations.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    destinations.forEach { destination ->
                        val selected = currentDestination?.hierarchy
                            ?.any { it.route == destination.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    // Keep a single copy of each tab and restore its scroll position,
                                    // which is what users expect from a bottom bar.
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        // A badge on 今日 when doses are still outstanding is the
                                        // cheapest possible nudge that something needs attention.
                                        if (destination.route == Routes.TODAY) {
                                            val pending = rememberPendingDoseCount()
                                            if (pending > 0) {
                                                Badge { Text(pending.toString()) }
                                            }
                                        }
                                    },
                                ) {
                                    Icon(
                                        imageVector = if (selected) destination.selectedIcon
                                        else destination.unselectedIcon,
                                        contentDescription = destination.label,
                                        // The tag rides on the icon, which is the element every tab
                                        // already renders; only the new tab needs one, for tests that
                                        // navigate the bar by tag rather than by localised label.
                                        modifier = if (destination.route == Routes.KNOWLEDGE) {
                                            Modifier.testTag(MediTrackTestTags.TAB_KNOWLEDGE)
                                        } else {
                                            Modifier
                                        },
                                    )
                                }
                            },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = if (preferences.onboardingCompleted) Routes.TODAY else Routes.ONBOARDING,
            modifier = Modifier.padding(
                bottom = if (showBottomBar) padding.calculateBottomPadding() else 0.dp
            ),
            enterTransition = {
                fadeIn(tween(180)) + slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Start,
                    tween(220),
                )
            },
            exitTransition = { fadeOut(tween(140)) },
            popEnterTransition = {
                fadeIn(tween(180)) + slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.End,
                    tween(220),
                )
            },
            popExitTransition = { fadeOut(tween(140)) },
        ) {
            composable(Routes.TODAY) {
                TodayScreen(
                    onAddMedication = { navController.navigate(Routes.medicationEditor()) },
                    onOpenMedicationId = { id -> navController.navigate(Routes.medicationEditor(id)) },
                    // A notification tap or a widget tap hands us the dose to reveal.
                    initialFocusDoseId = focusDoseId,
                    initialFocusEpochDay = focusEpochDay,
                )
            }

            composable(Routes.MEDICATIONS) {
                MedicationListScreen(
                    onAddMedication = { navController.navigate(Routes.medicationEditor()) },
                    onOpenMedication = { id -> navController.navigate(Routes.medicationEditor(id)) },
                )
            }

            composable(Routes.HISTORY) {
                HistoryScreen(
                    onOpenMedication = { id -> navController.navigate(Routes.medicationEditor(id)) },
                )
            }

            composable(Routes.KNOWLEDGE) {
                KnowledgeScreen()
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenAbout = { navController.navigate(Routes.ABOUT) },
                    onOpenOnboarding = { navController.navigate(Routes.ONBOARDING) },
                )
            }

            composable(
                route = Routes.MEDICATION_EDITOR_ROUTE,
                arguments = listOf(
                    navArgument("arg") {
                        type = NavType.LongType
                        defaultValue = 0L
                    },
                ),
            ) { entry ->
                val medicationId = entry.arguments?.getLong("arg") ?: 0L
                MedicationEditorScreen(
                    medicationId = medicationId,
                    onDone = { navController.popBackStack() },
                )
            }

            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    onFinished = {
                        onOnboardingFinished()
                        navController.navigate(Routes.TODAY) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                        }
                    },
                )
            }

            composable(Routes.ABOUT) {
                AboutScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

/**
 * The number of doses still open today, for the bottom-bar badge.
 *
 * A tiny dedicated collector rather than a shared ViewModel: the badge needs one integer, and
 * hoisting the whole today state to the navigation level would re-compose the entire scaffold on
 * every stepper tap.
 */
@Composable
private fun rememberPendingDoseCount(): Int {
    val viewModel: com.meditrack.ui.today.TodayViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    return state.summary?.remainingDoses ?: 0
}
