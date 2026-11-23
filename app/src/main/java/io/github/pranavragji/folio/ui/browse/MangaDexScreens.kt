package io.github.pranavragji.folio.ui.browse

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import io.github.pranavragji.folio.app
import io.github.pranavragji.folio.data.SavedTitle
import io.github.pranavragji.folio.reader.ComicReader
import io.github.pranavragji.folio.reader.PageSource
import io.github.pranavragji.folio.source.Chapter
import io.github.pranavragji.folio.source.MangaDex
import io.github.pranavragji.folio.source.Manga
import io.github.pranavragji.folio.source.Paged
import io.github.pranavragji.folio.source.friendly
import io.github.pranavragji.folio.ui.Routes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Offset-paged list state for a grid; dedupes by id because remote orderings shift between pages. */
class Pager<T>(private val scope: CoroutineScope, private val id: (T) -> Any, private val load: suspend (offset: Int) -> Paged<T>) {
    val items = mutableStateListOf<T>()
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var end by mutableStateOf(false); private set
    private var offset = 0

    fun next() {
        if (loading || end) return
        loading = true; error = null
        scope.launch {
            runCatching { load(offset) }
                .onSuccess { p ->
                    offset += p.items.size
                    val seen = items.mapTo(HashSet()) { id(it) }
                    items += p.items.filter { seen.add(id(it)) }
                    end = !p.hasMore || p.items.isEmpty()
                }
                .onFailure { error = it.friendly() }
            loading = false
        }
    }
}

