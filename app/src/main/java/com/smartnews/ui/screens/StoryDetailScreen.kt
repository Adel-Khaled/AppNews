package com.smartnews.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.smartnews.domain.model.Article
import com.smartnews.domain.model.Explanation
import com.smartnews.domain.model.MediaItem
import com.smartnews.domain.model.MediaType
import com.smartnews.domain.repository.AiRepository
import com.smartnews.domain.repository.NewsRepository
import com.smartnews.domain.repository.SettingsRepository
import com.smartnews.ui.components.AiBadge
import com.smartnews.ui.components.BreakingBadge
import com.smartnews.ui.components.ExtractBadge
import com.smartnews.ui.components.NewsImage
import com.smartnews.ui.components.Pill
import com.smartnews.ui.components.SectionHeader
import com.smartnews.ui.components.VideoRow
import com.smartnews.ui.components.dateTime
import com.smartnews.ui.components.relativeTime
import com.smartnews.ui.navigation.Nav
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ExplainState {
    data object Idle : ExplainState
    data object Loading : ExplainState
    data class Ready(val e: Explanation) : ExplainState
    data class Error(val message: String) : ExplainState
}

@HiltViewModel
class StoryDetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: NewsRepository,
    private val ai: AiRepository,
    settings: SettingsRepository,
) : ViewModel() {
    val storyId: String = checkNotNull(handle["id"])
    val story = stateOf(repo.observeStory(storyId), null)
    val articles = stateOf(repo.observeStoryArticles(storyId), emptyList())
    val media = stateOf(repo.observeStoryMedia(storyId), emptyList())
    val backendConfigured = stateOf(settings.settings.map { it.backendConfigured }, false)
    val mediaLoading = MutableStateFlow(false)
    val mediaError = MutableStateFlow<String?>(null)
    val summaryLoading = MutableStateFlow(true)
    val explainState = MutableStateFlow<ExplainState>(ExplainState.Idle)

    init {
        viewModelScope.launch {
            val lead = repo.observeStoryArticles(storyId).filter { it.isNotEmpty() }.first().first()
            repo.recordView(lead)
            ai.summarize(lead.id)
            ai.summarizeStory(storyId)
            summaryLoading.value = false
        }
        loadMedia(false)
    }

    fun loadMedia(force: Boolean) = viewModelScope.launch {
        mediaLoading.value = true
        repo.loadStoryMedia(storyId, force).onFailure { mediaError.value = it.message }.onSuccess { mediaError.value = null }
        mediaLoading.value = false
    }

    fun toggleSaveStory() = viewModelScope.launch {
        val s = story.value ?: return@launch
        repo.setStorySaved(s.id, !s.isSaved)
        articles.value.firstOrNull()?.let { repo.setArticleSaved(it.id, !s.isSaved) }
    }

    fun toggleSaveArticle(a: Article) = viewModelScope.launch { repo.setArticleSaved(a.id, !a.isSaved) }
    fun toggleSaveMedia(m: MediaItem) = viewModelScope.launch { repo.setMediaSaved(m.id, !m.isSaved) }

    fun explain(force: Boolean = false) = viewModelScope.launch {
        explainState.value = ExplainState.Loading
        explainState.value = ai.explain(storyId, force).fold({ ExplainState.Ready(it) }, { ExplainState.Error(it.message ?: "Failed") })
    }
    fun closeExplain() { explainState.value = ExplainState.Idle }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryDetailScreen(nav: Nav, vm: StoryDetailViewModel = hiltViewModel()) {
    val story by vm.story.collectAsStateWithLifecycle()
    val articles by vm.articles.collectAsStateWithLifecycle()
    val media by vm.media.collectAsStateWithLifecycle()
    val mediaLoading by vm.mediaLoading.collectAsStateWithLifecycle()
    val mediaError by vm.mediaError.collectAsStateWithLifecycle()
    val summaryLoading by vm.summaryLoading.collectAsStateWithLifecycle()
    val explain by vm.explainState.collectAsStateWithLifecycle()
    val backendConfigured by vm.backendConfigured.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var viewer by remember { mutableStateOf<MediaItem?>(null) }

    val s = story
    val lead = articles.firstOrNull()
    ScreenFrame(
        title = s?.leadSourceName ?: "Story", nav = nav, isTopLevel = false, showSearch = false,
        actions = {
            if (s != null) {
                IconButton(onClick = { lead?.let { nav.share(s.headline, it.url) } }) { Icon(Icons.Default.Share, "Share") }
                IconButton(onClick = { vm.toggleSaveStory() }) { Icon(if (s.isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, "Save") }
            }
        },
    ) {
        if (s == null || lead == null) { LoadingState("Opening story…"); return@ScreenFrame }
        val videos = media.filter { it.type != MediaType.IMAGE && !it.isInterview }
        val interviews = media.filter { it.type != MediaType.IMAGE && it.isInterview }
        val images = media.filter { it.type == MediaType.IMAGE }.distinctBy { it.url }
        val entities = articles.flatMap { it.entities }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(6).map { it.key }
        val coverageIndex = 4

        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 32.dp)) {
            item { NewsImage(s.imageUrl ?: images.firstOrNull()?.url, Modifier.fillMaxWidth().aspectRatio(16f / 9f)) }
            item {
                Column(Modifier.padding(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (s.isBreaking) BreakingBadge()
                        Pill("${s.sourceCount} publisher${if (s.sourceCount == 1) "" else "s"}", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, Icons.Default.Layers)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(s.headline, style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(10.dp))
                    Text("Published ${dateTime(lead.publishedAt)} by ${lead.sourceName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Collected by SmartNews ${dateTime(lead.collectedAt)} · last update ${relativeTime(s.lastPublishedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { SummaryCard(s.aiSummary, s.aiKeyPoints, lead, summaryLoading) }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { nav.openUrl(lead.url) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Original")
                    }
                    FilledTonalButton(onClick = { vm.explain() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Explain this")
                    }
                }
                if (articles.size > 1) TextButton(onClick = { scope.launch { listState.animateScrollToItem(coverageIndex) } }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("View related coverage (${articles.size} articles)")
                }
            }
            // index 4: coverage
            item {
                SectionHeader("Coverage by publisher")
                Text(
                    "Each publisher's reporting is shown as they published it. Where details differ, read each report — SmartNews does not merge them into one version.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            items(articles, key = { it.id }) { a -> PublisherReport(a, nav, onSave = { vm.toggleSaveArticle(a) }) }

            item { SectionHeader("Related videos") }
            item {
                when {
                    videos.isNotEmpty() -> VideoRow(videos) { nav.play(it) }
                    mediaLoading -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Finding videos about this event…")
                    }
                    else -> Column(Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            if (backendConfigured) "No videos matched this specific event yet." else
                                "No videos were embedded by the publishers. Matching YouTube coverage needs the SmartNews backend (Settings → Backend).",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        mediaError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = { vm.loadMedia(true) }) { Text("Search again") }
                    }
                }
            }
            if (interviews.isNotEmpty()) {
                item { SectionHeader("Interviews & analysis") }
                item { VideoRow(interviews) { nav.play(it) } }
            }
            if (images.isNotEmpty()) {
                item { SectionHeader("Images") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(images, key = { it.id }) { img ->
                            Column(Modifier.width(200.dp).clickable { viewer = img }) {
                                NewsImage(img.url, Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(MaterialTheme.shapes.small), img.title)
                                Text(img.attribution, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                }
            }
            if (entities.isNotEmpty()) {
                item { SectionHeader("People, places & organisations") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(entities) { e -> AssistChip(onClick = { nav.search(e) }, label = { Text(e.replaceFirstChar { it.titlecase() }) }) }
                    }
                }
            }
        }
    }

    viewer?.let { img ->
        AlertDialog(
            onDismissRequest = { viewer = null },
            title = { Text(img.attribution, style = MaterialTheme.typography.titleSmall) },
            text = { NewsImage(img.url, Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(MaterialTheme.shapes.small), img.title) },
            confirmButton = { TextButton(onClick = { viewer = null }) { Text("Close") } },
            dismissButton = {
                val src = articles.firstOrNull { it.id == img.articleId }?.url ?: img.url
                TextButton(onClick = { nav.openUrl(src) }) { Text("Open source") }
            },
        )
    }

    if (explain != ExplainState.Idle) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = vm::closeExplain, sheetState = sheetState) {
            ExplainContent(explain, onRetry = { vm.explain(force = true) })
        }
    }
}

@Composable
private fun SummaryCard(storySummary: String?, storyPoints: List<String>, lead: Article, loading: Boolean) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            val summary = storySummary ?: lead.summary
            val isAi = storySummary != null || lead.summaryIsAi
            val points = if (storySummary != null) storyPoints else lead.keyPoints
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Summary", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (summary != null) { if (isAi) AiBadge() else ExtractBadge() }
            }
            Spacer(Modifier.height(8.dp))
            when {
                summary != null -> Text(summary, style = MaterialTheme.typography.bodyLarge)
                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Summarising…")
                }
                else -> Text(lead.description.ifBlank { "No summary available. Open the original article." }, style = MaterialTheme.typography.bodyLarge)
            }
            if (points.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(if (storySummary != null) "Key developments" else "Key points", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                points.forEach { p ->
                    Row(Modifier.padding(top = 6.dp)) { Text("•  "); Text(p, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            if (summary != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    if (isAi) "Written by an AI model from the publishers' text. It can make mistakes — check the original reports."
                    else "Sentences selected automatically from ${lead.sourceName}'s own text.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PublisherReport(a: Article, nav: Nav, onSave: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        HorizontalDivider()
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(a.sourceName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                Text("Published ${dateTime(a.publishedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onSave) { Icon(if (a.isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, "Save") }
        }
        Text(a.title, style = MaterialTheme.typography.titleMedium)
        if (a.description.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(a.description, style = MaterialTheme.typography.bodyMedium, maxLines = 6, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = { nav.openUrl(a.url) }, modifier = Modifier.padding(top = 8.dp)) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Read on ${a.sourceName}")
        }
    }
}

@Composable
private fun ExplainContent(state: ExplainState, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Explain this news", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            AiBadge()
        }
        Spacer(Modifier.height(12.dp))
        when (state) {
            ExplainState.Loading, ExplainState.Idle -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Reading every publisher's report…")
            }
            is ExplainState.Error -> {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry) { Text("Try again") }
            }
            is ExplainState.Ready -> {
                val e = state.e
                ExplainSection("What happened", e.explanation)
                ExplainSection("Background", e.background)
                ExplainSection("Why it matters", e.whyItMatters)
                if (e.differences.isNotEmpty()) {
                    Text("Where sources differ", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                    e.differences.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
                }
                if (e.unconfirmed.isNotEmpty()) {
                    Text("Not yet confirmed", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                    e.unconfirmed.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "AI-generated from the articles in this story${if (e.model.isNotBlank()) " (${e.model})" else ""}. It is not original reporting and can be wrong.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRetry) { Text("Regenerate") }
            }
        }
    }
}

@Composable
private fun ExplainSection(title: String, body: String) {
    if (body.isBlank()) return
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    Text(body, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
}
