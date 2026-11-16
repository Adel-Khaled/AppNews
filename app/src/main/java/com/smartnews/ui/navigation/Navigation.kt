package com.smartnews.ui.navigation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.LayoutDirection
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.smartnews.domain.model.MediaItem
import com.smartnews.domain.model.Playback
import com.smartnews.domain.repository.SettingsRepository
import com.smartnews.sync.ConnectivityObserver
import com.smartnews.sync.RefreshController
import com.smartnews.ui.components.LocalAllowNetworkImages
import com.smartnews.ui.components.LocalLang
import com.smartnews.ui.components.OfflineBanner
import com.smartnews.ui.components.tr
import com.smartnews.ui.screens.HomeScreen
import com.smartnews.ui.screens.LatestScreen
import com.smartnews.ui.screens.MultimediaScreen
import com.smartnews.ui.screens.PlayerScreen
import com.smartnews.ui.screens.SavedScreen
import com.smartnews.ui.screens.SearchScreen
import com.smartnews.ui.screens.SettingsScreen
import com.smartnews.ui.screens.SourceArticlesScreen
import com.smartnews.ui.screens.SourcesScreen
import com.smartnews.ui.screens.StoryDetailScreen
import com.smartnews.ui.screens.YouTubePlayerScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface LaunchRequest {
    data class OpenStory(val storyId: String) : LaunchRequest
    data class AddSource(val url: String) : LaunchRequest
}

object Routes {
    const val HOME = "home"
    const val LATEST = "latest"
    const val VIDEOS = "videos"
    const val SAVED = "saved"
    const val SEARCH = "search?q={q}"
    const val SETTINGS = "settings"
    const val SOURCES = "sources?add={add}"
    const val STORY = "story/{id}"
    const val SOURCE = "source/{id}"
    const val PLAYER = "player?url={url}&title={title}"
    const val YOUTUBE = "youtube/{id}?title={title}&url={url}"
}

private data class Tab(val route: String, val navRoute: String, val en: String, val ar: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "home", "Home", "الرئيسية", Icons.Default.Home),
    Tab(Routes.LATEST, "latest", "Latest", "الأحدث", Icons.Default.Schedule),
    Tab(Routes.VIDEOS, "videos", "Videos", "فيديو", Icons.Default.VideoLibrary),
    Tab(Routes.SAVED, "saved", "Saved", "المحفوظات", Icons.Default.Bookmarks),
    Tab(Routes.SEARCH, "search", "Search", "بحث", Icons.Default.Search),
)

