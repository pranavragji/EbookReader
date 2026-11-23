package io.github.pranavragji.folio.sync

import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import io.github.pranavragji.folio.FolioApp
import io.github.pranavragji.folio.data.Book
import io.github.pranavragji.folio.data.SavedTitle
import io.github.pranavragji.folio.data.SyncConfig
import io.github.pranavragji.folio.source.friendly
import io.github.pranavragji.folio.source.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

data class SyncState(val running: Boolean = false, val lastSync: Long = 0, val error: String? = null)

@Serializable
data class BookSync(val progress: Float, val locator: String? = null, val updatedAt: Long, val title: String = "")

@Serializable
data class TitleSync(
    val sourceId: String,
    val titleId: String,
    val title: String,
    val coverUrl: String? = null,
    val author: String = "",
    val inLibrary: Boolean = false,
    val lastChapterId: String? = null,
    val lastChapterName: String? = null,
    val lastPage: Int = 0,
    val lastReadAt: Long = 0,
    val updatedAt: Long = 0,
) {
    fun toSaved() = SavedTitle(sourceId, titleId, title, coverUrl, author, inLibrary, lastChapterId, lastChapterName, lastPage, lastReadAt, updatedAt)
}

fun SavedTitle.toSync() = TitleSync(sourceId, titleId, title, coverUrl, author, inLibrary, lastChapterId, lastChapterName, lastPage, lastReadAt, updatedAt)

@Serializable
data class SyncFile(
    val version: Int = 1,
    val books: Map<String, BookSync> = emptyMap(),
    val titles: Map<String, TitleSync> = emptyMap(),
)

/** Result of merging: what to upload, and which local rows the remote side has newer data for. */
data class Merged(val file: SyncFile, val books: Map<String, BookSync>, val titles: List<SavedTitle>)

/** Per-key last-writer-wins. Remote keys for books we don't have locally are kept, so other devices don't lose them. */
fun merge(remote: SyncFile, books: List<Book>, titles: List<SavedTitle>): Merged {
    val outBooks = remote.books.toMutableMap()
    val bookUpdates = HashMap<String, BookSync>()
    for (b in books) {
        val r = outBooks[b.id]
        when {
            r == null || b.progressUpdatedAt > r.updatedAt -> if (b.progressUpdatedAt > 0)
                outBooks[b.id] = BookSync(b.progress, b.locator, b.progressUpdatedAt, b.title)
            r.updatedAt > b.progressUpdatedAt -> bookUpdates[b.id] = r
        }
    }
    val outTitles = remote.titles.toMutableMap()
    val local = titles.associateBy { "${it.sourceId}|${it.titleId}" }
    for ((k, t) in local) {
        val r = outTitles[k]
        if (r == null || t.updatedAt > r.updatedAt) outTitles[k] = t.toSync()
    }
    val titleUpdates = outTitles.filter { (k, r) -> (local[k]?.updatedAt ?: -1) < r.updatedAt }.values.map { it.toSaved() }
    return Merged(SyncFile(1, outBooks, outTitles), bookUpdates, titleUpdates)
}

class SyncManager(private val app: FolioApp) {
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state
    private val lock = Mutex()
    private var pending: Job? = null

    init {
        app.scope.launch { _state.update { it.copy(lastSync = app.prefs.current().sync.lastSync) } }
    }

    fun remote(cfg: SyncConfig): Remote? = when (cfg.provider) {
        "google" -> DriveRemote(app.http) { GoogleAuth.token(app) }
        "webdav" -> if (cfg.webdavUrl.isBlank()) null else WebDavRemote(app.http, cfg.webdavUrl, cfg.webdavUser, cfg.webdavPass)
        else -> null
    }

    /** Fire-and-forget, debounced; no-op when no provider is configured. Safe to call often. */
    fun requestSync() {
        pending?.cancel()
        // Only the wait is cancellable; a sync that already started always finishes.
        pending = app.scope.launch {
            delay(3_000)
            app.scope.launch { syncNow() }
        }
    }

    suspend fun syncNow(): Result<Unit> = lock.withLock {
        val remote = remote(app.prefs.current().sync) ?: return Result.success(Unit)
        _state.update { it.copy(running = true, error = null) }
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val text = remote.readSync()
                val file = text?.let { runCatching { json.decodeFromString(SyncFile.serializer(), it) }.getOrNull() } ?: SyncFile()
                val m = merge(file, app.db.books().all(), app.db.titles().all())
                m.books.forEach { (id, r) -> app.db.books().updateProgress(id, r.progress, r.locator, r.updatedAt) }
                if (m.titles.isNotEmpty()) app.db.titles().upsert(m.titles)
                remote.writeSync(json.encodeToString(SyncFile.serializer(), m.file))
            }
        }
        val now = System.currentTimeMillis()
        if (result.isSuccess) app.prefs.update { it.copy(sync = it.sync.copy(lastSync = now)) }
        _state.update {
            SyncState(running = false, lastSync = if (result.isSuccess) now else it.lastSync, error = result.exceptionOrNull()?.friendly())
        }
        result
    }

    /** Cloud library listing for the Sync tab. */
    suspend fun cloudBooks(): List<RemoteBook> = remote(app.prefs.current().sync)?.listBooks() ?: emptyList()

    /** Download a cloud book into the local library. */
    suspend fun download(book: RemoteBook): Book? {
        val remote = remote(app.prefs.current().sync) ?: return null
        val dest = File(app.downloadsDir, book.name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
        if (!dest.exists()) remote.download(book, dest)
        return app.scanner.import(dest)
    }

    /** Filename the book will have in the cloud library. */
    fun fileName(book: Book): String {
        val uri = Uri.parse(book.uri)
        val name = if (uri.scheme == "content") runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() else uri.lastPathSegment
        return name ?: "${book.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")}.${book.format}"
    }

    private fun open(book: Book): InputStream {
        val uri = Uri.parse(book.uri)
        return if (uri.scheme == "content") app.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(book.uri)
        else File(uri.path ?: book.uri).inputStream()
    }

    /** Upload the book file to the user's cloud library so other devices can download it. */
    suspend fun uploadBook(book: Book): Result<Unit> = runCatching {
        val remote = remote(app.prefs.current().sync) ?: error("Connect Google Drive or WebDAV first")
        val name = fileName(book)
        if (remote.listBooks().any { it.name == name && (book.size == 0L || it.size == book.size) }) return@runCatching
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(book.format) ?: "application/octet-stream"
        val size = if (book.size > 0) book.size else -1L
        remote.upload(name, size, mime) { open(book) }
    }.recoverCatching { throw Exception(it.friendly(), it) }
}
