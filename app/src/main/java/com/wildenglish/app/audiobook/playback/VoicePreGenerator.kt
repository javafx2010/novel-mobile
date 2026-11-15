package com.wildenglish.app.audiobook.playback

import com.wildenglish.app.audiobook.data.Audiobook
import com.wildenglish.app.audiobook.data.RoleVoices
import com.wildenglish.app.audiobook.data.VoiceCache
import com.wildenglish.app.audiobook.data.VoiceConfig
import com.wildenglish.app.audiobook.data.loadChapterSentences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Non-empty sentence slots for a book, using the same chapter/index pairing and
 * the same per-role voices as [VoicePreGenerator] and playback, so readiness
 * checks stay consistent.
 */
internal fun voiceSlots(book: Audiobook): List<VoiceCache.Slot> {
    val slots = ArrayList<VoiceCache.Slot>()
    book.chapters.forEach { chapter ->
        val sentences = loadChapterSentences(chapter)
        val roles = RoleVoices.assign(sentences)
        sentences.forEachIndexed { index, raw ->
            if (raw.trim().isNotEmpty()) {
                slots.add(VoiceCache.Slot(chapter.index, index, roles[index].voice.name))
            }
        }
    }
    return slots
}

/** Result of one pre-generation run. */
data class PrepareResult(
    val total: Int,
    val synthesized: Int,
    val skipped: Int,
    val failed: Int,
) {
    // An empty book has nothing to synthesize and counts as ready.
    val isComplete: Boolean get() = failed == 0
}

/**
 * Bulk-synthesizes every sentence of a book into [VoiceCache] ahead of time.
 *
 * Sequential on purpose: Microsoft throttles bursts of parallel requests with
 * 403, and each sentence is its own WebSocket handshake. Requests are
 * idempotent from the cache's point of view — an interrupted run leaves
 * completed files behind and the next run skips them, so it is resumable.
 */
class VoicePreGenerator {

    /**
     * @param onProgress invoked with (done, total); may be called from any thread.
     * @throws CancellationException when the calling coroutine is cancelled.
     */
    suspend fun prepare(
        book: Audiobook,
        onProgress: (done: Int, total: Int) -> Unit,
    ): PrepareResult = withContext(Dispatchers.IO) {
        val jobs = ArrayList<SentenceJob>()
        book.chapters.forEach { chapter ->
            val sentences = loadChapterSentences(chapter)
            // 分角色配音：旁白与每个角色各用一个音色，逐句分配。
            val roles = RoleVoices.assign(sentences)
            sentences.forEachIndexed { index, raw ->
                val text = raw.trim()
                if (text.isNotEmpty()) {
                    jobs.add(
                        SentenceJob(chapter.index, index, text, roles[index].voice)
                    )
                }
            }
        }

        val total = jobs.size
        var synthesized = 0
        var skipped = 0
        var failed = 0
        var done = 0
        var consecutiveFailures = 0
        val dir = book.dir

        // Already fully cached (e.g. .ready was lost): reuse files, do not re-synthesize.
        val already = jobs.count {
            VoiceCache.hasFile(dir, it.chapterIndex, it.sentenceIndex, it.voice.name)
        }
        if (already == total) {
            onProgress(total, total)
            VoiceCache.markReady(book)
            return@withContext PrepareResult(total, 0, total, 0)
        }

        for (job in jobs) {
            currentCoroutineContext().ensureActive()
            if (VoiceCache.hasFile(dir, job.chapterIndex, job.sentenceIndex, job.voice.name)) {
                skipped++
                consecutiveFailures = 0
            } else {
                val bytes = try {
                    VoiceSynth.synthesize(
                        job.text,
                        job.voice.name,
                        job.voice.lang,
                        priority = false,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                // A re-import may have replaced the book dir while the request
                // was in flight; never write stale audio into the fresh book.
                currentCoroutineContext().ensureActive()
                if (bytes == null || bytes.isEmpty()) {
                    failed++
                    consecutiveFailures++
                } else {
                    VoiceCache.put(
                        dir,
                        job.chapterIndex,
                        job.sentenceIndex,
                        job.voice.name,
                        bytes,
                    )
                    synthesized++
                    consecutiveFailures = 0
                }
            }
            done++
            onProgress(done, total)
            // Network is clearly unavailable: stop hammering and leave the rest
            // for a later run, which resumes from the files already written.
            if (consecutiveFailures >= ABORT_AFTER_FAILURES) {
                failed += total - done
                break
            }
        }

        val result = PrepareResult(total, synthesized, skipped, failed)
        if (result.isComplete) VoiceCache.markReady(book)
        result
    }

    private data class SentenceJob(
        val chapterIndex: Int,
        val sentenceIndex: Int,
        val text: String,
        val voice: VoiceConfig,
    )

    private companion object {
        const val ABORT_AFTER_FAILURES = 5
    }
}
