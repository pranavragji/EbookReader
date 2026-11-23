package io.github.pranavragji.folio.source

import io.github.pranavragji.folio.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.IOException

val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

/** GET a URL as text on the IO dispatcher; throws on non-2xx. */
suspend fun fetchText(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
    val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
    app.http.newCall(req).execute().use { r ->
        if (!r.isSuccessful) throw IOException("HTTP ${r.code} for ${req.url.host}")
        r.body?.string() ?: ""
    }
}

/** Human message for an exception, for error states in the UI. */
fun Throwable.friendly(): String = when (this) {
    is java.net.UnknownHostException -> "You're offline"
    is java.net.SocketTimeoutException -> "The server took too long to answer"
    else -> message ?: javaClass.simpleName
}
