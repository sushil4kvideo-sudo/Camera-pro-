package com.hinnka.mycamera.video

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import java.io.File
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import androidx.core.app.ActivityCompat
import com.hinnka.mycamera.lut.RealtimeVideoRenderer
import com.hinnka.mycamera.video.DualCaptureLayout
import com.hinnka.mycamera.lut.VideoColorEffectLayer
import com.hinnka.mycamera.stabilization.DEFAULT_VIDEO_STABILIZATION_LOOKAHEAD
import com.hinnka.mycamera.stabilization.DEFAULT_VIDEO_STABILIZATION_STRENGTH
import com.hinnka.mycamera.stabilization.RealtimeStabilizationCoordinator
import com.hinnka.mycamera.stabilization.normalizeStabilizationLookahead
import com.hinnka.mycamera.stabilization.normalizeStabilizationStrength
import com.hinnka.mycamera.utils.MediaWatermark
import com.hinnka.mycamera.utils.PLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class VideoRecorder(
    private val context: Context,
    private val stabilizationCoordinator: RealtimeStabilizationCoordinator? = null,
) {

    companion object {
        private const val TAG = "VideoRecorder"
        private const val SPECIAL_TIMELINE_TAG = "SpecialVideoTimeline"

        private const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val AUDIO_SAMPLE_RATE = 48_000
        private const val AUDIO_MONO_CHANNEL_COUNT = 1
        private const val AUDIO_STEREO_CHANNEL_COUNT = 2
        private const val AUDIO_BYTES_PER_SAMPLE = 2
        private const val AUDIO_MONO_BITRATE = 96_000
        private const val AUDIO_STEREO_BITRATE = 192_000
        private const val I_FRAME_INTERVAL = 1
        private const val UNSET_TIMESTAMP = Long.MIN_VALUE
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val MICROS_PER_SECOND = 1_000_000L
        private const val SPECIAL_VIDEO_TAIL_WINDOW_US = 2_000_000L
        private const val SPECIAL_VIDEO_MIN_TAIL_BYTES = 4L * 1024L * 1024L
        private const val SPECIAL_VIDEO_MAX_TAIL_BYTES = 48L * 1024L * 1024L
    }

    private data class VideoEncoderCandidate(
        val codecInfo: MediaCodecInfo,
        val mimeType: String,
        val mimePriority: Int,
    )

    private data class AudioCaptureConfig(
        val channelMask: Int,
        val channelCount: Int,
        val bitrate: Int,
        val label: String
    )

    private data class PreparedAudioRecord(
        val recorder: AudioRecord,
        val config: AudioCaptureConfig
    )

    private data class EncodedSample(
        val data: ByteArray,
        val info: MediaCodec.BufferInfo
    )

    private data class PauseInterval(
        val startTimeUs: Long,
        val endTimeUs: Long,
    )

    private data class FrameRange(
        val startFrame: Int,
        val endFrameExclusive: Int,
    )

    private data class CameraInputCropUpdate(
        val zoomRatio: Float,
        val panX: Float,
        val panY: Float,
        val rollDegrees: Float,
        val fixedRollCover: Boolean,
    )

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val renderDispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val pendingCameraInputCrop = AtomicReference<CameraInputCropUpdate?>(null)
    private val cameraInputCropDispatchScheduled = AtomicBoolean(false)
    private val muxerLock = Any()
    private val timelineLock = Any()
    private val realtimeFrameLock = Any()
    private val videoCodecLock = Any()
    private val audioCodecLock = Any()

    @Volatile
    private var isRecording = false

    @Volatile
    private var isPaused = false

    @Volatile
    private var stopRequested = false

    @Volatile
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var inputSurface: Surface? = null
    private var renderer: RealtimeVideoRenderer? = null
    private var requestedBitrateMbps: Int = 30
    private var requestedApvQuality: ApvQuality = ApvQuality.HQ
    private var requestedCodecMime: String = MediaFormat.MIMETYPE_VIDEO_AVC
    private var requestedFallbackCodecMimes: List<String> = emptyList()
    private var preferredEncoderCodecName: String? = null
    private var requestedOrientationHintDegrees: Int = 0
    private var requestedFlipEncodedFrame: Boolean = false
    private var requestedRecordingPath: VideoRecordingPath = VideoRecordingPath.DCIM_PHOTON
    private var requestedRecordingTreeUri: String? = null
    private var preferredAudioInputId: String = VIDEO_AUDIO_INPUT_AUTO
    private var requestedColorConfig: VideoEncoderColorRequest = VideoEncoderColorRequest()
    @Volatile private var requestedCameraInputIsHlg10: Boolean = false
    private var preparedEncoderColorConfig: VideoEncoderColorConfig? = null
    private var requestedOutputSize = Size(1080, 1920)
    private var requestedCameraInputSize = Size(1920, 1080)
    private var requestedCameraInputZoomRatio: Float = 1f
    private var requestedCameraInputPanX: Float = 0f
    private var requestedCameraInputPanY: Float = 0f
    private var requestedCameraInputRollDegrees: Float = 0f
    private var requestedCameraInputFixedRollCover: Boolean = false
    private var requestedColorLayers: List<VideoColorEffectLayer> = emptyList()
    private var requestedEnhancedStabilization = false
    private var requestedEnhancedStabilizationStrength = DEFAULT_VIDEO_STABILIZATION_STRENGTH
    private var requestedEnhancedStabilizationLookahead = DEFAULT_VIDEO_STABILIZATION_LOOKAHEAD
    private var requestedDualCaptureInputSize: Size? = null
    private var requestedDualCaptureSensorOrientationDegrees: Int = 0
    private var requestedSwitchableCameraInputSize: Size? = null
    private var requestedSwitchableCameraSensorOrientationDegrees: Int = 0
    private var requestedSwitchableCameraTimestampSource = CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN
    @Volatile private var switchableCameraTimestampToBoottimeOffsetNs = UNSET_TIMESTAMP
    private var requestedDualCaptureLayout: DualCaptureLayout = DualCaptureLayout.PICTURE_IN_PICTURE
    private var requestedDualCapturePositionX: Float = 0.66f
    private var requestedDualCapturePositionY: Float = 0.05f
    private var requestedDualCapturePipWidthFraction: Float = 0.33f
    private var cameraInputStarted = false
    private var requestedCameraTimestampSource = CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN
    private var requestedSensorOrientationDegrees = 0
    private var cameraTimestampToBoottimeOffsetNs = UNSET_TIMESTAMP
    private var timelineOriginUs = UNSET_TIMESTAMP
    private var sharedTimelineOriginProvider: (() -> Long?)? = null
    private var sharedTimelineStopTimeProvider: (() -> Long?)? = null
    private var onSharedTimelineFirstFrame: ((Long) -> Unit)? = null
    @Volatile private var usesSharedTimelineOrigin = false
    private var sharedTimelineVideoEndReporter: ((Long?, Boolean) -> Unit)? = null
    private var sharedTimelineCommonEndAwaiter: (suspend () -> Long?)? = null
    private var sharedTimelineDrainAwaiter: (suspend () -> Unit)? = null
    private var sharedTimelineDrainCompleter: (() -> Unit)? = null
    private var sharedCameraTimestampOffsetResolver: ((Long) -> Long)? = null
    private var onSharedTimelineDrainComplete: (suspend () -> Unit)? = null
    private var sharedTimelineStreamName: String? = null
    private var sharedTimelineDrainDurationMs: Long = 0L
    @Volatile private var acceptStopDrainFrames = false
    private var sharedVideoTailSamples = java.util.ArrayDeque<EncodedSample>()
    private var sharedAudioTailSamples = java.util.ArrayDeque<EncodedSample>()
    private var sharedTailBytes = 0L
    private var sharedTailByteLimit = 0L
    private var sharedTailOverflowed = false
    private var firstSharedVideoSourcePtsUs = UNSET_TIMESTAMP
    private var lastSharedVideoSourcePtsUs = UNSET_TIMESTAMP
    private var lastSharedAudioSourcePtsUs = UNSET_TIMESTAMP
    private var sharedVideoOutputOffsetUs = UNSET_TIMESTAMP
    private var sharedTimelineEndResolved = false
    private var sharedTimelineResolvedCommonEndUs: Long? = null
    private var sharedTimelineStopTimeUs = UNSET_TIMESTAMP
    private var pauseIntervals = mutableListOf<PauseInterval>()
    private var activePauseStartTimeUs = UNSET_TIMESTAMP

    private var requestedSize = Size(1080, 1920)
    private var requestedFallbackOutputSizes: List<Size> = emptyList()
    private var requestedFps = 30
    private var outputDateTakenMs: Long = 0L
    private var pendingVideoOutput: VideoMediaStoreWriter.PendingVideo? = null
    private var privateOutputFile: File? = null
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var colorMetadataTrackIndex = -1
    private var muxerStarted = false
    private var audioEnabled = true
    private var pendingVideoSamples = mutableListOf<EncodedSample>()
    private var pendingAudioSamples = mutableListOf<EncodedSample>()
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null
    private var errorCallback: ((String) -> Unit)? = null
    private var finishCallback: ((Uri?) -> Unit)? = null
    private var audioFramesRead = 0L
    private var audioFallbackStartTimeNs = UNSET_TIMESTAMP
    private var firstAudioCaptureTimeUs = UNSET_TIMESTAMP
    private var lastQueuedAudioPresentationTimeUs = UNSET_TIMESTAMP
    private var audioEndPresentationTimeUs = UNSET_TIMESTAMP
    private var activeAudioConfig = monoAudioConfig()
    private var lastMuxedVideoPresentationTimeUs = Long.MIN_VALUE
    private var lastMuxedAudioPresentationTimeUs = Long.MIN_VALUE
    private var pendingRealtimeFrameCount = 0
    private var renderLoopRunning = false
    private var statsWindowStartMs: Long = 0L
    private var statsIncomingFrames: Int = 0
    private var statsAcceptedFrames: Int = 0
    private var statsReplacedPendingFrames: Int = 0
    private var statsRenderedFrames: Int = 0
    private var statsRenderTimeTotalMs: Long = 0L
    private var statsRenderTimeMaxMs: Long = 0L

    private var videoDrainJob: Job? = null
    private var audioDrainJob: Job? = null
    private var audioRecordJob: Job? = null

    val usesDedicatedCameraInput: Boolean
        get() = isRecording

    val cameraInputSurface: Surface?
        get() = renderer?.inputSurface.takeIf {
            isRecording && !stopRequested
        }

    val dualCaptureInputSurface: Surface?
        get() = renderer?.dualCaptureInputSurface.takeIf {
            isRecording && !stopRequested
        }

    val switchableCameraInputSurface: Surface?
        get() = renderer?.switchableCameraInputSurface.takeIf {
            isRecording && !stopRequested
        }

    val pendingOutputUri: Uri?
        get() = pendingVideoOutput?.uri

    fun startRecording(
        size: Size,
        cameraInputSize: Size = size,
        cameraInputZoomRatio: Float = 1f,
        cameraInputPanX: Float = 0f,
        cameraInputPanY: Float = 0f,
        cameraInputRollDegrees: Float = 0f,
        cameraInputFixedRollCover: Boolean = false,
        fps: Int,
        bitrateMbps: Int,
        codecMime: String,
        apvQuality: ApvQuality = ApvQuality.HQ,
        fallbackCodecMimes: List<String> = emptyList(),
        fallbackOutputSizes: List<Size> = emptyList(),
        colorConfig: VideoEncoderColorRequest = VideoEncoderColorRequest(),
        cameraInputIsHlg10: Boolean = false,
        colorLayers: List<VideoColorEffectLayer> = emptyList(),
        cameraTimestampSource: Int = CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN,
        sensorOrientationDegrees: Int = 0,
        orientationHintDegrees: Int = 0,
        flipEncodedFrame: Boolean = false,
        enhancedStabilization: Boolean = false,
        enhancedStabilizationStrength: Float = DEFAULT_VIDEO_STABILIZATION_STRENGTH,
        enhancedStabilizationLookahead: Int = DEFAULT_VIDEO_STABILIZATION_LOOKAHEAD,
        recordingPath: VideoRecordingPath = VideoRecordingPath.DCIM_PHOTON,
        recordingTreeUri: String? = null,
        privateOutputFile: File? = null,
        recordAudio: Boolean = true,
        sharedTimelineOriginProvider: (() -> Long?)? = null,
        sharedTimelineStopTimeProvider: (() -> Long?)? = null,
        onFirstVideoFrame: ((Long) -> Unit)? = null,
        sharedTimelineVideoEndReporter: ((Long?, Boolean) -> Unit)? = null,
        sharedTimelineCommonEndAwaiter: (suspend () -> Long?)? = null,
        sharedTimelineDrainAwaiter: (suspend () -> Unit)? = null,
        sharedTimelineDrainCompleter: (() -> Unit)? = null,
        sharedCameraTimestampOffsetResolver: ((Long) -> Long)? = null,
        onSharedTimelineDrainComplete: (suspend () -> Unit)? = null,
        sharedTimelineStreamName: String? = null,
        sharedTimelineDrainDurationMs: Long = 0L,
        dualCaptureInputSize: Size? = null,
        dualCaptureSensorOrientationDegrees: Int = 0,
        dualCaptureLayout: DualCaptureLayout = DualCaptureLayout.PICTURE_IN_PICTURE,
        switchableCameraInputSize: Size? = null,
        switchableCameraSensorOrientationDegrees: Int = 0,
        switchableCameraTimestampSource: Int = CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN,
        dualCapturePositionX: Float = 0.66f,
        dualCapturePositionY: Float = 0.05f,
        dualCapturePipWidthFraction: Float = 0.33f,
        onError: ((String) -> Unit)? = null,
        onFinished: ((Uri?) -> Unit)? = null
    ): Boolean {
        if (isRecording) return false

        requestedOrientationHintDegrees = normalizeOrientationHint(orientationHintDegrees)
        requestedOutputSize = Size(size.width.align16(), size.height.align16())
        requestedCameraInputSize = cameraInputSize
        requestedCameraInputZoomRatio = cameraInputZoomRatio.takeIf { it.isFinite() }?.coerceIn(1f, 20f) ?: 1f
        requestedCameraInputPanX = cameraInputPanX.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
        requestedCameraInputPanY = cameraInputPanY.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
        requestedCameraInputRollDegrees = cameraInputRollDegrees.takeIf { it.isFinite() }?.coerceIn(-45f, 45f) ?: 0f
        requestedCameraInputFixedRollCover = cameraInputFixedRollCover
        requestedDualCaptureInputSize = dualCaptureInputSize
        requestedDualCaptureSensorOrientationDegrees = dualCaptureSensorOrientationDegrees
        requestedDualCaptureLayout = dualCaptureLayout
        requestedSwitchableCameraInputSize = switchableCameraInputSize
            ?.takeIf { it.width > 0 && it.height > 0 }
        requestedSwitchableCameraSensorOrientationDegrees = switchableCameraSensorOrientationDegrees
        requestedSwitchableCameraTimestampSource = switchableCameraTimestampSource
        switchableCameraTimestampToBoottimeOffsetNs = UNSET_TIMESTAMP
        requestedDualCapturePositionX = dualCapturePositionX.coerceIn(0f, 1f)
        requestedDualCapturePositionY = dualCapturePositionY.coerceIn(0f, 1f)
        requestedDualCapturePipWidthFraction = dualCapturePipWidthFraction.coerceIn(0.01f, 0.33f)
        // SurfaceTexture 变换后的 OES 内容已经处于自然显示方向，编码画布必须继续使用
        // 已解析的显示尺寸（片门全开使用最大相机输入尺寸）。设备横竖屏差异只通过容器 rotation metadata 表达。
        requestedSize = requestedOutputSize
        requestedFps = fps
        requestedFallbackCodecMimes = fallbackCodecMimes
            .filter { it.isNotBlank() && it != codecMime }
            .distinct()
        requestedFallbackOutputSizes = fallbackOutputSizes
            .filter { it.width > 0 && it.height > 0 }
            .map { Size(it.width.align16(), it.height.align16()) }
            .distinct()
        requestedBitrateMbps = bitrateMbps
        requestedApvQuality = apvQuality
        val hlg10Encoder = if (
            colorConfig.logProfile == VideoLogProfile.HLG10 &&
            !colorConfig.force8BitHlg &&
            codecMime != VideoCodec.APV.mimeType
        ) findHlg10Encoder() else null
        preferredEncoderCodecName = hlg10Encoder?.first?.name
        requestedCodecMime = hlg10Encoder?.second ?: codecMime
        requestedColorConfig = colorConfig
        requestedCameraInputIsHlg10 = cameraInputIsHlg10
        if (hlg10Encoder != null) {
            PLog.i(
                TAG,
                "HLG10 selected ${hlg10Encoder.first.name} (${hlg10Encoder.second}); " +
                    "using a Main10-capable encoder"
            )
        }
        requestedColorLayers = colorLayers.toList()
        requestedCameraTimestampSource = cameraTimestampSource
        requestedSensorOrientationDegrees = sensorOrientationDegrees
        requestedFlipEncodedFrame = flipEncodedFrame
        requestedEnhancedStabilization = enhancedStabilization
        requestedEnhancedStabilizationStrength =
            normalizeStabilizationStrength(enhancedStabilizationStrength)
        requestedEnhancedStabilizationLookahead =
            normalizeStabilizationLookahead(enhancedStabilizationLookahead)
        requestedRecordingPath = recordingPath
        requestedRecordingTreeUri = recordingTreeUri?.takeIf { it.isNotBlank() }
        outputDateTakenMs = System.currentTimeMillis()
        this.errorCallback = onError
        this.finishCallback = onFinished
        resetMuxerState()
        this.privateOutputFile = privateOutputFile
        this.audioEnabled = recordAudio
        this.sharedTimelineOriginProvider = sharedTimelineOriginProvider
        this.sharedTimelineStopTimeProvider = sharedTimelineStopTimeProvider
        this.onSharedTimelineFirstFrame = onFirstVideoFrame
        this.usesSharedTimelineOrigin = onFirstVideoFrame != null
        this.sharedTimelineVideoEndReporter = sharedTimelineVideoEndReporter
        this.sharedTimelineCommonEndAwaiter = sharedTimelineCommonEndAwaiter
        this.sharedTimelineDrainAwaiter = sharedTimelineDrainAwaiter
        this.sharedTimelineDrainCompleter = sharedTimelineDrainCompleter
        this.sharedCameraTimestampOffsetResolver = sharedCameraTimestampOffsetResolver
        this.onSharedTimelineDrainComplete = onSharedTimelineDrainComplete
        this.sharedTimelineStreamName = sharedTimelineStreamName
        this.sharedTimelineDrainDurationMs = sharedTimelineDrainDurationMs.coerceAtLeast(0L)
        this.acceptStopDrainFrames = false
        this.sharedTailByteLimit = (
            requestedBitrateMbps.toLong() * 1_000_000L / 8L * 3L
        ).coerceIn(SPECIAL_VIDEO_MIN_TAIL_BYTES, SPECIAL_VIDEO_MAX_TAIL_BYTES)
        isPaused = false
        statsWindowStartMs = 0L
        statsIncomingFrames = 0
        statsAcceptedFrames = 0
        statsReplacedPendingFrames = 0
        statsRenderedFrames = 0
        statsRenderTimeTotalMs = 0L
        statsRenderTimeMaxMs = 0L
        stopRequested = false
        cameraInputStarted = false
        var rendererInitialized = false
        try {
            prepareVideoEncoder()
            runBlocking(renderDispatcher) {
                initializeRealtimeRenderer()
            }
            rendererInitialized = true
            createMuxer()
            audioEnabled = recordAudio && initAudioEncoder(startLoop = false)
            startDrains()
        } catch (e: Exception) {
            val retryAs8BitHlg = !rendererInitialized &&
                requestedColorConfig.logProfile == VideoLogProfile.HLG10 &&
                !requestedColorConfig.force8BitHlg
            PLog.e(TAG, "Failed to prepare video recording before start", e)
            cleanupPreparedStart()
            if (!retryAs8BitHlg) {
                errorCallback?.invoke("Failed to prepare video recording: ${e.localizedMessage ?: "Unknown error"}")
                return false
            }

            // A codec can advertise Main10 but reject it at configuration time, or the
            // device may not expose a recordable RGB10_A2 EGL surface. Recreate the
            // encoder and renderer together as 8-bit HLG instead of leaving a nominal
            // Main10 stream carrying 8-bit source pixels.
            PLog.w(TAG, "HLG10 10-bit setup failed; automatically retrying with 8-bit HLG")
            requestedColorConfig = colorConfig.copy(force8BitHlg = true)
            requestedCodecMime = codecMime
            preferredEncoderCodecName = null
            try {
                prepareVideoEncoder()
                runBlocking(renderDispatcher) {
                    initializeRealtimeRenderer()
                }
                createMuxer()
                audioEnabled = recordAudio && initAudioEncoder(startLoop = false)
                startDrains()
            } catch (fallbackError: Exception) {
                PLog.e(TAG, "Failed to prepare 8-bit HLG fallback recording", fallbackError)
                cleanupPreparedStart()
                errorCallback?.invoke(
                    "Failed to prepare HLG recording: ${fallbackError.localizedMessage ?: "Unknown error"}"
                )
                return false
            }
        }
        isRecording = true
        if (sharedTimelineVideoEndReporter != null) {
            val timestampSourceLabel = if (
                requestedCameraTimestampSource == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            ) "REALTIME" else "UNKNOWN(${requestedCameraTimestampSource})"
            PLog.i(
                SPECIAL_TIMELINE_TAG,
                "Recorder configured: stream=${sharedTimelineStreamName ?: "paired"}, " +
                    "timestampSource=$timestampSourceLabel, EIS=${requestedEnhancedStabilization}, " +
                    "lookahead=${requestedEnhancedStabilizationLookahead}, " +
                    "tailWindowUs=$SPECIAL_VIDEO_TAIL_WINDOW_US, tailByteLimit=$sharedTailByteLimit"
            )
        }
        PLog.d(
            TAG,
            "Video recording prepared: encoder=${requestedSize.width}x${requestedSize.height}, " +
                "output=${requestedOutputSize.width}x${requestedOutputSize.height} @ " +
                "${requestedFps}fps, orientationHint=$requestedOrientationHintDegrees, " +
                "sensorOrientation=$requestedSensorOrientationDegrees, " +
                "enhancedStabilization=$requestedEnhancedStabilization, " +
                "stabilizationStrength=$requestedEnhancedStabilizationStrength, " +
                "input=${if (requestedEnhancedStabilization) "timestamped-yuv" else "dedicated-surface-texture"}, " +
                "cameraTimestampSource=$requestedCameraTimestampSource, " +
                "path=${requestedRecordingPath.name}, uri=${requestedRecordingTreeUri?.take(48)}"
        )
        return true
    }

    /**
     * EIS+ input is delivered through the timestamped YUV reader rather than cameraInputSurface.
     * Explicitly restart its renderer session after Camera2 swaps physical lenses; no encoder
     * SurfaceTexture callback is guaranteed to wake a stopped EIS session.
     */
    fun onCameraSwitched(
        sensorOrientationDegrees: Int,
        frontFacing: Boolean,
        restartStabilization: Boolean,
    ) {
        if (!isRecording || stopRequested) return

        requestedFlipEncodedFrame = frontFacing
        val mirrorHorizontally = frontFacing && requestedOrientationHintDegrees % 180 == 0
        val mirrorVertically = frontFacing && requestedOrientationHintDegrees % 180 != 0

        scope.launch(renderDispatcher) {
            if (!isRecording || stopRequested) return@launch

            // The encoder stays alive across lens switches, so update only the frame mirror
            // transform for the newly active lens. Do not restart or alter the recording pipeline.
            renderer?.updateMirrorTransform(mirrorHorizontally, mirrorVertically)

            if (restartStabilization && !isPaused && requestedEnhancedStabilization) {
                renderer?.restartStabilizationForCameraSwitch(sensorOrientationDegrees)
            }
        }
    }

    /**
     * A camera switch can expose a different supported SurfaceTexture stream size/aspect ratio.
     * Update the input surface and crop geometry while keeping the encoder's output size fixed.
     */
    fun updateCameraInputSize(
        size: Size,
        currentCameraUsesEnhancedStabilization: Boolean,
        sensorOrientationDegrees: Int = requestedSensorOrientationDegrees,
    ) {
        // The renderer can start with EIS on the rear camera but fall back to its ordinary
        // SurfaceTexture input on a switched front camera. Only keep the old buffer geometry
        // when the current camera is still using the EIS input path; the recording-start flag
        // alone must not block the front-camera input-size refresh.
        if (
            !isRecording ||
            stopRequested ||
            (requestedEnhancedStabilization && currentCameraUsesEnhancedStabilization)
        ) return
        if (size.width <= 0 || size.height <= 0) return
        requestedCameraInputSize = size
        requestedSensorOrientationDegrees = sensorOrientationDegrees
        // Serialize buffer-size and transform-geometry changes with the realtime render loop.
        // Updating SurfaceTexture/crop state from the camera handler can otherwise race the first
        // frame from the replacement lens and leave the encoder using stale crop geometry.
        runBlocking(renderDispatcher) {
            renderer?.updateCameraInputSize(size, sensorOrientationDegrees)
        }
        PLog.i(
            TAG,
            "Recording input size refreshed after camera switch: ${size.width}x${size.height}, " +
                "sensorOrientation=$sensorOrientationDegrees, " +
                "recordingStartedWithEis=$requestedEnhancedStabilization, " +
                "currentCameraUsesEis=$currentCameraUsesEnhancedStabilization",
        )
    }

    fun setSwitchableCameraInputActive(active: Boolean) {
        if (!isRecording || stopRequested) return
        if (requestedSwitchableCameraInputSize == null) {
            if (active) PLog.w(TAG, "Cannot select ultra-wide video source: switchable input was not prepared")
            return
        }
        // Separate CameraDevices can expose different clock origins when their timestamp
        // source is UNKNOWN. Recalibrate before the next frame is rendered so its PTS cannot
        // accidentally reuse the previous lens' offset.
        if (requestedSwitchableCameraTimestampSource != CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) {
            switchableCameraTimestampToBoottimeOffsetNs = UNSET_TIMESTAMP
        }
        runBlocking(renderDispatcher) {
            renderer?.setSwitchableCameraInputActive(active)
        }
        PLog.i(TAG, "Video encoder input source updated")
    }

    fun onCameraInputStarted(): Boolean {
        if (!isRecording || stopRequested || cameraInputStarted) return false
        cameraInputStarted = true
        renderer?.resumeStabilization()
        if (audioEnabled) {
            startAudioLoop()
        }
        PLog.d(TAG, "Dedicated Camera2 recording input started")
        return true
    }

    fun stopRecording() {
        if (!isRecording || stopRequested) return
        stopRequested = true
        acceptStopDrainFrames = sharedTimelineVideoEndReporter != null
        val sharedStopTimeUs = sharedTimelineStopTimeProvider?.invoke()
        if (sharedStopTimeUs != null) {
            synchronized(timelineLock) {
                sharedTimelineStopTimeUs = sharedStopTimeUs
            }
            PLog.i(TAG, "Recorder captured shared stop cutoff: stopTimeUs=$sharedStopTimeUs")
        }
        val completion = finishCallback
        scope.launch {
            var publishedUri: Uri? = null
            var sharedEndReported = false
            try {
                audioRecordJob?.join()
                withContext(renderDispatcher) {
                    drainRealtimeFrames(allowWhenStopping = true)
                }

                if (sharedTimelineVideoEndReporter != null) {
                    if (sharedTimelineDrainAwaiter != null) {
                        // The secondary camera remains live until the primary EIS+ lookahead
                        // drain completes, so a shared logical Camera2 session cannot back up.
                        sharedTimelineDrainAwaiter?.invoke()
                    } else {
                        delay(sharedTimelineDrainDurationMs.coerceAtLeast(1L))
                        withContext(renderDispatcher) {
                            drainRealtimeFrames(allowWhenStopping = true)
                        }
                        onSharedTimelineDrainComplete?.invoke()
                        sharedTimelineDrainCompleter?.invoke()
                    }
                }

                acceptStopDrainFrames = false
                withContext(renderDispatcher) {
                    drainRealtimeFrames(allowWhenStopping = true)
                    renderer?.pauseStabilization()
                    signalVideoEndOfInputStream()
                }
                queueAudioEndOfStream()
                videoDrainJob?.join()
                audioDrainJob?.join()

                if (sharedTimelineVideoEndReporter != null) {
                    val videoEndPtsUs = normalizedSpecialVideoEndPtsUs()
                    val audioEndPtsUs = normalizedSpecialAudioEndPtsUs()
                    val tailOverflowed = synchronized(muxerLock) { sharedTailOverflowed }
                    val stabilizationState = renderer?.stabilizationDiagnostics()
                        ?: "renderer-unavailable"
                    val timestampSource = if (
                        requestedCameraTimestampSource == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
                    ) {
                        "REALTIME"
                    } else {
                        "UNKNOWN(${requestedCameraTimestampSource})"
                    }
                    PLog.i(
                        SPECIAL_TIMELINE_TAG,
                        "Recorder EOS: stream=${sharedTimelineStreamName ?: "paired"}, " +
                            "firstVideoSourcePtsUs=${firstSharedVideoSourcePtsUs.takeIf { it != UNSET_TIMESTAMP }}, " +
                            "lastVideoEndPtsUs=$videoEndPtsUs, lastAudioPtsUs=$audioEndPtsUs, " +
                            "stopCutoffUs=${sharedTimelineStopTimeUs.takeIf { it != UNSET_TIMESTAMP }}, " +
                            "timestampSource=$timestampSource, EIS=$stabilizationState, " +
                            "tailOverflowed=$tailOverflowed"
                    )
                    sharedTimelineVideoEndReporter?.invoke(videoEndPtsUs, tailOverflowed)
                    sharedEndReported = true
                    val commonEndUs = sharedTimelineCommonEndAwaiter?.invoke()
                    synchronized(muxerLock) {
                        sharedTimelineEndResolved = true
                        sharedTimelineResolvedCommonEndUs = commonEndUs
                    }
                }

                releaseCaptureResources()
                isRecording = false
                publishedUri = finalizeOutput()
            } catch (e: Exception) {
                PLog.e(TAG, "Failed to stop recording cleanly", e)
                if (!sharedEndReported) {
                    sharedTimelineVideoEndReporter?.invoke(null, true)
                }
                runCatching { onSharedTimelineDrainComplete?.invoke() }
                sharedTimelineDrainCompleter?.invoke()
            } finally {
                acceptStopDrainFrames = false
                cleanup()
                completion?.invoke(publishedUri)
            }
        }
    }

    fun forceStop() {
        if (!isRecording || stopRequested) return
        stopRequested = true
        val completion = finishCallback
        scope.launch {
            errorCallback?.invoke("Recording stopped unexpectedly")
            runCatching { onSharedTimelineDrainComplete?.invoke() }
            sharedTimelineDrainCompleter?.invoke()
            sharedTimelineVideoEndReporter?.invoke(null, true)
            cleanup()
            completion?.invoke(null)
        }
    }

    fun pauseRecording() {
        if (!isRecording || isPaused || stopRequested) return
        val nowUs = SystemClock.elapsedRealtimeNanos() / 1_000L
        synchronized(timelineLock) {
            activePauseStartTimeUs = nowUs
        }
        isPaused = true
        renderer?.pauseStabilization()
        PLog.d(TAG, "Video recording paused")
    }

    fun resumeRecording() {
        if (!isRecording || !isPaused || stopRequested) return
        val nowUs = SystemClock.elapsedRealtimeNanos() / 1_000L
        synchronized(timelineLock) {
            val pauseStartUs = activePauseStartTimeUs
            if (pauseStartUs != UNSET_TIMESTAMP && nowUs > pauseStartUs) {
                pauseIntervals += PauseInterval(pauseStartUs, nowUs)
            }
            activePauseStartTimeUs = UNSET_TIMESTAMP
        }
        renderer?.resumeStabilization()
        isPaused = false
        PLog.d(TAG, "Video recording resumed")
    }

    fun updateCameraInputCrop(
        zoomRatio: Float,
        panX: Float,
        panY: Float,
        rollDegrees: Float = 0f,
        fixedRollCover: Boolean = false,
    ) {
        if (!isRecording || stopRequested) return
        requestedCameraInputZoomRatio = zoomRatio.takeIf { it.isFinite() }?.coerceIn(1f, 20f) ?: 1f
        requestedCameraInputPanX = panX.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
        requestedCameraInputPanY = panY.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
        requestedCameraInputRollDegrees = rollDegrees.takeIf { it.isFinite() }?.coerceIn(-45f, 45f) ?: 0f
        requestedCameraInputFixedRollCover = fixedRollCover
        pendingCameraInputCrop.set(
            CameraInputCropUpdate(
                requestedCameraInputZoomRatio,
                requestedCameraInputPanX,
                requestedCameraInputPanY,
                requestedCameraInputRollDegrees,
                requestedCameraInputFixedRollCover,
            )
        )
        dispatchPendingCameraInputCrop()
    }

    private fun dispatchPendingCameraInputCrop() {
        if (!cameraInputCropDispatchScheduled.compareAndSet(false, true)) return
        scope.launch(renderDispatcher) {
            try {
                // A queued gyro update is replaceable: applying an old crop after a newer
                // orientation sample is what makes the recorded frame visibly catch up/jump.
                val update = pendingCameraInputCrop.getAndSet(null)
                if (update != null && isRecording && !stopRequested) {
                    renderer?.updateCameraInputCrop(
                        update.zoomRatio,
                        update.panX,
                        update.panY,
                        update.rollDegrees,
                        update.fixedRollCover,
                    )
                }
            } finally {
                cameraInputCropDispatchScheduled.set(false)
                if (pendingCameraInputCrop.get() != null && isRecording && !stopRequested) {
                    dispatchPendingCameraInputCrop()
                }
            }
        }
    }

    fun updateDualCaptureLayout(layout: DualCaptureLayout) {
        if (!isRecording || stopRequested) return
        requestedDualCaptureLayout = layout
        scope.launch(renderDispatcher) {
            if (!isRecording || stopRequested) return@launch
            renderer?.updateDualCaptureLayout(layout)
        }
    }

    fun updateDualCapturePosition(x: Float, y: Float) {
        if (!isRecording || stopRequested) return
        requestedDualCapturePositionX = x.coerceIn(0f, 1f)
        requestedDualCapturePositionY = y.coerceIn(0f, 1f)
        scope.launch(renderDispatcher) {
            if (!isRecording || stopRequested) return@launch
            renderer?.updateDualCapturePosition(requestedDualCapturePositionX, requestedDualCapturePositionY)
        }
    }

    fun updateCreativeRecipeParams(params: com.hinnka.mycamera.model.ColorRecipeParams) {
        if (!isRecording || stopRequested) return
        scope.launch(renderDispatcher) {
            if (!isRecording || stopRequested) return@launch
            renderer?.updateCreativeRecipeParams(params)
        }
    }

    fun setCameraInputHlg10Source(enabled: Boolean) {
        requestedCameraInputIsHlg10 = enabled
        renderer?.setCameraInputHlg10Source(enabled)
    }

    fun isRecording(): Boolean = isRecording && !stopRequested

    fun setPreferredAudioInputId(audioInputId: String) {
        preferredAudioInputId = audioInputId.ifBlank { VIDEO_AUDIO_INPUT_AUTO }
    }

    private fun initializeRealtimeRenderer() {
        val encoderSurface = inputSurface ?: throw IllegalStateException("Video encoder input surface is not prepared")
        val encoderColorConfig = preparedEncoderColorConfig ?: VideoEncoderColorConfig.sdrDisplay()
        val realtimeRenderer = RealtimeVideoRenderer(
            context = context,
            cameraInputSize = requestedCameraInputSize,
            encoderOutputSize = requestedSize,
            sensorOrientationDegrees = requestedSensorOrientationDegrees,
            colorLayers = requestedColorLayers,
            videoLogProfile = requestedColorConfig.logProfile,
            cameraInputIsHlg10 = requestedCameraInputIsHlg10,
            mirrorHorizontally =
                requestedFlipEncodedFrame && requestedOrientationHintDegrees % 180 == 0,
            mirrorVertically =
                requestedFlipEncodedFrame && requestedOrientationHintDegrees % 180 != 0,
            encoderColorConfig = encoderColorConfig,
            stabilizationCoordinator = stabilizationCoordinator,
            enhancedStabilizationEnabled = requestedEnhancedStabilization,
            enhancedStabilizationStrength = requestedEnhancedStabilizationStrength,
            enhancedStabilizationLookahead = requestedEnhancedStabilizationLookahead,
            dualCaptureInputSize = requestedDualCaptureInputSize,
            dualCaptureSensorOrientationDegrees = requestedDualCaptureSensorOrientationDegrees,
            dualCaptureLayout = requestedDualCaptureLayout,
            dualCapturePositionX = requestedDualCapturePositionX,
            dualCapturePositionY = requestedDualCapturePositionY,
            dualCapturePipWidthFraction = requestedDualCapturePipWidthFraction,
            encoderOrientationHintDegrees = requestedOrientationHintDegrees,
            switchableCameraInputSize = requestedSwitchableCameraInputSize,
            switchableCameraSensorOrientationDegrees = requestedSwitchableCameraSensorOrientationDegrees,
            cameraInputZoomRatio = requestedCameraInputZoomRatio,
            cameraInputPanX = requestedCameraInputPanX,
            cameraInputPanY = requestedCameraInputPanY,
            cameraInputRollDegrees = requestedCameraInputRollDegrees,
            cameraInputFixedRollCover = requestedCameraInputFixedRollCover,
            watermarkLabel = if (sharedTimelineStreamName != null) MediaWatermark.SPECIAL_VIDEO else null,
        )
        renderer = realtimeRenderer
        realtimeRenderer.initialize(
            encoderSurface = encoderSurface,
            onFrameAvailable = ::onRealtimeFrameAvailable,
        )
        PLog.d(
            TAG,
            "Realtime video renderer initialized: ${requestedSize.width}x${requestedSize.height} @ ${requestedFps}fps"
        )
    }

    private fun onRealtimeFrameAvailable() {
        // Special Video keeps consuming camera buffers briefly after Stop so EIS+ can
        // emit its delayed frames and the companion SurfaceTexture cannot stall a shared
        // logical Camera2 session. Samples beyond the shared stop cutoff are discarded.
        if (!isRecording || (stopRequested && !acceptStopDrainFrames) || isPaused || !cameraInputStarted) return
        statsIncomingFrames += 1
        synchronized(realtimeFrameLock) {
            if (pendingRealtimeFrameCount > 0) {
                statsReplacedPendingFrames += 1
            }
            pendingRealtimeFrameCount = if (requestedEnhancedStabilization) {
                pendingRealtimeFrameCount + 1
            } else {
                // SurfaceTexture itself is latest-only; multiple callbacks before the GL
                // consumer runs therefore represent one materializable OES frame.
                1
            }
            if (!renderLoopRunning) {
                renderLoopRunning = true
                scope.launch(renderDispatcher) {
                    drainRealtimeFrames(allowWhenStopping = stopRequested)
                }
            }
        }
    }

    private fun findHlg10Encoder(): Pair<MediaCodecInfo, String>? {
        val codecInfos = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        }.getOrNull() ?: return null

        val candidates = codecInfos.flatMap { info ->
            if (!info.isEncoder) return@flatMap emptyList()
            val possibleProfiles = listOf(
                MediaFormat.MIMETYPE_VIDEO_HEVC to MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                MediaFormat.MIMETYPE_VIDEO_AVC to MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10
            )
            possibleProfiles.mapNotNull { (mime, profile) ->
                if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) {
                    return@mapNotNull null
                }
                val capabilities = runCatching {
                    info.getCapabilitiesForType(mime)
                }.getOrNull() ?: return@mapNotNull null
                if (!capabilities.colorFormats.contains(
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    )
                ) {
                    return@mapNotNull null
                }
                val profiles = capabilities.profileLevels.map { it.profile }.toSet()
                if (profile in profiles) info to mime else null
            }
        }
        // Prefer hardware encode, then HEVC Main10; AVC High10 is a valid fallback
        // when it is the only genuine 10-bit encoder exposed by the device.
        return candidates.sortedWith(
            compareByDescending<Pair<MediaCodecInfo, String>> { it.first.isHardwareAccelerated }
                .thenBy { if (it.second == MediaFormat.MIMETYPE_VIDEO_HEVC) 0 else 1 }
        ).firstOrNull()
    }

    private fun prepareVideoEncoder() {
        if (videoEncoder != null && inputSurface != null) return
        val preferredMime = requestedCodecMime
        val sizeCandidates = (listOf(requestedOutputSize) + requestedFallbackOutputSizes)
            .filter { it.width > 0 && it.height > 0 }
            .distinctBy { it.width to it.height }
        val mimeCandidates = (listOf(preferredMime) + requestedFallbackCodecMimes).distinct()
        val codecInfos = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        }.getOrDefault(emptyArray())
        var lastFailure: Exception? = null

        for ((sizeIndex, candidateSize) in sizeCandidates.withIndex()) {
            val candidates = buildList {
                mimeCandidates.forEachIndexed { mimePriority, mimeType ->
                    codecInfos.forEach codecLoop@{ codecInfo ->
                        if (!codecInfo.isEncoder ||
                            codecInfo.supportedTypes.none { it.equals(mimeType, ignoreCase = true) } ||
                            (preferredEncoderCodecName != null && codecInfo.name != preferredEncoderCodecName)
                        ) return@codecLoop

                        val capabilities = runCatching {
                            codecInfo.getCapabilitiesForType(mimeType)
                        }.getOrNull() ?: return@codecLoop
                        if (!capabilities.colorFormats.contains(
                                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                            )
                        ) return@codecLoop
                        val sizeSupported = runCatching {
                            capabilities.videoCapabilities.isSizeSupported(
                                candidateSize.width,
                                candidateSize.height,
                            )
                        }.getOrDefault(false)
                        if (sizeSupported) {
                            add(VideoEncoderCandidate(codecInfo, mimeType, mimePriority))
                        }
                    }
                }
            }.sortedWith(
                compareBy<VideoEncoderCandidate> { it.mimePriority }
                    .thenByDescending { it.codecInfo.isHardwareAccelerated }
                    .thenBy { it.codecInfo.name }
            )

            if (candidates.isEmpty()) {
                PLog.w(
                    TAG,
                    "No surface encoder supports ${candidateSize.width}x${candidateSize.height}; " +
                        "checking the next Open Gate canvas"
                )
                lastFailure = IllegalStateException(
                    "No encoder supports ${candidateSize.width}x${candidateSize.height}"
                )
                continue
            }

            for (candidate in candidates) {
                var codec: MediaCodec? = null
                var surface: Surface? = null
                try {
                    codec = MediaCodec.createByCodecName(candidate.codecInfo.name)
                    val capabilities = candidate.codecInfo.getCapabilitiesForType(candidate.mimeType)
                    val requestedVideoBitrate = if (candidate.mimeType == VideoCodec.APV.mimeType) {
                        requestedApvQuality.resolveBitrate(
                            candidateSize.width,
                            candidateSize.height,
                            requestedFps,
                            capabilities.videoCapabilities.bitrateRange.upper
                        )
                    } else {
                        (requestedBitrateMbps * 1_000_000).coerceIn(2_000_000, 300_000_000)
                    }
                    val videoBitrate =
                        capabilities.videoCapabilities.bitrateRange.clamp(requestedVideoBitrate)
                    val isCbrSupported = capabilities.encoderCapabilities?.isBitrateModeSupported(
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    ) == true
                    var bitrateMode = if (isCbrSupported) {
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    } else {
                        PLog.w(TAG, "CBR bitrate mode not supported by ${candidate.codecInfo.name}; using VBR")
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                    }
                    val resolvedColorConfig = resolveVideoEncoderColorConfig(
                        candidate.codecInfo,
                        candidate.mimeType,
                        requestedColorConfig,
                    )
                    val format = MediaFormat.createVideoFormat(
                        candidate.mimeType,
                        candidateSize.width,
                        candidateSize.height,
                    ).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                        setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate)
                        setInteger(MediaFormat.KEY_FRAME_RATE, requestedFps)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
                        setInteger(MediaFormat.KEY_PRIORITY, 0)
                        setInteger(MediaFormat.KEY_BITRATE_MODE, bitrateMode)
                        setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                        resolvedColorConfig.applyTo(this)
                    }

                    try {
                        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    } catch (configureError: Exception) {
                        if (!isCbrSupported) throw configureError
                        PLog.w(
                            TAG,
                            "CBR configuration failed for ${candidate.codecInfo.name}; retrying with VBR",
                            configureError,
                        )
                        bitrateMode = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                        format.setInteger(MediaFormat.KEY_BITRATE_MODE, bitrateMode)
                        codec.reset()
                        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    }

                    surface = codec.createInputSurface()
                    codec.start()
                    videoEncoder = codec
                    inputSurface = surface
                    requestedCodecMime = candidate.mimeType
                    requestedSize = candidateSize
                    preparedEncoderColorConfig = resolvedColorConfig

                    PLog.i(
                        TAG,
                        "Prepared video encoder: codec=${candidate.codecInfo.name}, mime=${candidate.mimeType}, " +
                            "preferredMime=$preferredMime, hardware=${candidate.codecInfo.isHardwareAccelerated}, " +
                            "encoder=${candidateSize.width}x${candidateSize.height}, " +
                            "requestedCanvas=${requestedOutputSize.width}x${requestedOutputSize.height}, " +
                            "sizeFallback=${sizeIndex > 0}, bitrateMode=${if (bitrateMode == 2) "CBR" else "VBR"}, " +
                            "colorPipeline=${resolvedColorConfig.pipeline}, colorStandard=${resolvedColorConfig.colorStandard}, " +
                            "colorTransfer=${resolvedColorConfig.colorTransfer}, colorRange=${resolvedColorConfig.colorRange}, " +
                            "codecProfile=${resolvedColorConfig.codecProfile}, " +
                            "prefer10BitSurface=${resolvedColorConfig.prefer10BitInputSurface}, " +
                            "request=${requestedColorConfig.logProfile.name}, hasLut=${requestedColorConfig.hasActiveLut}"
                    )
                    if (sizeIndex > 0) {
                        PLog.w(
                            TAG,
                            "Open Gate is using the largest compatible fallback canvas: " +
                                "${candidateSize.width}x${candidateSize.height}; requested full-sensor canvas was " +
                                "${requestedOutputSize.width}x${requestedOutputSize.height}"
                        )
                    }
                    if (requestedColorConfig.logProfile == VideoLogProfile.HLG10 &&
                        !resolvedColorConfig.prefer10BitInputSurface
                    ) {
                        PLog.w(TAG, "No end-to-end HLG10 path available; recording with 8-bit HLG")
                    } else if (
                        requestedColorConfig.logProfile.isEnabled &&
                        requestedColorConfig.logProfile != VideoLogProfile.HLG10 &&
                        resolvedColorConfig.codecProfile == null
                    ) {
                        PLog.w(
                            TAG,
                            "Selected codec does not expose a 10-bit profile for ${requestedColorConfig.logProfile.name}. " +
                                "Recording will continue, but encoded Log compatibility may be reduced."
                        )
                    }
                    return
                } catch (error: Exception) {
                    lastFailure = error
                    PLog.w(
                        TAG,
                        "Encoder rejected ${candidate.codecInfo.name} (${candidate.mimeType}) at " +
                            "${candidateSize.width}x${candidateSize.height}: ${error.message}",
                    )
                    runCatching { surface?.release() }
                    runCatching { codec?.stop() }
                    runCatching { codec?.release() }
                }
            }
        }

        throw IllegalStateException(
            "No available video encoder could prepare ${requestedOutputSize.width}x" +
                "${requestedOutputSize.height} at ${requestedFps}fps for codecs $mimeCandidates",
            lastFailure,
        )
    }

    private fun initAudioEncoder(startLoop: Boolean = true): Boolean {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            PLog.w(TAG, "Audio permission missing, continue without audio")
            return false
        }

        return try {
            val preparedAudioRecord = createAudioRecordWithFallback() ?: return false
            audioRecord = preparedAudioRecord.recorder.apply {
                if (startLoop) {
                    startRecording()
                }
            }
            activeAudioConfig = preparedAudioRecord.config

            audioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME).apply {
                val format = MediaFormat.createAudioFormat(
                    AUDIO_MIME,
                    AUDIO_SAMPLE_RATE,
                    activeAudioConfig.channelCount
                ).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, activeAudioConfig.bitrate)
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                }
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            PLog.i(
                TAG,
                "Audio encoder initialized: ${activeAudioConfig.label}, " +
                    "channels=${activeAudioConfig.channelCount}, bitrate=${activeAudioConfig.bitrate}"
            )
            if (startLoop) {
                startAudioLoop()
            }
            true
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to initialize audio encoder", e)
            audioRecord?.release()
            audioRecord = null
            audioEncoder?.release()
            audioEncoder = null
            false
        }
    }

    private fun createAudioRecordWithFallback(): PreparedAudioRecord? {
        val configs = listOf(stereoAudioConfig(), monoAudioConfig())
        for (config in configs) {
            val minBufferSize = try {
                AudioRecord.getMinBufferSize(
                    AUDIO_SAMPLE_RATE,
                    config.channelMask,
                    AudioFormat.ENCODING_PCM_16BIT
                )
            } catch (e: Exception) {
                PLog.w(TAG, "AudioRecord min buffer query failed for ${config.label}: ${e.message}")
                continue
            }
            if (minBufferSize <= 0) {
                PLog.w(TAG, "AudioRecord does not support ${config.label}: minBufferSize=$minBufferSize")
                continue
            }

            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.CAMCORDER,
                    AUDIO_SAMPLE_RATE,
                    config.channelMask,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufferSize * 2
                )
            } catch (e: Exception) {
                PLog.w(TAG, "AudioRecord creation failed for ${config.label}: ${e.message}")
                continue
            }
            if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                applyPreferredAudioInput(recorder)
                PLog.i(TAG, "AudioRecord initialized: ${config.label}, bufferSize=${minBufferSize * 2}")
                return PreparedAudioRecord(recorder, config)
            }

            recorder.release()
            PLog.w(TAG, "AudioRecord failed to initialize with ${config.label}")
        }
        PLog.w(TAG, "No supported audio capture channel configuration found")
        return null
    }

    private fun stereoAudioConfig(): AudioCaptureConfig {
        return AudioCaptureConfig(
            channelMask = AudioFormat.CHANNEL_IN_STEREO,
            channelCount = AUDIO_STEREO_CHANNEL_COUNT,
            bitrate = AUDIO_STEREO_BITRATE,
            label = "stereo"
        )
    }

    private fun monoAudioConfig(): AudioCaptureConfig {
        return AudioCaptureConfig(
            channelMask = AudioFormat.CHANNEL_IN_MONO,
            channelCount = AUDIO_MONO_CHANNEL_COUNT,
            bitrate = AUDIO_MONO_BITRATE,
            label = "mono"
        )
    }

    private fun applyPreferredAudioInput(audioRecord: AudioRecord) {
        if (preferredAudioInputId == VIDEO_AUDIO_INPUT_AUTO) {
            PLog.d(TAG, "Use system default audio input routing")
            return
        }
        val preferredDevice = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.isSource && it.toVideoAudioInputId() == preferredAudioInputId }
        if (preferredDevice == null) {
            PLog.w(TAG, "Preferred audio input not found, fallback to system routing: $preferredAudioInputId")
            return
        }
        val routed = audioRecord.setPreferredDevice(preferredDevice)
        PLog.i(
            TAG,
            "Apply preferred audio input=${preferredDevice.toVideoAudioInputId()}, type=${preferredDevice.type}, success=$routed"
        )
    }

    private fun createMuxer() {
        val privateFile = privateOutputFile
        if (privateFile != null) {
            privateFile.parentFile?.mkdirs()
            if (privateFile.exists() && !privateFile.delete()) {
                throw IllegalStateException("Cannot replace private Special Video output")
            }
            muxer = MediaMuxer(privateFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
                setOrientationHint(requestedOrientationHintDegrees)
                colorMetadataTrackIndex = VideoColorMetadata.addTrack(this)
            }
            return
        }
        val output = VideoMediaStoreWriter.createPendingVideo(
            context = context,
            dateTakenMs = outputDateTakenMs,
            recordingPath = requestedRecordingPath,
            recordingTreeUri = requestedRecordingTreeUri
        ) ?: throw IllegalStateException("Failed to create video output")
        pendingVideoOutput = output
        muxer = MediaMuxer(output.descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
            setOrientationHint(requestedOrientationHintDegrees)
            colorMetadataTrackIndex = VideoColorMetadata.addTrack(this)
        }
    }

    private fun startAudioLoop() {
        val recorder = audioRecord ?: return
        val encoder = audioEncoder ?: return
        audioRecordJob = scope.launch {
            try {
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    audioFallbackStartTimeNs = SystemClock.elapsedRealtimeNanos()
                    recorder.startRecording()
                }
            } catch (e: Exception) {
                PLog.w(TAG, "Failed to start audio recording loop: ${e.message}")
                return@launch
            }
            val buffer = ByteArray(4096)
            recordingLoop@ while (isRecording && !stopRequested) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                val frameAlignedRead = read - (read % audioBytesPerFrame())
                if (frameAlignedRead <= 0) continue

                val bytesPerFrame = audioBytesPerFrame()
                val frameCount = frameAlignedRead / bytesPerFrame
                val firstFramePosition = audioFramesRead
                val bufferStartTimeUs = resolveAudioCaptureTimeUs(
                    recorder = recorder,
                    firstFramePosition = firstFramePosition,
                )
                audioFramesRead += frameCount.toLong()
                logFirstAudioCaptureTime(bufferStartTimeUs)

                val activeRanges = resolveActiveAudioFrameRanges(
                    bufferStartTimeUs = bufferStartTimeUs,
                    frameCount = frameCount,
                )
                for (range in activeRanges) {
                    val rangeFrameCount = range.endFrameExclusive - range.startFrame
                    val rangeStartTimeUs = bufferStartTimeUs +
                        audioFramesToDurationUs(range.startFrame.toLong())
                    if (!queueAudioPcm(
                            encoder = encoder,
                            source = buffer,
                            sourceOffset = range.startFrame * bytesPerFrame,
                            byteCount = rangeFrameCount * bytesPerFrame,
                            firstPresentationTimeUs = rangeStartTimeUs,
                        )
                    ) {
                        break@recordingLoop
                    }
                }
            }
            try {
                recorder.stop()
            } catch (_: Exception) {
            }
        }
    }

    private fun queueAudioPcm(
        encoder: MediaCodec,
        source: ByteArray,
        sourceOffset: Int,
        byteCount: Int,
        firstPresentationTimeUs: Long,
    ): Boolean {
        val bytesPerFrame = audioBytesPerFrame()
        val stopTimeUs = synchronized(timelineLock) { sharedTimelineStopTimeUs }
        val stopLimitedFrames = if (stopTimeUs == UNSET_TIMESTAMP) {
            byteCount / bytesPerFrame
        } else {
            (((stopTimeUs - firstPresentationTimeUs).coerceAtLeast(0L) * AUDIO_SAMPLE_RATE) /
                MICROS_PER_SECOND)
                .coerceAtMost((byteCount / bytesPerFrame).toLong())
                .toInt()
        }
        val stopLimitedBytes = stopLimitedFrames * bytesPerFrame
        if (stopLimitedBytes <= 0) return true

        var offset = sourceOffset
        val endOffset = sourceOffset + stopLimitedBytes
        while (offset < endOffset && isRecording && !stopRequested) {
            val inputIndex = try {
                encoder.dequeueInputBuffer(10_000L)
            } catch (_: IllegalStateException) {
                return false
            }
            if (inputIndex < 0) continue

            val inputBuffer = encoder.getInputBuffer(inputIndex) ?: continue
            inputBuffer.clear()
            val maxChunkSize = minOf(endOffset - offset, inputBuffer.remaining())
            val chunkSize = maxChunkSize - (maxChunkSize % bytesPerFrame)
            if (chunkSize <= 0) {
                PLog.w(TAG, "Skip audio chunk because encoder input buffer cannot fit a complete audio frame")
                return true
            }

            inputBuffer.put(source, offset, chunkSize)
            val sourceFrameOffset = (offset - sourceOffset) / bytesPerFrame
            val capturePresentationTimeUs = firstPresentationTimeUs +
                audioFramesToDurationUs(sourceFrameOffset.toLong())
            val presentationTimeUs = if (lastQueuedAudioPresentationTimeUs == UNSET_TIMESTAMP) {
                capturePresentationTimeUs
            } else {
                capturePresentationTimeUs.coerceAtLeast(lastQueuedAudioPresentationTimeUs + 1L)
            }
            try {
                encoder.queueInputBuffer(
                    inputIndex,
                    0,
                    chunkSize,
                    presentationTimeUs,
                    0
                )
            } catch (_: IllegalStateException) {
                return false
            }
            lastQueuedAudioPresentationTimeUs = presentationTimeUs
            audioEndPresentationTimeUs = presentationTimeUs +
                audioFramesToDurationUs((chunkSize / bytesPerFrame).toLong())
            offset += chunkSize
        }
        return true
    }

    private fun resolveAudioCaptureTimeUs(
        recorder: AudioRecord,
        firstFramePosition: Long,
    ): Long {
        val timestamp = AudioTimestamp()
        val timestampAvailable = recorder.getTimestamp(
            timestamp,
            AudioTimestamp.TIMEBASE_BOOTTIME,
        ) == AudioRecord.SUCCESS && timestamp.nanoTime > 0L
        val captureTimeNs = if (timestampAvailable) {
            timestamp.nanoTime + audioFramesToDurationNs(firstFramePosition - timestamp.framePosition)
        } else {
            val fallbackStartNs = audioFallbackStartTimeNs.takeIf { it != UNSET_TIMESTAMP }
                ?: SystemClock.elapsedRealtimeNanos().also { audioFallbackStartTimeNs = it }
            fallbackStartNs + audioFramesToDurationNs(firstFramePosition)
        }
        return captureTimeNs / 1_000L
    }

    private fun logFirstAudioCaptureTime(captureTimeUs: Long) {
        if (firstAudioCaptureTimeUs != UNSET_TIMESTAMP) return
        firstAudioCaptureTimeUs = captureTimeUs
        val originUs = synchronized(timelineLock) { timelineOriginUs }
        val offsetMs = if (originUs == UNSET_TIMESTAMP) {
            "pending-video-origin"
        } else {
            "${(captureTimeUs - originUs) / 1_000f}"
        }
        PLog.i(
            TAG,
            "Audio capture timeline established: firstAudioCaptureUs=$captureTimeUs, " +
                "initialAvOffsetMs=$offsetMs"
        )
    }

    private fun resolveActiveAudioFrameRanges(
        bufferStartTimeUs: Long,
        frameCount: Int,
    ): List<FrameRange> {
        if (frameCount <= 0) return emptyList()
        val bufferEndTimeUs = bufferStartTimeUs + audioFramesToDurationUs(frameCount.toLong())
        val pauses = synchronized(timelineLock) {
            buildList {
                addAll(pauseIntervals)
                if (activePauseStartTimeUs != UNSET_TIMESTAMP) {
                    add(PauseInterval(activePauseStartTimeUs, Long.MAX_VALUE))
                }
            }
        }
        var activeRanges = listOf(FrameRange(0, frameCount))
        for (pause in pauses) {
            if (pause.startTimeUs >= bufferEndTimeUs || pause.endTimeUs <= bufferStartTimeUs) continue
            val pauseStartFrame = audioFrameOffsetAtOrAfter(
                timestampUs = pause.startTimeUs,
                bufferStartTimeUs = bufferStartTimeUs,
                bufferEndTimeUs = bufferEndTimeUs,
                frameCount = frameCount,
            )
            val pauseEndFrame = if (pause.endTimeUs == Long.MAX_VALUE) {
                frameCount
            } else {
                audioFrameOffsetAtOrAfter(
                    timestampUs = pause.endTimeUs,
                    bufferStartTimeUs = bufferStartTimeUs,
                    bufferEndTimeUs = bufferEndTimeUs,
                    frameCount = frameCount,
                )
            }
            activeRanges = activeRanges.flatMap { range ->
                if (pauseEndFrame <= range.startFrame || pauseStartFrame >= range.endFrameExclusive) {
                    listOf(range)
                } else {
                    buildList {
                        if (range.startFrame < pauseStartFrame) {
                            add(FrameRange(range.startFrame, pauseStartFrame))
                        }
                        if (pauseEndFrame < range.endFrameExclusive) {
                            add(FrameRange(pauseEndFrame, range.endFrameExclusive))
                        }
                    }
                }
            }
            if (activeRanges.isEmpty()) break
        }
        val stopTimeUs = synchronized(timelineLock) { sharedTimelineStopTimeUs }
        if (stopTimeUs != UNSET_TIMESTAMP) {
            val stopFrame = audioFrameOffsetAtOrAfter(
                timestampUs = stopTimeUs,
                bufferStartTimeUs = bufferStartTimeUs,
                bufferEndTimeUs = bufferEndTimeUs,
                frameCount = frameCount,
            )
            activeRanges = activeRanges.mapNotNull { range ->
                val endFrameExclusive = minOf(range.endFrameExclusive, stopFrame)
                if (endFrameExclusive > range.startFrame) {
                    FrameRange(range.startFrame, endFrameExclusive)
                } else {
                    null
                }
            }
        }
        return activeRanges
    }

    private fun audioFrameOffsetAtOrAfter(
        timestampUs: Long,
        bufferStartTimeUs: Long,
        bufferEndTimeUs: Long,
        frameCount: Int,
    ): Int {
        if (timestampUs <= bufferStartTimeUs) return 0
        if (timestampUs >= bufferEndTimeUs) return frameCount
        val deltaUs = timestampUs - bufferStartTimeUs
        return ((deltaUs * AUDIO_SAMPLE_RATE + MICROS_PER_SECOND - 1L) / MICROS_PER_SECOND)
            .toInt()
            .coerceIn(0, frameCount)
    }

    private fun audioFramesToDurationUs(frameCount: Long): Long {
        return frameCount * MICROS_PER_SECOND / AUDIO_SAMPLE_RATE
    }

    private fun audioFramesToDurationNs(frameCount: Long): Long {
        return frameCount * NANOS_PER_SECOND / AUDIO_SAMPLE_RATE
    }

    private fun audioBytesPerFrame(): Int {
        return activeAudioConfig.channelCount * AUDIO_BYTES_PER_SAMPLE
    }

    private fun mapCameraTimestampToBoottimeNs(
        cameraTimestampNs: Long,
        switchableInput: Boolean = false,
    ): Long {
        val timestampSource = if (switchableInput) {
            requestedSwitchableCameraTimestampSource
        } else {
            requestedCameraTimestampSource
        }
        if (timestampSource == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) {
            return cameraTimestampNs
        }

        if (switchableInput) {
            if (switchableCameraTimestampToBoottimeOffsetNs == UNSET_TIMESTAMP) {
                switchableCameraTimestampToBoottimeOffsetNs =
                    SystemClock.elapsedRealtimeNanos() - cameraTimestampNs
                PLog.w(
                    TAG,
                    "Ultra-wide camera timestamp calibrated to BOOTTIME with " +
                        "offsetNs=$switchableCameraTimestampToBoottimeOffsetNs",
                )
            }
            return cameraTimestampNs + switchableCameraTimestampToBoottimeOffsetNs
        }

        if (cameraTimestampToBoottimeOffsetNs == UNSET_TIMESTAMP) {
            val candidateOffsetNs = SystemClock.elapsedRealtimeNanos() - cameraTimestampNs
            cameraTimestampToBoottimeOffsetNs =
                sharedCameraTimestampOffsetResolver?.invoke(candidateOffsetNs) ?: candidateOffsetNs
            PLog.w(
                if (sharedTimelineVideoEndReporter != null) SPECIAL_TIMELINE_TAG else TAG,
                "Camera timestamp source is not REALTIME; calibrated to BOOTTIME with " +
                    "offsetNs=$cameraTimestampToBoottimeOffsetNs, " +
                    "shared=${sharedCameraTimestampOffsetResolver != null}, " +
                    "stream=${sharedTimelineStreamName ?: "single"}"
            )
        }
        return cameraTimestampNs + cameraTimestampToBoottimeOffsetNs
    }
    private fun establishTimelineOrigin(firstVideoPresentationTimeUs: Long) {
        if (usesSharedTimelineOrigin) {
            onSharedTimelineFirstFrame?.invoke(firstVideoPresentationTimeUs)
            onSharedTimelineFirstFrame = null
            synchronized(timelineLock) {
                resolveTimelineOriginLocked()
            }
            return
        }

        val established = synchronized(timelineLock) {
            if (timelineOriginUs != UNSET_TIMESTAMP) {
                false
            } else {
                timelineOriginUs = firstVideoPresentationTimeUs
                true
            }
        }
        if (established) {
            val audioOffsetMs = firstAudioCaptureTimeUs
                .takeIf { it != UNSET_TIMESTAMP }
                ?.let { (it - firstVideoPresentationTimeUs) / 1_000f }
            PLog.i(
                TAG,
                "Recording timeline established: firstVideoCaptureUs=$firstVideoPresentationTimeUs, " +
                    "initialAvOffsetMs=${audioOffsetMs ?: "pending-audio"}"
            )
        }
    }

    /**
     * Must be called while timelineLock is held. Shared recorders retain encoded samples until
     * both streams report a first frame and the common origin becomes available.
     */
    private fun resolveTimelineOriginLocked(): Long {
        if (timelineOriginUs == UNSET_TIMESTAMP) {
            val sharedOriginUs = sharedTimelineOriginProvider?.invoke()
            if (sharedOriginUs != null) {
                timelineOriginUs = sharedOriginUs
                PLog.i(TAG, "Shared recorder timeline origin received: originUs=$sharedOriginUs")
            }
        }
        return timelineOriginUs
    }

    private fun drainRealtimeFrames(allowWhenStopping: Boolean) {
        while (true) {
            val hasFrame = synchronized(realtimeFrameLock) {
                if (pendingRealtimeFrameCount <= 0) {
                    renderLoopRunning = false
                    return
                }
                pendingRealtimeFrameCount -= 1
                true
            }

            if (!hasFrame ||
                !isRecording ||
                (stopRequested && !allowWhenStopping) ||
                isPaused
            ) {
                continue
            }

            try {
                val videoRenderer = renderer ?: continue
                val renderStartMs = SystemClock.elapsedRealtime()
                val presentationTimeNs = videoRenderer.renderLatestFrame(
                    mapPresentationTimeNs = { timestampNs, switchableInput ->
                        mapCameraTimestampToBoottimeNs(timestampNs, switchableInput)
                    },
                ) ?: continue
                establishTimelineOrigin(presentationTimeNs / 1_000L)
                val renderCostMs = (SystemClock.elapsedRealtime() - renderStartMs).coerceAtLeast(0L)
                statsAcceptedFrames += 1
                statsRenderedFrames += 1
                statsRenderTimeTotalMs += renderCostMs
                if (renderCostMs > statsRenderTimeMaxMs) {
                    statsRenderTimeMaxMs = renderCostMs
                }
                logRenderStatsIfNeeded()
            } catch (e: Exception) {
                val diagnostic = if (e is MediaCodec.CodecException) {
                    "isTransient=${e.isTransient}, isRecoverable=${e.isRecoverable}, errorCode=${e.errorCode}"
                } else ""
                val message = "Failed to render dedicated recording frame. $diagnostic"
                PLog.e(TAG, message, e)
                errorCallback?.invoke(message)
                if (!stopRequested) {
                    forceStop()
                }
                return
            }
        }
    }

    private fun logRenderStatsIfNeeded() {
        val nowMs = SystemClock.elapsedRealtime()
        if (statsWindowStartMs == 0L) {
            statsWindowStartMs = nowMs
            return
        }
        val elapsedMs = nowMs - statsWindowStartMs
        if (elapsedMs < 1000L) return

        val incomingFps = statsIncomingFrames * 1000f / elapsedMs.toFloat()
        val acceptedFps = statsAcceptedFrames * 1000f / elapsedMs.toFloat()
        val renderedFps = statsRenderedFrames * 1000f / elapsedMs.toFloat()
        val avgRenderMs = if (statsRenderedFrames > 0) {
            statsRenderTimeTotalMs.toFloat() / statsRenderedFrames.toFloat()
        } else {
            0f
        }
        PLog.i(
            TAG,
            "Video encoder stats: requested=${requestedFps}, incomingFps=${"%.1f".format(incomingFps)}, " +
                "acceptedFps=${"%.1f".format(acceptedFps)}, renderedFps=${"%.1f".format(renderedFps)}, " +
                "pendingDrops=$statsReplacedPendingFrames, avgRenderMs=${"%.1f".format(avgRenderMs)}, " +
                "maxRenderMs=$statsRenderTimeMaxMs"
        )

        statsWindowStartMs = nowMs
        statsIncomingFrames = 0
        statsAcceptedFrames = 0
        statsReplacedPendingFrames = 0
        statsRenderedFrames = 0
        statsRenderTimeTotalMs = 0L
        statsRenderTimeMaxMs = 0L
    }

    private fun startDrains() {
        videoDrainJob = scope.launch {
            val bufferInfo = MediaCodec.BufferInfo()
            while (isActive) {
                if (!drainVideoEncoderOnce(bufferInfo)) {
                    break
                }
            }
        }

        if (audioEnabled) {
            audioDrainJob = scope.launch {
                val bufferInfo = MediaCodec.BufferInfo()
                while (isActive) {
                    if (!drainAudioEncoderOnce(bufferInfo)) {
                        break
                    }
                }
            }
        }
    }

    private fun drainVideoEncoderOnce(bufferInfo: MediaCodec.BufferInfo): Boolean {
        return synchronized(videoCodecLock) {
            val encoder = videoEncoder ?: return@synchronized false
            val index = try {
                encoder.dequeueOutputBuffer(bufferInfo, 10_000L)
            } catch (_: IllegalStateException) {
                return@synchronized false
            }
            drainEncoderOutput(
                encoder = encoder,
                bufferInfo = bufferInfo,
                index = index,
                isVideo = true
            )
        }
    }

    private fun drainAudioEncoderOnce(bufferInfo: MediaCodec.BufferInfo): Boolean {
        return synchronized(audioCodecLock) {
            val encoder = audioEncoder ?: return@synchronized false
            val index = try {
                encoder.dequeueOutputBuffer(bufferInfo, 10_000L)
            } catch (_: IllegalStateException) {
                return@synchronized false
            }
            drainEncoderOutput(
                encoder = encoder,
                bufferInfo = bufferInfo,
                index = index,
                isVideo = false
            )
        }
    }

    private fun drainEncoderOutput(
        encoder: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        index: Int,
        isVideo: Boolean
    ): Boolean {
        when {
            index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                synchronized(muxerLock) {
                    if (isVideo) {
                        videoFormat = encoder.outputFormat
                    } else {
                        audioFormat = encoder.outputFormat
                    }
                    maybeStartMuxerLocked()
                }
            }
            index >= 0 -> {
                val outputBuffer = try {
                    encoder.getOutputBuffer(index)
                } catch (_: IllegalStateException) {
                    return false
                }
                if (outputBuffer != null && bufferInfo.size > 0) {
                    writeSample(
                        isVideo = isVideo,
                        buffer = outputBuffer,
                        info = bufferInfo
                    )
                }
                val isEos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                try {
                    encoder.releaseOutputBuffer(index, false)
                } catch (e: IllegalStateException) {
                    PLog.w(TAG, "Encoder output buffer release skipped during codec state change: ${e.message}")
                    return false
                }
                if (isEos) {
                    return false
                }
            }
        }
        return true
    }

    private fun queueAudioEndOfStream() {
        synchronized(audioCodecLock) {
            val encoder = audioEncoder ?: return
            val inputIndex = try {
                encoder.dequeueInputBuffer(10_000L)
            } catch (_: IllegalStateException) {
                return
            }
            if (inputIndex >= 0) {
                try {
                    val endOfStreamTimeUs = audioEndPresentationTimeUs
                        .takeIf { it != UNSET_TIMESTAMP }
                        ?: SystemClock.elapsedRealtimeNanos() / 1_000L
                    encoder.queueInputBuffer(
                        inputIndex,
                        0,
                        0,
                        endOfStreamTimeUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                } catch (_: IllegalStateException) {
                }
            }
        }
    }

    private fun signalVideoEndOfInputStream() {
        synchronized(videoCodecLock) {
            try {
                videoEncoder?.signalEndOfInputStream()
            } catch (e: IllegalStateException) {
                PLog.w(TAG, "Video encoder EOS signal skipped during codec state change: ${e.message}")
            }
        }
    }

    private fun writeSample(
        isVideo: Boolean,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo
    ) {
        if (info.size <= 0 ||
            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        ) {
            return
        }
        synchronized(muxerLock) {
            val timelineReady = synchronized(timelineLock) {
                resolveTimelineOriginLocked() != UNSET_TIMESTAMP
            }
            if (!muxerStarted || !timelineReady) {
                val pending = if (isVideo) pendingVideoSamples else pendingAudioSamples
                pending += copyEncodedSample(buffer = buffer, info = info)
                return
            }

            flushPendingSamplesLocked()
            if (sharedTimelineVideoEndReporter != null) {
                enqueueSpecialVideoTailSampleLocked(isVideo, buffer, info)
            } else {
                val sampleBuffer = buffer.duplicate()
                sampleBuffer.position(info.offset)
                sampleBuffer.limit(info.offset + info.size)
                writeEncodedSampleLocked(
                    isVideo = isVideo,
                    buffer = sampleBuffer.slice(),
                    info = info,
                )
            }
        }
    }

    private fun copyEncodedSample(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo
    ): EncodedSample {
        val sampleBytes = ByteArray(info.size)
        val duplicate = buffer.duplicate()
        duplicate.position(info.offset)
        duplicate.limit(info.offset + info.size)
        duplicate.get(sampleBytes)
        val copiedInfo = MediaCodec.BufferInfo().apply {
            set(0, info.size, info.presentationTimeUs, info.flags)
        }
        return EncodedSample(
            data = sampleBytes,
            info = copiedInfo,
        )
    }

    private fun normalizeSampleInfo(
        isVideo: Boolean,
        info: MediaCodec.BufferInfo,
        maximumPresentationTimeUs: Long? = null,
    ): MediaCodec.BufferInfo? {
        val normalizedSourceTimeUs = synchronized(timelineLock) {
            val originUs = resolveTimelineOriginLocked()
            if (originUs == UNSET_TIMESTAMP ||
                info.presentationTimeUs < originUs ||
                (sharedTimelineStopTimeUs != UNSET_TIMESTAMP &&
                    info.presentationTimeUs > sharedTimelineStopTimeUs)
            ) {
                return null
            }
            var normalizedTimeUs = info.presentationTimeUs - originUs -
                pausedDurationBetweenLocked(originUs, info.presentationTimeUs)
            if (sharedTimelineVideoEndReporter != null) {
                if (sharedVideoOutputOffsetUs == UNSET_TIMESTAMP) {
                    val firstVideoPtsUs = firstSharedVideoSourcePtsUs
                    if (firstVideoPtsUs == UNSET_TIMESTAMP) return null
                    sharedVideoOutputOffsetUs = firstVideoPtsUs - originUs -
                        pausedDurationBetweenLocked(originUs, firstVideoPtsUs)
                }
                normalizedTimeUs -= sharedVideoOutputOffsetUs
            }
            if (maximumPresentationTimeUs != null && normalizedTimeUs > maximumPresentationTimeUs) {
                return null
            }
            normalizedTimeUs
        }
        val lastPresentationTimeUs = if (isVideo) {
            lastMuxedVideoPresentationTimeUs
        } else {
            lastMuxedAudioPresentationTimeUs
        }
        val sanitizedPresentationTimeUs = if (lastPresentationTimeUs == Long.MIN_VALUE) {
            normalizedSourceTimeUs.coerceAtLeast(0L)
        } else {
            normalizedSourceTimeUs.coerceAtLeast(lastPresentationTimeUs + 1L)
        }
        val sanitizedInfo = MediaCodec.BufferInfo().apply {
            set(0, info.size, sanitizedPresentationTimeUs, info.flags)
        }
        if (isVideo) {
            lastMuxedVideoPresentationTimeUs = sanitizedPresentationTimeUs
        } else {
            lastMuxedAudioPresentationTimeUs = sanitizedPresentationTimeUs
        }
        return sanitizedInfo
    }

    private fun pausedDurationBetweenLocked(startTimeUs: Long, endTimeUs: Long): Long {
        if (endTimeUs <= startTimeUs) return 0L
        var pausedDurationUs = 0L
        pauseIntervals.forEach { pause ->
            val overlapStartUs = maxOf(startTimeUs, pause.startTimeUs)
            val overlapEndUs = minOf(endTimeUs, pause.endTimeUs)
            if (overlapEndUs > overlapStartUs) {
                pausedDurationUs += overlapEndUs - overlapStartUs
            }
        }
        val activePauseStartUs = activePauseStartTimeUs
        if (activePauseStartUs != UNSET_TIMESTAMP) {
            val overlapStartUs = maxOf(startTimeUs, activePauseStartUs)
            if (endTimeUs > overlapStartUs) {
                pausedDurationUs += endTimeUs - overlapStartUs
            }
        }
        return pausedDurationUs
    }

    /**
     * Keep only a short encoded tail for paired Special Video. The samples outside that
     * window are already guaranteed to precede the other stream's end when the pair differs
     * by less than the safety limit, so they can be muxed without retaining the whole clip.
     */
    private fun enqueueSpecialVideoTailSampleLocked(
        isVideo: Boolean,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
    ) {
        val sourcePtsUs = info.presentationTimeUs
        val shouldKeep = synchronized(timelineLock) {
            val originUs = resolveTimelineOriginLocked()
            originUs != UNSET_TIMESTAMP &&
                sourcePtsUs >= originUs &&
                (sharedTimelineStopTimeUs == UNSET_TIMESTAMP || sourcePtsUs <= sharedTimelineStopTimeUs)
        }
        if (!shouldKeep) return

        if (isVideo) {
            if (firstSharedVideoSourcePtsUs == UNSET_TIMESTAMP ||
                sourcePtsUs < firstSharedVideoSourcePtsUs
            ) {
                firstSharedVideoSourcePtsUs = sourcePtsUs
            }
            if (lastSharedVideoSourcePtsUs == UNSET_TIMESTAMP ||
                sourcePtsUs > lastSharedVideoSourcePtsUs
            ) {
                lastSharedVideoSourcePtsUs = sourcePtsUs
            }
        } else if (lastSharedAudioSourcePtsUs == UNSET_TIMESTAMP ||
            sourcePtsUs > lastSharedAudioSourcePtsUs
        ) {
            lastSharedAudioSourcePtsUs = sourcePtsUs
        }

        val sample = copyEncodedSample(buffer, info)
        val queue = if (isVideo) sharedVideoTailSamples else sharedAudioTailSamples
        queue.addLast(sample)
        sharedTailBytes += sample.data.size.toLong()

        if (isVideo) {
            flushSpecialVideoTailQueueLocked(
                sharedVideoTailSamples,
                isVideo = true,
                newestPtsUs = lastSharedVideoSourcePtsUs,
            )
            // Audio uses the same per-recorder video origin. Keep its tail until that
            // origin is known, then age it with the audio clock independently.
            if (firstSharedVideoSourcePtsUs != UNSET_TIMESTAMP &&
                lastSharedAudioSourcePtsUs != UNSET_TIMESTAMP
            ) {
                flushSpecialVideoTailQueueLocked(
                    sharedAudioTailSamples,
                    isVideo = false,
                    newestPtsUs = lastSharedAudioSourcePtsUs,
                )
            }
        } else if (firstSharedVideoSourcePtsUs != UNSET_TIMESTAMP) {
            flushSpecialVideoTailQueueLocked(
                sharedAudioTailSamples,
                isVideo = false,
                newestPtsUs = lastSharedAudioSourcePtsUs,
            )
        }
    }

    private fun flushSpecialVideoTailQueueLocked(
        queue: java.util.ArrayDeque<EncodedSample>,
        isVideo: Boolean,
        newestPtsUs: Long,
    ) {
        val flushBeforeUs = newestPtsUs - SPECIAL_VIDEO_TAIL_WINDOW_US
        while (queue.isNotEmpty()) {
            val oldest = queue.peekFirst()
            val olderThanTail = oldest.info.presentationTimeUs <= flushBeforeUs
            val overByteLimit = sharedTailBytes > sharedTailByteLimit
            if (!olderThanTail && !overByteLimit) break

            if (overByteLimit && !olderThanTail) {
                sharedTailOverflowed = true
                PLog.e(
                    SPECIAL_TIMELINE_TAG,
                    "Bounded tail buffer reached its byte limit; automatic pair trim is disabled. " +
                        "stream=${sharedTimelineStreamName ?: "paired"}, bytes=$sharedTailBytes, " +
                        "limit=$sharedTailByteLimit"
                )
            }
            queue.removeFirst()
            sharedTailBytes = (sharedTailBytes - oldest.data.size.toLong()).coerceAtLeast(0L)
            writeEncodedSampleLocked(
                isVideo = isVideo,
                buffer = ByteBuffer.wrap(oldest.data),
                info = oldest.info,
            )
        }
    }

    private fun normalizedSpecialVideoEndPtsUs(): Long? =
        normalizedSpecialSourcePtsUs(lastSharedVideoSourcePtsUs)

    private fun normalizedSpecialAudioEndPtsUs(): Long? =
        normalizedSpecialSourcePtsUs(lastSharedAudioSourcePtsUs)

    private fun normalizedSpecialSourcePtsUs(sourcePtsUs: Long): Long? {
        if (sourcePtsUs == UNSET_TIMESTAMP) return null
        return synchronized(timelineLock) {
            val originUs = resolveTimelineOriginLocked()
            val firstVideoPtsUs = firstSharedVideoSourcePtsUs
            if (originUs == UNSET_TIMESTAMP || firstVideoPtsUs == UNSET_TIMESTAMP ||
                sourcePtsUs < originUs ||
                (sharedTimelineStopTimeUs != UNSET_TIMESTAMP && sourcePtsUs > sharedTimelineStopTimeUs)
            ) {
                return@synchronized null
            }
            val outputOffsetUs = firstVideoPtsUs - originUs -
                pausedDurationBetweenLocked(originUs, firstVideoPtsUs)
            sourcePtsUs - originUs -
                pausedDurationBetweenLocked(originUs, sourcePtsUs) - outputOffsetUs
        }?.coerceAtLeast(0L)
    }

    /** Must be called under muxerLock after pending samples have been enqueued. */
    private fun flushSpecialVideoTailLocked(commonEndUs: Long?) {
        fun drain(queue: java.util.ArrayDeque<EncodedSample>, isVideo: Boolean) {
            while (queue.isNotEmpty()) {
                val sample = queue.removeFirst()
                sharedTailBytes = (sharedTailBytes - sample.data.size.toLong()).coerceAtLeast(0L)
                writeEncodedSampleLocked(
                    isVideo = isVideo,
                    buffer = ByteBuffer.wrap(sample.data),
                    info = sample.info,
                    maximumPresentationTimeUs = commonEndUs,
                )
            }
        }
        drain(sharedVideoTailSamples, isVideo = true)
        drain(sharedAudioTailSamples, isVideo = false)
        PLog.i(
            SPECIAL_TIMELINE_TAG,
            "Recorder tail finalized: stream=${sharedTimelineStreamName ?: "paired"}, " +
                "commonEndUs=$commonEndUs, videoEndPtsUs=$lastMuxedVideoPresentationTimeUs, " +
                "audioEndPtsUs=$lastMuxedAudioPresentationTimeUs, tailOverflowed=$sharedTailOverflowed"
        )
    }

    private fun writeEncodedSampleLocked(
        isVideo: Boolean,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        maximumPresentationTimeUs: Long? = null,
    ) {
        val trackIndex = if (isVideo) videoTrackIndex else audioTrackIndex
        if (trackIndex < 0) return
        val normalizedInfo = normalizeSampleInfo(
            isVideo = isVideo,
            info = info,
            maximumPresentationTimeUs = maximumPresentationTimeUs,
        ) ?: return
        try {
            muxer?.writeSampleData(trackIndex, buffer, normalizedInfo)
        } catch (e: Exception) {
            PLog.w(TAG, "Video recorder write sample error", e)
        }
    }

    private fun flushPendingSamplesLocked() {
        if (!muxerStarted) return
        val timelineReady = synchronized(timelineLock) {
            resolveTimelineOriginLocked() != UNSET_TIMESTAMP
        }
        if (!timelineReady) return

        pendingVideoSamples.forEach { sample ->
            if (sharedTimelineVideoEndReporter != null) {
                enqueueSpecialVideoTailSampleLocked(true, ByteBuffer.wrap(sample.data), sample.info)
            } else {
                writeEncodedSampleLocked(
                    isVideo = true,
                    buffer = ByteBuffer.wrap(sample.data),
                    info = sample.info,
                )
            }
        }
        pendingVideoSamples.clear()

        pendingAudioSamples.forEach { sample ->
            if (sharedTimelineVideoEndReporter != null) {
                enqueueSpecialVideoTailSampleLocked(false, ByteBuffer.wrap(sample.data), sample.info)
            } else {
                writeEncodedSampleLocked(
                    isVideo = false,
                    buffer = ByteBuffer.wrap(sample.data),
                    info = sample.info,
                )
            }
        }
        pendingAudioSamples.clear()
    }

    private fun writeColorMetadata(localMuxer: MediaMuxer) {
        val profile = if (preparedEncoderColorConfig?.pipeline == VideoEncodedColorPipeline.CUSTOM_LOG) {
            requestedColorConfig.logProfile
        } else {
            VideoLogProfile.OFF
        }
        VideoColorMetadata.writeSample(localMuxer, colorMetadataTrackIndex, profile)
    }

    private fun maybeStartMuxerLocked() {
        if (muxerStarted) return
        val localMuxer = muxer ?: return
        val localVideoFormat = videoFormat ?: return
        val canStartWithAudio = !audioEnabled || audioFormat != null
        if (!canStartWithAudio) return

        videoTrackIndex = localMuxer.addTrack(localVideoFormat)
        if (audioEnabled && audioFormat != null) {
            audioTrackIndex = localMuxer.addTrack(audioFormat!!)
        }
        localMuxer.start()
        writeColorMetadata(localMuxer)
        muxerStarted = true
        flushPendingSamplesLocked()
    }

    private suspend fun finalizeOutput(): Uri? {
        val output = pendingVideoOutput
        val privateFile = privateOutputFile
        if (output == null && privateFile == null) return null
        var muxerStopSucceeded = false
        synchronized(muxerLock) {
            if (!muxerStarted && videoFormat != null) {
                maybeStartMuxerLocked()
                if (!muxerStarted) {
                    videoTrackIndex = muxer?.addTrack(videoFormat!!) ?: -1
                    muxer?.let {
                        it.start()
                        writeColorMetadata(it)
                    }
                    muxerStarted = true
                    flushPendingSamplesLocked()
                }
            }

            flushPendingSamplesLocked()
            if (sharedTimelineVideoEndReporter != null && sharedTimelineEndResolved) {
                flushSpecialVideoTailLocked(sharedTimelineResolvedCommonEndUs)
            }

            val finalDurationUs = maxOf(
                lastMuxedVideoPresentationTimeUs,
                lastMuxedAudioPresentationTimeUs,
            ).takeIf { it != Long.MIN_VALUE }
            val outputLabel = privateOutputFile?.name
                ?: pendingVideoOutput?.uri?.lastPathSegment
                ?: "MediaStore"
            PLog.i(
                TAG,
                "Final recording duration: output=$outputLabel, " +
                    "videoEndPtsUs=${lastMuxedVideoPresentationTimeUs.takeIf { it != Long.MIN_VALUE }}, " +
                    "audioEndPtsUs=${lastMuxedAudioPresentationTimeUs.takeIf { it != Long.MIN_VALUE }}, " +
                    "durationMs=${finalDurationUs?.div(1_000L) ?: "unknown"}"
            )

            try {
                if (muxerStarted) {
                    muxer?.stop()
                    muxerStopSucceeded = true
                }
            } catch (e: Exception) {
                PLog.w(TAG, "Failed to stop muxer cleanly: ${e.message}")
            } finally {
                try {
                    muxer?.release()
                } catch (_: Exception) {
                }
                muxer = null
            }
        }

        try {
            output?.descriptor?.close()
        } catch (_: Exception) {
        }

        if (!muxerStopSucceeded) {
            output?.let { VideoMediaStoreWriter.discardPendingVideo(context, it) }
            privateFile?.delete()
            pendingVideoOutput = null
            privateOutputFile = null
            return null
        }

        val uri = if (privateFile != null) {
            if (privateFile.isFile && privateFile.length() > 0L) Uri.fromFile(privateFile) else null
        } else {
            output?.let { VideoMediaStoreWriter.publishPendingVideo(context, it) }
        }
        if (uri == null) {
            output?.let { VideoMediaStoreWriter.discardPendingVideo(context, it) }
            privateFile?.delete()
        }
        pendingVideoOutput = null
        privateOutputFile = null
        return uri
    }

    private suspend fun releaseCaptureResources() {
        videoDrainJob?.cancel()
        audioDrainJob?.cancel()
        audioRecordJob?.cancel()
        videoDrainJob?.join()
        audioDrainJob?.join()
        videoDrainJob = null
        audioDrainJob = null
        audioRecordJob = null

        withContext(renderDispatcher) {
            try {
                renderer?.release()
            } catch (_: Exception) {
            }
            renderer = null
            inputSurface?.release()
            inputSurface = null
            synchronized(videoCodecLock) {
                try {
                    videoEncoder?.stop()
                } catch (_: Exception) {
                }
                try {
                    videoEncoder?.release()
                } catch (_: Exception) {
                }
                videoEncoder = null
            }
        }

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null

        synchronized(audioCodecLock) {
            try {
                audioEncoder?.stop()
            } catch (_: Exception) {
            }
            try {
                audioEncoder?.release()
            } catch (_: Exception) {
            }
            audioEncoder = null
        }
    }

    private suspend fun cleanup() {
        releaseCaptureResources()

        resetMuxerState()
        finishCallback = null
        pendingVideoOutput?.let { output ->
            try {
                output.descriptor.close()
            } catch (_: Exception) {
            }
            VideoMediaStoreWriter.discardPendingVideo(context, output)
        }
        pendingVideoOutput = null
        privateOutputFile?.delete()
        privateOutputFile = null
        isRecording = false
        stopRequested = false
    }

    private fun resetMuxerState() {
        synchronized(muxerLock) {
            pendingVideoSamples = mutableListOf()
            pendingAudioSamples = mutableListOf()
            sharedVideoTailSamples = java.util.ArrayDeque()
            sharedAudioTailSamples = java.util.ArrayDeque()
            sharedTailBytes = 0L
            sharedTailOverflowed = false
            firstSharedVideoSourcePtsUs = UNSET_TIMESTAMP
            lastSharedVideoSourcePtsUs = UNSET_TIMESTAMP
            lastSharedAudioSourcePtsUs = UNSET_TIMESTAMP
            sharedVideoOutputOffsetUs = UNSET_TIMESTAMP
            sharedTimelineEndResolved = false
            sharedTimelineResolvedCommonEndUs = null
            videoTrackIndex = -1
            audioTrackIndex = -1
            colorMetadataTrackIndex = -1
            muxerStarted = false
            videoFormat = null
            audioFormat = null
            audioFramesRead = 0L
            audioFallbackStartTimeNs = UNSET_TIMESTAMP
            firstAudioCaptureTimeUs = UNSET_TIMESTAMP
            lastQueuedAudioPresentationTimeUs = UNSET_TIMESTAMP
            audioEndPresentationTimeUs = UNSET_TIMESTAMP
            activeAudioConfig = monoAudioConfig()
            lastMuxedVideoPresentationTimeUs = Long.MIN_VALUE
            lastMuxedAudioPresentationTimeUs = Long.MIN_VALUE
        }
        synchronized(timelineLock) {
            cameraTimestampToBoottimeOffsetNs = UNSET_TIMESTAMP
            timelineOriginUs = UNSET_TIMESTAMP
            sharedTimelineOriginProvider = null
            sharedTimelineStopTimeProvider = null
            onSharedTimelineFirstFrame = null
            usesSharedTimelineOrigin = false
            sharedTimelineVideoEndReporter = null
            sharedTimelineCommonEndAwaiter = null
            sharedTimelineDrainAwaiter = null
            sharedTimelineDrainCompleter = null
            sharedCameraTimestampOffsetResolver = null
            onSharedTimelineDrainComplete = null
            sharedTimelineStreamName = null
            sharedTimelineDrainDurationMs = 0L
            sharedTimelineStopTimeUs = UNSET_TIMESTAMP
            pauseIntervals = mutableListOf()
            activePauseStartTimeUs = UNSET_TIMESTAMP
        }
        synchronized(realtimeFrameLock) {
            pendingRealtimeFrameCount = 0
            renderLoopRunning = false
            acceptStopDrainFrames = false
        }
    }

    private fun cleanupPreparedStart() {
        runBlocking(renderDispatcher) {
            try {
                renderer?.release()
            } catch (_: Exception) {
            }
        }
        renderer = null
        try {
            inputSurface?.release()
        } catch (_: Exception) {
        }
        inputSurface = null
        try {
            videoEncoder?.stop()
        } catch (_: Exception) {
        }
        try {
            videoEncoder?.release()
        } catch (_: Exception) {
        }
        videoEncoder = null
        preparedEncoderColorConfig = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        try {
            audioEncoder?.stop()
        } catch (_: Exception) {
        }
        try {
            audioEncoder?.release()
        } catch (_: Exception) {
        }
        audioEncoder = null
        pendingVideoOutput?.let { output ->
            try {
                output.descriptor.close()
            } catch (_: Exception) {
            }
            VideoMediaStoreWriter.discardPendingVideo(context, output)
        }
        pendingVideoOutput = null
        privateOutputFile?.delete()
        privateOutputFile = null
        try {
            muxer?.release()
        } catch (_: Exception) {
        }
        muxer = null
        isRecording = false
        stopRequested = false
        resetMuxerState()
    }

    fun release() {
        if (isRecording || renderer != null || videoEncoder != null || audioEncoder != null) {
            stopRequested = true
            runBlocking {
                cleanup()
            }
        }
        scope.cancel()
        renderDispatcher.close()
    }
}

private fun Int.align16(): Int {
    return (this / 16 * 16).coerceAtLeast(16)
}

private fun normalizeOrientationHint(degrees: Int): Int {
    val normalized = ((degrees % 360) + 360) % 360
    return (normalized / 90) * 90
}
