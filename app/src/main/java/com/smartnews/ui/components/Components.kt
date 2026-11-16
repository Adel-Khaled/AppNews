package com.smartnews.ui.components

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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Translate
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.smartnews.domain.model.Article
import com.smartnews.domain.model.DateFilter
import com.smartnews.domain.model.MediaItem
import com.smartnews.domain.model.Scope
import com.smartnews.domain.model.Story
import com.smartnews.ui.theme.Signal
import java.text.DateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------- language

/** App language ("ar" or "en"), provided at the root from Settings. */
val LocalLang = staticCompositionLocalOf { "ar" }

/** "3 sources" / "٣ مصادر" with correct Arabic singular/dual. */
@Composable
fun sourcesLabel(n: Int): String = if (LocalLang.current != "ar") (if (n == 1) "1 source" else "$n sources") else when (n) {
    1 -> "مصدر واحد"
    2 -> "مصدران"
    in 3..10 -> "$n مصادر"
    else -> "$n مصدراً"
}

/** Inline bilingual text: tr("Home", "الرئيسية"). */
@Composable
fun tr(en: String, ar: String): String = if (LocalLang.current == "ar") ar else en

fun relativeTime(ms: Long, lang: String): String {
    val diff = (System.currentTimeMillis() - ms).coerceAtLeast(0)
    val min = diff / 60_000
    val ar = lang == "ar"
    return when {
        min < 1 -> if (ar) "الآن" else "just now"
        min < 60 -> if (ar) "منذ $min د" else "${min}m ago"
        min < 24 * 60 -> if (ar) "منذ ${min / 60} س" else "${min / 60}h ago"
        min < 7 * 24 * 60 -> if (ar) "منذ ${min / 1440} يوم" else "${min / 1440}d ago"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM, Locale(lang)).format(Date(ms))
    }
}

fun dateTime(ms: Long, lang: String): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale(lang)).format(Date(ms))

fun duration(sec: Int?): String? = sec?.let { "%d:%02d".format(Locale.US, it / 60, it % 60) }

/** false when the user chose "images on Wi-Fi only" and the phone is on mobile data. */
val LocalAllowNetworkImages = staticCompositionLocalOf { true }

// ---------------------------------------------------------------- building blocks

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

@Composable fun LiveBadge() = Pill(tr("LIVE", "مباشر"), Signal, Color.White)
@Composable fun BreakingBadge() = Pill(tr("BREAKING", "عاجل"), Signal, Color.White)
@Composable fun TranslatedBadge() = Pill(tr("Translated", "مترجم"), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, Icons.Default.Translate)

@Composable
fun SourceLine(source: String, publishedAt: Long, translated: Boolean = false) {
    val lang = LocalLang.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(source, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Text("  ·  ${relativeTime(publishedAt, lang)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        if (translated) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Default.Translate, tr("Translated", "مترجم"), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Egypt / World switch. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScopeSwitch(scope: Scope, onChange: (Scope) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        SegmentedButton(
            selected = scope == Scope.LOCAL, onClick = { onChange(Scope.LOCAL) },
            shape = SegmentedButtonDefaults.itemShape(0, 2), modifier = Modifier.testTag("scope-local"),
        ) { Text(tr("🇪🇬  Egypt (local)", "🇪🇬  مصر (محلي)")) }
        SegmentedButton(
            selected = scope == Scope.GLOBAL, onClick = { onChange(Scope.GLOBAL) },
            shape = SegmentedButtonDefaults.itemShape(1, 2), modifier = Modifier.testTag("scope-global"),
        ) { Text(tr("🌍  World (global)", "🌍  العالم (عالمي)")) }
    }
}

@Composable
fun HeroStoryCard(story: Story, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val lang = LocalLang.current
    Card(onClick = onClick, modifier = modifier.testTag("story"), shape = MaterialTheme.shapes.large, elevation = CardDefaults.cardElevation(0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Box {
            NewsImage(story.imageUrl, Modifier.fillMaxWidth().aspectRatio(16f / 10f))
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000)))))
            Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (story.isBreaking) BreakingBadge()
                    if (story.sourceCount > 1) Pill(sourcesLabel(story.sourceCount), Color(0x33FFFFFF), Color.White, Icons.Default.Layers)
                }
                Spacer(Modifier.height(8.dp))
                Text(story.headlineFor(lang), style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 3,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("story-title"))
                Spacer(Modifier.height(6.dp))
                Text("${story.leadSourceName} · ${relativeTime(story.lastPublishedAt, lang)}", style = MaterialTheme.typography.labelMedium, color = Color(0xDDFFFFFF))
            }
        }
    }
}

