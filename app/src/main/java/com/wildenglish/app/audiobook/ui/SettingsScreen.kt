@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wildenglish.app.audiobook.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wildenglish.app.audiobook.AudioBookUiState
import com.wildenglish.app.audiobook.AudioBookViewModel
import com.wildenglish.app.ui.theme.GlassIconButton

/**
 * 设置：朗读音色 + 配音文件存储目录。
 *
 * 音色是全局的 —— 一本书从头到尾用同一个声音朗读，不区分角色；换音色后离线配音会按新音色
 * 重新生成。存储目录支持自动（SD 卡优先）、应用内部、主存储应用目录，以及用系统文件选择器
 * 指定的任意文件夹。
 */
@Composable
fun SettingsScreen(state: AudioBookUiState, vm: AudioBookViewModel) {
    val dark = isSystemInDarkTheme()

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
            Text(
                "设置",
                modifier = Modifier.padding(horizontal = 6.dp),
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                SectionTitle(
                    title = "朗读",
                    hint = "整本书使用同一个音色朗读，切换音色后会按新音色重新生成离线配音。",
                )
            }
            item {
                SettingsEntryRow(
                    title = "朗读音色",
                    value = state.voice.title,
                    hint = state.voice.name,
                    onClick = vm::openVoice,
                )
            }

            item {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                SectionTitle(
                    title = "配音存储",
                    hint = "朗读时生成的语音文件（MP3）保存在该目录。",
                )
            }
            item {
                SettingsEntryRow(
                    title = "存储位置",
                    value = storageModeLabel(state.storageMode),
                    hint = "语音文件保存目录",
                    onClick = vm::openStorage,
                )
            }

            item {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                SectionTitle(
                    title = "播放页",
                    hint = "控制播放页（有声书界面）显示的内容。",
                )
            }
            item {
                SettingsEntryRow(
                    title = "显示当前朗读句",
                    value = if (state.showSentence) "开" else "关",
                    hint = "关闭后播放页只保留封面、进度和播放控件",
                    onClick = vm::openPlaybackSettings,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, hint: String) {
    Column(Modifier
        .fillMaxWidth()
        .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp)) {
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
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}


@Composable
private fun SettingsEntryRow(
    title: String,
    value: String,
    hint: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                hint ?: value,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            value,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(end = 4.dp)
                .widthIn(max = 180.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
