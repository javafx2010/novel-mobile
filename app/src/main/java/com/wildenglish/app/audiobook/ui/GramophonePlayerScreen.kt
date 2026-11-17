@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wildenglish.app.audiobook.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.wildenglish.app.R
import com.wildenglish.app.audiobook.AudioBookUiState
import com.wildenglish.app.audiobook.AudioBookViewModel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val GramoTop = Color(0xFF14110E)
private val GramoBottom = Color(0xFF070605)
private val BrassLight = Color(0xFFF0D8A8)
private val Brass = Color(0xFFC79A56)
private val BrassDark = Color(0xFF6E5128)
private val Cream = Color(0xFFF4E9D4)
private val GramoInk = Color(0xFF17120C)
private val PlinthTop = Color(0xFF2B221A)
private val PlinthBottom = Color(0xFF16100B)

private val GRAMO_SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
private val GRAMO_SLEEP = listOf(0 to "关闭", 15 to "15 分钟", 30 to "30 分钟", 60 to "60 分钟")

/**
 * The one player for every book, MP3 and TTS alike: a gramophone-inspired layout —
 * a large spinning vinyl record with the cover as its label (glow, sound waves, light
 * sweep), a brass tonearm that drops onto the record while playing, and a wooden plinth.
 *
 * No subtitle plate and no 文稿 mode: all free space belongs to the record. The progress
 * bar adapts to the playback mode — elapsed time for MP3 files, sentence index for TTS.
 */
