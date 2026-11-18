package com.wildenglish.app.audiobook.gl

import kotlin.math.tan

/** Minimal column-major 4x4 matrix helpers for OpenGL ES. */
object SceneMath {

    fun identity(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    fun perspective(fovDeg: Float, aspect: Float, near: Float, far: Float): FloatArray {
        val f = 1f / tan(Math.toRadians(fovDeg.toDouble()).toFloat() / 2f)
        val nf = 1f / (near - far)
        return floatArrayOf(
            f / aspect, 0f, 0f, 0f,
            0f, f, 0f, 0f,
            0f, 0f, (far + near) * nf, -1f,
            0f, 0f, 2f * far * near * nf, 0f,
        )
    }

    fun lookAt(eye: FloatArray, center: FloatArray, up: FloatArray): FloatArray {
        var zx = eye[0] - center[0]
        var zy = eye[1] - center[1]
        var zz = eye[2] - center[2]
        var len = kotlin.math.sqrt(zx * zx + zy * zy + zz * zz)
        if (len == 0f) len = 1f
        zx /= len; zy /= len; zz /= len

        var xx = up[1] * zz - up[2] * zy
        var xy = up[2] * zx - up[0] * zz
        var xz = up[0] * zy - up[1] * zx
        len = kotlin.math.sqrt(xx * xx + xy * xy + xz * xz)
        if (len == 0f) {
            xx = 1f; xy = 0f; xz = 0f
        } else {
            xx /= len; xy /= len; xz /= len
        }

        val yx = zy * xz - zz * xy
        val yy = zz * xx - zx * xz
        val yz = zx * xy - zy * xx

        return floatArrayOf(
            xx, yx, zx, 0f,
            xy, yy, zy, 0f,
            xz, yz, zz, 0f,
            -(xx * eye[0] + xy * eye[1] + xz * eye[2]),
            -(yx * eye[0] + yy * eye[1] + yz * eye[2]),
            -(zx * eye[0] + zy * eye[1] + zz * eye[2]),
            1f,
        )
    }

    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (c in 0..3) {
            for (r in 0..3) {
                var sum = 0f
                for (k in 0..3) sum += a[k * 4 + r] * b[c * 4 + k]
                out[c * 4 + r] = sum
            }
        }
        return out
    }

    fun translation(x: Float, y: Float, z: Float): FloatArray {
        val m = identity()
        m[12] = x; m[13] = y; m[14] = z
        return m
    }

    fun scale(x: Float, y: Float, z: Float): FloatArray {
        val m = identity()
        m[0] = x; m[5] = y; m[10] = z
        return m
    }

    fun rotateY(a: Float): FloatArray {
        val c = kotlin.math.cos(a);
        val s = kotlin.math.sin(a)
        return floatArrayOf(
            c, 0f, -s, 0f,
            0f, 1f, 0f, 0f,
            s, 0f, c, 0f,
            0f, 0f, 0f, 1f,
        )
    }

    fun rotateX(a: Float): FloatArray {
        val c = kotlin.math.cos(a);
        val s = kotlin.math.sin(a)
        return floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, c, s, 0f,
            0f, -s, c, 0f,
            0f, 0f, 0f, 1f,
        )
    }

    fun rotateZ(a: Float): FloatArray {
        val c = kotlin.math.cos(a);
        val s = kotlin.math.sin(a)
        return floatArrayOf(
            c, s, 0f, 0f,
            -s, c, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
    }

    fun compose(vararg matrices: FloatArray): FloatArray {
        var result = identity()
        for (m in matrices) result = multiply(result, m)
        return result
    }

    /** Projects a world-space point with [mvp]; returns pixel x/y or null when behind the camera. */
    fun project(
        mvp: FloatArray,
        x: Float,
        y: Float,
        z: Float,
        width: Int,
        height: Int
    ): FloatArray? {
        val ox = mvp[0] * x + mvp[4] * y + mvp[8] * z + mvp[12]
        val oy = mvp[1] * x + mvp[5] * y + mvp[9] * z + mvp[13]
        val ow = mvp[3] * x + mvp[7] * y + mvp[11] * z + mvp[15]
        if (ow <= 0f) return null
        val nx = ox / ow
        val ny = oy / ow
        return floatArrayOf((nx * 0.5f + 0.5f) * width, (0.5f - ny * 0.5f) * height)
    }
}
