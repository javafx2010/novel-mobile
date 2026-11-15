package com.wildenglish.app.audiobook.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.UUID

/** File-name-safe token for a voice (`zh-CN-YunxiNeural` -> `zh-CN-Yunxi`). */
internal fun voiceKey(voice: String): String =
    voice.removeSuffix("Neural").replace(Regex("[^A-Za-z0-9-]"), "-")

/** Per-sentence audio file name; the voice is part of it, so switching voices never mixes audio. */
internal fun audioFileName(sentenceIndex: Int, voice: String): String =
    "$sentenceIndex.${voiceKey(voice)}.mp3"

/**
 * Physical storage of synthesized voice files.
 *
 * Layout under the configured root: `<bookId>/c<chapter>/<sentence>.<voice>.mp3` plus a
 * `<bookId>/.ready` marker. Two backends implement it: a plain [File] tree (internal storage or
 * the app-specific external directory) and a SAF tree the user picked in the settings.
 *
 * Every write reports success/failure instead of silently succeeding, so the UI can tell the user
 * when the SD card / chosen folder cannot be written to.
 */
interface VoiceStore {

    /** Human-readable location, shown in the settings screen and in error messages. */
    val label: String

    fun exists(bookId: String, chapterIndex: Int, sentenceIndex: Int, voice: String): Boolean

    fun read(bookId: String, chapterIndex: Int, sentenceIndex: Int, voice: String): ByteArray?

    /** @return false when the file could not be stored (no space, card removed, permission lost). */
    fun write(
        bookId: String,
        chapterIndex: Int,
        sentenceIndex: Int,
        voice: String,
        bytes: ByteArray,
    ): Boolean

    fun readMarker(bookId: String): String?

    fun writeMarker(bookId: String, text: String): Boolean

    fun deleteBook(bookId: String)
}

/** Storage in a normal directory (app-internal files, or the app-specific external dir). */
class FileVoiceStore(private val root: File) : VoiceStore {

    override val label: String get() = root.absolutePath

    private fun bookDir(bookId: String) = File(root, bookId)

    private fun chapterDir(bookId: String, chapterIndex: Int) = File(bookDir(bookId), "c$chapterIndex")

    private fun audioFile(bookId: String, chapterIndex: Int, sentenceIndex: Int, voice: String) =
        File(chapterDir(bookId, chapterIndex), audioFileName(sentenceIndex, voice))

    override fun exists(bookId: String, chapterIndex: Int, sentenceIndex: Int, voice: String): Boolean =
        runCatching {
            val file = audioFile(bookId, chapterIndex, sentenceIndex, voice)
            file.isFile && file.length() > 0
        }.getOrDefault(false)

    override fun read(
        bookId: String,
        chapterIndex: Int,
        sentenceIndex: Int,
        voice: String,
    ): ByteArray? = runCatching {
        val file = audioFile(bookId, chapterIndex, sentenceIndex, voice)
        if (file.isFile && file.length() > 0) file.readBytes() else null
    }.getOrNull()

    override fun write(
        bookId: String,
        chapterIndex: Int,
        sentenceIndex: Int,
        voice: String,
        bytes: ByteArray,
    ): Boolean = try {
        val target = audioFile(bookId, chapterIndex, sentenceIndex, voice)
        val parent = target.parentFile
        when {
            bytes.isEmpty() || parent == null -> false
            !parent.isDirectory && !parent.mkdirs() -> false
            else -> {
                // Unique temp file + rename: the pre-generator and live playback may
                // race on the same sentence.
                val tmp = File(parent, target.name + "." + UUID.randomUUID() + ".tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) {
                    if (!target.isFile || target.length() <= 0) tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                target.isFile && target.length() > 0
            }
        }
    } catch (e: Exception) {
        false
    }

    override fun readMarker(bookId: String): String? = runCatching {
        val file = File(bookDir(bookId), MARKER)
        if (file.isFile) file.readText() else null
    }.getOrNull()

    override fun writeMarker(bookId: String, text: String): Boolean = runCatching {
        val dir = bookDir(bookId)
        if (!dir.isDirectory && !dir.mkdirs()) return false
        File(dir, MARKER).writeText(text)
        true
    }.getOrDefault(false)

    override fun deleteBook(bookId: String) {
        runCatching { bookDir(bookId).deleteRecursively() }
    }

    private companion object {
        const val MARKER = ".ready"
    }
}

/**
 * Storage in a folder the user picked with the system file picker (SAF tree).
 *
 * Directory handles and per-chapter listings are cached in memory, because every `findFile` is a
 * ContentResolver query and playback asks for one sentence at a time.
 */
class SafVoiceStore(context: Context, treeUri: Uri) : VoiceStore {

