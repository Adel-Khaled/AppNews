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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.smartnews.data.seed.DefaultCatalog
import com.smartnews.domain.model.Article
import com.smartnews.domain.model.ContentType
import com.smartnews.domain.model.DateFilter
import com.smartnews.domain.repository.CategoryRepository
import com.smartnews.domain.repository.NewsRepository
import com.smartnews.domain.repository.SearchResults
import com.smartnews.sync.RefreshController
import com.smartnews.ui.components.ArticleRow
import com.smartnews.ui.components.DateFilterRow
import com.smartnews.ui.components.EmptyState
import com.smartnews.ui.components.ErrorBanner
import com.smartnews.ui.components.LocalLang
import com.smartnews.ui.components.SectionHeader
import com.smartnews.ui.components.VideoRow
import com.smartnews.ui.components.relativeTime
import com.smartnews.ui.components.tr
import com.smartnews.ui.navigation.Nav
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

// =============================================================== Saved & history

@HiltViewModel
class SavedViewModel @Inject constructor(private val repo: NewsRepository) : ViewModel() {
    val articles = stateOf(repo.observeSavedArticles(), emptyList())
    val media = stateOf(repo.observeSavedMedia(), emptyList())
    val history = stateOf(repo.observeHistory(300), emptyList())
    fun toggle(a: Article) = viewModelScope.launch { repo.setArticleSaved(a.id, !a.isSaved) }
    fun clearHistory() = viewModelScope.launch { repo.clearHistory() }
}

