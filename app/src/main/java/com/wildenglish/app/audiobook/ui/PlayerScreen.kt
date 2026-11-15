@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wildenglish.app.audiobook.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wildenglish.app.audiobook.AudioBookUiState
import com.wildenglish.app.audiobook.AudioBookViewModel
import com.wildenglish.app.ui.theme.GlassBarShape
import com.wildenglish.app.ui.theme.GlassIconButton
import com.wildenglish.app.ui.theme.GlassPanel
import com.wildenglish.app.ui.theme.GlassTextButton
import com.wildenglish.app.ui.theme.liquidBlur
import kotlin.math.roundToInt

private val SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
private val SLEEP_OPTIONS = listOf(0 to "关闭", 15 to "15 分钟", 30 to "30 分钟", 60 to "60 分钟")

/** Audiobook view: cover, title and transport controls (the "有声书" presentation). */
@Composable
fun PlayerScreen(state: AudioBookUiState, vm: AudioBookViewModel) {
    val book = state.currentBook
    val chapter = state.currentChapter
    if (book == null || chapter == null) {
        CenteredMessage(
            title = "还没有正在朗读的内容",
            hint = "从书架里选一本书开始收听。",
            actionLabel = "返回书架",
            onAction = vm::goBack,
        )
        return
    }

    var showSpeed by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }

    val lastIndex = (state.sentences.size - 1).coerceAtLeast(1)
    var sliderValue by remember(state.sentences.size) { mutableStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    LaunchedEffect(state.currentSentenceIndex, state.sentences.size) {
        if (!isDragging) {
            sliderValue = state.currentSentenceIndex.coerceAtLeast(0).toFloat() / lastIndex
        }
    }

    val cover = rememberCover(book.coverFile)
    val coverScale = remember { Animatable(0.88f) }
    LaunchedEffect(Unit) {
        coverScale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        )
    }

    Box(Modifier
        .fillMaxSize()
        .background(Brush.verticalGradient(listOf(Color(0xFF1A2230), Color.Black)))
    ) {
        if (cover != null) {
            Image(
                bitmap = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = 1.18f; scaleY = 1.18f }
                    .liquidBlur(48.dp),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.20f), Color.Black.copy(alpha = 0.62f))
                        )
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 8.dp),
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
                    GlassIconButton(onClick = vm::goBack, dark = true) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "收起")
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "正在朗读",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.82f)
                    )
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.size(40.dp))
                }
            }

            Spacer(Modifier.height(20.dp))
            BookCover(
                coverFile = book.coverFile,
                title = book.title,
                modifier = Modifier
                    .width(240.dp)
                    .aspectRatio(0.72f)
                    .graphicsLayer {
                        scaleX = coverScale.value
                        scaleY = coverScale.value
                    },
            )
            Spacer(Modifier.height(28.dp))
            Text(
                book.title,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                chapter.title,
                fontSize = 15.sp,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )

            val currentSentence = state.sentences.getOrNull(state.currentSentenceIndex)
            val waterTint by animateColorAsState(
                targetValue = state.readerTheme.flowTint(onDark = true),
                animationSpec = tween(420),
                label = "playerWaterTint",
            )
            if (!currentSentence.isNullOrBlank()) {
                Spacer(Modifier.height(18.dp))
                GlassPanel(
                    shape = GlassBarShape,
                    dark = true,
                    modifier = Modifier.fillMaxWidth(),
                    elevation = 10.dp,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        WaterWipeSentence(
                            text = currentSentence,
                            index = state.currentSentenceIndex,
                            tint = waterTint,
                            color = Color.White,
                            fontFamily = FontFamily.Serif,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            lineHeight = 26.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(GlassBarShape),
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Slider(
                value = sliderValue,
                onValueChange = {
                    isDragging = true
                    sliderValue = it
                },
                onValueChangeFinished = {
                    isDragging = false
                    vm.seekToSentence((sliderValue * lastIndex).roundToInt())
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp),
                thumb = {
                    Box(
                        Modifier
                            .size(12.dp)
                            .background(Color.White, CircleShape),
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
                    "第 ${(state.currentSentenceIndex + 1).coerceAtLeast(1)} / ${state.sentences.size} 句",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.6f),
                )
                Text(
                    if (state.isBuffering) "正在合成语音…" else "",
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
                IconButton(onClick = vm::previousChapter) {
                    Icon(
                        Icons.Filled.SkipPrevious,
                        contentDescription = "上一章",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
                TextButton(
                    onClick = { vm.skipSentences(-1) },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                ) { Text("上一句", fontSize = 13.sp) }
                FilledIconButton(
                    onClick = vm::togglePlayPause,
                    modifier = Modifier.size(68.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black,
                    ),
                ) {
                    if (state.isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = Color.Black,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (state.isPlaying) "暂停" else "播放",
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }
                TextButton(
                    onClick = { vm.skipSentences(1) },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                ) { Text("下一句", fontSize = 13.sp) }
                IconButton(onClick = vm::nextChapter) {
                    Icon(
                        Icons.Filled.SkipNext,
                        contentDescription = "下一章",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                GlassTextButton(
                    label = "语速 ${state.speed}×",
                    onClick = { showSpeed = true },
                    dark = true
                )
                GlassTextButton(
                    label = if (state.sleepRemainingSeconds > 0) {
                        "定时 ${formatTime(state.sleepRemainingSeconds * 1000L)}"
                    } else {
                        "定时"
                    },
                    onClick = { showSleep = true },
                    dark = true,
                )
                GlassTextButton(label = "文稿", onClick = vm::showDocument, dark = true)
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showSpeed) {
        AlertDialog(
            onDismissRequest = { showSpeed = false },
            title = { Text("朗读速度") },
            text = {
                Column {
                    SPEEDS.forEach { speed ->
                        TextButton(onClick = {
                            vm.setSpeed(speed)
                            showSpeed = false
                        }) { Text("${speed}×") }
                    }
                }
            },
            confirmButton = {},
        )
    }

    if (showSleep) {
        AlertDialog(
            onDismissRequest = { showSleep = false },
            title = { Text("睡眠定时") },
            text = {
                Column {
                    SLEEP_OPTIONS.forEach { (minutes, label) ->
                        TextButton(onClick = {
                            vm.setSleepTimer(minutes)
                            showSleep = false
                        }) { Text(label) }
                    }
                }
            },
            confirmButton = {},
        )
    }
}

@Composable
internal fun PlayerAction(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
    ) {
        Text(label, fontSize = 14.sp)
    }
}
