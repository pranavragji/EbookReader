package io.github.pranavragji.folio.source

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URLEncoder

data class Manga(
    val id: String,
    val title: String,
    val cover: String?,
    val author: String = "",
    val description: String = "",
    val status: String = "",
    val tags: List<String> = emptyList(),
)

data class Chapter(val id: String, val name: String, val group: String, val publishedAt: String)

data class Paged<T>(val items: List<T>, val hasMore: Boolean)

/** MangaDex public API (no key). https://api.mangadex.org/docs */
object MangaDex {
    const val ID = "mangadex"
    const val NAME = "MangaDex"
    private const val API = "https://api.mangadex.org"
    private const val PAGE = 30
    private const val RATING = "&contentRating[]=safe&contentRating[]=suggestive"

    // ponytail: one global spacing of ~4.5 req/s (API limit is 5/s); per-endpoint buckets if we ever need more.
    private val gate = Mutex()
    private var last = 0L

    private suspend fun get(path: String): JsonObject {
        gate.withLock {
            val wait = 220 - (System.currentTimeMillis() - last)
            if (wait > 0) delay(wait)
            last = System.currentTimeMillis()
        }
        return json.parseToJsonElement(fetchText(API + path)) as JsonObject
    }

    private const val LIST = "/manga?limit=$PAGE&includes[]=cover_art&includes[]=author&availableTranslatedLanguage[]=en$RATING"

    suspend fun popular(offset: Int) = list("$LIST&offset=$offset&order[followedCount]=desc")
    suspend fun latest(offset: Int) = list("$LIST&offset=$offset&order[latestUploadedChapter]=desc")
    suspend fun search(query: String, offset: Int) =
        list("$LIST&offset=$offset&order[relevance]=desc&title=${URLEncoder.encode(query, "UTF-8")}")

    private suspend fun list(path: String): Paged<Manga> {
        val o = get(path)
        val items = o.arr("data").map { parseManga(it as JsonObject) }
        val total = o.int("total"); val offset = o.int("offset")
        return Paged(items, offset + items.size < total)
    }

    suspend fun details(id: String): Manga =
        parseManga(get("/manga/$id?includes[]=cover_art&includes[]=author&includes[]=artist")["data"] as JsonObject)

    private val chapterCache = HashMap<String, List<Chapter>>()

    /** Every readable English chapter, newest first. Cached per title for next/prev navigation. */
    suspend fun allChapters(mangaId: String, refresh: Boolean = false): List<Chapter> {
        if (!refresh) chapterCache[mangaId]?.let { return it }
        val out = ArrayList<Chapter>()
        var offset = 0
        while (offset < 5000) {
            val o = get("/manga/$mangaId/feed?limit=500&offset=$offset&translatedLanguage[]=en" +
                "&order[volume]=desc&order[chapter]=desc&includes[]=scanlation_group" +
                "&contentRating[]=safe&contentRating[]=suggestive&contentRating[]=erotica")
            val data = o.arr("data")
            data.forEach { el -> parseChapter(el as JsonObject)?.let(out::add) }
            offset += data.size
            if (data.isEmpty() || offset >= o.int("total")) break
        }
        return out.also { chapterCache[mangaId] = it }
    }

    /** Image URLs for one chapter via the MangaDex@Home network. */
    suspend fun pages(chapterId: String, dataSaver: Boolean = false): List<String> {
        val o = get("/at-home/server/$chapterId")
        val base = o.str("baseUrl") ?: error("No image server for this chapter")
        val ch = o["chapter"] as JsonObject
        val hash = ch.str("hash")
        val (dir, files) = if (dataSaver) "data-saver" to ch.arr("dataSaver") else "data" to ch.arr("data")
        return files.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.map { "$base/$dir/$hash/$it" }
    }

    private fun parseManga(o: JsonObject): Manga {
        val id = o.str("id")!!
        val a = o["attributes"] as? JsonObject ?: JsonObject(emptyMap())
        val rels = o.arr("relationships").mapNotNull { it as? JsonObject }
        val file = rels.firstOrNull { it.str("type") == "cover_art" }?.let { (it["attributes"] as? JsonObject)?.str("fileName") }
        val authors = rels.filter { it.str("type") == "author" || it.str("type") == "artist" }
            .mapNotNull { (it["attributes"] as? JsonObject)?.str("name") }.distinct().joinToString()
        val titles = a["title"] as? JsonObject
        val altEn = a.arr("altTitles").firstNotNullOfOrNull { ((it as? JsonObject)?.get("en") as? JsonPrimitive)?.contentOrNull }
        val title = (titles?.get("en") as? JsonPrimitive)?.contentOrNull ?: altEn ?: localized(titles)
        return Manga(
            id = id,
            title = title.ifBlank { "Untitled" },
            cover = file?.let { "https://uploads.mangadex.org/covers/$id/$it.512.jpg" },
            author = authors,
            description = localized(a["description"]).trim(),
            status = a.str("status")?.replaceFirstChar { it.uppercase() } ?: "",
            tags = a.arr("tags").mapNotNull { t -> localized(((t as? JsonObject)?.get("attributes") as? JsonObject)?.get("name")).ifBlank { null } },
        )
    }

    private fun parseChapter(o: JsonObject): Chapter? {
        val a = o["attributes"] as? JsonObject ?: return null
        if (a.str("externalUrl") != null || (a["pages"] as? JsonPrimitive)?.intOrNull == 0) return null
        val vol = a.str("volume"); val num = a.str("chapter"); val t = a.str("title")
        val name = buildString {
            if (vol != null) append("Vol. $vol ")
            append(if (num != null) "Ch. $num" else "Oneshot")
            if (!t.isNullOrBlank()) append(" · $t")
        }
        val group = o.arr("relationships").mapNotNull { it as? JsonObject }
            .firstOrNull { it.str("type") == "scanlation_group" }?.let { (it["attributes"] as? JsonObject)?.str("name") } ?: ""
        return Chapter(o.str("id")!!, name, group, a.str("publishAt")?.take(10) ?: "")
    }

    private fun localized(e: JsonElement?): String {
        val m = e as? JsonObject ?: return ""
        return ((m["en"] ?: m.values.firstOrNull()) as? JsonPrimitive)?.contentOrNull ?: ""
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull ?: 0
    private fun JsonObject.arr(k: String): List<JsonElement> = (this[k] as? JsonArray) ?: emptyList()
}
