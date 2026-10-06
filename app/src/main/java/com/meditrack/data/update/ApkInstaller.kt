package com.meditrack.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a downloaded package to the system installer.
 *
 * ## Why the app cannot install its own update
 *
 * Since Android 8 every app that installs another APK must be granted `REQUEST_INSTALL_PACKAGES` by the
 * user, and the install itself is always performed by the platform's own installer activity - there is
 * no API that lets an app replace itself silently. So the flow is necessarily two steps: ask for the
 * permission if it has not been granted, then launch the installer with a uri it can read.
 *
 * ## Why the uri is a FileProvider uri rather than a file path
 *
 * The installer runs in a different process and cannot read this app's private cache directory. Passing
 * a `file://` uri throws `FileUriExposedException` on Android 7+, so the file has to be exposed through
 * the provider declared in the manifest with an explicit read grant. The manifest entry for
 * `cache/updates/` in `file_paths.xml` is what makes that possible; without it the grant silently fails
 * and the installer reports "解析软件包时出现问题".
 *
 * ## Why the permission check happens here and not at the call site
 *
 * Because there are two ways to react to "no permission" - the system settings page and the per-app
 * "install unknown apps" page, which differ by Android version - and both callers (the update dialog and
 * the settings screen) would otherwise have to know about that.
 */
object ApkInstaller {

    private const val TAG = "ApkInstaller"

    /** True when this app is already allowed to prompt for an install. */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /**
     * Starts the install flow for [file].
     *
     * @return null on success, or a user-facing reason when the flow could not even be started. The
     *         caller shows the reason; this never throws, because being unable to install is a normal
     *         outcome rather than a bug.
     */
    fun install(context: Context, file: File): String? {
        if (!file.exists()) return "安装包已不在，请重新下载"

        if (!canInstall(context)) {
            // Not an error the user can fix inside this app, so it is reported as a reason and the caller
            // offers the settings page instead.
            return NEEDS_PERMISSION
        }

        return runCatching {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME_TYPE)
                // The flags are the whole point: without the read grant the installer receives a uri it
                // is not allowed to open, and without NEW_TASK it cannot be started from a non-Activity
                // context.
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            null
        }.onFailure { Log.w(TAG, "could not start the installer", it) }
            .exceptionOrNull()
            ?.let { "无法打开安装程序：${it.message ?: "未知原因"}" }
    }

    /**
     * Opens the page where the user can allow installs from this app.
     *
     * Only reachable on API 26+, which is also the only place the permission exists; the caller guards on
     * [NEEDS_PERMISSION] so this is never called on an older platform.
     */
    fun openPermissionSettings(context: Context) {
        runCatching {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${context.packageName}"))
            } else {
                Intent(Settings.ACTION_SECURITY_SETTINGS)
            }
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "could not open the unknown-sources page", it) }
    }

    /** Sent back as the reason when the install permission is missing, so callers can branch on it. */
    const val NEEDS_PERMISSION = "__needs_install_permission__"

    /**
     * The APK mime type.
     *
     * The long official form rather than `application/vnd.android.package-archive`, because a few OEM
     * installers match on the former and ignore the latter, and a mismatched type makes the installer
     * open as a text file.
     */
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
}
