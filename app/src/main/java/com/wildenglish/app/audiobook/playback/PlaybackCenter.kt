package com.wildenglish.app.audiobook.playback

import android.app.Application
import android.content.Intent
import com.wildenglish.app.audiobook.data.Audiobook
import com.wildenglish.app.audiobook.data.AudiobookLibrary
import com.wildenglish.app.audiobook.data.loadChapterSentences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Snapshot consumed by the in-app player, the lock screen, and the always-on display. */
data class PlaybackSnapshot(
    val book: Audiobook? = null,
    val chapterIndex: Int = 0,
    val sentences: List<String> = emptyList(),
    val currentSentenceIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val ttsReady: Boolean = false,
    val speed: Float = 1.0f,
    val error: String? = null,
) {
    val hasPlayer: Boolean get() = sentences.isNotEmpty()
    val title: String get() = book?.title.orEmpty()
    val chapterTitle: String get() = book?.chapters?.getOrNull(chapterIndex)?.title.orEmpty()
    val currentSentence: String get() = sentences.getOrNull(currentSentenceIndex).orEmpty()
    val coverFile: File? get() = book?.coverFile
}

/**
 * Process-wide owner of speech playback.
 *
 * Lives outside the Activity ViewModel so locking the phone or the system
 * destroying the UI does not stop audio or tear down lock-screen controls.
 */
object PlaybackCenter {

    private var app: Application? = null
    private var library: AudiobookLibrary? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadJob: Job? = null

    /** 朗读器里是否已装载当前会话（冷启动恢复的会话只是快照，尚未装载）。 */
    @Volatile
    private var speechLoaded = false

    private val _snapshot = MutableStateFlow(PlaybackSnapshot())
    val snapshot: StateFlow<PlaybackSnapshot> = _snapshot.asStateFlow()

    private val speech: SpeechController by lazy {
        SpeechController(checkNotNull(app), object : SpeechController.Callback {
            override fun onReady(success: Boolean) {
                _snapshot.update { it.copy(ttsReady = success, isBuffering = false) }
                if (!success) {
                    _snapshot.update {
                        it.copy(
                            isPlaying = false,
                            error = "微软 Edge 语音服务不可用，请检查网络连接后重试",
                        )
                    }
                }
                publish()
            }

            override fun onBuffering(isBuffering: Boolean) {
                _snapshot.update { it.copy(isBuffering = isBuffering) }
                publish()
            }

            override fun onSentenceStart(index: Int) {
                _snapshot.update { it.copy(currentSentenceIndex = index, isBuffering = false) }
                persistProgress()
                publish()
            }

            override fun onChapterComplete() {
                playNextChapter()
            }

            override fun onPlayingChanged(isPlaying: Boolean) {
                _snapshot.update { it.copy(isPlaying = isPlaying) }
                if (!isPlaying) {
                    persistProgress()
                }
                publish()
            }

            override fun onError(message: String) {
                _snapshot.update { it.copy(isBuffering = false, error = message) }
                publish()
            }
        })
    }

    fun init(application: Application) {
        if (app != null) return
        app = application
        library = AudiobookLibrary(application)
        PlaybackSession.init(application)
        // 冷启动恢复上次的收听位置（只装载不自动播放）。
        restoreLastSession()
        PlaybackSession.setListener(object : PlaybackSession.Listener {
            override fun onPlay() {
                if (!_snapshot.value.isPlaying && _snapshot.value.hasPlayer) resume()
            }

            override fun onPause() {
                if (_snapshot.value.isPlaying) pause()
            }

            override fun onPrevious() = skipSentences(-1)

            override fun onNext() = skipSentences(1)
        })
    }

    /**
     * 冷启动恢复上次的收听位置：把书 / 章节 / 句子装载到快照，但不自动播放。
     * 于是首页迷你播放器、播放页都停在昨天听到的地方，点一下播放即接着读。
     */
    fun restoreLastSession() {
        if (_snapshot.value.hasPlayer) return
        val ctx = app ?: return
        val lib = library ?: return
        scope.launch {
            val bookId = withContext(Dispatchers.IO) { lib.lastBookId() } ?: return@launch
            val books = withContext(Dispatchers.IO) { AudiobookLibrary(ctx).listBooks() }
            val book = books.firstOrNull { it.id == bookId } ?: return@launch
            if (book.chapters.isEmpty()) return@launch
            val chapterIndex = lib.progressOf(bookId).first.coerceIn(0, book.chapters.lastIndex)
            val chapter = book.chapters[chapterIndex]
            val sentences = withContext(Dispatchers.IO) { loadChapterSentences(chapter) }
            if (sentences.isEmpty()) return@launch
            val position = lib.sentenceOf(bookId, chapterIndex).coerceIn(0, sentences.lastIndex)
            // 恢复期间用户可能已经开始听别的书：不要覆盖正在播放的会话。
            if (_snapshot.value.hasPlayer) return@launch
            speechLoaded = false
            _snapshot.update {
                it.copy(
                    book = book,
                    chapterIndex = chapterIndex,
                    sentences = sentences,
                    currentSentenceIndex = position,
                    isPlaying = false,
                    isBuffering = false,
                )
            }
        }
    }

