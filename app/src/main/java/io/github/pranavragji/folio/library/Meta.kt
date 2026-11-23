package io.github.pranavragji.folio.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Base64
import android.util.Xml
import org.apache.commons.compress.archivers.zip.ZipFile
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.charset.Charset

/** What the library shows for a file. [cover] is encoded image bytes; [hasPages] false = not really a book/comic. */
class Meta(
    val title: String? = null,
    val author: String? = null,
    val cover: ByteArray? = null,
    val coverBitmap: Bitmap? = null,
    val hasPages: Boolean = true,
)

@Suppress("DEPRECATION")
object MetaReader {
    fun read(ctx: Context, uri: Uri, format: String, key: String): Meta = when (format) {
        "epub" -> Io.withChannel(ctx, uri, key) { ZipFile(it).use(::epub) }
        "fbz" -> Io.withChannel(ctx, uri, key) { ch ->
            ZipFile(ch).use { zip ->
                val e = zip.entries.toList().firstOrNull { it.name.lowercase().endsWith(".fb2") } ?: return@use Meta()
                zip.getInputStream(e).use(::fb2)
            }
        }
        "fb2" -> Io.open(ctx, uri).use(::fb2)
        "mobi", "azw", "azw3" -> Io.withChannel(ctx, uri, key, ::mobi)
        "pdf" -> pdf(ctx, uri, key)
        in Formats.COMIC -> Archives.firstPage(ctx, uri, format, key).let { Meta(cover = it, hasPages = it != null) }
        else -> Meta()
    }

