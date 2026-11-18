package com.wildenglish.app.audiobook.gl

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * GLSurfaceView with game-like gesture handling: press → glow, drag → rotate the universe
 * with inertia, long-press → delete, tap → select/play. All listener callbacks are posted
 * to the main thread before touching the ViewModel (MediaPlayer requires it).
 */
class RecordUniverseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    val renderer = RecordUniverseRenderer(context)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastMoveTime = 0L
    private var velocityX = 0f
    private var velocityY = 0f
    private var dragging = false
    private var longPressFired = false
    private var pressedKey: String? = null

    private val longPressRunnable = Runnable {
        if (!dragging && !longPressFired) {
            longPressFired = true
            pressedKey?.let { key -> renderer.onLongPress?.invoke(key) }
        }
    }

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(renderer)
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                lastX = event.x; lastY = event.y
                lastMoveTime = event.eventTime
                velocityX = 0f; velocityY = 0f
                dragging = false
                longPressFired = false
                pressedKey = hitAt(event.x, event.y)
                queueEvent { renderer.setPressed(pressedKey) }
                mainHandler.removeCallbacks(longPressRunnable)
                mainHandler.postDelayed(longPressRunnable, longPressTimeout)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(event.x - downX, event.y - downY) > touchSlop) {
                    dragging = true
                    mainHandler.removeCallbacks(longPressRunnable)
                    queueEvent { renderer.setPressed(null) }
                }
                if (dragging) {
                    val now = event.eventTime
                    val dt = ((now - lastMoveTime).coerceAtLeast(1L)).toFloat() / 1000f
                    velocityX = (event.x - lastX) / dt
                    velocityY = (event.y - lastY) / dt
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    queueEvent { renderer.onDrag(dx, dy) }
                    lastMoveTime = now
                    lastX = event.x; lastY = event.y
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(longPressRunnable)
                if (dragging) {
                    val vx = velocityX.coerceIn(-1600f, 1600f)
                    val vy = velocityY.coerceIn(-1600f, 1600f)
                    queueEvent { renderer.onDragEnd(vx, vy) }
                } else if (!longPressFired) {
                    val key = pressedKey ?: hitAt(event.x, event.y)
                    if (key != null) {
                        // Marshal to main: AudioFilePlayer/MediaPlayer must run there.
                        mainHandler.post { renderer.onTap?.invoke(key) }
                    }
                }
                queueEvent { renderer.setPressed(null) }
                pressedKey = null
                dragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressRunnable)
                queueEvent { renderer.setPressed(null) }
                pressedKey = null
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitAt(x: Float, y: Float): String? =
        renderer.hitTargets()
            .filter { hypot(it.cx - x, it.cy - y) <= it.radius }
            .minByOrNull { hypot(it.cx - x, it.cy - y) }
            ?.key
}
