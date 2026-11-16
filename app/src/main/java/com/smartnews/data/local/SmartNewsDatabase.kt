package com.smartnews.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.smartnews.domain.model.Article
import com.smartnews.domain.model.Category
import com.smartnews.domain.model.MediaItem
import com.smartnews.domain.model.MediaType
import com.smartnews.domain.model.NewsSource
import com.smartnews.domain.model.Playback
import com.smartnews.domain.model.SourceType
import com.smartnews.domain.model.Story

@Database(
    entities = [
        CategoryEntity::class, SourceEntity::class, ArticleEntity::class,
        StoryEntity::class, MediaEntity::class, HistoryEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class SmartNewsDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun sourceDao(): SourceDao
    abstract fun articleDao(): ArticleDao
    abstract fun storyDao(): StoryDao
    abstract fun mediaDao(): MediaDao
    abstract fun historyDao(): HistoryDao
}

// ---- Entity <-> domain mapping ----

fun CategoryEntity.toDomain(language: String) = Category(
    id = id, name = name, nameAr = nameAr,
    keywords = (if (language == "ar" && keywordsAr.isNotBlank()) keywordsAr else keywordsEn)
        .split(',').map { it.trim() }.filter { it.isNotEmpty() },
    enabled = enabled, isCustom = isCustom, isTopic = isTopic, sortOrder = sortOrder,
)

fun SourceEntity.toDomain(count: Int = 0) = NewsSource(
    id = id, name = name, siteUrl = siteUrl, feedUrl = feedUrl,
    type = runCatching { SourceType.valueOf(type) }.getOrDefault(SourceType.RSS),
    categoryId = categoryId, enabled = enabled, isCustom = isCustom,
    lastSyncAt = lastSyncAt, lastSuccessAt = lastSuccessAt, lastError = lastError, articleCount = count,
)

fun ArticleEntity.toDomain() = Article(
    id = id, url = url, title = title, description = description, sourceId = sourceId,
    sourceName = sourceName, author = author, imageUrl = imageUrl, publishedAt = publishedAt,
    collectedAt = collectedAt, categoryId = categoryId, storyId = storyId, provider = provider,
    isSaved = isSaved, isRead = isRead, summary = summary,
    keyPoints = keyPoints.split('\n').filter { it.isNotBlank() }, summaryIsAi = summaryIsAi,
    entities = entities.split('|').filter { it.isNotBlank() },
)

fun StoryEntity.toDomain() = Story(
    id = id, headline = headline, imageUrl = imageUrl, categoryId = categoryId,
    firstPublishedAt = firstPublishedAt, lastPublishedAt = lastPublishedAt, updatedAt = updatedAt,
    articleCount = articleCount, sourceCount = sourceCount, isBreaking = isBreaking, isSaved = isSaved,
    aiSummary = aiSummary, aiKeyPoints = aiKeyPoints.split('\n').filter { it.isNotBlank() },
    leadSourceName = leadSourceName,
)

fun MediaEntity.toDomain() = MediaItem(
    id = id, storyId = storyId, articleId = articleId,
    type = runCatching { MediaType.valueOf(type) }.getOrDefault(MediaType.VIDEO),
    title = title, url = url, thumbnailUrl = thumbnailUrl, publisher = publisher,
    publishedAt = publishedAt, durationSec = durationSec, isLive = isLive,
    playback = runCatching { Playback.valueOf(playback) }.getOrDefault(Playback.EXTERNAL),
    attribution = attribution, relevance = relevance, isSaved = isSaved, isInterview = isInterview,
)
