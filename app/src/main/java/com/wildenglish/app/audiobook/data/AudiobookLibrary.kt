package com.wildenglish.app.audiobook.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

sealed interface ImportResult {
    data class Success(val book: Audiobook) : ImportResult
    data class Failure(val message: String) : ImportResult
}

/**
 * Local library: imports books into `filesDir/library/<id>/`. No backend.
 *
 * Accepted inputs:
 *  - `.jpd` — the encrypted package (decrypted in memory, never written in plaintext).
 *  - `.epub` — a standard EPUB ZIP, converted into the internal package layout
 *    (manifest.json + text/NNN.txt + cover.*) by [EpubImporter].
 *  - a plain, unencrypted internal package ZIP (manifest.json at its root).
 *  - a ZIP of audio files (MP3/M4A/...) with optional `.vtt` / `.srt` subtitles,
 *    converted on the fly by [AudioFolderImporter].
 *
 * The internal representation is identical for every source, so the rest of the
 * app (parsing, reading, UI) is source-agnostic.
 */
class AudiobookLibrary(context: Context) {

    private val appContext = context.applicationContext
    private val libraryRoot = File(appContext.filesDir, "library")
    private val prefs = appContext.getSharedPreferences("audiobook_local", Context.MODE_PRIVATE)

    /** 每本书的最近访问时间（bookId -> epochMillis），用于首页「最近访问优先」。 */
    private val accessPrefs =
        appContext.getSharedPreferences("audiobook_access", Context.MODE_PRIVATE)

