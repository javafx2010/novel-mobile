package com.wildenglish.app.audiobook.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder

/**
 * Converts an unpacked EPUB (EPUB 2 / EPUB 3) into the app's internal package
 * layout — `manifest.json` + `text/NNN.txt` + `cover.*` — inside [destDir].
 *
 * The whole downstream pipeline (ManifestParser, loadChapterSentences, the UI)
 * works on that layout, so importing an EPUB requires no changes beyond this
 * conversion step. Chapter text is extracted from the spine's XHTML documents;
 * the existing sentence splitter turns it into spoken lines.
 *
 * This is a deliberately small, dependency-free reader covering the common
 * EPUB shapes (container.xml -> OPF -> spine, NCX/nav TOC, DC metadata, cover).
 * It does not implement the full EPUB spec (no DRM/encryption, no media
 * overlays); malformed or exotic books degrade to "第 N 章" titles.
 */
object EpubImporter {

    /** Returns null on success, or a human-readable error to surface to the user. */
    fun convert(epubRoot: File, destDir: File): String? {
        val container = File(epubRoot, "META-INF/container.xml")
        if (!container.isFile) return "不是有效的 EPUB：缺少 META-INF/container.xml"
        val containerXml = read(container) ?: return "无法读取 container.xml"
        val opfRel = Regex("""<rootfile\b[^>]*full-path\s*=\s*["']([^"']+)["']""")
            .find(containerXml)?.groupValues?.get(1)
            ?: return "EPUB 缺少 rootfile（OPF）声明"
        val opfFile = resolve(epubRoot, opfRel)?.takeIf { it.isFile }
            ?: return "找不到 EPUB 的 OPF 文件（$opfRel）"
        val opf = read(opfFile) ?: return "无法读取 OPF 文件"
        val opfBase = opfFile.parentFile ?: epubRoot

        val title = firstTag(opf, "dc:title") ?: epubRoot.name
        val author = firstTag(opf, "dc:creator")
        val description = firstTag(opf, "dc:description")
        val identifier = firstTag(opf, "dc:identifier")

        val items = parseManifest(opf)
        val spine = parseSpine(opf)
        val toc = parseToc(opfBase, items)

        val textDir = File(destDir, "text").apply { mkdirs() }
        val chapters = JSONArray()
        var written = 0
        for (idref in spine) {
            val item = items[idref] ?: continue
            if (!isHtml(item)) continue
            val htmlFile = resolve(opfBase, item.href)?.takeIf { it.isFile } ?: continue
            val html = read(htmlFile) ?: continue
            val text = HtmlText.toText(html)
            if (text.isBlank()) continue
            val name = "%03d.txt".format(written + 1)
            File(textDir, name).writeText(text, Charsets.UTF_8)
            val chTitle = toc[keyOf(item.href)]
                ?: firstTag(html, "title")?.takeIf { it.isNotBlank() && it.length < 80 }
                ?: "第 ${written + 1} 章"
            chapters.put(
                JSONObject()
                    .put("title", chTitle)
                    .put("text", "text/$name")
            )
            written++
        }
        if (written == 0) return "EPUB 中没有可朗读的正文（spine 中无有效 XHTML 文稿）"

        copyCover(opf, items, opfBase, destDir)

        val id = identifier?.takeIf { it.isNotBlank() } ?: (title + "_" + (author ?: ""))
        val manifest = JSONObject()
            .put("format", ManifestParser.FORMAT)
            .put("version", ManifestParser.VERSION)
            .put("id", id)
            .put("title", title)
            .put("chapters", chapters)
        author?.let { manifest.put("author", it) }
        description?.let { manifest.put("description", it) }
        File(destDir, "manifest.json").writeText(manifest.toString(), Charsets.UTF_8)
        return null
    }

    // ---- OPF ----

    private data class Item(val href: String, val mediaType: String, val properties: String)

    private fun parseManifest(opf: String): Map<String, Item> {
        val map = HashMap<String, Item>()
        for (m in Regex("""<item\b[^>]*>""").findAll(opf)) {
            val tag = m.value
            val id = attr(tag, "id") ?: continue
            val href = attr(tag, "href") ?: continue
            map[id] = Item(
                href = href,
                mediaType = attr(tag, "media-type") ?: "",
                properties = attr(tag, "properties") ?: "",
            )
        }
        return map
    }

    private fun parseSpine(opf: String): List<String> {
        val body = Regex("""(?is)<spine\b[^>]*>(.*?)</spine>""")
            .find(opf)?.groupValues?.get(1) ?: opf
        return Regex("""<itemref\b[^>]*idref\s*=\s*["']([^"']+)["']""")
            .findAll(body).map { it.groupValues[1] }.toList()
    }

    private fun isHtml(item: Item): Boolean =
        item.mediaType.contains("xhtml", true) ||
                item.mediaType.contains("html", true) ||
                item.href.substringBefore('#').lowercase().let {
                    it.endsWith(".xhtml") || it.endsWith(".html") || it.endsWith(".htm")
                }

    // ---- TOC (EPUB3 nav / EPUB2 NCX) ----

