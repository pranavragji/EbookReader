package io.github.pranavragji.folio.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.pranavragji.folio.app
import io.github.pranavragji.folio.data.Settings
import io.github.pranavragji.folio.ui.theme.readerPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Page colours for text readers. "auto" follows the system. */
data class ReaderColors(val name: String, val bg: Color, val fg: Color, val link: Color, val dark: Boolean)

/** Page/ink come from the design palette so the Settings preview matches the reader exactly. */
fun readerColors(theme: String, systemDark: Boolean): ReaderColors {
    val p = readerPalette(theme, systemDark)
    return when (p.id) {
        "sepia" -> ReaderColors("sepia", p.page, p.ink, Color(0xFF8A5A2B), false)
        "dark" -> ReaderColors("dark", p.page, p.ink, Color(0xFF9DB7FF), true)
        "black" -> ReaderColors("black", p.page, p.ink, Color(0xFF8FA8E8), true)
        else -> ReaderColors("light", p.page, p.ink, Color(0xFF2A5DB0), false)
    }
}

fun Color.hex(): String = "#%06X".format(0xFFFFFF and (value shr 32).toInt())

/** Persist reading position off the main thread. Cheap Room update, so every page turn is saved. */
fun saveProgress(bookId: String, progress: Float, locator: String?) {
    app.scope.launch(Dispatchers.IO) {
        app.db.books().updateProgress(bookId, progress.coerceIn(0f, 1f), locator)
    }
}

fun updateSettings(f: (Settings) -> Settings) {
    app.scope.launch { app.prefs.update(f) }
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Immersive while reading (bars come back with the chrome), optional keep-screen-on. */
@Composable
fun ReaderSystem(chromeVisible: Boolean, keepScreenOn: Boolean, lightBars: Boolean = false) {
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window }
    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(chromeVisible, lightBars) {
        window?.let {
            val c = WindowCompat.getInsetsController(it, view)
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.isAppearanceLightStatusBars = lightBars && !chromeVisible
            if (chromeVisible) c.show(WindowInsetsCompat.Type.systemBars()) else c.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {}
    }
    DisposableEffect(Unit) {
        onDispose { window?.let { WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars()) } }
    }
}

/** Volume keys (if enabled), arrows and page keys turn pages. Holds focus so WebViews don't steal keys. */
@Composable
fun Modifier.readerKeys(volumeKeys: Boolean, onNext: () -> Unit, onPrev: () -> Unit): Modifier {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    return this
        .focusRequester(focus)
        .focusable()
        .onPreviewKeyEvent { e ->
            val dir = when (e.key) {
                Key.VolumeDown -> if (volumeKeys) 1 else 0
                Key.VolumeUp -> if (volumeKeys) -1 else 0
                Key.DirectionRight, Key.PageDown, Key.Spacebar -> 1
                Key.DirectionLeft, Key.PageUp -> -1
                else -> 0
            }
            if (dir == 0) return@onPreviewKeyEvent false
            if (e.type == KeyEventType.KeyDown) { if (dir > 0) onNext() else onPrev() }
            true
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderChrome(
    visible: Boolean,
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    bottom: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f), tonalElevation = 2.dp) {
                TopAppBar(
                    title = {
                        Column {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            if (!subtitle.isNullOrBlank()) Text(
                                subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    actions = actions,
                    windowInsets = WindowInsets.statusBars,
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            }
        }
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f), tonalElevation = 2.dp) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp),
                    content = bottom,
                )
            }
        }
    }
}

@Composable
fun ReaderLoading(bg: Color = MaterialTheme.colorScheme.surface, progress: Float? = null, label: String? = null) {
    Box(Modifier.fillMaxSize().background(bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (progress == null) CircularProgressIndicator(strokeWidth = 3.dp)
            else LinearProgressIndicator(progress = { progress }, modifier = Modifier.width(180.dp))
            if (label != null) {
                Spacer(Modifier.height(12.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun ReaderMessage(text: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            Button(onClick = onBack) { Text("Back") }
        }
    }
}

/** Typography controls shared by the EPUB-family and plain-text readers. */
@Composable
fun ReaderStyleControls(s: Settings, showLayout: Boolean) {
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            for (t in listOf("auto", "light", "sepia", "dark", "black")) {
                val c = readerColors(t, systemDark = false)
                val selected = s.readerTheme == t
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(40.dp)
                            .background(if (t == "auto") MaterialTheme.colorScheme.surfaceVariant else c.bg, CircleShape)
                            .border(if (selected) 2.5.dp else 1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .clickable { updateSettings { it.copy(readerTheme = t) } },
                        contentAlignment = Alignment.Center,
                    ) { Text("Aa", color = if (t == "auto") MaterialTheme.colorScheme.onSurfaceVariant else c.fg, style = MaterialTheme.typography.labelMedium) }
                    Text(t.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Text size", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            FilledTonalIconButton(onClick = { updateSettings { it.copy(fontScale = (it.fontScale - 10).coerceAtLeast(60)) } }) {
                Icon(Icons.Filled.Remove, "Smaller")
            }
            Text("${s.fontScale}%", Modifier.width(56.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall)
            FilledTonalIconButton(onClick = { updateSettings { it.copy(fontScale = (it.fontScale + 10).coerceAtMost(250)) } }) {
                Icon(Icons.Filled.Add, "Larger")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((key, label) in listOf("serif" to "Serif", "sans" to "Sans", "publisher" to "Original")) {
                FilterChip(selected = s.fontFamily == key, onClick = { updateSettings { it.copy(fontFamily = key) } }, label = { Text(label) })
            }
        }
        LabeledSlider("Line spacing", "%.1f".format(s.lineHeight), s.lineHeight, 1.1f..2.2f) { v -> updateSettings { it.copy(lineHeight = v) } }
        LabeledSlider("Margins", "${s.margin}", s.margin.toFloat(), 0f..72f) { v -> updateSettings { it.copy(margin = v.toInt()) } }
        SwitchRow("Justify text", s.justify) { v -> updateSettings { it.copy(justify = v) } }
        if (showLayout) SwitchRow("Turn pages (off = scroll)", s.paginated) { v -> updateSettings { it.copy(paginated = v) } }
    }
}

@Composable
private fun LabeledSlider(label: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, onDone: (Float) -> Unit) {
    var v by remember(current) { mutableFloatStateOf(current) }
    Column {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(if (v == current) value else if (range.endInclusive > 10) "${v.toInt()}" else "%.1f".format(v),
                style = MaterialTheme.typography.titleSmall)
        }
        Slider(value = v, onValueChange = { v = it }, valueRange = range, onValueChangeFinished = { onDone(v) })
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
