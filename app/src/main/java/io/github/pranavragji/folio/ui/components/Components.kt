package io.github.pranavragji.folio.ui.components

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.pranavragji.folio.app
import io.github.pranavragji.folio.ui.theme.Overline
import io.github.pranavragji.folio.ui.theme.Serif
import kotlinx.coroutines.launch
import java.io.File

val CoverShape = RoundedCornerShape(8.dp)

// Curated, muted cloth-binding colors: background to ink.
private val Bindings = listOf(
    Color(0xFF2F4A3A) to Color(0xFFEDE3CF), // bottle green
    Color(0xFF6B2E2E) to Color(0xFFF1E2D0), // oxblood
    Color(0xFF22324A) to Color(0xFFE6E0D2), // navy
    Color(0xFFB5893A) to Color(0xFF2A2116), // ochre
    Color(0xFF4A5560) to Color(0xFFEDE8DF), // slate
    Color(0xFF4E3550) to Color(0xFFF0E4E6), // plum
    Color(0xFF2E5652) to Color(0xFFE8EFE8), // teal
    Color(0xFFC9B79C) to Color(0xFF2B241B), // sand
    Color(0xFF8C4A2F) to Color(0xFFF5E6D8), // sienna
)

/**
 * A book cover: the real image if there is one, drawn over a generated typographic cover
 * (so a missing or broken image degrades gracefully with no loading state to manage).
 */
@Composable
fun BookCover(
    id: String,
    title: String,
    author: String,
    image: Any?,                 // File path string, URL, or null
    modifier: Modifier = Modifier,
    progress: Float = 0f,
    elevation: Dp = 3.dp,
) {
    Box(
        modifier
            .aspectRatio(2f / 3f)
            .shadow(elevation, CoverShape, clip = false, ambientColor = Color.Black.copy(alpha = .35f), spotColor = Color.Black.copy(alpha = .35f))
            .clip(CoverShape)
    ) {
        GeneratedCover(id, title, author)
        if (image != null) {
            val model: Any = (image as? String)?.let { if (it.startsWith("/")) File(it) else it } ?: image
            AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        // Spine crease: a whisper of depth on the left edge, like a real binding.
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = .22f), .035f to Color.White.copy(alpha = .10f),
                    .07f to Color.Transparent, 1f to Color.Transparent,
                )
            )
        )
        if (progress > 0f) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = .35f)))
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
private fun GeneratedCover(id: String, title: String, author: String) {
    val (bg, ink) = Bindings[Math.floorMod(id.hashCode(), Bindings.size)]
    BoxWithConstraints(Modifier.fillMaxSize().background(bg)) {
        val w = maxWidth.value
        val inset = (w * .06f).dp
        Box(Modifier.fillMaxSize().padding(inset).border(0.75.dp, ink.copy(alpha = .28f), RoundedCornerShape(3.dp)))
        Column(
            Modifier.fillMaxSize().padding(horizontal = inset * 2, vertical = inset * 2.4f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(.6f))
            Text(
                title.ifBlank { "Untitled" },
                color = ink,
                fontFamily = Serif,
                fontSize = (w * .115f).coerceIn(10f, 30f).sp,
                lineHeight = (w * .135f).coerceIn(12f, 36f).sp,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(inset))
            Box(Modifier.width((w * .14f).dp).height(0.75.dp).background(ink.copy(alpha = .55f)))
            Spacer(Modifier.weight(1f))
            if (author.isNotBlank()) Text(
                author.uppercase(),
                color = ink.copy(alpha = .8f),
                fontSize = (w * .06f).coerceIn(7f, 13f).sp,
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = Overline,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 8.dp),
    )
}

/** Opens the SAF folder picker; persists read access, adds it to the library and rescans. */
@Composable
fun rememberFolderPicker(): () -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        scope.launch {
            app.prefs.update { it.copy(folders = (it.folders + uri.toString()).distinct()) }
            app.scanner.scanAll()
        }
    }
    return { launcher.launch(null) }
}

/** "Books/Manga" from a SAF tree URI like content://…/tree/primary%3ABooks%2FManga */
fun folderLabel(uri: String): String =
    Uri.decode(uri.substringAfterLast("/tree/", uri)).substringAfter(':').ifBlank { "Internal storage" }
