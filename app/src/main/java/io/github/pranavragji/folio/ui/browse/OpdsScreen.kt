package io.github.pranavragji.folio.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import io.github.pranavragji.folio.app
import io.github.pranavragji.folio.data.OpdsSource
import io.github.pranavragji.folio.source.FORMATS
import io.github.pranavragji.folio.source.Opds
import io.github.pranavragji.folio.source.OpdsEntry
import io.github.pranavragji.folio.source.friendly
import io.github.pranavragji.folio.ui.Routes
import kotlinx.coroutines.launch

@Composable
fun OpdsScreen(nav: NavHostController, sourceId: String, url: String?) {
    val settings by app.prefs.settings.collectAsStateWithLifecycle(initialValue = null)
    val s = settings ?: return CenteredLoading()
    val src = s.opds.firstOrNull { it.id == sourceId }
        ?: return Scaffold { ErrorState("This catalog was removed", null, Modifier.padding(it)) }
    OpdsFeedScreen(nav, src, url ?: src.url, isRoot = url == null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpdsFeedScreen(nav: NavHostController, src: OpdsSource, start: String, isRoot: Boolean) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val entries = remember(start) { mutableStateListOf<OpdsEntry>() }
    var next by remember(start) { mutableStateOf<String?>(start) }
    var title by remember { mutableStateOf(src.name) }
    var search by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<OpdsEntry?>(null) }

    fun loadMore() {
        val u = next ?: return
        if (loading) return
        loading = true; error = null
        scope.launch {
            runCatching { Opds.feed(src, u) }
                .onSuccess { f ->
                    if (entries.isEmpty() && f.title.isNotBlank() && !isRoot) title = f.title
                    if (search == null) search = f.search
                    val seen = entries.mapTo(HashSet()) { it.id }
                    entries += f.entries.filter { seen.add(it.id) }
                    next = f.next?.takeIf { it != u }
                }
                .onFailure { error = it.friendly() }
            loading = false
        }
    }

    val navs = entries.filter { it.navigation != null }
    val books = entries.filter { it.navigation == null }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton(nav) },
                title = {
                    if (searching) TextField(
                        value = query, onValueChange = { query = it },
                        placeholder = { Text("Search ${src.name}") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            val t = search
                            if (t != null && query.isNotBlank()) nav.navigate(opdsRoute(src.id, Opds.searchUrl(t, query.trim())))
                        }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) else Text(title, maxLines = 1)
                },
                actions = {
                    if (search != null) IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search, "Search")
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            CoverGrid(
                items = books, key = { it.id }, loading = loading, error = error,
                endReached = next == null, onLoadMore = ::loadMore,
                showEmpty = navs.isEmpty(),
                header = {
                    navs.forEach { e ->
                        item(key = "nav:${e.id}", span = { GridItemSpan(maxLineSpan) }) {
                            ListItem(
                                headlineContent = { Text(e.title) },
                                supportingContent = if (e.summary.isNotBlank()) ({ Text(e.summary, maxLines = 2) }) else null,
                                leadingContent = { Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                                trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
                                modifier = Modifier.clickable { nav.navigate(opdsRoute(src.id, e.navigation)) },
                            )
                        }
                    }
                },
            ) { e ->
                CoverCard(e.title, e.thumbnail, onClick = { selected = e }, subtitle = e.author, headers = e.thumbnail?.let { Opds.headers(src, it) } ?: emptyMap())
            }
        }
    }

    selected?.let { e ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Cover(e.thumbnail, Modifier.width(96.dp), e.thumbnail?.let { Opds.headers(src, it) } ?: emptyMap())
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(e.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (e.author.isNotBlank()) Text(e.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val formats = e.acquisitions.mapNotNull { l -> FORMATS.firstOrNull { l.type.startsWith(it.first) }?.second }.distinct()
                        if (formats.isNotEmpty()) Text(formats.joinToString(" · ") { it.uppercase() }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                if (e.summary.isNotBlank()) Text(e.summary, style = MaterialTheme.typography.bodyMedium)
                val link = e.best
                val progress = link?.let { Opds.downloads[it.href] }
                when {
                    link == null -> Text("No downloadable file in this entry", color = MaterialTheme.colorScheme.error)
                    progress != null -> {
                        if (progress < 0) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    }
                    else -> Button(
                        onClick = {
                            scope.launch {
                                runCatching { Opds.download(src, e, link) }
                                    .onSuccess { book ->
                                        selected = null
                                        val r = snackbar.showSnackbar("Added to library", actionLabel = if (book != null) "Read" else null)
                                        if (r == SnackbarResult.ActionPerformed && book != null) nav.navigate(Routes.reader(book.id))
                                    }
                                    .onFailure { snackbar.showSnackbar(it.friendly()) }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Download, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Download")
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
