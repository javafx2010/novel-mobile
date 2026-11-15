package com.wildenglish.app.audiobook.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Fetches and parses RSS 2.0 / Atom feeds with the platform XmlPullParser (no extra dependency).
 *
 * Only the article body is kept, as plain text, because the app reads it aloud with Edge TTS:
 * `content:encoded` / `content` is preferred, falling back to `description` / `summary`.
 */
object RssApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun fetch(feed: RssFeed): List<RssArticle> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(feed.url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) WildEnglish/1.0")
            .header(
                "Accept",
                "application/rss+xml, application/atom+xml, application/xml, text/xml, */*",
            )
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("订阅源加载失败（HTTP ${response.code}）")
            val body = response.body?.string() ?: throw IOException("订阅源返回为空")
            parse(feed, body)
        }
    }

    private fun parse(feed: RssFeed, xml: String): List<RssArticle> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))

        val articles = ArrayList<RssArticle>()
        var event = runCatching { parser.eventType }.getOrDefault(XmlPullParser.START_DOCUMENT)
        var inItem = false
        var title = ""
        var link: String? = null
        var published: String? = null
        val body = StringBuilder()

        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                // 关闭命名空间处理后 name 可能带前缀（content:encoded），统一取本地名。
                when (parser.name.substringAfter(':').lowercase()) {
                    "item", "entry" -> {
                        inItem = true
                        title = ""
                        link = null
                        published = null
                        body.setLength(0)
                    }

                    "title" -> if (inItem && title.isBlank()) title = readText(parser).trim()

                    "link" -> if (inItem && link == null) {
                        val href = parser.getAttributeValue(null, "href")
                        link = if (!href.isNullOrBlank()) href else readText(parser).trim()
                    }

                    "pubdate", "published", "updated", "date" ->
                        if (inItem && published == null) published = readText(parser).trim()

                    "encoded", "content", "description", "summary" -> if (inItem) {
                        val text = readText(parser)
                        if (text.isNotBlank()) {
                            if (body.isNotEmpty()) body.append('\n')
                            body.append(text)
                        }
                    }
                }
            } else if (event == XmlPullParser.END_TAG &&
                parser.name.substringAfter(':').lowercase() in setOf("item", "entry")
            ) {
                inItem = false
                if (title.isNotBlank()) {
                    val plain = stripHtml(body.toString())
                    articles.add(
                        RssArticle(
                            id = articleId(feed, link, title),
                            feedId = feed.id,
                            feedTitle = feed.title,
                            title = title,
                            link = link,
                            publishedAt = parseTime(published),
                            summary = plain.take(160),
                            text = plain,
                        )
                    )
                }
            }
            event = try {
                parser.next()
            } catch (e: Exception) {
                // 有些源的 XML 不严格：保留已解析出的条目，不让整次订阅失败。
                break
            }
        }
        return articles.sortedByDescending { it.publishedAt ?: 0L }
    }

    /** Reads the text of the current element, skipping any nested markup. */
    private fun readText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> sb.append(parser.text)
                else -> Unit
            }
        }
        return sb.toString()
    }

    private fun articleId(feed: RssFeed, link: String?, title: String): String {
        val seed = (link ?: title).hashCode()
        return "rss-${feed.id}-${abs(seed)}"
    }

    /** HTML -> plain text (tags removed, common entities decoded, whitespace collapsed). */
    fun stripHtml(raw: String): String {
        if (raw.isBlank()) return ""
        var text = raw
            .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?s)<br\\s*/?>"), "\n")
            .replace(Regex("(?s)</p>"), "\n")
            .replace(Regex("(?s)<[^>]+>"), " ")
        text = text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
        text = Regex("&#(\\d+);").replace(text) { m ->
            m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: ""
        }
        text = Regex("&#x([0-9a-fA-F]+);").replace(text) { m ->
            m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: ""
        }
        return text.replace(Regex("[ \\t\\u00A0]+"), " ").trim()
    }

    /**
     * 抓取文章网页正文（RSS 里通常只有摘要）。用 jsoup 去掉导航/脚本/样式，取最长的正文容器；
     * 抓不到或内容太短时返回 null，调用方退回订阅里自带的正文。
     */
    suspend fun fetchFullText(url: String): String? = withContext(Dispatchers.IO) {
        if (url.isBlank() || !url.startsWith("http", ignoreCase = true)) return@withContext null
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) WildEnglish/1.0")
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val html = response.body?.string() ?: return@use null
                extractReadable(html, url)
            }
        }.getOrNull()
    }

    private fun extractReadable(html: String, baseUrl: String): String? {
        val doc = Jsoup.parse(html, baseUrl)
        doc.select(
            "script, style, noscript, nav, header, footer, aside, form, iframe, figure, figcaption, " +
                    ".comment, .comments, .share, .social, .ad, .ads, .sidebar, .menu, " +
                    ".author, .authors, .byline, .meta, .toolbar, .actions, .recommend, .related, " +
                    ".breadcrumb"
        ).remove()
        val selectors = listOf(
            "article",
            "[role=article]",
            ".article-content",
            ".post-content",
            ".entry-content",
            ".rich_media_content",
            "main",
            "#content",
            ".content",
        )
        // 按"越像正文越靠前"的顺序取，而不是取最长（body 往往包含整个导航）。
        for (selector in selectors) {
            val element = doc.selectFirst(selector) ?: continue
            // 优先用段落文字：能自然跳过站点头部的作者/分享等 div 噪音。
            val paragraphs = element.select("p")
                .map { it.text().trim() }
                .filter { it.length > 25 }
            val joined = paragraphs.joinToString("\n")
            if (paragraphs.size >= 3 && joined.length >= 300) return joined
            val text = element.text().trim()
            if (text.length >= 300) return text
        }
        return doc.body()?.text()?.trim()?.takeIf { it.length >= 180 }
    }

    private fun parseTime(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        runCatching { OffsetDateTime.parse(raw) }.getOrNull()
            ?.let { return it.toInstant().toEpochMilli() }
        runCatching { ZonedDateTime.parse(raw) }.getOrNull()
            ?.let { return it.toInstant().toEpochMilli() }
        return runCatching {
            DateTimeFormatter.RFC_1123_DATE_TIME.parse(raw, Instant::from).toEpochMilli()
        }.getOrNull()
    }
}
