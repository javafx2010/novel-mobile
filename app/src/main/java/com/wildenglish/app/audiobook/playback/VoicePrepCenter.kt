package com.wildenglish.app.audiobook.playback

import android.app.Application
import android.content.Intent
import com.wildenglish.app.audiobook.VoicePrepProgress
import com.wildenglish.app.audiobook.data.AppSettings
import com.wildenglish.app.audiobook.data.AudiobookLibrary
import com.wildenglish.app.audiobook.data.VoiceCache
import kotlinx.coroutines.CancellationException
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

/**
 * Process-wide owner of offline voice pre-generation.
 *
 * Lives in a singleton rather than a ViewModel so dubbing keeps running after
 * the Activity and its ViewModel are gone. [VoicePrepService] is the resident
 * foreground host that keeps the process alive and shows the tray notification;
 * this object owns the actual work and the observable state.
 */
object VoicePrepCenter {

    /** Snapshot of the current background work, consumed by the tray notification. */
    data class Status(
        val isWorking: Boolean = false,
        val activeBookTitle: String? = null,
        val done: Int = 0,
        val total: Int = 0,
        val pendingCount: Int = 0,
    )

    private var app: Application? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generator by lazy { VoicePreGenerator() }

    private val lock = Any()
    private val queue = ArrayDeque<String>()
    private var runnerJob: Job? = null
    private var runnerToken: Any? = null
    private var preparingId: String? = null
    private var running = false

    private val _progress = MutableStateFlow<Map<String, VoicePrepProgress>>(emptyMap())
    val progress: StateFlow<Map<String, VoicePrepProgress>> = _progress.asStateFlow()

    private val _readyIds = MutableStateFlow<Set<String>>(emptySet())
    val readyIds: StateFlow<Set<String>> = _readyIds.asStateFlow()

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    fun init(application: Application) {
        app = application
        AppSettings.init(application)
        VoiceCache.init(application)
    }

    /**
     * 设置里的音色或配音目录变了：已有的离线音频不再匹配，停止当前任务并重新扫描书库，
     * 未按新音色生成的书会重新排队配音。
     */
    fun onSettingsChanged() {
        stopAll()
        // 扫描统一交给后台服务，不在前台直接跑。
        ensureService()
    }

    /**
     * 开机 / 后台入口：拉起常驻服务，由服务的 onStartCommand 统一触发 refresh。
     * 首页 Activity 不调用它，避免首屏加载时抢 IO。
     */
    fun onAppStart() {
        ensureService()
    }

    /** Starts (or re-starts) the resident foreground service that owns the tray. */
    fun ensureService() {
        val ctx = app ?: return
        runCatching { ctx.startForegroundService(Intent(ctx, VoicePrepService::class.java)) }
    }

    /** Re-scans the library, recomputes ready books and enqueues everything unfinished. */
    fun refresh() {
        val ctx = app ?: return
        scope.launch {
            val books = withContext(Dispatchers.IO) { AudiobookLibrary(ctx).listBooks() }
            val ready = LinkedHashSet<String>()
            val pending = ArrayList<String>()
            withContext(Dispatchers.IO) {
                for (book in books) {
                    // 只读 .ready 标记（O(1)），不再逐句 exists() 扫整本书 —— 那是首屏
                    // 卡顿的主因。文件已齐但标记丢失的情况由后台 VoicePreGenerator
                    // 自己算缺失并补写标记，功能等价且不阻塞启动。
                    if (VoiceCache.isReady(book)) ready.add(book.id) else pending.add(book.id)
                }
            }
            _readyIds.value = ready
            // Drop stale "配音中" badges for books that are already complete.
            if (ready.isNotEmpty()) {
                _progress.update { it.filterKeys { id -> id !in ready } }
            }
            pending.forEach { enqueueInternal(it) }
            startRunner()
        }
    }

    /** Adds one book to the dubbing queue (used after import). */
    fun enqueue(bookId: String) {
        ensureService()
        enqueueInternal(bookId)
        startRunner()
    }

