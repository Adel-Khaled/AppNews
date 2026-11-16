package com.smartnews.sync

import com.smartnews.domain.repository.NewsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class RefreshState(val isRefreshing: Boolean = false, val message: String? = null)

/**
 * App-wide refresh: every screen's Refresh button / pull-to-refresh shares one state, and the
 * work keeps running if the user navigates away mid-refresh.
 */
@Singleton
class RefreshController @Inject constructor(private val repository: NewsRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(RefreshState())
    val state: StateFlow<RefreshState> = _state.asStateFlow()

    fun refresh(categoryId: String? = null) {
        if (_state.value.isRefreshing) return
        _state.value = RefreshState(isRefreshing = true)
        scope.launch {
            val msg = runCatching { repository.refresh(categoryId) }.fold(
                onSuccess = { r ->
                    buildString {
                        append(if (r.newArticles == 0) "No new articles" else "${r.newArticles} new articles")
                        if (r.updatedArticles > 0) append(", ${r.updatedArticles} updated")
                        if (r.newVideos > 0) append(", ${r.newVideos} videos/images")
                        if (r.sourcesFailed > 0) append(" · ${r.sourcesFailed} source(s) unavailable — see My Sources")
                    }
                },
                onFailure = { "Refresh failed: ${it.message}" },
            )
            _state.value = RefreshState(isRefreshing = false, message = msg)
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}
