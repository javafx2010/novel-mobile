package com.wildenglish.app.audiobook.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.wildenglish.app.audiobook.data.CoverThumbCache.MAX_FILES
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 封面缩略图的文件缓存：把大图统一裁剪/缩放到小尺寸后写入 `cacheDir/cover_thumbs_v3/`，
 * 并**自适应压缩到 30KB 以内**（先降 WEBP 质量，仍超再降尺寸）。
 *
 * 之后直接 decode 这张小图，不再重复解大图、不再占十几 MB 内存。
 * key 由调用方给出，建议包含「源路径 + lastModified」或「url」，源变了自然生成新缩略图；
 * 超出 [MAX_FILES] 时按修改时间清理最旧的。
 */
object CoverThumbCache {

    private const val DIR = "cover_thumbs_v3"

    /** 起始最长边（px）。30KB 预算下可比之前更清晰。 */
    private const val START_EDGE = 960
    private const val MIN_EDGE = 160
    private const val START_QUALITY = 72
    private const val MIN_QUALITY = 35

    /** 30KB 上限。 */
    private const val TARGET_BYTES = 30 * 1024
    private const val MAX_FILES = 160

    private fun dir(context: Context): File =
        File(context.applicationContext.cacheDir, DIR).apply { if (!isDirectory) mkdirs() }

    private fun fileFor(context: Context, key: String): File =
        File(dir(context), md5(key) + ".webp")

    /**
     * 封面文件对应的缓存 key。导入时预生成与界面读取必须用同一个 key，
     * 否则会生成两份或读不到。
     */
    fun keyFor(file: File): String = file.absolutePath + ":" + file.lastModified()

    /** 导入图书时调用：提前把封面缩略图生成并落盘，之后界面一直读缓存。 */
    fun preGenerate(context: Context, file: File?): Bitmap? {
        if (file == null || !file.isFile) return null
        val key = keyFor(file)
        // 已存在就直接用，避免重复生成。
        read(context, key)?.let { return it }
        return create(context, key, file)
    }

    /** 删除图书时清掉对应的封面缩略图缓存。 */
    fun remove(context: Context, file: File) {
        runCatching { File(dir(context), md5(keyFor(file)) + ".webp").delete() }
    }

    /** 命中磁盘小图则直接解码返回；旧的大图或不存在的返回 null。 */
    fun read(context: Context, key: String): Bitmap? = runCatching {
        val f = fileFor(context, key)
        if (!f.isFile || f.length() == 0L || f.length() > TARGET_BYTES * 2L) null
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

    /**
     * 自适应编码：从 START_EDGE / START_QUALITY 起步，超过 30KB 先逐级降质量，
     * 降到最低质量仍超则逐级缩小尺寸，直到 <= TARGET_BYTES。
     */
    private fun save(context: Context, key: String, bitmap: Bitmap): Bitmap {
        var current = scaleDown(bitmap, START_EDGE)
        var quality = START_QUALITY
        var encoded = encode(current, quality)
        var guard = 0
        while (encoded.size > TARGET_BYTES && guard++ < 16) {
            if (quality > MIN_QUALITY) {
                quality -= 12
                encoded = encode(current, quality)
            } else {
                val longest = maxOf(current.width, current.height)
                val nextEdge = maxOf(MIN_EDGE, (longest * 0.8f).toInt())
                if (nextEdge >= longest) break
                val next = scaleDown(current, nextEdge)
                if (next === current) break
                current.recycle()
                current = next
                encoded = encode(current, quality)
            }
        }
        runCatching { FileOutputStream(fileFor(context, key)).use { it.write(encoded) } }
        prune(context)
        return current
    }

    private fun encode(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use { bos ->
            @Suppress("DEPRECATION")
            bitmap.compress(Bitmap.CompressFormat.WEBP, quality, bos)
            bos.toByteArray()
        }

    private fun sampleSize(w: Int, h: Int): Int {
        if (w <= 0 || h <= 0) return 1
        var sample = 1
        while (w / sample > START_EDGE || h / sample > START_EDGE) sample *= 2
        return sample
    }

    /** 采样是 2 的幂，可能没精确到 [maxEdge]，这里再等比缩放到上限。 */
    private fun scaleDown(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / longest
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
