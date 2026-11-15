@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wildenglish.app.audiobook.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wildenglish.app.audiobook.AudioBookUiState
import com.wildenglish.app.audiobook.AudioBookViewModel
import com.wildenglish.app.audiobook.data.VoiceConfig
import com.wildenglish.app.audiobook.data.VoiceStorageMode
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
    val pickFolder = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> vm.setStorageTree(uri) }

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
                    title = "朗读音色",
                    hint = "整本书使用同一个音色朗读，不区分角色。切换音色后会按新音色重新生成离线配音。",
                )
            }
            items(VoiceConfig.OPTIONS, key = { it.name }) { voice ->
                VoiceRow(
                    voice = voice,
                    selected = state.voice.name == voice.name,
                    onClick = { vm.setVoice(voice.name) },
                )
            }

            item {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                SectionTitle(
                    title = "配音存储",
                    hint = "朗读时生成的语音文件（MP3）保存在该目录。存储失败会直接提示。",
                )
            }
            items(VoiceStorageMode.entries.toList()) { mode ->
                StorageRow(
                    mode = mode,
                    selected = state.storageMode == mode,
                    onClick = { vm.setStorageMode(mode) },
                )
            }
            item {
                Column(Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(
                        "当前目录",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        state.storagePath.ifBlank { "—" },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        "把配音放在 SD 卡上可以省下手机内部空间；卡被拔出或目录不可写时会提示保存失败。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    TextButton(
                        onClick = { pickFolder.launch(null) },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.height(18.dp),
                        )
                        Text("  选择自定义目录", fontSize = 14.sp)
                    }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                SectionTitle(
                    title = "播放页",
                    hint = "控制播放页（有声书界面）显示的内容。",
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.setShowSentence(!state.showSentence) }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "显示当前朗读句",
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            "关闭后播放页只保留封面、进度和播放控件",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.showSentence,
                        onCheckedChange = { vm.setShowSentence(it) },
                    )
                }
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
private fun VoiceRow(voice: VoiceConfig, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 4.dp)) {
            Text(
                voice.title,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                voice.name,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StorageRow(mode: VoiceStorageMode, selected: Boolean, onClick: () -> Unit) {
    val (label, hint) = when (mode) {
        VoiceStorageMode.AUTO -> "自动（SD 卡优先）" to "有可用的 SD 卡时保存在卡上，否则退回应用内部存储"
        VoiceStorageMode.INTERNAL -> "应用内部存储" to "随应用卸载 / 清除数据一起删除，最稳定"
        VoiceStorageMode.EXTERNAL -> "主存储（应用目录）" to "/sdcard/Android/data/com.wildenglish.app/files/voice_cache"
        VoiceStorageMode.CUSTOM -> "自定义目录" to "用系统文件选择器指定任意文件夹（长期授权）"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 4.dp)) {
            Text(label, fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground)
            Text(
                hint,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
