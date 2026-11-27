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
        private const val MIN_SCORE = 0.32f
        private const val MAX_RESULTS = 20
        private const val LOST_FRAMES_BEFORE_RESET = 18
        private const val SMOOTHING_ALPHA = 0.55f
        private const val VISUAL_TRACK_INTERVAL_MS = 42L
        private const val DETECTION_INTERVAL_MS = 320L
        private const val MANUAL_DETECTION_MATCH_RADIUS = 0.24f
        private const val MAX_TARGET_CENTER_DISTANCE = 0.58f
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
    private val visualFrameInFlight = AtomicBoolean(false)
    private val generation = AtomicInteger(0)
    private val patchTracker = PreviewPatchTracker()
    @Volatile private var lastVisualFrameAtMs = 0L
    @Volatile private var lastDetectionFrameAtMs = 0L
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
    private var velocityX = 0f
    private var velocityY = 0f
    private var missingFrames = 0
    private var pendingVisualSeed: Target? = null
    private var manualSelectionActive = false

    /**
     * Consumes [frame.bitmap]. Busy frames are dropped immediately so inference never
     * queues stale images behind the current frame.
     */
    /**
     * The viewfinder stream is split into two lightweight paths:
     * - visual patch tracking runs continuously on fresh frames (throttled to 24 fps);
     * - MediaPipe runs less often and is used only for first acquisition/reacquisition.
     *
     * The incoming bitmap is consumed here. A second bitmap is made only for the slower
     * detector path, so detector latency never blocks visual tracking.
     */
    fun processFrame(frame: EyeFocusPreviewFrame) {
        val bitmap = frame.bitmap
        if (released.get()) {
            recycle(bitmap)
            return
        }

        val now = SystemClock.uptimeMillis()
        var detectorBitmap: Bitmap? = null
        if (
            !detectorInitializationFailed &&
            now - lastDetectionFrameAtMs >= DETECTION_INTERVAL_MS &&
            frameInFlight.compareAndSet(false, true)
        ) {
            detectorBitmap = runCatching {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            }.getOrNull()
            if (detectorBitmap != null) {
                lastDetectionFrameAtMs = now
            } else {
                frameInFlight.set(false)
            }
        }

        val shouldTrack = now - lastVisualFrameAtMs >= VISUAL_TRACK_INTERVAL_MS &&
            visualFrameInFlight.compareAndSet(false, true)
        if (shouldTrack) {
            lastVisualFrameAtMs = now
            val frameGeneration = generation.get()
            try {
                executor.execute {
                    try {
                        updateVisualTracking(bitmap, frameGeneration)
                    } catch (error: RuntimeException) {
                        Log.w(TAG, "Visual subject tracking failed for this frame", error)
                    } finally {
                        recycle(bitmap)
                        visualFrameInFlight.set(false)
                    }
                }
            } catch (_: RejectedExecutionException) {
                recycle(bitmap)
                visualFrameInFlight.set(false)
            }
        } else {
            recycle(bitmap)
        }

        detectorBitmap?.let { detectionInput ->
            val frameGeneration = generation.get()
            try {
                executor.execute {
                    submitFrame(detectionInput, frameGeneration)
                }
            } catch (_: RejectedExecutionException) {
                recycle(detectionInput)
                frameInFlight.set(false)
            }
        }
    }

    /**
     * Selects the visual region under a viewfinder tap. This path works even when the
     * object detector cannot classify the item (or cannot detect it at all).
     */
    fun selectTargetAt(normalizedX: Float, normalizedY: Float) {
        if (released.get() || !normalizedX.isFinite() || !normalizedY.isFinite()) return
        val centerX = normalizedX.coerceIn(0.02f, 0.98f)
        val centerY = normalizedY.coerceIn(0.02f, 0.98f)
        val width = 0.32f
        val height = 0.36f
        val left = (centerX - width * 0.5f).coerceIn(0f, 1f - width)
        val top = (centerY - height * 0.5f).coerceIn(0f, 1f - height)
        val selected = Target(
            left = left,
            top = top,
            right = left + width,
            bottom = top + height,
            label = "Selected target",
            confidence = 1f,
        )
        synchronized(lock) {
            manualSelectionActive = true
            lastBounds = Candidate(
                left = selected.left,
                top = selected.top,
                right = selected.right,
                bottom = selected.bottom,
                label = selected.label,
                confidence = selected.confidence,
            )
            pendingVisualSeed = selected
            velocityX = 0f
            velocityY = 0f
            missingFrames = 0
        }
        dispatchTarget(selected)
    }

    fun resetTracking() {
        generation.incrementAndGet()
        synchronized(lock) {
            lastBounds = null
            pendingVisualSeed = null
            manualSelectionActive = false
            velocityX = 0f
            velocityY = 0f
            missingFrames = 0
            patchTracker.reset()
        }
        dispatchTarget(null)
    }

    private fun updateVisualTracking(bitmap: Bitmap, frameGeneration: Int) {
        val nextTarget = synchronized(lock) {
            if (released.get() || frameGeneration != generation.get()) {
                return@synchronized null
            }
            val seed = pendingVisualSeed
            if (seed != null) {
                pendingVisualSeed = null
                patchTracker.initialize(bitmap, seed)
                seed
            } else {
                val fallback = lastBounds?.toTarget() ?: return@synchronized null
                val tracked = patchTracker.track(bitmap, fallback)
                if (tracked == null) {
                    lastBounds = null
                    manualSelectionActive = false
                } else {
                    lastBounds = Candidate(
                        left = tracked.left,
                        top = tracked.top,
                        right = tracked.right,
                        bottom = tracked.bottom,
                        label = tracked.label,
                        confidence = tracked.confidence,
                    )
                }
                tracked
            }
        }
        if (!released.get() && frameGeneration == generation.get()) {
            dispatchTarget(nextTarget)
        }
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
            image = BitmapImageBuilder(checkNotNull(preparedBitmap)).build()
        } catch (error: RuntimeException) {
            image?.close()
            recycle(preparedBitmap)
            if (preparedBitmap !== sourceBitmap) recycle(sourceBitmap)
            frameInFlight.set(false)
            Log.w(TAG, "Unable to prepare tracking frame", error)
            return
        }

        val submittedBitmap = checkNotNull(preparedBitmap)
        val submittedImage = checkNotNull(image)
        val activeDetector = getOrCreateDetector()
        if (activeDetector == null || released.get()) {
            submittedImage.close()
            recycle(submittedBitmap)
            frameInFlight.set(false)
            return
        }

        val timestampMs = synchronized(lock) {
            lastTimestampMs = maxOf(SystemClock.uptimeMillis(), lastTimestampMs + 1L)
            pendingImage = submittedImage
            pendingBitmap = submittedBitmap
            pendingGeneration = frameGeneration
            lastTimestampMs
        }

        try {
            activeDetector.detectAsync(submittedImage, timestampMs)
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
                // Category names are deliberately not part of target identity. The same
            // physical object must remain tracked even when the detector changes labels.
            label = "Subject",
                confidence = confidence.coerceIn(0f, 1f),
            )
        }
    }

    private fun selectTargetLocked(candidates: List<Candidate>): Target? {
        val previous = lastBounds
        val selected = when {
            previous == null -> {
                // Automatic acquisition still works when the user has not tapped a target.
                candidates.maxByOrNull { candidate ->
                    val centrePenalty = centerDistanceToViewfinderCentre(candidate)
                    candidate.confidence *
                        (0.45f + sqrt(candidate.area.coerceAtLeast(0f))) *
                        (1f - (centrePenalty * 0.30f).coerceIn(0f, 0.30f))
                }
            }
            manualSelectionActive -> {
                // A tap locks the visual region first. If the detector sees an object nearby,
                // use its tighter box; otherwise keep tracking the tapped region without
                // requiring a category match.
                val nearest = candidates.minByOrNull { centerDistance(previous, it) }
                val match = nearest?.takeIf {
                    centerDistance(previous, it) <= MANUAL_DETECTION_MATCH_RADIUS
                }
                if (match == null) {
                    missingFrames = 0
                    return previous.toTarget()
                }
                manualSelectionActive = false
                match
            }
            else -> {
                // The visual tracker updates lastBounds between detector results, so this
                // association gate stays around the moving object rather than a stale box.
                val predictionFrames = 1f + missingFrames.coerceAtMost(5) * 0.65f
                val predicted = previous.translated(
                    velocityX * predictionFrames,
                    velocityY * predictionFrames,
                )
                val maxDistance = (MAX_TARGET_CENTER_DISTANCE + missingFrames * 0.025f)
                    .coerceAtMost(0.82f)
                candidates.maxByOrNull { candidate ->
                    val overlap = intersectionOverUnion(predicted, candidate)
                    val distance = centerDistance(predicted, candidate)
                    val motionSimilarity = 1f - (distance / maxDistance).coerceIn(0f, 1f)
                    val sizeSimilarity = minOf(predicted.area, candidate.area) /
                        maxOf(predicted.area, candidate.area, 1e-4f)
                    overlap * 2.4f +
                        motionSimilarity * 2.1f +
                        sizeSimilarity * 0.35f +
                        candidate.confidence * 0.15f
                }?.takeIf { candidate ->
                    intersectionOverUnion(predicted, candidate) >= 0.001f ||
                        centerDistance(predicted, candidate) <= maxDistance
                }
            }
        }

        if (selected == null) {
            missingFrames++
            if (missingFrames >= LOST_FRAMES_BEFORE_RESET) {
                lastBounds = null
                pendingVisualSeed = null
                velocityX = 0f
                velocityY = 0f
                missingFrames = 0
                return null
            }

            // Brief detector dropouts do not remove the current visual lock.
            velocityX *= 0.82f
            velocityY *= 0.82f
            val predicted = previous?.translated(velocityX, velocityY)
            lastBounds = predicted
            return predicted?.toTarget()
        }

        missingFrames = 0
        if (previous == null) {
            velocityX = 0f
            velocityY = 0f
        } else {
            val observedX = selected.centerX - previous.centerX
            val observedY = selected.centerY - previous.centerY
            velocityX = (velocityX * 0.35f + observedX * 0.65f).coerceIn(-0.20f, 0.20f)
            velocityY = (velocityY * 0.35f + observedY * 0.65f).coerceIn(-0.20f, 0.20f)
        }

        val movement = previous?.let { centerDistance(it, selected) } ?: 0f
        val smoothing = if (movement > 0.075f) 0.68f else SMOOTHING_ALPHA
        val smoothed = if (previous == null) {
            selected
        } else {
            Candidate(
                left = lerp(previous.left, selected.left, smoothing),
                top = lerp(previous.top, selected.top, smoothing),
                right = lerp(previous.right, selected.right, smoothing),
                bottom = lerp(previous.bottom, selected.bottom, smoothing),
                label = selected.label,
                confidence = selected.confidence,
            )
        }
        lastBounds = smoothed
        pendingVisualSeed = smoothed.toTarget()
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

    private fun centerDistanceToViewfinderCentre(candidate: Candidate): Float =
        hypot(candidate.centerX - 0.5f, candidate.centerY - 0.5f)

    private fun Candidate.translated(dx: Float, dy: Float): Candidate {
        val width = (right - left).coerceIn(0.015f, 1f)
        val height = (bottom - top).coerceIn(0.015f, 1f)
        val centerX = (this.centerX + dx).coerceIn(width * 0.5f, 1f - width * 0.5f)
        val centerY = (this.centerY + dy).coerceIn(height * 0.5f, 1f - height * 0.5f)
        return copy(
            left = centerX - width * 0.5f,
            top = centerY - height * 0.5f,
            right = centerX + width * 0.5f,
            bottom = centerY + height * 0.5f,
        )
    }

    private fun lerp(start: Float, end: Float, amount: Float): Float =
        start + (end - start) * amount
}
