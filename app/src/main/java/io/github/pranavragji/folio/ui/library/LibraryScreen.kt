package io.github.pranavragji.folio.ui.library

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pranavragji.folio.app
import io.github.pranavragji.folio.data.Book
import io.github.pranavragji.folio.data.SavedTitle
import io.github.pranavragji.folio.data.Settings
import io.github.pranavragji.folio.ui.components.BookCover
import io.github.pranavragji.folio.ui.components.rememberFolderPicker
import io.github.pranavragji.folio.ui.theme.Serif
import kotlinx.coroutines.launch

/** One cover in the grid: a local file or a saved online title. */
private sealed interface Item {
    val key: String; val id: String; val title: String; val author: String
    val cover: Any?; val progress: Float; val lastRead: Long; val added: Long
}

private data class BookItem(val book: Book) : Item {
    override val key get() = "b:" + book.id
    override val id get() = book.id
    override val title get() = book.title
    override val author get() = book.author
    override val cover get() = book.coverPath
    override val progress get() = book.progress
    override val lastRead get() = book.lastReadAt
    override val added get() = book.addedAt
}

private data class TitleItem(val t: SavedTitle) : Item {
    override val key get() = "t:${t.sourceId}:${t.titleId}"
    override val id get() = t.sourceId + t.titleId
    override val title get() = t.title
    override val author get() = t.author
    override val cover get() = t.coverUrl
    override val progress get() = 0f
    override val lastRead get() = t.lastReadAt
    override val added get() = t.updatedAt
}

private val filters = listOf("all" to "All", "books" to "Books", "comics" to "Comics", "online" to "Online")
private val sorts = listOf("recent" to "Recently read", "title" to "Title", "author" to "Author", "added" to "Date added")

