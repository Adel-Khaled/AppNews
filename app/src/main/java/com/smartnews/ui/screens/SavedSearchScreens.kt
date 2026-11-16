package com.smartnews.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.smartnews.domain.model.Article
import com.smartnews.domain.model.ContentType
import com.smartnews.domain.model.DateFilter
import com.smartnews.domain.model.SortMode
import com.smartnews.domain.repository.CategoryRepository
import com.smartnews.domain.repository.NewsRepository
import com.smartnews.domain.repository.SearchResults
import com.smartnews.domain.repository.SourceRepository
import com.smartnews.ui.components.ArticleRow
import com.smartnews.ui.components.DateFilterRow
import com.smartnews.ui.components.EmptyState
import com.smartnews.ui.components.ErrorBanner
import com.smartnews.ui.components.SectionHeader
import com.smartnews.ui.components.StoryCard
import com.smartnews.ui.components.VideoRow
import com.smartnews.ui.components.relativeTime
import com.smartnews.ui.navigation.Nav
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// =============================================================== Saved & history

@HiltViewModel
class SavedViewModel @Inject constructor(private val repo: NewsRepository, categoryRepo: CategoryRepository) : ViewModel() {
    val articles = stateOf(repo.observeSavedArticles(), emptyList())
    val stories = stateOf(repo.observeSavedStories(), emptyList())
    val media = stateOf(repo.observeSavedMedia(), emptyList())
    val history = stateOf(repo.observeHistory(300), emptyList())
    val categories = stateOf(categoryRepo.observeCategories(), emptyList())
    fun unsave(a: Article) = viewModelScope.launch { repo.setArticleSaved(a.id, !a.isSaved) }
    fun clearHistory() = viewModelScope.launch { repo.clearHistory() }
}

@Composable
fun SavedScreen(nav: Nav, vm: SavedViewModel = hiltViewModel()) {
    val articles by vm.articles.collectAsStateWithLifecycle()
    val stories by vm.stories.collectAsStateWithLifecycle()
    val media by vm.media.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var q by rememberSaveable { mutableStateOf("") }
    val names = categories.associate { it.id to it.displayName() }
    fun match(vararg fields: String) = q.isBlank() || fields.any { it.contains(q, ignoreCase = true) }

    ScreenFrame("Saved Articles", nav) {
        TabRow(selectedTabIndex = tab) {
            listOf("Articles", "Stories", "Videos", "History").forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        OutlinedTextField(
            q, { q = it }, placeholder = { Text("Search saved content") }, singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        when (tab) {
            0 -> {
                val filtered = articles.filter { match(it.title, it.description, it.sourceName) }
                if (filtered.isEmpty()) EmptyState("No saved articles", "Tap the bookmark on any article to keep it here — it stays after restarts and offline.", Icons.Default.Bookmarks)
                else LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    filtered.groupBy { names[it.categoryId] ?: it.categoryId }.toSortedMap().forEach { (cat, list) ->
                        item(key = "h-$cat") { Text(cat, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                        items(list, key = { it.id }) { a -> ArticleRow(a, { nav.story(a.storyId) }, { vm.unsave(a) }) }
                    }
                }
            }
            1 -> {
                val filtered = stories.filter { match(it.headline, it.leadSourceName) }
                if (filtered.isEmpty()) EmptyState("No saved stories", "Save a whole story (all its coverage) from its page.")
                else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filtered, key = { it.id }) { s -> StoryCard(s, { nav.story(s.id) }) }
                }
            }
            2 -> {
                val filtered = media.filter { match(it.title, it.publisher) }
                if (filtered.isEmpty()) EmptyState("No saved videos", "Videos you save appear here. Playback needs internet.")
                else Column { VideoRow(filtered) { nav.play(it) } }
            }
            else -> {
                val filtered = history.filter { match(it.article.title, it.article.sourceName) }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${history.size} recently viewed", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    if (history.isNotEmpty()) TextButton(onClick = { vm.clearHistory() }) { Text("Clear history") }
                }
                if (filtered.isEmpty()) EmptyState("No reading history", "Articles you open are listed here.", Icons.Default.History)
                else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filtered, key = { it.article.id }) { h ->
                        Column {
                            Text("Viewed ${relativeTime(h.viewedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ArticleRow(h.article, { nav.story(h.article.storyId) })
                        }
                    }
                }
            }
        }
    }
}

