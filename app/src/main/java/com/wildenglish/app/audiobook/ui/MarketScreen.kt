package com.wildenglish.app.audiobook.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wildenglish.app.R
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
                    contentDescription = stringResource(R.string.back),
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp),
            ) {
                Text(
                    stringResource(R.string.market_title),
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    if (state.marketTotalElements > 0) {
                        stringResource(
                            R.string.market_total,
                            state.marketTotalElements,
                        )
                    } else {
                        stringResource(R.string.market_hint)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            GlassIconButton(onClick = vm::refreshMarket, dark = dark) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.market_refresh),
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
                    label = stringResource(R.string.all),
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
                            title = stringResource(
                                R.string.market_category_empty,
                                selected.name,
                            ),
                            hint = stringResource(R.string.market_category_hint),
                            actionLabel = stringResource(R.string.market_view_all),
                            onAction = { vm.selectMarketCategory(null) },
                        )
                    } else {
                        CenteredMessage(
                            title = stringResource(R.string.market_empty_title),
                            hint = stringResource(R.string.market_empty_hint),
                            actionLabel = stringResource(R.string.market_refresh),
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
            // 默认封面垫底，远程封面加载好后淡入覆盖。
            val coverAlpha by animateFloatAsState(
                targetValue = if (cover != null) 1f else 0f,
                animationSpec = tween(durationMillis = 260),
                label = "marketCoverFadeIn",
            )
            Image(
                painter = painterResource(R.drawable.cover_default),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (cover != null) {
                Image(
                    bitmap = cover,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(coverAlpha),
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
            item.author ?: stringResource(R.string.chapter_count, item.chapterCount),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val status = when {
            inLibrary -> stringResource(R.string.market_in_shelf)
            downloading -> stringResource(R.string.market_downloading)
            item.fileSize != null -> formatBytes(item.fileSize) +
                    stringResource(R.string.market_download_suffix)

            else -> stringResource(R.string.market_download)
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
