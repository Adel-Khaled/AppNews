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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
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
import com.smartnews.ui.components.BreakingBadge
import com.smartnews.ui.components.LocalLang
import com.smartnews.ui.components.NewsImage
import com.smartnews.ui.components.Pill
import com.smartnews.ui.components.SectionHeader
import com.smartnews.ui.components.TranslatedBadge
import com.smartnews.ui.components.VideoRow
import com.smartnews.ui.components.dateTime
import com.smartnews.ui.components.sourcesLabel
import com.smartnews.ui.components.tr
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
    val translating = MutableStateFlow(true)
    val explainState = MutableStateFlow<ExplainState>(ExplainState.Idle)

    init {
        viewModelScope.launch {
            val lead = repo.observeStoryArticles(storyId).filter { it.isNotEmpty() }.first().first()
            repo.recordView(lead)
            repo.translateStory(storyId)
            translating.value = false
            ai.summarizeStory(storyId)
        }
        viewModelScope.launch {
            mediaLoading.value = true
            repo.loadStoryMedia(storyId, false)
            mediaLoading.value = false
        }
    }

    fun toggleSave() = viewModelScope.launch {
        val s = story.value ?: return@launch
        repo.setStorySaved(s.id, !s.isSaved)
        articles.value.firstOrNull()?.let { repo.setArticleSaved(it.id, !s.isSaved) }
    }

    fun toggleSaveArticle(a: Article) = viewModelScope.launch { repo.setArticleSaved(a.id, !a.isSaved) }

    fun explain(force: Boolean = false) = viewModelScope.launch {
        explainState.value = ExplainState.Loading
        explainState.value = ai.explain(storyId, force).fold({ ExplainState.Ready(it) }, { ExplainState.Error(it.message ?: "Failed") })
    }
    fun closeExplain() { explainState.value = ExplainState.Idle }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryDetailScreen(nav: Nav, vm: StoryDetailViewModel = hiltViewModel()) {
    val lang = LocalLang.current
    val story by vm.story.collectAsStateWithLifecycle()
    val articles by vm.articles.collectAsStateWithLifecycle()
    val media by vm.media.collectAsStateWithLifecycle()
    val mediaLoading by vm.mediaLoading.collectAsStateWithLifecycle()
    val translating by vm.translating.collectAsStateWithLifecycle()
    val explain by vm.explainState.collectAsStateWithLifecycle()
    val backendConfigured by vm.backendConfigured.collectAsStateWithLifecycle()
    var showOriginal by rememberSaveable { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<MediaItem?>(null) }

    val s = story
    val lead = articles.firstOrNull()
    val viewLang = if (showOriginal) "-" else lang
    ScreenFrame(
        title = s?.leadSourceName ?: tr("Story", "الخبر"), nav = nav, isTopLevel = false,
        actions = {
            if (s != null) {
                IconButton(onClick = { lead?.let { nav.share(s.headlineFor(lang), it.url) } }) { Icon(Icons.Default.Share, tr("Share", "مشاركة")) }
                IconButton(onClick = { vm.toggleSave() }, modifier = Modifier.testTag("save-story")) {
                    Icon(if (s.isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, tr("Save", "حفظ"))
                }
            }
        },
    ) {
        if (s == null || lead == null) { LoadingState(tr("Opening…", "جاري الفتح…")); return@ScreenFrame }
        val videos = media.filter { it.type != MediaType.IMAGE }
        val images = media.filter { it.type == MediaType.IMAGE }.distinctBy { it.url }
        val anyTranslated = articles.any { it.isTranslated(lang) }

        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.testTag("story-detail")) {
            item { NewsImage(s.imageUrl ?: images.firstOrNull()?.url, Modifier.fillMaxWidth().aspectRatio(16f / 9f)) }
            item {
                Column(Modifier.padding(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (s.isBreaking) BreakingBadge()
                        Pill(sourcesLabel(s.sourceCount), MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                        if (anyTranslated && !showOriginal) TranslatedBadge()
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(lead.titleFor(viewLang), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("detail-title"))
                    Spacer(Modifier.height(10.dp))
                    Text(tr("Published ${dateTime(lead.publishedAt, lang)} · ${lead.sourceName}", "نُشر ${dateTime(lead.publishedAt, lang)} · ${lead.sourceName}"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (translating) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(Modifier.width(6.dp))
                        Text(tr("Translating…", "جاري الترجمة…"), style = MaterialTheme.typography.bodySmall)
                    }
                    if (anyTranslated) TextButton(onClick = { showOriginal = !showOriginal }) {
                        Text(if (showOriginal) tr("Show translation", "عرض الترجمة") else tr("Show original text", "عرض النص الأصلي"))
                    }
                }
            }
            val summary = s.aiSummary ?: lead.descriptionFor(viewLang).takeIf { it.isNotBlank() && it != lead.titleFor(viewLang) }
            if (summary != null) item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f))) {
                    Column(Modifier.padding(16.dp)) {
                        Text(tr("Summary", "الملخص"), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(summary, style = MaterialTheme.typography.bodyLarge)
                        s.aiKeyPoints.forEach { p -> Row(Modifier.padding(top = 6.dp)) { Text("•  "); Text(p, style = MaterialTheme.typography.bodyMedium) } }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            when {
                                s.aiSummary != null -> tr("AI-generated from the publishers' reports.", "مُلخص مُنشأ بالذكاء الاصطناعي من تقارير الناشرين.")
                                lead.isTranslated(lang) && !showOriginal -> tr("Machine translation of ${lead.sourceName}'s summary.", "ترجمة آلية لملخص ${lead.sourceName}.")
                                else -> tr("Summary from ${lead.sourceName}.", "ملخص من ${lead.sourceName}.")
                            },
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { nav.openUrl(lead.url) }, modifier = Modifier.weight(1f).testTag("open-original")) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Read original", "اقرأ الخبر كاملاً"))
                    }
                    if (backendConfigured) FilledTonalButton(onClick = { vm.explain() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Explain", "اشرح الخبر"))
                    }
                }
            }
            if (videos.isNotEmpty() || mediaLoading) {
                item { SectionHeader(tr("Videos", "فيديو")) }
                item {
                    if (videos.isNotEmpty()) VideoRow(videos) { nav.play(it) }
                    else Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp))
                        Text(tr("Looking for videos…", "جاري البحث عن فيديو…"), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (images.size > 1) {
                item { SectionHeader(tr("Images", "صور")) }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(images, key = { it.id }) { img ->
                            Column(Modifier.width(200.dp).clickable { viewer = img }) {
                                NewsImage(img.url, Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(MaterialTheme.shapes.small))
                                Text(img.attribution, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                }
            }
            if (articles.size > 1) {
                item {
                    SectionHeader(tr("Coverage from ${articles.size} articles", "التغطية من ${articles.size} مقالات"))
                    Text(tr("Each publisher's report is shown separately.", "يُعرض تقرير كل ناشر بشكل منفصل."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                }
                items(articles.drop(1), key = { it.id }) { a -> PublisherReport(a, viewLang, nav) { vm.toggleSaveArticle(a) } }
            }
        }
    }

    viewer?.let { img ->
        AlertDialog(
            onDismissRequest = { viewer = null },
            title = { Text(img.attribution, style = MaterialTheme.typography.titleSmall) },
            text = { NewsImage(img.url, Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(MaterialTheme.shapes.small)) },
            confirmButton = { TextButton(onClick = { viewer = null }) { Text(tr("Close", "إغلاق")) } },
        )
    }

    if (explain != ExplainState.Idle) {
        ModalBottomSheet(onDismissRequest = vm::closeExplain, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            ExplainContent(explain) { vm.explain(force = true) }
        }
    }
}

@Composable
private fun PublisherReport(a: Article, viewLang: String, nav: Nav, onSave: () -> Unit) {
    val lang = LocalLang.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        HorizontalDivider()
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(a.sourceName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                Text(dateTime(a.publishedAt, lang), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onSave) { Icon(if (a.isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, tr("Save", "حفظ")) }
        }
        Text(a.titleFor(viewLang), style = MaterialTheme.typography.titleMedium)
        val d = a.descriptionFor(viewLang)
        if (d.isNotBlank() && d != a.titleFor(viewLang)) {
            Spacer(Modifier.height(4.dp))
            Text(d, style = MaterialTheme.typography.bodyMedium, maxLines = 6, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = { nav.openUrl(a.url) }, modifier = Modifier.padding(top = 8.dp)) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Read on ${a.sourceName}", "اقرأ على ${a.sourceName}"))
        }
    }
}

@Composable
private fun ExplainContent(state: ExplainState, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
        Text(tr("Explain this news", "شرح الخبر"), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        when (state) {
            ExplainState.Loading, ExplainState.Idle -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text(tr("Reading the reports…", "جاري قراءة التقارير…"))
            }
            is ExplainState.Error -> { Text(state.message, color = MaterialTheme.colorScheme.error); TextButton(onClick = onRetry) { Text(tr("Try again", "إعادة المحاولة")) } }
            is ExplainState.Ready -> {
                val e = state.e
                Section(tr("What happened", "ماذا حدث"), e.explanation)
                Section(tr("Background", "الخلفية"), e.background)
                Section(tr("Why it matters", "لماذا يهم"), e.whyItMatters)
                if (e.differences.isNotEmpty()) Section(tr("Where sources differ", "اختلاف المصادر"), e.differences.joinToString("\n") { "• $it" })
                if (e.unconfirmed.isNotEmpty()) Section(tr("Not yet confirmed", "غير مؤكد بعد"), e.unconfirmed.joinToString("\n") { "• $it" })
                Spacer(Modifier.height(12.dp))
                Text(tr("AI-generated. It can make mistakes.", "مُنشأ بالذكاء الاصطناعي وقد يحتوي على أخطاء."), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun Section(title: String, body: String) {
    if (body.isBlank()) return
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    Text(body, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
}
