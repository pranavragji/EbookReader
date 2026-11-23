package io.github.pranavragji.folio.source

import android.net.Uri
import android.util.Xml
import androidx.compose.runtime.mutableStateMapOf
import io.github.pranavragji.folio.app
import io.github.pranavragji.folio.data.Book
import io.github.pranavragji.folio.data.OpdsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.net.URL
import java.net.URLEncoder

data class OpdsLink(val href: String, val type: String, val rel: String, val title: String = "")

data class OpdsEntry(
    val id: String,
    val title: String,
    val author: String,
    val summary: String,
    val links: List<OpdsLink>,
) {
    val thumbnail: String? get() =
        (links.firstOrNull { it.rel.endsWith("image/thumbnail") || it.rel.endsWith("thumbnail") }
            ?: links.firstOrNull { it.rel.endsWith("/image") || it.rel.endsWith("cover") })?.href

    val acquisitions: List<OpdsLink> get() =
        links.filter { it.rel.contains("acquisition") && !it.rel.endsWith("/buy") && !it.rel.endsWith("/subscribe") }

    /** Link to a sub-catalog, when this entry is a navigation entry. */
    val navigation: String? get() =
        if (acquisitions.isNotEmpty()) null
        else links.firstOrNull { it.type.contains("atom+xml") || it.type.contains("opds-catalog") }?.href

    /** The acquisition we'd download: epub first, then Kindle formats, pdf, comics, fb2. */
    val best: OpdsLink? get() = acquisitions.minByOrNull { l -> FORMATS.indexOfFirst { l.type.startsWith(it.first) }.let { if (it < 0) 99 else it } }
}

data class OpdsFeed(val title: String, val entries: List<OpdsEntry>, val next: String?, val search: String?)

/** mime prefix → file extension, in download preference order. */
internal val FORMATS = listOf(
    "application/epub+zip" to "epub",
    "application/x-mobi8-ebook" to "azw3",
    "application/vnd.amazon.ebook" to "azw3",
    "application/x-mobipocket-ebook" to "mobi",
    "application/pdf" to "pdf",
    "application/vnd.comicbook+zip" to "cbz",
    "application/x-cbz" to "cbz",
    "application/vnd.comicbook-rar" to "cbr",
    "application/x-cbr" to "cbr",
    "application/x-fictionbook+xml" to "fb2",
    "application/fb2+zip" to "fbz",
    "text/plain" to "txt",
)

object Opds {
    /** Basic auth only for the source's own host, so credentials never leak to linked third-party servers. */
    fun headers(src: OpdsSource, url: String): Map<String, String> {
        if (src.user.isBlank()) return emptyMap()
        val same = runCatching { URL(url).host.equals(URL(src.url).host, true) }.getOrDefault(false)
        return if (same) mapOf("Authorization" to Credentials.basic(src.user, src.pass)) else emptyMap()
    }

    suspend fun feed(src: OpdsSource, url: String): OpdsFeed {
        val feed = withContext(Dispatchers.Default) { parse(fetchText(url, headers(src, url)), url) }
        val search = feed.search ?: return feed
        // rel=search usually points at an OpenSearch description; resolve it to a {searchTerms} template.
        if (search.contains("{searchTerms}")) return feed
        val template = runCatching { openSearchTemplate(fetchText(search, headers(src, search)), search) }.getOrNull()
        return feed.copy(search = template)
    }

    fun searchUrl(template: String, query: String): String =
        template.replace("{searchTerms}", URLEncoder.encode(query, "UTF-8")).replace(Regex("\\{[^}]*\\?}"), "")

