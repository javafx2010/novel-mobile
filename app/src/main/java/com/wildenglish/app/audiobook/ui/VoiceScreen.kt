package com.wildenglish.app.audiobook.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wildenglish.app.R
import com.wildenglish.app.audiobook.AudioBookUiState
import com.wildenglish.app.audiobook.AudioBookViewModel
import com.wildenglish.app.audiobook.data.VoiceConfig
import com.wildenglish.app.audiobook.data.VoiceGender
import com.wildenglish.app.audiobook.data.VoiceLanguage

/** 「朗读音色」下一级页面：整本书统一使用的音色列表，选中即生效。 */
@Composable
fun VoiceScreen(state: AudioBookUiState, vm: AudioBookViewModel) {
    SettingsScaffold(
        title = stringResource(R.string.voice_title),
        subtitle = stringResource(R.string.voice_all_books_hint),
        onBack = vm::goBack,
    ) {
        item {
            SettingsSection(
                title = stringResource(R.string.voice_available),
                hint = stringResource(R.string.voice_hint),
            )
        }
        items(VoiceConfig.OPTIONS, key = { it.name }) { voice ->
            VoiceIconRow(
                voice = voice,
                selected = state.voice.name == voice.name,
                onClick = { vm.setVoice(voice.name) },
            )
        }
    }
}

/** One voice: a generated avatar, a national-flag language badge, a gender glyph and a radio. */
@Composable
private fun VoiceIconRow(
    voice: VoiceConfig,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (selected) Color(0xFF2A2016).copy(alpha = 0.95f)
                else Color(0xFF1C1611).copy(alpha = 0.9f)
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = SetBrass.copy(alpha = if (selected) 0.85f else 0.2f),
                shape = RoundedCornerShape(20.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Portrait avatar.
        Box(
            Modifier
                .size(58.dp)
                .clip(CircleShape)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) SetBrassLight else SetBrass.copy(alpha = 0.5f),
                    shape = CircleShape,
                ),
        ) {
            Image(
                painter = painterResource(voice.avatarRes),
                contentDescription = null,
                modifier = Modifier.size(58.dp),
            )
            // National flag badge pinned to the lower-right of the avatar.
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF0E0B08))
                    .border(1.5.dp, SetBrassLight, CircleShape)
                    .padding(2.5.dp),
            ) {
                Canvas(Modifier.size(17.dp)) {
                    drawFlag(voice.language)
                }
            }
        }

        Spacer(Modifier.width(16.dp))

        // Gender glyph instead of the written name.
        Text(
            text = if (voice.gender == VoiceGender.FEMALE) "♀" else "♂",
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) SetBrassLight else SetBrass.copy(alpha = 0.75f),
        )

        Spacer(Modifier.weight(1f))

        // Radio selector.
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .border(2.dp, SetBrass, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    Modifier
                        .size(11.dp)
                        .clip(CircleShape)
                        .background(SetBrassLight)
                )
            }
        }
    }
}

/** Precisely drawn national flags (AI-generated flags are almost always wrong). */
private fun DrawScope.drawFlag(language: VoiceLanguage) {
    val w = size.width
    val h = size.height
    when (language) {
        VoiceLanguage.CHINESE -> {
            drawRect(Color(0xFFDE2910), size = androidx.compose.ui.geometry.Size(w, h))
            val cx = w * 0.27f
            val cy = h * 0.28f
            drawStar(Offset(cx, cy), w * 0.16f, Color(0xFFFFDE00))
            val r = w * 0.16f
            drawStar(Offset(cx + r * 0.95f, cy + r * 0.28f), r * 0.42f, Color(0xFFFFDE00))
            drawStar(Offset(cx + r * 1.15f, cy + r * 0.85f), r * 0.42f, Color(0xFFFFDE00))
            drawStar(Offset(cx + r * 0.72f, cy + r * 1.25f), r * 0.42f, Color(0xFFFFDE00))
            drawStar(Offset(cx + r * 0.28f, cy + r * 1.12f), r * 0.42f, Color(0xFFFFDE00))
        }

        VoiceLanguage.ENGLISH -> {
            drawRect(Color.White, size = androidx.compose.ui.geometry.Size(w, h))
            val stripe = h / 13f
            for (i in 0 until 13 step 2) {
                drawRect(
                    Color(0xFFB22234),
                    topLeft = Offset(0f, i * stripe),
                    size = androidx.compose.ui.geometry.Size(w, stripe)
                )
            }
            val cw = w * 0.4f
            val ch = stripe * 7
            drawRect(Color(0xFF3C3B6E), size = androidx.compose.ui.geometry.Size(cw, ch))
            for (row in 0 until 4) {
                for (col in 0 until 5) {
                    val x = cw * (0.1f + col * 0.2f)
                    val y = ch * (0.12f + row * 0.26f)
                    drawCircle(Color.White, radius = w * 0.012f, center = Offset(x, y))
                }
            }
        }
    }
}

/** A filled five-point star centred at [c]. */
private fun DrawScope.drawStar(c: Offset, radius: Float, color: Color) {
    val path = androidx.compose.ui.graphics.Path()
    val points = 5
    for (i in 0 until points * 2) {
        val ang = -Math.PI / 2 + i * Math.PI / points
        val r = if (i % 2 == 0) radius else radius * 0.4f
        val x = c.x + Math.cos(ang).toFloat() * r
        val y = c.y + Math.sin(ang).toFloat() * r
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color)
}
