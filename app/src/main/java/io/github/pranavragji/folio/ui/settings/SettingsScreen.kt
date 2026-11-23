package io.github.pranavragji.folio.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import io.github.pranavragji.folio.BuildConfig
import io.github.pranavragji.folio.app
import io.github.pranavragji.folio.data.Settings
import io.github.pranavragji.folio.ui.components.SectionHeader
import io.github.pranavragji.folio.ui.components.folderLabel
import io.github.pranavragji.folio.ui.components.rememberFolderPicker
import io.github.pranavragji.folio.ui.theme.ReaderPalettes
import io.github.pranavragji.folio.ui.theme.Serif
import io.github.pranavragji.folio.ui.theme.readerPalette
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val licenses = listOf(
    "foliate-js" to "MIT",
    "Jetpack Compose, Room, DataStore, Navigation" to "Apache-2.0",
    "Material Components & Icons" to "Apache-2.0",
    "Coil" to "Apache-2.0",
    "Telephoto" to "Apache-2.0",
    "OkHttp" to "Apache-2.0",
    "kotlinx.coroutines / serialization" to "Apache-2.0",
    "Apache Commons Compress" to "Apache-2.0",
    "XZ for Java" to "0BSD",
    "junrar" to "UnRAR license",
    "Google Play services Auth" to "Android SDK License",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavHostController) {
    val s by app.prefs.settings.collectAsStateWithLifecycle(initialValue = Settings())
    val scanning by app.scanner.scanning.collectAsStateWithLifecycle()
    // Draft lets sliders and the live preview move every frame; we persist once on release.
    var draft by remember(s) { mutableStateOf(s) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    val pickFolder = rememberFolderPicker()
    var showLicenses by remember { mutableStateOf(false) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val set: ((Settings) -> Settings) -> Unit = { f -> draft = f(draft); scope.launch { app.prefs.update(f) } }
    val d = draft

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            LargeTopAppBar(
                title = { Text("Settings", fontFamily = Serif) },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
            item {
                SectionHeader("Appearance")
                Segments("Theme", listOf(0 to "System", 1 to "Light", 2 to "Dark"), d.themeMode) { v -> set { it.copy(themeMode = v) } }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    SwitchRow("Dynamic color", "Match your wallpaper", d.dynamicColor) { v -> set { it.copy(dynamicColor = v) } }
                SwitchRow("Pure black", "True black in dark mode, kind to OLED screens", d.amoled) { v -> set { it.copy(amoled = v) } }
            }

            item {
                SectionHeader("Library")
                d.folders.forEach { f ->
                    PrefRow(Icons.Outlined.Folder, folderLabel(f), trailing = {
                        IconButton(onClick = {
                            runCatching { ctx.contentResolver.releasePersistableUriPermission(Uri.parse(f), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                            set { it.copy(folders = it.folders - f) }
                            scope.launch { app.scanner.scanAll() }
                        }) { Icon(Icons.Outlined.RemoveCircleOutline, "Remove folder") }
                    })
                }
                PrefRow(Icons.Outlined.Add, "Add folder", "Books, comics and manga inside are found automatically", onClick = pickFolder)
                PrefRow(Icons.Outlined.Sync, "Rescan library", if (scanning) "Scanning…" else "Pick up new and removed files",
                    trailing = { if (scanning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
                    onClick = { scope.launch { app.scanner.scanAll() } })
                Segments("Grid columns", listOf(0 to "Auto", 2 to "2", 3 to "3", 4 to "4", 5 to "5"), d.gridColumns, compact = true) { v ->
                    set { it.copy(gridColumns = v) }
                }
            }

            item {
                SectionHeader("Reading")
                ReaderPreview(d)
                ThemeSwatches(d.readerTheme) { v -> set { it.copy(readerTheme = v) } }
                Segments("Typeface", listOf("serif" to "Serif", "sans" to "Sans", "publisher" to "Publisher"), d.fontFamily) { v ->
                    set { it.copy(fontFamily = v) }
                }
                SliderRow("Text size", "${d.fontScale}%", d.fontScale.toFloat(), 70f..200f, 12,
                    onChange = { draft = draft.copy(fontScale = it.roundToInt()) },
                    onDone = { scope.launch { app.prefs.update { it.copy(fontScale = draft.fontScale) } } })
                SliderRow("Line spacing", "%.1f".format(d.lineHeight), d.lineHeight, 1.0f..2.2f, 11,
                    onChange = { draft = draft.copy(lineHeight = (it * 10).roundToInt() / 10f) },
                    onDone = { scope.launch { app.prefs.update { it.copy(lineHeight = draft.lineHeight) } } })
                SliderRow("Margins", "${d.margin}", d.margin.toFloat(), 0f..64f, 7,
                    onChange = { draft = draft.copy(margin = it.roundToInt()) },
                    onDone = { scope.launch { app.prefs.update { it.copy(margin = draft.margin) } } })
                Segments("Layout", listOf(true to "Pages", false to "Scroll"), d.paginated) { v -> set { it.copy(paginated = v) } }
                SwitchRow("Justify text", null, d.justify) { v -> set { it.copy(justify = v) } }
            }

            item {
                SectionHeader("Comics & manga")
                Segments(
                    "Reading direction",
                    listOf("ltr" to "LTR", "rtl" to "RTL", "vertical" to "Vertical", "webtoon" to "Webtoon"),
                    d.comicMode, compact = true,
                    caption = when (d.comicMode) {
                        "ltr" -> "Left to right, like Western comics"
                        "rtl" -> "Right to left, like Japanese manga"
                        "vertical" -> "Pages stacked top to bottom"
                        else -> "One seamless strip, made for manhwa"
                    },
                ) { v -> set { it.copy(comicMode = v) } }
                SwitchRow("Volume keys turn pages", null, d.volumeKeys) { v -> set { it.copy(volumeKeys = v) } }
                SwitchRow("Keep screen on", "While a book is open", d.keepScreenOn) { v -> set { it.copy(keepScreenOn = v) } }
            }

            item {
                SectionHeader("About")
                PrefRow(Icons.Outlined.Info, "Folio", "Version ${BuildConfig.VERSION_NAME}")
                PrefRow(Icons.Outlined.Code, "Source code", "github.com/pranavragji/EbookReader",
                    onClick = { uri.openUri("https://github.com/pranavragji/EbookReader") })
                PrefRow(Icons.Outlined.Description, "Open-source licenses", onClick = { showLicenses = true })
            }
        }
    }

    if (showLicenses) AlertDialog(
        onDismissRequest = { showLicenses = false },
        confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("Close") } },
        title = { Text("Open-source licenses") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                licenses.forEach { (name, lic) ->
                    Column {
                        Text(name, style = MaterialTheme.typography.bodyMedium)
                        Text(lic, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
    )
}

@Composable
private fun PrefRow(
    icon: ImageVector?,
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = icon?.let { { Icon(it, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) } },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 8.dp),
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(horizontal = 8.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segments(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    compact: Boolean = false,
    caption: String? = null,
    onPick: (T) -> Unit,
) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (caption != null) Text(caption, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (value, label) ->
                SegmentedButton(
                    selected = value == selected,
                    onClick = { onPick(value) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    icon = if (compact) ({}) else ({ SegmentedButtonDefaults.Icon(value == selected) }),
                ) { Text(label, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = current, onValueChange = onChange, onValueChangeFinished = onDone, valueRange = range, steps = steps)
    }
}

@Composable
private fun ReaderPreview(s: Settings) {
    val dark = MaterialTheme.colorScheme.background.let { (it.red + it.green + it.blue) / 3 < .5f }
    val p = readerPalette(s.readerTheme, dark)
    val size = 16f * s.fontScale / 100f
    Surface(
        color = p.page,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large),
    ) {
        Text(
            "It was a bright cold day in April, and the clocks were striking thirteen. The hallway smelt of boiled cabbage and old rag mats, and a quiet wind moved the pages.",
            color = p.ink,
            fontFamily = if (s.fontFamily == "sans") FontFamily.SansSerif else Serif,
            fontSize = size.sp,
            lineHeight = (size * s.lineHeight).sp,
            textAlign = if (s.justify) TextAlign.Justify else TextAlign.Start,
            maxLines = 5,
            modifier = Modifier.padding(horizontal = (8 + s.margin * .5f).dp, vertical = 20.dp),
        )
    }
}

@Composable
private fun ThemeSwatches(current: String, onPick: (String) -> Unit) {
    val options = listOf("auto" to "Auto") + ReaderPalettes.map { it.id to it.label }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        options.forEach { (id, label) ->
            val sel = id == current
            Column(
                Modifier.clip(MaterialTheme.shapes.medium)
                    .selectable(selected = sel, role = Role.RadioButton, onClick = { onPick(id) })
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val p = ReaderPalettes.firstOrNull { it.id == id }
                val fill = if (p != null) Modifier.background(p.page) else Modifier.background(
                    Brush.linearGradient(0f to ReaderPalettes[0].page, .5f to ReaderPalettes[0].page, .5f to ReaderPalettes[2].page, 1f to ReaderPalettes[2].page)
                )
                Box(
                    Modifier.size(48.dp).clip(CircleShape).then(fill)
                        .border(
                            if (sel) 2.dp else 1.dp,
                            if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Aa", color = p?.ink ?: Color(0xFF7A7570), fontFamily = Serif, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    label, style = MaterialTheme.typography.labelSmall,
                    color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
