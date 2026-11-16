package com.smartnews.sync

import com.smartnews.domain.model.RefreshResult
import com.smartnews.domain.repository.NewsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class RefreshState(
    val isRefreshing: Boolean = false,
    val refreshingCategory: String? = null,
    val translating: Boolean = false,
    val result: RefreshResult? = null,
    val failed: Boolean = false,
)

/**
 * App-wide refresh and translation. Every screen shares this state, and the work keeps running
 * when the user switches tabs.
 */
@Singleton
class RefreshController @Inject constructor(private val repository: NewsRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(RefreshState())
    val state: StateFlow<RefreshState> = _state.asStateFlow()
    private var translateJob: Job? = null

    fun refresh(categoryId: String? = null) {
        if (_state.value.isRefreshing) return
        _state.update { it.copy(isRefreshing = true, refreshingCategory = categoryId, result = null, failed = false) }
        scope.launch {
            val r = runCatching { repository.refresh(categoryId) }
            _state.update { it.copy(isRefreshing = false, refreshingCategory = null, result = r.getOrNull(), failed = r.isFailure) }
            translate()
        }
    }

    /** Translates new articles in small batches so the newest appear in the user's language first. */
    fun translate() {
        if (translateJob?.isActive == true) return
        translateJob = scope.launch {
            _state.update { it.copy(translating = true) }
            var rounds = 0
            while (rounds++ < 60) {
                val n = runCatching { repository.translatePending(25) }.getOrDefault(0)
                if (n == 0) break
            }
            _state.update { it.copy(translating = false) }
        }
    }

    fun consumeResult() = _state.update { it.copy(result = null, failed = false) }
}
