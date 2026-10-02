package com.meditrack.data.update

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A release the app could update to. */
data class UpdateInfo(
    /** "1.8.0" - the tag without its "v", which is what a human reads and what gets compared. */
    val version: String,
    val tagName: String,
    val releaseUrl: String,
    /** Direct download of the APK asset, when the release carries one. */
    val apkUrl: String?,
    val body: String,
)

/**
 * Version maths, kept pure.
 *
 * String comparison is the classic way to get this wrong: "1.10.0" < "1.9.0" as text but the opposite
 * as versions. Both sides are split into numbers and compared field by field.
 */
object UpdateVersion {

    /** "v1.8.0-beta.2" -> [1, 8, 0]. Unparseable pieces become 0 rather than throwing. */
    fun parse(raw: String): List<Int> = raw.trim()
        .removePrefix("v")
        .removePrefix("V")
        .substringBefore('-')
        .substringBefore('+')
        .split('.')
        .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    fun compare(a: List<Int>, b: List<Int>): Int {
        val size = maxOf(a.size, b.size)
        for (index in 0 until size) {
            val left = a.getOrElse(index) { 0 }
            val right = b.getOrElse(index) { 0 }
            if (left != right) return left.compareTo(right)
        }
        return 0
    }

    /** True when [latest] is a version the running [current] build should offer an upgrade to. */
    fun isNewer(latest: String, current: String): Boolean = compare(parse(latest), parse(current)) > 0

    /** The tag as a person should see it: "v1.8.0" -> "1.8.0". */
    fun display(raw: String): String = raw.trim().removePrefix("v").removePrefix("V")
}

/** The GitHub release payload, in the shape of the fields this app reads. */
internal data class GitHubRelease(
    @SerializedName("tag_name") val tagName: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("html_url") val htmlUrl: String? = null,
    @SerializedName("body") val body: String? = null,
    @SerializedName("assets") val assets: List<GitHubAsset>? = null,
)

internal data class GitHubAsset(
    @SerializedName("name") val name: String? = null,
    @SerializedName("browser_download_url") val downloadUrl: String? = null,
)

/**
 * Turns GitHub's JSON into an [UpdateInfo].
 *
 * Written against Gson rather than org.json so it can be tested on the JVM: org.json is part of the
 * Android framework and throws in plain unit tests.
 */
object GitHubReleaseParser {

    private val gson = Gson()

    const val FALLBACK_URL = "https://github.com/ChenyangLT/meditrack/releases/latest"

    fun parse(json: String): UpdateInfo? = runCatching {
        val release = gson.fromJson(json, GitHubRelease::class.java) ?: return null
        val tag = release.tagName?.takeIf { it.isNotBlank() } ?: return null
        UpdateInfo(
            version = UpdateVersion.display(tag),
            tagName = tag,
            releaseUrl = release.htmlUrl?.takeIf { it.isNotBlank() } ?: FALLBACK_URL,
            apkUrl = release.assets
                ?.firstOrNull { it.name?.endsWith(".apk", ignoreCase = true) == true }
                ?.downloadUrl,
            body = release.body.orEmpty(),
        )
    }.getOrNull()
}

/**
 * Asks GitHub for the latest release.
 *
 * This is the **only** network call in the application. It is a plain GET of a public endpoint with a
 * hard-coded URL: no identifiers, no query parameters, no bodies, nothing about the user or their
 * medication leaves the device. Anything that fails returns null - an update check must never be
 * visible as an error, and must never keep the app from working offline.
 */
@Singleton
class UpdateChecker @Inject constructor() {

    suspend fun latest(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                // GitHub answers 403 without a User-Agent.
                setRequestProperty("User-Agent", "MediTrack/$currentVersion (Android)")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
                GitHubReleaseParser.parse(connection.inputStream.bufferedReader().use { it.readText() })
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    companion object {
        /** Hard-coded on purpose: the app has exactly one network destination. */
        const val ENDPOINT = "https://api.github.com/repos/ChenyangLT/meditrack/releases/latest"

        private const val TIMEOUT_MILLIS = 10_000
    }
}
