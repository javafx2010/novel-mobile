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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wildenglish.app.audiobook.data.MarketApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private val coverCache = LruCache<String, ImageBitmap>(24)

private suspend fun loadBitmap(key: String, file: File): ImageBitmap? = withContext(Dispatchers.IO) {
    if (!file.isFile) return@withContext null
    val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@withContext null
    val image = bitmap.asImageBitmap()
    coverCache.put(key, image)
    image
}

/** Loads (and caches) a cover bitmap from a local file. */
@Composable
fun rememberCover(file: File?): ImageBitmap? {
    val key = file?.let { it.absolutePath + ":" + it.lastModified() }
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

private val remoteCoverCache = LruCache<String, ImageBitmap>(32)

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
            Text(
                text = title.firstOrNull()?.toString() ?: "书",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
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
