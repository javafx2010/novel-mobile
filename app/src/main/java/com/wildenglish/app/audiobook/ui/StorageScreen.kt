package com.wildenglish.app.audiobook.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wildenglish.app.audiobook.AudioBookUiState
import com.wildenglish.app.audiobook.AudioBookViewModel
import com.wildenglish.app.audiobook.data.VoiceStorageMode

/**
 * 「配音存储」下一级页面：选择语音文件（MP3）的保存位置，并展示当前实际目录。
 */
@Composable
fun StorageScreen(state: AudioBookUiState, vm: AudioBookViewModel) {
    val pickFolder = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> vm.setStorageTree(uri) }

    SettingsScaffold(
        title = "配音存储",
        subtitle = "朗读生成的语音文件保存在这里，存储失败会直接提示。",
        onBack = vm::goBack,
    ) {
        item { SettingsSection(title = "存储位置", hint = "换目录后，未生成的书会重新排队配音。") }
        items(VoiceStorageMode.entries.toList(), key = { it.name }) { mode ->
            val (label, hint) = storageModeHint(mode)
            SettingsRadioRow(
                label = label,
                hint = hint,
                selected = state.storageMode == mode,
                onClick = { vm.setStorageMode(mode) },
            )
        }

        item { SettingsDivider() }
        item { SettingsSection(title = "当前目录") }
        item {
            SettingsInfoCard {
                Text(
                    state.storagePath.ifBlank { "—" },
                    fontSize = 13.sp,
                    color = SetCream,
                )
                Text(
                    "把配音放在 SD 卡上可以省下手机内部空间；卡被拔出或目录不可写时会提示保存失败。",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = SetBrass.copy(alpha = 0.65f),
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { pickFolder.launch(null) }
                        .padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = SetBrass)
                    Text(
                        "  选择自定义目录",
                        fontSize = 14.sp,
                        color = SetBrassLight,
                    )
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

private fun storageModeHint(mode: VoiceStorageMode): Pair<String, String> = when (mode) {
    VoiceStorageMode.AUTO -> "自动（SD 卡优先）" to "有可用的 SD 卡时保存在卡上，否则退回应用内部存储"
    VoiceStorageMode.INTERNAL -> "应用内部存储" to "随应用卸载 / 清除数据一起删除，最稳定"
    VoiceStorageMode.EXTERNAL -> "主存储（应用目录）" to "/sdcard/Android/data/com.wildenglish.app/files/voice_cache"
    VoiceStorageMode.CUSTOM -> "自定义目录" to "用系统文件选择器指定任意文件夹（长期授权）"
}
