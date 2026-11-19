package com.wildenglish.app.audiobook.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.wildenglish.app.R

internal val SetTop = Color(0xFF14110E)
internal val SetBottom = Color(0xFF070605)
internal val SetBrassLight = Color(0xFFF0D8A8)
internal val SetBrass = Color(0xFFC79A56)
internal val SetBrassDark = Color(0xFF6E5128)
internal val SetCream = Color(0xFFF4E9D4)
internal val SetInk = Color(0xFF17120C)

/**
 * Shared scaffold for the settings family (设置/音色/存储/播放页).
 * Same dark gramophone palette, light status-bar icons and brass back button.
 */
@Composable
internal fun SettingsScaffold(
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = context.settingsActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val lightStatus = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        onDispose {
            if (lightStatus != null) controller.isAppearanceLightStatusBars = lightStatus
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(SetTop, SetBottom)))
    ) {
        SettingsBackdrop(Modifier.fillMaxSize())

        Column(Modifier
            .fillMaxSize()
            .statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1D1711).copy(alpha = 0.92f))
                        .border(1.dp, SetBrass.copy(alpha = 0.45f), CircleShape)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.back),
                        tint = SetBrassLight,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Column(Modifier
                    .padding(horizontal = 12.dp)
                    .weight(1f)) {
                    Text(
                        title,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp,
                        color = SetCream,
                    )
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = SetBrass.copy(alpha = 0.78f),
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 32.dp
                ),
                content = content,
            )
        }
    }
}

@Composable
private fun SettingsBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val glow = Offset(w * 0.88f, h * 0.03f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(SetBrass.copy(alpha = 0.12f), Color.Transparent),
                center = glow,
                radius = w * 0.9f,
            ),
            radius = w * 0.9f,
            center = glow,
        )
        val bell = Offset(w * 1.04f, h * 0.02f)
        for (i in 0..4) {
            val r = w * 0.26f + i * w * 0.06f
            drawArc(
                color = SetBrass.copy(alpha = 0.09f - i * 0.014f),
                startAngle = 100f,
                sweepAngle = 170f,
                useCenter = false,
                topLeft = Offset(bell.x - r, bell.y - r),
                size = Size(r * 2, r * 2),
                style = Stroke(width = 1.2.dp.toPx()),
            )
        }
    }
}

@Composable
internal fun SettingsSection(title: String, hint: String? = null) {
    Column(Modifier
        .fillMaxWidth()
        .padding(start = 6.dp, end = 6.dp, top = 18.dp, bottom = 6.dp)) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = SetCream)
        if (hint != null) {
            Text(
                hint,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = SetBrass.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
internal fun SettingsDivider() {
    Spacer(Modifier.height(10.dp))
    Box(Modifier
        .fillMaxWidth()
        .height(1.dp)
        .background(SetBrass.copy(alpha = 0.16f)))
    Spacer(Modifier.height(4.dp))
}

/** A tappable settings entry with a value on the right. */
@Composable
internal fun SettingsEntryRow(
    title: String,
    value: String,
    hint: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF1C1611).copy(alpha = 0.9f))
            .border(1.dp, SetBrass.copy(alpha = 0.22f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 15.sp,
                color = SetCream,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!hint.isNullOrBlank()) {
                Text(
                    hint,
                    fontSize = 12.sp,
                    color = SetBrass.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        // Cap the value at its share of the row; a long localized label must not squeeze
        // the title column down to a single character (which wraps text vertically).
        Text(
            value,
            fontSize = 13.sp,
            color = SetBrassLight.copy(alpha = 0.9f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(end = 6.dp),
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = SetBrass,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Like [SettingsEntryRow] but shows a circular trailing icon instead of value text. */
@Composable
internal fun SettingsEntryIconRow(
    title: String,
    hint: String? = null,
    @DrawableRes iconRes: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF1C1611).copy(alpha = 0.9f))
            .border(1.dp, SetBrass.copy(alpha = 0.22f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 15.sp,
                color = SetCream,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!hint.isNullOrBlank()) {
                Text(
                    hint,
                    fontSize = 12.sp,
                    color = SetBrass.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .border(1.5.dp, SetBrass, CircleShape),
        ) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(44.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = SetBrass,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** A single-choice row (radio) for voice / storage options. */
@Composable
internal fun SettingsRadioRow(
    label: String,
    hint: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (selected) Color(0xFF2A2016).copy(alpha = 0.95f)
                else Color(0xFF1C1611).copy(alpha = 0.9f)
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = SetBrass.copy(alpha = if (selected) 0.85f else 0.2f),
                shape = RoundedCornerShape(18.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .border(2.dp, SetBrass, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(SetBrassLight))
            }
        }
        Column(Modifier.padding(start = 14.dp)) {
            Text(label, fontSize = 15.sp, color = SetCream)
            if (hint.isNotBlank()) {
                Text(
                    hint,
                    fontSize = 12.sp,
                    color = SetBrass.copy(alpha = 0.65f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/** A switch row for boolean settings. */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    hint: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF1C1611).copy(alpha = 0.9f))
            .border(1.dp, SetBrass.copy(alpha = 0.22f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = SetCream)
            Text(
                hint,
                fontSize = 12.sp,
                color = SetBrass.copy(alpha = 0.65f),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
internal fun SettingsInfoCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF1C1611).copy(alpha = 0.9f))
            .border(1.dp, SetBrass.copy(alpha = 0.22f), RoundedCornerShape(18.dp))
            .padding(16.dp),
        content = content,
    )
}

private tailrec fun Context.settingsActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.settingsActivity()
    else -> null
}
