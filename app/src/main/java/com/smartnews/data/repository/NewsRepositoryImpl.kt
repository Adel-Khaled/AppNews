package com.smartnews.data.repository

import android.content.Context
import com.smartnews.data.local.ArticleDao
import com.smartnews.data.local.ArticleEntity
import com.smartnews.data.local.CategoryDao
import com.smartnews.data.local.CategoryEntity
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
import com.smartnews.data.remote.TranslationService
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
import com.smartnews.domain.model.Scope
import com.smartnews.domain.model.SourceType
import com.smartnews.domain.model.Story
import com.smartnews.domain.repository.NewsRepository
import com.smartnews.domain.repository.SearchResults
import com.smartnews.domain.repository.SettingsRepository
import com.smartnews.sync.Notifier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Seeds built-in categories and feeds once per seed version, so user removals are respected. */
@Singleton
class Seeder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val categoryDao: CategoryDao,
    private val sourceDao: SourceDao,
) {
    private val mutex = Mutex()
    suspend fun ensureSeeded() = mutex.withLock {
        val prefs = context.getSharedPreferences("seed", Context.MODE_PRIVATE)
        val version = prefs.getInt("version", 0)
        if (version >= SEED_VERSION) return@withLock
        val now = System.currentTimeMillis()
        if (version < 1) {
            categoryDao.insertIgnore(DefaultCatalog.categoryEntities())
            sourceDao.insertIgnore(DefaultCatalog.sourceEntities(now) + DefaultCatalog.systemSources(now))
        }
        if (version < 2) sourceDao.insertIgnore(DefaultCatalog.arabicSourceEntities(now))
        prefs.edit().putInt("version", SEED_VERSION).apply()
    }
    private companion object { const val SEED_VERSION = 2 }
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
    val scope: Scope,
    val images: List<String> = emptyList(),
    val videos: List<ParsedVideo> = emptyList(),
)

