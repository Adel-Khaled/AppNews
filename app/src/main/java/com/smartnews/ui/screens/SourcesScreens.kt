package com.smartnews.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.smartnews.domain.model.NewsSource
import com.smartnews.domain.model.SourceDiscoveryResult
import com.smartnews.domain.model.SourceType
import com.smartnews.domain.repository.CategoryRepository
import com.smartnews.domain.repository.NewsRepository
import com.smartnews.domain.repository.SourceRepository
import com.smartnews.ui.components.ArticleRow
import com.smartnews.ui.components.EmptyState
import com.smartnews.ui.components.ErrorBanner
import com.smartnews.ui.components.LoadMoreEffect
import com.smartnews.ui.components.relativeTime
import com.smartnews.ui.navigation.Nav
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface AddState {
    data object Idle : AddState
    data object Checking : AddState
    data class Found(val result: SourceDiscoveryResult) : AddState
}

@HiltViewModel
class SourcesViewModel @Inject constructor(
    private val sources: SourceRepository,
    private val news: NewsRepository,
    categoryRepo: CategoryRepository,
) : ViewModel() {
    val list = stateOf(sources.observeSources(), null as List<NewsSource>?)
    val categories = stateOf(categoryRepo.observeCategories(), emptyList())
    val add = MutableStateFlow<AddState>(AddState.Idle)
    val refreshing = MutableStateFlow<String?>(null)
    val message = MutableStateFlow<String?>(null)

    fun check(url: String) = viewModelScope.launch {
        add.value = AddState.Checking
        add.value = AddState.Found(runCatching { sources.discover(url) }.getOrElse {
            SourceDiscoveryResult(false, url, url, null, SourceType.UNSUPPORTED, it.message ?: "Failed")
        })
    }

    fun confirmAdd(result: SourceDiscoveryResult, name: String, categoryId: String?) = viewModelScope.launch {
        runCatching { sources.addSource(result, name, categoryId) }
            .onSuccess { id ->
                add.value = AddState.Idle
                refreshing.value = id
                val r = news.refreshSource(id)
                message.value = "Added. ${r.newArticles} articles retrieved."
                refreshing.value = null
            }
            .onFailure { message.value = it.message }
    }

    fun resetAdd() { add.value = AddState.Idle }
    fun toggle(s: NewsSource) = viewModelScope.launch { sources.setEnabled(s.id, !s.enabled) }
    fun remove(s: NewsSource) = viewModelScope.launch { sources.remove(s.id); message.value = "Removed ${s.name}. Articles already collected were kept." }
    fun edit(s: NewsSource, name: String, feedUrl: String, categoryId: String?) = viewModelScope.launch { sources.updateSource(s.id, name, feedUrl, categoryId) }
    fun refresh(s: NewsSource) = viewModelScope.launch {
        refreshing.value = s.id
        val r = news.refreshSource(s.id)
        message.value = if (r.errors.isEmpty()) "${s.name}: ${r.newArticles} new, ${r.updatedArticles} updated" else "${s.name}: ${r.errors.first()}"
        refreshing.value = null
    }
}

