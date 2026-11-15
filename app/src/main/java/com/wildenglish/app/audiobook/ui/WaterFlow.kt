package com.wildenglish.app.audiobook.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.wildenglish.app.audiobook.ReaderTheme
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import androidx.compose.ui.graphics.lerp as lerpColor

/** Soft ease-in-out: starts gently and settles without a snap. */
internal val WaterEase = CubicBezierEasing(0.33f, 0.05f, 0.14f, 1f)
private val TwoPi = (PI * 2.0).toFloat()
private const val TravelDurationMs = 820
private const val WipeDurationMs = 780

/** Palette derived only from a skin tint — no fixed cyan overlay. */
internal data class WaterColors(
    val fill: Color,
    val deep: Color,
    val sheen: Color,
    val foam: Color,
)

/**
 * Turns a reading-skin highlight into a liquid tint.
 *
 * [onDark] lifts a dark highlight (夜间) just enough to stay visible on a
 * dark surface, while keeping the same hue family as the skin.
 */
internal fun ReaderTheme.flowTint(onDark: Boolean = false): Color {
    val lum = highlight.luminance()
    return if (onDark && lum < 0.35f) {
        lerpColor(highlight, Color(0xFFFFF1D4), 0.48f)
    } else {
        highlight
    }
}

internal fun Color.asWaterColors(): WaterColors {
    val lum = luminance()
    val fill = if (lum < 0.22f) lerpColor(this, Color.White, 0.38f) else this
    return WaterColors(
        fill = fill,
        deep = lerpColor(fill, Color.Black, 0.16f),
        sheen = lerpColor(fill, Color.White, 0.52f),
        foam = lerpColor(fill, Color.White, 0.72f),
    )
}

/** Shared travel of the water highlight from one sentence to the next. */
class SentenceWaterState {
    val layouts = mutableStateMapOf<Int, Rect>()
    var fromIndex by mutableIntStateOf(-1)
    var toIndex by mutableIntStateOf(-1)
    val progress = Animatable(1f)
}

@Composable
fun rememberSentenceWaterState(): SentenceWaterState = remember { SentenceWaterState() }

@Composable
fun SentenceWaterState.animateTo(index: Int) {
    LaunchedEffect(index) {
        val previous = toIndex
        fromIndex = previous
        toIndex = index
        if (previous >= 0 && previous != index) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(durationMillis = TravelDurationMs, easing = WaterEase))
        } else {
            progress.snapTo(1f)
        }
    }
}

class LayoutCoordRef {
    var value: LayoutCoordinates? = null
}

fun Modifier.reportSentenceLayout(
    index: Int,
    state: SentenceWaterState,
    parentRef: LayoutCoordRef,
): Modifier = this.onGloballyPositioned { coords ->
    val origin = parentRef.value
    if (origin == null || !origin.isAttached || !coords.isAttached) return@onGloballyPositioned
    val parentPos = origin.positionInRoot()
    val childPos = coords.positionInRoot()
    val topLeft = childPos - parentPos
    state.layouts[index] = Rect(
        topLeft,
        Size(coords.size.width.toFloat(), coords.size.height.toFloat()),
    )
}

@Composable
fun Modifier.clearSentenceLayout(index: Int, state: SentenceWaterState): Modifier {
    DisposableEffect(index) {
        onDispose { state.layouts.remove(index) }
    }
    return this
}

/** Draw the traveling water slug in the list's coordinate space, after children. */
fun Modifier.drawTravelingWater(
    state: SentenceWaterState,
    tint: Color,
    progress: Float,
): Modifier = this.drawWithContent {
    drawContent()
    val p = progress
    val from = state.layouts[state.fromIndex]
    val to = state.layouts[state.toIndex]
    if (from == null && to == null) return@drawWithContent
    if (from != null && to != null && state.fromIndex != state.toIndex && p < 1f) {
        val slug = travelingSlug(from, to, p)
        val gap = if (to.top >= from.bottom - 1f) {
            Rect(min(from.left, to.left), from.bottom, max(from.right, to.right), to.top)
        } else if (from.top >= to.bottom - 1f) {
            Rect(min(from.left, to.left), to.bottom, max(from.right, to.right), from.top)
        } else {
            Rect.Zero
        }
        if (gap.height > 0.5f) {
            clipPath(Path().apply { addRect(gap.expanded(2f)) }) {
                drawWaterSlug(slug, tint, p)
            }
        }
    }
}

/**
 * Water mass flows from [from] to [to]: the leading edge arrives first, the
 * trailing edge follows, so the slug stretches through the gap like a stream
 * instead of briefly becoming the bounding box of both lines.
 */
fun travelingSlug(from: Rect, to: Rect, progress: Float): Rect {
    val t = progress.coerceIn(0f, 1f)
    val lead = t
    val trail = ((t - 0.14f) / 0.86f).coerceIn(0f, 1f)
    val downward = to.center.y >= from.center.y
    val top = lerpFloat(from.top, to.top, if (downward) trail else lead)
    val bottom = lerpFloat(from.bottom, to.bottom, if (downward) lead else trail)
    val left = lerpFloat(from.left, to.left, t)
    val right = lerpFloat(from.right, to.right, t)
    val flare = sin(t * PI.toFloat()) * 5f
    return Rect(
        left - flare,
        min(top, bottom),
        right + flare,
        max(top, bottom),
    )
}

