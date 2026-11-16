package com.smartnews.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 2,
    exportSchema = false,
)
abstract class SmartNewsDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun sourceDao(): SourceDao
    abstract fun articleDao(): ArticleDao
    abstract fun storyDao(): StoryDao
    abstract fun mediaDao(): MediaDao
    abstract fun historyDao(): HistoryDao

    companion object {
        /** v2: local/global scope and machine translations. Keeps every stored article. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE articles ADD COLUMN scope TEXT NOT NULL DEFAULT 'global'")
                db.execSQL("ALTER TABLE articles ADD COLUMN titleTr TEXT")
                db.execSQL("ALTER TABLE articles ADD COLUMN descriptionTr TEXT")
                db.execSQL("ALTER TABLE articles ADD COLUMN trLang TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_articles_scope ON articles(scope)")
                db.execSQL("ALTER TABLE stories ADD COLUMN hasLocal INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE stories ADD COLUMN hasGlobal INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE stories ADD COLUMN headlineTr TEXT")
                db.execSQL("ALTER TABLE stories ADD COLUMN trLang TEXT")
            }
        }
    }
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
    language = language, scope = scope, titleTr = titleTr, descriptionTr = descriptionTr, trLang = trLang,
)

fun StoryEntity.toDomain() = Story(
    id = id, headline = headline, imageUrl = imageUrl, categoryId = categoryId,
    firstPublishedAt = firstPublishedAt, lastPublishedAt = lastPublishedAt, updatedAt = updatedAt,
    articleCount = articleCount, sourceCount = sourceCount, isBreaking = isBreaking, isSaved = isSaved,
    aiSummary = aiSummary, aiKeyPoints = aiKeyPoints.split('\n').filter { it.isNotBlank() },
    leadSourceName = leadSourceName, headlineTr = headlineTr, trLang = trLang,
)

fun MediaEntity.toDomain() = MediaItem(
    id = id, storyId = storyId, articleId = articleId,
    type = runCatching { MediaType.valueOf(type) }.getOrDefault(MediaType.VIDEO),
    title = title, url = url, thumbnailUrl = thumbnailUrl, publisher = publisher,
    publishedAt = publishedAt, durationSec = durationSec, isLive = isLive,
    playback = runCatching { Playback.valueOf(playback) }.getOrDefault(Playback.EXTERNAL),
    attribution = attribution, relevance = relevance, isSaved = isSaved, isInterview = isInterview,
)
