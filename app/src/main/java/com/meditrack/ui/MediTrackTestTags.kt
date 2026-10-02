package com.meditrack.ui

/**
 * Stable identifiers for UI tests.
 *
 * Tests target these tags rather than display text: the text is localised, appears in more than one
 * place by design (both empty states offer "添加药品"), and Text inside a Button is merged into the
 * button's semantics node, which makes `onNodeWithText` fail even though the label is visible.
 */
object MediTrackTestTags {
    /** The Scaffold's primary "add medication" FloatingActionButton. */
    const val ADD_MEDICATION_FAB = "add_medication_fab"

    /**
     * The scrollable content container of the settings screen.
     *
     * Tests must scroll the *container* rather than call `performScrollTo()` on an item: inside a
     * LazyColumn the target may not be composed at all yet, and `performScrollTo` only works when
     * the node already exists within a scrollable ancestor.
     */
    const val SETTINGS_LIST = "settings_list"

    /** The scrollable content container of the medication editor. */
    const val EDITOR_LIST = "editor_list"

    /**
     * The "启用该功能" switch for the opt-in usage monitoring.
     *
     * Tagged because the switch is a *sibling* of its label Text inside the row, not a descendant -
     * so `hasText(...) and isToggleable()` matches nothing, and there is no reliable way to reach
     * the control from the label alone.
     */
    const val IDLE_DEFERRAL_SWITCH = "idle_deferral_switch"

    /** The bottom-bar tab labels, for navigation in tests. */
    const val TAB_TODAY = "tab_today"
    const val TAB_MEDICATIONS = "tab_medications"
    const val TAB_HISTORY = "tab_history"
    const val TAB_SETTINGS = "tab_settings"
}