@Composable
fun GramophonePlayerScreen(state: AudioBookUiState, vm: AudioBookViewModel) {
    val book = state.currentBook
    val chapter = state.currentChapter
    if (book == null || chapter == null) {
        CenteredMessage(
            title = "还没有正在播放的内容",
            hint = "从书架里选一本书开始收听。",
            actionLabel = "返回书架",
            onAction = vm::goBack,
        )
        return
    }

    val audioMode = state.audioMode
    val sentenceLast = (state.sentences.size - 1).coerceAtLeast(1)
    var showSpeed by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var sliderValue by remember(chapter.index, audioMode) { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    // The gramophone is a dark screen; switch the system bar icons to light while shown.
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = context.gramophoneActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val lightStatus = controller?.isAppearanceLightStatusBars
        val lightNav = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            if (lightStatus != null) controller.isAppearanceLightStatusBars = lightStatus
            if (lightNav != null) controller.isAppearanceLightNavigationBars = lightNav
        }
    }

    LaunchedEffect(
        state.positionMs,
        state.durationMs,
        state.currentSentenceIndex,
        state.sentences.size,
        audioMode,
        dragging,
    ) {
        if (!dragging) {
            sliderValue = if (audioMode) {
                if (state.durationMs > 0) {
                    (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
                } else {
                    0f
                }
            } else {
                state.currentSentenceIndex.coerceAtLeast(0).toFloat() / sentenceLast
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(GramoTop, GramoBottom)))
    ) {
        GramophoneBackdrop(Modifier.fillMaxSize())

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(6.dp))
            ChapterPlaque(title = chapter.title, onBack = vm::goBack)
            Spacer(Modifier.height(6.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                GramophoneHero(
                    cover = book.coverFile,
                    playing = state.isPlaying,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Spacer(Modifier.height(4.dp))

            GramophoneSlider(
                value = sliderValue,
                onValueChange = {
                    dragging = true
                    sliderValue = it
                },
                onValueChangeFinished = {
                    dragging = false
                    if (audioMode) {
                        vm.seekToPosition((sliderValue * state.durationMs).toLong())
                    } else {
                        vm.seekToSentence((sliderValue * sentenceLast).roundToInt())
                    }
                },
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (audioMode) {
                    Text(
                        formatTime(state.positionMs),
                        fontSize = 12.sp,
                        letterSpacing = 1.sp,
                        color = Cream.copy(alpha = 0.66f),
                    )
                    Text(
                        if (state.durationMs > 0) formatTime(state.durationMs) else "--:--",
                        fontSize = 12.sp,
                        letterSpacing = 1.sp,
                        color = Cream.copy(alpha = 0.66f),
                    )
                } else {
                    Text(
                        "第 ${(state.currentSentenceIndex + 1).coerceAtLeast(1)} / ${state.sentences.size} 句",
                        fontSize = 12.sp,
                        letterSpacing = 1.sp,
                        color = Cream.copy(alpha = 0.66f),
                    )
                    Text(
                        if (state.isBuffering) "正在合成语音…" else "",
                        fontSize = 12.sp,
                        letterSpacing = 1.sp,
                        color = Cream.copy(alpha = 0.66f),
                    )
                }
            }

            GramophoneControls(
                isPlaying = state.isPlaying,
                isBuffering = state.isBuffering,
                skipLabel = if (audioMode) "15" else "句",
                onPreviousChapter = vm::previousChapter,
                onNextChapter = vm::nextChapter,
                onRewind = { if (audioMode) vm.seekBy(-15_000) else vm.skipSentences(-1) },
                onForward = { if (audioMode) vm.seekBy(15_000) else vm.skipSentences(1) },
                onToggle = vm::togglePlayPause,
            )
            Spacer(Modifier.height(12.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrassChip(label = "语速 ${state.speed}×") { showSpeed = true }
                BrassChip(
                    label = if (state.sleepRemainingSeconds > 0) {
                        "定时 ${formatTime(state.sleepRemainingSeconds * 1000L)}"
                    } else {
                        "定时"
                    },
                ) { showSleep = true }
            }
            Spacer(Modifier.height(10.dp))
        }
    }

    if (showSpeed) {
        AlertDialog(
            onDismissRequest = { showSpeed = false },
            title = { Text("播放速度") },
            text = {
                Column {
                    GRAMO_SPEEDS.forEach { speed ->
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
                    GRAMO_SLEEP.forEach { (minutes, label) ->
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

/** Brass sound-wave arcs, a stylized horn and a warm glow behind the record. */
@Composable
private fun GramophoneBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val glowCenter = Offset(w * 0.18f, h * 0.10f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Brass.copy(alpha = 0.20f), Color.Transparent),
                center = glowCenter,
                radius = w * 0.95f,
            ),
            radius = w * 0.95f,
            center = glowCenter,
        )

        // Horn bell: nested arcs opening downwards from the top-left corner.
        val bell = Offset(w * 0.10f, h * 0.02f)
        for (i in 0..5) {
            val radius = w * 0.20f + i * w * 0.05f
            drawArc(
                color = Brass.copy(alpha = 0.13f - i * 0.018f),
                startAngle = 5f,
                sweepAngle = 170f,
                useCenter = false,
                topLeft = Offset(bell.x - radius, bell.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = 1.2.dp.toPx()),
            )
        }

        // Horn tube curling towards the record.
        val tube = Path().apply {
            moveTo(w * 0.16f, h * 0.13f)
            cubicTo(w * 0.22f, h * 0.28f, w * 0.06f, h * 0.40f, w * 0.10f, h * 0.56f)
        }
        drawPath(
            path = tube,
            color = Brass.copy(alpha = 0.12f),
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
        )

        // Faint staff lines at the bottom keep the composition from looking empty.
        for (i in 0..3) {
            val y = h * 0.86f + i * 9.dp.toPx()
            drawLine(
                color = Cream.copy(alpha = 0.03f),
                start = Offset(w * 0.08f, y),
                end = Offset(w * 0.92f, y),
                strokeWidth = 1.dp.toPx(),
            )
        }
    }
}

@Composable
private fun ChapterPlaque(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(Color(0xFF1D1711).copy(alpha = 0.9f))
            .border(1.dp, Brass.copy(alpha = 0.24f), RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = "收起",
                tint = Cream.copy(alpha = 0.9f),
            )
        }
        Text(
            title,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp),
            fontFamily = FontFamily.Serif,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = Cream,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Symmetry spacer matching the back button.
        Spacer(Modifier.width(40.dp))
    }
}

/**
 * The hero: a spinning vinyl record whose label is the book cover, plus a brass tonearm
 * that swings in while playing and lifts away when paused.
 */
@Composable
private fun GramophoneHero(
    cover: java.io.File?,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val bitmap = rememberCover(cover)
    // Kept as State and read inside graphicsLayer so the record spins without
    // recomposing the whole player on every frame.
    val angle = remember { mutableStateOf(0f) }

    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val seconds = (now - last) / 1_000_000_000f
                angle.value = (angle.value + seconds * 33f) % 360f
                last = now
            }
        }
    }

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // The plate is gone: let the record use almost the whole free area.
        val discSize = minOf(maxWidth * 0.94f, maxHeight * 0.99f)
        val discRadiusPx = with(LocalDensity.current) { discSize.toPx() } / 2f
        VinylAura(
            playing = playing,
            discRadius = discRadiusPx,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.size(discSize)) {
            VinylRecord(size = discSize, bitmap = bitmap, angle = angle, playing = playing)
            TonearmLayer(size = discSize, playing = playing)
        }
    }
}

