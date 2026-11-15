package com.wildenglish.app.audiobook.data

import java.io.File

/** A time-aligned line of the transcript (WebVTT / SRT cue). */
data class TranscriptCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/**
 * Minimal WebVTT / SRT parser. Any line containing `-->` starts a cue; the
 * following non-blank lines (until the next cue or blank line) are its text.
 * Cue settings after the end timestamp are ignored.
 */
object CueParser {

    fun parse(file: File?): List<TranscriptCue> {
        if (file == null || !file.isFile) return emptyList()
        val lines = runCatching { file.readLines(Charsets.UTF_8) }.getOrNull() ?: return emptyList()
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
                if (candidate.isEmpty() || candidate.contains("-->") || candidate.startsWith("WEBVTT")) break
                // SRT puts a numeric index right before the next timestamp line.
                if (candidate.all { it.isDigit() } &&
                    i + 1 < lines.size && lines[i + 1].contains("-->")
                ) break
                textLines.add(candidate)
                i++
            }
            val text = textLines.joinToString("\n").trim()
            if (text.isNotEmpty()) {
                cues.add(TranscriptCue(start, end, text))
            }
        }
        return cues.sortedBy { it.startMs }
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
}

/**
 * Loads the sentences to be read aloud for a chapter. When a WebVTT/SRT cue
 * file exists its cue texts are used verbatim (authored sentence boundaries);
 * otherwise the plain transcript is split into sentences. This function does
 * blocking IO — call it from a background dispatcher.
 */
fun loadChapterSentences(chapter: Chapter): List<String> {
    val cues = CueParser.parse(chapter.cuesFile)
    if (cues.isNotEmpty()) return cues.map { it.text }
    val text = chapter.textFile?.takeIf { it.isFile }
        ?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrNull() }
    return text?.let { CueParser.splitSentences(it) }.orEmpty()
}

/** Auto-detects `text/001.vtt` / `.srt` next to a plain transcript file. */
internal fun findSiblingCues(text: File): File? {
    val base = text.nameWithoutExtension
    return text.parentFile?.listFiles()?.firstOrNull {
        it.isFile && it.nameWithoutExtension == base &&
            it.extension.lowercase() in setOf("vtt", "srt")
    }
}
