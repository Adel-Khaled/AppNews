package com.smartnews.domain.logic

import kotlin.math.exp

/** Minimal view of an article used for clustering. */
data class ClusterCandidate(
    val id: String,
    val storyId: String,
    val sourceName: String,
    val titleNormalized: String,
    val tokens: Set<String>,
    val entities: Set<String>,
    val publishedAt: Long,
)

/**
 * Groups articles that cover the same event into one story, and detects true duplicates
 * (the same article arriving from two feeds).
 */
object StoryClusterer {

    enum class Relation { DUPLICATE, SAME_STORY, UNRELATED }

    fun relation(a: ClusterCandidate, b: ClusterCandidate): Relation {
        val hours = TextAnalysis.hoursBetween(a.publishedAt, b.publishedAt)
        if (hours > 72) return Relation.UNRELATED
        // Identical headline = the same article syndicated or seen through two feeds (e.g. a
        // publisher's RSS and Google News). Keep one copy.
        if (a.titleNormalized == b.titleNormalized && a.titleNormalized.length > 15) return Relation.DUPLICATE
        val tokenSim = TextAnalysis.jaccard(a.tokens, b.tokens)
        val tokenOverlap = TextAnalysis.overlap(a.tokens, b.tokens)
        val sharedEntities = a.entities.count { it in b.entities }
        val timeFactor = if (hours <= 24) 1.0 else 0.8
        val score = (0.55 * tokenSim + 0.25 * tokenOverlap + 0.08 * sharedEntities.coerceAtMost(3)) * timeFactor
        if (a.sourceName.equals(b.sourceName, true) && tokenSim > 0.8) return Relation.DUPLICATE
        return if (score >= 0.42 && sharedEntities >= 1 || tokenSim >= 0.5) Relation.SAME_STORY else Relation.UNRELATED
    }

    /** Returns the best existing story match for [new], or null. */
    fun bestMatch(new: ClusterCandidate, existing: List<ClusterCandidate>): Pair<ClusterCandidate, Relation>? {
        var best: Pair<ClusterCandidate, Relation>? = null
        var bestScore = 0.0
        for (e in existing) {
            if (e.id == new.id) continue
            val rel = relation(new, e)
            if (rel == Relation.UNRELATED) continue
            val s = TextAnalysis.jaccard(new.tokens, e.tokens) + if (rel == Relation.DUPLICATE) 1.0 else 0.0
            if (s > bestScore) { bestScore = s; best = e to rel }
        }
        return best
    }
}

/** Keyword classifier used when a source does not declare a category. */
object CategoryClassifier {
    private val extra: Map<String, List<String>> = mapOf(
        "politics" to listOf("election", "parliament", "president", "minister", "government", "senate", "vote", "party", "انتخابات", "برلمان", "رئيس", "وزير", "حكومة"),
        "economy" to listOf("inflation", "economy", "gdp", "central bank", "interest rate", "stocks", "market", "oil price", "currency", "تضخم", "اقتصاد", "البنك المركزي", "البورصة", "الدولار"),
        "business" to listOf("company", "ceo", "earnings", "merger", "acquisition", "startup", "revenue", "شركة", "أرباح", "استحواذ"),
        "technology" to listOf("smartphone", "apple", "google", "microsoft", "software", "chip", "app", "iphone", "android", "تكنولوجيا", "هاتف", "تطبيق"),
        "ai" to listOf("artificial intelligence", "ai ", "chatgpt", "openai", "anthropic", "claude", "gemini", "machine learning", "llm", "الذكاء الاصطناعي"),
        "science" to listOf("nasa", "space", "research", "scientists", "study", "physics", "planet", "علماء", "فضاء", "دراسة"),
        "sports" to listOf("football", "soccer", "match", "league", "cup", "goal", "tennis", "nba", "olympic", "coach", "كرة", "مباراة", "الدوري", "منتخب"),
        "health" to listOf("health", "hospital", "virus", "vaccine", "disease", "cancer", "who ", "doctors", "صحة", "مستشفى", "فيروس", "لقاح"),
        "entertainment" to listOf("music", "concert", "album", "festival", "award", "grammy", "فن", "حفل", "مهرجان"),
        "celebrities" to listOf("star", "singer", "actress", "actor", "celebrity", "married", "نجم", "فنانة", "فنان"),
        "movies" to listOf("film", "movie", "netflix", "series", "box office", "oscar", "trailer", "فيلم", "مسلسل"),
        "international" to listOf("united nations", "summit", "foreign", "war", "ceasefire", "sanctions", "قمة", "حرب", "هدنة"),
        "automotive" to listOf("car", "tesla", "electric vehicle", "ev ", "toyota", "automaker", "سيارة", "سيارات"),
        "education" to listOf("school", "university", "students", "exam", "teachers", "جامعة", "طلاب", "امتحانات"),
        "environment" to listOf("climate", "emissions", "wildfire", "flood", "pollution", "renewable", "المناخ", "تلوث", "فيضانات"),
        "travel" to listOf("tourism", "travel", "airline", "flights", "hotel", "visa", "سياحة", "طيران", "سفر"),
        "gaming" to listOf("game", "gaming", "playstation", "xbox", "nintendo", "esports", "ألعاب"),
        "security" to listOf("military", "army", "missile", "defense", "attack", "drone", "security", "terror", "جيش", "صاروخ", "عسكري", "هجوم"),
    )

    fun classify(text: String, categoryKeywords: Map<String, List<String>>): String {
        val t = " " + text.lowercase() + " "
        var bestId = "general"
        var best = 0
        val ids = (categoryKeywords.keys + extra.keys).toSet()
        for (id in ids) {
            val kws = (categoryKeywords[id].orEmpty() + extra[id].orEmpty()).map { it.lowercase() }
            val score = kws.count { it.isNotBlank() && t.contains(it) }
            if (score > best) { best = score; bestId = id }
        }
        return bestId
    }
}

/**
 * Scores how well a video matches a specific news event: shared entities and terms in
 * title/description, closeness of publication time, and whether the channel is a news outlet.
 */
object VideoMatcher {
    data class Input(
        val title: String,
        val description: String,
        val channel: String,
        val publishedAt: Long?,
    )

    fun score(storyTokens: Set<String>, storyEntities: Set<String>, storyTime: Long, video: Input, newsChannel: Boolean): Double {
        val vText = video.title + " " + video.description
        val vTokens = TextAnalysis.tokens(vText)
        val vLower = vText.lowercase()
        val entityHits = storyEntities.count { vLower.contains(it) }
        val entityScore = if (storyEntities.isEmpty()) 0.0 else entityHits.toDouble() / storyEntities.size.coerceAtMost(5)
        val termScore = TextAnalysis.overlap(storyTokens, vTokens)
        val timeScore = video.publishedAt?.let {
            val h = (it - storyTime) / 3_600_000.0
            if (h < -48) 0.0 else exp(-kotlin.math.abs(h) / 72.0)
        } ?: 0.3
        val channelBonus = if (newsChannel) 0.1 else 0.0
        return 0.4 * entityScore.coerceAtMost(1.0) + 0.3 * termScore + 0.2 * timeScore + channelBonus
    }

    const val THRESHOLD = 0.35

    fun isInterview(title: String): Boolean {
        val t = title.lowercase()
        return listOf("interview", "speaks to", "in conversation", "exclusive:", "q&a", "مقابلة", "حوار", "لقاء خاص").any { it in t }
    }
}
