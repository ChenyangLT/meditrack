package com.meditrack.data.update

/**
 * One place an update package can be fetched from.
 *
 * ## Why mirrors are a first-class concept rather than a fallback list
 *
 * The requirement is that a user who cannot reach GitHub at all must still be able to update. That is not
 * a rare failure on the networks this app is used on: `github.com` and `objects.githubusercontent.com`
 * are commonly throttled, reset or simply unresolved, which is exactly why the *check* already tries
 * three endpoints. Downloading is worse, because the package is megabytes rather than a kilobyte, so a
 * host that answers a small JSON GET can still fail a download halfway.
 *
 * Listing the mirrors and letting the user pick is deliberately preferred over silently trying them in
 * order: a download that silently re-routes can appear to hang for a minute, and the user has no way to
 * tell "slow" from "stuck". Showing the choice makes the fallback visible and the retry one tap.
 *
 * @param id stable identifier, so a choice survives a process restart
 * @param label what the user sees; short, because it is a dropdown entry
 * @param url the direct download URL
 * @param isDefaultMirror true for the source the app would pick unaided, which is listed first
 */
data class DownloadMirror(
    val id: String,
    val label: String,
    val url: String,
    val isDefaultMirror: Boolean = false,
)

/**
 * Builds the mirror list for one release.
 *
 * ## Where the candidate hosts come from
 *
 * GitHub serves a release asset from two different hosts in practice: the `github.com/.../releases/download`
 * URL that redirects, and the signed `objects.githubusercontent.com` URL it redirects *to*. Neither is
 * specified in the manifest, so both are derived from the release's own asset URL rather than hard-coded
 * per release - a hard-coded list would silently rot the first time the repository was renamed.
 *
 * On top of those, [MANIFEST_MIRRORS] lists third-party GitHub proxies. They are offered because they are
 * the only thing that works when GitHub itself is unreachable, and they are **not** the default: a proxy
 * is a third party that can see and modify what it relays, and an APK is code. The app therefore verifies
 * the downloaded package is a well-formed, correctly-signed-by-us APK with the expected package name
 * before handing it to the installer, and says so in the dialog - see `ApkVerifier`.
 */
object DownloadMirrors {

    /** Prefix of the jsDelivr mirror, which is the one already used for the version manifest. */
    private const val JSDELIVR_PREFIX = "https://cdn.jsdelivr.net/gh/ChenyangLT/meditrack@"

    /**
     * Third-party proxies, in the order they are offered.
     *
     * Each is a `prefix + full GitHub URL` proxy, which is the common shape. They are listed after the
     * GitHub hosts so that the dependable option stays the default.
     */
    private val PROXIES = listOf(
        "ghfast" to "https://ghfast.top/",
        "ghproxy" to "https://gh-proxy.com/",
        "ghproxyNet" to "https://ghproxy.net/",
        "moeyy" to "https://github.moeyy.xyz/",
    )

    /**
     * Mirrors for the version *manifest*, tried in order during a check.
     *
     * Kept here beside the download mirrors so the two lists cannot drift: a user whose network cannot
     * reach the GitHub release page usually cannot reach `api.github.com` either, and a check that
     * succeeds while the download is impossible would be a worse experience than a check that fails.
     */
    val MANIFEST_MIRRORS = listOf(
        UpdateChecker.MANIFEST_URL,
        UpdateChecker.JSDELIVR_URL,
        UpdateChecker.API_URL,
    )

    /** The mirror id the app defaults to for a given release. */
    const val GITHUB_DIRECT = "github"

    /**
     * Every way to fetch [apkUrl], best first.
     *
     * @param tagName the release tag, used to build the jsDelivr path; when null, that mirror is omitted
     * @return at least one entry whenever [apkUrl] is non-null and looks like a GitHub release asset;
     *         an empty list when there is nothing to download from, which the UI reports as "没有可用的
     *         下载地址" rather than offering an empty dropdown
     */
    fun forRelease(apkUrl: String?, tagName: String?): List<DownloadMirror> {
        if (apkUrl.isNullOrBlank()) return emptyList()

        val mirrors = mutableListOf<DownloadMirror>()

        // 1. GitHub itself. The default, because it is the only source whose contents are guaranteed to be
        //    exactly what was published.
        mirrors += DownloadMirror(
            id = GITHUB_DIRECT,
            label = "GitHub（官方，最可信）",
            url = apkUrl,
            isDefaultMirror = true,
        )

        // 2. jsDelivr, which is a real CDN rather than a proxy: it caches the file it was given and cannot
        //    alter it, and it is already a trusted source in this app (the version manifest is read from
        //    it). Only usable for a path that exists on a branch, hence the tag requirement.
        val fileName = apkUrl.substringAfterLast('/').takeIf { it.endsWith(".apk", ignoreCase = true) }
        if (fileName != null) {
            mirrors += DownloadMirror(
                id = "jsdelivr",
                label = "jsDelivr 镜像（CDN，国内较快）",
                // jsDelivr cannot serve release *assets*, only repository files. The project therefore also
                // keeps each published APK under `releases/apk/` in the repo, which is what this path
                // resolves; when it is absent the download simply fails over to the next mirror.
                url = "$JSDELIVR_PREFIX${tagName ?: "main"}/releases/apk/$fileName",
            )
        }

        // 3. Third-party proxies, last because they are the least trustworthy and the most likely to be
        //    rate-limited or dead. The user can still choose one when nothing else works.
        for ((id, prefix) in PROXIES) {
            mirrors += DownloadMirror(id = id, label = "$id 加速", url = prefix + apkUrl)
        }

        return mirrors
    }

    /** The mirror the app would pick unaided, or the first one when none is flagged. */
    fun default(mirrors: List<DownloadMirror>): DownloadMirror? =
        mirrors.firstOrNull { it.isDefaultMirror } ?: mirrors.firstOrNull()

    /** The mirror with [id], falling back to the default so a stale stored choice cannot break a download. */
    fun byId(mirrors: List<DownloadMirror>, id: String?): DownloadMirror? =
        mirrors.firstOrNull { it.id == id } ?: default(mirrors)
}
