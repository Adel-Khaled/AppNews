package com.smartnews.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.smartnews.domain.model.Article
import com.smartnews.domain.model.Category
import com.smartnews.domain.model.DateFilter
import com.smartnews.domain.model.MediaType
import com.smartnews.domain.model.SortMode
import com.smartnews.domain.model.Story
import com.smartnews.domain.repository.CategoryRepository
import com.smartnews.domain.repository.NewsRepository
import com.smartnews.domain.repository.SettingsRepository
import com.smartnews.sync.RefreshController
import com.smartnews.ui.components.ArticleRow
import com.smartnews.ui.components.DateFilterRow
import com.smartnews.ui.components.EmptyState
import com.smartnews.ui.components.HeroStoryCard
import com.smartnews.ui.components.LoadMoreEffect
import com.smartnews.ui.components.SectionHeader
import com.smartnews.ui.components.StoryCard
import com.smartnews.ui.components.VideoRow
import com.smartnews.ui.navigation.Nav
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

fun <T> ViewModel.stateOf(flow: Flow<T>, initial: T): StateFlow<T> =
    flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

const val PAGE = 30

// =============================================================== Home

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    repo: NewsRepository,
    categoryRepo: CategoryRepository,
    private val refresher: RefreshController,
) : ViewModel() {
    private val enabledIds = categoryRepo.observeCategories().map { l -> l.filter { it.enabled }.map { it.id } }.distinctUntilChanged()
    val breaking = stateOf(repo.observeBreaking(), emptyList())
    val top = stateOf(repo.observeTopStories(8), emptyList())
    val forYou = stateOf(enabledIds.flatMapLatest { repo.observeStoriesFor(it, DateFilter.Last7Days, SortMode.RELEVANCE, 10) }, emptyList())
    val international = stateOf(repo.observeStories("international", DateFilter.Last7Days, SortMode.NEWEST, 6), emptyList())
    val trending = stateOf(repo.observeTrendingTopics(), emptyList())
    val categories = stateOf(categoryRepo.observeCategories().map { l -> l.filter { it.enabled } }, emptyList())
    val videos = stateOf(repo.observeMedia(MediaType.VIDEO, 12), emptyList())
    val recent = stateOf(repo.observeRecentlyUpdated(10), emptyList())
    val count = stateOf(repo.observeArticleCount(), -1)
    val refresh = refresher.state
    fun onRefresh() = refresher.refresh()
}

