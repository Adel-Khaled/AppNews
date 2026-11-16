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
import com.smartnews.ui.components.LocalLang
import com.smartnews.ui.components.tr
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
                message.value = "✓ +${r.newArticles}"
                refreshing.value = null
            }
            .onFailure { message.value = it.message }
    }

    fun resetAdd() { add.value = AddState.Idle }
    fun toggle(s: NewsSource) = viewModelScope.launch { sources.setEnabled(s.id, !s.enabled) }
    fun remove(s: NewsSource) = viewModelScope.launch { sources.remove(s.id); message.value = "✓ ${s.name}" }
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

    ScreenFrame(tr("My Sources", "مصادري"), nav, isTopLevel = false) {
        ErrorBanner(message) { vm.message.value = null }
        Box(Modifier.weight(1f)) {
            val l = list
            if (l == null) LoadingState(tr("Loading…", "جاري التحميل…"))
            else LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(tr("Add any news website, blog, RSS feed or YouTube channel.", "أضف أي موقع أخبار أو مدونة أو موجز RSS أو قناة يوتيوب."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val (custom, builtIn) = l.partition { it.isCustom }
                item { Text(tr("Your sources (${custom.size})", "مصادرك (${custom.size})"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                if (custom.isEmpty()) item { Text(tr("None yet — tap “Add source”.", "لا يوجد بعد — اضغط «إضافة مصدر»."), style = MaterialTheme.typography.bodySmall) }
                items(custom, key = { it.id }) { s -> SourceRow(s, refreshing == s.id, nav, vm, onEdit = { editing = s }) }
                item { Text(tr("Built-in sources (${builtIn.size})", "المصادر المدمجة (${builtIn.size})"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
                items(builtIn, key = { it.id }) { s -> SourceRow(s, refreshing == s.id, nav, vm, onEdit = { editing = s }) }
            }
            ExtendedFloatingActionButton(onClick = { showAdd = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text(tr("Add source", "إضافة مصدر")) },
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
            title = { Text(tr("Edit source", "تعديل المصدر")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text(tr("Name", "الاسم")) }, singleLine = true)
                    if (s.type == SourceType.RSS || s.type == SourceType.YOUTUBE_CHANNEL || s.type == SourceType.GOOGLE_NEWS)
                        OutlinedTextField(feed, { feed = it }, label = { Text(tr("Feed URL", "رابط الموجز")) }, singleLine = true)
                    CategoryPicker(categories, cat) { cat = it }
                }
            },
            confirmButton = { TextButton(onClick = { vm.edit(s, name, feed, cat); editing = null }) { Text(tr("Save", "حفظ")) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(tr("Cancel", "إلغاء")) } },
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
                Text("${if (LocalLang.current == "ar") s.type.labelAr else s.type.label} · ${s.articleCount}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    refreshing -> Text(tr("Refreshing…", "جاري التحديث…"), style = MaterialTheme.typography.labelSmall)
                    s.lastError != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Error, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                        Text(" ${s.lastError}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
                    }
                    s.lastSuccessAt != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
                        Text(" " + relativeTime(s.lastSuccessAt, LocalLang.current), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Switch(checked = s.enabled, onCheckedChange = { vm.toggle(s) })
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(tr("View articles", "عرض الأخبار")) }, onClick = { menu = false; nav.source(s.id) })
                    DropdownMenuItem(text = { Text(tr("Refresh this source", "تحديث المصدر")) }, onClick = { menu = false; vm.refresh(s) })
                    DropdownMenuItem(text = { Text(tr("Edit", "تعديل")) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text(tr("Remove", "حذف")) }, onClick = { menu = false; vm.remove(s) })
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
        title = { Text(tr("Add a source", "إضافة مصدر")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(url, { url = it }, label = { Text(tr("Website, feed or channel URL", "رابط الموقع أو الموجز أو القناة")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { onCheck(url) }, enabled = url.isNotBlank() && state != AddState.Checking) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(16.dp)); Text("  " + tr("Check source", "تحقق من المصدر"))
                }
                when (state) {
                    AddState.Idle -> Text(tr("SmartNews checks the link and finds its news feed.", "يتحقق التطبيق من الرابط ويبحث عن موجز الأخبار الخاص به."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AddState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text("  " + tr("Checking…", "جاري التحقق…"))
                    }
                    is AddState.Found -> {
                        val r = state.result
                        Text(if (r.ok) "✓ ${if (LocalLang.current == "ar") r.type.labelAr else r.type.label}" else tr("✗ Can't follow this source", "✗ لا يمكن متابعة هذا المصدر"),
                            style = MaterialTheme.typography.titleSmall, color = if (r.ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error)
                        Text(r.message, style = MaterialTheme.typography.bodySmall)
                        if (r.ok) {
                            OutlinedTextField(name, { name = it }, label = { Text(tr("Name", "الاسم")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            CategoryPicker(categories, cat) { cat = it }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val found = (state as? AddState.Found)?.result
            TextButton(enabled = found?.ok == true, onClick = { found?.let { onAdd(it, name, cat) } }) { Text(tr("Add", "إضافة")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "إلغاء")) } },
    )
}

@Composable
fun CategoryPicker(categories: List<Category>, selected: String?, onSelect: (String?) -> Unit) {
    Column {
        Text(tr("Category", "القسم"), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item { FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text(tr("Auto-detect", "تلقائي")) }) }
            items(categories.filter { !it.isTopic }, key = { it.id }) { c ->
                FilterChip(selected = selected == c.id, onClick = { onSelect(c.id) }, label = { Text(c.label(LocalLang.current)) })
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
    ScreenFrame(source?.name ?: tr("Source", "المصدر"), nav, isTopLevel = false, isRefreshing = refreshing, onRefresh = { vm.refresh(); Unit }) {
        ErrorBanner(message) { vm.message.value = null }
        source?.lastError?.let {
            Text(tr("Last refresh failed: $it", "فشل آخر تحديث: $it"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
        }
        val state = rememberLazyListState()
        LoadMoreEffect(state, onLoadMore = vm::loadMore)
        val list = articles
        when {
            list == null -> LoadingState(tr("Loading…", "جاري التحميل…"))
            list.isEmpty() -> EmptyState(tr("No articles yet", "لا توجد أخبار بعد"), tr("Pull down to refresh.", "اسحب للأسفل للتحديث."))
            else -> LazyColumn(state = state, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { a -> ArticleRow(a, { nav.story(a.storyId) }, { vm.toggleSave(a) }) }
            }
        }
    }
}