    private val resolver = context.applicationContext.contentResolver

    private val root: DocumentFile? = runCatching {
        DocumentFile.fromTreeUri(context.applicationContext, treeUri)?.takeIf { it.isDirectory }
    }.getOrNull()

    /** False when the picked folder is gone / no longer writable (permission revoked). */
    val isUsable: Boolean = runCatching { root?.canWrite() == true }.getOrDefault(false)

    override val label: String
        get() = runCatching { root?.name }.getOrNull()?.let { "自定义目录：$it" } ?: "自定义目录"

    /** Cached handles: bookId or "bookId/cN" -> directory (null = known missing). */
    private val dirs = HashMap<String, DocumentFile?>()

    /** Cached per-chapter file listings, keyed the same way. */
    private val listings = HashMap<String, MutableMap<String, DocumentFile>>()

    private fun bookDir(bookId: String, create: Boolean): DocumentFile? {
        val r = root ?: return null
        synchronized(dirs) {
            if (dirs.containsKey(bookId)) return dirs[bookId]
            val found = runCatching { r.findFile(bookId) }.getOrNull()
            val dir = when {
                found != null && found.isDirectory -> found
                create -> runCatching { r.createDirectory(bookId) }.getOrNull()
                else -> null
            }
            dirs[bookId] = dir
            return dir
        }
    }

    private fun chapterDir(bookId: String, chapterIndex: Int, create: Boolean): DocumentFile? {
        val key = "$bookId/c$chapterIndex"
        synchronized(dirs) {
            if (dirs.containsKey(key)) return dirs[key]
            val book = bookDir(bookId, create)
            val name = "c$chapterIndex"
            val found = book?.let { runCatching { it.findFile(name) }.getOrNull() }
            val dir = when {
                found != null && found.isDirectory -> found
                book != null && create -> runCatching { book.createDirectory(name) }.getOrNull()
                else -> null
            }
            dirs[key] = dir
            return dir
        }
    }

    private fun files(
        bookId: String,
        chapterIndex: Int,
        create: Boolean,
    ): MutableMap<String, DocumentFile>? {
        val key = "$bookId/c$chapterIndex"
        synchronized(listings) { listings[key]?.let { return it } }
        val dir = chapterDir(bookId, chapterIndex, create) ?: return null
        val map = HashMap<String, DocumentFile>()
        runCatching {
            dir.listFiles().forEach { file -> file.name?.let { map[it] = file } }
        }
        synchronized(listings) { listings[key] = map }
        return map
    }

    private fun find(
        bookId: String,
        chapterIndex: Int,
        sentenceIndex: Int,
        voice: String,
        create: Boolean,
    ): DocumentFile? {
        val files = files(bookId, chapterIndex, create) ?: return null
        return synchronized(files) { files[audioFileName(sentenceIndex, voice)] }
    }

    override fun exists(bookId: String, chapterIndex: Int, sentenceIndex: Int, voice: String): Boolean {
        val doc = find(bookId, chapterIndex, sentenceIndex, voice, create = false) ?: return false
        return runCatching { doc.isFile && doc.length() > 0 }.getOrDefault(false)
    }