@Composable
fun BackButton(nav: NavHostController) {
    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MangaDexScreen(nav: NavHostController) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val key = if (submitted.isNotBlank()) "s:$submitted" else "t:$tab"
    val pager = remember(key) {
        val q = submitted; val t = tab
        Pager<Manga>(scope, { it.id }) { o ->
            when {
                q.isNotBlank() -> MangaDex.search(q, o)
                t == 0 -> MangaDex.popular(o)
                else -> MangaDex.latest(o)
            }
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = { BackButton(nav) },
                    title = {
                        if (searching) TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search MangaDex") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { submitted = query.trim() }),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) else Text(MangaDex.NAME)
                    },
                    actions = {
                        IconButton(onClick = {
                            if (searching) { searching = false; query = ""; submitted = "" } else searching = true
                        }) { Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search, "Search") }
                    },
                )
                if (submitted.isBlank()) TabRow(selectedTabIndex = tab) {
                    Tab(tab == 0, onClick = { tab = 0 }, text = { Text("Popular") })
                    Tab(tab == 1, onClick = { tab = 1 }, text = { Text("Latest") })
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            CoverGrid(
                items = pager.items, key = { it.id }, loading = pager.loading, error = pager.error,
                endReached = pager.end, onLoadMore = pager::next,
            ) { m -> CoverCard(m.title, m.cover, onClick = { nav.navigate(Routes.title(MangaDex.ID, m.id)) }) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TitleScreen(nav: NavHostController, sourceId: String, titleId: String) {
    if (sourceId != MangaDex.ID) {
        Scaffold(topBar = { TopAppBar(title = {}, navigationIcon = { BackButton(nav) }) }) {
            ErrorState("This source doesn't have title pages", null, Modifier.padding(it))
        }
        return
    }
    val dao = app.db.titles()
    val saved by remember(titleId) { dao.observe(sourceId, titleId) }.collectAsStateWithLifecycle(initialValue = null)
    var manga by remember { mutableStateOf<Manga?>(null) }
    var chapters by remember { mutableStateOf<List<Chapter>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(attempt) {
        error = null
        runCatching {
            manga = MangaDex.details(titleId)
            chapters = MangaDex.allChapters(titleId, refresh = attempt > 0)
        }.onFailure { error = it.friendly() }
    }

    fun row(cur: SavedTitle?): SavedTitle {
        val m = manga
        return (cur ?: SavedTitle(sourceId, titleId, m?.title ?: "")).copy(
            title = m?.title ?: cur?.title ?: "",
            coverUrl = m?.cover ?: cur?.coverUrl,
            author = m?.author ?: cur?.author ?: "",
        )
    }

    fun open(c: Chapter) = scope.launch {
        dao.upsert(listOf(row(dao.get(sourceId, titleId))))
        nav.navigate(chapterRoute(sourceId, titleId, c.id))
    }

    fun toggleLibrary() = scope.launch {
        val r = row(dao.get(sourceId, titleId))
        dao.upsert(listOf(r.copy(inLibrary = !r.inLibrary, updatedAt = System.currentTimeMillis())))
        app.sync.requestSync()
    }

    val list = chapters
    val lastIdx = list?.indexOfFirst { it.id == saved?.lastChapterId } ?: -1

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { BackButton(nav) },
                actions = {
                    val inLib = saved?.inLibrary == true
                    IconButton(onClick = { toggleLibrary() }) {
                        Icon(
                            if (inLib) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            if (inLib) "Remove from library" else "Add to library",
                            tint = if (inLib) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (!list.isNullOrEmpty()) {
                val resume = list.getOrNull(lastIdx)
                ExtendedFloatingActionButton(
                    onClick = { open(resume ?: list.last()) },
                    icon = { Icon(Icons.Filled.PlayArrow, null) },
                    text = { Text(if (resume != null) "Continue" else "Start reading") },
                )
            }
        },
    ) { pad ->
        val m = manga
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Bottom) {
                    Cover(m?.cover ?: saved?.coverUrl, Modifier.width(118.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(m?.title ?: saved?.title ?: "", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        val by = m?.author ?: saved?.author
                        if (!by.isNullOrBlank()) Text(by, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!m?.status.isNullOrBlank()) Text(m!!.status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (m != null && m.tags.isNotEmpty()) item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(m.tags) { SuggestionChip(onClick = {}, label = { Text(it) }) }
                }
            }
            if (m != null && m.description.isNotBlank()) item {
                Text(
                    m.description,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.animateContentSize().padding(16.dp),
                )
            }
            when {
                list == null && error != null -> item { ErrorState(error!!, onRetry = { attempt++ }) }
                list == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
                list.isEmpty() -> item { ErrorState("No English chapters hosted on MangaDex", onRetry = null) }
                else -> {
                    item {
                        Text(
                            "${list.size} chapters",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                        )
                    }
                    items(list.size, key = { list[it].id }) { i ->
                        val c = list[i]
                        val current = i == lastIdx
                        ListItem(
                            headlineContent = { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    listOf(c.publishedAt, c.group, if (current) "Page ${(saved?.lastPage ?: 0) + 1}" else "")
                                        .filter { it.isNotBlank() }.joinToString(" · "),
                                    maxLines = 1,
                                )
                            },
                            colors = ListItemDefaults.colors(
                                headlineColor = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            ),
                            modifier = Modifier.clickable { open(c) }.alpha(if (lastIdx >= 0 && i > lastIdx) 0.5f else 1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChapterScreen(nav: NavHostController, sourceId: String, titleId: String, chapterId: String) {
    val dao = app.db.titles()
    var pages by remember { mutableStateOf<List<PageSource>?>(null) }
    var chapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }
    var title by remember { mutableStateOf("") }
    var start by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        error = null
        runCatching {
            val t = dao.get(sourceId, titleId)
            title = t?.title ?: ""
            start = if (t?.lastChapterId == chapterId) t.lastPage else 0
            chapters = MangaDex.allChapters(titleId)
            pages = MangaDex.pages(chapterId).map { PageSource.Url(it) }
        }.onFailure { error = it.friendly() }
    }
    DisposableEffect(Unit) { onDispose { app.sync.requestSync() } }

    val idx = chapters.indexOfFirst { it.id == chapterId }
    val current = chapters.getOrNull(idx)
    fun go(c: Chapter) {
        val here = nav.currentBackStackEntry?.destination?.id
        nav.navigate(chapterRoute(sourceId, titleId, c.id)) { if (here != null) popUpTo(here) { inclusive = true } }
    }

    val p = pages
    when {
        error != null && p == null -> Scaffold { ErrorState(error!!, onRetry = { attempt++ }, Modifier.padding(it)) }
        p == null -> CenteredLoading()
        else -> ComicReader(
            title = title,
            pages = p,
            startPage = start.coerceIn(0, (p.size - 1).coerceAtLeast(0)),
            onPageChange = { page, _ ->
                app.scope.launch {
                    dao.get(sourceId, titleId)?.let {
                        val now = System.currentTimeMillis()
                        dao.upsert(listOf(it.copy(lastChapterId = chapterId, lastChapterName = current?.name, lastPage = page, lastReadAt = now, updatedAt = now)))
                    }
                }
            },
            onBack = { nav.popBackStack() },
            subtitle = current?.name,
            // List is newest-first: the next chapter is the one before this in the list.
            onNextChapter = chapters.getOrNull(idx - 1)?.takeIf { idx > 0 }?.let { c -> { go(c) } },
            onPrevChapter = chapters.getOrNull(idx + 1)?.takeIf { idx >= 0 }?.let { c -> { go(c) } },
        )
    }
}
