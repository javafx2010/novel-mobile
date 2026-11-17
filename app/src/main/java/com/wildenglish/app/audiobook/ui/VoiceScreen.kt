package com.wildenglish.app.audiobook.ui

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import com.wildenglish.app.audiobook.AudioBookUiState
import com.wildenglish.app.audiobook.AudioBookViewModel
import com.wildenglish.app.audiobook.data.VoiceConfig

/**
 * 「朗读音色」下一级页面：整本书统一使用的音色列表，选中即生效。
 */
@Composable
fun VoiceScreen(state: AudioBookUiState, vm: AudioBookViewModel) {
    SettingsScaffold(
        title = "朗读音色",
        subtitle = "整本书使用同一个音色，切换后会按新音色重新生成离线配音。",
        onBack = vm::goBack,
    ) {
        item { SettingsSection(title = "可用音色", hint = "中文与英文音色，选中的音色对全书生效。") }
        items(VoiceConfig.OPTIONS, key = { it.name }) { voice ->
            SettingsRadioRow(
                label = voice.title,
                hint = voice.name,
                selected = state.voice.name == voice.name,
                onClick = { vm.setVoice(voice.name) },
            )
        }
    }
}
