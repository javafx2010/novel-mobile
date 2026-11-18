package com.wildenglish.app.audiobook.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.wildenglish.app.R
import com.wildenglish.app.audiobook.AudioBookUiState
import com.wildenglish.app.audiobook.AudioBookViewModel

/** 「播放页」下一级页面：控制播放页显示的内容。 */
@Composable
fun PlaybackSettingsScreen(state: AudioBookUiState, vm: AudioBookViewModel) {
    SettingsScaffold(
        title = stringResource(R.string.playback_title),
        subtitle = stringResource(R.string.playback_show_hint),
        onBack = vm::goBack,
    ) {
        item {
            SettingsSection(
                title = stringResource(R.string.playback_show_title),
                hint = stringResource(R.string.playback_hint),
            )
        }
        item {
            SettingsSwitchRow(
                title = stringResource(R.string.playback_show_sentence),
                hint = stringResource(R.string.playback_show_sentence_hint),
                checked = state.showSentence,
                onCheckedChange = { vm.setShowSentence(it) },
                onClick = { vm.setShowSentence(!state.showSentence) },
            )
        }
    }
}
