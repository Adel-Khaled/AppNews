package com.smartnews.data.remote

import com.smartnews.domain.model.SourceDiscoveryResult
import com.smartnews.domain.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

class HttpStatusException(val code: Int, message: String) : IOException(message)

@Singleton
class WebFetcher @Inject constructor(private val client: OkHttpClient) {

    suspend fun get(url: String, maxBytes: Long = 3_000_000): Pair<String, String?> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, text/html;q=0.9, */*;q=0.8")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpStatusException(resp.code, "HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty response")
            val source = body.source()
            source.request(maxBytes)
            val text = source.buffer.snapshot().utf8().let { if (it.length > maxBytes) it.take(maxBytes.toInt()) else it }
            text to resp.header("Content-Type")
        }
    }

    suspend fun getFeed(url: String): ParsedFeed {
        val (body, _) = get(url)
        if (!RssParser.looksLikeFeed(body)) throw IOException("The URL did not return an RSS/Atom feed")
        return withContext(Dispatchers.Default) { RssParser.parse(body) }
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) SmartNews/1.0 (personal news reader)"
    }
}

object GoogleNews {
    private fun hl(lang: String, country: String) = if (lang == "en") "en-$country" else lang

    fun search(query: String, lang: String, country: String, days: Int? = 2): String {
        val q = URLEncoder.encode(query + (days?.let { " when:${it}d" } ?: ""), "UTF-8")
        return "https://news.google.com/rss/search?q=$q&hl=${hl(lang, country)}&gl=$country&ceid=$country:$lang"
    }

    fun top(lang: String, country: String) =
        "https://news.google.com/rss?hl=${hl(lang, country)}&gl=$country&ceid=$country:$lang"

    /** Google News world section, in the given edition. */
    fun world(lang: String, country: String) =
        "https://news.google.com/rss/headlines/section/topic/WORLD?hl=${hl(lang, country)}&gl=$country&ceid=$country:$lang"

    /** Google News "national" section: headlines for the selected country (used for Local News). */
    fun nation(lang: String, country: String) =
        "https://news.google.com/rss/headlines/section/topic/NATION?hl=${hl(lang, country)}&gl=$country&ceid=$country:$lang"
}

/** Extra media and text from an article's own page (only fetched when the user opens a story). */
data class PageExtras(
    val leadImage: String?,
    val images: List<String>,
    val youtubeIds: List<String>,
    val videoFiles: List<String>,
    val text: String,
    val siteName: String?,
)

@Singleton
class ArticlePageScraper @Inject constructor(private val fetcher: WebFetcher) {
    suspend fun extras(url: String): PageExtras = withContext(Dispatchers.IO) {
        val (html, _) = fetcher.get(url, maxBytes = 2_000_000)
        val doc = Jsoup.parse(html, url)
        fun meta(vararg names: String) = names.firstNotNullOfOrNull { n ->
            doc.selectFirst("meta[property=$n], meta[name=$n]")?.attr("abs:content")?.takeIf { it.isNotBlank() }
        }
        val lead = meta("og:image", "twitter:image", "twitter:image:src")
        val body = doc.selectFirst("article") ?: doc.body()
        val images = body.select("figure img[src], article img[src], picture img[src]")
            .map { it.attr("abs:src") }
            .filter { it.startsWith("http") && !it.endsWith(".svg") && !it.contains("logo", true) && !it.contains("avatar", true) && !it.contains("icon", true) }
            .distinct().take(8)
        val yt = doc.select("iframe[src*=youtube.com/embed/], iframe[data-src*=youtube.com/embed/]").mapNotNull {
            val src = it.attr("src").ifBlank { it.attr("data-src") }
            Regex("youtube\\.com/embed/([A-Za-z0-9_-]{11})").find(src)?.groupValues?.get(1)
        }.distinct()
        val files = (listOfNotNull(meta("og:video", "og:video:url", "og:video:secure_url")) +
            body.select("video source[src], video[src]").map { it.attr("abs:src") })
            .filter { val l = it.lowercase().substringBefore('?'); l.endsWith(".mp4") || l.endsWith(".m3u8") }
            .distinct()
        val text = body.select("p").joinToString("\n") { it.text() }.take(8000)
        PageExtras(lead, images, yt, files, text, meta("og:site_name"))
    }
}

/** Figures out how (and whether) SmartNews can follow a URL the user pasted into My Sources. */
@Singleton
class SourceDiscovery @Inject constructor(private val fetcher: WebFetcher) {

