package com.smartnews.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class SourceCount(val sourceId: String, val c: Int)

data class HistoryWithArticle(
    @Embedded val article: ArticleEntity,
    val viewedAt: Long,
)

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY isTopic, sortOrder, name")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY isTopic, sortOrder, name")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun get(id: String): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(items: List<CategoryEntity>)

    @Upsert
    suspend fun upsert(item: CategoryEntity)

    @Query("UPDATE categories SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM categories WHERE id = :id AND isCustom = 1")
    suspend fun deleteCustom(id: String)
}

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY isCustom DESC, name")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE enabled = 1")
    suspend fun getEnabled(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun get(id: String): SourceEntity?

    @Query("SELECT * FROM sources WHERE id = :id")
    fun observe(id: String): Flow<SourceEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(items: List<SourceEntity>)

    @Upsert
    suspend fun upsert(item: SourceEntity)

    @Update
    suspend fun update(item: SourceEntity)

    @Query("UPDATE sources SET lastSyncAt = :at, lastSuccessAt = CASE WHEN :error IS NULL THEN :at ELSE lastSuccessAt END, lastError = :error WHERE id = :id")
    suspend fun markSync(id: String, at: Long, error: String?)

    @Query("UPDATE sources SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    /** Removing a source keeps the articles it already delivered (they are history). */
    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT sourceId, COUNT(*) AS c FROM articles GROUP BY sourceId")
    fun observeCounts(): Flow<List<SourceCount>>
}

@Dao
interface ArticleDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(items: List<ArticleEntity>): List<Long>

    /** Refresh path: updates publisher content but never touches saved/read/summary state. */
    @Query(
        """UPDATE articles SET title = :title, titleNormalized = :titleNormalized, description = :description,
           imageUrl = COALESCE(:imageUrl, imageUrl), updatedAt = :now
           WHERE id = :id AND (title != :title OR description != :description OR (imageUrl IS NULL AND :imageUrl IS NOT NULL))"""
    )
    suspend fun updateContent(id: String, title: String, titleNormalized: String, description: String, imageUrl: String?, now: Long): Int

    @Query("SELECT id FROM articles WHERE id IN (:ids)")
    suspend fun existingIds(ids: List<String>): List<String>

    @Query("SELECT * FROM articles WHERE id = :id")
    suspend fun get(id: String): ArticleEntity?

    @Query("SELECT * FROM articles WHERE id = :id")
    fun observe(id: String): Flow<ArticleEntity?>

    @Query("SELECT * FROM articles WHERE publishedAt >= :since ORDER BY publishedAt DESC LIMIT 3000")
    suspend fun recentForClustering(since: Long): List<ArticleEntity>

    @Query(
        """SELECT * FROM articles WHERE (:categoryId IS NULL OR categoryId = :categoryId)
           AND (:sourceId IS NULL OR sourceId = :sourceId)
           AND (:scope IS NULL OR scope = :scope)
           AND publishedAt BETWEEN :from AND :to
           ORDER BY publishedAt DESC LIMIT :limit"""
    )
    fun observeFeed(categoryId: String?, sourceId: String?, scope: String?, from: Long, to: Long, limit: Int): Flow<List<ArticleEntity>>

    /** Newest articles whose text is not yet available in [target]. */
    @Query(
        """SELECT * FROM articles WHERE (trLang IS NULL OR trLang != :target)
           AND (language IS NULL OR language != :target)
           ORDER BY publishedAt DESC LIMIT :limit"""
    )
    suspend fun needingTranslation(target: String, limit: Int): List<ArticleEntity>

    /** Story headlines first (what the user sees on Home), newest stories first. */
    @Query(
        """SELECT a.* FROM articles a JOIN stories s ON s.id = a.id
           WHERE (a.trLang IS NULL OR a.trLang != :target) AND (a.language IS NULL OR a.language != :target)
           ORDER BY s.lastPublishedAt DESC LIMIT :limit"""
    )
    suspend fun leadsNeedingTranslation(target: String, limit: Int): List<ArticleEntity>

    @Query(
        """SELECT COUNT(*) FROM articles WHERE (trLang IS NULL OR trLang != :target)
           AND (language IS NULL OR language != :target) AND publishedAt >= :since"""
    )
    fun observePendingTranslation(target: String, since: Long): Flow<Int>

    @Query("UPDATE articles SET titleTr = :title, descriptionTr = :description, trLang = :target, language = COALESCE(:detected, language) WHERE id = :id")
    suspend fun setTranslation(id: String, title: String?, description: String?, target: String, detected: String?)

    @Query("SELECT * FROM articles WHERE storyId = :storyId ORDER BY publishedAt ASC")
    fun observeByStory(storyId: String): Flow<List<ArticleEntity>>

    @Query("SELECT * FROM articles WHERE storyId = :storyId ORDER BY publishedAt ASC")
    suspend fun getByStory(storyId: String): List<ArticleEntity>

    @Query(
        """SELECT * FROM articles WHERE
           (title LIKE '%' || :q || '%' OR description LIKE '%' || :q || '%' OR sourceName LIKE '%' || :q || '%' OR entities LIKE '%' || :q || '%')
           AND (:categoryId IS NULL OR categoryId = :categoryId)
           AND (:sourceId IS NULL OR sourceId = :sourceId)
           AND publishedAt BETWEEN :from AND :to
           AND (:savedOnly = 0 OR isSaved = 1)
           ORDER BY publishedAt DESC LIMIT :limit"""
    )
    suspend fun search(q: String, categoryId: String?, sourceId: String?, from: Long, to: Long, savedOnly: Boolean, limit: Int): List<ArticleEntity>

    @Query("SELECT * FROM articles WHERE isSaved = 1 ORDER BY savedAt DESC")
    fun observeSaved(): Flow<List<ArticleEntity>>

    @Query("UPDATE articles SET isSaved = :saved, savedAt = :at WHERE id = :id")
    suspend fun setSaved(id: String, saved: Boolean, at: Long?)

    @Query("UPDATE articles SET isRead = 1 WHERE id = :id")
    suspend fun markRead(id: String)

    @Query("UPDATE articles SET summary = :summary, keyPoints = :keyPoints, summaryIsAi = :isAi WHERE id = :id")
    suspend fun setSummary(id: String, summary: String, keyPoints: String, isAi: Boolean)

    @Query("UPDATE articles SET imageUrl = :imageUrl WHERE id = :id AND imageUrl IS NULL")
    suspend fun setImageIfMissing(id: String, imageUrl: String)

    @Query("SELECT entities FROM articles WHERE publishedAt >= :since")
    suspend fun entitiesSince(since: Long): List<String>

    @Query("SELECT entities FROM articles WHERE publishedAt >= :since")
    fun observeEntitiesSince(since: Long): Flow<List<String>>

    @Query(
        """SELECT storyId AS id, COUNT(*) AS articleCount, COUNT(DISTINCT sourceName) AS sourceCount,
           MIN(publishedAt) AS firstPublishedAt, MAX(publishedAt) AS lastPublishedAt,
           SUM(CASE WHEN scope = 'local' THEN 1 ELSE 0 END) AS localCount,
           SUM(CASE WHEN scope = 'global' THEN 1 ELSE 0 END) AS globalCount,
           (SELECT imageUrl FROM articles a2 WHERE a2.storyId = :storyId AND a2.imageUrl IS NOT NULL ORDER BY a2.publishedAt LIMIT 1) AS imageUrl
           FROM articles WHERE storyId = :storyId"""
    )
    suspend fun storyAgg(storyId: String): StoryAggRow?

    @Query("SELECT COUNT(*) FROM articles")
    fun observeCount(): Flow<Int>

    /** Only called from the explicit "Clean up old news" action in Settings — never from refresh. */
    @Query("DELETE FROM articles WHERE isSaved = 0 AND publishedAt < :before AND id NOT IN (SELECT articleId FROM history)")
    suspend fun deleteUnsavedOlderThan(before: Long): Int
}

