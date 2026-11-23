package io.github.pranavragji.folio.reader

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.github.pranavragji.folio.app
import io.github.pranavragji.folio.data.Book
import io.github.pranavragji.folio.library.Archives
import io.github.pranavragji.folio.library.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Registers "reader/{bookId}" (id is URI-encoded by Routes.reader). */
fun NavGraphBuilder.readerGraph(nav: NavHostController) {
    composable("reader/{bookId}") { entry ->
        val id = Uri.decode(entry.arguments?.getString("bookId").orEmpty())
        ReaderScreen(id, onBack = { nav.popBackStack() })
    }
}

@Composable
private fun ReaderScreen(bookId: String, onBack: () -> Unit) {
    // (loaded, book): distinguishes "still loading" from "not in the library".
    val loaded by produceState<Pair<Boolean, Book?>>(false to null, bookId) { value = true to app.db.books().get(bookId) }
    val settings = app.prefs.settings.collectAsStateWithLifecycle(initialValue = null).value
    DisposableEffect(bookId) { onDispose { app.sync.requestSync() } }

    val book = loaded.second
    when {
        !loaded.first || settings == null -> ReaderLoading()
        book == null -> ReaderMessage("This book is no longer in your library.", onBack)
        book.format in Formats.FOLIATE -> EbookReader(book, settings, onBack)
        book.format == "pdf" -> PdfReader(book, settings, onBack)
        book.isComic -> ComicBookReader(book, onBack)
        else -> TextReader(book, settings, onBack)
    }
}

@Composable
private fun ComicBookReader(book: Book, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var pages by remember { mutableStateOf<List<PageSource>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0 to 0) }
    LaunchedEffect(book.id) {
        runCatching {
            withContext(Dispatchers.IO) {
                Archives.extractPages(ctx, Uri.parse(book.uri), book.format, book.id) { done, total -> progress = done to total }
            }
        }.onSuccess { files -> if (files.isEmpty()) error = "No images found in this archive." else pages = files.map { PageSource.Local(it) } }
            .onFailure { error = it.message ?: "Couldn't open this archive." }
    }
    val p = pages
    when {
        error != null -> ReaderMessage(error!!, onBack)
        p == null -> Box(Modifier.fillMaxSize().background(Color.Black)) {
            val (done, total) = progress
            ReaderLoading(Color.Black, if (total > 0) done / total.toFloat() else null, if (total > 0) "Preparing pages $done / $total" else null)
        }
        else -> ComicReader(
            title = book.title,
            subtitle = book.author.ifBlank { null },
            pages = p,
            startPage = book.locator?.toIntOrNull() ?: 0,
            onPageChange = { page, total -> saveProgress(book.id, (page + 1f) / total, page.toString()) },
            onBack = onBack,
        )
    }
}