private fun List<Item>.shaped(s: Settings, query: String): List<Item> {
    val q = query.trim()
    return filter {
        when (s.libraryFilter) {
            "books" -> it is BookItem && !it.book.isComic
            "comics" -> it is BookItem && it.book.isComic
            "online" -> it is TitleItem
            else -> true
        } && (q.isEmpty() || it.title.contains(q, true) || it.author.contains(q, true))
    }.let { list ->
        when (s.librarySort) {
            "title" -> list.sortedBy { it.title.lowercase() }
            "author" -> list.sortedWith(compareBy<Item>({ it.author.lowercase().ifEmpty { "￿" } }, { it.title.lowercase() }))
            "added" -> list.sortedByDescending { it.added }
            else -> list.sortedWith(compareByDescending<Item> { it.lastRead }.thenByDescending { it.added })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpenBook: (Book) -> Unit, onOpenTitle: (SavedTitle) -> Unit, onBrowse: () -> Unit) {
    val settings by app.prefs.settings.collectAsStateWithLifecycle(initialValue = null)
    val books by remember { app.db.books().observeAll() }.collectAsStateWithLifecycle(initialValue = null)
    val titles by remember { app.db.titles().observeLibrary() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val history by remember { app.db.titles().observeHistory() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val scanning by app.scanner.scanning.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<Item?>(null) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val pickFolder = rememberFolderPicker()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val s = settings ?: Settings()

    val all = remember(books, titles) { books.orEmpty().map(::BookItem) + titles.map(::TitleItem) }
    val shown = remember(all, s.libraryFilter, s.librarySort, query) { all.shaped(s, query) }
    val continueItems = remember(books, history) {
        (books.orEmpty().filter { it.lastReadAt > 0 && it.progress < .995f }.map(::BookItem) + history.map(::TitleItem))
            .sortedByDescending { it.lastRead }.take(12)
    }
    val open: (Item) -> Unit = { when (it) { is BookItem -> onOpenBook(it.book); is TitleItem -> onOpenTitle(it.t) } }

    BackHandler(searching) { searching = false; query = "" }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0.dp),
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            if (searching) SearchBar(query, { query = it }) { searching = false; query = "" }
            else LargeTopAppBar(
                title = { Text("Library", fontFamily = Serif) },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
                actions = {
                    IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.Search, "Search") }
                    SortMenu(s.librarySort) { v -> scope.launch { app.prefs.update { it.copy(librarySort = v) } } }
                    IconButton(onClick = pickFolder) { Icon(Icons.Outlined.CreateNewFolder, "Add folder") }
                },
            )
        },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = scanning,
            onRefresh = { scope.launch { app.scanner.scanAll() } },
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            if (books != null && all.isEmpty()) {
                EmptyLibrary(pickFolder, onBrowse)
            } else LazyVerticalGrid(
                columns = if (s.gridColumns > 0) GridCells.Fixed(s.gridColumns) else GridCells.Adaptive(108.dp),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (continueItems.isNotEmpty() && query.isBlank() && s.libraryFilter == "all") {
                    item(key = "continue", span = { GridItemSpan(maxLineSpan) }, contentType = "continue") {
                        ContinueRow(continueItems, open)
                    }
                }
                item(key = "filters", span = { GridItemSpan(maxLineSpan) }, contentType = "filters") {
                    FilterRow(s.libraryFilter) { v -> scope.launch { app.prefs.update { it.copy(libraryFilter = v) } } }
                }
                items(shown, key = { it.key }, contentType = { "cover" }) { item ->
                    CoverTile(item, Modifier.animateItem(), onClick = { open(item) }, onLongClick = { sheet = item })
                }
                if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, contentType = "none") {
                    Text(
                        if (query.isNotBlank()) "No matches for “$query”" else "Nothing here yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    )
                }
            }
        }
    }

    sheet?.let { item ->
        DetailsSheet(
            item = item,
            onDismiss = { sheet = null },
            onOpen = { sheet = null; open(item) },
            onUpload = { book ->
                sheet = null
                scope.launch {
                    val r = app.sync.uploadBook(book)
                    snack.showSnackbar(
                        if (r.isSuccess) "Uploaded “${book.title}”"
                        else "Upload failed: ${r.exceptionOrNull()?.message ?: "unknown error"}"
                    )
                }
            },
            onUnread = { book -> sheet = null; scope.launch { app.db.books().updateProgress(book.id, 0f, null) } },
            onRemoveTitle = { t -> sheet = null; scope.launch { app.db.titles().upsert(listOf(t.copy(inLibrary = false))) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") } },
        title = {
            TextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("Title or author") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        actions = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Close, "Clear") } },
    )
}

@Composable
private fun SortMenu(current: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            sorts.forEach { (id, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { open = false; onPick(id) },
                    trailingIcon = { if (id == current) Icon(Icons.Filled.Check, "Selected") },
                )
            }
        }
    }
}

@Composable
private fun FilterRow(current: String, onPick: (String) -> Unit) {
    Row(Modifier.fullBleed().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        filters.forEach { (id, label) ->
            FilterChip(
                selected = id == current,
                onClick = { onPick(id) },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.onSurface,
                    selectedLabelColor = MaterialTheme.colorScheme.surface,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true, selected = id == current,
                    borderColor = MaterialTheme.colorScheme.outlineVariant, selectedBorderColor = Color.Transparent,
                ),
            )
        }
    }
}

/** Lets a full-span grid row (carousel, chips) bleed past the grid's 20dp side padding. */
private fun Modifier.fullBleed() = layout { m, c ->
    val extra = 40.dp.roundToPx()
    val p = m.measure(c.copy(minWidth = c.minWidth + extra, maxWidth = c.maxWidth + extra))
    layout(c.maxWidth, p.height) { p.place(-extra / 2, 0) }
}

@Composable
private fun ContinueRow(items: List<Item>, onOpen: (Item) -> Unit) {
    Column(Modifier.fullBleed().padding(bottom = 4.dp)) {
        Text(
            "Continue reading",
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = Serif, fontWeight = FontWeight.Normal),
            modifier = Modifier.padding(start = 20.dp, bottom = 12.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.key }) { item -> ContinueCard(item) { onOpen(item) } }
        }
    }
}

