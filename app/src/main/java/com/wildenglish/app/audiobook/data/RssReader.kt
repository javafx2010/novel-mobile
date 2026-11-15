package com.wildenglish.app.audiobook.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 把一篇文章写成 App 内部的书籍包（`manifest.json` + `text/001.txt`），
 * 这样朗读、进度、离线配音、播放页等现有链路都能原样复用。
 */
object RssReader {

    private const val DIR = "rss_books"

    fun prepare(context: Context, article: RssArticle): Audiobook? {
        val text = article.text.ifBlank { article.summary }
        if (text.isBlank()) return null
        val dir = File(File(context.filesDir, DIR), article.id)
        val written = runCatching {
            if (dir.exists()) dir.deleteRecursively()
            File(dir, "text").mkdirs()
            File(dir, "text/001.txt").writeText(text, Charsets.UTF_8)
            val manifest = JSONObject().apply {
                put("format", ManifestParser.FORMAT)
                put("version", ManifestParser.VERSION)
                put("id", article.id)
                put("title", article.title)
                put("author", article.feedTitle)
                put("description", article.summary)
                put("chapters", JSONArray().apply {
                    put(JSONObject().apply {
                        put("title", article.feedTitle)
                        put("text", "text/001.txt")
                    })
                })
            }
            File(dir, "manifest.json").writeText(manifest.toString(), Charsets.UTF_8)
            true
        }.getOrDefault(false)
        if (!written) return null
        return ManifestParser.parse(dir)
    }

    /** 只保留最近 [keep] 篇文章的缓存，避免浏览订阅时无限占用空间。 */
    fun prune(context: Context, keep: Int = 30) {
        val dirs = File(context.filesDir, DIR).listFiles()?.filter { it.isDirectory } ?: return
        dirs.sortedByDescending { it.lastModified() }.drop(keep).forEach { dir ->
            // 连同这篇文章的配音缓存一起清掉。
            VoiceCache.clearForId(dir.name)
            runCatching { dir.deleteRecursively() }
        }
    }
}
