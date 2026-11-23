package io.github.pranavragji.folio.sync

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import io.github.pranavragji.folio.source.json
import okhttp3.Credentials
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class RemoteBook(val id: String, val name: String, val size: Long)

/** A place that stores folio-sync.json and the uploaded-books folder. Two implementations: Drive and WebDAV. */
interface Remote {
    suspend fun readSync(): String?
    suspend fun writeSync(content: String)
    suspend fun listBooks(): List<RemoteBook>
    suspend fun upload(name: String, size: Long, mime: String, open: () -> InputStream)
    suspend fun download(book: RemoteBook, dest: File)
}

const val SYNC_FILE = "folio-sync.json"
private val JSON_MIME = "application/json; charset=utf-8".toMediaType()

private fun streamBody(type: MediaType, size: Long, open: () -> InputStream) = object : RequestBody() {
    override fun contentType() = type
    override fun contentLength() = size
    override fun writeTo(sink: BufferedSink) { open().source().use { sink.writeAll(it) } }
}

private suspend fun OkHttpClient.call(req: Request): Response = withContext(Dispatchers.IO) { newCall(req).execute() }

private fun Response.ensure(what: String): Response {
    if (!isSuccessful) { val c = code; close(); throw IOException("$what failed (HTTP $c)") }
    return this
}

private suspend fun Response.saveTo(dest: File) = withContext(Dispatchers.IO) {
    use { r ->
        val tmp = File(dest.parentFile, ".${dest.name}.part")
        r.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
        if (!tmp.renameTo(dest)) { tmp.delete(); throw IOException("Couldn't save ${dest.name}") }
        Unit
    }
}

// ---------------------------------------------------------------- Google

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { c.resume(it) }
    addOnFailureListener { c.resumeWithException(it) }
}

object GoogleAuth {
    private val request: AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(
            Scope("https://www.googleapis.com/auth/drive.appdata"),
            Scope("https://www.googleapis.com/auth/drive.file"),
        ))
        .build()

    /** May need UI: check [AuthorizationResult.hasResolution] and launch its pendingIntent. */
    suspend fun authorize(ctx: Context): AuthorizationResult = Identity.getAuthorizationClient(ctx).authorize(request).await()

    /** Background token: silent once the user granted access. */
    suspend fun token(ctx: Context): String {
        val r = authorize(ctx)
        if (r.hasResolution()) throw IOException("Google Drive access expired. Reconnect in the Sync tab.")
        return r.accessToken ?: throw IOException("Google didn't return an access token")
    }

    fun explain(t: Throwable): String = when {
        t.message?.contains("UNREGISTERED_ON_API_CONSOLE") == true ->
            "Google doesn't recognise this app yet. A newly created OAuth client can take up to a few hours " +
                "to activate; otherwise check its package name and SHA-1 (see README)."
        else -> explainCode(t)
    }

    private fun explainCode(t: Throwable): String = when ((t as? ApiException)?.statusCode) {
        CommonStatusCodes.DEVELOPER_ERROR ->
            "Google sign-in isn't set up for this build. Register an Android OAuth client for " +
                "io.github.pranavragji.folio with the release SHA-1 (see README), or use WebDAV."
        CommonStatusCodes.NETWORK_ERROR -> "You're offline"
        CommonStatusCodes.CANCELED, 16 -> "Sign-in cancelled"
        else -> t.message ?: "Google sign-in failed"
    }
}

class DriveRemote(private val http: OkHttpClient, private val token: suspend () -> String) : Remote {
    private val api = "https://www.googleapis.com/drive/v3"
    private val upload = "https://www.googleapis.com/upload/drive/v3"
    private var folderId: String? = null

    private suspend fun req(url: String) = Request.Builder().url(url).header("Authorization", "Bearer ${token()}")

    private suspend fun getJson(url: String): JsonObject =
        http.call(req(url).build()).ensure("Drive").use { json.parseToJsonElement(it.body!!.string()) as JsonObject }

    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")

