package com.smartnews.ui.screens

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import com.smartnews.BuildConfig
import com.smartnews.data.remote.BackendApi
import com.smartnews.data.remote.BackendConfig
import com.smartnews.data.repository.SettingsRepositoryImpl
import com.smartnews.domain.model.AppSettings
import com.smartnews.domain.model.SortMode
import com.smartnews.domain.model.ThemeMode
import com.smartnews.domain.repository.CategoryRepository
import com.smartnews.domain.repository.NewsRepository
import com.smartnews.domain.repository.SettingsRepository
import com.smartnews.domain.repository.SourceRepository
import com.smartnews.ui.navigation.Nav
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val news: NewsRepository,
    private val api: BackendApi,
    private val backend: BackendConfig,
    categoryRepo: CategoryRepository,
    sourceRepo: SourceRepository,
) : ViewModel() {
    val settings = repo.settings
    val categories = stateOf(categoryRepo.observeCategories(), emptyList())
    val sources = stateOf(sourceRepo.observeSources(), emptyList())
    val articleCount = stateOf(news.observeArticleCount(), 0)
    val backendStatus = MutableStateFlow<String?>(null)
    val message = MutableStateFlow<String?>(null)

    fun update(t: (AppSettings) -> AppSettings) = viewModelScope.launch { repo.update(t) }

    fun saveBackend(url: String, token: String) = viewModelScope.launch {
        repo.update { it.copy(backendUrl = url.trim(), appToken = token.trim()) }
        backend.update(url, token)
        testBackend()
    }

    fun testBackend() = viewModelScope.launch {
        if (!backend.isConfigured) { backendStatus.value = "Not configured — SmartNews runs in RSS + Google News mode."; return@launch }
        backendStatus.value = "Testing…"
        backendStatus.value = runCatching { api.health() }.fold(
            { h ->
                val on = h.providers.filterValues { it }.keys.joinToString().ifBlank { "none" }
                val off = h.providers.filterValues { !it }.keys.joinToString().ifBlank { "none" }
                "Connected (backend ${h.version}). Enabled: $on. Missing keys: $off."
            },
            { "Can't reach backend: ${it.message}" },
        )
    }

    @OptIn(ExperimentalCoilApi::class)
    fun clearImageCache(context: Context) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            context.imageLoader.diskCache?.clear()
        }
        context.imageLoader.memoryCache?.clear()
        message.value = "Image cache cleared. Articles were not affected."
    }

    fun cleanup(days: Int) = viewModelScope.launch {
        val n = news.deleteOldUnsaved(days)
        message.value = "Removed $n unsaved articles older than $days days. Saved articles and history were kept."
    }
}