    fun parse(xml: String, base: String): OpdsFeed {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(StringReader(xml))
        var feedTitle = ""
        val feedLinks = ArrayList<OpdsLink>()
        val entries = ArrayList<OpdsEntry>()
        var inEntry = false; var inAuthor = false
        var id = ""; var title = ""; var summary = ""; val authors = ArrayList<String>(); val links = ArrayList<OpdsLink>()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "entry" -> { inEntry = true; id = ""; title = ""; summary = ""; authors.clear(); links.clear() }
                    "author" -> inAuthor = true
                    "name" -> if (inEntry && inAuthor) authors += readText(p)
                    "id" -> if (inEntry) id = readText(p)
                    "title" -> if (inEntry) title = readText(p) else if (feedTitle.isEmpty()) feedTitle = readText(p)
                    "summary", "content" -> if (inEntry) readText(p).let { if (summary.isEmpty() || p.name == "content") summary = it }
                    "link" -> {
                        val href = p.getAttributeValue(null, "href")
                        if (href != null) {
                            val l = OpdsLink(
                                resolve(base, href),
                                p.getAttributeValue(null, "type") ?: "",
                                p.getAttributeValue(null, "rel") ?: "",
                                p.getAttributeValue(null, "title") ?: "",
                            )
                            if (inEntry) links += l else feedLinks += l
                        }
                    }
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "author" -> inAuthor = false
                    "entry" -> {
                        inEntry = false
                        entries += OpdsEntry(id.ifBlank { links.firstOrNull()?.href ?: title }, title.trim(), authors.joinToString(), summary.trim(), links.toList())
                    }
                }
            }
        }
        return OpdsFeed(
            title = feedTitle.trim(),
            entries = entries,
            next = feedLinks.firstOrNull { it.rel == "next" }?.href,
            search = feedLinks.firstOrNull { it.rel == "search" }?.href,
        )
    }

    private fun openSearchTemplate(xml: String, base: String): String? {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(StringReader(xml))
        var fallback: String? = null
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "Url") {
                val t = p.getAttributeValue(null, "template") ?: continue
                val type = p.getAttributeValue(null, "type") ?: ""
                if (type.contains("atom")) return resolve(base, t)
                if (fallback == null) fallback = resolve(base, t)
            }
        }
        return fallback
    }

    /** All text inside the current element, child tags stripped (handles type="xhtml"/"html" content). */
    private fun readText(p: XmlPullParser): String {
        val depth = p.depth
        val sb = StringBuilder()
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.TEXT) sb.append(p.text)
            if ((ev == XmlPullParser.END_TAG && p.depth == depth) || ev == XmlPullParser.END_DOCUMENT) break
        }
        return sb.toString().replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
    }

    private fun resolve(base: String, href: String) =
        runCatching { URL(URL(base), href).toString() }.getOrDefault(href)

    /** Download state for the UI: acquisition href → 0..1 (or -1 while size unknown). */
    val downloads = mutableStateMapOf<String, Float>()

    /** Download an acquisition link into app.downloadsDir and index it. */
    suspend fun download(src: OpdsSource, entry: OpdsEntry, link: OpdsLink): Book? = withContext(Dispatchers.IO) {
        downloads[link.href] = -1f
        try {
            val req = Request.Builder().url(link.href).apply { headers(src, link.href).forEach { (k, v) -> header(k, v) } }.build()
            app.http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Download failed (HTTP ${r.code})")
                val body = r.body ?: throw IOException("Empty download")
                val ext = FORMATS.firstOrNull { link.type.startsWith(it.first) }?.second
                    ?: Uri.parse(link.href).lastPathSegment?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.length in 2..4 }
                    ?: "epub"
                val base = listOf(entry.title, entry.author.substringBefore(',')).filter { it.isNotBlank() }
                    .joinToString(" - ").replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "").take(120).ifBlank { "book" }
                var dest = File(app.downloadsDir, "$base.$ext")
                var n = 2
                while (dest.exists()) dest = File(app.downloadsDir, "$base ($n).$ext").also { n++ }
                val tmp = File(app.downloadsDir, ".${dest.name}.part")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val read = input.read(buf)
                            if (read < 0) break
                            out.write(buf, 0, read)
                            done += read
                            if (total > 0) downloads[link.href] = done.toFloat() / total
                        }
                    }
                }
                if (!tmp.renameTo(dest)) { tmp.delete(); throw IOException("Couldn't save the file") }
                app.scanner.import(dest)
            }
        } finally {
            downloads.remove(link.href)
        }
    }
}
