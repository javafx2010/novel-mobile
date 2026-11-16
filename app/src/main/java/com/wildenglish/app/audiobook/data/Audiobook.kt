package com.wildenglish.app.audiobook.data

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
     * A transcript (plain text or a cue file) is MANDATORY for every chapter, since
     * the app synthesizes the voice from it. Audio files are optional/ignored.
     */
    fun validate(dir: File): String? {
        val manifestFile = File(dir, "manifest.json")
        if (!manifestFile.isFile) return "压缩包缺少 manifest.json"
        val root = runCatching { JSONObject(manifestFile.readText(Charsets.UTF_8)) }.getOrNull()
            ?: return "manifest.json 不是合法的 JSON"

        if (root.optString("format", FORMAT) != FORMAT) return "format 必须为 $FORMAT"
        if (root.optInt("version", VERSION) > VERSION) return "version 高于 $VERSION，当前版本不支持"
        if (root.optString("title").isBlank()) return "title 不能为空"

        val chapters = root.optJSONArray("chapters") ?: return "chapters 不能为空"
        if (chapters.length() == 0) return "chapters 至少需要 1 章"
        for (i in 0 until chapters.length()) {
            val c = chapters.optJSONObject(i) ?: continue
            val text = resolveSafe(dir, c.optString("text"))?.takeIf { it.isFile }
            val cues = resolveSafe(dir, c.optString("cues"))?.takeIf { it.isFile }
                ?: text?.let { findSiblingCues(it) }
            if (text == null && cues == null) {
                return "第 ${i + 1} 章缺少文稿：每章必须提供 text 或 cues（WebVTT/SRT），App 将据此朗读"
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
            val textFile = resolveSafe(dir, c.optString("text"))?.takeIf { it.isFile }
            val cuesFile = resolveSafe(dir, c.optString("cues"))?.takeIf { it.isFile }
                ?: textFile?.let { findSiblingCues(it) }
            // A transcript is mandatory; skip chapters that lack one (legacy packages).
            if (textFile == null && cuesFile == null) continue
            val audioFile = resolveSafe(dir, c.optString("audio"))?.takeIf { it.isFile }
            val chapterTitle = c.optString("title").takeIf { it.isNotBlank() }
                ?: textFile?.nameWithoutExtension
                ?: cuesFile?.nameWithoutExtension
                ?: "第 ${chapters.size + 1} 章"
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
            val textFile = textRel.takeIf { it.isNotBlank() }?.let { File(base, it) }
            val cuesFile = cuesRel.takeIf { it.isNotBlank() }?.let { File(base, it) }
                ?: textFile?.let { File(it.parentFile, it.nameWithoutExtension + ".vtt") }
            if (textFile == null && cuesFile == null) continue
            val audioFile = audioRel.takeIf { it.isNotBlank() }?.let { File(base, it) }
            val chapterTitle = c.optString("title").takeIf { it.isNotBlank() }
                ?: textFile?.nameWithoutExtension ?: "第 ${chapters.size + 1} 章"
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
