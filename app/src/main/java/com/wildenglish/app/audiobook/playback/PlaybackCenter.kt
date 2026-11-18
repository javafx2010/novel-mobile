package com.wildenglish.app.audiobook.playback

import android.app.Application
import android.content.Intent
import com.wildenglish.app.R
import com.wildenglish.app.audiobook.data.Audiobook
import com.wildenglish.app.audiobook.data.AudiobookLibrary
import com.wildenglish.app.audiobook.data.loadChapterSentences
import com.wildenglish.app.i18n.Strings
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
    /** True when playing a real audio file instead of Edge TTS. */
    val audioMode: Boolean = false,
    /** Playback position of the audio file (0 in TTS mode). */
    val positionMs: Long = 0L,
    /** Duration of the audio file; 0 until known. */
    val durationMs: Long = 0L,
) {
    val hasPlayer: Boolean get() = audioMode || sentences.isNotEmpty()
    val title: String get() = book?.title.orEmpty()
    val chapterTitle: String get() = book?.chapters?.getOrNull(chapterIndex)?.title.orEmpty()
    val currentSentence: String get() = sentences.getOrNull(currentSentenceIndex).orEmpty()
    val coverFile: File? get() = book?.coverFile
}

/**
 * Process-wide owner of playback.
 *
 * Lives outside the Activity ViewModel so locking the phone or the system
 * destroying the UI does not stop audio or tear down lock-screen controls.
 *
 * Two playback modes share the same snapshot / UI:
 *  - transcript mode: [SpeechController] reads sentence by sentence with Edge TTS;
 *  - audio mode: [AudioFilePlayer] plays the chapter's imported MP3 directly and
 *    follows its subtitle cues.
 */
object PlaybackCenter {

    private var app: Application? = null
    private var library: AudiobookLibrary? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadJob: Job? = null

    /** 朗读器里是否已装载当前会话（冷启动恢复的会话只是快照，尚未装载）。 */
    @Volatile
    private var speechLoaded = false

    /** 音频播放器里是否已装载当前章节。 */
    @Volatile
    private var audioLoaded = false

    /** Position saved to storage last, so audio progress is not written on every tick. */
    private var lastPersistedMs = 0L

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
                            error = Strings.get(R.string.tts_service_unavailable),
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

