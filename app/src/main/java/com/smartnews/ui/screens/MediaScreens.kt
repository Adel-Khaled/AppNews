package com.smartnews.ui.screens

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.smartnews.domain.model.MediaItem
import com.smartnews.domain.model.MediaType
import com.smartnews.domain.repository.NewsRepository
import com.smartnews.domain.repository.SettingsRepository
import com.smartnews.ui.components.EmptyState
import com.smartnews.ui.components.NewsImage
import com.smartnews.ui.components.VideoCard
import com.smartnews.ui.components.tr
import com.smartnews.ui.navigation.Nav
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MultimediaViewModel @Inject constructor(
    private val repo: NewsRepository,
    settings: SettingsRepository,
) : ViewModel() {
    val videos = stateOf(repo.observeMedia(MediaType.VIDEO, 200), emptyList())
    val images = stateOf(repo.observeMedia(MediaType.IMAGE, 300), emptyList())
    val live = stateOf(repo.observeMedia(MediaType.LIVE, 50), emptyList())
    val backendConfigured = stateOf(settings.settings.map { it.backendConfigured }, false)
    val liveLoading = MutableStateFlow(false)
    val liveError = MutableStateFlow<String?>(null)

    fun loadLive() = viewModelScope.launch {
        liveLoading.value = true
        repo.fetchLive(null).onFailure { liveError.value = it.message }.onSuccess { liveError.value = null }
        liveLoading.value = false
    }
}

@Composable
fun MultimediaScreen(nav: Nav, vm: MultimediaViewModel = hiltViewModel()) {
    val videos by vm.videos.collectAsStateWithLifecycle()
    val images by vm.images.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val liveLoading by vm.liveLoading.collectAsStateWithLifecycle()
    val liveError by vm.liveError.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(tab) { if (tab == 2) vm.loadLive() }

    ScreenFrame(tr("Videos", "فيديو"), nav, isRefreshing = liveLoading, onRefresh = if (tab == 2) ({ vm.loadLive(); Unit }) else null) {
        TabRow(selectedTabIndex = tab) {
            listOf(tr("Videos", "فيديو"), tr("Images", "صور"), tr("Live", "مباشر")).forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        when (tab) {
            0 -> if (videos.isEmpty()) EmptyState(tr("No videos yet", "لا توجد فيديوهات بعد"), tr("Videos appear here when you open news stories that include them.", "تظهر الفيديوهات هنا عند فتح الأخبار التي تحتوي عليها."), Icons.Default.VideoLibrary)
            else LazyVerticalGrid(GridCells.Adaptive(300.dp), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(videos, key = { it.id }) { v -> VideoCard(v, onClick = { nav.play(v) }, width = null) }
            }
            1 -> if (images.isEmpty()) EmptyState(tr("No images yet", "لا توجد صور بعد"), tr("Images from the news appear here.", "تظهر صور الأخبار هنا."))
            else LazyVerticalGrid(GridCells.Adaptive(160.dp), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(images.distinctBy { it.url }, key = { it.id }) { img ->
                    Column(Modifier.clickable { if (img.storyId.length > 10) nav.story(img.storyId) }) {
                        NewsImage(img.url, Modifier.fillMaxWidth().aspectRatio(1f), img.title)
                        Text(img.attribution, style = MaterialTheme.typography.labelSmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            else -> Column {
                liveError?.takeIf { it != "backend" }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                }
                Text(
                    tr("Only broadcasts that are live right now are labelled LIVE.", "تظهر علامة مباشر فقط للبث الجاري الآن."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                val current = live.filter { it.isLive }
                if (current.isEmpty() && !liveLoading) EmptyState(tr("No live streams found", "لا يوجد بث مباشر"), if (vm.backendConfigured.value) tr("Tap refresh to look again.", "اضغط تحديث للبحث مرة أخرى.") else tr("Live streams need the SmartNews backend (Settings).", "البث المباشر يحتاج إلى خادم سمارت نيوز (الإعدادات)."), Icons.Default.LiveTv)
                LazyVerticalGrid(GridCells.Adaptive(300.dp), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(current, key = { it.id }) { v -> VideoCard(v, onClick = { nav.play(v) }, width = null) }
                }
            }
        }
    }
}

@HiltViewModel
class PlayerViewModel @Inject constructor(settings: SettingsRepository) : ViewModel() {
    val autoplay = settings.settings.value.videoAutoplay
}

/** Media3 ExoPlayer for video files and HLS streams published by news organisations. */
@Composable
fun PlayerScreen(nav: Nav, url: String, title: String, vm: PlayerViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(ExoMediaItem.fromUri(url))
            prepare()
            playWhenReady = vm.autoplay
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    ScreenFrame(title.ifBlank { tr("Video", "فيديو") }, nav, isTopLevel = false) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
            AndroidView(factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } }, modifier = Modifier.fillMaxSize())
        }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        TextButton(onClick = { nav.openUrl(url) }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, null); Text("  " + tr("Open original", "فتح المصدر"))
        }
    }
}

/**
 * YouTube's official embedded IFrame player. SmartNews never extracts YouTube streams; if the
 * uploader disabled embedding, the player says so and the user can open the YouTube app.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubePlayerScreen(nav: Nav, videoId: String, title: String, url: String, vm: PlayerViewModel = hiltViewModel()) {
    val autoplay = if (vm.autoplay) 1 else 0
    val html = """
        <!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <style>html,body{margin:0;padding:0;background:#000;height:100%;overflow:hidden}iframe{position:absolute;inset:0;width:100%;height:100%;border:0}</style>
        </head><body>
        <iframe src="https://www.youtube.com/embed/$videoId?autoplay=$autoplay&playsinline=1&rel=0"
          allow="accelerometer; autoplay; encrypted-media; gyroscope; picture-in-picture; fullscreen" allowfullscreen
          referrerpolicy="strict-origin-when-cross-origin"></iframe>
        </body></html>
    """.trimIndent()
    ScreenFrame(title.ifBlank { tr("Video", "فيديو") }, nav, isTopLevel = false) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        webChromeClient = WebChromeClient()
                        webViewClient = WebViewClient()
                        loadDataWithBaseURL("https://com.smartnews", html, "text/html", "utf-8", null)
                    }
                },
                onRelease = { it.destroy() },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        Button(onClick = { nav.openUrl(url.ifBlank { "https://www.youtube.com/watch?v=$videoId" }) }, modifier = Modifier.padding(horizontal = 16.dp)) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, null); Text("  " + tr("Open in YouTube", "فتح في يوتيوب"))
        }
    }
}