/**
 * Effects around the record: a warm halo behind the vinyl and, while playing,
 * sound-wave rings radiating out of the disc edge. The animated part only exists
 * while playing, so pausing stops all effect redraws.
 */
@Composable
private fun VinylAura(playing: Boolean, discRadius: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Brass.copy(alpha = 0.12f),
                    BrassDark.copy(alpha = 0.06f),
                    Color.Transparent,
                ),
                center = center,
                radius = discRadius * 1.6f,
            ),
            radius = discRadius * 1.6f,
            center = center,
        )
    }
    if (playing) {
        SoundWaves(discRadius = discRadius, modifier = modifier)
    }
}

@Composable
private fun SoundWaves(discRadius: Float, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "soundWaves")
    val wave by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween<Float>(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wavePhase",
    )
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        for (i in 0..2) {
            val p = (wave + i / 3f) % 1f
            drawCircle(
                color = BrassLight.copy(alpha = 0.24f * (1f - p)),
                radius = discRadius * (1.01f + p * 0.28f),
                center = center,
                style = Stroke(width = 1.6.dp.toPx()),
            )
        }
    }
}

/**
 * A specular highlight drifting across the vinyl independently of the record's own
 * rotation, so the grooves appear to catch the light as they spin.
 */
@Composable
private fun VinylSheen(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "vinylSheen")
    val degrees by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween<Float>(durationMillis = 7000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sheenDegrees",
    )
    Canvas(modifier) {
        val center = this.center
        val radius = this.size.minDimension / 2f
        rotate(degrees = degrees, pivot = center) {
            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.11f),
                        Color.Transparent,
                    ),
                    center = center,
                ),
                startAngle = -28f,
                sweepAngle = 56f,
                useCenter = true,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2f, radius * 2f),
            )
        }
    }
}

@Composable
private fun VinylRecord(
    size: Dp,
    bitmap: androidx.compose.ui.graphics.ImageBitmap?,
    angle: State<Float>,
    playing: Boolean,
) {
    Box(
        Modifier
            .size(size)
            .shadow(18.dp, CircleShape)
            .graphicsLayer { rotationZ = angle.value }
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF33291F),
                        Color(0xFF1A1410),
                        Color(0xFF0A0806),
                    )
                )
            ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = this.center
            val radius = this.size.minDimension / 2f
            for (i in 1..20) {
                val r = radius * (0.40f + i * 0.028f)
                drawCircle(
                    color = if (i % 2 == 0) Cream.copy(alpha = 0.045f)
                    else Color.White.copy(alpha = 0.025f),
                    radius = r,
                    center = center,
                    style = Stroke(width = 0.8.dp.toPx()),
                )
            }
            // Brass rim.
            drawCircle(
                color = Brass.copy(alpha = 0.35f),
                radius = radius - 1.dp.toPx(),
                center = center,
                style = Stroke(width = 1.5.dp.toPx()),
            )
            // A bright glint travelling around the rim with the record.
            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        Color.Transparent,
                        BrassLight.copy(alpha = 0.6f),
                        Color.Transparent,
                    ),
                    center = center,
                ),
                startAngle = -22f,
                sweepAngle = 44f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round),
            )
            // Specks of light caught in the grooves.
            for (i in 0 until 16) {
                val theta = i * 137.5f * PI.toFloat() / 180f
                val r = radius * (0.45f + (i % 5) * 0.09f)
                val x = center.x + cos(theta) * r
                val y = center.y + sin(theta) * r
                drawCircle(
                    color = Cream.copy(alpha = 0.09f + (i % 3) * 0.045f),
                    radius = 1.1.dp.toPx(),
                    center = Offset(x, y),
                )
            }
        }
        // Independent moving reflection over the grooves (only while spinning).
        if (playing) {
            VinylSheen(Modifier.fillMaxSize())
        }

        Box(
            Modifier
                .size(size * 0.40f)
                .align(Alignment.Center)
                .clip(CircleShape)
                .border(1.dp, BrassLight.copy(alpha = 0.45f), CircleShape)
                .background(
                    Brush.linearGradient(listOf(BrassLight, Brass, BrassDark))
                ),
            contentAlignment = Alignment.Center,
        ) {
            // No embedded cover: show the app's default artwork as the record label.
            Image(
                painter = painterResource(R.drawable.cover_default),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Spindle hole.
        Box(
            Modifier
                .size(9.dp)
                .align(Alignment.Center)
                .background(Color(0xFF090706), CircleShape)
                .border(1.dp, Cream.copy(alpha = 0.22f), CircleShape),
        )
    }
}