    init {
        VoiceCache.init(appContext)
        AppDatabase.init(appContext)
        // SD 卡进度恢复放到后台线程异步做，绝不阻塞列表加载（读 SD 卡可能很慢）。
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() +
                    kotlinx.coroutines.Dispatchers.IO
        ).launch {
            restoreProgressFromSdcard()
        }
    }

    /**
     * Re-imports reading progress mirrored on the SD card when the local SharedPreferences no
     * longer has any progress at all. Internal state always wins when present, so a stale mirror
     * can never roll back newer progress.
     */
    private fun restoreProgressFromSdcard() {
        val hasLocal = prefs.all.keys.any { it.startsWith("progress_") || it.startsWith("pos_") }
        if (hasLocal) return
        val mirror = AppStorage.readProgress(appContext) ?: return
        val editor = prefs.edit()
        var changed = false
        for ((key, value) in mirror) {
            when {
                key.startsWith("pos_") -> value.toLongOrNull()
                    ?.let { editor.putLong(key, it); changed = true }

                key.startsWith("progress_") -> {
                    editor.putString(key, value); changed = true
                }
            }
        }
        if (changed) editor.apply()
    }

    /** Snapshot of every progress-related pref as strings, for the SD-card mirror. */
    private fun progressSnapshot(): Map<String, String> =
        prefs.all.entries.mapNotNull { (key, value) ->
            if ((key.startsWith("progress_") || key.startsWith("pos_")) && value != null) {
                key to value.toString()
            } else {
                null
            }
        }.toMap()

    /**
     * 记录一次访问（打开书），key 用书籍**目录名**，这样渐进加载时无需先解析
     * manifest 就能按最近访问排序。
     */
    fun touchAccess(dirName: String) {
        accessPrefs.edit().putLong(dirName, System.currentTimeMillis()).apply()
        runCatching { AppDatabase.touchBook(dirName) }
    }

    private fun accessTime(dirName: String): Long =
        runCatching { accessPrefs.getLong(dirName, 0L) }.getOrDefault(0L)

    /** 最近访问时间降序；从未访问过的书按书名排在后面。 */
    fun sortByRecentAccess(books: List<Audiobook>): List<Audiobook> =
        books.sortedWith(
            compareByDescending<Audiobook> { accessTime(it.dir.name) }.thenBy { it.title }
        )

    /** 只列目录并按访问时间排序，不解析 manifest（渐进加载用，极快）。 */
    private fun orderedBookDirs(): List<File> =
        libraryRoot.listFiles().orEmpty()
            .filter { it.isDirectory }
            .sortedWith(compareByDescending<File> { accessTime(it.name) }.thenBy { it.name })

    /**
     * 同步快照：直接列目录 + 解析 manifest，供首页一次性加载。
     * 不经过 Flow 的 emit 反压，毫秒级返回。
     */
    /** 从数据库读轻量图书（毫秒级），首页优先用它，无需解析 manifest。 */
    fun booksFromDb(): List<Audiobook> =
        AppDatabase.loadBooks()
            .sortedByDescending { it.lastAccess }
            .map { row ->
                Audiobook(
                    id = row.id,
                    dir = File(row.dirPath),
                    title = row.title,
                    author = row.author,
                    narrator = row.narrator,
                    description = row.description,
                    coverFile = row.coverPath?.let { File(it) },
                    chapters = emptyList(),
                    declaredChapterCount = row.chapterCount,
                )
            }

    suspend fun loadBooksSnapshot(): List<Audiobook> {
        // 数据库优先：冷启动不再解析 manifest，直接读库（毫秒级）。
        val fromDb = booksFromDb()
        if (fromDb.isNotEmpty()) return fromDb
        // DB 为空（首次安装/升级）才解析 manifest，并 upsert 回库。
        return parseBooksFromDisk()
    }

    private suspend fun parseBooksFromDisk(): List<Audiobook> {
        val dirs = orderedBookDirs()
        val parsed = kotlinx.coroutines.coroutineScope {
            dirs.map { dir ->
                async(Dispatchers.IO) { runCatching { ManifestParser.parseLight(dir) }.getOrNull() }
            }.awaitAll().filterNotNull()
        }
        runCatching { parsed.forEach { AppDatabase.upsertBook(it) } }
        return parsed
    }

    /**
     * 逐部解析并 emit，供渐进显示用（保留给其它可能的调用点）。
     */
    fun booksFlow(): Flow<Audiobook> = flow {
        loadBooksSnapshot().forEach { emit(it) }
    }.flowOn(Dispatchers.IO)

    fun listBooks(): List<Audiobook> {
        if (!libraryRoot.isDirectory) return emptyList()
        val books = sortByRecentAccess(
            orderedBookDirs().mapNotNull { runCatching { ManifestParser.parse(it) }.getOrNull() }
        )
        // 扫描结果同步进数据库（覆盖升级前的旧书 / 外部放入的书）。
        runCatching { books.forEach { AppDatabase.upsertBook(it) } }
        return books
    }

    suspend fun import(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        // A raw audio file (picked here or handed over by another app) becomes a book.
        if (isAudioUri(uri)) return@withContext importAudioFiles(listOf(uri))
        val temp = File(appContext.cacheDir, "import-" + System.currentTimeMillis())
        try {
            if (!temp.mkdirs()) return@withContext ImportResult.Failure("无法创建导入临时目录")
            val source = appContext.contentResolver.openInputStream(uri)
                ?: return@withContext ImportResult.Failure("无法读取所选文件")

            var prepareError: String? = null
            BufferedInputStream(source).use { buf ->
                buf.mark(8)
                val head = ByteArray(4)
                val n = buf.read(head)
                buf.reset()
                if (n == 4 && isJpdMagic(head)) {
                    JpdCodec.decrypt(buf).use { decrypted -> unzip(decrypted, temp) }
                } else {
                    val staging = File(temp, "_src")
                    if (!staging.mkdirs()) {
                        prepareError = "无法创建解包临时目录"
                    } else {
                        try {
                            unzip(buf, staging)
                            prepareError = preparePackage(staging, temp)
                        } catch (e: Exception) {
                            prepareError = e.message ?: "解包失败"
                        } finally {
                            staging.deleteRecursively()
                        }
                    }
                }
            }
            if (prepareError != null) return@withContext ImportResult.Failure(prepareError)
            installPackage(temp)
        } catch (e: Exception) {
            ImportResult.Failure(e.message ?: "导入失败")
        } finally {
            if (temp.exists()) temp.deleteRecursively()
        }
    }

    /**
     * Imports one or more audio files as **one** audiobook: every file becomes a chapter
     * (naturally ordered by file name) and the cover is extracted from the first MP3.
     *
     * Used by the "打开方式 / 分享" entry points and when raw audio is picked from the
     * document picker. Sharing a single MP3 produces a one-chapter book.
     */
    suspend fun importAudioFiles(uris: List<Uri>): ImportResult = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext ImportResult.Failure("没有可导入的音频文件")
        val temp = File(appContext.cacheDir, "import-audio-" + System.currentTimeMillis())
        val staging = File(temp, "_src")
        try {
            if (!staging.mkdirs()) return@withContext ImportResult.Failure("无法创建导入临时目录")
            var copied = 0
            for ((index, uri) in uris.withIndex()) {
                val input = runCatching { appContext.contentResolver.openInputStream(uri) }
                    .getOrNull() ?: continue
                val target = File(staging, audioFileName(uri, index, staging))
                input.use { source ->
                    FileOutputStream(target).use { out -> source.copyTo(out) }
                }
                copied++
            }
            if (copied == 0) return@withContext ImportResult.Failure("无法读取音频文件")
            val error = AudioFolderImporter.convert(staging, temp)
            // Drop the staging folder BEFORE moving temp into the library, otherwise it
            // would be carried into the book directory by the rename.
            staging.deleteRecursively()
            if (error != null) return@withContext ImportResult.Failure(error)
            installPackage(temp)
        } catch (e: Exception) {
            ImportResult.Failure(e.message ?: "导入音频失败")
        } finally {
            staging.deleteRecursively()
            if (temp.exists()) temp.deleteRecursively()
        }
    }

    /** Validates the unpacked package in [temp], moves it into the library and loads it. */
    private fun installPackage(temp: File): ImportResult {
        // Older audio imports could leave their staging folder inside the package.
        File(temp, "_src").deleteRecursively()
        val validationError = ManifestParser.validate(temp)
        if (validationError != null) return ImportResult.Failure(validationError)
        val parsed = ManifestParser.parse(temp)
            ?: return ImportResult.Failure("内容校验失败")

        val id = sanitizeId(parsed.id)
        val destination = File(libraryRoot, id)
        libraryRoot.mkdirs()
        // Audio may live on the SD card (outside the book dir); dropping the
        // directory alone would leave stale voice files for a re-import.
        VoiceCache.clearForId(id)
        if (destination.exists()) destination.deleteRecursively()
        if (!temp.renameTo(destination)) {
            temp.copyRecursively(destination, overwrite = true)
            temp.deleteRecursively()
        }
        val book = ManifestParser.parse(destination)?.let { ensureCover(it) }
            ?: return ImportResult.Failure("导入后的校验失败")
        // 图书信息落库。
        runCatching { AppDatabase.upsertBook(book) }
        // 导入时就把封面缩略图生成并落盘；之后书架/详情/播放一直读缓存小图。
        runCatching { CoverThumbCache.preGenerate(appContext, book.coverFile) }
        return ImportResult.Success(book)
    }

    private fun isAudioUri(uri: Uri): Boolean {
        val mime = runCatching { appContext.contentResolver.getType(uri) }.getOrNull()
        return AudioFolderImporter.isAudioFile(displayName(uri), mime)
    }

    private fun displayName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    /**
     * Keeps the provider's display name (so chapters read as the track names) while
     * stripping path separators and de-duplicating when several files share a name.
     */
    private fun audioFileName(uri: Uri, index: Int, dir: File): String {
        val raw = displayName(uri)?.substringAfterLast('/')?.substringAfterLast('\\')
        val cleaned = raw?.replace(Regex("[\\\\/:*?\"<>|]"), "_")?.trim()?.trim('.')
        val name = cleaned?.takeIf { it.isNotBlank() } ?: "audio-${index + 1}"
        val extension = name.substringAfterLast('.', "").lowercase()
        val supported = extension in AudioFolderImporter.AUDIO_EXTENSIONS
        val base = (if (supported) name.substringBeforeLast('.') else name)
            .ifBlank { "audio-${index + 1}" }
        val finalExtension = if (supported) extension else "mp3"
        var candidate = "$base.$finalExtension"
        var suffix = 2
        while (File(dir, candidate).exists()) {
            candidate = "$base (${suffix++}).$finalExtension"
        }
        return candidate
    }

    /** Turns an unpacked archive in [staging] into the internal layout in [dest]. */
    private fun preparePackage(staging: File, dest: File): String? {
        val epubRoot = findEpubRoot(staging)
        return when {
            epubRoot != null -> EpubImporter.convert(epubRoot, dest)
            File(staging, "manifest.json").isFile -> {
                // Plain (unencrypted) internal package: lift its contents into dest.
                staging.listFiles().orEmpty().forEach { f ->
                    val target = File(dest, f.name)
                    if (f.isDirectory) f.copyRecursively(target, overwrite = true)
                    else f.copyTo(target, overwrite = true)
                }
                null
            }

            else -> if (AudioFolderImporter.looksLikeAudioFolder(staging)) {
                AudioFolderImporter.convert(staging, dest)
            } else {
                "无法识别的文件格式：请选择 .epub 图书、.jpd 有声书或包含 MP3 的音频压缩包"
            }
        }
    }

    /**
     * MP3 audiobooks usually carry their cover inside the audio tags instead of a
     * separate image. Pull it out on import so the shelf / player / lock screen all
     * show the album art.
     */
    private fun ensureCover(book: Audiobook): Audiobook {
        if (book.coverFile?.isFile == true) return book
        val audio = book.chapters.firstNotNullOfOrNull { chapter ->
            chapter.audioFile?.takeIf { it.isFile }
        } ?: return book
        val cover = Mp3Metadata.writeCover(Mp3Metadata.read(audio), book.dir) ?: return book
        return book.copy(coverFile = cover)
    }

    /** EPUBs normally keep container.xml at the archive root, but some are wrapped. */
    private fun findEpubRoot(staging: File): File? {
        if (File(staging, "META-INF/container.xml").isFile) return staging
        return staging.listFiles()?.firstOrNull {
            it.isDirectory && File(it, "META-INF/container.xml").isFile
        }
    }

    private fun isJpdMagic(head: ByteArray): Boolean =
        head[0] == 'J'.code.toByte() && head[1] == 'P'.code.toByte() &&
                head[2] == 'D'.code.toByte() && head[3] == '1'.code.toByte()

    /**
     * 删除整本书：直接用 [book.dir]（唯一可靠的目录引用），不再从 id 猜目录名。
     * 同时清掉进度、访问记录与封面缩略图缓存。
     */
    fun delete(book: Audiobook) {
        val id = book.id
        val dirName = book.dir.name
        runCatching { AppDatabase.deleteBook(id) }
        VoiceCache.clearForId(dirName)
        runCatching { book.dir.deleteRecursively() }
        val editor = prefs.edit()
        editor.remove(progressKey(id))
        prefs.all.keys
            .filter { it.startsWith(positionKeyPrefix(id)) }
            .forEach { editor.remove(it) }
        editor.apply()
        accessPrefs.edit().remove(dirName).apply()
        // 封面缩略图缓存按「封面路径+lastModified」做 key，随书一起清掉。
        book.coverFile?.let { runCatching { CoverThumbCache.remove(appContext, it) } }
        AppStorage.mirrorProgress(appContext, progressSnapshot())
    }

    /** Last played chapter and its position, used by "继续收听". */
    fun progressOf(id: String): Pair<Int, Long> {
        val raw = prefs.getString(progressKey(id), null) ?: return 0 to 0L
        val parts = raw.split('|')
        val chapter = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val position = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        return chapter to position
    }

    /** Position of a specific chapter, so every chapter remembers where it stopped. */
    fun positionOf(id: String, chapterIndex: Int): Long =
        prefs.getLong(positionKey(id, chapterIndex), 0L)

    /** Persists the resume point AND the per-chapter position. */
    fun saveProgress(id: String, chapterIndex: Int, positionMs: Long) {
        prefs.edit()
            .putString(progressKey(id), "$chapterIndex|$positionMs")
            .putLong(positionKey(id, chapterIndex), positionMs)
            .putString(KEY_LAST_BOOK, id)
            .apply()
        runCatching { AppDatabase.updateBookProgress(id, chapterIndex, positionMs.toInt()) }
        AppStorage.mirrorProgress(appContext, progressSnapshot())
    }

    /** 最近一次收听的书（启动时恢复播放位置用），没有则返回 null。 */
    fun lastBookId(): String? = prefs.getString(KEY_LAST_BOOK, null)

    /** 当前句在章节里的序号（第几句），从上次中断处继续。 */
    fun sentenceOf(id: String, chapterIndex: Int): Int =
        prefs.getLong(positionKey(id, chapterIndex), 0L).toInt().coerceAtLeast(0)

    private fun progressKey(id: String) = "progress_$id"
    private fun positionKeyPrefix(id: String) = "pos_${id}_"
    private fun positionKey(id: String, chapterIndex: Int) = "${positionKeyPrefix(id)}$chapterIndex"

    private companion object {
        const val KEY_LAST_BOOK = "last_book"
    }

    private fun sanitizeId(raw: String): String {
        val cleaned = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_')
        return cleaned.ifBlank { "book-" + System.currentTimeMillis() }
    }

    private fun unzip(input: InputStream, destDir: File) {
        val basePath = destDir.canonicalPath
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/')
                if (name.isNotBlank() && !name.endsWith("/")) {
                    val outFile = File(destDir, name)
                    val target = outFile.canonicalPath
                    if (target != basePath && !target.startsWith(basePath + File.separator)) {
                        throw IOException("压缩包包含非法路径：$name")
                    }
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out -> zip.copyTo(out) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }
}