internal data class FetchTask(
    val sourceId: String, val publisher: String, val url: String, val categoryId: String?,
    val provider: String, val scope: Scope, val maxItems: Int = 40,
)

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
    private val translator: TranslationService,
) : NewsRepository {

    private val refreshMutex = Mutex()
    private val storeMutex = Mutex()
    private val translateMutex = Mutex()

    // ------------------------------------------------------------------ refresh

    override suspend fun refresh(onlyCategoryId: String?): RefreshResult = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            seeder.ensureSeeded()
            val s = settings.snapshot()
            val firstSync = s.lastSyncAt == 0L
            val categories = categoryDao.getAll()
            val sources = sourceDao.getEnabled()
            val errors = mutableListOf<String>()

            val tasks = buildTasks(s, sources, categories, onlyCategoryId)
            val sem = Semaphore(5)
            val results = coroutineScope {
                tasks.map { t ->
                    async {
                        sem.withPermit {
                            t to runCatching { fetcher.getFeed(t.url).items.take(t.maxItems).map { it.toIncoming(t) } }
                        }
                    }
                }.awaitAll()
            }
            val now = System.currentTimeMillis()
            results.groupBy { it.first.sourceId }.forEach { (sid, rs) ->
                val failure = rs.firstOrNull { it.second.isFailure }?.second?.exceptionOrNull()
                val err = if (rs.any { it.second.isSuccess }) null else (failure?.message ?: "Unknown error")
                if (err != null) errors += "${rs.first().first.publisher}: $err"
                sourceDao.markSync(sid, now, err)
            }
            val incoming = results.flatMap { it.second.getOrNull().orEmpty() }.toMutableList()

            // Optional backend news APIs (NewsAPI / GNews / Guardian) for world coverage.
            if (backend.isConfigured && sources.any { it.id == DefaultCatalog.BACKEND_SOURCE_ID }) {
                val err = runCatching {
                    val ids = if (onlyCategoryId != null) listOf(onlyCategoryId) else DefaultCatalog.tabCategoryIds.take(8)
                    for (id in ids) {
                        incoming += api.news(id, null, null, null, "en", "US").items.mapNotNull { it.toIncoming(id, Scope.GLOBAL) }
                    }
                }.exceptionOrNull()?.message
                sourceDao.markSync(DefaultCatalog.BACKEND_SOURCE_ID, now, err)
                if (err != null) errors += "Backend: $err"
            }
            if (onlyCategoryId == null) {
                sources.filter { it.type == SourceType.FACEBOOK_PAGE.name && it.feedUrl != null }.forEach { src ->
                    val err = if (!backend.isConfigured) "Backend not configured" else runCatching {
                        incoming += api.facebookPosts(src.feedUrl!!).items.mapNotNull { it.toIncoming(src.categoryId, scopeOf(src), src.id, src.name) }
                    }.exceptionOrNull()?.message
                    sourceDao.markSync(src.id, now, err)
                    if (err != null) errors += "${src.name}: $err"
                }
            }

            val stored = store(incoming)
            var newVideos = stored.newMedia
            if (backend.isConfigured && onlyCategoryId == null) {
                storyDao.needingMedia(now - 2 * DAY, limit = 6).forEach { st ->
                    newVideos += loadStoryMedia(st.id, force = false).getOrDefault(0)
                }
            }
            maybeNotify(s, firstSync)
            if (onlyCategoryId == null) settings.update { it.copy(lastSyncAt = System.currentTimeMillis()) }
            val failed = results.groupBy { it.first.sourceId }.count { (_, rs) -> rs.none { it.second.isSuccess } }
            RefreshResult(
                newArticles = stored.inserted, updatedArticles = stored.updated, newVideos = newVideos,
                sourcesOk = results.map { it.first.sourceId }.distinct().size - failed, sourcesFailed = failed, errors = errors,
            )
        }
    }

    override suspend fun refreshSource(sourceId: String): RefreshResult = withContext(Dispatchers.IO) {
        val src = sourceDao.get(sourceId) ?: return@withContext RefreshResult(0, 0, 0, 0, 1, listOf("Source not found"))
        val now = System.currentTimeMillis()
        val result = runCatching {
            if (src.type == SourceType.FACEBOOK_PAGE.name) {
                api.facebookPosts(src.feedUrl ?: error("No page")).items.mapNotNull { it.toIncoming(src.categoryId, scopeOf(src), src.id, src.name) }
            } else {
                val url = src.feedUrl ?: error("This source has no feed")
                fetcher.getFeed(url).items.map { it.toIncoming(FetchTask(src.id, src.name, url, src.categoryId, providerFor(src), scopeOf(src))) }
            }
        }
        sourceDao.markSync(src.id, now, result.exceptionOrNull()?.message)
        val items = result.getOrElse { return@withContext RefreshResult(0, 0, 0, 0, 1, listOf(it.message ?: "Failed")) }
        val stored = store(items)
        RefreshResult(stored.inserted, stored.updated, stored.newMedia, 1, 0, emptyList())
    }

    private fun providerFor(src: SourceEntity) = when (src.type) {
        SourceType.YOUTUBE_CHANNEL.name -> "youtube"
        SourceType.GOOGLE_NEWS.name -> "google-news"
        else -> "rss"
    }

    /** Custom sources on an Egyptian domain count as local; everything else as global. */
    private fun scopeOf(src: SourceEntity): Scope {
        val host = runCatching { java.net.URI(src.siteUrl).host.orEmpty() }.getOrDefault("")
        return if (host.endsWith(".eg") || src.categoryId == "local") Scope.LOCAL else Scope.GLOBAL
    }

    /**
     * Egypt (local): Google News Egypt edition — headlines, the national section and every category
     * searched together with "مصر".  World (global): international publishers (English and Arabic)
     * and Google News world coverage per category. Everything is translated into the app language later.
     */
    private fun buildTasks(s: AppSettings, sources: List<SourceEntity>, categories: List<CategoryEntity>, only: String?): List<FetchTask> {
        val tasks = mutableListOf<FetchTask>()
        sources.filter { it.feedUrl != null && it.type != SourceType.FACEBOOK_PAGE.name && it.type != SourceType.UNSUPPORTED.name }
            .filter { only == null || it.categoryId == only }
            .forEach { tasks += FetchTask(it.id, it.name, it.feedUrl!!, it.categoryId, providerFor(it), scopeOf(it)) }

        if (sources.none { it.id == DefaultCatalog.GOOGLE_NEWS_SOURCE_ID }) return tasks
        val g = DefaultCatalog.GOOGLE_NEWS_SOURCE_ID
        val ar = s.language == "ar"
        if (only == null) {
            tasks += FetchTask(g, "Google News", GoogleNews.top("ar", "EG"), "general", "google-news", Scope.LOCAL)
            tasks += FetchTask(g, "Google News", GoogleNews.nation("ar", "EG"), "local", "google-news", Scope.LOCAL)
            tasks += FetchTask(g, "Google News", GoogleNews.world(if (ar) "ar" else "en", if (ar) "EG" else "US"), "international", "google-news", Scope.GLOBAL)
            if (!ar) tasks += FetchTask(g, "Google News", GoogleNews.top("en", "US"), "general", "google-news", Scope.GLOBAL)
        }
        val byId = categories.associateBy { it.id }
        val catIds = if (only != null) listOf(only) else DefaultCatalog.tabCategoryIds
        for (id in catIds) {
            val c = byId[id] ?: continue
            val kwAr = c.keywordsAr.split(',').map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: c.nameAr ?: continue
            val kwEn = c.keywordsEn.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            tasks += FetchTask(g, "Google News", GoogleNews.search("$kwAr مصر", "ar", "EG", days = 3), id, "google-news", Scope.LOCAL, 30)
            // World coverage: the English edition has the widest international reach; it is translated on device.
            tasks += FetchTask(g, "Google News", GoogleNews.search(kwEn.joinToString(" OR "), "en", "US", days = 2), id, "google-news", Scope.GLOBAL, if (ar) 12 else 30)
            if (ar) tasks += FetchTask(g, "Google News", GoogleNews.search("$kwAr -مصر", "ar", "EG", days = 2), id, "google-news", Scope.GLOBAL, 20)
        }
        return tasks
    }

    private fun ParsedItem.toIncoming(t: FetchTask) = Incoming(
        url = link, title = title, description = description, sourceId = t.sourceId,
        sourceName = sourceName ?: t.publisher, author = author, imageUrl = images.firstOrNull(),
        publishedAt = publishedAt, categoryId = t.categoryId, provider = t.provider, scope = t.scope,
        images = images, videos = videos,
    )

    private fun NewsItemDto.toIncoming(categoryId: String?, scope: Scope, sourceId: String = DefaultCatalog.BACKEND_SOURCE_ID, fallbackName: String? = null): Incoming? {
        if (title.isBlank() || !url.startsWith("http")) return null
        return Incoming(
            url = url, title = RssParser.cleanText(title), description = RssParser.cleanText(description ?: ""),
            sourceId = sourceId, sourceName = source.ifBlank { fallbackName ?: provider }, author = author,
            imageUrl = imageUrl, publishedAt = RssParser.parseDate(publishedAt), categoryId = category ?: categoryId,
            provider = provider.ifBlank { "backend" }, scope = scope, images = listOfNotNull(imageUrl),
        )
    }

    /** Placeholder headlines ("META_TITLE_QUOTE") and spam ("*%[<SPORT TV]]* … LIVE Stream Free"). */
    private fun looksLikeJunk(title: String): Boolean {
        if (title.length < 12) return true
        if (Regex("^[A-Z0-9_ ]+$").matches(title) && title.contains('_')) return true
        if (title.count { it in "[]<>{}*%|" } >= 3) return true
        val t = title.lowercase()
        return Regex("live ?stream|watch (it )?(free|online)|full match|free tv|tv channel|streaming free|reddit stream").containsMatchIn(t) &&
            Regex("\\bvs\\.?\\b|free|tv channel|reddit|online").containsMatchIn(t)
    }

    private data class StoreResult(val inserted: Int, val updated: Int, val newMedia: Int)

    private fun isArabicScript(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        return letters.isNotEmpty() && letters.count { it in '؀'..'ۿ' } > letters.length * 0.4
    }

    /** Inserts new articles, updates changed ones, drops exact duplicates and clusters the rest into stories. */
    private suspend fun store(incoming: List<Incoming>): StoreResult = storeMutex.withLock {
        if (incoming.isEmpty()) return@withLock StoreResult(0, 0, 0)
        val now = System.currentTimeMillis()
        val categoryKeywords = categoryDao.getAll().associate { c ->
            c.id to (c.keywordsEn.split(',') + c.keywordsAr.split(',')).map { it.trim() }.filter { it.isNotEmpty() }
        }
        val generic = setOf("general", "local", "international")
        val byId = LinkedHashMap<String, Pair<ArticleEntity, Incoming>>()
        for (raw in incoming) {
            // Skip placeholder headlines some feeds emit (e.g. "META_TITLE_QUOTE").
            if (looksLikeJunk(raw.title)) continue
            // Evergreen feed entries ("Watch live", "About us") carry years-old dates; they are not news.
            if (raw.publishedAt != null && raw.publishedAt < now - 45 * DAY) continue
            val inc = if (raw.description.startsWith(raw.title.take(40))) raw.copy(description = "") else raw
            val id = TextAnalysis.sha1(TextAnalysis.canonicalUrl(inc.url))
            if (id in byId) continue
            val text = inc.title + ". " + inc.description
            val published = (inc.publishedAt ?: now).coerceAtMost(now + 3_600_000)
            val cat = inc.categoryId?.takeIf { it !in generic }
                ?: CategoryClassifier.classify(text, categoryKeywords).takeIf { it != "general" }
                ?: inc.categoryId ?: "general"
            byId[id] = ArticleEntity(
                id = id, url = inc.url, title = inc.title, titleNormalized = TextAnalysis.normalize(inc.title),
                description = inc.description, sourceId = inc.sourceId, sourceName = inc.sourceName, author = inc.author,
                imageUrl = inc.imageUrl, publishedAt = published, collectedAt = now, updatedAt = now, categoryId = cat,
                language = if (isArabicScript(inc.title)) "ar" else null, storyId = id, provider = inc.provider,
                tokens = TextAnalysis.tokens(text).joinToString(" "),
                entities = TextAnalysis.entities(text).joinToString("|"),
                scope = inc.scope.id,
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
            if (match?.second == StoryClusterer.Relation.DUPLICATE) continue
            val storyId = match?.first?.storyId ?: entity.id
            candidates += cand.copy(storyId = storyId)
            toInsert += entity.copy(storyId = storyId) to inc
        }
        articleDao.insertIgnore(toInsert.map { it.first })

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
        StoreResult(toInsert.size, updated, newMedia)
    }

    private suspend fun updateStory(storyId: String, now: Long) {
        val agg = articleDao.storyAgg(storyId) ?: return
        if (agg.articleCount == 0) return
        val first = agg.firstPublishedAt ?: now
        val last = agg.lastPublishedAt ?: now
        val existing = storyDao.get(storyId)
        val lead = articleDao.get(storyId) ?: articleDao.getByStory(storyId).firstOrNull() ?: return
        val breaking = (agg.sourceCount >= 3 && last - first <= 6 * HOUR && last >= now - 6 * HOUR) ||
            (TextAnalysis.looksBreaking(lead.title) && last >= now - 6 * HOUR)
        val hasLocal = (agg.localCount ?: 0) > 0
        val hasGlobal = (agg.globalCount ?: 0) > 0
        val story = existing?.copy(
            imageUrl = existing.imageUrl ?: agg.imageUrl, firstPublishedAt = first, lastPublishedAt = last, updatedAt = now,
            articleCount = agg.articleCount, sourceCount = agg.sourceCount, isBreaking = existing.isBreaking || breaking,
            hasLocal = hasLocal, hasGlobal = hasGlobal,
        ) ?: StoryEntity(
            id = storyId, headline = lead.title, imageUrl = agg.imageUrl, categoryId = lead.categoryId,
            firstPublishedAt = first, lastPublishedAt = last, updatedAt = now, articleCount = agg.articleCount,
            sourceCount = agg.sourceCount, leadSourceName = lead.sourceName, isBreaking = breaking,
            hasLocal = hasLocal, hasGlobal = hasGlobal,
            headlineTr = lead.titleTr, trLang = lead.trLang,
        )
        storyDao.upsert(story)
    }

    private suspend fun maybeNotify(s: AppSettings, firstSync: Boolean) {
        val now = System.currentTimeMillis()
        val candidates = if (s.notifyBreaking) storyDao.breakingNotNotified(now - 6 * HOUR) else emptyList()
        if (!s.notificationsEnabled || firstSync) {
            candidates.forEach { storyDao.markNotified(it.id, now) }
            return
        }
        val allowed = notifier.remainingToday(s.maxNotificationsPerDay)
        candidates.sortedByDescending { it.sourceCount }.take(allowed).forEach {
            notifier.showStory(it.toDomain(), s.language)
            storyDao.markNotified(it.id, now)
        }
    }

    // ------------------------------------------------------------------ translation

    override suspend fun translatePending(limit: Int): Int = translateMutex.withLock {
        withContext(Dispatchers.IO) {
            val s = settings.snapshot()
            if (!s.translateNews) return@withContext 0
            val target = s.language
            if (!translator.supported(target)) return@withContext 0
            var done = 0
            val t0 = System.currentTimeMillis()
            val batch = articleDao.leadsNeedingTranslation(target, limit).ifEmpty { articleDao.needingTranslation(target, limit) }
            for (a in batch) {
                if (translateArticle(a, target)) done++ else break // model missing / offline: retry later
            }
            android.util.Log.i("SmartNewsTr", "translated $done to $target in ${System.currentTimeMillis() - t0} ms")
            done
        }
    }

    /** Returns false when translation is currently impossible (no model and no network). */
    private suspend fun translateArticle(a: ArticleEntity, target: String): Boolean {
        val src = a.language ?: translator.detect(a.title + ". " + a.description.take(200))
        if (src == null || src == target || !translator.supported(src)) {
            articleDao.setTranslation(a.id, null, null, target, src) // nothing to translate
            return true
        }
        return try {
            val title = translator.translate(a.title, src, target)
            val desc = a.description.takeIf { it.isNotBlank() && !it.startsWith(a.title) }?.let { translator.translate(it.take(500), src, target) }
            articleDao.setTranslation(a.id, title, desc, target, src)
            storyDao.setHeadlineTr(a.id, title, target) // no-op unless this article leads a story
            true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun translateStory(storyId: String) {
        withContext(Dispatchers.IO) {
            val target = settings.snapshot().language
            if (!translator.supported(target)) return@withContext
            articleDao.getByStory(storyId)
                .filter { it.trLang != target && it.language != target }
                .forEach { if (!translateArticle(it, target)) return@withContext }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observePendingTranslation(): Flow<Int> =
        settings.settings.map { it.language to it.translateNews }.distinctUntilChanged().flatMapLatest { (lang, on) ->
            if (!on) kotlinx.coroutines.flow.flowOf(0)
            else articleDao.observePendingTranslation(lang, System.currentTimeMillis() - 3 * DAY)
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

        articles.filter { !it.url.contains("news.google.com") }.distinctBy { it.sourceName }.take(3).forEach { a ->
            runCatching { scraper.extras(a.url) }.onSuccess { ex ->
                ex.leadImage?.let { articleDao.setImageIfMissing(a.id, it) }
                (listOfNotNull(ex.leadImage) + ex.images).distinct().take(6).forEach { img ->
                    media += mediaEntity(storyId, a.id, MediaType.IMAGE, a.title, img, img, a.sourceName, a.publishedAt, null, Playback.NONE, 0.95, now)
                }
                ex.youtubeIds.take(3).forEach { id ->
                    media += mediaEntity(storyId, a.id, MediaType.VIDEO, a.title, "https://www.youtube.com/watch?v=$id",
                        "https://i.ytimg.com/vi/$id/hqdefault.jpg", a.sourceName, a.publishedAt, null, Playback.YOUTUBE_EMBED, 1.0, now)
                }
                ex.videoFiles.take(2).forEach { f ->
                    media += mediaEntity(storyId, a.id, MediaType.VIDEO, a.title, f, a.imageUrl ?: ex.leadImage, a.sourceName, a.publishedAt, null, Playback.EXOPLAYER, 1.0, now)
                }
            }.onFailure { errors += "${a.sourceName}: ${it.message}" }
        }

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
        if (media.isEmpty() && errors.isNotEmpty()) Result.failure(IllegalStateException(errors.joinToString("\n")))
        else Result.success(added)
    }

    private fun VideoDto.toMedia(storyId: String, score: Double, now: Long): MediaEntity {
        val yt = videoId ?: youtubeId(url)
        val playback = if (yt != null && embeddable) Playback.YOUTUBE_EMBED else Playback.EXTERNAL
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
        attribution = publisher, relevance = relevance, collectedAt = now, isInterview = VideoMatcher.isInterview(title),
    )

    private fun youtubeId(url: String): String? =
        Regex("(?:youtube\\.com/(?:watch\\?v=|embed/|shorts/|live/)|youtu\\.be/)([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1)

    override suspend fun fetchLive(query: String?): Result<List<MediaItem>> = withContext(Dispatchers.IO) {
        if (!backend.isConfigured) return@withContext Result.failure(IllegalStateException("backend"))
        runCatching {
            val now = System.currentTimeMillis()
            mediaDao.expireLive(now - 30 * 60_000)
            val items = api.live(query, settings.snapshot().language).items.filter { it.isLive }.map { it.toMedia(LIVE_STORY, 1.0, now) }
            mediaDao.insertIgnore(items)
            items.map { it.toDomain() }
        }
    }

    // ------------------------------------------------------------------ observe

    override fun observeHome(categoryId: String?, scope: Scope, limit: Int): Flow<List<Story>> =
        storyDao.observeScoped(categoryId, scope == Scope.LOCAL, limit).map { l -> l.map { it.toDomain() } }

    override fun observeArticles(scope: Scope?, sourceId: String?, filter: DateFilter, limit: Int): Flow<List<Article>> {
        val r = filter.range()
        return articleDao.observeFeed(null, sourceId, scope?.id, r.first, r.last, limit).map { l -> l.map { it.toDomain() } }
    }

    override fun observeArticleCount() = articleDao.observeCount()
    override fun observeStory(id: String) = storyDao.observe(id).map { it?.toDomain() }
    override fun observeStoryArticles(storyId: String) = articleDao.observeByStory(storyId).map { l -> l.map { it.toDomain() } }
    override fun observeStoryMedia(storyId: String) = mediaDao.observeByStory(storyId).map { l -> l.map { it.toDomain() } }
    override fun observeMedia(type: MediaType, limit: Int) = mediaDao.observeByType(type.name, limit).map { l -> l.map { it.toDomain() } }

    // ------------------------------------------------------------------ search

    override suspend fun search(query: String, categoryId: String?, filter: DateFilter, type: ContentType, includeOnline: Boolean): SearchResults =
        withContext(Dispatchers.IO) {
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
                val arabicQuery = isArabicScript(query)
                // Search the edition that matches the query's script, plus the other one for wider coverage.
                val editions = if (arabicQuery) listOf(Triple("ar", "EG", Scope.LOCAL)) else listOf(Triple("en", "US", Scope.GLOBAL), Triple("ar", "EG", Scope.LOCAL))
                for ((lang, country, scope) in editions) {
                    runCatching {
                        val url = GoogleNews.search(query, lang, country, days)
                        val t = FetchTask(DefaultCatalog.GOOGLE_NEWS_SOURCE_ID, "Google News", url, categoryId, "google-news", scope)
                        incoming += fetcher.getFeed(url).items.take(40).map { it.toIncoming(t) }
                    }.onFailure { errs += "Google News: ${it.message}" }
                }
                if (backend.isConfigured) {
                    runCatching {
                        incoming += api.news(categoryId, query, null, null, "en", "US").items.mapNotNull { it.toIncoming(categoryId, Scope.GLOBAL) }
                    }.onFailure { errs += "Backend: ${it.message}" }
                    if (type == ContentType.ALL || type == ContentType.VIDEOS) runCatching {
                        val resp = api.videos(VideoRequestDto(query, "", terms, Instant.now().toString(), s.language, 10))
                        val now = System.currentTimeMillis()
                        mediaDao.insertIgnore(resp.items.map { it.toMedia(SEARCH_STORY, it.relevance, now) })
                    }
                }
                store(incoming)
                if (errs.isNotEmpty() && incoming.isEmpty()) onlineError = errs.joinToString("; ")
            }

            val r = filter.range()
            val primary = terms.maxBy { it.length }
            val articles = if (type == ContentType.ALL || type == ContentType.ARTICLES) {
                articleDao.search(primary, categoryId, null, r.first, r.last, savedOnly = false, limit = 400)
                    .filter { a ->
                        val hay = listOf(a.title, a.description, a.entities, a.sourceName, a.titleTr.orEmpty(), a.descriptionTr.orEmpty()).joinToString(" ").lowercase()
                        terms.all { it in hay }
                    }
                    .map { it.toDomain() }
            } else emptyList()
            val media = when (type) {
                ContentType.ALL, ContentType.VIDEOS -> mediaDao.search(primary, MediaType.VIDEO.name, r.first, r.last, 100)
                ContentType.IMAGES -> mediaDao.search(primary, MediaType.IMAGE.name, r.first, r.last, 100)
                ContentType.ARTICLES -> emptyList()
            }.filter { m -> terms.all { m.title.lowercase().contains(it) || m.publisher.lowercase().contains(it) } }
                .distinctBy { it.url }.map { it.toDomain() }
            SearchResults(articles.sortedByDescending { it.publishedAt }.take(200), media, onlineError)
        }

    // ------------------------------------------------------------------ saved & history

    override suspend fun setArticleSaved(id: String, saved: Boolean) = articleDao.setSaved(id, saved, if (saved) System.currentTimeMillis() else null)
    override suspend fun setStorySaved(id: String, saved: Boolean) = storyDao.setSaved(id, saved)
    override suspend fun setMediaSaved(id: String, saved: Boolean) = mediaDao.setSaved(id, saved, if (saved) System.currentTimeMillis() else null)
    override fun observeSavedArticles() = articleDao.observeSaved().map { l -> l.map { it.toDomain() } }
    override fun observeSavedMedia() = mediaDao.observeSaved().map { l -> l.map { it.toDomain() } }

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
