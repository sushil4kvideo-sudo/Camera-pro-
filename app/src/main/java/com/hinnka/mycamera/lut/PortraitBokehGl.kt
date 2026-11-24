package com.hinnka.mycamera.lut

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Mask-driven, background-only bokeh pass shared by live preview and the video encoder.
 * All GL calls (except setMask, which only copies CPU data) must run on the owning GL thread.
 */
class PortraitBokehGl(private val tag: String) {
    private data class MaskUpdate(val width: Int, val height: Int, val data: ByteArray?)

    @Volatile private var pendingUpdate: MaskUpdate? = null
    @Volatile private var updateGeneration = 0L
    @Volatile var hasMask: Boolean = false
        private set

    private var appliedGeneration = -1L
    private var programId = 0
    private var maskTextureId = 0
    private var outputTextureId = 0
    private var outputFramebufferId = 0
    private var outputWidth = 0
    private var outputHeight = 0

    private val positions: FloatBuffer = directFloatBuffer(
        floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    )
    private val texCoords: FloatBuffer = directFloatBuffer(
        floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    )

    /** Thread-safe: queues only the latest mask; texture upload is deferred to the GL thread. */
    fun setMask(width: Int, height: Int, data: ByteArray?) {
        val valid = width > 0 && height > 0 && data != null && data.size == width * height
        pendingUpdate = if (valid) MaskUpdate(width, height, data!!.copyOf()) else MaskUpdate(0, 0, null)
        updateGeneration += 1L
        hasMask = valid
    }

    /** Render the processed camera texture into this helper's output FBO. Returns 0 on failure. */
    fun renderToTexture(sourceTextureId: Int, width: Int, height: Int, blur: Float = DEFAULT_BLUR): Int {
        applyPendingMask()
        if (sourceTextureId == 0 || maskTextureId == 0 || !ensureProgram() ||
            !ensureOutput(width, height)
        ) return 0
        draw(sourceTextureId, outputFramebufferId, width, height, blur)
        return outputTextureId
    }

    /** Recreate GL names after GLSurfaceView loses its EGL context; CPU mask is retained. */
    fun onContextLost() {
        programId = 0
        maskTextureId = 0
        outputTextureId = 0
        outputFramebufferId = 0
        outputWidth = 0
        outputHeight = 0
        appliedGeneration = -1L
    }

    /** Apply queued mask bytes/deletion while the owning GL context is current. */
    fun applyPendingUpdates() {
        applyPendingMask()
    }

    /** Render directly into the currently bound/default target framebuffer. */
    fun drawToFramebuffer(
        sourceTextureId: Int,
        targetFramebufferId: Int,
        width: Int,
        height: Int,
        blur: Float = DEFAULT_BLUR,
    ): Boolean {
        applyPendingMask()
        if (sourceTextureId == 0 || maskTextureId == 0 || !ensureProgram()) return false
        draw(sourceTextureId, targetFramebufferId, width, height, blur)
        return true
    }

