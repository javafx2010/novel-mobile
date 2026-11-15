package com.wildenglish.app.audiobook.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.wildenglish.app.audiobook.data.AppSettings
import com.wildenglish.app.audiobook.data.Audiobook
import com.wildenglish.app.audiobook.data.VoiceCache
import com.wildenglish.app.audiobook.data.VoiceConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads a chapter's sentences aloud, one at a time, preferring locally cached
 * audio produced by [VoicePreGenerator].
 *
 * Smoothness comes from two things that were absent before:
 *  - cached MP3s are read instead of doing a WebSocket round-trip per sentence;
 *  - the next two sentences are resolved into memory while the current one
 *    plays, so advancing never waits on disk or network.
 *
 * Cache misses fall back to live synthesis (and are written back to the cache),
 * so playback still works while pre-generation is in progress or offline audio
 * was deleted. Speed is applied through MediaPlayer PlaybackParams rather than
 * re-synthesizing, so cached audio stays valid at every speed and changing
 * speed never restarts the current sentence.
 */
class SpeechController(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val callback: Callback,
) {

    interface Callback {
        fun onReady(success: Boolean)
        fun onSentenceStart(index: Int)
        fun onChapterComplete()
        fun onPlayingChanged(isPlaying: Boolean)
        fun onBuffering(isBuffering: Boolean) {}
        fun onError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val ioThread = HandlerThread("edge-tts-player").apply { start() }
    private val io = Handler(ioThread.looper)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var sentences: List<String> = emptyList()
    @Volatile
    private var speed = 1.0f
    @Volatile
    private var currentIndex = 0
    @Volatile
    private var playing = false
    @Volatile
    private var released = false
    @Volatile
    private var generation = 0

    /** 整本书用同一个音色朗读（音色在设置中选择，不区分角色）。 */
    @Volatile
    private var voice: VoiceConfig = VoiceConfig.DEFAULT
    @Volatile
    private var bookId: String? = null
    @Volatile
    private var chapterIndex = 0

    /** Coroutines belonging to the current reading run; cancelled on any reset. */
    @Volatile
    private var runScope: CoroutineScope? = null

    /** Resolved audio for upcoming sentences, shared with the prefetch coroutines. */
    private val memory = ConcurrentHashMap<Int, ByteArray>()

    /** In-flight resolutions keyed by sentence index, so prefetch and playback share one request. */
    private val inFlight = ConcurrentHashMap<Int, Deferred<ByteArray?>>()

    /** MediaPlayer is created, prepared and released on the [io] thread only. */
    private var player: MediaPlayer? = null

    init {
        main.post { callback.onReady(true) }
    }

    /** Replaces the current document and starts reading from [startIndex]. */
    fun speak(
        sentences: List<String>,
        startIndex: Int,
        speed: Float,
        book: Audiobook,
        chapterIndex: Int,
    ) = main.post {
        if (released) return@post
        this.sentences = sentences
        this.speed = speed.coerceIn(0.5f, 2.0f)
        this.bookId = book.id
        this.chapterIndex = chapterIndex
        // 全书统一音色：预生成与实时合成都读设置里的同一个音色，缓存才能命中。
        voice = AppSettings.voice()
        playing = true
        callback.onPlayingChanged(true)
        startRun(startIndex.coerceIn(0, (sentences.size - 1).coerceAtLeast(0)))
    }

    fun pause() = main.post {
        if (!playing) return@post
        playing = false
        resetRun()
        callback.onPlayingChanged(false)
        callback.onBuffering(false)
        io.post { stopPlayerLocked() }
    }

    fun resume() = main.post {
        if (playing || released) return@post
        playing = true
        callback.onPlayingChanged(true)
        startRun(currentIndex)
    }

    fun togglePlayPause() = main.post {
        if (released) return@post
        if (playing) {
            playing = false
            resetRun()
            callback.onPlayingChanged(false)
            callback.onBuffering(false)
            io.post { stopPlayerLocked() }
        } else {
            playing = true
            callback.onPlayingChanged(true)
            startRun(currentIndex)
        }
    }

    /** Jumps to a sentence (used by tapping a line in the document). */
    fun seekTo(index: Int) = main.post {
        if (released || sentences.isEmpty()) return@post
        val target = index.coerceIn(0, sentences.lastIndex)
        currentIndex = target
        if (playing) {
            startRun(target)
        } else {
            callback.onSentenceStart(target)
        }
    }

    /** Applies the new speed instantly to the running player — no re-synthesis. */
    fun setSpeed(value: Float) = main.post {
        speed = value.coerceIn(0.5f, 2.0f)
        io.post {
            val p = player ?: return@post
            applySpeedLocked(p)
        }
    }

    fun stop() = main.post {
        playing = false
        resetRun()
        callback.onPlayingChanged(false)
        callback.onBuffering(false)
        io.post { stopPlayerLocked() }
    }

    fun release() {
        if (released) return
        released = true
        generation++
        runScope?.cancel()
        scope.cancel()
        io.post {
            stopPlayerLocked()
            memory.clear()
            inFlight.clear()
            ioThread.quitSafely()
        }
    }

    // ---- run control (main thread) ----

    private fun resetRun() {
        generation++
        runScope?.cancel()
        memory.clear()
        inFlight.clear()
    }

    private fun startRun(startIndex: Int) {
        val gen = ++generation
        runScope?.cancel()
        memory.clear()
        inFlight.clear()
        val run = CoroutineScope(scope.coroutineContext + SupervisorJob())
        runScope = run
        io.post {
            if (released || gen != generation) return@post
            stopPlayerLocked()
            currentIndex = startIndex
            playSentence(gen, run)
        }
    }

    // ---- synthesis / playback (io thread) ----

    private fun playSentence(gen: Int, run: CoroutineScope) {
        if (released || gen != generation || !playing) return
        if (currentIndex !in sentences.indices) return
        val index = currentIndex
        if (sentences[index].trim().isEmpty()) {
            advance(gen, run)
            return
        }

        memory.remove(index)?.let { audio ->
            finalizeSynthesis(gen, run, index, audio)
            return
        }

        if (!VoiceCache.hasFile(bookId.orEmpty(), chapterIndex, index, voice.name)) {
            main.post { if (gen == generation && playing) callback.onBuffering(true) }
        }
        run.launch {
            val audio = awaitAudio(run, index)
            io.post {
                if (released || gen != generation || !playing || index != currentIndex) return@post
                if (audio == null) fail(gen, "语音合成失败，请检查网络或稍后重试")
                else finalizeSynthesis(gen, run, index, audio)
            }
        }
    }

    /** Resolves audio once per index; prefetch and playback share the same request. */
    private fun audioFor(index: Int, run: CoroutineScope): Deferred<ByteArray?> {
        inFlight[index]?.let { return it }
        val created = run.async {
            val bytes = resolveAudio(index)
            // Publish at completion time so a concurrent playSentence never
            // starts a second request for the same sentence.
            if (bytes != null && bytes.isNotEmpty()) memory[index] = bytes
            bytes
        }
        val prior = inFlight.putIfAbsent(index, created)
        if (prior != null) {
            created.cancel()
            return prior
        }
        created.invokeOnCompletion { inFlight.remove(index, created) }
        return created
    }

    private suspend fun awaitAudio(run: CoroutineScope, index: Int): ByteArray? = try {
        audioFor(index, run).await()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Cache hit -> disk; miss -> live synthesis, written back to the cache. */
    private suspend fun resolveAudio(index: Int): ByteArray? {
        val id = bookId ?: return null
        val text = sentences.getOrNull(index)?.trim().orEmpty()
        if (text.isEmpty()) return ByteArray(0)
        VoiceCache.get(id, chapterIndex, index, voice.name)?.let { return it }
        val bytes = try {
            // Live cache-miss: insert at the FRONT of the global synthesis queue
            // so it never waits behind the background dubbing backlog.
            VoiceSynth.synthesize(text, voice.name, voice.lang, priority = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        // 合成一句就立刻落盘：即使这一句还没播完用户就暂停/跳转（本协程被取消），
        // 音频也已经写进配音目录，下次朗读直接命中，不必重新联网合成。
        // 写入失败由 VoiceCache 自己发错误提示，播放继续使用内存里的数据。
        withContext(NonCancellable) {
            VoiceCache.put(id, chapterIndex, index, voice.name, bytes)
        }
        return bytes
    }

    private fun finalizeSynthesis(gen: Int, run: CoroutineScope, index: Int, audio: ByteArray) {
        if (released || gen != generation || !playing || index != currentIndex) return
        if (audio.isEmpty()) {
            advance(gen, run)
            return
        }
        stopPlayerLocked()
        try {
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(ByteArrayMediaDataSource(audio))
            mp.setOnCompletionListener {
                io.post { if (gen == generation && playing && !released) advance(gen, run) }
            }
            mp.setOnErrorListener { _, what, extra ->
                fail(gen, "语音播放失败 ($what/$extra)")
                true
            }
            mp.prepare()
            if (gen != generation || released || !playing) {
                stopPlayerLocked()
                return
            }
            applySpeedLocked(mp)
            mp.start()
            main.post {
                if (gen != generation) return@post
                callback.onBuffering(false)
                callback.onSentenceStart(index)
            }
            prefetch(gen, run, index + 1, index + 2)
        } catch (e: Exception) {
            stopPlayerLocked()
            fail(gen, "语音播放失败：" + (e.message ?: "音频数据无效"))
        }
    }

    /** Warms the following sentences so the next transition has nothing to wait for. */
    private fun prefetch(gen: Int, run: CoroutineScope, vararg indices: Int) {
        for (index in indices) {
            if (index !in sentences.indices || memory.containsKey(index)) continue
            run.launch {
                awaitAudio(run, index)
                if (released || gen != generation) return@launch
                // Keep memory bounded to a small window around the playhead.
                val minKeep = currentIndex - 1
                memory.keys.removeAll { it < minKeep }
            }
        }
    }

    private fun advance(gen: Int, run: CoroutineScope) {
        if (released || gen != generation || !playing) return
        if (currentIndex < sentences.lastIndex) {
            currentIndex++
            playSentence(gen, run)
        } else {
            playing = false
            main.post {
                callback.onPlayingChanged(false)
                callback.onChapterComplete()
            }
        }
    }

    private fun applySpeedLocked(mp: MediaPlayer) {
        try {
            val params = try {
                mp.playbackParams
            } catch (e: IllegalStateException) {
                PlaybackParams()
            }
            mp.playbackParams = params.setSpeed(speed.coerceIn(0.5f, 2.0f))
        } catch (e: IllegalStateException) {
        } catch (e: IllegalArgumentException) {
        }
    }

    private fun stopPlayerLocked() {
        val p = player ?: return
        player = null
        try {
            p.reset()
        } catch (e: Exception) {
        }
        try {
            p.release()
        } catch (e: Exception) {
        }
    }

    private fun fail(gen: Int, message: String) {
        runScope?.cancel()
        main.post {
            if (gen != generation || released) return@post
            playing = false
            callback.onBuffering(false)
            callback.onPlayingChanged(false)
            callback.onError(message)
        }
    }

    /** Feeds an in-memory MP3 to MediaPlayer without a temp file (API 23+). */
    private class ByteArrayMediaDataSource(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position < 0 || position >= data.size) return -1
            val count = minOf(size, (data.size - position).toInt(), buffer.size - offset)
            if (count <= 0) return 0
            System.arraycopy(data, position.toInt(), buffer, offset, count)
            return count
        }

        override fun getSize(): Long = data.size.toLong()
        override fun close() = Unit
    }
}
