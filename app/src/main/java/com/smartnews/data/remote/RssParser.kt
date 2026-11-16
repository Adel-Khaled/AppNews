package com.smartnews.data.remote

import android.util.Xml
import org.jsoup.Jsoup
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ParsedVideo(val url: String, val mimeType: String?, val thumbnail: String?, val durationSec: Int?)

data class ParsedItem(
    val title: String,
    val link: String,
    val description: String,
    val contentHtml: String?,
    val publishedAt: Long?,
    val author: String?,
    val images: List<String>,
    val videos: List<ParsedVideo>,
    val sourceName: String?,
    val sourceUrl: String?,
    val youtubeVideoId: String?,
)

data class ParsedFeed(val title: String?, val siteLink: String?, val items: List<ParsedItem>)

/** Parses RSS 2.0, RSS 1.0 (RDF), Atom, Media RSS, Google News and YouTube feeds. */
object RssParser {

    fun looksLikeFeed(body: String): Boolean {
        val head = body.take(1500).lowercase()
        return "<rss" in head || "<feed" in head || "<rdf:rdf" in head
    }

    fun parse(xml: String): ParsedFeed {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(StringReader(xml.trimStart('﻿', ' ', '\n', '\r', '\t')))

        var feedTitle: String? = null
        var feedLink: String? = null
        val items = mutableListOf<ParsedItem>()
        var b: Builder? = null

        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val name = p.name.lowercase()
                val cur = b
                if (name == "item" || name == "entry") {
                    b = Builder()
                } else if (cur == null) {
                    when (name) {
                        "title" -> if (feedTitle == null) feedTitle = readText(p)
                        "link" -> if (feedLink == null) {
                            val href = p.getAttributeValue(null, "href")
                            feedLink = href ?: readText(p)
                        }
                    }
                } else {
                    handleItemTag(p, name, cur)
                }
            } else if (event == XmlPullParser.END_TAG) {
                val name = p.name.lowercase()
                if ((name == "item" || name == "entry") && b != null) {
                    b!!.build()?.let { items += it }
                    b = null
                }
            }
            event = p.next()
        }
        return ParsedFeed(feedTitle?.let(::cleanText), feedLink, items)
    }

    private class Builder {
        var title = ""
        var link = ""
        var guid: String? = null
        var description = ""
        var content: String? = null
        var published: Long? = null
        var updated: Long? = null
        var author: String? = null
        val images = LinkedHashSet<String>()
        val videos = mutableListOf<ParsedVideo>()
        var thumbnail: String? = null
        var sourceName: String? = null
        var sourceUrl: String? = null
        var ytId: String? = null

        fun build(): ParsedItem? {
            val url = link.ifBlank { guid?.takeIf { it.startsWith("http") } ?: "" }
            if (title.isBlank() || url.isBlank()) return null
            val htmlImages = (content ?: description).let { html ->
                runCatching { Jsoup.parse(html).select("img[src]").map { it.attr("src") } }.getOrDefault(emptyList())
            }.filter { it.startsWith("http") && !it.contains("feedburner") && !it.contains("pixel") }
            val allImages = (listOfNotNull(thumbnail) + images + htmlImages).distinct()
            var cleanTitle = cleanText(title)
            // Google News appends " - Publisher" to titles; strip it when we know the publisher.
            val src = sourceName
            if (src != null && cleanTitle.endsWith(" - $src")) cleanTitle = cleanTitle.removeSuffix(" - $src").trim()
            val vids = if (ytId != null) {
                listOf(ParsedVideo("https://www.youtube.com/watch?v=$ytId", "youtube", thumbnail, null))
            } else videos
            return ParsedItem(
                title = cleanTitle,
                link = url.trim(),
                description = cleanText(description.ifBlank { content ?: "" }).take(2000),
                contentHtml = content,
                publishedAt = published ?: updated,
                author = author?.let(::cleanText),
                images = allImages,
                videos = vids,
                sourceName = src?.let(::cleanText),
                sourceUrl = sourceUrl,
                youtubeVideoId = ytId,
            )
        }
    }

    private fun handleItemTag(p: XmlPullParser, name: String, b: Builder) {
        when (name) {
            "title" -> b.title = readText(p)
            "link" -> {
                val href = p.getAttributeValue(null, "href")
                val rel = p.getAttributeValue(null, "rel")
                if (href != null) {
                    if ((rel == null || rel == "alternate") && b.link.isBlank()) b.link = href
                    val type = p.getAttributeValue(null, "type")
                    if (rel == "enclosure" && type?.startsWith("image") == true) b.images += href
                } else {
                    val t = readText(p)
                    if (b.link.isBlank()) b.link = t
                }
            }
            "guid", "id" -> b.guid = readText(p)
            "description", "summary" -> b.description = readText(p)
            "media:description" -> if (b.description.isBlank()) b.description = readText(p)
            "content:encoded", "content" -> b.content = readText(p)
            "pubdate", "published", "dc:date", "a10:updated" -> b.published = parseDate(readText(p))
            "updated" -> b.updated = parseDate(readText(p))
            "author" -> {
                val t = readText(p)
                if (t.isNotBlank()) b.author = t
            }
            "dc:creator" -> b.author = readText(p)
            "media:thumbnail" -> p.getAttributeValue(null, "url")?.let { if (b.thumbnail == null) b.thumbnail = it }
            "itunes:image" -> p.getAttributeValue(null, "href")?.let { b.images += it }
            "media:content", "enclosure" -> {
                val url = p.getAttributeValue(null, "url") ?: return
                val type = p.getAttributeValue(null, "type")?.lowercase()
                val medium = p.getAttributeValue(null, "medium")?.lowercase()
                val duration = p.getAttributeValue(null, "duration")?.toIntOrNull()
                val lower = url.lowercase().substringBefore('?')
                when {
                    medium == "image" || type?.startsWith("image") == true ||
                        lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") -> b.images += url
                    medium == "video" || type?.startsWith("video") == true || type == "application/x-mpegurl" ||
                        lower.endsWith(".mp4") || lower.endsWith(".m3u8") || lower.endsWith(".webm") ->
                        b.videos += ParsedVideo(url, type, null, duration)
                    medium == null && type == null -> b.images += url
                }
            }
            "source" -> {
                b.sourceUrl = p.getAttributeValue(null, "url")
                b.sourceName = readText(p)
            }
            "yt:videoid" -> b.ytId = readText(p)
        }
    }

    /** Reads all text inside the current element, tolerating nested markup. */
    private fun readText(p: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.TEXT -> sb.append(p.text)
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return sb.toString().trim()
            }
        }
        return sb.toString().trim()
    }

    fun cleanText(htmlOrText: String): String =
        if ('<' in htmlOrText || '&' in htmlOrText) Jsoup.parse(htmlOrText).text().trim() else htmlOrText.trim()

    private val legacyFormats = listOf(
        "EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, d MMM yyyy HH:mm:ss zzz", "EEE, dd MMM yyyy HH:mm zzz",
        "dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd",
    )

    fun parseDate(raw: String?): Long? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        runCatching { return OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        runCatching { return ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
        for (f in legacyFormats) {
            runCatching {
                val d = SimpleDateFormat(f, Locale.US).apply { isLenient = true }.parse(s)
                if (d != null) return d.time
            }
        }
        return null
    }
}
