package com.wildenglish.app.audiobook.data

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One book offered by the backend market. */
data class MarketItem(
    val id: Long,
    val title: String,
    val author: String?,
    val description: String?,
    val coverUrl: String?,
    val language: String?,
    val chapterCount: Int,
    val fileName: String,
    val fileSize: Long?,
    val downloadUrl: String,
    /** Backend "last updated" timestamp (ISO local date-time); used to detect newer versions. */
    val updatedAt: String? = null,
)

/** One page of the market listing. */
data class MarketPage(
    val items: List<MarketItem>,
    val page: Int,
    val totalPages: Int,
    val totalElements: Long,
)

/** One backend category, used to filter the market. */
data class MarketCategory(val id: Long, val name: String)

/**
 * Client of the "novel-browse" backend's book market (`/market`).
 *
 * The market only offers browsing and downloading — there is no purchase: a book with a stored
 * EPUB is listed and its original file can be downloaded straight to the device, then imported into
 * the local library by the normal import flow.
 */
object MarketApi {

    /** Backend address, fixed at build time (the market address is not user-configurable). */
    private const val BASE_URL = "http://43.173.26.117:8080"

    /** 书城封面解码的最长边上限（px），与本地封面一致，避免大图进内存。 */
    private const val COVER_MAX_EDGE = 1280

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // Whole-call cap so a hung connection cannot block forever, generous for EPUB downloads.
        .callTimeout(300, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Market listing; [language] "all" shows every language version, [categoryId] filters to one category.
     */
    suspend fun fetchPage(
        page: Int,
        size: Int,
        language: String = "all",
        categoryId: Long? = null,
    ): MarketPage = withContext(Dispatchers.IO) {
        val category = categoryId?.let { "&categoryId=$it" } ?: ""
        val url =
            "${BASE_URL}/market/books?language=$language&sort=update&page=$page&size=$size$category"
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("市场加载失败（HTTP ${resp.code}）")
            val body = resp.body?.string() ?: throw IOException("市场返回为空")
            parsePage(body)
        }
    }

    /** Backend categories, fetched automatically so the market can show filter chips. */
    suspend fun fetchCategories(language: String = "all"): List<MarketCategory> =
        withContext(Dispatchers.IO) {
            val url = "${BASE_URL}/categories?language=$language"
            val request = Request.Builder().url(url).header("Accept", "application/json").build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("分类加载失败（HTTP ${resp.code}）")
                val body = resp.body?.string() ?: return@use emptyList()
                parseCategories(body)
            }
        }

    /** One book's current market entry (used by the background update check). */
    suspend fun fetchBook(id: Long): MarketItem? = withContext(Dispatchers.IO) {
        val url = "${BASE_URL}/market/books/$id"
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val body = resp.body?.string() ?: return@use null
            runCatching { parseItem(JSONObject(body)) }.getOrNull()
        }
    }

    /**
     * Categories that actually contain downloadable books, so the market never offers a dead-end
     * category. Returns null when the backend is older and has no such endpoint (404), letting the
     * caller fall back to the plain [fetchCategories].
     */
    suspend fun fetchMarketCategories(language: String = "all"): List<MarketCategory>? =
        withContext(Dispatchers.IO) {
            val url = "${BASE_URL}/market/categories?language=$language"
            val request = Request.Builder().url(url).header("Accept", "application/json").build()
            runCatching {
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string() ?: return@use null
                    parseCategories(body)
                }
            }.getOrNull()
        }

    private fun parseCategories(body: String): List<MarketCategory> {
        val array = runCatching { JSONArray(body) }.getOrDefault(JSONArray())
        val out = ArrayList<MarketCategory>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optLong("id", -1L)
            val name = item.optString("name").takeIf { it.isNotBlank() } ?: continue
            if (id >= 0L) out.add(MarketCategory(id, name))
        }
        return out
    }

    /** Downloads one market book's EPUB to [dest] and returns the saved file. */
    suspend fun download(item: MarketItem, dest: File): File = withContext(Dispatchers.IO) {
        if (item.downloadUrl.isBlank()) throw IOException("下载地址为空")
        client.newCall(Request.Builder().url(item.downloadUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("下载失败（HTTP ${resp.code}）")
            val body = resp.body ?: throw IOException("下载内容为空")
            dest.parentFile?.mkdirs()
            body.byteStream().use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
        dest
    }

    /** Loads a remote cover image (used by the market grid). */
    suspend fun fetchBitmap(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val bytes = resp.body?.bytes()
                if (bytes == null || bytes.isEmpty()) {
                    null
                } else {
                    // 按显示尺寸采样，避免整张高清封面进内存造成 GC 掉帧。
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                        while (bounds.outWidth / sample > COVER_MAX_EDGE ||
                            bounds.outHeight / sample > COVER_MAX_EDGE
                        ) {
                            sample *= 2
                        }
                    }
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
                }
            }
        }.getOrNull()
    }

    private fun parsePage(body: String): MarketPage {
        val root = JSONObject(body)
        val array = root.optJSONArray("content") ?: JSONArray()
        val items = ArrayList<MarketItem>(array.length())
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            parseItem(entry)?.let { items.add(it) }
        }
        return MarketPage(
            items = items,
            page = root.optInt("page", 0),
            totalPages = root.optInt("totalPages", 0),
            totalElements = root.optLong("totalElements", 0L),
        )
    }

    private fun parseItem(entry: JSONObject): MarketItem? {
        val novel = entry.optJSONObject("novel") ?: return null
        return MarketItem(
            id = novel.optLong("id"),
            title = novel.optString("title"),
            author = novel.optString("author").takeIf { it.isNotBlank() },
            description = novel.optString("description").takeIf { it.isNotBlank() },
            coverUrl = absolute(novel.optString("coverUrl").takeIf { it.isNotBlank() }),
            language = novel.optString("language").takeIf { it.isNotBlank() },
            chapterCount = novel.optInt("chapterCount"),
            fileName = entry.optString("fileName"),
            fileSize = if (entry.has("fileSize") && !entry.isNull("fileSize")) {
                entry.optLong("fileSize")
            } else {
                null
            },
            downloadUrl = absolute(entry.optString("downloadUrl")),
            updatedAt = novel.optString("updatedAt").takeIf { it.isNotBlank() },
        )
    }

    private fun absolute(path: String?): String {
        if (path.isNullOrBlank()) return ""
        return if (path.startsWith(
                "http",
                ignoreCase = true
            )
        ) path else BASE_URL + path
    }
}
