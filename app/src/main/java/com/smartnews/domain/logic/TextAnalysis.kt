package com.smartnews.domain.logic

import java.security.MessageDigest
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.min

/**
 * Lightweight, on-device text intelligence. Works offline and without the backend.
 * The backend's LLM features improve on this but are optional.
 */
object TextAnalysis {

    private val STOPWORDS = setOf(
        // English
        "a", "an", "the", "and", "or", "but", "of", "to", "in", "on", "at", "for", "with", "by", "from",
        "as", "is", "are", "was", "were", "be", "been", "it", "its", "this", "that", "these", "those",
        "after", "before", "over", "under", "into", "about", "than", "then", "has", "have", "had",
        "will", "would", "can", "could", "says", "say", "said", "new", "news", "live", "update", "updates",
        "video", "watch", "how", "why", "what", "who", "when", "where", "not", "no", "up", "out", "more",
        "amid", "first", "us", "his", "her", "their", "they", "he", "she", "we", "you", "i", "via",
        // Arabic
        "في", "من", "على", "إلى", "الى", "عن", "مع", "أن", "ان", "إن", "هذا", "هذه", "التي", "الذي",
        "بعد", "قبل", "حول", "خلال", "بين", "كما", "او", "أو", "ثم", "قد", "لا", "ما", "هو", "هي",
    )

    private val COUNTRIES = setOf(
        "afghanistan", "algeria", "argentina", "australia", "austria", "bahrain", "bangladesh", "belgium",
        "brazil", "canada", "chile", "china", "colombia", "cuba", "denmark", "egypt", "ethiopia", "finland",
        "france", "germany", "ghana", "greece", "india", "indonesia", "iran", "iraq", "ireland", "israel",
        "italy", "japan", "jordan", "kenya", "kuwait", "lebanon", "libya", "malaysia", "mexico", "morocco",
        "netherlands", "nigeria", "norway", "oman", "pakistan", "palestine", "gaza", "peru", "philippines",
        "poland", "portugal", "qatar", "russia", "saudi", "singapore", "somalia", "spain", "sudan", "sweden",
        "switzerland", "syria", "taiwan", "tunisia", "turkey", "ukraine", "uae", "emirates", "uk", "britain",
        "yemen", "venezuela", "vietnam", "korea", "nato", "eu", "un",
        "مصر", "السعودية", "الإمارات", "قطر", "الكويت", "الأردن", "لبنان", "سوريا", "العراق", "فلسطين",
        "غزة", "إسرائيل", "إيران", "تركيا", "روسيا", "أوكرانيا", "الصين", "أمريكا", "فرنسا", "ألمانيا",
        "بريطانيا", "السودان", "ليبيا", "تونس", "الجزائر", "المغرب", "اليمن",
    )

    fun sha1(input: String): String =
        MessageDigest.getInstance("SHA-1").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** Canonical URL used as the de-duplication key (drops tracking params and fragments). */
    fun canonicalUrl(url: String): String {
        val noFragment = url.trim().substringBefore('#')
        val base = noFragment.substringBefore('?')
        val query = noFragment.substringAfter('?', "")
        val kept = query.split('&').filter { p ->
            p.isNotBlank() && !p.startsWith("utm_") && !p.startsWith("fbclid") &&
                !p.startsWith("ocid") && !p.startsWith("cmpid") && !p.startsWith("at_")
        }
        val normalizedBase = base.removeSuffix("/").replaceFirst("http://", "https://")
        return if (kept.isEmpty()) normalizedBase else "$normalizedBase?${kept.joinToString("&")}"
    }

    fun normalize(text: String): String {
        val n = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFKD)
            .replace(Regex("\\p{Mn}+"), "")
        return n.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    fun tokens(text: String): Set<String> =
        normalize(text).split(' ')
            .map { stripArabicArticle(it) }
            .filter { it.length > 2 && it !in STOPWORDS && !it.all(Char::isDigit) }
            .toSet()

    private fun stripArabicArticle(w: String): String =
        if (w.length > 4 && w.startsWith("ال")) w.substring(2) else w

    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { it in b }
        return inter.toDouble() / (a.size + b.size - inter)
    }

    /** Overlap coefficient: robust when one text is much shorter than the other. */
    fun overlap(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.count { it in b }.toDouble() / min(a.size, b.size)
    }

    /**
     * Heuristic named-entity extraction: capitalised word sequences (people, organisations,
     * places) plus a gazetteer of countries. Returns lower-cased entity strings.
     */
    fun entities(text: String): List<String> {
        val result = LinkedHashSet<String>()
        val words = text.replace(Regex("[\"“”‘’()\\[\\]:;,!?|]"), " ").split(Regex("\\s+"))
        var current = mutableListOf<String>()
        fun flush() {
            if (current.isNotEmpty()) {
                val phrase = current.joinToString(" ").trim('.', '-', '\'')
                val lower = phrase.lowercase()
                if (phrase.length > 2 && lower !in STOPWORDS) result += lower
            }
            current = mutableListOf()
        }
        words.forEachIndexed { i, raw ->
            val w = raw.trim('.', '-', '\'')
            val isCap = w.length > 1 && w[0].isUpperCase() && w.drop(1).any { it.isLowerCase() || it.isUpperCase() }
            val isAcronym = w.length in 2..6 && w.all { it.isUpperCase() || it.isDigit() }
            if ((isCap || isAcronym) && !(i == 0 && w.lowercase() in STOPWORDS)) current += w else flush()
        }
        flush()
        tokens(text).filter { it in COUNTRIES }.forEach { result += it }
        return result.filter { it.lowercase() !in STOPWORDS }.take(15)
    }

    fun sentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?؟])\\s+")).map { it.trim() }.filter { it.length in 25..400 }

    /**
     * Extractive summary: picks the sentences that best cover the article's key terms.
     * This is NOT AI-generated text — it is a selection of the publisher's own sentences.
     */
    fun extractiveSummary(title: String, body: String, maxSentences: Int = 3): Pair<String, List<String>> {
        val sents = sentences(body)
        if (sents.isEmpty()) return body.take(300) to emptyList()
        val titleTokens = tokens(title)
        val freq = HashMap<String, Int>()
        sents.forEach { s -> tokens(s).forEach { freq[it] = (freq[it] ?: 0) + 1 } }
        val scored = sents.mapIndexed { idx, s ->
            val t = tokens(s)
            val score = t.sumOf { (freq[it] ?: 0).toDouble() } / (t.size + 1) +
                2.0 * overlap(t, titleTokens) + (if (idx == 0) 1.0 else 0.0)
            Triple(idx, s, score)
        }
        val top = scored.sortedByDescending { it.third }.take(maxSentences).sortedBy { it.first }
        val summary = top.joinToString(" ") { it.second }
        val points = scored.sortedByDescending { it.third }.take(4).map { it.second }
        return summary to points
    }

    /** Words that commonly mark breaking/urgent coverage. */
    fun looksBreaking(title: String): Boolean {
        val t = title.lowercase()
        return listOf("breaking", "just in", "urgent", "developing", "عاجل", "خبر عاجل").any { it in t }
    }

    fun hoursBetween(a: Long, b: Long): Double = abs(a - b) / 3_600_000.0
}
