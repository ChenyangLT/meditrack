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
     * The "解锁时补提醒没吃的药" switch.
     *
     * Tagged because the switch is a *sibling* of its label Text inside the row, not a descendant -
     * so `hasText(...) and isToggleable()` matches nothing, and there is no reliable way to reach
     * the control from the label alone.
     */
    const val UNLOCK_REMINDER_SWITCH = "unlock_reminder_switch"

    /**
     * The "开启免打扰时段" switch.
     *
     * Tagged for the same reason as the switches above: the control is a sibling of its label, so it
     * cannot be reached from the label's text alone.
     */
    const val QUIET_HOURS_SWITCH = "quiet_hours_switch"

    /** The "选择文件夹" button that points backups at a user-visible folder. */
    const val BACKUP_FOLDER_BUTTON = "backup_folder_button"

    /** The bottom-bar tab labels, for navigation in tests. */
    const val TAB_TODAY = "tab_today"
    const val TAB_MEDICATIONS = "tab_medications"
    const val TAB_HISTORY = "tab_history"
    const val TAB_SETTINGS = "tab_settings"

    /** The 知识 (offline knowledge base) tab, added in 2.0.0. */
    const val TAB_KNOWLEDGE = "tab_knowledge"

    /** The two scrollable documents of the first-run gate, and its accept button. */
    const val AGREEMENT_TERMS = "agreement_terms"
    const val AGREEMENT_GUIDE = "agreement_guide"
    const val AGREEMENT_ACCEPT = "agreement_accept"

    /** The cached-sound rows of 设置 → 清除缓存. */
    const val CLEAR_CACHE_BUTTON = "clear_cache_button"

    /** The «复查提醒» section of the medication editor. */
    const val REVIEW_SECTION = "review_section"
}
