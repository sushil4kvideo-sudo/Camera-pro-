package com.hinnka.mycamera.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * On-demand, single-frame-in-flight object detector for the live viewfinder.
 *
 * This processor is deliberately not created or prewarmed by Application startup.
 * The EfficientDet model is opened only after the user enables AI tracking and the
 * first preview frame arrives. Input bitmaps are always consumed by this class.
 */
class PreviewSubjectTrackingProcessor(context: Context) : Closeable {

    data class Target(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val label: String,
        val confidence: Float,
    ) {
        val centerX: Float get() = (left + right) * 0.5f
        val centerY: Float get() = (top + bottom) * 0.5f
    }

    private data class Candidate(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val label: String,
        val confidence: Float,
    ) {
        val centerX: Float get() = (left + right) * 0.5f
        val centerY: Float get() = (top + bottom) * 0.5f
        val area: Float get() = ((right - left) * (bottom - top)).coerceAtLeast(0f)

        fun toTarget(): Target = Target(left, top, right, bottom, label, confidence)
    }

    companion object {
        private const val TAG = "PreviewSubjectTracking"
        private const val MODEL_ASSET = "efficientdet_lite0_int8.tflite"
        private const val MIN_SCORE = 0.38f
        private const val MAX_RESULTS = 8
        private const val LOST_FRAMES_BEFORE_RESET = 3
        private const val SMOOTHING_ALPHA = 0.42f
        private const val MAX_TARGET_CENTER_DISTANCE = 0.30f
    }

