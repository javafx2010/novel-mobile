package com.wildenglish.app.audiobook.gl

import android.opengl.GLES20
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin

/** Fixed-size point-sprite particles, drawn with additive blending. GL-thread-only. */
class ParticleSystem(private val capacity: Int = 256) {

    private class P {
        var x = 0f;
        var y = 0f;
        var z = 0f
        var vx = 0f;
        var vy = 0f;
        var vz = 0f
        var life = 0f;
        var maxLife = 1f;
        var size = 4f
    }

    private val pool = Array(capacity) { P() }
    private var cursor = 0
    private val positionData = FloatArray(capacity * 3)
    private val sizeData = FloatArray(capacity)

    /** Spawns a radial burst; [color] is rgba. */
    fun spawnBurst(
        x: Float,
        y: Float,
        z: Float,
        count: Int,
        color: FloatArray,
        spread: Float = 1f
    ) {
        val n = minOf(count, capacity / 2)
        repeat(n) {
            val p = pool[cursor]
            cursor = (cursor + 1) % capacity
            val theta = Math.random().toFloat() * 2f * Math.PI.toFloat()
            val phi = (Math.random().toFloat() - 0.5f) * 0.8f
            val speed = (0.35f + Math.random().toFloat() * 1.2f) * spread
            p.x = x; p.y = y; p.z = z
            p.vx = cos(theta) * cos(phi) * speed
            p.vy = sin(theta) * cos(phi) * speed
            p.vz = sin(phi) * speed * 0.5f
            p.maxLife = 0.55f + Math.random().toFloat() * 0.75f
            p.life = p.maxLife
            p.size = 6f + Math.random().toFloat() * 12f
        }
    }

    /** Gentle ambient emission while playing. */
    fun spawnAmbient(x: Float, y: Float, z: Float, color: FloatArray) {
        val p = pool[cursor]
        cursor = (cursor + 1) % capacity
        p.x = x + (Math.random().toFloat() - 0.5f) * 0.6f
        p.y = y + (Math.random().toFloat() - 0.5f) * 0.6f
        p.z = z
        p.vx = (Math.random().toFloat() - 0.5f) * 0.25f
        p.vy = 0.25f + Math.random().toFloat() * 0.5f
        p.vz = 0f
        p.maxLife = 0.9f + Math.random().toFloat() * 0.8f
        p.life = p.maxLife
        p.size = 4f + Math.random().toFloat() * 7f
    }

    fun update(dt: Float) {
        for (p in pool) {
            if (p.life <= 0f) continue
            p.life -= dt
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.z += p.vz * dt
            p.vy -= 0.25f * dt
            p.vx *= 0.985f
            p.vz *= 0.985f
        }
    }

    /** Compacts live particles into buffers and draws them. */
    fun draw(program: GlProgram, mvp: FloatArray, posBuffer: FloatBuffer, sizeBuffer: FloatBuffer) {
        var n = 0
        for (p in pool) {
            if (p.life <= 0f) continue
            positionData[n * 3] = p.x
            positionData[n * 3 + 1] = p.y
            positionData[n * 3 + 2] = p.z
            val t = (p.life / p.maxLife).coerceIn(0f, 1f)
            sizeData[n] = p.size * (0.35f + 0.65f * t)
            n++
        }
        if (n == 0) return

        posBuffer.clear()
        posBuffer.put(positionData, 0, n * 3)
        posBuffer.position(0)
        sizeBuffer.clear()
        sizeBuffer.put(sizeData, 0, n)
        sizeBuffer.position(0)

        val posLoc = program.attrib("aPos")
        val sizeLoc = program.attrib("aSize")
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 0, posBuffer)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(sizeLoc, 1, GLES20.GL_FLOAT, false, 0, sizeBuffer)
        GLES20.glEnableVertexAttribArray(sizeLoc)

        GLES20.glUniformMatrix4fv(program.uniform("uMVP"), 1, false, mvp, 0)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, n)

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(sizeLoc)
    }

    fun clear() {
        for (p in pool) p.life = 0f
    }

    /** Exposed so the renderer can pass a color whose alpha fades with overall life. */
    fun aliveCount(): Int = pool.count { it.life > 0f }
}
