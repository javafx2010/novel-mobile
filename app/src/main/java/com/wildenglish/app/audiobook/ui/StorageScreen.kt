@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wildenglish.app.audiobook.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import com.wildenglish.app.audiobook.data.VoiceStorageMode
import com.wildenglish.app.ui.theme.GlassIconButton

/**
 * 「配音存储」下一级页面：选择语音文件（MP3）的保存位置，并展示当前实际目录。
 */
@Composable
fun StorageScreen(state: AudioBookUiState, vm: AudioBookViewModel) {
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
            Column(Modifier.padding(horizontal = 6.dp)) {
                Text(
                    "配音存储",
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "朗读生成的语音文件保存在这里，存储失败会直接提示。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
        ) {
            item {
                Text(
                    "存储位置",
                    modifier = Modifier.padding(
                        start = 20.dp,
                        end = 20.dp,
                        top = 8.dp,
                        bottom = 6.dp
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
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
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
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
        }
    }
}

/** 简短标签，用于设置首页的入口行摘要。 */
fun storageModeLabel(mode: VoiceStorageMode): String = when (mode) {
    VoiceStorageMode.AUTO -> "自动（SD 卡优先）"
    VoiceStorageMode.INTERNAL -> "应用内部存储"
    VoiceStorageMode.EXTERNAL -> "主存储（应用目录）"
    VoiceStorageMode.CUSTOM -> "自定义目录"
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
            .padding(start = 14.dp, end = 20.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 4.dp)) {
            Text(
                label,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                hint,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
