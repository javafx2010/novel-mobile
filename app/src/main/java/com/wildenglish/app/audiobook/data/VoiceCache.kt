package com.wildenglish.app.audiobook.data

import android.content.Context
import com.wildenglish.app.R
import com.wildenglish.app.i18n.Strings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * On-device cache of synthesized sentence audio (MP3), one file per sentence.
 *
 * Every book is read with a single voice ([AppSettings.voice]), so the file name is
 * `<sentence>.<voice>.mp3` — switching voices simply stops matching the old files. Where the files
 * live is configured in the settings ([VoiceStorage]): app-internal storage, the app-specific
 * external directory, or a folder the user picked with the system picker.
 *
 * A `.ready` marker records "this book is fully dubbed **with this voice**", so a voice change
 * also invalidates the marker and the book is dubbed again.
 *
 * Write failures are never silent: [error] publishes a message the UI shows, because a full or
 * removed SD card must be visible to the user instead of looking like "audio just won't play".
 */
object VoiceCache {

    private const val CACHE_VERSION = "3"

    @Volatile
    private var app: Context? = null

    private val _error = MutableStateFlow<String?>(null)

    /** Last storage failure ("配音文件保存失败…"), consumed by the UI as a snackbar. */
    val error: StateFlow<String?> = _error.asStateFlow()

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun dismissError() {
        _error.value = null
    }

    private fun store(): VoiceStore? {
        val context = app ?: return null
        val resolved = VoiceStorage.resolve(context)
        // A directory that became unusable (card removed, permission revoked) is reported
        // once per change; repeating the same value is a no-op for StateFlow.
        resolved.error?.let { _error.value = it }
        return resolved.store
    }

    private fun markerText(): String = "$CACHE_VERSION|${AppSettings.voice().name}"

    fun isReady(book: Audiobook): Boolean {
        val marker = store()?.readMarker(book.id)?.trim() ?: return false
        return marker == markerText()
    }

    fun markReady(book: Audiobook) {
        val store = store() ?: return
        if (!store.writeMarker(book.id, markerText())) reportWriteFailure(store)
    }

    /** Cheap existence check used to decide whether to show the buffering state. */
    fun hasFile(
        bookId: String,
        chapterIndex: Int,
        sentenceIndex: Int,
        voice: String,
    ): Boolean = store()?.exists(bookId, chapterIndex, sentenceIndex, voice) ?: false

    /** Returns cached MP3 bytes, or null when missing/empty/corrupt. */
    fun get(
        bookId: String,
        chapterIndex: Int,
        sentenceIndex: Int,
        voice: String,
    ): ByteArray? = store()?.read(bookId, chapterIndex, sentenceIndex, voice)

    /**
     * Stores MP3 bytes for one sentence.
     *
     * @return false when the configured directory refused the write; the failure is also
     * published through [error] so the user is told immediately.
     */
    fun put(
        bookId: String,
        chapterIndex: Int,
        sentenceIndex: Int,
        voice: String,
        bytes: ByteArray,
    ): Boolean {
        if (bytes.isEmpty()) return false
        val store = store() ?: return false
        // Already cached (e.g. written by the pre-generator): keep it, no duplicate write.
        if (store.exists(bookId, chapterIndex, sentenceIndex, voice)) return true
        val ok = store.write(bookId, chapterIndex, sentenceIndex, voice, bytes)
        if (!ok) reportWriteFailure(store)
        return ok
    }

    /** Clears generated audio (e.g. before regenerating). Keeps the book itself. */
    fun clear(book: Audiobook) {
        store()?.deleteBook(book.id)
        // Legacy single-voice cache inside the book directory is no longer read.
        runCatching { File(book.dir, "tts").deleteRecursively() }
    }

    /** Clears audio stored outside the book dir for a re-imported / deleted id. */
    fun clearForId(id: String) {
        store()?.deleteBook(id)
    }

    private fun reportWriteFailure(store: VoiceStore) {
        _error.value = Strings.get(R.string.voice_save_failed_detail, store.label) +
                " " + Strings.get(R.string.voice_change_dir_hint)
    }
}
