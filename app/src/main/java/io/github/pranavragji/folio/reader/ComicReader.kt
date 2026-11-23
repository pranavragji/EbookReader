package io.github.pranavragji.folio.reader

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import io.github.pranavragji.folio.app
import io.github.pranavragji.folio.data.Settings
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import java.io.File
import kotlin.math.roundToInt

/** One page image for [ComicReader]: a remote URL (online sources) or a local file (archives, downloads). */
sealed interface PageSource {
    data class Url(val url: String, val headers: Map<String, String> = emptyMap()) : PageSource
    data class Local(val file: File) : PageSource
}

internal fun pageRequest(ctx: Context, page: PageSource): ImageRequest =
    ImageRequest.Builder(ctx)
        .data(
            when (page) {
                is PageSource.Url -> page.url
                is PageSource.Local -> page.file
            }
        )
        .apply {
            if (page is PageSource.Url && page.headers.isNotEmpty())
                httpHeaders(NetworkHeaders.Builder().apply { page.headers.forEach { (k, v) -> set(k, v) } }.build())
        }
        .build()

/**
 * Manga / comic / webtoon reader. Modes come from Settings.comicMode: rtl, ltr, vertical (paged), webtoon (strip).
 * Used for local archives and, by the sources feature, for online chapters (PageSource.Url + headers).
 */
@Composable
fun ComicReader(
    title: String,
    pages: List<PageSource>,
    startPage: Int,
    onPageChange: (page: Int, total: Int) -> Unit,
    onBack: () -> Unit,
    subtitle: String? = null,
    onNextChapter: (() -> Unit)? = null,
    onPrevChapter: (() -> Unit)? = null,
) {
    val s = app.prefs.settings.collectAsStateWithLifecycle(initialValue = null).value
    when {
        s == null -> Box(Modifier.fillMaxSize().background(Color.Black))
        pages.isEmpty() -> ReaderMessage("This chapter has no pages.", onBack)
        else -> key(pages) {
            ComicPages(title, pages, startPage, onPageChange, onBack, subtitle, onNextChapter, onPrevChapter, s)
        }
    }
}

@Composable
private fun ComicPages(
    title: String,
    pages: List<PageSource>,
    startPage: Int,
    onPageChange: (Int, Int) -> Unit,
    onBack: () -> Unit,
    subtitle: String?,
    onNextChapter: (() -> Unit)?,
    onPrevChapter: (() -> Unit)?,
    s: Settings,
) {
    val mode = s.comicMode
    val rtl = mode == "rtl"
    val webtoon = mode == "webtoon"
    val total = pages.size
    val scope = rememberCoroutineScope()
    var chrome by remember { mutableStateOf(false) }
    var current by remember { mutableIntStateOf(startPage.coerceIn(0, total - 1)) }
    var size by remember { mutableStateOf(IntSize(1, 1)) }
    val pager = rememberPagerState(initialPage = current) { total }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = current)
    val onChange by rememberUpdatedState(onPageChange)
    val onNext by rememberUpdatedState(onNextChapter)
    val onPrev by rememberUpdatedState(onPrevChapter)

    LaunchedEffect(mode) {
        // After a mode switch, put the new navigator where the old one was.
        if (webtoon) list.scrollToItem(current) else pager.scrollToPage(current)
        snapshotFlow { if (webtoon) list.firstVisibleItemIndex else pager.currentPage }
            .distinctUntilChanged()
            .collect { current = it; onChange(it, total) }
    }

    fun goTo(p: Int) {
        val t = p.coerceIn(0, total - 1)
        scope.launch { if (webtoon) list.animateScrollToItem(t) else pager.animateScrollToPage(t) }
    }
    fun next() {
        if (webtoon) {
            if (!list.canScrollForward) onNext?.invoke() else scope.launch { list.animateScrollBy(size.height * 0.85f) }
        } else if (current >= total - 1) onNext?.invoke() else goTo(current + 1)
    }
    fun prev() {
        if (webtoon) {
            if (!list.canScrollBackward) onPrev?.invoke() else scope.launch { list.animateScrollBy(-size.height * 0.85f) }
        } else if (current <= 0) onPrev?.invoke() else goTo(current - 1)
    }
    fun tap(x: Float) {
        if (chrome) { chrome = false; return }
        val z = x / size.width.coerceAtLeast(1)
        when {
            z < 0.3f -> if (rtl) next() else prev()
            z > 0.7f -> if (rtl) prev() else next()
            else -> chrome = true
        }
    }

    ReaderSystem(chrome, s.keepScreenOn)
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onSizeChanged { size = it }
            .readerKeys(s.volumeKeys, onNext = { next() }, onPrev = { prev() })
    ) {
        when (mode) {
            "webtoon" -> LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().pointerInput(mode) { detectTapGestures { tap(it.x) } },
            ) {
                items(total) { i -> WebtoonPage(pages[i]) }
            }
            "vertical" -> VerticalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 2) { i ->
                PagedImage(pages[i], i) { tap(it.x) }
            }
            else -> HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 2, reverseLayout = rtl) { i ->
                PagedImage(pages[i], i) { tap(it.x) }
            }
        }

        var drag by remember { mutableStateOf<Float?>(null) }
        ReaderChrome(
            visible = chrome,
            title = title,
            subtitle = subtitle,
            onBack = onBack,
            actions = { ModeMenu(mode) },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onPrevChapter != null) IconButton(onClick = onPrevChapter) { Icon(Icons.Filled.SkipPrevious, "Previous chapter") }
                CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    Slider(
                        value = drag ?: current.toFloat(),
                        onValueChange = { drag = it },
                        valueRange = 0f..(total - 1).coerceAtLeast(1).toFloat(),
                        onValueChangeFinished = { drag?.let { goTo(it.roundToInt()) }; drag = null },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (onNextChapter != null) IconButton(onClick = onNextChapter) { Icon(Icons.Filled.SkipNext, "Next chapter") }
            }
            Text(
                "${(drag?.roundToInt() ?: current) + 1} / $total",
                Modifier.align(Alignment.CenterHorizontally),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun PagedImage(page: PageSource, index: Int, onTap: (Offset) -> Unit) {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("${index + 1}", color = Color.White.copy(alpha = 0.25f), style = MaterialTheme.typography.headlineMedium)
        ZoomableAsyncImage(
            model = remember(page) { pageRequest(ctx, page) },
            contentDescription = "Page ${index + 1}",
            modifier = Modifier.fillMaxSize(),
            state = rememberZoomableImageState(rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 4f))),
            onClick = onTap,
        )
    }
}

@Composable
private fun WebtoonPage(page: PageSource) {
    val ctx = LocalContext.current
    var ratio by remember(page) { mutableStateOf<Float?>(null) }
    AsyncImage(
        model = remember(page) { pageRequest(ctx, page) },
        contentDescription = null,
        contentScale = ContentScale.FillWidth,
        modifier = Modifier.fillMaxWidth().then(ratio?.let { Modifier.aspectRatio(it) } ?: Modifier.height(480.dp)),
        onSuccess = { st -> ratio = st.result.image.width.toFloat() / st.result.image.height.coerceAtLeast(1) },
    )
}

@Composable
private fun ModeMenu(mode: String) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.AutoStories, "Reading mode") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((k, label) in listOf(
                "rtl" to "Right to left (manga)",
                "ltr" to "Left to right (comics)",
                "vertical" to "Vertical pages",
                "webtoon" to "Long strip (webtoon)",
            )) {
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { RadioButton(selected = k == mode, onClick = null) },
                    onClick = { open = false; updateSettings { it.copy(comicMode = k) } },
                )
            }
        }
    }
}
