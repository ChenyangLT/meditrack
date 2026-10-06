package com.meditrack.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * What verification concluded about a downloaded package.
 *
 * @param ok true only when the package may be handed to the installer
 * @param reason user-facing explanation when [ok] is false, empty when it is true
 * @param versionName the downloaded package's own version, for the confirmation text
 * @param versionCode the downloaded package's own versionCode, checked against the running build so a
 *        "new version" that is actually older cannot be installed over a newer one
 */
data class ApkVerification(
    val ok: Boolean,
    val reason: String,
    val versionName: String? = null,
    val versionCode: Long = 0L,
)

/**
 * Checks a downloaded APK before it is offered to the system installer.
 *
 * ## Why this is not optional
 *
 * The app downloads its own update from the network - and, when GitHub cannot be reached, from a
 * third-party mirror (see [DownloadMirrors]). An APK is code that will be installed with the user's
 * blessing, so "the mirror is probably fine" is not an acceptable assurance. Three independent checks
 * are made, and any one failing aborts the install:
 *
 *  1. **It parses as an APK at all** (`PackageManager.getPackageArchiveInfo` returning null). This
 *     catches a truncated download and a mirror that answered with an HTML error page - both of which
 *     otherwise reach the installer and fail there with an opaque message.
 *  2. **It has the expected package name.** A file that is not this app cannot install over it anyway,
 *     but saying so here produces a sentence instead of `INSTALL_FAILED_INVALID_APK`.
 *  3. **It is signed with the same key as the running app.** This is the check that actually matters:
 *     Android refuses to install an APK signed by a different key over an existing one, so a mirror
 *     that swapped the file cannot succeed *silently* - but without this check the user would see a
 *     platform error with no explanation, and worse, there would be nothing stopping the app from
 *     *offering* a tampered file as if it were legitimate.
 *
 * ## Why the signature is compared rather than validated against a pinned value
 *
 * Comparing against the *running* app's own signing certificate is both simpler and stronger than
 * pinning a hash in the source: it cannot go stale when the signing key changes, it needs no update to
 * the app to support a new key, and it is exactly the condition the platform itself enforces for an
 * in-place upgrade. A pinned hash would also have to be shipped in the same APK it is meant to protect.
 *
 * The certificate bytes are hashed with SHA-256 as a whole rather than compared byte-for-byte, because
 * two encodings of the same certificate can differ in irrelevant whitespace; a hash makes the comparison
 * exact and the log line short enough to be useful.
 */
object ApkVerifier {

    private const val TAG = "ApkVerifier"

    /** The package this app installs as. Compared against the downloaded file's manifest. */
    private const val EXPECTED_PACKAGE = "com.meditrack"

    /**
     * Verifies [file] against the currently installed app.
     *
     * Never throws: a package that cannot be read is reported as a failed verification with a reason,
     * because the caller is a UI flow that has to explain itself either way.
     */
    fun verify(context: Context, file: File): ApkVerification {
        if (!file.exists() || file.length() <= 0L) {
            return ApkVerification(false, "下载的文件是空的，请换一个下载方式重试")
        }

        val packageManager = context.packageManager
        val downloaded = readArchiveInfo(packageManager, file)
            ?: return ApkVerification(false, "下载的文件不是有效的安装包，可能没有下载完整")

        if (downloaded.packageName != EXPECTED_PACKAGE) {
            return ApkVerification(
                false,
                "这个文件不是药准时的安装包（包名是 ${downloaded.packageName}）",
            )
        }

        val installed = readInstalledInfo(packageManager)
            ?: return ApkVerification(false, "无法读取当前安装的版本信息")

        // An "update" that is not newer would be refused by the platform as a downgrade, so it is caught
        // here where a sentence can be produced instead.
        if (downloaded.longVersionCode <= installed.longVersionCode) {
            return ApkVerification(
                ok = false,
                reason = "下载到的版本（${downloaded.versionName}）并不比当前版本新，已取消安装",
                versionName = downloaded.versionName,
                versionCode = downloaded.longVersionCode,
            )
        }

        val expectedSigner = signerDigest(packageManager, installed.packageName)
        val actualSigner = signerDigestOfArchive(packageManager, file)
        if (expectedSigner == null || actualSigner == null || expectedSigner != actualSigner) {
            Log.w(TAG, "signature mismatch: installed=$expectedSigner downloaded=$actualSigner")
            return ApkVerification(
                ok = false,
                reason = "安装包的签名与当前版本不一致，出于安全考虑已取消安装",
                versionName = downloaded.versionName,
                versionCode = downloaded.longVersionCode,
            )
        }

        return ApkVerification(
            ok = true,
            reason = "",
            versionName = downloaded.versionName,
            versionCode = downloaded.longVersionCode,
        )
    }

    /** The installed package's metadata, or null when it cannot be read. */
    private fun readInstalledInfo(packageManager: PackageManager): PackageInfo? = runCatching {
        packageManager.getPackageInfo(EXPECTED_PACKAGE, signatureFlags())
    }.getOrNull()

    /**
     * The flag that makes the platform return the signing certificate.
     *
     * `GET_SIGNING_CERTIFICATES` only exists from API 28; on 26 and 27 passing it is simply ignored and
     * `signingInfo` comes back null, so the legacy `GET_SIGNATURES` has to be requested as well. Both are
     * passed together because the legacy bit is harmless on newer platforms and the two together are what
     * make this work across the whole supported range (minSdk 26).
     */
    @Suppress("DEPRECATION")
    private fun signatureFlags(): Int =
        PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES

    /** Reads the downloaded file's manifest. */
    private fun readArchiveInfo(packageManager: PackageManager, file: File): PackageInfo? = runCatching {
        packageManager.getPackageArchiveInfo(file.absolutePath, signatureFlags())
    }.getOrNull()

    /**
     * The first signing certificate of [info], from whichever API surface carries it.
     *
     * `signingInfo` is the modern answer; `signatures` is the pre-28 one and is the only field populated
     * on API 26/27.
     */
    @Suppress("DEPRECATION")
    private fun signerBytes(info: PackageInfo?): ByteArray? {
        if (info == null) return null
        info.signingInfo?.apkContentsSigners?.firstOrNull()?.let { return it.toByteArray() }
        return info.signatures?.firstOrNull()?.toByteArray()
    }

    /** SHA-256 of the installed app's signing certificate. */
    private fun signerDigest(packageManager: PackageManager, packageName: String): String? =
        runCatching {
            signerBytes(packageManager.getPackageInfo(packageName, signatureFlags()))
        }.getOrNull()?.let(::sha256)

    /**
     * SHA-256 of the downloaded archive's signing certificate.
     *
     * Read through the same archive-info path as the manifest rather than by opening the APK as a zip:
     * the platform is the only thing that knows how to find the signing block, and it is also the thing
     * that will refuse the install, so asking it keeps the two answers consistent.
     */
    private fun signerDigestOfArchive(packageManager: PackageManager, file: File): String? {
        val bytes = signerBytes(readArchiveInfo(packageManager, file)) ?: return null
        return sha256(bytes)
    }

    /** Lowercase hex SHA-256, or null for an empty input. */
    private fun sha256(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
