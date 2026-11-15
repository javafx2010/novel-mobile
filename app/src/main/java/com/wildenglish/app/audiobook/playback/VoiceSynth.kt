package com.wildenglish.app.audiobook.playback

import com.wildenglish.app.audiobook.playback.VoiceSynth.normalQueue
import com.wildenglish.app.audiobook.playback.VoiceSynth.priorityQueue
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Single, process-wide owner of the Edge TTS WebSocket: "配音放入全局管理".
 *
 * EVERY speech synthesis goes through here — both the background pre-generation
 * driven by [VoicePrepCenter] and the live cache-miss synthesis in
 * [SpeechController]. A single worker drains requests serially, so there is only
 * ever one WebSocket handshake in flight. Parallel handshakes are exactly what
 * trigger Microsoft's 403 throttling, so this both unifies management and makes
 * playback reliable while a book is still being pre-dubbed.
 *
 * Requests are split into two queues: [priorityQueue] (a sentence the user is
 * actively waiting for after pressing play) and [normalQueue] (background
 * pre-generation). The worker always drains the priority queue first, so
 * "用户点击播放时，插入优先": a playback request jumps ahead of the whole
 * background backlog instead of waiting behind an entire book being pre-dubbed.
 *
 * The scope is a process-wide singleton (like [VoicePrepCenter]) and is never
 * cancelled, so synthesis can outlive the Activity/ViewModel.
 */
object VoiceSynth {

    private val synthesizer = EdgeTtsSynthesizer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val lock = Any()
    private val priorityQueue = ArrayDeque<Request>()
    private val normalQueue = ArrayDeque<Request>()
    private var workerActive = false

    /**
     * Synthesizes one sentence and suspends until its MP3 bytes are ready.
     *
     * @param priority true for live playback (inserted ahead of background work).
     * @throws java.io.IOException when Edge TTS fails (callers already handle it).
     */
    suspend fun synthesize(
        text: String,
        voice: String,
        lang: String,
        priority: Boolean,
    ): ByteArray = suspendCancellableCoroutine { cont ->
        val request = Request(text, voice, lang, cont)
        var startWorker = false
        synchronized(lock) {
            if (priority) priorityQueue.addLast(request) else normalQueue.addLast(request)
            if (!workerActive) {
                workerActive = true
                startWorker = true
            }
        }
        if (startWorker) scope.launch { drain() }
    }

    private suspend fun drain() {
        while (true) {
            val request = synchronized(lock) {
                // Priority first: playback always wins over background dubbing.
                val next = priorityQueue.removeFirstOrNull() ?: normalQueue.removeFirstOrNull()
                if (next == null) workerActive = false
                next
            } ?: return
            // The waiting coroutine may already be gone (seek / stop / release);
            // never start a pointless WebSocket handshake for a stale request.
            if (!request.cont.isActive) continue
            try {
                val bytes = synthesizer.synthesize(request.text, request.voice, request.lang)
                if (request.cont.isActive) request.cont.resume(bytes)
            } catch (e: CancellationException) {
                if (request.cont.isActive) request.cont.resumeWithException(e)
            } catch (e: Throwable) {
                if (request.cont.isActive) request.cont.resumeWithException(e)
            }
        }
    }

    private data class Request(
        val text: String,
        val voice: String,
        val lang: String,
        val cont: CancellableContinuation<ByteArray>,
    )
}
