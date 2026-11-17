package com.wildenglish.app.audiobook.ui

import androidx.compose.runtime.Composable
import com.wildenglish.app.audiobook.AudioBookUiState
import com.wildenglish.app.audiobook.AudioBookViewModel

/**
 * 「播放页」下一级页面：控制播放页显示的内容。
 */
@Composable
fun PlaybackSettingsScreen(state: AudioBookUiState, vm: AudioBookViewModel) {
    SettingsScaffold(
        title = "播放页",
        subtitle = "控制播放页显示的内容。",
        onBack = vm::goBack,
    ) {
        item { SettingsSection(title = "显示", hint = "有声书界面（留声机播放页）上的内容开关。") }
        item {
            SettingsSwitchRow(
                title = "显示当前朗读句",
                hint = "关闭后播放页只保留唱片、进度和播放控件",
                checked = state.showSentence,
                onCheckedChange = { vm.setShowSentence(it) },
                onClick = { vm.setShowSentence(!state.showSentence) },
            )
        }
    }
}
