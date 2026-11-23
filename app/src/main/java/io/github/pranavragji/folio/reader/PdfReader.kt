package io.github.pranavragji.folio.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.pranavragji.folio.app
import io.github.pranavragji.folio.data.Book
import io.github.pranavragji.folio.data.Settings
import io.github.pranavragji.folio.library.Io
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** PdfRenderer allows one open page at a time, so every access goes through [lock]. */
private class PdfDoc(private val pfd: ParcelFileDescriptor) {
    private val renderer = PdfRenderer(pfd)
    private val lock = Mutex()
    val count = renderer.pageCount
    val aspects: FloatArray = FloatArray(count) { i ->
        val p = renderer.openPage(i)
        try { p.width.toFloat() / p.height.coerceAtLeast(1) } finally { p.close() }
    }

    suspend fun render(i: Int, widthPx: Int): Bitmap = lock.withLock {
        withContext(Dispatchers.IO) {
            val p = renderer.openPage(i)
            try {
                // Cap at ~12 MP (48 MB) per page so zoomed renders of tall pages can't OOM.
                var w = widthPx.coerceIn(64, 4096)
                var h = (w / aspects[i]).roundToInt().coerceAtLeast(64)
                val px = w.toLong() * h
                if (px > 12_000_000L) {
                    val f = kotlin.math.sqrt(12_000_000.0 / px)
                    w = (w * f).toInt(); h = (h * f).toInt()
                }
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(AColor.WHITE)
                    p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            } finally { p.close() }
        }
    }

    fun close() {
        app.scope.launch { lock.withLock { renderer.close(); pfd.close() } }
    }

    companion object {
        fun open(ctx: Context, book: Book): PdfDoc {
            val uri = Uri.parse(book.uri)
            val pfd = Io.seekableFd(ctx, uri)
                ?: ParcelFileDescriptor.open(Io.localFile(ctx, uri, book.id, "pdf"), ParcelFileDescriptor.MODE_READ_ONLY)
            return try { PdfDoc(pfd) } catch (e: Exception) { pfd.close(); throw e }
        }
    }
}

@Composable
fun PdfReader(book: Book, s: Settings, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var doc by remember { mutableStateOf<PdfDoc?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(book.id) {
        runCatching { withContext(Dispatchers.IO) { PdfDoc.open(ctx, book) } }
            .onSuccess { doc = it }
            .onFailure { error = if (it is SecurityException) "This PDF is password-protected." else "Couldn't open this PDF.\n${it.message ?: ""}" }
    }
    DisposableEffect(doc) { onDispose { doc?.close() } }

    error?.let { ReaderMessage(it, onBack); return }
    val d = doc ?: run { ReaderLoading(Color(0xFF2B2B2E)); return }

    val start = (book.locator?.toIntOrNull() ?: 0).coerceIn(0, (d.count - 1).coerceAtLeast(0))
    val list = rememberLazyListState(initialFirstVisibleItemIndex = start)
    val scope = rememberCoroutineScope()
    var chrome by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    // Re-render in steps so zoomed text stays crisp without re-rendering every frame.
    val renderScale by remember { derivedStateOf { if (scale < 1.4f) 1f else if (scale < 2.4f) 2f else 2.75f } }
    val page by remember { derivedStateOf { list.firstVisibleItemIndex } }

    LaunchedEffect(d) {
        snapshotFlow { list.firstVisibleItemIndex }.distinctUntilChanged()
            .collect { saveProgress(book.id, (it + 1f) / d.count, it.toString()) }
    }
    ReaderSystem(chrome, s.keepScreenOn)

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color(0xFF2B2B2E))
            .readerKeys(s.volumeKeys,
                onNext = { scope.launch { list.animateScrollToItem((page + 1).coerceAtMost(d.count - 1)) } },
                onPrev = { scope.launch { list.animateScrollToItem((page - 1).coerceAtLeast(0)) } })
    ) {
        val widthPx = constraints.maxWidth
        val maxPan = (scale - 1f) * widthPx / 2f
        LazyColumn(
            state = list,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    // Two fingers zoom/pan (taken before the list scrolls); one finger pans sideways when zoomed.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            if (ev.changes.count { it.pressed } >= 2) {
                                scale = (scale * ev.calculateZoom()).coerceIn(1f, 4f)
                                val lim = (scale - 1f) * size.width / 2f
                                panX = (panX + ev.calculatePan().x).coerceIn(-lim, lim)
                                ev.changes.forEach { it.consume() }
                            } else if (scale > 1f) {
                                val lim = (scale - 1f) * size.width / 2f
                                panX = (panX + ev.calculatePan().x).coerceIn(-lim, lim)
                            }
                        } while (ev.changes.any { it.pressed })
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { chrome = !chrome },
                        onDoubleTap = { if (scale > 1f) { scale = 1f; panX = 0f } else scale = 2f },
                    )
                }
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = panX.coerceIn(-maxPan, maxPan) },
        ) {
            items(d.count) { i -> PdfPage(d, i, (widthPx * renderScale).roundToInt()) }
        }

        if (!chrome) Text(
            "${page + 1} / ${d.count}",
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp),
            color = Color.White, style = MaterialTheme.typography.labelMedium,
        )

        var drag by remember { mutableStateOf<Float?>(null) }
        ReaderChrome(visible = chrome, title = book.title, subtitle = book.author, onBack = onBack) {
            Slider(
                value = drag ?: page.toFloat(),
                onValueChange = { drag = it },
                valueRange = 0f..(d.count - 1).coerceAtLeast(1).toFloat(),
                onValueChangeFinished = { drag?.let { scope.launch { list.scrollToItem(it.roundToInt()) } }; drag = null },
            )
            Text("Page ${(drag?.roundToInt() ?: page) + 1} of ${d.count}",
                Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun PdfPage(doc: PdfDoc, index: Int, widthPx: Int) {
    // produceState keeps the previous bitmap on screen while a sharper one renders.
    val bmp by produceState<Bitmap?>(null, index, widthPx) { value = runCatching { doc.render(index, widthPx) }.getOrNull() }
    Box(Modifier.fillMaxWidth().aspectRatio(doc.aspects[index]).background(Color.White)) {
        bmp?.let { Image(it.asImageBitmap(), "Page ${index + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
    }
}