    // ---- EPUB: container.xml -> OPF -> dc:title / dc:creator / cover image ----
    private fun epub(zip: ZipFile): Meta {
        fun text(path: String) = zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }
        val container = text("META-INF/container.xml")?.toString(Charsets.UTF_8) ?: return Meta()
        val opfPath = Regex("full-path\\s*=\\s*\"([^\"]+)\"").find(container)?.groupValues?.get(1) ?: return Meta()
        val opf = zip.getEntry(opfPath) ?: return Meta()
        var title: String? = null
        var author: String? = null
        var coverId: String? = null
        var guideCover: String? = null
        data class Item(val id: String, val href: String, val type: String, val props: String)
        val items = mutableListOf<Item>()
        zip.getInputStream(opf).use { input ->
            val p = parser(input)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                when (p.name) {
                    "title" -> if (title == null) title = p.nextText().trim()
                    "creator" -> if (author == null) author = p.nextText().trim()
                    "meta" -> if (p.attr("name") == "cover") coverId = p.attr("content")
                    "item" -> items += Item(p.attr("id") ?: "", p.attr("href") ?: "", p.attr("media-type") ?: "", p.attr("properties") ?: "")
                    "reference" -> if (p.attr("type") == "cover") guideCover = p.attr("href")
                }
            }
        }
        val images = items.filter { it.type.startsWith("image/") }
        val cover = images.firstOrNull { "cover-image" in it.props }
            ?: images.firstOrNull { it.id == coverId }
            ?: items.firstOrNull { it.id == coverId && it.href.isNotEmpty() && Formats.isImage(it.href) }
            ?: guideCover?.takeIf { Formats.isImage(it) }?.let { Item("", it, "image/", "") }
            ?: images.firstOrNull { "cover" in it.id.lowercase() || "cover" in it.href.lowercase() }
        val bytes = cover?.let { text(resolve(opfPath, it.href)) }
        return Meta(title, author, bytes)
    }

    private fun resolve(base: String, href: String): String {
        val parts = (base.substringBeforeLast('/', "").split('/') + Uri.decode(href.substringBefore('#')).split('/'))
        val out = ArrayDeque<String>()
        for (s in parts) when (s) { "", "." -> {}; ".." -> out.removeLastOrNull(); else -> out.addLast(s) }
        return out.joinToString("/")
    }

    // ---- FB2: title-info + base64 coverpage binary ----
    private fun fb2(input: InputStream): Meta {
        val p = parser(input)
        var inTitleInfo = false
        var inCoverpage = false
        var title: String? = null
        var author: String? = null
        var coverId: String? = null
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "title-info" -> inTitleInfo = true
                    "book-title" -> if (inTitleInfo && title == null) title = p.nextText().trim()
                    "author" -> if (inTitleInfo && author == null) {
                        val names = mutableListOf<String>()
                        while (true) {
                            val ev = p.next()
                            if (ev == XmlPullParser.END_DOCUMENT || (ev == XmlPullParser.END_TAG && p.name == "author")) break
                            if (p.eventType == XmlPullParser.START_TAG && p.name in setOf("first-name", "middle-name", "last-name", "nickname"))
                                p.nextText().trim().takeIf { it.isNotEmpty() }?.let(names::add)
                        }
                        author = names.joinToString(" ")
                    }
                    "coverpage" -> inCoverpage = inTitleInfo
                    "image" -> if (inCoverpage && coverId == null) {
                        coverId = (0 until p.attributeCount).firstNotNullOfOrNull { i ->
                            p.getAttributeValue(i).takeIf { p.getAttributeName(i).endsWith("href") }
                        }?.removePrefix("#")
                    }
                    "body" -> if (coverId == null) return Meta(title, author)
                    "binary" -> if (p.attr("id") == coverId) {
                        val bytes = runCatching { Base64.decode(p.nextText(), Base64.DEFAULT) }.getOrNull()
                        return Meta(title, author, bytes)
                    }
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "title-info" -> inTitleInfo = false
                    "coverpage" -> inCoverpage = false
                }
            }
        }
        return Meta(title, author)
    }

    // ---- MOBI / AZW3: PalmDB -> MOBI header -> EXTH (100 author, 503 title, 201 cover offset) ----
    private fun mobi(ch: SeekableByteChannel): Meta {
        fun read(pos: Long, len: Int): ByteBuffer {
            val b = ByteBuffer.allocate(len.coerceAtLeast(0))
            ch.position(pos)
            while (b.hasRemaining() && ch.read(b) > 0) Unit
            b.flip()
            return b
        }
        fun str(b: ByteBuffer, at: Int, len: Int, cs: Charset = Charsets.ISO_8859_1): String {
            val arr = ByteArray(len); b.position(at); b.get(arr); return String(arr, cs)
        }
        val head = read(0, 78)
        if (head.limit() < 78 || str(head, 60, 8) !in setOf("BOOKMOBI", "TEXtREAd")) return Meta()
        val n = head.getShort(76).toInt() and 0xffff
        val table = read(78, n * 8)
        val offsets = LongArray(n) { table.getInt(it * 8).toLong() and 0xffffffffL }
        val size = ch.size()
        fun record(i: Int): ByteBuffer {
            val end = if (i + 1 < n) offsets[i + 1] else size
            return read(offsets[i], (end - offsets[i]).toInt())
        }
        val r0 = record(0)
        if (r0.limit() < 132 || str(r0, 16, 4) != "MOBI") return Meta()
        val mobiLen = r0.getInt(20)
        val cs = if (r0.getInt(28) == 65001) Charsets.UTF_8 else Charset.forName("windows-1252")
        val nameOff = r0.getInt(84)
        val nameLen = r0.getInt(88)
        val firstImage = r0.getInt(108)
        var title = if (nameOff > 0 && nameOff + nameLen <= r0.limit()) str(r0, nameOff, nameLen, cs) else null
        var author: String? = null
        var coverOffset = -1
        if (r0.getInt(128) and 0x40 != 0) {
            var p = 16 + mobiLen
            if (p + 12 <= r0.limit() && str(r0, p, 4) == "EXTH") {
                val count = r0.getInt(p + 8)
                p += 12
                repeat(count) {
                    if (p + 8 > r0.limit()) return@repeat
                    val type = r0.getInt(p)
                    val len = r0.getInt(p + 4)
                    if (len < 8 || p + len > r0.limit()) return@repeat
                    when (type) {
                        100 -> author = (author?.let { "$it, " } ?: "") + str(r0, p + 8, len - 8, cs).trim()
                        503 -> title = str(r0, p + 8, len - 8, cs).trim()
                        201 -> coverOffset = r0.getInt(p + 8)
                    }
                    p += len
                }
            }
        }
        val coverIndex = firstImage + coverOffset
        val cover = if (coverOffset >= 0 && firstImage > 0 && coverIndex < n) record(coverIndex).let { b ->
            ByteArray(b.remaining()).also { b.get(it) }
        } else null
        return Meta(title, author, cover)
    }

    // ---- PDF: first page render ----
    private fun pdf(ctx: Context, uri: Uri, key: String): Meta {
        val pfd = Io.seekableFd(ctx, uri)
            ?: android.os.ParcelFileDescriptor.open(Io.localFile(ctx, uri, key, "pdf"), android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        try {
            if (renderer.pageCount == 0) return Meta(hasPages = false)
            val page = renderer.openPage(0)
            try {
                val w = Io.COVER_W
                val h = (w.toLong() * page.height / page.width.coerceAtLeast(1)).toInt().coerceIn(1, w * 3)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return Meta(coverBitmap = bmp)
            } finally { page.close() }
        } finally { renderer.close() }
    }

    private fun parser(input: InputStream): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setInput(input, null)
    }

    private fun XmlPullParser.attr(name: String): String? =
        (0 until attributeCount).firstNotNullOfOrNull { i -> getAttributeValue(i).takeIf { getAttributeName(i) == name } }
}