    suspend fun discover(input: String, lang: String, country: String, backendConfigured: Boolean): SourceDiscoveryResult {
        val raw = input.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
        val url = raw.toHttpUrlOrNull()
            ?: return fail(raw, "This is not a valid web address.")
        val host = url.host.removePrefix("www.").removePrefix("m.").lowercase()

        // Platforms that cannot be read without an official, authorised integration.
        when {
            host.endsWith("facebook.com") || host == "fb.com" || host == "fb.me" -> {
                val page = url.pathSegments.firstOrNull { it.isNotBlank() } ?: ""
                return if (backendConfigured && page.isNotBlank()) SourceDiscoveryResult(
                    ok = true, name = "Facebook: $page", siteUrl = raw, feedUrl = page, type = SourceType.FACEBOOK_PAGE,
                    message = "Will be read through the Facebook Graph API on your backend. This only works if the backend " +
                        "has a Page access token with permission to read this Page (see README → Facebook).",
                ) else fail(
                    raw,
                    "Facebook pages can't be read by scraping, and Meta's terms forbid it. Options: (1) add the organisation's " +
                        "website — most publish an RSS feed; (2) configure the backend with a Facebook Page access token " +
                        "(Graph API) and add the page again.",
                    SourceType.FACEBOOK_PAGE,
                )
            }
            host.endsWith("instagram.com") || host.endsWith("x.com") || host.endsWith("twitter.com") || host.endsWith("tiktok.com") ->
                return fail(raw, "$host does not offer public feeds and forbids scraping. Add the publisher's website instead; SmartNews will look for its RSS feed.")
        }

        val isYouTube = host.endsWith("youtube.com") || host == "youtu.be"
        val body: String
        try {
            body = fetcher.get(raw).first
        } catch (e: HttpStatusException) {
            val why = when (e.code) {
                401, 403 -> "The site refused automated access (HTTP ${e.code})."
                404 -> "The page was not found (HTTP 404)."
                429 -> "The site is rate-limiting requests (HTTP 429). Try again later."
                else -> "The site returned an error (HTTP ${e.code})."
            }
            return googleNewsFallback(raw, host, lang, country, "$why ")
        } catch (e: Exception) {
            return fail(raw, "Couldn't reach the site: ${e.message ?: e.javaClass.simpleName}. Check the address and your connection.")
        }

        if (RssParser.looksLikeFeed(body)) {
            val feed = runCatching { RssParser.parse(body) }.getOrNull()
            return if (feed != null && feed.items.isNotEmpty()) SourceDiscoveryResult(
                true, feed.title ?: host, feed.siteLink ?: raw, raw,
                if (isYouTube) SourceType.YOUTUBE_CHANNEL else SourceType.RSS,
                "Feed found with ${feed.items.size} items.", feed.items.size,
            ) else fail(raw, "This looks like a feed but it has no readable items.")
        }

        val doc = Jsoup.parse(body, raw)
        val siteName = doc.selectFirst("meta[property=og:site_name]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.title().takeIf { it.isNotBlank() } ?: host

        if (isYouTube) {
            val channelId = Regex("\"(?:channelId|externalId)\":\"(UC[A-Za-z0-9_-]{22})\"").find(body)?.groupValues?.get(1)
                ?: Regex("channel/(UC[A-Za-z0-9_-]{22})").find(raw)?.groupValues?.get(1)
            if (channelId != null) {
                val feedUrl = "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"
                val count = runCatching { fetcher.getFeed(feedUrl).items.size }.getOrDefault(0)
                return SourceDiscoveryResult(true, siteName.removeSuffix(" - YouTube"), raw, feedUrl, SourceType.YOUTUBE_CHANNEL,
                    "YouTube channel feed found ($count recent videos). Videos play with YouTube's official embedded player.", count)
            }
            return fail(raw, "Couldn't identify the YouTube channel. Paste the channel's page URL (youtube.com/@name or /channel/UC…).")
        }

        // 1) Feeds advertised in the page head.
        val advertised = doc.select("link[rel=alternate][type~=(?i)rss|atom]").map { it.attr("abs:href") }.filter { it.isNotBlank() }
        // 2) Common feed locations.
        val origin = "${url.scheme}://${url.host}"
        val guesses = listOf("/feed", "/rss", "/rss.xml", "/feed.xml", "/atom.xml", "/index.xml", "/feeds/posts/default", "/?feed=rss2")
            .map { origin + it }
        for (candidate in (advertised + guesses).distinct().take(12)) {
            val feed = runCatching { fetcher.getFeed(candidate) }.getOrNull() ?: continue
            if (feed.items.isNotEmpty()) {
                return SourceDiscoveryResult(true, feed.title ?: siteName, raw, candidate, SourceType.RSS,
                    "RSS/Atom feed found at $candidate (${feed.items.size} items).", feed.items.size)
            }
        }
        return googleNewsFallback(raw, host, lang, country, "No RSS/Atom feed was found on this site. ", siteName)
    }

    private suspend fun googleNewsFallback(raw: String, host: String, lang: String, country: String, why: String, name: String? = null): SourceDiscoveryResult {
        val feedUrl = GoogleNews.search("site:$host", lang, country, days = 30)
        val count = runCatching { fetcher.getFeed(feedUrl).items.size }.getOrDefault(0)
        return if (count > 0) SourceDiscoveryResult(true, name ?: host, raw, feedUrl, SourceType.GOOGLE_NEWS,
            why + "SmartNews will follow this site through Google News' index of it ($count recent articles found). " +
                "Links open the original articles.", count)
        else fail(raw, why + "Google News has no recent articles from $host either, so there is nothing SmartNews can follow yet.")
    }

    private fun fail(raw: String, msg: String, type: SourceType = SourceType.UNSUPPORTED) =
        SourceDiscoveryResult(false, raw, raw, null, type, msg)
}
