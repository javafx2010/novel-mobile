package com.wildenglish.app.audiobook.data

import com.wildenglish.app.R
import com.wildenglish.app.i18n.Strings
import org.json.JSONObject
import java.io.File

/** One chapter inside a local package. The voice is synthesized from [textFile]/[cuesFile]. */
data class Chapter(
    val index: Int,
    val title: String,
    val audioFile: File?,
    val textFile: File?,
    val cuesFile: File?,
    val durationSeconds: Int?,
)

/** A locally imported audiobook package (see PACKAGE_FORMAT.md at the repo root). */
data class Audiobook(
    val id: String,
    val dir: File,
    val title: String,
    val author: String?,
    val narrator: String?,
    val description: String?,
    val coverFile: File?,
    val chapters: List<Chapter>,
    /** 从数据库轻量加载时章节数为已知值、章节列表为空，用此值显示。 */
    val declaredChapterCount: Int = -1,
) {
    val chapterCount: Int get() = if (declaredChapterCount >= 0) declaredChapterCount else chapters.size

    /** True when the book ships pre-recorded audio, which is played directly instead of Edge TTS. */
    val audioMode: Boolean get() = chapters.any { it.audioFile != null }

    /**
     * EPUB 前置页（封面/目录/「共 N 章」信息页）常被当成章节导入：它们的标题重复书名。
     * 这类章节不算正文碟片，选碟架隐藏、连续播放跳过。音频书的章节名来自文件名，不适用。
     */
    fun isToc(chapter: Chapter): Boolean = !audioMode && chapter.title == title

    /** 正文碟片（去掉目录前置页）。整本都是前置页时退回全部，避免出现空书架。 */
    val contentChapters: List<Chapter>
        get() {
            val content = chapters.filterNot { isToc(it) }
            return content.ifEmpty { chapters }
        }
}

/**
 * Parses `manifest.json` from an extracted package directory.
 * Returns null when the manifest is missing or invalid.
 */
object ManifestParser {

    const val FORMAT = "audiobook-package"
    const val VERSION = 1

    /**
     * Validates a package before import and returns a human-readable reason on failure.
     * Every chapter needs content: either an audio file (played directly) or a transcript
     * (text / cues) that the app will read aloud with Edge TTS.
     */
    fun validate(dir: File): String? {
        val manifestFile = File(dir, "manifest.json")
        if (!manifestFile.isFile) return Strings.get(R.string.import_manifest_missing)
        val root = runCatching { JSONObject(manifestFile.readText(Charsets.UTF_8)) }.getOrNull()
            ?: return Strings.get(R.string.manifest_bad_json)

        if (root.optString("format", FORMAT) != FORMAT) {
            return Strings.get(R.string.manifest_format, FORMAT)
        }
        if (root.optInt("version", VERSION) > VERSION) {
            return Strings.get(R.string.manifest_version_high, VERSION)
        }
        if (root.optString("title").isBlank()) return Strings.get(R.string.manifest_title_empty)

        val chapters = root.optJSONArray("chapters")
            ?: return Strings.get(R.string.manifest_chapters_empty)
        if (chapters.length() == 0) return Strings.get(R.string.manifest_chapters_empty)
        for (i in 0 until chapters.length()) {
            val c = chapters.optJSONObject(i) ?: continue
            val text = resolveSafe(dir, c.optString("text"))?.takeIf { it.isFile }
            val cues = resolveSafe(dir, c.optString("cues"))?.takeIf { it.isFile }
            val audio = resolveSafe(dir, c.optString("audio"))?.takeIf { it.isFile }
            if (text == null && cues == null && audio == null) {
                return Strings.get(R.string.manifest_chapter_missing, i + 1)
            }
        }
        return null
    }

    fun parse(dir: File): Audiobook? {
        val manifestFile = File(dir, "manifest.json")
        if (!manifestFile.isFile) return null
        val root = runCatching { JSONObject(manifestFile.readText(Charsets.UTF_8)) }.getOrNull() ?: return null

        if (root.optString("format", FORMAT) != FORMAT) return null
        if (root.optInt("version", VERSION) > VERSION) return null

        val title = root.optString("title").takeIf { it.isNotBlank() } ?: return null
        val chaptersJson = root.optJSONArray("chapters") ?: return null

        val chapters = ArrayList<Chapter>()
        for (i in 0 until chaptersJson.length()) {
            val c = chaptersJson.optJSONObject(i) ?: continue
            val audioFile = resolveSafe(dir, c.optString("audio"))?.takeIf { it.isFile }
            val textFile = resolveSafe(dir, c.optString("text"))?.takeIf { it.isFile }
                ?: audioFile?.let { findSiblingText(it) }
            val cuesFile = resolveSafe(dir, c.optString("cues"))?.takeIf { it.isFile }
                ?: textFile?.let { findSiblingCues(it) }
            // 每章至少要有音频、文稿或字幕之一；旧包缺内容时跳过。
            if (textFile == null && cuesFile == null && audioFile == null) continue
            val chapterTitle = c.optString("title").takeIf { it.isNotBlank() }
                ?: textFile?.nameWithoutExtension
                ?: cuesFile?.nameWithoutExtension
                ?: audioFile?.nameWithoutExtension
                ?: Strings.get(R.string.chapter_title_default, chapters.size + 1)
            val duration = if (c.has("durationSeconds") && !c.isNull("durationSeconds")) {
                c.optInt("durationSeconds")
            } else {
                null
            }
            chapters.add(
                Chapter(chapters.size, chapterTitle, audioFile, textFile, cuesFile, duration)
            )
        }
        if (chapters.isEmpty()) return null

        return Audiobook(
            id = root.optString("id").takeIf { it.isNotBlank() } ?: dir.name,
            dir = dir,
            title = title,
            author = root.optString("author").takeIf { it.isNotBlank() },
            narrator = root.optString("narrator").takeIf { it.isNotBlank() },
            description = root.optString("description").takeIf { it.isNotBlank() },
            coverFile = resolveSafe(dir, root.optString("cover"))?.takeIf { it.isFile } ?: findCover(dir),
            chapters = chapters,
        )
    }

