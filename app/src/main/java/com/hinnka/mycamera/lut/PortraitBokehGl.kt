package com.hinnka.mycamera.lut

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Mask-driven portrait bokeh shared by preview and encoded video.
 * The expensive background blur runs at half resolution (quarter-resolution for 4K),
 * then a full-resolution composite uses the original subject mask to keep edges sharp.
 *
 * GL methods must run on the owning GL thread. setMask() only copies CPU data and is thread-safe.
 */
class PortraitBokehGl(private val tag: String) {
    private data class MaskUpdate(val width: Int, val height: Int, val data: ByteArray?)

    @Volatile private var pendingUpdate: MaskUpdate? = null
    @Volatile private var updateGeneration = 0L
    @Volatile var hasMask: Boolean = false
        private set

    private var appliedGeneration = -1L
    private var blurProgramId = 0
    private var compositeProgramId = 0
    private var maskTextureId = 0
    private var blurTextureId = 0
    private var blurFramebufferId = 0
    private var blurWidth = 0
    private var blurHeight = 0
    private var outputTextureId = 0
    private var outputFramebufferId = 0
    private var outputWidth = 0
    private var outputHeight = 0

    private val positions = directFloatBuffer(
        floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    )
    private val texCoords = directFloatBuffer(
        floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    )

    fun setMask(width: Int, height: Int, data: ByteArray?) {
        val valid = width > 0 && height > 0 && data != null && data.size == width * height
        pendingUpdate = if (valid) MaskUpdate(width, height, data!!.copyOf()) else MaskUpdate(0, 0, null)
        updateGeneration += 1L
        hasMask = valid
    }

    fun renderToTexture(sourceTextureId: Int, width: Int, height: Int, blur: Float = DEFAULT_BLUR): Int {
        applyPendingMask()
        if (sourceTextureId == 0 || maskTextureId == 0 || !ensurePrograms() ||
            !ensureTargets(width, height)
        ) return 0
        renderPasses(sourceTextureId, outputFramebufferId, width, height, blur)
        return outputTextureId
    }

    fun drawToFramebuffer(
        sourceTextureId: Int,
        targetFramebufferId: Int,
        width: Int,
        height: Int,
        blur: Float = DEFAULT_BLUR,
    ): Boolean {
        applyPendingMask()
        if (sourceTextureId == 0 || maskTextureId == 0 || !ensurePrograms() ||
            !ensureBlurTarget(width, height)
        ) return false
        renderPasses(sourceTextureId, targetFramebufferId, width, height, blur)
        // Do not fail recording because another renderer left an unrelated GL error pending.
        return true
    }

    fun onContextLost() {
        blurProgramId = 0
        compositeProgramId = 0
        maskTextureId = 0
        blurTextureId = 0
        blurFramebufferId = 0
        blurWidth = 0
        blurHeight = 0
        outputTextureId = 0
        outputFramebufferId = 0
        outputWidth = 0
        outputHeight = 0
        appliedGeneration = -1L
    }

    fun applyPendingUpdates() {
        applyPendingMask()
    }