    /**
     * Inserts a book at the FRONT of the global dubbing queue. Called when the
     * user starts playing it, so the book being listened to is dubbed first.
     *
     * If a different book is currently being generated, its run is cancelled and
     * pushed back to the tail. Pre-generation is resumable — every MP3 already
     * written is kept and skipped on the next pass — so nothing is lost; the
     * interrupted book simply continues after the priority one. A book that is
     * already fully dubbed (or already being dubbed) is a no-op.
     */
    fun prioritize(bookId: String) {
        if (_readyIds.value.contains(bookId)) return
        ensureService()
        val alreadyPreparing = synchronized(lock) {
            if (preparingId == bookId) {
                true
            } else {
                queue.remove(bookId)
                queue.addFirst(bookId)
                val current = preparingId
                if (current != null) {
                    runnerJob?.cancel()
                    runnerToken = null
                    runnerJob = null
                    preparingId = null
                    running = false
                    queue.addLast(current)
                }
                false
            }
        }
        if (alreadyPreparing) return
        // Keep any partial progress already recorded for this book.
        _progress.update { if (bookId in it) it else it + (bookId to VoicePrepProgress(active = true)) }
        startRunner()
    }

    /** Cancels any work for a book (delete, or a re-import that replaced its directory). */
    fun cancel(bookId: String) {
        var restart = false
        synchronized(lock) {
            queue.remove(bookId)
            if (preparingId == bookId) {
                runnerJob?.cancel()
                runnerToken = null
                runnerJob = null
                preparingId = null
                running = false
                restart = true
            }
        }
        _progress.value = _progress.value - bookId
        _readyIds.value = _readyIds.value - bookId
        if (restart) startRunner()
    }

    /** Clears a book's cache and regenerates it from scratch. */
    fun regenerate(bookId: String) {
        ensureService()
        cancel(bookId)
        scope.launch {
            withContext(Dispatchers.IO) {
                val ctx = app
                val book = ctx?.let {
                    AudiobookLibrary(it).listBooks().firstOrNull { b -> b.id == bookId }
                }
                book?.let { VoiceCache.clear(it) }
            }
            enqueueInternal(bookId)
            startRunner()
        }
    }

    /** Stops all work; the service removes its foreground notification. */
    fun stopAll() {
        synchronized(lock) {
            queue.clear()
            runnerJob?.cancel()
            runnerToken = null
            runnerJob = null
            preparingId = null
            running = false
        }
        _progress.value = emptyMap()
        _status.value = Status()
    }

    private fun enqueueInternal(bookId: String) {
        val added = synchronized(lock) {
            if (preparingId == bookId || queue.contains(bookId)) {
                false
            } else {
                queue.addLast(bookId)
                true
            }
        }
        if (added) {
            _progress.value = _progress.value + (bookId to VoicePrepProgress(active = true))
        }
    }

    private fun startRunner() {
        val token = Any()
        synchronized(lock) {
            if (running) return
            running = true
            runnerToken = token
            // Assign the token/job under the same lock as the running flag so a
            // concurrent cancel() cannot slip in between and spawn a 2nd runner.
            runnerJob = scope.launch {
                var becameIdle = false
                try {
                    while (true) {
                        val bookId = synchronized(lock) {
                            val id = queue.removeFirstOrNull()
                            if (id == null) running = false
                            id
                        } ?: break
                        processBook(bookId)
                    }
                } finally {
                    synchronized(lock) {
                        if (runnerToken === token) {
                            runnerToken = null
                            runnerJob = null
                            preparingId = null
                            running = false
                            becameIdle = true
                        }
                    }
                    if (becameIdle) _status.value = Status()
                }
            }
        }
    }

    private suspend fun processBook(bookId: String) {
        val ctx = app ?: return
        val book = withContext(Dispatchers.IO) {
            AudiobookLibrary(ctx).listBooks().firstOrNull { it.id == bookId }
        }
        if (book == null) {
            _progress.value = _progress.value - bookId
            return
        }
        synchronized(lock) { preparingId = bookId }
        _status.value =
            Status(isWorking = true, activeBookTitle = book.title, pendingCount = pendingCount())
        val result = try {
            generator.prepare(book) { done, total ->
                _progress.value = _progress.value +
                        (bookId to VoicePrepProgress(done, total, 0, true))
                _status.value = Status(true, book.title, done, total, pendingCount())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        synchronized(lock) { preparingId = null }
        _progress.value = _progress.value - bookId
        if (result != null && result.isComplete) {
            _readyIds.value = _readyIds.value + bookId
        }
    }

    private fun pendingCount(): Int = synchronized(lock) {
        queue.size + if (preparingId != null) 1 else 0
    }
}