    /**
     * 轻量解析：只从 manifest 构造元数据，不做逐章 canonicalFile / isFile / listFiles 检查。
     * 用于首页列表快速加载 —— 完整文件校验留给真正打开/播放该书时。
     */
    fun parseLight(dir: File): Audiobook? {
        val manifestFile = File(dir, "manifest.json")
        if (!manifestFile.isFile) return null
        val root = runCatching { JSONObject(manifestFile.readText(Charsets.UTF_8)) }.getOrNull()
            ?: return null
        if (root.optString("format", FORMAT) != FORMAT) return null
        if (root.optInt("version", VERSION) > VERSION) return null
        val title = root.optString("title").takeIf { it.isNotBlank() } ?: return null
        val chaptersJson = root.optJSONArray("chapters") ?: return null

        val chapters = ArrayList<Chapter>()
        for (i in 0 until chaptersJson.length()) {
            val c = chaptersJson.optJSONObject(i) ?: continue
            val base = dir.absolutePath
            val textRel = c.optString("text")
            val cuesRel = c.optString("cues")
            val audioRel = c.optString("audio")
            val audioFile = audioRel.takeIf { it.isNotBlank() }?.let { File(base, it) }
            val textFile = textRel.takeIf { it.isNotBlank() }?.let { File(base, it) }
                ?: audioFile?.let { findSiblingText(it) }
            // TTS 章节沿用「同名 .vtt」的零 IO 推断；音频章节不使用字幕。
            val cuesFile = cuesRel.takeIf { it.isNotBlank() }?.let { File(base, it) }
                ?: textFile?.let { File(it.parentFile, it.nameWithoutExtension + ".vtt") }
            if (textFile == null && cuesFile == null && audioFile == null) continue
            val chapterTitle = c.optString("title").takeIf { it.isNotBlank() }
                ?: textFile?.nameWithoutExtension
                ?: audioFile?.nameWithoutExtension
                ?: Strings.get(R.string.chapter_title_default, chapters.size + 1)
            val duration = if (c.has("durationSeconds") && !c.isNull("durationSeconds")) {
                c.optInt("durationSeconds")
            } else null
            chapters.add(
                Chapter(
                    chapters.size,
                    chapterTitle,
                    audioFile,
                    textFile,
                    cuesFile,
                    duration
                )
            )
        }
        if (chapters.isEmpty()) return null
        val coverRel = root.optString("cover")
        return Audiobook(
            id = root.optString("id").takeIf { it.isNotBlank() } ?: dir.name,
            dir = dir,
            title = title,
            author = root.optString("author").takeIf { it.isNotBlank() },
            narrator = root.optString("narrator").takeIf { it.isNotBlank() },
            description = root.optString("description").takeIf { it.isNotBlank() },
            // 用 manifest 声明的封面路径（不 listFiles 扫目录）。封面显示层已用
            // 默认图兜底 + 淡入，路径无效时自然显示默认封面，无需在这里验证。
            // manifest 未声明封面时扫一次目录找 cover.*（每本仅一次 listFiles，
            // 远比重解析全部章节便宜）；否则详情页替换后的书会丢掉封面。
            coverFile = coverRel.takeIf { it.isNotBlank() }
                ?.let { File(dir.absolutePath, it) }
                ?: findCover(dir),
            chapters = chapters,
        )
    }

    /** Resolves a package-relative path, rejecting traversal outside [dir]. */
    fun resolveSafe(dir: File, relative: String?): File? {
        if (relative.isNullOrBlank()) return null
        val normalized = relative.replace('\\', '/').trimStart('/')
        return try {
            val base = dir.canonicalFile
            val candidate = File(dir, normalized).canonicalFile
            if (candidate.path == base.path || candidate.path.startsWith(base.path + File.separator)) {
                candidate
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun findCover(dir: File): File? = dir.listFiles()?.firstOrNull {
        it.isFile &&
            it.nameWithoutExtension.equals("cover", ignoreCase = true) &&
            it.extension.lowercase() in COVER_EXTENSIONS
    }

    private val COVER_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
}