fun DrawScope.drawWaterSlug(rect: Rect, tint: Color, progress: Float) {
    if (rect.width <= 0f || rect.height <= 0f) return
    val colors = tint.asWaterColors()
    val settle = if (progress < 0.86f) 1f else (1f - (progress - 0.86f) / 0.14f).coerceIn(0.4f, 1f)
    val amp = min(9f, rect.height * 0.14f)
    val wavy = wavyCapsule(rect, amp, phase = progress * TwoPi * 1.35f)
    val fill = Brush.verticalGradient(
        colors = listOf(
            colors.foam.copy(alpha = 0.34f * settle),
            colors.fill.copy(alpha = 0.48f * settle),
            colors.deep.copy(alpha = 0.30f * settle),
        ),
        startY = rect.top,
        endY = rect.bottom,
    )
    drawPath(wavy, fill)
    drawPath(
        wavy,
        color = colors.sheen.copy(alpha = 0.38f * settle),
        style = Stroke(width = 2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    if (progress in 0.06f..0.94f) {
        val leadY = lerpFloat(rect.top, rect.bottom, 0.5f + 0.18f * sin(progress * TwoPi))
        drawCircle(
            colors.foam.copy(alpha = 0.32f * settle),
            radius = min(7f, rect.height * 0.14f),
            center = Offset(rect.center.x + sin(progress * PI.toFloat()) * 8f, leadY),
        )
    }
}

/**
 * Text that wipes from the previous sentence to the next with a wavy water front.
 * Forward (higher index) flows downward; going back flows upward.
 */
@Composable
fun WaterWipeSentence(
    text: String,
    index: Int,
    tint: Color,
    color: Color,
    fontFamily: FontFamily,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    textAlign: TextAlign,
    lineHeight: TextUnit,
    modifier: Modifier = Modifier,
) {
    var outgoing by remember { mutableStateOf(text) }
    var incoming by remember { mutableStateOf(text) }
    var lastIndex by remember { mutableIntStateOf(index) }
    var forward by remember { mutableStateOf(true) }
    val progress = remember { Animatable(1f) }

    LaunchedEffect(index, text) {
        if (text != incoming || index != lastIndex) {
            outgoing = incoming
            incoming = text
            forward = index >= lastIndex
            lastIndex = index
            if (outgoing != incoming) {
                progress.snapTo(0f)
                progress.animateTo(1f, tween(durationMillis = WipeDurationMs, easing = WaterEase))
            } else {
                progress.snapTo(1f)
            }
        }
    }

    val p = progress.value
    val transitioning = p < 0.999f && outgoing != incoming

    Box(
        modifier
            .fillMaxWidth()
            .drawWithContent {
                drawContent()
                if (transitioning) {
                    drawWipeFront(p, forward, tint)
                }
            },
    ) {
        if (transitioning) {
            Text(
                outgoing,
                color = color,
                fontFamily = fontFamily,
                fontSize = fontSize,
                fontWeight = fontWeight,
                textAlign = textAlign,
                lineHeight = lineHeight,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp)
                    .clipToWave(p, forward, keepIncomingSide = false),
            )
        }
        Text(
            incoming,
            color = color,
            fontFamily = fontFamily,
            fontSize = fontSize,
            fontWeight = fontWeight,
            textAlign = textAlign,
            lineHeight = lineHeight,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp)
                .then(
                    if (transitioning) Modifier.clipToWave(p, forward, keepIncomingSide = true)
                    else Modifier
                ),
        )
    }
}

private fun Modifier.clipToWave(
    progress: Float,
    forward: Boolean,
    keepIncomingSide: Boolean,
): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        clipPath(waveClipPath(size, progress, forward, keepIncomingSide)) {
            this@drawWithContent.drawContent()
        }
    }

private fun waveClipPath(
    size: Size,
    progress: Float,
    forward: Boolean,
    keepIncomingSide: Boolean,
): Path {
    val amp = (size.height * 0.08f).coerceAtLeast(5f).coerceAtMost(12f)
    val front = if (forward) {
        size.height * progress
    } else {
        size.height * (1f - progress)
    }
    val steps = 40
    fun edgeY(x: Float): Float {
        val nx = if (size.width == 0f) 0f else x / size.width
        return front +
                sin(nx * 2f * TwoPi + progress * TwoPi * 1.4f) * amp * 0.7f +
                sin(nx * 3.6f * TwoPi + progress * TwoPi * 1.9f) * amp * 0.3f
    }
    return Path().apply {
        if (keepIncomingSide == forward) {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            addSmoothEdge(size.width, steps, reverse = true, yAt = ::edgeY)
            close()
        } else {
            moveTo(0f, size.height)
            lineTo(size.width, size.height)
            addSmoothEdge(size.width, steps, reverse = true, yAt = ::edgeY)
            close()
        }
    }
}

