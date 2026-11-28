package com.hinnka.mycamera.lut

import android.media.Image
import android.opengl.EGL14
import android.opengl.EGL15
import android.opengl.EGLSync
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLES31
import com.hinnka.mycamera.processor.GlesHardwareBufferImage
import com.hinnka.mycamera.stabilization.POSITION_LOCK_CROP_ZOOM
import com.hinnka.mycamera.stabilization.STABILIZATION_ROW_COUNT
import com.hinnka.mycamera.stabilization.StabilizationFrame
import com.hinnka.mycamera.utils.PLog

/**
 * MGC's production image path: Camera2 HardwareBuffer -> EGLImage -> external texture -> mesh.
 *
 * This mirrors the original jxg renderer. Successful HardwareBuffer imports are retained until
 * an EGL fence confirms that the GPU has finished sampling the source buffer. This avoids a
 * blocking fence wait on every frame while keeping Camera2 buffer lifetime correct. A null
 * result leaves the image available for the explicit CPU YUV fallback.
 */
internal class MgcEisHardwareBufferRenderer(private val tag: String) {
    data class Frame(val timestampNs: Long, val textureId: Int, val width: Int, val height: Int)

    private val mesh = MgcEisMesh()
    // Preserve the active mode's crop even if an individual timestamp loses pose coverage.
    private fun droppedFrameHomographies(positionLock: Boolean): FloatArray =
        FloatArray(STABILIZATION_ROW_COUNT * 9).also { values ->
            val h22 = if (positionLock) 1f / POSITION_LOCK_CROP_ZOOM else 0.9f
            repeat(STABILIZATION_ROW_COUNT) { row ->
                val offset = row * 9
                values[offset] = 1f
                values[offset + 4] = 1f
                values[offset + 8] = h22
            }
        }

    private var programId = 0
    private var positionLocation = -1
    private var textureCoordinateLocation = -1
    private var sourceSamplerLocation = -1
    private data class PendingSourceSampling(
        val slot: Int,
        val imageHandle: Long,
        val fence: EGLSync,
        val image: com.hinnka.mycamera.stabilization.StabilizationImage,
    )

    private val pendingSourceSampling = mutableListOf<PendingSourceSampling>()
    private var sourceTextureIds = IntArray(0)
    private var nextSourceTextureSlot = 0
    private var outputTextureId = 0
    private var outputFramebufferId = 0
    private var width = 0
    private var height = 0
    private var loggedImportFailure = false
    private var loggedZeroCopy = false

