package com.wildenglish.app.audiobook

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wildenglish.app.R
import com.wildenglish.app.audiobook.data.AppSettings
import com.wildenglish.app.audiobook.data.Audiobook
import com.wildenglish.app.audiobook.data.AudiobookLibrary
import com.wildenglish.app.audiobook.data.ImportResult
import com.wildenglish.app.audiobook.data.VoiceCache
import com.wildenglish.app.audiobook.data.VoiceStorage
import com.wildenglish.app.audiobook.data.VoiceStorageMode
import com.wildenglish.app.audiobook.playback.PlaybackCenter
import com.wildenglish.app.audiobook.playback.VoicePrepCenter
import com.wildenglish.app.i18n.AppLanguage
import com.wildenglish.app.i18n.LocaleHelper
import com.wildenglish.app.i18n.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AudioBookViewModel(application: Application) : AndroidViewModel(application) {

    private val library = AudiobookLibrary(application)

    private val _state = MutableStateFlow(
        AudioBookUiState(language = LocaleHelper.current(application))
    )
    val state: StateFlow<AudioBookUiState> = _state.asStateFlow()

    private var sleepJob: Job? = null

    init {
        // 第一优先：立刻启动图书加载，首页先出书，其余初始化全部让到首帧之后。
        reload()

        viewModelScope.launch {
            // 让出主线程，等首页第一次组合/图书开始显示后再做其它初始化。
            kotlinx.coroutines.delay(80)
            withContext(Dispatchers.IO) { initAfterFirstFrame() }
        }
    }

    /** 非首屏关键的初始化：设置 / 播放恢复 / 各类 Flow 收集。 */
    private fun initAfterFirstFrame() {
        AppSettings.init(getApplication())
        // 音色与配音目录属于全局设置，改完界面立即反映（并用于“设置”页展示）。
        viewModelScope.launch {
            AppSettings.state.collect { settings ->
                val path = withContext(Dispatchers.IO) { VoiceStorage.describe(getApplication()) }
                _state.update {
                    it.copy(
                        voice = settings.voice,
                        storageMode = settings.storageMode,
                        customTreeUri = settings.customTreeUri,
                        storagePath = path,
                        showSentence = settings.showSentence,
                    )
                }
            }
        }
        // 配音文件写入失败（SD 卡不可写等）直接提示用户。
        viewModelScope.launch {
            VoiceCache.error.collect { message ->
                if (message != null) _state.update { it.copy(error = message) }
            }
        }
        // Mirror the resident service's observable state into the UI.
        viewModelScope.launch {
            VoicePrepCenter.progress.collect { p -> _state.update { it.copy(voicePrep = p) } }
        }
        viewModelScope.launch {
            VoicePrepCenter.readyIds.collect { r -> _state.update { it.copy(readyVoiceBookIds = r) } }
        }
        PlaybackCenter.init(getApplication())
        viewModelScope.launch {
            PlaybackCenter.snapshot.collect { snap ->
                _state.update { ui ->
                    // 用户已进入选碟页/播放页时，书名由 openBook 决定，不能被
                    // 冷启动恢复的播放会话或正在播放的其它书覆盖。
                    val followPlayback = snap.book != null && ui.screen != Screen.DETAIL
                    ui.copy(
                        currentBookId = if (followPlayback && ui.screen == Screen.LIBRARY) {
                            snap.book?.id ?: ui.currentBookId
                        } else {
                            ui.currentBookId
                        },
                        chapterIndex = if (followPlayback) snap.chapterIndex else ui.chapterIndex,
                        sentences = if (followPlayback) snap.sentences else ui.sentences,
                        currentSentenceIndex = if (followPlayback) {
                            snap.currentSentenceIndex
                        } else {
                            ui.currentSentenceIndex
                        },
                        isPlaying = snap.isPlaying,
                        isBuffering = snap.isBuffering,
                        ttsReady = snap.ttsReady,
                        audioMode = snap.audioMode,
                        positionMs = snap.positionMs,
                        durationMs = snap.durationMs,
                        speed = snap.speed,
                        error = if (snap.error != null || snap.book != null) snap.error else ui.error,
                    )
                }
            }
        }
    }

    // ---- Library ----

    private var reloadJob: Job? = null

    /** 渐进加载：解析完一部就显示一部，不再等整库扫描完成才刷新。 */
    fun reload() {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, books = emptyList(), error = null) }
            try {
                val all = withContext(Dispatchers.IO) { library.loadBooksSnapshot() }
                _state.update { it.copy(isLoading = false, books = all) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(error = e.message ?: Strings.get(R.string.error_library_failed))
                }
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    fun importPackage(uri: Uri) {
        viewModelScope.launch { importUri(uri) }
    }

    /**
     * 其他应用分享进来的单个 / 多个音频文件：合并为一本有声书（每个文件一章）。
     */
    fun importAudioFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (uris.size == 1) {
            importPackage(uris.first())
            return
        }
        viewModelScope.launch {
            importMutex.withLock {
                runImport({ library.importAudioFiles(uris) }, navigate = true)
            }
        }
    }

    /** 多次快速导入串行执行，避免并发写同一本书目录 / 状态互相覆盖导致卡住。 */
    private val importMutex = Mutex()

    /** Shared by the file picker / external share: import, refresh, open and pre-dub. */
    private suspend fun importUri(uri: Uri, navigate: Boolean = true): ImportResult =
        importMutex.withLock {
            runImport({ library.import(uri) }, navigate)
        }

    private suspend fun runImport(
        block: suspend () -> ImportResult,
        navigate: Boolean,
    ): ImportResult {
        _state.update { it.copy(isImporting = true, error = null) }
        val result = try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            _state.update { it.copy(isImporting = false, isLoading = false) }
            throw e
        } catch (e: Exception) {
            // 任何意外都不能让转圈一直挂着。
            _state.update {
                it.copy(
                    isImporting = false,
                    isLoading = false,
                    error = e.message ?: Strings.get(R.string.error_import_failed),
                )
            }
            ImportResult.Failure(e.message ?: Strings.get(R.string.error_import_failed))
        }
        when (result) {
            is ImportResult.Success -> {
                val books = withContext(Dispatchers.IO) { library.listBooks() }
                _state.update {
                    it.copy(isImporting = false, isLoading = false, books = books)
                }
                if (navigate) openBook(result.book.id)
                // Re-import replaced the whole book dir (including tts/): drop
                // any in-flight preparation holding the old book and re-queue.
                // 音频书直接播放 MP3，不需要 Edge TTS 配音。
                VoicePrepCenter.cancel(result.book.id)
                if (!result.book.audioMode) VoicePrepCenter.enqueue(result.book.id)
            }

            is ImportResult.Failure ->
                _state.update {
                    it.copy(isImporting = false, isLoading = false, error = result.message)
                }
        }
        return result
    }

    fun deleteBook(book: Audiobook) {
        val id = book.id
        viewModelScope.launch {
            VoicePrepCenter.cancel(id)
            if (_state.value.currentBookId == id) PlaybackCenter.stop()
            // 直接用 book.dir 删除，不再靠 id 重新拼目录名（id 可能和目录名不一致）。
            withContext(Dispatchers.IO) { library.delete(book) }
            val books = withContext(Dispatchers.IO) { library.listBooks() }
            _state.update {
                val wasCurrent = it.currentBookId == id
                it.copy(
                    books = books,
                    currentBookId = if (wasCurrent) null else it.currentBookId,
                    screen = if (wasCurrent) Screen.LIBRARY else it.screen,
                    sentences = if (wasCurrent) emptyList() else it.sentences,
                    isPlaying = if (wasCurrent) false else it.isPlaying,
                )
            }
        }
    }

    fun openBook(id: String) {
        val book = _state.value.books.firstOrNull { it.id == id } ?: return
        // 打开书即记为一次访问：首页会按最近访问优先排序（key 用目录名）。
        library.touchAccess(book.dir.name)
        val (savedChapter, _) = library.progressOf(id)
        val chapter = savedChapter.coerceAtLeast(0)
        _state.update {
            it.copy(
                currentBookId = id,
                chapterIndex = chapter,
                // 上次听到第几句 —— 详情页据此显示「继续朗读」并从该句接着读。
                savedChapterIndex = chapter,
                savedSentenceIndex = library.sentenceOf(id, chapter),
                savedPositionMs = library.positionOf(id, chapter),
                sentences = emptyList(),
                currentSentenceIndex = -1,
                error = null,
            )
        }
        // 章节已解析时可以直接判断：MP3 书跳过选碟页，直接进播放器。
        if (book.chapters.isNotEmpty()) {
            if (book.audioMode) {
                startChapter(book, chapter.coerceIn(0, book.chapters.lastIndex), Screen.PLAYER, -1)
            } else {
                _state.update { it.copy(screen = Screen.DETAIL) }
            }
            return
        }
        // 首页用的是 DB 轻量书（chapters 为空）：先异步补齐再决定去向，
        // 这样 MP3 书不会先闪一帧选碟页。
        viewModelScope.launch {
            // 用轻量解析（只从 manifest 取元数据，不做逐章文件校验）补齐章节标题。
            // 完整 parse 对 465 章要做上千次文件 stat，实测 3 秒，会卡详情页。
            val full = withContext(Dispatchers.IO) {
                runCatching {
                    com.wildenglish.app.audiobook.data.ManifestParser.parseLight(book.dir)
                }.getOrNull()
            }
            val resolved = full?.let { f ->
                // 轻量解析若没找到封面，保留原书封面，避免详情/首页封面消失。
                if (f.coverFile == null && book.coverFile != null) {
                    f.copy(coverFile = book.coverFile)
                } else {
                    f
                }
            }
            if (resolved != null) {
                _state.update { ui ->
                    ui.copy(
                        books = ui.books.map { if (it.id == id) resolved else it },
                        currentBookId = id,
                    )
                }
            }
            val target = resolved ?: book
            if (target.audioMode && target.chapters.isNotEmpty()) {
                startChapter(
                    target,
                    chapter.coerceIn(0, target.chapters.lastIndex),
                    Screen.PLAYER,
                    -1,
                )
            } else {
                _state.update {
                    it.copy(screen = Screen.DETAIL, currentBookId = id)
                }
            }
        }
    }

    // ---- Offline voice preparation (delegated to the resident service) ----

    /** Clears the cached track and regenerates it (or resumes an interrupted one). */
    fun prepareVoice(bookId: String) {
        VoicePrepCenter.regenerate(bookId)
    }

    // ---- Settings (朗读音色 / 配音存储目录) ----

    fun openSettings() = _state.update { it.copy(screen = Screen.SETTINGS, error = null) }

    fun openVoice() = _state.update { it.copy(screen = Screen.VOICE, error = null) }

    fun openStorage() = _state.update { it.copy(screen = Screen.STORAGE, error = null) }

    fun openPlaybackSettings() =
        _state.update { it.copy(screen = Screen.PLAYBACK, error = null) }

    fun openLanguage() = _state.update { it.copy(screen = Screen.LANGUAGE, error = null) }

    /** 切换界面语言：持久化到偏好，宿主 Activity 结束自身，用户重进时加载新资源。 */
    fun setLanguage(language: AppLanguage) {
        if (_state.value.language == language) return
        LocaleHelper.set(getApplication(), language)
        _state.update {
            it.copy(language = language, languageChangeToken = it.languageChangeToken + 1)
        }
    }

    /**
     * 换朗读音色。整本书统一用这个音色，已缓存的音频是按旧音色生成的，
     * 因此让后台按新音色重新生成（旧文件不再命中）。
     */
    fun setVoice(name: String) {
        if (_state.value.voice.name == name) return
        AppSettings.setVoice(name)
        VoicePrepCenter.onSettingsChanged()
    }

    /** 换配音文件存储位置；新目录里没有旧音频，未生成的书会重新排队配音。 */
    fun setStorageMode(mode: VoiceStorageMode) {
        if (_state.value.storageMode == mode) return
        AppSettings.setStorageMode(mode)
        VoicePrepCenter.onSettingsChanged()
    }

    /** 用户在系统文件选择器里选定的自定义配音目录（长期授权）。 */
    fun setStorageTree(uri: Uri?) {
        val target = uri ?: return
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                target,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        AppSettings.setCustomTreeUri(target.toString())
        AppSettings.setStorageMode(VoiceStorageMode.CUSTOM)
        VoicePrepCenter.onSettingsChanged()
    }

    /** 播放页是否展示当前朗读句（纯展示开关，不影响配音）。 */
    fun setShowSentence(show: Boolean) {
        AppSettings.setShowSentence(show)
    }

    // ---- Reading ----

    /** Loads a chapter's sentences and starts reading it in [screen]. */
    private fun startChapter(book: Audiobook, index: Int, screen: Screen, startSentence: Int = -1) {
        if (book.chapters.getOrNull(index) == null) return
        _state.update {
            it.copy(
                screen = screen,
                currentBookId = book.id,
                chapterIndex = index,
                error = null
            )
        }
        PlaybackCenter.startChapter(book, index, startSentence)
    }

    /** Tap a chapter: read it in the audiobook view. */
    fun playChapter(book: Audiobook, index: Int) =
        startChapter(book, index, Screen.PLAYER, 0)

    /** Detail "播放": resume the last chapter/sentence in the audiobook view. */
    fun playSaved() {
        val book = _state.value.currentBook ?: return
        if (book.chapters.isEmpty()) return
        val (chapter, _) = library.progressOf(book.id)
        startChapter(book, chapter.coerceIn(0, book.chapters.lastIndex), Screen.PLAYER, -1)
    }

    /** Mini player tap: open the audiobook view. */
    fun openPlayer() {
        if (_state.value.hasActivePlayer) _state.update { it.copy(screen = Screen.PLAYER) }
    }


    // ---- Playback controls ----

    fun togglePlayPause() = PlaybackCenter.togglePlayPause()

    fun seekToSentence(index: Int) = PlaybackCenter.seekToSentence(index)

    /** 音频模式：跳到音频文件的绝对位置。 */
    fun seekToPosition(positionMs: Long) = PlaybackCenter.seekToPosition(positionMs)

    /** 音频模式：前进 / 后退若干毫秒。 */
    fun seekBy(deltaMs: Long) = PlaybackCenter.seekBy(deltaMs)

    fun skipSentences(delta: Int) = PlaybackCenter.skipSentences(delta)

    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        _state.update { it.copy(speed = clamped) }
        PlaybackCenter.setSpeed(clamped)
    }

    fun nextChapter() {
        val s = _state.value
        val book = s.currentBook ?: return
        if (s.chapterIndex + 1 < book.chapters.size) {
            startChapter(book, s.chapterIndex + 1, s.screen, 0)
        }
    }

    fun previousChapter() {
        val s = _state.value
        val book = s.currentBook ?: return
        if (s.chapterIndex > 0) startChapter(book, s.chapterIndex - 1, s.screen, 0)
    }

    private fun playNextChapter() {
        val s = _state.value
        val book = s.currentBook ?: return
        val next = s.chapterIndex + 1
        if (next < book.chapters.size) {
            startChapter(book, next, s.screen, 0)
        } else {
            _state.update { it.copy(isPlaying = false) }
        }
    }

    // ---- Sleep timer ----

    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) {
            _state.update { it.copy(sleepRemainingSeconds = 0) }
            return
        }
        var remaining = minutes * 60
        _state.update { it.copy(sleepRemainingSeconds = remaining) }
        sleepJob = viewModelScope.launch {
            while (remaining > 0) {
                delay(1000L)
                remaining--
                _state.update { it.copy(sleepRemainingSeconds = remaining) }
            }
            PlaybackCenter.pause()
            persistProgress()
        }
    }

    // ---- Navigation / misc ----

    fun goBack() {
        when (_state.value.screen) {
            // MP3 书跳过了选碟页，返回时也应直接回书架，而不是冒出选碟页。
            Screen.PLAYER -> _state.update {
                val audio = it.currentBook?.audioMode == true
                it.copy(
                    screen = if (audio) Screen.LIBRARY else Screen.DETAIL,
                    books = if (audio) library.sortByRecentAccess(it.books) else it.books,
                )
            }
            // 从详情返回首页时才按最近访问重排（点击瞬间不改首页顺序）。
            Screen.DETAIL -> _state.update {
                it.copy(screen = Screen.LIBRARY, books = library.sortByRecentAccess(it.books))
            }
            Screen.SETTINGS -> _state.update { it.copy(screen = Screen.LIBRARY) }
            Screen.VOICE -> _state.update { it.copy(screen = Screen.SETTINGS) }
            Screen.STORAGE -> _state.update { it.copy(screen = Screen.SETTINGS) }
            Screen.PLAYBACK -> _state.update { it.copy(screen = Screen.SETTINGS) }
            Screen.LANGUAGE -> _state.update { it.copy(screen = Screen.SETTINGS) }
            Screen.LIBRARY -> Unit
        }
    }

    fun dismissError() {
        PlaybackCenter.dismissError()
        VoiceCache.dismissError()
        _state.update { it.copy(error = null) }
    }

    private fun persistProgress() {
        val s = _state.value
        val book = s.currentBook ?: return
        val position = if (s.audioMode) s.positionMs else s.currentSentenceIndex.toLong()
        library.saveProgress(book.id, s.chapterIndex, position.coerceAtLeast(0L))
    }

    override fun onCleared() {
        sleepJob?.cancel()
        // Deliberately do NOT stop playback or VoicePrepCenter: the resident
        // services keep audio and dubbing alive after the UI is gone.
        super.onCleared()
    }

}
