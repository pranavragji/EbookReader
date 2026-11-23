package io.github.pranavragji.folio.ui.browse

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.LocalLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.github.pranavragji.folio.app
import io.github.pranavragji.folio.data.OpdsSource
import io.github.pranavragji.folio.source.MangaDex
import kotlinx.coroutines.launch
import java.util.UUID

fun opdsRoute(sourceId: String, url: String? = null) =
    "opds/${Uri.encode(sourceId)}" + (url?.let { "?url=${Uri.encode(it)}" } ?: "")

fun chapterRoute(sourceId: String, titleId: String, chapterId: String) =
    "chapter/${Uri.encode(sourceId)}/${Uri.encode(titleId)}/${Uri.encode(chapterId)}"

/** Registers "browse", "title/{sourceId}/{titleId}" and the source/chapter screens. Nav decodes path args for us. */
fun NavGraphBuilder.browseGraph(nav: NavHostController) {
    composable("browse") { BrowseScreen(nav) }
    composable("source/mangadex") { MangaDexScreen(nav) }
    composable(
        "opds/{sourceId}?url={url}",
        arguments = listOf(navArgument("url") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) { e -> OpdsScreen(nav, e.arguments?.getString("sourceId").orEmpty(), e.arguments?.getString("url")) }
    composable("title/{sourceId}/{titleId}") { e ->
        TitleScreen(nav, e.arguments?.getString("sourceId").orEmpty(), e.arguments?.getString("titleId").orEmpty())
    }
    composable("chapter/{sourceId}/{titleId}/{chapterId}") { e ->
        val a = e.arguments
        ChapterScreen(nav, a?.getString("sourceId").orEmpty(), a?.getString("titleId").orEmpty(), a?.getString("chapterId").orEmpty())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseScreen(nav: NavHostController) {
    val settings by app.prefs.settings.collectAsStateWithLifecycle(initialValue = null)
    var dialog by remember { mutableStateOf<OpdsSource?>(null) }     // source being added/edited
    var menuFor by remember { mutableStateOf<OpdsSource?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Browse") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { dialog = OpdsSource(id = "", name = "", url = "") },
                icon = { Icon(Icons.Outlined.Add, null) },
                text = { Text("Add source") },
            )
        },
    ) { pad ->
        LazyColumn(contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 88.dp)) {
            item { Section("Manga & webtoons") }
            item {
                SourceRow(Icons.Outlined.AutoStories, MangaDex.NAME, "Manga, manhwa & manhua · English", onClick = { nav.navigate("source/mangadex") })
            }
            item { Section("Book catalogs · OPDS") }
            items(settings?.opds.orEmpty(), key = { it.id }) { s ->
                SourceRow(
                    Icons.Outlined.LocalLibrary, s.name, Uri.parse(s.url).host ?: s.url,
                    onClick = { nav.navigate(opdsRoute(s.id)) },
                    onLongClick = { menuFor = s },
                )
            }
            item {
                Text(
                    "Add any OPDS catalog: Calibre-Web, Kavita, Komga, COPS, Standard Ebooks and more. Long-press a catalog to edit it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }

    menuFor?.let { s ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(s.name) },
            text = { Text(s.url) },
            confirmButton = { TextButton(onClick = { menuFor = null; dialog = s }) { Text("Edit") } },
            dismissButton = {
                TextButton(onClick = {
                    menuFor = null
                    scope.launch { app.prefs.update { st -> st.copy(opds = st.opds.filterNot { it.id == s.id }) } }
                }) { Text("Remove") }
            },
        )
    }

    dialog?.let { s ->
        SourceDialog(s, onDismiss = { dialog = null }) { edited ->
            dialog = null
            scope.launch {
                app.prefs.update { st ->
                    val list = if (st.opds.any { it.id == edited.id }) st.opds.map { if (it.id == edited.id) edited else it }
                    else st.opds + edited.copy(id = UUID.randomUUID().toString())
                    st.copy(opds = list)
                }
            }
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourceRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, maxLines = 1) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}

@Composable
private fun SourceDialog(initial: OpdsSource, onDismiss: () -> Unit, onSave: (OpdsSource) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var url by remember { mutableStateOf(initial.url) }
    var user by remember { mutableStateOf(initial.user) }
    var pass by remember { mutableStateOf(initial.pass) }
    val validUrl = url.trim().let { it.startsWith("https://") || it.startsWith("http://") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id.isEmpty()) "Add OPDS catalog" else "Edit catalog") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    url, { url = it }, label = { Text("Catalog URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = url.isNotBlank() && !validUrl,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(user, { user = it }, label = { Text("Username (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    pass, { pass = it }, label = { Text("Password (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = validUrl,
                onClick = {
                    val u = url.trim()
                    onSave(initial.copy(name = name.trim().ifBlank { Uri.parse(u).host ?: "Catalog" }, url = u, user = user.trim(), pass = pass))
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
