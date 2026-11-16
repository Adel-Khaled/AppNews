package com.smartnews.data.seed

import com.smartnews.data.local.CategoryEntity
import com.smartnews.data.local.SourceEntity
import com.smartnews.domain.model.SourceType

/**
 * Built-in categories and a starter set of public publisher RSS feeds.
 * Feeds are real publisher URLs; publishers occasionally move them. A broken feed shows its
 * error in My Sources and never stops the others from syncing — disable or edit it there.
 */
object DefaultCatalog {

    private data class C(val id: String, val en: String, val ar: String, val kwEn: String, val kwAr: String, val on: Boolean)

    private val categories = listOf(
        C("general", "General News", "أخبار عامة", "top news", "أخبار", true),
        C("international", "International News", "أخبار دولية", "world news", "أخبار العالم", true),
        C("local", "Local News", "أخبار محلية", "local news", "أخبار محلية", true),
        C("politics", "Politics", "سياسة", "politics", "سياسة", true),
        C("economy", "Economy & Finance", "اقتصاد ومال", "economy,finance,markets", "اقتصاد,البورصة", true),
        C("business", "Business", "أعمال", "business", "أعمال,شركات", true),
        C("technology", "Technology", "تكنولوجيا", "technology", "تكنولوجيا", true),
        C("ai", "Artificial Intelligence", "الذكاء الاصطناعي", "artificial intelligence", "الذكاء الاصطناعي", true),
        C("science", "Science", "علوم", "science", "علوم", true),
        C("sports", "Sports", "رياضة", "sports", "رياضة", true),
        C("health", "Health & Medicine", "صحة وطب", "health,medicine", "صحة,طب", true),
        C("entertainment", "Entertainment", "ترفيه", "entertainment", "فن,ترفيه", false),
        C("celebrities", "Celebrities", "مشاهير", "celebrities", "مشاهير,نجوم", false),
        C("movies", "Movies & TV Shows", "أفلام ومسلسلات", "movies,tv series", "أفلام,مسلسلات", false),
        C("automotive", "Automotive", "سيارات", "cars,automotive", "سيارات", false),
        C("education", "Education", "تعليم", "education", "تعليم", false),
        C("environment", "Environment", "بيئة", "climate,environment", "المناخ,بيئة", false),
        C("travel", "Travel & Tourism", "سفر وسياحة", "travel,tourism", "سياحة,سفر", false),
        C("gaming", "Gaming", "ألعاب", "video games", "ألعاب الفيديو", false),
        C("security", "Security & Defense", "أمن ودفاع", "defense,military", "عسكري,أمن", false),
    )

    fun categoryEntities(): List<CategoryEntity> = categories.mapIndexed { i, c ->
        CategoryEntity(c.id, c.en, c.ar, c.kwEn, c.kwAr, c.on, isCustom = false, isTopic = false, sortOrder = i)
    }

    private data class F(val id: String, val name: String, val site: String, val feed: String, val cat: String)

