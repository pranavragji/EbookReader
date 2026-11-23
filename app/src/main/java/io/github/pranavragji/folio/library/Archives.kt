package io.github.pranavragji.folio.library

import android.content.Context
import android.net.Uri
import com.github.junrar.Archive
import com.github.junrar.exception.UnsupportedRarV5Exception
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarFile
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.InputStream

class UnsupportedArchive(msg: String) : Exception(msg)

/** Image pages of a comic archive (cbz/zip, cbr/rar, cb7/7z, cbt/tar), in natural order. */
@Suppress("DEPRECATION") // channel constructors are deprecated in commons-compress 1.26+, still the simplest API
object Archives {

    /** Bytes of the first page, for covers. Null if the archive has no images. */
    fun firstPage(ctx: Context, uri: Uri, format: String, key: String): ByteArray? {
        var out: ByteArray? = null
        visit(ctx, uri, format, key, onlyFirst = true) { _, _, _, input -> out = input.readBytes(); false }
        return out
    }

    /**
     * Extracts every page once into cacheDir/pages/<key>/ (reused on later opens).
     * ponytail: extract-all up front (~1-2 s per 50 MB); switch to a Coil Fetcher over entries if huge archives feel slow.
     */
    fun extractPages(ctx: Context, uri: Uri, format: String, key: String, onProgress: (Int, Int) -> Unit): List<File> {
        val dir = File(ctx.cacheDir, "pages/$key")
        val done = File(dir, ".complete")
        if (done.exists()) {
            dir.setLastModified(System.currentTimeMillis())
            return pagesIn(dir)
        }
        trimCache(File(ctx.cacheDir, "pages"))
        dir.deleteRecursively(); dir.mkdirs()
        visit(ctx, uri, format, key, onlyFirst = false) { index, total, name, input ->
            val ext = name.substringAfterLast('.', "img").lowercase()
            File(dir, "%05d.%s".format(index, ext)).outputStream().use { input.copyTo(it, 1 shl 16) }
            onProgress(index + 1, total)
            true
        }
        done.writeText("ok")
        return pagesIn(dir)
    }

    private fun pagesIn(dir: File) = dir.listFiles { f -> !f.name.startsWith(".") }.orEmpty().sortedBy { it.name }

    private fun trimCache(root: File, keep: Int = 4) {
        root.listFiles()?.sortedByDescending { it.lastModified() }?.drop(keep)?.forEach { it.deleteRecursively() }
    }

    /** Calls [fn] (index-in-natural-order, total, name, stream) per image; [fn] returns false to stop. */
    private fun visit(
        ctx: Context, uri: Uri, format: String, key: String, onlyFirst: Boolean,
        fn: (Int, Int, String, InputStream) -> Boolean,
    ) {
        when (format) {
            "cbz" -> Io.withChannel(ctx, uri, key) { ch ->
                ZipFile(ch).use { zip ->
                    val entries = zip.entries.toList().filter { !it.isDirectory && Formats.isImage(it.name) }
                        .sortedWith(compareBy(naturalOrder) { it.name })
                    for ((i, e) in entries.withIndex()) {
                        if (!zip.getInputStream(e).use { fn(i, entries.size, e.name, it) } || onlyFirst) break
                    }
                }
            }
            "cbt" -> Io.withChannel(ctx, uri, key) { ch ->
                TarFile(ch).use { tar ->
                    val entries = tar.entries.filter { it.isFile && Formats.isImage(it.name) }
                        .sortedWith(compareBy(naturalOrder) { it.name })
                    for ((i, e) in entries.withIndex()) {
                        if (!tar.getInputStream(e).use { fn(i, entries.size, e.name, it) } || onlyFirst) break
                    }
                }
            }
            "cb7" -> Io.withChannel(ctx, uri, key) { ch ->
                SevenZFile(ch).use { sz ->
                    val names = sz.entries.filter { !it.isDirectory && Formats.isImage(it.name) }.map { it.name }
                        .sortedWith(naturalOrder)
                    if (names.isEmpty()) return@use
                    if (onlyFirst) {
                        val e = sz.entries.first { it.name == names[0] }
                        sz.getInputStream(e).use { fn(0, names.size, e.name, it) }
                        return@use
                    }
                    // Solid 7z: random access re-decompresses from block start, so walk in archive order.
                    val index = names.withIndex().associate { it.value to it.index }
                    while (true) {
                        val e = sz.nextEntry ?: break
                        val i = index[e.name] ?: continue
                        val bytes = ByteArray(e.size.toInt())
                        var n = 0
                        while (n < bytes.size) { val r = sz.read(bytes, n, bytes.size - n); if (r < 0) break; n += r }
                        if (!fn(i, names.size, e.name, bytes.inputStream())) break
                    }
                }
            }
            "cbr" -> {
                val file = Io.localFile(ctx, uri, key, "rar")
                val archive = try { Archive(file) } catch (e: UnsupportedRarV5Exception) {
                    throw UnsupportedArchive("RAR5 archives aren't supported yet. Repack as CBZ to read it here.")
                }
                archive.use { rar ->
                    val headers = rar.fileHeaders.filter { !it.isDirectory && Formats.isImage(it.fileName.replace('\\', '/')) }
                        .sortedWith(compareBy(naturalOrder) { it.fileName })
                    for ((i, h) in headers.withIndex()) {
                        val bytes = java.io.ByteArrayOutputStream().also { rar.extractFile(h, it) }.toByteArray()
                        if (!fn(i, headers.size, h.fileName, bytes.inputStream()) || onlyFirst) break
                    }
                }
            }
            else -> throw UnsupportedArchive("Not a comic archive: $format")
        }
    }
}
