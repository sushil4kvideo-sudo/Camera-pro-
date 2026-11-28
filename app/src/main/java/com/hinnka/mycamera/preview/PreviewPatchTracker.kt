package com.hinnka.mycamera.preview

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Lightweight appearance tracker used between slower detector inferences.
 *
 * It follows the image patch inside the last selected box, so the target does not need
 * to keep a stable MediaPipe category (or even be recognised by the detector at all).
 * Frames are reduced to a small luminance image before matching to keep CPU and memory
 * use bounded on mid-range phones.
 */
internal class PreviewPatchTracker {
    private data class GrayFrame(
        val width: Int,
        val height: Int,
        val pixels: FloatArray,
    )

    companion object {
        private const val MAX_FRAME_EDGE = 192
        private const val GRID = 10
        private const val SAMPLE_COUNT = GRID * GRID
        private const val MIN_MATCH_SCORE = 0.44f
        private const val MAX_LOST_FRAMES = 10
        private val SCALES = floatArrayOf(0.95f, 1.0f, 1.05f)
    }

    private val offsetX = FloatArray(SAMPLE_COUNT)
    private val offsetY = FloatArray(SAMPLE_COUNT)
    private val samples = FloatArray(SAMPLE_COUNT)

    private var template: FloatArray? = null
    private var templateMean = 0f
    private var templateVariance = 0f
    private var frameWidth = 0
    private var frameHeight = 0
    private var centerX = 0f
    private var centerY = 0f
    private var boxWidth = 0f
    private var boxHeight = 0f
    private var velocityX = 0f
    private var velocityY = 0f
    private var lostFrames = 0
    private var label = "Subject"
    private var confidence = 1f

    init {
        var index = 0
        for (row in 0 until GRID) {
            for (column in 0 until GRID) {
                offsetX[index] = ((column + 0.5f) / GRID - 0.5f) * 0.82f
                offsetY[index] = ((row + 0.5f) / GRID - 0.5f) * 0.82f
                index++
            }
        }
    }

    fun reset() {
        template = null
        frameWidth = 0
        frameHeight = 0
        centerX = 0f
        centerY = 0f
        boxWidth = 0f
        boxHeight = 0f
        velocityX = 0f
        velocityY = 0f
        lostFrames = 0
        confidence = 1f
    }

    fun initialize(bitmap: Bitmap, target: PreviewSubjectTrackingProcessor.Target) {
        initialize(toGrayFrame(bitmap), target)
    }