    override fun read(
        bookId: String,
        chapterIndex: Int,
        sentenceIndex: Int,
        voice: String,
    ): ByteArray? {
        val doc = find(bookId, chapterIndex, sentenceIndex, voice, create = false) ?: return null
        return runCatching {
            resolver.openInputStream(doc.uri)?.use { stream -> stream.readBytes() }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    override fun write(
        bookId: String,
        chapterIndex: Int,
        sentenceIndex: Int,
        voice: String,
        bytes: ByteArray,
    ): Boolean {
        if (bytes.isEmpty()) return false
        val files = files(bookId, chapterIndex, create = true) ?: return false
        val name = audioFileName(sentenceIndex, voice)
        val dir = chapterDir(bookId, chapterIndex, create = true) ?: return false
        val doc = runCatching { dir.createFile("audio/mpeg", name) }.getOrNull() ?: return false
        val written = runCatching {
            resolver.openOutputStream(doc.uri)?.use { stream ->
                stream.write(bytes)
                stream.flush()
            } != null
        }.getOrDefault(false)
        if (!written) {
            runCatching { doc.delete() }
            return false
        }
        synchronized(files) { files[name] = doc }
        return true
    }

    override fun readMarker(bookId: String): String? = runCatching {
        val doc = bookDir(bookId, create = false)?.findFile(MARKER) ?: return null
        resolver.openInputStream(doc.uri)?.use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        }
    }.getOrNull()

    override fun writeMarker(bookId: String, text: String): Boolean = runCatching {
        val dir = bookDir(bookId, create = true) ?: return false
        dir.findFile(MARKER)?.delete()
        val doc = dir.createFile("text/plain", MARKER) ?: return false
        resolver.openOutputStream(doc.uri)?.use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.flush()
        } != null
    }.getOrDefault(false)

    override fun deleteBook(bookId: String) {
        runCatching { bookDir(bookId, create = false)?.delete() }
        synchronized(dirs) {
            dirs.keys.filter { it == bookId || it.startsWith("$bookId/") }.forEach { dirs.remove(it) }
        }
        synchronized(listings) {
            listings.keys.filter { it.startsWith("$bookId/") }.forEach { listings.remove(it) }
        }
    }

    private companion object {
        const val MARKER = ".ready"
    }
}

/**
 * Resolves the [VoiceStore] the settings currently ask for, caching the result until the settings
 * change ([invalidate]).
 *
 * The user's explicit choice is never silently downgraded: when the picked directory is unusable
 * the app keeps working on internal storage but returns an [Resolved.error] so the UI can show it.
 */
object VoiceStorage {

    data class Resolved(val store: VoiceStore, val error: String? = null)

    @Volatile
    private var cached: Resolved? = null

    @Volatile
    private var cachedKey: String? = null

    fun invalidate() {
        cached = null
        cachedKey = null
    }

    fun resolve(context: Context): Resolved {
        val app = context.applicationContext
        val settings = AppSettings.state.value
        val key = "${settings.storageMode}|${settings.customTreeUri}"
        cached?.let { if (key == cachedKey) return it }
        val resolved = build(app, settings)
        cached = resolved
        cachedKey = key
        return resolved
    }

    /** Location currently in use, for the settings screen. */
    fun describe(context: Context): String = resolve(context).store.label

    private fun build(app: Context, settings: AppSettingsState): Resolved {
        val internal = FileVoiceStore(File(app.filesDir, AppStorage.VOICE_CACHE_DIR))
        return when (settings.storageMode) {
            VoiceStorageMode.INTERNAL -> Resolved(internal)

            VoiceStorageMode.EXTERNAL -> {
                val volume = runCatching {
                    app.getExternalFilesDirs(null).filterNotNull().firstOrNull()
                }.getOrNull()
                if (volume != null && writable(volume)) {
                    Resolved(FileVoiceStore(File(volume, AppStorage.VOICE_CACHE_DIR)))
                } else {
                    Resolved(internal, "外部存储不可写，已临时改用应用内部存储")
                }
            }

            VoiceStorageMode.CUSTOM -> {
                val saf = settings.customTreeUri?.let { uri ->
                    runCatching { SafVoiceStore(app, Uri.parse(uri)) }.getOrNull()
                }
                if (saf != null && saf.isUsable) {
                    Resolved(saf)
                } else {
                    Resolved(
                        internal,
                        if (settings.customTreeUri == null) "尚未选择自定义目录，已使用应用内部存储"
                        else "自定义目录已失效（权限被回收或目录被删除），已临时改用应用内部存储",
                    )
                }
            }

            VoiceStorageMode.AUTO -> {
                val root = AppStorage.sdcardRoot(app)
                if (root != null) Resolved(FileVoiceStore(File(root, AppStorage.VOICE_CACHE_DIR)))
                else Resolved(internal)
            }
        }
    }

    private fun writable(dir: File): Boolean = runCatching {
        (dir.isDirectory || dir.mkdirs()) && dir.canWrite()
    }.getOrDefault(false)
}
