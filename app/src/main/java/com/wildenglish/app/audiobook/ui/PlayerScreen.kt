package com.wildenglish.app.audiobook.ui

import androidx.compose.runtime.Composable
import com.wildenglish.app.audiobook.AudioBookUiState
import com.wildenglish.app.audiobook.AudioBookViewModel

/**
 * All books — TTS transcripts and imported MP3 files alike — use the same
 * gramophone player design. See [GramophonePlayerScreen] for the actual UI.
 *
 * The player switches its progress bar and skip buttons by
 * [AudioBookUiState.audioMode]; there is no separate document/文稿 view any more.
 */
@Composable
fun PlayerScreen(state: AudioBookUiState, vm: AudioBookViewModel) {
    GramophonePlayerScreen(state, vm)
}
