package com.wildenglish.app.audiobook.ui

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wildenglish.app.R
import com.wildenglish.app.audiobook.data.CoverThumbCache
import com.wildenglish.app.audiobook.data.MarketApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 缩略图直接读文件缓存（小图）；没有才从原图生成并落盘。
 * 全程在 IO 线程。解码后的 ImageBitmap 由 rememberCover 的 produceState 持有，不再另设内存池。
 */
private suspend fun loadBitmap(
    context: Context,
    key: String,
    file: File,
): ImageBitmap? = withContext(Dispatchers.IO) {
    val bitmap = CoverThumbCache.read(context, key)
        ?: CoverThumbCache.create(context, key, file)
        ?: return@withContext null
    val image = bitmap.asImageBitmap()
    image
}

/** Loads (and caches) a cover bitmap from a local file. */
@Composable
fun rememberCover(file: File?): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    // lastModified() 是一次磁盘 stat，用 remember 缓存，避免每次重组都在主线程执行。
    val key = remember(file?.absolutePath) {
        file?.let { CoverThumbCache.keyFor(it) }
    }
    val image by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = key,
    ) {
        value = if (file == null || key == null) {
            null
        } else {
            loadBitmap(context, key, file)
        }
    }
    return image
}

/** Loads a cover bitmap over the network (cached to file by [MarketApi.fetchBitmap]). */
@Composable
fun rememberRemoteCover(url: String?): ImageBitmap? {
    if (url.isNullOrBlank()) return null
    val context = LocalContext.current.applicationContext
    var image by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        if (image == null) {
            val loaded = MarketApi.fetchBitmap(context, url)
            if (loaded != null) image = loaded
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
    // 默认封面先显示；真实封面加载好后淡入覆盖，避免先空白后跳图的闪烁。
    val targetAlpha = if (bitmap != null) 1f else 0f
    val coverAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 260),
        label = "coverFadeIn",
    )
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .shadow(elevation = 10.dp, shape = shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        // 底层：默认书封（ComfyUI 生成的通用封面），始终存在。
        Image(
            painter = painterResource(R.drawable.cover_default),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // 上层：真实封面，加载好后淡入。
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(coverAlpha),
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
