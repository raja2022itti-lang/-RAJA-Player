package com.raja.player

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Receives decoded frames from ExoPlayer through a SurfaceTexture and draws them with a
 * GLSL shader. The shader does: optional noise reduction (blur mix), edge sharpening,
 * brightness, contrast, saturation, and aspect-ratio scaling.
 */
class VideoRenderer(
    private val onSurface: (Surface) -> Unit,
    private val onOverload: () -> Unit,
    private val onRequestFrame: () -> Unit
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    @Volatile var brightness = 0f
    @Volatile var contrast = 1f
    @Volatile var saturation = 1f
    @Volatile var sharpness = 0f
    @Volatile var enhanced = false
    @Volatile var aspect = Aspect.FIT
    @Volatile var videoW = 0
    @Volatile var videoH = 0

    private var program = 0
    private var texId = 0
    private var surfaceTexture: SurfaceTexture? = null
    @Volatile var viewW = 1
    @Volatile var viewH = 1

    private var aPos = 0
    private var aTex = 0
    private var uTexMatrix = 0
    private var uScale = 0
    private var uTexel = 0
    private var uDenoise = 0
    private var uSharp = 0
    private var uBright = 0
    private var uContrast = 0
    private var uSat = 0

    private val texMatrix = FloatArray(16)

    // x, y, u, v for each of the 4 triangle-strip corners
    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
            position(0)
        }

    // Frame timing, measured on the GL thread (CPU-side draw time)
    private var frameNs = 0L
    private var frameCount = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        surfaceTexture?.release()
        surfaceTexture = null

        program = buildProgram()
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uScale = GLES20.glGetUniformLocation(program, "uScale")
        uTexel = GLES20.glGetUniformLocation(program, "uTexel")
        uDenoise = GLES20.glGetUniformLocation(program, "uDenoise")
        uSharp = GLES20.glGetUniformLocation(program, "uSharp")
        uBright = GLES20.glGetUniformLocation(program, "uBright")
        uContrast = GLES20.glGetUniformLocation(program, "uContrast")
        uSat = GLES20.glGetUniformLocation(program, "uSat")

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(texId)
        st.setOnFrameAvailableListener(this)
        surfaceTexture = st
        onSurface(Surface(st))
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width.coerceAtLeast(1)
        viewH = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return
        val t0 = System.nanoTime()

        st.updateTexImage()
        st.getTransformMatrix(texMatrix)

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)

        val w = if (videoW > 0) videoW else 1920
        val h = if (videoH > 0) videoH else 1080
        val on = enhanced
        GLES20.glUniform2f(uTexel, 1f / w, 1f / h)
        GLES20.glUniform1f(uDenoise, if (on) 0.5f else 0f)
        GLES20.glUniform1f(uSharp, sharpness + if (on) 0.4f else 0f)
        GLES20.glUniform1f(uBright, brightness)
        GLES20.glUniform1f(uContrast, contrast)
        GLES20.glUniform1f(uSat, saturation)

        val scale = scaleForAspect()
        GLES20.glUniform2f(uScale, scale[0], scale[1])
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)

        vertices.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(aPos)
        vertices.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(aTex)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        frameNs += System.nanoTime() - t0
        frameCount++
        if (frameCount >= 90) {
            val avgMs = frameNs / frameCount / 1_000_000f
            if (on && avgMs > 28f) onOverload()
            frameNs = 0L
            frameCount = 0
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        onRequestFrame()
    }

    fun release() {
        surfaceTexture?.release()
        surfaceTexture = null
    }

    private fun scaleForAspect(): FloatArray {
        if (aspect == Aspect.STRETCH || videoW <= 0 || videoH <= 0) return floatArrayOf(1f, 1f)
        val va = videoW.toFloat() / videoH
        val wa = viewW.toFloat() / viewH
        val fit = aspect == Aspect.FIT
        return if (va > wa) {
            if (fit) floatArrayOf(1f, wa / va) else floatArrayOf(va / wa, 1f)
        } else {
            if (fit) floatArrayOf(va / wa, 1f) else floatArrayOf(1f, wa / va)
        }
    }

    private fun buildProgram(): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, v)
        GLES20.glAttachShader(prog, f)
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { "Program link failed: ${GLES20.glGetProgramInfoLog(prog)}" }
        return prog
    }

    private fun compile(type: Int, source: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, source)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw IllegalStateException("Shader compile failed: $log")
        }
        return s
    }

    companion object {
        private val VERTEX_SHADER = """
            attribute vec2 aPos;
            attribute vec2 aTex;
            uniform mat4 uTexMatrix;
            uniform vec2 uScale;
            varying vec2 vTex;
            void main() {
                gl_Position = vec4(aPos * uScale, 0.0, 1.0);
                vTex = (uTexMatrix * vec4(aTex, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES uTex;
            uniform vec2 uTexel;
            uniform float uDenoise;
            uniform float uSharp;
            uniform float uBright;
            uniform float uContrast;
            uniform float uSat;
            void main() {
                vec3 c = texture2D(uTex, vTex).rgb;
                vec3 n = texture2D(uTex, vTex + vec2(uTexel.x, 0.0)).rgb
                       + texture2D(uTex, vTex - vec2(uTexel.x, 0.0)).rgb
                       + texture2D(uTex, vTex + vec2(0.0, uTexel.y)).rgb
                       + texture2D(uTex, vTex - vec2(0.0, uTexel.y)).rgb;
                vec3 low = (c * 4.0 + n) / 8.0;
                vec3 col = mix(c, low, uDenoise);
                col = col + (c - low) * uSharp;
                col = col + uBright;
                col = (col - 0.5) * uContrast + 0.5;
                float lum = dot(col, vec3(0.299, 0.587, 0.114));
                col = mix(vec3(lum), col, uSat);
                gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
            }
        """.trimIndent()
    }
}
