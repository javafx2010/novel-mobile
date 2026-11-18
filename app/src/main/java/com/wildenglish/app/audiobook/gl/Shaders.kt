package com.wildenglish.app.audiobook.gl

import android.opengl.GLES20
import kotlin.math.min

/** Tiny GLSL program wrapper: compiles/links once and caches locations by name. */
class GlProgram(vertexSource: String, fragmentSource: String) {
    val programId: Int = create(vertexSource, fragmentSource)
    private val locations = HashMap<String, Int>()

    fun attrib(name: String): Int = locations.getOrPut(name) {
        GLES20.glGetAttribLocation(programId, name)
    }

    fun uniform(name: String): Int = locations.getOrPut(name) {
        GLES20.glGetUniformLocation(programId, name)
    }

    fun use() = GLES20.glUseProgram(programId)

    fun delete() = GLES20.glDeleteProgram(programId)

    private fun create(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, v)
        GLES20.glAttachShader(id, f)
        GLES20.glLinkProgram(id)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, linked, 0)
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        if (linked[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(id)
            GLES20.glDeleteProgram(id)
            throw RuntimeException("GL program link failed: $log")
        }
        return id
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("GL shader compile failed: ${log?.take(min(400, log.length))}")
        }
        return shader
    }
}

object Shaders {

    const val QUAD_VERTEX = """
        attribute vec2 aPos;
        uniform mat4 uMVP;
        uniform mat4 uModel;
        varying vec2 vUV;
        void main() {
            vUV = aPos * 0.5 + 0.5;
            gl_Position = uMVP * uModel * vec4(aPos, 0.0, 1.0);
        }
    """

    /** Flat / gradient quad: backdrop, shelf boards, plinth, tonearm, glow. */
    const val QUAD_FRAGMENT = """
        precision mediump float;
        varying vec2 vUV;
        uniform vec4 uColor;
        uniform vec4 uColor2;
        uniform float uGradient;
        uniform int uTextured;
        uniform sampler2D uTex;
        void main() {
            vec4 c = mix(uColor, uColor2, clamp(vUV.y * uGradient, 0.0, 1.0));
            if (uTextured == 1) c *= texture2D(uTex, vUV);
            gl_FragColor = c;
        }
    """

    /** Record disc: procedural grooves, brass rim, moving sheen, cover label. */
    const val DISC_FRAGMENT = """
        precision mediump float;
        varying vec2 vUV;
        uniform sampler2D uCover;
        uniform float uSpin;
        uniform float uHighlight;
        uniform float uTime;
        uniform float uBuffering;
        void main() {
            // vUV is 0..1; convert to a centred -1..1 coordinate so the disc is a
            // full circle instead of a quarter wedge in the lower-left corner.
            vec2 p = vUV * 2.0 - 1.0;
            float r = length(p);
            if (r > 1.0) discard;
            float ang = atan(p.y, p.x) + uSpin;

            float grooves = 0.5 + 0.5 * sin(r * 110.0);
            vec3 col = mix(vec3(0.035, 0.028, 0.022), vec3(0.105, 0.085, 0.065), grooves * 0.35);

            float sheen = pow(max(0.0, cos(ang - 0.9)), 10.0) * 0.22;
            col += vec3(sheen, sheen * 0.88, sheen * 0.55);

            if (r > 0.94) {
                float rim = (r - 0.94) / 0.06;
                col = mix(col, vec3(0.86, 0.68, 0.38), rim * 0.9);
            }
            if (uHighlight > 0.0 && r > 0.83 && r < 0.90) {
                col += vec3(0.95, 0.76, 0.44) * uHighlight * 0.55;
            }

            if (r < 0.42) {
                vec2 luv = p / 0.42 * 0.5 + 0.5;
                vec3 cover = texture2D(uCover, luv).rgb;
                col = cover * (0.92 + 0.08 * sin(uTime * 1.4));
                if (r > 0.395) col = vec3(0.80, 0.63, 0.36);
                if (r < 0.045) col = vec3(0.02, 0.015, 0.010);
            }

            if (uBuffering > 0.0) {
                col *= 0.72 + 0.28 * (0.5 + 0.5 * sin(uTime * 5.0));
            }
            gl_FragColor = vec4(col, 1.0);
        }
    """

    /** Soft radial glow; avoids drawing a hard-edged square behind the record. */
    const val GLOW_FRAGMENT = """
        precision mediump float;
        varying vec2 vUV;
        uniform vec4 uColor;
        void main() {
            vec2 p = vUV * 2.0 - 1.0;
            float r = length(p);
            if (r > 1.0) discard;
            float a = pow(1.0 - r, 2.0);
            gl_FragColor = vec4(uColor.rgb, uColor.a * a);
        }
    """

    const val POINT_VERTEX = """
        attribute vec3 aPos;
        attribute float aSize;
        uniform mat4 uMVP;
        void main() {
            gl_Position = uMVP * vec4(aPos, 1.0);
            gl_PointSize = aSize;
        }
    """

    const val POINT_FRAGMENT = """
        precision mediump float;
        uniform vec4 uColor;
        void main() {
            float d = length(gl_PointCoord - vec2(0.5));
            float a = smoothstep(0.5, 0.05, d);
            gl_FragColor = vec4(uColor.rgb, uColor.a * a);
        }
    """
}
