package io.github.pranavragji.folio.reader

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.text.HtmlCompat
import io.github.pranavragji.folio.data.Book
import io.github.pranavragji.folio.data.Settings
import io.github.pranavragji.folio.library.Io
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlin.math.roundToInt

private fun loadParagraphs(ctx: Context, book: Book): List<String> {
    val bytes = Io.open(ctx, Uri.parse(book.uri)).use { it.readBytes() }
    var text = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        String(bytes, Charset.forName("windows-1252"))
    }.removePrefix("\uFEFF")
    if (book.format == "html") text = HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
    text = text.replace("\r\n", "\n")
    val lines = text.count { it == '\n' } + 1
    val blank = Regex("\n[ \t]*\n").findAll(text).count()
    // Hard-wrapped text (Gutenberg style) separates paragraphs with blank lines; otherwise one line = one paragraph.
    val paras = if (blank > lines / 10) text.split(Regex("\n[ \t]*\n+")).map { it.replace(Regex("\\s*\n\\s*"), " ") }
    else text.split('\n')
    return paras.map { it.trim() }.filter { it.isNotEmpty() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextReader(book: Book, s: Settings, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val colors = readerColors(s.readerTheme, isSystemInDarkTheme())
    val paragraphs by produceState<Result<List<String>>?>(null, book.id) {
        value = runCatching { withContext(Dispatchers.IO) { loadParagraphs(ctx, book) } }
    }
    val result = paragraphs ?: run { ReaderLoading(colors.bg); return }
    val paras = result.getOrElse { ReaderMessage("Couldn't open this file.\n${it.message ?: ""}", onBack); return }
    if (paras.isEmpty()) { ReaderMessage("This file is empty.", onBack); return }

    val start = (book.locator?.toIntOrNull() ?: 0).coerceIn(0, paras.size - 1)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = start)
    val scope = rememberCoroutineScope()
    var chrome by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    var height by remember { mutableIntStateOf(1) }

    LaunchedEffect(paras) {
        snapshotFlow { list.firstVisibleItemIndex }.distinctUntilChanged()
            .collect { saveProgress(book.id, (it + 1f) / paras.size, it.toString()) }
    }
    ReaderSystem(chrome || sheet, s.keepScreenOn, lightBars = !colors.dark)

    val style = TextStyle(
        color = colors.fg,
        fontSize = (18 * s.fontScale / 100f).sp,
        lineHeight = s.lineHeight.em,
        fontFamily = if (s.fontFamily == "sans") FontFamily.SansSerif else FontFamily.Serif,
        textAlign = if (s.justify) TextAlign.Justify else TextAlign.Start,
        hyphens = Hyphens.Auto,
        lineBreak = LineBreak.Paragraph,
    )
    fun page(dir: Int) { scope.launch { list.animateScrollBy(dir * height * 0.9f) } }

    Box(
        Modifier.fillMaxSize().background(colors.bg).onSizeChanged { height = it.height }
            .readerKeys(s.volumeKeys, onNext = { page(1) }, onPrev = { page(-1) })
    ) {
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(horizontal = (12 + s.margin).dp, vertical = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures { o ->
                    val z = o.x / size.width
                    when {
                        chrome -> chrome = false
                        z < 0.3f -> page(-1)
                        z > 0.7f -> page(1)
                        else -> chrome = true
                    }
                }
            },
        ) {
            itemsIndexed(paras) { _, p ->
                Text(p, style = style, modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(bottom = 12.dp))
            }
        }

        var drag by remember { mutableStateOf<Float?>(null) }
        val idx = list.firstVisibleItemIndex
        ReaderChrome(
            visible = chrome,
            title = book.title,
            subtitle = book.author,
            onBack = onBack,
            actions = { IconButton(onClick = { sheet = true }) { Icon(Icons.Filled.FormatSize, "Text settings") } },
        ) {
            Slider(
                value = drag ?: idx.toFloat(),
                onValueChange = { drag = it },
                valueRange = 0f..(paras.size - 1).coerceAtLeast(1).toFloat(),
                onValueChangeFinished = { drag?.let { scope.launch { list.scrollToItem(it.roundToInt()) } }; drag = null },
            )
            Text("${(((drag ?: idx.toFloat()) + 1) * 100 / paras.size).roundToInt()}%",
                Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.labelMedium)
        }
    }
    if (sheet) ModalBottomSheet(onDismissRequest = { sheet = false }) { ReaderStyleControls(s, showLayout = false) }
}
