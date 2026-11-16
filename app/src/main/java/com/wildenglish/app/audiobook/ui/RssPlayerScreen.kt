@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wildenglish.app.audiobook.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wildenglish.app.R
import com.wildenglish.app.audiobook.AudioBookUiState
import com.wildenglish.app.audiobook.AudioBookViewModel
import com.wildenglish.app.ui.theme.GlassBarShape
import com.wildenglish.app.ui.theme.GlassIconButton
import com.wildenglish.app.ui.theme.GlassPanel
import com.wildenglish.app.ui.theme.GlassTextButton
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val RSS_SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

/**
 * 订阅文章的播放页 —— 版式沿用有声书播放页（悬浮条 / 中间标题 / 当前句 / 进度 / 控制条），
 * 但用的是 [com.wildenglish.app.audiobook.playback.RssPlayerCenter]，与图书播放互不影响：
 * 不进迷你播放器、不写书籍进度、不影响正在听的电子书。
 */
@Composable
fun RssPlayerScreen(state: AudioBookUiState, vm: AudioBookViewModel) {
    val player = state.rssPlayer
    val article = player.article
    if (article == null) {
        CenteredMessage(
            title = "没有正在朗读的文章",
            hint = "回到订阅列表选一篇开始收听。",
            actionLabel = "返回订阅",
            onAction = vm::closeRssPlayer,
        )
        return
    }

    var showSpeed by remember { mutableStateOf(false) }
    val lastIndex = (player.sentences.size - 1).coerceAtLeast(1)
    var sliderValue by remember(player.sentences.size) { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(player.index, player.sentences.size) {
        if (!dragging) sliderValue = player.index.coerceAtLeast(0).toFloat() / lastIndex
    }

    val waterTint by animateColorAsState(
        targetValue = state.readerTheme.flowTint(onDark = true),
        animationSpec = tween(420),
        label = "rssWaterTint",
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1A2230), Color.Black)))
    ) {
        // 文章没有封面：用默认封面做背景，保持与图书播放页一致的观感。
        Image(
            painter = painterResource(R.drawable.cover_default),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.6f),
                            Color.Black.copy(alpha = 0.74f),
                            Color.Black.copy(alpha = 0.92f),
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GlassPanel(
                shape = GlassBarShape,
                dark = true,
                modifier = Modifier.fillMaxWidth(),
                elevation = 8.dp,
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassIconButton(onClick = vm::closeRssPlayer, dark = true) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "收起")
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        article.feedTitle,
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.86f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.size(40.dp))
                }
            }

            Spacer(Modifier.height(14.dp))

            // 中间区域：文章标题（没有封面，用文字撑起视觉重心）。
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        article.title,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        lineHeight = 30.sp,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        listOfNotNull(article.feedTitle, rssDate(article.publishedAt))
                            .joinToString(" · "),
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
            }

            if (state.showSentence) {
                GlassPanel(
                    shape = GlassBarShape,
                    dark = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 88.dp, max = 132.dp),
                    elevation = 10.dp,
                ) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (player.currentSentence.isNotBlank()) {
                            WaterWipeSentence(
                                text = player.currentSentence,
                                index = player.index,
                                tint = waterTint,
                                color = Color.White,
                                fontFamily = FontFamily.Serif,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                lineHeight = 26.sp,
                                maxLines = 2,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(GlassBarShape),
                            )
                        } else {
                            Text(
                                "准备中…",
                                fontSize = 15.sp,
                                color = Color.White.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            Slider(
                value = sliderValue,
                onValueChange = {
                    dragging = true
                    sliderValue = it
                },
                onValueChangeFinished = {
                    dragging = false
                    vm.rssSeek((sliderValue * lastIndex).roundToInt())
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp),
                thumb = {
                    Box(
                        Modifier
                            .size(12.dp)
                            .background(Color.White, CircleShape)
                    )
                },
                track = { sliderState ->
                    val range = sliderState.valueRange
                    val fraction = ((sliderState.value - range.start) /
                            (range.endInclusive - range.start)).coerceIn(0f, 1f)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(2.dp)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(fraction)
                                .height(4.dp)
                                .background(Color.White, RoundedCornerShape(2.dp)),
                        )
                    }
                },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "第 ${(player.index + 1).coerceAtLeast(1)} / ${player.sentences.size} 句",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.6f),
                )
                Text(
                    if (player.isBuffering) "正在合成语音…" else "",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }

            Spacer(Modifier.height(8.dp))
            GlassPanel(
                shape = GlassBarShape,
                dark = true,
                modifier = Modifier.fillMaxWidth(),
                elevation = 12.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = vm::rssPrevious) {
                        Icon(
                            Icons.Filled.SkipPrevious,
                            contentDescription = "上一句",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    FilledIconButton(
                        onClick = vm::rssTogglePlayPause,
                        modifier = Modifier.size(68.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black,
                        ),
                    ) {
                        if (player.isBuffering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = Color.Black,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = if (player.isPlaying) Icons.Filled.Pause
                                else Icons.Filled.PlayArrow,
                                contentDescription = if (player.isPlaying) "暂停" else "播放",
                                modifier = Modifier.size(34.dp),
                            )
                        }
                    }
                    IconButton(onClick = vm::rssNext) {
                        Icon(
                            Icons.Filled.SkipNext,
                            contentDescription = "下一句",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                GlassTextButton(
                    label = "语速 ${player.speed}×",
                    onClick = { showSpeed = true },
                    dark = true,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showSpeed) {
        AlertDialog(
            onDismissRequest = { showSpeed = false },
            title = { Text("朗读速度") },
            text = {
                Column {
                    RSS_SPEEDS.forEach { speed ->
                        TextButton(onClick = {
                            vm.rssSetSpeed(speed)
                            showSpeed = false
                        }) { Text("${speed}×") }
                    }
                }
            },
            confirmButton = {},
        )
    }
}

private val rssDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun rssDate(epochMillis: Long?): String {
    if (epochMillis == null || epochMillis <= 0L) return ""
    return runCatching {
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(rssDateFormatter)
    }.getOrDefault("")
}
