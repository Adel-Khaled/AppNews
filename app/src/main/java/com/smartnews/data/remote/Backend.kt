package com.smartnews.data.remote

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The SmartNews backend keeps every API key (NewsAPI, GNews, Guardian, YouTube, Facebook,
 * Anthropic) on the server. The app only knows the backend URL and a non-secret app token.
 */
interface BackendApi {
    @GET("health")
    suspend fun health(): HealthDto

    @GET("api/news")
    suspend fun news(
        @Query("category") category: String?,
        @Query("q") query: String?,
        @Query("from") from: String?,
        @Query("to") to: String?,
        @Query("lang") lang: String,
        @Query("country") country: String,
        @Query("page") page: Int = 1,
    ): NewsResponseDto

    @POST("api/videos")
    suspend fun videos(@Body request: VideoRequestDto): VideoResponseDto

    @GET("api/live")
    suspend fun live(@Query("q") query: String?, @Query("lang") lang: String): VideoResponseDto

    @POST("api/analyze")
    suspend fun analyze(@Body request: AnalyzeRequestDto): AnalyzeResponseDto

    @POST("api/explain")
    suspend fun explain(@Body request: ExplainRequestDto): ExplainResponseDto

    @GET("api/facebook/posts")
    suspend fun facebookPosts(@Query("page") page: String): NewsResponseDto
}

@Serializable
data class HealthDto(
    val ok: Boolean = false,
    val version: String = "",
    val providers: Map<String, Boolean> = emptyMap(),
)

@Serializable
data class NewsItemDto(
    val title: String,
    val url: String,
    val source: String = "",
    val publishedAt: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val author: String? = null,
    val provider: String = "",
    val category: String? = null,
)

@Serializable
data class ProviderStatusDto(val provider: String, val ok: Boolean, val count: Int = 0, val error: String? = null)

@Serializable
data class NewsResponseDto(
    val items: List<NewsItemDto> = emptyList(),
    val providers: List<ProviderStatusDto> = emptyList(),
    val error: String? = null,
)

@Serializable
data class VideoRequestDto(
    val title: String,
    val description: String,
    val entities: List<String>,
    val publishedAt: String,
    val lang: String,
    val maxResults: Int = 8,
)

@Serializable
data class VideoDto(
    val videoId: String? = null,
    val url: String,
    val title: String,
    val channel: String = "",
    val publishedAt: String? = null,
    val thumbnailUrl: String? = null,
    val durationSec: Int? = null,
    /** Only true when the platform reports the broadcast as live right now. */
    val isLive: Boolean = false,
    val relevance: Double = 0.0,
    val embeddable: Boolean = true,
    val description: String = "",
)

@Serializable
data class VideoResponseDto(val items: List<VideoDto> = emptyList(), val error: String? = null)

@Serializable
data class AnalyzeRequestDto(val title: String, val text: String, val source: String, val lang: String)

@Serializable
data class AnalyzeResponseDto(
    val summary: String = "",
    val keyPoints: List<String> = emptyList(),
    val entities: List<String> = emptyList(),
    val category: String? = null,
    val breaking: Boolean = false,
    val model: String = "",
)

@Serializable
data class ExplainArticleDto(val source: String, val title: String, val description: String, val publishedAt: String, val url: String)

@Serializable
data class ExplainRequestDto(val headline: String, val articles: List<ExplainArticleDto>, val lang: String)

@Serializable
data class ExplainResponseDto(
    val explanation: String = "",
    val background: String = "",
    val whyItMatters: String = "",
    val differences: List<String> = emptyList(),
    val unconfirmed: List<String> = emptyList(),
    val model: String = "",
)

/** Runtime backend settings, updated from DataStore. */
@Singleton
class BackendConfig @Inject constructor() {
    @Volatile var baseUrl: HttpUrl? = null
        private set
    @Volatile var token: String = ""

    val isConfigured: Boolean get() = baseUrl != null

    fun update(url: String, token: String) {
        val trimmed = url.trim()
        baseUrl = if (trimmed.isBlank()) null else (if (trimmed.endsWith("/")) trimmed else "$trimmed/").toHttpUrlOrNull()
        this.token = token.trim()
    }

    companion object { const val PLACEHOLDER_HOST = "backend.invalid" }
}

/** Rewrites the placeholder Retrofit base URL to the user-configured backend. */
class BackendUrlInterceptor(private val config: BackendConfig) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.url.host != BackendConfig.PLACEHOLDER_HOST) return chain.proceed(req)
        val base = config.baseUrl ?: throw IOException("Backend is not configured (Settings → Backend)")
        val builder = base.newBuilder()
        req.url.pathSegments.filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        for (i in 0 until req.url.querySize) {
            val v = req.url.queryParameterValue(i)
            if (v != null) builder.addQueryParameter(req.url.queryParameterName(i), v)
        }
        val newReq = req.newBuilder().url(builder.build()).apply {
            if (config.token.isNotEmpty()) header("X-App-Token", config.token)
        }.build()
        return chain.proceed(newReq)
    }
}
