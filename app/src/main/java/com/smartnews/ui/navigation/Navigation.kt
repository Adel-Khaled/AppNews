package com.smartnews.ui.navigation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import com.smartnews.ui.components.OfflineBanner
import com.smartnews.ui.screens.CategoriesScreen
import com.smartnews.ui.screens.CategoryFeedScreen
import com.smartnews.ui.screens.ForYouScreen
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface LaunchRequest {
    data class OpenStory(val storyId: String) : LaunchRequest
    data class AddSource(val url: String) : LaunchRequest
}

object Routes {
    const val HOME = "home"
    const val FOR_YOU = "foryou"
    const val CATEGORIES = "categories"
    const val LATEST = "latest"
    const val MULTIMEDIA = "multimedia"
    const val SOURCES = "sources?add={add}"
    const val SAVED = "saved"
    const val SEARCH = "search?q={q}"
    const val SETTINGS = "settings"
    const val STORY = "story/{id}"
    const val CATEGORY = "category/{id}"
    const val SOURCE = "source/{id}"
    const val PLAYER = "player?url={url}&title={title}"
    const val YOUTUBE = "youtube/{id}?title={title}&url={url}"
}

private data class TopDest(val route: String, val navRoute: String, val label: String, val icon: ImageVector)

private val drawerDestinations = listOf(
    TopDest(Routes.HOME, "home", "Home", Icons.Default.Home),
    TopDest(Routes.FOR_YOU, "foryou", "For You", Icons.Default.Star),
    TopDest(Routes.CATEGORIES, "categories", "Categories", Icons.Default.Category),
    TopDest(Routes.LATEST, "latest", "Latest News", Icons.Default.Schedule),
    TopDest(Routes.MULTIMEDIA, "multimedia", "Multimedia", Icons.Default.VideoLibrary),
    TopDest(Routes.SOURCES, "sources", "My Sources", Icons.Default.RssFeed),
    TopDest(Routes.SAVED, "saved", "Saved Articles", Icons.Default.Bookmarks),
    TopDest(Routes.SEARCH, "search", "Search", Icons.Default.Search),
    TopDest(Routes.SETTINGS, "settings", "Settings", Icons.Default.Settings),
)
private val bottomDestinations = drawerDestinations.filter { it.route in setOf(Routes.HOME, Routes.FOR_YOU, Routes.LATEST, Routes.MULTIMEDIA, Routes.SEARCH) }

/** Navigation actions handed to every screen. */
class Nav(private val controller: NavHostController, private val context: Context, val openDrawer: () -> Unit) {
    fun back() { controller.popBackStack() }
    fun story(id: String) = controller.navigate("story/${Uri.encode(id)}")
    fun category(id: String) = controller.navigate("category/${Uri.encode(id)}")
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

    /** Opens the original source in its official app (if installed) or the browser. */
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
        context.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
    val refreshState = refresh.state

    init {
        // Show the latest stored news immediately; fetch new items in the background on launch.
        viewModelScope.launch {
            val s = settings.snapshot()
            if (System.currentTimeMillis() - s.lastSyncAt > 20 * 60_000) refresh.refresh()
        }
    }
}

@Composable
fun AppRoot(launchRequest: LaunchRequest?, onLaunchHandled: () -> Unit) {
    val vm: RootViewModel = hiltViewModel()
    val controller = rememberNavController()
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val nav = remember(controller) { Nav(controller, context.applicationContext) { scope.launch { drawerState.open() } } }
    val online by vm.online.collectAsStateWithLifecycle()
    val allowImages by vm.allowImages.collectAsStateWithLifecycle()
    val refreshState by vm.refreshState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val backStack by controller.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    LaunchedEffect(refreshState.message) {
        refreshState.message?.let { snackbar.showSnackbar(it); vm.refresh.consumeMessage() }
    }
    LaunchedEffect(launchRequest) {
        when (launchRequest) {
            is LaunchRequest.OpenStory -> nav.story(launchRequest.storyId)
            is LaunchRequest.AddSource -> nav.sources(launchRequest.url)
            null -> Unit
        }
        if (launchRequest != null) onLaunchHandled()
    }

    CompositionLocalProvider(LocalAllowNetworkImages provides allowImages) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Spacer(Modifier.height(16.dp))
                        Text("SmartNews", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
                        drawerDestinations.forEach { d ->
                            NavigationDrawerItem(
                                label = { Text(d.label) }, icon = { Icon(d.icon, null) },
                                selected = currentRoute == d.route,
                                onClick = { scope.launch { drawerState.close() }; nav.top(d.navRoute) },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    }
                }
            },
        ) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    if (bottomDestinations.any { it.route == currentRoute } || currentRoute == null) {
                        NavigationBar {
                            bottomDestinations.forEach { d ->
                                NavigationBarItem(
                                    selected = currentRoute == d.route, onClick = { nav.top(d.navRoute) },
                                    icon = { Icon(d.icon, null) }, label = { Text(d.label, maxLines = 1) },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                Column(Modifier.padding(bottom = padding.calculateBottomPadding())) {
                    NavHost(controller, startDestination = Routes.HOME, modifier = Modifier.weight(1f)) {
                        composable(Routes.HOME) { HomeScreen(nav) }
                        composable(Routes.FOR_YOU) { ForYouScreen(nav) }
                        composable(Routes.CATEGORIES) { CategoriesScreen(nav) }
                        composable(Routes.LATEST) { LatestScreen(nav) }
                        composable(Routes.MULTIMEDIA) { MultimediaScreen(nav) }
                        composable(Routes.SOURCES, arguments = listOf(navArgument("add") { type = NavType.StringType; defaultValue = "" })) {
                            SourcesScreen(nav, prefillUrl = it.arguments?.getString("add").orEmpty())
                        }
                        composable(Routes.SAVED) { SavedScreen(nav) }
                        composable(Routes.SEARCH, arguments = listOf(navArgument("q") { type = NavType.StringType; defaultValue = "" })) {
                            SearchScreen(nav, initialQuery = it.arguments?.getString("q").orEmpty())
                        }
                        composable(Routes.SETTINGS) { SettingsScreen(nav) }
                        composable(Routes.STORY) { StoryDetailScreen(nav) }
                        composable(Routes.CATEGORY) { CategoryFeedScreen(nav) }
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
}
