package io.github.pranavragji.folio.ui.cloud

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.AddToDrive
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.google.android.gms.auth.api.identity.Identity
import io.github.pranavragji.folio.app
import io.github.pranavragji.folio.data.Book
import io.github.pranavragji.folio.data.SyncConfig
import io.github.pranavragji.folio.source.friendly
import io.github.pranavragji.folio.sync.DriveRemote
import io.github.pranavragji.folio.sync.GoogleAuth
import io.github.pranavragji.folio.sync.RemoteBook
import io.github.pranavragji.folio.sync.WebDavRemote
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val settings by app.prefs.settings.collectAsStateWithLifecycle(initialValue = null)
    val state by app.sync.state.collectAsStateWithLifecycle()
    val books by app.db.books().observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val cfg = settings?.sync ?: SyncConfig()
    val connected = cfg.provider != "none"

    var cloud by remember { mutableStateOf<List<RemoteBook>?>(null) }
    var cloudError by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    val busy = remember { mutableStateMapOf<String, Boolean>() }  // remote name / book id → in flight
    var webdavDialog by remember { mutableStateOf(false) }
    var uploadSheet by remember { mutableStateOf(false) }

    fun say(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }

    suspend fun saveProvider(provider: String, account: String, update: (SyncConfig) -> SyncConfig = { it }) {
        app.prefs.update { it.copy(sync = update(it.sync.copy(provider = provider, account = account))) }
        app.sync.syncNow().onFailure { say(it.friendly()) }
        refresh++
    }

    suspend fun finishGoogle(token: String) {
        val email = runCatching { DriveRemote(app.http) { token }.email() }.getOrDefault("Google Drive")
        saveProvider("google", email)
    }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        scope.launch {
            runCatching { Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(res.data) }
                .onSuccess { r -> r.accessToken?.let { finishGoogle(it) } ?: say("Google didn't grant Drive access") }
                .onFailure { say(GoogleAuth.explain(it)) }
        }
    }

    fun connectGoogle() = scope.launch {
        runCatching { GoogleAuth.authorize(ctx) }
            .onSuccess { r ->
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) consent.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                else r.accessToken?.let { finishGoogle(it) }
            }
            .onFailure { say(GoogleAuth.explain(it)) }
    }

    LaunchedEffect(cfg.provider, cfg.webdavUrl, refresh) {
        cloud = null; cloudError = null
        if (cfg.provider != "none") runCatching { app.sync.cloudBooks() }
            .onSuccess { cloud = it }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it else cloudError = it.friendly() }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Sync") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 4.dp, bottom = pad.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!connected) {
                item {
                    Text(
                        "Read on your phone, pick up on your tablet. Your progress and library follow you, stored in your own cloud. Nothing goes to us.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item { ProviderCard(Icons.Outlined.AddToDrive, "Google Drive", "Sign in with Google. Uses a private app folder plus a Folio folder for your books.") { connectGoogle() } }
                item { ProviderCard(Icons.Outlined.Dns, "WebDAV", "Nextcloud, ownCloud, Koofr, Synology, any WebDAV server. No Google account needed.") { webdavDialog = true } }
            } else {
                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (cfg.provider == "google") Icons.Outlined.AddToDrive else Icons.Outlined.Dns, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(if (cfg.provider == "google") "Google Drive" else "WebDAV", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(cfg.account, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            val last = state.lastSync.takeIf { it > 0 } ?: cfg.lastSync
                            Text(
                                if (last > 0) "Last synced ${DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}" else "Not synced yet",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Button(enabled = !state.running, onClick = { scope.launch { app.sync.syncNow().onSuccess { refresh++ } } }) {
                                    if (state.running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    else Icon(Icons.Outlined.Sync, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp)); Text("Sync now")
                                }
                                TextButton(onClick = {
                                    scope.launch { app.prefs.update { it.copy(sync = SyncConfig()) } }
                                }) { Text("Disconnect") }
                            }
                        }
                    }
                }
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text("What syncs", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        Bullet(Icons.Outlined.Bookmarks, "Reading position in books, comics and PDFs")
                        Bullet(Icons.Outlined.AutoStories, "Saved manga and the chapter and page you're on")
                        Bullet(Icons.Outlined.CloudUpload, "Books you upload, downloadable on any device")
                    }
                }
            }

            if (connected) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Cloud library", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { refresh++ }) { Icon(Icons.Outlined.Refresh, "Refresh") }
                        FilledTonalButton(onClick = { uploadSheet = true }) {
                            Icon(Icons.Outlined.CloudUpload, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Upload")
                        }
                    }
                }
                val list = cloud
                when {
                    cloudError != null -> item { Text(cloudError!!, color = MaterialTheme.colorScheme.error) }
                    list == null -> item { Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() } }
                    list.isEmpty() -> item {
                        Text("Nothing uploaded yet. Upload books here to read them on your other devices.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> items(list, key = { it.id }) { rb ->
                        val local = File(app.downloadsDir, rb.name).exists()
                        ListItem(
                            headlineContent = { Text(rb.name.substringBeforeLast('.'), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text("${rb.name.substringAfterLast('.', "").uppercase()} · ${Formatter.formatShortFileSize(ctx, rb.size)}") },
                            leadingContent = { Icon(Icons.Outlined.Description, null) },
                            trailingContent = {
                                when {
                                    busy[rb.id] == true -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                    local -> Icon(Icons.Outlined.CheckCircle, "On this device", tint = MaterialTheme.colorScheme.primary)
                                    else -> IconButton(onClick = {
                                        busy[rb.id] = true
                                        scope.launch {
                                            runCatching { app.sync.download(rb) }
                                                .onSuccess { b -> say(if (b != null) "Added to library" else "Downloaded") }
                                                .onFailure { say(it.friendly()) }
                                            busy.remove(rb.id)
                                        }
                                    }) { Icon(Icons.Outlined.CloudDownload, "Download") }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (webdavDialog) WebDavDialog(cfg, onDismiss = { webdavDialog = false }) { url, user, pass ->
        webdavDialog = false
        scope.launch {
            saveProvider("webdav", "$user@${android.net.Uri.parse(url).host ?: url}") { it.copy(webdavUrl = url, webdavUser = user, webdavPass = pass) }
        }
    }

    if (uploadSheet) {
        val names = cloud.orEmpty().mapTo(HashSet()) { it.name }
        ModalBottomSheet(onDismissRequest = { uploadSheet = false }) {
            Text("Upload to cloud library", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            if (books.isEmpty()) Text("Your library is empty. Add a folder in the Library tab first.", Modifier.padding(24.dp))
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                items(books, key = { it.id }) { b -> UploadRow(b, uploaded = app.sync.fileName(b) in names, busy = busy[b.id] == true) {
                    busy[b.id] = true
                    scope.launch {
                        app.sync.uploadBook(b).onSuccess { refresh++ }.onFailure { say(it.message ?: "Upload failed") }
                        busy.remove(b.id)
                    }
                } }
            }
        }
    }
}

@Composable
private fun UploadRow(b: Book, uploaded: Boolean, busy: Boolean, onUpload: () -> Unit) {
    ListItem(
        headlineContent = { Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(listOf(b.author, b.format.uppercase()).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 1) },
        trailingContent = {
            when {
                busy -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                uploaded -> Icon(Icons.Outlined.CheckCircle, "Uploaded", tint = MaterialTheme.colorScheme.primary)
                else -> IconButton(onClick = onUpload) { Icon(Icons.Outlined.CloudUpload, "Upload") }
            }
        },
        modifier = Modifier.clickable(enabled = !busy && !uploaded, onClick = onUpload),
    )
}

@Composable
private fun ProviderCard(icon: ImageVector, title: String, body: String, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Bullet(icon: ImageVector, text: String) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun WebDavDialog(cfg: SyncConfig, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var url by remember { mutableStateOf(cfg.webdavUrl) }
    var user by remember { mutableStateOf(cfg.webdavUser) }
    var pass by remember { mutableStateOf(cfg.webdavPass) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val valid = url.trim().startsWith("https://") || url.trim().startsWith("http://")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("WebDAV server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    url, { url = it; ok = false }, label = { Text("Server URL") }, singleLine = true,
                    placeholder = { Text("https://cloud.example.com/remote.php/dav/files/me") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(user, { user = it; ok = false }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    pass, { pass = it; ok = false }, label = { Text("Password / app password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
                )
                Text("A Folio folder is created there.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                result?.let { Text(it, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = valid && !testing, onClick = {
                if (ok) onSave(url.trim(), user.trim(), pass)
                else {
                    testing = true; result = null
                    scope.launch {
                        runCatching { WebDavRemote(app.http, url.trim(), user.trim(), pass).test() }
                            .onSuccess { ok = true; result = "Connected. Tap Save." }
                            .onFailure { ok = false; result = it.friendly() }
                        testing = false
                    }
                }
            }) { Text(if (testing) "Testing…" else if (ok) "Save" else "Test connection") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