/** Brass tonearm anchored at the top-right of the record. */
@Composable
private fun TonearmLayer(size: Dp, playing: Boolean) {
    val swing by animateFloatAsState(
        targetValue = if (playing) 0f else -11f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessLow),
        label = "tonearmSwing",
    )
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val pivot = Offset(w * 0.93f, h * 0.13f)

        // Pivot base.
        drawCircle(Color(0xFF241C15), radius = 17.dp.toPx(), center = pivot)
        drawCircle(
            color = BrassDark,
            radius = 17.dp.toPx(),
            center = pivot,
            style = Stroke(width = 2.dp.toPx()),
        )
        drawCircle(Brass, radius = 5.dp.toPx(), center = pivot)

        rotate(degrees = swing, pivot = pivot) {
            val head = Offset(w * 0.76f, h * 0.44f)
            // Counterweight behind the pivot.
            drawLine(
                color = BrassDark,
                start = pivot,
                end = Offset(pivot.x + w * 0.05f, pivot.y - h * 0.05f),
                strokeWidth = 7.dp.toPx(),
                cap = StrokeCap.Round,
            )
            // Arm.
            drawLine(
                brush = Brush.linearGradient(
                    colors = listOf(BrassLight, Brass, BrassDark),
                    start = pivot,
                    end = head,
                ),
                start = pivot,
                end = head,
                strokeWidth = 6.dp.toPx(),
                cap = StrokeCap.Round,
            )
            // Cartridge / stylus.
            drawCircle(BrassLight, radius = 7.dp.toPx(), center = head)
            drawLine(
                color = Cream.copy(alpha = 0.85f),
                start = head,
                end = Offset(head.x - w * 0.02f, head.y + h * 0.03f),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun GramophoneSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Slider(
        value = value.coerceIn(0f, 1f),
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        modifier = Modifier
            .fillMaxWidth()
            .height(20.dp),
        thumb = {
            Box(
                Modifier
                    .size(13.dp)
                    .background(Cream, CircleShape)
                    .border(2.dp, Brass, CircleShape)
            )
        },
        track = { sliderState ->
            val range = sliderState.valueRange
            val fraction = ((sliderState.value - range.start) /
                    (range.endInclusive - range.start)).coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(BrassDark.copy(alpha = 0.45f), RoundedCornerShape(2.dp)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(3.dp)
                        .background(
                            Brush.horizontalGradient(listOf(BrassDark, Brass, BrassLight)),
                            RoundedCornerShape(2.dp),
                        ),
                )
            }
        },
    )
}

/** Wooden plinth with brass transport controls. */
@Composable
private fun GramophoneControls(
    isPlaying: Boolean,
    isBuffering: Boolean,
    skipLabel: String,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onToggle: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(30.dp))
            .background(Brush.verticalGradient(listOf(PlinthTop, PlinthBottom)))
            .border(
                1.dp,
                Brush.verticalGradient(
                    listOf(
                        Brass.copy(alpha = 0.38f),
                        BrassDark.copy(alpha = 0.2f)
                    )
                ),
                RoundedCornerShape(30.dp),
            ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPreviousChapter) {
                Icon(
                    Icons.Filled.SkipPrevious,
                    contentDescription = "上一章",
                    tint = Brass,
                    modifier = Modifier.size(26.dp),
                )
            }
            SkipButton(icon = Icons.Filled.FastRewind, label = skipLabel, onClick = onRewind)
            PlayButton(isPlaying = isPlaying, isBuffering = isBuffering, onClick = onToggle)
            SkipButton(icon = Icons.Filled.FastForward, label = skipLabel, onClick = onForward)
            IconButton(onClick = onNextChapter) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = "下一章",
                    tint = Brass,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

@Composable
private fun SkipButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = Cream, modifier = Modifier.size(22.dp))
        Text(
            label,
            fontSize = 10.sp,
            letterSpacing = 0.5.sp,
            color = Cream.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun PlayButton(isPlaying: Boolean, isBuffering: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(70.dp)
            .shadow(14.dp, CircleShape)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFFFBF0D8), BrassLight, Brass)
                )
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isBuffering) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = GramoInk,
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "暂停" else "播放",
                tint = GramoInk,
                modifier = Modifier.size(34.dp),
            )
        }
    }
}

@Composable
private fun BrassChip(label: String, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xFF1D1711))
            .border(1.dp, Brass.copy(alpha = 0.32f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        fontSize = 13.sp,
        color = Cream.copy(alpha = 0.9f),
    )
}

private tailrec fun Context.gramophoneActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.gramophoneActivity()
    else -> null
}
