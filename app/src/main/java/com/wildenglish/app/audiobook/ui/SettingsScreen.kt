package com.wildenglish.app.audiobook.ui

import androidx.compose.runtime.Composable
import com.wildenglish.app.audiobook.AudioBookUiState
import com.wildenglish.app.audiobook.AudioBookViewModel

/**
 * 设置：朗读音色 + 配音文件存储目录 + 播放页显示。
 *
 * 音色是全局的 —— 一本书从头到尾用同一个声音朗读，不区分角色；换音色后离线配音会按新音色
 * 重新生成。存储目录支持自动（SD 卡优先）、应用内部、主存储应用目录，以及用系统文件选择器
 * 指定的任意文件夹。
 */
@Composable
fun SettingsScreen(state: AudioBookUiState, vm: AudioBookViewModel) {
    SettingsScaffold(title = "设置", onBack = vm::goBack) {
        item {
            SettingsSection(
                title = "朗读",
                hint = "整本书使用同一个音色朗读，切换音色后会按新音色重新生成离线配音。"
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

        item { SettingsDivider() }
        item {
            SettingsSection(
                title = "配音存储",
                hint = "朗读时生成的语音文件（MP3）保存在该目录。"
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

        item { SettingsDivider() }
        item { SettingsSection(title = "播放页", hint = "控制播放页显示的内容。") }
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