    private suspend fun find(query: String, spaces: String = "drive", fields: String = "files(id,name,size)"): List<JsonObject> =
        (getJson("$api/files?spaces=$spaces&pageSize=1000&fields=${q(fields)}&q=${q(query)}")["files"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject } ?: emptyList()

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    suspend fun email(): String =
        ((getJson("$api/about?fields=user(emailAddress)")["user"] as? JsonObject)?.s("emailAddress")) ?: "Google Drive"

    private suspend fun syncFileId() =
        find("name='$SYNC_FILE' and trashed=false", spaces = "appDataFolder").firstOrNull()?.s("id")

    override suspend fun readSync(): String? {
        val id = syncFileId() ?: return null
        return http.call(req("$api/files/$id?alt=media").build()).ensure("Drive read").use { it.body!!.string() }
    }

    override suspend fun writeSync(content: String) {
        val id = syncFileId()
        if (id != null) {
            http.call(req("$upload/files/$id?uploadType=media").patch(content.toRequestBody(JSON_MIME)).build()).ensure("Drive write").close()
        } else {
            val meta = buildJsonObject { put("name", SYNC_FILE); put("parents", buildJsonArray { add(JsonPrimitive("appDataFolder")) }) }
            multipart(meta, content.toRequestBody(JSON_MIME))
        }
    }

    private suspend fun multipart(meta: JsonObject, media: RequestBody) {
        val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(meta.toString().toRequestBody(JSON_MIME))
            .addPart(media)
            .build()
        http.call(req("$upload/files?uploadType=multipart&fields=id").post(body).build()).ensure("Drive upload").close()
    }

    private suspend fun folder(): String {
        folderId?.let { return it }
        val existing = find("name='Folio' and mimeType='application/vnd.google-apps.folder' and trashed=false", fields = "files(id)")
            .firstOrNull()?.s("id")
        val id = existing ?: run {
            val meta = buildJsonObject { put("name", "Folio"); put("mimeType", "application/vnd.google-apps.folder") }
            http.call(req("$api/files?fields=id").post(meta.toString().toRequestBody(JSON_MIME)).build()).ensure("Drive folder")
                .use { (json.parseToJsonElement(it.body!!.string()) as JsonObject).s("id")!! }
        }
        return id.also { folderId = it }
    }

    override suspend fun listBooks(): List<RemoteBook> =
        find("'${folder()}' in parents and trashed=false").map {
            RemoteBook(it.s("id")!!, it.s("name") ?: "book", (it["size"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0)
        }.sortedBy { it.name.lowercase() }

    override suspend fun upload(name: String, size: Long, mime: String, open: () -> InputStream) {
        val meta = buildJsonObject { put("name", name); put("parents", buildJsonArray { add(JsonPrimitive(folder())) }) }
        multipart(meta, streamBody(mime.toMediaType(), size, open))
    }

    override suspend fun download(book: RemoteBook, dest: File) =
        http.call(req("$api/files/${book.id}?alt=media").build()).ensure("Drive download").saveTo(dest)
}

// ---------------------------------------------------------------- WebDAV

class WebDavRemote(private val http: OkHttpClient, url: String, user: String, pass: String) : Remote {
    private val root = url.trim().trimEnd('/') + "/Folio/"
    private val books = root + "Books/"
    private val auth = Credentials.basic(user, pass)
    private var ready = false

    private fun req(url: String) = Request.Builder().url(url).header("Authorization", auth)

    private suspend fun ensureDirs() {
        if (ready) return
        for (dir in listOf(root, books)) {
            http.call(req(dir).method("MKCOL", null).build()).use { r ->
                // 201 created, 405 already exists; anything else is a real problem (bad URL / auth).
                if (!r.isSuccessful && r.code != 405) throw IOException(
                    if (r.code == 401) "WebDAV login rejected" else "WebDAV folder failed (HTTP ${r.code})"
                )
            }
        }
        ready = true
    }

    /** Used by the "Test connection" button. */
    suspend fun test() { ensureDirs(); listBooks() }

    override suspend fun readSync(): String? {
        ensureDirs()
        return http.call(req(root + SYNC_FILE).build()).use { r ->
            when {
                r.code == 404 -> null
                !r.isSuccessful -> throw IOException("WebDAV read failed (HTTP ${r.code})")
                else -> r.body!!.string()
            }
        }
    }

    override suspend fun writeSync(content: String) {
        ensureDirs()
        http.call(req(root + SYNC_FILE).put(content.toRequestBody(JSON_MIME)).build()).ensure("WebDAV write").close()
    }

    override suspend fun listBooks(): List<RemoteBook> {
        ensureDirs()
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:getcontentlength/><d:resourcetype/></d:prop></d:propfind>"""
        val xml = http.call(
            req(books).header("Depth", "1").method("PROPFIND", body.toRequestBody("application/xml".toMediaType())).build()
        ).ensure("WebDAV list").use { it.body!!.string() }
        return parsePropfind(xml).sortedBy { it.name.lowercase() }
    }

    private fun parsePropfind(xml: String): List<RemoteBook> {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(StringReader(xml))
        val out = ArrayList<RemoteBook>()
        var href = ""; var size = 0L; var dir = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG) when (p.name) {
                "response" -> { href = ""; size = 0; dir = false }
                "href" -> href = p.nextText().trim()
                "getcontentlength" -> size = p.nextText().trim().toLongOrNull() ?: 0
                "collection" -> dir = true
            } else if (p.eventType == XmlPullParser.END_TAG && p.name == "response" && !dir && href.isNotEmpty()) {
                val name = Uri.decode(href.trimEnd('/').substringAfterLast('/'))
                out += RemoteBook(name, name, size)
            }
        }
        return out
    }

    override suspend fun upload(name: String, size: Long, mime: String, open: () -> InputStream) {
        ensureDirs()
        http.call(req(books + Uri.encode(name)).put(streamBody(mime.toMediaType(), size, open)).build()).ensure("WebDAV upload").close()
    }

    override suspend fun download(book: RemoteBook, dest: File) =
        http.call(req(books + Uri.encode(book.name)).build()).ensure("WebDAV download").saveTo(dest)
}