@Composable
fun SourcesScreen(nav: Nav, prefillUrl: String, vm: SourcesViewModel = hiltViewModel()) {
    val list by vm.list.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val add by vm.add.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var showAdd by rememberSaveable { mutableStateOf(prefillUrl.isNotBlank()) }
    var editing by remember { mutableStateOf<NewsSource?>(null) }

    ScreenFrame("My Sources", nav) {
        ErrorBanner(message) { vm.message.value = null }
        Box(Modifier.weight(1f)) {
            val l = list
            if (l == null) LoadingState("Loading sources…")
            else LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text("Add any news website, blog, RSS feed or YouTube channel. Sources that fail are shown with the reason; the rest keep working.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val (custom, builtIn) = l.partition { it.isCustom }
                item { Text("Your sources (${custom.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                if (custom.isEmpty()) item { Text("None yet — tap “Add source”.", style = MaterialTheme.typography.bodySmall) }
                items(custom, key = { it.id }) { s -> SourceRow(s, refreshing == s.id, nav, vm, onEdit = { editing = s }) }
                item { Text("Built-in sources (${builtIn.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
                items(builtIn, key = { it.id }) { s -> SourceRow(s, refreshing == s.id, nav, vm, onEdit = { editing = s }) }
            }
            ExtendedFloatingActionButton(onClick = { showAdd = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add source") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
        }
    }

    if (showAdd) AddSourceDialog(prefillUrl, add, categories, onCheck = vm::check,
        onAdd = { r, name, cat -> vm.confirmAdd(r, name, cat); showAdd = false },
        onDismiss = { vm.resetAdd(); showAdd = false })

    editing?.let { s ->
        var name by remember(s.id) { mutableStateOf(s.name) }
        var feed by remember(s.id) { mutableStateOf(s.feedUrl.orEmpty()) }
        var cat by remember(s.id) { mutableStateOf(s.categoryId) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit source") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    if (s.type == SourceType.RSS || s.type == SourceType.YOUTUBE_CHANNEL || s.type == SourceType.GOOGLE_NEWS)
                        OutlinedTextField(feed, { feed = it }, label = { Text("Feed URL") }, singleLine = true)
                    CategoryPicker(categories, cat) { cat = it }
                }
            },
            confirmButton = { TextButton(onClick = { vm.edit(s, name, feed, cat); editing = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SourceRow(s: NewsSource, refreshing: Boolean, nav: Nav, vm: SourcesViewModel, onEdit: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(onClick = { nav.source(s.id) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.name, style = MaterialTheme.typography.titleSmall)
                Text("${s.type.label} · ${s.articleCount} articles", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    refreshing -> Text("Refreshing…", style = MaterialTheme.typography.labelSmall)
                    s.lastError != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Error, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                        Text(" ${s.lastError}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
                    }
                    s.lastSuccessAt != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
                        Text(" Updated ${relativeTime(s.lastSuccessAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Switch(checked = s.enabled, onCheckedChange = { vm.toggle(s) })
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("View articles") }, onClick = { menu = false; nav.source(s.id) })
                    DropdownMenuItem(text = { Text("Refresh this source") }, onClick = { menu = false; vm.refresh(s) })
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; vm.remove(s) })
                }
            }
        }
    }
}

@Composable
private fun AddSourceDialog(
    prefill: String, state: AddState, categories: List<Category>,
    onCheck: (String) -> Unit, onAdd: (SourceDiscoveryResult, String, String?) -> Unit, onDismiss: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(prefill) }
    var name by rememberSaveable { mutableStateOf("") }
    var cat by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state) { if (state is AddState.Found && name.isBlank()) name = state.result.name }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a source") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(url, { url = it }, label = { Text("Website, feed or channel URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { onCheck(url) }, enabled = url.isNotBlank() && state != AddState.Checking) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(16.dp)); Text("  Check source")
                }
                when (state) {
                    AddState.Idle -> Text("SmartNews validates the URL, detects the source type and looks for an RSS feed or official integration.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AddState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text("  Checking…")
                    }
                    is AddState.Found -> {
                        val r = state.result
                        Text(if (r.ok) "✓ ${r.type.label}" else "✗ Can't follow this source",
                            style = MaterialTheme.typography.titleSmall, color = if (r.ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error)
                        Text(r.message, style = MaterialTheme.typography.bodySmall)
                        if (r.ok) {
                            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            CategoryPicker(categories, cat) { cat = it }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val found = (state as? AddState.Found)?.result
            TextButton(enabled = found?.ok == true, onClick = { found?.let { onAdd(it, name, cat) } }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun CategoryPicker(categories: List<Category>, selected: String?, onSelect: (String?) -> Unit) {
    Column {
        Text("Category", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item { FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("Auto-detect") }) }
            items(categories.filter { !it.isTopic }, key = { it.id }) { c ->
                FilterChip(selected = selected == c.id, onClick = { onSelect(c.id) }, label = { Text(c.displayName()) })
            }
        }
    }
}

// ---------------------------------------------------------------- articles of one source

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SourceArticlesViewModel @Inject constructor(
    handle: SavedStateHandle,
    sources: SourceRepository,
    private val news: NewsRepository,
) : ViewModel() {
    val sourceId: String = checkNotNull(handle["id"])
    val source = stateOf(sources.observeSource(sourceId), null)
    private val limit = MutableStateFlow(60)
    val articles = stateOf(limit.flatMapLatest { news.observeArticles(null, sourceId, DateFilter.All, it) }, null as List<Article>?)
    val refreshing = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    fun loadMore() { limit.value += 60 }
    fun refresh() = viewModelScope.launch {
        refreshing.value = true
        val r = news.refreshSource(sourceId)
        message.value = r.errors.firstOrNull()
        refreshing.value = false
    }
    fun toggleSave(a: Article) = viewModelScope.launch { news.setArticleSaved(a.id, !a.isSaved) }
}

@Composable
fun SourceArticlesScreen(nav: Nav, vm: SourceArticlesViewModel = hiltViewModel()) {
    val source by vm.source.collectAsStateWithLifecycle()
    val articles by vm.articles.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    ScreenFrame(source?.name ?: "Source", nav, isTopLevel = false, isRefreshing = refreshing, onRefresh = { vm.refresh(); Unit }) {
        ErrorBanner(message) { vm.message.value = null }
        source?.lastError?.let {
            Text("Last refresh failed: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
        }
        val state = rememberLazyListState()
        LoadMoreEffect(state, onLoadMore = vm::loadMore)
        val list = articles
        when {
            list == null -> LoadingState("Loading…")
            list.isEmpty() -> EmptyState("No articles from this source yet", "Pull down to refresh it.")
            else -> LazyColumn(state = state, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { a -> ArticleRow(a, { nav.story(a.storyId) }, { vm.toggleSave(a) }) }
            }
        }
    }
}
