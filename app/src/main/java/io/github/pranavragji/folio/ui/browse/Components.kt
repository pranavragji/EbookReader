package io.github.pranavragji.folio.ui.browse

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest

val CoverShape = RoundedCornerShape(10.dp)

/** Pulsing surface used as a loading placeholder. */
@Composable
fun Modifier.shimmer(): Modifier {
    val t = rememberInfiniteTransition(label = "shimmer")
    val a by t.animateFloat(0.35f, 0.8f, infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "alpha")
    return this.graphicsLayer { alpha = a }.background(MaterialTheme.colorScheme.surfaceVariant)
}

@Composable
fun Cover(url: String?, modifier: Modifier = Modifier, headers: Map<String, String> = emptyMap()) {
    val ctx = LocalContext.current
    val request = remember(url, headers) {
        ImageRequest.Builder(ctx).data(url)
            .apply { if (headers.isNotEmpty()) httpHeaders(NetworkHeaders.Builder().apply { headers.forEach { (k, v) -> set(k, v) } }.build()) }
            .build()
    }
    Box(modifier.aspectRatio(2f / 3f).clip(CoverShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (url != null) AsyncImage(request, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
fun CoverCard(title: String, cover: String?, onClick: () -> Unit, subtitle: String? = null, headers: Map<String, String> = emptyMap()) {
    Column(Modifier.clip(CoverShape).clickable(onClick = onClick).padding(4.dp)) {
        Cover(cover, Modifier.fillMaxWidth(), headers)
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        if (!subtitle.isNullOrBlank())
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PlaceholderCard() {
    Column(Modifier.padding(4.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(CoverShape).shimmer())
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth(0.8f).height(12.dp).clip(RoundedCornerShape(4.dp)).shimmer())
    }
}

@Composable
fun ErrorState(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.CloudOff, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onRetry != null) FilledTonalButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
fun CenteredLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

fun LazyGridScope.fullLine(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }

/**
 * Adaptive cover grid with infinite scroll: shows shimmer cards on first load,
 * calls [onLoadMore] near the end, and a full-width error/retry row.
 */
@Composable
fun <T> CoverGrid(
    items: List<T>,
    key: (T) -> Any,
    loading: Boolean,
    error: String?,
    endReached: Boolean,
    onLoadMore: () -> Unit,
    showEmpty: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    header: (LazyGridScope.() -> Unit)? = null,
    card: @Composable (T) -> Unit,
) {
    val state = rememberLazyGridState()
    val nearEnd by remember {
        derivedStateOf {
            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.layoutInfo.totalItemsCount - 6
        }
    }
    LaunchedEffect(nearEnd, items.size, loading, endReached, error) {
        if (nearEnd && !loading && !endReached && error == null) onLoadMore()
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        state = state,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        header?.invoke(this)
        items(items.size, key = { key(items[it]) }) { card(items[it]) }
        if (loading && items.isEmpty()) items(12) { PlaceholderCard() }
        else if (loading) fullLine { Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) } }
        if (error != null) fullLine { ErrorState(error, onRetry = onLoadMore) }
        else if (showEmpty && !loading && endReached && items.isEmpty()) fullLine { ErrorState("Nothing here", onRetry = null) }
    }
}
