package io.github.pranavragji.folio.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import java.security.MessageDigest

object Formats {
    private val byExt = mapOf(
        "epub" to "epub", "mobi" to "mobi", "prc" to "mobi", "azw" to "azw", "azw3" to "azw3", "kf8" to "azw3",
        "fb2" to "fb2", "fbz" to "fbz", "pdf" to "pdf",
        "cbz" to "cbz", "zip" to "cbz", "cbr" to "cbr", "rar" to "cbr", "cb7" to "cb7", "7z" to "cb7",
        "cbt" to "cbt", "tar" to "cbt", "txt" to "txt", "html" to "html", "htm" to "html", "xhtml" to "html",
    )
    val COMIC = setOf("cbz", "cbr", "cb7", "cbt")
    val FOLIATE = setOf("epub", "mobi", "azw", "azw3", "fb2", "fbz")
    private val IMAGE = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic", "heif", "jxl")

    fun of(name: String): String? {
        val n = name.lowercase()
        if (n.startsWith(".")) return null
        if (n.endsWith(".fb2.zip")) return "fbz"
        return byExt[n.substringAfterLast('.', "")]
    }

    fun isImage(path: String): Boolean {
        val n = path.lowercase()
        if (n.contains("__macosx/") || n.substringAfterLast('/').startsWith(".")) return false
        return n.substringAfterLast('.', "") in IMAGE
    }

    fun mime(format: String) = when (format) {
        "epub" -> "application/epub+zip"
        "fb2" -> "application/x-fictionbook+xml"
        "fbz" -> "application/x-zip-compressed-fb2"
        "cbz" -> "application/vnd.comicbook+zip"
        "pdf" -> "application/pdf"
        else -> "application/x-mobipocket-ebook"
    }

    /** "My_Book.v2 (2019).epub" -> "My Book.v2 (2019)" */
    fun cleanName(name: String): String =
        name.removeSuffix(".fb2.zip").substringBeforeLast('.').replace('_', ' ').replace(Regex("\\s+"), " ").trim()
            .ifEmpty { name }
}

/** Natural order: "page2" < "page10". */
val naturalOrder: Comparator<String> = run {
    val chunk = Regex("\\d+|\\D+")
    Comparator { a, b ->
        val x = chunk.findAll(a.lowercase()).map { it.value }.toList()
        val y = chunk.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]; val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) {
                val pt = p.trimStart('0'); val qt = q.trimStart('0')
                if (pt.length != qt.length) pt.length - qt.length else pt.compareTo(qt)
            } else p.compareTo(q)
            if (c != 0) return@Comparator c
        }
        x.size - y.size
    }
}

/**
 * KOReader partial MD5: 1 KiB samples at offsets 0, 1K, 4K, 16K … 1G. Matches KOReader/kosync, so
 * the same file gets the same id on every device. Offsets only grow, so a plain stream works.
 */
fun partialMd5(input: InputStream): String {
    val md = MessageDigest.getInstance("MD5")
    val buf = ByteArray(1024)
    var pos = 0L
    loop@ for (i in -1..10) {
        val off = if (i < 0) 0L else 1024L shl (2 * i)
        while (pos < off) {
            val s = input.skip(off - pos)
            if (s > 0) pos += s
            else if (input.read() == -1) break@loop
            else pos++
        }
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        if (n == 0) break
        pos += n
        md.update(buf, 0, n)
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

object Io {
    fun open(ctx: Context, uri: Uri): InputStream =
        if (uri.scheme == "file") FileInputStream(uri.path!!)
        else ctx.contentResolver.openInputStream(uri) ?: error("Cannot open $uri")

    /** A seekable fd, or null when the provider only hands out pipes (e.g. some cloud providers). */
    fun seekableFd(ctx: Context, uri: Uri): ParcelFileDescriptor? {
        if (uri.scheme == "file") return ParcelFileDescriptor.open(File(uri.path!!), ParcelFileDescriptor.MODE_READ_ONLY)
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return null
        if (pfd.statSize >= 0) return pfd
        pfd.close()
        return null
    }

    /** A local File for libraries that insist on one; copies into cache only when needed. */
    fun localFile(ctx: Context, uri: Uri, key: String, ext: String): File {
        if (uri.scheme == "file") return File(uri.path!!)
        val f = File(ctx.cacheDir, "files/$key.$ext")
        if (!f.exists()) {
            f.parentFile!!.mkdirs()
            val tmp = File(f.path + ".part")
            open(ctx, uri).use { i -> tmp.outputStream().use { i.copyTo(it, 1 shl 16) } }
            tmp.renameTo(f)
        }
        return f
    }

    /** Random-access channel over [uri]; falls back to a cached copy for pipe-only providers. */
    fun <T> withChannel(ctx: Context, uri: Uri, key: String, block: (SeekableByteChannel) -> T): T {
        val pfd = seekableFd(ctx, uri)
        val stream = if (pfd != null) ParcelFileDescriptor.AutoCloseInputStream(pfd)
        else FileInputStream(localFile(ctx, uri, key, "bin"))
        return stream.channel.use(block)
    }

    fun saveCover(ctx: Context, id: String, bytes: ByteArray?): String? {
        bytes ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= COVER_W) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        return saveCover(ctx, id, bmp)
    }

    fun saveCover(ctx: Context, id: String, bmp: Bitmap): String {
        val scaled = if (bmp.width > COVER_W) Bitmap.createScaledBitmap(bmp, COVER_W, bmp.height * COVER_W / bmp.width, true) else bmp
        val f = File(ctx.filesDir, "covers/$id.jpg").apply { parentFile!!.mkdirs() }
        f.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        if (scaled !== bmp) scaled.recycle()
        bmp.recycle()
        return f.absolutePath
    }

    const val COVER_W = 600
}
