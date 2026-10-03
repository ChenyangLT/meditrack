package com.meditrack.data.update

import android.util.Log
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
 * The manifest this project publishes on its own site (`docs/version.json`), which GitHub Pages serves.
 *
 * It exists because `api.github.com` is an unreliable destination on some networks: the host is
 * commonly throttled or reset while the Pages CDN answers. Both carry the same information, so the
 * app tries the cheap, dependable one first.
 */
internal data class VersionManifest(
    @SerializedName("version") val version: String? = null,
    @SerializedName("tagName") val tagName: String? = null,
    @SerializedName("releaseUrl") val releaseUrl: String? = null,
    @SerializedName("apkUrl") val apkUrl: String? = null,
    @SerializedName("body") val body: String? = null,
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

    /** Reads this project's own manifest; see [VersionManifest]. */
    fun parseManifest(json: String): UpdateInfo? = runCatching {
        val manifest = gson.fromJson(json, VersionManifest::class.java) ?: return null
        val version = manifest.version?.takeIf { it.isNotBlank() } ?: return null
        UpdateInfo(
            version = UpdateVersion.display(version),
            tagName = manifest.tagName?.takeIf { it.isNotBlank() } ?: "v" + UpdateVersion.display(version),
            releaseUrl = manifest.releaseUrl?.takeIf { it.isNotBlank() } ?: FALLBACK_URL,
            apkUrl = manifest.apkUrl?.takeIf { it.isNotBlank() },
            body = manifest.body.orEmpty(),
        )
    }.getOrNull()
}

/** What one check concluded, with a reason a human can act on. */
sealed interface UpdateOutcome {
    data class Found(val info: UpdateInfo) : UpdateOutcome

    /** [reason] is shown on a manual check and logged on every check. */
    data class Failed(val reason: String) : UpdateOutcome
}

/**
 * Asks for the latest release.
 *
 * These are the app's only network calls: plain GETs of public endpoints with hard-coded URLs - no
 * parameters, no bodies, nothing about the user or their medication. Several endpoints are tried in
 * order because any single host can be unreachable on a given network; the first is this project's
 * own site, which is the most dependable of the three.
 *
 * Every attempt is logged. An update check used to fail completely silently, which made "it says
 * there is a network problem" impossible to diagnose from the device.
 */
@Singleton
class UpdateChecker @Inject constructor() {

    private class Endpoint(val url: String, val parse: (String) -> UpdateInfo?)

    private val endpoints = listOf(
        Endpoint(MANIFEST_URL) { GitHubReleaseParser.parseManifest(it) },
        Endpoint(JSDELIVR_URL) { GitHubReleaseParser.parseManifest(it) },
        Endpoint(API_URL) { GitHubReleaseParser.parse(it) },
    )

    suspend fun latest(currentVersion: String): UpdateOutcome = withContext(Dispatchers.IO) {
        val failures = mutableListOf<String>()
        for (endpoint in endpoints) {
            when (val result = attempt(endpoint, currentVersion)) {
                is UpdateOutcome.Found -> {
                    Log.i(TAG, "check ok via ${endpoint.url} -> ${result.info.tagName}")
                    return@withContext result
                }

                is UpdateOutcome.Failed -> {
                    Log.w(TAG, "check failed via ${endpoint.url}: ${result.reason}")
                    failures += result.reason
                }
            }
        }
        UpdateOutcome.Failed(failures.firstOrNull() ?: "未知错误")
    }

    private fun attempt(endpoint: Endpoint, currentVersion: String): UpdateOutcome {
        val startedAt = System.currentTimeMillis()
        return runCatching {
            val connection = (URL(endpoint.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                instanceFollowRedirects = true
                // GitHub answers 403 without a User-Agent.
                setRequestProperty("User-Agent", "MediTrack/$currentVersion (Android)")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Cache-Control", "no-cache")
            }
            try {
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    return UpdateOutcome.Failed(httpReason(code))
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val info = endpoint.parse(body)
                if (info == null) {
                    UpdateOutcome.Failed("返回内容无法解析（HTTP 200，${body.length} 字节）")
                } else {
                    Log.d(TAG, "attempt ${endpoint.url} took ${System.currentTimeMillis() - startedAt} ms")
                    UpdateOutcome.Found(info)
                }
            } finally {
                connection.disconnect()
            }
        }.getOrElse { error -> UpdateOutcome.Failed(describe(error)) }
    }

    private fun httpReason(code: Int): String = when (code) {
        HttpURLConnection.HTTP_FORBIDDEN, 429 -> "请求被拒绝（HTTP $code，可能过于频繁）"
        HttpURLConnection.HTTP_NOT_FOUND -> "找不到发布信息（HTTP 404）"
        else -> "服务器返回 HTTP $code"
    }

    /** Network exceptions, in words a person can act on. */
    private fun describe(error: Throwable): String = when (error) {
        is java.net.SocketTimeoutException -> "连接超时"
        is java.net.UnknownHostException -> "无法解析域名（DNS）"
        is java.net.ConnectException -> "连接被拒绝（可能被网络拦截）"
        is javax.net.ssl.SSLException -> "安全连接失败（可能被网络拦截）"
        else -> "${error.javaClass.simpleName}: ${error.message ?: "无详细信息"}"
    }

    companion object {
        private const val TAG = "UpdateChecker"

        const val API_URL = "https://api.github.com/repos/ChenyangLT/meditrack/releases/latest"

        /** Published by this repo at `docs/version.json` and served by GitHub Pages. */
        const val MANIFEST_URL = "https://chenyanglt.github.io/meditrack/version.json"

        /** The same file through Cloudflare's GitHub mirror. */
        const val JSDELIVR_URL = "https://cdn.jsdelivr.net/gh/ChenyangLT/meditrack@main/docs/version.json"

        /**
         * Generous on purpose. A cold TLS handshake to GitHub from a congested network can take more
         * than ten seconds, and timing out there was indistinguishable from being offline.
         */
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val READ_TIMEOUT_MILLIS = 15_000
    }
}
