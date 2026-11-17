package com.wildenglish.app.audiobook.data

import java.io.File
import java.io.RandomAccessFile

/** Text tags and embedded cover art read from an MP3's ID3v2 header. */
data class Mp3Tags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val picture: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Mp3Tags) return false
        return title == other.title && artist == other.artist &&
                album == other.album && picture.contentEquals(other.picture)
    }

    override fun hashCode(): Int {
        var result = title?.hashCode() ?: 0
        result = 31 * result + (artist?.hashCode() ?: 0)
        result = 31 * result + (album?.hashCode() ?: 0)
        result = 31 * result + (picture?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Minimal ID3v2 reader (MP3 only).
 *
 * Supports TIT2/TPE1/TALB text frames and APIC (v2.3/v2.4) / PIC (v2.2) embedded
 * cover art, which is how most audiobook MP3s carry their album image. Reading is
 * best-effort: malformed tags simply produce `null` instead of failing the import.
 */
object Mp3Metadata {

    private const val MAX_TAG_BYTES = 16 * 1024 * 1024

    /** Reads the ID3v2 tags of [file], or null when the file has none. */
    fun read(file: File): Mp3Tags? {
        if (!file.isFile) return null
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(10)
                if (raf.read(header) < 10) return null
                if (header[0] != 'I'.code.toByte() ||
                    header[1] != 'D'.code.toByte() ||
                    header[2] != '3'.code.toByte()
                ) {
                    return null
                }
                val major = header[3].toInt() and 0xFF
                if (major !in 2..4) return null
                val flags = header[5].toInt() and 0xFF
                val size = syncSafe(header, 6).coerceAtMost(MAX_TAG_BYTES)
                if (size <= 0) return null
                val data = ByteArray(size)
                var read = 0
                while (read < size) {
                    val n = raf.read(data, read, size - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < 10) return null
                parseTag(data, major, flags)
            }
        }.getOrNull()
    }

    /**
     * Writes the first embedded picture found in [tags] into [dir] as `cover.jpg/png/webp`.
     * Returns the written file, or null when the tags carry no usable image.
     */
    fun writeCover(tags: Mp3Tags?, dir: File): File? {
        val bytes = tags?.picture?.takeIf { it.size > 16 } ?: return null
        val extension = when {
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "png"
            bytes.size > 12 &&
                    String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
                    String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "webp"

            else -> return null
        }
        val target = File(dir, "cover.$extension")
        return runCatching {
            target.writeBytes(bytes)
            target
        }.getOrNull()
    }

    private fun parseTag(data: ByteArray, major: Int, flags: Int): Mp3Tags? {
        // v2.2 compression makes the frame layout unreadable; skip such tags.
        if (major == 2 && flags and 0x40 != 0) return null

        var pos = 0
        if (major != 2 && flags and 0x40 != 0) {
            // Extended header: v2.3 size excludes its own 4 bytes, v2.4 includes them.
            val skip = if (major == 4) syncSafe(data, 0) else beInt(data, 0) + 4
            if (skip <= 0 || skip >= data.size) return null
            pos = skip
        }

        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var picture: ByteArray? = null
        val headerSize = if (major == 2) 6 else 10

        while (pos + headerSize <= data.size) {
            val idLength = if (major == 2) 3 else 4
            val id = String(data, pos, idLength, Charsets.ISO_8859_1)
            if (id[0] == '\u0000' || id.any { it.code < 0x20 || it.code > 0x7E }) break
            val frameSize = when {
                major == 2 -> u24(data, pos + 3)
                major == 4 -> syncSafe(data, pos + 4)
                else -> beInt(data, pos + 4)
            }
            val body = pos + headerSize
            if (frameSize <= 0 || body + frameSize > data.size) break
            when (id) {
                "TIT2", "TT2" -> if (title == null) title = decodeText(data, body, frameSize)
                "TPE1", "TP1" -> if (artist == null) artist = decodeText(data, body, frameSize)
                "TALB", "TAL" -> if (album == null) album = decodeText(data, body, frameSize)
                "APIC" -> if (picture == null) picture =
                    parsePicture(data, body, frameSize, modern = true)

                "PIC" -> if (picture == null) picture =
                    parsePicture(data, body, frameSize, modern = false)
            }
            pos = body + frameSize
        }

        if (title == null && artist == null && album == null && picture == null) return null
        return Mp3Tags(title, artist, album, picture)
    }

    private fun decodeText(data: ByteArray, offset: Int, size: Int): String? {
        if (size <= 1) return null
        val encoding = data[offset].toInt() and 0xFF
        val charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return runCatching {
            String(data, offset + 1, size - 1, charset)
                .trim('\u0000', ' ', '\uFEFF')
                .takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun parsePicture(
        data: ByteArray,
        offset: Int,
        size: Int,
        modern: Boolean,
    ): ByteArray? {
        val end = offset + size
        if (size < 5 || end > data.size) return null
        val encoding = data[offset].toInt() and 0xFF
        var pos = offset + 1
        pos += if (modern) {
            val mimeEnd = indexOfZero(data, pos, end)
            if (mimeEnd < 0) return null
            (mimeEnd + 1) - pos
        } else {
            3 // v2.2 stores a 3-character format tag ("JPG"/"PNG")
        }
        pos += 1 // picture type
        pos = skipEncodedTerminator(data, pos, end, encoding)
        if (pos >= end) return null
        return data.copyOfRange(pos, end)
    }

    /** Skips a null-terminated description written in the frame's text encoding. */
    private fun skipEncodedTerminator(data: ByteArray, start: Int, end: Int, encoding: Int): Int {
        if (encoding == 1 || encoding == 2) {
            var i = start
            while (i + 1 < end) {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) return i + 2
                i += 2
            }
            return end
        }
        val zero = indexOfZero(data, start, end)
        return if (zero < 0) end else zero + 1
    }

    private fun indexOfZero(data: ByteArray, start: Int, end: Int): Int {
        var i = start.coerceAtLeast(0)
        while (i < end && i < data.size) {
            if (data[i].toInt() == 0) return i
            i++
        }
        return -1
    }

    /** ID3 frame sizes use 7-bit bytes so they never collide with a frame marker. */
    private fun syncSafe(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return 0
        return ((data[offset].toInt() and 0x7F) shl 21) or
                ((data[offset + 1].toInt() and 0x7F) shl 14) or
                ((data[offset + 2].toInt() and 0x7F) shl 7) or
                (data[offset + 3].toInt() and 0x7F)
    }

    private fun beInt(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
    }

    private fun u24(data: ByteArray, offset: Int): Int {
        if (offset + 3 > data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 16) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                (data[offset + 2].toInt() and 0xFF)
    }
}
