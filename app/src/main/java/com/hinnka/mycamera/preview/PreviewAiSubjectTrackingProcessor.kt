package com.hinnka.mycamera.preview

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import com.hinnka.mycamera.camera.AiSubjectTrackingMode
import com.hinnka.mycamera.camera.AiSubjectTrackingTarget
import com.hinnka.mycamera.utils.PLog
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot

/**
 * Live-stream MediaPipe object detector for AI subject tracking.
 *
 * A single inference is in flight at a time. The processor owns the supplied bitmap,
 * keeps the MPImage alive until MediaPipe returns its result, and drops stale frames
 * rather than building an inference queue behind the camera preview.
 */
class PreviewAiSubjectTrackingProcessor(context: Context) : Closeable {
    private data class Candidate(
        val target: AiSubjectTrackingTarget,
        val area: Float,
    )

    companion object {
        private const val TAG = "AiSubjectTracking"
        private const val MODEL_ASSET = "efficientdet_lite0_int8.tflite"
        private const val SCORE_THRESHOLD = 0.38f
        private const val MAX_RESULTS = 12
        private const val MIN_BOX_SIZE = 0.012f
        private const val TARGET_HOLD_DURATION_MS = 650L
        private const val MAX_CONTINUITY_DISTANCE = 0.48f
    }

    private val appContext = context.applicationContext
    private val inferenceExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MediaPipeAiSubjectTracking").apply {
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private val stateLock = Any()
    private val frameInFlight = AtomicBoolean(false)
    private val released = AtomicBoolean(false)

    @Volatile
    private var mode = AiSubjectTrackingMode.HUMAN

    private var detector: ObjectDetector? = null
    private var detectorInitializationFailed = false
    private var lastTimestampMs = 0L
    private var pendingImage: MPImage? = null
    private var pendingBitmap: Bitmap? = null
    private var sampleStartedAtNanos = 0L
    private var captureDurationNanos = 0L
    private var processingStartedAtNanos = 0L
    private var latestDetections: List<Candidate> = emptyList()
    private var lastTarget: AiSubjectTrackingTarget? = null
    private var lastTargetAtMs = 0L
    private var lockedCategory: String? = null
    private var lockedTarget = false