@Composable
fun HomeScreen(nav: Nav, vm: HomeViewModel = hiltViewModel()) {
    val breaking by vm.breaking.collectAsStateWithLifecycle()
    val top by vm.top.collectAsStateWithLifecycle()
    val forYou by vm.forYou.collectAsStateWithLifecycle()
    val international by vm.international.collectAsStateWithLifecycle()
    val trending by vm.trending.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val videos by vm.videos.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val count by vm.count.collectAsStateWithLifecycle()
    val refresh by vm.refresh.collectAsStateWithLifecycle()

    ScreenFrame("SmartNews", nav, isRefreshing = refresh.isRefreshing, onRefresh = vm::onRefresh) {
        if (count == 0) {
            if (refresh.isRefreshing) LoadingState("Collecting news from your sources…")
            else EmptyState("No news yet", "Tap Refresh to collect news from your sources.", Icons.Default.Newspaper, "Refresh", vm::onRefresh)
            return@ScreenFrame
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            if (breaking.isNotEmpty()) {
                item { SectionHeader("Breaking") }
                items(breaking, key = { "b" + it.id }) { StoryCard(it, { nav.story(it.id) }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            }
            if (top.isNotEmpty()) {
                item { SectionHeader("Top headlines") }
                item { HeroStoryCard(top.first(), { nav.story(top.first().id) }, Modifier.padding(horizontal = 16.dp)) }
                items(top.drop(1), key = { "t" + it.id }) { StoryCard(it, { nav.story(it.id) }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            }
            if (trending.isNotEmpty()) {
                item { SectionHeader("Trending topics") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(trending) { t ->
                            AssistChip(onClick = { nav.search(t) }, label = { Text(t) }, leadingIcon = { Icon(Icons.Default.TrendingUp, null) })
                        }
                    }
                }
            }
            item { SectionHeader("Categories", "All", { nav.top("categories") }) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(categories, key = { it.id }) { c -> SuggestionChip(onClick = { nav.category(c.id) }, label = { Text(c.displayName()) }) }
                }
            }
            if (forYou.isNotEmpty()) {
                item { SectionHeader("For you", "More", { nav.top("foryou") }) }
                items(forYou.take(6), key = { "f" + it.id }) { StoryCard(it, { nav.story(it.id) }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            }
            if (videos.isNotEmpty()) {
                item { SectionHeader("Videos", "Multimedia", { nav.top("multimedia") }) }
                item { VideoRow(videos) { nav.play(it) } }
            }
            if (international.isNotEmpty()) {
                item { SectionHeader("Around the world") }
                items(international, key = { "i" + it.id }) { StoryCard(it, { nav.story(it.id) }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            }
            if (recent.isNotEmpty()) {
                item { SectionHeader("Recently updated") }
                items(recent, key = { "r" + it.id }) { StoryCard(it, { nav.story(it.id) }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            }
        }
    }
}

@Composable
fun LoadingState(text: String) {
    Column(Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun Category.displayName(language: String = java.util.Locale.getDefault().language) =
    if (language == "ar" && nameAr != null) nameAr else name

// =============================================================== Story feeds (For You / Category)

/** Shared state for any paged, filterable feed of stories. */
abstract class StoryFeedViewModel(settings: SettingsRepository) : ViewModel() {
    val filter = MutableStateFlow<DateFilter>(DateFilter.Last7Days)
    val sort = MutableStateFlow(settings.settings.value.sortMode)
    val limit = MutableStateFlow(PAGE)
    fun setFilter(f: DateFilter) { filter.value = f; limit.value = PAGE }
    fun setSort(s: SortMode) { sort.value = s; limit.value = PAGE }
    fun loadMore() { limit.value += PAGE }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ForYouViewModel @Inject constructor(
    repo: NewsRepository,
    categories: CategoryRepository,
    settings: SettingsRepository,
    private val refresher: RefreshController,
) : StoryFeedViewModel(settings) {
    val enabled = stateOf(categories.observeCategories().map { l -> l.filter { it.enabled } }, emptyList())
    val stories = stateOf(
        combine(categories.observeCategories().map { l -> l.filter { it.enabled }.map { it.id } }, filter, sort, limit) { ids, f, s, l -> Params(ids, f, s, l) }
            .flatMapLatest { p -> repo.observeStoriesFor(p.ids, p.f, p.s, p.l) },
        null as List<Story>?,
    )
    val refresh = refresher.state
    fun onRefresh() = refresher.refresh()
    private data class Params(val ids: List<String>, val f: DateFilter, val s: SortMode, val l: Int)
}

@Composable
fun ForYouScreen(nav: Nav, vm: ForYouViewModel = hiltViewModel()) {
    val stories by vm.stories.collectAsStateWithLifecycle()
    val enabled by vm.enabled.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val refresh by vm.refresh.collectAsStateWithLifecycle()
    ScreenFrame("For You", nav, isRefreshing = refresh.isRefreshing, onRefresh = vm::onRefresh) {
        Text(
            "Built from ${enabled.size} categories & topics you follow",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        StoryList(stories, filter, sort, vm::setFilter, vm::setSort, vm::loadMore, nav,
            emptyText = "Nothing in this period. Try a longer date range, or follow more categories.")
    }
}

@Composable
fun StoryList(
    stories: List<Story>?, filter: DateFilter, sort: SortMode,
    onFilter: (DateFilter) -> Unit, onSort: (SortMode) -> Unit, onLoadMore: () -> Unit, nav: Nav, emptyText: String,
) {
    DateFilterRow(filter, onFilter, sort, onSort)
    val state = rememberLazyListState()
    LoadMoreEffect(state, onLoadMore = onLoadMore)
    when {
        stories == null -> LoadingState("Loading…")
        stories.isEmpty() -> EmptyState("No stories", emptyText)
        else -> LazyColumn(state = state, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { HeroStoryCard(stories.first(), { nav.story(stories.first().id) }) }
            items(stories.drop(1), key = { it.id }) { StoryCard(it, { nav.story(it.id) }) }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CategoryFeedViewModel @Inject constructor(
    handle: SavedStateHandle,
    repo: NewsRepository,
    categories: CategoryRepository,
    settings: SettingsRepository,
    private val refresher: RefreshController,
) : StoryFeedViewModel(settings) {
    val categoryId: String = checkNotNull(handle["id"])
    val category = stateOf(categories.observeCategories().map { l -> l.firstOrNull { it.id == categoryId } }, null)
    val stories = stateOf(
        combine(filter, sort, limit) { f, s, l -> Triple(f, s, l) }.flatMapLatest { (f, s, l) -> repo.observeStories(categoryId, f, s, l) },
        null as List<Story>?,
    )
    val refresh = refresher.state
    fun onRefresh() = refresher.refresh(categoryId)
}

@Composable
fun CategoryFeedScreen(nav: Nav, vm: CategoryFeedViewModel = hiltViewModel()) {
    val category by vm.category.collectAsStateWithLifecycle()
    val stories by vm.stories.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val refresh by vm.refresh.collectAsStateWithLifecycle()
    ScreenFrame(category?.displayName() ?: "Category", nav, isTopLevel = false, isRefreshing = refresh.isRefreshing, onRefresh = vm::onRefresh) {
        if (category?.enabled == false) Text(
            "This category is turned off, so it isn't refreshed automatically. Turn it on in Categories.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp),
        )
        StoryList(stories, filter, sort, vm::setFilter, vm::setSort, vm::loadMore, nav,
            emptyText = "No stories stored for this period. Refresh, or pick a longer date range.")
    }
}

// =============================================================== Latest

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LatestViewModel @Inject constructor(
    private val repo: NewsRepository,
    private val refresher: RefreshController,
) : ViewModel() {
    val filter = MutableStateFlow<DateFilter>(DateFilter.Today)
    private val limit = MutableStateFlow(PAGE * 2)
    val articles = stateOf(
        combine(filter, limit) { f, l -> f to l }.flatMapLatest { (f, l) -> repo.observeArticles(null, null, f, l) },
        null as List<Article>?,
    )
    val refresh = refresher.state
    fun setFilter(f: DateFilter) { filter.value = f; limit.value = PAGE * 2 }
    fun loadMore() { limit.value += PAGE * 2 }
    fun onRefresh() = refresher.refresh()
    fun toggleSave(a: Article) = viewModelScope.launch { repo.setArticleSaved(a.id, !a.isSaved) }
}

@Composable
fun LatestScreen(nav: Nav, vm: LatestViewModel = hiltViewModel()) {
    val articles by vm.articles.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val refresh by vm.refresh.collectAsStateWithLifecycle()
    ScreenFrame("Latest News", nav, isRefreshing = refresh.isRefreshing, onRefresh = vm::onRefresh) {
        DateFilterRow(filter, vm::setFilter)
        val state = rememberLazyListState()
        LoadMoreEffect(state, onLoadMore = vm::loadMore)
        val list = articles
        when {
            list == null -> LoadingState("Loading…")
            list.isEmpty() -> EmptyState("Nothing yet", "No articles published in this period are stored. Refresh or choose another range.")
            else -> LazyColumn(state = state, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { a -> ArticleRow(a, { nav.story(a.storyId) }, { vm.toggleSave(a) }) }
            }
        }
    }
}

// =============================================================== Categories

@HiltViewModel
class CategoriesViewModel @Inject constructor(private val repo: CategoryRepository) : ViewModel() {
    val categories = stateOf(repo.observeCategories(), emptyList())
    val error = MutableStateFlow<String?>(null)
    fun toggle(c: Category) = viewModelScope.launch { repo.setEnabled(c.id, !c.enabled) }
    fun addTopic(name: String, keywords: String) = viewModelScope.launch {
        runCatching { repo.addTopic(name, keywords.split(',').map { it.trim() }.filter { it.isNotEmpty() }) }
            .onFailure { error.value = it.message }
    }
    fun delete(c: Category) = viewModelScope.launch { repo.deleteCustom(c.id) }
}

@Composable
fun CategoriesScreen(nav: Nav, vm: CategoriesViewModel = hiltViewModel()) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    var showAdd by rememberSaveable { mutableStateOf(false) }
    ScreenFrame("Categories", nav) {
        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text("Turn categories on to include them in refreshes, Home and For You. Tap a category to browse it.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val (topics, cats) = categories.partition { it.isTopic }
                items(cats, key = { it.id }) { c -> CategoryRow(c, nav, vm) }
                item { SectionHeader("My topics & keywords") }
                if (topics.isEmpty()) item {
                    Text("Follow anything: a person, team, company or keyword. SmartNews searches for it on every refresh.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(topics, key = { it.id }) { c -> CategoryRow(c, nav, vm) }
            }
            ExtendedFloatingActionButton(
                onClick = { showAdd = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add topic") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
    if (showAdd) {
        var name by rememberSaveable { mutableStateOf("") }
        var kw by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Follow a topic") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Topic name") }, singleLine = true)
                    OutlinedTextField(kw, { kw = it }, label = { Text("Keywords (comma-separated, optional)") })
                }
            },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { vm.addTopic(name, kw); showAdd = false }) { Text("Follow") } },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CategoryRow(c: Category, nav: Nav, vm: CategoriesViewModel) {
    Card(onClick = { nav.category(c.id) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.displayName(), style = MaterialTheme.typography.titleMedium)
                if (c.isTopic) Text(c.keywords.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (c.isCustom) IconButton(onClick = { vm.delete(c) }) { Icon(Icons.Default.Delete, "Remove topic") }
            Switch(checked = c.enabled, onCheckedChange = { vm.toggle(c) })
        }
    }
}
