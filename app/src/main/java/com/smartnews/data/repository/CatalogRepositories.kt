package com.smartnews.data.repository

import com.smartnews.data.local.CategoryDao
import com.smartnews.data.local.CategoryEntity
import com.smartnews.data.local.SourceDao
import com.smartnews.data.local.SourceEntity
import com.smartnews.data.local.toDomain
import com.smartnews.data.remote.BackendConfig
import com.smartnews.data.remote.SourceDiscovery
import com.smartnews.domain.logic.TextAnalysis
import com.smartnews.domain.model.Category
import com.smartnews.domain.model.NewsSource
import com.smartnews.domain.model.SourceDiscoveryResult
import com.smartnews.domain.repository.CategoryRepository
import com.smartnews.domain.repository.SettingsRepository
import com.smartnews.domain.repository.SourceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryRepositoryImpl @Inject constructor(
    private val dao: CategoryDao,
    private val settings: SettingsRepository,
) : CategoryRepository {

    override fun observeCategories(): Flow<List<Category>> =
        combine(dao.observeAll(), settings.settings) { list, s -> list.map { it.toDomain(s.language) } }

    override suspend fun setEnabled(id: String, enabled: Boolean) = dao.setEnabled(id, enabled)

    override suspend fun addTopic(name: String, keywords: List<String>) {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Topic name is empty" }
        val kws = (keywords.ifEmpty { listOf(clean) }).joinToString(",") { it.trim() }
        dao.upsert(
            CategoryEntity(
                id = "topic-" + TextAnalysis.sha1(clean.lowercase()).take(10), name = clean, nameAr = null,
                keywordsEn = kws, keywordsAr = kws, enabled = true, isCustom = true, isTopic = true, sortOrder = 1000,
            )
        )
    }

    override suspend fun deleteCustom(id: String) = dao.deleteCustom(id)
}

@Singleton
class SourceRepositoryImpl @Inject constructor(
    private val dao: SourceDao,
    private val discovery: SourceDiscovery,
    private val settings: SettingsRepository,
    private val backend: BackendConfig,
) : SourceRepository {

    override fun observeSources(): Flow<List<NewsSource>> =
        combine(dao.observeAll(), dao.observeCounts()) { sources, counts ->
            val m = counts.associate { it.sourceId to it.c }
            sources.map { it.toDomain(m[it.id] ?: 0) }
        }

    override fun observeSource(id: String): Flow<NewsSource?> = dao.observe(id).map { it?.toDomain() }

    override suspend fun discover(url: String): SourceDiscoveryResult {
        val s = settings.snapshot()
        return discovery.discover(url, s.language, "EG", backend.isConfigured)
    }

    override suspend fun addSource(result: SourceDiscoveryResult, name: String, categoryId: String?): String {
        require(result.ok) { result.message }
        val id = "custom-" + TextAnalysis.sha1(result.feedUrl ?: result.siteUrl).take(12)
        dao.upsert(
            SourceEntity(
                id = id, name = name.ifBlank { result.name }, siteUrl = result.siteUrl, feedUrl = result.feedUrl,
                type = result.type.name, categoryId = categoryId, language = null, enabled = true,
                isCustom = true, createdAt = System.currentTimeMillis(),
            )
        )
        return id
    }

    override suspend fun updateSource(id: String, name: String, feedUrl: String?, categoryId: String?) {
        val e = dao.get(id) ?: return
        dao.update(e.copy(name = name.ifBlank { e.name }, feedUrl = feedUrl?.ifBlank { null } ?: e.feedUrl, categoryId = categoryId))
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) = dao.setEnabled(id, enabled)

    override suspend fun remove(id: String) = dao.delete(id)
}