@Composable
fun SavedScreen(nav: Nav, vm: SavedViewModel = hiltViewModel()) {
    val lang = LocalLang.current
    val articles by vm.articles.collectAsStateWithLifecycle()
    val media by vm.media.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var q by rememberSaveable { mutableStateOf("") }
    fun match(vararg fields: String?) = q.isBlank() || fields.any { it?.contains(q, ignoreCase = true) == true }

    ScreenFrame(tr("Saved", "المحفوظات"), nav) {
        TabRow(selectedTabIndex = tab) {
            listOf(tr("Articles", "المقالات"), tr("Videos", "فيديو"), tr("History", "السجل")).forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }, modifier = Modifier.testTag("saved-tab-$i"))
            }
        }
        OutlinedTextField(
            q, { q = it }, placeholder = { Text(tr("Search saved", "ابحث في المحفوظات")) }, singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        when (tab) {
            0 -> {
                val filtered = articles.filter { match(it.title, it.titleTr, it.sourceName) }
                if (filtered.isEmpty()) EmptyState(tr("No saved articles", "لا توجد مقالات محفوظة"),
                    tr("Tap the bookmark on any article to keep it here, even offline.", "اضغط علامة الحفظ على أي خبر للاحتفاظ به هنا حتى بدون إنترنت."), Icons.Default.Bookmarks)
                else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("saved-list")) {
                    items(filtered, key = { it.id }) { a -> ArticleRow(a, { nav.story(a.storyId) }, { vm.toggle(a) }) }
                }
            }
            1 -> {
                val filtered = media.filter { match(it.title, it.publisher) }
                if (filtered.isEmpty()) EmptyState(tr("No saved videos", "لا توجد فيديوهات محفوظة"), tr("Saved videos appear here.", "تظهر الفيديوهات المحفوظة هنا."))
                else Column { VideoRow(filtered) { nav.play(it) } }
            }
            else -> {
                val filtered = history.filter { match(it.article.title, it.article.titleTr, it.article.sourceName) }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("${history.size} recently viewed", "${history.size} خبر تمت قراءته مؤخراً"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    if (history.isNotEmpty()) TextButton(onClick = { vm.clearHistory() }) { Text(tr("Clear", "مسح")) }
                }
                if (filtered.isEmpty()) EmptyState(tr("No reading history", "لا يوجد سجل قراءة"), tr("Articles you open are listed here.", "تظهر هنا الأخبار التي فتحتها."), Icons.Default.History)
                else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filtered, key = { it.article.id }) { h ->
                        Column {
                            Text(relativeTime(h.viewedAt, lang), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    private val refresher: RefreshController,
) : ViewModel() {
    val categories = stateOf(categoryRepo.observeCategories().map { all -> DefaultCatalog.tabCategoryIds.mapNotNull { id -> all.firstOrNull { it.id == id } } }, emptyList())
    val results = MutableStateFlow<SearchResults?>(null)
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    private var job: Job? = null

    fun search(q: String, categoryId: String?, filter: DateFilter, type: ContentType) {
        if (q.isBlank()) return
        job?.cancel()
        job = viewModelScope.launch {
            loading.value = true
            error.value = null
            // Stored news first (instant, works offline), then fresh online results merged in.
            results.value = repo.search(q, categoryId, filter, type, includeOnline = false)
            val r = repo.search(q, categoryId, filter, type, includeOnline = true)
            results.value = r
            error.value = r.onlineError
            loading.value = false
            refresher.translate()
        }
    }
    fun toggleSave(a: Article) = viewModelScope.launch { repo.setArticleSaved(a.id, !a.isSaved) }
}

@Composable
fun SearchScreen(nav: Nav, initialQuery: String, vm: SearchViewModel = hiltViewModel()) {
    val lang = LocalLang.current
    val categories by vm.categories.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var q by rememberSaveable { mutableStateOf(initialQuery) }
    var type by rememberSaveable { mutableStateOf(ContentType.ALL) }
    var cat by rememberSaveable { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf<DateFilter>(DateFilter.All) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val view = LocalView.current
    val run = {
        focus.clearFocus(); keyboard?.hide()
        (view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0)
        vm.search(q, cat, filter, type)
    }
    LaunchedEffect(Unit) { if (initialQuery.isNotBlank()) run() }

    ScreenFrame(tr("Search", "بحث"), nav, isRefreshing = loading) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                q, { q = it }, singleLine = true, placeholder = { Text(tr("People, places, events…", "أشخاص، أماكن، أحداث…")) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (q.isNotEmpty()) IconButton(onClick = { q = "" }) { Icon(Icons.Default.Clear, tr("Clear", "مسح")) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { run() }),
                modifier = Modifier.weight(1f).testTag("search-field"),
            )
            Button(onClick = { run() }, enabled = q.isNotBlank(), modifier = Modifier.padding(start = 8.dp).testTag("search-go")) { Text(tr("Search", "بحث")) }
        }
        val types = listOf(ContentType.ALL to tr("All", "الكل"), ContentType.ARTICLES to tr("Articles", "مقالات"), ContentType.VIDEOS to tr("Videos", "فيديو"))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(types) { (t, label) -> FilterChip(selected = type == t, onClick = { type = t; run() }, label = { Text(label) }) }
            item { FilterChip(selected = cat == null, onClick = { cat = null; run() }, label = { Text(tr("All categories", "كل الأقسام")) }) }
            items(categories, key = { it.id }) { c -> FilterChip(selected = cat == c.id, onClick = { cat = c.id; run() }, label = { Text(c.label(lang)) }) }
        }
        DateFilterRow(filter) { filter = it; run() }
        ErrorBanner(error) { vm.error.value = null }

        val r = results
        when {
            r == null -> EmptyState(tr("Search all news", "ابحث في كل الأخبار"),
                tr("Searches news stored on your phone and new results online.", "يبحث في الأخبار المحفوظة على هاتفك وفي النتائج الجديدة على الإنترنت."), Icons.Default.Search)
            r.articles.isEmpty() && r.media.isEmpty() && !loading -> EmptyState(tr("No results", "لا توجد نتائج"),
                tr("Try other words or a longer period.", "جرّب كلمات أخرى أو فترة أطول."))
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("search-results")) {
                if (r.media.isNotEmpty()) {
                    item { SectionHeader(tr("Videos", "فيديو")) }
                    item { VideoRow(r.media) { nav.play(it) } }
                }
                if (r.articles.isNotEmpty()) item { SectionHeader(tr("Articles (${r.articles.size})", "المقالات (${r.articles.size})")) }
                items(r.articles, key = { it.id }) { a -> ArticleRow(a, { nav.story(a.storyId) }, { vm.toggleSave(a) }, Modifier.padding(horizontal = 16.dp)) }
            }
        }
    }
}