@Composable
private fun ContinueCard(item: Item, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.width(272.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(item.id, item.title, item.author, item.cover, Modifier.width(56.dp), elevation = 2.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val sub = (item as? TitleItem)?.t?.lastChapterName ?: item.author
                if (sub.isNotBlank()) Text(
                    sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                if (item is BookItem) {
                    LinearProgressIndicator(
                        progress = { item.progress },
                        strokeCap = StrokeCap.Round,
                        trackColor = MaterialTheme.colorScheme.outlineVariant,
                        drawStopIndicator = {},
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    buildString {
                        if (item is BookItem) append("${(item.progress * 100).toInt()}%  ·  ")
                        append(DateUtils.getRelativeTimeSpanString(item.lastRead, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CoverTile(item: Item, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Column(
        modifier.combinedClickable(
            onClick = onClick,
            onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() },
            onLongClickLabel = "Details",
        )
    ) {
        BookCover(item.id, item.title, item.author, item.cover, Modifier.fillMaxWidth(), progress = item.progress)
        Spacer(Modifier.height(8.dp))
        Text(
            item.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (item.author.isNotBlank()) Text(
            item.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EmptyLibrary(onPickFolder: () -> Unit, onBrowse: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.height(190.dp).width(240.dp), contentAlignment = Alignment.Center) {
            BookCover("manga", "Manga", "", null, Modifier.width(96.dp).rotate(-10f).offsetX(-62), elevation = 6.dp)
            BookCover("comics", "Comics", "", null, Modifier.width(96.dp).rotate(9f).offsetX(62), elevation = 6.dp)
            BookCover("novels", "Novels", "Your shelf", null, Modifier.width(110.dp), elevation = 10.dp)
        }
        Spacer(Modifier.height(36.dp))
        Text("A quiet place for your books", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(
            "Choose a folder and Folio will gather everything in it: EPUB, MOBI, AZW3, FB2, PDF, CBZ, CBR and more.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onPickFolder, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)) {
            Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("Choose a folder")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBrowse) {
            Icon(Icons.Outlined.Explore, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Browse sources")
        }
    }
}

private fun Modifier.offsetX(dp: Int) = layout { m, c ->
    val p = m.measure(c)
    layout(p.width, p.height) { p.place(dp.dp.roundToPx(), 0) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsSheet(
    item: Item,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onUpload: (Book) -> Unit,
    onUnread: (Book) -> Unit,
    onRemoveTitle: (SavedTitle) -> Unit,
) {
    val ctx = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                BookCover(item.id, item.title, item.author, item.cover, Modifier.width(84.dp), progress = item.progress, elevation = 6.dp)
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (item.author.isNotBlank()) Text(
                        item.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    val meta = when (item) {
                        is BookItem -> listOfNotNull(
                            item.book.format.uppercase(),
                            item.book.size.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(ctx, it) },
                            item.progress.takeIf { it > 0 }?.let { "${(it * 100).toInt()}% read" },
                        )
                        is TitleItem -> listOfNotNull(item.t.sourceId.replaceFirstChar { it.uppercase() }, item.t.lastChapterName)
                    }.joinToString("  ·  ")
                    Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(4.dp))
            SheetAction(Icons.AutoMirrored.Filled.MenuBook, if (item.progress > 0f || item.lastRead > 0) "Continue reading" else "Open", onOpen)
            when (item) {
                is BookItem -> {
                    SheetAction(Icons.Outlined.CloudUpload, "Upload to cloud") { onUpload(item.book) }
                    if (item.progress > 0f) SheetAction(Icons.Outlined.RestartAlt, "Mark as unread") { onUnread(item.book) }
                }
                is TitleItem -> SheetAction(Icons.Outlined.BookmarkRemove, "Remove from library") { onRemoveTitle(item.t) }
            }
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent) {
        ListItem(
            headlineContent = { Text(label) },
            leadingContent = { Icon(icon, null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}
