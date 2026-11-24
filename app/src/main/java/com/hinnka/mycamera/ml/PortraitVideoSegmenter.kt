package com.hinnka.mycamera.ml

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import java.nio.ByteOrder

data class PortraitSubjectMask(val width: Int, val height: Int, val data: ByteArray)

/**
 * Luma-compatible selfie multiclass segmentation. The returned mask is subject probability
 * (1 - background), so hair, face, skin, clothes and accessories remain sharp.
 *
 * GPU is attempted first; devices without a compatible MediaPipe GPU delegate fall back to CPU.
 * Calls are intentionally serialized by the ViewModel's single segmentation job.
 */
class PortraitVideoSegmenter(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private var segmenter: ImageSegmenter? = null
    private var closed = false

    @Synchronized
    fun segment(bitmap: Bitmap): PortraitSubjectMask? {
        if (closed || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        val active = segmenter ?: createSegmenter()?.also { segmenter = it } ?: return null
        var inputBitmap: Bitmap? = null
        var image: com.google.mediapipe.framework.image.MPImage? = null
        try {
            inputBitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) {
                bitmap
            } else {
                bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            }
            image = BitmapImageBuilder(inputBitmap).build()
            val result = active.segment(image)
            val masks = result.confidenceMasks().orElse(null) ?: return null
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
            return PortraitSubjectMask(width, height, subject)
        } catch (_: Exception) {
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
            try {
                val options = ImageSegmenter.ImageSegmenterOptions.builder()
                    .setBaseOptions(
                        BaseOptions.builder()
                            .setDelegate(delegate)
                            .setModelAssetPath(MODEL_ASSET)
                            .build()
                    )
                    .setRunningMode(RunningMode.IMAGE)
                    .setOutputConfidenceMasks(true)
                    .setOutputCategoryMask(false)
                    .build()
                return ImageSegmenter.createFromOptions(appContext, options)
            } catch (_: Exception) {
                // Try the CPU delegate when this device/driver cannot run the GPU delegate.
            }
        }
        return null
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        segmenter?.close()
        segmenter = null
    }

    private companion object {
        const val MODEL_ASSET = "models/selfie_multiclass_256x256.tflite"
    }
}
