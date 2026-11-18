package com.wildenglish.app.audiobook.ui

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.wildenglish.app.R
import com.wildenglish.app.audiobook.AudioBookUiState
import com.wildenglish.app.audiobook.AudioBookViewModel
import com.wildenglish.app.audiobook.data.VoiceConfig

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
            SettingsRadioRow(
                label = voice.title,
                hint = voice.name,
                selected = state.voice.name == voice.name,
                onClick = { vm.setVoice(voice.name) },
            )
        }
    }
}
