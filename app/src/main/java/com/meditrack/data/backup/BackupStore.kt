package com.meditrack.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import com.meditrack.data.prefs.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where backups are written and read from.
 *
 * [AppPrivate] is the app's own `files/exports` directory: always available, needs no permission, and
 * invisible to a file manager - which is exactly why the user can point [Tree] at a folder they can
 * actually see (Downloads, a cloud provider, an SD card).
 */
sealed interface BackupFolder {
    data object AppPrivate : BackupFolder

    /** A folder chosen through the system picker, with a persisted read/write grant. */
    data class Tree(val uri: Uri) : BackupFolder
}

/** How to reach one file: a plain path, or a document in a chosen tree. */
sealed interface BackupHandle {
    data class Local(val file: File) : BackupHandle
    data class Document(val uri: Uri) : BackupHandle
}

data class BackupEntry(
    val name: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val handle: BackupHandle,
)

data class MigrationResult(val copied: Int, val skipped: Int)

/**
 * The listing rules, kept pure so they can be tested without a device.
 *
 * Two decisions live here that are easy to get subtly wrong in I/O code: which files count as
 * backups, and when a file is already present.
 */
object BackupListing {
    const val JSON_PREFIX = "meditrack-backup-"
    const val CSV_PREFIX = "meditrack-"

    fun isJsonBackup(name: String): Boolean =
        name.startsWith(JSON_PREFIX) && name.endsWith(".json", ignoreCase = true)

    fun isCsvExport(name: String): Boolean =
        name.startsWith(CSV_PREFIX) && name.endsWith(".csv", ignoreCase = true)

    fun isBackupOrExport(name: String): Boolean = isJsonBackup(name) || isCsvExport(name)

    /** Newest first, the order the list is shown in; the name breaks ties so it is stable. */
    fun newestFirst(entries: List<BackupEntry>): List<BackupEntry> = entries.sortedWith(
        compareByDescending<BackupEntry> { it.modifiedAtMillis }.thenByDescending { it.name },
    )

    /**
     * True when [candidate] is already in [target].
     *
     * Same name **and** same size means the same file. A same-named file of a different size is
     * treated as absent, so migrating overwrites an older copy of the same day's backup instead of
     * silently keeping the stale one. An unknown size (0, which some providers report) therefore
     * counts as absent - re-copying is harmless, while skipping a newer backup is not.
     */
    fun isPresent(target: List<BackupEntry>, candidate: BackupEntry): Boolean =
        candidate.sizeBytes > 0L && target.any { it.name == candidate.name && it.sizeBytes == candidate.sizeBytes }
}

/**
 * Reads and writes backup files wherever the user pointed the app.
 *
 * Uses [DocumentsContract] directly rather than androidx.documentfile: the app builds offline against
 * a fixed dependency set, and this needs three calls (list children, create, open).
 */
@Singleton
class BackupStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {

    private val resolver get() = context.contentResolver

    suspend fun folder(): BackupFolder {
        val saved = settings.current().backupFolderUri?.takeIf { it.isNotBlank() } ?: return BackupFolder.AppPrivate
        val uri = runCatching { Uri.parse(saved) }.getOrNull() ?: return BackupFolder.AppPrivate
        // A granted folder can disappear (uninstalled provider, revoked permission). Fall back rather
        // than failing every export from then on.
        return if (canRead(uri)) BackupFolder.Tree(uri) else BackupFolder.AppPrivate
    }

    suspend fun folderLabel(): String = when (val current = folder()) {
        BackupFolder.AppPrivate -> "应用内部存储（默认）"
        is BackupFolder.Tree -> documentName(current.uri) ?: "已选择的文件夹"
    }

    suspend fun setFolder(uri: Uri) {
        resolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        settings.setBackupFolderUri(uri.toString())
    }

    suspend fun clearFolder() = settings.setBackupFolderUri(null)

    // ------------------------------------------------------------------ listing

    /** The current folder's contents, or a specific one when the caller is mid-switch. */
    suspend fun list(): List<BackupEntry> = list(folder())

    suspend fun list(folder: BackupFolder): List<BackupEntry> = withContext(Dispatchers.IO) {
        val entries = when (folder) {
            BackupFolder.AppPrivate -> listLocal()
            is BackupFolder.Tree -> listTree(folder.uri)
        }
        BackupListing.newestFirst(entries.filter { BackupListing.isBackupOrExport(it.name) })
    }

    private fun listLocal(): List<BackupEntry> {
        val dir = File(context.filesDir, EXPORT_DIR)
        val files = dir.listFiles() ?: return emptyList()
        return files.filter { it.isFile }.map {
            BackupEntry(it.name, it.length(), it.lastModified(), BackupHandle.Local(it))
        }
    }

