package com.smartnews.ui.components

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.smartnews.domain.model.Article
import com.smartnews.domain.model.DateFilter
import com.smartnews.domain.model.MediaItem
import com.smartnews.domain.model.SortMode
import com.smartnews.domain.model.Story
import com.smartnews.ui.theme.AiTint
import com.smartnews.ui.theme.Signal
import java.text.DateFormat
import java.util.Date

/** false when the user chose "images on Wi-Fi only" and the phone is on mobile data. */
val LocalAllowNetworkImages = compositionLocalOf { true }

fun relativeTime(ms: Long): String =
    DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

fun dateTime(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

fun duration(sec: Int?): String? = sec?.let { "%d:%02d".format(it / 60, it % 60) }

@Composable
fun NewsImage(url: String?, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val allowNetwork = LocalAllowNetworkImages.current
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (url != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(url)
                    .networkCachePolicy(if (allowNetwork) CachePolicy.ENABLED else CachePolicy.DISABLED)
                    .crossfade(true).build(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun Pill(text: String, container: Color, content: Color, icon: ImageVector? = null) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, Modifier.size(12.dp)); Spacer(Modifier.width(4.dp)) }
            Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable fun LiveBadge() = Pill("LIVE", Signal, Color.White)
@Composable fun BreakingBadge() = Pill("BREAKING", Signal, Color.White)
@Composable fun AiBadge(text: String = "AI-generated") = Pill(text, AiTint.copy(alpha = 0.16f), AiTint, Icons.Default.AutoAwesome)
@Composable fun ExtractBadge() = Pill("Extract from publisher text", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun SourceLine(source: String, publishedAt: Long, extra: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(source, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Text("  ·  ${relativeTime(publishedAt)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        if (extra != null) Text("  ·  $extra", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
fun HeroStoryCard(story: Story, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = onClick, modifier = modifier, shape = MaterialTheme.shapes.large, elevation = CardDefaults.cardElevation(0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Box {
            NewsImage(story.imageUrl, Modifier.fillMaxWidth().aspectRatio(16f / 10f))
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))))
            Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (story.isBreaking) BreakingBadge()
                    if (story.sourceCount > 1) Pill("${story.sourceCount} sources", Color(0x33FFFFFF), Color.White, Icons.Default.Layers)
                }
                Spacer(Modifier.height(8.dp))
                Text(story.headline, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text("${story.leadSourceName} · ${relativeTime(story.lastPublishedAt)}", style = MaterialTheme.typography.labelMedium, color = Color(0xDDFFFFFF))
            }
        }
    }
}

@Composable
fun StoryCard(story: Story, onClick: () -> Unit, onToggleSave: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, elevation = CardDefaults.cardElevation(0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Row(Modifier.padding(12.dp)) {
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (story.isBreaking) BreakingBadge()
                    if (story.sourceCount > 1) Pill("${story.sourceCount} sources", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, Icons.Default.Layers)
                }
                if (story.isBreaking || story.sourceCount > 1) Spacer(Modifier.height(6.dp))
                Text(story.headline, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                SourceLine(story.leadSourceName, story.lastPublishedAt)
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                NewsImage(story.imageUrl, Modifier.size(92.dp).clip(MaterialTheme.shapes.small))
                if (onToggleSave != null) IconButton(onClick = onToggleSave) {
                    Icon(if (story.isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, "Save story")
                }
            }
        }
    }
}

@Composable
fun ArticleRow(article: Article, onClick: () -> Unit, onToggleSave: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, elevation = CardDefaults.cardElevation(0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Row(Modifier.padding(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    article.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    color = if (article.isRead) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                if (article.description.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(article.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(6.dp))
                SourceLine(article.sourceName, article.publishedAt)
            }
            if (article.imageUrl != null || onToggleSave != null) {
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    if (article.imageUrl != null) NewsImage(article.imageUrl, Modifier.size(80.dp).clip(MaterialTheme.shapes.small))
                    if (onToggleSave != null) IconButton(onClick = onToggleSave) {
                        Icon(if (article.isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, "Save article")
                    }
                }
            }
        }
    }
}

@Composable
fun VideoCard(item: MediaItem, onClick: () -> Unit, modifier: Modifier = Modifier, width: Int? = 260) {
    Column(modifier.then(if (width != null) Modifier.width(width.dp) else Modifier.fillMaxWidth()).clip(MaterialTheme.shapes.medium).clickable(onClick = onClick)) {
        Box {
            NewsImage(item.thumbnailUrl, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium))
            Box(Modifier.align(Alignment.Center).size(44.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, "Play", tint = Color.White)
            }
            Row(Modifier.align(Alignment.TopStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (item.isLive) LiveBadge()
                if (item.isInterview) Pill("Interview", Color(0xCC000000), Color.White)
            }
            duration(item.durationSec)?.let {
                Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) { Pill(it, Color(0xCC000000), Color.White) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            item.publisher + (item.publishedAt?.let { " · " + relativeTime(it) } ?: ""),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
        )
    }
}

@Composable
fun VideoRow(items: List<MediaItem>, onClick: (MediaItem) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items, key = { it.id }) { VideoCard(it, onClick = { onClick(it) }) }
    }
}

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateFilterRow(selected: DateFilter, onSelect: (DateFilter) -> Unit, sort: SortMode? = null, onSort: ((SortMode) -> Unit)? = null) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (sort != null && onSort != null) item {
            AssistChip(
                onClick = { onSort(if (sort == SortMode.NEWEST) SortMode.RELEVANCE else SortMode.NEWEST) },
                label = { Text(if (sort == SortMode.NEWEST) "Newest" else "Relevance") },
                leadingIcon = { Icon(Icons.Default.Sort, null, Modifier.size(18.dp)) },
            )
        }
        items(DateFilter.presets) { f ->
            FilterChip(selected = selected == f, onClick = { onSelect(f) }, label = { Text(f.label) })
        }
        item {
            FilterChip(
                selected = selected is DateFilter.Custom, onClick = { showPicker = true },
                label = {
                    Text(if (selected is DateFilter.Custom) DateFormat.getDateInstance(DateFormat.SHORT).format(Date(selected.from)) + " – " +
                        DateFormat.getDateInstance(DateFormat.SHORT).format(Date(selected.to)) else "Custom range")
                },
                leadingIcon = { Icon(Icons.Default.DateRange, null, Modifier.size(18.dp)) },
            )
        }
    }
    if (showPicker) {
        val state = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(enabled = state.selectedStartDateMillis != null, onClick = {
                    val from = state.selectedStartDateMillis!!
                    val to = (state.selectedEndDateMillis ?: from) + 86_399_999L
                    onSelect(DateFilter.Custom(from, to)); showPicker = false
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } },
        ) {
            DateRangePicker(state = state, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun EmptyState(title: String, message: String, icon: ImageVector = Icons.Default.Inbox, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null && onAction != null) { Spacer(Modifier.height(16.dp)); Button(onClick = onAction) { Text(action) } }
    }
}

@Composable
fun ErrorBanner(message: String?, onDismiss: () -> Unit) {
    AnimatedVisibility(message != null) {
        Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), shape = MaterialTheme.shapes.small) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ErrorOutline, null)
                Spacer(Modifier.width(8.dp))
                Text(message.orEmpty(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("OK") }
            }
        }
    }
}

@Composable
fun OfflineBanner(visible: Boolean) {
    AnimatedVisibility(visible) {
        Surface(color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudOff, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Offline — showing news saved on this phone. Refreshing and streaming need internet.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Calls [onLoadMore] when the user scrolls near the end of the list (infinite scrolling). */
@Composable
fun LoadMoreEffect(state: LazyListState, threshold: Int = 5, onLoadMore: () -> Unit) {
    val shouldLoad by remember {
        derivedStateOf {
            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = state.layoutInfo.totalItemsCount
            total > 0 && last >= total - threshold
        }
    }
    LaunchedEffect(state) { snapshotFlow { shouldLoad }.collect { if (it) onLoadMore() } }
}