    private val feeds = listOf(
        F("bbc-world", "BBC News – World", "https://www.bbc.com/news/world", "https://feeds.bbci.co.uk/news/world/rss.xml", "international"),
        F("bbc-business", "BBC News – Business", "https://www.bbc.com/news/business", "https://feeds.bbci.co.uk/news/business/rss.xml", "business"),
        F("bbc-tech", "BBC News – Technology", "https://www.bbc.com/news/technology", "https://feeds.bbci.co.uk/news/technology/rss.xml", "technology"),
        F("bbc-science", "BBC News – Science & Environment", "https://www.bbc.com/news/science_and_environment", "https://feeds.bbci.co.uk/news/science_and_environment/rss.xml", "science"),
        F("bbc-health", "BBC News – Health", "https://www.bbc.com/news/health", "https://feeds.bbci.co.uk/news/health/rss.xml", "health"),
        F("bbc-ent", "BBC News – Entertainment & Arts", "https://www.bbc.com/news/entertainment_and_arts", "https://feeds.bbci.co.uk/news/entertainment_and_arts/rss.xml", "entertainment"),
        F("bbc-sport", "BBC Sport", "https://www.bbc.com/sport", "https://feeds.bbci.co.uk/sport/rss.xml", "sports"),
        F("aljazeera", "Al Jazeera English", "https://www.aljazeera.com", "https://www.aljazeera.com/xml/rss/all.xml", "international"),
        F("guardian-world", "The Guardian – World", "https://www.theguardian.com/world", "https://www.theguardian.com/world/rss", "international"),
        F("guardian-env", "The Guardian – Environment", "https://www.theguardian.com/environment", "https://www.theguardian.com/environment/rss", "environment"),
        F("npr", "NPR News", "https://www.npr.org", "https://feeds.npr.org/1001/rss.xml", "general"),
        F("france24", "France 24 (English)", "https://www.france24.com/en/", "https://www.france24.com/en/rss", "international"),
        F("dw", "DW (English)", "https://www.dw.com/en/", "https://rss.dw.com/rdf/rss-en-all", "international"),
        F("cnbc", "CNBC – Top News", "https://www.cnbc.com", "https://www.cnbc.com/id/100003114/device/rss/rss.html", "economy"),
        F("techcrunch", "TechCrunch", "https://techcrunch.com", "https://techcrunch.com/feed/", "technology"),
        F("verge", "The Verge", "https://www.theverge.com", "https://www.theverge.com/rss/index.xml", "technology"),
        F("ars", "Ars Technica", "https://arstechnica.com", "https://feeds.arstechnica.com/arstechnica/index", "technology"),
        F("espn", "ESPN", "https://www.espn.com", "https://www.espn.com/espn/rss/news", "sports"),
        F("variety", "Variety", "https://variety.com", "https://variety.com/feed/", "movies"),
        F("polygon", "Polygon", "https://www.polygon.com", "https://www.polygon.com/rss/index.xml", "gaming"),
        F("defensenews", "Defense News", "https://www.defensenews.com", "https://www.defensenews.com/arc/outboundfeeds/rss/", "security"),
    )

    /** Arabic-language international publishers (added in seed v2). */
    private val arabicFeeds = listOf(
        F("bbc-arabic", "بي بي سي عربي", "https://www.bbc.com/arabic", "https://feeds.bbci.co.uk/arabic/rss.xml", "international"),
        F("france24-ar", "فرانس 24 عربي", "https://www.france24.com/ar/", "https://www.france24.com/ar/rss", "international"),
        F("dw-ar", "DW عربية", "https://www.dw.com/ar/", "https://rss.dw.com/rdf/rss-ar-all", "international"),
    )

    /** Every built-in feed URL (used by the CI feed check). */
    val allFeedUrls: List<String> get() = (feeds + arabicFeeds).map { it.feed }

    fun sourceEntities(now: Long): List<SourceEntity> = feeds.map {
        SourceEntity(
            id = it.id, name = it.name, siteUrl = it.site, feedUrl = it.feed, type = SourceType.RSS.name,
            categoryId = it.cat, language = "en", enabled = true, isCustom = false, createdAt = now,
        )
    }

    fun arabicSourceEntities(now: Long): List<SourceEntity> = arabicFeeds.map {
        SourceEntity(
            id = it.id, name = it.name, siteUrl = it.site, feedUrl = it.feed, type = SourceType.RSS.name,
            categoryId = it.cat, language = "ar", enabled = true, isCustom = false, createdAt = now,
        )
    }

    /** Categories shown as tabs on Home (local/international are covered by the Egypt/World switch). */
    val tabCategoryIds = listOf(
        "politics", "economy", "business", "technology", "ai", "science", "sports", "health", "entertainment",
        "celebrities", "movies", "automotive", "education", "environment", "travel", "gaming", "security",
    )

    /** Pseudo-source used for articles that come from Google News search feeds. */
    const val GOOGLE_NEWS_SOURCE_ID = "google-news"
    /** Pseudo-source used for articles that come from the backend's news APIs. */
    const val BACKEND_SOURCE_ID = "backend-apis"

    fun systemSources(now: Long) = listOf(
        SourceEntity(GOOGLE_NEWS_SOURCE_ID, "Google News", "https://news.google.com", null,
            SourceType.GOOGLE_NEWS.name, null, null, enabled = true, isCustom = false, createdAt = now),
        SourceEntity(BACKEND_SOURCE_ID, "News APIs via your backend (NewsAPI, GNews, Guardian)", "", null,
            SourceType.BACKEND_API.name, null, null, enabled = true, isCustom = false, createdAt = now),
    )
}
