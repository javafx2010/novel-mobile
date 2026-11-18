package com.wildenglish.app.audiobook.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.wildenglish.app.R
import com.wildenglish.app.audiobook.AudioBookUiState
import com.wildenglish.app.audiobook.AudioBookViewModel
import com.wildenglish.app.i18n.AppLanguage

/** Interface language picker. AUTO follows the device locale. */
@Composable
fun LanguageScreen(state: AudioBookUiState, vm: AudioBookViewModel) {
    SettingsScaffold(
        title = stringResource(R.string.language_section),
        subtitle = stringResource(R.string.language_hint),
        onBack = vm::goBack,
    ) {
        item {
            SettingsSection(title = stringResource(R.string.language_section))
        }
        for (language in AppLanguage.entries) {
            item(key = language.name) {
                SettingsRadioRow(
                    label = stringResource(language.labelRes),
                    hint = if (language == AppLanguage.AUTO) {
                        stringResource(R.string.language_hint)
                    } else {
                        ""
                    },
                    selected = state.language == language,
                    onClick = { vm.setLanguage(language) },
                )
            }
        }
    }
}
