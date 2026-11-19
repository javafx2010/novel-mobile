@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wildenglish.app.audiobook.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.ui.res.stringResource
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
import com.wildenglish.app.audiobook.playback.AmplitudeMeter
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
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

/** Disc spin: baseline degrees/sec plus an amplitude-driven boost (voice waveform). */
private const val SPIN_BASE_DPS = 10f
private const val SPIN_GAIN_DPS = 38f

private val GRAMO_SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
private val GRAMO_SLEEP = listOf(
    0 to R.string.close,
    15 to R.string.minutes_15,
    30 to R.string.minutes_30,
    60 to R.string.minutes_60,
)

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
            title = stringResource(R.string.player_empty),
            hint = stringResource(R.string.player_empty_hint),
            actionLabel = stringResource(R.string.back_to_library),
            onAction = vm::goBack,
        )
        return
    }

    val audioMode = state.audioMode
    var showSpeed by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }

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

    // Raw playback progress still drives the tonearm sweep; no visible progress bar.
    val progress = if (audioMode) {
        if (state.durationMs > 0) {
            (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
        } else {
            0f
        }
    } else {
        val last = (state.sentences.size - 1).coerceAtLeast(1)
        state.currentSentenceIndex.coerceAtLeast(0).toFloat() / last
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
                    progress = progress,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            GramophoneControls(
                isPlaying = state.isPlaying,
                isBuffering = state.isBuffering,
                skipLabel = if (audioMode) "15" else "",
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
                BrassChip(label = stringResource(R.string.speed_value, "${state.speed}")) {
                    showSpeed = true
                }
                BrassChip(
                    label = if (state.sleepRemainingSeconds > 0) {
                        stringResource(
                            R.string.timer_value,
                            formatTime(state.sleepRemainingSeconds * 1000L),
                        )
                    } else {
                        stringResource(R.string.timer)
                    },
                ) { showSleep = true }
            }
            Spacer(Modifier.height(10.dp))
        }
    }

    if (showSpeed) {
        AlertDialog(
            onDismissRequest = { showSpeed = false },
            title = { Text(stringResource(R.string.speed)) },
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
            title = { Text(stringResource(R.string.sleep_timer)) },
            text = {
                Column {
                    GRAMO_SLEEP.forEach { (minutes, label) ->
                        TextButton(onClick = {
                            vm.setSleepTimer(minutes)
                            showSleep = false
                        }) { Text(stringResource(label)) }
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
                contentDescription = stringResource(R.string.collapse),
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
    progress: Float,
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
                // Spin speed tracks the voice amplitude: slow and steady in quiet
                // passages, faster on loud ones. Read in the frame loop (no recompose).
                val amp = AmplitudeMeter.level.value
                val degreesPerSecond = SPIN_BASE_DPS + amp * SPIN_GAIN_DPS
                angle.value = (angle.value + seconds * degreesPerSecond) % 360f
                last = now
            }
        }
    }

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // With the progress bar and status row gone, let the record fill the width.
        val discSize = minOf(maxWidth, maxHeight * 0.99f)
        val discRadiusPx = with(LocalDensity.current) { discSize.toPx() } / 2f
        VinylAura(
            playing = playing,
            discRadius = discRadiusPx,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.size(discSize)) {
            VinylRecord(size = discSize, bitmap = bitmap, angle = angle, playing = playing)
            TonearmLayer(
                size = discSize,
                playing = playing,
                progress = progress,
                rotation = angle,
            )
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
private fun TonearmLayer(
    size: Dp,
    playing: Boolean,
    progress: Float,
    rotation: State<Float>,
) {
    // The stylus rides an arc of fixed arm length. Sweep it across the groove band only:
    // -37° sits at the outer edge (~0.96 of the disc radius), -8° stops just outside the
    // 0.40R label. The old 0°→+20° range started mid-disc and dug into the label.
    // Paused, the arm lifts off the record entirely.
    val targetSwing = if (playing) {
        -37f + progress.coerceIn(0f, 1f) * 29f
    } else {
        -42f
    }
    val swing by animateFloatAsState(
        targetValue = targetSwing,
        animationSpec = tween(durationMillis = 420, easing = LinearEasing),
        label = "tonearmSwing",
    )
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val pivot = Offset(w * 0.93f, h * 0.13f)
        val center = Offset(w * 0.5f, h * 0.5f)

        // Resolve the rotated geometry by hand so the exact stylus point is available
        // for the groove trail (Compose rotate() is a clockwise screen-space rotation).
        fun spin(p: Offset, degrees: Float): Offset {
            val rad = Math.toRadians(degrees.toDouble())
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            val dx = p.x - pivot.x
            val dy = p.y - pivot.y
            return Offset(pivot.x + dx * c - dy * s, pivot.y + dx * s + dy * c)
        }

        val baseHead = Offset(w * 0.76f, h * 0.44f)
        val head = spin(baseHead, swing)
        val tail = spin(Offset(pivot.x + w * 0.05f, pivot.y - h * 0.05f), swing)
        val tip = spin(Offset(baseHead.x - w * 0.02f, baseHead.y + h * 0.03f), swing)

        // Groove trail: discrete marks the stylus paints onto the record. Each mark is
        // carried clockwise by the spinning disc and fades out after half a revolution;
        // new ones appear at the stylus, so the tail visibly rotates with the record.
        // Reading rotation.value here redraws every frame and freezes the trail on pause.
        if (playing) {
            val radius = hypot(tip.x - center.x, tip.y - center.y)
            val startAngle = atan2(tip.y - center.y, tip.x - center.x)
            val halfTurn = PI.toFloat()
            // 0→1 every 180° of disc rotation; seamless because the marks are evenly spaced.
            val phase = (((rotation.value % 180f) + 180f) % 180f) / 180f
            // Live audio amplitude (0..1) scales each dash's angular length: the trail
            // grows and shrinks with the voice waveform. Sampled off the main ticker;
            // this draw block already redraws every frame via rotation.value.
            val amplitude = AmplitudeMeter.level.value
            val marks = 40
            val seg = halfTurn / marks
            for (i in 0 until marks) {
                // Every dash drifts clockwise with the record: phase shifts all ages
                // continuously, so the tail visibly slides and wraps at the half turn.
                val age = ((i.toFloat() / marks) + phase) % 1f
                val a0 = startAngle + age * halfTurn
                // Keep a solid minimum length so quiet passages still read as dashes,
                // not dots; loud ones grow toward a full slot.
                val a1 = a0 + seg * (0.55f + 0.4f * amplitude)
                drawLine(
                    color = BrassLight.copy(alpha = 0.17f * (1f - age) * (1f - age)),
                    start = Offset(center.x + cos(a0) * radius, center.y + sin(a0) * radius),
                    end = Offset(center.x + cos(a1) * radius, center.y + sin(a1) * radius),
                    strokeWidth = 1.3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }

        // Pivot base.
        drawCircle(Color(0xFF241C15), radius = 17.dp.toPx(), center = pivot)
        drawCircle(
            color = BrassDark,
            radius = 17.dp.toPx(),
            center = pivot,
            style = Stroke(width = 2.dp.toPx()),
        )
        drawCircle(Brass, radius = 5.dp.toPx(), center = pivot)

        // Counterweight behind the pivot.
        drawLine(
            color = BrassDark,
            start = pivot,
            end = tail,
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
            end = tip,
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
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
                    contentDescription = stringResource(R.string.previous_chapter),
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
                    contentDescription = stringResource(R.string.next_chapter),
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
        if (label.isNotEmpty()) {
            Text(
                label,
                fontSize = 10.sp,
                letterSpacing = 0.5.sp,
                color = Cream.copy(alpha = 0.7f),
            )
        }
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
                contentDescription = stringResource(
                    if (isPlaying) R.string.pause else R.string.play
                ),
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