@Composable
fun SettingsScreen(nav: Nav, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val count by vm.articleCount.collectAsStateWithLifecycle()
    val backendStatus by vm.backendStatus.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmCleanup by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.update { it.copy(notificationsEnabled = granted) }
    }

    ScreenFrame("Settings", nav, showSearch = false) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            message?.let { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp)) }

            Section("Appearance")
            ChipRow(ThemeMode.entries, s.themeMode, { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }) { m -> vm.update { it.copy(themeMode = m) } }

            Section("Language & region")
            ChipRow(SettingsRepositoryImpl.SUPPORTED_LANGUAGES.keys.toList(), s.language, { SettingsRepositoryImpl.SUPPORTED_LANGUAGES[it] ?: it }) { l -> vm.update { it.copy(language = l) } }
            var country by rememberSaveable(s.country) { mutableStateOf(s.country) }
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(country, { country = it.take(2).uppercase() }, label = { Text("Country code (local news)") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(enabled = country.length == 2 && country != s.country, onClick = { vm.update { it.copy(country = country) } }) { Text("Save") }
            }
            Hint("Used for Local News and Google News results. Example: EG, US, GB, SA, AE, FR.")

            Section("Feed")
            Toggle("Use Google News search feeds", "Finds coverage for every enabled category and topic across thousands of publishers.", s.useGoogleNews) { v -> vm.update { it.copy(useGoogleNews = v) } }
            Text("Default sort", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
            ChipRow(SortMode.entries, s.sortMode, { if (it == SortMode.NEWEST) "Newest first" else "Most relevant" }) { m -> vm.update { it.copy(sortMode = m) } }
            Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = { nav.top("categories") }) { Text("Preferred categories (${categories.count { it.enabled }})") }
                TextButton(onClick = { nav.top("sources") }) { Text("News sources (${sources.count { it.enabled }})") }
            }

            Section("Automatic updates")
            ChipRow(listOf(0, 1, 3, 6, 12, 24), s.refreshIntervalHours, { if (it == 0) "Manual" else "Every ${it}h" }) { h -> vm.update { it.copy(refreshIntervalHours = h) } }
            Toggle("Only sync on Wi-Fi", null, s.wifiOnlySync) { v -> vm.update { it.copy(wifiOnlySync = v) } }
            Hint("Background sync uses Android WorkManager; Android may delay it to save battery. Refreshing never deletes stored news.")

            Section("Notifications")
            Toggle("Enable notifications", "At most ${s.maxNotificationsPerDay} per day.", s.notificationsEnabled) { v ->
                if (v && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else vm.update { it.copy(notificationsEnabled = v) }
            }
            if (s.notificationsEnabled) {
                Toggle("Breaking news", "Stories reported by several outlets within hours.", s.notifyBreaking) { v -> vm.update { it.copy(notifyBreaking = v) } }
                Text("Important developments in", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                MultiChipRow(categories.filter { it.enabled }.map { it.id to it.displayName() }, s.notifyCategoryIds) { set -> vm.update { it.copy(notifyCategoryIds = set) } }
                Text("New articles from", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                MultiChipRow(sources.filter { it.isCustom }.map { it.id to it.name }, s.notifySourceIds) { set -> vm.update { it.copy(notifySourceIds = set) } }
                Text("Maximum per day", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                ChipRow(listOf(2, 5, 10, 20), s.maxNotificationsPerDay, { "$it" }) { n -> vm.update { it.copy(maxNotificationsPerDay = n) } }
            }

            Section("Video & data")
            Toggle("Autoplay videos", "Start playing as soon as a video opens.", s.videoAutoplay) { v -> vm.update { it.copy(videoAutoplay = v) } }
            Toggle("Load images only on Wi-Fi", "On mobile data, only images already cached are shown.", s.imagesOnWifiOnly) { v -> vm.update { it.copy(imagesOnWifiOnly = v) } }

            Section("Backend (AI, YouTube, news APIs)")
            var url by rememberSaveable(s.backendUrl) { mutableStateOf(s.backendUrl) }
            var token by rememberSaveable(s.appToken) { mutableStateOf(s.appToken) }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(url, { url = it }, label = { Text("Backend URL (e.g. https://smartnews.example.com)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(token, { token = it }, label = { Text("App token") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.saveBackend(url, token) }) { Text("Save & test") }
                    OutlinedButton(onClick = { vm.testBackend() }) { Text("Test") }
                }
                backendStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Hint("Optional. Without it SmartNews still works with RSS feeds and Google News, using on-device extracts instead of AI summaries. API keys live only on the backend, never in the app.")

            Section("Storage")
            Hint("$count articles stored on this phone.")
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.clearImageCache(context) }) { Text("Clear image cache") }
                OutlinedButton(onClick = { confirmCleanup = true }) { Text("Clean up old news") }
            }

            Section("About")
            Hint("SmartNews ${BuildConfig.VERSION_NAME}. Every article links to its original publisher. AI-generated text is always labelled. Videos play through official players or open in their own app.")
        }
    }

    if (confirmCleanup) AlertDialog(
        onDismissRequest = { confirmCleanup = false },
        title = { Text("Clean up old news?") },
        text = { Text("Deletes unsaved articles published more than 12 months ago that you never opened. Saved articles and reading history are kept. This is the only action in SmartNews that deletes news.") },
        confirmButton = { TextButton(onClick = { vm.cleanup(365); confirmCleanup = false }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmCleanup = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 16.dp))
    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
}

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options) { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) }) }
    }
}

@Composable
private fun MultiChipRow(options: List<Pair<String, String>>, selected: Set<String>, onChange: (Set<String>) -> Unit) {
    if (options.isEmpty()) { Hint("Nothing to choose yet."); return }
    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options, key = { it.first }) { (id, name) ->
            FilterChip(selected = id in selected, onClick = { onChange(if (id in selected) selected - id else selected + id) }, label = { Text(name) })
        }
    }
}
