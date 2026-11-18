package com.wildenglish.app.audiobook

import com.wildenglish.app.audiobook.data.Audiobook
import com.wildenglish.app.audiobook.data.Chapter
import com.wildenglish.app.audiobook.data.MarketCategory
import com.wildenglish.app.audiobook.data.MarketItem
import com.wildenglish.app.audiobook.data.VoiceConfig
import com.wildenglish.app.audiobook.data.VoiceStorageMode
import com.wildenglish.app.i18n.AppLanguage

enum class Screen { LIBRARY, DETAIL, PLAYER, MARKET, SETTINGS, VOICE, STORAGE, PLAYBACK, LANGUAGE }

/** Progress of pre-synthesizing a book's whole voice track into local cache. */
data class VoicePrepProgress(
    val done: Int = 0,
    val total: Int = 0,
    val failed: Int = 0,
    val active: Boolean = false,
)

data class AudioBookUiState(
    val screen: Screen = Screen.LIBRARY,
    val isLoading: Boolean = false,
    val isImporting: Boolean = false,
    val error: String? = null,
    val books: List<Audiobook> = emptyList(),
    val currentBookId: String? = null,
    val chapterIndex: Int = 0,
    /** All sentences of the current chapter, read aloud one by one. */
    val sentences: List<String> = emptyList(),
    /** Index into [sentences] currently being spoken; -1 when unknown. */
    val currentSentenceIndex: Int = -1,
    val isPlaying: Boolean = false,
    /** True while a sentence's speech is being synthesized over the network. */
    val isBuffering: Boolean = false,
    val ttsReady: Boolean = false,
    /** True when the current chapter is a real audio file (MP3) instead of Edge TTS. */
    val audioMode: Boolean = false,
    /** Playback position / duration of the audio file (0 in TTS mode). */
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1.0f,
    val sleepRemainingSeconds: Int = 0,
    /** Per-book offline voice preparation progress, keyed by book id. */
    val voicePrep: Map<String, VoicePrepProgress> = emptyMap(),
    /** Books whose full voice track has been cached locally. */
    val readyVoiceBookIds: Set<String> = emptySet(),
    /** Books listed by the backend market (browse + download, no purchase). */
    val marketItems: List<MarketItem> = emptyList(),
    val marketLoading: Boolean = false,
    /** Last loaded market page; -1 means nothing loaded yet. */
    val marketPage: Int = -1,
    val marketTotalPages: Int = 0,
    val marketTotalElements: Long = 0,
    /** Market book id whose EPUB is being downloaded right now. */
    val downloadingMarketId: Long? = null,
    /** Categories synced automatically from the backend, used as market filter chips. */
    val marketCategories: List<MarketCategory> = emptyList(),
    /** Selected market category id; null means "全部". */
    val selectedMarketCategoryId: Long? = null,
    /** 全局朗读音色：全书统一，不区分角色。 */
    val voice: VoiceConfig = VoiceConfig.DEFAULT,
    /** 配音文件存储方式（自动 / 内部 / 外部 / 自定义目录）。 */
    val storageMode: VoiceStorageMode = VoiceStorageMode.AUTO,
    /** 界面语言（AUTO 跟随系统）。 */
    val language: AppLanguage = AppLanguage.AUTO,
    /** 语言切换后自增，宿主 Activity 据此重建以刷新资源。 */
    val languageChangeToken: Int = 0,
    /** 自定义目录的 SAF tree uri。 */
    val customTreeUri: String? = null,
    /** 当前实际写入配音文件的目录，展示在设置页。 */
    val storagePath: String = "",
    /** 播放页是否展示「当前朗读句」。 */
    val showSentence: Boolean = true,
    /** 上次中断的章节 / 句子（详情页「继续朗读」用）。 */
    val savedChapterIndex: Int = 0,
    val savedSentenceIndex: Int = 0,
    /** 音频模式下上次中断的播放位置（毫秒）。 */
    val savedPositionMs: Long = 0L,
) {
    val currentBook: Audiobook? get() = books.firstOrNull { it.id == currentBookId }
    val currentChapter: Chapter? get() = currentBook?.chapters?.getOrNull(chapterIndex)
    val hasActivePlayer: Boolean get() = audioMode || sentences.isNotEmpty()

    /** Whether another market page can be fetched. */
    val marketHasMore: Boolean get() = marketPage >= 0 && marketPage + 1 < marketTotalPages
}
