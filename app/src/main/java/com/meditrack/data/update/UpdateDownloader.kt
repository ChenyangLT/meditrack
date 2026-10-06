package com.meditrack.data.update

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * How a download ended.
 *
 * [Progress] is emitted repeatedly while it runs rather than being returned, so the UI can show a
 * determinate bar; the two terminal states carry a message that is already fit to display.
 */
sealed interface DownloadState {
    /** @param percent 0..100, or -1 when the server did not send a content length */
    data class Progress(val percent: Int, val bytesRead: Long, val totalBytes: Long) : DownloadState

    data class Done(val file: File) : DownloadState

    /** [reason] is user-facing; [retryable] says whether another mirror is worth trying. */
    data class Failed(val reason: String, val retryable: Boolean = true) : DownloadState
}

/**
 * Fetches an update package into the app's own cache directory.
 *
 * ## Why the app downloads instead of handing the URL to a browser
 *
 * The browser hand-off was the v2.0 behaviour and it is what the requirement calls broken: on the
 * networks where `github.com` is unreachable the browser shows a connection error and the user is stuck
 * with no alternative - and even when it works, the download lands in the Downloads folder and the user
 * has to find the file and tap it themselves. Downloading in-app means the mirror can be switched, the
 * progress is visible, and installation follows immediately from the same screen.
 *
 * ## Why this is not `DownloadManager`
 *
 * The platform `DownloadManager` would give resumable downloads and a system notification for free, but
 * it writes to shared storage and returns a `content://` uri this app then has to read back through
 * `DownloadManager`'s own cursor API - and on the ROMs this app targets it is frequently disabled or
 * silently throttled. A plain streaming GET into `cacheDir` is a few dozen lines, gives exact progress,
 * needs no permission (app-private storage), and is trivially cancellable.
 *
 * ## Why the connection is configured the way it is
 *
 * Redirects are followed because GitHub's download URL 302s to a signed CDN URL; without that the
 * download would be a zero-byte "success". A `User-Agent` is required because GitHub answers 403 to a
 * request without one. The read timeout is generous because the failure it guards against is a stalled
 * connection on a throttled network, and three seconds there would abort downloads that were merely slow.
 */
@Singleton
class UpdateDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Streams [url] into a file named after the mirror that served it.
     *
     * @param onProgress invoked from the calling coroutine on every chunk that changes the reported
     *        percentage, so the caller controls the dispatcher
     * @return the terminal [DownloadState]; never throws
     */
    suspend fun download(
        url: String,
        fileName: String,
        onProgress: (DownloadState.Progress) -> Unit,
    ): DownloadState = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, UPDATE_DIRECTORY).apply { mkdirs() }
        // A stale partial file from an earlier attempt would otherwise be appended to or mistaken for a
        // finished download, and the verifier would then reject a package that was actually fine.
        val target = File(directory, fileName)
        val partial = File(directory, "$fileName.part")
        partial.delete()
        target.delete()

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/octet-stream")
                // No transparent decompression: the size reported by Content-Length must match the bytes
                // written, or the progress bar and the verifier's length check both become meaningless.
                setRequestProperty("Accept-Encoding", "identity")
            }

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                return@withContext DownloadState.Failed(httpReason(code))
            }

            val total = connection.contentLengthLong
            var read = 0L
            var lastPercent = -1

            connection.inputStream.use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        // Cancellation is checked explicitly: an interrupt alone does not stop a blocking
                        // socket read, so a user who backs out of the dialog must not be left with a
                        // download running until the next chunk happens to arrive.
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        read += count
                        val percent = if (total > 0L) ((read * 100L) / total).toInt() else -1
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(DownloadState.Progress(percent, read, total))
                        }
                    }
                }
            }

            if (read <= 0L) {
                partial.delete()
                return@withContext DownloadState.Failed("下载到的文件是空的")
            }
            // A server that advertises a length and then sends less has produced a truncated package; the
            // verifier would reject it too, but failing here names the cause instead of the symptom.
            if (total > 0L && read < total) {
                partial.delete()
                return@withContext DownloadState.Failed("下载中断（$read / $total 字节）")
            }

            if (!partial.renameTo(target)) {
                // A rename can fail across filesystems; both files are in the same directory here, so the
                // fallback is only for an OS that refuses the rename anyway.
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            Log.i(TAG, "downloaded $fileName ($read bytes) from $url")
            DownloadState.Done(target)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            partial.delete()
            throw cancelled
        } catch (error: Throwable) {
            partial.delete()
            Log.w(TAG, "download failed from $url", error)
            DownloadState.Failed(describe(error))
        } finally {
            connection?.disconnect()
        }
    }

    /** Removes every downloaded package; used by 清除缓存 and after a successful install. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching { File(context.cacheDir, UPDATE_DIRECTORY).deleteRecursively() }
    }

    /** What the downloaded packages currently occupy, for the cache-size display. */
    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, UPDATE_DIRECTORY)
        if (!directory.exists()) 0L else directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun httpReason(code: Int): String = when (code) {
        HttpURLConnection.HTTP_FORBIDDEN, 429 -> "下载地址拒绝了请求（HTTP $code），换一个下载方式试试"
        HttpURLConnection.HTTP_NOT_FOUND -> "下载地址不存在（HTTP 404），换一个下载方式试试"
        HttpURLConnection.HTTP_CLIENT_TIMEOUT -> "服务器超时，换一个下载方式试试"
        else -> "下载失败（HTTP $code）"
    }

    private fun describe(error: Throwable): String = when (error) {
        is java.net.SocketTimeoutException -> "下载超时，换一个下载方式试试"
        is java.net.UnknownHostException -> "无法解析下载地址的域名，换一个下载方式试试"
        is java.net.ConnectException -> "连接被拒绝，可能被网络拦截，换一个下载方式试试"
        is javax.net.ssl.SSLException -> "安全连接失败，可能被网络拦截，换一个下载方式试试"
        is IOException -> "下载中断：${error.message ?: "网络错误"}"
        else -> "下载失败：${error.javaClass.simpleName}"
    }

    companion object {
        private const val TAG = "UpdateDownloader"

        /** Subdirectory of `cacheDir`; app-private, so no storage permission is involved. */
        const val UPDATE_DIRECTORY = "updates"

        /** GitHub rejects a request without a User-Agent. */
        private const val USER_AGENT = "MediTrack-Android"

        private const val CONNECT_TIMEOUT_MILLIS = 20_000
        private const val READ_TIMEOUT_MILLIS = 60_000

        /** 64 KiB: large enough that the per-chunk overhead disappears, small enough for smooth progress. */
        private const val BUFFER_BYTES = 64 * 1024
    }
}
