package io.github.pranavragji.folio.reader

import androidx.compose.runtime.Composable
import java.io.File

/** One page image for [ComicReader]: a remote URL (online sources) or a local file (archives, downloads). */
sealed interface PageSource {
    data class Url(val url: String, val headers: Map<String, String> = emptyMap()) : PageSource
    data class Local(val file: File) : PageSource
}

// STUB: owned by the readers agent. Keep this signature; the sources agent calls it for online chapters.
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
) {}
