package com.smartnews.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val nameAr: String?,
    /** Comma-separated search keywords (English). */
    val keywordsEn: String,
    /** Comma-separated search keywords (Arabic). */
    val keywordsAr: String,
    val enabled: Boolean,
    val isCustom: Boolean,
    val isTopic: Boolean,
    val sortOrder: Int,
)

@Entity(tableName = "sources", indices = [Index("enabled")])
data class SourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val siteUrl: String,
    val feedUrl: String?,
    val type: String,
    val categoryId: String?,
    val language: String?,
    val enabled: Boolean,
    val isCustom: Boolean,
    val createdAt: Long,
    val lastSyncAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val lastError: String? = null,
)

@Entity(
    tableName = "articles",
    indices = [Index("publishedAt"), Index("storyId"), Index("categoryId"), Index("sourceId"), Index("isSaved"), Index("scope")],
)
data class ArticleEntity(
    /** sha1(canonical url) – stable across refreshes, so re-fetching never duplicates. */
    @PrimaryKey val id: String,
    val url: String,
    val title: String,
    val titleNormalized: String,
    val description: String,
    val sourceId: String,
    val sourceName: String,
    val author: String?,
    val imageUrl: String?,
    val publishedAt: Long,
    val collectedAt: Long,
    val updatedAt: Long,
    val categoryId: String,
    val language: String?,
    val storyId: String,
    val provider: String,
    /** Space-separated tokens for clustering. */
    val tokens: String,
    /** "|"-separated entities. */
    val entities: String,
    val isSaved: Boolean = false,
    val savedAt: Long? = null,
    val isRead: Boolean = false,
    val summary: String? = null,
    /** "\n"-separated key points. */
    val keyPoints: String = "",
    val summaryIsAi: Boolean = false,
    /** "local" (Egypt) or "global". */
    val scope: String = "global",
    /** Machine translation of title/description into [trLang]. */
    val titleTr: String? = null,
    val descriptionTr: String? = null,
    val trLang: String? = null,
)

@Entity(tableName = "stories", indices = [Index("lastPublishedAt"), Index("categoryId")])
data class StoryEntity(
    @PrimaryKey val id: String,
    val headline: String,
    val imageUrl: String?,
    val categoryId: String,
    val firstPublishedAt: Long,
    val lastPublishedAt: Long,
    val updatedAt: Long,
    val articleCount: Int,
    val sourceCount: Int,
    val leadSourceName: String,
    val isBreaking: Boolean = false,
    val isSaved: Boolean = false,
    val aiSummary: String? = null,
    val aiKeyPoints: String = "",
    val aiExplanationJson: String? = null,
    val mediaFetchedAt: Long? = null,
    val notifiedAt: Long? = null,
    val hasLocal: Boolean = false,
    val hasGlobal: Boolean = true,
    val headlineTr: String? = null,
    val trLang: String? = null,
)

@Entity(tableName = "media", indices = [Index("storyId"), Index("type"), Index("isSaved")])
data class MediaEntity(
    @PrimaryKey val id: String,
    val storyId: String,
    val articleId: String?,
    val type: String,
    val title: String,
    val url: String,
    val thumbnailUrl: String?,
    val publisher: String,
    val publishedAt: Long?,
    val durationSec: Int?,
    val isLive: Boolean,
    val playback: String,
    val attribution: String,
    val relevance: Double,
    val collectedAt: Long,
    val isInterview: Boolean = false,
    val isSaved: Boolean = false,
    val savedAt: Long? = null,
)

@Entity(tableName = "history", indices = [Index("articleId"), Index("viewedAt")])
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val articleId: String,
    val storyId: String,
    val viewedAt: Long,
)
