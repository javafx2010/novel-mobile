package com.wildenglish.app.audiobook.gl

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.wildenglish.app.R
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.min

/**
 * Draws the 3D record universe. The GL thread only reads the immutable [RenderModel]
 * snapshot; every user action is marshalled to the main thread by [RecordUniverseView].
 */
class RecordUniverseRenderer(private val context: Context) : GLSurfaceView.Renderer {

    private val modelRef = AtomicReference(RenderModel())
    private val hitsRef = AtomicReference<List<HitTarget>>(emptyList())

    /** Called on the main thread when a disc is tapped / long-pressed. */
    var onTap: ((String) -> Unit)? = null
    var onLongPress: ((String) -> Unit)? = null

    private lateinit var quadProgram: GlProgram
    private lateinit var discProgram: GlProgram
    private lateinit var glowProgram: GlProgram
    private lateinit var pointProgram: GlProgram

    private lateinit var quadBuffer: FloatBuffer
    private lateinit var starBuffer: FloatBuffer
    private lateinit var starSizeBuffer: FloatBuffer
    private lateinit var particlePosBuffer: FloatBuffer
    private lateinit var particleSizeBuffer: FloatBuffer

    private val textures = TextureStore()
    private val particles = ParticleSystem()

    private var width = 1
    private var height = 1
    private var lastFrame = 0L
    private var time = 0f
    private var spin = 0f
    private var ambientTimer = 0f

    private var yaw = 0f
    private var pitch = 0f
    private var velocityYaw = 0f
    private var velocityPitch = 0f
    private var dragging = false
    private var pressedKey: String? = null

    private var camEye = floatArrayOf(0f, 0f, 9f)
    private var camTarget = floatArrayOf(0f, 0f, 0f)
    private val up = floatArrayOf(0f, 1f, 0f)

    private var lastBurst = -1
    private var lastMode = SceneMode.SHELF
    private var defaultCoverKey = "default_cover"

    fun submitModel(model: RenderModel) = modelRef.set(model)

    fun hitTargets(): List<HitTarget> = hitsRef.get()

    fun setPressed(key: String?) {
        pressedKey = key
        requestFrame()
    }

    private fun requestFrame() {
        // The View sets RENDERMODE_CONTINUOUSLY for 3D screens, so this is only a hint
        // for the WHEN_DIRTY fallback.
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.027f, 0.022f, 0.018f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)

        quadProgram = GlProgram(Shaders.QUAD_VERTEX, Shaders.QUAD_FRAGMENT)
        discProgram = GlProgram(Shaders.QUAD_VERTEX, Shaders.DISC_FRAGMENT)
        glowProgram = GlProgram(Shaders.QUAD_VERTEX, Shaders.GLOW_FRAGMENT)
        pointProgram = GlProgram(Shaders.POINT_VERTEX, Shaders.POINT_FRAGMENT)

        quadBuffer = SceneObjects.floatBuffer(SceneObjects.QUAD)
        val stars = SceneObjects.stars()
        val starPos = FloatArray(stars.size / 4 * 3)
        val starSize = FloatArray(stars.size / 4)
        for (i in 0 until stars.size / 4) {
            starPos[i * 3] = stars[i * 4]
            starPos[i * 3 + 1] = stars[i * 4 + 1]
            starPos[i * 3 + 2] = stars[i * 4 + 2]
            starSize[i] = stars[i * 4 + 3]
        }
        starBuffer = SceneObjects.floatBuffer(starPos)
        starSizeBuffer = SceneObjects.floatBuffer(starSize)
        particlePosBuffer = SceneObjects.floatBuffer(FloatArray(ParticleSystem_CAPACITY * 3))
        particleSizeBuffer = SceneObjects.floatBuffer(FloatArray(ParticleSystem_CAPACITY))

