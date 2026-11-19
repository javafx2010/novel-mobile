package com.wildenglish.app.audiobook.data

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.wildenglish.app.R
import com.wildenglish.app.i18n.Strings

/** Language shown as a national flag on the voice picker. */
enum class VoiceLanguage { CHINESE, ENGLISH }

/** Gender shown as a small glyph on the voice picker. */
enum class VoiceGender { FEMALE, MALE }

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
    @DrawableRes val avatarRes: Int,
    val language: VoiceLanguage,
    val gender: VoiceGender,
) {
    /** "晓晓 · 女声 · 中文" — shown in the settings list, localized. */
    val title: String get() = "$label · ${Strings.get(hintRes)}"

    companion object {

        const val DEFAULT_NAME = "en-US-EmmaMultilingualNeural"

        /** Selectable voices: 中文 first, then English (multilingual voices read both). */
        val OPTIONS: List<VoiceConfig> = listOf(
            VoiceConfig(
                "zh-CN-XiaoxiaoNeural", "zh-CN", "晓晓", R.string.voice_desc_female_zh,
                R.drawable.voice_xiaoxiao, VoiceLanguage.CHINESE, VoiceGender.FEMALE
            ),
            VoiceConfig(
                "zh-CN-XiaoyiNeural", "zh-CN", "晓伊", R.string.voice_desc_female_zh,
                R.drawable.voice_xiaoyi, VoiceLanguage.CHINESE, VoiceGender.FEMALE
            ),
            VoiceConfig(
                "zh-CN-YunxiNeural", "zh-CN", "云希", R.string.voice_desc_male_zh,
                R.drawable.voice_yunxi, VoiceLanguage.CHINESE, VoiceGender.MALE
            ),
            VoiceConfig(
                "zh-CN-YunjianNeural", "zh-CN", "云健", R.string.voice_desc_male_zh,
                R.drawable.voice_yunjian, VoiceLanguage.CHINESE, VoiceGender.MALE
            ),
            VoiceConfig(
                "zh-CN-YunyangNeural", "zh-CN", "云扬", R.string.voice_desc_male_zh,
                R.drawable.voice_yunyang, VoiceLanguage.CHINESE, VoiceGender.MALE
            ),
            VoiceConfig(
                "zh-CN-YunxiaNeural", "zh-CN", "云夏", R.string.voice_desc_male_zh,
                R.drawable.voice_yunxia, VoiceLanguage.CHINESE, VoiceGender.MALE
            ),
            VoiceConfig(
                "en-US-EmmaMultilingualNeural", "en-US", "Emma", R.string.voice_desc_female_multi,
                R.drawable.voice_emma, VoiceLanguage.ENGLISH, VoiceGender.FEMALE
            ),
            VoiceConfig(
                "en-US-AvaMultilingualNeural", "en-US", "Ava", R.string.voice_desc_female_multi,
                R.drawable.voice_ava, VoiceLanguage.ENGLISH, VoiceGender.FEMALE
            ),
            VoiceConfig(
                "en-US-AndrewMultilingualNeural", "en-US", "Andrew", R.string.voice_desc_male_multi,
                R.drawable.voice_andrew, VoiceLanguage.ENGLISH, VoiceGender.MALE
            ),
            VoiceConfig(
                "en-US-BrianMultilingualNeural", "en-US", "Brian", R.string.voice_desc_male_multi,
                R.drawable.voice_brian, VoiceLanguage.ENGLISH, VoiceGender.MALE
            ),
            VoiceConfig(
                "en-US-AriaNeural", "en-US", "Aria", R.string.voice_desc_female_en,
                R.drawable.voice_aria, VoiceLanguage.ENGLISH, VoiceGender.FEMALE
            ),
            VoiceConfig(
                "en-US-GuyNeural", "en-US", "Guy", R.string.voice_desc_male_en,
                R.drawable.voice_guy, VoiceLanguage.ENGLISH, VoiceGender.MALE
            ),
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
