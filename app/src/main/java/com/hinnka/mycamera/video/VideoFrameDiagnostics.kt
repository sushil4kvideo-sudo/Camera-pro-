package com.hinnka.mycamera.video

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Size
import com.hinnka.mycamera.BuildConfig
import com.hinnka.mycamera.utils.PLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Low-overhead EIS+ 4K/30 frame telemetry.
 *
 * Hot-path methods only update atomics. Disk I/O and report formatting happen once, after
 * the video encoder and muxer have drained. This avoids making diagnostics a source of jank.
 */
internal class VideoFrameDiagnostics(
    private val context: Context,
    private val requestedSize: Size,
    private val requestedFps: Int,
    private val requestedBitrateMbps: Int,
    private val requestedMime: String,
    private val lookaheadFrames: Int,
    private val stabilizationStrength: Float,
) {
    companion object {
        private const val TAG = "VideoFrameDiagnostics"
        private const val CODEC_CONFIG_FLAG = 2
        private const val NANOS_PER_SECOND = 1_000_000_000L
    }

    private val startedAtMs = System.currentTimeMillis()
    private val startedAtNs = SystemClock.elapsedRealtimeNanos()
    private val expectedIntervalNs = NANOS_PER_SECOND / requestedFps.coerceAtLeast(1)
    private val expectedIntervalUs = expectedIntervalNs / 1_000L
    private val finished = AtomicBoolean(false)

    private val renderWakeCallbacks = AtomicLong()
    private val renderWakeGapEvents = AtomicLong()
    private val renderWakeEstimatedMissing = AtomicLong()
    private val callbacksWhilePending = AtomicLong()
    private val pendingQueueDepthMax = AtomicLong()
    private val renderAttempts = AtomicLong()
    private val renderSuccesses = AtomicLong()
    private val renderNoFrame = AtomicLong()
    private val renderTotalNs = AtomicLong()
    private val renderMaxNs = AtomicLong()
    private val slowRenderFrames = AtomicLong()
    private val lastWakeNs = AtomicLong(0L)
    private val anomalyEvents = ArrayDeque<String>()

    private val encodedFrames = AtomicLong()
    private val encodedBytes = AtomicLong()
    private val encodedPtsGapEvents = AtomicLong()
    private val encodedPtsEstimatedMissing = AtomicLong()
    private val encodedPtsMaxGapUs = AtomicLong()
    private val lastEncodedPtsUs = AtomicLong(Long.MIN_VALUE)

    private val muxerWrites = AtomicLong()
    private val muxerWriteFailures = AtomicLong()
    private val muxerWriteTotalNs = AtomicLong()
    private val muxerWriteMaxNs = AtomicLong()

    @Volatile private var encoderName = "unavailable"
    @Volatile private var encoderMime = requestedMime
    @Volatile private var encoderHardware = false
    @Volatile private var encoderSize = requestedSize
    @Volatile private var encoderBitrate = 0
    @Volatile private var encoderProfile = "unavailable"
    @Volatile private var encoderColorPipeline = "unavailable"

    fun setEncoderInfo(
        name: String,
        mime: String,
        isHardwareAccelerated: Boolean,
        size: Size,
        bitrate: Int,
        profile: String,
        colorPipeline: String,
    ) {
        encoderName = name
        encoderMime = mime
        encoderHardware = isHardwareAccelerated
        encoderSize = size
        encoderBitrate = bitrate
        encoderProfile = profile
        encoderColorPipeline = colorPipeline
    }

    /** Records render-loop wake-up cadence; this is not a direct Camera2 sensor-frame counter. */
    fun recordRenderWakeCallback(timestampNs: Long) {
        renderWakeCallbacks.incrementAndGet()
        val previous = lastWakeNs.getAndSet(timestampNs)
        if (previous <= 0L || timestampNs <= previous) return
        val deltaNs = timestampNs - previous
        if (deltaNs > expectedIntervalNs * 3L / 2L) {
            renderWakeGapEvents.incrementAndGet()
            val estimate = (deltaNs / expectedIntervalNs) - 1L
            if (estimate > 0L) renderWakeEstimatedMissing.addAndGet(estimate)
            appendAnomaly("render_callback_gap_ms=" + String.format(Locale.US, "%.3f", deltaNs / 1_000_000.0))
        }
    }

    fun recordCallbackWhilePending() {
        callbacksWhilePending.incrementAndGet()
    }

    fun recordPendingQueueDepth(depth: Int) {
        updateMax(pendingQueueDepthMax, depth.coerceAtLeast(0).toLong())
    }

    fun recordRenderAttempt(success: Boolean, durationNs: Long) {
        renderAttempts.incrementAndGet()
        val safe = durationNs.coerceAtLeast(0L)
        renderTotalNs.addAndGet(safe)
        updateMax(renderMaxNs, safe)
        if (safe > expectedIntervalNs) {
            slowRenderFrames.incrementAndGet()
            appendAnomaly("render_over_30fps_budget_ms=" + String.format(Locale.US, "%.3f", safe / 1_000_000.0))
        }
        if (success) renderSuccesses.incrementAndGet() else renderNoFrame.incrementAndGet()
    }

    /** Call only for actual encoded video access units, excluding codec-config and zero-size buffers. */
    fun recordEncoderOutput(presentationTimeUs: Long, sizeBytes: Int, flags: Int) {
        if (sizeBytes <= 0 || flags and CODEC_CONFIG_FLAG != 0) return
        encodedFrames.incrementAndGet()
        encodedBytes.addAndGet(sizeBytes.toLong())
        val previous = lastEncodedPtsUs.getAndSet(presentationTimeUs)
        if (previous == Long.MIN_VALUE || presentationTimeUs <= previous) return
        val deltaUs = presentationTimeUs - previous
        if (deltaUs > expectedIntervalUs * 3L / 2L) {
            encodedPtsGapEvents.incrementAndGet()
            val estimate = (deltaUs / expectedIntervalUs) - 1L
            if (estimate > 0L) encodedPtsEstimatedMissing.addAndGet(estimate)
            updateMax(encodedPtsMaxGapUs, deltaUs)
            appendAnomaly(
                "encoded_pts_gap_ms=" + String.format(Locale.US, "%.3f", deltaUs / 1000.0) +
                    ",previousPtsUs=" + previous + ",currentPtsUs=" + presentationTimeUs
            )
        }
    }

    fun recordMuxerWrite(durationNs: Long, succeeded: Boolean) {
        val safe = durationNs.coerceAtLeast(0L)
        muxerWrites.incrementAndGet()
        muxerWriteTotalNs.addAndGet(safe)
        updateMax(muxerWriteMaxNs, safe)
        if (!succeeded) {
            muxerWriteFailures.incrementAndGet()
            appendAnomaly("muxer_write_failed")
        } else if (safe > expectedIntervalNs / 2L) {
            appendAnomaly("slow_muxer_write_ms=" + String.format(Locale.US, "%.3f", safe / 1_000_000.0))
        }
    }

    fun finish(eisSnapshot: String, rendererSnapshot: String): File? {
        if (!finished.compareAndSet(false, true)) return null

        val elapsedNs = (SystemClock.elapsedRealtimeNanos() - startedAtNs).coerceAtLeast(1L)
        val budgetMs = expectedIntervalNs / 1_000_000.0
        val averageRenderMs = averageMs(renderTotalNs.get(), renderAttempts.get())
        val maximumRenderMs = renderMaxNs.get() / 1_000_000.0
        val averageMuxerMs = averageMs(muxerWriteTotalNs.get(), muxerWrites.get())
        val maximumMuxerMs = muxerWriteMaxNs.get() / 1_000_000.0
        val encodedMissing = encodedPtsEstimatedMissing.get()
        val eisOverflow = metric(eisSnapshot, "imageQueueOverflow")
        val eisMissingMetadata = metric(eisSnapshot, "missingMetadata")
        val eisMissingImages = metric(eisSnapshot, "resultImageMissing")
        val eisNativeDrops = metric(eisSnapshot, "nativeDropped")
        val cameraCaptureMissing = metric(eisSnapshot, "captureEstimatedMissing")
        val imageReaderMissing = metric(eisSnapshot, "imageEstimatedMissing")
        val nativeAverageMs = metricDouble(eisSnapshot, "nativeProcessAverageMs")
        val eisImageAverageMs = metricDouble(rendererSnapshot, "eisImageRenderAverageMs")
        val eisImageMaxMs = metricDouble(rendererSnapshot, "eisImageRenderMaxMs")
        val eglSwapAverageMs = metricDouble(rendererSnapshot, "eglSwapAverageMs")
        val eglSwapMaxMs = metricDouble(rendererSnapshot, "eglSwapMaxMs")
        val renderOk = renderSuccesses.get()
        val encoded = encodedFrames.get()
        val diagnosis = mutableListOf<String>()

        if (eisOverflow > 0L || eisMissingMetadata > 0L || eisMissingImages > 0L) {
            diagnosis += "EIS+ feeder/image pairing lost frames: queueOverflow=" + eisOverflow +
                ", missingMetadata=" + eisMissingMetadata + ", resultImageMissing=" + eisMissingImages +
                ". Inspect look-ahead depth, ImageReader pressure and timestamp matching."
        }
        if (cameraCaptureMissing > 0L) {
            diagnosis += "Camera2 CaptureResult SENSOR_TIMESTAMP cadence has about " + cameraCaptureMissing +
                " missing 30-fps intervals. This points upstream (sensor/HAL cadence, exposure limits or thermal throttling)."
        }
        if (imageReaderMissing > cameraCaptureMissing) {
            diagnosis += "EIS ImageReader timestamps have about " + imageReaderMissing +
                " missing intervals versus " + cameraCaptureMissing +
                " in CaptureResult. ImageReader delivery/back-pressure or buffer acquisition is more likely than sensor cadence."
        }
        if (eisNativeDrops > 0L) {
            diagnosis += "Native EIS reported " + eisNativeDrops +
                " dropped-pose results. This can be a pose/gyro/metadata coverage issue; inspect feeder lag and pose readiness."
        }
        if (nativeAverageMs > budgetMs * 0.5) {
            diagnosis += "Native EIS processFrame average is " +
                String.format(Locale.US, "%.2f", nativeAverageMs) +
                " ms. Native pose computation is consuming a large part of the 30-fps budget."
        }
        if (eisImageAverageMs > budgetMs * 0.25 || eisImageMaxMs > budgetMs * 0.75) {
            diagnosis += "EIS image conversion/render is expensive (avg=" +
                String.format(Locale.US, "%.2f", eisImageAverageMs) + " ms, max=" +
                String.format(Locale.US, "%.2f", eisImageMaxMs) +
                "). Compare HardwareBuffer and YUV fallback counts; fence waits or CPU YUV upload may be bottlenecks."
        }
        if (eglSwapAverageMs > budgetMs * 0.25 || eglSwapMaxMs > budgetMs * 0.75) {
            diagnosis += "EGL encoder-surface swap is taking too long (avg=" +
                String.format(Locale.US, "%.2f", eglSwapAverageMs) + " ms, max=" +
                String.format(Locale.US, "%.2f", eglSwapMaxMs) +
                "). This often indicates MediaCodec input back-pressure / encoder throughput limits."
        }
        if (renderWakeEstimatedMissing.get() > 0L) {
            diagnosis += "Render-loop callback cadence contains about " +
                renderWakeEstimatedMissing.get() +
                " missing 30-fps intervals. This is a scheduling signal, not proof of Camera2 sensor drops."
        }
        if (averageRenderMs > budgetMs * 0.75 || maximumRenderMs > budgetMs * 1.5) {
            diagnosis += "Render path consumed too much of the " +
                String.format(Locale.US, "%.2f", budgetMs) +
                " ms frame budget. Check GPU shader/LUT/grain work and EIS HardwareBuffer fence/YUV upload timings."
        }
        if (encodedMissing > 0L) {
            diagnosis += "Encoded PTS contains about " + encodedMissing +
                " missing frame intervals. Compare successful render count with encoded count; loss is at or after EGL/encoder input."
        }
        if (encoded < renderOk && renderOk > 0L) {
            diagnosis += "Successful EGL renders (" + renderOk + ") exceed encoded access units (" + encoded +
                "). Check MediaCodec input-surface/encoder throughput or output drain."
        }
        if (averageMuxerMs > budgetMs * 0.25 || maximumMuxerMs > budgetMs) {
            diagnosis += "Muxer write latency is high (avg=" +
                String.format(Locale.US, "%.2f", averageMuxerMs) + " ms, max=" +
                String.format(Locale.US, "%.2f", maximumMuxerMs) +
                "). Storage throughput or filesystem contention may be stalling the pipeline."
        }
        if (muxerWriteFailures.get() > 0L) {
            diagnosis += "Muxer write failures: " + muxerWriteFailures.get() + ". Inspect VideoRecorder errors."
        }
        if (diagnosis.isEmpty()) {
            diagnosis += "No single bottleneck was proven by these counters. Compare EIS, render and encoded PTS totals with the raw recording log."
        }

        val report = buildString {
            appendLine("PHOTON CAMERA — EIS+ VIDEO FRAME DIAGNOSTICS")
            appendLine("schemaVersion=1")
            appendLine("recordingStarted=" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(startedAtMs)))
            appendLine("elapsedWallSeconds=" + String.format(Locale.US, "%.3f", elapsedNs / 1_000_000_000.0))
            appendLine()
            val memoryInfo = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.getMemoryInfo(memoryInfo)
            val thermalStatus = runCatching {
                (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                    ?.currentThermalStatus
                    ?.toString() ?: "unavailable"
            }.getOrDefault("unavailable")
            val runtime = Runtime.getRuntime()
            val usedHeapBytes = runtime.totalMemory() - runtime.freeMemory()

            appendLine("[DEVICE]")
            appendLine("manufacturer=" + Build.MANUFACTURER)
            appendLine("model=" + Build.MODEL)
            appendLine("hardware=" + Build.HARDWARE)
            appendLine("android=" + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")")
            appendLine("app=" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
            appendLine("systemAvailableMemoryMiB=" + memoryInfo.availMem / (1024L * 1024L))
            appendLine("systemLowMemory=" + memoryInfo.lowMemory)
            appendLine("appHeapUsedMiB=" + usedHeapBytes / (1024L * 1024L))
            appendLine("appHeapMaxMiB=" + runtime.maxMemory() / (1024L * 1024L))
            appendLine("thermalStatusAtStop=" + thermalStatus)
            appendLine()
            appendLine("[RECORDING]")
            appendLine("requestedSize=" + requestedSize.width + "x" + requestedSize.height)
            appendLine("actualEncoderSize=" + encoderSize.width + "x" + encoderSize.height)
            appendLine("requestedFps=" + requestedFps)
            appendLine("expectedFrameIntervalMs=" + String.format(Locale.US, "%.3f", budgetMs))
            appendLine("requestedBitrateMbps=" + requestedBitrateMbps)
            appendLine("actualBitrateBps=" + encoderBitrate)
            appendLine("requestedMime=" + requestedMime)
            appendLine("actualMime=" + encoderMime)
            appendLine("encoder=" + encoderName)
            appendLine("hardwareAccelerated=" + encoderHardware)
            appendLine("codecProfile=" + encoderProfile)
            appendLine("colorPipeline=" + encoderColorPipeline)
            appendLine("eisPlus=true")
            appendLine("lookaheadFrames=" + lookaheadFrames)
            appendLine("stabilizationStrength=" + stabilizationStrength)
            appendLine()
            appendLine("[RENDER_SCHEDULER]")
            appendLine("renderWakeCallbacks=" + renderWakeCallbacks.get())
            appendLine("renderWakeGapEvents=" + renderWakeGapEvents.get())
            appendLine("renderWakeEstimatedMissing=" + renderWakeEstimatedMissing.get())
            appendLine("callbacksArrivingWithPendingWork=" + callbacksWhilePending.get())
            appendLine("pendingQueueDepthMax=" + pendingQueueDepthMax.get())
            appendLine("renderAttempts=" + renderAttempts.get())
            appendLine("renderSuccesses=" + renderOk)
            appendLine("renderNoFrame=" + renderNoFrame.get())
            appendLine("renderAverageMs=" + String.format(Locale.US, "%.3f", averageRenderMs))
            appendLine("renderMaxMs=" + String.format(Locale.US, "%.3f", maximumRenderMs))
            appendLine("renderFramesOver33ms=" + slowRenderFrames.get())
            appendLine()
            appendLine("[MEDIACODEC_OUTPUT]")
            appendLine("encodedFrames=" + encoded)
            appendLine("encodedMiB=" + String.format(Locale.US, "%.3f", encodedBytes.get() / (1024.0 * 1024.0)))
            appendLine("encodedPtsGapEvents=" + encodedPtsGapEvents.get())
            appendLine("encodedPtsEstimatedMissing=" + encodedMissing)
            appendLine("encodedPtsMaxGapMs=" + String.format(Locale.US, "%.3f", encodedPtsMaxGapUs.get() / 1000.0))
            appendLine()
            appendLine("[MUXER]")
            appendLine("writes=" + muxerWrites.get())
            appendLine("writeFailures=" + muxerWriteFailures.get())
            appendLine("writeAverageMs=" + String.format(Locale.US, "%.3f", averageMuxerMs))
            appendLine("writeMaxMs=" + String.format(Locale.US, "%.3f", maximumMuxerMs))
            appendLine()
            appendLine("[EIS_PLUS_FEEDER]")
            appendLine(eisSnapshot.ifBlank { "unavailable" })
            appendLine()
            appendLine("[REALTIME_RENDERER]")
            appendLine(rendererSnapshot.ifBlank { "unavailable" })
            appendLine()
            appendLine("[AUTOMATED_TRIAGE]")
            diagnosis.forEachIndexed { index, item -> appendLine((index + 1).toString() + ". " + item) }
            appendLine()
            appendLine("[ANOMALY_EVENTS — most recent 100]")
            synchronized(anomalyEvents) {
                anomalyEvents.forEach(::appendLine)
            }
            appendLine()
            appendLine("Important: timestamp-gap missing-frame values are estimates. They are not exact frame IDs.")
            appendLine("Wall-clock duration includes pauses; use encoded PTS and the stage counters for frame-rate conclusions.")
        }

        return runCatching {
            val root = context.getExternalFilesDir(null) ?: context.filesDir
            val directory = File(root, "video-diagnostics").apply { mkdirs() }
            val file = File(directory, "eis-plus-4k30-" + startedAtMs + ".txt")
            file.writeText(report, Charsets.UTF_8)
            PLog.i(TAG, "Saved EIS+ frame diagnostics: " + file.absolutePath + "\n" + report)
            file
        }.onFailure {
            PLog.e(TAG, "Could not save EIS+ frame diagnostics report", it)
            PLog.i(TAG, report)
        }.getOrNull()
    }

    private fun appendAnomaly(message: String) {
        val elapsedMs = ((SystemClock.elapsedRealtimeNanos() - startedAtNs).coerceAtLeast(0L)) / 1_000_000L
        synchronized(anomalyEvents) {
            if (anomalyEvents.size >= 100) anomalyEvents.removeFirst()
            anomalyEvents.addLast("t+" + elapsedMs + "ms " + message)
        }
    }

    private fun metric(snapshot: String, key: String): Long =
        snapshot.lineSequence()
            .firstOrNull { it.startsWith(key + "=") }
            ?.substringAfter("=")
            ?.toLongOrNull() ?: 0L

    private fun metricDouble(snapshot: String, key: String): Double =
        snapshot.lineSequence()
            .firstOrNull { it.startsWith(key + "=") }
            ?.substringAfter("=")
            ?.toDoubleOrNull() ?: 0.0

    private fun averageMs(totalNs: Long, count: Long): Double =
        if (count <= 0L) 0.0 else totalNs.toDouble() / count / 1_000_000.0

    private fun updateMax(target: AtomicLong, value: Long) {
        while (true) {
            val current = target.get()
            if (value <= current || target.compareAndSet(current, value)) return
        }
    }
}