        // Default cover texture is shared by every record without its own art.
        val defaultBitmap =
            BitmapFactory.decodeResource(context.resources, R.drawable.cover_default)
        if (defaultBitmap != null) textures.ensure(defaultCoverKey, defaultBitmap)
        lastBurst = -1
        lastFrame = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w.coerceAtLeast(1)
        height = h.coerceAtLeast(1)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1_000_000_000f).coerceIn(
            0f,
            0.05f
        )
        lastFrame = now
        time += dt

        val model = modelRef.get()
        updateCamera(model.mode, dt)
        updateMotion(dt)
        if (model.isPlaying) {
            spin += dt * 3.4f * model.speed.coerceIn(0.25f, 3f)
            ambientTimer += dt
            if (ambientTimer > 0.09f) {
                ambientTimer = 0f
                centerOfInterest(model)?.let {
                    particles.spawnAmbient(it[0], it[1], it[2], BrassParticle)
                }
            }
        }
        if (model.burstToken != lastBurst) {
            lastBurst = model.burstToken
            centerOfInterest(model)?.let {
                particles.spawnBurst(it[0], it[1], it[2], 90, BrassBurst, 1.4f)
            }
        }
        particles.update(dt)

        val aspect = width.toFloat() / height.toFloat()
        val projection = SceneMath.perspective(52f, aspect, 0.1f, 60f)
        val view = SceneMath.lookAt(camEye, camTarget, up)
        val vp = SceneMath.multiply(projection, view)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        drawBackdrop()
        drawStars(vp)
        drawScene(model, vp)
        drawParticles(vp)
    }

    // ---------------------------------------------------------------- camera / motion

    private fun updateCamera(mode: SceneMode, dt: Float) {
        val targetEye = when (mode) {
            SceneMode.SHELF -> floatArrayOf(0f, 0f, 9.2f)
            SceneMode.DETAIL -> floatArrayOf(0f, 0f, 8.2f)
            SceneMode.TURNTABLE -> floatArrayOf(0f, 2.4f, 7.8f)
        }
        val targetLook = when (mode) {
            SceneMode.TURNTABLE -> floatArrayOf(0f, 0f, 0f)
            else -> floatArrayOf(0f, 0f, 0f)
        }
        if (lastMode != mode) {
            lastMode = mode
            yaw = 0f
            pitch = 0f
            velocityYaw = 0f
            velocityPitch = 0f
        }
        val k = min(1f, dt * 5f)
        for (i in 0..2) {
            camEye[i] += (targetEye[i] - camEye[i]) * k
            camTarget[i] += (targetLook[i] - camTarget[i]) * k
        }
    }

    private fun updateMotion(dt: Float) {
        if (dragging) return
        yaw += velocityYaw * dt
        pitch = (pitch + velocityPitch * dt).coerceIn(-0.45f, 0.45f)
        val damping = Math.pow(0.06, dt.toDouble()).toFloat() // ~0.94 per 16ms frame
        velocityYaw *= damping
        velocityPitch *= damping
        if (kotlin.math.abs(velocityYaw) < 0.01f) velocityYaw = 0f
        if (kotlin.math.abs(velocityPitch) < 0.01f) velocityPitch = 0f
    }

    fun onDrag(dx: Float, dy: Float) {
        dragging = true
        yaw += dx * 0.006f
        pitch = (pitch + dy * 0.0035f).coerceIn(-0.45f, 0.45f)
        velocityYaw = dx * 0.9f
        velocityPitch = dy * 0.5f
    }

    fun onDragEnd(vx: Float, vy: Float) {
        dragging = false
        velocityYaw = vx * 0.9f
        velocityPitch = vy * 0.5f
    }

    // ---------------------------------------------------------------- drawing

    private fun drawBackdrop() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        quadProgram.use()
        SceneObjects.bindQuad(quadProgram, quadBuffer)
        GLES20.glUniformMatrix4fv(quadProgram.uniform("uMVP"), 1, false, SceneMath.identity(), 0)
        GLES20.glUniformMatrix4fv(quadProgram.uniform("uModel"), 1, false, SceneMath.identity(), 0)
        GLES20.glUniform4f(quadProgram.uniform("uColor"), 0.10f, 0.075f, 0.045f, 1f)
        GLES20.glUniform4f(quadProgram.uniform("uColor2"), 0.015f, 0.012f, 0.010f, 1f)
        GLES20.glUniform1f(quadProgram.uniform("uGradient"), 1f)
        GLES20.glUniform1i(quadProgram.uniform("uTextured"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        SceneObjects.unbindQuad(quadProgram)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun drawStars(vp: FloatArray) {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        pointProgram.use()
        val posLoc = pointProgram.attrib("aPos")
        val sizeLoc = pointProgram.attrib("aSize")
        starBuffer.position(0)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 0, starBuffer)
        GLES20.glEnableVertexAttribArray(posLoc)
        starSizeBuffer.position(0)
        GLES20.glVertexAttribPointer(sizeLoc, 1, GLES20.GL_FLOAT, false, 0, starSizeBuffer)
        GLES20.glEnableVertexAttribArray(sizeLoc)
        GLES20.glUniformMatrix4fv(pointProgram.uniform("uMVP"), 1, false, vp, 0)
        GLES20.glUniform4f(pointProgram.uniform("uColor"), 0.85f, 0.70f, 0.45f, 0.32f)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, starSizeBuffer.capacity())
        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(sizeLoc)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun drawScene(model: RenderModel, vp: FloatArray) {
        val group = SceneMath.compose(
            SceneMath.rotateY(yaw),
            SceneMath.rotateX(pitch),
        )
        val hits = ArrayList<HitTarget>()
        val mode = model.mode
        val records = model.records

        // Shelf boards give the universe a sense of depth.
        if (mode != SceneMode.TURNTABLE) {
            quadProgram.use()
            SceneObjects.bindQuad(quadProgram, quadBuffer)
            GLES20.glUniformMatrix4fv(quadProgram.uniform("uMVP"), 1, false, vp, 0)
            GLES20.glUniform1i(quadProgram.uniform("uTextured"), 0)
            GLES20.glUniform1f(quadProgram.uniform("uGradient"), 0f)
            GLES20.glUniform4f(quadProgram.uniform("uColor2"), 0f, 0f, 0f, 1f)
            for (row in -2..2) {
                val boardModel = SceneMath.compose(
                    group,
                    SceneMath.translation(0f, row * 1.12f - 0.62f, -0.35f),
                    SceneMath.scale(2.9f, 0.035f, 1f),
                )
                GLES20.glUniformMatrix4fv(quadProgram.uniform("uModel"), 1, false, boardModel, 0)
                GLES20.glUniform4f(quadProgram.uniform("uColor"), 0.42f, 0.31f, 0.16f, 0.55f)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }
            SceneObjects.unbindQuad(quadProgram)
        }

        // Upload every cover used by the current model; prune stale textures.
        model.covers.forEach { (key, bitmap) -> textures.ensure(key, bitmap) }
        textures.prune(model.covers.keys + defaultCoverKey)

        discProgram.use()
        SceneObjects.bindQuad(discProgram, quadBuffer)
        GLES20.glUniform1i(discProgram.uniform("uCover"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1f(discProgram.uniform("uTime"), time)
        GLES20.glUniform1f(discProgram.uniform("uBuffering"), if (model.isBuffering) 1f else 0f)

        val size = if (mode == SceneMode.TURNTABLE) 1.45f else 0.44f
        val columns = if (mode == SceneMode.TURNTABLE) 1 else 4
        val visible =
            if (mode == SceneMode.TURNTABLE) minOf(1, records.size) else minOf(24, records.size)

        for (i in 0 until visible) {
            val record = records[i]
            val col = i % columns
            val row = i / columns
            val x = if (mode == SceneMode.TURNTABLE) 0f else (col - (columns - 1) / 2f) * 1.18f
            val y = if (mode == SceneMode.TURNTABLE) 0.65f else 1.65f - row * 1.12f
            val z = if (mode == SceneMode.TURNTABLE) 0.2f else 0f

            val placement = SceneMath.compose(group, SceneMath.translation(x, y, z))
            val localSpin = spin + i * 0.37f
            val discModel = if (mode == SceneMode.TURNTABLE) {
                SceneMath.compose(
                    placement,
                    SceneMath.rotateX(-0.78f),
                    SceneMath.rotateZ(localSpin),
                    SceneMath.scale(size, size, 1f),
                )
            } else {
                SceneMath.compose(
                    placement,
                    SceneMath.rotateZ(localSpin * 0.18f),
                    SceneMath.scale(size, size, 1f),
                )
            }
            val mvp = SceneMath.multiply(vp, discModel)

            // Hit circle from the same placement (before scaling).
            val center = SceneMath.project(mvp, 0f, 0f, 0f, width, height)
            val edge = SceneMath.project(mvp, 1f, 0f, 0f, width, height)
            if (center != null && edge != null) {
                val radius = kotlin.math.abs(edge[0] - center[0])
                hits.add(HitTarget(record.key, center[0], center[1], radius * 1.12f))
            }

            val texture = textures.get(record.coverKey) ?: textures.get(defaultCoverKey) ?: 0
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glUniformMatrix4fv(discProgram.uniform("uMVP"), 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(
                discProgram.uniform("uModel"),
                1,
                false,
                SceneMath.identity(),
                0
            )
            GLES20.glUniform1f(discProgram.uniform("uSpin"), localSpin)
            val highlight = when {
                pressedKey == record.key -> 1.4f
                record.isCurrent -> 1f
                else -> 0f
            }
            GLES20.glUniform1f(discProgram.uniform("uHighlight"), highlight)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        SceneObjects.unbindQuad(discProgram)

        // Additive glow behind the current disc.
        if (records.isNotEmpty()) {
            drawGlow(vp, group, records, mode)
        }

        if (mode == SceneMode.TURNTABLE) drawTonearm(vp, model)

        hitsRef.set(hits)
    }

    private fun drawGlow(
        vp: FloatArray,
        group: FloatArray,
        records: List<RecordItem>,
        mode: SceneMode
    ) {
        val record = records.firstOrNull { it.isCurrent } ?: records[0]
        val i = records.indexOf(record).coerceAtLeast(0)
        val col = i % 4
        val row = i / 4
        val x = if (mode == SceneMode.TURNTABLE) 0f else (col - 1.5f) * 1.18f
        val y = if (mode == SceneMode.TURNTABLE) 0.65f else 1.65f - row * 1.12f
        val z = if (mode == SceneMode.TURNTABLE) 0.19f else -0.02f
        val s = if (mode == SceneMode.TURNTABLE) 1.75f else 0.58f
        val glowModel = SceneMath.compose(
            group,
            SceneMath.translation(x, y, z),
            if (mode == SceneMode.TURNTABLE) SceneMath.rotateX(-0.78f) else SceneMath.identity(),
            SceneMath.scale(s, s, 1f),
        )
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        GLES20.glDepthMask(false)
        glowProgram.use()
        SceneObjects.bindQuad(glowProgram, quadBuffer)
        GLES20.glUniformMatrix4fv(glowProgram.uniform("uMVP"), 1, false, vp, 0)
        GLES20.glUniformMatrix4fv(glowProgram.uniform("uModel"), 1, false, glowModel, 0)
        val pulse = 0.20f + 0.08f * kotlin.math.sin(time * 2.2f)
        GLES20.glUniform4f(glowProgram.uniform("uColor"), 0.90f, 0.70f, 0.35f, pulse)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        SceneObjects.unbindQuad(glowProgram)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun drawTonearm(vp: FloatArray, model: RenderModel) {
        val engaged = model.isPlaying
        val angle = if (engaged) -0.45f else -0.05f
        val armModel = SceneMath.compose(
            SceneMath.translation(1.55f, 1.55f, 0.55f),
            SceneMath.rotateZ(angle),
            SceneMath.translation(-1.05f, 0f, 0f),
            SceneMath.scale(1.15f, 0.06f, 1f),
        )
        quadProgram.use()
        SceneObjects.bindQuad(quadProgram, quadBuffer)
        GLES20.glUniformMatrix4fv(quadProgram.uniform("uMVP"), 1, false, vp, 0)
        GLES20.glUniformMatrix4fv(quadProgram.uniform("uModel"), 1, false, armModel, 0)
        GLES20.glUniform4f(quadProgram.uniform("uColor"), 0.92f, 0.76f, 0.46f, 1f)
        GLES20.glUniform4f(quadProgram.uniform("uColor2"), 0.55f, 0.40f, 0.20f, 1f)
        GLES20.glUniform1f(quadProgram.uniform("uGradient"), 0.6f)
        GLES20.glUniform1i(quadProgram.uniform("uTextured"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        val pivot = SceneMath.compose(
            SceneMath.translation(1.55f, 1.55f, 0.56f),
            SceneMath.scale(0.17f, 0.17f, 1f),
        )
        GLES20.glUniformMatrix4fv(quadProgram.uniform("uModel"), 1, false, pivot, 0)
        GLES20.glUniform4f(quadProgram.uniform("uColor"), 0.98f, 0.86f, 0.60f, 1f)
        GLES20.glUniform4f(quadProgram.uniform("uColor2"), 0.62f, 0.46f, 0.24f, 1f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        SceneObjects.unbindQuad(quadProgram)
    }

    private fun drawParticles(vp: FloatArray) {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        GLES20.glDepthMask(false)
        pointProgram.use()
        GLES20.glUniform4f(pointProgram.uniform("uColor"), 1f, 0.82f, 0.48f, 0.75f)
        particles.draw(pointProgram, vp, particlePosBuffer, particleSizeBuffer)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun centerOfInterest(model: RenderModel): FloatArray? {
        if (model.records.isEmpty()) return null
        val record = model.records.firstOrNull { it.isCurrent } ?: model.records[0]
        val i = model.records.indexOf(record).coerceAtLeast(0)
        val col = i % 4
        val row = i / 4
        if (model.mode == SceneMode.TURNTABLE) return floatArrayOf(0f, 0.65f, 0.4f)
        return floatArrayOf((col - 1.5f) * 1.18f, 1.65f - row * 1.12f, 0.1f)
    }

    fun release() {
        textures.release()
        particles.clear()
    }

    private companion object {
        const val ParticleSystem_CAPACITY = 256
        val BrassParticle = floatArrayOf(0.95f, 0.78f, 0.45f, 1f)
        val BrassBurst = floatArrayOf(1f, 0.86f, 0.55f, 1f)
    }
}
