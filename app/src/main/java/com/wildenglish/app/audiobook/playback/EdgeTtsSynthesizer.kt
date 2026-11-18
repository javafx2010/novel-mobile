package com.wildenglish.app.audiobook.playback

import android.util.Log
import com.wildenglish.app.R
import com.wildenglish.app.i18n.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * One-shot Edge TTS synthesis: turns a single sentence into MP3 bytes.
 *
 * Single owner of the (undocumented, frequently changing) Edge TTS WebSocket
 * protocol, shared by [VoicePreGenerator] (bulk offline synthesis) and
 * [SpeechController] (live fallback for cache misses) so a protocol fix is
 * made in exactly one place.
 *
 * Audio is always synthesized at a neutral rate; playback speed is applied by
 * MediaPlayer PlaybackParams, which keeps cached audio speed-independent.
 */
class EdgeTtsSynthesizer {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var clockSkewSeconds = 0.0
    private val clockSyncAttempted = AtomicBoolean(false)

    init {
        if (!GecToken.selfTest()) Log.e(TAG, "Sec-MS-GEC v2 self-test FAILED")
    }

    /** Best-effort one-time server-clock alignment; safe to call repeatedly. */
    suspend fun syncClock() {
        if (!clockSyncAttempted.compareAndSet(false, true)) return
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(CLOCK_URL)
                    .header("User-Agent", USER_AGENT).build()
                client.newCall(req).execute().use { resp ->
                    val server = resp.header("Date")?.let(::parseHttpDate) ?: return@withContext
                    clockSkewSeconds = server - System.currentTimeMillis() / 1000L
                }
            } catch (e: Exception) {
                clockSkewSeconds = 0.0 // device clock is normally NTP-synced
            }
        }
    }

    private fun adjustClockSkew(date: String): Boolean {
        val server = parseHttpDate(date) ?: return false
        clockSkewSeconds = server - System.currentTimeMillis() / 1000L
        return true
    }

    /** @throws IOException on unrecoverable failure. */
    suspend fun synthesize(text: String, voice: String, lang: String): ByteArray {
        syncClock()
        var attempt = 0
        while (true) {
            when (val result = synthesizeOnce(text, voice, lang)) {
                is SynthResult.Ok -> return result.bytes
                is SynthResult.Fail -> {
                    val serverDate = result.serverDate
                    val skewFixed = (result.httpCode == 401 || result.httpCode == 403) &&
                            serverDate != null && adjustClockSkew(serverDate)
                    if (attempt < MAX_SKEW_RETRIES && skewFixed) {
                        attempt++
                        continue
                    }
                    throw IOException(result.message)
                }
            }
        }
    }

    private suspend fun synthesizeOnce(text: String, voice: String, lang: String): SynthResult =
        suspendCancellableCoroutine { cont ->
            val audio = ByteArrayOutputStream()
            val finished = AtomicBoolean(false)
            var socket: WebSocket? = null

            fun complete(result: SynthResult) {
                if (!finished.compareAndSet(false, true)) return
                try {
                    socket?.cancel()
                } catch (e: Exception) {
                }
                if (cont.isActive) cont.resume(result)
            }

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val ts = dateString()
                    webSocket.send(
                        "X-Timestamp:$ts\r\n" +
                                "Content-Type:application/json; charset=utf-8\r\n" +
                                "Path:speech.config\r\n\r\n" + CONFIG
                    )
                    webSocket.send(
                        "X-RequestId:${uuid()}\r\n" +
                                "Content-Type:application/ssml+xml\r\n" +
                                "X-Timestamp:${ts}Z\r\n" +
                                "Path:ssml\r\n\r\n" + buildSsml(text, voice, lang)
                    )
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (finished.get()) return
                    val data = bytes.toByteArray()
                    if (data.size < 2) return
                    val headerLen = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                    if (data.size < 2 + headerLen) return
                    val header = String(data, 2, headerLen, Charsets.US_ASCII)
                    if (header.contains("Path:audio") && !header.contains("audio.metadata")) {
                        synchronized(audio) {
                            audio.write(data, 2 + headerLen, data.size - 2 - headerLen)
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("Path:turn.end")) {
                        val bytes = synchronized(audio) { audio.toByteArray() }
                        complete(SynthResult.Ok(bytes))
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    try {
                        webSocket.close(1000, null)
                    } catch (e: Exception) {
                    }
                    completeFromClose()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                    completeFromClose()

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val code = response?.code
                    val date = response?.header("Date")
                    try {
                        response?.close()
                    } catch (e: Exception) {
                    }
                    complete(
                        SynthResult.Fail(
                            Strings.get(R.string.speech_http_error, code?.toString() ?: "?") +
                                    (t.message?.take(120)
                                        ?: Strings.get(R.string.network_unavailable)),
                            code, date
                        )
                    )
                }

                private fun completeFromClose() {
                    val bytes = synchronized(audio) { audio.toByteArray() }
                    complete(
                        if (bytes.isEmpty()) {
                            SynthResult.Fail(Strings.get(R.string.connection_closed), null, null)
                        }
                        else SynthResult.Ok(bytes)
                    )
                }
            }

            val request = Request.Builder()
                .url(wsUrl())
                .header("User-Agent", USER_AGENT)
                .header("Origin", ORIGIN)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Accept-Encoding", "gzip, deflate, br, zstd")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Sec-WebSocket-Version", "13")
                .header("Cookie", "muid=${generateMuid()};")
                .build()

            try {
                socket = client.newWebSocket(request, listener)
            } catch (e: Exception) {
                complete(
                    SynthResult.Fail(
                        Strings.get(R.string.connect_failed, e.message ?: ""), null, null
                    )
                )
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation {
                try {
                    socket?.cancel()
                } catch (e: Exception) {
                }
            }
        }

    private sealed interface SynthResult {
        class Ok(val bytes: ByteArray) : SynthResult
        class Fail(val message: String, val httpCode: Int?, val serverDate: String?) : SynthResult
    }

    // ---- protocol helpers ----

    private fun wsUrl(): String {
        val unix = System.currentTimeMillis() / 1000.0 + clockSkewSeconds
        val gec = GecToken.generate(unix)
        return "$WSS_ENDPOINT?TrustedClientToken=$TRUSTED_CLIENT_TOKEN" +
                "&Sec-MS-GEC=$gec" +
                "&Sec-MS-GEC-Version=$GEC_VERSION" +
                "&ConnectionId=${uuid()}"
    }

    private fun parseHttpDate(date: String): Double? = runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .parse(date)?.time?.div(1000.0)
    }.getOrNull()

    private fun buildSsml(text: String, voice: String, lang: String): String =
        "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$lang'>" +
                "<voice name='$voice'><prosody pitch='+0Hz' rate='+0%' volume='+0%'>" +
                cleanText(text) + "</prosody></voice></speak>"

    private fun cleanText(text: String): String {
        val sb = StringBuilder()
        for (c in text) {
            val code = c.code
            sb.append(if (code in 0..8 || code in 11..12 || code in 14..31) ' ' else c)
        }
        return sb.toString()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun dateString(): String =
        SimpleDateFormat(
            "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
            Locale.US
        )
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .format(Date())

    private fun uuid(): String = UUID.randomUUID().toString().replace("-", "")

    private fun generateMuid(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        val sb = StringBuilder(32)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]); sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    companion object {
        private const val TAG = "EdgeTts"
        private const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
        private const val CHROMIUM_MAJOR_VERSION = "143"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/$CHROMIUM_MAJOR_VERSION.0.0.0 Safari/537.36 " +
                    "Edg/$CHROMIUM_MAJOR_VERSION.0.0.0"
        private const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
        private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val GEC_VERSION = "2-$CHROMIUM_FULL_VERSION"
        private const val WSS_ENDPOINT =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
        private const val CLOCK_URL = "https://speech.platform.bing.com/"
        private const val MAX_SKEW_RETRIES = 1
        private const val CONFIG =
            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
                    "{\"sentenceBoundaryEnabled\":\"true\",\"wordBoundaryEnabled\":\"false\"}," +
                    "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"
        private val HEX = "0123456789ABCDEF".toCharArray()
    }
}
