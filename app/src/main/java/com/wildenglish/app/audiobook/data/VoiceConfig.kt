package com.wildenglish.app.audiobook.data

/**
 * An Edge TTS voice/language pair.
 *
 * Shared by the offline [com.wildenglish.app.audiobook.playback.VoicePreGenerator]
 * and the live fallback in `SpeechController` so that cached audio always matches
 * the voice that would have been used online. The voice name is part of the cache
 * key, so changing a cast invalidates only the affected sentences.
 */
data class VoiceConfig(val name: String, val lang: String) {

    companion object {
        const val DEFAULT_VOICE = "en-US-EmmaMultilingualNeural"
        const val DEFAULT_LANG = "en-US"
        const val CJK_VOICE = "zh-CN-XiaoxiaoNeural"
        const val CJK_LANG = "zh-CN"

        /**
         * Cast per language. Index 0 is the narrator; the rest are the voices
         * handed out to characters (see [RoleVoices]).
         */
        private val EN_CAST = listOf(
            VoiceConfig("en-US-EmmaMultilingualNeural", "en-US"),      // 旁白
            VoiceConfig("en-US-AndrewMultilingualNeural", "en-US"),
            VoiceConfig("en-US-AvaMultilingualNeural", "en-US"),
            VoiceConfig("en-US-BrianMultilingualNeural", "en-US"),
            VoiceConfig("en-US-AriaNeural", "en-US"),
        )

        private val ZH_CAST = listOf(
            VoiceConfig("zh-CN-XiaoxiaoNeural", "zh-CN"),              // 旁白
            VoiceConfig("zh-CN-YunxiNeural", "zh-CN"),
            VoiceConfig("zh-CN-XiaoyiNeural", "zh-CN"),
            VoiceConfig("zh-CN-YunjianNeural", "zh-CN"),
            VoiceConfig("zh-CN-XiaochenNeural", "zh-CN"),
        )

        fun cast(lang: String): List<VoiceConfig> = if (lang == CJK_LANG) ZH_CAST else EN_CAST

        fun narrator(lang: String): VoiceConfig = cast(lang).first()

        /** Chapter language, decided by sampling the opening sentences. */
        fun detectLang(sentences: List<String>): String {
            val sample = sentences.asSequence().filter { it.isNotBlank() }.take(12).toList()
            if (sample.isEmpty()) return DEFAULT_LANG
            val cjk = sample.count { hasCjk(it) }
            return if (cjk * 2 >= sample.size) CJK_LANG else DEFAULT_LANG
        }

        fun hasCjk(text: String): Boolean = text.any { it.code in 0x4E00..0x9FFF }

        /**
         * True for the single voice older versions used for a whole chapter.
         * Audio cached under that voice stays valid for narrator lines.
         */
        fun isLegacyDefaultVoice(name: String): Boolean =
            name == DEFAULT_VOICE || name == CJK_VOICE
    }
}
