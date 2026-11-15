package com.wildenglish.app.audiobook.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wildenglish.app.R
import com.wildenglish.app.audiobook.data.MarketApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private val coverCache = LruCache<String, ImageBitmap>(12)

/** 封面最长边的解码上限（px）：书架格子约 500px、详情页 450px、播放页背景约 1080px，够用。 */
private const val COVER_MAX_EDGE = 1280

/**
 * 按显示尺寸采样解码封面，避免把上千像素的原图整张读进内存。
 * 全尺寸解码会产生十几 MB 的 Bitmap，触发大 GC 并让 UI 掉帧。
 */
private suspend fun loadBitmap(key: String, file: File): ImageBitmap? = withContext(Dispatchers.IO) {
    if (!file.isFile) return@withContext null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

    var sample = 1
    while (bounds.outWidth / sample > COVER_MAX_EDGE ||
        bounds.outHeight / sample > COVER_MAX_EDGE
    ) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return@withContext null
    val image = bitmap.asImageBitmap()
    coverCache.put(key, image)
    image
}

/** Loads (and caches) a cover bitmap from a local file. */
@Composable
fun rememberCover(file: File?): ImageBitmap? {
    // lastModified() 是一次磁盘 stat，用 remember 缓存，避免每次重组都在主线程执行。
    val key = remember(file?.absolutePath) {
        file?.let { it.absolutePath + ":" + it.lastModified() }
    }
    val image by produceState<ImageBitmap?>(
        initialValue = key?.let { coverCache.get(it) },
        key1 = key,
    ) {
        value = if (file == null || key == null) {
            null
        } else {
            coverCache.get(key) ?: loadBitmap(key, file)
        }
    }
    return image
}

private val remoteCoverCache = LruCache<String, ImageBitmap>(16)

/** Loads (and caches) a cover bitmap over the network, e.g. a market cover from the backend. */
@Composable
fun rememberRemoteCover(url: String?): ImageBitmap? {
    if (url.isNullOrBlank()) return null
    var image by remember(url) { mutableStateOf(remoteCoverCache.get(url)) }
    LaunchedEffect(url) {
        if (image == null) {
            val loaded = MarketApi.fetchBitmap(url)
            if (loaded != null) {
                remoteCoverCache.put(url, loaded)
                image = loaded
            }
        }
    }
    return image
}

/** Book cover with an iOS-style drop shadow; fills whatever size the caller sets. */
@Composable
fun BookCover(
    coverFile: File?,
    title: String,
    modifier: Modifier = Modifier,
) {
    val bitmap = rememberCover(coverFile)
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .shadow(elevation = 10.dp, shape = shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // 没有封面：用默认书封（ComfyUI 生成的通用封面）占位，不再显示首字。
            Image(
                painter = painterResource(R.drawable.cover_default),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

fun formatDurationLabel(seconds: Int?): String? {
    if (seconds == null || seconds <= 0) return null
    val minutes = seconds / 60
    return when {
        minutes >= 60 -> "${minutes / 60} 小时 ${minutes % 60} 分"
        minutes >= 1 -> "$minutes 分钟"
        else -> "$seconds 秒"
    }
}

@Composable
fun CenteredLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun CenteredMessage(
    title: String,
    hint: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(Modifier
        .fillMaxSize()
        .padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(6.dp))
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
