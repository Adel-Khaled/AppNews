package com.smartnews.domain.model

import java.util.Calendar

data class Category(
    val id: String,
    val name: String,
    val nameAr: String?,
    val keywords: List<String>,
    val enabled: Boolean,
    val isCustom: Boolean,
    val isTopic: Boolean,
    val sortOrder: Int,
) {
    fun label(lang: String) = if (lang == "ar" && nameAr != null) nameAr else name
}

/** Local = Egypt. Global = the rest of the world. */
enum class Scope(val id: String) { LOCAL("local"), GLOBAL("global") }

enum class SourceType(val label: String, val labelAr: String) {
    RSS("RSS / Atom feed", "موجز RSS"),
    GOOGLE_NEWS("Google News search", "بحث أخبار جوجل"),
    YOUTUBE_CHANNEL("YouTube channel", "قناة يوتيوب"),
    BACKEND_API("News APIs (backend)", "واجهات الأخبار (الخادم)"),
    FACEBOOK_PAGE("Facebook page (Graph API)", "صفحة فيسبوك"),
    UNSUPPORTED("Not accessible", "غير متاح"),
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
    val publishedAt: Long,
    val collectedAt: Long,
    val categoryId: String,
    val storyId: String,
    val provider: String,
    val isSaved: Boolean,
    val isRead: Boolean,
    val summary: String?,
    val keyPoints: List<String>,
    val summaryIsAi: Boolean,
    val entities: List<String>,
    val language: String? = null,
    val scope: String = "global",
    val titleTr: String? = null,
    val descriptionTr: String? = null,
    val trLang: String? = null,
) {
    /** True when the text shown for [lang] is a machine translation. */
    fun isTranslated(lang: String) = trLang == lang && titleTr != null
    fun titleFor(lang: String) = if (isTranslated(lang)) titleTr!! else title
    fun descriptionFor(lang: String) = if (trLang == lang && descriptionTr != null) descriptionTr else description
}

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
    val headlineTr: String? = null,
    val trLang: String? = null,
) {
    fun headlineFor(lang: String) = if (trLang == lang && headlineTr != null) headlineTr else headline
    fun isTranslated(lang: String) = trLang == lang && headlineTr != null
}

enum class MediaType { VIDEO, IMAGE, LIVE }

enum class Playback { EXOPLAYER, YOUTUBE_EMBED, EXTERNAL, NONE }

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

sealed class DateFilter(val label: String, val labelAr: String) {
    data object Today : DateFilter("Today", "اليوم")
    data object Yesterday : DateFilter("Yesterday", "أمس")
    data object Last7Days : DateFilter("7 days", "٧ أيام")
    data object Last30Days : DateFilter("30 days", "٣٠ يوماً")
    data object Last3Months : DateFilter("3 months", "٣ أشهر")
    data object Last6Months : DateFilter("6 months", "٦ أشهر")
    data object Last12Months : DateFilter("12 months", "١٢ شهراً")
    data object All : DateFilter("All", "الكل")
    data class Custom(val from: Long, val to: Long) : DateFilter("Custom", "مخصص")

    fun label(lang: String) = if (lang == "ar") labelAr else label

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
        // A getter, not a stored list: a stored list in the sealed parent's companion is built
        // while a subclass object may still be initialising, which put `null` into the list and
        // crashed every screen that shows the date filter (For You, Latest, Search).
        val presets: List<DateFilter>
            get() = listOf(Today, Yesterday, Last7Days, Last30Days, Last3Months, Last6Months, Last12Months, All)
    }
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val language: String = "ar",
    val scope: Scope = Scope.LOCAL,
    val refreshIntervalHours: Int = 3,
    val wifiOnlySync: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val notifyBreaking: Boolean = true,
    val maxNotificationsPerDay: Int = 5,
    val videoAutoplay: Boolean = false,
    val imagesOnWifiOnly: Boolean = false,
    val translateNews: Boolean = true,
    val backendUrl: String = "",
    val appToken: String = "",
    val lastSyncAt: Long = 0L,
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