@Composable
fun StoryCard(story: Story, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val lang = LocalLang.current
    Card(onClick = onClick, modifier = modifier.fillMaxWidth().testTag("story"), shape = MaterialTheme.shapes.medium, elevation = CardDefaults.cardElevation(0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Row(Modifier.padding(12.dp)) {
            Column(Modifier.weight(1f)) {
                if (story.isBreaking || story.sourceCount > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (story.isBreaking) BreakingBadge()
                        if (story.sourceCount > 1) Pill(sourcesLabel(story.sourceCount),
                            MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, Icons.Default.Layers)
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Text(story.headlineFor(lang), style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("story-title"))
                Spacer(Modifier.height(6.dp))
                SourceLine(story.leadSourceName, story.lastPublishedAt, story.isTranslated(lang))
            }
            if (story.imageUrl != null) {
                Spacer(Modifier.width(12.dp))
                NewsImage(story.imageUrl, Modifier.size(92.dp).clip(MaterialTheme.shapes.small))
            }
        }
    }
}

@Composable
fun ArticleRow(article: Article, onClick: () -> Unit, onToggleSave: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val lang = LocalLang.current
    Card(onClick = onClick, modifier = modifier.fillMaxWidth().testTag("article"), shape = MaterialTheme.shapes.medium, elevation = CardDefaults.cardElevation(0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Row(Modifier.padding(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    article.titleFor(lang), style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    color = if (article.isRead) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("article-title"),
                )
                val desc = article.descriptionFor(lang)
                if (desc.isNotBlank() && desc != article.titleFor(lang)) {
                    Spacer(Modifier.height(4.dp))
                    Text(desc, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(6.dp))
                SourceLine(article.sourceName, article.publishedAt, article.isTranslated(lang))
            }
            if (article.imageUrl != null || onToggleSave != null) {
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    if (article.imageUrl != null) NewsImage(article.imageUrl, Modifier.size(80.dp).clip(MaterialTheme.shapes.small))
                    if (onToggleSave != null) IconButton(onClick = onToggleSave, modifier = Modifier.testTag("save")) {
                        Icon(if (article.isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, tr("Save", "حفظ"))
                    }
                }
            }
        }
    }
}

@Composable
fun VideoCard(item: MediaItem, onClick: () -> Unit, modifier: Modifier = Modifier, width: Int? = 260) {
    val lang = LocalLang.current
    Column(modifier.then(if (width != null) Modifier.width(width.dp) else Modifier.fillMaxWidth()).clip(MaterialTheme.shapes.medium)
        .clickable(onClick = onClick).testTag("video")) {
        Box {
            NewsImage(item.thumbnailUrl, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium))
            if (item.type != com.smartnews.domain.model.MediaType.IMAGE) {
                Box(Modifier.align(Alignment.Center).size(44.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PlayArrow, null, tint = Color.White)
                }
            }
            Row(Modifier.align(Alignment.TopStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (item.isLive) LiveBadge()
                if (item.isInterview) Pill(tr("Interview", "مقابلة"), Color(0xCC000000), Color.White)
            }
            duration(item.durationSec)?.let { Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) { Pill(it, Color(0xCC000000), Color.White) } }
        }
        Spacer(Modifier.height(6.dp))
        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(item.publisher + (item.publishedAt?.let { " · " + relativeTime(it, lang) } ?: ""),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
fun VideoRow(items: List<MediaItem>, onClick: (MediaItem) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items, key = { it.id }) { VideoCard(it, onClick = { onClick(it) }) }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateFilterRow(selected: DateFilter, onSelect: (DateFilter) -> Unit) {
    val lang = LocalLang.current
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val presets = remember { DateFilter.presets }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("date-filter")) {
        items(presets, key = { it.label }) { f ->
            FilterChip(selected = selected == f, onClick = { onSelect(f) }, label = { Text(f.label(lang)) })
        }
        item(key = "custom") {
            val fmt = DateFormat.getDateInstance(DateFormat.SHORT, Locale(lang))
            FilterChip(
                selected = selected is DateFilter.Custom, onClick = { showPicker = true },
                label = { Text(if (selected is DateFilter.Custom) fmt.format(Date(selected.from)) + " – " + fmt.format(Date(selected.to)) else tr("Custom", "مخصص")) },
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
                }) { Text(tr("Apply", "تطبيق")) }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text(tr("Cancel", "إلغاء")) } },
        ) { DateRangePicker(state = state, modifier = Modifier.weight(1f)) }
    }
}

@Composable
fun EmptyState(title: String, message: String, icon: ImageVector = Icons.Default.Inbox, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(32.dp).testTag("empty"), horizontalAlignment = Alignment.CenterHorizontally) {
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
                TextButton(onClick = onDismiss) { Text(tr("OK", "حسناً")) }
            }
        }
    }
}

@Composable
fun InfoBanner(text: String, icon: ImageVector = Icons.Default.Translate) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
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
                Text(tr("Offline — showing news saved on this phone.", "غير متصل — يتم عرض الأخبار المحفوظة على الهاتف."), style = MaterialTheme.typography.bodySmall)
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
