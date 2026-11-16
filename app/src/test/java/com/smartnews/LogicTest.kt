package com.smartnews

import com.smartnews.domain.logic.CategoryClassifier
import com.smartnews.domain.logic.ClusterCandidate
import com.smartnews.domain.logic.StoryClusterer
import com.smartnews.domain.logic.TextAnalysis
import com.smartnews.domain.logic.VideoMatcher
import com.smartnews.domain.model.DateFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {

    private fun cand(id: String, source: String, title: String, at: Long = 1_700_000_000_000) = ClusterCandidate(
        id, id, source, TextAnalysis.normalize(title), TextAnalysis.tokens(title), TextAnalysis.entities(title).toSet(), at,
    )

    @Test fun canonicalUrlDropsTrackingParams() {
        assertEquals(
            TextAnalysis.canonicalUrl("https://ex.com/a/?utm_source=x&id=5#top"),
            TextAnalysis.canonicalUrl("http://ex.com/a?id=5"),
        )
    }

    @Test fun sameEventFromTwoPublishersIsGrouped() {
        val a = cand("a", "BBC News", "Egypt and Sudan sign Nile water agreement in Cairo")
        val b = cand("b", "Al Jazeera", "Sudan, Egypt sign landmark Nile water deal in Cairo talks")
        assertEquals(StoryClusterer.Relation.SAME_STORY, StoryClusterer.relation(a, b))
    }

    @Test fun identicalHeadlineIsDuplicate() {
        val a = cand("a", "BBC News", "Central bank raises interest rates to 5 percent")
        val b = cand("b", "Google News", "Central bank raises interest rates to 5 percent")
        assertEquals(StoryClusterer.Relation.DUPLICATE, StoryClusterer.relation(a, b))
    }

    @Test fun differentEventsStaySeparate() {
        val a = cand("a", "BBC", "Egypt and Sudan sign Nile water agreement in Cairo")
        val b = cand("b", "ESPN", "Real Madrid beat Barcelona in El Clasico thriller")
        assertEquals(StoryClusterer.Relation.UNRELATED, StoryClusterer.relation(a, b))
    }

    @Test fun eventsDaysApartAreNotMerged() {
        val a = cand("a", "BBC", "Earthquake hits Turkey", at = 0)
        val b = cand("b", "CNN", "Earthquake hits Turkey", at = 5 * 86_400_000L)
        assertEquals(StoryClusterer.Relation.UNRELATED, StoryClusterer.relation(a, b))
    }

    @Test fun classifierUsesKeywords() {
        assertEquals("sports", CategoryClassifier.classify("Liverpool win the league after late goal", emptyMap()))
        assertNotEquals("sports", CategoryClassifier.classify("Central bank raises interest rate to fight inflation", emptyMap()))
    }

    @Test fun extractiveSummaryUsesPublisherSentences() {
        val body = "The government announced a new budget on Monday. The budget increases spending on schools by ten percent. " +
            "Critics said the plan does not address rising prices. Officials will present details to parliament next week."
        val (summary, points) = TextAnalysis.extractiveSummary("Government announces new budget", body)
        assertTrue(summary.isNotBlank())
        assertTrue(points.all { body.contains(it) })
    }

    @Test fun videoMatcherRejectsUnrelated() {
        val t = "Egypt and Sudan sign Nile water agreement in Cairo"
        val good = VideoMatcher.score(TextAnalysis.tokens(t), TextAnalysis.entities(t).toSet(), 0L,
            VideoMatcher.Input("Egypt, Sudan sign Nile water deal in Cairo", "", "Al Jazeera English", 3_600_000L), true)
        val bad = VideoMatcher.score(TextAnalysis.tokens(t), TextAnalysis.entities(t).toSet(), 0L,
            VideoMatcher.Input("Top 10 water bottles", "", "Gadgets", 3_600_000L), false)
        assertTrue(good >= VideoMatcher.THRESHOLD)
        assertTrue(bad < VideoMatcher.THRESHOLD)
    }

    @Test fun dateFiltersAreOrdered() {
        val now = System.currentTimeMillis()
        assertTrue(DateFilter.Last7Days.range(now).first < DateFilter.Today.range(now).first)
        assertTrue(DateFilter.Yesterday.range(now).last < DateFilter.Today.range(now).first)
        assertEquals(0L, DateFilter.All.range(now).first)
    }
}
