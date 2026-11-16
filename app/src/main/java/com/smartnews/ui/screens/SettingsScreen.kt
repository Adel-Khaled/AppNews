package com.smartnews.ui.screens

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.platform.testTag
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
import com.smartnews.domain.model.AppSettings
import com.smartnews.domain.model.Scope
import com.smartnews.domain.model.ThemeMode
import com.smartnews.domain.repository.NewsRepository
import com.smartnews.domain.repository.SettingsRepository
import com.smartnews.ui.components.tr
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
) : ViewModel() {
    val settings = repo.settings
    val articleCount = stateOf(news.observeArticleCount(), 0)
    val backendStatus = MutableStateFlow<String?>(null)
    val message = MutableStateFlow<String?>(null)

    fun update(t: (AppSettings) -> AppSettings) = viewModelScope.launch { repo.update(t) }

    fun saveBackend(url: String, token: String) = viewModelScope.launch {
        repo.update { it.copy(backendUrl = url.trim(), appToken = token.trim()) }
        backend.update(url, token)
        if (!backend.isConfigured) { backendStatus.value = "—"; return@launch }
        backendStatus.value = "…"
        backendStatus.value = runCatching { api.health() }.fold(
            { h -> "OK ${h.version}: " + h.providers.filterValues { it }.keys.joinToString().ifBlank { "-" } },
            { "Error: ${it.message}" },
        )
    }

    @OptIn(ExperimentalCoilApi::class)
    fun clearImageCache(context: Context, done: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { context.imageLoader.diskCache?.clear() }
        context.imageLoader.memoryCache?.clear()
        message.value = done
    }

    fun cleanup(done: (Int) -> String) = viewModelScope.launch { message.value = done(news.deleteOldUnsaved(365)) }
}

