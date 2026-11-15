package com.wildenglish.app.audiobook.data

import android.content.Context
import android.content.SharedPreferences

/** One RSS/Atom feed. [url] must point at the feed itself (not the site). */
data class RssFeed(
    val id: String,
    val title: String,
    val url: String,
    val category: String,
)

/** One article fetched from a feed. [text] is the plain-text body used for reading aloud. */
data class RssArticle(
    val id: String,
    val feedId: String,
    val feedTitle: String,
    val title: String,
    val link: String?,
    val publishedAt: Long?,
    val summary: String,
    val text: String,
)

/** Built-in "常用 RSS" list, offered as one-tap subscriptions. */
object RssFeeds {

    val DEFAULTS: List<RssFeed> = listOf(
        RssFeed("sspai", "少数派", "https://sspai.com/feed", "科技"),
        RssFeed("ithome", "IT之家", "https://www.ithome.com/rss/", "科技"),
        RssFeed("ifanr", "爱范儿", "https://www.ifanr.com/feed", "科技"),
        RssFeed("geekpark", "极客公园", "https://www.geekpark.net/rss", "科技"),
        RssFeed("infoq", "InfoQ 中文", "https://www.infoq.cn/feed", "技术"),
        RssFeed("solidot", "Solidot", "https://www.solidot.org/index.rss", "技术"),
        RssFeed("williamlong", "月光博客", "https://www.williamlong.info/rss.xml", "技术"),
        RssFeed("hn", "Hacker News", "https://hnrss.org/frontpage", "技术"),
    )

    fun byId(id: String?): RssFeed? = DEFAULTS.firstOrNull { it.id == id }
}

/** 订阅关系（常用 RSS 的 id 集合）持久化在 SharedPreferences。 */
object RssStore {

    private const val PREFS = "rss_subscriptions"
    private const val KEY = "feed_ids"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun subscriptions(context: Context): Set<String> =
        prefs(context).getStringSet(KEY, emptySet())?.toHashSet() ?: HashSet()

    fun setSubscribed(context: Context, feedId: String, subscribed: Boolean) {
        val current = subscriptions(context).toMutableSet()
        if (subscribed) current.add(feedId) else current.remove(feedId)
        prefs(context).edit().putStringSet(KEY, current).apply()
    }
}