    private val appContext = context.applicationContext
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CameraProSubjectTracking").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = true
        }
    }
    private val frameInFlight = AtomicBoolean(false)
    private val generation = AtomicInteger(0)
    private val released = AtomicBoolean(false)

    @Volatile
    private var detectorInitializationFailed = false

    @Volatile
    var onTargetChanged: ((Target?) -> Unit)? = null

    private var detector: ObjectDetector? = null
    private var pendingImage: MPImage? = null
    private var pendingBitmap: Bitmap? = null
    private var pendingGeneration = 0
    private var lastTimestampMs = 0L
    private var lastBounds: Candidate? = null
    private var missingFrames = 0

    /**
     * Consumes [frame.bitmap]. Busy frames are dropped immediately so inference never
     * queues stale images behind the current frame.
     */
    fun processFrame(frame: EyeFocusPreviewFrame) {
        val bitmap = frame.bitmap
        if (released.get() || detectorInitializationFailed ||
            !frameInFlight.compareAndSet(false, true)
        ) {
            recycle(bitmap)
            return
        }

        val frameGeneration = generation.get()
        try {
            executor.execute {
                submitFrame(bitmap, frameGeneration)
            }
        } catch (_: RejectedExecutionException) {
            recycle(bitmap)
            frameInFlight.set(false)
        }
    }

    fun resetTracking() {
        generation.incrementAndGet()
        synchronized(lock) {
            lastBounds = null
            missingFrames = 0
        }
        dispatchTarget(null)
    }

    private fun submitFrame(sourceBitmap: Bitmap, frameGeneration: Int) {
        if (released.get()) {
            recycle(sourceBitmap)
            frameInFlight.set(false)
            return
        }

        var preparedBitmap: Bitmap? = null
        var image: MPImage? = null
        try {
            preparedBitmap = if (sourceBitmap.config == Bitmap.Config.ARGB_8888) {
                sourceBitmap
            } else {
                val converted = sourceBitmap.copy(Bitmap.Config.ARGB_8888, false)
                    ?: throw IllegalStateException("Unable to convert subject-tracking frame")
                recycle(sourceBitmap)
                converted
            }
            image = BitmapImageBuilder(preparedBitmap).build()
        } catch (error: RuntimeException) {
            image?.close()
            recycle(preparedBitmap)
            if (preparedBitmap !== sourceBitmap) recycle(sourceBitmap)
            frameInFlight.set(false)
            Log.w(TAG, "Unable to prepare tracking frame", error)
            return
        }

        val activeDetector = getOrCreateDetector()
        if (activeDetector == null || released.get()) {
            image.close()
            recycle(preparedBitmap)
            frameInFlight.set(false)
            return
        }

        val timestampMs = synchronized(lock) {
            lastTimestampMs = maxOf(SystemClock.uptimeMillis(), lastTimestampMs + 1L)
            pendingImage = image
            pendingBitmap = preparedBitmap
            pendingGeneration = frameGeneration
            lastTimestampMs
        }

        try {
            activeDetector.detectAsync(image, timestampMs)
        } catch (error: RuntimeException) {
            cleanupPendingInput()
            frameInFlight.set(false)
            Log.w(TAG, "Object detector rejected a preview frame", error)
        }
    }

    private fun getOrCreateDetector(): ObjectDetector? {
        detector?.let { return it }
        if (detectorInitializationFailed || released.get()) return null

        return try {
            val baseOptions = BaseOptions.builder()
                .setDelegate(Delegate.CPU)
                .setModelAssetPath(MODEL_ASSET)
                .build()
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setScoreThreshold(MIN_SCORE)
                .setMaxResults(MAX_RESULTS)
                .setResultListener(::handleResult)
                .setErrorListener(::handleError)
                .build()
            ObjectDetector.createFromOptions(appContext, options).also {
                detector = it
                Log.i(TAG, "EfficientDet-Lite0 object detector initialized on demand")
            }
        } catch (error: RuntimeException) {
            detectorInitializationFailed = true
            Log.e(TAG, "AI tracking model initialization failed; camera preview remains active", error)
            dispatchTarget(null)
            null
        }
    }

    private fun handleResult(result: ObjectDetectorResult, input: MPImage) {
        val inputWidth = input.width
        val inputHeight = input.height
        val nextTarget: Target?
        val resultGeneration: Int

        synchronized(lock) {
            resultGeneration = pendingGeneration
            cleanupPendingInputLocked()
            nextTarget = if (released.get() || resultGeneration != generation.get()) {
                null
            } else {
                selectTargetLocked(readCandidates(result, inputWidth, inputHeight))
            }
        }
        frameInFlight.set(false)

        if (!released.get() && resultGeneration == generation.get()) {
            dispatchTarget(nextTarget)
        }
    }

    private fun readCandidates(
        result: ObjectDetectorResult,
        inputWidth: Int,
        inputHeight: Int,
    ): List<Candidate> {
        if (inputWidth <= 0 || inputHeight <= 0) return emptyList()
        return result.detections().mapNotNull { detection ->
            val box: RectF = detection.boundingBox()
            val category = detection.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
            val confidence = category.score()
            if (!confidence.isFinite() || confidence < MIN_SCORE) return@mapNotNull null

            val left = (box.left / inputWidth).coerceIn(0f, 1f)
            val top = (box.top / inputHeight).coerceIn(0f, 1f)
            val right = (box.right / inputWidth).coerceIn(0f, 1f)
            val bottom = (box.bottom / inputHeight).coerceIn(0f, 1f)
            if (right - left < 0.015f || bottom - top < 0.015f) return@mapNotNull null

            Candidate(
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                label = category.categoryName().takeIf { it.isNotBlank() } ?: "Object",
                confidence = confidence.coerceIn(0f, 1f),
            )
        }
    }

    private fun selectTargetLocked(candidates: List<Candidate>): Target? {
        val previous = lastBounds
        val selected = if (previous == null) {
            // Prefer a person when the scene contains one; otherwise track the most
            // prominent confident object instead of silently doing nothing.
            val people = candidates.filter { it.label.equals("person", ignoreCase = true) }
            (people.ifEmpty { candidates }).maxByOrNull {
                it.confidence * (0.55f + sqrt(it.area.coerceAtLeast(0f)))
            }
        } else {
            val sameClass = candidates.filter { it.label.equals(previous.label, ignoreCase = true) }
            val pool = sameClass.ifEmpty { candidates }
            pool.maxByOrNull { candidate ->
                val overlap = intersectionOverUnion(previous, candidate)
                val distance = centerDistance(previous, candidate)
                overlap * 2.0f + (1f - (distance / MAX_TARGET_CENTER_DISTANCE).coerceIn(0f, 1f)) +
                    candidate.confidence * 0.15f
            }?.takeIf { candidate ->
                intersectionOverUnion(previous, candidate) >= 0.015f ||
                    centerDistance(previous, candidate) <= MAX_TARGET_CENTER_DISTANCE
            }
        }

        if (selected == null) {
            missingFrames++
            if (missingFrames >= LOST_FRAMES_BEFORE_RESET) {
                lastBounds = null
                missingFrames = 0
                return null
            }
            return lastBounds?.toTarget()
        }

        missingFrames = 0
        val smoothed = if (previous == null || !selected.label.equals(previous.label, true)) {
            selected
        } else {
            Candidate(
                left = lerp(previous.left, selected.left, SMOOTHING_ALPHA),
                top = lerp(previous.top, selected.top, SMOOTHING_ALPHA),
                right = lerp(previous.right, selected.right, SMOOTHING_ALPHA),
                bottom = lerp(previous.bottom, selected.bottom, SMOOTHING_ALPHA),
                label = selected.label,
                confidence = selected.confidence,
            )
        }
        lastBounds = smoothed
        return smoothed.toTarget()
    }

    private fun handleError(error: RuntimeException) {
        cleanupPendingInput()
        frameInFlight.set(false)
        Log.w(TAG, "Object detector inference failed; skipping this frame", error)
    }

    private fun cleanupPendingInput() {
        synchronized(lock) {
            cleanupPendingInputLocked()
        }
    }

    private fun cleanupPendingInputLocked() {
        pendingImage?.close()
        pendingImage = null
        pendingBitmap?.let(::recycle)
        pendingBitmap = null
    }

    private fun dispatchTarget(target: Target?) {
        mainHandler.post {
            if (!released.get()) onTargetChanged?.invoke(target)
        }
    }

    override fun close() {
        if (!released.compareAndSet(false, true)) return
        generation.incrementAndGet()
        onTargetChanged = null
        try {
            executor.execute {
                cleanupPendingInput()
                runCatching { detector?.close() }
                detector = null
                frameInFlight.set(false)
            }
            executor.shutdown()
        } catch (_: RejectedExecutionException) {
            cleanupPendingInput()
            runCatching { detector?.close() }
            detector = null
            frameInFlight.set(false)
        }
    }

    private fun recycle(bitmap: Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
    }

    private fun intersectionOverUnion(a: Candidate, b: Candidate): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val intersection = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
        val union = a.area + b.area - intersection
        return if (union > 0f) intersection / union else 0f
    }

    private fun centerDistance(a: Candidate, b: Candidate): Float =
        hypot(a.centerX - b.centerX, a.centerY - b.centerY)

    private fun lerp(start: Float, end: Float, amount: Float): Float =
        start + (end - start) * amount
}
