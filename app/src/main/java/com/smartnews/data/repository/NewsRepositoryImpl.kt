package com.smartnews.data.repository

import android.content.Context
import com.smartnews.data.local.ArticleDao
import com.smartnews.data.local.ArticleEntity
import com.smartnews.data.local.CategoryDao
import com.smartnews.data.local.HistoryDao
import com.smartnews.data.local.HistoryEntity
import com.smartnews.data.local.MediaDao
import com.smartnews.data.local.MediaEntity
import com.smartnews.data.local.SourceDao
import com.smartnews.data.local.SourceEntity
import com.smartnews.data.local.StoryDao
import com.smartnews.data.local.StoryEntity
import com.smartnews.data.local.toDomain
import com.smartnews.data.remote.ArticlePageScraper
import com.smartnews.data.remote.BackendApi
import com.smartnews.data.remote.BackendConfig
import com.smartnews.data.remote.GoogleNews
import com.smartnews.data.remote.NewsItemDto
import com.smartnews.data.remote.ParsedItem
import com.smartnews.data.remote.ParsedVideo
import com.smartnews.data.remote.RssParser
import com.smartnews.data.remote.VideoDto
import com.smartnews.data.remote.VideoRequestDto
import com.smartnews.data.remote.WebFetcher
import com.smartnews.data.seed.DefaultCatalog
import com.smartnews.domain.logic.CategoryClassifier
import com.smartnews.domain.logic.ClusterCandidate
import com.smartnews.domain.logic.StoryClusterer
import com.smartnews.domain.logic.TextAnalysis
import com.smartnews.domain.logic.VideoMatcher
import com.smartnews.domain.model.AppSettings
import com.smartnews.domain.model.Article
import com.smartnews.domain.model.ContentType
import com.smartnews.domain.model.DateFilter
import com.smartnews.domain.model.HistoryEntry
import com.smartnews.domain.model.MediaItem
import com.smartnews.domain.model.MediaType
import com.smartnews.domain.model.Playback
import com.smartnews.domain.model.RefreshResult
import com.smartnews.domain.model.SortMode
import com.smartnews.domain.model.SourceType
import com.smartnews.domain.model.Story
import com.smartnews.domain.repository.NewsRepository
import com.smartnews.domain.repository.SearchResults
import com.smartnews.domain.repository.SettingsRepository
import com.smartnews.sync.Notifier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Seeds built-in categories and feeds exactly once, so user removals are respected. */
@Singleton
class Seeder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val categoryDao: CategoryDao,
    private val sourceDao: SourceDao,
) {
    private val mutex = Mutex()
    suspend fun ensureSeeded() = mutex.withLock {
        val prefs = context.getSharedPreferences("seed", Context.MODE_PRIVATE)
        if (prefs.getInt("version", 0) >= SEED_VERSION) return@withLock
        val now = System.currentTimeMillis()
        categoryDao.insertIgnore(DefaultCatalog.categoryEntities())
        sourceDao.insertIgnore(DefaultCatalog.sourceEntities(now) + DefaultCatalog.systemSources(now))
        prefs.edit().putInt("version", SEED_VERSION).apply()
    }
    private companion object { const val SEED_VERSION = 1 }
}

/** One normalised incoming item, whatever provider it came from. */
internal data class Incoming(
    val url: String,
    val title: String,
    val description: String,
    val sourceId: String,
    val sourceName: String,
    val author: String?,
    val imageUrl: String?,
    val publishedAt: Long?,
    val categoryId: String?,
    val provider: String,
    val images: List<String> = emptyList(),
    val videos: List<ParsedVideo> = emptyList(),
)

internal data class FetchTask(val sourceId: String, val publisher: String, val url: String, val categoryId: String?, val provider: String)

