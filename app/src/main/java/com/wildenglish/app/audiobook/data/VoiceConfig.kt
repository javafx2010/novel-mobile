package com.wildenglish.app.audiobook.data

import androidx.annotation.StringRes
import com.wildenglish.app.R
import com.wildenglish.app.i18n.Strings

/**
 * An Edge TTS voice.
 *
 * The whole book is read aloud with ONE voice (no per-character dubbing), so a voice is just a
 * global setting: [name] is what the WebSocket handshake needs, [lang] is the SSML language.
 * The voice name is part of the cache file name and of the `.ready` marker, so switching voices
 * invalidates exactly the audio that was synthesized with the previous one.
 */
data class VoiceConfig(
    val name: String,
    val lang: String,
    val label: String,
    @StringRes val hintRes: Int,
) {
    /** "晓晓 · 女声 · 中文" — shown in the settings list, localized. */
    val title: String get() = "$label · ${Strings.get(hintRes)}"

    companion object {

        const val DEFAULT_NAME = "en-US-EmmaMultilingualNeural"

        /** Selectable voices: 中文 first, then English (multilingual voices read both). */
        val OPTIONS: List<VoiceConfig> = listOf(
            VoiceConfig("zh-CN-XiaoxiaoNeural", "zh-CN", "晓晓", R.string.voice_desc_female_zh),
            VoiceConfig("zh-CN-XiaoyiNeural", "zh-CN", "晓伊", R.string.voice_desc_female_zh),
            VoiceConfig("zh-CN-YunxiNeural", "zh-CN", "云希", R.string.voice_desc_male_zh),
            VoiceConfig("zh-CN-YunjianNeural", "zh-CN", "云健", R.string.voice_desc_male_zh),
            VoiceConfig("zh-CN-YunyangNeural", "zh-CN", "云扬", R.string.voice_desc_male_zh),
            VoiceConfig("zh-CN-YunxiaNeural", "zh-CN", "云夏", R.string.voice_desc_male_zh),
            VoiceConfig(
                "en-US-EmmaMultilingualNeural",
                "en-US",
                "Emma",
                R.string.voice_desc_female_multi
            ),
            VoiceConfig(
                "en-US-AvaMultilingualNeural",
                "en-US",
                "Ava",
                R.string.voice_desc_female_multi
            ),
            VoiceConfig(
                "en-US-AndrewMultilingualNeural",
                "en-US",
                "Andrew",
                R.string.voice_desc_male_multi
            ),
            VoiceConfig(
                "en-US-BrianMultilingualNeural",
                "en-US",
                "Brian",
                R.string.voice_desc_male_multi
            ),
            VoiceConfig("en-US-AriaNeural", "en-US", "Aria", R.string.voice_desc_female_en),
            VoiceConfig("en-US-GuyNeural", "en-US", "Guy", R.string.voice_desc_male_en),
        )

        val DEFAULT: VoiceConfig = OPTIONS.first { it.name == DEFAULT_NAME }

        /**
         * 旧版本（单音色时代）用这两个音色合成过 `<句号>.mp3`（文件名里没有音色）。
         * 现在文件名带音色后缀，遇到这两个音色的旧文件时按旧名回退读取并就地改名。
         */
        fun isLegacyVoice(name: String): Boolean =
            name == DEFAULT_NAME || name == "zh-CN-XiaoxiaoNeural"

        /** Looks a voice up by name, falling back to the default for unknown/blank input. */
        fun byName(name: String?): VoiceConfig =
            OPTIONS.firstOrNull { it.name == name } ?: DEFAULT
    }
}