data class StoryAggRow(
    val id: String?,
    val articleCount: Int,
    val sourceCount: Int,
    val firstPublishedAt: Long?,
    val lastPublishedAt: Long?,
    val localCount: Int?,
    val globalCount: Int?,
    val imageUrl: String?,
)

@Dao
interface StoryDao {
    @Upsert
    suspend fun upsert(story: StoryEntity)

    @Query("SELECT * FROM stories WHERE id = :id")
    suspend fun get(id: String): StoryEntity?

    @Query("SELECT * FROM stories WHERE id = :id")
    fun observe(id: String): Flow<StoryEntity?>

    @Query(
        """SELECT * FROM stories WHERE (:categoryId IS NULL OR categoryId = :categoryId)
           AND lastPublishedAt BETWEEN :from AND :to
           ORDER BY CASE WHEN :byRelevance = 1 THEN sourceCount * 3 + articleCount ELSE 0 END DESC, lastPublishedAt DESC
           LIMIT :limit"""
    )
    fun observeFeed(categoryId: String?, from: Long, to: Long, byRelevance: Boolean, limit: Int): Flow<List<StoryEntity>>

    @Query(
        """SELECT * FROM stories WHERE categoryId IN (:categoryIds)
           AND lastPublishedAt BETWEEN :from AND :to
           ORDER BY CASE WHEN :byRelevance = 1 THEN sourceCount * 3 + articleCount ELSE 0 END DESC, lastPublishedAt DESC
           LIMIT :limit"""
    )
    fun observeForCategories(categoryIds: List<String>, from: Long, to: Long, byRelevance: Boolean, limit: Int): Flow<List<StoryEntity>>

    /** Home feed: one category (or all) for Egypt (local) or the world (global), newest first. */
    @Query(
        """SELECT * FROM stories WHERE (:categoryId IS NULL OR categoryId = :categoryId)
           AND ((:local = 1 AND hasLocal = 1) OR (:local = 0 AND hasGlobal = 1))
           ORDER BY lastPublishedAt DESC LIMIT :limit"""
    )
    fun observeScoped(categoryId: String?, local: Boolean, limit: Int): Flow<List<StoryEntity>>

