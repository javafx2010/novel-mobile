package com.wildenglish.app.audiobook.data

import java.io.File
import java.nio.charset.Charset

/** A time-aligned line of the transcript (WebVTT / SRT / ASS / LRC cue). */
data class TranscriptCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/** Subtitle formats the app can load next to (or anywhere inside) a book package. */
val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "lrc")

/** Plain transcript formats. */
val TRANSCRIPT_EXTENSIONS = setOf("txt", "md")

/**
 * Minimal subtitle parser. SRT/WebVTT use `-->` cue lines, ASS/SSA use `Dialogue:`
 * lines and LRC uses `[mm:ss.xx]` tags. Everything is best-effort so a partially
 * malformed file still yields the cues that can be read.
 */
object CueParser {

    fun parse(file: File?): List<TranscriptCue> {
        if (file == null || !file.isFile) return emptyList()
        val text = readText(file) ?: return emptyList()
        val lines = text.lines()
        return when (file.extension.lowercase()) {
            "lrc" -> parseLrc(lines)
            "ass", "ssa" -> parseAss(lines)
            else -> parseSrtVtt(lines)
        }.sortedBy { it.startMs }
    }

    /**
     * Reads a subtitle file tolerantly: strips a UTF BOM, falls back to GBK when the
     * bytes are not valid UTF-8 (Chinese SRT/LRC files are frequently GBK-encoded).
     */
    private fun readText(file: File): String? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        return when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
                    bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                String(bytes, 3, bytes.size - 3, Charsets.UTF_8)

            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)

            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)

            else -> {
                val utf8 = String(bytes, Charsets.UTF_8)
                if ('\uFFFD' !in utf8) {
                    utf8
                } else {
                    runCatching { String(bytes, Charset.forName("GBK")) }
                        .getOrDefault(utf8)
                }
            }
        }
    }

    /** SRT / WebVTT: any line containing `-->` starts a cue. */
    private fun parseSrtVtt(lines: List<String>): List<TranscriptCue> {
        val cues = ArrayList<TranscriptCue>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            val arrow = line.indexOf("-->")
            if (arrow < 0) {
                i++
                continue
            }
            val start = parseTimestamp(line.substring(0, arrow).trim())
            val endToken = line.substring(arrow + 3).trim().substringBefore(' ')
            val end = parseTimestamp(endToken)
            i++
            if (start == null || end == null || end < start) continue

            val textLines = ArrayList<String>()
            while (i < lines.size) {
                val candidate = lines[i].trim()
                if (candidate.isEmpty() || candidate.contains("-->") ||
                    candidate.startsWith("WEBVTT")
                ) {
                    break
                }
                // SRT puts a numeric index right before the next timestamp line.
                if (candidate.all { it.isDigit() } &&
                    i + 1 < lines.size && lines[i + 1].contains("-->")
                ) {
                    break
                }
                textLines.add(candidate)
                i++
            }
            val text = textLines.joinToString("\n").trim()
            if (text.isNotEmpty()) cues.add(TranscriptCue(start, end, text))
        }
        return cues
    }

    /** ASS/SSA: `Dialogue: 0,0:00:01.00,0:00:05.00,Default,,0,0,0,,text`. */
    private fun parseAss(lines: List<String>): List<TranscriptCue> {
        val cues = ArrayList<TranscriptCue>()
        for (raw in lines) {
            val line = raw.trim()
            if (!line.startsWith("Dialogue:", ignoreCase = true)) continue
            val parts = line.substringAfter(':').trim().split(",", limit = 10)
            if (parts.size < 10) continue
            val start = parseTimestamp(parts[1].trim())
            val end = parseTimestamp(parts[2].trim())
            if (start == null || end == null || end < start) continue
            val text = parts[9]
                .replace(Regex("\\{[^}]*}"), "")
                .replace("\\N", "\n")
                .replace("\\n", "\n")
                .trim()
            if (text.isNotEmpty()) cues.add(TranscriptCue(start, end, text))
        }
        return cues
    }

    /** LRC: `[00:12.34]lyric` — each line ends when the next timestamp starts. */
    private fun parseLrc(lines: List<String>): List<TranscriptCue> {
        val tag = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
        val stamps = ArrayList<Pair<Long, String>>()
        for (line in lines) {
            val text = tag.replace(line, "").trim()
            if (text.isEmpty()) continue
            for (match in tag.findAll(line)) {
                val minutes = match.groupValues[1].toLongOrNull() ?: continue
                val seconds = match.groupValues[2].toDoubleOrNull() ?: continue
                val fraction = match.groupValues[3]
                val millis = minutes * 60_000 + (seconds * 1000).toLong() +
                        when (fraction.length) {
                            1 -> fraction.toLongOrNull()?.times(100) ?: 0L
                            2 -> fraction.toLongOrNull()?.times(10) ?: 0L
                            3 -> fraction.toLongOrNull() ?: 0L
                            else -> 0L
                        }
                stamps.add(millis to text)
            }
        }
        stamps.sortBy { it.first }
        return stamps.mapIndexed { index, (start, text) ->
            val next = stamps.getOrNull(index + 1)?.first ?: (start + 5_000L)
            TranscriptCue(start, maxOf(next, start + 200L), text)
        }
    }

    /** Accepts `HH:MM:SS.mmm` and `MM:SS.mmm`; SRT commas are normalised to dots. */
    private fun parseTimestamp(raw: String): Long? {
        val parts = raw.replace(',', '.').trim().split(':')
        return try {
            when (parts.size) {
                3 -> {
                    val hours = parts[0].toLong()
                    val minutes = parts[1].toLong()
                    val seconds = parts[2].toDouble()
                    (hours * 3600 + minutes * 60) * 1000 + (seconds * 1000).toLong()
                }

                2 -> {
                    val minutes = parts[0].toLong()
                    val seconds = parts[1].toDouble()
                    minutes * 60_000 + (seconds * 1000).toLong()
                }

                else -> null
            }
        } catch (e: NumberFormatException) {
            null
        }
    }

    /**
     * Splits a plain transcript into the sentences the TTS engine will read one
     * by one. Sentence boundaries cover both Chinese and English punctuation; a
     * period only ends a sentence when followed by whitespace (so 3.14 survives).
     * Very long lines (an unpunctuated paragraph) are broken at word boundaries
     * so a single synthesis request stays within the service's text limits.
     */
    fun splitSentences(text: String): List<String> {
        val normalized = text.trim()
            .replace(Regex("([。！？；;!?])\\s*"), "$1\n")
            .replace(Regex("([.])\\s+"), "$1\n")
        return normalized.split(Regex("\\n+"))
            .flatMap { chunkLong(it.trim(), MAX_SENTENCE_CHARS) }
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private const val MAX_SENTENCE_CHARS = 300

    /** Breaks [line] at whitespace so no chunk exceeds [max] characters. */
    private fun chunkLong(line: String, max: Int): List<String> {
        if (line.length <= max) return listOf(line)
        val out = ArrayList<String>()
        var rest = line
        while (rest.length > max) {
            var cut = rest.lastIndexOf(' ', max)
            if (cut < max / 2) cut = max // no sensible word boundary (e.g. CJK): hard cut
            out.add(rest.substring(0, cut))
            rest = rest.substring(cut).trimStart()
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }
}

/**
 * Loads the sentences to be read aloud for a chapter. When a subtitle file exists
 * its cue texts are used verbatim; otherwise the plain transcript is split into
 * sentences. This function does blocking IO — call it from a background dispatcher.
 */
fun loadChapterSentences(chapter: Chapter): List<String> {
    val cues = CueParser.parse(chapter.cuesFile)
    if (cues.isNotEmpty()) return cues.map { it.text }
    val text = chapter.textFile?.takeIf { it.isFile }
        ?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrNull() }
    return text?.let { CueParser.splitSentences(it) }.orEmpty()
}

/** Auto-detects `text/001.vtt` / `.srt` next to a plain transcript file. */
internal fun findSiblingCues(text: File): File? =
    findSiblingByExtension(text, SUBTITLE_EXTENSIONS)

/** Auto-detects a plain transcript next to an audio file. */
internal fun findSiblingText(audio: File): File? =
    findSiblingByExtension(audio, TRANSCRIPT_EXTENSIONS)

private fun findSiblingByExtension(file: File, extensions: Set<String>): File? {
    val base = file.nameWithoutExtension
    return file.parentFile?.listFiles()?.firstOrNull {
        it.isFile && it.nameWithoutExtension.equals(base, ignoreCase = true) &&
                it.extension.lowercase() in extensions
    }
}