    private fun listTree(treeUri: Uri): List<BackupEntry> {
        val children = childrenUri(treeUri)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val out = mutableListOf<BackupEntry>()
        runCatching {
            resolver.query(children, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                val timeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (cursor.moveToNext()) {
                    if (cursor.getString(mimeCol) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val name = cursor.getString(nameCol) ?: continue
                    val size = if (cursor.isNull(sizeCol)) 0L else cursor.getLong(sizeCol)
                    val modified = if (cursor.isNull(timeCol)) 0L else cursor.getLong(timeCol)
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri, cursor.getString(idCol),
                    )
                    out += BackupEntry(name, size, modified, BackupHandle.Document(docUri))
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------ reading & writing

    suspend fun readBytes(entry: BackupEntry): ByteArray = withContext(Dispatchers.IO) {
        when (val handle = entry.handle) {
            is BackupHandle.Local -> handle.file.readBytes()
            is BackupHandle.Document -> resolver.openInputStream(handle.uri)?.use { it.readBytes() }
                ?: throw IOException("无法读取 ${entry.name}")
        }
    }

    suspend fun read(entry: BackupEntry): InputStream = withContext(Dispatchers.IO) {
        when (val handle = entry.handle) {
            is BackupHandle.Local -> handle.file.inputStream()
            is BackupHandle.Document -> resolver.openInputStream(handle.uri)
                ?: throw IOException("无法读取 ${entry.name}")
        }
    }

    suspend fun writeText(folder: BackupFolder, name: String, mimeType: String, text: String): BackupEntry =
        withContext(Dispatchers.IO) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            when (folder) {
                BackupFolder.AppPrivate -> {
                    val dir = File(context.filesDir, EXPORT_DIR)
                    dir.mkdirs()
                    val target = File(dir, name)
                    // Temp file + rename: a crash mid-write must not leave a truncated file that
                    // looks like a valid backup.
                    val temp = File(dir, "$name.tmp")
                    temp.writeBytes(bytes)
                    if (!temp.renameTo(target)) {
                        target.writeBytes(bytes)
                        temp.delete()
                    }
                    BackupEntry(target.name, target.length(), target.lastModified(), BackupHandle.Local(target))
                }

                is BackupFolder.Tree -> {
                    val uri = documentFor(folder.uri, name, mimeType)
                    val stream = resolver.openOutputStream(uri, "wt") ?: resolver.openOutputStream(uri)
                    stream?.use { it.write(bytes) } ?: throw IOException("无法写入 $name")
                    val written = listTree(folder.uri).firstOrNull { it.name == name }
                    written ?: BackupEntry(name, bytes.size.toLong(), System.currentTimeMillis(), BackupHandle.Document(uri))
                }
            }
        }

    /** Copies every backup that [from] holds and [to] does not, so switching folders loses nothing. */
    suspend fun migrate(from: List<BackupFolder>, to: BackupFolder): MigrationResult = withContext(Dispatchers.IO) {
        var copied = 0
        var skipped = 0
        val present = list(to).toMutableList()
        for (source in from) {
            if (source == to) continue
            for (entry in list(source)) {
                if (BackupListing.isPresent(present, entry)) {
                    skipped++
                    continue
                }
                runCatching {
                    val bytes = readBytes(entry)
                    writeBytes(to, entry.name, mimeTypeFor(entry.name), bytes)
                    present += entry
                    copied++
                }
            }
        }
        MigrationResult(copied, skipped)
    }

    private fun writeBytes(folder: BackupFolder, name: String, mimeType: String, bytes: ByteArray) {
        when (folder) {
            BackupFolder.AppPrivate -> {
                val dir = File(context.filesDir, EXPORT_DIR).apply { mkdirs() }
                File(dir, name).writeBytes(bytes)
            }

            is BackupFolder.Tree -> {
                val uri = documentFor(folder.uri, name, mimeType)
                (resolver.openOutputStream(uri, "wt") ?: resolver.openOutputStream(uri))
                    ?.use { it.write(bytes) } ?: throw IOException("无法写入 $name")
            }
        }
    }

    /** An existing document with this name, or a newly created one. */
    private fun documentFor(treeUri: Uri, name: String, mimeType: String): Uri {
        findChild(treeUri, name)?.let { existing ->
            return existing
        }
        return DocumentsContract.createDocument(resolver, treeDocumentUri(treeUri), mimeType, name)
            ?: throw IOException("无法创建 $name")
    }

    private fun findChild(treeUri: Uri, name: String): Uri? =
        listTree(treeUri).firstOrNull { it.name == name }?.let { (it.handle as BackupHandle.Document).uri }

    private fun mimeTypeFor(name: String): String =
        if (name.endsWith(".csv", ignoreCase = true)) "text/csv" else "application/json"

    /** A uri another app can read, for the share sheet. */
    fun shareUri(entry: BackupEntry): Uri = when (val handle = entry.handle) {
        is BackupHandle.Local -> FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", handle.file,
        )
        is BackupHandle.Document -> handle.uri
    }

    // ------------------------------------------------------------------ SAF plumbing

    private fun treeDocumentUri(treeUri: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri, DocumentsContract.getTreeDocumentId(treeUri),
    )

    private fun childrenUri(treeUri: Uri): Uri = DocumentsContract.buildChildDocumentsUriUsingTree(
        treeUri, DocumentsContract.getTreeDocumentId(treeUri),
    )

    private fun canRead(treeUri: Uri): Boolean =
        runCatching { resolver.query(childrenUri(treeUri), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { true } ?: false }
            .getOrDefault(false)

    private fun documentName(treeUri: Uri): String? = runCatching {
        resolver.query(
            treeDocumentUri(treeUri),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    companion object {
        /** Subdirectory of `filesDir` that holds the default backups. Mirrors `xml/file_paths.xml`. */
        const val EXPORT_DIR = "exports"
    }
}