    /**
     * Attempts the zero-copy path. On success this renderer takes ownership of the acquired image
     * until the GPU fence signals; callers must not close that image. On failure the caller still
     * owns it and may use the CPU YUV fallback.
     */
    fun render(frame: StabilizationFrame): Frame? {
        val width = frame.image.width
        val height = frame.image.height
        if (width <= 0 || height <= 0) return null
        val source = frame.image.image
        val hardwareBuffer = try {
            source.hardwareBuffer
        } catch (error: IllegalStateException) {
            PLog.w(tag, "MGC cannot access HardwareBuffer ts=${frame.timestampNs}: ${error.message}")
            return null
        } ?: return null
        if (!ensure(width, height)) return null

        val slot = acquireSourceTextureSlot()
        val sourceTextureId = sourceTextureIds[slot]
        var imageHandle = GlesHardwareBufferImage.create(hardwareBuffer)
        if (imageHandle == 0L) {
            if (!loggedImportFailure) {
                loggedImportFailure = true
                PLog.w(tag, "MGC EGLImage import unavailable; using CPU YUV fallback")
            }
            return null
        }

        var sourceSamplingSubmitted = false
        try {
            if (!GlesHardwareBufferImage.bind(imageHandle, sourceTextureId)) {
                PLog.w(tag, "MGC EGLImage bind failed ts=${frame.timestampNs}")
                return null
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFramebufferId)
            GLES30.glViewport(0, 0, source.width, source.height)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glUseProgram(programId)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, sourceTextureId)
            GLES30.glUniform1i(sourceSamplerLocation, 0)
            mesh.draw(
                positionLocation = positionLocation,
                textureCoordinateLocation = textureCoordinateLocation,
                rowHomographies = frame.transform?.rowHomographies ?: droppedFrameHomographies(frame.positionLock),
            )
            sourceSamplingSubmitted = true

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES31.glMemoryBarrier(
                GLES31.GL_FRAMEBUFFER_BARRIER_BIT or GLES31.GL_TEXTURE_FETCH_BARRIER_BIT,
            )
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

            // Defer the source Image/EGLImage release instead of blocking this render thread for
            // every camera frame. The ring slot prevents rebinding this OES texture while the GPU
            // may still be sampling the previous HardwareBuffer from it.
            val display = EGL14.eglGetCurrentDisplay()
            val fence = if (display != EGL14.EGL_NO_DISPLAY) {
                EGL15.eglCreateSync(
                    display,
                    EGL15.EGL_SYNC_FENCE,
                    longArrayOf(EGL14.EGL_NONE.toLong()),
                    0,
                )
            } else {
                EGL15.EGL_NO_SYNC
            }
            if (fence == EGL15.EGL_NO_SYNC) {
                // Fence creation is uncommon to fail, but never release the camera buffer early.
                GLES30.glFinish()
                GlesHardwareBufferImage.destroy(imageHandle)
                imageHandle = 0L
                frame.image.close()
            } else {
                GLES30.glFlush()
                pendingSourceSampling += PendingSourceSampling(
                    slot = slot,
                    imageHandle = imageHandle,
                    fence = fence,
                    image = frame.image,
                )
                imageHandle = 0L // Ownership moved to pendingSourceSampling.
            }

            if (!loggedZeroCopy) {
                loggedZeroCopy = true
                PLog.i(tag, "MGC HardwareBuffer EGLImage renderer active (fenced ring buffer)")
            }
            return Frame(frame.timestampNs, outputTextureId, width, height)
        } catch (error: RuntimeException) {
            if (sourceSamplingSubmitted) {
                // The caller will run the YUV fallback; wait before it can close/recycle this Image.
                GLES30.glFinish()
            }
            PLog.w(tag, "MGC EGLImage draw failed ts=${frame.timestampNs}: ${error.message}")
            return null
        } finally {
            if (imageHandle != 0L) {
                if (sourceSamplingSubmitted) {
                    GLES30.glFinish()
                }
                GlesHardwareBufferImage.destroy(imageHandle)
            }
        }
    }

    /**
     * Poll fences without stalling the render thread. When all ring slots are still in flight,
     * wait only for the oldest one before reusing that slot. The Camera2 Image and EGLImage are
     * released together, only after their source sampling has completed.
     */
    private fun acquireSourceTextureSlot(): Int {
        pollCompletedSourceSampling()
        val occupied = pendingSourceSampling.mapTo(mutableSetOf()) { it.slot }
        for (offset in sourceTextureIds.indices) {
            val slot = (nextSourceTextureSlot + offset) % sourceTextureIds.size
            if (slot !in occupied) {
                nextSourceTextureSlot = (slot + 1) % sourceTextureIds.size
                return slot
            }
        }

        val oldest = pendingSourceSampling.firstOrNull() ?: return 0
        waitForSourceSampling(oldest)
        pendingSourceSampling.remove(oldest)
        nextSourceTextureSlot = (oldest.slot + 1) % sourceTextureIds.size
        return oldest.slot
    }

    private fun pollCompletedSourceSampling() {
        if (pendingSourceSampling.isEmpty()) return
        val display = EGL14.eglGetCurrentDisplay()
        if (display == EGL14.EGL_NO_DISPLAY) return

        var index = 0
        while (index < pendingSourceSampling.size) {
            val pending = pendingSourceSampling[index]
            val result = runCatching {
                EGL15.eglClientWaitSync(display, pending.fence, 0, 0L)
            }.getOrDefault(EGL14.EGL_FALSE)
            if (result == EGL15.EGL_CONDITION_SATISFIED) {
                releasePendingSourceSampling(pending, display)
                pendingSourceSampling.removeAt(index)
            } else if (result == EGL14.EGL_FALSE) {
                PLog.w(tag, "MGC EGL fence poll failed; forcing GL completion")
                runCatching { GLES30.glFinish() }
                releasePendingSourceSampling(pending, display)
                pendingSourceSampling.removeAt(index)
            } else {
                index += 1
            }
        }
    }

    private fun waitForSourceSampling(pending: PendingSourceSampling) {
        val display = EGL14.eglGetCurrentDisplay()
        if (display == EGL14.EGL_NO_DISPLAY) {
            runCatching { GLES30.glFinish() }
            releasePendingSourceSampling(pending, display)
            return
        }
        val result = runCatching {
            EGL15.eglClientWaitSync(
                display,
                pending.fence,
                EGL15.EGL_SYNC_FLUSH_COMMANDS_BIT,
                EGL15.EGL_FOREVER,
            )
        }.getOrDefault(EGL14.EGL_FALSE)
        if (result != EGL15.EGL_CONDITION_SATISFIED) {
            PLog.w(tag, "MGC EGL fence wait failed result=$result; forcing GL completion")
            runCatching { GLES30.glFinish() }
        }
        releasePendingSourceSampling(pending, display)
    }

    private fun releasePendingSourceSampling(
        pending: PendingSourceSampling,
        display: android.opengl.EGLDisplay,
    ) {
        if (display != EGL14.EGL_NO_DISPLAY) {
            runCatching { EGL15.eglDestroySync(display, pending.fence) }
        }
        runCatching { GlesHardwareBufferImage.destroy(pending.imageHandle) }
            .onFailure { PLog.w(tag, "MGC EGLImage cleanup failed: ${it.message}") }
        pending.image.close()
    }

    private fun releaseAllPendingSourceSampling() {
        if (pendingSourceSampling.isEmpty()) return
        val display = EGL14.eglGetCurrentDisplay()
        if (display != EGL14.EGL_NO_DISPLAY) {
            pendingSourceSampling.toList().forEach(::waitForSourceSampling)
        } else {
            // A lost EGL context cannot safely use its fences; retire native and Camera2 leases.
            runCatching { GLES30.glFinish() }
            pendingSourceSampling.forEach { pending ->
                runCatching { GlesHardwareBufferImage.destroy(pending.imageHandle) }
                pending.image.close()
            }
        }
        pendingSourceSampling.clear()
    }

    fun resetAfterContextLoss() {
        releaseAllPendingSourceSampling()
        programId = 0
        positionLocation = -1
        textureCoordinateLocation = -1
        sourceSamplerLocation = -1
        sourceTextureIds = IntArray(0)
        nextSourceTextureSlot = 0
        outputTextureId = 0
        outputFramebufferId = 0
        width = 0
        height = 0
        mesh.resetAfterContextLoss()
    }

    fun release() {
        releaseAllPendingSourceSampling()
        val textures = (sourceTextureIds.toList() + outputTextureId)
            .filter { it != 0 }
            .toIntArray()
        if (textures.isNotEmpty()) GLES30.glDeleteTextures(textures.size, textures, 0)
        if (outputFramebufferId != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(outputFramebufferId), 0)
        }
        if (programId != 0) GLES30.glDeleteProgram(programId)
        mesh.release()
        resetAfterContextLoss()
    }

    private fun ensure(requestedWidth: Int, requestedHeight: Int): Boolean {
        if (programId != 0 && width == requestedWidth && height == requestedHeight) return true
        release()
        val vertexShader = GlUtils.compileShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = GlUtils.compileShader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        programId = GlUtils.linkProgram(vertexShader, fragmentShader)
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)
        if (programId == 0) return false
        positionLocation = GLES30.glGetAttribLocation(programId, "aPosition")
        textureCoordinateLocation = GLES30.glGetAttribLocation(programId, "aTexCoord")
        sourceSamplerLocation = GLES30.glGetUniformLocation(programId, "uCameraTexture")
        mesh.initialize()
        sourceTextureIds = IntArray(SOURCE_TEXTURE_SLOT_COUNT) { GlUtils.createOESTexture() }
        nextSourceTextureSlot = 0
        outputTextureId = createTexture(requestedWidth, requestedHeight)
        if (sourceTextureIds.any { it == 0 } || outputTextureId == 0 ||
            !createOutputTarget(outputTextureId)
        ) {
            release()
            return false
        }
        width = requestedWidth
        height = requestedHeight
        return true
    }

    private fun createTexture(textureWidth: Int, textureHeight: Int): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA8,
            textureWidth,
            textureHeight,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            null,
        )
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    private fun createOutputTarget(textureId: Int): Boolean {
        val framebuffers = IntArray(1)
        GLES30.glGenFramebuffers(1, framebuffers, 0)
        outputFramebufferId = framebuffers[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFramebufferId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            textureId,
            0,
        )
        val complete = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) ==
            GLES30.GL_FRAMEBUFFER_COMPLETE
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (!complete) PLog.e(tag, "Cannot allocate MGC HardwareBuffer output ${width}x$height")
        return complete
    }

    private companion object {
        const val SOURCE_TEXTURE_SLOT_COUNT = 3

        val VERTEX_SHADER = """
            #version 300 es
            in vec4 aPosition;
            in vec2 aTexCoord;
            out vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        val FRAGMENT_SHADER = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;
            uniform samplerExternalOES uCameraTexture;
            in vec2 vTexCoord;
            out vec4 fragColor;
            void main() {
                fragColor = texture(uCameraTexture, vTexCoord);
            }
        """.trimIndent()
    }
}