    var onTargetUpdated: ((AiSubjectTrackingTarget?) -> Unit)? = null
    var onBusyStateChanged: ((Boolean) -> Unit)? = null
    var onFrameProcessed: ((EyeFocusProcessingTiming) -> Unit)? = null
    var onInitializationError: ((Throwable) -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null

    fun setMode(newMode: AiSubjectTrackingMode) {
        val changed = mode != newMode
        mode = newMode
        if (changed) resetTracking()
    }

    fun prewarm() {
        if (released.get() || detectorInitializationFailed) return
        try {
            inferenceExecutor.execute {
                if (!released.get()) getOrCreateDetector()
            }
        } catch (_: RejectedExecutionException) {
            // The processor is already shutting down.
        }
    }

    /** Takes ownership of frame.bitmap. Returns false when the frame was dropped. */
    fun processFrame(frame: EyeFocusPreviewFrame): Boolean {
        if (
            released.get() ||
            detectorInitializationFailed ||
            !frameInFlight.compareAndSet(false, true)
        ) {
            frame.bitmap.recycleIfAlive()
            return false
        }

        sampleStartedAtNanos = frame.sampleStartedAtNanos
        captureDurationNanos = frame.captureDurationNanos
        processingStartedAtNanos = SystemClock.elapsedRealtimeNanos()
        onBusyStateChanged?.invoke(true)
        try {
            inferenceExecutor.execute {
                submitBitmap(frame.bitmap)
            }
            return true
        } catch (_: RejectedExecutionException) {
            frame.bitmap.recycleIfAlive()
            finishFrame()
            return false
        }
    }

    /** Tap-to-lock: the nearest current detection is selected and followed across frames. */
    fun selectTargetAt(normalizedX: Float, normalizedY: Float): Boolean {
        if (
            !normalizedX.isFinite() || !normalizedY.isFinite() ||
            normalizedX !in 0f..1f || normalizedY !in 0f..1f
        ) return false

        val selected = synchronized(stateLock) {
            val candidates = if (mode == AiSubjectTrackingMode.HUMAN) {
                latestDetections.filter { it.target.category.equals("person", ignoreCase = true) }
            } else {
                latestDetections
            }
            val containing = candidates.filter { candidate ->
                val box = candidate.target
                normalizedX in box.left..box.right && normalizedY in box.top..box.bottom
            }
            val chosen = (containing.maxByOrNull { it.target.confidence }
                ?: candidates.minByOrNull { candidate ->
                    distance(
                        candidate.target.centerX,
                        candidate.target.centerY,
                        normalizedX,
                        normalizedY,
                    )
                }?.takeIf { candidate ->
                    distance(
                        candidate.target.centerX,
                        candidate.target.centerY,
                        normalizedX,
                        normalizedY,
                    ) <= 0.22f
                }) ?: return@synchronized null

            lockedCategory = chosen.target.category
            lockedTarget = true
            lastTarget = chosen.target.copy(isFresh = true, isLocked = true)
            lastTargetAtMs = SystemClock.elapsedRealtime()
            lastTarget
        }
        selected?.let { onTargetUpdated?.invoke(it) }
        return selected != null
    }

    fun resetTracking() {
        val notify = synchronized(stateLock) {
            latestDetections = emptyList()
            lastTarget = null
            lastTargetAtMs = 0L
            lockedCategory = null
            lockedTarget = false
            true
        }
        if (notify) onTargetUpdated?.invoke(null)
    }

    private fun submitBitmap(bitmap: Bitmap) {
        if (released.get()) {
            bitmap.recycleIfAlive()
            finishFrame()
            return
        }

        var preparedBitmap: Bitmap? = null
        var image: MPImage? = null
        try {
            preparedBitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) {
                bitmap
            } else {
                val converted = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    ?: throw IllegalStateException("Unable to convert AI tracking input bitmap")
                bitmap.recycleIfAlive()
                converted
            }
            image = BitmapImageBuilder(preparedBitmap).build()
        } catch (error: RuntimeException) {
            image?.close()
            preparedBitmap?.recycleIfAlive()
            bitmap.recycleIfAlive()
            onError?.invoke(error)
            finishFrame()
            return
        }

        val inputBitmap = checkNotNull(preparedBitmap)
        val inputImage = checkNotNull(image)
        val objectDetector = getOrCreateDetector()
        if (objectDetector == null) {
            inputImage.close()
            inputBitmap.recycleIfAlive()
            finishFrame()
            return
        }

        val timestampMs = synchronized(stateLock) {
            val now = SystemClock.uptimeMillis()
            lastTimestampMs = maxOf(now, lastTimestampMs + 1L)
            pendingImage = inputImage
            pendingBitmap = inputBitmap
            lastTimestampMs
        }
        try {
            objectDetector.detectAsync(inputImage, timestampMs)
        } catch (error: RuntimeException) {
            cleanupPendingInput()
            onError?.invoke(error)
            finishFrame()
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
                .setScoreThreshold(SCORE_THRESHOLD)
                .setMaxResults(MAX_RESULTS)
                .setResultListener(::handleResult)
                .setErrorListener(::handleError)
                .build()
            ObjectDetector.createFromOptions(appContext, options).also {
                detector = it
                PLog.i(TAG, "EfficientDet-Lite0 object detector initialized")
            }
        } catch (error: RuntimeException) {
            detectorInitializationFailed = true
            PLog.e(TAG, "Failed to initialize AI subject detector", error)
            onInitializationError?.invoke(error)
            null
        }
    }

