package com.wildenglish.app.audiobook.playback

import com.wildenglish.app.audiobook.data.AppSettings
import com.wildenglish.app.audiobook.data.Audiobook
import com.wildenglish.app.audiobook.data.VoiceCache
import com.wildenglish.app.audiobook.data.VoiceConfig
import com.wildenglish.app.audiobook.data.loadChapterSentences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Non-empty sentence slots for a book, using the same chapter/index pairing and the same global
 * voice as [VoicePreGenerator] and playback, so readiness checks stay consistent.
 */
internal fun voiceSlots(book: Audiobook): List<VoiceCache.Slot> {
    val voice = AppSettings.voice()
    val slots = ArrayList<VoiceCache.Slot>()
    book.chapters.forEach { chapter ->
        val sentences = loadChapterSentences(chapter)
        sentences.forEachIndexed { index, raw ->
            if (raw.trim().isNotEmpty()) {
                slots.add(VoiceCache.Slot(chapter.index, index, voice.name))
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
    /** True when the last failure came from storage rather than the network. */
    val storageFailed: Boolean = false,
) {
    // An empty book has nothing to synthesize and counts as ready.
    val isComplete: Boolean get() = failed == 0
}

/**
 * Bulk-synthesizes every sentence of a book into [VoiceCache] ahead of time.
 *
 * The whole book uses one voice (the one picked in the settings) — no per-character cast.
 *
 * Sequential on purpose: Microsoft throttles bursts of parallel requests with 403, and each
 * sentence is its own WebSocket handshake. Requests are idempotent from the cache's point of
 * view — an interrupted run leaves completed files behind and the next run skips them, so it is
 * resumable.
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
        val voice = AppSettings.voice()
        val jobs = ArrayList<SentenceJob>()
        book.chapters.forEach { chapter ->
            val sentences = loadChapterSentences(chapter)
            sentences.forEachIndexed { index, raw ->
                val text = raw.trim()
                if (text.isNotEmpty()) {
                    jobs.add(SentenceJob(chapter.index, index, text, voice))
                }
            }
        }

        val total = jobs.size
        // 先算出已有音频（旧命名的文件由 VoiceStore 就地改名后也算命中），进度直接从已完成
        // 的位置开始，不再每次都显示从 0 重配。
        val missing = jobs.filterNot {
            VoiceCache.hasFile(book.id, it.chapterIndex, it.sentenceIndex, it.voice.name)
        }
        val skipped = total - missing.size
        if (missing.isEmpty()) {
            onProgress(total, total)
            VoiceCache.markReady(book)
            return@withContext PrepareResult(total, 0, skipped, 0)
        }
        onProgress(skipped, total)

        var synthesized = 0
        var failed = 0
        var storageFailed = false
        var done = skipped
        var consecutiveFailures = 0

        for (job in missing) {
            currentCoroutineContext().ensureActive()
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
            // 一句合成完立刻落盘（不等整本完成），并使用 NonCancellable：切换书籍 /
            // 用户暂停导致的中断不会丢掉刚合成好的这一句。仅在书本目录被重新导入替换
            // 时拒绝写入，避免把旧书的音频写进新书。
            if (bytes == null || bytes.isEmpty()) {
                failed++
                consecutiveFailures++
            } else {
                val stored = withContext(NonCancellable) {
                    if (File(book.dir, "manifest.json").isFile) {
                        // A failed write means the configured directory is unusable: count it
                        // as a failure (the cache already published the error for the UI).
                        VoiceCache.put(
                            book.id,
                            job.chapterIndex,
                            job.sentenceIndex,
                            job.voice.name,
                            bytes,
                        )
                    } else {
                        false
                    }
                }
                if (stored) {
                    synthesized++
                    consecutiveFailures = 0
                } else {
                    failed++
                    storageFailed = true
                    consecutiveFailures++
                }
            }
            done++
            onProgress(done, total)
            // Network is clearly unavailable (or the directory refuses writes): stop hammering
            // and leave the rest for a later run, which resumes from the files already written.
            if (consecutiveFailures >= ABORT_AFTER_FAILURES) {
                failed += total - done
                break
            }
        }

        val result = PrepareResult(total, synthesized, skipped, failed, storageFailed)
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
