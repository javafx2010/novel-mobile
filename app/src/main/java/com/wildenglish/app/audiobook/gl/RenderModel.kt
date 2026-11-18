package com.wildenglish.app.audiobook.gl

import android.graphics.Bitmap

/** Which 3D set is visible behind the Compose HUD. */
enum class SceneMode { SHELF, DETAIL, TURNTABLE }

/** One selectable disc in the 3D scene. [key] is stable and used for hit dispatch. */
data class RecordItem(
    val key: String,
    val coverKey: String,
    val title: String,
    val subtitle: String? = null,
    val isCurrent: Boolean = false,
    val progress: Float = 0f,
)

/** Screen-space circle published by the renderer so the View can hit-test taps. */
data class HitTarget(
    val key: String,
    val cx: Float,
    val cy: Float,
    val radius: Float,
)

/**
 * Immutable snapshot handed from the main thread to the GL thread.
 * Contains no ViewModel / PlaybackCenter references and no mutable objects other than Bitmaps
 * (which are uploaded once and kept alive for the lifetime of the snapshot).
 */
data class RenderModel(
    val mode: SceneMode = SceneMode.SHELF,
    val records: List<RecordItem> = emptyList(),
    val covers: Map<String, Bitmap> = emptyMap(),
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    /** 0..1 playback progress; drives the stylus position. */
    val progress01: Float = 0f,
    val speed: Float = 1f,
    /** Increment whenever a play / chapter-change effect should burst. */
    val burstToken: Int = 0,
    /** Disc currently under the finger, drawn with a brighter rim. */
    val pressedKey: String? = null,
    /** Idle rotation angle of the shelf, preserved across model updates. */
    val shelfYaw: Float = 0f,
)