    private fun parseToc(opfBase: File, items: Map<String, Item>): Map<String, String> {
        val result = HashMap<String, String>()

        // EPUB3 nav document
        val nav = items.values.firstOrNull {
            it.properties.contains("nav") ||
                    (it.href.contains("nav", true) && it.href.endsWith(".xhtml", true))
        }
        if (nav != null) {
            val navText = resolve(opfBase, nav.href)?.takeIf { it.isFile }?.let { read(it) }
            if (navText != null) {
                for (a in Regex("""(?is)<a\b[^>]*href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""")
                    .findAll(navText)) {
                    val label = HtmlText.strip(a.groupValues[2]).trim()
                    if (label.isNotEmpty()) result[keyOf(a.groupValues[1])] = label
                }
            }
        }

        // EPUB2 NCX (fill gaps; nav takes precedence)
        val ncx = items.values.firstOrNull {
            it.mediaType.contains("ncx", true) || it.href.endsWith(".ncx", true)
        }
        if (ncx != null) {
            val ncxText = resolve(opfBase, ncx.href)?.takeIf { it.isFile }?.let { read(it) }
            if (ncxText != null) {
                for (seg in ncxText.split(Regex("(?i)<navPoint\\b")).drop(1)) {
                    val label = Regex("""(?is)<text>(.*?)</text>""").find(seg)?.groupValues?.get(1)
                        ?.let { HtmlText.strip(it).trim() }
                    val src = Regex("""(?i)<content\b[^>]*src\s*=\s*["']([^"']+)["']""")
                        .find(seg)?.groupValues?.get(1)
                    if (!label.isNullOrEmpty() && src != null) {
                        result.putIfAbsent(keyOf(src), label)
                    }
                }
            }
        }
        return result
    }

    // ---- cover ----

    private fun copyCover(opf: String, items: Map<String, Item>, opfBase: File, destDir: File) {
        val coverId =
            Regex("""<meta\b[^>]*name\s*=\s*["']cover["'][^>]*content\s*=\s*["']([^"']+)["']""")
                .find(opf)?.groupValues?.get(1)
                ?: Regex("""<meta\b[^>]*content\s*=\s*["']([^"']+)["'][^>]*name\s*=\s*["']cover["']""")
                    .find(opf)?.groupValues?.get(1)
                ?: items.entries.firstOrNull { it.value.properties.contains("cover-image") }?.key
        val item = coverId?.let { items[it] } ?: return
        val file = resolve(opfBase, item.href)?.takeIf { it.isFile } ?: return
        val ext = file.extension.lowercase().takeIf { it in COVER_EXT } ?: "jpg"
        runCatching { file.copyTo(File(destDir, "cover.$ext"), overwrite = true) }
    }

    // ---- helpers ----

    private fun read(file: File): String? =
        runCatching { file.readText(Charsets.UTF_8) }.getOrNull()

    private fun attr(tag: String, name: String): String? =
        Regex("""${Regex.escape(name)}\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(1)

    private fun firstTag(xml: String, tag: String): String? {
        val t = Regex.escape(tag)
        return Regex("""(?is)<$t\b[^>]*>(.*?)</$t>""").find(xml)?.groupValues?.get(1)
            ?.let { HtmlText.strip(it).trim() }
            ?.takeIf { it.isNotEmpty() }
    }

    private fun keyOf(href: String): String =
        href.substringBefore('#').substringBefore('?').substringAfterLast('/')

    /** Resolves an OPF-relative (possibly percent-encoded) href within [base]. */
    private fun resolve(base: File, href: String): File? {
        val clean = href.substringBefore('#').substringBefore('?')
        val decoded = runCatching { URLDecoder.decode(clean, "UTF-8") }.getOrDefault(clean)
        return try {
            val b = base.canonicalFile
            val f = File(base, decoded.replace('\\', '/')).canonicalFile
            if (f.path == b.path || f.path.startsWith(b.path + File.separator)) f else null
        } catch (e: Exception) {
            null
        }
    }

    private val COVER_EXT = setOf("jpg", "jpeg", "png", "webp")

    /** Tiny HTML -> readable text converter (no external dependencies). */
    private object HtmlText {
        fun toText(html: String): String {
            var s = html
            s = Regex("""(?is)<(script|style|head)\b[^>]*>.*?</\1>""").replace(s, " ")
            s = Regex("""(?i)<br\s*/?>""").replace(s, "\n")
            s =
                Regex("""(?i)</?(p|div|h[1-6]|li|tr|td|section|article|blockquote|pre|hr)\b[^>]*>""")
                    .replace(s, "\n")
            s = Regex("""(?s)<[^>]+>""").replace(s, " ")
            s = decodeEntities(s)
            val lines = s.lines().map { it.replace(Regex("""[ \t\u000B\f\r]+"""), " ").trim() }
            return lines.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
        }

        fun strip(html: String): String = decodeEntities(Regex("""(?s)<[^>]+>""").replace(html, ""))

        private val ENTITIES = mapOf(
            "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">",
            "quot" to "\"", "apos" to "'", "mdash" to "—", "ndash" to "–",
            "hellip" to "…", "lsquo" to "‘", "rsquo" to "’",
            "ldquo" to "“", "rdquo" to "”", "bull" to "•",
        )

        private fun decodeEntities(s: String): String =
            Regex("""&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);""").replace(s) { m ->
                val e = m.groupValues[1]
                when {
                    e.startsWith("#x") || e.startsWith("#X") ->
                        e.substring(2).toIntOrNull(16)?.let { charOf(it) } ?: m.value

                    e.startsWith("#") ->
                        e.substring(1).toIntOrNull()?.let { charOf(it) } ?: m.value

                    else -> ENTITIES[e] ?: m.value
                }
            }

        private fun charOf(cp: Int): String = runCatching {
            if (cp in 0..0x10FFFF) String(Character.toChars(cp)) else ""
        }.getOrDefault("")
    }
}
