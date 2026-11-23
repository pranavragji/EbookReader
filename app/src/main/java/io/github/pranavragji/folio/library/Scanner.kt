package io.github.pranavragji.folio.library

import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import io.github.pranavragji.folio.FolioApp
import io.github.pranavragji.folio.data.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File

/** Indexes every supported file under the library folders into the books table. */
class Scanner(private val app: FolioApp) {
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning
    private val lock = Mutex()

    private class Found(val uri: String, val name: String, val size: Long, val root: String)

    private val downloadsRoot get() = Uri.fromFile(app.downloadsDir).toString()

    /** Rescan every folder in Settings.folders plus app.downloadsDir; upsert new/changed, delete vanished. */
    suspend fun scanAll() = withContext(Dispatchers.IO) {
        if (!lock.tryLock()) return@withContext   // a scan is already running; it will pick everything up
        _scanning.value = true
        try {
            scan()
        } catch (e: Exception) {
            Log.w(TAG, "scan failed", e)
        } finally {
            _scanning.value = false
            lock.unlock()
        }
    }

    /** Index one file that was just downloaded into app.downloadsDir. */
    suspend fun import(file: File): Book? = withContext(Dispatchers.IO) {
        val found = Found(Uri.fromFile(file).toString(), file.name, file.length(), downloadsRoot)
        val known = app.db.books().all().associateBy { it.id }
        index(found, known)?.also { app.db.books().upsert(listOf(it)) }
    }

    private suspend fun scan() {
        val dao = app.db.books()
        val found = mutableListOf<Found>()
        val failedRoots = mutableSetOf<String>()
        for (root in app.prefs.current().folders) {
            try { walkTree(Uri.parse(root), root, found) } catch (e: Exception) {
                // Permission revoked / provider gone: keep its books rather than wiping them.
                Log.w(TAG, "cannot scan $root", e); failedRoots += root
            }
        }
        val dl = downloadsRoot
        app.downloadsDir.walkTopDown().filter { it.isFile && Formats.of(it.name) != null }
            .forEach { found += Found(Uri.fromFile(it).toString(), it.name, it.length(), dl) }

        val existing = dao.all()
        val byUri = existing.associateBy { it.uri }
        val byId = existing.associateBy { it.id }.toMutableMap()
        val seen = HashSet<String>()
        val batch = mutableListOf<Book>()
        for (f in found) {
            val old = byUri[f.uri]
            if (old != null && old.size == f.size && old.folder == f.root) { seen += old.id; continue }
            val book = try { index(f, byId) } catch (e: Exception) { Log.w(TAG, "skip ${f.name}", e); null } ?: continue
            if (!seen.add(book.id)) continue    // same file found twice; first copy wins
            byId[book.id] = book
            batch += book
            if (batch.size >= 12) { dao.upsert(batch.toList()); batch.clear() }   // covers appear progressively
        }
        if (batch.isNotEmpty()) dao.upsert(batch)
        val gone = existing.filter { it.id !in seen && it.folder !in failedRoots }.map { it.id }
        gone.chunked(500).forEach { dao.delete(it) }
        gone.forEach { File(app.filesDir, "covers/$it.jpg").delete() }
    }

    /** Hash, and read metadata only for files never seen before (moved/renamed files keep everything). */
    private fun index(f: Found, known: Map<String, Book>): Book? {
        val format = Formats.of(f.name) ?: return null
        val uri = Uri.parse(f.uri)
        val id = Io.open(app, uri).use(::partialMd5)
        known[id]?.let { return it.copy(uri = f.uri, size = f.size, folder = f.root) }
        val meta = try { MetaReader.read(app, uri, format, id) } catch (e: Exception) {
            Log.w(TAG, "metadata ${f.name}", e); Meta()
        }
        if (!meta.hasPages) return null
        val cover = try {
            meta.coverBitmap?.let { Io.saveCover(app, id, it) } ?: Io.saveCover(app, id, meta.cover)
        } catch (e: Exception) { null }
        File(app.cacheDir, "files/$id.bin").delete()   // temp copy from pipe-only providers
        return Book(
            id = id,
            uri = f.uri,
            title = meta.title?.takeIf { it.isNotBlank() } ?: Formats.cleanName(f.name),
            author = meta.author.orEmpty(),
            format = format,
            isComic = format in Formats.COMIC,
            coverPath = cover,
            size = f.size,
            folder = f.root,
        )
    }

    private fun walkTree(tree: Uri, root: String, out: MutableList<Found>) {
        val cr = app.contentResolver
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val stack = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(tree)))
        while (stack.isNotEmpty()) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, stack.removeLast())
            cr.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (name.startsWith(".")) continue
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) stack.addLast(id)
                    else if (Formats.of(name) != null)
                        out += Found(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), name, c.getLong(3), root)
                }
            }
        }
    }

    private companion object { const val TAG = "Scanner" }
}
