package com.wildenglish.app.audiobook.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.wildenglish.app.audiobook.data.CoverThumbCache.MAX_EDGE
import com.wildenglish.app.audiobook.data.CoverThumbCache.MAX_FILES
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 封面缩略图的**文件缓存**：把大图统一裁剪/缩放到最长边 [MAX_EDGE] 后写入 `cacheDir/cover_thumbs/`，
 * 之后直接 decode 这张小图，不再重复解大图、也不再占十几 MB 内存。
 *
 * key 由调用方给出，建议包含「源路径 + lastModified」或「url」，源变了自然生成新缩略图；
 * 超出 [MAX_FILES] 时按修改时间清理最旧的，避免无限增长。
 */
object CoverThumbCache {

    private const val DIR = "cover_thumbs"
    private const val MAX_EDGE = 1280
    private const val QUALITY = 85
    private const val MAX_FILES = 120

    private fun dir(context: Context): File =
        File(context.applicationContext.cacheDir, DIR).apply { if (!isDirectory) mkdirs() }

    private fun fileFor(context: Context, key: String): File =
        File(dir(context), md5(key) + ".webp")

    /** 命中磁盘小图则直接解码返回（无需 bounds/采样）；未缓存返回 null。 */
    fun read(context: Context, key: String): Bitmap? = runCatching {
        val f = fileFor(context, key)
        if (!f.isFile || f.length() == 0L) null
        else BitmapFactory.decodeFile(f.absolutePath)
    }.getOrNull()

    /** 从本地原图生成缩略图、落盘并返回。 */
    fun create(context: Context, key: String, source: File): Bitmap? = runCatching {
        if (!source.isFile) return@runCatching null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        }
        val bitmap = BitmapFactory.decodeFile(source.absolutePath, opts)
            ?: return@runCatching null
        save(context, key, bitmap)
    }.getOrNull()

    /** 从网络/内存中的原始图片字节生成缩略图、落盘并返回。 */
    fun create(context: Context, key: String, bytes: ByteArray): Bitmap? = runCatching {
        if (bytes.isEmpty()) return@runCatching null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: return@runCatching null
        save(context, key, bitmap)
    }.getOrNull()

    private fun save(context: Context, key: String, bitmap: Bitmap): Bitmap {
        val scaled = scaleDown(bitmap)
        runCatching {
            FileOutputStream(fileFor(context, key)).use { out ->
                @Suppress("DEPRECATION")
                scaled.compress(Bitmap.CompressFormat.WEBP, QUALITY, out)
            }
        }
        prune(context)
        return scaled
    }

    private fun sampleSize(w: Int, h: Int): Int {
        if (w <= 0 || h <= 0) return 1
        var sample = 1
        while (w / sample > MAX_EDGE || h / sample > MAX_EDGE) sample *= 2
        return sample
    }

    /** 采样是 2 的幂，可能没精确到 MAX_EDGE，这里再等比缩放到统一上限。 */
    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_EDGE) return bitmap
        val ratio = MAX_EDGE.toFloat() / longest
        val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun prune(context: Context) {
        val files = dir(context).listFiles()?.filter { it.isFile } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_FILES)
            .forEach { runCatching { it.delete() } }
    }

    private fun md5(value: String): String = runCatching {
        MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }.getOrDefault(value.hashCode().toString(16))
}