    fun track(
        bitmap: Bitmap,
        fallback: PreviewSubjectTrackingProcessor.Target,
    ): PreviewSubjectTrackingProcessor.Target? {
        val frame = toGrayFrame(bitmap)
        if (
            template == null ||
            frame.width != frameWidth ||
            frame.height != frameHeight
        ) {
            initialize(frame, fallback)
            return fallback
        }

        val predictedX = (centerX + velocityX).coerceIn(1f, frame.width - 2f)
        val predictedY = (centerY + velocityY).coerceIn(1f, frame.height - 2f)
        // Search close to the motion prediction. A broad 17%-of-frame search lets a
        // similar-looking neighbour win and makes the overlay jump between subjects.
        val radiusX = max(boxWidth * 0.32f, frame.width * 0.025f)
            .coerceAtMost(frame.width * 0.10f).roundToInt().coerceAtLeast(4)
        val radiusY = max(boxHeight * 0.32f, frame.height * 0.025f)
            .coerceAtMost(frame.height * 0.10f).roundToInt().coerceAtLeast(4)

        var bestScore = Float.NEGATIVE_INFINITY
        var bestX = predictedX.roundToInt()
        var bestY = predictedY.roundToInt()
        var bestScale = 1f

        val minX = (predictedX.roundToInt() - radiusX).coerceAtLeast(1)
        val maxX = (predictedX.roundToInt() + radiusX).coerceAtMost(frame.width - 2)
        val minY = (predictedY.roundToInt() - radiusY).coerceAtLeast(1)
        val maxY = (predictedY.roundToInt() + radiusY).coerceAtMost(frame.height - 2)

        for (scale in SCALES) {
            val candidateWidth = boxWidth * scale
            val candidateHeight = boxHeight * scale
            for (y in minY..maxY step 3) {
                for (x in minX..maxX step 3) {
                    val score = scoreAt(frame, x.toFloat(), y.toFloat(), candidateWidth, candidateHeight)
                    if (score > bestScore) {
                        bestScore = score
                        bestX = x
                        bestY = y
                        bestScale = scale
                    }
                }
            }
        }

        // Refine the best coarse match to single-pixel precision.
        val coarseX = bestX
        val coarseY = bestY
        for (scale in SCALES) {
            val candidateWidth = boxWidth * scale
            val candidateHeight = boxHeight * scale
            for (y in (coarseY - 3).coerceAtLeast(1)..(coarseY + 3).coerceAtMost(frame.height - 2)) {
                for (x in (coarseX - 3).coerceAtLeast(1)..(coarseX + 3).coerceAtMost(frame.width - 2)) {
                    val score = scoreAt(frame, x.toFloat(), y.toFloat(), candidateWidth, candidateHeight)
                    if (score > bestScore) {
                        bestScore = score
                        bestX = x
                        bestY = y
                        bestScale = scale
                    }
                }
            }
        }

        if (!bestScore.isFinite() || bestScore < MIN_MATCH_SCORE) {
            lostFrames++
            if (lostFrames > MAX_LOST_FRAMES) {
                reset()
                return null
            }
            centerX = predictedX
            centerY = predictedY
            velocityX *= 0.78f
            velocityY *= 0.78f
            confidence = (confidence * 0.92f).coerceAtLeast(0.18f)
            return createTarget(frame, confidence)
        }

        lostFrames = 0
        val previousX = centerX
        val previousY = centerY
        val matchedX = bestX.toFloat()
        val matchedY = bestY.toFloat()
        centerX = (predictedX + (matchedX - predictedX) * 0.78f)
            .coerceIn(boxWidth * 0.5f, frame.width - boxWidth * 0.5f)
        centerY = (predictedY + (matchedY - predictedY) * 0.78f)
            .coerceIn(boxHeight * 0.5f, frame.height - boxHeight * 0.5f)
        velocityX = (velocityX * 0.45f + (centerX - previousX) * 0.55f)
            .coerceIn(-22f, 22f)
        velocityY = (velocityY * 0.45f + (centerY - previousY) * 0.55f)
            .coerceIn(-22f, 22f)

        boxWidth = (boxWidth * (0.78f + bestScale * 0.22f))
            .coerceIn(8f, frame.width * 0.92f)
        boxHeight = (boxHeight * (0.78f + bestScale * 0.22f))
            .coerceIn(8f, frame.height * 0.92f)
        confidence = ((bestScore + 1f) * 0.5f).coerceIn(0f, 1f)

        if (bestScore >= 0.62f && scoreAt(frame, centerX, centerY, boxWidth, boxHeight) > MIN_MATCH_SCORE) {
            val reference = template
            if (reference != null) {
                for (i in samples.indices) {
                    reference[i] = reference[i] * 0.985f + samples[i] * 0.015f
                }
                updateTemplateStatistics()
            }
        }
        return createTarget(frame, confidence)
    }

    private fun initialize(
        frame: GrayFrame,
        target: PreviewSubjectTrackingProcessor.Target,
    ) {
        frameWidth = frame.width
        frameHeight = frame.height
        centerX = target.centerX.coerceIn(0f, 1f) * frame.width
        centerY = target.centerY.coerceIn(0f, 1f) * frame.height
        boxWidth = ((target.right - target.left) * frame.width)
            .coerceIn(8f, frame.width * 0.92f)
        boxHeight = ((target.bottom - target.top) * frame.height)
            .coerceIn(8f, frame.height * 0.92f)
        centerX = centerX.coerceIn(boxWidth * 0.5f, frame.width - boxWidth * 0.5f)
        centerY = centerY.coerceIn(boxHeight * 0.5f, frame.height - boxHeight * 0.5f)
        velocityX = 0f
        velocityY = 0f
        lostFrames = 0
        label = target.label
        confidence = target.confidence.coerceIn(0f, 1f)
        if (!readSamples(frame, centerX, centerY, boxWidth, boxHeight)) {
            reset()
            return
        }
        template = samples.copyOf()
        updateTemplateStatistics()
    }