private fun DrawScope.drawWipeFront(progress: Float, forward: Boolean, tint: Color) {
    val colors = tint.asWaterColors()
    val amp = (size.height * 0.08f).coerceAtLeast(5f).coerceAtMost(12f)
    val front = if (forward) size.height * progress else size.height * (1f - progress)
    val steps = 40
    fun edgeY(x: Float): Float {
        val nx = if (size.width == 0f) 0f else x / size.width
        return front +
                sin(nx * 2f * TwoPi + progress * TwoPi * 1.4f) * amp * 0.7f +
                sin(nx * 3.6f * TwoPi + progress * TwoPi * 1.9f) * amp * 0.3f
    }
    val ridge = Path().apply {
        addSmoothEdge(size.width, steps, reverse = false, yAt = ::edgeY, startNew = true)
    }
    drawPath(
        ridge,
        color = colors.fill.copy(alpha = 0.42f),
        style = Stroke(width = 8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawPath(
        ridge,
        color = colors.sheen.copy(alpha = 0.62f),
        style = Stroke(width = 2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    val midX = size.width * 0.5f
    drawCircle(colors.foam.copy(alpha = 0.40f), 6f, Offset(midX, edgeY(midX)))
    drawCircle(colors.sheen.copy(alpha = 0.28f), 4f, Offset(midX + 18f, edgeY(midX + 18f) + 4f))
}

/** Behind-the-text highlight for a line that is part of the traveling slug. */
fun Modifier.sentenceWaterBehind(
    index: Int,
    state: SentenceWaterState,
    tint: Color,
): Modifier = this.drawWithContent {
    val p = state.progress.value
    val from = state.layouts[state.fromIndex]
    val to = state.layouts[state.toIndex]
    val mine = state.layouts[index]
    if (mine != null && from != null && to != null && p < 1f &&
        (index == state.fromIndex || index == state.toIndex)
    ) {
        val slug = travelingSlug(from, to, p)
        val local = Rect(
            slug.left - mine.left,
            slug.top - mine.top,
            slug.right - mine.left,
            slug.bottom - mine.top,
        )
        val bounds = Rect(0f, 0f, size.width, size.height)
        val hit = local.intersect(bounds)
        if (!hit.isEmpty) {
            drawWaterSlug(hit.expanded(1f), tint, p)
        }
    } else if (index == state.toIndex && (p >= 1f || from == null)) {
        val colors = tint.asWaterColors()
        drawRect(
            brush = Brush.verticalGradient(
                listOf(
                    Color.Transparent,
                    colors.fill.copy(alpha = 0.22f),
                    colors.sheen.copy(alpha = 0.10f),
                ),
            ),
        )
    }
    drawContent()
}

private fun wavyCapsule(rect: Rect, amp: Float, phase: Float): Path {
    val steps = 40
    fun yTop(x: Float): Float {
        val nx = if (rect.width == 0f) 0f else (x - rect.left) / rect.width
        return rect.top +
                sin(nx * 2f * TwoPi + phase) * amp * 0.7f +
                sin(nx * 3.5f * TwoPi + phase * 1.25f) * amp * 0.3f
    }

    fun yBottom(x: Float): Float {
        val nx = if (rect.width == 0f) 0f else (x - rect.left) / rect.width
        return rect.bottom +
                sin(nx * 2f * TwoPi + phase + 1.15f) * amp * 0.7f +
                sin(nx * 3.5f * TwoPi + phase * 1.25f + 0.7f) * amp * 0.3f
    }
    return Path().apply {
        addSmoothEdge(rect.left, rect.width, steps, reverse = false, yAt = ::yTop, startNew = true)
        addSmoothEdge(
            rect.left,
            rect.width,
            steps,
            reverse = true,
            yAt = ::yBottom,
            startNew = false
        )
        close()
    }
}

/** Cubic-smoothed polyline along a horizontal edge, so the wave isn't faceted. */
private fun Path.addSmoothEdge(
    left: Float,
    width: Float,
    steps: Int,
    reverse: Boolean,
    yAt: (Float) -> Float,
    startNew: Boolean = false,
) {
    val indices = if (reverse) steps downTo 0 else 0..steps
    var prevX = 0f
    var prevY = 0f
    var started = false
    for (i in indices) {
        val x = left + width * i / steps
        val y = yAt(x)
        if (!started) {
            if (startNew) moveTo(x, y) else lineTo(x, y)
            prevX = x
            prevY = y
            started = true
        } else {
            val midX = (prevX + x) * 0.5f
            cubicTo(midX, prevY, midX, y, x, y)
            prevX = x
            prevY = y
        }
    }
}

private fun Path.addSmoothEdge(
    width: Float,
    steps: Int,
    reverse: Boolean,
    yAt: (Float) -> Float,
    startNew: Boolean = false,
) = addSmoothEdge(0f, width, steps, reverse, yAt, startNew)

private fun lerpFloat(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private fun Rect.expanded(delta: Float) =
    Rect(left - delta, top - delta, right + delta, bottom + delta)
