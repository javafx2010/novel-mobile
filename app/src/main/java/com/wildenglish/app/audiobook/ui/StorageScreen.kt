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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wildenglish.app.R
import com.wildenglish.app.audiobook.AudioBookUiState
import com.wildenglish.app.audiobook.AudioBookViewModel
import com.wildenglish.app.audiobook.data.VoiceStorageMode

/** 「配音存储」下一级页面：选择语音文件（MP3）的保存位置，并展示当前实际目录。 */
@Composable
fun StorageScreen(state: AudioBookUiState, vm: AudioBookViewModel) {
    val pickFolder = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> vm.setStorageTree(uri) }

    SettingsScaffold(
        title = stringResource(R.string.storage_title),
        subtitle = stringResource(R.string.storage_hint),
        onBack = vm::goBack,
    ) {
        item {
            SettingsSection(
                title = stringResource(R.string.storage_location),
                hint = stringResource(R.string.storage_change_hint),
            )
        }
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
        item { SettingsSection(title = stringResource(R.string.storage_current_dir)) }
        item {
            SettingsInfoCard {
                Text(
                    state.storagePath.ifBlank { "—" },
                    fontSize = 13.sp,
                    color = SetCream,
                )
                Text(
                    stringResource(R.string.storage_sd_hint),
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
                        "  ${stringResource(R.string.storage_choose_custom)}",
                        fontSize = 14.sp,
                        color = SetBrassLight,
                    )
                }
            }
        }
    }
}

/** 简短标签，用于设置首页的入口行摘要。 */
@Composable
fun storageModeLabel(mode: VoiceStorageMode): String = when (mode) {
    VoiceStorageMode.AUTO -> stringResource(R.string.storage_auto)
    VoiceStorageMode.INTERNAL -> stringResource(R.string.storage_internal)
    VoiceStorageMode.EXTERNAL -> stringResource(R.string.storage_primary)
    VoiceStorageMode.CUSTOM -> stringResource(R.string.storage_custom)
}

@Composable
private fun storageModeHint(mode: VoiceStorageMode): Pair<String, String> = when (mode) {
    VoiceStorageMode.AUTO -> stringResource(R.string.storage_auto) to
            stringResource(R.string.storage_auto_hint)

    VoiceStorageMode.INTERNAL -> stringResource(R.string.storage_internal) to
            stringResource(R.string.storage_internal_hint)

    VoiceStorageMode.EXTERNAL -> stringResource(R.string.storage_primary) to
            stringResource(R.string.storage_primary_hint)

    VoiceStorageMode.CUSTOM -> stringResource(R.string.storage_custom) to
            stringResource(R.string.storage_custom_hint)
}
