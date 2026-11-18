package com.wildenglish.app.audiobook.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.wildenglish.app.R
import com.wildenglish.app.audiobook.AudioBookUiState
import com.wildenglish.app.audiobook.AudioBookViewModel

/**
 * 设置：朗读音色 + 配音文件存储目录 + 播放页显示 + 界面语言。
 *
 * 音色是全局的 —— 一本书从头到尾用同一个声音朗读，不区分角色；换音色后离线配音会按新音色
 * 重新生成。存储目录支持自动（SD 卡优先）、应用内部、主存储应用目录，以及用系统文件选择器
 * 指定的任意文件夹。
 */
@Composable
fun SettingsScreen(state: AudioBookUiState, vm: AudioBookViewModel) {
    SettingsScaffold(title = stringResource(R.string.settings_title), onBack = vm::goBack) {
        item {
            SettingsSection(
                title = stringResource(R.string.section_reading),
                hint = stringResource(R.string.section_reading_hint),
            )
        }
        item {
            SettingsEntryRow(
                title = stringResource(R.string.settings_voice),
                value = state.voice.title,
                hint = state.voice.name,
                onClick = vm::openVoice,
            )
        }

        item { SettingsDivider() }
        item {
            SettingsSection(
                title = stringResource(R.string.section_storage),
                hint = stringResource(R.string.section_storage_hint),
            )
        }
        item {
            SettingsEntryRow(
                title = stringResource(R.string.settings_storage_location),
                value = storageModeLabel(state.storageMode),
                hint = stringResource(R.string.settings_storage_path_hint),
                onClick = vm::openStorage,
            )
        }

        item { SettingsDivider() }
        item {
            SettingsSection(
                title = stringResource(R.string.section_player),
                hint = stringResource(R.string.section_player_hint),
            )
        }
        item {
            SettingsEntryRow(
                title = stringResource(R.string.settings_show_sentence),
                value = if (state.showSentence) {
                    stringResource(R.string.on_text)
                } else {
                    stringResource(R.string.off_text)
                },
                hint = stringResource(R.string.settings_show_sentence_hint),
                onClick = vm::openPlaybackSettings,
            )
        }

        item { SettingsDivider() }
        item {
            SettingsEntryRow(
                title = stringResource(R.string.language_section),
                value = stringResource(state.language.labelRes),
                hint = stringResource(R.string.language_hint),
                onClick = vm::openLanguage,
            )
        }
    }
}
