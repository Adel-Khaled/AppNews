package com.smartnews.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.smartnews.data.seed.DefaultCatalog
import com.smartnews.domain.model.Article
import com.smartnews.domain.model.DateFilter
import com.smartnews.domain.model.Scope
import com.smartnews.domain.model.Story
import com.smartnews.domain.repository.CategoryRepository
import com.smartnews.domain.repository.NewsRepository
import com.smartnews.domain.repository.SettingsRepository
import com.smartnews.sync.RefreshController
import com.smartnews.ui.components.ArticleRow
import com.smartnews.ui.components.DateFilterRow
import com.smartnews.ui.components.EmptyState
import com.smartnews.ui.components.HeroStoryCard
import com.smartnews.ui.components.InfoBanner
import com.smartnews.ui.components.LoadMoreEffect
import com.smartnews.ui.components.LocalLang
import com.smartnews.ui.components.ScopeSwitch
import com.smartnews.ui.components.StoryCard
import com.smartnews.ui.components.tr
import com.smartnews.ui.navigation.Nav
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Shared Egypt/World and language controls. */
abstract class ScopedViewModel(protected val settings: SettingsRepository) : ViewModel() {
    val scope = stateOf(settings.settings.map { it.scope }, settings.settings.value.scope)
    fun setScope(s: Scope) = viewModelScope.launch { settings.update { it.copy(scope = s) } }
    fun toggleLanguage() = viewModelScope.launch { settings.update { it.copy(language = if (it.language == "ar") "en" else "ar") } }
}

// =============================================================== Home: one tab per category

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: NewsRepository,
    categoryRepo: CategoryRepository,
    settings: SettingsRepository,
    private val refresher: RefreshController,
) : ScopedViewModel(settings) {
    val categories = stateOf(
        categoryRepo.observeCategories().map { all -> DefaultCatalog.tabCategoryIds.mapNotNull { id -> all.firstOrNull { it.id == id } } },
        emptyList(),
    )
    /** null = "All". */
    val selected = MutableStateFlow<String?>(null)
    private val limit = MutableStateFlow(PAGE)
    val stories = stateOf(
        combine(selected, scope, limit) { c, s, l -> Triple(c, s, l) }.flatMapLatest { (c, s, l) -> repo.observeHome(c, s, l) },
        null as List<Story>?,
    )
    val pending = stateOf(repo.observePendingTranslation(), 0)
    val refresh = refresher.state
    private val autoRefreshed = mutableSetOf<String>()

    fun select(id: String?) { selected.value = id; limit.value = PAGE }
    fun loadMore() { limit.value += PAGE }
    fun onRefresh() = refresher.refresh(selected.value)

    /** A tab with nothing stored yet fetches its own category once, right away. */
    fun onEmptyTab() {
        val key = (selected.value ?: "all") + scope.value
        if (refresh.value.isRefreshing || !autoRefreshed.add(key)) return
        refresher.refresh(selected.value)
    }
}

@Composable
fun HomeScreen(nav: Nav, vm: HomeViewModel = hiltViewModel()) {
    val lang = LocalLang.current
    val categories by vm.categories.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val stories by vm.stories.collectAsStateWithLifecycle()
    val scope by vm.scope.collectAsStateWithLifecycle()
    val refresh by vm.refresh.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val tabs = listOf<Pair<String?, String>>(null to tr("All", "الكل")) + categories.map { it.id to it.label(lang) }
    val selectedIndex = tabs.indexOfFirst { it.first == selected }.coerceAtLeast(0)

    ScreenFrame(
        title = tr("SmartNews", "سمارت نيوز"), nav = nav, isRefreshing = refresh.isRefreshing, onRefresh = vm::onRefresh,
        actions = {
            TextButton(onClick = { vm.toggleLanguage() }, modifier = Modifier.testTag("lang-toggle")) { Text(if (lang == "ar") "English" else "عربي") }
            IconButton(onClick = { nav.settings() }, modifier = Modifier.testTag("settings")) { Icon(Icons.Default.Settings, tr("Settings", "الإعدادات")) }
        },
    ) {
        ScopeSwitch(scope, vm::setScope)
        ScrollableTabRow(selectedTabIndex = selectedIndex, edgePadding = 12.dp, modifier = Modifier.testTag("tab-row")) {
            tabs.forEachIndexed { i, (id, label) ->
                Tab(selected = i == selectedIndex, onClick = { vm.select(id) }, text = { Text(label) },
                    modifier = Modifier.testTag("tab-" + (id ?: "all")))
            }
        }
        if (refresh.translating && pending > 0) InfoBanner(tr("Translating $pending articles into English…", "جاري ترجمة $pending خبراً إلى العربية…"))

        val list = stories
        val state = rememberLazyListState()
        LaunchedEffect(selected, scope) { state.scrollToItem(0) }
        LoadMoreEffect(state, onLoadMore = vm::loadMore)
        when {
            list == null -> LoadingState(tr("Loading…", "جاري التحميل…"))
            list.isEmpty() -> {
                LaunchedEffect(selected, scope) { vm.onEmptyTab() }
                if (refresh.isRefreshing) LoadingState(tr("Fetching the latest news…", "جاري جلب آخر الأخبار…"))
                else EmptyState(
                    tr("No news here yet", "لا توجد أخبار هنا بعد"),
                    tr("Pull down or tap Refresh to fetch news for this section.", "اسحب للأسفل أو اضغط تحديث لجلب أخبار هذا القسم."),
                    Icons.Default.Newspaper, tr("Refresh", "تحديث"), vm::onRefresh,
                )
            }
            else -> LazyColumn(state = state, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.testTag("home-list")) {
                item(key = "hero-" + list.first().id) { HeroStoryCard(list.first(), { nav.story(list.first().id) }) }
                items(list.drop(1), key = { it.id }) { StoryCard(it, { nav.story(it.id) }) }
            }
        }
    }
}

// =============================================================== Latest

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LatestViewModel @Inject constructor(
    private val repo: NewsRepository,
    settings: SettingsRepository,
    private val refresher: RefreshController,
) : ScopedViewModel(settings) {
    val filter = MutableStateFlow<DateFilter>(DateFilter.Today)
    private val limit = MutableStateFlow(PAGE * 2)
    val articles = stateOf(
        combine(filter, scope, limit) { f, s, l -> Triple(f, s, l) }.flatMapLatest { (f, s, l) -> repo.observeArticles(s, null, f, l) },
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
    val scope by vm.scope.collectAsStateWithLifecycle()
    val refresh by vm.refresh.collectAsStateWithLifecycle()
    ScreenFrame(tr("Latest News", "آخر الأخبار"), nav, isRefreshing = refresh.isRefreshing, onRefresh = vm::onRefresh) {
        ScopeSwitch(scope, vm::setScope)
        DateFilterRow(filter, vm::setFilter)
        val state = rememberLazyListState()
        LoadMoreEffect(state, onLoadMore = vm::loadMore)
        val list = articles
        when {
            list == null -> LoadingState(tr("Loading…", "جاري التحميل…"))
            list.isEmpty() -> EmptyState(
                tr("Nothing in this period", "لا توجد أخبار في هذه الفترة"),
                tr("Choose a longer period or refresh.", "اختر فترة أطول أو اضغط تحديث."),
            )
            else -> LazyColumn(state = state, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.testTag("latest-list")) {
                items(list, key = { it.id }) { a -> ArticleRow(a, { nav.story(a.storyId) }, { vm.toggleSave(a) }) }
            }
        }
    }
}