    private fun handleResult(result: ObjectDetectorResult, input: MPImage) {
        val inputWidth = input.width
        val inputHeight = input.height
        val detections = if (inputWidth > 0 && inputHeight > 0) {
            result.detections().mapNotNull { detection ->
                val category = detection.categories().maxByOrNull { it.score() }
                    ?: return@mapNotNull null
                val name = category.categoryName()?.trim().orEmpty()
                val score = category.score()
                val rect = detection.boundingBox()
                val left = (rect.left / inputWidth).coerceIn(0f, 1f)
                val top = (rect.top / inputHeight).coerceIn(0f, 1f)
                val right = (rect.right / inputWidth).coerceIn(0f, 1f)
                val bottom = (rect.bottom / inputHeight).coerceIn(0f, 1f)
                if (
                    name.isBlank() || !score.isFinite() || score < SCORE_THRESHOLD ||
                    right - left < MIN_BOX_SIZE || bottom - top < MIN_BOX_SIZE
                ) return@mapNotNull null
                Candidate(
                    target = AiSubjectTrackingTarget(
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom,
                        category = name,
                        confidence = score.coerceIn(0f, 1f),
                        isLocked = false,
                    ),
                    area = (right - left) * (bottom - top),
                )
            }
        } else {
            emptyList()
        }

        val now = SystemClock.elapsedRealtime()
        val target = synchronized(stateLock) {
            latestDetections = detections
            val eligible = if (mode == AiSubjectTrackingMode.HUMAN) {
                detections.filter { it.target.category.equals("person", ignoreCase = true) }
            } else {
                detections
            }
            val preferred = lockedCategory?.let { locked ->
                eligible.filter { it.target.category.equals(locked, ignoreCase = true) }
            } ?: eligible

            val previous = lastTarget
            val chosen = if (preferred.isEmpty()) {
                null
            } else if (previous != null) {
                val nearest = preferred.minByOrNull { candidate ->
                    distance(
                        candidate.target.centerX,
                        candidate.target.centerY,
                        previous.centerX,
                        previous.centerY,
                    ) - candidate.target.confidence * 0.035f
                }
                nearest?.takeIf { candidate ->
                    distance(
                        candidate.target.centerX,
                        candidate.target.centerY,
                        previous.centerX,
                        previous.centerY,
                    ) <= MAX_CONTINUITY_DISTANCE
                } ?: if (lockedTarget) null else chooseInitialCandidate(preferred)
            } else if (lockedTarget) {
                // Once a manually selected subject has fully disappeared, do not jump
                // to another object of the same class. The user can tap a new target.
                null
            } else {
                chooseInitialCandidate(preferred)
            }

            if (chosen != null) {
                lastTargetAtMs = now
                chosen.target.copy(isFresh = true, isLocked = lockedTarget).also {
                    lastTarget = it
                }
            } else {
                val held = lastTarget?.takeIf {
                    now - lastTargetAtMs <= TARGET_HOLD_DURATION_MS
                }?.copy(isFresh = false, isLocked = lockedTarget)
                if (held == null && now - lastTargetAtMs > TARGET_HOLD_DURATION_MS) {
                    lastTarget = null
                    if (!lockedTarget) lockedCategory = null
                }
                held
            }
        }

        input.close()
        synchronized(stateLock) {
            pendingImage = null
            pendingBitmap?.recycleIfAlive()
            pendingBitmap = null
        }
        finishFrame()
        onTargetUpdated?.invoke(target)
    }

    private fun chooseInitialCandidate(candidates: List<Candidate>): Candidate? =
        candidates.maxByOrNull { candidate ->
            val centerDistance = distance(
                candidate.target.centerX,
                candidate.target.centerY,
                0.5f,
                0.5f,
            )
            val centerWeight = (1f - centerDistance / 0.7072f).coerceIn(0f, 1f)
            candidate.target.confidence * 0.72f + centerWeight * 0.22f +
                candidate.area.coerceIn(0f, 0.5f) * 0.06f
        }

    private fun handleError(error: RuntimeException) {
        cleanupPendingInput()
        PLog.e(TAG, "AI subject detection failed", error)
        onError?.invoke(error)
        finishFrame()
    }

    private fun cleanupPendingInput() {
        synchronized(stateLock) {
            pendingImage?.close()
            pendingImage = null
            pendingBitmap?.recycleIfAlive()
            pendingBitmap = null
        }
    }

    private fun finishFrame() {
        val completedAtNanos = SystemClock.elapsedRealtimeNanos()
        val sampleStarted = sampleStartedAtNanos
        val processingStarted = processingStartedAtNanos
        val captured = captureDurationNanos
        sampleStartedAtNanos = 0L
        processingStartedAtNanos = 0L
        captureDurationNanos = 0L
        frameInFlight.set(false)
        if (sampleStarted > 0L && processingStarted > 0L) {
            onFrameProcessed?.invoke(
                EyeFocusProcessingTiming(
                    captureDurationNanos = captured.coerceAtLeast(0L),
                    processingDurationNanos = (completedAtNanos - processingStarted).coerceAtLeast(0L),
                    endToEndDurationNanos = (completedAtNanos - sampleStarted).coerceAtLeast(0L),
                )
            )
        }
        onBusyStateChanged?.invoke(false)
    }

    override fun close() {
        if (!released.compareAndSet(false, true)) return
        try {
            inferenceExecutor.execute {
                detector?.close()
                detector = null
                cleanupPendingInput()
                frameInFlight.set(false)
            }
        } catch (_: RejectedExecutionException) {
            detector?.close()
            detector = null
            cleanupPendingInput()
            frameInFlight.set(false)
        } finally {
            inferenceExecutor.shutdown()
        }
    }

    private fun Bitmap.recycleIfAlive() {
        if (!isRecycled) recycle()
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        hypot(x1 - x2, y1 - y2)
}
