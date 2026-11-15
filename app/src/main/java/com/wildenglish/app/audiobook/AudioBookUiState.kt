package com.wildenglish.app.audiobook

import com.wildenglish.app.audiobook.data.Audiobook
import com.wildenglish.app.audiobook.data.Chapter
import com.wildenglish.app.audiobook.data.MarketCategory
import com.wildenglish.app.audiobook.data.MarketItem

enum class Screen { LIBRARY, DETAIL, PLAYER, READER, MARKET }

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
    /** 每句的说话角色（旁白 / 角色名），与 [sentences] 一一对应。 */
    val roles: List<String> = emptyList(),
    /** Index into [sentences] currently being spoken; -1 when unknown. */
    val currentSentenceIndex: Int = -1,
    val isPlaying: Boolean = false,
    /** True while a sentence's speech is being synthesized over the network. */
    val isBuffering: Boolean = false,
    val ttsReady: Boolean = false,
    val speed: Float = 1.0f,
    val sleepRemainingSeconds: Int = 0,
    /** Per-book offline voice preparation progress, keyed by book id. */
    val voicePrep: Map<String, VoicePrepProgress> = emptyMap(),
    /** Books whose full voice track has been cached locally. */
    val readyVoiceBookIds: Set<String> = emptySet(),
    /** Reading skin used by the document (文稿) screen. */
    val readerTheme: ReaderTheme = ReaderTheme.PAPER,
    /** Books listed by the backend market (browse + download, no purchase). */
    val marketItems: List<MarketItem> = emptyList(),
    val marketLoading: Boolean = false,
    /** Last loaded market page; -1 means nothing loaded yet. */
    val marketPage: Int = -1,
    val marketTotalPages: Int = 0,
    val marketTotalElements: Long = 0,
    /** Market book id whose EPUB is being downloaded right now. */
    val downloadingMarketId: Long? = null,
    /** Backend address used by the market; editable in the market settings dialog. */
    val serverAddress: String = "",
    /** Categories synced automatically from the backend, used as market filter chips. */
    val marketCategories: List<MarketCategory> = emptyList(),
    /** Selected market category id; null means "全部". */
    val selectedMarketCategoryId: Long? = null,
) {
    val currentBook: Audiobook? get() = books.firstOrNull { it.id == currentBookId }
    val currentChapter: Chapter? get() = currentBook?.chapters?.getOrNull(chapterIndex)
    val hasActivePlayer: Boolean get() = sentences.isNotEmpty()

    /** 当前句的说话角色（旁白 / 角色名）。 */
    val currentRole: String get() = roles.getOrNull(currentSentenceIndex).orEmpty()

    /** Whether another market page can be fetched. */
    val marketHasMore: Boolean get() = marketPage >= 0 && marketPage + 1 < marketTotalPages
}