    fun startChapter(book: Audiobook, index: Int, startSentence: Int = -1) {
        val ctx = app ?: return
        val chapter = book.chapters.getOrNull(index) ?: return
        VoicePrepCenter.prioritize(book.id)
        loadJob?.cancel()
        loadJob = scope.launch {
            val sentences = withContext(Dispatchers.IO) { loadChapterSentences(chapter) }
            if (sentences.isEmpty()) {
                speech.stop()
                _snapshot.update {
                    it.copy(
                        book = book,
                        chapterIndex = index,
                        sentences = emptyList(),
                        isBuffering = false,
                        isPlaying = false,
                        error = "本章没有可朗读的文稿",
                    )
                }
                publish()
                return@launch
            }
            val stored = library?.positionOf(book.id, index)?.toInt() ?: 0
            val start = (if (startSentence >= 0) startSentence else stored)
                .coerceIn(0, sentences.lastIndex)
            _snapshot.update {
                it.copy(
                    book = book,
                    chapterIndex = index,
                    sentences = sentences,
                    currentSentenceIndex = start,
                    isBuffering = !it.ttsReady,
                    error = null,
                )
            }
            speech.speak(sentences, start, _snapshot.value.speed, book, chapter.index)
            speechLoaded = true
            ensureService()
            publish()
        }
    }

    fun togglePlayPause() {
        val s = _snapshot.value
        if (!s.hasPlayer) return
        if (!speechLoaded) {
            // 恢复出来的会话还没装载到朗读器：先按上次的句子装载再播。
            s.book?.let { startChapter(it, s.chapterIndex, s.currentSentenceIndex) }
            ensureService()
            return
        }
        speech.togglePlayPause()
        ensureService()
    }

    fun pause() = speech.pause()

    fun resume() {
        val s = _snapshot.value
        if (!s.hasPlayer) return
        if (!speechLoaded) {
            s.book?.let { startChapter(it, s.chapterIndex, s.currentSentenceIndex) }
            ensureService()
            return
        }
        speech.resume()
        ensureService()
        publish()
    }

    fun seekToSentence(index: Int) {
        val s = _snapshot.value
        if (s.sentences.isEmpty()) return
        val target = index.coerceIn(0, s.sentences.lastIndex)
        if (!speechLoaded) {
            s.book?.let { startChapter(it, s.chapterIndex, target) }
            return
        }
        speech.seekTo(target)
    }

    fun skipSentences(delta: Int) {
        val s = _snapshot.value
        if (s.sentences.isEmpty()) return
        seekToSentence(s.currentSentenceIndex + delta)
    }

    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        _snapshot.update { it.copy(speed = clamped) }
        speech.setSpeed(clamped)
    }

    fun nextChapter() {
        val s = _snapshot.value
        val book = s.book ?: return
        if (s.chapterIndex + 1 < book.chapters.size) {
            startChapter(book, s.chapterIndex + 1, 0)
        }
    }

    fun previousChapter() {
        val s = _snapshot.value
        val book = s.book ?: return
        if (s.chapterIndex > 0) startChapter(book, s.chapterIndex - 1, 0)
    }

    fun dismissError() = _snapshot.update { it.copy(error = null) }

    fun stop() {
        loadJob?.cancel()
        speechLoaded = false
        speech.stop()
        _snapshot.update {
            it.copy(
                book = null,
                sentences = emptyList(),
                currentSentenceIndex = -1,
                isPlaying = false,
                isBuffering = false,
            )
        }
        publish()
    }

    fun playNextChapter() {
        val s = _snapshot.value
        val book = s.book ?: return
        val next = s.chapterIndex + 1
        if (next < book.chapters.size) {
            startChapter(book, next, 0)
        } else {
            // 最后一章播完：停止播放。
            _snapshot.update { it.copy(isPlaying = false) }
            persistProgress()
            publish()
        }
    }

    private fun persistProgress() {
        val s = _snapshot.value
        val book = s.book ?: return
        library?.saveProgress(
            book.id,
            s.chapterIndex,
            s.currentSentenceIndex.coerceAtLeast(0).toLong(),
        )
    }

    private fun publish() {
        val s = _snapshot.value
        val ctx = app ?: return
        PlaybackSession.update(
            context = ctx,
            title = s.title,
            subtitle = s.chapterTitle,
            isPlaying = s.isPlaying,
            hasPlayer = s.hasPlayer,
            coverFile = s.coverFile,
            sentence = s.currentSentence,
        )
        if (s.hasPlayer) ensureService()
    }

    private fun ensureService() {
        val ctx = app ?: return
        runCatching { ctx.startForegroundService(Intent(ctx, PlaybackService::class.java)) }
    }
}
