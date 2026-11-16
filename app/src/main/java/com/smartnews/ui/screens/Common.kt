package com.smartnews.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import com.smartnews.ui.navigation.Nav

/**
 * Standard screen frame: collapsing top bar, refresh action, pull-to-refresh and a
 * thin progress bar while a refresh is running.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenFrame(
    title: String,
    nav: Nav,
    isTopLevel: Boolean = true,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    showSearch: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Column(Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection)) {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.headlineSmall) },
            navigationIcon = {
                if (isTopLevel) IconButton(onClick = nav.openDrawer) { Icon(Icons.Default.Menu, "Menu") }
                else IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            },
            actions = {
                actions()
                if (showSearch) IconButton(onClick = { nav.search() }) { Icon(Icons.Default.Search, "Search") }
                if (onRefresh != null) {
                    if (isRefreshing) Box(Modifier.size(48.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh") }
                }
            },
            scrollBehavior = scroll,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        )
        if (isRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (onRefresh != null) {
            PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = onRefresh, modifier = Modifier.weight(1f)) {
                Column(Modifier.fillMaxSize()) { content() }
            }
        } else {
            Column(Modifier.weight(1f)) { content() }
        }
    }
}