    private fun draw(
        sourceTextureId: Int,
        targetFramebufferId: Int,
        width: Int,
        height: Int,
        blur: Float,
    ) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFramebufferId)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(programId)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTextureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uSource"), 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTextureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uMask"), 1)

        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(programId, "uTexel"),
            1f / width.coerceAtLeast(1),
            1f / height.coerceAtLeast(1),
        )
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(programId, "uAspect"),
            width.toFloat() / height.coerceAtLeast(1),
        )
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(programId, "uBlur"),
            blur.coerceIn(0f, 0.08f),
        )

        val positionLocation = GLES30.glGetAttribLocation(programId, "aPosition")
        val textureLocation = GLES30.glGetAttribLocation(programId, "aTexCoord")
        positions.position(0)
        texCoords.position(0)
        GLES30.glEnableVertexAttribArray(positionLocation)
        GLES30.glVertexAttribPointer(positionLocation, 2, GLES30.GL_FLOAT, false, 0, positions)
        GLES30.glEnableVertexAttribArray(textureLocation)
        GLES30.glVertexAttribPointer(textureLocation, 2, GLES30.GL_FLOAT, false, 0, texCoords)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(positionLocation)
        GLES30.glDisableVertexAttribArray(textureLocation)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    private fun applyPendingMask() {
        val generation = updateGeneration
        if (generation == appliedGeneration) return
        val update = pendingUpdate
        if (update?.data == null) {
            if (maskTextureId != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(maskTextureId), 0)
                maskTextureId = 0
            }
            appliedGeneration = generation
            return
        }

        if (maskTextureId == 0) {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            maskTextureId = ids[0]
        }
        if (maskTextureId == 0) return
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTextureId)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, update.width, update.height, 0,
            GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, ByteBuffer.wrap(update.data),
        )
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        appliedGeneration = generation
    }

    private fun ensureProgram(): Boolean {
        if (programId != 0) return true
        val vertex = compileShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        if (vertex == 0 || fragment == 0) return false
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertex)
        GLES30.glAttachShader(program, fragment)
        GLES30.glLinkProgram(program)
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            GLES30.glDeleteProgram(program)
            return false
        }
        programId = program
        return true
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun ensureOutput(width: Int, height: Int): Boolean {
        if (outputTextureId != 0 && outputFramebufferId != 0 &&
            outputWidth == width && outputHeight == height
        ) return true
        releaseOutput()
        if (width <= 0 || height <= 0) return false

        val texture = IntArray(1)
        GLES30.glGenTextures(1, texture, 0)
        outputTextureId = texture[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, outputTextureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, width, height, 0,
            GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null,
        )

        val framebuffer = IntArray(1)
        GLES30.glGenFramebuffers(1, framebuffer, 0)
        outputFramebufferId = framebuffer[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFramebufferId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, outputTextureId, 0,
        )
        val complete = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) ==
            GLES30.GL_FRAMEBUFFER_COMPLETE
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        if (!complete || GLES30.glGetError() != GLES30.GL_NO_ERROR) {
            releaseOutput()
            return false
        }
        outputWidth = width
        outputHeight = height
        return true
    }

    fun release() {
        if (maskTextureId != 0) GLES30.glDeleteTextures(1, intArrayOf(maskTextureId), 0)
        maskTextureId = 0
        if (programId != 0) GLES30.glDeleteProgram(programId)
        programId = 0
        releaseOutput()
        pendingUpdate = null
        hasMask = false
        appliedGeneration = updateGeneration
    }

    private fun releaseOutput() {
        if (outputFramebufferId != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(outputFramebufferId), 0)
            outputFramebufferId = 0
        }
        if (outputTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(outputTextureId), 0)
            outputTextureId = 0
        }
        outputWidth = 0
        outputHeight = 0
    }

    private fun directFloatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(values)
            .apply { position(0) }

    private companion object {
        const val DEFAULT_BLUR = 0.042f

        val VERTEX_SHADER = """
            #version 300 es
            layout(location = 0) in vec2 aPosition;
            layout(location = 1) in vec2 aTexCoord;
            out vec2 vUv;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vUv = aTexCoord;
            }
        """.trimIndent()

        val FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;
            in vec2 vUv;
            uniform sampler2D uSource;
            uniform sampler2D uMask;
            uniform vec2 uTexel;
            uniform float uAspect;
            uniform float uBlur;
            out vec4 fragColor;

            float luma(vec3 c) {
                return dot(c, vec3(0.2126, 0.7152, 0.0722));
            }
            float subjectAt(vec2 uv) {
                return smoothstep(0.32, 0.68, texture(uMask, clamp(uv, vec2(0.001), vec2(0.999))).r);
            }
            vec3 bokeh(vec2 uv, float radius) {
                float angleOffset = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) *
                    43758.5453) * 6.2831853;
                vec3 accumulation = vec3(0.0);
                float weightSum = 0.0;
                for (int i = 0; i < 40; i++) {
                    float t = float(i) * 2.3999632 + angleOffset;
                    float r = sqrt((float(i) + 0.5) / 40.0);
                    vec2 offset = vec2(cos(t), sin(t)) * r * radius * vec2(1.0 / uAspect, 1.0);
                    vec2 sampleUv = clamp(uv + offset, vec2(0.001), vec2(0.999));
                    vec3 sampleColor = texture(uSource, sampleUv).rgb;
                    float weight = 1.0 - 0.97 * subjectAt(sampleUv);
                    weight *= 1.0 + 10.0 * smoothstep(0.45, 1.0, luma(sampleColor));
                    accumulation += sampleColor * weight;
                    weightSum += weight;
                }
                return accumulation / max(weightSum, 0.0001);
            }
            void main() {
                float subject = subjectAt(vUv);
                float depth = mix(0.65, 1.0, smoothstep(0.98, 0.35, vUv.y));
                float amount = (1.0 - subject) * depth;
                vec3 sharp = texture(uSource, vUv).rgb;
                vec3 color = amount > 0.02
                    ? mix(bokeh(vUv, uBlur * amount), sharp, subject)
                    : sharp;
                fragColor = vec4(color, 1.0);
            }
        """.trimIndent()
    }
}