/** Navigation actions handed to every screen. */
class Nav(private val controller: NavHostController, private val context: Context) {
    fun back() { controller.popBackStack() }
    fun story(id: String) = controller.navigate("story/${Uri.encode(id)}")
    fun source(id: String) = controller.navigate("source/${Uri.encode(id)}")
    fun search(q: String = "") = controller.navigate("search?q=${Uri.encode(q)}")
    fun sources(addUrl: String? = null) = controller.navigate("sources?add=${Uri.encode(addUrl ?: "")}")
    fun settings() = controller.navigate("settings")
    fun top(route: String) = controller.navigate(route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    fun play(item: MediaItem) {
        val yt = Regex("(?:v=|embed/|youtu\\.be/|shorts/|live/)([A-Za-z0-9_-]{11})").find(item.url)?.groupValues?.get(1)
        when {
            item.playback == Playback.YOUTUBE_EMBED && yt != null ->
                controller.navigate("youtube/$yt?title=${Uri.encode(item.title)}&url=${Uri.encode(item.url)}")
            item.playback == Playback.EXOPLAYER ->
                controller.navigate("player?url=${Uri.encode(item.url)}&title=${Uri.encode(item.title)}")
            else -> openUrl(item.url)
        }
    }

    fun openUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
        }
    }

    fun share(title: String, url: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, "$title\n$url")
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@HiltViewModel
class RootViewModel @Inject constructor(
    connectivity: ConnectivityObserver,
    settings: SettingsRepository,
    val refresh: RefreshController,
) : ViewModel() {
    val online = connectivity.isOnline.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val allowImages = combine(connectivity.isOnline, settings.settings) { _, s -> !s.imagesOnWifiOnly || connectivity.isUnmetered() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val language = settings.settings.map { it.language }.stateIn(viewModelScope, SharingStarted.Eagerly, settings.settings.value.language)
    val refreshState = refresh.state

    init {
        viewModelScope.launch {
            // Show stored news immediately; fetch new items in the background if they are stale.
            val s = settings.snapshot()
            if (System.currentTimeMillis() - s.lastSyncAt > 20 * 60_000) refresh.refresh() else refresh.translate()
            // Switching language translates what is stored and fetches that language's sources.
            settings.settings.map { it.language }.distinctUntilChanged().drop(1).collect {
                refresh.refresh()
                refresh.translate()
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppRoot(launchRequest: LaunchRequest?, onLaunchHandled: () -> Unit) {
    val vm: RootViewModel = hiltViewModel()
    val controller = rememberNavController()
    val context = LocalContext.current
    val nav = remember(controller) { Nav(controller, context.applicationContext) }
    val online by vm.online.collectAsStateWithLifecycle()
    val allowImages by vm.allowImages.collectAsStateWithLifecycle()
    val lang by vm.language.collectAsStateWithLifecycle()
    val refreshState by vm.refreshState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val backStack by controller.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val ar = lang == "ar"
    LaunchedEffect(refreshState.result, refreshState.failed) {
        val r = refreshState.result
        val msg = when {
            refreshState.failed -> if (ar) "تعذر التحديث. تحقق من الاتصال." else "Refresh failed. Check your connection."
            r == null -> null
            r.newArticles == 0 -> if (ar) "لا توجد أخبار جديدة" else "No new articles"
            else -> if (ar) "${r.newArticles} خبر جديد" else "${r.newArticles} new articles"
        }
        if (msg != null) { snackbar.showSnackbar(msg); vm.refresh.consumeResult() }
    }
    LaunchedEffect(launchRequest) {
        when (launchRequest) {
            is LaunchRequest.OpenStory -> nav.story(launchRequest.storyId)
            is LaunchRequest.AddSource -> nav.sources(launchRequest.url)
            null -> Unit
        }
        if (launchRequest != null) onLaunchHandled()
    }

    CompositionLocalProvider(
        LocalAllowNetworkImages provides allowImages,
        LocalLang provides lang,
        LocalLayoutDirection provides if (ar) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        Scaffold(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (tabs.any { it.route == currentRoute } || currentRoute == null) {
                    NavigationBar {
                        tabs.forEach { t ->
                            NavigationBarItem(
                                selected = currentRoute == t.route, onClick = { nav.top(t.navRoute) },
                                icon = { Icon(t.icon, null) }, label = { Text(tr(t.en, t.ar), maxLines = 1) },
                                modifier = Modifier.testTag("nav-" + t.navRoute),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Column(Modifier.padding(bottom = padding.calculateBottomPadding())) {
                NavHost(controller, startDestination = Routes.HOME, modifier = Modifier.weight(1f)) {
                    composable(Routes.HOME) { HomeScreen(nav) }
                    composable(Routes.LATEST) { LatestScreen(nav) }
                    composable(Routes.VIDEOS) { MultimediaScreen(nav) }
                    composable(Routes.SAVED) { SavedScreen(nav) }
                    composable(Routes.SEARCH, arguments = listOf(navArgument("q") { type = NavType.StringType; defaultValue = "" })) {
                        SearchScreen(nav, initialQuery = it.arguments?.getString("q").orEmpty())
                    }
                    composable(Routes.SETTINGS) { SettingsScreen(nav) }
                    composable(Routes.SOURCES, arguments = listOf(navArgument("add") { type = NavType.StringType; defaultValue = "" })) {
                        SourcesScreen(nav, prefillUrl = it.arguments?.getString("add").orEmpty())
                    }
                    composable(Routes.STORY) { StoryDetailScreen(nav) }
                    composable(Routes.SOURCE) { SourceArticlesScreen(nav) }
                    composable(Routes.PLAYER, arguments = listOf(
                        navArgument("url") { type = NavType.StringType; defaultValue = "" },
                        navArgument("title") { type = NavType.StringType; defaultValue = "" },
                    )) { PlayerScreen(nav, it.arguments?.getString("url").orEmpty(), it.arguments?.getString("title").orEmpty()) }
                    composable(Routes.YOUTUBE, arguments = listOf(
                        navArgument("title") { type = NavType.StringType; defaultValue = "" },
                        navArgument("url") { type = NavType.StringType; defaultValue = "" },
                    )) {
                        YouTubePlayerScreen(nav, it.arguments?.getString("id").orEmpty(), it.arguments?.getString("title").orEmpty(), it.arguments?.getString("url").orEmpty())
                    }
                }
                OfflineBanner(!online)
            }
        }
    }
}
