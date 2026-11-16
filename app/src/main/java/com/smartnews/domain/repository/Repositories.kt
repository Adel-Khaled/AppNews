package com.smartnews.domain.repository

import com.smartnews.domain.model.AppSettings
import com.smartnews.domain.model.Article
import com.smartnews.domain.model.Category
import com.smartnews.domain.model.ContentType
import com.smartnews.domain.model.DateFilter
import com.smartnews.domain.model.Explanation
import com.smartnews.domain.model.HistoryEntry
import com.smartnews.domain.model.MediaItem
import com.smartnews.domain.model.MediaType
import com.smartnews.domain.model.NewsSource
import com.smartnews.domain.model.RefreshResult
import com.smartnews.domain.model.SortMode
import com.smartnews.domain.model.SourceDiscoveryResult
import com.smartnews.domain.model.Story
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface SettingsRepository {
    val settings: StateFlow<AppSettings>
    /** Reads the persisted settings (waits for DataStore; use from workers). */
    suspend fun snapshot(): AppSettings
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

interface CategoryRepository {
    fun observeCategories(): Flow<List<Category>>
    suspend fun setEnabled(id: String, enabled: Boolean)
    suspend fun addTopic(name: String, keywords: List<String>)
    suspend fun deleteCustom(id: String)
}

interface SourceRepository {
    fun observeSources(): Flow<List<NewsSource>>
    fun observeSource(id: String): Flow<NewsSource?>
    suspend fun discover(url: String): SourceDiscoveryResult
    suspend fun addSource(result: SourceDiscoveryResult, name: String, categoryId: String?): String
    suspend fun updateSource(id: String, name: String, feedUrl: String?, categoryId: String?)
    suspend fun setEnabled(id: String, enabled: Boolean)
    suspend fun remove(id: String)
}

data class SearchResults(val articles: List<Article>, val media: List<MediaItem>, val onlineError: String?)

interface NewsRepository {
    /** Fetches new content from every enabled source. Never deletes stored articles. */
    suspend fun refresh(onlyCategoryId: String? = null): RefreshResult
    suspend fun refreshSource(sourceId: String): RefreshResult

    fun observeStories(categoryId: String?, filter: DateFilter, sort: SortMode, limit: Int): Flow<List<Story>>
    fun observeStoriesFor(categoryIds: List<String>, filter: DateFilter, sort: SortMode, limit: Int): Flow<List<Story>>
    fun observeTopStories(limit: Int): Flow<List<Story>>
    fun observeBreaking(): Flow<List<Story>>
    fun observeRecentlyUpdated(limit: Int): Flow<List<Story>>
    fun observeArticles(categoryId: String?, sourceId: String?, filter: DateFilter, limit: Int): Flow<List<Article>>
    fun observeTrendingTopics(): Flow<List<String>>
    fun observeArticleCount(): Flow<Int>

    fun observeStory(id: String): Flow<Story?>
    fun observeStoryArticles(storyId: String): Flow<List<Article>>
    fun observeStoryMedia(storyId: String): Flow<List<MediaItem>>
    fun observeMedia(type: MediaType, limit: Int): Flow<List<MediaItem>>

    /** Loads page images, embedded videos and (with backend) matched YouTube videos for a story. */
    suspend fun loadStoryMedia(storyId: String, force: Boolean = false): Result<Int>
    suspend fun fetchLive(query: String?): Result<List<MediaItem>>

    suspend fun search(
        query: String, categoryId: String?, sourceId: String?, filter: DateFilter,
        type: ContentType, sort: SortMode, includeOnline: Boolean,
    ): SearchResults

    suspend fun setArticleSaved(id: String, saved: Boolean)
    suspend fun setStorySaved(id: String, saved: Boolean)
    suspend fun setMediaSaved(id: String, saved: Boolean)
    fun observeSavedArticles(): Flow<List<Article>>
    fun observeSavedMedia(): Flow<List<MediaItem>>
    fun observeSavedStories(): Flow<List<Story>>

    suspend fun recordView(article: Article)
    fun observeHistory(limit: Int): Flow<List<HistoryEntry>>
    suspend fun clearHistory()
    suspend fun deleteOldUnsaved(olderThanDays: Int): Int
}

interface AiRepository {
    /** Summary + key points for an article. AI (backend) when available, otherwise an extract. */
    suspend fun summarize(articleId: String): Result<Unit>
    suspend fun summarizeStory(storyId: String): Result<Unit>
    suspend fun explain(storyId: String, force: Boolean = false): Result<Explanation>
}
