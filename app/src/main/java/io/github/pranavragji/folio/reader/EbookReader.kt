package io.github.pranavragji.folio.reader

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import io.github.pranavragji.folio.data.Book
import io.github.pranavragji.folio.data.Settings
import io.github.pranavragji.folio.library.Formats
import io.github.pranavragji.folio.library.Io
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

private data class TocItem(val label: String, val href: String, val depth: Int)

private sealed interface WebEvent {
    data class Ready(val json: String) : WebEvent
    data class Relocate(val cfi: String, val fraction: Float, val chapter: String) : WebEvent
    data class Tap(val zone: String) : WebEvent
    data class Error(val message: String) : WebEvent
    data class Link(val href: String) : WebEvent
}

/** JS -> Kotlin. Called on a WebView binder thread, so everything hops to main. */
private class Bridge(private val init: String, @Volatile var style: String, private val onEvent: (WebEvent) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private fun post(e: WebEvent) { main.post { onEvent(e) } }
    @JavascriptInterface fun getInit(): String = init
    @JavascriptInterface fun getStyle(): String = style
    @JavascriptInterface fun onReady(json: String) = post(WebEvent.Ready(json))
    @JavascriptInterface fun onRelocate(cfi: String, fraction: Double, chapter: String) = post(WebEvent.Relocate(cfi, fraction.toFloat(), chapter))
    @JavascriptInterface fun onTap(zone: String) = post(WebEvent.Tap(zone))
    @JavascriptInterface fun onError(message: String) = post(WebEvent.Error(message))
    @JavascriptInterface fun openLink(href: String) = post(WebEvent.Link(href))
}

private fun styleJson(s: Settings, c: ReaderColors) = buildJsonObject {
    put("theme", c.name); put("dark", c.dark)
    put("bg", c.bg.hex()); put("fg", c.fg.hex()); put("link", c.link.hex())
    put("fontScale", s.fontScale); put("fontFamily", s.fontFamily); put("lineHeight", s.lineHeight)
    put("margin", s.margin); put("justify", s.justify); put("paginated", s.paginated)
}.toString()

/** EPUB, MOBI/AZW3 and FB2/FBZ, rendered by foliate-js inside a WebView. */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun EbookReader(book: Book, s: Settings, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val colors = readerColors(s.readerTheme, isSystemInDarkTheme())
    val style = styleJson(s, colors)

    var chrome by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var fraction by remember { mutableFloatStateOf(book.progress) }
    var chapter by remember { mutableStateOf("") }
    var toc by remember { mutableStateOf(emptyList<TocItem>()) }
    var sheet by remember { mutableStateOf<String?>(null) } // "toc" | "style"
    val chromeState = rememberUpdatedState(chrome)

    val web = remember { WebView(ctx) }
    fun js(code: String) = web.evaluateJavascript(code, null)

    val bridge = remember {
        val init = buildJsonObject {
            put("name", "book.${book.format}")
            put("type", Formats.mime(book.format))
            put("cfi", book.locator?.takeIf { it.startsWith("epubcfi(") } ?: "")
        }.toString()
        Bridge(init, style) { e ->
            when (e) {
                is WebEvent.Ready -> {
                    ready = true
                    toc = runCatching {
                        Json.parseToJsonElement(e.json).jsonObject["toc"]!!.jsonArray.map {
                            val o = it.jsonObject
                            TocItem(o["label"]!!.jsonPrimitive.content, o["href"]!!.jsonPrimitive.content, o["depth"]!!.jsonPrimitive.int)
                        }
                    }.getOrDefault(emptyList())
                }
                is WebEvent.Relocate -> {
                    ready = true
                    fraction = e.fraction
                    chapter = e.chapter
                    if (e.cfi.isNotEmpty()) saveProgress(book.id, e.fraction, e.cfi)
                }
                is WebEvent.Tap -> when {
                    chromeState.value -> chrome = false
                    e.zone == "left" -> js("folio.left()")
                    e.zone == "right" -> js("folio.right()")
                    else -> chrome = true
                }
                is WebEvent.Error -> if (!ready) error = e.message
                is WebEvent.Link -> if (e.href.startsWith("http") || e.href.startsWith("mailto:"))
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(e.href))) }
            }
        }
    }

    remember {
        val bookUri = Uri.parse(book.uri)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
            .addPathHandler("/book/", WebViewAssetLoader.PathHandler {
                runCatching { WebResourceResponse(Formats.mime(book.format), null, Io.open(ctx, bookUri)) }.getOrNull()
            })
            .build()
        web.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = true
            isFocusable = false              // keep key focus in Compose for volume-key paging
            isFocusableInTouchMode = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = WebView.OVER_SCROLL_NEVER
            setBackgroundColor(colors.bg.toArgb())
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.isForMainFrame && request.url.host != "appassets.androidplatform.net"
            }
            addJavascriptInterface(bridge, "Android")
            loadUrl("https://appassets.androidplatform.net/assets/reader.html")
        }
        Unit
    }

    LaunchedEffect(style) {
        bridge.style = style
        web.setBackgroundColor(colors.bg.toArgb())
        if (ready) js("folio.setStyle($style)")
    }
    DisposableEffect(Unit) {
        onDispose {
            (web.parent as? ViewGroup)?.removeView(web)
            web.stopLoading()
            web.destroy()
        }
    }
    ReaderSystem(chrome || sheet != null, s.keepScreenOn, lightBars = !colors.dark)

    Box(
        Modifier.fillMaxSize().background(colors.bg)
            .readerKeys(s.volumeKeys, onNext = { js("folio.next()") }, onPrev = { js("folio.prev()") })
    ) {
        AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
        AnimatedVisibility(!ready && error == null, exit = fadeOut()) { ReaderLoading(colors.bg) }
        error?.let { ReaderMessage("Couldn't open this book.\n$it", onBack) }

        var drag by remember { mutableStateOf<Float?>(null) }
        ReaderChrome(
            visible = chrome && error == null,
            title = book.title,
            subtitle = chapter.ifBlank { book.author },
            onBack = onBack,
            actions = {
                if (toc.isNotEmpty()) IconButton(onClick = { sheet = "toc" }) { Icon(Icons.AutoMirrored.Filled.List, "Contents") }
                IconButton(onClick = { sheet = "style" }) { Icon(Icons.Filled.FormatSize, "Text settings") }
            },
        ) {
            Slider(
                value = drag ?: fraction,
                onValueChange = { drag = it },
                onValueChangeFinished = { drag?.let { js("folio.goToFraction($it)") }; drag = null },
            )
            Row(Modifier.fillMaxWidth()) {
                Text(chapter, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${((drag ?: fraction) * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
            }
        }
    }

    if (sheet != null) {
        ModalBottomSheet(onDismissRequest = { sheet = null }) {
            if (sheet == "toc") {
                LazyColumn {
                    itemsIndexed(toc) { _, t ->
                        Text(
                            t.label.ifBlank { "Untitled" },
                            Modifier.fillMaxWidth()
                                .clickable { js("folio.goTo(${JsonPrimitive(t.href)})"); sheet = null; chrome = false }
                                .padding(start = (24 + 16 * t.depth).dp, end = 24.dp, top = 14.dp, bottom = 14.dp),
                            style = if (t.depth == 0) MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
                            else MaterialTheme.typography.bodyMedium,
                            color = if (t.label == chapter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            } else ReaderStyleControls(s, showLayout = true)
        }
    }
}
