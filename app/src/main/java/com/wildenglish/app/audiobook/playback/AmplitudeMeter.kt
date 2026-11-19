package com.wildenglish.app.audiobook.playback

import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * Process-wide audio amplitude meter that drives the vinyl trail effect.
 *
 * Reads the waveform of the active MediaPlayer's own audio session via
 * [Visualizer] (no RECORD_AUDIO permission needed) and publishes a smoothed
 * 0..1 RMS level. The two playback engines attach their active player; whichever
 * is playing wins (they never run at the same time). Consumers read [level] in
 * the draw phase so updates redraw the canvas without recomposing the player.
 */
object AmplitudeMeter {
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private var visualizer: Visualizer? = null
    private var boundSession = -1
    private var smoothed = 0f
    private var running = false
    private val wave = ByteArray(WAVE_SIZE)

    private val sampler = object : Runnable {
        override fun run() {
            val v = visualizer
            if (v == null || !running) return
            val rms = safeRms(v)
            // Fast attack, slow release gives a natural waveform-like envelope.
            val factor = if (rms > smoothed) 0.55f else 0.18f
            smoothed += (rms - smoothed) * factor
            _level.value = smoothed
            handler.postDelayed(this, SAMPLE_MS)
        }
    }

    /** Binds the currently playing player and starts sampling its audio session. */
    @Synchronized
    fun attach(mp: MediaPlayer) {
        val session = runCatching { mp.audioSessionId }.getOrNull() ?: return
        releaseVisualizer()
        val v = runCatching {
            Visualizer(session).apply {
                runCatching { setCaptureSize(WAVE_SIZE) }
                runCatching { setEnabled(true) }
            }
        }.getOrNull() ?: return
        visualizer = v
        boundSession = session
        smoothed = 0f
        _level.value = 0f
        if (!running) {
            running = true
            handler.post(sampler)
        }
    }

    /** Unbinds the active player and stops sampling until the next attach. */
    @Synchronized
    fun detach(mp: MediaPlayer) {
        if (visualizer == null) return
        if (runCatching { mp.audioSessionId }.getOrNull() != boundSession) return
        stopLoop()
    }

    @Synchronized
    fun release() {
        stopLoop()
    }

    private fun safeRms(v: Visualizer): Float {
        // getWaveForm writes 8-bit unsigned samples centred on 128.
        if (runCatching { v.getWaveForm(wave) }.getOrDefault(-1) != Visualizer.SUCCESS) return 0f
        var sum = 0.0
        for (b in wave) {
            val centered = (b.toInt() - 128) / 128.0
            sum += centered * centered
        }
        // Scale up for speech, which is quiet relative to full scale.
        return (sqrt(sum / wave.size) * 3.2).coerceIn(0.0, 1.0).toFloat()
    }

    private fun stopLoop() {
        running = false
        handler.removeCallbacks(sampler)
        smoothed = 0f
        _level.value = 0f
        releaseVisualizer()
    }

    private fun releaseVisualizer() {
        visualizer?.let { v -> runCatching { v.setEnabled(false) }; runCatching { v.release() } }
        visualizer = null
        boundSession = -1
    }

    private const val SAMPLE_MS = 40L
    private const val WAVE_SIZE = 128
}
