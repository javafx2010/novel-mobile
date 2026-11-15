package com.wildenglish.app.audiobook.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wildenglish.app.audiobook.AudioBookUiState
import com.wildenglish.app.audiobook.AudioBookViewModel
import com.wildenglish.app.audiobook.data.MarketItem
import com.wildenglish.app.ui.theme.GlassIconButton
import java.util.Locale

/**
 * 图书市场：浏览服务器上可下载的书，点封面把 EPUB 下载到本地并加入书架。
 * 没有购买/付费，下载后直接走本地导入流程。后台地址写死在 [MarketApi] 中，不可配置。
 */
@Composable
fun MarketScreen(state: AudioBookUiState, vm: AudioBookViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val dark = isSystemInDarkTheme()
            GlassIconButton(onClick = vm::goBack, dark = dark) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "返回",
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp),
            ) {
                Text(
                    "图书市场",
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    if (state.marketTotalElements > 0) {
                        "共 ${state.marketTotalElements} 本 · 点击封面下载到本地"
                    } else {
                        "从服务器下载图书到本地"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            GlassIconButton(onClick = vm::refreshMarket, dark = dark) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "刷新",
                )
            }
        }

        if (state.marketCategories.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CategoryChip(
                    label = "全部",
                    selected = state.selectedMarketCategoryId == null,
                    onClick = { vm.selectMarketCategory(null) },
                )
                state.marketCategories.forEach { category ->
                    CategoryChip(
                        label = category.name,
                        selected = state.selectedMarketCategoryId == category.id,
                        onClick = { vm.selectMarketCategory(category.id) },
                    )
                }
            }
        }

        when {
            state.marketLoading && state.marketItems.isEmpty() ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) { CenteredLoading() }

            state.marketItems.isEmpty() ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    val selected = state.marketCategories
                        .firstOrNull { it.id == state.selectedMarketCategoryId }
                    if (selected != null) {
                        CenteredMessage(
                            title = "「${selected.name}」暂无可下载图书",
                            hint = "该分类下的书还没有可下载的 EPUB 文件。",
                            actionLabel = "查看全部",
                            onAction = { vm.selectMarketCategory(null) },
                        )
                    } else {
                        CenteredMessage(
                            title = "市场暂无图书",
                            hint = "服务器上还没有可下载的图书，稍后再试或点刷新。",
                            actionLabel = "刷新",
                            onAction = vm::refreshMarket,
                        )
                    }
                }

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 8.dp,
                    bottom = 24.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                items(state.marketItems, key = { it.id }) { item ->
                    MarketTile(
                        item = item,
                        inLibrary = state.books.any { it.title == item.title },
                        downloading = state.downloadingMarketId == item.id,
                        onDownload = { vm.downloadMarketBook(item) },
                    )
                }
                if (state.marketHasMore) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            LaunchedEffect(state.marketItems.size) { vm.loadMoreMarket() }
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 13.sp,
        maxLines = 1,
        color = if (selected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun MarketTile(
    item: MarketItem,
    inLibrary: Boolean,
    downloading: Boolean,
    onDownload: () -> Unit,
) {
    Column(
        Modifier.clickable(enabled = !inLibrary && !downloading, onClick = onDownload),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val cover = rememberRemoteCover(item.coverUrl)
            if (cover != null) {
                Image(
                    bitmap = cover,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    item.title.firstOrNull()?.toString() ?: "书",
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 30.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (downloading) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            item.title,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            item.author ?: "${item.chapterCount} 章",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val status = when {
            inLibrary -> "已在书架"
            downloading -> "下载中…"
            item.fileSize != null -> formatBytes(item.fileSize) + " · 点击下载"
            else -> "点击下载"
        }
        Text(
            status,
            fontSize = 11.sp,
            color = if (inLibrary) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
