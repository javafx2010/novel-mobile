package com.wildenglish.app.audiobook.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wildenglish.app.audiobook.AudioBookUiState
import com.wildenglish.app.audiobook.AudioBookViewModel
import com.wildenglish.app.audiobook.ReaderTheme
import com.wildenglish.app.ui.theme.GlassBarShape
import com.wildenglish.app.ui.theme.GlassIconButton
import com.wildenglish.app.ui.theme.GlassPanel
import com.wildenglish.app.ui.theme.GlassTextButton

/**
 * 文稿模式：只显示文档正文。朗读推进到下一句时，水面高亮从上一句拉伸、流过句间空隙，
 * 再落到下一句上，并自动滚动到视线内。点任意一句可跳转朗读到该句。顶栏可切换阅读皮肤。
 */
@Composable
fun ReaderScreen(state: AudioBookUiState, vm: AudioBookViewModel) {
    val book = state.currentBook
    val chapter = state.currentChapter
    val theme = state.readerTheme
    val listState = rememberLazyListState()
    var showThemeMenu by remember { mutableStateOf(false) }

    val water = rememberSentenceWaterState()
    val listCoordRef = remember { LayoutCoordRef() }
    water.animateTo(state.currentSentenceIndex)
    val travelProgress = water.progress.value
    val waterTint by animateColorAsState(
        targetValue = theme.flowTint(),
        animationSpec = tween(420),
        label = "waterTint",
    )

    LaunchedEffect(state.currentSentenceIndex) {
        if (state.currentSentenceIndex >= 0) {
            val target = (state.currentSentenceIndex - 3).coerceAtLeast(0)
            listState.animateScrollToItem(target)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(theme.background)
            .statusBarsPadding(),
    ) {
        val darkChrome = theme == ReaderTheme.NIGHT || isSystemInDarkTheme()
        GlassPanel(
            shape = GlassBarShape,
            dark = darkChrome,
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .fillMaxWidth(),
        ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(onClick = vm::goBack, dark = darkChrome) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "返回播放器",
                )
            }
            Column(Modifier
                .weight(1f)
                .padding(horizontal = 10.dp)
            ) {
                Text(
                    chapter?.title ?: "文稿",
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = theme.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    book?.title ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                GlassTextButton(
                    label = "皮肤",
                    onClick = { showThemeMenu = true },
                    dark = darkChrome
                )
                DropdownMenu(
                    expanded = showThemeMenu,
                    onDismissRequest = { showThemeMenu = false },
                ) {
                    ReaderTheme.values().forEach { t ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier
                                            .size(16.dp)
                                            .clip(CircleShape)
                                            .background(t.background)
                                            .border(1.dp, t.divider, CircleShape),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        if (t == theme) "${t.label}  ✓" else t.label,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            },
                            onClick = {
                                vm.setReaderTheme(t)
                                showThemeMenu = false
                            },
                        )
                    }
                }
            }
            GlassTextButton(label = "封面", onClick = vm::showPlayer, dark = darkChrome)
        }
        }

        if (state.sentences.isEmpty()) {
            Box(Modifier
                .weight(1f)
                .fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("本章暂无文稿", color = theme.dim, fontFamily = FontFamily.Serif)
            }
        } else {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { listCoordRef.value = it }
                    .drawTravelingWater(water, waterTint, travelProgress),
            ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(state.sentences, key = { index, _ -> index }) { index, sentence ->
                    SentenceLine(
                        index = index,
                        text = sentence,
                        active = index == state.currentSentenceIndex,
                        theme = theme,
                        waterTint = waterTint,
                        water = water,
                        parentRef = listCoordRef,
                        onClick = { vm.seekToSentence(index) },
                    )
                }
            }
            }
        }

        GlassPanel(
            shape = GlassBarShape,
            dark = darkChrome,
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth(),
        ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassTextButton(label = "上一句", onClick = { vm.skipSentences(-1) }, dark = darkChrome)
            GlassIconButton(onClick = vm::togglePlayPause, dark = darkChrome) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) "暂停" else "播放",
                    modifier = Modifier.size(26.dp),
                )
            }
            GlassTextButton(label = "下一句", onClick = { vm.skipSentences(1) }, dark = darkChrome)
            Text(
                "第 ${(state.currentSentenceIndex + 1).coerceAtLeast(1)} / ${state.sentences.size} 句",
                fontSize = 12.sp,
                color = theme.dim,
            )
        }
        }
    }
}

@Composable
private fun SentenceLine(
    index: Int,
    text: String,
    active: Boolean,
    theme: ReaderTheme,
    waterTint: Color,
    water: SentenceWaterState,
    parentRef: LayoutCoordRef,
    onClick: () -> Unit,
) {
    val ink by animateColorAsState(
        targetValue = if (active) theme.ink else theme.ink.copy(alpha = 0.72f),
        animationSpec = tween(260),
        label = "sentenceInk",
    )
    val traveling = water.progress.value < 0.999f &&
            (index == water.fromIndex || index == water.toIndex)
    val base by animateColorAsState(
        targetValue = if (active && !traveling) theme.highlight.copy(alpha = 0.16f) else Color.Transparent,
        animationSpec = tween(320),
        label = "sentenceHighlight",
    )

    Text(
        text = text,
        color = ink,
        fontFamily = FontFamily.Serif,
        fontSize = if (active) 17.sp else 15.sp,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        lineHeight = 26.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clearSentenceLayout(index, water)
            .reportSentenceLayout(index, water, parentRef)
            .clip(RoundedCornerShape(10.dp))
            .background(base)
            .sentenceWaterBehind(index, water, waterTint)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}
