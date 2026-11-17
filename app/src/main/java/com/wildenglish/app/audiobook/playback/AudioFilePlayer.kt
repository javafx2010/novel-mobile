package com.wildenglish.app.audiobook.playback

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Plays a chapter's real audio file (MP3 / M4A / FLAC...) directly.
 *
 * This is the "audio book" playback mode: books that ship pre-recorded audio never
 * touch Edge TTS and do not use subtitles — the gramophone player only shows the
 * playback position. All calls and callbacks happen on the main thread.
 */
class AudioFilePlayer(private val callback: Callback) {

    interface Callback {
        fun onPrepared(durationMs: Long)
        fun onPosition(positionMs: Long)
        fun onPlayingChanged(isPlaying: Boolean)
        fun onChapterComplete()
        fun onError(message: String)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var durationMs = 0L
    private var speed = 1f
    private var generation = 0
    private var prepared = false
    private var playing = false
    private var startMs = 0L

    val isPlaying: Boolean get() = playing

    private val ticker = object : Runnable {
        override fun run() {
            val mp = player ?: return
            if (!prepared || !playing) return
            val position = runCatching { mp.currentPosition.toLong() }.getOrDefault(0L)
            callback.onPosition(position)
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** Starts (or loads paused) [file] from [startMs]. */
    fun load(file: File, startMs: Long, speed: Float, play: Boolean) {
        generation++
        val gen = generation
        handler.removeCallbacks(ticker)
        releasePlayer()
        this.durationMs = 0L
        this.speed = speed.coerceIn(0.5f, 2.0f)
        this.startMs = startMs.coerceAtLeast(0L)
        this.prepared = false
        this.playing = play

        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener { preparedPlayer ->
                if (gen != generation) return@setOnPreparedListener
                prepared = true
                durationMs = runCatching { preparedPlayer.duration.toLong() }
                    .getOrDefault(0L)
                    .coerceAtLeast(0L)
                applySpeed(preparedPlayer)
                val start = if (durationMs > 0) startMs.coerceIn(0L, durationMs) else startMs
                if (start > 0) runCatching { preparedPlayer.seekTo(start.toInt()) }
                if (playing) runCatching { preparedPlayer.start() }
                callback.onPrepared(durationMs)
                callback.onPosition(start)
                callback.onPlayingChanged(playing)
                scheduleTick()
            }
            mp.setOnCompletionListener {
                if (gen != generation) return@setOnCompletionListener
                playing = false
                handler.removeCallbacks(ticker)
                callback.onPosition(durationMs)
                callback.onPlayingChanged(false)
                callback.onChapterComplete()
            }
            mp.setOnErrorListener { _, what, extra ->
                if (gen == generation) {
                    playing = false
                    prepared = false
                    handler.removeCallbacks(ticker)
                    releasePlayer()
                    callback.onPlayingChanged(false)
                    callback.onError("音频播放失败 ($what/$extra)")
                }
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            releasePlayer()
            prepared = false
            playing = false
            callback.onError("无法播放音频：" + (e.message ?: file.name))
        }
    }

    fun pause() {
        if (!playing) return
        playing = false
        handler.removeCallbacks(ticker)
        if (prepared) runCatching { player?.pause() }
        callback.onPlayingChanged(false)
    }

    fun resume() {
        val mp = player ?: return
        if (playing) return
        playing = true
        if (prepared) {
            applySpeed(mp)
            runCatching { mp.start() }
        }
        scheduleTick()
        callback.onPlayingChanged(true)
    }

    fun toggle() {
        if (playing) pause() else resume()
    }

    fun setSpeed(value: Float) {
        speed = value.coerceIn(0.5f, 2.0f)
        if (prepared) player?.let { applySpeed(it) }
    }

    /** Seeks to an absolute position; before preparation this becomes the start offset. */
    fun seekTo(positionMs: Long) {
        val target = if (durationMs > 0) {
            positionMs.coerceIn(0L, durationMs)
        } else {
            positionMs.coerceAtLeast(0L)
        }
        startMs = target
        if (prepared) {
            runCatching { player?.seekTo(target.toInt()) }
            callback.onPosition(target)
        }
    }

    fun stop() {
        generation++
        playing = false
        prepared = false
        durationMs = 0L
        startMs = 0L
        handler.removeCallbacks(ticker)
        releasePlayer()
    }

    fun release() = stop()

    private fun scheduleTick() {
        handler.removeCallbacks(ticker)
        if (playing) handler.postDelayed(ticker, TICK_MS)
    }

    private fun applySpeed(mp: MediaPlayer) {
        try {
            val params = try {
                mp.playbackParams
            } catch (e: IllegalStateException) {
                PlaybackParams()
            }
            mp.playbackParams = params.setSpeed(speed.coerceIn(0.5f, 2.0f))
        } catch (e: IllegalStateException) {
        } catch (e: IllegalArgumentException) {
        }
    }

    private fun releasePlayer() {
        val p = player ?: return
        player = null
        runCatching { p.reset() }
        runCatching { p.release() }
    }

    private companion object {
        const val TICK_MS = 400L
    }
}
