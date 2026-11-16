package com.smartnews.data.repository

import com.smartnews.data.local.ArticleDao
import com.smartnews.data.local.StoryDao
import com.smartnews.data.remote.AnalyzeRequestDto
import com.smartnews.data.remote.ArticlePageScraper
import com.smartnews.data.remote.BackendApi
import com.smartnews.data.remote.BackendConfig
import com.smartnews.data.remote.ExplainArticleDto
import com.smartnews.data.remote.ExplainRequestDto
import com.smartnews.data.remote.ExplainResponseDto
import com.smartnews.domain.logic.TextAnalysis
import com.smartnews.domain.model.Explanation
import com.smartnews.domain.repository.AiRepository
import com.smartnews.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiRepositoryImpl @Inject constructor(
    private val articleDao: ArticleDao,
    private val storyDao: StoryDao,
    private val api: BackendApi,
    private val backend: BackendConfig,
    private val scraper: ArticlePageScraper,
    private val settings: SettingsRepository,
    private val json: Json,
) : AiRepository {

    override suspend fun summarize(articleId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val a = articleDao.get(articleId) ?: error("Article not found")
            if (a.summary != null && (a.summaryIsAi || !backend.isConfigured)) return@runCatching
            // Use the publisher's page text when we can reach it; otherwise the feed description.
            val pageText = if (a.url.contains("news.google.com")) "" else runCatching { scraper.extras(a.url).text }.getOrDefault("")
            val text = pageText.ifBlank { a.description }
            if (backend.isConfigured) {
                val r = runCatching {
                    api.analyze(AnalyzeRequestDto(a.title, text.take(6000), a.sourceName, settings.snapshot().language))
                }.getOrNull()
                if (r != null && r.summary.isNotBlank()) {
                    articleDao.setSummary(a.id, r.summary, r.keyPoints.joinToString("\n"), isAi = true)
                    return@runCatching
                }
            }
            val (summary, points) = TextAnalysis.extractiveSummary(a.title, text)
            articleDao.setSummary(a.id, summary.ifBlank { a.description }, points.joinToString("\n"), isAi = false)
        }
    }

    override suspend fun summarizeStory(storyId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val story = storyDao.get(storyId) ?: error("Story not found")
            if (story.aiSummary != null) return@runCatching
            if (!backend.isConfigured) return@runCatching
            val articles = articleDao.getByStory(storyId)
            val text = articles.joinToString("\n\n") { "[${it.sourceName}] ${it.title}. ${it.description}" }.take(8000)
            val r = api.analyze(AnalyzeRequestDto(story.headline, text, "multiple publishers", settings.snapshot().language))
            if (r.summary.isNotBlank()) storyDao.setAi(storyId, r.summary, r.keyPoints.joinToString("\n"))
        }
    }

    override suspend fun explain(storyId: String, force: Boolean): Result<Explanation> = withContext(Dispatchers.IO) {
        runCatching {
            val story = storyDao.get(storyId) ?: error("Story not found")
            if (!force) story.aiExplanationJson?.let { cached ->
                runCatching { json.decodeFromString<ExplainResponseDto>(cached) }.getOrNull()?.let { return@runCatching it.toDomain() }
            }
            check(backend.isConfigured) {
                "\"Explain This News\" uses an AI model on your SmartNews backend. Add the backend URL in Settings → Backend."
            }
            val articles = articleDao.getByStory(storyId)
            val resp = api.explain(
                ExplainRequestDto(
                    headline = story.headline,
                    articles = articles.take(8).map {
                        ExplainArticleDto(it.sourceName, it.title, it.description.take(1200), Instant.ofEpochMilli(it.publishedAt).toString(), it.url)
                    },
                    lang = settings.snapshot().language,
                )
            )
            storyDao.setExplanation(storyId, json.encodeToString(resp))
            resp.toDomain()
        }
    }

    private fun ExplainResponseDto.toDomain() = Explanation(explanation, background, whyItMatters, differences, unconfirmed, model)
}
