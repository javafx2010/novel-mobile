package com.wildenglish.app.audiobook.gl

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Small GPU buffer helpers. All buffers are immutable once uploaded in onSurfaceCreated. */
object SceneObjects {

    /** Unit quad as a triangle strip: (-1,-1) .. (1,1). */
    val QUAD = floatArrayOf(
        -1f, -1f,
        1f, -1f,
        -1f, 1f,
        1f, 1f,
    )

    fun floatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(data)
                position(0)
            }

    fun stars(count: Int = 220): FloatArray {
        val out = FloatArray(count * 4) // x, y, z, size
        var seed = 1337L
        fun next(): Float {
            seed = (seed * 1103515245L + 12345L) and 0x7fffffffL
            return seed.toFloat() / 0x7fffffffL
        }
        for (i in 0 until count) {
            out[i * 4] = (next() - 0.5f) * 16f
            out[i * 4 + 1] = (next() - 0.5f) * 12f
            out[i * 4 + 2] = -3f - next() * 9f
            out[i * 4 + 3] = 1.5f + next() * 3.5f
        }
        return out
    }

    fun bindQuad(program: GlProgram, buffer: FloatBuffer) {
        buffer.position(0)
        GLES20.glVertexAttribPointer(program.attrib("aPos"), 2, GLES20.GL_FLOAT, false, 0, buffer)
        GLES20.glEnableVertexAttribArray(program.attrib("aPos"))
    }

    fun unbindQuad(program: GlProgram) {
        GLES20.glDisableVertexAttribArray(program.attrib("aPos"))
    }
}