// =============================================================== Search

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repo: NewsRepository,
    categoryRepo: CategoryRepository,
    sourceRepo: SourceRepository,
) : ViewModel() {
    val categories = stateOf(categoryRepo.observeCategories(), emptyList())
    val sources = stateOf(sourceRepo.observeSources(), emptyList())
    val results = MutableStateFlow<SearchResults?>(null)
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    private var job: Job? = null

    fun search(q: String, categoryId: String?, sourceId: String?, filter: DateFilter, type: ContentType, sort: SortMode, online: Boolean) {
        if (q.isBlank()) return
        job?.cancel()
        job = viewModelScope.launch {
            loading.value = true
            // Local results first (instant, offline-capable), then merge online results.
            results.value = repo.search(q, categoryId, sourceId, filter, type, sort, includeOnline = false)
            if (online) {
                val r = repo.search(q, categoryId, sourceId, filter, type, sort, includeOnline = true)
                results.value = r
                error.value = r.onlineError
            }
            loading.value = false
        }
    }
    fun toggleSave(a: Article) = viewModelScope.launch { repo.setArticleSaved(a.id, !a.isSaved) }
}

@Composable
fun SearchScreen(nav: Nav, initialQuery: String, vm: SearchViewModel = hiltViewModel()) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var q by rememberSaveable { mutableStateOf(initialQuery) }
    var type by rememberSaveable { mutableStateOf(ContentType.ALL) }
    var cat by rememberSaveable { mutableStateOf<String?>(null) }
    var src by rememberSaveable { mutableStateOf<String?>(null) }
    var filter by androidx.compose.runtime.remember { mutableStateOf<DateFilter>(DateFilter.All) }
    var sort by rememberSaveable { mutableStateOf(SortMode.RELEVANCE) }
    var online by rememberSaveable { mutableStateOf(true) }
    val run = { vm.search(q, cat, src, filter, type, sort, online) }
    LaunchedEffect(Unit) { if (initialQuery.isNotBlank()) run() }

    ScreenFrame("Search", nav, isRefreshing = loading, showSearch = false) {
        OutlinedTextField(
            q, { q = it }, singleLine = true, placeholder = { Text("People, places, companies, events, keywords…") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (q.isNotEmpty()) IconButton(onClick = { q = "" }) { Icon(Icons.Default.Clear, "Clear") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { run() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ContentType.entries) { t ->
                FilterChip(selected = type == t, onClick = { type = t; run() }, label = { Text(t.name.lowercase().replaceFirstChar { it.uppercase() }) })
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = cat == null, onClick = { cat = null; run() }, label = { Text("All categories") }) }
            items(categories, key = { it.id }) { c -> FilterChip(selected = cat == c.id, onClick = { cat = c.id; run() }, label = { Text(c.displayName()) }) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = src == null, onClick = { src = null; run() }, label = { Text("All sources") }) }
            items(sources, key = { it.id }) { s -> FilterChip(selected = src == s.id, onClick = { src = s.id; run() }, label = { Text(s.name) }) }
        }
        DateFilterRow(filter, { filter = it; run() }, sort, { sort = it; run() })
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Also search online (Google News, plus backend APIs if configured)", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Switch(checked = online, onCheckedChange = { online = it })
        }
        ErrorBanner(error) { vm.error.value = null }

        val r = results
        when {
            r == null -> EmptyState("Search everything", "Searches articles, videos and images stored on this phone — including saved and older news — plus new online results.", Icons.Default.Search)
            r.articles.isEmpty() && r.media.isEmpty() && !loading -> EmptyState("No results", "Try fewer words, a longer date range or turn on online search.")
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (r.media.isNotEmpty()) {
                    item { SectionHeader("Videos & images (${r.media.size})") }
                    item { VideoRow(r.media) { m -> if (m.type == com.smartnews.domain.model.MediaType.IMAGE) nav.openUrl(m.url) else nav.play(m) } }
                }
                if (r.articles.isNotEmpty()) item { SectionHeader("Articles (${r.articles.size})") }
                items(r.articles, key = { it.id }) { a ->
                    ArticleRow(a, { nav.story(a.storyId) }, { vm.toggleSave(a) }, Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}