    private val audioFile: AudioFilePlayer by lazy {
        AudioFilePlayer(object : AudioFilePlayer.Callback {
            override fun onPrepared(durationMs: Long) {
                _snapshot.update { it.copy(durationMs = durationMs, isBuffering = false) }
                publish()
            }

            override fun onPosition(positionMs: Long) {
                _snapshot.update { it.copy(positionMs = positionMs) }
                maybePersistAudio(positionMs)
            }

            override fun onPlayingChanged(isPlaying: Boolean) {
                _snapshot.update { it.copy(isPlaying = isPlaying) }
                if (!isPlaying) persistProgress()
                publish()
            }

            override fun onChapterComplete() {
                playNextChapter()
            }

            override fun onError(message: String) {
                _snapshot.update {
                    it.copy(
                        isPlaying = false,
                        isBuffering = false,
                        error = message
                    )
                }
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
     * 冷启动恢复上次的收听位置：把书 / 章节 / 句子（或音频位置）装载到快照，但不自动播放。
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
            val audioChapter = chapter.audioFile?.isFile == true
            // MP3 书直接播放音频、不加载字幕；TTS 书才需要文稿句子。
            val sentences = if (audioChapter) {
                emptyList()
            } else {
                withContext(Dispatchers.IO) { loadChapterSentences(chapter) }
            }
            if (!audioChapter && sentences.isEmpty()) return@launch

            val positionMs = if (audioChapter) lib.positionOf(bookId, chapterIndex) else 0L
            val sentenceIndex = if (audioChapter) {
                -1
            } else {
                lib.sentenceOf(bookId, chapterIndex).coerceIn(0, sentences.lastIndex)
            }
            // 恢复期间用户可能已经开始听别的书：不要覆盖正在播放的会话。
            if (_snapshot.value.hasPlayer) return@launch
            speechLoaded = false
            audioLoaded = false
            _snapshot.update {
                it.copy(
                    book = book,
                    chapterIndex = chapterIndex,
                    sentences = sentences,
                    currentSentenceIndex = sentenceIndex,
                    isPlaying = false,
                    isBuffering = false,
                    audioMode = audioChapter,
                    positionMs = positionMs,
                    durationMs = 0L,
                )
            }
        }
    }

    fun startChapter(book: Audiobook, index: Int, startSentence: Int = -1) {
        val chapter = book.chapters.getOrNull(index) ?: return
        loadJob?.cancel()
        loadJob = scope.launch {
            val audioSource = chapter.audioFile?.takeIf { it.isFile }
            if (audioSource != null) {
                startAudioChapter(book, index, audioSource, startSentence)
                return@launch
            }
            VoicePrepCenter.prioritize(book.id)
            audioFileStop()
            val sentences = withContext(Dispatchers.IO) { loadChapterSentences(chapter) }
            if (sentences.isEmpty()) {
                speech.stop()
                _snapshot.update {
                    it.copy(
                        book = book,
                        chapterIndex = index,
                        sentences = emptyList(),
                        audioMode = false,
                        isBuffering = false,
                        isPlaying = false,
                        error = Strings.get(R.string.no_speech_text),
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
                    isPlaying = true,
                    error = null,
                    audioMode = false,
                    positionMs = 0L,
                    durationMs = 0L,
                )
            }
            speech.speak(sentences, start, _snapshot.value.speed, book, chapter.index)
            speechLoaded = true
            ensureService()
            publish()
        }
    }

    /** Loads the chapter's real audio. MP3 playback has no subtitle / transcript. */
    private suspend fun startAudioChapter(
        book: Audiobook,
        index: Int,
        file: File,
        startSentence: Int,
    ) {
        val stored = library?.positionOf(book.id, index) ?: 0L
        val startMs = if (startSentence >= 0) 0L else stored
        speech.stop()
        speechLoaded = false
        audioLoaded = false
        _snapshot.update {
            it.copy(
                book = book,
                chapterIndex = index,
                sentences = emptyList(),
                currentSentenceIndex = -1,
                audioMode = true,
                positionMs = startMs,
                durationMs = 0L,
                isPlaying = true,
                isBuffering = false,
                error = null,
            )
        }
        lastPersistedMs = startMs
        audioFile.load(file, startMs, _snapshot.value.speed, play = true)
        audioLoaded = true
        ensureService()
        publish()
    }

    fun togglePlayPause() {
        val s = _snapshot.value
        if (!s.hasPlayer) return
        if (s.audioMode) {
            if (!audioLoaded) {
                s.book?.let { startChapter(it, s.chapterIndex, -1) }
            } else {
                audioFile.toggle()
            }
            ensureService()
            return
        }
        if (!speechLoaded) {
            // 恢复出来的会话还没装载到朗读器：先按上次的句子装载再播。
            s.book?.let { startChapter(it, s.chapterIndex, s.currentSentenceIndex) }
            ensureService()
            return
        }
        speech.togglePlayPause()
        ensureService()
    }

    fun pause() {
        if (_snapshot.value.audioMode) {
            if (audioLoaded) audioFile.pause()
        } else {
            speech.pause()
        }
    }

    fun resume() {
        val s = _snapshot.value
        if (!s.hasPlayer) return
        if (s.audioMode) {
            if (!audioLoaded) {
                s.book?.let { startChapter(it, s.chapterIndex, -1) }
            } else {
                audioFile.resume()
            }
            ensureService()
            publish()
            return
        }
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
        // MP3 模式没有句子可跳转（字幕已移除）；用 seekToPosition 按时间定位。
        if (s.audioMode || s.sentences.isEmpty()) return
        val target = index.coerceIn(0, s.sentences.lastIndex)
        if (!speechLoaded) {
            s.book?.let { startChapter(it, s.chapterIndex, target) }
            return
        }
        speech.seekTo(target)
    }

    fun skipSentences(delta: Int) {
        val s = _snapshot.value
        if (s.audioMode) {
            seekBy(if (delta < 0) -SKIP_MS else SKIP_MS)
            return
        }
        if (s.sentences.isEmpty()) return
        seekToSentence(s.currentSentenceIndex + delta)
    }

    /** Jumps inside the audio file by an absolute position. */
    fun seekToPosition(positionMs: Long) {
        val s = _snapshot.value
        if (!s.audioMode || !audioLoaded) return
        audioFile.seekTo(positionMs)
    }

    /** Jumps forward/backward in the audio file (player buttons, lock screen). */
    fun seekBy(deltaMs: Long) = seekToPosition(_snapshot.value.positionMs + deltaMs)

    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        _snapshot.update { it.copy(speed = clamped) }
        if (_snapshot.value.audioMode) audioFile.setSpeed(clamped) else speech.setSpeed(clamped)
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
        audioLoaded = false
        speech.stop()
        audioFileStop()
        _snapshot.update {
            it.copy(
                book = null,
                sentences = emptyList(),
                currentSentenceIndex = -1,
                isPlaying = false,
                isBuffering = false,
                audioMode = false,
                positionMs = 0L,
                durationMs = 0L,
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

    private fun audioFileStop() {
        audioFile.stop()
    }

    private fun persistProgress() {
        val s = _snapshot.value
        val book = s.book ?: return
        val position = if (s.audioMode) s.positionMs else s.currentSentenceIndex.toLong()
        library?.saveProgress(
            book.id,
            s.chapterIndex,
            position.coerceAtLeast(0L),
        )
    }

    private fun maybePersistAudio(positionMs: Long) {
        if (positionMs - lastPersistedMs < PERSIST_INTERVAL_MS) return
        lastPersistedMs = positionMs
        persistProgress()
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

    private const val SKIP_MS = 15_000L
    private const val PERSIST_INTERVAL_MS = 10_000L
}
