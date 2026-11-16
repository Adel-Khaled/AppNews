package com.smartnews.domain.model

import java.util.Calendar

data class Category(
    val id: String,
    val name: String,
    val nameAr: String?,
    val keywords: List<String>,
    val enabled: Boolean,
    val isCustom: Boolean,
    /** true for user-defined topics/keywords (e.g. "SpaceX", "Champions League"). */
    val isTopic: Boolean,
    val sortOrder: Int,
)

enum class SourceType(val label: String) {
    RSS("RSS / Atom feed"),
    GOOGLE_NEWS("Google News search feed"),
    YOUTUBE_CHANNEL("YouTube channel feed"),
    BACKEND_API("News API (via backend)"),
    FACEBOOK_PAGE("Facebook page (Graph API via backend)"),
    UNSUPPORTED("Not accessible"),
}

data class NewsSource(
    val id: String,
    val name: String,
    val siteUrl: String,
    val feedUrl: String?,
    val type: SourceType,
    val categoryId: String?,
    val enabled: Boolean,
    val isCustom: Boolean,
    val lastSyncAt: Long?,
    val lastSuccessAt: Long?,
    val lastError: String?,
    val articleCount: Int = 0,
)

data class Article(
    val id: String,
    val url: String,
    val title: String,
    val description: String,
    val sourceId: String,
    val sourceName: String,
    val author: String?,
    val imageUrl: String?,
    /** When the publisher says the article was published. */
    val publishedAt: Long,
    /** When SmartNews first collected it. */
    val collectedAt: Long,
    val categoryId: String,
    val storyId: String,
    val provider: String,
    val isSaved: Boolean,
    val isRead: Boolean,
    val summary: String?,
    val keyPoints: List<String>,
    /** true = produced by an LLM on the backend; false = automatic extract of publisher text. */
    val summaryIsAi: Boolean,
    val entities: List<String>,
)

data class Story(
    val id: String,
    val headline: String,
    val imageUrl: String?,
    val categoryId: String,
    val firstPublishedAt: Long,
    val lastPublishedAt: Long,
    val updatedAt: Long,
    val articleCount: Int,
    val sourceCount: Int,
    val isBreaking: Boolean,
    val isSaved: Boolean,
    val aiSummary: String?,
    val aiKeyPoints: List<String>,
    val leadSourceName: String,
)

enum class MediaType { VIDEO, IMAGE, LIVE }

/** How a video can be played without violating platform terms. */
enum class Playback {
    /** Direct media file / HLS stream published in a feed: played with Media3 ExoPlayer. */
    EXOPLAYER,
    /** YouTube: official embedded IFrame player (no stream extraction). */
    YOUTUBE_EMBED,
    /** Open in the official app or browser. */
    EXTERNAL,
    NONE,
}

data class MediaItem(
    val id: String,
    val storyId: String,
    val articleId: String?,
    val type: MediaType,
    val title: String,
    val url: String,
    val thumbnailUrl: String?,
    val publisher: String,
    val publishedAt: Long?,
    val durationSec: Int?,
    val isLive: Boolean,
    val playback: Playback,
    val attribution: String,
    val relevance: Double,
    val isSaved: Boolean,
    val isInterview: Boolean,
)

data class HistoryEntry(val article: Article, val viewedAt: Long)

enum class SortMode { NEWEST, RELEVANCE }

enum class ContentType { ALL, ARTICLES, VIDEOS, IMAGES }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

sealed class DateFilter(val label: String) {
    data object Today : DateFilter("Today")
    data object Yesterday : DateFilter("Yesterday")
    data object Last7Days : DateFilter("7 days")
    data object Last30Days : DateFilter("30 days")
    data object Last3Months : DateFilter("3 months")
    data object Last6Months : DateFilter("6 months")
    data object Last12Months : DateFilter("12 months")
    data object All : DateFilter("All")
    data class Custom(val from: Long, val to: Long) : DateFilter("Custom")

    /** Inclusive [from, to] in epoch millis. */
    fun range(now: Long = System.currentTimeMillis()): LongRange {
        val startOfToday = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val day = 86_400_000L
        return when (this) {
            Today -> startOfToday..Long.MAX_VALUE
            Yesterday -> (startOfToday - day) until startOfToday
            Last7Days -> (now - 7 * day)..Long.MAX_VALUE
            Last30Days -> (now - 30 * day)..Long.MAX_VALUE
            Last3Months -> (now - 91 * day)..Long.MAX_VALUE
            Last6Months -> (now - 182 * day)..Long.MAX_VALUE
            Last12Months -> (now - 365 * day)..Long.MAX_VALUE
            All -> 0L..Long.MAX_VALUE
            is Custom -> from..to
        }
    }

    companion object {
        val presets = listOf(Today, Yesterday, Last7Days, Last30Days, Last3Months, Last6Months, Last12Months, All)
    }
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val language: String = "en",
    val country: String = "US",
    val refreshIntervalHours: Int = 3,
    val wifiOnlySync: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val notifyBreaking: Boolean = true,
    val notifyCategoryIds: Set<String> = emptySet(),
    val notifySourceIds: Set<String> = emptySet(),
    val maxNotificationsPerDay: Int = 5,
    val sortMode: SortMode = SortMode.NEWEST,
    val videoAutoplay: Boolean = false,
    val imagesOnWifiOnly: Boolean = false,
    val backendUrl: String = "",
    val appToken: String = "",
    val lastSyncAt: Long = 0L,
    val useGoogleNews: Boolean = true,
) {
    val backendConfigured: Boolean get() = backendUrl.isNotBlank()
}

data class RefreshResult(
    val newArticles: Int,
    val updatedArticles: Int,
    val newVideos: Int,
    val sourcesOk: Int,
    val sourcesFailed: Int,
    val errors: List<String>,
)

data class SourceDiscoveryResult(
    val ok: Boolean,
    val name: String,
    val siteUrl: String,
    val feedUrl: String?,
    val type: SourceType,
    val message: String,
    val previewCount: Int = 0,
)

data class Explanation(
    val explanation: String,
    val background: String,
    val whyItMatters: String,
    val differences: List<String>,
    val unconfirmed: List<String>,
    val model: String,
)
