package com.meditrack.data.update

import com.meditrack.data.prefs.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/** What a check concluded, so the UI can say the right thing in each case. */
sealed interface UpdateCheckResult {
    /** Running the newest release. */
    data object UpToDate : UpdateCheckResult

    /** Offline, rate-limited, GitHub down, no releases yet - all the same to the user. */
    data object Failed : UpdateCheckResult

    /** A newer release exists and has not been waved away. */
    data class Available(val info: UpdateInfo) : UpdateCheckResult

    /** A newer release exists, but this version was already dismissed; only a manual check shows it. */
    data class Dismissed(val info: UpdateInfo) : UpdateCheckResult
}

/**
 * The update policy: when to check, and whether to interrupt.
 *
 * Deliberately generous about *not* interrupting. An update prompt is the app's own business, not the
 * user's, and a medication reminder app that nags about versions is worse than one that is a release
 * behind: a dismissed version stays dismissed until a newer one appears.
 */
@Singleton
class UpdateRepository @Inject constructor(
    private val settings: SettingsRepository,
    private val checker: UpdateChecker,
) {

    /**
     * Checks GitHub when policy allows.
     *
     * @param force a user-initiated check: ignores the interval *and* the dismissed version, because
     *   asking explicitly means "show me".
     */
    suspend fun check(currentVersion: String, force: Boolean, nowMillis: Long = System.currentTimeMillis()): UpdateCheckResult {
        val prefs = settings.current()
        val allowed = UpdatePolicy.shouldCheck(
            autoCheckEnabled = prefs.autoUpdateCheck,
            force = force,
            lastCheckAtMillis = prefs.lastUpdateCheckAtMillis,
            nowMillis = nowMillis,
        )
        if (!allowed) return UpdateCheckResult.Failed

        val info = checker.latest(currentVersion)
        settings.setLastUpdateCheckAt(nowMillis)
        if (info == null) return UpdateCheckResult.Failed
        if (!UpdateVersion.isNewer(info.version, currentVersion)) return UpdateCheckResult.UpToDate

        return if (UpdatePolicy.shouldInterrupt(force, prefs.dismissedUpdateVersion, info.tagName)) {
            UpdateCheckResult.Available(info)
        } else {
            UpdateCheckResult.Dismissed(info)
        }
    }

    /** "以后再说": this exact version stops being offered until a newer one shows up. */
    suspend fun dismiss(info: UpdateInfo) = settings.setDismissedUpdateVersion(info.tagName)

}
