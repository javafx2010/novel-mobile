package com.wildenglish.app.audiobook.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.wildenglish.app.R
import com.wildenglish.app.i18n.Strings
import java.io.IOException

class PlayerController(appContext: Context, private val callback: Callback) {

    interface Callback {
        fun onPrepared(durationMs: Long)
        fun onPosition(positionMs: Long, durationMs: Long)
        fun onPlayingChanged(isPlaying: Boolean)
        fun onBuffering(isBuffering: Boolean)
        fun onCompletion()
        fun onError(message: String)
    }

    private val thread = HandlerThread("audiobook-player").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    private var player: MediaPlayer? = null
    private var generation = 0
    private var playing = false
    private var buffering = false
    private var speed = 1.0f
    private var pendingStartMs = 0L

    @Volatile private var released = false
    @Volatile private var positionMirror = 0L
    @Volatile private var durationMirror = 0L

    private val poll = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!playing) return
            val pos = try {
                p.currentPosition.toLong().coerceAtLeast(0)
            } catch (e: IllegalStateException) {
                return
            }
            positionMirror = pos
            postMain { callback.onPosition(pos, durationMirror) }
            handler.postDelayed(this, 500L)
        }
    }

    fun play(url: String, startMs: Long, speed: Float) = post {
        generation++
        val token = generation
        releasePlayerLocked()
        this.speed = speed.coerceIn(0.5f, 3.0f)
        pendingStartMs = startMs.coerceAtLeast(0)
        positionMirror = pendingStartMs
        durationMirror = 0L
        setBufferingLocked(true)
        val p = MediaPlayer()
        player = p
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            p.setDataSource(url)
            p.setOnPreparedListener { mp -> if (token == generation) onPreparedLocked(mp) }
            p.setOnCompletionListener { if (token == generation) onCompletionLocked() }
            p.setOnErrorListener { _, what, extra ->
                if (token == generation) {
                    failLocked(Strings.get(R.string.player_error, what, extra))
                }
                true
            }
            p.prepareAsync()
        } catch (e: IOException) {
            if (token == generation) {
                failLocked(Strings.get(R.string.audio_load_failed, e.message ?: ""))
            }
        } catch (e: IllegalStateException) {
            if (token == generation) {
                failLocked(Strings.get(R.string.player_state_error, e.message ?: ""))
            }
        } catch (e: IllegalArgumentException) {
            if (token == generation) {
                failLocked(Strings.get(R.string.audio_invalid_url, e.message ?: ""))
            }
        }
    }

    fun pause() = post { pauseLocked() }
    fun resume() = post { resumeLocked() }
    fun togglePlayPause() = post { if (playing) pauseLocked() else resumeLocked() }

    fun seekTo(ms: Long) = post {
        val p = player ?: return@post
        val dur = durationMirror
        if (dur <= 0L) {
            pendingStartMs = ms.coerceAtLeast(0)
            positionMirror = pendingStartMs
            return@post
        }
        val target = ms.coerceIn(0L, dur)
        try {
            p.seekTo(target.toInt())
        } catch (e: IllegalStateException) {
            return@post
        }
        positionMirror = target
        postMain { callback.onPosition(target, dur) }
    }

    fun skipBy(deltaMs: Long) = seekTo(positionMirror + deltaMs)

    fun setSpeed(value: Float) = post {
        speed = value.coerceIn(0.5f, 3.0f)
        player?.let { applySpeedLocked(it) }
    }

    fun stop() = post {
        generation++
        releasePlayerLocked()
        setBufferingLocked(false)
        postMain { callback.onPlayingChanged(false) }
    }

    fun release() {
        if (released) return
        released = true
        handler.post {
            generation++
            releasePlayerLocked()
            handler.removeCallbacks(poll)
            thread.quitSafely()
        }
    }

    private fun onPreparedLocked(mp: MediaPlayer) {
        setBufferingLocked(false)
        durationMirror = try {
            mp.duration.toLong().coerceAtLeast(0)
        } catch (e: IllegalStateException) {
            0L
        }
        val dur = durationMirror
        if (pendingStartMs > 0 && (dur <= 0 || pendingStartMs < dur)) {
            try {
                mp.seekTo(pendingStartMs.toInt())
            } catch (e: IllegalStateException) {
            }
            positionMirror = pendingStartMs
        }
        pendingStartMs = 0L
        resumeLocked()
        postMain { callback.onPrepared(dur) }
    }

    private fun resumeLocked() {
        val p = player ?: return
        if (playing) return
        applySpeedLocked(p)
        try {
            p.start()
        } catch (e: IllegalStateException) {
            postMain {
                callback.onError(Strings.get(R.string.player_start_failed, e.message ?: ""))
            }
            return
        }
        playing = true
        postMain { callback.onPlayingChanged(true) }
        startPollingLocked()
    }

    private fun pauseLocked() {
        val p = player ?: return
        if (!playing) return
        try {
            p.pause()
        } catch (e: IllegalStateException) {
            return
        }
        positionMirror = try {
            p.currentPosition.toLong().coerceAtLeast(0)
        } catch (e: IllegalStateException) {
            positionMirror
        }
        playing = false
        handler.removeCallbacks(poll)
        postMain { callback.onPlayingChanged(false) }
    }

    private fun onCompletionLocked() {
        playing = false
        handler.removeCallbacks(poll)
        val dur = durationMirror
        positionMirror = dur
        postMain {
            callback.onPlayingChanged(false)
            callback.onPosition(dur, dur)
            callback.onCompletion()
        }
    }

    private fun failLocked(message: String) {
        generation++
        releasePlayerLocked()
        setBufferingLocked(false)
        postMain { callback.onError(message) }
    }

    private fun releasePlayerLocked() {
        handler.removeCallbacks(poll)
        playing = false
        val p = player ?: return
        player = null
        try { p.reset() } catch (e: Exception) { }
        try { p.release() } catch (e: Exception) { }
    }

    private fun startPollingLocked() {
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    private fun setBufferingLocked(value: Boolean) {
        if (buffering == value) return
        buffering = value
        postMain { callback.onBuffering(value) }
    }

    private fun applySpeedLocked(p: MediaPlayer) {
        try {
            val params = try { p.playbackParams } catch (e: IllegalStateException) { PlaybackParams() }
            p.playbackParams = (params ?: PlaybackParams()).setSpeed(speed)
        } catch (e: IllegalStateException) {
        } catch (e: IllegalArgumentException) {
        }
    }

    private fun post(block: () -> Unit) {
        if (released) return
        handler.post(Runnable { if (!released) block() })
    }

    private fun postMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(Runnable { block() })
    }
}
