package com.wildenglish.app.audiobook

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wildenglish.app.audiobook.data.AppSettings
import com.wildenglish.app.audiobook.data.Audiobook
import com.wildenglish.app.audiobook.data.AudiobookLibrary
import com.wildenglish.app.audiobook.data.ImportResult
import com.wildenglish.app.audiobook.data.MarketApi
import com.wildenglish.app.audiobook.data.MarketItem
import com.wildenglish.app.audiobook.data.MarketSyncStore
import com.wildenglish.app.audiobook.data.VoiceCache
import com.wildenglish.app.audiobook.data.VoiceStorage
import com.wildenglish.app.audiobook.data.VoiceStorageMode
import com.wildenglish.app.audiobook.playback.PlaybackCenter
import com.wildenglish.app.audiobook.playback.VoicePrepCenter
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
import java.io.File
import java.time.LocalDateTime

class AudioBookViewModel(application: Application) : AndroidViewModel(application) {

    private val library = AudiobookLibrary(application)
    private val readerPrefs = application.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        AudioBookUiState(
            readerTheme = ReaderTheme.fromName(
                readerPrefs.getString(
                    KEY_READER_THEME,
                    null
                )
            )
        )
    )
    val state: StateFlow<AudioBookUiState> = _state.asStateFlow()

    private var sleepJob: Job? = null
    private var marketJob: Job? = null
    private var categoriesJob: Job? = null
    private var syncJob: Job? = null

    init {
        // 第一优先：立刻启动图书加载，首页先出书，其余初始化全部让到首帧之后。
        reload()

        viewModelScope.launch {
            // 让出主线程，等首页第一次组合/图书开始显示后再做其它初始化。
            kotlinx.coroutines.delay(80)
            withContext(Dispatchers.IO) { initAfterFirstFrame() }
        }
    }

    /** 非首屏关键的初始化：设置 / 播放恢复 / 各类 Flow 收集 / 市场同步。 */
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
                    ui.copy(
                        currentBookId = snap.book?.id ?: ui.currentBookId,
                        chapterIndex = if (snap.book != null) snap.chapterIndex else ui.chapterIndex,
                        sentences = if (snap.book != null) snap.sentences else ui.sentences,
                        currentSentenceIndex = if (snap.book != null) {
                            snap.currentSentenceIndex
                        } else {
                            ui.currentSentenceIndex
                        },
                        isPlaying = snap.isPlaying,
                        isBuffering = snap.isBuffering,
                        ttsReady = snap.ttsReady,
                        speed = snap.speed,
                        error = if (snap.error != null || snap.book != null) snap.error else ui.error,
                    )
                }
            }
        }
        // 启动时检查后台是否有已下载书的新版本（静默）。
        syncMarketUpdates()
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
                _state.update { it.copy(error = e.message ?: "书库加载失败") }
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    fun importPackage(uri: Uri) {
        viewModelScope.launch { importUri(uri) }
    }

    /** 多次快速导入串行执行，避免并发写同一本书目录 / 状态互相覆盖导致卡住。 */
    private val importMutex = Mutex()

    /** Shared by the file picker and the market download: import, refresh, open and pre-dub. */
    private suspend fun importUri(uri: Uri, navigate: Boolean = true): ImportResult =
        importMutex.withLock {
            _state.update { it.copy(isImporting = true, error = null) }
            try {
                val result = library.import(uri)
                when (result) {
                    is ImportResult.Success -> {
                        val books = withContext(Dispatchers.IO) { library.listBooks() }
                        _state.update {
                            it.copy(isImporting = false, isLoading = false, books = books)
                        }
                        if (navigate) openBook(result.book.id)
                        // Re-import replaced the whole book dir (including tts/): drop
                        // any in-flight preparation holding the old book and re-queue.
                        VoicePrepCenter.cancel(result.book.id)
                        VoicePrepCenter.enqueue(result.book.id)
                    }

                    is ImportResult.Failure ->
                        _state.update {
                            it.copy(isImporting = false, isLoading = false, error = result.message)
                        }
                }
                result
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.update { it.copy(isImporting = false, isLoading = false) }
                throw e
            } catch (e: Exception) {
                // 任何意外都不能让转圈一直挂着。
                _state.update {
                    it.copy(
                        isImporting = false,
                        isLoading = false,
                        error = e.message ?: "导入失败",
                    )
                }
                ImportResult.Failure(e.message ?: "导入失败")
            }
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
                screen = Screen.DETAIL,
                currentBookId = id,
                chapterIndex = chapter,
                // 上次听到第几句 —— 详情页据此显示「继续朗读」并从该句接着读。
                savedChapterIndex = savedChapter.coerceAtLeast(0),
                savedSentenceIndex = library.sentenceOf(id, chapter),
                // 首页排序不在点击瞬间改变；等从详情返回首页时再按最近访问重排。
                sentences = emptyList(),
                currentSentenceIndex = -1,
                error = null,
            )
        }
        // 首页用的是 DB 轻量书（chapters 为空）；后台做一次完整解析替换，
        // 供详情页目录 / 播放使用，不阻塞切屏。
        if (book.chapters.isEmpty()) {
            viewModelScope.launch {
                // 用轻量解析（只从 manifest 取元数据，不做逐章文件校验）补齐章节标题。
                // 完整 parse 对 465 章要做上千次文件 stat，实测 3 秒，会卡详情页。
                val full = withContext(Dispatchers.IO) {
                    runCatching {
                        com.wildenglish.app.audiobook.data.ManifestParser.parseLight(book.dir)
                    }.getOrNull()
                }
                if (full != null) {
                    // 轻量解析若没找到封面，保留原书封面，避免详情/首页封面消失。
                    val merged = if (full.coverFile == null && book.coverFile != null) {
                        full.copy(coverFile = book.coverFile)
                    } else {
                        full
                    }
                    _state.update { ui ->
                        ui.copy(books = ui.books.map { if (it.id == id) merged else it })
                    }
                }
            }
        }
    }

    // ---- Market (backend /market: browse + download, no purchase) ----

    fun openMarket() {
        _state.update { it.copy(screen = Screen.MARKET, error = null) }
        // 自动同步后台分类 + 检查已下载书的更新版本。
        loadMarketCategories()
        syncMarketUpdates()
        if (_state.value.marketPage < 0 && marketJob?.isActive != true) loadMarket(reset = true)
    }

    fun refreshMarket() {
        loadMarketCategories()
        syncMarketUpdates()
        loadMarket(reset = true)
    }

    /** Switches the market filter to a category (null = 全部) and reloads from page 0. */
    fun selectMarketCategory(categoryId: Long?) {
        if (_state.value.selectedMarketCategoryId == categoryId) return
        marketJob?.cancel()
        _state.update {
            it.copy(
                selectedMarketCategoryId = categoryId,
                marketItems = emptyList(),
                marketPage = -1,
                marketTotalPages = 0,
                marketTotalElements = 0,
            )
        }
        loadMarket(reset = true)
    }

    /**
     * Fetches the category list once per entry/refresh. Prefers the market's own list (only
     * categories that actually have downloadable books, so no dead-end chips); falls back to every
     * backend category when the backend is older. Best effort, silent on failure.
     */
    private fun loadMarketCategories() {
        if (categoriesJob?.isActive == true) return
        categoriesJob = viewModelScope.launch {
            val categories = runCatching {
                withContext(Dispatchers.IO) { MarketApi.fetchMarketCategories() }
            }.getOrNull() ?: runCatching {
                withContext(Dispatchers.IO) { MarketApi.fetchCategories() }
            }.getOrNull() ?: return@launch
            _state.update { it.copy(marketCategories = categories) }
        }
    }

    /** Loads the first page (reset) or the next page, appending to [AudioBookUiState.marketItems]. */
    private fun loadMarket(reset: Boolean) {
        if (marketJob?.isActive == true) return
        val nextPage = if (reset) 0 else (_state.value.marketPage + 1).coerceAtLeast(0)
        marketJob = viewModelScope.launch {
            _state.update { it.copy(marketLoading = true, error = null) }
            try {
                val categoryId = _state.value.selectedMarketCategoryId
                val page = withContext(Dispatchers.IO) {
                    MarketApi.fetchPage(nextPage, MARKET_PAGE_SIZE, categoryId = categoryId)
                }
                _state.update {
                    it.copy(
                        marketLoading = false,
                        marketItems = if (reset) page.items else it.marketItems + page.items,
                        marketPage = page.page,
                        marketTotalPages = page.totalPages,
                        marketTotalElements = page.totalElements,
                    )
                }
                seedKnownItems(page.items)
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        marketLoading = false,
                        error = e.message ?: "市场加载失败"
                    )
                }
            }
        }
    }

    /** Called by the grid footer when it becomes visible. */
    fun loadMoreMarket() {
        val s = _state.value
        if (s.marketLoading || !s.marketHasMore) return
        loadMarket(reset = false)
    }

    /** Downloads a market book's EPUB and imports it into the local library. */
    fun downloadMarketBook(item: MarketItem) {
        if (_state.value.downloadingMarketId != null) return
        viewModelScope.launch {
            val result = downloadAndImport(item, navigate = true)
            if (result is ImportResult.Success) rememberDownloaded(item, result.book.id)
        }
    }

    /**
     * 后台书更新后自动重新下载：遍历从市场下载过的书，按 [MarketItem.updatedAt] 与下载时记录的
     * 时间比较，发现后台版本更新就静默重新下载并覆盖本地。
     *
     * Runs on app start and whenever the market is opened / refreshed.
     */
    fun syncMarketUpdates() {
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            // 避开冷启动首屏：让书架先加载显示，再静默检查书城更新。
            delay(1500)
            val recorded = runCatching { MarketSyncStore.entries(getApplication()) }
                .getOrDefault(emptyMap())
            if (recorded.isEmpty()) return@launch
            val books = withContext(Dispatchers.IO) { library.listBooks() }
            for ((marketId, record) in recorded) {
                if (books.none { it.id == record.localId }) {
                    // The local copy was deleted: forget the record.
                    runCatching { MarketSyncStore.remove(getApplication(), marketId) }
                    continue
                }
                // Never replace the book the reader is currently listening to.
                if (_state.value.currentBookId == record.localId) continue
                val remote = runCatching {
                    withContext(Dispatchers.IO) { MarketApi.fetchBook(marketId) }
                }.getOrNull() ?: continue
                if (!isNewer(remote.updatedAt, record.updatedAt)) continue
                val result = downloadAndImport(remote, navigate = false)
                if (result is ImportResult.Success) rememberDownloaded(remote, result.book.id)
            }
        }
    }

    /** Downloads [item] into the cache and imports it; returns null when the download failed. */
    private suspend fun downloadAndImport(item: MarketItem, navigate: Boolean): ImportResult? {
        if (_state.value.downloadingMarketId != null) return null
        _state.update { it.copy(downloadingMarketId = item.id, error = null) }
        val file = try {
            withContext(Dispatchers.IO) {
                val dir = File(getApplication<Application>().cacheDir, "market").apply { mkdirs() }
                MarketApi.download(item, File(dir, "market-${item.id}.epub"))
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(downloadingMarketId = null, error = e.message ?: "下载失败")
            }
            return null
        }
        return try {
            // Reuse the normal import flow: sniff EPUB/JPD, unpack, (maybe open), pre-dub.
            importUri(Uri.fromFile(file), navigate)
        } finally {
            file.delete()
            _state.update { it.copy(downloadingMarketId = null) }
        }
    }

    /** Records the backend updatedAt of a downloaded book so newer versions can be detected. */
    private fun rememberDownloaded(item: MarketItem, localId: String) {
        val updatedAt = item.updatedAt ?: return
        runCatching { MarketSyncStore.put(getApplication(), item.id, updatedAt, localId) }
    }

    /**
     * First-sight baseline: a market book that matches a local book but has no record yet gets the
     * backend's current updatedAt recorded, so future backend updates can be detected. No download
     * happens here, so switching to this feature never re-downloads the whole shelf.
     */
    private fun seedKnownItems(items: List<MarketItem>) {
        if (items.isEmpty()) return
        val books = _state.value.books
        if (books.isEmpty()) return
        for (item in items) {
            val updatedAt = item.updatedAt ?: continue
            if (MarketSyncStore.get(getApplication(), item.id) != null) continue
            val local = books.firstOrNull {
                it.title == item.title && (it.author ?: "") == (item.author ?: "")
            } ?: continue
            runCatching { MarketSyncStore.put(getApplication(), item.id, updatedAt, local.id) }
        }
    }

    /** True when [remote] is strictly newer than [recorded] (ISO local date-time, string fallback). */
    private fun isNewer(remote: String?, recorded: String): Boolean {
        if (remote.isNullOrBlank() || recorded.isBlank()) return false
        val r = runCatching { LocalDateTime.parse(remote) }.getOrNull()
        val l = runCatching { LocalDateTime.parse(recorded) }.getOrNull()
        return if (r != null && l != null) r.isAfter(l) else remote > recorded
    }

    // ---- Offline voice preparation (delegated to the resident service) ----

    /** Clears the cached track and regenerates it (or resumes an interrupted one). */
    fun prepareVoice(bookId: String) {
        VoicePrepCenter.regenerate(bookId)
    }

    // ---- Settings (朗读音色 / 配音存储目录) ----

    fun openSettings() = _state.update { it.copy(screen = Screen.SETTINGS, error = null) }

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

    // ---- Reader theme ----
    fun setReaderTheme(theme: ReaderTheme) {
        readerPrefs.edit().putString(KEY_READER_THEME, theme.name).apply()
        _state.update { it.copy(readerTheme = theme) }
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

    /** Detail "文稿": read this chapter in the document view. */
    fun openDocument(book: Audiobook, index: Int) {
        val s = _state.value
        if (s.currentBookId == book.id && s.chapterIndex == index && s.sentences.isNotEmpty()) {
            _state.update { it.copy(screen = Screen.READER) }
        } else {
            startChapter(book, index, Screen.READER, -1)
        }
    }

    fun showDocument() {
        if (_state.value.sentences.isEmpty()) return
        _state.update { it.copy(screen = Screen.READER) }
    }

    fun showPlayer() {
        if (_state.value.sentences.isEmpty()) return
        _state.update { it.copy(screen = Screen.PLAYER) }
    }

    /** Mini player tap: open the audiobook view. */
    fun openPlayer() {
        if (_state.value.hasActivePlayer) _state.update { it.copy(screen = Screen.PLAYER) }
    }


    // ---- Playback controls ----

    fun togglePlayPause() = PlaybackCenter.togglePlayPause()

    fun seekToSentence(index: Int) = PlaybackCenter.seekToSentence(index)

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
            Screen.READER -> _state.update { it.copy(screen = Screen.PLAYER) }
            Screen.PLAYER -> _state.update { it.copy(screen = Screen.DETAIL) }
            // 从详情返回首页时才按最近访问重排（点击瞬间不改首页顺序）。
            Screen.DETAIL -> _state.update {
                it.copy(screen = Screen.LIBRARY, books = library.sortByRecentAccess(it.books))
            }
            Screen.MARKET -> _state.update { it.copy(screen = Screen.LIBRARY) }
            Screen.SETTINGS -> _state.update { it.copy(screen = Screen.LIBRARY) }
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
        library.saveProgress(
            book.id,
            s.chapterIndex,
            s.currentSentenceIndex.coerceAtLeast(0).toLong()
        )
    }

    override fun onCleared() {
        sleepJob?.cancel()
        // Deliberately do NOT stop playback or VoicePrepCenter: the resident
        // services keep audio and dubbing alive after the UI is gone.
        super.onCleared()
    }

    private companion object {
        const val KEY_READER_THEME = "reader_theme"
        const val MARKET_PAGE_SIZE = 20
    }
}