@Singleton
class NewsRepositoryImpl @Inject constructor(
    private val articleDao: ArticleDao,
    private val storyDao: StoryDao,
    private val mediaDao: MediaDao,
    private val sourceDao: SourceDao,
    private val categoryDao: CategoryDao,
    private val historyDao: HistoryDao,
    private val fetcher: WebFetcher,
    private val scraper: ArticlePageScraper,
    private val api: BackendApi,
    private val backend: BackendConfig,
    private val settings: SettingsRepository,
    private val notifier: Notifier,
    private val seeder: Seeder,
) : NewsRepository {

    private val refreshMutex = Mutex()
    private val storeMutex = Mutex()

    // ------------------------------------------------------------------ refresh

    override suspend fun refresh(onlyCategoryId: String?): RefreshResult = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            seeder.ensureSeeded()
            val s = settings.snapshot()
            val firstSync = s.lastSyncAt == 0L
            val categories = categoryDao.getAll().filter { it.enabled && (onlyCategoryId == null || it.id == onlyCategoryId) }
            val sources = sourceDao.getEnabled()
            val errors = mutableListOf<String>()

            val tasks = buildTasks(s, sources, categories, onlyCategoryId)
            val sem = Semaphore(6)
            val results = coroutineScope {
                tasks.map { t ->
                    async {
                        sem.withPermit {
                            t to runCatching { fetcher.getFeed(t.url).items.map { it.toIncoming(t) } }
                        }
                    }
                }.awaitAll()
            }
            // Record per-source health. A source is healthy if at least one of its feeds worked.
            val now = System.currentTimeMillis()
            results.groupBy { it.first.sourceId }.forEach { (sid, rs) ->
                val failure = rs.firstOrNull { it.second.isFailure }?.second?.exceptionOrNull()
                val anyOk = rs.any { it.second.isSuccess }
                val err = if (anyOk) null else (failure?.message ?: "Unknown error")
                if (err != null) errors += "${rs.first().first.publisher}: $err"
                sourceDao.markSync(sid, now, err)
            }
            val incoming = results.flatMap { it.second.getOrNull().orEmpty() }.toMutableList()

            // Backend news APIs (NewsAPI / GNews / Guardian …): optional, isolated from feed failures.
            val backendSource = sources.firstOrNull { it.id == DefaultCatalog.BACKEND_SOURCE_ID }
            if (backend.isConfigured && backendSource != null) {
                val err = runCatching {
                    val cats = categories.filter { !it.isTopic }.take(8)
                    for (c in cats) {
                        val resp = api.news(c.id, null, null, null, s.language, s.country)
                        incoming += resp.items.mapNotNull { it.toIncoming(c.id) }
                    }
                    categories.filter { it.isTopic }.take(5).forEach { t ->
                        val resp = api.news(null, t.keywordsEn.split(',').first(), null, null, s.language, s.country)
                        incoming += resp.items.mapNotNull { it.toIncoming(t.id) }
                    }
                }.exceptionOrNull()?.message
                sourceDao.markSync(DefaultCatalog.BACKEND_SOURCE_ID, now, err)
                if (err != null) errors += "Backend: $err"
            }
            // Facebook pages via the backend's Graph API integration.
            sources.filter { it.type == SourceType.FACEBOOK_PAGE.name && it.feedUrl != null }.forEach { src ->
                val err = if (!backend.isConfigured) "Backend not configured" else runCatching {
                    incoming += api.facebookPosts(src.feedUrl!!).items.mapNotNull { it.toIncoming(src.categoryId, src.id, src.name) }
                }.exceptionOrNull()?.message
                sourceDao.markSync(src.id, now, err)
                if (err != null) errors += "${src.name}: $err"
            }

            val stored = store(incoming, s)
            var newVideos = stored.third

            // Automatic related-video matching for the biggest fresh stories (quota friendly).
            if (backend.isConfigured) {
                storyDao.needingMedia(now - 2 * DAY, limit = 6).forEach { st ->
                    newVideos += loadStoryMedia(st.id, force = false).getOrDefault(0)
                }
            }
            maybeNotify(s, firstSync, stored.fourth)
            settings.update { it.copy(lastSyncAt = System.currentTimeMillis()) }
            val failed = results.groupBy { it.first.sourceId }.count { (_, rs) -> rs.none { it.second.isSuccess } }
            RefreshResult(
                newArticles = stored.first, updatedArticles = stored.second, newVideos = newVideos,
                sourcesOk = results.map { it.first.sourceId }.distinct().size - failed, sourcesFailed = failed, errors = errors,
            )
        }
    }

    override suspend fun refreshSource(sourceId: String): RefreshResult = withContext(Dispatchers.IO) {
        val src = sourceDao.get(sourceId) ?: return@withContext RefreshResult(0, 0, 0, 0, 1, listOf("Source not found"))
        val s = settings.snapshot()
        val now = System.currentTimeMillis()
        val result = runCatching {
            if (src.type == SourceType.FACEBOOK_PAGE.name) {
                api.facebookPosts(src.feedUrl ?: error("No page")).items.mapNotNull { it.toIncoming(src.categoryId, src.id, src.name) }
            } else {
                val url = src.feedUrl ?: error("This source has no feed")
                fetcher.getFeed(url).items.map { it.toIncoming(FetchTask(src.id, src.name, url, src.categoryId, providerFor(src))) }
            }
        }
        sourceDao.markSync(src.id, now, result.exceptionOrNull()?.message)
        val items = result.getOrElse { return@withContext RefreshResult(0, 0, 0, 0, 1, listOf(it.message ?: "Failed")) }
        val stored = store(items, s)
        RefreshResult(stored.first, stored.second, stored.third, 1, 0, emptyList())
    }

    private fun providerFor(src: SourceEntity) = when (src.type) {
        SourceType.YOUTUBE_CHANNEL.name -> "youtube"
        SourceType.GOOGLE_NEWS.name -> "google-news"
        else -> "rss"
    }

    private fun buildTasks(s: AppSettings, sources: List<SourceEntity>, categories: List<com.smartnews.data.local.CategoryEntity>, only: String?): List<FetchTask> {
        val tasks = mutableListOf<FetchTask>()
        sources.filter { it.feedUrl != null && it.type != SourceType.FACEBOOK_PAGE.name && it.type != SourceType.UNSUPPORTED.name }
            .filter { only == null || it.categoryId == only }
            .forEach { tasks += FetchTask(it.id, it.name, it.feedUrl!!, it.categoryId, providerFor(it)) }

        val google = sources.any { it.id == DefaultCatalog.GOOGLE_NEWS_SOURCE_ID }
        if (s.useGoogleNews && google) {
            val gid = DefaultCatalog.GOOGLE_NEWS_SOURCE_ID
            val gName = "Google News"
            if (only == null) tasks += FetchTask(gid, gName, GoogleNews.top(s.language, s.country), "general", "google-news")
            for (c in categories) {
                val url = when {
                    c.id == "local" -> GoogleNews.nation(s.language, s.country)
                    c.id == "general" -> continue
                    else -> {
                        val kw = (if (s.language == "ar" && c.keywordsAr.isNotBlank()) c.keywordsAr else c.keywordsEn)
                            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        if (kw.isEmpty()) continue
                        val q = if (c.isTopic) kw.joinToString(" OR ") { "\"$it\"" } else kw.joinToString(" OR ")
                        GoogleNews.search(q, s.language, s.country, days = 2)
                    }
                }
                tasks += FetchTask(gid, gName, url, c.id, "google-news")
            }
        }
        return tasks
    }

    private fun ParsedItem.toIncoming(t: FetchTask) = Incoming(
        url = link, title = title, description = description, sourceId = t.sourceId,
        sourceName = sourceName ?: t.publisher, author = author, imageUrl = images.firstOrNull(),
        publishedAt = publishedAt, categoryId = t.categoryId, provider = t.provider,
        images = images, videos = videos,
    )

    private fun NewsItemDto.toIncoming(categoryId: String?, sourceId: String = DefaultCatalog.BACKEND_SOURCE_ID, fallbackName: String? = null): Incoming? {
        if (title.isBlank() || !url.startsWith("http")) return null
        return Incoming(
            url = url, title = RssParser.cleanText(title), description = RssParser.cleanText(description ?: ""),
            sourceId = sourceId, sourceName = source.ifBlank { fallbackName ?: provider }, author = author,
            imageUrl = imageUrl, publishedAt = RssParser.parseDate(publishedAt), categoryId = category ?: categoryId,
            provider = provider.ifBlank { "backend" }, images = listOfNotNull(imageUrl),
        )
    }

    /**
     * Inserts new articles, updates changed ones, drops exact duplicates and clusters the rest
     * into stories. Returns (inserted, updated, newMedia, insertedEntities).
     */
    private suspend fun store(incoming: List<Incoming>, s: AppSettings): Quad = storeMutex.withLock {
        if (incoming.isEmpty()) return@withLock Quad(0, 0, 0, emptyList())
        val now = System.currentTimeMillis()
        val categoryKeywords = categoryDao.getAll().associate { c ->
            c.id to (c.keywordsEn.split(',') + c.keywordsAr.split(',')).map { it.trim() }.filter { it.isNotEmpty() }
        }
        val byId = LinkedHashMap<String, Pair<ArticleEntity, Incoming>>()
        for (inc in incoming) {
            val canonical = TextAnalysis.canonicalUrl(inc.url)
            val id = TextAnalysis.sha1(canonical)
            if (id in byId) continue
            val text = inc.title + ". " + inc.description
            val published = (inc.publishedAt ?: now).coerceAtMost(now + 3_600_000)
            val cat = inc.categoryId?.takeIf { it != "general" } ?: CategoryClassifier.classify(text, categoryKeywords)
            byId[id] = ArticleEntity(
                id = id, url = inc.url, title = inc.title, titleNormalized = TextAnalysis.normalize(inc.title),
                description = inc.description, sourceId = inc.sourceId, sourceName = inc.sourceName, author = inc.author,
                imageUrl = inc.imageUrl, publishedAt = published, collectedAt = now, updatedAt = now, categoryId = cat,
                language = s.language, storyId = id, provider = inc.provider,
                tokens = TextAnalysis.tokens(text).joinToString(" "),
                entities = TextAnalysis.entities(text).joinToString("|"),
            ) to inc
        }
        val existing = byId.keys.chunked(500).flatMap { articleDao.existingIds(it) }.toSet()
        var updated = 0
        existing.forEach { id ->
            val e = byId[id]!!.first
            updated += articleDao.updateContent(id, e.title, e.titleNormalized, e.description, e.imageUrl, now)
        }

        val candidates = articleDao.recentForClustering(now - 5 * DAY).map { it.toCandidate() }.toMutableList()
        val toInsert = mutableListOf<Pair<ArticleEntity, Incoming>>()
        for ((entity, inc) in byId.values.filter { it.first.id !in existing }.sortedBy { it.first.publishedAt }) {
            val cand = entity.toCandidate()
            val match = StoryClusterer.bestMatch(cand, candidates)
            if (match?.second == StoryClusterer.Relation.DUPLICATE) continue // same article from another feed
            val storyId = match?.first?.storyId ?: entity.id
            val finalEntity = entity.copy(storyId = storyId)
            candidates += cand.copy(storyId = storyId)
            toInsert += finalEntity to inc
        }
        articleDao.insertIgnore(toInsert.map { it.first })

        // Media published inside the feeds themselves (images, video files, YouTube items).
        val media = mutableListOf<MediaEntity>()
        for ((a, inc) in toInsert) {
            inc.images.take(3).forEach { img ->
                media += mediaEntity(a.storyId, a.id, MediaType.IMAGE, a.title, img, img, a.sourceName, a.publishedAt, null, Playback.NONE, 0.9, now)
            }
            inc.videos.take(2).forEach { v ->
                val yt = youtubeId(v.url)
                val playback = when {
                    yt != null -> Playback.YOUTUBE_EMBED
                    v.url.lowercase().substringBefore('?').let { it.endsWith(".mp4") || it.endsWith(".m3u8") || it.endsWith(".webm") } -> Playback.EXOPLAYER
                    else -> Playback.EXTERNAL
                }
                val thumb = v.thumbnail ?: yt?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" } ?: a.imageUrl
                media += mediaEntity(a.storyId, a.id, MediaType.VIDEO, a.title, v.url, thumb, a.sourceName, a.publishedAt, v.durationSec, playback, 1.0, now)
            }
        }
        val newMedia = if (media.isEmpty()) 0 else mediaDao.insertIgnore(media).count { it != -1L }

        toInsert.map { it.first.storyId }.distinct().forEach { updateStory(it, now) }
        Quad(toInsert.size, updated, newMedia, toInsert.map { it.first })
    }

    private data class Quad(val first: Int, val second: Int, val third: Int, val fourth: List<ArticleEntity>)

    private suspend fun updateStory(storyId: String, now: Long) {
        val agg = articleDao.storyAgg(storyId) ?: return
        if (agg.articleCount == 0) return
        val first = agg.firstPublishedAt ?: now
        val last = agg.lastPublishedAt ?: now
        val existing = storyDao.get(storyId)
        val lead = articleDao.get(storyId) ?: articleDao.getByStory(storyId).firstOrNull() ?: return
        val breaking = (agg.sourceCount >= 3 && last - first <= 6 * HOUR && last >= now - 6 * HOUR) ||
            (TextAnalysis.looksBreaking(lead.title) && last >= now - 6 * HOUR)
        val story = existing?.copy(
            imageUrl = existing.imageUrl ?: agg.imageUrl, firstPublishedAt = first, lastPublishedAt = last, updatedAt = now,
            articleCount = agg.articleCount, sourceCount = agg.sourceCount, isBreaking = existing.isBreaking || breaking,
        ) ?: StoryEntity(
            id = storyId, headline = lead.title, imageUrl = agg.imageUrl, categoryId = lead.categoryId,
            firstPublishedAt = first, lastPublishedAt = last, updatedAt = now, articleCount = agg.articleCount,
            sourceCount = agg.sourceCount, leadSourceName = lead.sourceName, isBreaking = breaking,
        )
        storyDao.upsert(story)
    }

    private suspend fun maybeNotify(s: AppSettings, firstSync: Boolean, inserted: List<ArticleEntity>) {
        val now = System.currentTimeMillis()
        val since = now - 6 * HOUR
        val candidates = mutableListOf<StoryEntity>()
        if (s.notifyBreaking) candidates += storyDao.breakingNotNotified(since)
        if (s.notifyCategoryIds.isNotEmpty()) candidates += storyDao.unnotifiedInCategories(s.notifyCategoryIds.toList(), since).filter { it.sourceCount >= 2 }
        if (s.notifySourceIds.isNotEmpty()) {
            inserted.filter { it.sourceId in s.notifySourceIds }.map { it.storyId }.distinct().take(3)
                .mapNotNull { storyDao.get(it) }.filter { it.notifiedAt == null }.forEach { candidates += it }
        }
        val unique = candidates.distinctBy { it.id }
        if (!s.notificationsEnabled || firstSync) {
            // Never flood the user with the backlog of a first sync.
            unique.forEach { storyDao.markNotified(it.id, now) }
            return
        }
        val allowed = notifier.remainingToday(s.maxNotificationsPerDay)
        unique.sortedByDescending { it.sourceCount }.take(allowed).forEach {
            notifier.showStory(it.toDomain())
            storyDao.markNotified(it.id, now)
        }
    }

    // ------------------------------------------------------------------ media

    override suspend fun loadStoryMedia(storyId: String, force: Boolean): Result<Int> = withContext(Dispatchers.IO) {
        val story = storyDao.get(storyId) ?: return@withContext Result.failure(IllegalStateException("Story not found"))
        val now = System.currentTimeMillis()
        if (!force && story.mediaFetchedAt != null && now - story.mediaFetchedAt < 6 * HOUR) return@withContext Result.success(0)
        val s = settings.snapshot()
        val articles = articleDao.getByStory(storyId)
        val media = mutableListOf<MediaEntity>()
        val errors = mutableListOf<String>()

        // 1) The publishers' own pages: lead images, galleries, embedded videos.
        articles.filter { !it.url.contains("news.google.com") }.distinctBy { it.sourceName }.take(3).forEach { a ->
            runCatching { scraper.extras(a.url) }.onSuccess { ex ->
                ex.leadImage?.let { articleDao.setImageIfMissing(a.id, it) }
                (listOfNotNull(ex.leadImage) + ex.images).distinct().take(6).forEach { img ->
                    media += mediaEntity(storyId, a.id, MediaType.IMAGE, a.title, img, img, a.sourceName, a.publishedAt, null, Playback.NONE, 0.95, now)
                }
                ex.youtubeIds.take(3).forEach { id ->
                    media += mediaEntity(storyId, a.id, MediaType.VIDEO, "Video embedded by ${a.sourceName}", "https://www.youtube.com/watch?v=$id",
                        "https://i.ytimg.com/vi/$id/hqdefault.jpg", a.sourceName, a.publishedAt, null, Playback.YOUTUBE_EMBED, 1.0, now)
                }
                ex.videoFiles.take(2).forEach { f ->
                    media += mediaEntity(storyId, a.id, MediaType.VIDEO, a.title, f, a.imageUrl ?: ex.leadImage, a.sourceName, a.publishedAt, null, Playback.EXOPLAYER, 1.0, now)
                }
            }.onFailure { errors += "${a.sourceName}: ${it.message}" }
        }

        // 2) YouTube search, matched to this exact event (backend holds the YouTube key).
        if (backend.isConfigured) {
            runCatching {
                val entities = articles.flatMap { it.entities.split('|') }.filter { it.isNotBlank() }
                    .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(8)
                val lead = articles.firstOrNull()
                val resp = api.videos(
                    VideoRequestDto(
                        title = story.headline, description = lead?.description.orEmpty().take(500), entities = entities,
                        publishedAt = Instant.ofEpochMilli(story.firstPublishedAt).toString(), lang = s.language,
                    )
                )
                val tokens = articles.flatMap { it.tokens.split(' ') }.filter { it.isNotBlank() }.toSet()
                resp.items.forEach { v ->
                    val local = VideoMatcher.score(
                        tokens, entities.toSet(), story.firstPublishedAt,
                        VideoMatcher.Input(v.title, v.description, v.channel, RssParser.parseDate(v.publishedAt)), newsChannel = v.relevance > 0.5,
                    )
                    val score = maxOf(local, v.relevance)
                    if (score >= VideoMatcher.THRESHOLD) media += v.toMedia(storyId, score, now)
                }
            }.onFailure { errors += "Video search: ${it.message}" }
        }
        val added = if (media.isEmpty()) 0 else mediaDao.insertIgnore(media).count { it != -1L }
        storyDao.markMediaFetched(storyId, now)
        if (added == 0 && errors.isNotEmpty() && media.isEmpty()) Result.failure(IllegalStateException(errors.joinToString("\n")))
        else Result.success(added)
    }

    private fun VideoDto.toMedia(storyId: String, score: Double, now: Long): MediaEntity {
        val yt = videoId ?: youtubeId(url)
        val playback = when {
            yt != null && embeddable -> Playback.YOUTUBE_EMBED
            else -> Playback.EXTERNAL
        }
        return mediaEntity(
            storyId, null, if (isLive) MediaType.LIVE else MediaType.VIDEO, RssParser.cleanText(title), url,
            thumbnailUrl ?: yt?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }, channel,
            RssParser.parseDate(publishedAt), durationSec, playback, score, now, isLive = isLive,
        )
    }

    private fun mediaEntity(
        storyId: String, articleId: String?, type: MediaType, title: String, url: String, thumb: String?, publisher: String,
        publishedAt: Long?, duration: Int?, playback: Playback, relevance: Double, now: Long, isLive: Boolean = false,
    ) = MediaEntity(
        id = TextAnalysis.sha1(storyId + "|" + TextAnalysis.canonicalUrl(url)), storyId = storyId, articleId = articleId,
        type = type.name, title = title, url = url, thumbnailUrl = thumb, publisher = publisher, publishedAt = publishedAt,
        durationSec = duration, isLive = isLive, playback = playback.name,
        attribution = if (type == MediaType.IMAGE) "Image: $publisher" else publisher,
        relevance = relevance, collectedAt = now, isInterview = VideoMatcher.isInterview(title),
    )

    private fun youtubeId(url: String): String? =
        Regex("(?:youtube\\.com/(?:watch\\?v=|embed/|shorts/|live/)|youtu\\.be/)([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1)

    override suspend fun fetchLive(query: String?): Result<List<MediaItem>> = withContext(Dispatchers.IO) {
        if (!backend.isConfigured) return@withContext Result.failure(
            IllegalStateException("Live stream discovery uses the YouTube Data API through your backend. Configure it in Settings → Backend.")
        )
        runCatching {
            val now = System.currentTimeMillis()
            mediaDao.expireLive(now - 30 * 60_000)
            val s = settings.snapshot()
            val items = api.live(query, s.language).items.filter { it.isLive }.map { it.toMedia(LIVE_STORY, 1.0, now) }
            mediaDao.insertIgnore(items)
            items.map { it.toDomain() }
        }
    }

    // ------------------------------------------------------------------ observe

    override fun observeStories(categoryId: String?, filter: DateFilter, sort: SortMode, limit: Int): Flow<List<Story>> {
        val r = filter.range()
        return storyDao.observeFeed(categoryId, r.first, r.last, sort == SortMode.RELEVANCE, limit).map { l -> l.map { it.toDomain() } }
    }

    override fun observeStoriesFor(categoryIds: List<String>, filter: DateFilter, sort: SortMode, limit: Int): Flow<List<Story>> {
        val r = filter.range()
        return storyDao.observeForCategories(categoryIds, r.first, r.last, sort == SortMode.RELEVANCE, limit).map { l -> l.map { it.toDomain() } }
    }

    override fun observeTopStories(limit: Int) =
        storyDao.observeTop(System.currentTimeMillis() - DAY, limit).map { l -> l.map { it.toDomain() } }

    override fun observeBreaking() =
        storyDao.observeBreaking(System.currentTimeMillis() - 12 * HOUR).map { l -> l.map { it.toDomain() } }

    override fun observeRecentlyUpdated(limit: Int) =
        storyDao.observeRecentlyUpdated(limit).map { l -> l.map { it.toDomain() } }

    override fun observeArticles(categoryId: String?, sourceId: String?, filter: DateFilter, limit: Int): Flow<List<Article>> {
        val r = filter.range()
        return articleDao.observeFeed(categoryId, sourceId, r.first, r.last, limit).map { l -> l.map { it.toDomain() } }
    }

    override fun observeTrendingTopics(): Flow<List<String>> =
        articleDao.observeEntitiesSince(System.currentTimeMillis() - DAY).map { rows ->
            rows.flatMap { it.split('|') }.filter { it.length > 2 }
                .groupingBy { it }.eachCount().filter { it.value >= 2 }
                .entries.sortedByDescending { it.value }.take(12)
                .map { e -> e.key.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } } }
        }

    override fun observeArticleCount() = articleDao.observeCount()
    override fun observeStory(id: String) = storyDao.observe(id).map { it?.toDomain() }
    override fun observeStoryArticles(storyId: String) = articleDao.observeByStory(storyId).map { l -> l.map { it.toDomain() } }
    override fun observeStoryMedia(storyId: String) = mediaDao.observeByStory(storyId).map { l -> l.map { it.toDomain() } }
    override fun observeMedia(type: MediaType, limit: Int) = mediaDao.observeByType(type.name, limit).map { l -> l.map { it.toDomain() } }

    // ------------------------------------------------------------------ search

    override suspend fun search(
        query: String, categoryId: String?, sourceId: String?, filter: DateFilter,
        type: ContentType, sort: SortMode, includeOnline: Boolean,
    ): SearchResults = withContext(Dispatchers.IO) {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) return@withContext SearchResults(emptyList(), emptyList(), null)
        var onlineError: String? = null

        if (includeOnline) {
            val s = settings.snapshot()
            val days = when (filter) {
                DateFilter.Today -> 1; DateFilter.Yesterday -> 2; DateFilter.Last7Days -> 7; DateFilter.Last30Days -> 30
                else -> null
            }
            val incoming = mutableListOf<Incoming>()
            val errs = mutableListOf<String>()
            runCatching {
                val url = GoogleNews.search(query, s.language, s.country, days)
                val t = FetchTask(DefaultCatalog.GOOGLE_NEWS_SOURCE_ID, "Google News", url, categoryId, "google-news")
                incoming += fetcher.getFeed(url).items.map { it.toIncoming(t) }
            }.onFailure { errs += "Google News: ${it.message}" }
            if (backend.isConfigured) {
                runCatching {
                    val r = filter.range()
                    val from = r.first.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).toString() }
                    val to = r.last.takeIf { it != Long.MAX_VALUE }?.let { Instant.ofEpochMilli(it).toString() }
                    incoming += api.news(categoryId, query, from, to, s.language, s.country).items.mapNotNull { it.toIncoming(categoryId) }
                }.onFailure { errs += "Backend: ${it.message}" }
                if (type == ContentType.ALL || type == ContentType.VIDEOS) runCatching {
                    val resp = api.videos(VideoRequestDto(query, "", terms, Instant.now().toString(), s.language, 10))
                    val now = System.currentTimeMillis()
                    mediaDao.insertIgnore(resp.items.map { it.toMedia(SEARCH_STORY, it.relevance, now) })
                }.onFailure { errs += "Video search: ${it.message}" }
            }
            store(incoming, s)
            if (errs.isNotEmpty() && incoming.isEmpty()) onlineError = errs.joinToString("; ")
        }

        val r = filter.range()
        val primary = terms.maxBy { it.length }
        val articles = if (type == ContentType.ALL || type == ContentType.ARTICLES) {
            articleDao.search(primary, categoryId, sourceId, r.first, r.last, savedOnly = false, limit = 400)
                .filter { a -> val hay = (a.title + " " + a.description + " " + a.entities + " " + a.sourceName).lowercase(); terms.all { it in hay } }
                .map { it.toDomain() }
        } else emptyList()
        val media = when (type) {
            ContentType.ALL -> mediaDao.search(primary, null, r.first, r.last, 200)
            ContentType.VIDEOS -> mediaDao.search(primary, MediaType.VIDEO.name, r.first, r.last, 200)
            ContentType.IMAGES -> mediaDao.search(primary, MediaType.IMAGE.name, r.first, r.last, 200)
            ContentType.ARTICLES -> emptyList()
        }.filter { m -> terms.all { m.title.lowercase().contains(it) || m.publisher.lowercase().contains(it) } }
            .distinctBy { it.url }.map { it.toDomain() }

        val sorted = when (sort) {
            SortMode.NEWEST -> articles.sortedByDescending { it.publishedAt }
            SortMode.RELEVANCE -> articles.sortedByDescending { a ->
                val t = a.title.lowercase(); val d = a.description.lowercase()
                terms.sumOf { (if (it in t) 3.0 else 0.0) + (if (it in d) 1.0 else 0.0) } +
                    (if (query.lowercase() in t) 4.0 else 0.0) + recencyBoost(a.publishedAt)
            }
        }
        SearchResults(sorted.take(200), media, onlineError)
    }

    private fun recencyBoost(t: Long): Double {
        val days = (System.currentTimeMillis() - t) / DAY.toDouble()
        return 2.0 / (1.0 + days)
    }

    // ------------------------------------------------------------------ saved & history

    override suspend fun setArticleSaved(id: String, saved: Boolean) = articleDao.setSaved(id, saved, if (saved) System.currentTimeMillis() else null)
    override suspend fun setStorySaved(id: String, saved: Boolean) = storyDao.setSaved(id, saved)
    override suspend fun setMediaSaved(id: String, saved: Boolean) = mediaDao.setSaved(id, saved, if (saved) System.currentTimeMillis() else null)
    override fun observeSavedArticles() = articleDao.observeSaved().map { l -> l.map { it.toDomain() } }
    override fun observeSavedMedia() = mediaDao.observeSaved().map { l -> l.map { it.toDomain() } }
    override fun observeSavedStories() = storyDao.observeSaved().map { l -> l.map { it.toDomain() } }

    override suspend fun recordView(article: Article) {
        articleDao.markRead(article.id)
        historyDao.insert(HistoryEntity(articleId = article.id, storyId = article.storyId, viewedAt = System.currentTimeMillis()))
    }

    override fun observeHistory(limit: Int) = historyDao.observe(limit).map { l -> l.map { HistoryEntry(it.article.toDomain(), it.viewedAt) } }
    override suspend fun clearHistory() = historyDao.clear()

    override suspend fun deleteOldUnsaved(olderThanDays: Int): Int {
        val n = articleDao.deleteUnsavedOlderThan(System.currentTimeMillis() - olderThanDays * DAY)
        storyDao.deleteOrphans()
        return n
    }

    private fun ArticleEntity.toCandidate() = ClusterCandidate(
        id = id, storyId = storyId, sourceName = sourceName, titleNormalized = titleNormalized,
        tokens = tokens.split(' ').filter { it.isNotBlank() }.toSet(),
        entities = entities.split('|').filter { it.isNotBlank() }.toSet(), publishedAt = publishedAt,
    )

    companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
        const val LIVE_STORY = "live"
        const val SEARCH_STORY = "search"
    }
}