    private fun renderPasses(
        sourceTextureId: Int,
        targetFramebufferId: Int,
        width: Int,
        height: Int,
        blur: Float,
    ) {
        val divisor = if (maxOf(width, height) > 1920) 4 else 2
        val targetBlurWidth = (width / divisor).coerceAtLeast(1)
        val targetBlurHeight = (height / divisor).coerceAtLeast(1)
        check(ensureBlurTarget(targetBlurWidth, targetBlurHeight)) {
            "Portrait bokeh blur target allocation failed"
        }

        // Pass 1: background-aware bokeh at reduced resolution.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, blurFramebufferId)
        GLES30.glViewport(0, 0, blurWidth, blurHeight)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(blurProgramId)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTextureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(blurProgramId, "uSource"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTextureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(blurProgramId, "uMask"), 1)
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(blurProgramId, "uAspect"),
            width.toFloat() / height.coerceAtLeast(1),
        )
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(blurProgramId, "uBlur"),
            blur.coerceIn(0f, 0.08f),
        )
        drawQuad(blurProgramId)

        // Pass 2: composite the original sharp frame with the reduced-resolution blur.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFramebufferId)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(compositeProgramId)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTextureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(compositeProgramId, "uSource"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blurTextureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(compositeProgramId, "uBlurred"), 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTextureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(compositeProgramId, "uMask"), 2)
        drawQuad(compositeProgramId)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    private fun drawQuad(programId: Int) {
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
    }

    private fun applyPendingMask() {
        val generation = updateGeneration
        if (generation == appliedGeneration) return
        val update = pendingUpdate
        if (update?.data == null) {
            if (maskTextureId != 0) GLES30.glDeleteTextures(1, intArrayOf(maskTextureId), 0)
            maskTextureId = 0
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

    private fun ensurePrograms(): Boolean {
        if (blurProgramId != 0 && compositeProgramId != 0) return true
        if (blurProgramId != 0) GLES30.glDeleteProgram(blurProgramId)
        if (compositeProgramId != 0) GLES30.glDeleteProgram(compositeProgramId)
        blurProgramId = linkProgram(VERTEX_SHADER, BLUR_FRAGMENT_SHADER)
        compositeProgramId = linkProgram(VERTEX_SHADER, COMPOSITE_FRAGMENT_SHADER)
        return blurProgramId != 0 && compositeProgramId != 0
    }

    private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        if (vertex == 0 || fragment == 0) return 0
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
            return 0
        }
        return program
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

    private fun ensureTargets(width: Int, height: Int): Boolean {
        if (!ensureBlurTarget(width, height)) return false
        if (outputTextureId != 0 && outputFramebufferId != 0 &&
            outputWidth == width && outputHeight == height
        ) return true
        releaseOutput()
        val target = createFramebuffer(width, height, "output")
        outputTextureId = target.first
        outputFramebufferId = target.second
        outputWidth = width
        outputHeight = height
        return true
    }

    private fun ensureBlurTarget(sourceWidth: Int, sourceHeight: Int): Boolean {
        if (sourceWidth <= 0 || sourceHeight <= 0) return false
        val divisor = if (maxOf(sourceWidth, sourceHeight) > 1920) 4 else 2
        val width = if (sourceWidth > 2 && sourceHeight > 2) (sourceWidth / divisor).coerceAtLeast(1) else sourceWidth
        val height = if (sourceWidth > 2 && sourceHeight > 2) (sourceHeight / divisor).coerceAtLeast(1) else sourceHeight
        if (blurTextureId != 0 && blurFramebufferId != 0 && blurWidth == width && blurHeight == height) {
            return true
        }
        releaseBlurTarget()
        val target = createFramebuffer(width, height, "blur")
        blurTextureId = target.first
        blurFramebufferId = target.second
        blurWidth = width
        blurHeight = height
        return true
    }

    private fun createFramebuffer(width: Int, height: Int, label: String): Pair<Int, Int> {
        val texture = IntArray(1)
        GLES30.glGenTextures(1, texture, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture[0])
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
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer[0])
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, texture[0], 0,
        )
        val complete = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) ==
            GLES30.GL_FRAMEBUFFER_COMPLETE
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        if (!complete || GLES30.glGetError() != GLES30.GL_NO_ERROR) {
            GLES30.glDeleteFramebuffers(1, framebuffer, 0)
            GLES30.glDeleteTextures(1, texture, 0)
            error("Portrait $label framebuffer is incomplete")
        }
        return texture[0] to framebuffer[0]
    }

    private fun releaseBlurTarget() {
        if (blurFramebufferId != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(blurFramebufferId), 0)
        if (blurTextureId != 0) GLES30.glDeleteTextures(1, intArrayOf(blurTextureId), 0)
        blurFramebufferId = 0
        blurTextureId = 0
        blurWidth = 0
        blurHeight = 0
    }

    private fun releaseOutput() {
        if (outputFramebufferId != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(outputFramebufferId), 0)
        if (outputTextureId != 0) GLES30.glDeleteTextures(1, intArrayOf(outputTextureId), 0)
        outputFramebufferId = 0
        outputTextureId = 0
        outputWidth = 0
        outputHeight = 0
    }

    fun release() {
        if (maskTextureId != 0) GLES30.glDeleteTextures(1, intArrayOf(maskTextureId), 0)
        if (blurProgramId != 0) GLES30.glDeleteProgram(blurProgramId)
        if (compositeProgramId != 0) GLES30.glDeleteProgram(compositeProgramId)
        maskTextureId = 0
        blurProgramId = 0
        compositeProgramId = 0
        releaseBlurTarget()
        releaseOutput()
        pendingUpdate = null
        hasMask = false
        appliedGeneration = updateGeneration
    }

    private fun directFloatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(values)
            .apply { position(0) }

    private companion object {
        const val DEFAULT_BLUR = 0.042f
        const val BOKEH_SAMPLES = 20

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

        val BLUR_FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;
            in vec2 vUv;
            uniform sampler2D uSource;
            uniform sampler2D uMask;
            uniform float uAspect;
            uniform float uBlur;
            out vec4 fragColor;

            float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
            float subjectAt(vec2 uv) {
                return smoothstep(0.32, 0.68, texture(uMask, clamp(uv, vec2(0.001), vec2(0.999))).r);
            }
            void main() {
                vec2 uv = vUv;
                float angleOffset = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) *
                    43758.5453) * 6.2831853;
                vec3 accumulation = vec3(0.0);
                float weightSum = 0.0;
                for (int i = 0; i < 20; i++) {
                    float t = float(i) * 2.3999632 + angleOffset;
                    float r = sqrt((float(i) + 0.5) / 20.0);
                    vec2 offset = vec2(cos(t), sin(t)) * r * uBlur * vec2(1.0 / uAspect, 1.0);
                    vec2 sampleUv = clamp(uv + offset, vec2(0.001), vec2(0.999));
                    vec3 sampleColor = texture(uSource, sampleUv).rgb;
                    float weight = 1.0 - 0.97 * subjectAt(sampleUv);
                    weight *= 1.0 + 10.0 * smoothstep(0.45, 1.0, luma(sampleColor));
                    accumulation += sampleColor * weight;
                    weightSum += weight;
                }
                fragColor = vec4(accumulation / max(weightSum, 0.0001), 1.0);
            }
        """.trimIndent()

        val COMPOSITE_FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;
            in vec2 vUv;
            uniform sampler2D uSource;
            uniform sampler2D uBlurred;
            uniform sampler2D uMask;
            out vec4 fragColor;
            void main() {
                float subject = smoothstep(0.32, 0.68, texture(uMask, vUv).r);
                float depth = mix(0.65, 1.0, smoothstep(0.98, 0.35, vUv.y));
                vec3 sharp = texture(uSource, vUv).rgb;
                vec3 blurred = texture(uBlurred, vUv).rgb;
                vec3 color = mix(sharp, blurred, (1.0 - subject) * depth);
                fragColor = vec4(color, 1.0);
            }
        """.trimIndent()
    }
}
