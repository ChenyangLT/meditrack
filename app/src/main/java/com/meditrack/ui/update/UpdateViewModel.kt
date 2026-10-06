package com.meditrack.ui.update

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meditrack.data.update.ApkInstaller
import com.meditrack.data.update.ApkVerifier
import com.meditrack.data.update.DownloadMirror
import com.meditrack.data.update.DownloadMirrors
import com.meditrack.data.update.DownloadState
import com.meditrack.data.update.UpdateDownloader
import com.meditrack.data.update.UpdateInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Where the update flow currently is.
 *
 * One sealed state rather than a handful of booleans (`isDownloading`, `progress`, `error`, …) because the
 * flow is strictly sequential and the illegal combinations are exactly the bugs: "downloading" with a
 * stale error showing, or "installing" with no file.
 */
sealed interface UpdateFlowState {
    /** Nothing started; the dialog shows the release notes and the download button. */
    data object Idle : UpdateFlowState

    data class Downloading(val percent: Int, val bytesRead: Long, val totalBytes: Long) : UpdateFlowState

    /** Downloaded and verified; the installer is being launched. */
    data class Verifying(val fileName: String) : UpdateFlowState

    /** The file is ready and valid but the install permission is missing, so the user must grant it. */
    data object NeedsInstallPermission : UpdateFlowState

    /** Terminal failure with a message already fit to show. */
    data class Failed(val reason: String) : UpdateFlowState
}

/**
 * Drives the whole "there is a new version" flow: check, choose a download source, download, verify, install.
 *
 * ## Why this ViewModel owns the download
 *
 * The dialog is the only place the flow is visible, and the download has to survive the dialog being
 * recomposed (a rotation, a theme change, the app being backgrounded mid-download). Holding it in a
 * ViewModel means it is cancelled exactly when the screen really goes away, and progress is observable
 * rather than being a callback into a composable that may no longer exist.
 *
 * ## Why a mirror can be re-chosen after a failure
 *
 * Because the failure this feature exists for is "GitHub is unreachable on my network", and the answer to
 * that is a different host - not a retry of the same one. A failed download therefore returns to a state
 * where the dropdown is still live and the reason is on screen, so the next attempt is one tap.
 */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val updateRepository: com.meditrack.data.update.UpdateRepository,
    private val downloader: UpdateDownloader,
) : ViewModel() {

    /** Non-null while the "a new version is out" dialog is up. */
    private val _available = MutableStateFlow<UpdateInfo?>(null)
    val available: StateFlow<UpdateInfo?> = _available.asStateFlow()

    private val _state = MutableStateFlow<UpdateFlowState>(UpdateFlowState.Idle)
    val state: StateFlow<UpdateFlowState> = _state.asStateFlow()

    private val _mirrors = MutableStateFlow<List<DownloadMirror>>(emptyList())
    val mirrors: StateFlow<List<DownloadMirror>> = _mirrors.asStateFlow()

    private val _selectedMirrorId = MutableStateFlow<String?>(null)
    val selectedMirrorId: StateFlow<String?> = _selectedMirrorId.asStateFlow()

    private var downloadJob: Job? = null

    /**
     * Called once per launch from the app root.
     *
     * Silence is the expected outcome: the repository decides whether the twelve-hour interval has
     * elapsed, whether the switch is on, and whether this version was already waved away.
     */
    fun checkOnStart(currentVersion: String) {
        viewModelScope.launch {
            when (val result = updateRepository.check(currentVersion, force = false)) {
                is com.meditrack.data.update.UpdateCheckResult.Available -> present(result.info)
                else -> Unit
            }
        }
    }

    /** Shows the dialog for [info] and prepares its download sources. */
    fun present(info: UpdateInfo) {
        _available.value = info
        _state.value = UpdateFlowState.Idle
        // The manifest may advertise its own mirrors, in which case they win: they are the list the
        // maintainer can fix without shipping an update, which is the only list that can react to a mirror
        // going down. Otherwise they are derived from the release asset url.
        val resolved = info.advertisedMirrors.ifEmpty {
            DownloadMirrors.forRelease(info.apkUrl, info.tagName)
        }
        _mirrors.value = resolved
        _selectedMirrorId.value = DownloadMirrors.default(resolved)?.id
    }

    /** The user picked a different download source. */
    fun selectMirror(id: String) {
        _selectedMirrorId.value = id
    }

    /**
     * Starts downloading from the selected source.
     *
     * A no-op when the release carries no download url at all, which is reported as a failure so the
     * dialog can always say *something* rather than leaving a dead button.
     */
    fun startDownload() {
        val info = _available.value ?: return
        if (downloadJob?.isActive == true) return

        val mirror = DownloadMirrors.byId(_mirrors.value, _selectedMirrorId.value)
        if (mirror == null) {
            _state.value = UpdateFlowState.Failed("这个版本没有可用的下载地址，请到发布页手动下载")
            return
        }

        _state.value = UpdateFlowState.Downloading(percent = 0, bytesRead = 0L, totalBytes = 0L)
        downloadJob = viewModelScope.launch {
            val fileName = "meditrack-${info.version}.apk"
            val result = downloader.download(mirror.url, fileName) { progress ->
                _state.value = UpdateFlowState.Downloading(
                    percent = progress.percent,
                    bytesRead = progress.bytesRead,
                    totalBytes = progress.totalBytes,
                )
            }

            when (result) {
                is DownloadState.Done -> verifyAndInstall(result.file)
                is DownloadState.Failed -> _state.value = UpdateFlowState.Failed(result.reason)
                is DownloadState.Progress -> Unit
            }
        }
    }

    /** Abandons the current download; the partial file is deleted by the downloader. */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _state.value = UpdateFlowState.Idle
    }

    /**
     * Verifies the downloaded package and hands it to the installer.
     *
     * Verification is not optional even from the official GitHub url: the point is that this app is about
     * to ask the user to install code, and the one thing it can do to deserve that trust is refuse a file
     * that is not signed by the same key as the app already running. See [ApkVerifier].
     */
    private suspend fun verifyAndInstall(file: java.io.File) {
        _state.value = UpdateFlowState.Verifying(file.name)
        val verification = ApkVerifier.verify(context, file)
        if (!verification.ok) {
            _state.value = UpdateFlowState.Failed(verification.reason)
            return
        }

        val failure = ApkInstaller.install(context, file)
        _state.value = when (failure) {
            null -> UpdateFlowState.Idle
            ApkInstaller.NEEDS_PERMISSION -> UpdateFlowState.NeedsInstallPermission
            else -> UpdateFlowState.Failed(failure)
        }
    }

    /** Opens the system page that grants this app permission to prompt the installer. */
    fun openInstallPermissionSettings() {
        ApkInstaller.openPermissionSettings(context)
    }

    /**
     * "以后再说" and tapping outside both land here: this version stops being offered.
     *
     * The download is cancelled first, so waving the dialog away mid-download does not leave a socket open
     * and a partial file growing in the cache.
     */
    fun dismiss(info: UpdateInfo) {
        cancelDownload()
        viewModelScope.launch { updateRepository.dismiss(info) }
        _available.value = null
    }

    /** Hides the dialog without recording a dismissal; used after a successful install hand-off. */
    fun close() {
        cancelDownload()
        _available.value = null
    }
}