@Composable
fun SettingsScreen(nav: Nav, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val count by vm.articleCount.collectAsStateWithLifecycle()
    val backendStatus by vm.backendStatus.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmCleanup by remember { mutableStateOf(false) }
    val ar = s.language == "ar"
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.update { it.copy(notificationsEnabled = granted) }
    }
    val cacheDone = tr("Image cache cleared.", "تم مسح ذاكرة الصور.")

    ScreenFrame(tr("Settings", "الإعدادات"), nav, isTopLevel = false) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp).testTag("settings-screen")) {
            message?.let { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp)) }

            Section(tr("Language", "اللغة"))
            ChipRow(listOf("ar", "en"), s.language, { if (it == "ar") "العربية" else "English" }, "lang") { l -> vm.update { it.copy(language = l) } }
            Toggle(tr("Translate news into the app language", "ترجمة الأخبار إلى لغة التطبيق"),
                tr("Foreign news is translated on your phone (free). Models download once.", "تُترجم الأخبار الأجنبية على هاتفك مجاناً. يتم تنزيل نموذج الترجمة مرة واحدة."),
                s.translateNews) { v -> vm.update { it.copy(translateNews = v) } }

            Section(tr("Default news", "الأخبار الافتراضية"))
            ChipRow(listOf(Scope.LOCAL, Scope.GLOBAL), s.scope, { if (it == Scope.LOCAL) (if (ar) "🇪🇬 محلي (مصر)" else "🇪🇬 Local (Egypt)") else (if (ar) "🌍 عالمي" else "🌍 Global") }, "scope") { sc ->
                vm.update { it.copy(scope = sc) }
            }

            Section(tr("Appearance", "المظهر"))
            ChipRow(ThemeMode.entries, s.themeMode, {
                when (it) { ThemeMode.SYSTEM -> if (ar) "تلقائي" else "System"; ThemeMode.LIGHT -> if (ar) "فاتح" else "Light"; ThemeMode.DARK -> if (ar) "داكن" else "Dark" }
            }, "theme") { m -> vm.update { it.copy(themeMode = m) } }

            Section(tr("Automatic updates", "التحديث التلقائي"))
            ChipRow(listOf(0, 1, 3, 6, 12), s.refreshIntervalHours, {
                if (it == 0) (if (ar) "يدوي" else "Manual") else (if (ar) "كل $it س" else "Every ${it}h")
            }, "interval") { h -> vm.update { it.copy(refreshIntervalHours = h) } }
            Toggle(tr("Only on Wi-Fi", "عبر الواي فاي فقط"), null, s.wifiOnlySync) { v -> vm.update { it.copy(wifiOnlySync = v) } }

            Section(tr("Notifications", "الإشعارات"))
            Toggle(tr("Breaking news alerts", "تنبيهات الأخبار العاجلة"), tr("At most ${s.maxNotificationsPerDay} per day.", "بحد أقصى ${s.maxNotificationsPerDay} يومياً."),
                s.notificationsEnabled && s.notifyBreaking) { v ->
                if (v && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                vm.update { it.copy(notificationsEnabled = v, notifyBreaking = v) }
            }

            Section(tr("Video & data", "الفيديو والبيانات"))
            Toggle(tr("Autoplay videos", "تشغيل الفيديو تلقائياً"), null, s.videoAutoplay) { v -> vm.update { it.copy(videoAutoplay = v) } }
            Toggle(tr("Load images only on Wi-Fi", "تحميل الصور عبر الواي فاي فقط"), null, s.imagesOnWifiOnly) { v -> vm.update { it.copy(imagesOnWifiOnly = v) } }

            Section(tr("Sources", "المصادر"))
            Hint(tr("Add any news website, RSS feed or YouTube channel.", "أضف أي موقع أخبار أو موجز RSS أو قناة يوتيوب."))
            Button(onClick = { nav.sources() }, modifier = Modifier.padding(horizontal = 16.dp).testTag("open-sources")) { Text(tr("My Sources", "مصادري")) }

            Section(tr("Storage", "التخزين"))
            Hint(tr("$count articles stored on this phone.", "$count خبر محفوظ على هذا الهاتف."))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.clearImageCache(context, cacheDone) }) { Text(tr("Clear image cache", "مسح ذاكرة الصور")) }
                OutlinedButton(onClick = { confirmCleanup = true }) { Text(tr("Delete old news", "حذف الأخبار القديمة")) }
            }

            Section(tr("Advanced: backend", "متقدم: الخادم"))
            Hint(tr("Optional. Adds AI summaries, YouTube videos and more news APIs. See README.", "اختياري. يضيف ملخصات الذكاء الاصطناعي وفيديو يوتيوب ومصادر إضافية. راجع ملف README."))
            var url by rememberSaveable(s.backendUrl) { mutableStateOf(s.backendUrl) }
            var token by rememberSaveable(s.appToken) { mutableStateOf(s.appToken) }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(url, { url = it }, label = { Text(tr("Backend URL", "رابط الخادم")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(token, { token = it }, label = { Text(tr("App token", "رمز التطبيق")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { vm.saveBackend(url, token) }) { Text(tr("Save & test", "حفظ واختبار")) }
                backendStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Hint("SmartNews ${BuildConfig.VERSION_NAME}")
        }
    }

    if (confirmCleanup) {
        val done: (Int) -> String = if (ar) { n -> "تم حذف $n خبر قديم غير محفوظ." } else { n -> "Deleted $n old unsaved articles." }
        AlertDialog(
            onDismissRequest = { confirmCleanup = false },
            title = { Text(tr("Delete old news?", "حذف الأخبار القديمة؟")) },
            text = { Text(tr("Deletes unsaved news older than 12 months. Saved articles are kept. Refreshing never deletes news.",
                "يحذف الأخبار غير المحفوظة الأقدم من ١٢ شهراً. تبقى المحفوظات كما هي. التحديث لا يحذف الأخبار أبداً.")) },
            confirmButton = { TextButton(onClick = { vm.cleanup(done); confirmCleanup = false }) { Text(tr("Delete", "حذف")) } },
            dismissButton = { TextButton(onClick = { confirmCleanup = false }) { Text(tr("Cancel", "إلغاء")) } },
        )
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 16.dp))
    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp))
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
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, tag: String, onSelect: (T) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options) { o ->
            FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) }, modifier = Modifier.testTag("$tag-$o"))
        }
    }
}
