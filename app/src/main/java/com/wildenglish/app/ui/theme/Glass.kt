package com.wildenglish.app.ui.theme

import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

val GlassCardShape = RoundedCornerShape(24.dp)
val GlassBarShape = RoundedCornerShape(28.dp)
val GlassCapsuleShape = RoundedCornerShape(50)

/**
 * Soft, slowly drifting color fields that sit behind every glass surface,
 * approximating the luminous wallpaper of iOS 26/27 Liquid Glass.
 */
@Composable
fun LiquidGlassBackdrop(
    modifier: Modifier = Modifier,
    dark: Boolean = false,
) {
    val motion = rememberInfiniteTransition(label = "glassBackdrop")
    val phase by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 22_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "glassPhase",
    )
    val canvas = if (dark) Color(0xFF0B1018) else Color(0xFFE6EDF6)
    Box(
        modifier
            .background(canvas)
            .drawWithCache {
                val w = size.width
                val h = size.height
                val t = phase
                val orbs = if (dark) {
                    listOf(
                        Triple(
                            Color(0xFF1A3A6B),
                            Offset(w * (0.18f + 0.10f * kotlin.math.sin(t * 6.28f)), h * 0.22f),
                            min(w, h) * 0.72f
                        ),
                        Triple(
                            Color(0xFF3A2070),
                            Offset(w * (0.86f - 0.08f * kotlin.math.cos(t * 6.28f)), h * 0.18f),
                            min(w, h) * 0.64f
                        ),
                        Triple(
                            Color(0xFF0E4A4A),
                            Offset(
                                w * 0.55f,
                                h * (0.78f + 0.06f * kotlin.math.sin(t * 6.28f + 1.2f))
                            ),
                            min(w, h) * 0.80f
                        ),
                        Triple(Color(0xFF1C2A4A), Offset(w * 0.10f, h * 0.85f), min(w, h) * 0.50f),
                    )
                } else {
                    listOf(
                        Triple(
                            Color(0xFFB9D4F7),
                            Offset(w * (0.16f + 0.10f * kotlin.math.sin(t * 6.28f)), h * 0.18f),
                            min(w, h) * 0.78f
                        ),
                        Triple(
                            Color(0xFFD5C4F5),
                            Offset(w * (0.88f - 0.08f * kotlin.math.cos(t * 6.28f)), h * 0.16f),
                            min(w, h) * 0.70f
                        ),
                        Triple(
                            Color(0xFFB8E8E0),
                            Offset(
                                w * 0.58f,
                                h * (0.82f + 0.06f * kotlin.math.sin(t * 6.28f + 1.2f))
                            ),
                            min(w, h) * 0.86f
                        ),
                        Triple(Color(0xFFF3D4C8), Offset(w * 0.08f, h * 0.78f), min(w, h) * 0.55f),
                    )
                }
                onDrawBehind {
                    orbs.forEach { (color, center, radius) ->
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    color.copy(alpha = if (dark) 0.70f else 0.85f),
                                    Color.Transparent
                                ),
                                center = center,
                                radius = radius,
                            ),
                            radius = radius,
                            center = center,
                        )
                    }
                }
            },
    )
}

/**
 * Applies a Liquid Glass material: translucent fill, specular rim light, and a soft drop shadow.
 */
fun Modifier.glass(
    shape: Shape = GlassCardShape,
    dark: Boolean = false,
    tint: Color = Color.Unspecified,
    elevation: Dp = 16.dp,
): Modifier {
    val fill = when {
        tint != Color.Unspecified -> tint
        dark -> Color.White.copy(alpha = 0.10f)
        else -> Color.White.copy(alpha = 0.46f)
    }
    val topShine = if (dark) Color.White.copy(alpha = 0.42f) else Color.White.copy(alpha = 0.86f)
    val bottomShade = if (dark) Color.Black.copy(alpha = 0.45f) else Color.Black.copy(alpha = 0.10f)
    return this
        .shadow(
            elevation = elevation,
            shape = shape,
            ambientColor = Color.Black.copy(alpha = if (dark) 0.40f else 0.10f),
            spotColor = Color.Black.copy(alpha = if (dark) 0.55f else 0.16f),
        )
        .clip(shape)
        .drawWithCache {
            val stroke = Stroke(width = 1.2.dp.toPx())
            val radius = when (shape) {
                is RoundedCornerShape -> {
                    val px = shape.topStart.toPx(size, this)
                    CornerRadius(px, px)
                }

                else -> CornerRadius(size.minDimension * 0.22f)
            }
            onDrawBehind {
                drawRoundRect(color = fill, cornerRadius = radius)
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (dark) 0.16f else 0.28f),
                            Color.Transparent,
                        ),
                        start = Offset.Zero,
                        end = Offset(size.width * 0.15f, size.height * 0.85f),
                    ),
                    cornerRadius = radius,
                )
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(topShine, Color.White.copy(alpha = 0.12f), bottomShade),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height),
                    ),
                    cornerRadius = radius,
                    style = stroke,
                )
            }
        }
}

@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = GlassCardShape,
    dark: Boolean = false,
    tint: Color = Color.Unspecified,
    elevation: Dp = 16.dp,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.glass(shape = shape, dark = dark, tint = tint, elevation = elevation),
        contentAlignment = contentAlignment,
        content = content,
    )
}

@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean = false,
    content: @Composable () -> Unit,
) {
    val contentColor = if (dark) Color.White else MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .size(40.dp)
            .glass(shape = CircleShape, dark = dark, elevation = 8.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, color = contentColor),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}

@Composable
fun GlassTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean = false,
    tint: Color = Color.Unspecified,
) {
    val contentColor = if (dark) Color.White else MaterialTheme.colorScheme.onBackground
    Box(
        modifier = modifier
            .glass(shape = GlassCapsuleShape, dark = dark, tint = tint, elevation = 8.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, color = contentColor),
                onClick = onClick,
            )
            .defaultMinSize(minHeight = 36.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = contentColor, fontSize = 14.sp)
    }
}

@Composable
fun GlassPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .glass(
                shape = RoundedCornerShape(18.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.88f),
                elevation = 12.dp,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, color = Color.White),
                onClick = onClick,
            )
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                CompositionLocalProvider(LocalContentColor provides Color.White) { leading() }
                androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
            }
            Text(label, color = Color.White, fontSize = 16.sp)
        }
    }
}

/** Softly blurred, slightly scaled layer used behind player chrome. */
fun Modifier.liquidBlur(radius: Dp): Modifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) this.blur(radius) else this.graphicsLayer {
        alpha = 0.92f
    }
