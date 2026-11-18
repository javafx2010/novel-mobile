package com.wildenglish.app.audiobook.gl

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils

/**
 * GL-thread-only texture cache. Keys are CoverThumbCache keys (path + lastModified),
 * so a re-imported cover naturally gets a new texture and the old one is deleted.
 */
class TextureStore {

    private val textures = HashMap<String, Int>()

    fun ensure(key: String, bitmap: Bitmap): Int {
        textures[key]?.let { return it }
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        textures[key] = ids[0]
        return ids[0]
    }

    fun get(key: String): Int? = textures[key]

    /** Deletes textures whose key no longer appears in the latest [keep] set. */
    fun prune(keep: Set<String>) {
        val stale = textures.keys.filter { it !in keep }
        if (stale.isEmpty()) return
        val ids = IntArray(stale.size)
        stale.forEachIndexed { i, key -> ids[i] = textures.remove(key) ?: 0 }
        GLES20.glDeleteTextures(ids.size, ids, 0)
    }

    fun release() {
        if (textures.isEmpty()) return
        val ids = IntArray(textures.size) { i -> textures.values.elementAt(i) }
        GLES20.glDeleteTextures(ids.size, ids, 0)
        textures.clear()
    }
}
