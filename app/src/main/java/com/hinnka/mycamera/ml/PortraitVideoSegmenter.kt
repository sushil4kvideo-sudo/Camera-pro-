package com.hinnka.mycamera.ml

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import java.nio.ByteOrder
import com.hinnka.mycamera.utils.PLog

data class PortraitSubjectMask(val width: Int, val height: Int, val data: ByteArray)

/**
 * Luma-compatible selfie multiclass segmentation. The returned mask is subject probability
 * (1 - background), so hair, face, skin, clothes and accessories remain sharp.
 *
 * Important: MediaPipe's Android GPU delegate is thread-affine. The segmenter must be created,
 * used and closed on the same thread. CameraViewModel therefore runs the complete portrait
 * segmentation loop on a dedicated single-thread dispatcher.
 *
 * VIDEO mode also gives MediaPipe monotonically timestamped frames instead of treating every
 * preview sample as an unrelated still image.
 */
class PortraitVideoSegmenter(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private var segmenter: ImageSegmenter? = null
    private var activeDelegateName = "uninitialized"
    private var lastVideoTimestampMs = 0L
    private var lastPerfLogElapsedMs = 0L
    private var closed = false

    @Synchronized
    fun segment(bitmap: Bitmap): PortraitSubjectMask? {
        if (closed || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null

        val totalStartedAt = SystemClock.elapsedRealtime()
        var inputBitmap: Bitmap? = null
        var image: com.google.mediapipe.framework.image.MPImage? = null
        try {
            val active = segmenter ?: createSegmenter()?.also { segmenter = it } ?: return null
            inputBitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) {
                bitmap
            } else {
                bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            }
            image = BitmapImageBuilder(inputBitmap).build()

            val timestampMs = maxOf(SystemClock.uptimeMillis(), lastVideoTimestampMs + 1L)
            lastVideoTimestampMs = timestampMs
            val inferenceStartedAt = SystemClock.elapsedRealtime()
            val result = active.segmentForVideo(image, timestampMs)
            val inferenceMs = SystemClock.elapsedRealtime() - inferenceStartedAt
            val masks = result.confidenceMasks().orElse(null) ?: return null
            try {
                if (masks.isEmpty()) return null

                // The first confidence channel is background for this MediaPipe selfie model.
                val background = masks[0]
                val width = background.width
                val height = background.height
                if (width <= 0 || height <= 0) return null
                val values = ByteBufferExtractor.extract(background)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
                val subject = ByteArray(width * height)
                for (index in subject.indices) {
                    val backgroundConfidence = values.get(index).coerceIn(0f, 1f)
                    subject[index] = ((1f - backgroundConfidence) * 255f + 0.5f).toInt().toByte()
                }

                val totalMs = SystemClock.elapsedRealtime() - totalStartedAt
                if (SystemClock.elapsedRealtime() - lastPerfLogElapsedMs >= PERF_LOG_INTERVAL_MS) {
                    lastPerfLogElapsedMs = SystemClock.elapsedRealtime()
                    PLog.i(
                        TAG,
                        "Portrait segmentation: backend=$activeDelegateName mode=VIDEO " +
                            "inference=${inferenceMs}ms total=${totalMs}ms " +
                            "input=${inputBitmap.width}x${inputBitmap.height} " +
                            "mask=${width}x${height}"
                    )
                }
                return PortraitSubjectMask(width, height, subject)
            } finally {
                masks.forEach { it.close() }
            }
        } catch (error: Exception) {
            PLog.e(TAG, "Portrait segmentation failed (backend=$activeDelegateName)", error)
            return null
        } finally {
            image?.close()
            if (inputBitmap != null && inputBitmap !== bitmap && !inputBitmap.isRecycled) {
                inputBitmap.recycle()
            }
        }
    }

    private fun createSegmenter(): ImageSegmenter? {
        val delegates = listOf(Delegate.GPU, Delegate.CPU)
        for (delegate in delegates) {
            var created: ImageSegmenter? = null
            try {
                val options = ImageSegmenter.ImageSegmenterOptions.builder()
                    .setBaseOptions(
                        BaseOptions.builder()
                            .setDelegate(delegate)
                            .setModelAssetPath(MODEL_ASSET)
                            .build()
                    )
                    .setRunningMode(RunningMode.VIDEO)
                    .setOutputConfidenceMasks(true)
                    .setOutputCategoryMask(false)
                    .build()
                created = ImageSegmenter.createFromOptions(appContext, options)
                activeDelegateName = delegate.name
                PLog.i(
                    TAG,
                    "Portrait segmenter initialized: backend=$activeDelegateName " +
                        "mode=VIDEO model=$MODEL_ASSET"
                )
                return created
            } catch (error: Exception) {
                runCatching { created?.close() }
                PLog.w(
                    TAG,
                    "Portrait segmenter initialization failed for ${delegate.name}; trying next delegate",
                    error
                )
            }
        }
        activeDelegateName = "unavailable"
        PLog.e(TAG, "Unable to initialize portrait segmenter with GPU or CPU delegate")
        return null
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        segmenter?.close()
        segmenter = null
        activeDelegateName = "closed"
        lastVideoTimestampMs = 0L
    }

    private companion object {
        const val TAG = "PortraitVideoSegmenter"
        const val MODEL_ASSET = "models/selfie_multiclass_256x256.tflite"
        const val PERF_LOG_INTERVAL_MS = 2_000L
    }
}
