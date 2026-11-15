@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wildenglish.app.audiobook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.wildenglish.app.audiobook.AudioBookUiState
import com.wildenglish.app.audiobook.AudioBookViewModel
import com.wildenglish.app.audiobook.data.RssArticle
import com.wildenglish.app.audiobook.data.RssFeed
import com.wildenglish.app.audiobook.data.RssFeeds
import com.wildenglish.app.ui.theme.GlassCardShape
import com.wildenglish.app.ui.theme.GlassIconButton
import com.wildenglish.app.ui.theme.glass
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 订阅：常用 RSS 一键订阅 + 已订阅源的文章列表，点文章直接用 Edge TTS 朗读。
 */
@Composable
fun RssScreen(state: AudioBookUiState, vm: AudioBookViewModel) {
    val dark = isSystemInDarkTheme()
    val feed = state.rssFeed

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
                    feed?.title ?: "订阅",
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (feed == null) "关注 RSS，文章可直接朗读" else "点文章开始朗读",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (feed != null) {
                GlassIconButton(onClick = vm::refreshRssFeed, dark = dark) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                }
            }
        }

        if (feed == null) {
            FeedOverview(state, vm, dark)
        } else {
            ArticleList(state, vm, dark)
        }
    }

    // 抓取全文时给个明确反馈（RSS 正文往往只有摘要）。
    if (state.rssPreparing) {
        Dialog(onDismissRequest = {}) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 28.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                Spacer(Modifier.height(12.dp))
                Text(
                    "正在获取全文…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.FeedOverview(
    state: AudioBookUiState,
    vm: AudioBookViewModel,
    dark: Boolean
) {
    val subscribed = RssFeeds.DEFAULTS.filter { it.id in state.rssSubscriptions }
    val others = RssFeeds.DEFAULTS.filter { it.id !in state.rssSubscriptions }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SectionTitle("我的订阅", "已订阅 ${subscribed.size} 个源，点开源查看最新文章") }

        if (subscribed.isEmpty()) {
            item {
                Text(
                    "还没有订阅。从下面的「常用 RSS」里挑几个，订阅后就能在这里看到最新文章。",
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(subscribed, key = { "sub-${it.id}" }) { feed ->
                FeedRow(
                    feed = feed,
                    subscribed = true,
                    dark = dark,
                    onOpen = { vm.openRssFeed(feed) },
                    onToggle = { vm.toggleRssSubscription(feed) },
                )
            }
        }

        item { SectionTitle("常用 RSS", "常见中英文源，一键订阅后可直接朗读文章") }
        items(others, key = { "all-${it.id}" }) { feed ->
            FeedRow(
                feed = feed,
                subscribed = false,
                dark = dark,
                onOpen = { vm.openRssFeed(feed) },
                onToggle = { vm.toggleRssSubscription(feed) },
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String, hint: String) {
    Column(Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FeedRow(
    feed: RssFeed,
    subscribed: Boolean,
    dark: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glass(shape = GlassCardShape, dark = dark)
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                feed.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${feed.category} · ${feed.url.substringAfter("//")}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            if (subscribed) "已订阅" else "＋ 订阅",
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            fontSize = 13.sp,
            color = if (subscribed) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ColumnScope.ArticleList(
    state: AudioBookUiState,
    vm: AudioBookViewModel,
    dark: Boolean
) {
    when {
        state.rssLoading && state.rssArticles.isEmpty() ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { CenteredLoading() }

        state.rssArticles.isEmpty() ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CenteredMessage(
                    title = "暂无文章",
                    hint = "这个源还没有返回内容，或网络不可用。",
                    actionLabel = "刷新",
                    onAction = vm::refreshRssFeed,
                )
            }

        else -> LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.rssArticles, key = { it.id }) { article ->
                ArticleRow(article, dark) { vm.readArticle(article) }
            }
        }
    }
}

@Composable
private fun ArticleRow(article: RssArticle, dark: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .glass(shape = RoundedCornerShape(18.dp), dark = dark)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            article.title,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (article.summary.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                article.summary,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                article.feedTitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                formatDate(article.publishedAt),
                modifier = Modifier.padding(start = 8.dp),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClick) { Text("朗读", fontSize = 13.sp) }
        }
    }
}

private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun formatDate(epochMillis: Long?): String {
    if (epochMillis == null || epochMillis <= 0L) return ""
    return runCatching {
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(dateFormatter)
    }.getOrDefault("")
}
