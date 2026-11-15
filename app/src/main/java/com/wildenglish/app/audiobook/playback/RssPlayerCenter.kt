package com.wildenglish.app.audiobook.playback

import android.app.Application
import com.wildenglish.app.audiobook.data.Audiobook
import com.wildenglish.app.audiobook.data.RssArticle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 订阅文章的朗读器 —— 与图书播放（[PlaybackCenter]）**互不影响**的独立一套：
 * 自己的状态、自己的 [SpeechController]，不进入迷你播放器 / 锁屏 / 书籍进度。
 *
 * 音频仍然写进 [com.wildenglish.app.audiobook.data.VoiceCache]（按文章 id 分目录），
 * 所以同一篇文章第二次朗读直接命中缓存。
 */
object RssPlayerCenter {

    data class Status(
        val article: RssArticle? = null,
        val sentences: List<String> = emptyList(),
        val index: Int = 0,
        val isPlaying: Boolean = false,
        val isBuffering: Boolean = false,
        val speed: Float = 1.0f,
        val error: String? = null,
    ) {
        val hasArticle: Boolean get() = article != null && sentences.isNotEmpty()
        val currentSentence: String get() = sentences.getOrNull(index).orEmpty()
    }

    private var app: Application? = null
    private var controller: SpeechController? = null
    private var book: Audiobook? = null

    private val _state = MutableStateFlow(Status())
    val state: StateFlow<Status> = _state.asStateFlow()

    fun init(context: Application) {
        if (app == null) app = context
    }

    /** 开始朗读一篇文章（[book] 由 RssReader 生成的临时包提供缓存目录与 id）。 */
    fun play(article: RssArticle, sentences: List<String>, book: Audiobook) {
        val ctx = app ?: return
        if (sentences.isEmpty()) return
        this.book = book
        val player = controller ?: SpeechController(ctx, callback).also { controller = it }
        _state.value = Status(article = article, sentences = sentences, index = 0, isPlaying = true)
        player.speak(sentences, 0, _state.value.speed, book, 0)
    }

    fun togglePlayPause() {
        val s = _state.value
        if (!s.hasArticle) return
        val player = controller ?: return
        if (s.isPlaying) player.pause() else player.resume()
    }

    fun pause() = controller?.pause()

    fun next() = seek(_state.value.index + 1)

    fun previous() = seek(_state.value.index - 1)

    fun seek(index: Int) {
        val s = _state.value
        if (s.sentences.isEmpty()) return
        val target = index.coerceIn(0, s.sentences.lastIndex)
        controller?.seekTo(target)
        _state.value = s.copy(index = target)
    }

    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2f)
        _state.value = _state.value.copy(speed = clamped)
        controller?.setSpeed(clamped)
    }

    /** 退出文章播放页时释放（下次播放会重新创建朗读器）。 */
    fun stop() {
        controller?.release()
        controller = null
        book = null
        _state.value = Status()
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    private val callback = object : SpeechController.Callback {
        override fun onReady(success: Boolean) = Unit

        override fun onSentenceStart(index: Int) {
            _state.value = _state.value.copy(index = index, isBuffering = false)
        }

        override fun onChapterComplete() {
            _state.value = _state.value.copy(isPlaying = false)
        }

        override fun onPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(isPlaying = isPlaying)
            if (!isPlaying) _state.value = _state.value.copy(isBuffering = false)
        }

        override fun onBuffering(isBuffering: Boolean) {
            _state.value = _state.value.copy(isBuffering = isBuffering)
        }

        override fun onError(message: String) {
            _state.value = _state.value.copy(
                isPlaying = false,
                isBuffering = false,
                error = message,
            )
        }
    }
}
