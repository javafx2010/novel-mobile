package com.wildenglish.app.audiobook.data

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
    val hint: String,
) {
    /** "晓晓 · 女声 · 中文" — shown in the settings list. */
    val title: String get() = "$label · $hint"

    companion object {

        const val DEFAULT_NAME = "en-US-EmmaMultilingualNeural"

        /** Selectable voices: 中文 first, then English (multilingual voices read both). */
        val OPTIONS: List<VoiceConfig> = listOf(
            VoiceConfig("zh-CN-XiaoxiaoNeural", "zh-CN", "晓晓", "女声 · 中文"),
            VoiceConfig("zh-CN-XiaoyiNeural", "zh-CN", "晓伊", "女声 · 中文"),
            VoiceConfig("zh-CN-YunxiNeural", "zh-CN", "云希", "男声 · 中文"),
            VoiceConfig("zh-CN-YunjianNeural", "zh-CN", "云健", "男声 · 中文"),
            VoiceConfig("zh-CN-YunyangNeural", "zh-CN", "云扬", "男声 · 中文"),
            VoiceConfig("zh-CN-YunxiaNeural", "zh-CN", "云夏", "男声 · 中文"),
            VoiceConfig("en-US-EmmaMultilingualNeural", "en-US", "Emma", "女声 · 多语种"),
            VoiceConfig("en-US-AvaMultilingualNeural", "en-US", "Ava", "女声 · 多语种"),
            VoiceConfig("en-US-AndrewMultilingualNeural", "en-US", "Andrew", "男声 · 多语种"),
            VoiceConfig("en-US-BrianMultilingualNeural", "en-US", "Brian", "男声 · 多语种"),
            VoiceConfig("en-US-AriaNeural", "en-US", "Aria", "女声 · 英文"),
            VoiceConfig("en-US-GuyNeural", "en-US", "Guy", "男声 · 英文"),
        )

        val DEFAULT: VoiceConfig = OPTIONS.first { it.name == DEFAULT_NAME }

        /** Looks a voice up by name, falling back to the default for unknown/blank input. */
        fun byName(name: String?): VoiceConfig =
            OPTIONS.firstOrNull { it.name == name } ?: DEFAULT
    }
}