    private fun scoreAt(
        frame: GrayFrame,
        candidateX: Float,
        candidateY: Float,
        candidateWidth: Float,
        candidateHeight: Float,
    ): Float {
        val reference = template ?: return Float.NEGATIVE_INFINITY
        if (!readSamples(frame, candidateX, candidateY, candidateWidth, candidateHeight)) {
            return Float.NEGATIVE_INFINITY
        }

        var sum = 0f
        var sumSquares = 0f
        var dot = 0f
        for (index in samples.indices) {
            val value = samples[index]
            sum += value
            sumSquares += value * value
            dot += reference[index] * value
        }
        val candidateMean = sum / SAMPLE_COUNT
        val candidateVariance = (sumSquares / SAMPLE_COUNT - candidateMean * candidateMean)
            .coerceAtLeast(0f)
        if (templateVariance < 4f || candidateVariance < 4f) {
            return (1f - abs(templateMean - candidateMean) / 160f).coerceIn(-1f, 1f)
        }
        val covariance = dot / SAMPLE_COUNT - templateMean * candidateMean
        val denominator = sqrt((templateVariance * candidateVariance).toDouble()).toFloat()
        return if (denominator > 1e-4f) {
            (covariance / denominator).coerceIn(-1f, 1f)
        } else {
            Float.NEGATIVE_INFINITY
        }
    }

    private fun readSamples(
        frame: GrayFrame,
        candidateX: Float,
        candidateY: Float,
        candidateWidth: Float,
        candidateHeight: Float,
    ): Boolean {
        if (candidateWidth < 4f || candidateHeight < 4f) return false
        for (index in samples.indices) {
            val x = (candidateX + offsetX[index] * candidateWidth).roundToInt()
            val y = (candidateY + offsetY[index] * candidateHeight).roundToInt()
            if (x < 1 || y < 1 || x >= frame.width - 1 || y >= frame.height - 1) {
                return false
            }
            samples[index] = frame.pixels[y * frame.width + x]
        }
        return true
    }

    private fun updateTemplateStatistics() {
        val reference = template ?: return
        templateMean = reference.sum() / SAMPLE_COUNT
        var variance = 0f
        for (value in reference) {
            val difference = value - templateMean
            variance += difference * difference
        }
        templateVariance = variance / SAMPLE_COUNT
    }

    private fun createTarget(
        frame: GrayFrame,
        matchConfidence: Float,
    ): PreviewSubjectTrackingProcessor.Target {
        val halfWidth = boxWidth * 0.5f
        val halfHeight = boxHeight * 0.5f
        val left = ((centerX - halfWidth) / frame.width).coerceIn(0f, 1f)
        val top = ((centerY - halfHeight) / frame.height).coerceIn(0f, 1f)
        val right = ((centerX + halfWidth) / frame.width).coerceIn(0f, 1f)
        val bottom = ((centerY + halfHeight) / frame.height).coerceIn(0f, 1f)
        return PreviewSubjectTrackingProcessor.Target(
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            label = label,
            confidence = matchConfidence,
        )
    }

    private fun toGrayFrame(bitmap: Bitmap): GrayFrame {
        val scale = min(1f, MAX_FRAME_EDGE.toFloat() / max(bitmap.width, bitmap.height).coerceAtLeast(1))
        val width = max(24, (bitmap.width * scale).roundToInt())
        val height = max(24, (bitmap.height * scale).roundToInt())
        val scaled = if (bitmap.width == width && bitmap.height == height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
        try {
            val argb = IntArray(width * height)
            val gray = FloatArray(width * height)
            scaled.getPixels(argb, 0, width, 0, 0, width, height)
            for (index in argb.indices) {
                val color = argb[index]
                val red = (color shr 16) and 0xff
                val green = (color shr 8) and 0xff
                val blue = color and 0xff
                gray[index] = (red * 77 + green * 150 + blue * 29) / 256f
            }
            return GrayFrame(width, height, gray)
        } finally {
            if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
        }
    }
}
