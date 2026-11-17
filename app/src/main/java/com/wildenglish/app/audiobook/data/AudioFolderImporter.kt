package com.wildenglish.app.audiobook.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Converts a plain archive of audio files into the internal package layout used by
 * the rest of the app.
 *
 * This is what makes MP3 audiobooks importable without a hand-written manifest:
 *  - every audio file becomes one chapter, ordered naturally by path/name;
 *  - the cover is taken from `cover.*` / `folder.*` when present, otherwise extracted
 *    from the first MP3's ID3 APIC frame.
 *
 * Subtitle files in the archive are kept on disk but are not attached to chapters —
 * MP3 playback does not show subtitles.
 */
object AudioFolderImporter {

    internal val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "ogg", "wav")
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    private val COVER_NAMES = setOf("cover", "folder", "front", "poster", "album", "albumart")

    /** True when [name] / [mime] identifies a single supported audio file. */
    fun isAudioFile(name: String?, mime: String?): Boolean {
        if (mime != null && mime.startsWith("audio/")) return true
        val extension = name?.substringAfterLast('.', "")?.lowercase() ?: return false
        return extension in AUDIO_EXTENSIONS
    }

    /** True when [src] contains at least one audio file anywhere in the tree. */
    fun looksLikeAudioFolder(src: File): Boolean = audioFiles(src).isNotEmpty()

    /**
     * Copies [src] into [dest] and writes a `manifest.json` describing the audio chapters.
     * @return an error message, or null on success.
     */
    fun convert(src: File, dest: File): String? {
        val files = filesOf(src)
        if (files.isEmpty()) return "压缩包是空的"
        val audios = files.filter { it.extension.lowercase() in AUDIO_EXTENSIONS }
        if (audios.isEmpty()) return "压缩包里没有可播放的音频文件"

        for (file in files) {
            val target = File(dest, file.relativeTo(src).path)
            target.parentFile?.mkdirs()
            runCatching { file.copyTo(target, overwrite = true) }
                .onFailure { return "解包音频失败：${it.message ?: file.name}" }
        }

        val copied = files.map { File(dest, it.relativeTo(src).path) }
        val destAudios = audios
            .map { File(dest, it.relativeTo(src).path) }
            .sortedWith(compareByNaturalPath())

        val firstTags = Mp3Metadata.read(destAudios.first())
        val cover = findCoverImage(copied, dest, destAudios.first())
            ?: extractCover(destAudios, dest)

        val chapters = JSONArray()
        for (audio in destAudios) {
            val chapter = JSONObject()
            chapter.put("title", audio.nameWithoutExtension)
            chapter.put("audio", relativePath(dest, audio))
            chapters.put(chapter)
        }

        val wrapperName = src.listFiles()
            ?.singleOrNull { it.isDirectory && !it.name.startsWith(".") && it.name != "__MACOSX" }
            ?.name
        // 单文件用曲名做书名；多文件优先专辑名，避免书名变成第一首曲名。
        val title = firstTags?.album
            ?: wrapperName
            ?: firstTags?.title?.takeIf { destAudios.size == 1 }
            ?: "有声书"

        val root = JSONObject()
        root.put("format", ManifestParser.FORMAT)
        root.put("version", ManifestParser.VERSION)
        root.put("id", stableId(title, destAudios))
        root.put("title", title)
        firstTags?.artist?.let { root.put("author", it) }
        cover?.let { root.put("cover", relativePath(dest, it)) }
        root.put("chapters", chapters)

        return runCatching {
            File(dest, "manifest.json").writeText(root.toString(2), Charsets.UTF_8)
            null
        }.getOrElse { "写入清单失败：${it.message ?: "未知错误"}" }
    }

    private fun filesOf(src: File): List<File> =
        src.walkTopDown()
            .onEnter { it.name != "__MACOSX" && !it.name.startsWith(".") }
            .filter { it.isFile && !it.name.startsWith(".") }
            .toList()

    private fun audioFiles(src: File): List<File> =
        filesOf(src).filter { it.extension.lowercase() in AUDIO_EXTENSIONS }

    /** Prefers a conventional cover image, otherwise any image beside the first audio. */
    private fun findCoverImage(copied: List<File>, dest: File, firstAudio: File): File? {
        val images = copied.filter { it.extension.lowercase() in IMAGE_EXTENSIONS }
        return images.firstOrNull { it.nameWithoutExtension.lowercase() in COVER_NAMES }
            ?: images.firstOrNull { it.parentFile == firstAudio.parentFile }
            ?: images.firstOrNull { it.parentFile == dest }
    }

    private fun extractCover(audios: List<File>, dest: File): File? {
        for (audio in audios) {
            val tags = Mp3Metadata.read(audio) ?: continue
            if (tags.picture != null) {
                Mp3Metadata.writeCover(tags, dest)?.let { return it }
            }
        }
        return null
    }

    private fun relativePath(root: File, file: File): String =
        file.relativeTo(root).path.replace('\\', '/')

    private fun stableId(title: String, audios: List<File>): String {
        val seed = "$title|${audios.size}|${audios.first().name}"
        return "audio-" + Integer.toHexString(seed.hashCode())
    }

    private fun compareByNaturalPath(): Comparator<File> = Comparator { a, b ->
        naturalCompare(a.path.replace('\\', '/'), b.path.replace('\\', '/'))
    }

    /** Compares strings so `2.mp3` sorts before `10.mp3`. */
    private fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val startA = i
                val startB = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val numA = a.substring(startA, i).trimStart('0')
                val numB = b.substring(startB, j).trimStart('0')
                if (numA.length != numB.length) return numA.length - numB.length
                val cmp = numA.compareTo(numB)
                if (cmp != 0) return cmp
            } else {
                if (ca != cb) return ca.compareTo(cb)
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