    @Query("UPDATE stories SET headlineTr = :headline, trLang = :target WHERE id = :id")
    suspend fun setHeadlineTr(id: String, headline: String?, target: String)

    @Query("SELECT * FROM stories WHERE lastPublishedAt >= :since ORDER BY sourceCount DESC, articleCount DESC, lastPublishedAt DESC LIMIT :limit")
    fun observeTop(since: Long, limit: Int): Flow<List<StoryEntity>>

    @Query("SELECT * FROM stories WHERE isBreaking = 1 AND lastPublishedAt >= :since ORDER BY lastPublishedAt DESC LIMIT 10")
    fun observeBreaking(since: Long): Flow<List<StoryEntity>>

    @Query("SELECT * FROM stories ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecentlyUpdated(limit: Int): Flow<List<StoryEntity>>

    @Query("SELECT * FROM stories WHERE lastPublishedAt >= :since AND (mediaFetchedAt IS NULL OR mediaFetchedAt < updatedAt - 3600000) ORDER BY sourceCount DESC, lastPublishedAt DESC LIMIT :limit")
    suspend fun needingMedia(since: Long, limit: Int): List<StoryEntity>

    @Query("SELECT * FROM stories WHERE isBreaking = 1 AND notifiedAt IS NULL AND lastPublishedAt >= :since ORDER BY sourceCount DESC LIMIT 5")
    suspend fun breakingNotNotified(since: Long): List<StoryEntity>

    @Query("SELECT * FROM stories WHERE notifiedAt IS NULL AND categoryId IN (:categoryIds) AND lastPublishedAt >= :since ORDER BY sourceCount DESC LIMIT 3")
    suspend fun unnotifiedInCategories(categoryIds: List<String>, since: Long): List<StoryEntity>

    @Query("UPDATE stories SET notifiedAt = :at WHERE id = :id")
    suspend fun markNotified(id: String, at: Long)

    @Query("UPDATE stories SET mediaFetchedAt = :at WHERE id = :id")
    suspend fun markMediaFetched(id: String, at: Long)

    @Query("UPDATE stories SET aiSummary = :summary, aiKeyPoints = :points WHERE id = :id")
    suspend fun setAi(id: String, summary: String, points: String)

    @Query("UPDATE stories SET aiExplanationJson = :json WHERE id = :id")
    suspend fun setExplanation(id: String, json: String)

    @Query("UPDATE stories SET isSaved = :saved WHERE id = :id")
    suspend fun setSaved(id: String, saved: Boolean)

    @Query("SELECT * FROM stories WHERE isSaved = 1 ORDER BY lastPublishedAt DESC")
    fun observeSaved(): Flow<List<StoryEntity>>

    @Query("DELETE FROM stories WHERE id NOT IN (SELECT DISTINCT storyId FROM articles)")
    suspend fun deleteOrphans(): Int
}

@Dao
interface MediaDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(items: List<MediaEntity>): List<Long>

    @Query("SELECT * FROM media WHERE storyId = :storyId ORDER BY isLive DESC, relevance DESC")
    fun observeByStory(storyId: String): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media WHERE type = :type ORDER BY COALESCE(publishedAt, collectedAt) DESC LIMIT :limit")
    fun observeByType(type: String, limit: Int): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media WHERE isSaved = 1 ORDER BY savedAt DESC")
    fun observeSaved(): Flow<List<MediaEntity>>

    @Query("UPDATE media SET isSaved = :saved, savedAt = :at WHERE id = :id")
    suspend fun setSaved(id: String, saved: Boolean, at: Long?)

    @Query(
        """SELECT * FROM media WHERE (title LIKE '%' || :q || '%' OR publisher LIKE '%' || :q || '%')
           AND (:type IS NULL OR type = :type)
           AND COALESCE(publishedAt, collectedAt) BETWEEN :from AND :to
           ORDER BY COALESCE(publishedAt, collectedAt) DESC LIMIT :limit"""
    )
    suspend fun search(q: String, type: String?, from: Long, to: Long, limit: Int): List<MediaEntity>

    /** Live flags go stale; a broadcast that ended is downgraded, never deleted. */
    @Query("UPDATE media SET isLive = 0, type = 'VIDEO' WHERE isLive = 1 AND collectedAt < :before")
    suspend fun expireLive(before: Long)

    @Query("DELETE FROM media WHERE isSaved = 0 AND storyId NOT IN (SELECT id FROM stories) AND storyId != 'live'")
    suspend fun deleteOrphans(): Int
}

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(entry: HistoryEntity)

    @Query(
        """SELECT articles.*, h.viewedAt AS viewedAt FROM history h
           JOIN articles ON articles.id = h.articleId
           WHERE h.id IN (SELECT MAX(id) FROM history GROUP BY articleId)
           ORDER BY h.viewedAt DESC LIMIT :limit"""
    )
    fun observe(limit: Int): Flow<List<HistoryWithArticle>>

    @Query("DELETE FROM history")
    suspend fun clear()
}
