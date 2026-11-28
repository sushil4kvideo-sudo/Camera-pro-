package com.hinnka.mycamera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ColorSpace
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Range
import android.util.Rational
import android.util.Size
import android.view.Surface
import android.net.Uri
import java.io.File
import androidx.exifinterface.media.ExifInterface
import com.hinnka.mycamera.raw.ColorSpace as RawColorSpace
import com.hinnka.mycamera.raw.DngSdkColorSpec
import com.hinnka.mycamera.raw.RawEnginePreviewSource
import com.hinnka.mycamera.raw.RawCameraCalibration
import com.hinnka.mycamera.raw.RawColorCalibrationPolicy
import com.hinnka.mycamera.utils.PLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import com.hinnka.mycamera.livephoto.LivePhotoRecorder
import com.hinnka.mycamera.lut.LutConfig
import com.hinnka.mycamera.lut.VideoColorEffectLayer
import com.hinnka.mycamera.model.SafeImage
import com.hinnka.mycamera.model.ColorRecipeParams
import com.hinnka.mycamera.processor.MgcRawMaxMode
import com.hinnka.mycamera.stabilization.ExternalLensStabilizationConfig
import com.hinnka.mycamera.stabilization.RealtimeStabilizationCoordinator
import com.hinnka.mycamera.stabilization.StabilizationImageReader
import com.hinnka.mycamera.stabilization.normalizeStabilizationLookahead
import com.hinnka.mycamera.stabilization.normalizeStabilizationStrength
import com.hinnka.mycamera.utils.DeviceUtil
import com.hinnka.mycamera.utils.OrientationObserver
import com.hinnka.mycamera.video.CaptureMode
import com.hinnka.mycamera.video.VideoAspectRatio
import com.hinnka.mycamera.video.VIDEO_AUDIO_INPUT_AUTO
import com.hinnka.mycamera.video.VideoBitratePreset
import com.hinnka.mycamera.video.VideoCapabilitiesResolver
import com.hinnka.mycamera.video.VideoFpsPreset
import com.hinnka.mycamera.video.VideoEncoderColorRequest
import com.hinnka.mycamera.video.VideoLogProfile
import com.hinnka.mycamera.video.OppoNaturalVideoEngine
import com.hinnka.mycamera.video.OppoHlg10AutoExposureEngine
import com.hinnka.mycamera.video.VideoLogLutMode
import com.hinnka.mycamera.video.DualCaptureLayout
import com.hinnka.mycamera.video.DualCaptureSource
import com.hinnka.mycamera.video.DualVideoAspectRatio
import com.hinnka.mycamera.video.VideoMediaStoreWriter
import com.hinnka.mycamera.video.VideoRecorder
import com.hinnka.mycamera.video.SpecialVideoRecordingTimeline
import com.hinnka.mycamera.video.SpatialVideoStore
import com.hinnka.mycamera.video.SpatialVideoPhysicalCameraCandidate
import com.hinnka.mycamera.video.resolveSpatialVideoPhysicalCameraPair
import com.hinnka.mycamera.video.VideoRecordingPath
import com.hinnka.mycamera.video.VideoResolutionPreset
import com.hinnka.mycamera.video.VideoRecordingState
import com.hinnka.mycamera.video.VideoStabilizationMode
import com.hinnka.mycamera.video.resolveSurfaceTextureVideoOrientationDegrees
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

sealed interface EnhancedStabilizationFallback {
    data class Video(val mode: VideoStabilizationMode) : EnhancedStabilizationFallback
    data object PhotoPreview : EnhancedStabilizationFallback
}

/**
 * Camera2 相机控制器
 *
 * 使用原生 Camera2 API 直接控制相机，支持：
 * - 绑定隐藏的物理摄像头（通过探测发现的 Camera ID）
 * - 手动曝光控制（ISO、快门速度）
 * - 变焦控制
 */
class Camera2Controller(private val context: Context) {

    companion object {
        private const val TAG = "Camera2Controller"
        private const val ULTRA_WIDE_AE_HANDOFF_MAX_AGE_MS = 900L

        // 自定义错误代码
        const val ERROR_CAMERA_DISCONNECTED = 1000
        const val ERROR_CAMERA_OPEN_FAILED = 1001
        const val ERROR_CAMERA_CHARACTERISTICS_UNAVAILABLE = 1002
        const val ERROR_CAMERA_SESSION_CONFIG_FAILED = 1003

        private const val CAPTURE_READER_MAX_IMAGES = 50
        private const val CAPTURE_IMAGE_DELIVERY_TIMEOUT_MS = 10_000L
        private const val BURST_CAPTURE_BATCH_SIZE = 8

        // 拍照状态机常量
        private const val PRECAPTURE_TIMEOUT_MS = 3_000L
        private const val PRECAPTURE_MAX_WAIT_FRAMES = 60L
        private const val MULTI_FRAME_TORCH_WARMUP_TIMEOUT_MS = 2_000L
        private const val EIS_PLUS_INPUT_WATCHDOG_MS = 2_000L
        private const val OPPO_SUPER_STABILIZATION_REAR_SESSION_MODE = 0x8028
        private const val OPPO_SUPER_STABILIZATION_FRONT_SESSION_MODE = 0x802B

        // 场景变化检测阈值
        private const val SCENE_CHANGE_EXPOSURE_RATIO = 1.5   // 曝光乘积变化判定为场景变化
        private const val SCENE_CHANGE_FOCUS_DISTANCE_DELTA = 0.2f // 焦距跳变阈值（diopters），对焦锁定后逐帧跟踪
        private const val FOCUS_LOCK_SETTLE_FRAMES = 5        // 对焦锁定后等待镜头稳定的帧数
        private const val SCENE_CHANGE_CONFIRM_FRAMES = 3     // 连续 N 帧检测到变化才确认
        private const val EYE_TARGET_RECENT_MS = 1_500L
        private const val POINT_EXPOSURE_LOCK_TIMEOUT_MS = 1_200L
        private const val AF_REGION_WIDTH_FRACTION = 0.10f
        private const val AF_REGION_HEIGHT_FRACTION = 0.10f
        private const val SPOT_AE_REGION_FRACTION = 0.06f
        private const val CENTER_WEIGHTED_AE_REGION_FRACTION = 0.40f
        private const val HIGHLIGHT_AE_REGION_FRACTION = 0.16f
        private const val DEFAULT_HYPERFOCAL_FOCAL_LENGTH_MM = 4.0f
        private const val DEFAULT_HYPERFOCAL_APERTURE = 1.8f
        private const val HYPERFOCAL_COC_DIAGONAL_DIVISOR = 1500.0
        private const val NO_IMAGE_READER_FORMAT = -1
        private const val AWB_TEMPERATURE_MIN = 2000
        private const val AWB_TEMPERATURE_MAX = 8000
        private val FORCED_VENDOR_SESSION_PARAMETER_KEYS = setOf(
            VendorCaptureKey.VIVO_FORCE_SENSOR_MODE
        )
    }

    private data class PhysicalOutputFailureKey(
        val openCameraId: String,
        val physicalCameraId: String,
        val captureMode: CaptureMode,
        val readerFormat: Int
    )

    private data class CameraOpenRequest(
        val surfaceTexture: SurfaceTexture,
        val preserveVideoRecording: Boolean,
    )

    /** Latest converged AE sample produced by the hidden ultra-wide physical output. */
    private data class UltraWideAeReading(
        val physicalCameraId: String,
        val exposureTimeNs: Long,
        val sensitivityIso: Int,
        val frameDurationNs: Long,
        val sampledAtElapsedRealtimeMs: Long,
    )

    private data class UltraWideAeHandoffRequestTag(val physicalCameraId: String)

    private data class CaptureModeReconfigureRequest(
        val generation: Int,
        val surfaceTexture: SurfaceTexture,
        val targetCaptureMode: CaptureMode,
        val tapTimestampMs: Long?,
        val onStarted: ((sessionStartElapsedRealtimeMs: Long, tapTimestampMs: Long?) -> Unit)?,
    )

    private enum class CameraDeviceLifecycle {
        CLOSED,
        OPENING,
        OPEN,
        CLOSING,
    }

    private data class HyperfocalFocusResult(
        val cameraId: String,
        val focalLengthMm: Float,
        val aperture: Float,
        val circleOfConfusionMm: Float,
        val distanceMeters: Float,
        val focusDistanceDiopters: Float
    )

    private data class WhiteBalanceResultSnapshot(
        val awbMode: Int,
        val colorTemperature: Int?,
        val colorTint: Int?,
        val gains: RggbChannelVector?,
        val transform: ColorSpaceTransform?
    )

    private data class ManualWhiteBalanceAnchor(
        val controlPath: WhiteBalanceControlPath,
        val baseTemperature: Int,
        val colorTint: Int?,
        val gains: RggbChannelVector?,
        val transform: ColorSpaceTransform?
    )

    private data class NormalizedRgb(
        val red: Float,
        val green: Float,
        val blue: Float
    )

    private data class InitialSessionParametersResult(
        val applied: Boolean,
        val usedVendorParameters: Boolean
    )

    private enum class StillFlashPhase {
        APPLYING_SESSION,
        WAITING_AF_CONVERGENCE,
        WAITING_AE_AF,
    }

    private data class StillFlashRequestTag(
        val generation: Long,
        val phase: StillFlashPhase,
        val isTrigger: Boolean,
    )

    private data class StillFlashPrecapture(
        val generation: Long,
        val afMode: Int,
        val triggerAf: Boolean,
        var phase: StillFlashPhase = StillFlashPhase.APPLYING_SESSION,
        var triggerFrameNumber: Long? = null,
        var triggerSequenceId: Int? = null,
        var repeatingSequenceId: Int? = null,
        var firstResultFrameNumber: Long? = null,
        var lastResult: TotalCaptureResult? = null,
        var aeReady: Boolean = false,
        var afReady: Boolean = false,
    )

    private data class MultiFrameTorchWarmupRequestTag(
        val generation: Long,
        val isAePrecaptureTrigger: Boolean = false,
    )

    private enum class WhiteBalanceControlPath {
        CCT,
        MATRIX,
        UNAVAILABLE
    }

    private val cameraManager: CameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private val cameraDiscovery = CameraDiscovery(context)
    // --- 拍照状态机相关 ---

    // 缓存拍照所需的设备和 Reader，供状态机回调使用
    private var pendingCaptureDevice: CameraDevice? = null
    private var pendingCaptureReader: ImageReader? = null
    private var pendingCaptureBaseExposureResult: CaptureResult? = null
    private var precaptureGeneration = 0L
    private var stillFlashPrecapture: StillFlashPrecapture? = null
    private var stillFlashCaptureState: CameraState? = null
    // Keep the capture-owned AF mode until the still request finishes and AF is cancelled.
    private var stillFlashAfMode: Int? = null
    private var stillFlashFocusDistance: Float? = null
    private var precaptureTimeoutRunnable: Runnable? = null
    private var multiFrameTorchWarmupGeneration = 0L
    private var activeMultiFrameTorchWarmupGeneration = 0L
    private var multiFrameTorchWarmupTimeoutRunnable: Runnable? = null
    private var lastMultiFrameTorchWarmupResult: TotalCaptureResult? = null
    private var pendingMultiFrameTorchWarmupAction: ((TotalCaptureResult?) -> Unit)? = null
    private var multiFrameTorchWarmupNeedsAePrecapture = false
    private var multiFrameTorchWarmupPrecaptureSubmitted = false
    private var multiFrameTorchWarmupTriggerResultSeen = false
    private var isMultiFrameTorchCaptureActive = false
    private var isContinuousBurstTorchActive = false
    private var livePhotoVideoStartTimestampUs: Long? = null
    @Volatile
    private var isCaptureFocusFrozen = false
    // ---------------------

    private var cameraDevice: CameraDevice? = null
    private var cameraDeviceLifecycle = CameraDeviceLifecycle.CLOSED
    // Track all retired devices by identity. A late onClosed callback from an older lens
    // must never reset the lifecycle of a newer active CameraDevice.
    private val closingCameraDevices = java.util.Collections.newSetFromMap(
        java.util.IdentityHashMap<CameraDevice, Boolean>()
    )
    private var lifecycleClosingCameraDevice: CameraDevice? = null
    private var pendingCameraOpenRequest: CameraOpenRequest? = null
    private val captureModeReconfigureGeneration = AtomicInteger()
    @Volatile
    private var pendingCaptureModeReconfigureRequest: CaptureModeReconfigureRequest? = null
    @Volatile
    private var captureModeReconfiguringSurfaceTexture: SurfaceTexture? = null
    @Volatile
    private var releaseCloseLatch: java.util.concurrent.CountDownLatch? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var previewSessionGeneration: Long = 0L
    private val previewUpdateScheduled = AtomicBoolean(false)
    private var pendingVendorSessionParameterRestart = false
    private var oppoSuperStabilizationEnabled = false

    private var previewSurface: Surface? = null
    private var previewSurfaceTexture: SurfaceTexture? = null
    private var dualCaptureEnabled = false
    private var spatialVideoEnabled = false

    @Volatile private var dualAspectRecordingActive = false
    private var dualAspectPrimaryFinishReceived = false
    private var dualAspectSecondaryFinishReceived = false
    private var dualAspectPrimaryFinishedUri: Uri? = null
    private var dualAspectSecondaryFinishedFile: File? = null
    private var dualAspectCompanionFile: File? = null
    private var dualAspectOutputRatio: DualVideoAspectRatio? = null
    private var dualAspectOutputDateTakenMs = 0L
    private var dualAspectOutputRecordingPath = VideoRecordingPath.DCIM_PHOTON
    private var dualAspectOutputTreeUri: String? = null
    private var dualAspectFinalizing = false

    private var spatialVideoMainPhysicalCameraId: String? = null
    private var spatialVideoUltraWidePhysicalCameraId: String? = null
    private var spatialVideoPrimaryCameraId: String? = null
    private var spatialVideoStreamingMode: SpatialVideoStreamingMode? = null
    // A dedicated alternate OES input feeds the normal-video encoder when 0.5x is selected.
    private var normalVideoUltraWideInstantSwitchEnabled = false
    private var normalVideoUltraWideInstantSwitchActive = false
    private var normalVideoMainPhysicalCameraId: String? = null
    private var normalVideoUltraWidePhysicalCameraId: String? = null
    private var normalVideoPrimaryCameraId: String? = null
    private var normalVideoStreamingMode: SpatialVideoStreamingMode? = null
    private val _normalVideoUltraWideInstantSwitchActive = MutableStateFlow(false)
    val normalVideoUltraWideInstantSwitchActiveFlow: StateFlow<Boolean> =
        _normalVideoUltraWideInstantSwitchActive.asStateFlow()
    @Volatile private var spatialVideoUnsupportedReason: String? = null
    @Volatile private var spatialVideoPrimaryReopenInProgress = false
    @Volatile private var spatialPrimaryFinishedUri: Uri? = null
    @Volatile private var spatialUltraWideFinishedFile: File? = null
    @Volatile private var spatialTelephotoFinishedFile: File? = null
    @Volatile private var spatialVideoCompanionFile: File? = null
    @Volatile private var spatialVideoTelephotoCompanionFile: File? = null
    private var spatialVideoStandbyCameraDevice: CameraDevice? = null
    private var spatialVideoStandbyOpeningCameraId: String? = null
    private var spatialVideoStandbySession: CameraCaptureSession? = null
    private var spatialVideoStandbyRepeatingRequest: CaptureRequest? = null
    private var spatialVideoStandbyRequestBuilder: CaptureRequest.Builder? = null
    private var spatialVideoStandbyEdgeModes: IntArray = intArrayOf()
    private var spatialVideoStandbyNoiseReductionModes: IntArray = intArrayOf()
    private var spatialVideoPrimarySessionAwaitingStandby = false
    private var spatialVideoStandbySessionGeneration = 0L
    private val specialVideoRecordingTimeline = SpecialVideoRecordingTimeline()
    @Volatile private var spatialVideoWideFinishReceived = false
    @Volatile private var spatialVideoTelephotoFinishReceived = false
    @Volatile private var spatialVideoSavedCallback: (() -> Unit)? = null
    @Volatile private var specialVideoDrainDurationMs: Long = 0L
    private var spatialVideoStandbyPreviewSurfaceTexture: SurfaceTexture? = null
    private var spatialVideoStandbyPreviewSurface: Surface? = null
    private var spatialVideoStandbyInputSize: Size? = null
    private val specialVideoPreferences = context.getSharedPreferences("special_video_settings", Context.MODE_PRIVATE)
    private val _topOffZoomEnabled = MutableStateFlow(
        specialVideoPreferences.getBoolean("top_off_zoom_enabled", false)
    )
    val topOffZoomEnabledFlow: StateFlow<Boolean> = _topOffZoomEnabled.asStateFlow()
    private val _topOffPositionLockActive = MutableStateFlow(false)
    val topOffPositionLockActiveFlow: StateFlow<Boolean> = _topOffPositionLockActive.asStateFlow()
    @Volatile
    private var topOffPositionLockCapabilityCacheKey: String? = null
    @Volatile
    private var topOffPositionLockCapabilityCacheAvailable = false

    private val _topOffZoomFactor = MutableStateFlow(
        specialVideoPreferences.getFloat("top_off_zoom_factor", 2f).coerceIn(1f, TopOffPanPreset.MAX_ZOOM_RATIO)
    )
    val topOffZoomFactorFlow: StateFlow<Float> = _topOffZoomFactor.asStateFlow()
    private val _topOffZoomLockedRatio = MutableStateFlow(
        specialVideoPreferences.getFloat("top_off_zoom_locked_ratio", Float.NaN)
            .takeIf { it.isFinite() && it >= 1f } ?: Float.NaN
    )
    val topOffZoomLockedRatioFlow: StateFlow<Float> = _topOffZoomLockedRatio.asStateFlow()
    @Volatile private var lastTopOffZoomDiagnosticSignature: String? = null
    private var lastTopOffZoomPanDiagnosticAtMs = 0L
    private var lastTopOffZoomCaptureResultLogAtMs = 0L
    private val _topOffZoomPanX = MutableStateFlow(
        specialVideoPreferences.getFloat("top_off_zoom_pan_x", 0f).coerceIn(-1f, 1f)
    )
    val topOffZoomPanXFlow: StateFlow<Float> = _topOffZoomPanX.asStateFlow()
    private val _topOffZoomPanY = MutableStateFlow(
        specialVideoPreferences.getFloat("top_off_zoom_pan_y", 0f).coerceIn(-1f, 1f)
    )
    val topOffZoomPanYFlow: StateFlow<Float> = _topOffZoomPanY.asStateFlow()
    private val _topOffZoomRollDegrees = MutableStateFlow(
        specialVideoPreferences.getFloat("top_off_zoom_roll_degrees", 0f)
            .takeIf { it.isFinite() }?.coerceIn(-20f, 20f) ?: 0f
    )
    val topOffZoomRollDegreesFlow: StateFlow<Float> = _topOffZoomRollDegrees.asStateFlow()
    private val _topOffJoystickSpeed = MutableStateFlow(
        specialVideoPreferences.getFloat("top_off_joystick_speed", 1f).coerceIn(0.25f, 4f)
    )
    val topOffJoystickSpeedFlow: StateFlow<Float> = _topOffJoystickSpeed.asStateFlow()
    private val _topOffShowLatency = MutableStateFlow(
        specialVideoPreferences.getBoolean("top_off_show_latency", false)
    )
    val topOffShowLatencyFlow: StateFlow<Boolean> = _topOffShowLatency.asStateFlow()
    private val _topOffPanLatencyMs = MutableStateFlow(0f)
    val topOffPanLatencyMsFlow: StateFlow<Float> = _topOffPanLatencyMs.asStateFlow()
    private val _topOffPanPresets = MutableStateFlow(
        TopOffPanPresetCodec.decode(specialVideoPreferences.getString("top_off_pan_presets", null))
    )
    val topOffPanPresetsFlow: StateFlow<List<TopOffPanPreset>> = _topOffPanPresets.asStateFlow()
    private val _spatialVideoEnabled = MutableStateFlow(false)
    val spatialVideoEnabledFlow: StateFlow<Boolean> = _spatialVideoEnabled.asStateFlow()
    private val _spatialVideoShowUltraWidePreview = MutableStateFlow(
        specialVideoPreferences.getBoolean("show_ultra_wide_preview", false)
    )
    val spatialVideoShowUltraWidePreview: StateFlow<Boolean> = _spatialVideoShowUltraWidePreview.asStateFlow()
    private val _spatialVideoVirtualTelephotoEnabled = MutableStateFlow(
        specialVideoPreferences.getBoolean("virtual_telephoto_enabled", true)
    )
    val spatialVideoVirtualTelephotoEnabledFlow: StateFlow<Boolean> =
        _spatialVideoVirtualTelephotoEnabled.asStateFlow()
    private val spatialVideoVirtualTelephotoEnabled: Boolean
        get() = _spatialVideoVirtualTelephotoEnabled.value
    private val _spatialVideoPreviewStreamSize = MutableStateFlow<Size?>(null)
    val spatialVideoPreviewStreamSize: StateFlow<Size?> = _spatialVideoPreviewStreamSize.asStateFlow()
    private val _spatialVideoPreviewSensorOrientationDegrees = MutableStateFlow(0)
    val spatialVideoPreviewSensorOrientationDegrees: StateFlow<Int> =
        _spatialVideoPreviewSensorOrientationDegrees.asStateFlow()
    private fun isUltraWideStandbyPreviewActive(): Boolean =
        spatialVideoEnabled || normalVideoUltraWideInstantSwitchActive

    private fun currentUltraWideStandbyCameraId(): String? =
        spatialVideoUltraWidePhysicalCameraId?.takeIf { spatialVideoEnabled }
            ?: normalVideoUltraWidePhysicalCameraId?.takeIf { normalVideoUltraWideInstantSwitchActive }

    private fun currentUltraWideStandbyStreamingMode(): SpatialVideoStreamingMode? =
        spatialVideoStreamingMode.takeIf { spatialVideoEnabled }
            ?: normalVideoStreamingMode.takeIf { normalVideoUltraWideInstantSwitchActive }

    private fun cameraBelongsToSpatialPair(camera: CameraInfo?, pair: SpatialVideoCameraPair): Boolean {
        camera ?: return false
        if (camera.lensFacing != CameraCharacteristics.LENS_FACING_BACK) return false
        val ids = buildSet {
            add(camera.cameraId)
            camera.outputPhysicalCameraId?.takeIf { it.isNotBlank() }?.let(::add)
            runCatching { camera.getOpenCameraId() }.getOrNull()?.takeIf { it.isNotBlank() }?.let(::add)
            camera.physicalCameras.mapTo(this) { it.cameraId }
        }
        val mainIds = buildSet {
            add(pair.mainCameraInfo.cameraId)
            pair.mainPhysicalCameraId?.takeIf { it.isNotBlank() }?.let(::add)
            pair.mainCameraInfo.outputPhysicalCameraId?.takeIf { it.isNotBlank() }?.let(::add)
            pair.mainCameraInfo.physicalCameras.mapTo(this) { it.cameraId }
        }
        val wideIds = buildSet {
            add(pair.ultraWideCameraId)
            _state.value.availableCameras.filter { info ->
                info.cameraId == pair.ultraWideCameraId ||
                    info.outputPhysicalCameraId == pair.ultraWideCameraId ||
                    info.physicalCameras.any { it.cameraId == pair.ultraWideCameraId }
            }.forEach { info ->
                add(info.cameraId)
                info.outputPhysicalCameraId?.takeIf { it.isNotBlank() }?.let(::add)
                info.physicalCameras.mapTo(this) { it.cameraId }
            }
        }
        return ids.any { it in mainIds || it in wideIds }
    }

    private fun isNormalVideoUltraWideLens(camera: CameraInfo?): Boolean {
        camera ?: return false
        val wideId = normalVideoUltraWidePhysicalCameraId ?: return false
        val openId = runCatching { camera.getOpenCameraId() }.getOrNull()
        return camera.lensFacing == CameraCharacteristics.LENS_FACING_BACK && (
            camera.lensType == LensType.BACK_ULTRA_WIDE ||
                camera.cameraId == wideId ||
                camera.outputPhysicalCameraId == wideId ||
                openId == wideId ||
                (camera.intrinsicZoomRatio.isFinite() && camera.intrinsicZoomRatio in 0.2f..0.75f)
            )
    }

    private fun isCurrentNormalVideoPairLens(camera: CameraInfo?): Boolean {
        camera ?: return false
        val mainId = normalVideoMainPhysicalCameraId ?: return false
        val wideId = normalVideoUltraWidePhysicalCameraId ?: return false
        val ids = buildSet {
            add(camera.cameraId)
            camera.outputPhysicalCameraId?.takeIf { it.isNotBlank() }?.let(::add)
            runCatching { camera.getOpenCameraId() }.getOrNull()?.takeIf { it.isNotBlank() }?.let(::add)
            camera.physicalCameras.mapTo(this) { it.cameraId }
        }
        val mainMatches = mainId in ids || camera.cameraId == normalVideoPrimaryCameraId ||
            camera.cameraId == _state.value.availableCameras.firstOrNull {
                it.outputPhysicalCameraId == mainId ||
                    it.physicalCameras.any { physical -> physical.cameraId == mainId }
            }?.cameraId
        return camera.lensFacing == CameraCharacteristics.LENS_FACING_BACK &&
            (mainMatches || wideId in ids)
    }

    private fun clearNormalVideoUltraWideInstantSwitchRuntime(reason: String, reopenSelectedCamera: Boolean) {
        if (!normalVideoUltraWideInstantSwitchActive) return
        if (videoRecorder.isRecording()) {
            // A missing standby stream must never leave the encoder waiting on an empty
            // alternate texture. Fall back to the main 1x source and finish the same MP4.
            videoRecorder.setSwitchableCameraInputActive(false)
        }
        val oldPrimaryId = normalVideoPrimaryCameraId
        normalVideoUltraWideInstantSwitchActive = false
        _normalVideoUltraWideInstantSwitchActive.value = false
        normalVideoMainPhysicalCameraId = null
        normalVideoUltraWidePhysicalCameraId = null
        normalVideoPrimaryCameraId = null
        normalVideoStreamingMode = null
        stopSpatialVideoStandbyCamera("normal video preview disabled: $reason")
        spatialVideoStandbyInputSize = null
        _spatialVideoPreviewStreamSize.value = null
        _spatialVideoPreviewSensorOrientationDegrees.value = 0
        val selected = _state.value.getCurrentCameraInfo()
        activeOutputPhysicalCameraId = resolveRequestedPhysicalCameraId(_state.value, selected)
        logicalMultiCameraAutoSwitchActive = !_topOffPositionLockActive.value &&
            shouldUseLogicalMultiCameraZoom(_state.value, selected)
        PLog.i(TAG, "Normal Video ultra-wide instant switch runtime cleared: reason=$reason")
        if (!reopenSelectedCamera || spatialVideoEnabled) return
        val texture = previewSurfaceTexture ?: return
        val selectedOpenId = selected?.let { runCatching { it.getOpenCameraId() }.getOrNull() }
        if (selectedOpenId != null && selectedOpenId != activeOpenCameraId &&
            (oldPrimaryId == activeOpenCameraId || activeOpenCameraId.isNotBlank())
        ) {
            openCamera(texture, preserveVideoRecording = true)
        } else if (cameraDevice != null) {
            createPreviewSession(openGeneration = cameraOpenGeneration)
        }
    }

    private fun activateNormalVideoUltraWideInstantSwitch(reopenPrimaryIfNeeded: Boolean): Boolean {
        val captureMode = _state.value.captureMode
        if (!normalVideoUltraWideInstantSwitchEnabled || normalVideoUltraWideInstantSwitchActive ||
            _topOffPositionLockActive.value || spatialVideoEnabled ||
            (captureMode != CaptureMode.VIDEO && captureMode != CaptureMode.PHOTO) ||
            _state.value.videoRecordingState.isRecording ||
            _state.value.videoRecordingState.isProcessing
        ) return normalVideoUltraWideInstantSwitchActive
        val selected = _state.value.getCurrentCameraInfo() ?: return false
        if (selected.lensFacing != CameraCharacteristics.LENS_FACING_BACK) return false
        val pair = resolveSpatialVideoConfiguration() ?: run {
            PLog.w(TAG, "Normal Video instant switch unavailable: ${spatialVideoUnsupportedReason ?: "rear 1x/0.5x pair not found"}")
            return false
        }
        if (!cameraBelongsToSpatialPair(selected, pair)) return false

        // Photo mode can share the logical CameraDevice with the ultra-wide physical
        // preview, but its ImageReader must remain on that logical device so still
        // captures follow CONTROL_ZOOM_RATIO. Do not enable this path for independent
        // CameraDevices or RAW configurations that cannot be served by the logical parent.
        val photoLogicalHandoffState = _state.value.copy(
            currentCameraId = pair.mainCameraInfo.cameraId,
            currentLensType = pair.mainCameraInfo.lensType,
        )
        val photoLogicalHandoffSupported = captureMode == CaptureMode.PHOTO &&
            pair.mode == SpatialVideoStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL &&
            shouldUseLogicalMultiCameraZoom(photoLogicalHandoffState, pair.mainCameraInfo)
        if (captureMode == CaptureMode.PHOTO && !photoLogicalHandoffSupported) {
            PLog.i(
                TAG,
                "Photo instant ultra-wide handoff unavailable: logical still-capture output is not supported"
            )
            return false
        }

        normalVideoMainPhysicalCameraId = pair.mainPhysicalCameraId
            ?: pair.mainCameraInfo.outputPhysicalCameraId ?: pair.mainCameraInfo.cameraId
        normalVideoUltraWidePhysicalCameraId = pair.ultraWideCameraId
        normalVideoPrimaryCameraId = pair.primaryCameraId
        normalVideoStreamingMode = pair.mode
        normalVideoUltraWideInstantSwitchActive = true
        _normalVideoUltraWideInstantSwitchActive.value = true
        if (dualCaptureEnabled) setDualCaptureEnabled(false)
        // Keep the 1x physical camera as the primary GL/encoder path; the wide TextureView
        // is already streaming in parallel and is only promoted by the UI on a lens tap.
        _state.value = _state.value.copy(
            currentCameraId = pair.mainCameraInfo.cameraId,
            currentLensType = pair.mainCameraInfo.lensType,
            zoomRatio = 1f,
            zoomPanX = 0f,
            zoomPanY = 0f,
            zoomPanEnabled = false,
        )
        activeOutputPhysicalCameraId = when {
            pair.mode == SpatialVideoStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL ->
                if (captureMode == CaptureMode.PHOTO) null else normalVideoMainPhysicalCameraId
            pair.logicalParentCameraId != null -> null
            else -> pair.mainCameraInfo.outputPhysicalCameraId
        }
        logicalMultiCameraAutoSwitchActive = photoLogicalHandoffSupported
        PLog.i(
            TAG,
            "Normal Video instant switch enabled: mode=${pair.mode}, primary=${pair.primaryCameraId}, " +
                "main=${normalVideoMainPhysicalCameraId}, ultraWide=${pair.ultraWideCameraId}"
        )
        if (reopenPrimaryIfNeeded) {
            val texture = previewSurfaceTexture
            if (texture != null) {
                val activeId = activeOpenCameraId.takeIf { it.isNotBlank() }
                if (activeId != pair.primaryCameraId) {
                    openCamera(texture, preserveVideoRecording = true)
                } else {
                    if (pair.mode != SpatialVideoStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL &&
                        cameraDevice != null
                    ) startSpatialVideoStandbyCamera(pair.ultraWideCameraId)
                    createPreviewSession(openGeneration = cameraOpenGeneration)
                }
            }
        }
        return true
    }

    private var dualCaptureLayout = DualCaptureLayout.PICTURE_IN_PICTURE
    private val _dualCaptureSource = MutableStateFlow(DualCaptureSource.ULTRA_WIDE)
    val dualCaptureSource: StateFlow<DualCaptureSource> = _dualCaptureSource.asStateFlow()
    private val _dualCaptureSensorOrientationDegrees = MutableStateFlow(0)
    val dualCaptureSensorOrientationDegrees: StateFlow<Int> = _dualCaptureSensorOrientationDegrees.asStateFlow()
    private var dualCapturePositionX = 0.66f
    private var dualCapturePositionY = 0.05f
    private var dualCapturePipWidthFraction = 0.33f
    private var dualCapturePreviewSurfaceTexture: SurfaceTexture? = null
    private var dualCapturePreviewSurface: Surface? = null
    private var dualCaptureRecordSurface: Surface? = null
    private var dualCaptureCameraDevice: CameraDevice? = null
    private var dualCaptureSession: CameraCaptureSession? = null
    private var dualCaptureCameraId: String? = null
    private var dualCapturePhysicalCameraId: String? = null
    private var dualCaptureMainPhysicalCameraId: String? = null
    // True when rear ultra-wide is opened as a second concurrent CameraDevice instead
    // of being routed as a physical output of the primary logical CameraDevice.
    private var dualCaptureUsesConcurrentUltraWideCamera = false
    private var dualCaptureSavedPrimaryCameraState: DualCapturePrimaryCameraState? = null
    private var dualCaptureInputSize: Size? = null
    private var dualCaptureSessionGeneration = 0L
    private data class DualCapturePrimaryCameraState(
        val physicalCameraId: String?,
        val logicalAutoSwitchActive: Boolean,
        val zoomRatio: Float,
        val zoomPanX: Float,
        val zoomPanY: Float,
        val zoomPanEnabled: Boolean,
    )
    private enum class SpatialVideoStreamingMode {
        LOGICAL_MULTI_CAMERA_PHYSICAL,
        CONCURRENT_DEVICES,
        PERSISTENT_DUAL_SESSION,
    }
    private data class SpatialVideoCameraPair(
        val mode: SpatialVideoStreamingMode,
        /** CameraDevice ID that must be opened for the primary 1x stream. */
        val primaryCameraId: String,
        val mainCameraInfo: CameraInfo,
        val mainPhysicalCameraId: String?,
        val ultraWideCameraId: String,
        /** Logical parent when primaryCameraId is a physical child of that parent. */
        val logicalParentCameraId: String? = null,
    )
    private val _dualCaptureStreamSize = MutableStateFlow<Size?>(null)
    val dualCaptureStreamSize: StateFlow<Size?> = _dualCaptureStreamSize.asStateFlow()
    private var imageReader: ImageReader? = null
    private var stabilizationImageReader: StabilizationImageReader? = null

    // A low-resolution physical ultra-wide YUV output keeps that sensor's 3A pipeline warm
    // while the main lens is displayed. Frames are drained immediately; only AE metadata is kept.
    private var keepUltraWideAeReady = true
    private var ultraWideAeMeteringReader: ImageReader? = null
    private var ultraWideAeMeteringReaderPhysicalCameraId: String? = null
    private var activeUltraWideAeMeteringPhysicalCameraId: String? = null
    private var ultraWideAeMeteringSessionAttached = false
    private var ultraWideAeMeteringSuppressed = false
    private var latestUltraWideAeReading: UltraWideAeReading? = null
    private var pendingUltraWideAeHandoff: UltraWideAeReading? = null
    private var ultraWideAeHandoffActiveForSession = false

    // 降噪等级 (0=Off, 1=Fast, 2=High Quality, 3=ZSL, 4=Minimal)
    private var nrLevel = NoiseReductionLevel.DEFAULT

    // 锐化等级 (0=Off, 1=Fast, 2=High Quality, 3=Zero Shutter Lag/Real-time)
    private var edgeLevel = 1

    private var videoNrLevel = NoiseReductionLevel.DEFAULT
    private var videoEdgeLevel = 1

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private val burstGyroRecorder = BurstGyroRecorder(context)
    private val oppoContinuousBurstEngine = OppoContinuousBurstEngine(::dispatchOppoBurstJpegFrame)
    @Volatile private var isOppoBurstYuvSession = false
    private var burstCaptureStateSnapshot: CameraState? = null
    private var burstCaptureCharacteristics: CameraCharacteristics? = null

    private val cameraCharacteristicsCache = ConcurrentHashMap<String, CameraCharacteristics>()
    private var cachedCharacteristics: CameraCharacteristics? = null
    private var cachedCharacteristicsCameraId: String = ""
    private var activeOpenCameraId: String = ""
    private var activeOutputPhysicalCameraId: String? = null
    private var logicalMultiCameraAutoSwitchActive = false
    private var lastLogicalMultiCameraStabilizationPhysicalId: String? = null
    private val failedPhysicalOutputProfiles = mutableSetOf<PhysicalOutputFailureKey>()
    private var cachedSensorOrientation: Int = 0
    private var cachedLensFacing: Int = CameraCharacteristics.LENS_FACING_BACK
    private var cachedHardwareLevel: Int = CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED
    private var isManualSensorSupported = false
    private var isManualPostProcessingSupported = false
    private var isFlashSupported = false
    private var maxAfRegions = 0
    private var maxAeRegions = 0
    private var pointMeteringViewAspectRatio: Float? = null
    private var availableAfModes: IntArray = intArrayOf()
    private var availableEdgeModes: IntArray = intArrayOf()
    private var availableNoiseReductionModes: IntArray = intArrayOf()
    private var availableTonemapModes: IntArray = intArrayOf()
    private var availableColorCorrectionAberrationModes: IntArray = intArrayOf()
    private var availableHotPixelModes: IntArray = intArrayOf()
    private var availableShadingModes: IntArray = intArrayOf()
    private var availableDistortionCorrectionModes: IntArray = intArrayOf()
    private var availableVideoStabilizationModes: IntArray = intArrayOf()
    private var availableOpticalStabilizationModes: IntArray = intArrayOf()
    private var availableLensShadingMapModes: IntArray = intArrayOf()
    private var availableFaceDetectModes: IntArray = intArrayOf()
    private var maxFaceCount: Int = 0
    private var faceDetectMode: Int = CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF
    private var availableColorCorrectionModes: IntArray = intArrayOf()
    private var awbColorTemperatureRange: Range<Int>? = null
    private var lastWhiteBalanceResult: WhiteBalanceResultSnapshot? = null
    private var manualWhiteBalanceAnchor: ManualWhiteBalanceAnchor? = null
    @Volatile
    private var requestedRawCaptureEnabled = false
    private var isRawSupported = false
    private var isP010Supported = false
    private var isStreamUseCaseSupported = false
    private var availableStreamUseCases: LongArray = longArrayOf()
    private var isZslControlSupported: Boolean? = null
    private var availableCaptureRequestKeyNames: Set<String>? = null
    private var availableAeModes: IntArray = intArrayOf()
    private var availableAwbModes: IntArray = intArrayOf()
    // API 36 AE exposure-time priority is enabled only when low-light AE tries to
    // exceed the selected video's frame interval. AE continues controlling ISO.
    private var videoExposureTimePriorityActive = false
    private var videoExposureTimePriorityFps = 0
    private var videoCaptureStatsWindowStartMs: Long = 0L
    private var videoCaptureStatsFrames: Int = 0
    private var videoCaptureStatsFirstTimestampNs: Long = 0L
    private var stabilizationInputStatsFirstTimestampNs: Long = 0L
    private var stabilizationInputStatsFrames: Int = 0
    private var stabilizationInputFrameCount: Long = 0L
    private var mirrorFrontCameraEnabled: Boolean = true
    @Volatile
    private var cameraOpenGeneration: Long = 0L

    private val _state = MutableStateFlow(CameraState())
    val state: StateFlow<CameraState> = _state.asStateFlow()

    @Volatile
    var retainCaptureSettingsEnabled: Boolean = false
        private set
    private var retainedCaptureSettings: CustomCaptureSettings? = null
    private var pendingRetainedAwbTemperature: Int? = null

    // Live Photo 录制器
    val livePhotoRecorder = LivePhotoRecorder(context)
    val realtimeStabilizationCoordinator = RealtimeStabilizationCoordinator(context)
    val videoRecorder = VideoRecorder(context, realtimeStabilizationCoordinator)
    private val spatialVideoRecorder = VideoRecorder(context, realtimeStabilizationCoordinator)
    private val spatialVideoTelephotoRecorder = VideoRecorder(context, realtimeStabilizationCoordinator)
    var onEnhancedStabilizationUnavailable: ((EnhancedStabilizationFallback) -> Unit)? = null
    var onVideoSaved: ((Uri?) -> Unit)? = null
    private var previousVideoStabilizationMode = VideoStabilizationMode.OIS
    private val enhancedStabilizationUnavailableCameraIds =
        ConcurrentHashMap.newKeySet<String>()

    init {
        realtimeStabilizationCoordinator.onEnhancedStabilizationUnavailable = {
            handleEnhancedStabilizationUnavailable(
                reason = "Camera HAL stabilization conflicts with algorithmic stabilization"
            )
        }
    }

    @Volatile private var videoCameraHlg10InputActive: Boolean = false
    private val oppoNaturalVideoEngine = OppoNaturalVideoEngine()
    private val oppoHlg10AutoExposureEngine = OppoHlg10AutoExposureEngine()
    private var lastOppoVideoProfileIspUpdateMs = 0L
    private var lastAppliedVideoProfile: VideoLogProfile? = null
    private var videoRecordingStartElapsedMs: Long = 0L
    private var videoRecordingPausedMs: Long = 0L
    private var videoRecordingPauseStartElapsedMs: Long = 0L
    private val videoRecordingTicker = object : Runnable {
        override fun run() {
            val recordingState = _state.value.videoRecordingState
            if (!recordingState.isRecording) return
            if (recordingState.isPaused) {
                cameraHandler?.postDelayed(this, 250)
                return
            }
            val elapsed =
                (SystemClock.elapsedRealtime() - videoRecordingStartElapsedMs - videoRecordingPausedMs).coerceAtLeast(
                    0L
                )
            _state.value = _state.value.copy(
                videoRecordingState = recordingState.copy(elapsedMs = elapsed)
            )
            cameraHandler?.postDelayed(this, 250)
        }
    }

    // 缓存 CaptureResult 和 Image 用于配对 (timestamp -> Data)
    private val pendingResults = ConcurrentHashMap<Long, TotalCaptureResult>()
    private val pendingImages = ConcurrentHashMap<Long, SafeImage>()
    private val pendingFrameMetadata = ConcurrentHashMap<Long, CapturedFrameMetadata>()
    private val pendingCaptureStartedTimestamps = ConcurrentHashMap<Long, Long>()
    private val pendingCloseReaders = mutableListOf<ImageReader>()
    private val openImagesCount = AtomicInteger(0)
    private var imageReaderMaxImages = CAPTURE_READER_MAX_IMAGES

    // Owned by cameraHandler. Acquired images are counted atomically until their
    // processing owner closes them; reservations cover buffers still in the HAL.
    private class PhotoCaptureContext(
        val id: Long,
        var state: CameraState,
        val reader: ImageReader,
        val expectedFrames: Int,
        val characteristics: CameraCharacteristics?,
        val physicalCameraId: String?,
        val continuousBurst: Boolean = false,
        val captureTime: Long = System.currentTimeMillis(),
        val exposureCompensationStep: Float,
    ) {
        var reservedFrames = expectedFrames
        var submitted = false
        var sequenceCompleted = false
        var failed = false
        var delivered = false
        var livePhotoStartTimestampUs: Long? = null
        var animationCaptureResult: TotalCaptureResult? = null
        var timeout: Runnable? = null
        val timestamps = mutableSetOf<Long>()
        val startedFrameNumbers = mutableSetOf<Long>()
        val expectedImageTimestamps = mutableSetOf<Long>()
        val timestampByFrameNumber = mutableMapOf<Long, Long>()
        val lostImageFrameNumbers = mutableSetOf<Long>()
        val acquiredTimestamps = mutableSetOf<Long>()
        val frames = sortedMapOf<Long, CapturedPhotoFrame>()
    }

    private var nextPhotoCaptureId = 0L
    private var activePhotoCapture: PhotoCaptureContext? = null
    private var pendingContinuousBurstCapture: PhotoCaptureContext? = null
    private val photoCaptures = mutableMapOf<Long, PhotoCaptureContext>()
    private val photoCaptureByTimestamp = mutableMapOf<Long, PhotoCaptureContext>()
    private val discardedCaptureTimestamps = linkedSetOf<Long>()

    private var burstCapturing = false
    @Volatile private var fastShutterTargetFps = 20
    // 保留最近的一个结果作为后备
    @Volatile
    private var lastCaptureResult: TotalCaptureResult? = null

    @Volatile
    private var enginePreviewEnabled = false
    private val _enginePreviewSource = MutableStateFlow<RawEnginePreviewSource?>(null)
    val enginePreviewSource: StateFlow<RawEnginePreviewSource?> = _enginePreviewSource.asStateFlow()
    private var enginePreviewCharacteristics: CameraCharacteristics? = null
    private var enginePreviewCalibration: RawCameraCalibration? = null

    fun setEnginePreviewMetadataEnabled(enabled: Boolean) {
        enginePreviewEnabled = enabled
        if (!enabled) _enginePreviewSource.value = null
    }

    private fun updateEnginePreviewSource(result: TotalCaptureResult) {
        if (!enginePreviewEnabled) return
        val physicalId = activeOutputPhysicalCameraId
            ?: result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
        val cameraId = physicalId ?: getActiveOpenCameraId()
        val frame = physicalId?.let { result.physicalCameraResults[it] } ?: result
        val characteristics = getCameraCharacteristicsOrNull(cameraId, "Engine preview") ?: run {
            _enginePreviewSource.value = null
            return
        }
        val gains = frame.get(CaptureResult.COLOR_CORRECTION_GAINS)
            ?: result.get(CaptureResult.COLOR_CORRECTION_GAINS)
        if (gains == null) {
            _enginePreviewSource.value = null
            return
        }
        if (enginePreviewCharacteristics !== characteristics) {
            enginePreviewCalibration = RawColorCalibrationPolicy.fromCharacteristics(characteristics).toRawCalibration()
            enginePreviewCharacteristics = characteristics
            PLog.d(TAG, "Engine preview source camera=$cameraId")
        }
        val mode = frame.get(CaptureResult.COLOR_CORRECTION_MODE)
            ?: result.get(CaptureResult.COLOR_CORRECTION_MODE)
        val transform = if (mode == CaptureResult.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX) {
            frame.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
                ?: result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        } else null
        val source = RawEnginePreviewSource(
            cameraId = cameraId,
            iso = frame.get(CaptureResult.SENSOR_SENSITIVITY) ?: result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100,
            calibration = requireNotNull(enginePreviewCalibration),
            whiteBalanceGains = listOf(gains.red, gains.greenEven, gains.greenOdd, gains.blue),
            camera2Transform = transform?.let { matrix ->
                List(9) { index -> matrix.getElement(index % 3, index / 3).toFloat() }
            },
        )
        if (enginePreviewEnabled) _enginePreviewSource.value = source
    }

    // 场景变化检测：用于替代固定延迟恢复连续对焦
    private var isFocusLockedWaitingForSceneChange = false
    private var focusLockedReferenceIso: Int = 0
    private var focusLockedReferenceExposureNs: Long = 0L
    private var focusLockedReferenceDistance: Float = 0f
    private var focusLockSettleFrames = 0       // 对焦锁定后等待镜头稳定的帧数
    private var sceneChangeFrameCount = 0
    private var eyeTargetLastSeenElapsedMs = 0L
    private var pendingPointExposureLock = false
    private var pendingPointExposureLockStartedElapsedMs = 0L
    private var focusModeBeforeHyperfocal: Boolean? = null
    private var focusDistanceBeforeHyperfocal: Float? = null

    // 高光优先测光：最亮区域坐标（归一化 0-1）及平滑状态
    @Volatile
    private var highlightPointX: Float = 0.5f
    @Volatile
    private var highlightPointY: Float = 0.5f
    private var highlightPointSmoothedX: Float = 0.5f
    private var highlightPointSmoothedY: Float = 0.5f
    private var highlightPointInitialized = false
    private var lastSentHighlightPointX: Float = -1f
    private var lastSentHighlightPointY: Float = -1f

    // 图片拍摄回调（携带 CaptureInfo, CameraCharacteristics 和 CaptureResult 用于 RAW 处理）
    var onImageCaptured: ((SafeImage, CaptureInfo, CameraCharacteristics?, CaptureResult?, CapturedFrameMetadata?) -> Unit)? = null
    var onBurstJpegCaptured: ((OppoBurstJpegFrame, CaptureInfo) -> Unit)? = null
    var onBurstCaptureCompleted: (() -> Unit)? = null
    val isOppoBurstCaptureInProgress: Boolean get() = isOppoBurstYuvSession
    val isOppoContinuousBurstAvailable: Boolean
        get() = imageReader?.imageFormat == ImageFormat.YUV_420_888 && !_state.value.useRaw
    var onPhotoCaptureReady: ((CapturedPhoto) -> Unit)? = null
    var onPhotoCaptureCompleted: ((Long, CaptureInfo) -> Unit)? = null
    var onPhotoCaptureFailed: ((Long) -> Unit)? = null
    var onHdrBracketCaptureFailed: (() -> Unit)? = null

    private fun reservedImageCount(): Int = photoCaptures.values.sumOf { it.reservedFrames }

    private fun reservePhotoCapture(
        state: CameraState,
        reader: ImageReader,
        continuousBurst: Boolean = false,
    ): PhotoCaptureContext? {
        val frameCount = if (continuousBurst) BURST_CAPTURE_BATCH_SIZE else captureImageRequestCount(state, reader)
        val occupied = openImagesCount.get() + reservedImageCount()
        if (occupied + frameCount > CAPTURE_READER_MAX_IMAGES) {
            PLog.w(TAG, "Capture rejected: occupied=$occupied requested=$frameCount capacity=$CAPTURE_READER_MAX_IMAGES")
            return null
        }
        val shot = PhotoCaptureContext(
            id = ++nextPhotoCaptureId,
            state = state,
            reader = reader,
            expectedFrames = frameCount,
            characteristics = activeOutputPhysicalCameraId
                ?.let { getCameraCharacteristicsOrNull(it, "capture snapshot") }
                ?: getActiveOpenCameraCharacteristics(),
            physicalCameraId = activeOutputPhysicalCameraId,
            continuousBurst = continuousBurst,
            exposureCompensationStep = state.getExposureCompensationStep(),
        )
        photoCaptures[shot.id] = shot
        PLog.d(TAG, "Capture reserved: id=${shot.id} frames=$frameCount occupied=$occupied")
        return shot
    }

    private fun registerPhotoTimestamp(shot: PhotoCaptureContext, timestamp: Long) {
        if (shot.delivered) {
            discardCaptureTimestamp(timestamp)
            return
        }
        shot.timestamps.add(timestamp)
        photoCaptureByTimestamp[timestamp] = shot
        if (pendingImages.containsKey(timestamp)) {
            accountAcquiredPhotoImage(shot, timestamp)
            if (shot.failed) pendingImages.remove(timestamp)?.close()
        }
    }

    private fun accountAcquiredPhotoImage(shot: PhotoCaptureContext, timestamp: Long) {
        if (shot.acquiredTimestamps.add(timestamp)) {
            check(shot.reservedFrames > 0) { "Capture ${shot.id} received more images than requested" }
            shot.reservedFrames--
        }
    }

    private fun discardCaptureTimestamp(timestamp: Long) {
        discardedCaptureTimestamps.add(timestamp)
        while (discardedCaptureTimestamps.size > CAPTURE_READER_MAX_IMAGES * 4) {
            discardedCaptureTimestamps.remove(discardedCaptureTimestamps.first())
        }
        photoCaptureByTimestamp.remove(timestamp)
        pendingImages.remove(timestamp)?.close()
        pendingResults.remove(timestamp)
        pendingFrameMetadata.remove(timestamp)
    }

    private fun failPhotoCapture(shot: PhotoCaptureContext, reason: String, drainQueue: Boolean = true) {
        if (shot.failed || shot.delivered) return
        shot.failed = true
        clearPhotoCaptureProgress(shot)
        shot.timeout?.let { cameraHandler?.removeCallbacks(it) }
        shot.timeout = null
        shot.timestamps.forEach { timestamp ->
            pendingImages.remove(timestamp)?.close()
            pendingResults.remove(timestamp)
            pendingFrameMetadata.remove(timestamp)
        }
        shot.frames.values.forEach { it.image.close() }
        shot.frames.clear()
        PLog.e(TAG, "Capture failed: id=${shot.id} reason=$reason")
        if (!shot.continuousBurst) onPhotoCaptureFailed?.invoke(shot.id)
        settleFailedPhotoCapture(shot)
        if (drainQueue) deliverNextPhotoCaptureIfReady()
    }

    private fun disposeFailedPhotoCapture(shot: PhotoCaptureContext) {
        shot.reservedFrames = 0
        shot.delivered = true
        shot.timeout?.let { cameraHandler?.removeCallbacks(it) }
        shot.timeout = null
        photoCaptures.remove(shot.id)
        shot.startedFrameNumbers.forEach { pendingCaptureStartedTimestamps.remove(it) }
        shot.startedFrameNumbers.clear()
        shot.timestamps.forEach(::discardCaptureTimestamp)
    }

    private fun settleFailedPhotoCapture(shot: PhotoCaptureContext) {
        if (!shot.failed || shot.delivered) return
        // A failed frame does not cancel its sibling requests. Hold their slots
        // until the sequence tells us which requests actually produced images.
        if (shot.submitted && !shot.sequenceCompleted) return
        shot.reservedFrames = if (shot.submitted) {
            shot.expectedImageTimestamps.count { it !in shot.acquiredTimestamps }
        } else {
            0
        }
        if (shot.reservedFrames == 0) {
            disposeFailedPhotoCapture(shot)
        } else if (shot.timeout == null) {
            val timeout = Runnable {
                PLog.e(TAG, "Failed capture image drain timed out: id=${shot.id} remaining=${shot.reservedFrames}")
                disposeFailedPhotoCapture(shot)
            }
            shot.timeout = timeout
            cameraHandler?.postDelayed(timeout, CAPTURE_IMAGE_DELIVERY_TIMEOUT_MS)
        }
    }

    private fun finishPhotoCaptureSequence(shot: PhotoCaptureContext, successful: Boolean) {
        shot.sequenceCompleted = true
        clearPhotoCaptureProgress(shot)
        if (!successful) failPhotoCapture(shot, "capture sequence aborted")
        if (shot.failed) settleFailedPhotoCapture(shot)
        if (activePhotoCapture === shot) {
            activePhotoCapture = null
            _state.update {
                it.copy(isCapturing = false, hdrBracketCapturing = false, hdrBracketFrameCount = 0, refocusCapturing = false)
            }
            resetPreviewAfterCapture(keepLivePhotoCapture = successful && !shot.failed)
        }
        if (!shot.failed) {
            if (!shot.continuousBurst) {
                val captureInfo = rebuildCaptureInfo(
                    result = shot.animationCaptureResult,
                    imageWidth = shot.reader.width,
                    imageHeight = shot.reader.height,
                    latitude = shot.state.latitude,
                    longitude = shot.state.longitude,
                    effectiveCharacteristics = shot.characteristics,
                    captureState = shot.state,
                    captureTime = shot.captureTime,
                    exposureCompensationStep = shot.exposureCompensationStep,
                )
                try {
                    onPhotoCaptureCompleted?.invoke(shot.id, captureInfo)
                } catch (e: Exception) {
                    PLog.e(TAG, "Capture completion notification failed: id=${shot.id}", e)
                }
            }
            deliverPhotoCaptureIfReady(shot)
            if (!shot.delivered && !hasAllPhotoFrames(shot)) {
                val timeout = Runnable {
                    failPhotoCapture(shot, "image delivery timed out after sequence completion")
                    disposeFailedPhotoCapture(shot)
                }
                shot.timeout = timeout
                cameraHandler?.postDelayed(timeout, CAPTURE_IMAGE_DELIVERY_TIMEOUT_MS)
            }
        }
        if (shot.continuousBurst) {
            if (successful && !shot.failed) {
                checkBurstCaptureContinue()
            } else if (_state.value.burstCapturing) {
                burstCapturing = false
                _state.update { it.copy(burstCapturing = false, isCapturing = false) }
                resetPreviewAfterCapture()
            }
        }
    }

    private fun hasAllPhotoFrames(shot: PhotoCaptureContext): Boolean =
        shot.acquiredTimestamps.size == shot.expectedFrames &&
            if (shot.continuousBurst) {
                shot.timestamps.none { pendingResults.containsKey(it) || pendingImages.containsKey(it) }
            } else {
                shot.frames.size == shot.expectedFrames
            }

    private fun deliverNextPhotoCaptureIfReady() {
        photoCaptures.values.filter { !it.continuousBurst && !it.failed }.minByOrNull { it.id }
            ?.let(::deliverPhotoCaptureIfReady)
    }

    private fun deliverPhotoCaptureIfReady(shot: PhotoCaptureContext) {
        if (shot.failed || shot.delivered || !shot.sequenceCompleted ||
            !hasAllPhotoFrames(shot)
        ) return
        shot.timeout?.let { cameraHandler?.removeCallbacks(it) }
        shot.timeout = null
        // Delivery preserves shutter order even when a preceding reader buffer is late.
        if (!shot.continuousBurst && photoCaptures.values.any { !it.continuousBurst && !it.failed && it.id < shot.id }) return
        shot.delivered = true
        shot.timeout?.let { cameraHandler?.removeCallbacks(it) }
        shot.timeout = null
        photoCaptures.remove(shot.id)
        shot.timestamps.forEach { photoCaptureByTimestamp.remove(it) }
        if (shot.continuousBurst) return
        val photo = CapturedPhoto(shot.id, shot.state, shot.frames.values.toList())
        shot.frames.clear()
        val callback = onPhotoCaptureReady
        if (callback == null) {
            photo.frames.forEach { it.image.close() }
            onPhotoCaptureFailed?.invoke(shot.id)
            deliverNextPhotoCaptureIfReady()
            return
        }
        try {
            callback(photo)
        } catch (e: Exception) {
            photo.frames.forEach { it.image.close() }
            onPhotoCaptureFailed?.invoke(shot.id)
            PLog.e(TAG, "Photo capture callback failed: id=${shot.id}", e)
        }
        deliverNextPhotoCaptureIfReady()
    }

    private fun clearPhotoCaptureProgress(shot: PhotoCaptureContext) {
        _state.update {
            if (it.photoCaptureProgress?.captureId == shot.id) {
                it.copy(photoCaptureProgress = null)
            } else {
                it
            }
        }
    }

    private fun submitPhotoCapture(
        shot: PhotoCaptureContext,
        session: CameraCaptureSession,
        requests: List<CaptureRequest>,
        baseExposureResult: CaptureResult? = lastCaptureResult,
    ) {
        check(requests.size == shot.expectedFrames) {
            "Capture ${shot.id} reserved ${shot.expectedFrames} frames but built ${requests.size} requests"
        }
        shot.livePhotoStartTimestampUs = livePhotoVideoStartTimestampUs
        val captureSurface = shot.reader.surface
        // Sum the actual request plan so HDR frames with different exposures contribute
        // their own duration. Keep one timeline for the entire photo, including frame gaps.
        val estimatedDurationNs = requests.sumOf { request ->
            val manualExposure = request.get(CaptureRequest.CONTROL_AE_MODE) ==
                CaptureRequest.CONTROL_AE_MODE_OFF
            val exposureNs = if (manualExposure) {
                request.get(CaptureRequest.SENSOR_EXPOSURE_TIME) ?: shot.state.shutterSpeed
            } else {
                baseExposureResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: shot.state.shutterSpeed
            }
            val frameDurationNs = if (manualExposure) {
                request.get(CaptureRequest.SENSOR_FRAME_DURATION)
            } else {
                baseExposureResult?.get(CaptureResult.SENSOR_FRAME_DURATION)
            }
            maxOf(exposureNs, frameDurationNs ?: 0L, 1L)
        }
        var progressStarted = false
        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureStarted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                timestamp: Long,
                frameNumber: Long,
            ) {
                if (shot.delivered) {
                    discardCaptureTimestamp(timestamp)
                    return
                }
                shot.startedFrameNumbers.add(frameNumber)
                shot.timestampByFrameNumber[frameNumber] = timestamp
                pendingCaptureStartedTimestamps[frameNumber] = timestamp
                registerPhotoTimestamp(shot, timestamp)
                if (!progressStarted && activePhotoCapture === shot && !shot.continuousBurst && !shot.failed) {
                    progressStarted = true
                    val progress = PhotoCaptureProgress(
                        captureId = shot.id,
                        // Sensor timestamps may use a different clock from elapsedRealtime.
                        startedElapsedNs = SystemClock.elapsedRealtimeNanos(),
                        estimatedDurationNs = estimatedDurationNs,
                    )
                    _state.update { it.copy(photoCaptureProgress = progress) }
                }
            }

            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                val timestamp = getCaptureTimestamp(result)
                shot.startedFrameNumbers.remove(result.frameNumber)
                if (timestamp == null) {
                    failPhotoCapture(shot, "capture result has no timestamp")
                    return
                }
                shot.timestampByFrameNumber[result.frameNumber] = timestamp
                if (result.frameNumber !in shot.lostImageFrameNumbers) {
                    shot.expectedImageTimestamps.add(timestamp)
                }
                registerPhotoTimestamp(shot, timestamp)
                if (shot.failed || shot.delivered) {
                    settleFailedPhotoCapture(shot)
                    return
                }
                captureFrameMetadata(result, timestamp)?.let { pendingFrameMetadata[timestamp] = it }
                if (shot.animationCaptureResult == null) {
                    shot.animationCaptureResult = result
                }
                val image = pendingImages.remove(timestamp)
                if (image != null) {
                    processAndTriggerCapture(image, result, shot)
                } else {
                    pendingResults[timestamp] = result
                }
                if (request.tag !is MultiFrameCaptureRole || request.tag == MultiFrameCaptureRole.BASE) {
                    lastCaptureResult = result
                }
                if (shot.state.hdrBracketCapturing) logHdrBracketActualExposure(request, result)
            }

            override fun onCaptureSequenceCompleted(
                session: CameraCaptureSession,
                sequenceId: Int,
                frameNumber: Long,
            ) {
                finishPhotoCaptureSequence(shot, successful = true)
            }

            override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
                finishPhotoCaptureSequence(shot, successful = false)
            }

            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure,
            ) {
                val timestamp = pendingCaptureStartedTimestamps.remove(failure.frameNumber)
                shot.startedFrameNumbers.remove(failure.frameNumber)
                if (failure.wasImageCaptured() && timestamp != null) {
                    shot.expectedImageTimestamps.add(timestamp)
                } else if (!failure.wasImageCaptured()) {
                    shot.lostImageFrameNumbers.add(failure.frameNumber)
                    timestamp?.let { shot.expectedImageTimestamps.remove(it) }
                }
                failPhotoCapture(shot, "frame ${failure.frameNumber} failed: ${failure.reason}")
            }

            override fun onCaptureBufferLost(
                session: CameraCaptureSession,
                request: CaptureRequest,
                target: Surface,
                frameNumber: Long,
            ) {
                if (target == captureSurface) {
                    shot.lostImageFrameNumbers.add(frameNumber)
                    shot.timestampByFrameNumber[frameNumber]?.let { shot.expectedImageTimestamps.remove(it) }
                    failPhotoCapture(shot, "image buffer lost for frame $frameNumber")
                }
            }
        }
        session.captureBurst(requests, callback, cameraHandler)
        shot.submitted = true
    }

    private fun trackImage(image: Image?): SafeImage? {
        if (image != null) {
            openImagesCount.getAndIncrement()
        }
        return image?.let { SafeImage(it, this) }
    }

    private fun getCaptureTimestamp(result: TotalCaptureResult): Long? {
        val frameNumber = result.frameNumber
        val startedTimestamp = pendingCaptureStartedTimestamps.remove(frameNumber)
        return result.get(CaptureResult.SENSOR_TIMESTAMP) ?: startedTimestamp
    }

    private fun processOrBufferImageForCaptureResult(image: SafeImage) {
        val timestamp = image.timestamp
        if (timestamp in discardedCaptureTimestamps) {
            image.close()
            return
        }
        val shot = photoCaptureByTimestamp[timestamp]
        if (shot != null) {
            if (shot.delivered) {
                image.close()
                return
            }
            accountAcquiredPhotoImage(shot, timestamp)
            if (shot.failed) {
                image.close()
                settleFailedPhotoCapture(shot)
                return
            }
        }
        val pendingResult = pendingResults.remove(timestamp)
        if (pendingResult != null && shot != null) {
            processAndTriggerCapture(image, pendingResult, shot)
        } else {
            pendingImages.put(timestamp, image)?.close()
            // An Image callback can precede onCaptureStarted. Keep it until its
            // request timestamp arrives; never assign it using current UI state.
            if (shot == null) {
                cameraHandler?.postDelayed({
                    if (photoCaptureByTimestamp[timestamp] == null &&
                        pendingImages.remove(timestamp, image)
                    ) {
                        PLog.w(TAG, "Discarding image without a capture request: timestamp=$timestamp")
                        image.close()
                    }
                }, CAPTURE_IMAGE_DELIVERY_TIMEOUT_MS)
            }
        }
    }

    private fun captureFrameMetadata(
        result: TotalCaptureResult,
        sensorTimestampNs: Long,
    ): CapturedFrameMetadata? {
        val exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return null
        val sensitivityIso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return null
        val exposureProduct = RawExposureMath.productOrNull(exposureTimeNs, sensitivityIso)
            ?: return null
        val desiredExposureProduct = RawExposureMath.productOrNull(
            result.request.get(CaptureRequest.SENSOR_EXPOSURE_TIME),
            result.request.get(CaptureRequest.SENSOR_SENSITIVITY),
        )
        val channelNoiseProfile = captureChannelNoiseProfile(result)
        val framePhysicalCameraId = activeOutputPhysicalCameraId
            ?: result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
        val sensorCharacteristics = framePhysicalCameraId
            ?.let { cameraId ->
                getCameraCharacteristicsOrNull(cameraId, "RAW frame gain limits")
            }
            ?: getActiveOpenCameraCharacteristics()
        val sensitivityLimits = sensorSensitivityLimits(sensorCharacteristics)
        return CapturedFrameMetadata(
            sensorTimestampNs = sensorTimestampNs,
            frameNumber = result.frameNumber,
            exposureTimeNs = exposureTimeNs,
            sensitivityIso = sensitivityIso,
            minimumSensitivityIso = sensitivityLimits.minimumIso,
            maximumAnalogSensitivityIso = sensitivityLimits.maximumAnalogIso,
            exposureProduct = exposureProduct,
            focusDistanceDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: Float.NaN,
            lensState = result.get(CaptureResult.LENS_STATE),
            rollingShutterSkewNs = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW),
            gyroWindow = burstGyroRecorder.exposureWindow(sensorTimestampNs, exposureTimeNs),
            channelNoiseProfile = channelNoiseProfile,
            multiFrameCaptureRole = result.request.tag as? MultiFrameCaptureRole,
            desiredExposureProduct = desiredExposureProduct,
            dynamicBlackLevelByCfaPosition = result
                .get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
                ?.takeIf { it.size >= 4 }
                ?.copyOf(4),
        )
    }

    private data class SensorSensitivityLimits(
        val minimumIso: Int,
        val maximumAnalogIso: Int,
    )

    private fun sensorSensitivityLimits(
        characteristics: CameraCharacteristics?,
    ): SensorSensitivityLimits = SensorSensitivityLimits(
        minimumIso = characteristics
            ?.readMetadataOrNull(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            ?.lower
            ?: 0,
        maximumAnalogIso = characteristics
            ?.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY)
            ?: 0,
    )

    private fun captureChannelNoiseProfile(result: CaptureResult): FloatArray? {
        return result.get(CaptureResult.SENSOR_NOISE_PROFILE)
            ?.takeIf { it.isNotEmpty() }
            ?.let { profile ->
                FloatArray(profile.size * 2) { coefficientIndex ->
                    val pair = profile[coefficientIndex / 2]
                    val coefficient = if ((coefficientIndex and 1) == 0) {
                        pair.first
                    } else {
                        pair.second
                    }
                    coefficient.toFloat().takeIf { it.isFinite() && it >= 0f } ?: 0f
                }
            }
    }

    private fun logRawColorMatrices(
        characteristics: CameraCharacteristics,
        result: CaptureResult,
    ) {
        val matrices = listOf(
            "ColorMatrix1" to characteristics.get(
                CameraCharacteristics.SENSOR_COLOR_TRANSFORM1
            ),
            "ColorMatrix2" to characteristics.get(
                CameraCharacteristics.SENSOR_COLOR_TRANSFORM2
            ),
            "ForwardMatrix1" to characteristics.get(
                CameraCharacteristics.SENSOR_FORWARD_MATRIX1
            ),
            "ForwardMatrix2" to characteristics.get(
                CameraCharacteristics.SENSOR_FORWARD_MATRIX2
            ),
        )
        val illuminant1 = characteristics.get(
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1
        )
        val illuminant2 = characteristics.get(
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2
        )

        PLog.i(
            TAG,
            buildString {
                appendLine(
                    "RAW color matrices: frame=${result.frameNumber}, " +
                        "timestamp=${result.get(CaptureResult.SENSOR_TIMESTAMP)}, " +
                        "referenceIlluminant1=$illuminant1, referenceIlluminant2=$illuminant2"
                )
                matrices.forEachIndexed { index, (name, matrix) ->
                    append(name)
                    append(" = ")
                    append(formatColorSpaceTransform(matrix))
                    if (index != matrices.lastIndex) appendLine()
                }
            }
        )
    }

    private fun formatColorSpaceTransform(transform: ColorSpaceTransform?): String {
        if (transform == null) return "null"
        return (0 until 3).joinToString(prefix = "[", postfix = "]", separator = ", ") { row ->
            (0 until 3).joinToString(prefix = "[", postfix = "]", separator = ", ") { col ->
                val value = transform.getElement(col, row)
                "${value.numerator}/${value.denominator} (${value.toDouble()})"
            }
        }
    }

    // 快门音效播放回调
    var onPlayShutterSound: (() -> Unit)? = null

    // Live Photo 录制状态
    var onLivePhotoVideoCaptured: ((java.io.File, Long) -> Unit)? = null

    // 相机错误回调（供上层处理错误恢复）
    // errorCode: CameraDevice 的错误代码或自定义错误码
    // canRetry: 是否可以重试打开相机
    var onCameraError: ((errorCode: Int, message: String, canRetry: Boolean) -> Unit)? = null

    fun onImageRelease() {
        if (openImagesCount.decrementAndGet() == 0) {
            checkAndClosePendingReaders()
        }
    }

    private fun resolveImageReaderMaxImages(): Int = CAPTURE_READER_MAX_IMAGES

    private fun captureImageRequestCount(state: CameraState, reader: ImageReader): Int = when {
        state.refocusCaptureEnabled &&
            state.captureMode == CaptureMode.PHOTO &&
            state.minimumFocusDistance > 0f &&
            !isRawCaptureReader(reader) -> state.refocusFrameCount.coerceIn(3, 8)
        shouldUseJpgMaxHdrCapture(state, isRawCaptureReader(reader)) && !shouldUseMultiFrameTorch(state) ->
            buildHdrBracketEvOffsets(state).size
        state.requiresMultiFrameCaptureSequence -> state.activeMultiFrameCount
        else -> 1
    }

    private fun canAcquireImage(logPrefix: String): Boolean {
        val openImages = openImagesCount.get()
        if (openImages >= imageReaderMaxImages) {
            PLog.w(TAG, "$logPrefix ($openImages/$imageReaderMaxImages), skipping acquire")
            return false
        }
        return true
    }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            timestamp: Long,
            frameNumber: Long
        ) {
            super.onCaptureStarted(session, request, timestamp, frameNumber)
            val tag = request.tag as? StillFlashRequestTag ?: return
            val pending = stillFlashPrecapture ?: return
            if (tag.generation == pending.generation && tag.phase == pending.phase && tag.isTrigger) {
                pending.triggerFrameNumber = frameNumber
            }
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure,
        ) {
            val ultraWideTag = request.tag as? UltraWideAeHandoffRequestTag
            if (ultraWideTag != null) {
                finishUltraWideAeHandoff(session, request)
            }
            val flashTag = request.tag as? StillFlashRequestTag
            if (flashTag != null) {
                val pending = stillFlashPrecapture ?: return
                if (flashTag.generation == pending.generation && flashTag.phase == pending.phase) {
                    abortStillFlashPrecapture("request failed: frame=${failure.frameNumber} reason=${failure.reason}")
                }
                return
            }
        }

        override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
            if (session !== captureSession) return
            stillFlashPrecapture?.let { pending ->
                if (sequenceId == pending.triggerSequenceId || sequenceId == pending.repeatingSequenceId) {
                    abortStillFlashPrecapture("capture sequence aborted: $sequenceId")
                    return
                }
            }
        }

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            super.onCaptureCompleted(session, request, result)

            if (session === captureSession &&
                _topOffZoomEnabled.value &&
                _state.value.captureMode == CaptureMode.VIDEO &&
                !spatialVideoEnabled && !dualCaptureEnabled
            ) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastTopOffZoomCaptureResultLogAtMs >= 500L) {
                    lastTopOffZoomCaptureResultLogAtMs = now
                    val croppingType = getActiveOpenCameraCharacteristics()
                        ?.get(CameraCharacteristics.SCALER_CROPPING_TYPE)
                    val requestedCrop = request.get(CaptureRequest.SCALER_CROP_REGION)
                    val appliedCrop = result.get(CaptureResult.SCALER_CROP_REGION)
                    PLog.i(
                        "TopOffZoom",
                        "CAMERA_RESULT croppingType=$croppingType " +
                            "requestCrop=${requestedCrop?.toShortString() ?: "null"} " +
                            "appliedCrop=${appliedCrop?.toShortString() ?: "null"} " +
                            "requestZoom=${request.get(CaptureRequest.CONTROL_ZOOM_RATIO)} " +
                            "appliedZoom=${result.get(CaptureResult.CONTROL_ZOOM_RATIO)} " +
                            "frame=${result.frameNumber}"
                    )
                }
            }

            if (session === captureSession && _state.value.previewFirstFrameTimestampNs == null) {
                result.get(CaptureResult.SENSOR_TIMESTAMP)?.let { timestamp ->
                    _state.update { it.copy(previewFirstFrameTimestampNs = timestamp) }
                }
            }
            // Only the currently active CameraCaptureSession may feed the EIS timeline.
            // Results from a retired session can arrive after a physical-output handoff; using
            // the new global physical-camera ID for those old frames mixes two sensors' metadata.
            if (session === captureSession) {
                updateUltraWideAeMeteringResult(result)
                finishUltraWideAeHandoff(session, request)
                updateLogicalMultiCameraStabilizationCamera(result)
                realtimeStabilizationCoordinator.submitCaptureResult(
                    result = result,
                    request = request,
                    outputPhysicalCameraId = activeOutputPhysicalCameraId,
                )
            }

            lastCaptureResult = result
            logVideoCaptureStats(result)
            if (session === captureSession) {
                updateVideoExposureTimePriorityFromResult(result)
                updateEnginePreviewSource(result)
                updateOppoVideoProfileForFrame(result)
            }

            // 处理拍照状态机
            processStillFlashPrecapture(request, result)
            processMultiFrameTorchWarmupState(request, result)

            // 监听对焦状态
            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
            val afStateChanged = afState != null && afState != lastAfState
            if (afStateChanged) {
                lastAfState = afState
            }
            if (_state.value.isAutoFocus && _state.value.isFocusing) {
                when (afState) {
                    CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> {
                        logFocusTerminalResult(result, afState)
                        _state.value = _state.value.copy(isFocusing = false, focusSuccess = true)
                        // 只在首次锁定时记录一次，后续 AF 狩猎重新锁定不再覆盖
                        if (!_state.value.isFocusLocked && !isFocusLockedWaitingForSceneChange) {
                            recordFocusLockExposure(result)
                        }
                    }

                    CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> {
                        logFocusTerminalResult(result, afState)
                        _state.value = _state.value.copy(isFocusing = false, focusSuccess = false)
                        if (!_state.value.isFocusLocked && !isFocusLockedWaitingForSceneChange) {
                            recordFocusLockExposure(result)
                        }
                    }
                }
            }

            // Wait for the newly selected point's AE measurement to settle before locking it.
            applyPendingPointExposureLock(result)

            // 独立测光会主动改变曝光，期间保留用户选定的 AF 点，直到重新点按或解锁。
            // 其余状态在对焦后持续监测曝光变化和焦距跳变。
            if (_state.value.isAutoFocus && isFocusLockedWaitingForSceneChange && _state.value.meteringPoint == null) {
                // 对焦锁定后前几帧镜头还在微调，跳过不检测
                if (focusLockSettleFrames > 0) {
                    focusLockSettleFrames--
                } else {
                    val currentIso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
                    val currentExposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
                    val currentFocusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f
                    var sceneChanged = false

                    // 1. 曝光变化检测
                    if (focusLockedReferenceIso > 0 && focusLockedReferenceExposureNs > 0 &&
                        currentIso > 0 && currentExposure > 0L
                    ) {
                        val refProduct = focusLockedReferenceIso.toDouble() * focusLockedReferenceExposureNs.toDouble()
                        val curProduct = currentIso.toDouble() * currentExposure.toDouble()
                        if (refProduct > 0) {
                            val ratio = if (curProduct > refProduct) curProduct / refProduct else refProduct / curProduct
                            if (ratio > SCENE_CHANGE_EXPOSURE_RATIO) {
//                                PLog.d(TAG, "scene change: exposure ratio=$ratio")
                                sceneChanged = true
                            }
                        }
                    }

                    // 2. 焦距跳变检测：逐帧跟踪，CONTINUOUS_PICTURE 模式下 AF 系统持续工作
                    //    当场景距离变化时，AF 会重新对焦导致焦距大幅跳变
                    if (focusLockedReferenceDistance > 0f && currentFocusDistance > 0f) {
                        val delta = abs(currentFocusDistance - focusLockedReferenceDistance)
                        if (delta > SCENE_CHANGE_FOCUS_DISTANCE_DELTA) {
//                            PLog.d(TAG, "scene change: focusDistance delta=$delta (ref=$focusLockedReferenceDistance, cur=$currentFocusDistance)")
                            sceneChanged = true
                        }
                    }

                    if (sceneChanged) {
                        if (isEyeTargetRecentlySeen()) {
                            // 人眼目标仍在持续更新时，以当前状态作为新的参考，避免场景变化
                            // 检测把正在跟踪的人眼区域恢复成全画面连续对焦。
                            updateFocusLockSceneReference(result)
                            sceneChangeFrameCount = 0
                        } else {
                            sceneChangeFrameCount++
                            if (sceneChangeFrameCount >= SCENE_CHANGE_CONFIRM_FRAMES) {
                                restoreContinuousAf()
                            }
                        }
                    } else {
                        sceneChangeFrameCount = 0
                    }
                }
            }

            // 获取相机实际使用的参数
            val actualIso = result.get(CaptureResult.SENSOR_SENSITIVITY)
            val actualExposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            val aeMode = result.get(CaptureResult.CONTROL_AE_MODE)
            // 判断是否为自动曝光模式（包括所有 AE_MODE_ON 的变体）
            val isAutoExposure = aeMode == CaptureResult.CONTROL_AE_MODE_ON
                    || aeMode == CaptureResult.CONTROL_AE_MODE_ON_AUTO_FLASH
                    || aeMode == CaptureResult.CONTROL_AE_MODE_ON_ALWAYS_FLASH
            val exposureCompensation = result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION) ?: 0
            val awbMode = result.get(CaptureResult.CONTROL_AWB_MODE) ?: _state.value.awbMode
            val whiteBalanceResult = readWhiteBalanceResult(result, awbMode)
            lastWhiteBalanceResult = whiteBalanceResult
            val actualAwbTemperature =
                whiteBalanceResult.colorTemperature ?: whiteBalanceResult.gains?.let(::estimateKelvinFromRggbGains)
            val actualAwbGains = whiteBalanceResult.gains?.toStateGains()
            val awbRange = resolveAwbTemperatureRange()
            val canAdjustWhiteBalance =
                if (_state.value.awbMode == CameraMetadata.CONTROL_AWB_MODE_OFF) {
                    manualWhiteBalanceAnchor != null
                } else {
                    canAdjustManualWhiteBalance(whiteBalanceResult)
                }
            val aperture = result.get(CaptureResult.LENS_APERTURE)
            val focusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f

            // 关键修复：只在自动曝光模式下更新 ISO 和快门速度
            // 手动模式下保持用户设置不变（因为预览使用的是限制后的曝光时间，不是用户设置的值）
            _state.value = _state.value.copy(
                iso = if (isAutoExposure && _state.value.isIsoAuto) actualIso ?: _state.value.iso else _state.value.iso,
                shutterSpeed = if (isAutoExposure && _state.value.isShutterSpeedAuto) actualExposureTimeNs
                    ?: _state.value.shutterSpeed else _state.value.shutterSpeed,
                // In-flight results must not undo a user's WB mode change. A session without
                // a manual anchor still reports the automatic fallback used by the request.
                awbMode = if (_state.value.awbMode == CameraMetadata.CONTROL_AWB_MODE_OFF &&
                    manualWhiteBalanceAnchor == null
                ) awbMode else _state.value.awbMode,
                actualAwbTemperature = actualAwbTemperature,
                actualAwbTint = whiteBalanceResult.colorTint,
                actualAwbGains = actualAwbGains,
                canAdjustWhiteBalance = canAdjustWhiteBalance,
                supportsCctWhiteBalance = supportsCctWhiteBalance(),
                awbTemperatureMin = awbRange.lower,
                awbTemperatureMax = awbRange.upper,
                physicalAperture = aperture ?: _state.value.physicalAperture,
                // 手动对焦保留用户目标值，避免镜头移动中的回报值改变后续拍摄和预览请求。
                focusDistance = if (_state.value.isAutoFocus) focusDistance else _state.value.focusDistance
            )
            pendingRetainedAwbTemperature?.let { temperature ->
                if (canAdjustManualWhiteBalance(whiteBalanceResult)) {
                    setAwbTemperature(temperature)
                    if (pendingRetainedAwbTemperature == null) {
                        PLog.d(TAG, "Restored retained white balance: ${temperature}K")
                    }
                }
            }
        }
    }

    private var lastAfState: Int? = null

    private fun logVideoCaptureStats(result: TotalCaptureResult) {
        if (!_state.value.videoRecordingState.isRecording) return

        val sensorTimestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        val nowMs = SystemClock.elapsedRealtime()
        if (videoCaptureStatsWindowStartMs == 0L) {
            videoCaptureStatsWindowStartMs = nowMs
            videoCaptureStatsFrames = 1
            videoCaptureStatsFirstTimestampNs = sensorTimestampNs
            return
        }

        videoCaptureStatsFrames += 1
        val elapsedMs = nowMs - videoCaptureStatsWindowStartMs
        if (elapsedMs < 1000L) return

        val sensorElapsedNs =
            (sensorTimestampNs - videoCaptureStatsFirstTimestampNs).coerceAtLeast(0L)
        val callbackFps = videoCaptureStatsFrames * 1000f / elapsedMs.toFloat()
        val sensorFps = if (sensorElapsedNs > 0L && videoCaptureStatsFrames > 1) {
            (videoCaptureStatsFrames - 1) * 1_000_000_000f / sensorElapsedNs.toFloat()
        } else {
            0f
        }
        val fpsRange = result.readMetadataOrNull(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)
        PLog.i(
            TAG,
            "Video capture stats: requested=${_state.value.videoConfig.fps.fps}, " +
                "aeRange=$fpsRange, callbackFps=${"%.1f".format(callbackFps)}, " +
                "sensorFps=${"%.1f".format(sensorFps)}, preview=${_state.value.currentPreviewSize.width}x${_state.value.currentPreviewSize.height}"
        )

        videoCaptureStatsWindowStartMs = nowMs
        videoCaptureStatsFrames = 1
        videoCaptureStatsFirstTimestampNs = sensorTimestampNs
    }

    /** MGC forced flash: AE converged, AF locked when supported, AWB unrestricted. */
    private fun processStillFlashPrecapture(request: CaptureRequest, result: TotalCaptureResult) {
        val pending = stillFlashPrecapture ?: return
        val tag = request.tag as? StillFlashRequestTag ?: return
        if (tag.generation != pending.generation || tag.phase != pending.phase) return

        if (tag.isTrigger && pending.triggerFrameNumber == null) {
            pending.triggerFrameNumber = result.frameNumber
        }
        if (pending.phase != StillFlashPhase.APPLYING_SESSION) {
            val triggerFrame = pending.triggerFrameNumber ?: return
            if (result.frameNumber < triggerFrame) return
        }
        if (pending.firstResultFrameNumber == null) {
            pending.firstResultFrameNumber = result.frameNumber
        }
        pending.lastResult = result

        val aeMode = result.get(CaptureResult.CONTROL_AE_MODE)
        val afMode = result.get(CaptureResult.CONTROL_AF_MODE)
        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
        val afState = result.get(CaptureResult.CONTROL_AF_STATE)
        PLog.d(
            TAG,
            "Still flash 3A: generation=${pending.generation} phase=${pending.phase} " +
                "frame=${result.frameNumber} trigger=${tag.isTrigger} " +
                "aeMode=$aeMode ae=$aeState afMode=$afMode af=$afState " +
                "awb=${result.get(CaptureResult.CONTROL_AWB_STATE)} " +
                "flash=${result.get(CaptureResult.FLASH_STATE)}",
        )
        when (pending.phase) {
            StillFlashPhase.APPLYING_SESSION -> {
                val flashMode = result.get(CaptureResult.FLASH_MODE)
                if (aeMode == request.get(CaptureRequest.CONTROL_AE_MODE) &&
                    afMode == pending.afMode &&
                    flashMode == request.get(CaptureRequest.FLASH_MODE)
                ) {
                    val continuousAf = pending.afMode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE ||
                        pending.afMode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                    submitStillFlashPhase(
                        pending,
                        if (pending.triggerAf && continuousAf) StillFlashPhase.WAITING_AF_CONVERGENCE
                        else StillFlashPhase.WAITING_AE_AF,
                    )
                    return
                }
            }
            StillFlashPhase.WAITING_AF_CONVERGENCE -> {
                // CANCEL releases any previous continuous-AF lock before starting a new sweep.
                if (afMode == pending.afMode &&
                    (afState == CaptureResult.CONTROL_AF_STATE_INACTIVE ||
                        afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
                        afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED)
                ) {
                    submitStillFlashPhase(pending, StillFlashPhase.WAITING_AE_AF)
                    return
                }
            }
            StillFlashPhase.WAITING_AE_AF -> {
                // The trigger result is eligible. Like MGC's per-key listeners, retain each
                // completed condition while waiting for the other; AWB is not a prerequisite.
                pending.aeReady = pending.aeReady ||
                    (aeMode == request.get(CaptureRequest.CONTROL_AE_MODE) &&
                        (aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                            aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED))
                pending.afReady = pending.afReady || !pending.triggerAf ||
                    (afMode == pending.afMode &&
                        (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                            afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED))
                if (pending.aeReady && pending.afReady) {
                    completePrecapture(result, "AE converged and AF ready")
                    return
                }
            }
        }
        if (result.frameNumber - pending.firstResultFrameNumber!! > PRECAPTURE_MAX_WAIT_FRAMES) {
            onStillFlashPhaseTimeout(pending, "frame limit")
        }
    }

    private fun createPhotoPreparationRequestBuilder(device: CameraDevice): CaptureRequest.Builder {
        val previewTarget = checkNotNull(previewSurface) {
            "Preview surface unavailable for photo preparation"
        }
        val stabilizationTarget = if (shouldUseAlgorithmicStabilization()) {
            checkNotNull(stabilizationImageReader) {
                "Stabilization reader unavailable for photo preparation"
            }.surface
        } else {
            null
        }
        return device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            // Preserve the configured preview outputs, including the EIS YUV reader. Both
            // repeating and trigger requests must keep feeding it through previewCallback
            // so each stabilization image has matching CaptureResult metadata.
            addTarget(previewTarget)
            stabilizationTarget?.let(::addTarget)
        }
    }

    private fun buildStillFlashPreviewRequest(
        device: CameraDevice,
        pending: StillFlashPrecapture,
        isTrigger: Boolean,
    ): CaptureRequest {
        val triggerAe = isTrigger && pending.phase == StillFlashPhase.WAITING_AE_AF
        return createPhotoPreparationRequestBuilder(device).apply {
            applyBaseCameraSettings(
                builder = this,
                isCapture = false,
                useStillFlashAeMode = true,
                useStillFlashTrigger = triggerAe,
                currentState = checkNotNull(stillFlashCaptureState),
            )
            set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW)
            set(CaptureRequest.CONTROL_AE_LOCK, false)
            set(CaptureRequest.CONTROL_AF_MODE, pending.afMode)
            set(
                CaptureRequest.CONTROL_AF_TRIGGER,
                when {
                    !isTrigger || !pending.triggerAf -> CaptureRequest.CONTROL_AF_TRIGGER_IDLE
                    pending.phase == StillFlashPhase.WAITING_AF_CONVERGENCE -> CaptureRequest.CONTROL_AF_TRIGGER_CANCEL
                    else -> CaptureRequest.CONTROL_AF_TRIGGER_START
                },
            )
            setAePrecaptureTriggerIfSupported(
                this,
                if (triggerAe) CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START
                else CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE,
            )
            previewRequestBuilder?.let { preview ->
                preview.get(CaptureRequest.CONTROL_AF_REGIONS)?.let { set(CaptureRequest.CONTROL_AF_REGIONS, it) }
                preview.get(CaptureRequest.CONTROL_AE_REGIONS)?.let { set(CaptureRequest.CONTROL_AE_REGIONS, it) }
            }
            setTag(StillFlashRequestTag(pending.generation, pending.phase, isTrigger))
        }.build()
    }

    private fun submitStillFlashPhase(pending: StillFlashPrecapture, phase: StillFlashPhase) {
        if (stillFlashPrecapture !== pending) return
        val session = captureSession
        val device = cameraDevice
        val handler = cameraHandler
        if (session == null || device == null || previewSurface == null || handler == null) {
            abortStillFlashPrecapture("camera unavailable")
            return
        }
        precaptureTimeoutRunnable?.let(handler::removeCallbacks)
        pending.phase = phase
        pending.triggerFrameNumber = null
        pending.triggerSequenceId = null
        pending.repeatingSequenceId = null
        pending.firstResultFrameNumber = null
        pending.lastResult = null
        pending.aeReady = false
        pending.afReady = !pending.triggerAf
        try {
            val repeating = buildStillFlashPreviewRequest(device, pending, isTrigger = false)
            if (phase != StillFlashPhase.APPLYING_SESSION) {
                val trigger = buildStillFlashPreviewRequest(device, pending, isTrigger = true)
                pending.triggerSequenceId = session.capture(trigger, previewCallback, handler)
            }
            pending.repeatingSequenceId = session.setRepeatingRequest(repeating, previewCallback, handler)
            val timeout = Runnable {
                if (stillFlashPrecapture === pending && pending.phase == phase) {
                    onStillFlashPhaseTimeout(pending, "time limit")
                }
            }
            precaptureTimeoutRunnable = timeout
            handler.postDelayed(timeout, PRECAPTURE_TIMEOUT_MS)
            PLog.i(TAG, "Still flash phase submitted: generation=${pending.generation} phase=$phase " +
                "afMode=${pending.afMode} triggerAf=${pending.triggerAf} " +
                "aeMode=${repeating.get(CaptureRequest.CONTROL_AE_MODE)} " +
                "stabilizationTarget=${shouldUseAlgorithmicStabilization()}")
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to submit still flash phase $phase", e)
            abortStillFlashPrecapture("phase submission failed: $phase")
        }
    }

    private fun onStillFlashPhaseTimeout(pending: StillFlashPrecapture, reason: String) {
        PLog.w(TAG, "Still flash wait expired: generation=${pending.generation} phase=${pending.phase} " +
            "reason=$reason aeReady=${pending.aeReady} afReady=${pending.afReady}")
        if (pending.lastResult == null) {
            abortStillFlashPrecapture("no capture results for ${pending.phase}")
            return
        }
        when (pending.phase) {
            StillFlashPhase.APPLYING_SESSION -> abortStillFlashPrecapture("flash session did not apply")
            StillFlashPhase.WAITING_AF_CONVERGENCE -> submitStillFlashPhase(pending, StillFlashPhase.WAITING_AE_AF)
            StillFlashPhase.WAITING_AE_AF -> completePrecapture(pending.lastResult, reason)
        }
    }

    private fun abortStillFlashPrecapture(reason: String) {
        PLog.e(TAG, "Still flash preparation failed: $reason")
        // The reserved shot has not been submitted; resetPreviewAfterCapture fails it and
        // releases its buffers, AF ownership and pending capture references together.
        resetPreviewAfterCapture()
    }

    /**
     * CameraX does not wait for an AF lock when continuous AF, manual focus, or an unknown AF
     * mode is active. For a one-shot AF mode it waits for one of the terminal AF states.
     */
    private fun isTorchWarmupAfReady(result: CaptureResult): Boolean {
        val afMode = result.get(CaptureResult.CONTROL_AF_MODE)
        if (afMode == null ||
            afMode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE ||
            afMode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO ||
            afMode == CaptureRequest.CONTROL_AF_MODE_OFF
        ) {
            return true
        }

        return when (result.get(CaptureResult.CONTROL_AF_STATE)) {
            CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED,
            CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED,
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> true

            else -> false
        }
    }

    private fun completePrecapture(result: CaptureResult?, reason: String) {
        val pending = stillFlashPrecapture ?: return
        pendingCaptureBaseExposureResult = result ?: pendingCaptureBaseExposureResult
        clearPrecaptureTracking()
        PLog.i(
            TAG,
            "Precapture complete: generation=${pending.generation}, reason=$reason, " +
                "aeReady=${pending.aeReady} afReady=${pending.afReady} " +
                "ae=${result?.get(CaptureResult.CONTROL_AE_STATE)} " +
                "af=${result?.get(CaptureResult.CONTROL_AF_STATE)} " +
                "iso=${result?.get(CaptureResult.SENSOR_SENSITIVITY)} " +
                "shutter=${result?.get(CaptureResult.SENSOR_EXPOSURE_TIME)}",
        )
        runCaptureSequence()
    }

    private fun clearPrecaptureTracking() {
        precaptureTimeoutRunnable?.let { cameraHandler?.removeCallbacks(it) }
        precaptureTimeoutRunnable = null
        stillFlashPrecapture = null
    }

    private fun processMultiFrameTorchWarmupState(
        request: CaptureRequest,
        result: TotalCaptureResult,
    ) {
        val tag = request.tag as? MultiFrameTorchWarmupRequestTag ?: return
        if (tag.generation != activeMultiFrameTorchWarmupGeneration) return

        lastMultiFrameTorchWarmupResult = result
        if (tag.isAePrecaptureTrigger) {
            multiFrameTorchWarmupTriggerResultSeen = true
            return
        }

        if (multiFrameTorchWarmupNeedsAePrecapture &&
            !multiFrameTorchWarmupPrecaptureSubmitted
        ) {
            if (isTorchSessionUpdateApplied(request, result)) {
                submitMultiFrameTorchWarmupPrecapture(tag.generation)
            }
            return
        }

        if (multiFrameTorchWarmupNeedsAePrecapture &&
            !multiFrameTorchWarmupTriggerResultSeen
        ) {
            return
        }

        if (isTorchWarmup3aReady(result)) {
            completeMultiFrameTorchWarmup(result, "torch and 3A ready")
        }
    }

    private fun isTorchSessionUpdateApplied(
        request: CaptureRequest,
        result: TotalCaptureResult,
    ): Boolean {
        val requestedAeMode = request.get(CaptureRequest.CONTROL_AE_MODE)
        val appliedAeMode = result.get(CaptureResult.CONTROL_AE_MODE)
        val requestedFlashMode = request.get(CaptureRequest.FLASH_MODE)
        val appliedFlashMode = result.get(CaptureResult.FLASH_MODE)
        return (appliedAeMode == null || appliedAeMode == requestedAeMode) &&
            (appliedFlashMode == null || appliedFlashMode == requestedFlashMode)
    }

    private fun submitMultiFrameTorchWarmupPrecapture(generation: Long) {
        if (multiFrameTorchWarmupPrecaptureSubmitted) return

        val session = captureSession
        val device = cameraDevice
        val handler = cameraHandler
        if (session == null || device == null || previewSurface == null || handler == null) {
            completeMultiFrameTorchWarmup(lastMultiFrameTorchWarmupResult, "torch precapture unavailable")
            return
        }

        try {
            val triggerRequest = createPhotoPreparationRequestBuilder(device).apply {
                set(
                    CaptureRequest.CONTROL_CAPTURE_INTENT,
                    CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW
                )
                applyBaseCameraSettings(this, isCapture = false)
                if (_state.value.isAutoExposure) {
                    set(
                        CaptureRequest.CONTROL_AE_MODE,
                        resolveSupportedAeMode(CaptureRequest.CONTROL_AE_MODE_ON)
                    )
                }
                set(CaptureRequest.CONTROL_AE_LOCK, false)
                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                setAePrecaptureTriggerIfSupported(
                    this,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START,
                )
                setTag(
                    MultiFrameTorchWarmupRequestTag(
                        generation = generation,
                        isAePrecaptureTrigger = true,
                    )
                )
            }.build()

            multiFrameTorchWarmupPrecaptureSubmitted = true
            session.capture(triggerRequest, previewCallback, handler)
            PLog.d(
                TAG,
                "Multi-frame torch precapture submitted: generation=$generation, " +
                    "aeMode=${triggerRequest.get(CaptureRequest.CONTROL_AE_MODE)}, " +
                    "stabilizationTarget=${shouldUseAlgorithmicStabilization()}"
            )
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to submit multi-frame torch precapture", e)
            completeMultiFrameTorchWarmup(lastMultiFrameTorchWarmupResult, "torch precapture submission failed")
        }
    }

    private fun isTorchWarmup3aReady(result: CaptureResult): Boolean {
        val aeMode = result.get(CaptureResult.CONTROL_AE_MODE)
        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
        val aeReady = aeMode == CaptureResult.CONTROL_AE_MODE_OFF ||
            aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
            aeState == CaptureResult.CONTROL_AE_STATE_LOCKED
        val awbState = result.get(CaptureResult.CONTROL_AWB_STATE)
        val awbReady = awbState == null || awbState == CaptureResult.CONTROL_AWB_STATE_CONVERGED
        return aeReady && awbReady && isTorchWarmupAfReady(result)
    }

    private fun completeMultiFrameTorchWarmup(
        result: TotalCaptureResult?,
        reason: String,
    ) {
        val generation = activeMultiFrameTorchWarmupGeneration
        if (generation == 0L) return

        val onReady = pendingMultiFrameTorchWarmupAction
        clearMultiFrameTorchWarmupTracking()
        PLog.i(
            TAG,
            "Multi-frame torch warm-up complete: generation=$generation, reason=$reason, " +
                "aeState=${result?.get(CaptureResult.CONTROL_AE_STATE)}, " +
                "flashState=${result?.get(CaptureResult.FLASH_STATE)}, " +
                "iso=${result?.get(CaptureResult.SENSOR_SENSITIVITY)}, " +
                "shutter=${result?.get(CaptureResult.SENSOR_EXPOSURE_TIME)}"
        )
        onReady?.invoke(result)
    }

    private fun clearMultiFrameTorchWarmupTracking() {
        multiFrameTorchWarmupTimeoutRunnable?.let { cameraHandler?.removeCallbacks(it) }
        multiFrameTorchWarmupTimeoutRunnable = null
        activeMultiFrameTorchWarmupGeneration = 0L
        lastMultiFrameTorchWarmupResult = null
        pendingMultiFrameTorchWarmupAction = null
        multiFrameTorchWarmupNeedsAePrecapture = false
        multiFrameTorchWarmupPrecaptureSubmitted = false
        multiFrameTorchWarmupTriggerResultSeen = false
    }

    private fun abortMultiFrameTorchWarmup(reason: String, error: Throwable? = null) {
        if (error != null) {
            PLog.e(TAG, "Multi-frame torch warm-up failed: $reason", error)
        } else {
            PLog.e(TAG, "Multi-frame torch warm-up failed: $reason")
        }
        clearMultiFrameTorchWarmupTracking()
        pendingCaptureDevice = null
        pendingCaptureReader = null
        pendingCaptureBaseExposureResult = null
        burstCapturing = false
        pendingContinuousBurstCapture?.let {
            failPhotoCapture(it, "continuous burst preparation cancelled")
        }
        pendingContinuousBurstCapture = null
        isMultiFrameTorchCaptureActive = false
        isContinuousBurstTorchActive = false
        _state.value = _state.value.copy(isCapturing = false, burstCapturing = false)
        resetPreviewAfterCapture()
    }

    /**
     * 运行最终的拍照序列
     */
    private fun runCaptureSequence() {
        val device = pendingCaptureDevice
        val reader = pendingCaptureReader
        val baseExposureResult = pendingCaptureBaseExposureResult
        if (device != null && reader != null) {
            performCapture(device, reader, baseExposureResult)
        }
        // 清理缓存的数据
        pendingCaptureDevice = null
        pendingCaptureReader = null
        pendingCaptureBaseExposureResult = null
    }

    /**
     * 多帧模式不能连续触发单次主闪。先用持续补光让 AE 收敛，再保持补光完成整组拍摄。
     */
    private fun runMultiFrameTorchWarmupSequence(
        onReady: (TotalCaptureResult?) -> Unit,
    ) {
        val session = captureSession
        val device = cameraDevice
        val handler = cameraHandler
        if (session == null || device == null || previewSurface == null || handler == null) {
            abortMultiFrameTorchWarmup("camera session unavailable")
            return
        }

        val generation = ++multiFrameTorchWarmupGeneration
        activeMultiFrameTorchWarmupGeneration = generation
        lastMultiFrameTorchWarmupResult = null
        pendingMultiFrameTorchWarmupAction = onReady
        multiFrameTorchWarmupPrecaptureSubmitted = false
        multiFrameTorchWarmupTriggerResultSeen = false

        try {
            val torchRequestBuilder = createPhotoPreparationRequestBuilder(device).apply {
                set(
                    CaptureRequest.CONTROL_CAPTURE_INTENT,
                    CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW
                )
                applyBaseCameraSettings(this, isCapture = false)
                if (_state.value.isAutoExposure) {
                    set(
                        CaptureRequest.CONTROL_AE_MODE,
                        resolveSupportedAeMode(CaptureRequest.CONTROL_AE_MODE_ON)
                    )
                }
                set(CaptureRequest.CONTROL_AE_LOCK, false)
                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                setAePrecaptureTriggerIfSupported(
                    this,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE,
                )
                setTag(MultiFrameTorchWarmupRequestTag(generation))
            }
            val torchRequest = torchRequestBuilder.build()

            submitRepeatingPreviewRequest(session, torchRequest)
            multiFrameTorchWarmupNeedsAePrecapture = _state.value.isAutoExposure &&
                torchRequest.get(CaptureRequest.CONTROL_AE_MODE) != CaptureRequest.CONTROL_AE_MODE_OFF &&
                isAePrecaptureSupported()
            val timeout = Runnable {
                if (activeMultiFrameTorchWarmupGeneration != generation) return@Runnable
                val latestResult = lastMultiFrameTorchWarmupResult
                PLog.w(
                    TAG,
                    "Multi-frame torch warm-up timeout: generation=$generation, " +
                        "aeState=${latestResult?.get(CaptureResult.CONTROL_AE_STATE)}, " +
                        "flashState=${latestResult?.get(CaptureResult.FLASH_STATE)}"
                )
                completeMultiFrameTorchWarmup(latestResult, "timeout")
            }
            multiFrameTorchWarmupTimeoutRunnable = timeout
            handler.postDelayed(timeout, MULTI_FRAME_TORCH_WARMUP_TIMEOUT_MS)
            PLog.i(
                TAG,
                "Multi-frame torch warm-up started: generation=$generation, " +
                    "aeMode=${torchRequest.get(CaptureRequest.CONTROL_AE_MODE)}, " +
                    "flashMode=${torchRequest.get(CaptureRequest.FLASH_MODE)}, " +
                    "stabilizationTarget=${shouldUseAlgorithmicStabilization()}"
            )
        } catch (e: Exception) {
            abortMultiFrameTorchWarmup("request submission", e)
        }
    }

    /**
     * 运行预取序列（预闪）
     */
    private fun runPrecaptureSequence() {
        val state = activePhotoCapture?.state ?: _state.value
        stillFlashCaptureState = state
        val afMode = if (state.isAutoFocus) {
            previewRequestBuilder?.get(CaptureRequest.CONTROL_AF_MODE)
                ?: resolveAutoFocusMode(state.captureMode)
        } else {
            CaptureRequest.CONTROL_AF_MODE_OFF
        }
        val triggerAf = state.isAutoFocus && supportsAfTrigger(afMode) &&
            isCaptureRequestKeyAvailable(CaptureRequest.CONTROL_AF_TRIGGER.name)
        val pending = StillFlashPrecapture(++precaptureGeneration, afMode, triggerAf)
        stillFlashPrecapture = pending
        stillFlashAfMode = afMode
        stillFlashFocusDistance = if (afMode == CaptureRequest.CONTROL_AF_MODE_OFF) {
            previewRequestBuilder?.get(CaptureRequest.LENS_FOCUS_DISTANCE)
                ?: resolveValidFocusDistance(pendingCaptureBaseExposureResult, state)
        } else {
            null
        }
        isCaptureFocusFrozen = true
        submitStillFlashPhase(pending, StillFlashPhase.APPLYING_SESSION)
    }

    // ==================== 初始化 ====================

    /**
     * 初始化相机
     */
    fun initialize() {
        PLog.i(TAG, "初始化相机控制器")
        startBackgroundThread()
    }

    private suspend fun runOnCameraThread(action: () -> Boolean): Boolean =
        suspendCancellableCoroutine { continuation ->
            val handler = cameraHandler
            if (handler == null) {
                continuation.resume(false)
                return@suspendCancellableCoroutine
            }
            handler.post {
                val result = action()
                if (continuation.isActive) continuation.resume(result)
            }
        }

    /**
     * 在首次打开前完成相机发现，让调用方可以先确定最终镜头和倍率。
     */
    suspend fun prepareCameras(): Boolean = runOnCameraThread {
        if (_state.value.availableCameras.isNotEmpty()) {
            return@runOnCameraThread true
        }
        try {
            discoverCameras()
            if (_state.value.availableCameras.isEmpty()) {
                handleCameraOpenFailure(
                    cameraId = "",
                    errorCode = ERROR_CAMERA_CHARACTERISTICS_UNAVAILABLE,
                    message = "未发现可用摄像头",
                    error = IllegalStateException("Camera discovery returned no available camera"),
                )
                false
            } else {
                true
            }
        } catch (error: Exception) {
            handleCameraOpenFailure(
                cameraId = "",
                errorCode = ERROR_CAMERA_CHARACTERISTICS_UNAVAILABLE,
                message = "摄像头列表加载失败",
                error = error,
            )
            false
        }
    }

    /**
     * 在创建预览 Surface 前确定最终尺寸，避免首次打开过程中因尺寸变化重建 Surface。
     */
    suspend fun preparePreviewSize(): Boolean = runOnCameraThread {
        try {
            val state = _state.value
            val cameraId = state.getCurrentCameraInfo()?.getOpenCameraId()
                ?: state.currentCameraId
            if (cameraId.isEmpty()) {
                false
            } else {
                val characteristics = getCameraCharacteristicsCached(cameraId)
                val previewSize = when (state.captureMode) {
                    CaptureMode.PHOTO -> CameraUtils.getFixedPreviewSize(
                        characteristics,
                        state.aspectRatio,
                    )
                    CaptureMode.VIDEO -> {
                        availableTonemapModes = characteristics.get(
                            CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES
                        ) ?: intArrayOf()
                        availableVideoStabilizationModes = characteristics.get(
                            CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
                        ) ?: intArrayOf()
                        availableOpticalStabilizationModes = characteristics.get(
                            CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION
                        ) ?: intArrayOf()
                        isFlashSupported = characteristics.get(
                            CameraCharacteristics.FLASH_INFO_AVAILABLE
                        ) ?: false
                        refreshVideoCapabilities(characteristics)
                    }
                }
                _state.update { it.copy(currentPreviewSize = previewSize) }
                true
            }
        } catch (error: Exception) {
            handleCameraOpenFailure(
                cameraId = _state.value.currentCameraId,
                errorCode = ERROR_CAMERA_CHARACTERISTICS_UNAVAILABLE,
                message = "相机特性不可用",
                error = error,
            )
            false
        }
    }

    private fun startBackgroundThread() {
        cameraThread = HandlerThread("CameraBackground").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
    }

    private fun stopBackgroundThread() {
        cameraThread?.quitSafely()
        try {
            cameraThread?.join()
            cameraThread = null
            cameraHandler = null
        } catch (e: InterruptedException) {
            PLog.e(TAG, "Error stopping background thread", e)
        }
    }

    /**
     * 发现所有可用摄像头（包括隐藏摄像头）
     */
    private fun discoverCameras(preferredCameraId: String? = null) {
        val cameras = cameraDiscovery.discoverAllCameras()

        PLog.d(TAG, "Discovered ${cameras.size} cameras:")
        PLog.d(TAG, "发现 ${cameras.size} 个摄像头")
        cameras.forEach { cam ->
            PLog.d(
                TAG,
                "  - ${cam.cameraId}: ${cam.lensType}, intrinsicZoom=${cam.intrinsicZoomRatio}, " +
                        "displayZoom=${cam.displayIntrinsicZoomRatio}"
            )
            PLog.d(
                TAG,
                "摄像头: ${cam.cameraId}, 类型: ${cam.lensType}, 变焦: ${cam.displayIntrinsicZoomRatio}, " +
                        "提交倍率基准: ${cam.intrinsicZoomRatio}"
            )
        }

        // 默认选择主摄
        val defaultCamera = cameras.firstOrNull { it.cameraId == preferredCameraId }
            ?: cameras.firstOrNull { it.lensType == LensType.BACK_MAIN }
            ?: cameras.firstOrNull { it.lensFacing == CameraCharacteristics.LENS_FACING_BACK }
            ?: cameras.firstOrNull()

        PLog.i(TAG, "选择默认摄像头: ${defaultCamera?.cameraId}, 类型: ${defaultCamera?.lensType}")

        _state.value = _state.value.copy(
            availableCameras = cameras,
            currentCameraId = defaultCamera?.cameraId ?: "",
            currentLensType = defaultCamera?.lensType ?: LensType.BACK_MAIN
        )
    }

    fun refreshCameraList() {
        PLog.i(TAG, "刷新摄像头列表")
        val currentCameraId = _state.value.currentCameraId
        cameraDiscovery.clearCache()
        discoverCameras(preferredCameraId = currentCameraId.takeIf { it.isNotEmpty() })
    }

    private fun getCurrentOpenCameraId(): String {
        val state = _state.value
        return state.getCurrentCameraInfo()?.getOpenCameraId() ?: state.currentCameraId
    }

    private fun getActiveOpenCameraId(): String {
        return activeOpenCameraId.takeIf { it.isNotEmpty() } ?: getCurrentOpenCameraId()
    }

    private fun getCameraCharacteristicsCached(cameraId: String): CameraCharacteristics {
        require(cameraId.isNotEmpty()) { "cameraId must not be empty" }
        cachedCharacteristics?.let { characteristics ->
            if (cachedCharacteristicsCameraId == cameraId) {
                return characteristics
            }
        }
        cameraCharacteristicsCache[cameraId]?.let { return it }
        return cameraManager.getCameraCharacteristics(cameraId).also {
            it.associateMetadataCameraId(cameraId)
            cameraCharacteristicsCache[cameraId] = it
        }
    }

    private fun getCameraCharacteristicsOrNull(cameraId: String, reason: String): CameraCharacteristics? {
        if (cameraId.isEmpty()) return null
        return try {
            getCameraCharacteristicsCached(cameraId)
        } catch (e: Exception) {
            PLog.v(TAG, "Failed to load characteristics for camera $cameraId during $reason: ${e.message}")
            null
        }
    }

    private fun cacheActiveCameraCharacteristics(
        cameraId: String,
        characteristics: CameraCharacteristics
    ) {
        cachedCharacteristics = characteristics
        cachedCharacteristicsCameraId = cameraId
        cameraCharacteristicsCache[cameraId] = characteristics
    }

    private fun getActiveOpenCameraCharacteristics(): CameraCharacteristics? {
        val openCameraId = getActiveOpenCameraId()
        return getCameraCharacteristicsOrNull(openCameraId, "active open camera")
    }

    private fun clearCameraCapabilityCache() {
        _enginePreviewSource.value = null
        enginePreviewCharacteristics = null
        enginePreviewCalibration = null
        cachedCharacteristics = null
        cachedCharacteristicsCameraId = ""
        activeOpenCameraId = ""
        activeOutputPhysicalCameraId = null
        logicalMultiCameraAutoSwitchActive = false
        lastLogicalMultiCameraStabilizationPhysicalId = null
        cachedSensorOrientation = 0
        cachedLensFacing = CameraCharacteristics.LENS_FACING_BACK
        cachedHardwareLevel = CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED
        isManualSensorSupported = false
        isManualPostProcessingSupported = false
        isFlashSupported = false
        maxAfRegions = 0
        maxAeRegions = 0
        availableAfModes = intArrayOf()
        availableAeModes = intArrayOf()
        availableAwbModes = intArrayOf()
        availableEdgeModes = intArrayOf()
        availableNoiseReductionModes = intArrayOf()
        availableTonemapModes = intArrayOf()
        availableColorCorrectionAberrationModes = intArrayOf()
        availableHotPixelModes = intArrayOf()
        availableShadingModes = intArrayOf()
        availableDistortionCorrectionModes = intArrayOf()
        availableVideoStabilizationModes = intArrayOf()
        availableOpticalStabilizationModes = intArrayOf()
        availableLensShadingMapModes = intArrayOf()
        availableFaceDetectModes = intArrayOf()
        maxFaceCount = 0
        faceDetectMode = CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF
        availableColorCorrectionModes = intArrayOf()
        awbColorTemperatureRange = null
        lastWhiteBalanceResult = null
        manualWhiteBalanceAnchor = null
        isRawSupported = false
        isP010Supported = false
        isStreamUseCaseSupported = false
        availableStreamUseCases = longArrayOf()
        lastAfState = null
        isZslControlSupported = null
        availableCaptureRequestKeyNames = null
    }

    private fun clearCameraSessionState(reason: String, closeImageReader: Boolean = true) {
        if (isOppoBurstYuvSession || oppoContinuousBurstEngine.isActive()) {
            oppoContinuousBurstEngine.stop()
        }
        // Keep the reader in drain mode until all accepted workers and trailing HAL buffers finish.
        burstCapturing = false
        _state.update { it.copy(burstCapturing = false) }
        photoCaptures.values.toList().forEach {
            failPhotoCapture(it, "session cleared: $reason", drainQueue = false)
            disposeFailedPhotoCapture(it)
        }
        activePhotoCapture = null
        pendingContinuousBurstCapture = null
        pendingImages.values.toList().forEach(SafeImage::close)
        pendingImages.clear()
        pendingResults.clear()
        pendingFrameMetadata.clear()
        pendingCaptureStartedTimestamps.clear()
        photoCaptureByTimestamp.clear()
        _state.update {
            it.copy(
                isCapturing = false,
                hdrBracketCapturing = false,
                hdrBracketFrameCount = 0,
                previewFirstFrameTimestampNs = null,
            )
        }
        previewSessionGeneration++
        previewUpdateScheduled.set(false)
        livePhotoVideoStartTimestampUs = null
        clearPrecaptureTracking()
        clearMultiFrameTorchWarmupTracking()
        isMultiFrameTorchCaptureActive = false
        isContinuousBurstTorchActive = false
        pendingCaptureDevice = null
        pendingCaptureReader = null
        pendingCaptureBaseExposureResult = null
        clearCaptureFocusState("session cleared: $reason")
        safeCloseCaptureSession(captureSession, reason)
        captureSession = null
        _enginePreviewSource.value = null
        previewRequestBuilder = null
        safeReleasePreviewSurface(reason)
        if (closeImageReader) {
            safeCloseImageReader(imageReader)
            imageReader = null
            closeStabilizationImageReader()
            closeUltraWideAeMeteringReader()
            activeUltraWideAeMeteringPhysicalCameraId = null
            ultraWideAeMeteringSessionAttached = false
            ultraWideAeMeteringSuppressed = false
            latestUltraWideAeReading = null
            pendingUltraWideAeHandoff = null
            ultraWideAeHandoffActiveForSession = false
        }
    }

    private fun safeReleasePreviewSurface(reason: String) {
        val surface = previewSurface
        previewSurface = null
        previewSurfaceTexture = null
        try {
            surface?.release()
        } catch (e: Exception) {
            PLog.w(TAG, "Ignoring error while releasing preview surface ($reason): ${e.message}")
        }
    }

    private fun clearCameraRuntimeState(reason: String, closeImageReader: Boolean = true) {
        clearCameraSessionState(reason, closeImageReader)
        stopSpatialVideoStandbyCamera("camera runtime cleared: $reason")
        clearCameraCapabilityCache()
    }

    private fun setCameraInactive(resetVideoState: Boolean = true) {
        _state.value = if (resetVideoState) {
            _state.value.copy(
                isPreviewActive = false,
                isCapturing = false,
                actualAwbTemperature = null,
                actualAwbTint = null,
                actualAwbGains = null,
                canAdjustWhiteBalance = false,
                videoRecordingState = VideoRecordingState()
            )
        } else {
            _state.value.copy(
                isPreviewActive = false,
                isCapturing = false,
                actualAwbTemperature = null,
                actualAwbTint = null,
                actualAwbGains = null,
                canAdjustWhiteBalance = false
            )
        }
    }

    private fun closeCameraDeviceSafely(camera: CameraDevice?, reason: String) {
        try {
            camera?.close()
        } catch (e: Exception) {
            PLog.w(TAG, "Ignoring exception while closing camera device ($reason): ${e.message}")
        }
    }

    private fun closeCameraDeviceAndAwait(
        camera: CameraDevice,
        reason: String,
        blocksLifecycle: Boolean = true,
    ) {
        if (cameraDevice === camera) {
            cameraDevice = null
        }
        closingCameraDevices.add(camera)
        if (blocksLifecycle) {
            lifecycleClosingCameraDevice = camera
            cameraDeviceLifecycle = CameraDeviceLifecycle.CLOSING
        }
        closeCameraDeviceSafely(camera, reason)
    }

    private fun handleCameraDeviceClosed(camera: CameraDevice) {
        val wasActiveDevice = cameraDevice === camera
        val wasRequestedClose = closingCameraDevices.remove(camera)
        val blocksCurrentLifecycle = lifecycleClosingCameraDevice === camera
        if (!wasActiveDevice && !wasRequestedClose) {
            PLog.d(TAG, "Ignoring close callback from an unowned camera device: ${camera.id}")
            return
        }

        if (wasActiveDevice) {
            cameraDevice = null
        }

        // Retired devices closed after a warm handoff only release their own bookkeeping.
        // They cannot mark the replacement camera CLOSED or trigger another open.
        if (!wasActiveDevice && !blocksCurrentLifecycle) {
            PLog.d(
                TAG,
                "Retired camera ${camera.id} closed after handoff; active lifecycle remains $cameraDeviceLifecycle"
            )
            return
        }
        if (cameraDevice != null) {
            PLog.d(TAG, "Ignoring close callback for non-active camera ${camera.id}")
            return
        }

        if (blocksCurrentLifecycle) {
            lifecycleClosingCameraDevice = null
        }
        cameraDeviceLifecycle = CameraDeviceLifecycle.CLOSED
        PLog.d(TAG, "Camera device closed: ${camera.id}")
        openPendingCameraRequest()
        if (cameraDeviceLifecycle == CameraDeviceLifecycle.CLOSED) {
            releaseCloseLatch?.countDown()
        }
    }

    private fun handleCameraDeviceUnavailable(
        camera: CameraDevice,
        reason: String,
        resetVideoState: Boolean = true
    ) {
        cameraOpenGeneration++
        videoRecorder.forceStop()
        spatialVideoRecorder.forceStop()
        clearDualAspectRecordingState("primary camera unavailable: $reason")
        if (spatialVideoEnabled) {
            clearSpecialVideoRuntimeState("primary camera unavailable: $reason")
        } else {
            stopSpatialVideoStandbyCamera("primary camera unavailable: $reason")
        }
        stopVideoRecordingTicker()
        clearCameraSessionState(reason)
        closeCameraDeviceAndAwait(camera, reason)
        clearCameraCapabilityCache()
        livePhotoRecorder.stopRecording()
        setCameraInactive(resetVideoState)
    }

    private fun handleCameraOpenFailure(
        cameraId: String,
        errorCode: Int,
        message: String,
        error: Exception
    ) {
        spatialVideoPrimaryReopenInProgress = false
        cameraOpenGeneration++
        PLog.e(TAG, "Camera open failed for $cameraId: $message", error)
        val device = cameraDevice
        cameraDevice = null
        if (device != null) {
            closeCameraDeviceAndAwait(device, "open failure")
        } else {
            cameraDeviceLifecycle = CameraDeviceLifecycle.CLOSED
        }
        if (spatialVideoEnabled) {
            clearCameraSessionState("Special Video primary open failure: $cameraId")
            clearSpecialVideoRuntimeState("primary CameraDevice open failure: $cameraId")
            clearCameraCapabilityCache()
        } else {
            clearCameraRuntimeState("open failure: $cameraId")
        }
        livePhotoRecorder.stopRecording()
        setCameraInactive(resetVideoState = true)
        onCameraError?.invoke(errorCode, message, true)
    }

    private fun handlePreviewSessionFailure(
        reason: String,
        openGeneration: Long,
        error: Exception? = null
    ) {
        if (openGeneration != cameraOpenGeneration) {
            PLog.w(TAG, "Ignoring stale preview session failure: $reason")
            return
        }
        cameraOpenGeneration++
        if (error != null) {
            PLog.e(TAG, "Preview session failed: $reason", error)
        } else {
            PLog.e(TAG, "Preview session failed: $reason")
        }
        if (videoRecorder.isRecording()) {
            videoRecorder.forceStop()
            spatialVideoRecorder.forceStop()
            clearDualAspectRecordingState("preview session failed: $reason")
            stopVideoRecordingTicker()
        }
        if (spatialVideoEnabled) {
            clearSpecialVideoRuntimeState("primary preview session failed: $reason")
        }
        val device = cameraDevice
        if (device != null) {
            closeCameraDeviceAndAwait(device, "preview session failure: $reason")
        } else {
            cameraDeviceLifecycle = CameraDeviceLifecycle.CLOSED
        }
        clearCameraRuntimeState("preview session failure: $reason")
        livePhotoRecorder.stopRecording()
        setCameraInactive(resetVideoState = true)
        onCameraError?.invoke(
            ERROR_CAMERA_SESSION_CONFIG_FAILED,
            "预览会话配置失败",
            true
        )
    }

    private fun resolveActiveFocusCharacteristics(
        fallbackCharacteristics: CameraCharacteristics? = null
    ): Pair<String, CameraCharacteristics>? {
        val state = _state.value
        val camera = state.getCurrentCameraInfo()
        val targetZoomRatioByMain = getTargetZoomRatioByMain(state, camera)
        val candidateIds = buildList {
            activeOutputPhysicalCameraId?.let(::add)
            camera?.getBoundPhysicalCameraId(targetZoomRatioByMain)?.let(::add)
            camera?.baseCameraId?.let(::add)
            camera?.cameraId?.takeUnless { camera.isVirtualIszLens }?.let(::add)
            activeOpenCameraId.takeIf { it.isNotEmpty() }?.let(::add)
            state.currentCameraId.takeIf { it.isNotEmpty() }?.let(::add)
        }.distinct()

        for (cameraId in candidateIds) {
            getCameraCharacteristicsOrNull(cameraId, "focus characteristics")?.let {
                return cameraId to it
            }
        }

        return (fallbackCharacteristics ?: getActiveOpenCameraCharacteristics())?.let {
            val fallbackId = activeOpenCameraId.takeIf { id -> id.isNotEmpty() }
                ?: state.currentCameraId
            fallbackId to it
        }
    }

    private fun refreshActiveFocusLimit() {
        val focusCharacteristics = resolveActiveFocusCharacteristics()?.second
        val minimumFocusDistance =
            focusCharacteristics?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        if (focusCharacteristics != null) {
            maxAfRegions = focusCharacteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
            availableAfModes =
                focusCharacteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        }
        _state.value = _state.value.copy(
            minimumFocusDistance = minimumFocusDistance,
            supportsPointAutoFocus = supportsPointAutoFocus(
                minimumFocusDistance = minimumFocusDistance,
                maxRegions = maxAfRegions,
                afModes = availableAfModes,
            ),
        )
    }

    private fun supportsPointAutoFocus(
        minimumFocusDistance: Float,
        maxRegions: Int,
        afModes: IntArray,
    ): Boolean {
        return minimumFocusDistance > 0f &&
            maxRegions > 0 &&
            afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)
    }

    private fun refreshVideoCapabilities(
        characteristics: CameraCharacteristics? = null,
        timingCharacteristics: CameraCharacteristics? = null,
    ): Size {
        val openCameraId = getCurrentOpenCameraId()
        if (openCameraId.isEmpty()) {
            return _state.value.currentPreviewSize
        }

        // A physical output is the real source for preview and the MGC YUV input.
        // Resolve stream sizes against that sensor, not the logical parent camera.
        val capabilityCameraId = activeOutputPhysicalCameraId
            ?.takeIf { it.isNotBlank() && !logicalMultiCameraAutoSwitchActive }
            ?: openCameraId
        val resolvedCharacteristics = characteristics
            ?: getCameraCharacteristicsOrNull(capabilityCameraId, "video capabilities")
        if (resolvedCharacteristics == null) {
            PLog.e(TAG, "Failed to load video capabilities for $capabilityCameraId")
            return _state.value.currentPreviewSize
        }
        val resolvedTimingCharacteristics = timingCharacteristics
            ?: if (capabilityCameraId == openCameraId) {
                resolvedCharacteristics
            } else {
                getCameraCharacteristicsOrNull(openCameraId, "video capability timestamp source")
                    ?: resolvedCharacteristics
            }

        val requestedConfig = _state.value.videoConfig
        val snapshot = VideoCapabilitiesResolver.resolve(
            characteristics = resolvedCharacteristics,
            requestedConfig = requestedConfig,
            algorithmicStabilizationSupported =
                realtimeStabilizationCoordinator.isCameraSupported(
                    resolvedCharacteristics,
                    resolvedTimingCharacteristics,
                ) && !isEnhancedStabilizationUnavailableForCurrentCamera(),
        )
        if (snapshot.config.logProfile != requestedConfig.logProfile) {
            PLog.w(
                TAG,
                "Video Log capability resolved: camera=$openCameraId, " +
                    "requested=${requestedConfig.logProfile.name}, resolved=${snapshot.config.logProfile.name}, " +
                    "available=${snapshot.capabilities.availableLogProfiles.map { it.name }}, " +
                    "lifecycle=$cameraDeviceLifecycle"
            )
        }

        if (_state.value.videoConfig.stabilizationMode == VideoStabilizationMode.ENHANCED) {
            val enhancedInput = snapshot.capabilities
                .enhancedStabilizationInputSizesByResolution[snapshot.config.resolution]
            if (snapshot.config.stabilizationMode == VideoStabilizationMode.ENHANCED) {
                PLog.i(
                    TAG,
                    "EIS+ capability accepted: resolution=${snapshot.config.resolution.displayName}, " +
                        "fps=${snapshot.config.fps.fps}, yuvInput=$enhancedInput"
                )
            } else {
                PLog.e(
                    TAG,
                    "EIS+ capability rejected: resolution=${snapshot.config.resolution.displayName}, " +
                        "fps=${snapshot.config.fps.fps}, yuvInput=$enhancedInput, " +
                        "reason=missing YUV output for the selected resolution"
                )
            }
        }

        val enhancedFallbackMode = if (
            _state.value.videoConfig.stabilizationMode == VideoStabilizationMode.ENHANCED &&
            snapshot.config.stabilizationMode != VideoStabilizationMode.ENHANCED
        ) {
            resolvePreviousVideoStabilizationMode(
                snapshot.capabilities.availableStabilizationModes
            )
        } else {
            null
        }
        val resolvedConfig = enhancedFallbackMode?.let { fallbackMode ->
            snapshot.config.copy(stabilizationMode = fallbackMode)
        } ?: snapshot.config
        _state.value = _state.value.copy(
            videoConfig = resolvedConfig,
            videoCapabilities = snapshot.capabilities,
            currentPreviewSize = if (_state.value.captureMode == CaptureMode.VIDEO) {
                snapshot.previewSize
            } else {
                _state.value.currentPreviewSize
            }
        )
        if (enhancedFallbackMode != null) {
            previousVideoStabilizationMode = enhancedFallbackMode
            notifyEnhancedStabilizationUnavailable(
                EnhancedStabilizationFallback.Video(enhancedFallbackMode)
            )
        }
        return snapshot.previewSize
    }

    private fun startVideoRecordingTicker() {
        stopVideoRecordingTicker()
        videoRecordingStartElapsedMs = SystemClock.elapsedRealtime()
        cameraHandler?.post(videoRecordingTicker)
    }

    private fun stopVideoRecordingTicker() {
        cameraHandler?.removeCallbacks(videoRecordingTicker)
    }

    // ==================== 相机控制 ====================

    /**
     * Rebuilds mode-specific Camera2 outputs and the capture session while retaining the
     * currently open CameraDevice and the GL-owned SurfaceTexture.
     */
    fun reconfigureCaptureMode(
        surfaceTexture: SurfaceTexture,
        modeSwitchTapTimestampMs: Long? = null,
        onSessionReconfigureStarted: ((sessionStartElapsedRealtimeMs: Long, tapTimestampMs: Long?) -> Unit)? = null,
    ) {
        val request = CaptureModeReconfigureRequest(
            generation = captureModeReconfigureGeneration.incrementAndGet(),
            surfaceTexture = surfaceTexture,
            targetCaptureMode = _state.value.captureMode,
            tapTimestampMs = modeSwitchTapTimestampMs,
            onStarted = onSessionReconfigureStarted,
        )
        pendingCaptureModeReconfigureRequest = request
        val handler = cameraHandler
        if (handler == null) {
            PLog.w(TAG, "Capture-mode reconfigure deferred: camera handler is unavailable")
            return
        }
        handler.post {
            if (pendingCaptureModeReconfigureRequest !== request) {
                PLog.d(TAG, "Skipping superseded capture-mode reconfigure request ${request.generation}")
                return@post
            }
            val device = cameraDevice
            when {
                cameraDeviceLifecycle == CameraDeviceLifecycle.OPEN &&
                    device != null && previewSurfaceTexture === surfaceTexture -> {
                    pendingCaptureModeReconfigureRequest = null
                    beginCaptureModeSessionReconfigure(surfaceTexture, device, request)
                }
                cameraDeviceLifecycle == CameraDeviceLifecycle.OPENING -> {
                    PLog.d(
                        TAG,
                        "Capture-mode reconfigure ${request.generation} deferred while CameraDevice is opening; " +
                            "the open callback will reconcile the captured mode"
                    )
                }
                else -> {
                    pendingCaptureModeReconfigureRequest = null
                    PLog.i(
                        TAG,
                        "Capture-mode reconfigure falling back to normal camera open: " +
                            "lifecycle=$cameraDeviceLifecycle, sameSurface=${previewSurfaceTexture === surfaceTexture}"
                    )
                    openCamera(surfaceTexture)
                }
            }
        }
    }

    private fun beginCaptureModeSessionReconfigure(
        surfaceTexture: SurfaceTexture,
        existingCameraDevice: CameraDevice,
        request: CaptureModeReconfigureRequest?,
    ) {
        captureModeReconfiguringSurfaceTexture = surfaceTexture
        val sessionStartMs = SystemClock.elapsedRealtime()
        PLog.i(
            TAG,
            "Capture-mode session reconfigure start: mode=${_state.value.captureMode}, " +
                "cameraDeviceReused=true, generation=${request?.generation}, " +
                "tapToSessionReconfigureMs=${request?.tapTimestampMs?.let { (sessionStartMs - it).coerceAtLeast(0L) }}, " +
                "elapsedRealtimeMs=$sessionStartMs"
        )
        clearCameraSessionState("capture mode switch")
        request?.onStarted?.invoke(sessionStartMs, request.tapTimestampMs)
        openCameraNow(
            CameraOpenRequest(surfaceTexture = surfaceTexture, preserveVideoRecording = false),
            reuseCameraDevice = existingCameraDevice,
        )
    }

    /**
     * 打开相机并开始预览
     *
     * @param surfaceTexture SurfaceTexture 用于预览
     */
    @SuppressLint("MissingPermission")
    fun openCamera(surfaceTexture: SurfaceTexture, preserveVideoRecording: Boolean = false) {
        cancelPendingCaptureModeReconfigure("normal camera open request")
        captureModeReconfiguringSurfaceTexture = null
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post {
                openCamera(surfaceTexture, preserveVideoRecording)
            }
            return
        }
        pendingCameraOpenRequest = CameraOpenRequest(
            surfaceTexture = surfaceTexture,
            preserveVideoRecording = preserveVideoRecording,
        )
        when (cameraDeviceLifecycle) {
            CameraDeviceLifecycle.CLOSED -> openPendingCameraRequest()
            CameraDeviceLifecycle.OPENING,
            CameraDeviceLifecycle.OPEN -> closeCameraInternal(
                preserveVideoRecording = preserveVideoRecording,
                clearPendingOpen = false,
            )
            CameraDeviceLifecycle.CLOSING -> {
                PLog.d(TAG, "Camera open queued while previous device is closing")
            }
        }
    }

    private fun cancelPendingCaptureModeReconfigure(reason: String) {
        val pending = pendingCaptureModeReconfigureRequest ?: return
        pendingCaptureModeReconfigureRequest = null
        captureModeReconfigureGeneration.incrementAndGet()
        PLog.d(TAG, "Cancelled pending capture-mode reconfigure ${pending.generation}: $reason")
    }

    private fun openPendingCameraRequest() {
        if (cameraDeviceLifecycle != CameraDeviceLifecycle.CLOSED) return
        val request = pendingCameraOpenRequest ?: return
        pendingCameraOpenRequest = null
        cameraDeviceLifecycle = CameraDeviceLifecycle.OPENING
        openCameraNow(request)
    }

    @SuppressLint("MissingPermission")
    private fun openCameraNow(
        request: CameraOpenRequest,
        reuseCameraDevice: CameraDevice? = null,
    ) {
        val surfaceTexture = request.surfaceTexture
        val preserveVideoRecording = request.preserveVideoRecording
        clearPointFocusState()
        val openGeneration = if (reuseCameraDevice == null) {
            ++cameraOpenGeneration
        } else {
            cameraOpenGeneration
        }

        // 确保在权限已授予后才发现相机（延迟初始化）
        if (_state.value.availableCameras.isEmpty()) {
            PLog.i(TAG, "首次打开相机，开始发现可用摄像头")
            try {
                discoverCameras()
            } catch (error: Exception) {
                handleCameraOpenFailure(
                    cameraId = "",
                    errorCode = ERROR_CAMERA_CHARACTERISTICS_UNAVAILABLE,
                    message = "摄像头列表加载失败",
                    error = error
                )
                return
            }
        }

        val captureMode = _state.value.captureMode
        var selectedCamera = _state.value.getCurrentCameraInfo()
        if (normalVideoUltraWideInstantSwitchActive && !isCurrentNormalVideoPairLens(selectedCamera)) {
            clearNormalVideoUltraWideInstantSwitchRuntime(
                "selected camera is outside the 1x/0.5x pair",
                reopenSelectedCamera = false,
            )
        }
        if (!normalVideoUltraWideInstantSwitchActive && normalVideoUltraWideInstantSwitchEnabled &&
            !spatialVideoEnabled && !_topOffPositionLockActive.value &&
            (captureMode == CaptureMode.VIDEO || captureMode == CaptureMode.PHOTO)
        ) {
            activateNormalVideoUltraWideInstantSwitch(reopenPrimaryIfNeeded = false)
        }
        val cameraId = _state.value.currentCameraId
        selectedCamera = _state.value.getCurrentCameraInfo()
        val selectedOpenCameraId = selectedCamera?.getOpenCameraId() ?: cameraId
        val specialVideoPrimaryOverride = spatialVideoPrimaryCameraId?.takeIf { spatialVideoEnabled }
        val normalVideoPrimaryOverride = normalVideoPrimaryCameraId?.takeIf {
            normalVideoUltraWideInstantSwitchActive
        }
        val openCameraId = specialVideoPrimaryOverride ?: normalVideoPrimaryOverride ?: selectedOpenCameraId
        val targetZoomRatioByMain = getTargetZoomRatioByMain(_state.value, selectedCamera)
        if (captureMode != CaptureMode.VIDEO && dualCaptureCameraDevice != null) {
            stopDualCaptureCamera()
        }
        if (cameraId.isEmpty()) {
            handleCameraOpenFailure(
                cameraId = cameraId,
                errorCode = ERROR_CAMERA_CHARACTERISTICS_UNAVAILABLE,
                message = "未发现可用摄像头",
                error = IllegalStateException("Camera discovery returned no available camera")
            )
            return
        }

        activeOpenCameraId = openCameraId
        logicalMultiCameraAutoSwitchActive = !_topOffPositionLockActive.value &&
            !spatialVideoEnabled &&
            (!normalVideoUltraWideInstantSwitchActive || captureMode == CaptureMode.PHOTO) &&
            shouldUseLogicalMultiCameraZoom(_state.value, selectedCamera)
        val requestedPhysicalCameraId = when {
            spatialVideoEnabled &&
                spatialVideoStreamingMode == SpatialVideoStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL ->
                spatialVideoMainPhysicalCameraId
            normalVideoUltraWideInstantSwitchActive &&
                normalVideoStreamingMode == SpatialVideoStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL ->
                if (captureMode == CaptureMode.PHOTO) null else normalVideoMainPhysicalCameraId
            specialVideoPrimaryOverride != null || normalVideoPrimaryOverride != null -> null
            else -> resolveRequestedPhysicalCameraId(_state.value, selectedCamera)
        }
        var outputPhysicalCameraId = if (logicalMultiCameraAutoSwitchActive) null else requestedPhysicalCameraId
        activeOutputPhysicalCameraId = outputPhysicalCameraId
        lastLogicalMultiCameraStabilizationPhysicalId = null
        if (logicalMultiCameraAutoSwitchActive) {
            PLog.i(
                TAG,
                "Logical multi-camera seamless zoom enabled: logical=$openCameraId, " +
                    "physicalIds=${getCameraCharacteristicsCached(openCameraId).physicalCameraIds}"
            )
        }

        if (spatialVideoEnabled && openCameraId != spatialVideoPrimaryCameraId) {
            val reason = "Primary camera changed while Special Video was active " +
                "(newId=$openCameraId expected=$spatialVideoPrimaryCameraId)"
            PLog.w(TAG, reason)
            if (videoRecorder.isRecording() || _state.value.videoRecordingState.isProcessing) {
                failSpatialVideoStandby(reason)
            } else {
                spatialVideoEnabled = false
                _spatialVideoEnabled.value = false
                stopSpatialVideoStandbyCamera(reason)
                spatialVideoMainPhysicalCameraId = null
                spatialVideoUltraWidePhysicalCameraId = null
                spatialVideoPrimaryCameraId = null
                spatialVideoStreamingMode = null
            }
        }

        val cameraStateCallback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (
                    openGeneration != cameraOpenGeneration ||
                    (reuseCameraDevice == null && cameraDeviceLifecycle != CameraDeviceLifecycle.OPENING) ||
                    (reuseCameraDevice != null &&
                        (cameraDeviceLifecycle != CameraDeviceLifecycle.OPEN ||
                            cameraDevice !== reuseCameraDevice || camera !== reuseCameraDevice))
                ) {
                    PLog.w(TAG, "Ignoring stale camera open callback: camera=${camera.id}")
                    if (reuseCameraDevice == null || camera !== reuseCameraDevice) {
                        closeCameraDeviceAndAwait(camera, "stale camera open", blocksLifecycle = false)
                    }
                    return
                }
                spatialVideoPrimaryReopenInProgress = false
                PLog.d(TAG, "Camera opened/reused: ${camera.id}")
                cameraDeviceLifecycle = CameraDeviceLifecycle.OPEN
                cameraDevice = camera
                if (normalVideoUltraWideInstantSwitchActive &&
                    normalVideoPrimaryCameraId != null &&
                    camera.id != normalVideoPrimaryCameraId
                ) {
                    PLog.i(
                        TAG,
                        "Normal Video instant switch requested while CameraDevice was opening; " +
                            "reopening the stable primary: opened=${camera.id}, expected=$normalVideoPrimaryCameraId"
                    )
                    openCamera(surfaceTexture, preserveVideoRecording = preserveVideoRecording)
                    return
                }
                if (dualCaptureEnabled && captureMode == CaptureMode.VIDEO) {
                    startDualCaptureCamera()
                }
                if (
                    spatialVideoEnabled &&
                    spatialVideoStreamingMode != SpatialVideoStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL
                ) {
                    spatialVideoUltraWidePhysicalCameraId?.let { startSpatialVideoStandbyCamera(it) }
                } else if (
                    normalVideoUltraWideInstantSwitchActive &&
                    normalVideoStreamingMode != SpatialVideoStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL
                ) {
                    normalVideoUltraWidePhysicalCameraId?.let { startSpatialVideoStandbyCamera(it) }
                }
                val pendingReconfigure = pendingCaptureModeReconfigureRequest
                if (captureMode != _state.value.captureMode ||
                    pendingReconfigure?.targetCaptureMode?.let { it != captureMode } == true
                ) {
                    PLog.i(
                        TAG,
                        "Camera open completed with stale capture mode: opened=$captureMode, " +
                            "current=${_state.value.captureMode}; rebuilding outputs on the same CameraDevice"
                    )
                    if (pendingReconfigure != null) {
                        pendingCaptureModeReconfigureRequest = null
                    }
                    beginCaptureModeSessionReconfigure(surfaceTexture, camera, pendingReconfigure)
                    return
                }
                if (pendingReconfigure?.targetCaptureMode == captureMode) {
                    pendingCaptureModeReconfigureRequest = null
                }
                if (restartCameraForRawCaptureOutputMismatch("camera opened")) {
                    return
                }
                createPreviewSession(openGeneration = openGeneration)
            }

            override fun onDisconnected(camera: CameraDevice) {
                if (openGeneration != cameraOpenGeneration) {
                    closeCameraDeviceAndAwait(camera, "stale camera disconnect", blocksLifecycle = false)
                    return
                }
                if (spatialVideoEnabled) {
                    PLog.e(
                        TAG,
                        "Special Video primary disconnected: mode=$spatialVideoStreamingMode " +
                            "main=$spatialVideoMainPhysicalCameraId ultraWide=$spatialVideoUltraWidePhysicalCameraId " +
                            "openId=${camera.id} reason=CameraDevice disconnected"
                    )
                }
                spatialVideoPrimaryReopenInProgress = false
                PLog.w(TAG, "Camera disconnected: ${camera.id} - 相机被其他应用或系统接管")
                handleCameraDeviceUnavailable(
                    camera = camera,
                    reason = "camera disconnected: ${camera.id}"
                )

                // 通知上层：相机断开连接，可以在 onResume 时重试
                onCameraError?.invoke(
                    ERROR_CAMERA_DISCONNECTED,
                    "相机已被其他应用或系统接管",
                    true  // canRetry = true
                )
            }

            override fun onError(camera: CameraDevice, error: Int) {
                if (openGeneration != cameraOpenGeneration) {
                    closeCameraDeviceAndAwait(camera, "stale camera error", blocksLifecycle = false)
                    return
                }
                spatialVideoPrimaryReopenInProgress = false
                val errorMessage = when (error) {
                    ERROR_CAMERA_IN_USE ->
                        "相机正在被其他应用使用"

                    ERROR_MAX_CAMERAS_IN_USE ->
                        "已达到相机最大打开数量"

                    ERROR_CAMERA_DISABLED ->
                        "相机被系统策略禁用"

                    ERROR_CAMERA_DEVICE ->
                        "相机设备遇到严重错误"

                    ERROR_CAMERA_SERVICE ->
                        "相机服务遇到严重错误"

                    else -> "未知相机错误 ($error)"
                }

                if (spatialVideoEnabled) {
                    PLog.e(
                        TAG,
                        "Special Video primary open failed: mode=$spatialVideoStreamingMode " +
                            "main=$spatialVideoMainPhysicalCameraId ultraWide=$spatialVideoUltraWidePhysicalCameraId " +
                            "openId=${camera.id} error=$error reason=$errorMessage"
                    )
                }
                PLog.e(TAG, "Camera error: ${camera.id}, error=$error - $errorMessage")
                handleCameraDeviceUnavailable(
                    camera = camera,
                    reason = "camera error ${camera.id}: $error"
                )

                // 判断是否可以重试
                val canRetry = when (error) {
                    ERROR_CAMERA_IN_USE,
                    ERROR_MAX_CAMERAS_IN_USE -> true

                    ERROR_CAMERA_DISABLED,
                    ERROR_CAMERA_DEVICE,
                    ERROR_CAMERA_SERVICE -> false

                    else -> false
                }

                // 通知上层
                onCameraError?.invoke(error, errorMessage, canRetry)
                _state.value = _state.value.copy(isCapturing = false)
            }

            override fun onClosed(camera: CameraDevice) {
                handleCameraDeviceClosed(camera)
            }
        }
        PLog.i(
            TAG,
            "打开相机: selected=$cameraId, open=$openCameraId, targetZoom=$targetZoomRatioByMain, " +
                    "physicalOutput=$outputPhysicalCameraId, " +
                    "模式: ${captureMode.name}"
        )

        var previewSize = _state.value.currentPreviewSize
        // Never carry a low-light shutter cap across a front/back camera reopen.
        videoExposureTimePriorityActive = false
        videoExposureTimePriorityFps = 0
        availableCaptureRequestKeyNames = null

        try {
            try {
                val openCharacteristics = getCameraCharacteristicsCached(openCameraId)
                cacheActiveCameraCharacteristics(openCameraId, openCharacteristics)
                availableCaptureRequestKeyNames = loadAvailableCaptureRequestKeyNames(openCharacteristics)

                // 缓存固定属性（传感器方向、镜头朝向、硬件级别）
                // 这些值在相机生命周期内不会改变，避免在每帧预览中重复获取
                cachedSensorOrientation = openCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                cachedLensFacing = openCharacteristics.get(CameraCharacteristics.LENS_FACING)
                    ?: CameraCharacteristics.LENS_FACING_BACK
                cachedHardwareLevel = openCharacteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                    ?: CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED

                val hardwareLevelName = when (cachedHardwareLevel) {
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                    else -> "UNKNOWN($cachedHardwareLevel)"
                }

                // 更新硬件能力缓存
                val capabilities =
                    openCharacteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    isStreamUseCaseSupported = capabilities.contains(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE
                    )
                    availableStreamUseCases = openCharacteristics.get(
                        CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES
                    ) ?: longArrayOf()
                } else {
                    isStreamUseCaseSupported = false
                    availableStreamUseCases = longArrayOf()
                }
                var capabilityCameraId = outputPhysicalCameraId ?: openCameraId
                val capabilityCharacteristics = if (capabilityCameraId == openCameraId) {
                    openCharacteristics
                } else {
                    getCameraCharacteristicsOrNull(
                        capabilityCameraId,
                        "physical output capability preload"
                    ) ?: run {
                        PLog.w(
                            TAG,
                            "Physical output camera $capabilityCameraId characteristics unavailable; " +
                                    "falling back to open camera $openCameraId"
                        )
                        outputPhysicalCameraId = null
                        activeOutputPhysicalCameraId = null
                        capabilityCameraId = openCameraId
                        openCharacteristics
                    }
                }
                val stabilizationCameraChanged = realtimeStabilizationCoordinator.configureCamera(
                    cameraId = capabilityCameraId,
                    characteristics = capabilityCharacteristics,
                    timingCharacteristics = openCharacteristics,
                )
                if (_state.value.videoRecordingState.isRecording) {
                    videoRecorder.onCameraSwitched(
                        sensorOrientationDegrees = cachedSensorOrientation,
                        frontFacing = isCurrentCameraFrontFacing(),
                        restartStabilization = stabilizationCameraChanged,
                    )
                }
                if (_state.value.captureMode == CaptureMode.PHOTO &&
                    _state.value.photoPreviewStabilizationEnabled &&
                    !realtimeStabilizationCoordinator.isCurrentCameraSupported
                ) {
                    PLog.e(
                        TAG,
                        "Disabling photo preview stabilization: current camera is unsupported"
                    )
                    _state.value = _state.value.copy(
                        photoPreviewStabilizationEnabled = false
                    )
                    notifyEnhancedStabilizationUnavailable(
                        EnhancedStabilizationFallback.PhotoPreview
                    )
                }
                isManualSensorSupported =
                    capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) &&
                        openCharacteristics.readMetadataOrNull(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) != null &&
                        openCharacteristics.readMetadataOrNull(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) != null
                isManualPostProcessingSupported =
                    capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)
                isFlashSupported = openCharacteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                maxAfRegions = openCharacteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
                maxAeRegions = openCharacteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
                val maxAwbRegions = openCharacteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AWB) ?: 0
                availableAfModes =
                    openCharacteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
                availableEdgeModes =
                    openCharacteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: intArrayOf()
                availableNoiseReductionModes =
                    openCharacteristics.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
                        ?: intArrayOf()
                availableTonemapModes =
                    openCharacteristics.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES) ?: intArrayOf()
                availableColorCorrectionAberrationModes =
                    openCharacteristics.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES)
                        ?: intArrayOf()
                availableHotPixelModes =
                    openCharacteristics.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)
                        ?: intArrayOf()
                availableShadingModes =
                    openCharacteristics.get(CameraCharacteristics.SHADING_AVAILABLE_MODES) ?: intArrayOf()
                availableDistortionCorrectionModes =
                    openCharacteristics.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
                        ?: intArrayOf()
                availableVideoStabilizationModes =
                    openCharacteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                        ?: intArrayOf()
                availableOpticalStabilizationModes =
                    openCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                        ?: intArrayOf()
                availableLensShadingMapModes =
                    openCharacteristics.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)
                        ?: intArrayOf()
                availableFaceDetectModes =
                    openCharacteristics.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)
                        ?: intArrayOf()
                maxFaceCount =
                    openCharacteristics.get(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT) ?: 0
                faceDetectMode = resolveFaceDetectMode(availableFaceDetectModes, maxFaceCount)
                availableColorCorrectionModes = loadAvailableColorCorrectionModes(openCharacteristics)
                awbColorTemperatureRange = loadAwbColorTemperatureRange(openCharacteristics)
                lastWhiteBalanceResult = null
                isRawSupported = resolveRawCaptureOutput(capabilityCharacteristics, outputPhysicalCameraId) != null

                availableAeModes =
                    openCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: intArrayOf()
                availableAwbModes =
                    openCharacteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()

                PLog.i(
                    TAG,
                    "Flash capabilities: available=$isFlashSupported, " +
                            "aeModes=${availableAeModes.joinToString()}, " +
                            "alwaysFlash=${availableAeModes.contains(CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)}, " +
                            "aeOn=${availableAeModes.contains(CaptureRequest.CONTROL_AE_MODE_ON)}, " +
                            "aeOff=${availableAeModes.contains(CaptureRequest.CONTROL_AE_MODE_OFF)}"
                )
                PLog.i(
                    TAG,
                    "Face detection: available=${availableFaceDetectModes.joinToString()}, " +
                            "maxFaces=$maxFaceCount, selected=${faceDetectModeName(faceDetectMode)}"
                )

                isP010Supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        isOutputFormatAdvertised(capabilityCharacteristics, ImageFormat.YCBCR_P010) &&
                        (outputPhysicalCameraId?.let {
                            !isPhysicalOutputProfileFailed(it, ImageFormat.YCBCR_P010)
                        } ?: true)
                val resolvedVideoPreviewSize = refreshVideoCapabilities(
                    characteristics = capabilityCharacteristics,
                    timingCharacteristics = openCharacteristics,
                )
                previewSize = when (captureMode) {
                    CaptureMode.VIDEO -> resolvedVideoPreviewSize
                    CaptureMode.PHOTO -> CameraUtils.getFixedPreviewSize(openCharacteristics, _state.value.aspectRatio)
                }
                surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)

                // The encoder stays at its original output resolution, but the front camera may
                // expose a different valid input stream. Resize the recording SurfaceTexture before
                // creating the replacement Camera2 session, then recompute its crop on the next frame.
                if (
                    preserveVideoRecording &&
                    captureMode == CaptureMode.VIDEO &&
                    _state.value.videoRecordingState.isRecording
                ) {
                    val switchedVideoState = _state.value
                    val switchedUsesMgcStabilization = shouldUseVideoMgcStabilization()
                    val switchedInputSize = if (switchedUsesMgcStabilization) {
                        switchedVideoState.videoCapabilities.enhancedStabilizationInputSizesByResolution[
                            switchedVideoState.videoConfig.resolution
                        ]
                    } else {
                        switchedVideoState.videoCapabilities.cameraInputSizesByResolution[
                            switchedVideoState.videoConfig.resolution
                        ]
                    } ?: resolvedVideoPreviewSize
                    videoRecorder.updateCameraInputSize(
                        size = switchedInputSize,
                        currentCameraUsesEnhancedStabilization = switchedUsesMgcStabilization,
                        sensorOrientationDegrees = openCharacteristics.get(
                            CameraCharacteristics.SENSOR_ORIENTATION
                        ) ?: cachedSensorOrientation,
                    )
                }

                if (_state.value.useLivePhoto && captureMode == CaptureMode.PHOTO) {
                    livePhotoRecorder.startRecording()
                }

                PLog.i(
                    TAG, "Camera characteristics cached - selected=$cameraId, open=$openCameraId, " +
                            "capability=$capabilityCameraId, Level: $hardwareLevelName, " +
                            "ManualSensor: $isManualSensorSupported, ManualPost: $isManualPostProcessingSupported, " +
                            "RAW: $isRawSupported, P010: $isP010Supported, " +
                            "StreamUseCase: $isStreamUseCaseSupported " +
                            "[${availableStreamUseCases.joinToString()}], " +
                            "MaxRegions(AF/AE/AWB): $maxAfRegions/$maxAeRegions/$maxAwbRegions, " +
                            "AF modes: ${availableAfModes.joinToString()}, " +
                            "AWB modes: ${availableAwbModes.joinToString()}, " +
                            "ColorCorrection modes: ${availableColorCorrectionModes.joinToString()}, " +
                            "CCT range: ${awbColorTemperatureRange}"
                )

                val selectableNrModes = buildSelectableNoiseReductionModes(availableNoiseReductionModes)

                val focusCharacteristics = resolveActiveFocusCharacteristics(openCharacteristics)?.second
                    ?: openCharacteristics
                val minimumFocusDistance =
                    focusCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                maxAfRegions =
                    focusCharacteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
                availableAfModes =
                    focusCharacteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
                val supportsPointAutoFocus = supportsPointAutoFocus(
                    minimumFocusDistance = minimumFocusDistance,
                    maxRegions = maxAfRegions,
                    afModes = availableAfModes,
                )

                _state.update { currentState ->
                    currentState.copy(
                        useRaw = requestedRawCaptureEnabled,
                        isRawSupported = isRawSupported,
                        isP010Supported = isP010Supported,
                        availableNrModes = selectableNrModes,
                        supportsCctWhiteBalance = supportsCctWhiteBalance(),
                        canAdjustWhiteBalance = false,
                        actualAwbTemperature = null,
                        actualAwbTint = null,
                        actualAwbGains = null,
                        awbTemperatureMin = resolveAwbTemperatureRange().lower,
                        awbTemperatureMax = resolveAwbTemperatureRange().upper,
                        currentPreviewSize = previewSize,
                        currentCaptureSize = if (captureMode == CaptureMode.VIDEO) {
                            previewSize
                        } else {
                            currentState.currentCaptureSize
                        },
                        minimumFocusDistance = minimumFocusDistance,
                        supportsPointAutoFocus = supportsPointAutoFocus,
                        supportsPointMetering = maxAeRegions > 0,
                    )
                }
                refreshHyperfocalFocusDistanceIfEnabled(updatePreview = false)
                applyRetainedCaptureSettings()
            } catch (e: Exception) {
                handleCameraOpenFailure(
                    cameraId = openCameraId,
                    errorCode = ERROR_CAMERA_CHARACTERISTICS_UNAVAILABLE,
                    message = "相机特性不可用",
                    error = e
                )
                surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
                return
            }

            // 配置 SurfaceTexture
            if (captureModeReconfiguringSurfaceTexture === surfaceTexture) {
                captureModeReconfiguringSurfaceTexture = null
            }
            previewSurfaceTexture = surfaceTexture
            previewSurface = Surface(surfaceTexture)

            if (captureMode == CaptureMode.PHOTO) {
                val aspectRatio = state.value.aspectRatio
                val effectivelyUseRaw = requestedRawCaptureEnabled && isRawSupported
                val openCharacteristics = getCameraCharacteristicsCached(openCameraId)
                var outputCameraIdForStreams = outputPhysicalCameraId ?: openCameraId
                var outputCharacteristicsForStreams = if (outputCameraIdForStreams == openCameraId) {
                    openCharacteristics
                } else {
                    getCameraCharacteristicsCached(outputCameraIdForStreams)
                }
                var rawOutput = if (effectivelyUseRaw) {
                    resolveRawCaptureOutput(outputCharacteristicsForStreams, outputPhysicalCameraId)
                } else {
                    null
                }
                val wantsP010 = !effectivelyUseRaw &&
                        isP010Supported &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        state.value.useP010
                var captureFormat = if (rawOutput != null) {
                    rawOutput.format
                } else if (wantsP010 && isOutputFormatAdvertised(outputCharacteristicsForStreams, ImageFormat.YCBCR_P010)) {
                    ImageFormat.YCBCR_P010
                } else {
                    ImageFormat.YUV_420_888
                }
                if (outputPhysicalCameraId != null && isPhysicalOutputProfileFailed(outputPhysicalCameraId, captureFormat)) {
                    val failedPhysicalCameraId = outputPhysicalCameraId
                    PLog.w(
                        TAG,
                        "Skipping previously failed physical output profile: " +
                                "physicalCameraId=$failedPhysicalCameraId, format=${imageFormatToString(captureFormat)}. " +
                                "Falling back to logical output for this profile."
                    )
                    outputPhysicalCameraId = null
                    activeOutputPhysicalCameraId = null
                    outputCameraIdForStreams = openCameraId
                    outputCharacteristicsForStreams = openCharacteristics
                    rawOutput = null
                    captureFormat = ImageFormat.YUV_420_888
                }
                previewSize = CameraUtils.getFixedPreviewSize(outputCharacteristicsForStreams, aspectRatio)
                surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
                val captureSize = if (rawOutput != null) {
                    rawOutput.size
                } else {
                    CameraUtils.getBestCaptureSize(outputCharacteristicsForStreams, aspectRatio, captureFormat)
                }

                val isP3Supported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    outputCharacteristicsForStreams.get(CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES)
                        ?.getSupportedColorSpaces(captureFormat)
                        ?.contains(ColorSpace.Named.DISPLAY_P3) == true
                } else false

                _state.value = _state.value.copy(
                    isP3Supported = isP3Supported,
                    currentCaptureSize = captureSize
                )

                val readerMaxImages = resolveImageReaderMaxImages()
                imageReaderMaxImages = readerMaxImages

                PLog.d(
                    TAG,
                    "拍照尺寸: ${captureSize.width}x${captureSize.height}, 预览尺寸: ${previewSize.width}x${previewSize.height}, 格式: ${
                        when (captureFormat) {
                            ImageFormat.RAW_SENSOR -> "RAW_SENSOR"
                            ImageFormat.RAW10 -> "RAW10"
                            ImageFormat.YCBCR_P010 -> "P010"
                            else -> "YUV"
                        }
                    }, stream=$outputCameraIdForStreams, isP3Supported: $isP3Supported, imageReaderMaxImages: $readerMaxImages"
                )
                imageReader = ImageReader.newInstance(
                    captureSize.width,
                    captureSize.height,
                    captureFormat,
                    readerMaxImages
                ).apply {
                    setOnImageAvailableListener({ reader ->
                        if (isOppoBurstYuvSession) {
                            if (oppoContinuousBurstEngine.isActive()) {
                                oppoContinuousBurstEngine.onImageAvailable(reader)
                            } else {
                                // The repeating target has been detached; drain any trailing HAL buffers.
                                try {
                                    reader.acquireLatestImage()?.close()
                                } catch (error: Exception) {
                                    PLog.w(TAG, "Unable to drain trailing burst frame: ${error.message}")
                                }
                            }
                            return@setOnImageAvailableListener
                        }
                        try {
                            while (canAcquireImage("Too many open images")) {
                                val image = trackImage(reader.acquireNextImage()) ?: break
                                processOrBufferImageForCaptureResult(image)
                            }
                        } catch (e: Exception) {
                            PLog.e(TAG, "Error in onImageAvailable", e)
                            photoCaptures.values.filter { it.reader === reader }.toList()
                                .forEach { failPhotoCapture(it, "image acquisition failed") }
                        }
                    }, cameraHandler)
                }

            } else {
                safeCloseImageReader(imageReader)
                imageReader = null
                _state.value = _state.value.copy(isP3Supported = false)
            }

            PLog.d(TAG, "Opening camera: open=$openCameraId, selected=$cameraId")

            if (reuseCameraDevice != null) {
                PLog.i(TAG, "Reusing CameraDevice ${reuseCameraDevice.id} for capture-mode session rebuild")
                cameraStateCallback.onOpened(reuseCameraDevice)
            } else {
                cameraManager.openCamera(openCameraId, cameraStateCallback, cameraHandler)
            }

        } catch (e: Exception) {
            spatialVideoPrimaryReopenInProgress = false
            if (spatialVideoEnabled) {
                PLog.e(
                    TAG,
                    "Special Video primary open failed: mode=$spatialVideoStreamingMode " +
                        "main=$spatialVideoMainPhysicalCameraId ultraWide=$spatialVideoUltraWidePhysicalCameraId " +
                        "openId=$openCameraId reason=${e.message}",
                    e
                )
            }
            handleCameraOpenFailure(
                cameraId = openCameraId,
                errorCode = ERROR_CAMERA_OPEN_FAILED,
                message = "相机打开失败",
                error = e
            )
        }
    }

    fun updateHistogram(histogram: IntArray) {
        _state.value = _state.value.copy(histogram = histogram)
    }

    fun calculateAutoMetering(totalWeight: Double, weightedSumLuminance: Double) {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post { calculateAutoMetering(totalWeight, weightedSumLuminance) }
            return
        }
        val currentState = _state.value
        if (!currentState.isAutoExposure && (currentState.isIsoAuto || currentState.isShutterSpeedAuto)) {

            // --- 1. 计算亮度 ---
            val rawAvgLuminance = if (totalWeight > 0) weightedSumLuminance / totalWeight else 0.0

            // 保护：如果画面全黑，避免除以0或Log错误
            if (rawAvgLuminance < 1.0) return

            // --- 2. 按自动调整的参数选择测光域 ---
            val currentShutter = currentState.shutterSpeed
            val clampedPreviewTime = currentShutter.coerceAtMost(getMaxPreviewExposureTime(currentState))
            // 手动快门、自动 ISO：以实际预览亮度为目标。超过预览快门上限后，
            // 不能用拍摄快门放大测光亮度，否则会持续降低 ISO，反而压暗取景器。
            // 手动 ISO、自动快门：仍需换算拍摄亮度，才能在预览曝光不再变化时
            // 求出正确的长曝光快门，而不是一直增加到传感器上限。
            val exposureRatio = if (currentState.isShutterSpeedAuto) {
                currentShutter.toDouble() / clampedPreviewTime.toDouble()
            } else {
                1.0
            }
            val meteredLuminance = rawAvgLuminance * exposureRatio

            val targetLuminance = 128.0 // Target (Gamma Corrected 18% Gray)

            // --- 3. 计算 EV 误差 ---
            // 使用 Log2 计算差了多少档光圈 (Stops)
            // 这是一个更符合人眼和相机光学的度量方式
            val evErrorStops = ln(targetLuminance / meteredLuminance) / ln(2.0)

            // --- 4. 稳定性控制 (Deadband) ---
            // 如果误差在 +/- 0.3 EV (约 1/3 档) 以内，认为曝光准确，不调整
            // 这能极大减少画面“呼吸感”
            if (abs(evErrorStops) < 0.3) {
                return
            }

            // --- 5. 计算修正系数 (P控制 + 阻尼) ---
            // 阻尼系数 0.2 ~ 0.5 比较合适，太小收敛慢，太大容易震荡
            val damping = 0.3
            // 限制单次最大调整幅度，防止突变 (例如限制在 +/- 1 EV 内)
            val limitedEvError = evErrorStops.coerceIn(-1.0, 1.0)
            val correctionFactor = 2.0.pow(limitedEvError * damping)

            // --- 6. 应用调整 ---
            var newIso = currentState.iso
            var newShutter = currentState.shutterSpeed
            var needsUpdate = false

            if (currentState.isIsoAuto) {
                // 快门优先模式：快门固定，调 ISO
                val calculatedIso = (currentState.iso * correctionFactor).toInt()
                val range = currentState.getIsoRange()
                val clampedIso = calculatedIso.coerceIn(range.lower, range.upper)

                // 只有变化量超过一定阈值才应用（防止 ISO 在 100 和 101 之间跳动）
                if (abs(clampedIso - currentState.iso) > currentState.iso * 0.05) {
                    newIso = clampedIso
                    needsUpdate = true
                }
            } else {
                // ISO 优先模式：ISO 固定，调快门
                val calculatedShutter = (currentState.shutterSpeed * correctionFactor).toLong()
                val range = currentState.getManualShutterSpeedRange()
                val clampedShutter = calculatedShutter.coerceIn(range.lower, range.upper)

                if (abs(clampedShutter - currentState.shutterSpeed) > currentState.shutterSpeed * 0.05) {
                    newShutter = clampedShutter
                    needsUpdate = true
                }
            }

            // --- 7. 下发指令 ---
            if (needsUpdate) {
                PLog.d(
                    TAG,
                    "SEMI_AUTO_METERING isoAuto=${currentState.isIsoAuto} " +
                        "previewLuma=$rawAvgLuminance meteredLuma=$meteredLuminance " +
                        "exposureRatio=$exposureRatio evError=$evErrorStops " +
                        "previewShutterNs=$clampedPreviewTime " +
                        "captureShutterNs=$currentShutter->$newShutter " +
                        "iso=${currentState.iso}->$newIso",
                )
                // 更新状态
                _state.value = currentState.copy(iso = newIso, shutterSpeed = newShutter)

                // 关键修复：检查相机和会话是否仍然有效
                val device = cameraDevice
                val session = captureSession
                val builder = previewRequestBuilder

                if (device == null || session == null || builder == null) {
                    PLog.v(TAG, "calculateAutoMetering: camera not ready, skipping update")
                    return
                }

                try {
                    applyExposureSettings(builder, _state.value, false)
                    submitRepeatingPreviewRequest(session, builder)
                } catch (e: CameraAccessException) {
                    PLog.e(TAG, "Failed to update exposure: ${e.message}")
                } catch (e: IllegalStateException) {
                    PLog.w(TAG, "Failed to update exposure - camera closed: ${e.message}")
                } catch (e: Exception) {
                    PLog.e(TAG, "Failed to update exposure: ${e.message}")
                }
            }
        }
    }


    /**
     * Resolve the rear ultra-wide physical sensor belonging to the currently-open logical
     * camera. This deliberately avoids opening a second CameraDevice: the physical YUV
     * output is attached to the existing logical session, so unsupported devices simply
     * fall back to ordinary single-camera preview.
     */
    private fun resolveUltraWideAeMeteringPhysicalCameraId(
        state: CameraState,
        highSpeedVideo: Boolean,
    ): String? {
        if (!keepUltraWideAeReady || ultraWideAeMeteringSuppressed || _topOffPositionLockActive.value) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || highSpeedVideo) return null
        if (spatialVideoEnabled || dualCaptureEnabled) return null

        val currentCamera = state.getCurrentCameraInfo() ?: return null
        if (currentCamera.lensFacing != CameraCharacteristics.LENS_FACING_BACK ||
            currentCamera.isVirtualIszLens
        ) {
            return null
        }

        val logicalCameraId = currentCamera.logicalCameraId
            ?.takeIf { it.isNotBlank() }
            ?: return null
        if (logicalCameraId != getActiveOpenCameraId()) return null

        val logicalCharacteristics = getCameraCharacteristicsOrNull(
            logicalCameraId,
            "ultra-wide AE pre-metering logical camera",
        ) ?: return null
        val physicalIds = logicalCharacteristics.physicalCameraIds
        if (physicalIds.size < 2) return null

        val wideIdFromDiscoveredLens = state.availableCameras.asSequence()
            .filter {
                it.lensFacing == CameraCharacteristics.LENS_FACING_BACK &&
                    it.lensType == LensType.BACK_ULTRA_WIDE &&
                    !it.isVirtualIszLens
            }
            .filter { runCatching { it.getOpenCameraId() == logicalCameraId }.getOrDefault(false) }
            .mapNotNull { it.outputPhysicalCameraId ?: it.cameraId }
            .firstOrNull { it in physicalIds }

        val widePhysicalId = wideIdFromDiscoveredLens
            ?: currentCamera.physicalCameras
                .filter { it.intrinsicZoomRatio in 0.01f..0.85f }
                .minByOrNull { it.intrinsicZoomRatio }
                ?.cameraId
                ?.takeIf { it in physicalIds }
            ?: return null

        // The currently selected ultra-wide output is already running at full preview
        // resolution; do not allocate a duplicate stream for the same sensor.
        if (widePhysicalId == activeOutputPhysicalCameraId) return null

        // In HAL seamless-zoom mode the logical output may already be displaying ultra-wide
        // at sub-1x ratios. The hidden stream is only needed while another lens is active.
        if (logicalMultiCameraAutoSwitchActive &&
            getTargetZoomRatioByMain(state, currentCamera) <= 0.85f
        ) {
            return null
        }
        return widePhysicalId
    }

    private fun ensureUltraWideAeMeteringReader(
        physicalCameraId: String,
        handler: Handler,
    ): ImageReader? {
        ultraWideAeMeteringReader?.let { existing ->
            if (ultraWideAeMeteringReaderPhysicalCameraId == physicalCameraId) return existing
            closeUltraWideAeMeteringReader()
        }

        return try {
            val characteristics = getCameraCharacteristicsOrNull(
                physicalCameraId,
                "ultra-wide AE pre-metering stream",
            ) ?: return null
            val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return null
            val availableSizes = streamMap.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
            val size = availableSizes
                .filter { it.width <= 640 && it.height <= 480 }
                .minByOrNull { it.width.toLong() * it.height.toLong() }
                ?: availableSizes.minByOrNull { it.width.toLong() * it.height.toLong() }
                ?: return null

            ImageReader.newInstance(
                size.width,
                size.height,
                ImageFormat.YUV_420_888,
                2,
            ).also { reader ->
                reader.setOnImageAvailableListener({ source ->
                    try {
                        // The image pixels are intentionally unused. Draining every callback
                        // prevents the hidden stream from stalling the Camera2 pipeline.
                        source.acquireLatestImage()?.close()
                    } catch (error: Exception) {
                        PLog.v(TAG, "Ultra-wide AE metering frame drain skipped: ${error.message}")
                    }
                }, handler)
                ultraWideAeMeteringReader = reader
                ultraWideAeMeteringReaderPhysicalCameraId = physicalCameraId
                PLog.i(
                    TAG,
                    "Ultra-wide AE pre-metering stream ready: physical=$physicalCameraId " +
                        "size=${size.width}x${size.height}",
                )
            }
        } catch (error: Exception) {
            PLog.w(TAG, "Ultra-wide AE pre-metering unavailable for physical=$physicalCameraId", error)
            null
        }
    }

    private fun closeUltraWideAeMeteringReader() {
        val reader = ultraWideAeMeteringReader
        ultraWideAeMeteringReader = null
        ultraWideAeMeteringReaderPhysicalCameraId = null
        try {
            reader?.setOnImageAvailableListener(null, null)
            safeCloseImageReader(reader)
        } catch (error: Exception) {
            PLog.w(TAG, "Unable to close ultra-wide AE metering reader: ${error.message}")
        }
    }

    private fun updateUltraWideAeMeteringResult(result: TotalCaptureResult) {
        val physicalCameraId = activeUltraWideAeMeteringPhysicalCameraId
            ?: ultraWideAeMeteringReaderPhysicalCameraId
                ?.takeIf { it == activeOutputPhysicalCameraId }
            ?: return
        val physicalResult = result.physicalCameraResults[physicalCameraId]
            ?: if (activeOutputPhysicalCameraId == physicalCameraId) result else return

        val aeMode = physicalResult.get(CaptureResult.CONTROL_AE_MODE)
        if (aeMode == CaptureRequest.CONTROL_AE_MODE_OFF) return
        val aeState = physicalResult.get(CaptureResult.CONTROL_AE_STATE)
        if (aeState != CaptureResult.CONTROL_AE_STATE_CONVERGED &&
            aeState != CaptureResult.CONTROL_AE_STATE_LOCKED
        ) {
            return
        }
        val exposureTimeNs = physicalResult.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            ?.takeIf { it > 0L }
            ?: return
        val sensitivityIso = physicalResult.get(CaptureResult.SENSOR_SENSITIVITY)
            ?.takeIf { it > 0 }
            ?: return
        val frameDurationNs = physicalResult.get(CaptureResult.SENSOR_FRAME_DURATION)
            ?.takeIf { it > 0L }
            ?: exposureTimeNs

        latestUltraWideAeReading = UltraWideAeReading(
            physicalCameraId = physicalCameraId,
            exposureTimeNs = exposureTimeNs,
            sensitivityIso = sensitivityIso,
            frameDurationNs = frameDurationNs,
            sampledAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
        )
    }

    /**
     * Seed only the first frame after a same-logical-camera switch with the ultra-wide
     * sensor's recently converged exposure. The repeating request is immediately returned
     * to normal AE as soon as that first frame completes.
     */
    private fun applyPendingUltraWideAeHandoff(builder: CaptureRequest.Builder) {
        if (_topOffPositionLockActive.value) {
            pendingUltraWideAeHandoff = null
            ultraWideAeHandoffActiveForSession = false
            return
        }
        val reading = pendingUltraWideAeHandoff ?: return
        val ageMs = SystemClock.elapsedRealtime() - reading.sampledAtElapsedRealtimeMs
        val currentState = _state.value
        val currentCamera = currentState.getCurrentCameraInfo()
        val currentTargetZoom = getTargetZoomRatioByMain(currentState, currentCamera)
        val targetsUltraWide = reading.physicalCameraId == activeOutputPhysicalCameraId ||
            (logicalMultiCameraAutoSwitchActive &&
                reading.physicalCameraId == ultraWideAeMeteringReaderPhysicalCameraId &&
                currentTargetZoom <= 0.85f)
        if (!targetsUltraWide ||
            ageMs !in 0L..ULTRA_WIDE_AE_HANDOFF_MAX_AGE_MS ||
            !currentState.isAutoExposure
        ) {
            pendingUltraWideAeHandoff = null
            return
        }

        val physicalCharacteristics = getCameraCharacteristicsOrNull(
            reading.physicalCameraId,
            "ultra-wide AE handoff",
        ) ?: run {
            pendingUltraWideAeHandoff = null
            return
        }
        val exposureRange = physicalCharacteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            ?: run {
                pendingUltraWideAeHandoff = null
                return
            }
        val sensitivityRange = physicalCharacteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            ?: run {
                pendingUltraWideAeHandoff = null
                return
            }
        val physicalCapabilities = physicalCharacteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: intArrayOf()
        if (!isManualSensorSupported &&
            !physicalCapabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        ) {
            pendingUltraWideAeHandoff = null
            return
        }
        val maxFrameDurationNs = physicalCharacteristics.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)
            ?: Long.MAX_VALUE
        val exposureTimeNs = reading.exposureTimeNs.coerceIn(exposureRange.lower, exposureRange.upper)
        val sensitivityIso = reading.sensitivityIso.coerceIn(sensitivityRange.lower, sensitivityRange.upper)
        val frameDurationNs = reading.frameDurationNs
            .coerceAtLeast(exposureTimeNs)
            .coerceAtMost(maxFrameDurationNs)

        try {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTimeNs)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, sensitivityIso)
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs)
            builder.setTag(UltraWideAeHandoffRequestTag(reading.physicalCameraId))
            ultraWideAeHandoffActiveForSession = true
            pendingUltraWideAeHandoff = null
            PLog.i(
                TAG,
                "Ultra-wide AE handoff primed: physical=${reading.physicalCameraId} " +
                    "iso=$sensitivityIso exposureNs=$exposureTimeNs ageMs=$ageMs",
            )
        } catch (error: Exception) {
            pendingUltraWideAeHandoff = null
            ultraWideAeHandoffActiveForSession = false
            PLog.w(TAG, "Unable to apply cached ultra-wide exposure; continuing with normal AE", error)
        }
    }

    private fun finishUltraWideAeHandoff(
        session: CameraCaptureSession,
        request: CaptureRequest,
    ) {
        val tag = request.tag as? UltraWideAeHandoffRequestTag ?: return
        if (session !== captureSession || !ultraWideAeHandoffActiveForSession) {
            return
        }

        val builder = previewRequestBuilder ?: return
        try {
            applyExposureSettings(builder, _state.value, isCapture = false)
            builder.setTag(null)
            ultraWideAeHandoffActiveForSession = false
            submitRepeatingPreviewRequest(session, builder)
            PLog.i(TAG, "Ultra-wide AE handoff complete; automatic exposure resumed")
        } catch (error: Exception) {
            ultraWideAeHandoffActiveForSession = false
            PLog.w(TAG, "Unable to resume automatic exposure after ultra-wide handoff", error)
        }
    }

    private fun retryPreviewSessionWithoutUltraWideAeMetering(
        reason: String,
        openGeneration: Long,
    ): Boolean {
        if (!ultraWideAeMeteringSessionAttached ||
            ultraWideAeMeteringSuppressed ||
            openGeneration != cameraOpenGeneration
        ) {
            return false
        }
        ultraWideAeMeteringSuppressed = true
        activeUltraWideAeMeteringPhysicalCameraId = null
        ultraWideAeMeteringSessionAttached = false
        closeUltraWideAeMeteringReader()
        latestUltraWideAeReading = null
        PLog.w(TAG, "Retrying camera session without optional ultra-wide AE stream: $reason")
        createPreviewSession(openGeneration = openGeneration)
        return true
    }

    private fun createPreviewSession(
        forceWithoutVendorSessionParameters: Boolean = false,
        openGeneration: Long = cameraOpenGeneration
    ) {
        val handler = cameraHandler ?: return
        if (Looper.myLooper() != handler.looper) {
            handler.post { createPreviewSession(forceWithoutVendorSessionParameters, openGeneration) }
            return
        }
        if (openGeneration != cameraOpenGeneration) {
            PLog.w(TAG, "Skipping stale preview session creation")
            return
        }
        if (_state.value.captureMode == CaptureMode.PHOTO) {
            burstGyroRecorder.start(handler)
        } else {
            burstGyroRecorder.stop()
        }
        val device = cameraDevice ?: return
        val surface = previewSurface ?: return
        // createCaptureSession closes the previous session before onConfigured runs.
        // Stop exposing it while the replacement is being configured.
        invalidateCaptureSession(captureSession, "preview session replacement")
        if (!keepUltraWideAeReady || _topOffPositionLockActive.value) {
            closeUltraWideAeMeteringReader()
            activeUltraWideAeMeteringPhysicalCameraId = null
            ultraWideAeMeteringSessionAttached = false
            latestUltraWideAeReading = null
            pendingUltraWideAeHandoff = null
            ultraWideAeHandoffActiveForSession = false
            if (!keepUltraWideAeReady) {
                ultraWideAeMeteringSuppressed = false
            }
        }
        val captureMode = _state.value.captureMode
        val reader = imageReader
        val highSpeedVideo = captureMode == CaptureMode.VIDEO &&
            _state.value.videoConfig.fps == VideoFpsPreset.FPS_120 &&
            _state.value.videoRecordingState.shouldAttachCameraInput()
        val stabilizationReader = if (highSpeedVideo || dualAspectRecordingActive) null else ensureStabilizationImageReader()
        val useMgcStabilization = shouldUseVideoMgcStabilization()
        val videoSurface = if (
            captureMode == CaptureMode.VIDEO &&
            _state.value.videoRecordingState.shouldAttachCameraInput() &&
            !useMgcStabilization
        ) {
            videoRecorder.cameraInputSurface
        } else {
            null
        }
        val normalVideoSwitchableRecordSurface = if (
            captureMode == CaptureMode.VIDEO &&
            normalVideoUltraWideInstantSwitchActive &&
            !spatialVideoEnabled &&
            normalVideoStreamingMode == SpatialVideoStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL &&
            _state.value.videoRecordingState.shouldAttachCameraInput() &&
            !highSpeedVideo
        ) {
            videoRecorder.switchableCameraInputSurface?.takeIf { it.isValid }
        } else {
            null
        }
        val logicalSpatialOutput = spatialVideoEnabled &&
            spatialVideoStreamingMode == SpatialVideoStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL
        val logicalNormalVideoUltraWideOutput = normalVideoUltraWideInstantSwitchActive &&
            normalVideoStreamingMode == SpatialVideoStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL
        val logicalUltraWidePreviewOutput = logicalSpatialOutput || logicalNormalVideoUltraWideOutput
        val ultraWidePreviewPhysicalCameraId = currentUltraWideStandbyCameraId()
        // Keep the secondary physical output even when the primary stream uses EIS+.
        // Photon EIS+ processes the main camera through its YUV pipeline; the 0.5x
        // companion remains an independent, un-stabilized native Camera2 stream.
        val spatialSurface = if (
            captureMode == CaptureMode.VIDEO &&
            (logicalSpatialOutput || dualAspectRecordingActive) &&
            _state.value.videoRecordingState.shouldAttachCameraInput()
        ) spatialVideoRecorder.cameraInputSurface else null
        // Keep the ultra-wide preview output attached for the lifetime of Special Video.
        // Visibility is a UI-only choice; adding/removing this output on every lens tap would
        // force a CameraCaptureSession rebuild and defeat instant preview handoff.
        val spatialPreviewSurface = if (
            (captureMode == CaptureMode.VIDEO || captureMode == CaptureMode.PHOTO) &&
            logicalUltraWidePreviewOutput
        ) spatialVideoStandbyPreviewSurface?.takeIf { it.isValid } else null
        // The optional virtual 3.5x track is another live output from the same main sensor.
        // Its dedicated renderer applies 3.5x crop before encoding; the 1x request stays untouched.
        val spatialTelephotoSurface = if (
            captureMode == CaptureMode.VIDEO &&
            spatialVideoEnabled &&
            spatialVideoVirtualTelephotoEnabled &&
            _state.value.videoRecordingState.shouldAttachCameraInput()
        ) spatialVideoTelephotoRecorder.cameraInputSurface?.takeIf { it.isValid } else null
        val usesUltraWideDualCapture = captureMode == CaptureMode.VIDEO &&
            dualCaptureEnabled && _dualCaptureSource.value == DualCaptureSource.ULTRA_WIDE &&
            dualCapturePhysicalCameraId != null
        val ultraWidePreviewSurface = if (usesUltraWideDualCapture) dualCapturePreviewSurface else null
        val ultraWideRecordSurface = if (usesUltraWideDualCapture) dualCaptureRecordSurface else null
        val ultraWidePhysicalCameraId = dualCapturePhysicalCameraId
        val ultraWideAeMeteringPhysicalCameraId = if (
            dualAspectRecordingActive || _topOffPositionLockActive.value
        ) {
            null
        } else {
            resolveUltraWideAeMeteringPhysicalCameraId(
                state = _state.value,
                highSpeedVideo = highSpeedVideo,
            )
        }
        val ultraWideAeReader = ultraWideAeMeteringPhysicalCameraId?.let { physicalId ->
            ensureUltraWideAeMeteringReader(physicalId, handler)
        }
        val ultraWideAeMeteringSurface = ultraWideAeReader?.surface
        activeUltraWideAeMeteringPhysicalCameraId =
            ultraWideAeMeteringPhysicalCameraId.takeIf { ultraWideAeMeteringSurface != null }
        ultraWideAeMeteringSessionAttached = ultraWideAeMeteringSurface != null
        ultraWideAeHandoffActiveForSession = false
        val sessionGeneration = ++previewSessionGeneration
        pendingVendorSessionParameterRestart = false
        val templateType = if (
            captureMode == CaptureMode.VIDEO &&
            !useMgcStabilization
        ) {
            CameraDevice.TEMPLATE_RECORD
        } else {
            CameraDevice.TEMPLATE_PREVIEW
        }
        PLog.i(
            TAG,
            "Creating repeating request: " +
                "template=${if (templateType == CameraDevice.TEMPLATE_PREVIEW) "PREVIEW" else "RECORD"}, " +
                "captureMode=$captureMode, mgcStabilization=$useMgcStabilization"
        )
        var vendorSessionParametersApplied = false

        try {
            val highSpeedFpsRange = if (highSpeedVideo) {
                resolveHighSpeed120FpsRange()
                    ?: throw IllegalStateException("Camera no longer advertises a 120fps high-speed range for the selected 1080p stream")
            } else {
                null
            }
            previewRequestBuilder = device.createCaptureRequest(templateType).apply {
                addTarget(surface)
                stabilizationReader?.surface?.let(::addTarget)
                videoSurface?.let(::addTarget)
                normalVideoSwitchableRecordSurface?.let(::addTarget)
                spatialSurface?.let(::addTarget)
                spatialTelephotoSurface?.let(::addTarget)
                spatialPreviewSurface?.let(::addTarget)
                ultraWidePreviewSurface?.let(::addTarget)
                ultraWideRecordSurface?.let(::addTarget)
                ultraWideAeMeteringSurface?.let(::addTarget)

                // 应用所有相机参数（曝光、白平衡、闪光灯、变焦）
                applyBaseCameraSettings(this, isCapture = false)
                applyPendingUltraWideAeHandoff(this)
                if (highSpeedVideo) {
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, requireNotNull(highSpeedFpsRange))
                    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                    set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                }
            }

            val surfaces = mutableListOf(surface)
            stabilizationReader?.surface?.let(surfaces::add)
            videoSurface?.let(surfaces::add)
            normalVideoSwitchableRecordSurface?.let(surfaces::add)
            spatialSurface?.let(surfaces::add)
            spatialTelephotoSurface?.let(surfaces::add)
            spatialPreviewSurface?.let(surfaces::add)
            ultraWidePreviewSurface?.let(surfaces::add)
            ultraWideRecordSurface?.let(surfaces::add)
            ultraWideAeMeteringSurface?.let(surfaces::add)
            if (captureMode == CaptureMode.PHOTO) {
                val captureReader = reader ?: return
                surfaces += captureReader.surface
            }

            if (captureMode == CaptureMode.VIDEO) {
                if (highSpeedVideo) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || videoSurface == null || surfaces.size != 2) {
                        throw IllegalStateException("1080p120 requires a supported two-surface Camera2 high-speed session")
                    }
                    PLog.i(TAG, "Creating constrained high-speed video session: 1080p @ 120fps")
                    device.createConstrainedHighSpeedCaptureSession(
                        surfaces,
                        object : CameraCaptureSession.StateCallback() {
                            override fun onClosed(session: CameraCaptureSession) {
                                invalidateCaptureSession(session, "high-speed video session closed")
                            }

                            override fun onConfigured(session: CameraCaptureSession) {
                                if (openGeneration != cameraOpenGeneration || sessionGeneration != previewSessionGeneration) {
                                    safeCloseCaptureSession(session, "stale high-speed video session")
                                    return
                                }
                                onSessionConfigured(session, openGeneration, sessionGeneration)
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                if (openGeneration != cameraOpenGeneration || sessionGeneration != previewSessionGeneration) {
                                    safeCloseCaptureSession(session, "stale high-speed configure failure")
                                    return
                                }
                                PLog.e(TAG, "Camera2 constrained high-speed session configuration failed")
                                safeCloseCaptureSession(session, "high-speed configure failure")
                                handlePreviewSessionFailure("1080p120 high-speed session configure failed", openGeneration)
                            }
                        },
                        handler
                    )
                    return
                }
                val sessionOperatingMode = resolveVideoSessionOperatingMode()
                PLog.i(
                    TAG,
                    "Creating video session: operatingMode=0x${sessionOperatingMode.toString(16)}, " +
                        "oppoSuperStabilization=$oppoSuperStabilizationEnabled, " +
                        "lensFacing=${if (isCurrentCameraFrontFacing()) "front" else "rear"}"
                )
                val sessionConfig = SessionConfiguration(
                    sessionOperatingMode,
                    buildList {
                        add(createOutputConfiguration(surface))
                        stabilizationReader?.surface?.let { eisSurface ->
                            add(createOutputConfiguration(eisSurface))
                        }
                        videoSurface?.let { encoderSurface ->
                            add(createOutputConfiguration(
                                encoderSurface,
                                useHlg10 = videoCameraHlg10InputActive
                            ))
                        }
                        normalVideoSwitchableRecordSurface?.let { encoderSurface ->
                            val wideId = normalVideoUltraWidePhysicalCameraId
                                ?: throw IllegalStateException("Normal Video ultra-wide recording camera is unavailable")
                            if (normalVideoStreamingMode == SpatialVideoStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL) {
                                add(createOutputConfiguration(encoderSurface, physicalCameraId = wideId))
                            }
                        }
                        spatialSurface?.let { encoderSurface ->
                            val wideId = spatialVideoUltraWidePhysicalCameraId
                                ?: throw IllegalStateException("Special Video ultra-wide physical camera is unavailable")
                            add(createOutputConfiguration(encoderSurface, physicalCameraId = wideId))
                        }
                        spatialTelephotoSurface?.let { telephotoSurface ->
                            // Default physical routing keeps this output on the primary main sensor.
                            // The 3.5x crop is applied by its realtime encoder renderer, not Camera2's
                            // request-wide zoom (which would also crop the existing 1x stream).
                            add(createOutputConfiguration(
                                telephotoSurface,
                                useHlg10 = videoCameraHlg10InputActive
                            ))
                        }
                        spatialPreviewSurface?.let { previewOutput ->
                            val wideId = ultraWidePreviewPhysicalCameraId
                                ?: throw IllegalStateException("Ultra-wide preview physical camera is unavailable")
                            add(createOutputConfiguration(previewOutput, physicalCameraId = wideId))
                        }
                        if (ultraWidePreviewSurface != null || ultraWideRecordSurface != null) {
                            val wideId = ultraWidePhysicalCameraId
                                ?: throw IllegalStateException("Ultra-wide Dual Capture physical camera is unavailable")
                            ultraWidePreviewSurface?.let { widePreview ->
                                add(createOutputConfiguration(widePreview, physicalCameraId = wideId))
                            }
                            ultraWideRecordSurface?.let { wideEncoder ->
                                add(createOutputConfiguration(wideEncoder, physicalCameraId = wideId))
                            }
                        }
                        ultraWideAeMeteringSurface?.let { meteringSurface ->
                            val meteringId = ultraWideAeMeteringPhysicalCameraId
                                ?: throw IllegalStateException("Ultra-wide AE metering camera ID is unavailable")
                            add(createOutputConfiguration(meteringSurface, physicalCameraId = meteringId))
                        }
                    },
                    Executor { handler.post(it) },
                    object : CameraCaptureSession.StateCallback() {
                        override fun onClosed(session: CameraCaptureSession) {
                            invalidateCaptureSession(session, "video session closed")
                        }

                        override fun onConfigured(session: CameraCaptureSession) {
                            if (openGeneration != cameraOpenGeneration || sessionGeneration != previewSessionGeneration) {
                                PLog.w(TAG, "Closing stale video preview session")
                                safeCloseCaptureSession(session, "stale video preview session")
                                return
                            }
                            closeUnusedStabilizationImageReader(stabilizationReader)
                            onSessionConfigured(session, openGeneration, sessionGeneration)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            if (openGeneration != cameraOpenGeneration || sessionGeneration != previewSessionGeneration) {
                                safeCloseCaptureSession(session, "stale video configure failure")
                                return
                            }
                            PLog.e(TAG, "Video session configuration failed")
                            safeCloseCaptureSession(session, "video configure failure")
                            if (normalVideoSwitchableRecordSurface != null &&
                                normalVideoUltraWideInstantSwitchActive
                            ) {
                                PLog.w(
                                    TAG,
                                    "Normal Video ultra-wide recording output rejected by Camera2; " +
                                        "retrying the same recording on the main 1x input",
                                )
                                fallbackNormalVideoUltraWideInstantSwitchToMain(
                                    "Camera2 rejected the ultra-wide recording output",
                                )
                                return
                            }
                            if (retryVideoSessionWithoutCameraHlg(openGeneration)) return
                            if (retryPreviewSessionWithoutUltraWideAeMetering("video configure failed", openGeneration)) return
                            if (vendorSessionParametersApplied &&
                                retryPreviewSessionWithoutVendorSessionParameters(
                                    reason = "video configure failed",
                                    openGeneration = openGeneration
                                )
                            ) {
                                return
                            }
                            if (retryPreviewSessionWithoutPhysicalOutput("video configure failed", openGeneration)) {
                                return
                            }
                            handlePreviewSessionFailure("video configure failed", openGeneration)
                        }
                    }
                )
                vendorSessionParametersApplied = applyInitialSessionParameters(
                    sessionConfig = sessionConfig,
                    device = device,
                    templateType = templateType,
                    includeVendorSessionParameters = !forceWithoutVendorSessionParameters
                ).usedVendorParameters
                device.createCaptureSession(sessionConfig)
                return
            }

            // Android 9+ 使用 SessionConfiguration
            val readerFormat = reader?.imageFormat ?: ImageFormat.YUV_420_888
            PLog.i(
                TAG,
                "Creating preview session: readerFormat=${imageFormatToString(readerFormat)}, " +
                        "isP010Supported=$isP010Supported"
            )
            val outputConfigs = buildList {
                add(createOutputConfiguration(surface))
                stabilizationReader?.surface?.let { eisSurface ->
                    add(createOutputConfiguration(eisSurface))
                }
                reader?.surface?.let { captureSurface ->
                    add(createOutputConfiguration(captureSurface))
                }
                spatialPreviewSurface?.let { previewOutput ->
                    val wideId = ultraWidePreviewPhysicalCameraId
                        ?: throw IllegalStateException("Ultra-wide preview physical camera is unavailable")
                    add(createOutputConfiguration(previewOutput, physicalCameraId = wideId))
                }
                ultraWideAeMeteringSurface?.let { meteringSurface ->
                    val meteringId = ultraWideAeMeteringPhysicalCameraId
                        ?: throw IllegalStateException("Ultra-wide AE metering camera ID is unavailable")
                    add(createOutputConfiguration(meteringSurface, physicalCameraId = meteringId))
                }
            }
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputConfigs,
                Executor { handler.post(it) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onClosed(session: CameraCaptureSession) {
                        invalidateCaptureSession(session, "photo session closed")
                    }

                    override fun onConfigured(session: CameraCaptureSession) {
                        if (openGeneration != cameraOpenGeneration || sessionGeneration != previewSessionGeneration) {
                            PLog.w(TAG, "Closing stale preview session")
                            safeCloseCaptureSession(session, "stale preview session")
                            return
                        }
                        closeUnusedStabilizationImageReader(stabilizationReader)
                        onSessionConfigured(session, openGeneration, sessionGeneration)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (openGeneration != cameraOpenGeneration || sessionGeneration != previewSessionGeneration) {
                            safeCloseCaptureSession(session, "stale configure failure")
                            return
                        }
                        PLog.e(
                            TAG,
                            "Session configuration failed: readerFormat=${imageFormatToString(readerFormat)}, " +
                                    "sessionColorSpace=${if (shouldUseP3ColorSpace()) "DISPLAY_P3" else "DEFAULT"}"
                        )
                        safeCloseCaptureSession(session, "photo configure failure")
                        if (retryPreviewSessionWithoutUltraWideAeMetering("photo configure failed", openGeneration)) return
                        if (vendorSessionParametersApplied &&
                            retryPreviewSessionWithoutVendorSessionParameters(
                                reason = "photo configure failed",
                                openGeneration = openGeneration
                            )
                        ) {
                            return
                        }
                        if (retryPreviewSessionWithoutPhysicalOutput("photo configure failed", openGeneration)) {
                            return
                        }
                        handlePreviewSessionFailure("photo configure failed", openGeneration)
                    }
                }
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                if (shouldUseP3ColorSpace()) {
                    sessionConfig.setColorSpace(ColorSpace.Named.DISPLAY_P3)
                }
            }
            vendorSessionParametersApplied = applyInitialSessionParameters(
                sessionConfig = sessionConfig,
                device = device,
                templateType = templateType,
                includeVendorSessionParameters = !forceWithoutVendorSessionParameters
            ).usedVendorParameters
            device.createCaptureSession(sessionConfig)
        } catch (e: IllegalStateException) {
            PLog.w(TAG, "Failed to create preview session", e)
            if (retryVideoSessionWithoutCameraHlg(openGeneration)) return
            if (retryPreviewSessionWithoutUltraWideAeMetering("create session illegal state: ${e.message}", openGeneration)) return
            if (vendorSessionParametersApplied &&
                retryPreviewSessionWithoutVendorSessionParameters(
                    reason = "create session illegal state: ${e.message}",
                    openGeneration = openGeneration
                )
            ) {
                return
            }
            if (retryPreviewSessionWithoutPhysicalOutput(
                    "create session illegal state: ${e.message}",
                    openGeneration
                )
            ) {
                return
            }
            handlePreviewSessionFailure("create session illegal state", openGeneration, e)
        } catch (e: Exception) {
            PLog.w(TAG, "Failed to create preview session", e)
            if (retryVideoSessionWithoutCameraHlg(openGeneration)) return
            if (retryPreviewSessionWithoutUltraWideAeMetering("create session exception: ${e.message}", openGeneration)) return
            if (vendorSessionParametersApplied &&
                retryPreviewSessionWithoutVendorSessionParameters(
                    reason = "create session exception: ${e.message}",
                    openGeneration = openGeneration
                )
            ) {
                return
            }
            if (retryPreviewSessionWithoutPhysicalOutput(
                    "create session exception: ${e.message}",
                    openGeneration
                )
            ) {
                return
            }
            handlePreviewSessionFailure("create session exception", openGeneration, e)
        }
    }

    private fun ensureStabilizationImageReader(): StabilizationImageReader? {
        val handler = cameraHandler ?: return null
        val state = _state.value
        if (!shouldUseAlgorithmicStabilization()) return null
        val size = when (state.captureMode) {
            CaptureMode.PHOTO -> state.currentPreviewSize
            CaptureMode.VIDEO -> state.videoCapabilities
                .enhancedStabilizationInputSizesByResolution[state.videoConfig.resolution]
                ?: return null
        }
        if (size.width <= 0 || size.height <= 0) return null
        stabilizationImageReader?.let { existing ->
            if (existing.width == size.width && existing.height == size.height) {
                return existing
            }
            closeStabilizationImageReader()
        }
        return StabilizationImageReader(
            size.width,
            size.height,
            RealtimeStabilizationCoordinator.STABILIZATION_IMAGE_READER_MAX_IMAGES,
        ).also { reader ->
            reader.setOnImageAvailableListener(handler) { source ->
                while (true) {
                    val image = try {
                        source.acquireNextImage()
                    } catch (error: IllegalStateException) {
                        PLog.e(TAG, "Cannot acquire MGC YUV image", error)
                        null
                    } ?: break
                    logStabilizationInputStats(image)
                    realtimeStabilizationCoordinator.submitStabilizationImage(image)
                }
            }
            stabilizationImageReader = reader
            stabilizationInputStatsFirstTimestampNs = 0L
            stabilizationInputStatsFrames = 0
            PLog.i(TAG, "MGC YUV input configured: ${size.width}x${size.height}")
        }
    }

    private fun logStabilizationInputStats(image: StabilizationImageReader.AcquiredImage) {
        val timestampNs = image.timestampNs
        if (timestampNs <= 0L) return
        stabilizationInputFrameCount += 1L
        if (stabilizationInputStatsFirstTimestampNs == 0L) {
            stabilizationInputStatsFirstTimestampNs = timestampNs
            stabilizationInputStatsFrames = 1
            return
        }

        stabilizationInputStatsFrames += 1
        val elapsedNs = timestampNs - stabilizationInputStatsFirstTimestampNs
        if (elapsedNs < 1_000_000_000L) return
        val actualFps = if (stabilizationInputStatsFrames > 1 && elapsedNs > 0L) {
            (stabilizationInputStatsFrames - 1) * 1_000_000_000f / elapsedNs.toFloat()
        } else {
            0f
        }
        PLog.i(
            TAG,
            "EIS+ YUV input stats: requested=${_state.value.videoConfig.fps.fps}, " +
                "actual=${"%.1f".format(actualFps)}, " +
                "frames=$stabilizationInputStatsFrames, size=${image.width}x${image.height}"
        )
        stabilizationInputStatsFirstTimestampNs = timestampNs
        stabilizationInputStatsFrames = 1
    }

    private fun scheduleEnhancedStabilizationInputWatchdog(
        sessionGeneration: Long,
        baselineFrameCount: Long = stabilizationInputFrameCount,
    ) {
        val handler = cameraHandler ?: return
        handler.postDelayed({
            if (sessionGeneration != previewSessionGeneration ||
                !shouldUseAlgorithmicStabilization()
            ) {
                return@postDelayed
            }
            if (stabilizationInputFrameCount == baselineFrameCount) {
                disableAlgorithmicStabilizationForMissingInput(sessionGeneration)
                return@postDelayed
            }
            scheduleEnhancedStabilizationInputWatchdog(
                sessionGeneration = sessionGeneration,
                baselineFrameCount = stabilizationInputFrameCount,
            )
        }, EIS_PLUS_INPUT_WATCHDOG_MS)
    }

    private fun disableAlgorithmicStabilizationForMissingInput(sessionGeneration: Long) {
        if (sessionGeneration != previewSessionGeneration ||
            !shouldUseAlgorithmicStabilization()
        ) {
            return
        }
        handleEnhancedStabilizationUnavailable(
            reason = "YUV input produced no frames for ${EIS_PLUS_INPUT_WATCHDOG_MS}ms"
        )
    }

    private fun handleEnhancedStabilizationUnavailable(reason: String) {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post { handleEnhancedStabilizationUnavailable(reason) }
            return
        }

        val state = _state.value
        currentStabilizationCameraId()
            .takeIf { it.isNotEmpty() }
            ?.let(enhancedStabilizationUnavailableCameraIds::add)
        when {
            state.captureMode == CaptureMode.VIDEO && _topOffPositionLockActive.value -> {
                PLog.e(TAG, "Disabling MGC Position Lock: $reason")
                _topOffPositionLockActive.value = false
                if (_state.value.videoRecordingState.isRecording || videoRecorder.isRecording()) {
                    videoRecorder.forceStop()
                } else {
                    reconfigureCameraForPositionLockModeChange("MGC stabilization input unavailable")
                }
            }

            state.captureMode == CaptureMode.VIDEO &&
                state.videoConfig.stabilizationMode == VideoStabilizationMode.ENHANCED -> {
                val fallbackMode = resolvePreviousVideoStabilizationMode(
                    state.videoCapabilities.availableStabilizationModes
                )
                PLog.e(
                    TAG,
                    "Disabling EIS+: $reason at " +
                        "${state.videoConfig.resolution.displayName}@" +
                        "${state.videoConfig.fps.fps}; fallback=$fallbackMode"
                )
                previousVideoStabilizationMode = fallbackMode
                _state.value = state.copy(
                    videoConfig = state.videoConfig.copy(stabilizationMode = fallbackMode)
                )
                refreshVideoCapabilities()
                previousVideoStabilizationMode =
                    _state.value.videoConfig.stabilizationMode
                notifyEnhancedStabilizationUnavailable(
                    EnhancedStabilizationFallback.Video(
                        _state.value.videoConfig.stabilizationMode
                    )
                )
                if (_state.value.videoRecordingState.isRecording) {
                    videoRecorder.forceStop()
            spatialVideoRecorder.forceStop()
                } else if (cameraDevice != null && previewSurface != null) {
                    createPreviewSession(openGeneration = cameraOpenGeneration)
                }
            }

            state.captureMode == CaptureMode.PHOTO &&
                state.photoPreviewStabilizationEnabled -> {
                PLog.e(TAG, "Disabling photo preview stabilization: $reason")
                _state.value = state.copy(photoPreviewStabilizationEnabled = false)
                refreshVideoCapabilities()
                notifyEnhancedStabilizationUnavailable(
                    EnhancedStabilizationFallback.PhotoPreview
                )
                if (cameraDevice != null && previewSurface != null) {
                    createPreviewSession(openGeneration = cameraOpenGeneration)
                }
            }

            else -> PLog.w(TAG, "Ignoring stale stabilization unavailable callback: $reason")
        }
    }

    private fun notifyEnhancedStabilizationUnavailable(
        fallback: EnhancedStabilizationFallback
    ) {
        try {
            onEnhancedStabilizationUnavailable?.invoke(fallback)
        } catch (error: RuntimeException) {
            PLog.w(TAG, "Stabilization unavailable callback failed: ${error.message}")
        }
    }

    private fun closeUnusedStabilizationImageReader(activeReader: StabilizationImageReader?) {
        if (activeReader != null) return
        closeStabilizationImageReader()
    }

    private fun closeStabilizationImageReader() {
        val reader = stabilizationImageReader ?: return
        stabilizationImageReader = null
        // Stop acquisition first, then release queued frames. Renderer leases retain both
        // their source image and its reader until the upload/sampling has finished.
        reader.close()
        realtimeStabilizationCoordinator.discardStabilizationImages(reader)
    }

    private fun retryPreviewSessionWithoutVendorSessionParameters(
        reason: String,
        openGeneration: Long
    ): Boolean {
        if (openGeneration != cameraOpenGeneration) return false
        PLog.w(TAG, "Retrying preview session without vendor session parameters: $reason")
        createPreviewSession(
            forceWithoutVendorSessionParameters = true,
            openGeneration = openGeneration
        )
        return true
    }

    private fun retryPreviewSessionWithoutPhysicalOutput(
        reason: String,
        openGeneration: Long
    ): Boolean {
        val failedPhysicalCameraId = activeOutputPhysicalCameraId ?: return false
        rememberPhysicalOutputProfileFailure(failedPhysicalCameraId, reason = reason)
        if (isRawCaptureReader(imageReader)) {
            val remainingRawOutput = getCameraCharacteristicsOrNull(failedPhysicalCameraId, "RAW output retry")
                ?.let { resolveRawCaptureOutput(it, failedPhysicalCameraId) }
            val surfaceTexture = previewSurfaceTexture
            val handler = cameraHandler
            if (remainingRawOutput != null && surfaceTexture != null && handler != null) {
                PLog.w(
                    TAG,
                    "Retrying physical RAW output: physicalCameraId=$failedPhysicalCameraId, " +
                            "format=${imageFormatToString(remainingRawOutput.format)}, reason=$reason"
                )
                // The other format needs its own ImageReader and advertised dimensions.
                return handler.post {
                    if (openGeneration == cameraOpenGeneration) {
                        openCamera(surfaceTexture)
                    }
                }
            }
        }
        PLog.w(
            TAG,
            "Retrying preview session without physical output binding: " +
                    "physicalCameraId=$failedPhysicalCameraId, " +
                    "format=${imageFormatToString(imageReader?.imageFormat ?: NO_IMAGE_READER_FORMAT)}, " +
                    "reason=$reason"
        )
        activeOutputPhysicalCameraId = null
        createPreviewSession(openGeneration = openGeneration)
        return true
    }

    private fun supportsCameraHlg10Input(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val characteristics = getActiveOpenCameraCharacteristics() ?: return false
        val profiles = try {
            characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
        } catch (_: Exception) {
            null
        } ?: return false
        if (!profiles.supportedProfiles.contains(DynamicRangeProfiles.HLG10)) return false

        // HLG video must coexist with the ordinary SDR viewfinder output in this session.
        val requiredProfiles = runCatching {
            profiles.getProfileCaptureRequestConstraints(DynamicRangeProfiles.HLG10)
        }.getOrNull() ?: return false
        return requiredProfiles.isEmpty() || requiredProfiles.contains(DynamicRangeProfiles.STANDARD)
    }

    private fun retryVideoSessionWithoutCameraHlg(openGeneration: Long): Boolean {
        if (!videoCameraHlg10InputActive) return false
        PLog.w(
            TAG,
            "Camera HAL rejected HLG10 output; retrying with SDR camera input and software HLG encoding"
        )
        videoCameraHlg10InputActive = false
        videoRecorder.setCameraInputHlg10Source(false)
        createPreviewSession(openGeneration = openGeneration)
        return true
    }

    private fun createOutputConfiguration(
        surface: Surface,
        useHlg10: Boolean = false,
        physicalCameraId: String? = activeOutputPhysicalCameraId,
    ): OutputConfiguration {
        return OutputConfiguration(surface).apply {
            if (useHlg10) {
                check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                setDynamicRangeProfile(DynamicRangeProfiles.HLG10)
            }
            physicalCameraId?.let { physicalCameraId ->
                setPhysicalCameraId(physicalCameraId)
                PLog.i(
                    TAG,
                    "OutputConfiguration bound to physicalCameraId=$physicalCameraId " +
                            "(openCameraId=$activeOpenCameraId)"
                )
            }
        }
    }

    private fun applyInitialSessionParameters(
        sessionConfig: SessionConfiguration,
        device: CameraDevice,
        templateType: Int,
        includeVendorSessionParameters: Boolean
    ): InitialSessionParametersResult {
        val state = _state.value
        val sessionKeys = try {
            getActiveOpenCameraCharacteristics()?.availableSessionKeys
        } catch (e: Exception) {
            PLog.w(TAG, "Failed to query available session keys", e)
            null
        }

        val shouldApplyStandardSessionParameters =
            sessionKeys?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE) == true ||
                sessionKeys?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE) == true
        val vendorSessionValues = if (includeVendorSessionParameters) {
            forcedVendorSessionParameterValues(state)
        } else {
            emptyMap()
        }
        val customVendorSessionKeys = if (includeVendorSessionParameters) {
            customVendorSessionParameterKeys(state)
        } else {
            emptyList()
        }

        if (
            !shouldApplyStandardSessionParameters &&
            vendorSessionValues.isEmpty() &&
            customVendorSessionKeys.isEmpty()
        ) {
            return InitialSessionParametersResult(applied = false, usedVendorParameters = false)
        }

        try {
            val builder = device.createCaptureRequest(templateType)
            if (shouldApplyStandardSessionParameters) {
                applyStabilizationSettings(builder, state)
            }
            if (vendorSessionValues.isNotEmpty()) {
                applyVendorCaptureSettings(
                    builder = builder,
                    lensId = state.currentCameraId,
                    values = vendorSessionValues,
                    target = "session"
                )
            }
            applyCustomVendorKeys(
                builder = builder,
                lensId = state.currentCameraId,
                keys = customVendorSessionKeys,
                target = "session"
            )
            sessionConfig.setSessionParameters(builder.build())
            PLog.d(
                TAG,
                "Initial session parameters applied: standard=$shouldApplyStandardSessionParameters, " +
                        "vendorKeys=${vendorSessionValues.keys.map { it.requestKeyName }}, " +
                        "customVendorKeys=${customVendorSessionKeys.map { it.keyName }}"
            )
            return InitialSessionParametersResult(
                applied = true,
                usedVendorParameters =
                    vendorSessionValues.isNotEmpty() || customVendorSessionKeys.isNotEmpty()
            )
        } catch (e: Exception) {
            PLog.w(TAG, "Failed to set initial session parameters", e)
            return InitialSessionParametersResult(applied = false, usedVendorParameters = false)
        }
    }

    private fun getTargetZoomRatioByMain(
        state: CameraState,
        camera: CameraInfo?
    ): Float {
        return camera?.let { it.intrinsicZoomRatio * state.zoomRatio } ?: state.zoomRatio
    }

    /**
     * True when this selected physical lens is exposed through a real logical multi-camera
     * CameraDevice and the HAL can select its physical sensors from CONTROL_ZOOM_RATIO.
     * In this mode every output must remain attached to the logical camera; binding an
     * OutputConfiguration to a physical ID pins that stream and forces session recreation.
     */
    private fun shouldUseLogicalMultiCameraZoom(
        state: CameraState = _state.value,
        camera: CameraInfo? = state.getCurrentCameraInfo(),
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        camera ?: return false
        if (camera.isVirtualIszLens) return false
        val logicalCameraId = camera.logicalCameraId?.takeIf { it.isNotBlank() } ?: return false
        val openCameraId = runCatching { camera.getOpenCameraId() }.getOrNull() ?: return false
        if (openCameraId != logicalCameraId) return false

        val characteristics = getCameraCharacteristicsOrNull(
            logicalCameraId,
            "logical multi-camera seamless zoom",
        ) ?: return false
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: return false
        if (!capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) {
            return false
        }
        if (characteristics.physicalCameraIds.size < 2) return false

        val zoomRange = characteristics.readMetadataOrNull(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            ?: return false
        if (zoomRange.lower <= 0f || zoomRange.upper <= zoomRange.lower) return false

        // Keep full-resolution RAW capture on its existing per-physical-camera path when the
        // logical device does not advertise a RAW output of its own.
        if (state.captureMode == CaptureMode.PHOTO && requestedRawCaptureEnabled &&
            CameraUtils.getRawCaptureOutput(characteristics) == null
        ) {
            return false
        }
        return true
    }

    /**
     * Check whether the currently running logical CameraDevice can service a lens-button
     * change without closing/reopening the device or rebuilding its CaptureSession.
     */
    fun canSwitchLensWithoutReopen(targetCameraId: String): Boolean {
        val state = _state.value
        val currentCamera = state.getCurrentCameraInfo() ?: return false
        val targetCamera = state.availableCameras.firstOrNull { it.cameraId == targetCameraId } ?: return false

        // Special Video already owns both camera streams. Switching between its paired
        // 1x and 0.5x lenses must only change which live preview is displayed; reopening
        // the primary CameraDevice here would stall the viewfinder and interrupt a paired
        // recording even though the standby ultra-wide stream is already running.
        if (normalVideoUltraWideInstantSwitchActive) {
            if (!isCurrentNormalVideoPairLens(targetCamera)) return false
            val primaryReady = cameraDevice != null && captureSession != null &&
                (normalVideoPrimaryCameraId == null || activeOpenCameraId == normalVideoPrimaryCameraId)
            val wideDeviceReady = currentUltraWideStandbyCameraId() != null &&
                (currentUltraWideStandbyStreamingMode() == SpatialVideoStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL ||
                    spatialVideoStandbyCameraDevice?.id == normalVideoUltraWidePhysicalCameraId)
            val widePreviewReady = spatialVideoStandbyPreviewSurface?.isValid == true
            PLog.i(
                TAG,
                "Normal Video lens preview handoff in-place: selected=${targetCamera.cameraId}, " +
                    "primaryReady=$primaryReady, wideDeviceReady=$wideDeviceReady, " +
                    "widePreviewReady=$widePreviewReady, mode=$normalVideoStreamingMode"
            )
            return true
        }

        if (spatialVideoEnabled) {
            val targetOpenId = runCatching { targetCamera.getOpenCameraId() }.getOrNull()
            val mainPhysicalId = spatialVideoMainPhysicalCameraId
            val widePhysicalId = spatialVideoUltraWidePhysicalCameraId
            val isMainPairLens = targetCamera.cameraId == spatialVideoPrimaryCameraId ||
                targetCamera.cameraId == mainPhysicalId ||
                targetCamera.outputPhysicalCameraId == mainPhysicalId ||
                targetCamera.physicalCameras.any { it.cameraId == mainPhysicalId }
            val isUltraWidePairLens = targetCamera.cameraId == widePhysicalId ||
                targetCamera.outputPhysicalCameraId == widePhysicalId ||
                targetOpenId == widePhysicalId ||
                targetCamera.physicalCameras.any { it.cameraId == widePhysicalId }

            // Match the two resolved Special Video cameras by ID, not by zoom range:
            // a virtual ISZ/crop lens must never be mistaken for the physical 0.5x stream.
            if (targetCamera.lensFacing != CameraCharacteristics.LENS_FACING_BACK ||
                (!isMainPairLens && !isUltraWidePairLens)
            ) {
                return false
            }

            val primaryReady = cameraDevice != null &&
                captureSession != null &&
                (spatialVideoPrimaryCameraId == null || activeOpenCameraId == spatialVideoPrimaryCameraId)
            val wideDeviceReady = spatialVideoStandbyCameraDevice?.id == widePhysicalId
            val wideSessionReady = spatialVideoStandbySession != null
            val widePreviewReady = spatialVideoStandbyPreviewSurface?.isValid == true
            PLog.i(
                TAG,
                "Special Video lens preview handoff in-place: selected=${targetCamera.cameraId}, " +
                    "mainPair=$isMainPairLens widePair=$isUltraWidePairLens, " +
                    "primaryReady=$primaryReady, wideDeviceReady=$wideDeviceReady, " +
                    "wideSessionReady=$wideSessionReady, widePreviewReady=$widePreviewReady, " +
                    "mode=$spatialVideoStreamingMode"
            )

            // Do not fall back to reopenCamera() while the secondary device/session is warming
            // up. The UI promotes the persistent TextureView immediately; its first frames
            // appear as soon as the paired preview output is configured.
            return true
        }

        val logicalCameraId = currentCamera.logicalCameraId ?: return false
        if (targetCamera.logicalCameraId != logicalCameraId) return false
        if (runCatching { targetCamera.getOpenCameraId() }.getOrNull() != logicalCameraId) return false
        if (activeOpenCameraId != logicalCameraId || cameraDevice == null || captureSession == null) return false
        if (activeOutputPhysicalCameraId != null || !logicalMultiCameraAutoSwitchActive) return false
        return shouldUseLogicalMultiCameraZoom(state, targetCamera)
    }

    /**
     * Rebind software-stabilization calibration to the physical sensor currently selected by
     * the logical HAL. This updates metadata/calibration only; it never changes the camera
     * device or capture session.
     */
    private fun updateLogicalMultiCameraStabilizationCamera(result: TotalCaptureResult) {
        if (!logicalMultiCameraAutoSwitchActive || activeOutputPhysicalCameraId != null) return
        val physicalCameraId = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
            ?: return
        if (physicalCameraId == lastLogicalMultiCameraStabilizationPhysicalId) return

        val physicalCharacteristics = getCameraCharacteristicsOrNull(
            physicalCameraId,
            "logical multi-camera active physical sensor",
        ) ?: return
        val timingCharacteristics = getActiveOpenCameraCharacteristics() ?: return
        val cameraChanged = realtimeStabilizationCoordinator.configureCamera(
            cameraId = physicalCameraId,
            characteristics = physicalCharacteristics,
            timingCharacteristics = timingCharacteristics,
        )
        lastLogicalMultiCameraStabilizationPhysicalId = physicalCameraId
        PLog.i(TAG, "Logical HAL active physical camera changed: $physicalCameraId")
        if (_state.value.videoRecordingState.isRecording) {
            videoRecorder.onCameraSwitched(
                sensorOrientationDegrees = physicalCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
                    ?: cachedSensorOrientation,
                frontFacing = isCurrentCameraFrontFacing(),
                restartStabilization = cameraChanged,
            )
        }
    }

    private fun resolveRequestedPhysicalCameraId(
        state: CameraState = _state.value,
        camera: CameraInfo? = state.getCurrentCameraInfo()
    ): String? {
        return camera
            ?.getBoundPhysicalCameraId(getTargetZoomRatioByMain(state, camera))
    }

    private fun resolveOutputPhysicalCameraId(
        state: CameraState = _state.value,
        camera: CameraInfo? = state.getCurrentCameraInfo()
    ): String? {
        if (state.videoConfig.shouldLockLens(
                captureMode = state.captureMode,
                isRecording = state.videoRecordingState.isRecording
            )
        ) {
            return activeOutputPhysicalCameraId
        }
        if (shouldUseLogicalMultiCameraZoom(state, camera)) return null
        return resolveRequestedPhysicalCameraId(state, camera)
            ?.takeUnless { isPhysicalOutputProfileFailed(it) }
    }

    private fun physicalOutputFailureKey(
        physicalCameraId: String,
        readerFormat: Int = imageReader?.imageFormat ?: NO_IMAGE_READER_FORMAT
    ): PhysicalOutputFailureKey? {
        val openCameraId = activeOpenCameraId.takeIf { it.isNotEmpty() } ?: return null
        return PhysicalOutputFailureKey(
            openCameraId = openCameraId,
            physicalCameraId = physicalCameraId,
            captureMode = _state.value.captureMode,
            readerFormat = readerFormat
        )
    }

    private fun isPhysicalOutputProfileFailed(
        physicalCameraId: String,
        readerFormat: Int = imageReader?.imageFormat ?: NO_IMAGE_READER_FORMAT
    ): Boolean {
        return physicalOutputFailureKey(physicalCameraId, readerFormat)?.let { key ->
            failedPhysicalOutputProfiles.contains(key)
        } ?: false
    }

    private fun rememberPhysicalOutputProfileFailure(
        physicalCameraId: String,
        readerFormat: Int = imageReader?.imageFormat ?: NO_IMAGE_READER_FORMAT,
        reason: String
    ) {
        val key = physicalOutputFailureKey(physicalCameraId, readerFormat) ?: return
        if (failedPhysicalOutputProfiles.add(key)) {
            PLog.w(
                TAG,
                "Physical output profile disabled: open=${key.openCameraId}, " +
                        "physical=${key.physicalCameraId}, mode=${key.captureMode}, " +
                        "format=${imageFormatToString(key.readerFormat)}, reason=$reason"
            )
            updateCurrentOutputSupportAfterPhysicalFailure(key)
        }
    }

    private fun updateCurrentOutputSupportAfterPhysicalFailure(key: PhysicalOutputFailureKey) {
        val selectedPhysicalCameraId = resolveRequestedPhysicalCameraId()
        if (selectedPhysicalCameraId != key.physicalCameraId) return

        when (key.readerFormat) {
            ImageFormat.RAW_SENSOR, ImageFormat.RAW10 -> {
                isRawSupported = getCameraCharacteristicsOrNull(key.physicalCameraId, "RAW output support")
                    ?.let { resolveRawCaptureOutput(it, key.physicalCameraId) } != null
                _state.value = _state.value.copy(isRawSupported = isRawSupported)
            }

            ImageFormat.YCBCR_P010 -> {
                isP010Supported = false
                _state.value = _state.value.copy(isP010Supported = false)
            }
        }
    }

    private fun isOutputFormatAdvertised(
        characteristics: CameraCharacteristics,
        format: Int
    ): Boolean {
        val outputFormats = characteristics
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.outputFormats
            ?: return false
        return outputFormats.contains(format)
    }

    private fun resolveRawCaptureOutput(
        characteristics: CameraCharacteristics,
        physicalCameraId: String?,
    ): CameraUtils.RawCaptureOutput? = CameraUtils.getRawCaptureOutput(characteristics) { format ->
        physicalCameraId == null || !isPhysicalOutputProfileFailed(physicalCameraId, format)
    }

    private fun onSessionConfigured(
        session: CameraCaptureSession,
        openGeneration: Long = cameraOpenGeneration,
        sessionGeneration: Long = previewSessionGeneration,
    ) {
        if (openGeneration != cameraOpenGeneration || sessionGeneration != previewSessionGeneration) {
            PLog.w(TAG, "Ignoring stale configured session")
            safeCloseCaptureSession(session, "stale configured session")
            return
        }
        captureSession = session
        _state.update { it.copy(previewFirstFrameTimestampNs = null) }

        try {
            // 根据测光模式设置默认 AE 区域
            applyMeteringRegions()

            // 开始预览
            // 关键修复: 不再动态添加 surface，因为已经在创建 builder 时添加了
            val deferForIndependentSpecialVideo =
                _state.value.captureMode == CaptureMode.VIDEO &&
                    spatialVideoEnabled &&
                    spatialVideoStreamingMode != SpatialVideoStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL &&
                    _state.value.videoRecordingState.shouldAttachCameraInput() &&
                    spatialVideoRecorder.isRecording()
            previewRequestBuilder?.let { builder ->
                if (deferForIndependentSpecialVideo) {
                    spatialVideoPrimarySessionAwaitingStandby = true
                    PLog.i(TAG, "Special Video main session configured; waiting for the ultra-wide session")
                } else {
                    submitRepeatingPreviewRequest(session, builder)
                }
            }
            if (shouldUseAlgorithmicStabilization()) {
                scheduleEnhancedStabilizationInputWatchdog(sessionGeneration)
            }
            if (
                _state.value.captureMode == CaptureMode.VIDEO &&
                _state.value.videoRecordingState.shouldAttachCameraInput()
            ) {
                if (deferForIndependentSpecialVideo) {
                    startIndependentSpecialVideoInputsWhenReady()
                } else if (
                    videoRecorder.cameraInputSurface != null &&
                    videoRecorder.onCameraInputStarted()
                ) {
                    startVideoRecordingTicker()
                }
            }
            if (_state.value.captureMode == CaptureMode.VIDEO &&
                (dualAspectRecordingActive || (
                    spatialVideoEnabled &&
                        spatialVideoStreamingMode == SpatialVideoStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL
                )) &&
                _state.value.videoRecordingState.shouldAttachCameraInput() &&
                spatialVideoRecorder.cameraInputSurface != null
            ) {
                spatialVideoRecorder.onCameraInputStarted()
            }
            if (
                _state.value.captureMode == CaptureMode.VIDEO &&
                spatialVideoEnabled &&
                spatialVideoVirtualTelephotoEnabled &&
                !deferForIndependentSpecialVideo &&
                _state.value.videoRecordingState.shouldAttachCameraInput() &&
                spatialVideoTelephotoRecorder.cameraInputSurface != null
            ) {
                spatialVideoTelephotoRecorder.onCameraInputStarted()
            }

            _state.value = _state.value.copy(isPreviewActive = true)
            PLog.d(TAG, "Preview started")


        } catch (e: CameraAccessException) {
            PLog.e(TAG, "Failed to start preview")
            handlePreviewSessionFailure("start preview camera access", openGeneration, e)
        } catch (e: IllegalStateException) {
            PLog.e(TAG, "Failed to start preview - illegal state")
            handlePreviewSessionFailure("start preview illegal state", openGeneration, e)
        } catch (e: IllegalArgumentException) {
            PLog.e(TAG, "Failed to start preview - unconfigured surface")
            handlePreviewSessionFailure("start preview unconfigured surface", openGeneration, e)
        }
    }

    private fun imageFormatToString(format: Int): String {
        return when (format) {
            NO_IMAGE_READER_FORMAT -> "NO_IMAGE_READER"
            ImageFormat.RAW_SENSOR -> "RAW_SENSOR"
            ImageFormat.RAW10 -> "RAW10"
            ImageFormat.PRIVATE -> "PRIVATE"
            ImageFormat.YCBCR_P010 -> "YCBCR_P010"
            ImageFormat.YUV_420_888 -> "YUV_420_888"
            ImageFormat.JPEG -> "JPEG"
            else -> format.toString()
        }
    }

    private fun isRawCaptureReader(reader: ImageReader?): Boolean {
        return reader?.imageFormat == ImageFormat.RAW_SENSOR || reader?.imageFormat == ImageFormat.RAW10
    }

    /**
     * RAW is an ImageReader/session-level choice. If the desired state changes after an
     * ImageReader has already been created, changing [CameraState.useRaw] alone leaves the
     * active capture output in the old format.
     *
     * This check also runs from CameraDevice.onOpened so a preference restored while the
     * initial camera open is in flight is applied before the first preview session starts.
     */
    private fun restartCameraForRawCaptureOutputMismatch(reason: String): Boolean {
        val currentState = _state.value
        if (currentState.captureMode != CaptureMode.PHOTO) return false

        val currentReader = imageReader ?: return false
        val expectsRawOutput = requestedRawCaptureEnabled && isRawSupported
        val hasRawOutput = isRawCaptureReader(currentReader)
        if (expectsRawOutput == hasRawOutput) return false

        val surfaceTexture = previewSurfaceTexture ?: return false
        if (cameraDevice == null) {
            PLog.d(
                TAG,
                "RAW capture output update is waiting for camera open: " +
                        "reason=$reason, expectedRaw=$expectsRawOutput, actualRaw=$hasRawOutput"
            )
            return false
        }

        PLog.i(
            TAG,
            "Restarting camera for RAW capture output update: " +
                    "reason=$reason, expectedRaw=$expectsRawOutput, actualRaw=$hasRawOutput"
        )
        openCamera(surfaceTexture)
        return true
    }

// ==================== 统一参数配置 ====================

    private fun resolveFaceDetectMode(availableModes: IntArray, maximumFaceCount: Int): Int {
        if (maximumFaceCount <= 0) {
            return CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF
        }
        return when {
            availableModes.contains(CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL) ->
                CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL

            availableModes.contains(CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE) ->
                CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE

            else -> CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF
        }
    }

    private fun faceDetectModeName(mode: Int): String {
        return when (mode) {
            CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL -> "FULL"
            CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE -> "SIMPLE"
            else -> "OFF"
        }
    }

    /**
     * 将当前状态中的相机参数应用到 CaptureRequest.Builder
     *
     * 统一应用共享参数，并显式处理预览与拍摄阶段的必要差异
     *
     * @param builder 需要配置的 Builder
     * @param isCapture 是否为拍摄请求（预览时某些参数有限制）
     * @param isRawCapture 是否为 RAW 拍摄请求（跳过 RAW 不需要的 ISP 后处理参数）
     * @param useStillFlashAeMode 是否强制进入静态拍摄的闪光 AE 流程；全自动曝光下的强制闪光预览在设备支持
     * ON_ALWAYS_FLASH 时也会使用同一 AE 模式，预闪和主闪由同一闪光 AE 流程控制
     * @param useStillFlashTrigger 是否允许当前请求本身触发 SINGLE 闪光；会话更新和预览请求必须关闭
     * @param currentState 本次请求使用的设置；单帧闪光从预闪到主闪使用快门按下时的快照
     */
    private fun applyBaseCameraSettings(
        builder: CaptureRequest.Builder,
        isCapture: Boolean = false,
        isRawCapture: Boolean = false,
        disableZslForHdrCapture: Boolean = false,
        useStillFlashAeMode: Boolean = isCapture,
        useStillFlashTrigger: Boolean = isCapture,
        currentState: CameraState = _state.value,
    ) {
        // 1. 曝光设置
        applyExposureSettings(
            builder = builder,
            state = currentState,
            isCapture = isCapture,
            useStillFlashAeMode = useStillFlashAeMode
        )

        // 2. 白平衡设置
        applyWhiteBalanceSettings(builder, currentState, isCapture)

        // 3. 闪光灯设置（触发权限与 AE 模式分开控制）
        applyFlashSettings(builder, currentState, isCapture, useStillFlashTrigger)

        // 4. 变焦设置
        applyZoomSettings(builder, currentState, isCapture)

        // 5. 对焦设置
        applyFocusSettings(builder, currentState)

        if (!isRawCapture) {
            // 6. 图像质量设置（锐化、降噪）
            applyImageQualitySettings(builder, currentState)

        }

        // 8. 防抖设置
        applyStabilizationSettings(builder, currentState, isCapture)

        if (!isRawCapture) {
            // 9. 静态拍照后处理质量设置
            applyStillPostProcessingSettings(builder, currentState, isCapture)
        }

        // 10. 统计信息设置
        if (availableLensShadingMapModes.contains(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)) {
            builder.set(
                CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON
            )
        }
        if (faceDetectMode != CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF) {
            builder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, faceDetectMode)
        }

        if (disableZslForHdrCapture) {
            setZslDisabledIfSupported(builder)
        }
        applyVendorCaptureSettings(builder, isCapture)
        applyCustomVendorCaptureRequestKeys(builder, isCapture)
        applyVideoProfileColorProcessing(builder, currentState)
        if (isCapture) {
            PLog.i(
                TAG,
                "RAW_CROP_TRACE stage=CAMERA2_REQUEST isRaw=$isRawCapture " +
                    "selectedCamera=${currentState.currentCameraId} openCamera=${getActiveOpenCameraId()} " +
                    "physicalOutput=$activeOutputPhysicalCameraId stateZoom=${currentState.zoomRatio} " +
                    "requestZoom=${builder.get(CaptureRequest.CONTROL_ZOOM_RATIO)} " +
                    "requestScalerCrop=${builder.get(CaptureRequest.SCALER_CROP_REGION)} " +
                    "requestDistortion=${builder.get(CaptureRequest.DISTORTION_CORRECTION_MODE)}"
            )
        }
    }

    /**
     * Apply the same ISP processing path used by OppoCam's Natural and HLG10 profiles.
     * This request is shared by the preview and encoder surfaces, so the live viewfinder
     * and final recording receive the same camera-side tone and colour transform.
     */
    private fun applyVideoProfileColorProcessing(
        builder: CaptureRequest.Builder,
        state: CameraState
    ) {
        val profile = if (state.captureMode == CaptureMode.VIDEO) {
            state.videoConfig.logProfile
        } else {
            VideoLogProfile.OFF
        }
        val characteristics = getActiveOpenCameraCharacteristics()
        val colorModes = characteristics?.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_MODES)
            ?: intArrayOf()
        val maxCurvePoints = (characteristics?.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 64)
            .coerceIn(2, 64)

        when (profile) {
            VideoLogProfile.NATURAL -> {
                if (availableTonemapModes.contains(CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)) {
                    builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
                    builder.set(
                        CaptureRequest.TONEMAP_CURVE,
                        oppoNaturalVideoEngine.getTonemapCurve(maxCurvePoints)
                    )
                } else if (availableTonemapModes.contains(CaptureRequest.TONEMAP_MODE_GAMMA_VALUE)) {
                    builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_GAMMA_VALUE)
                    builder.set(CaptureRequest.TONEMAP_GAMMA, 2.2f)
                } else if (availableTonemapModes.contains(CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)) {
                    builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)
                }

                // OppoCam Natural: neutral D65 gains and its Rec.709 colour transform.
                if (colorModes.contains(CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)) {
                    builder.set(
                        CaptureRequest.COLOR_CORRECTION_MODE,
                        CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
                    )
                    builder.set(
                        CaptureRequest.COLOR_CORRECTION_TRANSFORM,
                        oppoNaturalVideoEngine.neutralColorTransform()
                    )
                    builder.set(
                        CaptureRequest.COLOR_CORRECTION_GAINS,
                        oppoNaturalVideoEngine.neutralWhiteGains()
                    )
                } else if (colorModes.contains(CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY)) {
                    builder.set(
                        CaptureRequest.COLOR_CORRECTION_MODE,
                        CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY
                    )
                }
            }

            VideoLogProfile.HLG10 -> {
                if (videoCameraHlg10InputActive) {
                    // Camera HAL is already producing the HLG10 source for the encoder.
                    // Apply OppoCam's adaptive ARIB curve in the ISP and do not encode it again in GL.
                    if (availableTonemapModes.contains(CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)) {
                        builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
                        builder.set(
                            CaptureRequest.TONEMAP_CURVE,
                            oppoHlg10AutoExposureEngine.getTonemapCurve(maxCurvePoints)
                        )
                    } else if (availableTonemapModes.contains(CaptureRequest.TONEMAP_MODE_GAMMA_VALUE)) {
                        builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_GAMMA_VALUE)
                        builder.set(CaptureRequest.TONEMAP_GAMMA, 2.2f)
                    }
                } else {
                    // SDR camera input is encoded to HLG by Photon's existing GL renderer.
                    // Keep the camera source display-referred here to prevent a double HLG OETF.
                    val sourceMode = when {
                        availableTonemapModes.contains(CaptureRequest.TONEMAP_MODE_HIGH_QUALITY) ->
                            CaptureRequest.TONEMAP_MODE_HIGH_QUALITY
                        availableTonemapModes.contains(CaptureRequest.TONEMAP_MODE_FAST) ->
                            CaptureRequest.TONEMAP_MODE_FAST
                        else -> null
                    }
                    sourceMode?.let { builder.set(CaptureRequest.TONEMAP_MODE, it) }
                }

                // Match OppoCam's HLG path: preserve factory-calibrated AWB to avoid colour casts.
                val correctionMode = when {
                    colorModes.contains(CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY) ->
                        CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY
                    colorModes.contains(CaptureRequest.COLOR_CORRECTION_MODE_FAST) ->
                        CaptureRequest.COLOR_CORRECTION_MODE_FAST
                    else -> null
                }
                correctionMode?.let { builder.set(CaptureRequest.COLOR_CORRECTION_MODE, it) }
            }

            else -> {
                // Apple Log remains on Photon's existing Log renderer. Also clear a previously
                // installed Oppo ISP curve when the user changes away from Natural / HLG10.
                if (lastAppliedVideoProfile == VideoLogProfile.NATURAL ||
                    lastAppliedVideoProfile == VideoLogProfile.HLG10
                ) {
                    val tonemapMode = when {
                        availableTonemapModes.contains(CaptureRequest.TONEMAP_MODE_HIGH_QUALITY) ->
                            CaptureRequest.TONEMAP_MODE_HIGH_QUALITY
                        availableTonemapModes.contains(CaptureRequest.TONEMAP_MODE_FAST) ->
                            CaptureRequest.TONEMAP_MODE_FAST
                        else -> null
                    }
                    tonemapMode?.let { builder.set(CaptureRequest.TONEMAP_MODE, it) }
                    val correctionMode = when {
                        colorModes.contains(CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY) ->
                            CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY
                        colorModes.contains(CaptureRequest.COLOR_CORRECTION_MODE_FAST) ->
                            CaptureRequest.COLOR_CORRECTION_MODE_FAST
                        else -> null
                    }
                    correctionMode?.let { builder.set(CaptureRequest.COLOR_CORRECTION_MODE, it) }
                }
            }
        }
        lastAppliedVideoProfile = profile
    }

    private fun updateOppoVideoProfileForFrame(result: TotalCaptureResult) {
        val state = _state.value
        if (state.captureMode != CaptureMode.VIDEO) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastOppoVideoProfileIspUpdateMs < 66L) return

        val shouldUpdate = when (state.videoConfig.logProfile) {
            VideoLogProfile.NATURAL -> {
                oppoNaturalVideoEngine.onFrameCaptured(result)
                oppoNaturalVideoEngine.hasSignificantChangeSinceLastIspUpdate()
            }
            VideoLogProfile.HLG10 -> {
                oppoHlg10AutoExposureEngine.onFrameCaptured(result)
                oppoHlg10AutoExposureEngine.hasSignificantChangeSinceLastIspUpdate()
            }
            else -> false
        }
        if (!shouldUpdate) return

        lastOppoVideoProfileIspUpdateMs = now
        when (state.videoConfig.logProfile) {
            VideoLogProfile.NATURAL -> oppoNaturalVideoEngine.markIspUpdated()
            VideoLogProfile.HLG10 -> oppoHlg10AutoExposureEngine.markIspUpdated()
            else -> return
        }

        val builder = previewRequestBuilder ?: return
        applyVideoProfileColorProcessing(builder, state)
        updatePreview()
    }

    private fun forcedVendorSessionParameterValues(state: CameraState): Map<VendorCaptureKey, Int> {
        val lensId = state.currentCameraId
        val settings = state.vendorCaptureSettingsByLens.settingsFor(lensId)
        if (!settings.isEnabled) return emptyMap()

        return settings.values.filterKeys { key ->
            FORCED_VENDOR_SESSION_PARAMETER_KEYS.contains(key)
        }
    }

    private fun customVendorSessionParameterKeys(state: CameraState): List<CustomVendorKey> {
        return state.customVendorKeySettings.keysFor(
            cameraId = state.currentCameraId,
            target = CustomVendorKeyTarget.SESSION_PARAMETER
        )
    }

    private fun applyVendorCaptureSettings(builder: CaptureRequest.Builder, isCapture: Boolean) {
        val state = _state.value
        val lensId = state.currentCameraId
        val settings = state.vendorCaptureSettingsByLens.settingsFor(lensId)
        if (!settings.isEnabled) return

        applyVendorCaptureSettings(
            builder = builder,
            lensId = lensId,
            values = settings.values,
            target = if (isCapture) "capture" else "preview"
        )
    }

    private fun applyVendorCaptureSettings(
        builder: CaptureRequest.Builder,
        lensId: String,
        values: Map<VendorCaptureKey, Int>,
        target: String
    ) {
        values.forEach { (key, value) ->
            val normalizedValue = key.normalizeValue(value)
            try {
                when (key.valueType) {
                    VendorCaptureValueType.INT -> {
                        builder.set(
                            CaptureRequest.Key(key.requestKeyName, Int::class.java),
                            normalizedValue
                        )
                    }

                    VendorCaptureValueType.BYTE -> {
                        builder.set(
                            CaptureRequest.Key(key.requestKeyName, Byte::class.java),
                            normalizedValue.toByte()
                        )
                    }
                }
                PLog.d(TAG, "Applied vendor $target key for lens $lensId: ${key.requestKeyName}=$normalizedValue")
            } catch (e: Exception) {
                PLog.w(TAG, "Failed to apply vendor $target key for lens $lensId: ${key.requestKeyName}", e)
            }
        }
    }

    private fun applyCustomVendorCaptureRequestKeys(
        builder: CaptureRequest.Builder,
        isCapture: Boolean
    ) {
        val state = _state.value
        val lensId = state.currentCameraId
        val keys = state.customVendorKeySettings.keysFor(
            cameraId = lensId,
            target = CustomVendorKeyTarget.CAPTURE_REQUEST
        )
        applyCustomVendorKeys(
            builder = builder,
            lensId = lensId,
            keys = keys,
            target = if (isCapture) "capture" else "preview"
        )
    }

    private fun applyCustomVendorKeys(
        builder: CaptureRequest.Builder,
        lensId: String,
        keys: List<CustomVendorKey>,
        target: String
    ) {
        keys.forEach { key ->
            try {
                when (key.valueType) {
                    CustomVendorKeyValueType.INT32 -> {
                        builder.set(
                            CaptureRequest.Key(key.keyName, Int::class.java),
                            key.normalizedValue
                        )
                    }

                    CustomVendorKeyValueType.U8 -> {
                        // Camera2 exposes byte vendor tags as Java Byte. Values 128..255
                        // keep their unsigned bit pattern when converted to Byte.
                        builder.set(
                            CaptureRequest.Key(key.keyName, Byte::class.java),
                            key.normalizedValue.toByte()
                        )
                    }
                }
                PLog.d(
                    TAG,
                    "Applied custom vendor $target key for lens $lensId: " +
                        "${key.keyName}=${key.normalizedValue} (${key.valueType})"
                )
            } catch (e: Exception) {
                PLog.w(
                    TAG,
                    "Failed to apply custom vendor $target key for lens $lensId: ${key.keyName}",
                    e
                )
            }
        }
    }

    private fun isCaptureRequestKeyAvailable(requestKeyName: String): Boolean {
        val keyNames = availableCaptureRequestKeyNames
            ?: loadAvailableCaptureRequestKeyNames(getActiveOpenCameraCharacteristics()).also {
                availableCaptureRequestKeyNames = it
            }
        return keyNames.contains(requestKeyName)
    }

    private fun loadAvailableCaptureRequestKeyNames(characteristics: CameraCharacteristics?): Set<String> {
        if (characteristics == null) return emptySet()
        return try {
            characteristics.availableCaptureRequestKeys
                .map { it.name }
                .toSet()
        } catch (e: Exception) {
            PLog.w(TAG, "Failed to query available capture request keys", e)
            emptySet()
        }
    }

    private fun loadAvailableColorCorrectionModes(characteristics: CameraCharacteristics): IntArray {
        return if (Build.VERSION.SDK_INT >= 36) {
            characteristics.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_MODES)
                ?: intArrayOf(
                    CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX,
                    CameraMetadata.COLOR_CORRECTION_MODE_FAST,
                    CameraMetadata.COLOR_CORRECTION_MODE_HIGH_QUALITY
                )
        } else {
            intArrayOf(
                CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX,
                CameraMetadata.COLOR_CORRECTION_MODE_FAST,
                CameraMetadata.COLOR_CORRECTION_MODE_HIGH_QUALITY
            )
        }
    }

    private fun loadAwbColorTemperatureRange(characteristics: CameraCharacteristics): Range<Int>? {
        return if (Build.VERSION.SDK_INT >= 36) {
            characteristics.readMetadataOrNull(CameraCharacteristics.COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE)
        } else {
            null
        }
    }

    private fun resolveAwbTemperatureRange(): Range<Int> {
        return Range(AWB_TEMPERATURE_MIN, AWB_TEMPERATURE_MAX)
    }

    private fun coerceCctAwbTemperature(kelvin: Int): Int {
        val advertisedRange = awbColorTemperatureRange ?: return kelvin
        return kelvin.coerceIn(advertisedRange.lower, advertisedRange.upper)
    }

    private fun supportsCctWhiteBalance(): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        if (!availableColorCorrectionModes.contains(CameraMetadata.COLOR_CORRECTION_MODE_CCT)) return false
        if (!availableAwbModes.contains(CameraMetadata.CONTROL_AWB_MODE_OFF)) return false
        if (awbColorTemperatureRange == null) return false
        return isCaptureRequestKeyAvailable(CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE.name) &&
                isCaptureRequestKeyAvailable(CaptureRequest.COLOR_CORRECTION_COLOR_TINT.name)
    }

    private fun supportsManualMatrixWhiteBalance(): Boolean {
        return isManualPostProcessingSupported &&
                availableAwbModes.contains(CameraMetadata.CONTROL_AWB_MODE_OFF) &&
                isCaptureRequestKeyAvailable(CaptureRequest.COLOR_CORRECTION_MODE.name) &&
                isCaptureRequestKeyAvailable(CaptureRequest.COLOR_CORRECTION_GAINS.name) &&
                isCaptureRequestKeyAvailable(CaptureRequest.COLOR_CORRECTION_TRANSFORM.name)
    }

    private fun canUseManualMatrixWhiteBalance(snapshot: WhiteBalanceResultSnapshot?): Boolean {
        return supportsManualMatrixWhiteBalance() &&
                snapshot?.gains != null &&
                (hasColorMatrixWhiteBalanceTransformSupport() || snapshot.transform != null)
    }

    private fun hasColorMatrixWhiteBalanceTransformSupport(): Boolean {
        val characteristics = resolveActiveWhiteBalanceCharacteristics() ?: return false
        return characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1) != null ||
                characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2) != null
    }

    private fun readWhiteBalanceResult(result: CaptureResult, awbMode: Int): WhiteBalanceResultSnapshot {
        val cctTemperature = if (Build.VERSION.SDK_INT >= 36) {
            result.get(CaptureResult.COLOR_CORRECTION_COLOR_TEMPERATURE)
        } else {
            null
        }
        val cctTint = if (Build.VERSION.SDK_INT >= 36) {
            result.get(CaptureResult.COLOR_CORRECTION_COLOR_TINT)
        } else {
            null
        }
        return WhiteBalanceResultSnapshot(
            awbMode = awbMode,
            colorTemperature = cctTemperature,
            colorTint = cctTint,
            gains = readColorCorrectionGains(result),
            transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        )
    }

    private fun readColorCorrectionGains(result: CaptureResult): RggbChannelVector? {
        return result.readMetadataOrNull(CaptureResult.COLOR_CORRECTION_GAINS, getActiveOpenCameraId())
    }

    private fun resolveWhiteBalanceControlPath(
        snapshot: WhiteBalanceResultSnapshot? = lastWhiteBalanceResult
    ): WhiteBalanceControlPath {
        if (snapshot == null) return WhiteBalanceControlPath.UNAVAILABLE
        if (canUseManualMatrixWhiteBalance(snapshot)) {
            return WhiteBalanceControlPath.MATRIX
        }
        if (supportsCctWhiteBalance() &&
            snapshot.colorTemperature != null &&
            snapshot.colorTint != null
        ) {
            return WhiteBalanceControlPath.CCT
        }
        return WhiteBalanceControlPath.UNAVAILABLE
    }

    private fun canAdjustManualWhiteBalance(snapshot: WhiteBalanceResultSnapshot? = lastWhiteBalanceResult): Boolean {
        return resolveWhiteBalanceControlPath(snapshot) != WhiteBalanceControlPath.UNAVAILABLE
    }

    private fun createManualWhiteBalanceAnchor(
        snapshot: WhiteBalanceResultSnapshot?,
        baseTemperature: Int
    ): ManualWhiteBalanceAnchor? {
        return when (resolveWhiteBalanceControlPath(snapshot)) {
            WhiteBalanceControlPath.CCT -> {
                val tint = snapshot?.colorTint ?: return null
                ManualWhiteBalanceAnchor(
                    controlPath = WhiteBalanceControlPath.CCT,
                    baseTemperature = baseTemperature,
                    colorTint = tint,
                    gains = null,
                    transform = null
                )
            }

            WhiteBalanceControlPath.MATRIX -> {
                val gains = snapshot?.gains ?: return null
                val transform = buildColorMatrixWhiteBalanceTransform(gains)
                    ?: snapshot.transform
                    ?: return null
                ManualWhiteBalanceAnchor(
                    controlPath = WhiteBalanceControlPath.MATRIX,
                    baseTemperature = baseTemperature,
                    colorTint = null,
                    gains = gains,
                    transform = transform
                )
            }

            WhiteBalanceControlPath.UNAVAILABLE -> null
        }
    }

    private fun RggbChannelVector.toStateGains(): WhiteBalanceGains {
        return WhiteBalanceGains(
            red = red,
            greenEven = greenEven,
            greenOdd = greenOdd,
            blue = blue
        )
    }

    private fun setZslDisabledIfSupported(builder: CaptureRequest.Builder) {
        if (!isZslControlAvailable()) return
        builder.set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
    }

    private fun isZslControlAvailable(): Boolean {
        isZslControlSupported?.let { return it }
        val supported = try {
            getActiveOpenCameraCharacteristics()
                ?.availableCaptureRequestKeys
                ?.contains(CaptureRequest.CONTROL_ENABLE_ZSL)
                ?: true
        } catch (e: Exception) {
            PLog.w(TAG, "Failed to query available capture request keys for ZSL control", e)
            true
        }
        isZslControlSupported = supported
        if (!supported) {
            PLog.i(TAG, "CONTROL_ENABLE_ZSL is not listed in available capture request keys")
        }
        return supported
    }

    /**
     * 应用连续自动对焦设置。
     *
     * 一些设备不会很好地处理未声明支持的 AF 模式，或者在单次 AF 触发后保持旧触发状态。
     * 这里统一按能力选择默认 AF 模式，并显式复位触发器，避免预览请求停在不稳定状态。
     */
    private fun applyAutoFocusSettings(builder: CaptureRequest.Builder, state: CameraState) {
        val afMode = resolveAutoFocusMode(state.captureMode)

        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        builder.set(CaptureRequest.CONTROL_AF_MODE, afMode)

        if (_state.value.currentAfMode != afMode) {
            _state.value = _state.value.copy(currentAfMode = afMode)
        }
    }

    private fun applyFocusSettings(builder: CaptureRequest.Builder, state: CameraState) {
        if (applyFrozenCaptureFocus(builder)) return
        if (state.isAutoFocus) {
            applyAutoFocusSettings(builder, state)
            return
        }

        val clampedDistance = if (state.minimumFocusDistance > 0f) {
            state.focusDistance.coerceIn(0f, state.minimumFocusDistance)
        } else {
            0f
        }

        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, clampedDistance)

        if (_state.value.currentAfMode != CaptureRequest.CONTROL_AF_MODE_OFF) {
            _state.value = _state.value.copy(currentAfMode = CaptureRequest.CONTROL_AF_MODE_OFF)
        }
    }

    /** Keep preview updates from resetting the single-flash AF sweep/lock. */
    private fun applyFrozenCaptureFocus(builder: CaptureRequest.Builder): Boolean {
        if (!isCaptureFocusFrozen) return false
        val afMode = stillFlashAfMode ?: return false
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        builder.set(CaptureRequest.CONTROL_AF_MODE, afMode)
        if (afMode == CaptureRequest.CONTROL_AF_MODE_OFF) {
            stillFlashFocusDistance?.let { builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, it) }
        }
        return true
    }

    private fun resolveAutoFocusMode(captureMode: CaptureMode): Int {
        if (availableAfModes.isEmpty()) {
            return CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        }

        val preferredModes = if (captureMode == CaptureMode.VIDEO) {
            intArrayOf(
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                CaptureRequest.CONTROL_AF_MODE_AUTO,
                CaptureRequest.CONTROL_AF_MODE_OFF
            )
        } else {
            intArrayOf(
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                CaptureRequest.CONTROL_AF_MODE_AUTO,
                CaptureRequest.CONTROL_AF_MODE_OFF
            )
        }

        return preferredModes.firstOrNull { availableAfModes.contains(it) }
            ?: availableAfModes.first()
    }

    private fun getMaxPreviewExposureTime(state: CameraState): Long {
        return state.getPreviewExposureTimeLimitNs()
    }

    private fun coerceManualShutterSpeed(state: CameraState, value: Long): Long {
        val range = state.getManualShutterSpeedRange()
        return value.coerceIn(range.lower, range.upper)
    }

    private fun coercePreviewExposureTime(state: CameraState): Long {
        return state.shutterSpeed.coerceAtMost(getMaxPreviewExposureTime(state))
    }

    /**
     * 应用曝光设置
     *
     * 统一管理 CONTROL_AE_MODE，确保与闪光灯模式正确配合
     */
    private fun resolveSupportedAeMode(preferredMode: Int): Int {
        if (availableAeModes.contains(preferredMode)) return preferredMode
        if (availableAeModes.contains(CaptureRequest.CONTROL_AE_MODE_ON)) {
            return CaptureRequest.CONTROL_AE_MODE_ON
        }
        if (availableAeModes.contains(CaptureRequest.CONTROL_AE_MODE_OFF)) {
            return CaptureRequest.CONTROL_AE_MODE_OFF
        }
        // CameraX deliberately falls back to OFF instead of selecting an arbitrary AE mode.
        // This is also required for FLASH_MODE_TORCH, whose AE mode may only be ON or OFF.
        return CaptureRequest.CONTROL_AE_MODE_OFF
    }

    private fun isAePrecaptureSupported(): Boolean {
        return cachedHardwareLevel != CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY &&
            isCaptureRequestKeyAvailable(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER.name)
    }

    private fun setAePrecaptureTriggerIfSupported(
        builder: CaptureRequest.Builder,
        trigger: Int,
    ) {
        if (isAePrecaptureSupported()) {
            builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, trigger)
        }
    }

    private fun resolveStillFlashAeMode(): Int {
        return if (availableAeModes.contains(CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)) {
            resolveSupportedAeMode(CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
        } else {
            resolveSupportedAeMode(CaptureRequest.CONTROL_AE_MODE_ON)
        }
    }

    private fun shouldUsePreviewStillFlashAeMode(state: CameraState): Boolean {
        return isFlashSupported &&
            state.captureMode == CaptureMode.PHOTO &&
            state.flashMode == CameraMetadata.FLASH_MODE_SINGLE &&
            state.isIsoAuto &&
            state.isShutterSpeedAuto &&
            availableAeModes.contains(CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
    }

    private fun applyExposureSettings(
        builder: CaptureRequest.Builder,
        state: CameraState,
        isCapture: Boolean,
        useStillFlashAeMode: Boolean = isCapture
    ) {
        if (state.captureMode == CaptureMode.VIDEO) {
            applyVideoFpsRange(builder, state.videoConfig.fps.fps)
        }

        val effectiveUseStillFlashAeMode = useStillFlashAeMode ||
            (!isCapture && shouldUsePreviewStillFlashAeMode(state))

        // 根据曝光模式和闪光灯模式联合决定 AE_MODE
        val aeMode = when {
            // 1. 全自动曝光：静态闪光拍摄及其预览会话使用闪光 AE 模式；真正的预闪仍由
            // CONTROL_AE_PRECAPTURE_TRIGGER 或静态拍摄请求触发
            state.isIsoAuto && state.isShutterSpeedAuto -> {
                if (state.captureMode == CaptureMode.VIDEO) {
                    resolveSupportedAeMode(CaptureRequest.CONTROL_AE_MODE_ON)
                } else {
                    when {
                        state.flashMode == CameraMetadata.FLASH_MODE_SINGLE &&
                                effectiveUseStillFlashAeMode ->
                            resolveStillFlashAeMode()

                        else -> resolveSupportedAeMode(CaptureRequest.CONTROL_AE_MODE_ON)
                    }
                }
            }
            // 2. 手动曝光或半自动曝光：尝试使用 OFF 模式，如果设备不支持则退而求其次使用 ON
            else -> {
                if (isManualSensorSupported && availableAeModes.contains(CaptureRequest.CONTROL_AE_MODE_OFF)) {
                    CaptureRequest.CONTROL_AE_MODE_OFF
                } else {
                    resolveSupportedAeMode(CaptureRequest.CONTROL_AE_MODE_ON)
                }
            }
        }

        builder.set(CaptureRequest.CONTROL_AE_MODE, aeMode)
        applyVideoExposureTimePrioritySettings(builder, state, aeMode)

        // 如果是全自动曝光，设置曝光补偿
        if (state.isIsoAuto && state.isShutterSpeedAuto) {
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, state.exposureCompensation)
        } else {
            // 手动曝光 / 半自动曝光：手动设置 ISO 和快门
            // 只有在支持 MANUAL_SENSOR 的设备上才设置，否则保持自动
            if (isManualSensorSupported) {
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, state.iso)

                // 预览时限制曝光时间，防止画面卡死；拍摄时使用完整的用户设置
                val exposureTime = if (isCapture) {
                    state.shutterSpeed
                } else {
                    coercePreviewExposureTime(state)
                }
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTime)
            }

            // 拍摄时设置低帧率范围以支持长曝光
            if (isCapture && state.captureMode == CaptureMode.PHOTO) {
                try {
                    val characteristics = getActiveOpenCameraCharacteristics() ?: return
                    val availableFpsRanges =
                        characteristics.readMetadataOrNull(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    val lowestFpsRange = availableFpsRanges?.minByOrNull { it.upper }
                    lowestFpsRange?.let {
                        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                    }
                } catch (e: Exception) {
                    PLog.e(TAG, "Failed to set FPS range", e)
                }
            }
        }
    }

    private fun calculateRawMinShutterAdjustedExposure(
        state: CameraState,
        baseIso: Int,
        baseShutter: Long
    ): Pair<Int, Long> {
        if (!shouldApplyRawMinShutterLimit(state)) {
            return Pair(baseIso, baseShutter)
        }
        if (baseIso <= 0 || baseShutter <= 0L) return Pair(baseIso, baseShutter)

        val isoRange = state.getIsoRange()
        val shutterRange = state.getShutterSpeedRange()
        val targetShutterLimit = state.rawMinShutterSpeedNs.coerceIn(shutterRange.lower, shutterRange.upper)
        if (baseShutter == targetShutterLimit) return Pair(baseIso, baseShutter)

        val exposureProduct = baseIso.toDouble() * baseShutter.toDouble()
        val adjusted = if (baseShutter < targetShutterLimit) {
            if (baseIso <= isoRange.lower) {
                Pair(baseIso, baseShutter)
            } else {
                val isoAtLimit = (exposureProduct / targetShutterLimit).roundToInt()
                if (isoAtLimit >= isoRange.lower) {
                    Pair(isoAtLimit.coerceIn(isoRange.lower, isoRange.upper), targetShutterLimit)
                } else {
                    val shutterAtMinIso = (exposureProduct / isoRange.lower).roundToLong()
                        .coerceIn(baseShutter, targetShutterLimit)
                        .coerceIn(shutterRange.lower, shutterRange.upper)
                    Pair(isoRange.lower, shutterAtMinIso)
                }
            }
        } else {
            val isoAtLimit = (exposureProduct / targetShutterLimit).roundToInt()
            if (isoAtLimit <= isoRange.upper) {
                Pair(isoAtLimit.coerceIn(isoRange.lower, isoRange.upper), targetShutterLimit)
            } else {
                val shutterAtMaxIso = (exposureProduct / isoRange.upper).roundToLong()
                    .coerceIn(targetShutterLimit, baseShutter)
                    .coerceIn(shutterRange.lower, shutterRange.upper)
                Pair(isoRange.upper, shutterAtMaxIso)
            }
        }

        if (adjusted.first != baseIso || adjusted.second != baseShutter) {
            PLog.d(
                TAG,
                "RAW min shutter override: ISO=$baseIso->${adjusted.first}, shutter=$baseShutter->${adjusted.second}, min=$targetShutterLimit"
            )
        }
        return adjusted
    }

    private fun shouldApplyRawMinShutterLimit(state: CameraState): Boolean {
        return state.captureMode == CaptureMode.PHOTO &&
                state.rawMinShutterSpeedNs > 0L &&
                state.isIsoAuto &&
                state.isShutterSpeedAuto &&
                state.flashMode != CameraMetadata.FLASH_MODE_SINGLE
    }

    private fun applyVideoFpsRange(builder: CaptureRequest.Builder, targetFps: Int) {
        val characteristics = getActiveOpenCameraCharacteristics() ?: return
        val availableRanges = characteristics
            .readMetadataOrNull(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.filter { it.lower > 0 && it.upper >= it.lower }
            .orEmpty()
        if (availableRanges.isEmpty()) {
            PLog.w(TAG, "Camera advertises no usable AE FPS ranges; keeping the HAL default")
            return
        }

        // Camera2 only accepts FPS ranges advertised by this camera. A fabricated
        // [targetFps, targetFps] range can make the repeating request fail entirely.
        // Prefer an exact fixed range. If unavailable, prefer a containing range
        // whose upper bound is the selected FPS, then the highest lower bound. This
        // avoids permitting a slower cadence or a higher cadence when a tighter
        // advertised range exists for this sensor.
        val exactRange = availableRanges.firstOrNull {
            it.lower == targetFps && it.upper == targetFps
        }
        val containingRange = availableRanges
            .filter { it.lower <= targetFps && it.upper >= targetFps }
            .sortedWith(
                compareBy<Range<Int>> { if (it.upper == targetFps) 0 else 1 }
                    .thenBy { it.upper }
                    .thenByDescending { it.lower }
            )
            .firstOrNull()
        val resolvedRange = exactRange ?: containingRange ?: availableRanges.minByOrNull { range ->
            when {
                targetFps < range.lower -> range.lower - targetFps
                targetFps > range.upper -> targetFps - range.upper
                else -> 0
            }
        } ?: return

        if (resolvedRange.lower != targetFps || resolvedRange.upper != targetFps) {
            PLog.i(
                TAG,
                "Using advertised AE FPS range $resolvedRange for target $targetFps; " +
                    "available=${availableRanges.joinToString()}"
            )
        }
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, resolvedRange)
    }

    /**
     * Apply an AE shutter-time limit only on Android 16+ cameras that advertise
     * CONTROL_AE_PRIORITY_MODE. Exposure time is held to one frame interval while
     * AE remains enabled and can adjust sensitivity/frame duration as needed.
     */
    private fun applyVideoExposureTimePrioritySettings(
        builder: CaptureRequest.Builder,
        state: CameraState,
        aeMode: Int,
    ) {
        if (Build.VERSION.SDK_INT < 36 ||
            !isCaptureRequestKeyAvailable(CaptureRequest.CONTROL_AE_PRIORITY_MODE.name) ||
            // Constrained-high-speed sessions have a restricted request-key set.
            state.videoConfig.fps == VideoFpsPreset.FPS_120
        ) {
            videoExposureTimePriorityActive = false
            videoExposureTimePriorityFps = 0
            return
        }

        val canPrioritizeVideoShutter = state.captureMode == CaptureMode.VIDEO &&
            state.isIsoAuto &&
            state.isShutterSpeedAuto &&
            aeMode == CaptureRequest.CONTROL_AE_MODE_ON
        if (!canPrioritizeVideoShutter) {
            builder.set(
                CaptureRequest.CONTROL_AE_PRIORITY_MODE,
                CameraMetadata.CONTROL_AE_PRIORITY_MODE_OFF,
            )
            videoExposureTimePriorityActive = false
            videoExposureTimePriorityFps = 0
            return
        }

        if (videoExposureTimePriorityActive) {
            val targetFps = state.videoConfig.fps.fps.coerceAtLeast(1)
            val maximumExposureTimeNs = 1_000_000_000L / targetFps
            builder.set(
                CaptureRequest.CONTROL_AE_PRIORITY_MODE,
                CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY,
            )
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, maximumExposureTimeNs)
            videoExposureTimePriorityFps = targetFps
        } else {
            builder.set(
                CaptureRequest.CONTROL_AE_PRIORITY_MODE,
                CameraMetadata.CONTROL_AE_PRIORITY_MODE_OFF,
            )
            videoExposureTimePriorityFps = 0
        }
    }

    /**
     * When automatic video AE asks for an exposure longer than one selected frame,
     * temporarily prioritize exposure time. This prevents low-light AE from slowing
     * the sensor; the camera keeps AE enabled and can raise ISO to retain brightness.
     * Release the cap once AE is converged near minimum ISO, avoiding a permanent
     * 1/FPS shutter in bright scenes.
     */
    private fun updateVideoExposureTimePriorityFromResult(result: TotalCaptureResult) {
        if (Build.VERSION.SDK_INT < 36 ||
            !isCaptureRequestKeyAvailable(CaptureRequest.CONTROL_AE_PRIORITY_MODE.name)
        ) {
            return
        }

        val state = _state.value
        val builder = previewRequestBuilder ?: return
        val aeMode = result.get(CaptureResult.CONTROL_AE_MODE)
        val isAutomaticVideoExposure = state.captureMode == CaptureMode.VIDEO &&
            state.videoConfig.fps != VideoFpsPreset.FPS_120 &&
            state.isIsoAuto &&
            state.isShutterSpeedAuto &&
            aeMode == CaptureResult.CONTROL_AE_MODE_ON &&
            result.get(CaptureResult.CONTROL_AE_LOCK) != true
        if (!isAutomaticVideoExposure) {
            if (videoExposureTimePriorityActive) {
                videoExposureTimePriorityActive = false
                videoExposureTimePriorityFps = 0
                applyVideoExposureTimePrioritySettings(
                    builder,
                    state,
                    aeMode ?: CaptureRequest.CONTROL_AE_MODE_OFF,
                )
                updatePreview()
            }
            return
        }

        val targetFps = state.videoConfig.fps.fps.coerceAtLeast(1)
        val maximumExposureTimeNs = 1_000_000_000L / targetFps
        val actualExposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
        if (!videoExposureTimePriorityActive) {
            if (actualExposureTimeNs > maximumExposureTimeNs) {
                videoExposureTimePriorityActive = true
                videoExposureTimePriorityFps = targetFps
                applyVideoExposureTimePrioritySettings(
                    builder,
                    state,
                    CaptureRequest.CONTROL_AE_MODE_ON,
                )
                PLog.i(
                    TAG,
                    "Video low-light shutter capped: requested=" +
                        "${targetFps}fps, AE exposure=${actualExposureTimeNs}ns, " +
                        "cap=${maximumExposureTimeNs}ns; AE remains enabled",
                )
                updatePreview()
            }
            return
        }

        if (videoExposureTimePriorityFps != targetFps) {
            applyVideoExposureTimePrioritySettings(
                builder,
                state,
                CaptureRequest.CONTROL_AE_MODE_ON,
            )
            updatePreview()
            return
        }

        val actualIso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
        val minimumIso = state.getIsoRange().lower
        val releaseIsoThreshold = minimumIso + (minimumIso / 10).coerceAtLeast(1)
        val aeConverged = result.get(CaptureResult.CONTROL_AE_STATE) ==
            CaptureResult.CONTROL_AE_STATE_CONVERGED
        if (aeConverged && actualIso <= releaseIsoThreshold) {
            videoExposureTimePriorityActive = false
            videoExposureTimePriorityFps = 0
            applyVideoExposureTimePrioritySettings(
                builder,
                state,
                CaptureRequest.CONTROL_AE_MODE_ON,
            )
            PLog.i(
                TAG,
                "Video low-light shutter cap released: AE converged at ISO=${actualIso}",
            )
            updatePreview()
        }
    }

    /**
     * 应用白平衡设置
     */
    private fun applyWhiteBalanceSettings(
        builder: CaptureRequest.Builder,
        state: CameraState,
        isCapture: Boolean
    ) {
        if (state.awbMode != CameraMetadata.CONTROL_AWB_MODE_OFF) {
            applyAutoWhiteBalanceSettings(builder, state, isCapture)
            return
        }

        val anchor = manualWhiteBalanceAnchor
        when (anchor?.controlPath ?: WhiteBalanceControlPath.UNAVAILABLE) {
            WhiteBalanceControlPath.CCT -> applyCctWhiteBalanceSettings(builder, state, isCapture, anchor)
            WhiteBalanceControlPath.MATRIX -> applyMatrixWhiteBalanceSettings(builder, state, isCapture, anchor)
            WhiteBalanceControlPath.UNAVAILABLE -> applyAutoWhiteBalanceSettings(
                builder = builder,
                state = state.copy(awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO),
                isCapture = isCapture
            )
        }
    }

    private fun applyAutoWhiteBalanceSettings(
        builder: CaptureRequest.Builder,
        state: CameraState,
        isCapture: Boolean
    ) {
        val requestedMode = state.awbMode
        val awbMode = if (availableAwbModes.isEmpty() || requestedMode in availableAwbModes) {
            requestedMode
        } else {
            CameraMetadata.CONTROL_AWB_MODE_AUTO
        }
        builder.set(CaptureRequest.CONTROL_AWB_MODE, awbMode)
        val shouldLockAwb = state.videoConfig.shouldLockWhiteBalance(
            captureMode = state.captureMode,
            isRecording = state.videoRecordingState.isRecording
        ) &&
            getActiveOpenCameraCharacteristics()
                ?.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true
        builder.set(CaptureRequest.CONTROL_AWB_LOCK, shouldLockAwb)

        // 自动白平衡：拍照优先高质量色彩校正，预览维持快速路径
        if (isManualPostProcessingSupported) {
            val colorCorrectionMode = if (isCapture && state.captureMode == CaptureMode.PHOTO) {
                CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY
            } else {
                CaptureRequest.COLOR_CORRECTION_MODE_FAST
            }
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, colorCorrectionMode)
        }
    }

    private fun applyCctWhiteBalanceSettings(
        builder: CaptureRequest.Builder,
        state: CameraState,
        isCapture: Boolean,
        anchor: ManualWhiteBalanceAnchor?
    ) {
        if (Build.VERSION.SDK_INT < 36 || anchor?.colorTint == null) {
            applyAutoWhiteBalanceSettings(
                builder = builder,
                state = state.copy(awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO),
                isCapture = isCapture
            )
            return
        }
        val range = resolveAwbTemperatureRange()
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
        builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_CCT)
        builder.set(
            CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE,
            coerceCctAwbTemperature(state.awbTemperature.coerceIn(range.lower, range.upper))
        )
        builder.set(CaptureRequest.COLOR_CORRECTION_COLOR_TINT, anchor.colorTint)
    }

    private fun applyMatrixWhiteBalanceSettings(
        builder: CaptureRequest.Builder,
        state: CameraState,
        isCapture: Boolean,
        anchor: ManualWhiteBalanceAnchor?
    ) {
        val resolvedAnchor = anchor ?: return applyAutoWhiteBalanceSettings(
            builder = builder,
            state = state.copy(awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO),
            isCapture = isCapture
        )
        val gains = resolvedAnchor.gains ?: return applyAutoWhiteBalanceSettings(
            builder = builder,
            state = state.copy(awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO),
            isCapture = isCapture
        )
        val resolvedGains = resolveManualMatrixGains(state.awbTemperature, resolvedAnchor, gains)
        val transform = buildColorMatrixWhiteBalanceTransform(resolvedGains)
            ?: resolvedAnchor.transform
            ?: return applyAutoWhiteBalanceSettings(
                builder = builder,
                state = state.copy(awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO),
                isCapture = isCapture
            )
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
        builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
        builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, resolvedGains)
        builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, transform)
    }

    private fun buildColorMatrixWhiteBalanceTransform(gains: RggbChannelVector): ColorSpaceTransform? {
        val characteristics = resolveActiveWhiteBalanceCharacteristics() ?: return null
        val calibration = RawColorCalibrationPolicy.fromCharacteristics(characteristics)
        val colorMatrix1 = calibration.colorMatrix1?.let(::extractMatrix3x3)
        val colorMatrix2 = calibration.colorMatrix2?.let(::extractMatrix3x3)
        if (colorMatrix1 == null && colorMatrix2 == null) return null

        val matrix = DngSdkColorSpec.computeCameraToWorkingMatrix(
            colorMatrix1 = colorMatrix1,
            colorMatrix2 = colorMatrix2,
            forwardMatrix1 = calibration.forwardMatrix1?.let(::extractMatrix3x3),
            forwardMatrix2 = calibration.forwardMatrix2?.let(::extractMatrix3x3),
            calibrationIlluminant1 = calibration.illuminant1,
            calibrationIlluminant2 = calibration.illuminant2,
            cameraCalibration1 = calibration.calibration1?.let(::extractMatrix3x3),
            cameraCalibration2 = calibration.calibration2?.let(::extractMatrix3x3),
            whiteBalanceGains = floatArrayOf(gains.red, gains.greenEven, gains.greenOdd, gains.blue),
            workingColorSpace = RawColorSpace.SRGB
        ) ?: return null

        return removeWhiteBalanceFromColorTransform(matrix, gains).toColorSpaceTransform()
    }

    private fun removeWhiteBalanceFromColorTransform(
        rawCameraToSrgb: FloatArray,
        gains: RggbChannelVector
    ): FloatArray {
        val redGain = gains.red.coerceAtLeast(1e-3f)
        val greenGain = ((gains.greenEven + gains.greenOdd) * 0.5f).coerceAtLeast(1e-3f)
        val blueGain = gains.blue.coerceAtLeast(1e-3f)
        val result = rawCameraToSrgb.copyOf()

        // Camera2 applies COLOR_CORRECTION_GAINS before COLOR_CORRECTION_TRANSFORM.
        // DNG-style ColorMatrix math bakes CameraNeutral/WB into the matrix, so divide
        // matrix columns by the gains to avoid applying white balance twice.
        for (row in 0 until 3) {
            result[row * 3] /= redGain
            result[row * 3 + 1] /= greenGain
            result[row * 3 + 2] /= blueGain
        }
        return result
    }

    private fun resolveActiveWhiteBalanceCharacteristics(): CameraCharacteristics? {
        val candidateIds = buildList {
            activeOutputPhysicalCameraId?.let(::add)
            getCurrentOpenCameraId().takeIf { it.isNotEmpty() }?.let(::add)
            _state.value.currentCameraId.takeIf { it.isNotEmpty() }?.let(::add)
        }.distinct()

        for (cameraId in candidateIds) {
            getCameraCharacteristicsOrNull(cameraId, "white balance color matrix")?.let { return it }
        }
        return getActiveOpenCameraCharacteristics()
    }

    private fun extractMatrix3x3(transform: ColorSpaceTransform): FloatArray {
        return FloatArray(9) { index ->
            val row = index / 3
            val col = index % 3
            transform.getElement(col, row).toFloat()
        }
    }

    private fun FloatArray.toColorSpaceTransform(): ColorSpaceTransform? {
        if (size != 9 || any { !it.isFinite() }) return null
        val rationals = Array(9) { index ->
            val value = this[index].coerceIn(-1.5f, 3f)
            Rational((value * 1_000_000f).roundToInt(), 1_000_000)
        }
        return ColorSpaceTransform(rationals)
    }

    /**
     * 应用闪光灯设置
     *
     * 注意：只设置 FLASH_MODE，AE_MODE 由 applyExposureSettings 统一管理
     *
     * @param isCapture 是否为拍摄请求（预览时某些闪光模式需要特殊处理）
     */
    private fun applyFlashSettings(
        builder: CaptureRequest.Builder,
        state: CameraState,
        isCapture: Boolean,
        useStillFlashTrigger: Boolean = isCapture,
    ) {
        if (!isFlashSupported) {
            builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
            return
        }

        if (state.captureMode == CaptureMode.VIDEO) {
            builder.set(
                CaptureRequest.FLASH_MODE,
                if (state.videoConfig.torchEnabled) {
                    CameraMetadata.FLASH_MODE_TORCH
                } else {
                    CameraMetadata.FLASH_MODE_OFF
                }
            )
            return
        }

        when (state.flashMode) {
            CameraMetadata.FLASH_MODE_SINGLE -> {
                if (!state.isIsoAuto || !state.isShutterSpeedAuto) {
                    // Manual/semi-manual exposure has no AE precapture convergence.
                    // Fire a single flash only for the still request.
                    builder.set(
                        CaptureRequest.FLASH_MODE,
                        if (isCapture) {
                            CameraMetadata.FLASH_MODE_SINGLE
                        } else {
                            CameraMetadata.FLASH_MODE_OFF
                        }
                    )
                } else {
                    val alwaysFlashAeSupported = availableAeModes.contains(
                        CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                    )
                    if (alwaysFlashAeSupported) {
                        // AE_MODE_ON_ALWAYS_FLASH owns precapture and main-flash control.
                        builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                    } else {
                        // Some devices advertise AE_MODE_ON but not ON_ALWAYS_FLASH. Keep the
                        // repeating session at FLASH_OFF; only the one-shot precapture request
                        // and the final still request use FLASH_SINGLE.
                        builder.set(
                            CaptureRequest.FLASH_MODE,
                            if (isCapture || useStillFlashTrigger) {
                                CameraMetadata.FLASH_MODE_SINGLE
                            } else {
                                CameraMetadata.FLASH_MODE_OFF
                            }
                        )
                    }
                }
            }

            CameraMetadata.FLASH_MODE_TORCH -> {
                builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
            }

            else -> {
                builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
            }
        }
    }

    /**
     * Convert the crop-box position from display coordinates to the active sensor
     * coordinate system used by SCALER_CROP_REGION. Both axes are normalized to [-1, 1].
     */
    private fun resolveTopOffZoomPan(
        cameraInfo: CameraInfo?,
        displayPanX: Float,
        displayPanY: Float,
        sensorOrientation: Int = cameraInfo?.sensorOrientation ?: 0,
    ): Pair<Float, Float> {
        val sensorPan = when (Math.floorMod(sensorOrientation, 360)) {
            90 -> displayPanY to -displayPanX
            180 -> -displayPanX to -displayPanY
            270 -> -displayPanY to displayPanX
            else -> displayPanX to displayPanY
        }
        val sensorX = if (
            cameraInfo?.lensFacing == CameraCharacteristics.LENS_FACING_FRONT
        ) -sensorPan.first else sensorPan.first
        return sensorX.coerceIn(-1f, 1f) to sensorPan.second.coerceIn(-1f, 1f)
    }

    /**
     * 应用变焦设置
     */
    private fun applyZoomSettings(
        builder: CaptureRequest.Builder,
        state: CameraState,
        isCapture: Boolean = false,
    ) {
        val openCameraId = getActiveOpenCameraId()
        if (openCameraId.isEmpty()) return

        try {
            val characteristics = resolveZoomRequestCharacteristics(openCameraId)
            val maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            val zoomRatioRange = characteristics.readMetadataOrNull(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            val minZoom = zoomRatioRange?.lower ?: 1f
            val maxSupportedZoom = zoomRatioRange?.upper ?: maxZoom
            val cameraInfo = state.getCurrentCameraInfo()
            val logicalAutoSwitch = shouldUseLogicalMultiCameraZoom(state, cameraInfo)
            val requestedDeviceZoomRatio = if (logicalAutoSwitch) {
                getTargetZoomRatioByMain(state, cameraInfo)
            } else {
                state.zoomRatio
            }
            val userZoomRatio = requestedDeviceZoomRatio.coerceIn(minZoom, maxSupportedZoom)
            val topOffZoomActive = state.captureMode == CaptureMode.VIDEO &&
                _topOffZoomEnabled.value &&
                !_topOffPositionLockActive.value &&
                !logicalAutoSwitch &&
                !spatialVideoEnabled &&
                !dualCaptureEnabled &&
                userZoomRatio >= 1f
            val zoomRatio = if (topOffZoomActive) {
                resolveTopOffZoomLockedRatio(userZoomRatio)
            } else {
                userZoomRatio
            }
            val activeRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
                ?: cameraInfo?.sensorOrientation ?: 0
            val cropAspectRatio = ZoomCropGeometry.sensorCoordinateAspectRatio(
                state.getPreviewAspectRatio(),
                sensorOrientation,
            )
            val topOffPan = if (topOffZoomActive) {
                resolveTopOffZoomPan(
                    cameraInfo,
                    _topOffZoomPanX.value,
                    _topOffZoomPanY.value,
                    sensorOrientation,
                )
            } else {
                null
            }
            val panX = topOffPan?.first ?: state.zoomPanX
            val panY = topOffPan?.second ?: state.zoomPanY

            if (_topOffZoomEnabled.value && !topOffZoomActive) {
                val inactiveReason = when {
                    state.captureMode != CaptureMode.VIDEO -> "capture-mode-not-video"
                    _topOffPositionLockActive.value -> "position-lock-native-crop-active"
                    logicalAutoSwitch -> "logical-multi-camera-active"
                    spatialVideoEnabled -> "spatial-video-active"
                    dualCaptureEnabled -> "dual-capture-active"
                    userZoomRatio < 1f -> "base-zoom-below-1x"
                    else -> "unknown"
                }
                val inactiveSignature = "inactive|$inactiveReason|$userZoomRatio|$logicalAutoSwitch"
                if (inactiveSignature != lastTopOffZoomDiagnosticSignature) {
                    lastTopOffZoomDiagnosticSignature = inactiveSignature
                    PLog.w(
                        "TopOffZoom",
                        "NOT_ACTIVE reason=$inactiveReason baseZoom=$userZoomRatio " +
                            "logicalAutoSwitch=$logicalAutoSwitch spatial=$spatialVideoEnabled dual=$dualCaptureEnabled"
                    )
                }
            }

            applyZoomRequestSettings(
                builder = builder,
                zoomRatio = zoomRatio,
                activeRect = activeRect,
                zoomRatioRange = zoomRatioRange,
                resetCropAtUnitZoom = false,
                panX = panX,
                panY = panY,
                useSoftwareCrop = topOffZoomActive,
                useFreeformPan = !topOffZoomActive && (
                    !logicalAutoSwitch && state.zoomPanEnabled &&
                        cameraInfo?.supportsFreeformZoomPan == true
                ),
                cropAspectRatio = cropAspectRatio,
            )
            if (topOffZoomActive) {
                logTopOffZoomRequest(
                    baseZoomRatio = userZoomRatio,
                    lockedZoomRatio = zoomRatio,
                    panX = panX,
                    panY = panY,
                    activeRect = activeRect,
                    cropAspectRatio = cropAspectRatio,
                    sensorOrientation = sensorOrientation,
                    softwareCrop = topOffZoomActive,
                )
            }
        } catch (e: Exception) {
            if (_topOffZoomEnabled.value) {
                PLog.e("TopOffZoom", "APPLY_FAILED while applying Camera2 crop request", e)
            }
            PLog.e(TAG, "Failed to apply zoom settings", e)
        }
    }

    private fun resolveZoomRequestCharacteristics(openCameraId: String): CameraCharacteristics {
        val physicalCameraId = activeOutputPhysicalCameraId
        if (physicalCameraId != null) {
            getCameraCharacteristicsOrNull(physicalCameraId, "physical zoom request")?.let {
                return it
            }
        }
        return getCameraCharacteristicsCached(openCameraId)
    }

    private fun applyZoomRequestSettings(
        builder: CaptureRequest.Builder,
        zoomRatio: Float,
        activeRect: Rect?,
        zoomRatioRange: android.util.Range<Float>?,
        resetCropAtUnitZoom: Boolean,
        panX: Float = 0f,
        panY: Float = 0f,
        useSoftwareCrop: Boolean = false,
        useFreeformPan: Boolean = false,
        cropAspectRatio: Float? = null,
    ) {
        // Top-Off framing is performed by the preview and encoder GPU renderers. Keep
        // Camera2 on the full active array so CENTER_ONLY HALs cannot recenter the crop.
        if (useSoftwareCrop) {
            if (zoomRatioRange != null) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f)
            }
            if (activeRect != null) {
                builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(activeRect))
            } else {
                builder.set(CaptureRequest.SCALER_CROP_REGION, null)
            }
            return
        }

        // Freeform SCALER_CROP_REGION must not be combined with CONTROL_ZOOM_RATIO != 1.
        // While joystick pan is active, express zoom as a non-centred sensor crop instead.
        if (useFreeformPan && activeRect != null) {
            if (zoomRatioRange != null) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f)
            }
            builder.set(
                CaptureRequest.SCALER_CROP_REGION,
                buildZoomPanCropRegion(
                    activeRect,
                    zoomRatio,
                    panX,
                    panY,
                    cropAspectRatio ?: (activeRect.width().toFloat() / activeRect.height().coerceAtLeast(1)),
                )
            )
            return
        }

        if (shouldUseControlZoomRatio(zoomRatioRange)) {
            // A previous joystick interaction may have installed a freeform crop.
            // Clear it before returning to native CONTROL_ZOOM_RATIO so the old offset
            // cannot compound the requested zoom or survive a joystick reset.
            builder.set(CaptureRequest.SCALER_CROP_REGION, null)
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
            return
        }

        activeRect ?: return
        if (zoomRatio <= 1f && !resetCropAtUnitZoom) return

        zoomRatioRange?.let {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f)
        }
        builder.set(CaptureRequest.SCALER_CROP_REGION, buildCenteredCropRegion(activeRect, zoomRatio))
    }

    private fun buildZoomPanCropRegion(
        activeRect: Rect,
        zoomRatio: Float,
        panX: Float,
        panY: Float,
        cropAspectRatio: Float,
    ): Rect {
        val geometry = ZoomCropGeometry.calculate(
            sourceWidth = activeRect.width().toFloat(),
            sourceHeight = activeRect.height().toFloat(),
            outputAspectRatio = cropAspectRatio,
            zoomRatio = zoomRatio,
            panX = panX,
            panY = panY,
        )
        val cropLeft = activeRect.left + (geometry.left * activeRect.width()).roundToInt()
        val cropTop = activeRect.top + (geometry.top * activeRect.height()).roundToInt()
        val cropRight = (activeRect.left + (geometry.right * activeRect.width()).roundToInt())
            .coerceIn(cropLeft + 1, activeRect.right)
        val cropBottom = (activeRect.top + (geometry.bottom * activeRect.height()).roundToInt())
            .coerceIn(cropTop + 1, activeRect.bottom)
        return Rect(cropLeft, cropTop, cropRight, cropBottom)
    }

    private fun shouldUseControlZoomRatio(zoomRatioRange: android.util.Range<Float>?): Boolean {
        zoomRatioRange ?: return false
        if (shouldUseLogicalMultiCameraZoom()) return true
        return _state.value.availableCameras.size <= 1 || zoomRatioRange.lower < 1f
    }

    private fun buildCenteredCropRegion(activeRect: Rect, zoomRatio: Float): Rect {
        val safeZoomRatio = zoomRatio.coerceAtLeast(1f)
        if (safeZoomRatio == 1f) return Rect(activeRect)

        val cropWidth = (activeRect.width() / safeZoomRatio).roundToInt().coerceAtLeast(1)
        val cropHeight = (activeRect.height() / safeZoomRatio).roundToInt().coerceAtLeast(1)
        val cropLeft = activeRect.left + (activeRect.width() - cropWidth) / 2
        val cropTop = activeRect.top + (activeRect.height() - cropHeight) / 2
        return Rect(
            cropLeft,
            cropTop,
            cropLeft + cropWidth,
            cropTop + cropHeight
        )
    }

    private fun Rect.toCameraCoordinateRect(): CameraCoordinateRect {
        return CameraCoordinateRect(
            left = left,
            top = top,
            right = right,
            bottom = bottom,
        )
    }

    private fun CameraCoordinateRect.toAndroidRect(): Rect {
        return Rect(left, top, right, bottom)
    }

    /**
     * 应用防抖设置
     *
     * 视频模式按用户选项启用 EIS/OIS。EIS+始终请求关闭 Camera2 EIS 和 HAL OIS；
     * 仅当 HAL 忽略 OIS OFF 时，才用逐时刻镜头内参或 OIS 位移修正滚快门网格。
     */
    private fun applyStabilizationSettings(
        builder: CaptureRequest.Builder,
        state: CameraState,
        isCapture: Boolean = false,
    ) {
        try {
            if (state.captureMode == CaptureMode.VIDEO) {
                val mode = state.videoConfig.stabilizationMode
                val enhancedSelected = mode == VideoStabilizationMode.ENHANCED
                val algorithmicVideoSelected = enhancedSelected || shouldUsePositionLockStabilization()
                val requestedVideoMode = if (algorithmicVideoSelected) {
                    // EIS+ performs the warp itself. Some front HALs omit OFF from their
                    // advertised mode list while still accepting the explicit request key.
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF.takeIf {
                        isCaptureRequestKeyAvailable(
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE.name
                        )
                    }
                } else {
                    resolveVideoStabilizationRequestMode(mode)
                }
                requestedVideoMode?.let { videoStabilizationMode ->
                    builder.set(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        videoStabilizationMode
                    )
                }
                if (algorithmicVideoSelected && isCaptureRequestKeyAvailable(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE.name
                    )
                ) {
                    // Never enable OIS proactively for EIS+. Some HALs default TEMPLATE_PREVIEW
                    // to OIS ON or ignore OFF; the coordinator fuses telemetry only for that
                    // forced-ON case and disables EIS+ if no correction stream is available.
                    builder.set(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF,
                    )
                } else if (availableOpticalStabilizationModes.contains(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                    )
                ) {
                    builder.set(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        if (mode == VideoStabilizationMode.OIS) {
                            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                        } else {
                            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                        }
                    )
                }
                applyOisDataMode(
                    builder,
                    algorithmicVideoSelected &&
                        realtimeStabilizationCoordinator.shouldRequestOisSamples,
                )
                return
            }

            val enhancedPhotoPreview = state.photoPreviewStabilizationEnabled && !isCapture

            // EIS+ performs its own frame correction in Photo preview. Explicitly request
            // Camera2 video stabilization OFF as well; preview templates on some HALs can
            // otherwise leave vendor stabilization active, which the coordinator correctly
            // interprets as a double-stabilization conflict and shuts EIS+ down for.
            if (enhancedPhotoPreview &&
                isCaptureRequestKeyAvailable(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE.name)
            ) {
                builder.set(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
                )
            }

            if (availableOpticalStabilizationModes.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
                builder.set(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    if (enhancedPhotoPreview) {
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                    } else {
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                    }
                )
            }
            applyOisDataMode(builder, enhancedPhotoPreview)
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to apply stabilization settings", e)
        }
    }

    private fun applyOisDataMode(builder: CaptureRequest.Builder, enabled: Boolean) {
        if (!isCaptureRequestKeyAvailable(CaptureRequest.STATISTICS_OIS_DATA_MODE.name)) return
        builder.set(
            CaptureRequest.STATISTICS_OIS_DATA_MODE,
            if (enabled) {
                CaptureRequest.STATISTICS_OIS_DATA_MODE_ON
            } else {
                CaptureRequest.STATISTICS_OIS_DATA_MODE_OFF
            },
        )
    }

    private fun resolveVideoStabilizationRequestMode(mode: VideoStabilizationMode): Int? {
        return when (mode) {
            VideoStabilizationMode.EIS -> when {
                isPreviewVideoStabilizationAvailable() ->
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION

                availableVideoStabilizationModes.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) ->
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON

                else -> null
            }

            VideoStabilizationMode.ENHANCED,
            VideoStabilizationMode.OIS,
            VideoStabilizationMode.OFF -> {
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                    .takeIf { availableVideoStabilizationModes.contains(it) }
            }
        }
    }

    private fun isPreviewVideoStabilizationAvailable(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                availableVideoStabilizationModes.contains(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
                )
    }

    /**
     * 应用图像质量设置（锐化、降噪）
     *
     * 照片与视频使用各自的参数，视频预览和录制保持一致。
     *
     * @param builder 需要配置的 Builder
     * @param currentState 当前拍摄模式及多帧配置
     */
    private fun applyImageQualitySettings(
        builder: CaptureRequest.Builder,
        currentState: CameraState,
        supportedEdgeModes: IntArray = availableEdgeModes,
        supportedNoiseReductionModes: IntArray = availableNoiseReductionModes,
    ) {
        try {
            val isVideo = currentState.captureMode == CaptureMode.VIDEO
            val selectedEdgeLevel = if (isVideo) videoEdgeLevel else edgeLevel
            val selectedNrLevel = if (isVideo) videoNrLevel else nrLevel
            val isBurst = !isVideo && currentState.requiresMultiFrameCaptureSequence
            val effectiveEdgeLevel = if (isBurst && selectedEdgeLevel == 2) 1 else selectedEdgeLevel
            val edgeMode = when (effectiveEdgeLevel) {
                0 -> CaptureRequest.EDGE_MODE_OFF
                1 -> CaptureRequest.EDGE_MODE_FAST
                2 -> CaptureRequest.EDGE_MODE_HIGH_QUALITY
                3 -> if (supportedEdgeModes.contains(CaptureRequest.EDGE_MODE_ZERO_SHUTTER_LAG)) {
                    CaptureRequest.EDGE_MODE_ZERO_SHUTTER_LAG
                } else {
                    CaptureRequest.EDGE_MODE_FAST
                }

                else -> CaptureRequest.EDGE_MODE_FAST
            }
            if (supportedEdgeModes.contains(edgeMode)) {
                builder.set(CaptureRequest.EDGE_MODE, edgeMode)
            } else if (supportedEdgeModes.contains(CaptureRequest.EDGE_MODE_FAST)) {
                builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
            }
            val effectiveNrLevel = if (
                isBurst && selectedNrLevel == NoiseReductionLevel.HIGH_QUALITY
            ) {
                NoiseReductionLevel.FAST
            } else {
                selectedNrLevel
            }
            val noiseReductionMode = when (effectiveNrLevel) {
                NoiseReductionLevel.OFF -> CaptureRequest.NOISE_REDUCTION_MODE_OFF
                NoiseReductionLevel.MINIMAL -> if (supportedNoiseReductionModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL)) {
                    CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL
                } else {
                    CaptureRequest.NOISE_REDUCTION_MODE_FAST
                }

                NoiseReductionLevel.FAST -> CaptureRequest.NOISE_REDUCTION_MODE_FAST
                NoiseReductionLevel.HIGH_QUALITY -> CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                NoiseReductionLevel.ZERO_SHUTTER_LAG -> if (supportedNoiseReductionModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG)) {
                    CaptureRequest.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG
                } else {
                    CaptureRequest.NOISE_REDUCTION_MODE_FAST
                }

                else -> CaptureRequest.NOISE_REDUCTION_MODE_FAST
            }

            if (supportedNoiseReductionModes.contains(noiseReductionMode)) {
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, noiseReductionMode)
            } else if (supportedNoiseReductionModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_FAST)) {
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
            }
            if (isVideo) {
                applyNormalVideoLogicalUltraWideImageQualitySettings(builder)
                applySpecialVideoLogicalUltraWideImageQualitySettings(
                    builder = builder,
                    selectedEdgeLevel = selectedEdgeLevel,
                    selectedNrLevel = effectiveNrLevel,
                )
            }
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to apply image quality settings", e)
        }
    }

    /**
     * Keep the Normal Video ultra-wide stream free of Camera2 HAL edge enhancement
     * (sharpness) and noise reduction. For a logical pair use physical request keys so
     * the main sensor retains its own settings; for a stand-alone wide camera use the
     * global request keys. The independent standby camera is configured separately below.
     */
    private fun applyNormalVideoLogicalUltraWideImageQualitySettings(
        builder: CaptureRequest.Builder,
    ) {
        if (spatialVideoEnabled) return

        if (normalVideoUltraWideInstantSwitchActive) {
            // Independent standby sessions receive OFF directly in their own request.
            if (normalVideoStreamingMode != SpatialVideoStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL ||
                Build.VERSION.SDK_INT < Build.VERSION_CODES.P
            ) return

            val physicalCameraId = normalVideoUltraWidePhysicalCameraId ?: return
            val characteristics = getCameraCharacteristicsOrNull(
                physicalCameraId,
                "Normal Video ultra-wide image quality",
            ) ?: return
            val physicalRequestKeys = characteristics.availablePhysicalCameraRequestKeys.orEmpty()
            val edgeModes = characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
                ?: intArrayOf()
            val noiseReductionModes = characteristics.get(
                CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES
            ) ?: intArrayOf()

            if (physicalRequestKeys.contains(CaptureRequest.EDGE_MODE) &&
                edgeModes.contains(CaptureRequest.EDGE_MODE_OFF)
            ) {
                builder.setPhysicalCameraKey(
                    CaptureRequest.EDGE_MODE,
                    CaptureRequest.EDGE_MODE_OFF,
                    physicalCameraId,
                )
            }
            if (physicalRequestKeys.contains(CaptureRequest.NOISE_REDUCTION_MODE) &&
                noiseReductionModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_OFF)
            ) {
                builder.setPhysicalCameraKey(
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    CaptureRequest.NOISE_REDUCTION_MODE_OFF,
                    physicalCameraId,
                )
            }
            return
        }

        // Normal Video with instant switching disabled: the selected camera itself may
        // be the rear ultra-wide lens, so disable both controls on its global request.
        val selectedCamera = _state.value.getCurrentCameraInfo() ?: return
        val selectedIsUltraWide = selectedCamera.lensFacing == CameraCharacteristics.LENS_FACING_BACK &&
            (selectedCamera.lensType == LensType.BACK_ULTRA_WIDE ||
                (selectedCamera.intrinsicZoomRatio.isFinite() &&
                    selectedCamera.intrinsicZoomRatio in 0.2f..0.85f))
        if (!selectedIsUltraWide) return

        if (availableEdgeModes.contains(CaptureRequest.EDGE_MODE_OFF)) {
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        }
        if (availableNoiseReductionModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_OFF)) {
            builder.set(
                CaptureRequest.NOISE_REDUCTION_MODE,
                CaptureRequest.NOISE_REDUCTION_MODE_OFF,
            )
        }
    }

    /**
     * A logical multi-camera Special Video session can route the 0.5x sensor through a
     * physical output of the main CameraDevice. Global request keys target the logical
     * camera, so also set the per-physical keys when the HAL advertises them.
     */
    private fun applySpecialVideoLogicalUltraWideImageQualitySettings(
        builder: CaptureRequest.Builder,
        selectedEdgeLevel: Int,
        selectedNrLevel: Int,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
            !spatialVideoEnabled ||
            spatialVideoStreamingMode != SpatialVideoStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL
        ) return

        val physicalCameraId = spatialVideoUltraWidePhysicalCameraId ?: return
        val characteristics = getCameraCharacteristicsOrNull(
            physicalCameraId,
            "Special Video ultra-wide image quality",
        ) ?: return
        val physicalRequestKeys = characteristics.availablePhysicalCameraRequestKeys.orEmpty()
        val edgeModes = characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
            ?: intArrayOf()
        val noiseReductionModes = characteristics.get(
            CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES
        ) ?: intArrayOf()

        val edgeMode = when (selectedEdgeLevel) {
            0 -> CaptureRequest.EDGE_MODE_OFF
            1 -> CaptureRequest.EDGE_MODE_FAST
            2 -> CaptureRequest.EDGE_MODE_HIGH_QUALITY
            3 -> if (edgeModes.contains(CaptureRequest.EDGE_MODE_ZERO_SHUTTER_LAG)) {
                CaptureRequest.EDGE_MODE_ZERO_SHUTTER_LAG
            } else {
                CaptureRequest.EDGE_MODE_FAST
            }
            else -> CaptureRequest.EDGE_MODE_FAST
        }
        if (physicalRequestKeys.contains(CaptureRequest.EDGE_MODE) && edgeModes.contains(edgeMode)) {
            builder.setPhysicalCameraKey(CaptureRequest.EDGE_MODE, edgeMode, physicalCameraId)
        }

        val noiseReductionMode = when (selectedNrLevel) {
            NoiseReductionLevel.OFF -> CaptureRequest.NOISE_REDUCTION_MODE_OFF
            NoiseReductionLevel.MINIMAL -> if (noiseReductionModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL)) {
                CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL
            } else {
                CaptureRequest.NOISE_REDUCTION_MODE_FAST
            }
            NoiseReductionLevel.FAST -> CaptureRequest.NOISE_REDUCTION_MODE_FAST
            NoiseReductionLevel.HIGH_QUALITY -> CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
            NoiseReductionLevel.ZERO_SHUTTER_LAG -> if (noiseReductionModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG)) {
                CaptureRequest.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG
            } else {
                CaptureRequest.NOISE_REDUCTION_MODE_FAST
            }
            else -> CaptureRequest.NOISE_REDUCTION_MODE_FAST
        }
        if (physicalRequestKeys.contains(CaptureRequest.NOISE_REDUCTION_MODE) &&
            noiseReductionModes.contains(noiseReductionMode)
        ) {
            builder.setPhysicalCameraKey(
                CaptureRequest.NOISE_REDUCTION_MODE,
                noiseReductionMode,
                physicalCameraId,
            )
        }
    }

    private fun applyStillPostProcessingSettings(
        builder: CaptureRequest.Builder,
        state: CameraState,
        isCapture: Boolean
    ) {
        if (!isCapture || state.captureMode != CaptureMode.PHOTO) {
            applyFastStillPostProcessingSettings(builder)
            return
        }

        applyHighQualityStillPostProcessingSettings(builder)
    }

    private fun applyFastStillPostProcessingSettings(builder: CaptureRequest.Builder) {
        selectBestMode(
            availableColorCorrectionAberrationModes,
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST,
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF
        )?.let { builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, it) }

        selectBestMode(
            availableHotPixelModes,
            CaptureRequest.HOT_PIXEL_MODE_FAST,
            CaptureRequest.HOT_PIXEL_MODE_OFF
        )?.let { builder.set(CaptureRequest.HOT_PIXEL_MODE, it) }

        selectBestMode(
            availableShadingModes,
            CaptureRequest.SHADING_MODE_FAST,
            CaptureRequest.SHADING_MODE_OFF
        )?.let { builder.set(CaptureRequest.SHADING_MODE, it) }

        selectBestMode(
            availableDistortionCorrectionModes,
            CaptureRequest.DISTORTION_CORRECTION_MODE_FAST,
            CaptureRequest.DISTORTION_CORRECTION_MODE_OFF
        )?.let { builder.set(CaptureRequest.DISTORTION_CORRECTION_MODE, it) }
    }

    private fun applyHighQualityStillPostProcessingSettings(builder: CaptureRequest.Builder) {
        selectBestMode(
            availableColorCorrectionAberrationModes,
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY,
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST,
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF
        )?.let { builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, it) }

        selectBestMode(
            availableHotPixelModes,
            CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY,
            CaptureRequest.HOT_PIXEL_MODE_FAST,
            CaptureRequest.HOT_PIXEL_MODE_OFF
        )?.let { builder.set(CaptureRequest.HOT_PIXEL_MODE, it) }

        selectBestMode(
            availableShadingModes,
            CaptureRequest.SHADING_MODE_HIGH_QUALITY,
            CaptureRequest.SHADING_MODE_FAST,
            CaptureRequest.SHADING_MODE_OFF
        )?.let { builder.set(CaptureRequest.SHADING_MODE, it) }

        selectBestMode(
            availableDistortionCorrectionModes,
            CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY,
            CaptureRequest.DISTORTION_CORRECTION_MODE_FAST,
            CaptureRequest.DISTORTION_CORRECTION_MODE_OFF
        )?.let { builder.set(CaptureRequest.DISTORTION_CORRECTION_MODE, it) }
    }

    private fun selectBestMode(availableModes: IntArray, vararg preferredModes: Int): Int? {
        return preferredModes.firstOrNull { availableModes.contains(it) }
    }

    private fun buildSelectableNoiseReductionModes(hardwareModes: IntArray): IntArray {
        val orderedModes = mutableListOf<Int>()
        val preferredOrder = listOf(
            CaptureRequest.NOISE_REDUCTION_MODE_OFF,
            CaptureRequest.NOISE_REDUCTION_MODE_FAST,
            CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
            CaptureRequest.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG,
            CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL
        )
        preferredOrder.forEach { mode ->
            if (hardwareModes.contains(mode)) {
                orderedModes += mode
            }
        }
        return orderedModes.toIntArray()
    }

    /**
     * 设置锐化等级
     */
    fun setEdgeLevel(level: Int) {
        edgeLevel = level
    }

    /**
     * 设置降噪等级
     */
    fun setNRLevel(level: Int) {
        val normalizedLevel = NoiseReductionLevel.normalize(level)
        nrLevel = normalizedLevel
        _state.value = _state.value.copy(nrLevel = normalizedLevel)
    }

    private fun refreshSpatialVideoStandbyImageQualitySettings() {
        if (!spatialVideoEnabled) return
        val state = _state.value
        if (state.videoRecordingState.isRecording || state.videoRecordingState.isProcessing ||
            videoRecorder.isRecording()
        ) return
        val builder = spatialVideoStandbyRequestBuilder ?: return
        val handler = cameraHandler ?: return

        try {
            applyImageQualitySettings(
                builder = builder,
                currentState = state,
                supportedEdgeModes = spatialVideoStandbyEdgeModes,
                supportedNoiseReductionModes = spatialVideoStandbyNoiseReductionModes,
            )
            val updatedRequest = builder.build()
            spatialVideoStandbyRepeatingRequest = updatedRequest
            val session = spatialVideoStandbySession
            if (session != null && !spatialVideoPrimarySessionAwaitingStandby) {
                session.setRepeatingRequest(updatedRequest, null, handler)
            }
            PLog.d(
                TAG,
                "Special Video ultra-wide image quality updated: nr=$videoNrLevel, edge=$videoEdgeLevel"
            )
        } catch (error: Exception) {
            PLog.e(TAG, "Could not update Special Video ultra-wide image quality", error)
        }
    }

    fun setVideoImageQualitySettings(nrLevel: Int, edgeLevel: Int) {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post { setVideoImageQualitySettings(nrLevel, edgeLevel) }
            return
        }

        val normalizedNrLevel = NoiseReductionLevel.normalize(nrLevel)
        if (videoNrLevel == normalizedNrLevel && videoEdgeLevel == edgeLevel) return
        videoNrLevel = normalizedNrLevel
        videoEdgeLevel = edgeLevel
        PLog.d(TAG, "Video image quality settings: nr=$videoNrLevel, edge=$videoEdgeLevel")

        val currentState = _state.value
        if (currentState.captureMode == CaptureMode.VIDEO) {
            previewRequestBuilder?.let { builder ->
                applyImageQualitySettings(builder, currentState)
                updatePreview()
            }
            // Special Video's 0.5x camera has its own Camera2 session. Apply the same
            // video NR/sharpness preferences to that sensor without touching normal Video.
            refreshSpatialVideoStandbyImageQualitySettings()
        }
    }

    fun setVendorCaptureSettingsByLens(settingsByLens: VendorCaptureSettingsByLens) {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post {
                setVendorCaptureSettingsByLens(settingsByLens)
            }
            return
        }

        val previousState = _state.value
        if (previousState.vendorCaptureSettingsByLens == settingsByLens) return

        val previousVendorSessionValues = forcedVendorSessionParameterValues(previousState)
        val updatedState = previousState.copy(vendorCaptureSettingsByLens = settingsByLens)
        val updatedVendorSessionValues = forcedVendorSessionParameterValues(updatedState)

        _state.value = updatedState
        PLog.d(TAG, "Vendor capture lens settings count: ${settingsByLens.settingsByLensId.size}")

        val shouldRecreateSession =
            previousVendorSessionValues != updatedVendorSessionValues &&
                    cameraDevice != null &&
                    previewSurface != null
        if (shouldRecreateSession) {
            if (_state.value.videoRecordingState.isRecording) {
                pendingVendorSessionParameterRestart = true
                PLog.w(
                    TAG,
                    "Vendor session parameter changed during recording; keeping active request until session restart"
                )
                return
            }
            PLog.d(
                TAG,
                "Recreating preview session for vendor session parameter change: " +
                        "old=$previousVendorSessionValues, new=$updatedVendorSessionValues"
            )
            createPreviewSession(openGeneration = cameraOpenGeneration)
            return
        }

        previewRequestBuilder?.apply {
            applyBaseCameraSettings(this, isCapture = false)
            updatePreview()
        }
    }

    fun setCustomVendorKeySettings(settings: CustomVendorKeySettings) {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post {
                setCustomVendorKeySettings(settings)
            }
            return
        }

        val previousState = _state.value
        if (previousState.customVendorKeySettings == settings) return

        val previousSessionKeys = customVendorSessionParameterKeys(previousState)
        val updatedState = previousState.copy(customVendorKeySettings = settings)
        val updatedSessionKeys = customVendorSessionParameterKeys(updatedState)

        _state.value = updatedState
        PLog.d(TAG, "Custom vendor key count: ${settings.keys.size}")

        val shouldRecreateSession =
            previousSessionKeys != updatedSessionKeys &&
                cameraDevice != null &&
                previewSurface != null
        if (shouldRecreateSession) {
            if (_state.value.videoRecordingState.isRecording) {
                pendingVendorSessionParameterRestart = true
                PLog.w(
                    TAG,
                    "Custom vendor session parameter changed during recording; " +
                        "keeping active request until session restart"
                )
                return
            }
            PLog.d(
                TAG,
                "Recreating preview session for custom vendor session parameter change: " +
                    "old=${previousSessionKeys.map { it.keyName }}, " +
                    "new=${updatedSessionKeys.map { it.keyName }}"
            )
            createPreviewSession(openGeneration = cameraOpenGeneration)
            return
        }

        previewRequestBuilder?.apply {
            applyBaseCameraSettings(this, isCapture = false)
            updatePreview()
        }
    }

    /**
     * 设置是否使用 RAW 格式拍照
     */
    fun setUseRaw(
        enabled: Boolean,
        reconfigureCaptureOutputIfNeeded: Boolean = true
    ) {
        requestedRawCaptureEnabled = enabled
        _state.update { it.copy(useRaw = enabled) }
        PLog.d(TAG, "RAW 格式拍照: $enabled")

        if (!reconfigureCaptureOutputIfNeeded) return

        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post {
                restartCameraForRawCaptureOutputMismatch("RAW setting changed")
            }
            return
        }
        restartCameraForRawCaptureOutputMismatch("RAW setting changed")
    }

    fun setRawMinShutterSpeedNs(value: Long) {
        val resolvedValue = value.coerceAtLeast(0L)
        _state.value = _state.value.copy(rawMinShutterSpeedNs = resolvedValue)
        PLog.d(TAG, "RAW 最低快门速度: $resolvedValue ns")
    }

    /**
     * 设置虚化模拟光圈大小
     */
    fun setAperture(value: Float) {
        _state.value = _state.value.copy(virtualAperture = value)
        PLog.d(TAG, "设置虚拟光圈: $value")
    }

    /**
     * 启用/禁用虚拟光圈控制
     */
    fun setVirtualApertureEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(isVirtualApertureEnabled = enabled)
        PLog.d(TAG, "虚拟光圈开关: $enabled")
    }

    /**
     * 获取当前摄像头 ID
     */
    fun getCurrentCameraId(): String {
        return _state.value.currentCameraId
    }

    /** Physical sensor area for the selected output lens, in square millimeters. */
    fun getCurrentSensorPhysicalAreaMm2(): Float? {
        val characteristics = resolveActiveFocusCharacteristics()?.second
            ?: getActiveOpenCameraCharacteristics()
            ?: return null
        val size = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?: return null
        return (size.width * size.height).takeIf { it.isFinite() && it > 0f }
    }

    /**
     * Resolve display-space FOV from the active camera's physical sensor, focal length and
     * active video-aspect crop. The zoom returned here matches the preview/encoder Top-Off crop.
     */
    fun getTopOffGyroPanOptics(): GyroPanOptics {
        val currentState = _state.value
        val cameraInfo = currentState.getCurrentCameraInfo()
        val selectedUltraWide = cameraInfo?.let { camera ->
            camera.lensFacing == CameraCharacteristics.LENS_FACING_BACK &&
                camera.intrinsicZoomRatio.isFinite() &&
                camera.intrinsicZoomRatio in 0.2f..0.85f
        } == true
        val requestedZoom = if (selectedUltraWide) {
            _topOffZoomFactor.value
        } else {
            _topOffZoomLockedRatio.value.takeIf { it.isFinite() && it >= 1f }
                ?: (currentState.zoomRatio.coerceAtLeast(1f) * _topOffZoomFactor.value)
        }
        val safeZoom = requestedZoom.takeIf { it.isFinite() }?.coerceIn(1f, 20f) ?: 1f

        val characteristics = activeOutputPhysicalCameraId
            ?.let { getCameraCharacteristicsOrNull(it, "Top-Off gyro optics") }
            ?: getActiveOpenCameraCharacteristics()
        val physicalSize = characteristics?.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focalLengthMm = characteristics
            ?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull { it.isFinite() && it > 0f }
        if (characteristics == null || physicalSize == null || focalLengthMm == null) {
            PLog.w(
                "GyroPan",
                "Active sensor size/focal length unavailable; using fallback FOV, zoom=$safeZoom"
            )
            return GyroPanOptics(60f, 45f, safeZoom)
        }

        // SENSOR_INFO_PHYSICAL_SIZE describes the pixel-array dimensions. Restrict it to the
        // active array when the device advertises a smaller active rectangle.
        val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val pixelArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val activeWidthScale = if (activeArray != null && pixelArray != null && pixelArray.width > 0) {
            (activeArray.width().toFloat() / pixelArray.width.toFloat()).coerceIn(0.1f, 1.1f)
        } else {
            1f
        }
        val activeHeightScale = if (activeArray != null && pixelArray != null && pixelArray.height > 0) {
            (activeArray.height().toFloat() / pixelArray.height.toFloat()).coerceIn(0.1f, 1.1f)
        } else {
            1f
        }
        val activeSensorWidthMm = physicalSize.width * activeWidthScale
        val activeSensorHeightMm = physicalSize.height * activeHeightScale
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
            ?: cameraInfo?.sensorOrientation
            ?: cachedSensorOrientation
        val sensorAspectRatio = ZoomCropGeometry.sensorCoordinateAspectRatio(
            currentState.getPreviewAspectRatio(),
            sensorOrientation,
        )
        val fieldOfView = GyroPanFilterMath.fieldOfViewDegrees(
            activeSensorWidthMm = activeSensorWidthMm,
            activeSensorHeightMm = activeSensorHeightMm,
            sensorCoordinateAspectRatio = sensorAspectRatio,
            focalLengthMm = focalLengthMm,
            sensorOrientationDegrees = sensorOrientation,
        )
        if (fieldOfView == null) {
            PLog.w(
                "GyroPan",
                "Could not resolve video crop FOV; using fallback FOV, zoom=$safeZoom"
            )
            return GyroPanOptics(60f, 45f, safeZoom)
        }
        return GyroPanOptics(
            horizontalFieldOfViewDegrees = fieldOfView.horizontalDegrees,
            verticalFieldOfViewDegrees = fieldOfView.verticalDegrees,
            zoomRatio = safeZoom,
        )
    }

    /** 获取传感器方向（供外部 YUV 处理使用） */
    fun getSensorOrientation(): Int {
        return cachedSensorOrientation
    }

    /**
     * 获取镜头朝向
     */
    fun getLensFacing(): Int {
        return cachedLensFacing
    }

    /**
     * 获取最后一次拍摄结果（用于异步获取 EXIF 信息）
     */
    fun getLastCaptureResult(): CaptureResult? {
        return lastCaptureResult
    }

// ==================== 镜头切换 ====================

    /**
     * 获取所有后置摄像头
     */
    fun getBackCameras(): List<CameraInfo> {
        return _state.value.availableCameras.filter {
            it.lensFacing == CameraCharacteristics.LENS_FACING_BACK
        }
    }

    /**
     * 切换摄像头（前后置切换）
     */
    fun switchCamera() {
        val currentLensType = _state.value.currentLensType

        val nextLensType = if (currentLensType == LensType.FRONT) {
            LensType.BACK_MAIN
        } else {
            LensType.FRONT
        }

        switchToLens(nextLensType)
    }

    /**
     * 切换到指定的镜头类型
     */
    fun switchToLens(lensType: LensType) {
        val cameras = _state.value.availableCameras
        val currentLensType = _state.value.currentLensType

        if (currentLensType == lensType) return

        val targetCamera = cameras.find { it.lensType == lensType }

        targetCamera?.let { cam ->
            PLog.d(TAG, "Switching to lens: $lensType, cameraId: ${cam.cameraId}")
            _state.value = _state.value.copy(
                currentCameraId = cam.cameraId,
                currentLensType = cam.lensType,
                zoomRatio = 1f
            )
        } ?: PLog.w(TAG, "Camera with lens type $lensType not found")
    }

    /**
     * 切换到指定的相机 ID
     */
    fun switchToCameraId(cameraId: String) {
        val cameras = _state.value.availableCameras
        val targetCamera = cameras.find { it.cameraId == cameraId }

        targetCamera?.let { cam ->
            PLog.d(TAG, "Switching to camera ID: $cameraId")
            val previousState = _state.value
            val currentCamera = previousState.getCurrentCameraInfo()
            val preserveNormalVideoPairZoom = normalVideoUltraWideInstantSwitchActive &&
                isCurrentNormalVideoPairLens(currentCamera) &&
                isCurrentNormalVideoPairLens(cam)
            _state.value = previousState.copy(
                currentCameraId = cam.cameraId,
                currentLensType = cam.lensType,
                // Both paired streams are already running; resetting to 1x here creates
                // a visible pause in the middle of an otherwise continuous zoom handoff.
                zoomRatio = if (preserveNormalVideoPairZoom) previousState.zoomRatio else 1f
            )
            if (normalVideoUltraWideInstantSwitchActive && videoRecorder.isRecording()) {
                videoRecorder.setSwitchableCameraInputActive(isNormalVideoUltraWideLens(cam))
            }
        } ?: PLog.w(TAG, "Camera with ID $cameraId not found")
    }

// ==================== 曝光控制 ====================

    /** Serialize user changes and their snapshots with the asynchronous focus controls. */
    fun updateUserCaptureSettings(
        update: Camera2Controller.() -> Unit,
        onUpdated: (CustomCaptureSettings) -> Unit,
    ) {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post { updateUserCaptureSettings(update, onUpdated) }
            return
        }
        update(this)
        val settings = snapshotCustomCaptureSettings()
        if (retainCaptureSettingsEnabled) {
            retainedCaptureSettings = settings
            onUpdated(settings)
        }
    }

    fun setCaptureSettingsRetention(
        enabled: Boolean,
        savedSettings: CustomCaptureSettings? = null,
        onConfigured: ((CustomCaptureSettings?) -> Unit)? = null,
    ) {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post { setCaptureSettingsRetention(enabled, savedSettings, onConfigured) }
            return
        }
        retainCaptureSettingsEnabled = enabled
        retainedCaptureSettings = if (enabled) savedSettings ?: snapshotCustomCaptureSettings() else null
        if (!enabled) pendingRetainedAwbTemperature = null
        onConfigured?.invoke(retainedCaptureSettings)
    }

    private fun snapshotCustomCaptureSettings(): CustomCaptureSettings {
        val current = _state.value
        return CustomCaptureSettings.fromState(current).copy(
            // Preserve the mode to return to when hyperfocal focus is turned off.
            isAutoFocus = focusModeBeforeHyperfocal ?: current.isAutoFocus,
            focusDistance = focusDistanceBeforeHyperfocal ?: current.focusDistance,
            awbMode = if (pendingRetainedAwbTemperature != null) {
                CameraMetadata.CONTROL_AWB_MODE_OFF
            } else current.awbMode,
            awbTemperature = pendingRetainedAwbTemperature ?: current.awbTemperature,
        )
    }

    /** Apply after lens capabilities are loaded, before constructing the first preview request. */
    private fun applyRetainedCaptureSettings() {
        val settings = retainedCaptureSettings ?: return
        val current = _state.value
        val evStep = current.getExposureCompensationStep()
        val evRange = current.getExposureCompensationRange()
        val compensation = if (evStep > 0f) {
            (settings.exposureCompensationEv / evStep).roundToInt()
                .coerceIn(evRange.lower, evRange.upper)
        } else 0
        val supportsManualExposure = isManualSensorSupported &&
            availableAeModes.contains(CaptureRequest.CONTROL_AE_MODE_OFF)
        val isoAuto = settings.isIsoAuto || !supportsManualExposure
        val shutterAuto = settings.isShutterSpeedAuto || !supportsManualExposure
        val isoRange = current.getIsoRange()
        val restoredIso = settings.iso.coerceIn(isoRange.lower, isoRange.upper)
        val restoredShutter = coerceManualShutterSpeed(current, settings.shutterSpeedNs)
        val manualExposureAdjustment = if (!isoAuto && !shutterAuto) {
            (ln(restoredIso.toDouble() / settings.iso) +
                ln(restoredShutter.toDouble() / settings.shutterSpeedNs)) / ln(2.0)
        } else 0.0
        _state.value = current.copy(
            exposureCompensation = compensation,
            exposureBias = if (isoAuto || shutterAuto) {
                compensation * evStep
            } else settings.exposureBiasEv + manualExposureAdjustment.toFloat(),
            isIsoAuto = isoAuto,
            iso = restoredIso,
            isShutterSpeedAuto = shutterAuto,
            shutterSpeed = restoredShutter,
        )
        setAutoFocus(settings.isAutoFocus || current.minimumFocusDistance <= 0f)
        if (!settings.isAutoFocus) setFocusDistance(settings.focusDistance)
        if (settings.isHyperfocalFocusEnabled) setHyperfocalFocusEnabled(true)

        // Manual WB needs a fresh CCT or gains/transform anchor from this camera session.
        manualWhiteBalanceAnchor = null
        if (settings.awbMode == CameraMetadata.CONTROL_AWB_MODE_OFF) {
            setAwbMode(CameraMetadata.CONTROL_AWB_MODE_AUTO)
            pendingRetainedAwbTemperature = settings.awbTemperature
        } else {
            setAwbMode(settings.awbMode)
        }
        PLog.d(TAG, "Restored custom capture settings for camera=${current.currentCameraId}: $settings")
    }

    /**
     * 设置曝光补偿
     */
    fun setExposureCompensation(value: Int) {
        val range = _state.value.getExposureCompensationRange()
        val evStep = _state.value.getExposureCompensationStep()
        val clampedValue = value.coerceIn(range.lower, range.upper)
        val exposureBias = clampedValue * evStep
        _state.value = _state.value.copy(exposureCompensation = clampedValue, exposureBias = exposureBias)

        previewRequestBuilder?.apply {
            // 使用统一的曝光设置方法，确保与闪光灯模式正确配合
            applyExposureSettings(this, _state.value, false)
            updatePreview()
        }
    }

    /**
     * 设置 ISO
     */
    fun setIso(value: Int) {
        val currentState = _state.value
        val range = currentState.getIsoRange()
        val clampedValue = value.coerceIn(range.lower, range.upper)

        val evStep = currentState.getExposureCompensationStep()
        val sliderBias = currentState.exposureCompensation * evStep
        // 只有当两者都是手动时，才需要计算并叠加曝光偏移
        val isFullManual = !currentState.isShutterSpeedAuto

        val newBias = if (isFullManual) {
            val deltaEv = if (currentState.iso > 0) {
                ln(clampedValue.toDouble() / currentState.iso.toDouble()) / ln(2.0)
            } else 0.0
            currentState.exposureBias + deltaEv.toFloat()
        } else {
            sliderBias
        }

        _state.value = currentState.copy(
            iso = clampedValue,
            isIsoAuto = false,
            exposureBias = newBias
        )

        previewRequestBuilder?.apply {
            applyExposureSettings(this, _state.value, false)
            updatePreview()
        }
    }

    /**
     * 设置快门速度
     *
     * 注意：照片预览会限制最大曝光时间为 1/15 秒，视频模式限制为 1/6 秒
     * 照片拍摄请求会使用完整的用户设置
     */
    fun setShutterSpeed(value: Long) {
        val currentState = _state.value
        val clampedValue = coerceManualShutterSpeed(currentState, value)

        val evStep = currentState.getExposureCompensationStep()
        val sliderBias = currentState.exposureCompensation * evStep
        // 只有当两者都是手动时，才需要计算并叠加曝光偏移
        val isFullManual = !currentState.isIsoAuto

        val newBias = if (isFullManual) {
            val deltaEv = if (currentState.shutterSpeed > 0) {
                ln(clampedValue.toDouble() / currentState.shutterSpeed.toDouble()) / ln(2.0)
            } else 0.0
            currentState.exposureBias + deltaEv.toFloat()
        } else {
            sliderBias
        }

        _state.value = currentState.copy(
            shutterSpeed = clampedValue,
            isShutterSpeedAuto = false,
            exposureBias = newBias
        )

        previewRequestBuilder?.apply {
            applyExposureSettings(this, _state.value, false)
            updatePreview()
        }
    }

    /* ... flash mode ... */

    fun setFlashMode(value: Int) {
        _state.value = _state.value.copy(flashMode = value)

        previewRequestBuilder?.apply {
            // 预览不发送预闪触发器；支持 ON_ALWAYS_FLASH 时由 AE 模式保留闪光会话配置，
            // 实际闪光仍只由静态拍摄/预闪序列触发。
            applyBaseCameraSettings(this, isCapture = false)
            setAePrecaptureTriggerIfSupported(
                this,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE,
            )
            updatePreview()
        }
    }

    /**
     * 设置自动曝光模式 (Legacy / Global)
     */
    fun setAutoExposure(enabled: Boolean) {
        val currentState = _state.value
        val evStep = currentState.getExposureCompensationStep()
        val sliderBias = currentState.exposureCompensation * evStep

        _state.value = currentState.copy(
            isIsoAuto = enabled,
            isShutterSpeedAuto = enabled,
            exposureBias = if (enabled) sliderBias else currentState.exposureBias
        )

        previewRequestBuilder?.apply {
            applyExposureSettings(this, _state.value, false)
            updatePreview()
        }
    }

    /**
     * 设置 ISO 自动模式
     */
    fun setIsoAuto(enabled: Boolean) {
        val currentState = _state.value
        val evStep = currentState.getExposureCompensationStep()
        val sliderBias = currentState.exposureCompensation * evStep

        // 只要 ISO 变成自动，或者快门已经是自动，都属于自动/半自动模式，重置偏移量
        val isAutoOrSemi = enabled || currentState.isShutterSpeedAuto
        val exposureBias = if (isAutoOrSemi) sliderBias else currentState.exposureBias

        _state.value = currentState.copy(
            isIsoAuto = enabled,
            exposureBias = exposureBias
        )
        previewRequestBuilder?.apply {
            applyExposureSettings(this, _state.value, false)
            updatePreview()
        }
    }

    /**
     * 设置快门自动模式
     */
    fun setShutterSpeedAuto(enabled: Boolean) {
        val currentState = _state.value
        val evStep = currentState.getExposureCompensationStep()
        val sliderBias = currentState.exposureCompensation * evStep

        // 只要快门变成自动，或者 ISO 已经是自动，都属于自动/半自动模式，重置偏移量
        val isAutoOrSemi = enabled || currentState.isIsoAuto
        val exposureBias = if (isAutoOrSemi) sliderBias else currentState.exposureBias

        _state.value = currentState.copy(
            isShutterSpeedAuto = enabled,
            exposureBias = exposureBias
        )
        previewRequestBuilder?.apply {
            applyExposureSettings(this, _state.value, false)
            updatePreview()
        }
    }

    /**
     * 设置白平衡模式
     */
    fun setAwbMode(mode: Int) {
        pendingRetainedAwbTemperature = null
        val normalizedMode = if (availableAwbModes.isEmpty() || mode in availableAwbModes) {
            mode
        } else {
            CameraMetadata.CONTROL_AWB_MODE_AUTO
        }

        if (normalizedMode == CameraMetadata.CONTROL_AWB_MODE_OFF) {
            val snapshot = lastWhiteBalanceResult
            if (!canAdjustManualWhiteBalance(snapshot)) {
                PLog.w(TAG, "Manual white balance ignored: current CCT or gains/transform result is unavailable")
                _state.value = _state.value.copy(
                    awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO,
                    canAdjustWhiteBalance = false
                )
                previewRequestBuilder?.apply {
                    applyWhiteBalanceSettings(this, _state.value, false)
                    updatePreview()
                }
                return
            }

            val range = resolveAwbTemperatureRange()
            val initialTemperature = (
                    snapshot?.colorTemperature
                        ?: _state.value.actualAwbTemperature
                        ?: snapshot?.gains?.let(::estimateKelvinFromRggbGains)
                        ?: _state.value.awbTemperature
                    ).coerceIn(range.lower, range.upper)
            val anchor = createManualWhiteBalanceAnchor(snapshot, initialTemperature)
            if (anchor == null) {
                PLog.w(TAG, "Manual white balance ignored: failed to freeze current white balance result")
                _state.value = _state.value.copy(
                    awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO,
                    canAdjustWhiteBalance = false
                )
                previewRequestBuilder?.apply {
                    applyWhiteBalanceSettings(this, _state.value, false)
                    updatePreview()
                }
                return
            }
            manualWhiteBalanceAnchor = anchor
            _state.value = _state.value.copy(
                awbMode = CameraMetadata.CONTROL_AWB_MODE_OFF,
                awbTemperature = initialTemperature,
                canAdjustWhiteBalance = true
            )
        } else {
            manualWhiteBalanceAnchor = null
            _state.value = _state.value.copy(awbMode = normalizedMode)
        }

        previewRequestBuilder?.apply {
            applyWhiteBalanceSettings(this, _state.value, false)
            updatePreview()
        }
    }

    /**
     * 设置白平衡色温（Kelvin）
     *
     * App 可调范围为 2000K - 8000K；CCT 下发时再按设备上报范围夹住。
     * 没有当前真实 CCT 或 RGGB gains + color transform 时忽略手动调整。
     */
    fun setAwbTemperature(kelvin: Int) {
        val range = resolveAwbTemperatureRange()
        val clampedKelvin = kelvin.coerceIn(range.lower, range.upper)
        val snapshot = lastWhiteBalanceResult
        val anchor = manualWhiteBalanceAnchor ?: createManualWhiteBalanceAnchor(
            snapshot = snapshot,
            baseTemperature = snapshot?.colorTemperature
                ?: _state.value.actualAwbTemperature
                ?: snapshot?.gains?.let(::estimateKelvinFromRggbGains)
                ?: clampedKelvin
        )?.also { manualWhiteBalanceAnchor = it }
        if (anchor == null) {
            PLog.w(TAG, "Manual white balance temperature ignored: current CCT or gains/transform result is unavailable")
            return
        }

        pendingRetainedAwbTemperature = null

        _state.value = _state.value.copy(
            awbTemperature = clampedKelvin,
            awbMode = CameraMetadata.CONTROL_AWB_MODE_OFF,
            canAdjustWhiteBalance = true
        )

        previewRequestBuilder?.apply {
            applyWhiteBalanceSettings(this, _state.value, false)
            PLog.d(
                TAG,
                "AWB temperature set to: ${clampedKelvin}K (${anchor.controlPath.name})"
            )
            updatePreview()
        }
    }

    /**
     * 设置测光模式
     */
    fun setMeteringMode(mode: MeteringMode) {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post { setMeteringMode(mode) }
            return
        }
        _state.value = _state.value.copy(meteringMode = mode, meteringPoint = null)
        if (mode == MeteringMode.HIGHLIGHT_PRIORITY) {
            highlightPointInitialized = false
            lastSentHighlightPointX = -1f
            lastSentHighlightPointY = -1f
        }
        applyMeteringRegions()
        updatePreview()
        PLog.d(TAG, "测光模式: $mode")
    }

    /**
     * 更新高光区域坐标（由 GL 测光回调调用）
     * 使用 EMA 平滑防止 AE 频繁跳动
     */
    fun updateHighlightPoint(x: Float, y: Float) {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post { updateHighlightPoint(x, y) }
            return
        }
        highlightPointX = x
        highlightPointY = y
        if (!highlightPointInitialized) {
            highlightPointSmoothedX = x
            highlightPointSmoothedY = y
            highlightPointInitialized = true
        } else {
            val alpha = 0.1 // 降低平滑系数，增加稳定性
            highlightPointSmoothedX = (alpha * x + (1 - alpha) * highlightPointSmoothedX).toFloat()
            highlightPointSmoothedY = (alpha * y + (1 - alpha) * highlightPointSmoothedY).toFloat()
        }
        if (_state.value.effectiveMeteringMode == MeteringMode.HIGHLIGHT_PRIORITY) {
            // 计算当前平滑点与上次发送点的位移距离
            val dist = hypot(
                highlightPointSmoothedX.toDouble() - lastSentHighlightPointX,
                highlightPointSmoothedY.toDouble() - lastSentHighlightPointY
            )
            
            // 只有位移超过 8% (0.08) 或者这是初始化后的第一帧，才更新测光区域并触发预览
            // 这样可以避免微小的坐标抖动导致 AE 系统频繁重算测光
            if (dist > 0.08 || lastSentHighlightPointX < 0) {
                lastSentHighlightPointX = highlightPointSmoothedX
                lastSentHighlightPointY = highlightPointSmoothedY
                
                applyMeteringRegions()
                updatePreview()
            }
        }
    }

    /**
     * 根据当前测光模式设置 AE 区域。
     *
     * 系统默认模式通常交由 Camera2/设备决定；Position Lock 例外：默认 AE
     * 被限制在稳定预览实际可见的裁切区域内，避免画面外的传感器内容影响曝光。
     * 点测光和高光优先模式设置加权区域；平均测光模式设置整个可见预览区域。
     */
    private fun applyMeteringRegions() {
        val builder = previewRequestBuilder ?: return
        val currentState = _state.value
        val selectedMode = currentState.effectiveMeteringMode
        val hasManualFocusPoint = currentState.focusPoint != null &&
            currentState.focusPointSource == FocusPointSource.MANUAL
        // In system-default mode, a tap-to-focus point must also become the AE metering point.
        // Explicit user-selected metering modes retain their existing behavior.
        val mode = if (selectedMode == MeteringMode.SYSTEM_DEFAULT && hasManualFocusPoint) {
            MeteringMode.SPOT
        } else {
            selectedMode
        }
        if (mode == MeteringMode.SYSTEM_DEFAULT) {
            if (
                _topOffPositionLockActive.value &&
                currentState.captureMode == CaptureMode.VIDEO &&
                maxAeRegions > 0
            ) {
                // Default AE must meter only the frame visible after Position Lock's
                // virtual-camera crop. Leaving this unset would make the HAL expose for
                // off-screen sensor content and visibly shift brightness in the crop.
                val visiblePreview = mapPreviewMeteringPoint(0.5f, 0.5f, null)
                    ?.visibleRegion
                if (visiblePreview != null) {
                    builder.set(
                        CaptureRequest.CONTROL_AE_REGIONS,
                        arrayOf(
                            MeteringRectangle(
                                visiblePreview.toAndroidRect(),
                                MeteringRectangle.METERING_WEIGHT_MAX,
                            )
                        ),
                    )
                    PLog.d(
                        TAG,
                        "Position Lock default AE follows visible preview crop: " +
                            "region=$visiblePreview zoom=${_topOffZoomFactor.value}",
                    )
                } else {
                    clearCustomAeRegions(builder)
                }
            } else {
                clearCustomAeRegions(builder)
            }
            return
        }
        if (maxAeRegions <= 0) return
        try {
            val point = if (mode == MeteringMode.HIGHLIGHT_PRIORITY && highlightPointInitialized) {
                highlightPointSmoothedX to highlightPointSmoothedY
            } else {
                currentState.meteringReferencePoint ?: (0.5f to 0.5f)
            }
            val mapping = mapPreviewMeteringPoint(point.first, point.second, pointMeteringViewAspectRatio)
                ?: return
            val rect = if (mode == MeteringMode.AVERAGE) {
                mapping.visibleRegion
            } else {
                val fraction = when (mode) {
                    MeteringMode.SPOT -> SPOT_AE_REGION_FRACTION
                    MeteringMode.CENTER_WEIGHTED -> CENTER_WEIGHTED_AE_REGION_FRACTION
                    MeteringMode.HIGHLIGHT_PRIORITY -> HIGHLIGHT_AE_REGION_FRACTION
                    MeteringMode.SYSTEM_DEFAULT -> return
                }
                PreviewMeteringMapper.buildCenteredRegion(mapping, fraction)
            }
            builder.set(
                CaptureRequest.CONTROL_AE_REGIONS,
                arrayOf(MeteringRectangle(rect.toAndroidRect(), MeteringRectangle.METERING_WEIGHT_MAX))
            )
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to apply metering regions", e)
        }
    }

    // AF 和 AE 共享预览裁切、前摄镜像和变焦坐标映射。
    private fun mapPreviewMeteringPoint(
        normX: Float,
        normY: Float,
        previewViewAspectRatio: Float?,
    ): PreviewMeteringMapping? {
        val builder = previewRequestBuilder ?: return null
        val openCameraId = getActiveOpenCameraId().takeIf { it.isNotEmpty() } ?: return null
        val characteristics = resolveZoomRequestCharacteristics(openCameraId)
        val activeRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val zoomRatioRange = characteristics.readMetadataOrNull(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        val zoomMode = if (shouldUseControlZoomRatio(zoomRatioRange)) {
            PreviewMeteringZoomMode.POST_ZOOM_ACTIVE_ARRAY
        } else {
            PreviewMeteringZoomMode.SCALER_CROP_REGION
        }
        val requestedScalerCrop = if (zoomMode == PreviewMeteringZoomMode.SCALER_CROP_REGION) {
            builder.get(CaptureRequest.SCALER_CROP_REGION)
                ?: buildCenteredCropRegion(activeRect, _state.value.zoomRatio)
        } else {
            null
        }
        return PreviewMeteringMapper.mapPoint(
            normalizedX = normX,
            normalizedY = normY,
            activeArray = activeRect.toCameraCoordinateRect(),
            scalerCropRegion = requestedScalerCrop?.toCameraCoordinateRect(),
            zoomMode = zoomMode,
            previewViewAspectRatio = previewViewAspectRatio?.takeIf { it.isFinite() && it > 0f }
                ?: _state.value.getPreviewAspectRatio(),
            sensorOrientationDegrees = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
                ?: getSensorOrientation(),
            isFrontFacing = (characteristics.get(CameraCharacteristics.LENS_FACING) ?: getLensFacing()) ==
                CameraCharacteristics.LENS_FACING_FRONT,
            previewCropZoom = if (
                _topOffPositionLockActive.value &&
                _state.value.captureMode == CaptureMode.VIDEO
            ) _topOffZoomFactor.value else 1f,
        )
    }

    /** 拖动测光圈仅更新 AE，保留原有 AF 区域与镜头位置。 */
    fun moveMeteringPoint(normX: Float, normY: Float, viewAspectRatio: Float) {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post { moveMeteringPoint(normX, normY, viewAspectRatio) }
            return
        }
        val currentState = _state.value
        if (!normX.isFinite() || !normY.isFinite() || !viewAspectRatio.isFinite() || viewAspectRatio <= 0f) return
        if (isCaptureFocusFrozen || currentState.isCapturing || !currentState.supportsPointMetering) return
        if (previewRequestBuilder == null || currentState.focusPoint == null ||
            currentState.focusPointSource != FocusPointSource.MANUAL) return
        val point = normX.coerceIn(0f, 1f) to normY.coerceIn(0f, 1f)
        pointMeteringViewAspectRatio = viewAspectRatio
        _state.value = currentState.copy(meteringPoint = point)
        if (currentState.meteringPoint == null) {
            PLog.d(TAG, "Separate metering point: focus=${currentState.focusPoint} metering=$point")
        }
        applyMeteringRegions()
        updatePreview()
    }

    fun mergeMeteringPoint() {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post { mergeMeteringPoint() }
            return
        }
        val currentState = _state.value
        if (isCaptureFocusFrozen || currentState.isCapturing || currentState.meteringPoint == null) return
        _state.value = currentState.copy(meteringPoint = null)
        applyMeteringRegions()
        updatePreview()
        PLog.d(TAG, "Merge metering point into focus=${currentState.focusPoint}")
    }

    private fun clearCustomAeRegions(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_AE_REGIONS, null)
    }

    private fun clearCustomAfRegions(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_AF_REGIONS, null)
    }

    /**
     * 将色温(Kelvin)转换为 RggbChannelVector
     *
     * 先估算光源 RGB，再取倒数作为 RAW Bayer 通道补偿增益。
     *
     * @param kelvin 色温值
     * @return RggbChannelVector 白平衡增益
     */
    private fun kelvinToRggbGains(kelvin: Int): RggbChannelVector {
        val illuminant = kelvinToNormalizedRgb(kelvin)
        val redGain = 1f / illuminant.red.coerceAtLeast(1e-3f)
        val greenGain = 1f / illuminant.green.coerceAtLeast(1e-3f)
        val blueGain = 1f / illuminant.blue.coerceAtLeast(1e-3f)
        val minGain = minOf(redGain, greenGain, blueGain).coerceAtLeast(1e-3f)

        return RggbChannelVector(
            (redGain / minGain).coerceIn(1f, 4f),
            (greenGain / minGain).coerceIn(1f, 4f),
            (greenGain / minGain).coerceIn(1f, 4f),
            (blueGain / minGain).coerceIn(1f, 4f)
        )
    }

    private fun resolveManualMatrixGains(
        targetKelvin: Int,
        anchor: ManualWhiteBalanceAnchor,
        frozenGains: RggbChannelVector
    ): RggbChannelVector {
        if (abs(targetKelvin - anchor.baseTemperature) <= 25) {
            return frozenGains
        }

        val baseGains = kelvinToRggbGains(anchor.baseTemperature)
        val targetGains = kelvinToRggbGains(targetKelvin)
        return RggbChannelVector(
            scaleFrozenWhiteBalanceGain(frozenGains.red, targetGains.red, baseGains.red),
            scaleFrozenWhiteBalanceGain(frozenGains.greenEven, targetGains.greenEven, baseGains.greenEven),
            scaleFrozenWhiteBalanceGain(frozenGains.greenOdd, targetGains.greenOdd, baseGains.greenOdd),
            scaleFrozenWhiteBalanceGain(frozenGains.blue, targetGains.blue, baseGains.blue)
        )
    }

    private fun scaleFrozenWhiteBalanceGain(
        frozenGain: Float,
        targetGain: Float,
        baseGain: Float
    ): Float {
        return (frozenGain * targetGain / baseGain.coerceAtLeast(1e-3f)).coerceAtLeast(1f)
    }

    private fun estimateKelvinFromRggbGains(gains: RggbChannelVector): Int {
        val range = resolveAwbTemperatureRange()
        val lower = range.lower.coerceAtLeast(1000)
        val upper = range.upper.coerceAtLeast(lower)
        val targetRed = gains.red.coerceAtLeast(1e-3f)
        val targetGreen = ((gains.greenEven + gains.greenOdd) / 2f).coerceAtLeast(1e-3f)
        val targetBlue = gains.blue.coerceAtLeast(1e-3f)
        var bestKelvin = lower
        var bestDistance = Double.MAX_VALUE

        var kelvin = lower
        while (kelvin <= upper) {
            val candidate = kelvinToRggbGains(kelvin)
            val candidateGreen = ((candidate.greenEven + candidate.greenOdd) / 2f).coerceAtLeast(1e-3f)
            val distance =
                abs(ln((candidate.red / targetRed).toDouble())) +
                        abs(ln((candidateGreen / targetGreen).toDouble())) * 0.5 +
                        abs(ln((candidate.blue / targetBlue).toDouble()))
            if (distance < bestDistance) {
                bestDistance = distance
                bestKelvin = kelvin
            }
            kelvin += 50
        }

        return (bestKelvin / 50f).roundToInt() * 50
    }

    private fun kelvinToNormalizedRgb(kelvin: Int): NormalizedRgb {
        val temperature = kelvin.coerceIn(1000, 40000) / 100.0f

        var red: Float
        var green: Float
        var blue: Float

        // 计算红色分量
        if (temperature <= 66) {
            red = 255f
        } else {
            red = (329.698727446 * (temperature - 60.0).pow(-0.1332047592)).toFloat()
            red = red.coerceIn(0f, 255f)
        }

        // 计算绿色分量
        if (temperature <= 66) {
            green = (99.4708025861 * ln(temperature.toDouble()) - 161.1195681661).toFloat()
        } else {
            green = (288.1221695283 * (temperature - 60.0).pow(-0.0755148492)).toFloat()
        }
        green = green.coerceIn(0f, 255f)

        // 计算蓝色分量
        if (temperature >= 66) {
            blue = 255f
        } else if (temperature <= 19) {
            blue = 0f
        } else {
            blue = (138.5177312231 * ln((temperature - 10).toDouble()) - 305.0447927307).toFloat()
            blue = blue.coerceIn(0f, 255f)
        }

        return NormalizedRgb(
            red = (red / 255f).coerceIn(0f, 1f),
            green = (green / 255f).coerceIn(0f, 1f),
            blue = (blue / 255f).coerceIn(0f, 1f)
        )
    }

    /**
     * 更新预览
     */
    private fun resolveHighSpeed120FpsRange(): Range<Int>? {
        val state = _state.value
        val inputSize = state.videoCapabilities.cameraInputSizesByResolution[state.videoConfig.resolution]
            ?: return null
        val characteristics = getActiveOpenCameraCharacteristics() ?: return null
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return null
        return runCatching {
            streamMap.getHighSpeedVideoFpsRangesFor(inputSize).firstOrNull { range ->
                range.lower <= 120 && range.upper >= 120
            }
        }.getOrNull()
    }

    private fun submitRepeatingPreviewRequest(
        session: CameraCaptureSession,
        builder: CaptureRequest.Builder
    ) {
        submitRepeatingPreviewRequest(session, builder.build())
    }

    private fun submitRepeatingPreviewRequest(
        session: CameraCaptureSession,
        request: CaptureRequest
    ) {
        if (session is CameraConstrainedHighSpeedCaptureSession) {
            val requests = session.createHighSpeedRequestList(request)
            session.setRepeatingBurst(requests, previewCallback, cameraHandler)
        } else {
            session.setRepeatingRequest(request, previewCallback, cameraHandler)
        }
    }

    private fun updatePreview() {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            if (!previewUpdateScheduled.compareAndSet(false, true)) return
            handler.post {
                previewUpdateScheduled.set(false)
                updatePreview()
            }
            return
        }

        // The flash sequence owns repeating requests until the still capture is finished.
        // Parameter setters may update the preview builder; apply them when restoring preview.
        if (stillFlashCaptureState != null) return

        // 检查相机和会话是否仍然有效，避免在相机关闭后的回调中调用 setRepeatingRequest。
        val device = cameraDevice
        val session = captureSession
        val builder = previewRequestBuilder

        if (device == null || session == null || builder == null) {
            PLog.v(TAG, "updatePreview: camera not ready (device=$device, session=$session, builder=$builder)")
            return
        }

        try {
            applyFrozenCaptureFocus(builder)
            submitRepeatingPreviewRequest(session, builder)
        } catch (e: CameraAccessException) {
            PLog.e(TAG, "Failed to update preview", e)
        } catch (e: IllegalStateException) {
            // 相机已关闭或处于错误状态
            PLog.w(TAG, "Failed to update preview - camera closed or in error state", e)
        } catch (e: IllegalArgumentException) {
            PLog.w(TAG, "Failed to update preview - request settings error", e)
        }
    }

// ==================== 变焦控制 ====================

    /**
     * 设置变焦倍数
     * 注意：优先使用 Camera2 CONTROL_ZOOM_RATIO；设备不支持时才回退到 SCALER_CROP_REGION。
     */
    fun setZoomRatio(ratio: Float) {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post {
                setZoomRatio(ratio)
            }
            return
        }
        val stateBeforeZoomRequest = _state.value
        val previousLogicalZoomRatio = getTargetZoomRatioByMain(
            stateBeforeZoomRequest,
            stateBeforeZoomRequest.getCurrentCameraInfo(),
        )
        val requestedRatio = if (
            spatialVideoEnabled || (dualCaptureEnabled && _dualCaptureSource.value == DualCaptureSource.ULTRA_WIDE)
        ) 1f else sanitizeZoomRatio(ratio)
        _state.value = _state.value.copy(
            zoomRatio = requestedRatio,
            zoomPanX = if (requestedRatio <= 1.001f) 0f else _state.value.zoomPanX,
            zoomPanY = if (requestedRatio <= 1.001f) 0f else _state.value.zoomPanY,
            zoomPanEnabled = requestedRatio > 1.001f && _state.value.zoomPanEnabled,
        )
        val builder = previewRequestBuilder ?: return
        val openCameraId = activeOpenCameraId.takeIf { it.isNotEmpty() } ?: return
        val selectedOpenCameraId = _state.value.getCurrentCameraInfo()?.let {
            runCatching { it.getOpenCameraId() }.getOrNull()
        }
        if (selectedOpenCameraId != null && selectedOpenCameraId != openCameraId) {
            // The selected lens belongs to another CameraDevice. Keep the requested zoom
            // in CameraState, but never submit it to the old device while its close/handoff
            // is pending. The replacement session applies this value during configuration.
            PLog.d(
                TAG,
                "Deferring zoom request until camera handoff: selected=$selectedOpenCameraId active=$openCameraId"
            )
            return
        }

        try {
            val characteristics = resolveZoomRequestCharacteristics(openCameraId)
            val maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            val zoomRatioRange = characteristics.readMetadataOrNull(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            val minZoom = zoomRatioRange?.lower ?: 1f
            val maxSupportedZoom = zoomRatioRange?.upper ?: maxZoom
            val selectedCamera = _state.value.getCurrentCameraInfo()
            val logicalAutoSwitch = shouldUseLogicalMultiCameraZoom(_state.value, selectedCamera)
            val requestedDeviceZoomRatio = if (logicalAutoSwitch) {
                getTargetZoomRatioByMain(_state.value, selectedCamera)
            } else {
                requestedRatio
            }
            val clampedDeviceZoomRatio = requestedDeviceZoomRatio.coerceIn(minZoom, maxSupportedZoom)
            val topOffZoomActive = _state.value.captureMode == CaptureMode.VIDEO &&
                _topOffZoomEnabled.value &&
                !logicalAutoSwitch &&
                !spatialVideoEnabled &&
                !dualCaptureEnabled &&
                clampedDeviceZoomRatio >= 1f
            val requestZoomRatio = if (topOffZoomActive) {
                updateTopOffZoomLockedRatio(
                    clampedDeviceZoomRatio * _topOffZoomFactor.value,
                    "manual-zoom-selection",
                )
            } else {
                clampedDeviceZoomRatio
            }
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
                ?: selectedCamera?.sensorOrientation ?: 0
            val cropAspectRatio = ZoomCropGeometry.sensorCoordinateAspectRatio(
                _state.value.getPreviewAspectRatio(),
                sensorOrientation,
            )
            val topOffPan = if (topOffZoomActive) {
                resolveTopOffZoomPan(
                    selectedCamera,
                    _topOffZoomPanX.value,
                    _topOffZoomPanY.value,
                    sensorOrientation,
                )
            } else {
                null
            }
            val intrinsicZoomRatio = selectedCamera?.intrinsicZoomRatio
                ?.takeIf { it.isFinite() && it > 0f } ?: 1f
            val clampedStateZoomRatio = if (logicalAutoSwitch) {
                clampedDeviceZoomRatio / intrinsicZoomRatio
            } else {
                clampedDeviceZoomRatio
            }

            val stateBeforeClamp = _state.value
            val keepZoomPan = !logicalAutoSwitch && clampedStateZoomRatio > 1.001f && stateBeforeClamp.zoomPanEnabled
            _state.value = stateBeforeClamp.copy(
                zoomRatio = clampedStateZoomRatio,
                zoomPanX = if (keepZoomPan) stateBeforeClamp.zoomPanX else 0f,
                zoomPanY = if (keepZoomPan) stateBeforeClamp.zoomPanY else 0f,
                zoomPanEnabled = keepZoomPan,
            )
            if (videoRecorder.isRecording()) {
                val softwareZoom = if (topOffZoomActive) requestZoomRatio else 1f
                val softwarePanX = if (topOffZoomActive) _topOffZoomPanX.value else 0f
                val softwarePanY = if (topOffZoomActive) _topOffZoomPanY.value else 0f
                val softwareRoll = if (topOffZoomActive) _topOffZoomRollDegrees.value else 0f
                videoRecorder.updateCameraInputCrop(softwareZoom, softwarePanX, softwarePanY, softwareRoll)
                if (dualAspectRecordingActive && spatialVideoRecorder.isRecording()) {
                    spatialVideoRecorder.updateCameraInputCrop(softwareZoom, softwarePanX, softwarePanY, softwareRoll)
                }
            }
            if (recreateSessionForPhysicalZoomIfNeeded(_state.value)) {
                PLog.d(TAG, "setZoomRatio: $ratio -> $clampedDeviceZoomRatio (physical output session recreated)")
                return
            }

            val activeRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            builder.apply {
                applyZoomRequestSettings(
                    builder = this,
                    zoomRatio = requestZoomRatio,
                    activeRect = activeRect,
                    zoomRatioRange = zoomRatioRange,
                    resetCropAtUnitZoom = true,
                    panX = topOffPan?.first ?: _state.value.zoomPanX,
                    panY = topOffPan?.second ?: _state.value.zoomPanY,
                    useSoftwareCrop = topOffZoomActive,
                useFreeformPan = !topOffZoomActive && (
                        !logicalAutoSwitch && _state.value.zoomPanEnabled &&
                            _state.value.getCurrentCameraInfo()?.supportsFreeformZoomPan == true
                    ),
                    cropAspectRatio = cropAspectRatio,
                )
            }
            if (topOffZoomActive) {
                logTopOffZoomRequest(
                    baseZoomRatio = clampedDeviceZoomRatio,
                    lockedZoomRatio = requestZoomRatio,
                    panX = topOffPan?.first ?: _state.value.zoomPanX,
                    panY = topOffPan?.second ?: _state.value.zoomPanY,
                    activeRect = activeRect,
                    cropAspectRatio = cropAspectRatio,
                    sensorOrientation = sensorOrientation,
                    softwareCrop = topOffZoomActive,
                )
            }
            if (logicalAutoSwitch && previousLogicalZoomRatio > 0.85f &&
                clampedDeviceZoomRatio <= 0.85f
            ) {
                pendingUltraWideAeHandoff = latestUltraWideAeReading?.takeIf { reading ->
                    reading.physicalCameraId == ultraWideAeMeteringReaderPhysicalCameraId &&
                        SystemClock.elapsedRealtime() - reading.sampledAtElapsedRealtimeMs in 0L..ULTRA_WIDE_AE_HANDOFF_MAX_AGE_MS
                }
                applyPendingUltraWideAeHandoff(builder)
            }
            if (_state.value.meteringPoint != null) {
                applyMeteringRegions()
            }
            if (cameraDevice != null && captureSession != null) {
                updatePreview()
            }

            val zoomMode = if (shouldUseControlZoomRatio(zoomRatioRange)) {
                "CONTROL_ZOOM_RATIO"
            } else {
                "SCALER_CROP_REGION"
            }
            val applyTiming = if (captureSession == null) "builder-only" else "preview"
            PLog.d(
                TAG,
                "setZoomRatio: $ratio -> $clampedDeviceZoomRatio " +
                    "(logicalAutoSwitch=$logicalAutoSwitch, $zoomMode, $applyTiming)"
            )

        } catch (e: Exception) {
            PLog.e(TAG, "Failed to set zoom", e)
        }
    }

    /**
     * Move the active digital-zoom crop without moving the phone.
     *
     * Crop changes are applied to the Camera2 repeating request and reused by
     * still/video capture requests through applyBaseCameraSettings().
     */
    fun panZoomBy(deltaX: Float, deltaY: Float) {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post { panZoomBy(deltaX, deltaY) }
            return
        }
        if (!deltaX.isFinite() || !deltaY.isFinite()) return

        val current = _state.value
        val cameraInfo = current.getCurrentCameraInfo()
        if (current.zoomRatio <= 1.001f || cameraInfo?.supportsFreeformZoomPan != true) {
            return
        }

        // Use the orientation/facing of the sensor that actually receives the
        // SCALER_CROP_REGION request. On logical multi-camera devices this can differ
        // from CameraInfo's logical-camera metadata (notably on the 1x main sensor),
        // which otherwise rotates or reverses the joystick's vertical movement.
        val openCameraId = getActiveOpenCameraId()
        val cropCharacteristics = openCameraId.takeIf { it.isNotEmpty() }?.let { id ->
            runCatching { resolveZoomRequestCharacteristics(id) }.getOrNull()
        }
        val cropSensorOrientation = cropCharacteristics
            ?.get(CameraCharacteristics.SENSOR_ORIENTATION)
            ?: cameraInfo.sensorOrientation
        val cropLensFacing = cropCharacteristics
            ?.get(CameraCharacteristics.LENS_FACING)
            ?: cameraInfo.lensFacing

        // Joystick deltas are expressed in displayed viewfinder axes, whereas
        // SCALER_CROP_REGION uses the active sensor's unrotated coordinates.
        val (sensorDeltaX, sensorDeltaY) = when (Math.floorMod(cropSensorOrientation, 360)) {
            90 -> deltaY to -deltaX
            180 -> -deltaX to -deltaY
            270 -> -deltaY to deltaX
            else -> deltaX to deltaY
        }
        val sensorDeltaXForFacing = if (
            cropLensFacing == CameraCharacteristics.LENS_FACING_FRONT
        ) -sensorDeltaX else sensorDeltaX

        val next = current.copy(
            zoomPanX = (current.zoomPanX + sensorDeltaXForFacing).coerceIn(-1f, 1f),
            zoomPanY = (current.zoomPanY + sensorDeltaY).coerceIn(-1f, 1f),
            zoomPanEnabled = true,
        )
        if (next.zoomPanX == current.zoomPanX &&
            next.zoomPanY == current.zoomPanY &&
            current.zoomPanEnabled
        ) {
            return
        }
        _state.value = next
        val builder = previewRequestBuilder ?: return
        builder.apply {
            applyBaseCameraSettings(this, isCapture = false, currentState = next)
            updatePreview()
        }
        PLog.d(TAG, "Zoom joystick pan: x=${next.zoomPanX}, y=${next.zoomPanY}, zoom=${next.zoomRatio}")
    }

    private fun sanitizeZoomRatio(ratio: Float): Float {
        return if (ratio.isFinite()) ratio.coerceAtLeast(0.01f) else 1f
    }

    private fun recreateSessionForPhysicalZoomIfNeeded(state: CameraState): Boolean {
        val desiredPhysicalCameraId = resolveOutputPhysicalCameraId(state)
        if (desiredPhysicalCameraId == activeOutputPhysicalCameraId) return false
        if (cameraDevice == null || previewSurface == null) return false

        val openCameraId = getActiveOpenCameraId()
        val openCharacteristics = getCameraCharacteristicsOrNull(
            openCameraId,
            "physical zoom stabilization timing",
        ) ?: run {
            PLog.e(TAG, "Cannot update EIS calibration: open camera characteristics unavailable")
            return false
        }
        val requestedPhysicalCameraId = desiredPhysicalCameraId
            ?.takeIf { it != openCameraId }
        val physicalCharacteristics = requestedPhysicalCameraId?.let {
            getCameraCharacteristicsOrNull(it, "physical zoom stabilization calibration")
        }
        // Match the session's existing safe fallback: if a physical sensor's metadata cannot
        // be read, use the logical output instead of keeping the previous lens calibration.
        val resolvedPhysicalCameraId = requestedPhysicalCameraId
            ?.takeIf { physicalCharacteristics != null }
        if (requestedPhysicalCameraId != null && resolvedPhysicalCameraId == null) {
            PLog.w(
                TAG,
                "Physical output calibration unavailable for $requestedPhysicalCameraId; " +
                    "falling back to logical camera $openCameraId",
            )
        }
        val stabilizationCharacteristics = physicalCharacteristics ?: openCharacteristics
        val stabilizationCameraId = resolvedPhysicalCameraId ?: openCameraId

        pendingUltraWideAeHandoff = latestUltraWideAeReading?.takeIf { reading ->
            reading.physicalCameraId == resolvedPhysicalCameraId &&
                resolvedPhysicalCameraId == ultraWideAeMeteringReaderPhysicalCameraId &&
                _state.value.isAutoExposure &&
                SystemClock.elapsedRealtime() - reading.sampledAtElapsedRealtimeMs in 0L..ULTRA_WIDE_AE_HANDOFF_MAX_AGE_MS
        }
        PLog.i(
            TAG,
            "Recreating session for zoom physical output: " +
                    "old=$activeOutputPhysicalCameraId, new=$resolvedPhysicalCameraId, " +
                    "targetZoom=${getTargetZoomRatioByMain(state, state.getCurrentCameraInfo())}"
        )

        // Retire the old session before changing the coordinator's camera calibration. Its
        // late CaptureResults must never be tagged with the new active physical-camera ID.
        safeCloseCaptureSession(captureSession, "physical zoom output changed")
        captureSession = null
        _enginePreviewSource.value = null
        activeOutputPhysicalCameraId = resolvedPhysicalCameraId

        // Flush timestamped YUV images still queued by the previous sensor before the new
        // calibration starts accepting CaptureResults and image boundaries.
        stabilizationImageReader?.let { reader ->
            realtimeStabilizationCoordinator.discardStabilizationImages(reader)
            reader.discardPendingImages()
        }

        val stabilizationCameraChanged = realtimeStabilizationCoordinator.configureCamera(
            cameraId = stabilizationCameraId,
            characteristics = stabilizationCharacteristics,
            timingCharacteristics = openCharacteristics,
        )
        if (_state.value.videoRecordingState.isRecording) {
            videoRecorder.onCameraSwitched(
                sensorOrientationDegrees = stabilizationCharacteristics.get(
                    CameraCharacteristics.SENSOR_ORIENTATION,
                ) ?: cachedSensorOrientation,
                frontFacing = isCurrentCameraFrontFacing(),
                restartStabilization = stabilizationCameraChanged,
            )
        }

        refreshActiveFocusLimit()
        refreshHyperfocalFocusDistanceIfEnabled(updatePreview = false)
        createPreviewSession(openGeneration = cameraOpenGeneration)
        return true
    }

    /** Reset joystick panning to the centred crop. Safe to call repeatedly. */
    fun resetZoomPan() {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post { resetZoomPan() }
            return
        }
        val current = _state.value
        if (!current.zoomPanEnabled && current.zoomPanX == 0f && current.zoomPanY == 0f) return
        val next = current.copy(zoomPanX = 0f, zoomPanY = 0f, zoomPanEnabled = false)
        _state.value = next
        val selectedOpenCameraId = current.getCurrentCameraInfo()?.let {
            runCatching { it.getOpenCameraId() }.getOrNull()
        }
        if (selectedOpenCameraId != null && selectedOpenCameraId != activeOpenCameraId) {
            return
        }
        previewRequestBuilder?.apply {
            applyBaseCameraSettings(this, isCapture = false, currentState = next)
            updatePreview()
        }
    }

    /**
     * 设置自动对焦开关
     */
    fun setAutoFocus(auto: Boolean) {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post { setAutoFocus(auto) }
            return
        }
        clearHyperfocalFocusMemory()
        clearPointFocusState()
        _state.value = _state.value.copy(
            isAutoFocus = auto,
            isHyperfocalFocusEnabled = false,
            hyperfocalDistanceMeters = 0f,
            isFocusLocked = false
        )
        if (!isCaptureFocusFrozen) {
            previewRequestBuilder?.apply {
                clearCustomAfRegions(this)
                applyFocusSettings(this, _state.value)
                applyMeteringRegions()
                updatePreview()
            }
        } else {
            PLog.d(TAG, "Auto-focus mode update deferred until multi-frame capture completes")
        }
    }

    /**
     * 设置对焦距离 (0.0 ~ minimumFocusDistance)
     */
    fun setFocusDistance(distance: Float) {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post { setFocusDistance(distance) }
            return
        }
        val minFocusDistance = _state.value.minimumFocusDistance
        if (minFocusDistance <= 0) return

        val clampedDistance = distance.coerceIn(0f, minFocusDistance)
        clearHyperfocalFocusMemory()
        _state.value = _state.value.copy(
            focusDistance = clampedDistance,
            isHyperfocalFocusEnabled = false,
            hyperfocalDistanceMeters = 0f,
            isFocusLocked = false
        )

        if (!_state.value.isAutoFocus && !isCaptureFocusFrozen) {
            previewRequestBuilder?.apply {
                applyFocusSettings(this, _state.value)
                updatePreview()
            }
        } else if (isCaptureFocusFrozen) {
            PLog.d(TAG, "Manual focus-distance update deferred until multi-frame capture completes")
        }
    }

    fun setHyperfocalFocusEnabled(enabled: Boolean) {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post { setHyperfocalFocusEnabled(enabled) }
            return
        }
        if (enabled) {
            applyHyperfocalFocus(storePreviousFocus = true, updatePreview = true)
        } else {
            restoreFocusBeforeHyperfocal(updatePreview = true)
        }
    }

    private fun refreshHyperfocalFocusDistanceIfEnabled(updatePreview: Boolean) {
        if (!_state.value.isHyperfocalFocusEnabled) return
        applyHyperfocalFocus(storePreviousFocus = false, updatePreview = updatePreview)
    }

    private fun applyHyperfocalFocus(
        storePreviousFocus: Boolean,
        updatePreview: Boolean
    ): Boolean {
        refreshActiveFocusLimit()

        val minFocusDistance = _state.value.minimumFocusDistance
        if (minFocusDistance <= 0f) {
            PLog.w(TAG, "Hyperfocal focus unavailable: manual focus is not supported")
            if (_state.value.isHyperfocalFocusEnabled) {
                restoreFocusBeforeHyperfocal(updatePreview = updatePreview)
            }
            return false
        }

        val result = calculateHyperfocalFocusResult()
        if (result == null) {
            PLog.w(TAG, "Hyperfocal focus unavailable: unable to resolve physical camera optics")
            if (_state.value.isHyperfocalFocusEnabled) {
                restoreFocusBeforeHyperfocal(updatePreview = updatePreview)
            }
            return false
        }

        if (storePreviousFocus && !_state.value.isHyperfocalFocusEnabled) {
            focusModeBeforeHyperfocal = _state.value.isAutoFocus
            focusDistanceBeforeHyperfocal = _state.value.focusDistance
        }

        val clampedFocusDistance = result.focusDistanceDiopters.coerceIn(0f, minFocusDistance)
        clearPointFocusState()
        _state.value = _state.value.copy(
            isAutoFocus = false,
            focusDistance = clampedFocusDistance,
            isHyperfocalFocusEnabled = true,
            hyperfocalDistanceMeters = result.distanceMeters,
            isFocusLocked = false
        )

        if (updatePreview && !isCaptureFocusFrozen) {
            previewRequestBuilder?.apply {
                clearCustomAfRegions(this)
                applyFocusSettings(this, _state.value)
                applyMeteringRegions()
                updatePreview()
            }
        }

        PLog.i(
            TAG,
            "Hyperfocal focus enabled: camera=${result.cameraId}, " +
                    "f=${result.focalLengthMm}mm, N=${result.aperture}, " +
                    "c=${result.circleOfConfusionMm}mm, H=${result.distanceMeters}m, " +
                    "focus=${clampedFocusDistance}D"
        )
        return true
    }

    private fun restoreFocusBeforeHyperfocal(updatePreview: Boolean) {
        val restoreAutoFocus = focusModeBeforeHyperfocal ?: true
        val restoreFocusDistance = focusDistanceBeforeHyperfocal
            ?.coerceIn(0f, _state.value.minimumFocusDistance.takeIf { it > 0f } ?: Float.MAX_VALUE)
            ?: _state.value.focusDistance

        clearHyperfocalFocusMemory()
        clearPointFocusState()
        _state.value = _state.value.copy(
            isAutoFocus = restoreAutoFocus,
            focusDistance = restoreFocusDistance,
            isHyperfocalFocusEnabled = false,
            hyperfocalDistanceMeters = 0f,
            isFocusLocked = false
        )

        if (updatePreview && !isCaptureFocusFrozen) {
            previewRequestBuilder?.apply {
                clearCustomAfRegions(this)
                applyFocusSettings(this, _state.value)
                applyMeteringRegions()
                updatePreview()
            }
        }

        PLog.i(TAG, "Hyperfocal focus disabled: restoreAutoFocus=$restoreAutoFocus")
    }

    private fun clearHyperfocalFocusMemory() {
        focusModeBeforeHyperfocal = null
        focusDistanceBeforeHyperfocal = null
    }

    private fun calculateHyperfocalFocusResult(): HyperfocalFocusResult? {
        val (cameraId, characteristics) = resolveActiveFocusCharacteristics() ?: return null

        val focalLengthMm = characteristics
            .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull()
            ?.takeIf { it.isFinite() && it > 0f }
            ?: DEFAULT_HYPERFOCAL_FOCAL_LENGTH_MM

        val aperture = characteristics
            .get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
            ?.firstOrNull()
            ?.takeIf { it.isFinite() && it > 0f }
            ?: DEFAULT_HYPERFOCAL_APERTURE

        val physicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?: return null
        val diagonal = hypot(
            physicalSize.width.toDouble(),
            physicalSize.height.toDouble()
        )
        if (!diagonal.isFinite() || diagonal <= 0.0) return null

        val circleOfConfusionMm = (diagonal / HYPERFOCAL_COC_DIAGONAL_DIVISOR).toFloat()
        if (!circleOfConfusionMm.isFinite() || circleOfConfusionMm <= 0f) return null

        val hyperfocalDistanceMm =
            (focalLengthMm * focalLengthMm) / (aperture * circleOfConfusionMm) + focalLengthMm
        val hyperfocalDistanceMeters = hyperfocalDistanceMm / 1000f
        if (!hyperfocalDistanceMeters.isFinite() || hyperfocalDistanceMeters <= 0f) return null

        return HyperfocalFocusResult(
            cameraId = cameraId,
            focalLengthMm = focalLengthMm,
            aperture = aperture,
            circleOfConfusionMm = circleOfConfusionMm,
            distanceMeters = hyperfocalDistanceMeters,
            focusDistanceDiopters = 1f / hyperfocalDistanceMeters
        )
    }

// ==================== 对焦控制 ====================

    private fun logFocusTerminalResult(result: CaptureResult, afState: Int) {
        PLog.d(
            TAG,
            "Focus terminal: afState=$afState " +
                "source=${_state.value.focusPointSource} " +
                "afRegions=${result.get(CaptureResult.CONTROL_AF_REGIONS)?.contentToString()} " +
                "zoom=${result.get(CaptureResult.CONTROL_ZOOM_RATIO)} " +
                "crop=${result.get(CaptureResult.SCALER_CROP_REGION)} " +
                "lensDistance=${result.get(CaptureResult.LENS_FOCUS_DISTANCE)}"
        )
    }

    private fun recordFocusLockExposure(result: CaptureResult) {
        updateFocusLockSceneReference(result)
        isFocusLockedWaitingForSceneChange = true
        focusLockSettleFrames = FOCUS_LOCK_SETTLE_FRAMES
        sceneChangeFrameCount = 0
    }

    private fun updateFocusLockSceneReference(result: CaptureResult) {
        focusLockedReferenceIso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
        focusLockedReferenceExposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
        focusLockedReferenceDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f
    }

    private fun applyPendingPointExposureLock(result: TotalCaptureResult) {
        if (!pendingPointExposureLock) return
        val currentState = _state.value
        if (!currentState.isFocusLocked) {
            pendingPointExposureLock = false
            return
        }
        if (currentState.isFocusing) return

        val builder = previewRequestBuilder ?: return
        val aeMode = builder.get(CaptureRequest.CONTROL_AE_MODE)
        if (aeMode == null || aeMode == CaptureRequest.CONTROL_AE_MODE_OFF) {
            // Manual exposure is already fixed by the user's ISO/shutter settings.
            pendingPointExposureLock = false
            return
        }

        val aeLockAvailable = getActiveOpenCameraCharacteristics()
            ?.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true
        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
        val exposureSettled = aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
            aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
            aeState == CaptureResult.CONTROL_AE_STATE_LOCKED
        val timedOut = SystemClock.elapsedRealtime() - pendingPointExposureLockStartedElapsedMs >=
            POINT_EXPOSURE_LOCK_TIMEOUT_MS
        if (!exposureSettled && !timedOut) return

        if (aeLockAvailable) {
            builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
            pendingPointExposureLock = false
            PLog.i(TAG, "Point focus locked: AE_LOCK=true, aeState=$aeState")
            updatePreview()
            return
        }

        // Some HALs do not expose AE_LOCK. If manual sensor control is available, freeze the
        // converged exposure values instead of leaving auto exposure free to drift.
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
        val exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        if (isManualSensorSupported &&
            availableAeModes.contains(CaptureRequest.CONTROL_AE_MODE_OFF) &&
            iso != null && iso > 0 && exposureTimeNs != null && exposureTimeNs > 0L
        ) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTimeNs)
            PLog.i(
                TAG,
                "Point focus locked with manual exposure fallback: ISO=$iso, exposureNs=$exposureTimeNs",
            )
            pendingPointExposureLock = false
            updatePreview()
        } else {
            PLog.w(TAG, "AE lock unavailable and manual exposure fallback unsupported")
            pendingPointExposureLock = false
        }
    }

    private fun restoreContinuousAf() {
        if (isCaptureFocusFrozen) {
            PLog.d(TAG, "Continuous AF restore deferred while multi-frame focus is frozen")
            return
        }
        clearPointFocusState()
        previewRequestBuilder?.apply {
            // Release both focus/exposure locks and restore the configured AE/AF modes.
            set(CaptureRequest.CONTROL_AE_LOCK, false)
            applyExposureSettings(this, _state.value, isCapture = false)
            applyFocusSettings(this, _state.value)
            clearCustomAfRegions(this)
            applyMeteringRegions()
            updatePreview()
        }
    }

    fun unlockFocus() {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post { unlockFocus() }
            return
        }
        PLog.d(TAG, "Unlock focus")
        restoreContinuousAf()
    }

    private fun clearPointFocusState() {
        pointMeteringViewAspectRatio = null
        isFocusLockedWaitingForSceneChange = false
        sceneChangeFrameCount = 0
        focusLockedReferenceIso = 0
        focusLockedReferenceExposureNs = 0L
        focusLockedReferenceDistance = 0f
        focusLockSettleFrames = 0
        eyeTargetLastSeenElapsedMs = 0L
        pendingPointExposureLock = false
        pendingPointExposureLockStartedElapsedMs = 0L
        _state.value = _state.value.copy(
            focusPoint = null,
            meteringPoint = null,
            focusPointSource = FocusPointSource.MANUAL,
            isFocusLocked = false,
            isFocusing = false,
            focusSuccess = null
        )
    }

    /**
     * 点击对焦
     */
    fun focusOnPoint(x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        focusOnNormalizedPoint(
            normX = x / viewWidth,
            normY = y / viewHeight,
            source = FocusPointSource.MANUAL,
            previewViewAspectRatio = viewWidth.toFloat() / viewHeight,
        )
    }

    fun lockFocusOnPoint(x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        PLog.d(TAG, "Lock focus on point: x=$x y=$y w=$viewWidth h=$viewHeight")
        focusOnNormalizedPoint(
            normX = x / viewWidth,
            normY = y / viewHeight,
            source = FocusPointSource.MANUAL,
            lockFocus = true,
            previewViewAspectRatio = viewWidth.toFloat() / viewHeight,
        )
    }

    /** 拖动中更新 AF 区域，松手后扫描一次；整个过程保持独立 AE 区域。 */
    fun moveFocusPoint(normX: Float, normY: Float, viewAspectRatio: Float, finished: Boolean) {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post { moveFocusPoint(normX, normY, viewAspectRatio, finished) }
            return
        }
        val currentState = _state.value
        if (currentState.meteringPoint == null || currentState.focusPointSource != FocusPointSource.MANUAL) return
        if (!normX.isFinite() || !normY.isFinite() || !viewAspectRatio.isFinite() || viewAspectRatio <= 0f) return
        focusOnNormalizedPoint(
            normX = normX,
            normY = normY,
            source = FocusPointSource.MANUAL,
            lockFocus = currentState.isFocusLocked,
            previewViewAspectRatio = viewAspectRatio,
            preserveMeteringPoint = true,
            triggerFocus = finished,
        )
    }

    fun focusOnNormalizedPoint(
        normX: Float,
        normY: Float,
        source: FocusPointSource,
        lockFocus: Boolean = false,
        previewViewAspectRatio: Float? = null,
        preserveMeteringPoint: Boolean = false,
        triggerFocus: Boolean = true,
    ) {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post {
                focusOnNormalizedPoint(
                    normX = normX,
                    normY = normY,
                    source = source,
                    lockFocus = lockFocus,
                    previewViewAspectRatio = previewViewAspectRatio,
                    preserveMeteringPoint = preserveMeteringPoint,
                    triggerFocus = triggerFocus,
                )
            }
            return
        }
        if (isCaptureFocusFrozen || _state.value.isCapturing) {
            PLog.d(TAG, "Ignoring focus update while capture focus is frozen")
            return
        }
        val autoFocusEnabled = _state.value.isAutoFocus
        if (!autoFocusEnabled && source == FocusPointSource.EYE) {
            PLog.d(TAG, "Ignoring eye-focus AF trigger while manual focus is active")
            return
        }
        if (
            source == FocusPointSource.EYE &&
            !_state.value.supportsPointAutoFocus
        ) {
            PLog.d(TAG, "Ignoring eye-focus AF trigger on a fixed-focus camera")
            return
        }
        if (
            source == FocusPointSource.EYE &&
            _state.value.focusPoint != null &&
            _state.value.focusPointSource == FocusPointSource.MANUAL
        ) {
            PLog.d(TAG, "Ignoring stale eye-focus update while manual point focus is active")
            return
        }
        val openCameraId = getActiveOpenCameraId()
        if (openCameraId.isEmpty()) return

        // 重置场景变化检测状态（新的对焦覆盖旧的）
        isFocusLockedWaitingForSceneChange = false
        sceneChangeFrameCount = 0
        if (source == FocusPointSource.MANUAL) {
            eyeTargetLastSeenElapsedMs = 0L
        } else {
            eyeTargetLastSeenElapsedMs = SystemClock.elapsedRealtime()
        }

        try {
            val builder = previewRequestBuilder ?: return
            val normalizedX = normX.coerceIn(0f, 1f)
            val normalizedY = normY.coerceIn(0f, 1f)
            val mapping = mapPreviewMeteringPoint(normalizedX, normalizedY, previewViewAspectRatio) ?: return
            if (source == FocusPointSource.MANUAL && !preserveMeteringPoint) {
                pointMeteringViewAspectRatio = previewViewAspectRatio
                // A new tap begins a fresh exposure measurement. Lock only after AE has settled
                // when the user is holding the point.
                builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
                pendingPointExposureLock = lockFocus
                pendingPointExposureLockStartedElapsedMs =
                    if (lockFocus) SystemClock.elapsedRealtime() else 0L
            }

            // Store the touch position for the on-screen indicator.
            _state.value = _state.value.copy(
                focusPoint = Pair(normalizedX, normalizedY),
                meteringPoint = if (preserveMeteringPoint) _state.value.meteringPoint else null,
                focusPointSource = source,
                isFocusLocked = lockFocus,
                isFocusing = triggerFocus && autoFocusEnabled,
                focusSuccess = null
            )
            // AF 区域保持为可见画面的 10%，避免 SCALER_CROP_REGION 变焦后区域相对画面膨胀。
            val afRect = PreviewMeteringMapper.buildCenteredRegion(
                mapping = mapping,
                widthFraction = AF_REGION_WIDTH_FRACTION,
                heightFraction = AF_REGION_HEIGHT_FRACTION,
            ).toAndroidRect()
            val afRegion = MeteringRectangle(afRect, MeteringRectangle.METERING_WEIGHT_MAX)

            if (triggerFocus) {
                PLog.d(
                    TAG,
                    "Focus mapping: ui=($normalizedX,$normalizedY) " +
                        "source=$source preserveMetering=$preserveMeteringPoint " +
                        "coordinateCamera=${activeOutputPhysicalCameraId ?: openCameraId} " +
                        "requestZoom=${builder.get(CaptureRequest.CONTROL_ZOOM_RATIO)} " +
                        "requestCrop=${builder.get(CaptureRequest.SCALER_CROP_REGION)} " +
                        "visible=${mapping.visibleRegion} center=(${mapping.centerX},${mapping.centerY}) " +
                        "af=$afRect metering=${_state.value.effectiveMeteringMode}"
                )
            }

            if (autoFocusEnabled && maxAfRegions > 0) {
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(afRegion))
            }
            // 人眼跟踪只接管 AF，不移动用户当前的测光区域。
            if (source == FocusPointSource.MANUAL && !preserveMeteringPoint) {
                applyMeteringRegions()
            }
            if (!autoFocusEnabled) {
                // Manual-focus mode still supports tap-to-meter and long-press exposure lock;
                // do not override the user's manual focus settings with an AF trigger.
                updatePreview()
            } else {
                val afMode = CaptureRequest.CONTROL_AF_MODE_AUTO
                _state.value = _state.value.copy(currentAfMode = afMode)
                if (!triggerFocus) {
                    updatePreview()
                } else if (!submitOneShotAfTrigger(afMode, "point focus")) {
                    PLog.w(TAG, "Unable to submit point-focus AF trigger")
                    _state.value = _state.value.copy(isFocusing = false, focusSuccess = false)
                }
            }

            // 对焦后通过场景变化检测自动恢复连续对焦（不再使用固定延迟）

        } catch (e: Exception) {
            PLog.e(TAG, "Failed to focus", e)
            _state.value = _state.value.copy(isFocusing = false, focusSuccess = false)
        }
    }

    /**
     * 更新人眼跟踪框的位置，但不重复触发一次 AF 扫描。
     * 实际的 AF 触发由调用方根据目标位移和节流策略决定。
     */
    fun updateEyeFocusTarget(normX: Float, normY: Float) {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post { updateEyeFocusTarget(normX, normY) }
            return
        }
        if (!normX.isFinite() || !normY.isFinite()) return
        val currentState = _state.value
        if (!currentState.isAutoFocus) return
        if (currentState.focusPoint != null && currentState.focusPointSource == FocusPointSource.MANUAL) {
            return
        }
        eyeTargetLastSeenElapsedMs = SystemClock.elapsedRealtime()
        _state.value = currentState.copy(
            focusPoint = Pair(normX.coerceIn(0f, 1f), normY.coerceIn(0f, 1f)),
            focusPointSource = FocusPointSource.EYE,
            isFocusLocked = false,
        )
    }

    fun cancelEyeFocus(reason: String) {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post { cancelEyeFocus(reason) }
            return
        }
        if (_state.value.focusPointSource != FocusPointSource.EYE) return
        PLog.d(TAG, "Cancel eye focus: $reason")
        if (isCaptureFocusFrozen || !_state.value.supportsPointAutoFocus) {
            clearPointFocusState()
            return
        }
        if (_state.value.isAutoFocus) {
            restoreContinuousAf()
        } else {
            previewRequestBuilder?.apply {
                clearCustomAfRegions(this)
                applyFocusSettings(this, _state.value)
                applyMeteringRegions()
                updatePreview()
            }
            clearPointFocusState()
        }
    }

    private fun isEyeTargetRecentlySeen(): Boolean {
        return _state.value.focusPointSource == FocusPointSource.EYE &&
            eyeTargetLastSeenElapsedMs > 0L &&
            SystemClock.elapsedRealtime() - eyeTargetLastSeenElapsedMs <= EYE_TARGET_RECENT_MS
    }

// ==================== 其他设置 ====================

    /**
     * 设置画面比例
     */
    fun setAspectRatio(ratio: AspectRatio) {
        _state.value = _state.value.copy(aspectRatio = ratio)
    }

    /**
     * 设置 LUT 启用状态
     */
    fun setLutEnabled(enabled: Boolean) {
        if (_state.value.lutEnabled == enabled) return
        _state.value = _state.value.copy(lutEnabled = enabled)
        createPreviewSession()
    }

    fun setLogLutActive(isLogLut: Boolean) {
        if (_state.value.isLogLutActive == isLogLut) return
        _state.value = _state.value.copy(isLogLutActive = isLogLut)
        createPreviewSession()
    }

    fun setCaptureMode(mode: CaptureMode) {
        if (_state.value.captureMode == mode ||
            _state.value.videoRecordingState.isRecording ||
            _state.value.videoRecordingState.isProcessing
        ) {
            return
        }
        if (normalVideoUltraWideInstantSwitchActive &&
            (mode != CaptureMode.PHOTO || _state.value.captureMode != CaptureMode.PHOTO)
        ) {
            clearNormalVideoUltraWideInstantSwitchRuntime(
                "capture mode changed; rebuilding the selected lens route",
                reopenSelectedCamera = false,
            )
        }
        val nextVideoConfig = if (mode == CaptureMode.VIDEO) {
            _state.value.videoConfig
        } else {
            _state.value.videoConfig.copy(logProfile = VideoLogProfile.OFF)
        }
        val nextState = _state.value.copy(
            captureMode = mode,
            videoConfig = nextVideoConfig,
            countdownValue = 0,
            isCapturingLivePhoto = false
        ).let { state ->
            if (mode == CaptureMode.VIDEO) {
                state.copy(shutterSpeed = coerceManualShutterSpeed(state, state.shutterSpeed))
            } else {
                state
            }
        }
        _state.value = nextState
        if (mode == CaptureMode.VIDEO) {
            livePhotoRecorder.stopRecording()
            refreshVideoCapabilities()
        } else {
            stopVideoRecordingTicker()
            _state.value = _state.value.copy(videoRecordingState = VideoRecordingState())
            if (_state.value.useLivePhoto) {
                livePhotoRecorder.startRecording()
            }
        }
    }

    fun setVideoResolution(resolution: VideoResolutionPreset) {
        val currentConfig = _state.value.videoConfig
        _state.value = _state.value.copy(
            videoConfig = currentConfig.copy(
                resolution = resolution,
            )
        )
        refreshVideoCapabilities()
    }

    fun setVideoFps(fps: VideoFpsPreset) {
        val currentConfig = _state.value.videoConfig
        _state.value = _state.value.copy(
            videoConfig = currentConfig.copy(
                fps = fps,
            )
        )
        refreshVideoCapabilities()
        previewRequestBuilder?.apply {
            applyBaseCameraSettings(this, isCapture = false)
            updatePreview()
        }
    }

    fun setVideoAspectRatio(aspectRatio: VideoAspectRatio) {
        val config = _state.value.videoConfig
        val selectedSecondary = config.dualAspectRatio
        val resolvedSecondary = selectedSecondary?.takeUnless { it.matchesPrimary(aspectRatio) }
            ?: selectedSecondary?.let { DualVideoAspectRatio.defaultFor(aspectRatio) }
        _state.value = _state.value.copy(
            videoConfig = config.copy(aspectRatio = aspectRatio, dualAspectRatio = resolvedSecondary)
        )
        refreshVideoCapabilities()
    }

    fun setDualVideoAspectRatio(aspectRatio: DualVideoAspectRatio?) {
        if (_state.value.videoRecordingState.isRecording ||
            _state.value.videoRecordingState.isProcessing
        ) return
        val currentConfig = _state.value.videoConfig
        val primary = currentConfig.aspectRatio.takeUnless { it == VideoAspectRatio.OPEN_GATE }
            ?: VideoAspectRatio.RATIO_16_9
        val resolved = aspectRatio?.takeUnless { it.matchesPrimary(primary) }
            ?: aspectRatio?.let { DualVideoAspectRatio.defaultFor(primary) }
        _state.value = _state.value.copy(
            videoConfig = currentConfig.copy(aspectRatio = primary, dualAspectRatio = resolved)
        )
    }

    fun setVideoLogLutMode(mode: VideoLogLutMode) {
        if (_state.value.videoRecordingState.isRecording || _state.value.videoRecordingState.isProcessing) return
        _state.value = _state.value.copy(videoConfig = _state.value.videoConfig.copy(logLutMode = mode))
    }

    fun setVideoLogProfile(logProfile: VideoLogProfile) {
        val previousProfile = _state.value.videoConfig.logProfile
        if (previousProfile != logProfile) {
            oppoNaturalVideoEngine.reset()
            oppoHlg10AutoExposureEngine.reset()
            lastOppoVideoProfileIspUpdateMs = 0L
        }
        val resolvedProfile = if (_state.value.captureMode == CaptureMode.VIDEO) {
            logProfile
        } else {
            VideoLogProfile.OFF
        }
        _state.value = _state.value.copy(videoConfig = _state.value.videoConfig.copy(logProfile = resolvedProfile))
        refreshVideoCapabilities()
        val appliedProfile = _state.value.videoConfig.logProfile
        if (previousProfile != appliedProfile || logProfile != appliedProfile) {
            PLog.d(
                TAG,
                "Video Log selection: requested=${logProfile.name}, previous=${previousProfile.name}, " +
                    "applied=${appliedProfile.name}, mode=${_state.value.captureMode}, " +
                    "lifecycle=$cameraDeviceLifecycle"
            )
        }
        previewRequestBuilder?.apply {
            applyBaseCameraSettings(this, isCapture = false)
            updatePreview()
        }
    }

    fun setVideoStabilizationMode(mode: VideoStabilizationMode) {
        val previousConfig = _state.value.videoConfig
        if (mode == VideoStabilizationMode.ENHANCED &&
            isEnhancedStabilizationUnavailableForCurrentCamera()
        ) {
            PLog.w(
                TAG,
                "Ignoring EIS+ selection because it was already verified unavailable for " +
                    currentStabilizationCameraId()
            )
            return
        }
        when {
            mode == VideoStabilizationMode.ENHANCED &&
                previousConfig.stabilizationMode != VideoStabilizationMode.ENHANCED -> {
                previousVideoStabilizationMode = previousConfig.stabilizationMode
            }

            mode != VideoStabilizationMode.ENHANCED -> {
                previousVideoStabilizationMode = mode
            }
        }
        _state.value = _state.value.copy(
            videoConfig = previousConfig.copy(
                stabilizationMode = mode,
            )
        )
        refreshVideoCapabilities()
        val resolvedConfig = _state.value.videoConfig
        if (
            mode == VideoStabilizationMode.ENHANCED &&
            resolvedConfig.stabilizationMode != VideoStabilizationMode.ENHANCED
        ) {
            PLog.e(
                TAG,
                "EIS+ rejected for ${resolvedConfig.resolution.displayName}@" +
                    "${resolvedConfig.fps.fps}: camera does not expose a matching YUV input"
            )
        }
        val outputGeometryChanged = previousConfig.resolution != resolvedConfig.resolution ||
            previousConfig.fps != resolvedConfig.fps
        if (_state.value.captureMode == CaptureMode.VIDEO &&
            !_state.value.videoRecordingState.isRecording &&
            previousConfig.stabilizationMode != resolvedConfig.stabilizationMode &&
            !outputGeometryChanged
        ) {
            createPreviewSession()
            return
        }
        previewRequestBuilder?.apply {
            applyBaseCameraSettings(this, isCapture = false)
            updatePreview()
        }
    }

    fun setOppoSuperStabilizationEnabled(enabled: Boolean) {
        val resolvedEnabled = enabled && DeviceUtil.isOppo
        if (oppoSuperStabilizationEnabled == resolvedEnabled) return
        oppoSuperStabilizationEnabled = resolvedEnabled
        PLog.i(TAG, "OPPO super stabilization enabled=$resolvedEnabled")
        if (_state.value.captureMode == CaptureMode.VIDEO &&
            !_state.value.videoRecordingState.isRecording &&
            cameraDevice != null && previewSurface != null
        ) {
            createPreviewSession(openGeneration = cameraOpenGeneration)
        }
    }

    fun setVideoBitrate(bitrate: VideoBitratePreset) {
        _state.value = _state.value.copy(videoConfig = _state.value.videoConfig.copy(bitrate = bitrate))
        refreshVideoCapabilities()
    }

    fun setVideoCodec(codec: com.hinnka.mycamera.video.VideoCodec) {
        _state.value = _state.value.copy(videoConfig = _state.value.videoConfig.copy(codec = codec))
        refreshVideoCapabilities()
    }

    fun setVideoApvQuality(quality: com.hinnka.mycamera.video.ApvQuality) {
        _state.value = _state.value.copy(
            videoConfig = _state.value.videoConfig.copy(apvQuality = quality)
        )
    }

    fun setVideoAudioInputId(audioInputId: String) {
        val normalizedAudioInputId = audioInputId.ifBlank { VIDEO_AUDIO_INPUT_AUTO }
        _state.value = _state.value.copy(
            videoConfig = _state.value.videoConfig.copy(audioInputId = normalizedAudioInputId)
        )
        videoRecorder.setPreferredAudioInputId(normalizedAudioInputId)
    }

    fun setVideoEnhancedStabilizationStrength(strength: Float) {
        _state.value = _state.value.copy(
            videoConfig = _state.value.videoConfig.copy(
                enhancedStabilizationStrength = normalizeStabilizationStrength(strength)
            )
        )
    }

    fun setVideoEnhancedStabilizationLookahead(lookahead: Int) {
        _state.value = _state.value.copy(
            videoConfig = _state.value.videoConfig.copy(
                enhancedStabilizationLookahead = normalizeStabilizationLookahead(lookahead)
            )
        )
    }

    fun setExternalLensStabilizationConfig(config: ExternalLensStabilizationConfig) {
        realtimeStabilizationCoordinator.setExternalLensStabilizationConfig(config)
    }

    fun setPhotoPreviewStabilizationEnabled(enabled: Boolean) {
        if (enabled && cameraDevice != null &&
            !realtimeStabilizationCoordinator.isCurrentCameraSupported
        ) {
            if (_state.value.photoPreviewStabilizationEnabled) {
                _state.value = _state.value.copy(
                    photoPreviewStabilizationEnabled = false
                )
            }
            PLog.e(TAG, "Photo preview stabilization rejected: current camera is unsupported")
            notifyEnhancedStabilizationUnavailable(
                EnhancedStabilizationFallback.PhotoPreview
            )
            return
        }
        if (_state.value.photoPreviewStabilizationEnabled == enabled) return
        _state.value = _state.value.copy(photoPreviewStabilizationEnabled = enabled)
        if (_state.value.captureMode == CaptureMode.PHOTO &&
            cameraDevice != null && previewSurface != null
        ) {
            createPreviewSession(openGeneration = cameraOpenGeneration)
            return
        }
        previewRequestBuilder?.apply {
            applyBaseCameraSettings(this, isCapture = false)
            updatePreview()
        }
    }

    fun setVideoRecordingPath(recordingPath: VideoRecordingPath, recordingTreeUri: String? = null) {
        _state.value = _state.value.copy(
            videoConfig = _state.value.videoConfig.copy(
                recordingPath = recordingPath,
                recordingTreeUri = recordingTreeUri?.takeIf { it.isNotBlank() }
            )
        )
    }

    fun updateVideoRecordingColorRecipeParams(params: ColorRecipeParams) {
        if (!videoRecorder.isRecording()) return
        videoRecorder.updateCreativeRecipeParams(params)
    }

    fun setVideoTorchEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(
            videoConfig = _state.value.videoConfig.copy(
                torchEnabled = enabled && _state.value.videoCapabilities.supportsTorch
            )
        )
        previewRequestBuilder?.apply {
            applyBaseCameraSettings(this, isCapture = false)
            updatePreview()
        }
    }

    fun setKeepUltraWideAeReady(enabled: Boolean) {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post { setKeepUltraWideAeReady(enabled) }
            return
        }
        if (keepUltraWideAeReady == enabled) return
        keepUltraWideAeReady = enabled
        pendingUltraWideAeHandoff = null
        if (enabled) ultraWideAeMeteringSuppressed = false
        PLog.i(TAG, "Keep Ultra-Wide AE Ready enabled=$enabled")
        if (cameraDevice != null && previewSurface != null &&
            !_state.value.videoRecordingState.isRecording &&
            !_state.value.videoRecordingState.isProcessing
        ) {
            createPreviewSession()
        } else if (_state.value.videoRecordingState.isRecording) {
            PLog.i(TAG, "Ultra-wide AE setting will take effect after recording stops")
        }
    }

    fun setVideoLensLockEnabled(enabled: Boolean) {
        if (_state.value.videoConfig.lensLockEnabled == enabled) return
        _state.value = _state.value.copy(
            videoConfig = _state.value.videoConfig.copy(lensLockEnabled = enabled)
        )
    }

    fun setVideoWhiteBalanceLockEnabled(enabled: Boolean) {
        if (_state.value.videoConfig.whiteBalanceLockEnabled == enabled) return
        _state.value = _state.value.copy(
            videoConfig = _state.value.videoConfig.copy(whiteBalanceLockEnabled = enabled)
        )
        previewRequestBuilder?.apply {
            applyWhiteBalanceSettings(this, _state.value, isCapture = false)
            updatePreview()
        }
    }

    fun setMirrorFrontCameraEnabled(enabled: Boolean) {
        mirrorFrontCameraEnabled = enabled
    }


    /**
     * Front-camera Dual Capture uses Android's concurrent-camera contract. The ultra-wide
     * option binds a second output to a physical camera of the active logical rear device.
     */
    private fun isDualCaptureFrontSupported(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val state = _state.value
        val current = state.getCurrentCameraInfo() ?: return false
        if (current.lensFacing == CameraCharacteristics.LENS_FACING_FRONT) return false
        val front = state.availableCameras.firstOrNull {
            it.lensFacing == CameraCharacteristics.LENS_FACING_FRONT
        } ?: return false
        val primaryId = activeOpenCameraId.takeIf { it.isNotBlank() }
            ?: runCatching { current.getOpenCameraId() }.getOrNull()
            ?: current.cameraId
        return runCatching {
            cameraManager.concurrentCameraIds.any { ids ->
                ids.contains(primaryId) && ids.contains(front.cameraId)
            }
        }.getOrDefault(false)
    }

    /**
     * Resolves the 1x and ultra-wide sensors behind the currently open logical rear camera.
     * Both outputs are configured on that logical CameraDevice; the ultra-wide sensor is never
     * opened as a second independent CameraDevice.
     */
    private fun resolveDualCapturePhysicalCameraPair(): Pair<String, String>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val state = _state.value
        val current = state.getCurrentCameraInfo() ?: return null
        if (current.lensFacing != CameraCharacteristics.LENS_FACING_BACK) return null

        val logicalId = current.logicalCameraId ?: current.cameraId
        val openCameraId = runCatching { current.getOpenCameraId() }
            .getOrDefault(current.cameraId)
        if (openCameraId != logicalId) return null
        if (activeOpenCameraId.isNotBlank() && activeOpenCameraId != logicalId) return null

        val logicalCharacteristics = runCatching {
            cameraManager.getCameraCharacteristics(logicalId)
        }.getOrNull() ?: return null
        val capabilities = logicalCharacteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: return null
        if (!capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) {
            return null
        }
        val physicalIds = logicalCharacteristics.physicalCameraIds
        if (physicalIds.size < 2) return null

        fun equivalentFocalLength(characteristics: CameraCharacteristics): Float? {
            val focalLength = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.firstOrNull() ?: return null
            val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                ?: return null
            val diagonal = hypot(sensorSize.width.toDouble(), sensorSize.height.toDouble()).toFloat()
            if (diagonal <= 0f) return null
            return focalLength * 43.2666f / diagonal
        }

        val discoveredById = state.availableCameras.associateBy { it.cameraId }
        val discoveredMainFocalLength = state.availableCameras
            .asSequence()
            .filter {
                it.lensFacing == CameraCharacteristics.LENS_FACING_BACK &&
                    it.logicalCameraId == logicalId &&
                    !it.isVirtualIszLens && !it.isCustomLensId &&
                    it.focalLength35mmEquivalent > 0f &&
                    it.intrinsicZoomRatio.isFinite() && it.intrinsicZoomRatio > 0f
            }
            .minByOrNull { abs(it.intrinsicZoomRatio - 1f) }
            ?.takeIf { abs(it.intrinsicZoomRatio - 1f) <= 0.15f }
            ?.focalLength35mmEquivalent
        val referenceFocalLength = discoveredMainFocalLength
            ?: equivalentFocalLength(logicalCharacteristics)
            ?: current.focalLength35mmEquivalent.takeIf {
                abs(current.intrinsicZoomRatio - 1f) <= 0.15f && it > 0f
            }

        val candidates = physicalIds.mapNotNull { physicalId ->
            val characteristics = runCatching {
                cameraManager.getCameraCharacteristics(physicalId)
            }.getOrNull() ?: return@mapNotNull null
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                ?: CameraCharacteristics.LENS_FACING_BACK
            if (facing != CameraCharacteristics.LENS_FACING_BACK) return@mapNotNull null
            val cameraInfo = discoveredById[physicalId]
            val focalLength = equivalentFocalLength(characteristics)
                ?: cameraInfo?.focalLength35mmEquivalent?.takeIf { it > 0f }
            val ratio = if (focalLength != null && referenceFocalLength != null &&
                referenceFocalLength > 0f
            ) {
                focalLength / referenceFocalLength
            } else {
                cameraInfo?.intrinsicZoomRatio
            } ?: return@mapNotNull null
            if (!ratio.isFinite() || ratio <= 0f) return@mapNotNull null
            physicalId to ratio
        }

        val main = candidates.minByOrNull { abs(it.second - 1f) }
            ?.takeIf { abs(it.second - 1f) <= 0.15f }
            ?: return null
        val widestAllowedRatio = minOf(0.85f, main.second - 0.15f)
        if (widestAllowedRatio < 0.2f) return null
        val ultraWide = candidates
            .filter { it.second in 0.2f..widestAllowedRatio }
            .minByOrNull { it.second }
            ?: return null
        if (main.first == ultraWide.first) return null

        PLog.i(
            TAG,
            "Dual Capture rear pair resolved: logical=$logicalId, main1x=${main.first}, " +
                "ultraWide=${ultraWide.first}, ratios=${main.second}/${ultraWide.second}"
        )
        return main.first to ultraWide.first
    }

    /**
     * Some devices expose the rear ultra-wide as an independent public Camera2 ID rather
     * than as a physical member of the active logical camera. Use it only when Android
     * explicitly advertises the active rear camera + ultra-wide pair as concurrent.
     */
    private fun resolveConcurrentDualCaptureUltraWideCameraId(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val state = _state.value
        val current = state.getCurrentCameraInfo() ?: return null
        if (current.lensFacing != CameraCharacteristics.LENS_FACING_BACK) return null

        val primaryId = activeOpenCameraId.takeIf { it.isNotBlank() }
            ?: runCatching { current.getOpenCameraId() }.getOrNull()
            ?: current.cameraId
        val publicCameraIds = runCatching { cameraManager.cameraIdList.toSet() }
            .getOrDefault(emptySet())
        if (primaryId !in publicCameraIds) return null

        val concurrentPairs = runCatching { cameraManager.concurrentCameraIds }
            .getOrDefault(emptySet())
        if (concurrentPairs.isEmpty()) return null

        return state.availableCameras.asSequence()
            .filter {
                it.lensFacing == CameraCharacteristics.LENS_FACING_BACK &&
                    !it.isVirtualIszLens &&
                    !it.isCustomLensId &&
                    (
                        it.lensType == LensType.BACK_ULTRA_WIDE ||
                            (it.intrinsicZoomRatio.isFinite() &&
                                it.intrinsicZoomRatio in 0.2f..0.85f)
                    )
            }
            .mapNotNull { info ->
                val openId = runCatching { info.getOpenCameraId() }.getOrNull()
                    ?: return@mapNotNull null
                if (openId == primaryId || openId !in publicCameraIds) {
                    return@mapNotNull null
                }
                info to openId
            }
            .filter { (_, openId) ->
                concurrentPairs.any { ids -> primaryId in ids && openId in ids }
            }
            .minByOrNull { (info, _) ->
                info.intrinsicZoomRatio.takeIf { it.isFinite() && it > 0f } ?: Float.MAX_VALUE
            }
            ?.second
            ?.also { cameraId ->
                PLog.i(
                    TAG,
                    "Dual Capture rear ultra-wide concurrent camera resolved: " +
                        "primary=$primaryId, ultraWide=$cameraId"
                )
            }
    }

    private fun isDualCaptureUltraWideSupported(): Boolean =
        resolveDualCapturePhysicalCameraPair() != null ||
            resolveConcurrentDualCaptureUltraWideCameraId() != null

    fun isDualCaptureSourceSupported(source: DualCaptureSource): Boolean = when (source) {
        DualCaptureSource.FRONT -> isDualCaptureFrontSupported()
        DualCaptureSource.ULTRA_WIDE -> isDualCaptureUltraWideSupported()
    }

    /**
     * Resolve the 1x and ultra-wide outputs directly from the active logical Camera2 device.
     *
     * Logical multi-camera discovery is an optional lens-list feature and is disabled by
     * default. Special Video must not depend on that preference: Camera2 already exposes
     * the physical output IDs through the logical camera's characteristics.
     *
     * The active CameraDevice must still be the logical parent. A standalone physical
     * camera cannot accept another physical camera's output configuration.
     */
    private fun resolveSpatialVideoCameraPair(): Triple<String, String, String>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null

        val state = _state.value
        val current = state.getCurrentCameraInfo() ?: return null
        // Special Video resolves both sensors from the logical rear camera.
        // The selected physical lens may be 0.5x; it must not constrain the pair.
        if (current.lensFacing != CameraCharacteristics.LENS_FACING_BACK) {
            return null
        }

        // With logical discovery enabled this is the parent ID. Otherwise, the selected
        // CameraInfo may itself represent the logical camera, with logicalCameraId unset.
        val logicalId = current.logicalCameraId ?: current.cameraId
        val openCameraId = runCatching { current.getOpenCameraId() }
            .getOrDefault(current.cameraId)
        if (openCameraId != logicalId) return null
        // Pair discovery is independent of the currently opened rear route. VCamera
        // resolves the main/ultra-wide pair from the full rear-lens inventory, then
        // performs the primary-device handoff when Special Video is enabled.
        val logicalCharacteristics = runCatching {
            cameraManager.getCameraCharacteristics(logicalId)
        }.getOrNull() ?: return null
        val capabilities = logicalCharacteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: return null
        if (!capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) {
            return null
        }

        val physicalIds = logicalCharacteristics.physicalCameraIds
        if (physicalIds.size < 2) return null

        fun equivalentFocalLength(characteristics: CameraCharacteristics): Float? {
            val focalLength = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.firstOrNull() ?: return null
            val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                ?: return null
            val diagonal = hypot(sensorSize.width.toDouble(), sensorSize.height.toDouble()).toFloat()
            if (diagonal <= 0f) return null
            return focalLength * 43.2666f / diagonal
        }

        val discoveredById = state.availableCameras.associateBy { it.cameraId }
        // Prefer the discovered 1x lens as the focal-length reference. Never use the
        // selected lens unconditionally: an ultra-wide selection would normalize itself to 1x.
        val discoveredMainReferenceFocalLength = state.availableCameras
            .asSequence()
            .filter {
                it.lensFacing == CameraCharacteristics.LENS_FACING_BACK &&
                    it.logicalCameraId == logicalId &&
                    !it.isVirtualIszLens && !it.isCustomLensId &&
                    it.focalLength35mmEquivalent > 0f &&
                    it.intrinsicZoomRatio.isFinite() && it.intrinsicZoomRatio > 0f
            }
            .minByOrNull { abs(it.intrinsicZoomRatio - 1f) }
            ?.takeIf { abs(it.intrinsicZoomRatio - 1f) <= 0.15f }
            ?.focalLength35mmEquivalent
        val referenceFocalLength = discoveredMainReferenceFocalLength
            ?: equivalentFocalLength(logicalCharacteristics)
            ?: current.focalLength35mmEquivalent.takeIf {
                abs(current.intrinsicZoomRatio - 1f) <= 0.15f && it > 0f
            }

        val physicalCandidates = physicalIds.mapNotNull { physicalId ->
            val characteristics = runCatching {
                cameraManager.getCameraCharacteristics(physicalId)
            }.getOrNull() ?: return@mapNotNull null
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                ?: CameraCharacteristics.LENS_FACING_BACK
            if (facing != CameraCharacteristics.LENS_FACING_BACK) return@mapNotNull null

            val cameraInfo = discoveredById[physicalId]
            val physicalFocalLength = equivalentFocalLength(characteristics)
                ?: cameraInfo?.focalLength35mmEquivalent?.takeIf { it > 0f }
            val zoomRatio = if (physicalFocalLength != null &&
                referenceFocalLength != null && referenceFocalLength > 0f
            ) {
                physicalFocalLength / referenceFocalLength
            } else {
                cameraInfo?.intrinsicZoomRatio
            } ?: return@mapNotNull null

            if (!zoomRatio.isFinite() || zoomRatio <= 0f) return@mapNotNull null
            SpatialVideoPhysicalCameraCandidate(
                cameraId = physicalId,
                isBackFacing = facing == CameraCharacteristics.LENS_FACING_BACK,
                intrinsicZoomRatio = zoomRatio,
            )
        }

        val pair = resolveSpatialVideoPhysicalCameraPair(
            selectedCameraIsBackFacing = true,
            candidates = physicalCandidates,
        ) ?: return null

        PLog.i(
            TAG,
            "Special Video rear pair resolved from selected=${current.cameraId} " +
                "(selectedZoom=${current.intrinsicZoomRatio}): logical=$logicalId, " +
                "main1x=${pair.mainCameraId}, ultraWide=${pair.ultraWideCameraId}, " +
                "referenceFocalLength35mm=$referenceFocalLength"
        )
        return Triple(logicalId, pair.mainCameraId, pair.ultraWideCameraId)
    }

    /**
     * Resolve in VCamera's priority order: logical physical outputs, declared concurrent
     * Camera2 devices, then a persistent pair of independently open CameraDevices.
     */
    private fun resolveSpatialVideoConfiguration(): SpatialVideoCameraPair? {
        spatialVideoUnsupportedReason = null
        val state = _state.value
        val current = state.getCurrentCameraInfo() ?: return null
        if (current.lensFacing != CameraCharacteristics.LENS_FACING_BACK) return null

        fun resolvePublicPhysicalPair(
            logicalId: String,
            mainPhysicalId: String,
            ultraWidePhysicalId: String,
            mainInfo: CameraInfo,
        ): SpatialVideoCameraPair? {
            fun resolveLogicalOutputPair(unavailableReason: String): SpatialVideoCameraPair? {
                val logicalCharacteristics = runCatching {
                    cameraManager.getCameraCharacteristics(logicalId)
                }.getOrNull()
                if (logicalCharacteristics == null) {
                    spatialVideoUnsupportedReason =
                        "$unavailableReason; logical parent $logicalId characteristics are unavailable"
                    return null
                }

                val capabilities = logicalCharacteristics.get(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
                )
                val advertisedPhysicalIds = logicalCharacteristics.physicalCameraIds
                val supportsPhysicalOutputs =
                    capabilities?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true &&
                        mainPhysicalId in advertisedPhysicalIds &&
                        ultraWidePhysicalId in advertisedPhysicalIds
                if (!supportsPhysicalOutputs) {
                    spatialVideoUnsupportedReason =
                        "$unavailableReason; logical parent $logicalId does not advertise both physical outputs"
                    return null
                }

                val selectedOpenId = runCatching { current.getOpenCameraId() }
                    .getOrDefault(current.cameraId)
                if (selectedOpenId != logicalId) {
                    spatialVideoUnsupportedReason =
                        "$unavailableReason; logical output fallback requires selected parent=$logicalId " +
                            "(selected=$selectedOpenId)"
                    return null
                }

                PLog.i(
                    TAG,
                    "Special Video fallback: mode=LOGICAL_MULTI_CAMERA_PHYSICAL logicalParent=$logicalId " +
                        "main=$mainPhysicalId ultraWide=$ultraWidePhysicalId reason=$unavailableReason"
                )
                return SpatialVideoCameraPair(
                    mode = SpatialVideoStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL,
                    primaryCameraId = logicalId,
                    mainCameraInfo = mainInfo,
                    mainPhysicalCameraId = mainPhysicalId,
                    ultraWideCameraId = ultraWidePhysicalId,
                    logicalParentCameraId = logicalId,
                )
            }

            val publicIds = try {
                cameraManager.cameraIdList.toSet()
            } catch (error: Exception) {
                val reason =
                    "CameraManager.cameraIdList unavailable: ${error.message ?: error.javaClass.simpleName}"
                val logicalFallback = resolveLogicalOutputPair(reason)
                if (logicalFallback != null) return logicalFallback
                val fallbackReason = spatialVideoUnsupportedReason ?: "logical physical-output fallback unavailable"
                spatialVideoUnsupportedReason = "$reason; fallback failed: $fallbackReason"
                PLog.e(
                    TAG,
                    "Special Video unsupported: mode=PERSISTENT_DUAL_SESSION main=$mainPhysicalId " +
                        "ultraWide=$ultraWidePhysicalId reason=$spatialVideoUnsupportedReason",
                    error
                )
                return null
            }
            val missingIds = listOf(mainPhysicalId, ultraWidePhysicalId).filterNot { it in publicIds }
            if (missingIds.isNotEmpty()) {
                val reason =
                    "Physical Camera2 ID(s) are not publicly openable: ${missingIds.joinToString()}"
                val logicalFallback = resolveLogicalOutputPair(reason)
                if (logicalFallback != null) return logicalFallback
                val fallbackReason = spatialVideoUnsupportedReason ?: "logical physical-output fallback unavailable"
                spatialVideoUnsupportedReason = "$reason; fallback failed: $fallbackReason"
                PLog.w(
                    TAG,
                    "Special Video unsupported: mode=PERSISTENT_DUAL_SESSION logicalParent=$logicalId " +
                        "main=$mainPhysicalId ultraWide=$ultraWidePhysicalId reason=$spatialVideoUnsupportedReason " +
                        "publicCameraIds=$publicIds"
                )
                return null
            }
            PLog.i(
                TAG,
                "Special Video configured: mode=PERSISTENT_DUAL_SESSION logicalParent=$logicalId " +
                    "main=$mainPhysicalId ultraWide=$ultraWidePhysicalId openPrimary=$mainPhysicalId"
            )
            return SpatialVideoCameraPair(
                mode = SpatialVideoStreamingMode.PERSISTENT_DUAL_SESSION,
                primaryCameraId = mainPhysicalId,
                mainCameraInfo = mainInfo,
                mainPhysicalCameraId = mainPhysicalId,
                ultraWideCameraId = ultraWidePhysicalId,
                logicalParentCameraId = logicalId,
            )
        }

        resolveSpatialVideoCameraPair()?.let { (logicalId, mainPhysicalId, ultraWidePhysicalId) ->
            val mainInfo = state.availableCameras.firstOrNull {
                it.cameraId == mainPhysicalId ||
                    it.outputPhysicalCameraId == mainPhysicalId ||
                    it.physicalCameras.any { physical -> physical.cameraId == mainPhysicalId }
            } ?: current
            val ultraWideInfo = state.availableCameras.firstOrNull {
                it.cameraId == ultraWidePhysicalId ||
                    it.outputPhysicalCameraId == ultraWidePhysicalId ||
                    it.physicalCameras.any { physical -> physical.cameraId == ultraWidePhysicalId }
            }
            val mainOpenId = runCatching { mainInfo.getOpenCameraId() }.getOrDefault(logicalId)
            val wideOpenId = ultraWideInfo?.let {
                runCatching { it.getOpenCameraId() }.getOrDefault(logicalId)
            } ?: logicalId

            // A shared logical parent cannot be opened twice. Open the two publicly exposed
            // physical child IDs as independent CameraDevices (VCamera strategy).
            if (mainOpenId == wideOpenId) {
                return resolvePublicPhysicalPair(
                    logicalId = logicalId,
                    mainPhysicalId = mainPhysicalId,
                    ultraWidePhysicalId = ultraWidePhysicalId,
                    mainInfo = mainInfo,
                )
            }

            // Keep Photon’s old logical-to-physical output path intact for devices where
            // the selected lenses resolve to different CameraDevices.
            PLog.i(
                TAG,
                "Special Video mode=LOGICAL_MULTI_CAMERA_PHYSICAL logical=$logicalId " +
                    "mainPhysical=$mainPhysicalId ultraWide=$ultraWidePhysicalId " +
                    "mainOpen=$mainOpenId ultraWideOpen=$wideOpenId"
            )
            return SpatialVideoCameraPair(
                mode = SpatialVideoStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL,
                primaryCameraId = logicalId,
                mainCameraInfo = mainInfo,
                mainPhysicalCameraId = mainPhysicalId,
                ultraWideCameraId = ultraWidePhysicalId,
                logicalParentCameraId = logicalId,
            )
        }

        val rearLenses = state.availableCameras.filter {
            it.lensFacing == CameraCharacteristics.LENS_FACING_BACK &&
                !it.isVirtualIszLens && !it.isCustomLensId
        }
        val main = rearLenses.asSequence()
            .filter { it.lensType == LensType.BACK_MAIN }
            .minByOrNull { abs(it.intrinsicZoomRatio - 1f) }
            ?: rearLenses.asSequence()
                .filter { it.intrinsicZoomRatio.isFinite() && it.intrinsicZoomRatio in 0.85f..1.15f }
                .minByOrNull { abs(it.intrinsicZoomRatio - 1f) }
            ?: return null
        val ultraWide = rearLenses.asSequence()
            .filter { it.lensType == LensType.BACK_ULTRA_WIDE }
            .minByOrNull {
                it.intrinsicZoomRatio.takeIf { ratio -> ratio.isFinite() && ratio > 0f }
                    ?.let { ratio -> abs(ratio - 0.5f) } ?: Float.MAX_VALUE
            }
            ?: rearLenses.asSequence()
                .filter { it.intrinsicZoomRatio.isFinite() && it.intrinsicZoomRatio in 0.2f..0.85f }
                .minByOrNull { abs(it.intrinsicZoomRatio - 0.5f) }
            ?: return null

        val mainOpenId = runCatching { main.getOpenCameraId() }.getOrNull() ?: return null
        val wideOpenId = runCatching { ultraWide.getOpenCameraId() }.getOrNull() ?: return null

        if (mainOpenId == wideOpenId) {
            fun physicalIdFor(info: CameraInfo, preferredZoom: Float, openId: String): String? =
                info.outputPhysicalCameraId?.takeIf { it.isNotBlank() && it != openId }
                    ?: info.physicalCameras
                        .filter { it.cameraId.isNotBlank() }
                        .minByOrNull { abs(it.intrinsicZoomRatio - preferredZoom) }
                        ?.cameraId?.takeIf { it != openId }
                    ?: info.cameraId.takeIf { it.isNotBlank() && it != openId }

            val mainPhysicalId = physicalIdFor(main, 1f, mainOpenId)
            val ultraWidePhysicalId = physicalIdFor(ultraWide, 0.5f, wideOpenId)
            if (mainPhysicalId == null || ultraWidePhysicalId == null ||
                mainPhysicalId == ultraWidePhysicalId
            ) {
                val reason = "Could not resolve two distinct physical camera IDs for shared logical parent $mainOpenId"
                spatialVideoUnsupportedReason = reason
                PLog.w(
                    TAG,
                    "Special Video unsupported: mode=PERSISTENT_DUAL_SESSION main=${mainPhysicalId ?: "missing"} " +
                        "ultraWide=${ultraWidePhysicalId ?: "missing"} reason=$reason"
                )
                return null
            }
            return resolvePublicPhysicalPair(
                logicalId = mainOpenId,
                mainPhysicalId = mainPhysicalId,
                ultraWidePhysicalId = ultraWidePhysicalId,
                mainInfo = main,
            )
        }

        // VCamera attempts the discovered Camera2 IDs directly. Do not reject a lens
        // merely because OEM firmware omitted it from CameraManager.cameraIdList:
        // hidden-but-queryable IDs can still be opened on devices that expose them.
        val unavailableIds = listOf(mainOpenId, wideOpenId).filter { cameraId ->
            runCatching { cameraManager.getCameraCharacteristics(cameraId) }.isFailure
        }
        if (unavailableIds.isNotEmpty()) {
            val reason = "Discovered Camera2 device ID(s) have no readable characteristics: ${unavailableIds.joinToString()}"
            spatialVideoUnsupportedReason = reason
            PLog.w(
                TAG,
                "Special Video unsupported: mode=independent main=$mainOpenId ultraWide=$wideOpenId reason=$reason"
            )
            return null
        }
        val concurrentAdvertised = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                cameraManager.concurrentCameraIds.any { ids -> mainOpenId in ids && wideOpenId in ids }
            }.getOrDefault(false)
        } else false
        val mode = if (concurrentAdvertised) {
            SpatialVideoStreamingMode.CONCURRENT_DEVICES
        } else {
            SpatialVideoStreamingMode.PERSISTENT_DUAL_SESSION
        }
        PLog.i(
            TAG,
            "Special Video independent pair mode=$mode main=${main.cameraId}/$mainOpenId " +
                "ultraWide=${ultraWide.cameraId}/$wideOpenId mainZoom=${main.intrinsicZoomRatio} " +
                "ultraWideZoom=${ultraWide.intrinsicZoomRatio}"
        )
        return SpatialVideoCameraPair(
            mode = mode,
            primaryCameraId = mainOpenId,
            mainCameraInfo = main,
            mainPhysicalCameraId = main.outputPhysicalCameraId,
            ultraWideCameraId = wideOpenId,
        )
    }

    fun isSpatialVideoSupported(): Boolean {
        val pair = resolveSpatialVideoConfiguration()
        if (pair == null) {
            val reason = spatialVideoUnsupportedReason ?: "rear main 1x + ultra-wide 0.5x pair not discovered"
            PLog.w(TAG, "Special Video unsupported: reason=$reason")
        }
        return pair != null
    }

    fun setNormalVideoUltraWideInstantSwitchEnabled(enabled: Boolean) {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post { setNormalVideoUltraWideInstantSwitchEnabled(enabled) }
            return
        }
        if (_state.value.videoRecordingState.isRecording || _state.value.videoRecordingState.isProcessing) {
            PLog.i(TAG, "Normal Video instant-switch setting is locked while recording")
            onCameraError?.invoke(-1, "Stop recording before changing the Normal Video instant-switch setting.", false)
            return
        }
        normalVideoUltraWideInstantSwitchEnabled = enabled
        if (!enabled) {
            clearNormalVideoUltraWideInstantSwitchRuntime("setting disabled", reopenSelectedCamera = true)
            return
        }
        if (spatialVideoEnabled ||
            (_state.value.captureMode != CaptureMode.VIDEO && _state.value.captureMode != CaptureMode.PHOTO)
        ) return
        if (_state.value.videoRecordingState.isRecording || _state.value.videoRecordingState.isProcessing) {
            PLog.i(TAG, "Normal Video instant-switch setting will apply after recording finishes")
            return
        }
        activateNormalVideoUltraWideInstantSwitch(
            reopenPrimaryIfNeeded = cameraDeviceLifecycle != CameraDeviceLifecycle.OPENING
        )
    }

    fun setSpatialVideoShowUltraWidePreview(enabled: Boolean) {
        _spatialVideoShowUltraWidePreview.value = enabled
        specialVideoPreferences.edit().putBoolean("show_ultra_wide_preview", enabled).apply()
        // The secondary preview surface stays attached while Special Video is active.
        // This setting only controls whether the UI shows the small inset when 1x is selected.
    }

    fun setSpatialVideoVirtualTelephotoEnabled(enabled: Boolean) {
        if (
            videoRecorder.isRecording() ||
            spatialVideoRecorder.isRecording() ||
            spatialVideoTelephotoRecorder.isRecording() ||
            _state.value.videoRecordingState.isRecording ||
            _state.value.videoRecordingState.isProcessing
        ) {
            onCameraError?.invoke(-1, "Stop recording before changing the 3.5x virtual lens.", false)
            return
        }
        _spatialVideoVirtualTelephotoEnabled.value = enabled
        specialVideoPreferences.edit().putBoolean("virtual_telephoto_enabled", enabled).apply()
        PLog.i(TAG, "Special Video 3.5x virtual telephoto ${if (enabled) "enabled" else "disabled"}")
    }

    fun isTopOffZoomAvailable(): Boolean {
        val currentState = _state.value
        if (currentState.captureMode != CaptureMode.VIDEO || spatialVideoEnabled || dualCaptureEnabled) {
            return false
        }
        val cameraInfo = currentState.getCurrentCameraInfo() ?: return false
        // Position Lock can deliberately pin the physical sensor selected by the current
        // zoom ratio, even while the normal preview is using logical multi-camera switching.
        val selectedPhysicalCameraCanBePinned =
            resolveRequestedPhysicalCameraId(currentState, cameraInfo)?.isNotBlank() == true
        val normalVideoPairLensSelected =
            normalVideoUltraWideInstantSwitchActive &&
                isCurrentNormalVideoPairLens(cameraInfo)
        val explicitlyPinnedPhysicalLens =
            activeOutputPhysicalCameraId?.isNotBlank() == true &&
                !logicalMultiCameraAutoSwitchActive
        if (
            shouldUseLogicalMultiCameraZoom(currentState, cameraInfo) &&
            !selectedPhysicalCameraCanBePinned &&
            !normalVideoPairLensSelected && !explicitlyPinnedPhysicalLens
        ) return false
        val activeArray = cameraInfo.activeArraySize ?: return false
        return activeArray.width() > 0 && activeArray.height() > 0
    }

    /**
     * Validate the exact sensor that Position Lock will pin. In the normal instant-switch
     * path the visible ultra-wide may be a standby output, so the active MGC calibration
     * can still belong to the main sensor; validate the selected sensor directly instead.
     */
    private fun isTopOffPositionLockCameraSupported(): Boolean {
        val state = _state.value
        val cameraInfo = state.getCurrentCameraInfo() ?: return false
        val openCameraId = runCatching { cameraInfo.getOpenCameraId() }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return false
        val physicalCameraId = resolveRequestedPhysicalCameraId(state, cameraInfo)
            ?.takeIf { it.isNotBlank() }
        val capabilityCameraId = physicalCameraId ?: openCameraId
        val sensorCharacteristics = getCameraCharacteristicsOrNull(
            capabilityCameraId,
            "MGC Position Lock selected sensor",
        ) ?: return false
        val timingCharacteristics = if (capabilityCameraId == openCameraId) {
            sensorCharacteristics
        } else {
            getCameraCharacteristicsOrNull(
                openCameraId,
                "MGC Position Lock timestamp source",
            ) ?: return false
        }
        if (!realtimeStabilizationCoordinator.isCameraSupported(
                sensorCharacteristics,
                timingCharacteristics,
            )
        ) return false
        if (capabilityCameraId == currentStabilizationCameraId() &&
            !realtimeStabilizationCoordinator.isCurrentCameraSupported
        ) return false
        if (capabilityCameraId in enhancedStabilizationUnavailableCameraIds) return false

        val activeCapabilityCameraId = activeOutputPhysicalCameraId
            ?.takeIf { it.isNotBlank() && !logicalMultiCameraAutoSwitchActive }
            ?: activeOpenCameraId
        if (capabilityCameraId == activeCapabilityCameraId) {
            return state.videoCapabilities.enhancedStabilizationInputSizesByResolution[
                state.videoConfig.resolution
            ]?.let { it.width > 0 && it.height > 0 } == true
        }

        val cacheKey = "${capabilityCameraId}|${state.videoConfig.hashCode()}"
        if (topOffPositionLockCapabilityCacheKey == cacheKey) {
            return topOffPositionLockCapabilityCacheAvailable
        }
        val candidateCapabilities = runCatching {
            VideoCapabilitiesResolver.resolve(
                characteristics = sensorCharacteristics,
                requestedConfig = state.videoConfig,
                algorithmicStabilizationSupported = true,
            ).capabilities
        }.getOrNull()
        val available = candidateCapabilities
            ?.enhancedStabilizationInputSizesByResolution
            ?.get(state.videoConfig.resolution)
            ?.let { it.width > 0 && it.height > 0 } == true
        topOffPositionLockCapabilityCacheKey = cacheKey
        topOffPositionLockCapabilityCacheAvailable = available
        return available
    }


    /**
     * Capture a complete rejection snapshot for the selected ultra-wide lens.
     * This is diagnostic-only: it does not change camera routing or stabilization state.
     */
    private fun logUltraWidePositionLockDiagnostics() {
        val tag = POSITION_LOCK_ULTRA_WIDE_DIAGNOSTIC_TAG
        val current = _state.value
        val cameraInfo = current.getCurrentCameraInfo()
        val openCameraId = runCatching { cameraInfo?.getOpenCameraId() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        val physicalCameraId = cameraInfo?.let {
            runCatching { resolveRequestedPhysicalCameraId(current, it) }.getOrNull()
        }?.takeIf { it.isNotBlank() }
        val capabilityCameraId = physicalCameraId ?: openCameraId
        val sensorCharacteristics = capabilityCameraId?.let {
            getCameraCharacteristicsOrNull(it, "Ultra-wide Position Lock diagnostic sensor")
        }
        val timingCharacteristics = when {
            sensorCharacteristics == null -> null
            capabilityCameraId == openCameraId -> sensorCharacteristics
            openCameraId == null -> null
            else -> getCameraCharacteristicsOrNull(
                openCameraId,
                "Ultra-wide Position Lock diagnostic timestamp source",
            )
        }
        val activeArray = sensorCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val yuvOutputSizes = sensorCharacteristics?.let { characteristics ->
            runCatching {
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(ImageFormat.YUV_420_888)
                    ?.toList()
                    .orEmpty()
            }.getOrDefault(emptyList())
        }.orEmpty()
        val physicalSize = sensorCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val lensFacing = sensorCharacteristics?.get(CameraCharacteristics.LENS_FACING)
            ?: timingCharacteristics?.get(CameraCharacteristics.LENS_FACING)
        val timestampSource = timingCharacteristics?.get(
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE,
        ) ?: CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN
        val staticMgcSupported = sensorCharacteristics != null && timingCharacteristics != null &&
            realtimeStabilizationCoordinator.isCameraSupported(
                sensorCharacteristics,
                timingCharacteristics,
            )
        val isCurrentCalibration = capabilityCameraId != null &&
            capabilityCameraId == currentStabilizationCameraId()
        val currentMgcSupported = isCurrentCalibration &&
            realtimeStabilizationCoordinator.isCurrentCameraSupported
        val activeCapabilityCameraId = activeOutputPhysicalCameraId
            ?.takeIf { it.isNotBlank() && !logicalMultiCameraAutoSwitchActive }
            ?: activeOpenCameraId
        val selectedSensorIsActive = capabilityCameraId != null &&
            capabilityCameraId == activeCapabilityCameraId
        val sensorVideoCapabilities = when {
            sensorCharacteristics == null -> null
            selectedSensorIsActive -> current.videoCapabilities
            else -> runCatching {
                VideoCapabilitiesResolver.resolve(
                    characteristics = sensorCharacteristics,
                    requestedConfig = current.videoConfig,
                    algorithmicStabilizationSupported = true,
                ).capabilities
            }.getOrNull()
        }
        val inputSize = sensorVideoCapabilities
            ?.enhancedStabilizationInputSizesByResolution
            ?.get(current.videoConfig.resolution)
        val hasInputSize = inputSize?.let { it.width > 0 && it.height > 0 } == true
        val topOffZoomAvailable = isTopOffZoomAvailable()
        val isRecording = current.videoRecordingState.isRecording ||
            videoRecorder.isRecording() ||
            spatialVideoRecorder.isRecording() ||
            spatialVideoTelephotoRecorder.isRecording()
        val isUltraWide = cameraInfo?.let {
            it.lensFacing == CameraCharacteristics.LENS_FACING_BACK &&
                (it.lensType == LensType.BACK_ULTRA_WIDE ||
                    (it.intrinsicZoomRatio.isFinite() && it.intrinsicZoomRatio in 0.2f..0.85f))
        } == true

        PLog.i(
            tag,
            "REQUEST ultraWide=$isUltraWide captureMode=${current.captureMode} " +
                "fps=${current.videoConfig.fps} resolution=${current.videoConfig.resolution} " +
                "recording=$isRecording processing=${current.videoRecordingState.isProcessing} " +
                "spatialVideo=$spatialVideoEnabled dualCapture=$dualCaptureEnabled " +
                "dualAspect=$dualAspectRecordingActive zoomAvailable=$topOffZoomAvailable",
        )
        PLog.i(
            tag,
            "CAMERAS selectedId=${cameraInfo?.cameraId} lens=${cameraInfo?.lensType} " +
                "intrinsicZoom=${cameraInfo?.intrinsicZoomRatio} minZoom=${cameraInfo?.minZoom} " +
                "logicalOpenId=$openCameraId requestedPhysicalId=$physicalCameraId " +
                "capabilityId=$capabilityCameraId activeOpenId=$activeOpenCameraId " +
                "activeOutputPhysicalId=$activeOutputPhysicalCameraId " +
                "normalUltraWideBackgroundStream=$normalVideoUltraWideInstantSwitchActive " +
                "logicalAutoSwitch=$logicalMultiCameraAutoSwitchActive " +
                "physicalIds=${cameraInfo?.physicalCameraIds}",
        )
        PLog.i(
            tag,
            "MGC gyroAvailable=${realtimeStabilizationCoordinator.isGyroscopeAvailable} " +
                "staticCameraSupported=$staticMgcSupported currentCalibration=$isCurrentCalibration " +
                "currentCalibrationSupported=$currentMgcSupported lensFacing=$lensFacing " +
                "timestampSource=$timestampSource activeArray=$activeArray physicalSizeMm=$physicalSize " +
                "enhancedUnavailable=${capabilityCameraId != null && capabilityCameraId in enhancedStabilizationUnavailableCameraIds}",
        )
        PLog.i(
            tag,
            "STREAM selectedSensorIsActive=$selectedSensorIsActive " +
                "inputSize=${inputSize?.let { "${it.width}x${it.height}" } ?: "missing"} " +
                "inputAvailable=$hasInputSize resolution=${current.videoConfig.resolution} " +
                "yuvCandidates=$yuvOutputSizes " +
                "activeSizes=${sensorVideoCapabilities?.enhancedStabilizationInputSizesByResolution}",
        )

        val reasons = buildList {
            if (current.captureMode != CaptureMode.VIDEO) add("Capture mode is not VIDEO: ${current.captureMode}")
            if (current.videoConfig.fps == VideoFpsPreset.FPS_120) add("120 FPS is not supported by Position Lock")
            if (isRecording) add("A video recorder is already active")
            if (current.videoRecordingState.isProcessing) add("A recording is still processing")
            if (spatialVideoEnabled || dualCaptureEnabled || dualAspectRecordingActive) {
                add("Spatial Video, Dual Capture, or Dual Aspect is active")
            }
            if (!topOffZoomAvailable) add("Top-Off Zoom prerequisite is unavailable for the current camera/mode")
            if (cameraInfo == null) add("No selected CameraInfo is available")
            if (openCameraId == null) add("Logical/open Camera2 ID could not be resolved")
            if (capabilityCameraId == null) add("Selected physical sensor ID could not be resolved")
            if (sensorCharacteristics == null) add("Selected ultra-wide CameraCharacteristics could not be read")
            if (timingCharacteristics == null) add("Timestamp-source CameraCharacteristics could not be read")
            if (sensorCharacteristics != null) {
                if (!realtimeStabilizationCoordinator.isGyroscopeAvailable) add("Device gyroscope is unavailable")
                if (activeArray == null) add("Ultra-wide active array metadata is missing")
                if (physicalSize == null) add("Ultra-wide physical sensor size metadata is missing")
                if (lensFacing != CameraCharacteristics.LENS_FACING_BACK &&
                    lensFacing != CameraCharacteristics.LENS_FACING_FRONT
                ) add("MGC does not accept this lens-facing value: $lensFacing")
            }
            if (timestampSource != CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) {
                add("Timestamp source is not REALTIME: $timestampSource")
            }
            if (sensorCharacteristics != null && timingCharacteristics != null && !staticMgcSupported) {
                add("MGC static sensor/timestamp capability validation failed")
            }
            if (isCurrentCalibration && !currentMgcSupported) {
                add("Current MGC calibration is unsupported or has a HAL stabilization conflict")
            }
            if (capabilityCameraId != null && capabilityCameraId in enhancedStabilizationUnavailableCameraIds) {
                add("Selected sensor is in enhancedStabilizationUnavailableCameraIds")
            }
            if (!hasInputSize) {
                if (yuvOutputSizes.isEmpty()) {
                    add("Selected sensor exposes no YUV_420_888 output stream")
                } else {
                    add(
                        "No YUV_420_888 stream can supply ${current.videoConfig.resolution}; " +
                            "available candidates=$yuvOutputSizes"
                    )
                }
            }
        }
        if (reasons.isEmpty()) {
            PLog.w(tag, "Availability returned false without a single explicit rejection predicate; inspect the snapshot above")
        } else {
            reasons.forEach { PLog.w(tag, "REJECTED: $it") }
        }
    }

    fun isTopOffPositionLockAvailable(): Boolean {
        val current = _state.value
        if (current.captureMode != CaptureMode.VIDEO ||
            current.videoConfig.fps == VideoFpsPreset.FPS_120 ||
            current.videoRecordingState.isRecording || current.videoRecordingState.isProcessing ||
            videoRecorder.isRecording() || spatialVideoRecorder.isRecording() ||
            spatialVideoTelephotoRecorder.isRecording() || spatialVideoEnabled ||
            dualCaptureEnabled || dualAspectRecordingActive ||
            !isTopOffZoomAvailable()
        ) return false
        return isTopOffPositionLockCameraSupported()
    }

    /**
     * Reopen Camera2 so logical zoom is disabled and the selected physical sensor is
     * applied consistently to preview, MGC YUV input, Camera2 metadata and calibration.
     */
    private fun reconfigureCameraForPositionLockModeChange(reason: String) {
        if (_topOffPositionLockActive.value && normalVideoUltraWideInstantSwitchActive) {
            clearNormalVideoUltraWideInstantSwitchRuntime(
                "MGC Position Lock requires one active physical lens: $reason",
                reopenSelectedCamera = false,
            )
        } else if (_topOffPositionLockActive.value && !spatialVideoEnabled) {
            // Defensive cleanup for a stale concurrent standby left by a previous session.
            stopSpatialVideoStandbyCamera("MGC Position Lock requires one active camera: $reason")
        }

        val texture = previewSurfaceTexture
        if (texture != null) {
            openCamera(texture, preserveVideoRecording = false)
        } else if (cameraDevice != null && previewSurface != null) {
            createPreviewSession(openGeneration = cameraOpenGeneration)
        } else {
            PLog.w(TAG, "Position Lock mode changed before the preview surface was ready: $reason")
        }
    }

    /** Activates MGC's fixed-reference pose renderer for the live preview. */
    fun setTopOffPositionLockActive(enabled: Boolean): Boolean {
        if (enabled && !isTopOffPositionLockAvailable()) {
            val selectedCamera = _state.value.getCurrentCameraInfo()
            val selectedUltraWide = selectedCamera?.let {
                it.lensFacing == CameraCharacteristics.LENS_FACING_BACK &&
                    (it.lensType == LensType.BACK_ULTRA_WIDE ||
                        (it.intrinsicZoomRatio.isFinite() && it.intrinsicZoomRatio in 0.2f..0.85f))
            } == true
            if (selectedUltraWide) {
                logUltraWidePositionLockDiagnostics()
            } else {
                PLog.w(TAG, "Position Lock rejected: selected physical sensor or input stream is unsupported")
            }
            return false
        }
        if (!enabled && (_state.value.videoRecordingState.isRecording || videoRecorder.isRecording())) {
            PLog.w(TAG, "Position Lock remains active until the current recording stops")
            return false
        }
        if (_topOffPositionLockActive.value == enabled) return true

        _topOffPositionLockActive.value = enabled
        PLog.i(
            TAG,
            "MGC Position Lock " + (if (enabled) "enabled" else "disabled") + "; " +
                "selected=${_state.value.currentCameraId}, " +
                "physical=${resolveRequestedPhysicalCameraId(_state.value, _state.value.getCurrentCameraInfo())}",
        )

        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post {
                reconfigureCameraForPositionLockModeChange(
                    if (enabled) "enabled" else "disabled",
                )
            }
        } else {
            reconfigureCameraForPositionLockModeChange(
                if (enabled) "enabled" else "disabled",
            )
        }
        return true
    }

    private fun updateTopOffZoomLockedRatio(ratio: Float, reason: String): Float {
        val safeRatio = if (ratio.isFinite()) ratio.coerceAtLeast(1f) else 1f
        _topOffZoomLockedRatio.value = safeRatio
        specialVideoPreferences.edit().putFloat("top_off_zoom_locked_ratio", safeRatio).apply()
        PLog.i(
            "TopOffZoom",
            "ZOOM_LOCK reason=$reason ratio=$safeRatio base=${_state.value.zoomRatio} factor=${_topOffZoomFactor.value}"
        )
        return safeRatio
    }

    private fun resolveTopOffZoomLockedRatio(baseZoomRatio: Float): Float {
        val current = _topOffZoomLockedRatio.value
        if (current.isFinite() && current >= 1f) return current
        return updateTopOffZoomLockedRatio(
            baseZoomRatio.coerceAtLeast(1f) * _topOffZoomFactor.value,
            "initialise",
        )
    }

    private fun logTopOffZoomRequest(
        baseZoomRatio: Float,
        lockedZoomRatio: Float,
        panX: Float,
        panY: Float,
        activeRect: Rect?,
        cropAspectRatio: Float,
        sensorOrientation: Int,
        softwareCrop: Boolean = false,
    ) {
        val geometry = activeRect?.let {
            ZoomCropGeometry.calculate(
                sourceWidth = it.width().toFloat(),
                sourceHeight = it.height().toFloat(),
                outputAspectRatio = cropAspectRatio,
                zoomRatio = lockedZoomRatio,
                panX = panX,
                panY = panY,
            )
        }
        val signature = listOf(
            baseZoomRatio.toString(), lockedZoomRatio.toString(),
            panX.toString(), panY.toString(),
            activeRect?.toShortString() ?: "no-active-array",
            cropAspectRatio.toString(), sensorOrientation.toString(),
        ).joinToString("|")
        if (signature == lastTopOffZoomDiagnosticSignature) return
        lastTopOffZoomDiagnosticSignature = signature
        PLog.d(
            "TopOffZoom",
            "CAMERA_REQUEST base=$baseZoomRatio locked=$lockedZoomRatio pan=($panX,$panY) " +
                "sensorOrientation=$sensorOrientation outputAspect=$cropAspectRatio " +
                "activeArray=${activeRect?.toShortString() ?: "missing"} " +
                "normalizedCrop=${geometry?.let { "(${it.left},${it.top},${it.right},${it.bottom})" } ?: "not-built"} " +
                "mode=${if (softwareCrop) "GPU_SOFTWARE_CROP+FULL_SENSOR" else if (activeRect != null) "SCALER_CROP_REGION" else "NO_ACTIVE_ARRAY"}"
        )
    }

    fun setTopOffZoomEnabled(enabled: Boolean) {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post { setTopOffZoomEnabled(enabled) }
            return
        }
        if (
            videoRecorder.isRecording() ||
            spatialVideoRecorder.isRecording() ||
            spatialVideoTelephotoRecorder.isRecording() ||
            _state.value.videoRecordingState.isRecording ||
            _state.value.videoRecordingState.isProcessing
        ) {
            PLog.w("TopOffZoom", "ENABLE rejected: recording/processing is active")
            return
        }
        if (enabled && !isTopOffZoomAvailable()) {
            PLog.w("TopOffZoom", "ENABLE rejected: feature unavailable for current camera/mode")
            return
        }
        if (enabled) {
            updateTopOffZoomLockedRatio(
                _state.value.zoomRatio.coerceAtLeast(1f) * _topOffZoomFactor.value,
                "enabled",
            )
        }
        PLog.i("TopOffZoom", "ENABLE changed=$enabled")
        _topOffZoomEnabled.value = enabled
        specialVideoPreferences.edit().putBoolean("top_off_zoom_enabled", enabled).apply()
        reapplyTopOffZoomRequest()
    }

    fun setTopOffZoomFactor(factor: Float) {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post { setTopOffZoomFactor(factor) }
            return
        }
        if (
            videoRecorder.isRecording() ||
            spatialVideoRecorder.isRecording() ||
            spatialVideoTelephotoRecorder.isRecording() ||
            _state.value.videoRecordingState.isRecording ||
            _state.value.videoRecordingState.isProcessing
        ) {
            PLog.w("TopOffZoom", "FACTOR rejected: recording/processing is active")
            return
        }

        val safeFactor = if (factor.isFinite()) factor.coerceIn(1f, TopOffPanPreset.MAX_ZOOM_RATIO) else 2f
        _topOffZoomFactor.value = safeFactor
        specialVideoPreferences.edit().putFloat("top_off_zoom_factor", safeFactor).apply()
        updateTopOffZoomLockedRatio(
            _state.value.zoomRatio.coerceAtLeast(1f) * safeFactor,
            "factor-selected",
        )
        reapplyTopOffZoomRequest()
    }

    fun setTopOffZoomPan(panX: Float, panY: Float) {
        val requestedAtNs = SystemClock.elapsedRealtimeNanos()
        val isRecording = videoRecorder.isRecording() ||
            _state.value.videoRecordingState.isRecording
        val handler = cameraHandler
        // Recording crop updates are thread-safe and dispatched by VideoRecorder to its render
        // dispatcher. Do not queue every joystick sample behind Camera2 request work.
        if (!isRecording && handler != null && Looper.myLooper() != handler.looper) {
            handler.post { applyTopOffZoomPan(panX, panY, requestedAtNs) }
            return
        }
        applyTopOffZoomPan(panX, panY, requestedAtNs)
    }

    private fun applyTopOffZoomPan(panX: Float, panY: Float, requestedAtNs: Long) {
        if (_topOffPositionLockActive.value) return
        val current = _state.value
        if (
            (spatialVideoRecorder.isRecording() && !dualAspectRecordingActive) ||
            spatialVideoTelephotoRecorder.isRecording() ||
            current.videoRecordingState.isProcessing
        ) {
            PLog.w("TopOffZoom", "PAN rejected: incompatible spatial recording/processing is active")
            return
        }

        val safePanX = if (panX.isFinite()) panX.coerceIn(-1f, 1f) else 0f
        val safePanY = if (panY.isFinite()) panY.coerceIn(-1f, 1f) else 0f
        _topOffZoomPanX.value = safePanX
        _topOffZoomPanY.value = safePanY
        _topOffZoomRollDegrees.value = 0f
        specialVideoPreferences.edit()
            .putFloat("top_off_zoom_pan_x", safePanX)
            .putFloat("top_off_zoom_pan_y", safePanY)
            .putFloat("top_off_zoom_roll_degrees", 0f)
            .apply()

        val recording = current.videoRecordingState.isRecording || videoRecorder.isRecording()
        val canApplySoftwareCrop = _topOffZoomEnabled.value &&
            current.captureMode == CaptureMode.VIDEO &&
            !spatialVideoEnabled && !dualCaptureEnabled
        if (recording && canApplySoftwareCrop) {
            val zoom = (
                _topOffZoomLockedRatio.value.takeIf { it.isFinite() && it >= 1f }
                    ?: (current.zoomRatio.coerceAtLeast(1f) * _topOffZoomFactor.value)
                ).coerceIn(1f, 20f)
            if (videoRecorder.isRecording()) {
                videoRecorder.updateCameraInputCrop(zoom, safePanX, safePanY, 0f)
            }
            if (dualAspectRecordingActive && spatialVideoRecorder.isRecording()) {
                spatialVideoRecorder.updateCameraInputCrop(zoom, safePanX, safePanY, 0f)
            }
        } else if (!recording) {
            // Camera2 request updates are only needed in the idle preview path. During video,
            // the preview follows StateFlow and the encoder receives the matching crop directly.
            reapplyTopOffZoomRequest()
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastTopOffZoomPanDiagnosticAtMs >= 120L) {
            lastTopOffZoomPanDiagnosticAtMs = now
            PLog.d(
                "TopOffZoom",
                "PAN accepted pan=($safePanX,$safePanY) recording=$recording " +
                    "lockedZoom=${_topOffZoomLockedRatio.value} baseZoom=${current.zoomRatio}"
            )
        }
        _topOffPanLatencyMs.value =
            ((SystemClock.elapsedRealtimeNanos() - requestedAtNs).coerceAtLeast(0L) / 1_000_000f)
    }

    /**
     * Update the software crop for one preset animation frame. StateFlows update the preview;
     * the active video encoder receives the same crop directly so waypoint playback continues
     * in the saved recording without rebuilding the Camera2 session.
     */
    fun setTopOffZoomPresetPoint(zoomRatio: Float, panX: Float, panY: Float) {
        val requestedAtNs = SystemClock.elapsedRealtimeNanos()
        if (_topOffPositionLockActive.value || !_topOffZoomEnabled.value) return

        val current = _state.value
        if (
            (spatialVideoRecorder.isRecording() && !dualAspectRecordingActive) ||
            spatialVideoTelephotoRecorder.isRecording() ||
            current.videoRecordingState.isProcessing
        ) {
            return
        }

        val baseZoom = current.zoomRatio.coerceAtLeast(1f)
        val requestedZoom = zoomRatio.takeIf { it.isFinite() }?.coerceAtLeast(baseZoom)
            ?: (baseZoom * 2f)
        val safeFactor = (requestedZoom / baseZoom).coerceIn(1f, TopOffPanPreset.MAX_ZOOM_RATIO)
        val safePanX = panX.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
        val safePanY = panY.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
        _topOffZoomFactor.value = safeFactor
        _topOffZoomLockedRatio.value = baseZoom * safeFactor
        _topOffZoomPanX.value = safePanX
        _topOffZoomPanY.value = safePanY
        _topOffZoomRollDegrees.value = 0f

        val recording = current.videoRecordingState.isRecording || videoRecorder.isRecording()
        val canApplySoftwareCrop = _topOffZoomEnabled.value &&
            current.captureMode == CaptureMode.VIDEO &&
            !spatialVideoEnabled && !dualCaptureEnabled
        if (recording && canApplySoftwareCrop) {
            val encoderZoom = _topOffZoomLockedRatio.value.coerceIn(1f, 20f)
            if (videoRecorder.isRecording()) {
                videoRecorder.updateCameraInputCrop(encoderZoom, safePanX, safePanY, 0f)
            }
            if (dualAspectRecordingActive && spatialVideoRecorder.isRecording()) {
                spatialVideoRecorder.updateCameraInputCrop(encoderZoom, safePanX, safePanY, 0f)
            }
        }

        _topOffPanLatencyMs.value =
            ((SystemClock.elapsedRealtimeNanos() - requestedAtNs).coerceAtLeast(0L) / 1_000_000f)
    }

    /** Save the final animated crop state once, after playback completes or is cancelled. */
    fun finishTopOffPanPresetPlayback() {
        specialVideoPreferences.edit()
            .putFloat("top_off_zoom_factor", _topOffZoomFactor.value)
            .putFloat("top_off_zoom_locked_ratio", _topOffZoomLockedRatio.value)
            .putFloat("top_off_zoom_pan_x", _topOffZoomPanX.value)
            .putFloat("top_off_zoom_pan_y", _topOffZoomPanY.value)
            .putFloat("top_off_zoom_roll_degrees", _topOffZoomRollDegrees.value)
            .apply()
    }

    /**
     * Frame-rate gyro updates. Unlike the manual pan control this remains valid during
     * recording and updates the dedicated encoder renderer without rebuilding Camera2.
     */
    fun setTopOffGyroPanCorrection(panX: Float, panY: Float, rollDegrees: Float) {
        if (_topOffPositionLockActive.value) return
        val safePanX = panX.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
        val safePanY = panY.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
        val safeRoll = rollDegrees.takeIf { it.isFinite() }?.coerceIn(-20f, 20f) ?: 0f
        _topOffZoomPanX.value = safePanX
        _topOffZoomPanY.value = safePanY
        _topOffZoomRollDegrees.value = safeRoll

        val current = _state.value
        val canApplySoftwareCrop = _topOffZoomEnabled.value &&
            current.captureMode == CaptureMode.VIDEO &&
            !spatialVideoEnabled && !dualCaptureEnabled
        if (!canApplySoftwareCrop) return

        val zoom = (
            _topOffZoomLockedRatio.value.takeIf { it.isFinite() && it >= 1f }
                ?: (current.zoomRatio.coerceAtLeast(1f) * _topOffZoomFactor.value)
            ).coerceIn(1f, 20f)
        if (videoRecorder.isRecording()) {
            videoRecorder.updateCameraInputCrop(zoom, safePanX, safePanY, safeRoll)
        }
        if (dualAspectRecordingActive && spatialVideoRecorder.isRecording()) {
            spatialVideoRecorder.updateCameraInputCrop(zoom, safePanX, safePanY, safeRoll)
        }
        _topOffPanLatencyMs.value = 0f
    }

    fun finishTopOffGyroPanPreset(panX: Float, panY: Float, rollDegrees: Float) {
        val safePanX = panX.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
        val safePanY = panY.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
        val safeRoll = rollDegrees.takeIf { it.isFinite() }?.coerceIn(-20f, 20f) ?: 0f
        _topOffZoomPanX.value = safePanX
        _topOffZoomPanY.value = safePanY
        _topOffZoomRollDegrees.value = safeRoll
        specialVideoPreferences.edit()
            .putFloat("top_off_zoom_factor", _topOffZoomFactor.value)
            .putFloat("top_off_zoom_locked_ratio", _topOffZoomLockedRatio.value)
            .putFloat("top_off_zoom_pan_x", safePanX)
            .putFloat("top_off_zoom_pan_y", safePanY)
            .putFloat("top_off_zoom_roll_degrees", safeRoll)
            .apply()
    }

    fun setTopOffJoystickSpeed(speed: Float) {
        val safeSpeed = speed.takeIf { it.isFinite() }?.coerceIn(0.25f, 4f) ?: 1f
        _topOffJoystickSpeed.value = safeSpeed
        specialVideoPreferences.edit().putFloat("top_off_joystick_speed", safeSpeed).apply()
    }

    fun setTopOffShowLatency(show: Boolean) {
        _topOffShowLatency.value = show
        specialVideoPreferences.edit().putBoolean("top_off_show_latency", show).apply()
    }

    fun saveTopOffPanPreset(preset: TopOffPanPreset) {
        val normalized = preset.normalized()
        if (normalized.points.size < 2) return
        val updated = (_topOffPanPresets.value.filterNot { it.id == normalized.id } + normalized)
            .takeLast(64)
        _topOffPanPresets.value = updated
        specialVideoPreferences.edit()
            .putString("top_off_pan_presets", TopOffPanPresetCodec.encode(updated))
            .apply()
    }

    fun deleteTopOffPanPreset(id: String) {
        val updated = _topOffPanPresets.value.filterNot { it.id == id }
        _topOffPanPresets.value = updated
        specialVideoPreferences.edit()
            .putString("top_off_pan_presets", TopOffPanPresetCodec.encode(updated))
            .apply()
    }

    private fun reapplyTopOffZoomRequest() {
        val builder = previewRequestBuilder ?: return
        builder.apply {
            applyBaseCameraSettings(this, isCapture = false, currentState = _state.value)
        }
        // Position Lock's preview crop can change independently of Camera2's zoom request.
        // Rebuild the AE region at the same time so exposure follows the new visible frame.
        applyMeteringRegions()
        updatePreview()
    }

    fun setSpatialVideoPreviewSurfaceTexture(surfaceTexture: SurfaceTexture?) {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post { setSpatialVideoPreviewSurfaceTexture(surfaceTexture) }
            return
        }
        if (spatialVideoStandbyPreviewSurfaceTexture === surfaceTexture) return
        spatialVideoStandbyPreviewSurface?.release()
        spatialVideoStandbyPreviewSurface = null
        spatialVideoStandbyPreviewSurfaceTexture = surfaceTexture
        val wideId = currentUltraWideStandbyCameraId()
        if (surfaceTexture != null) {
            val targetSize = spatialVideoStandbyInputSize
                ?: wideId?.let { resolveSpatialVideoInputSize(it, _state.value.currentPreviewSize) }
                ?: _state.value.currentPreviewSize
            runCatching {
                surfaceTexture.setDefaultBufferSize(targetSize.width, targetSize.height)
                spatialVideoStandbyPreviewSurface = Surface(surfaceTexture)
            }.onFailure { PLog.e(TAG, "Ultra-wide standby preview surface creation failed", it) }
            _spatialVideoPreviewStreamSize.value = targetSize
        } else {
            _spatialVideoPreviewStreamSize.value = null
        }
        if (isUltraWideStandbyPreviewActive()) {
            if (currentUltraWideStandbyStreamingMode() == SpatialVideoStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL) {
                createPreviewSession(openGeneration = cameraOpenGeneration)
            } else if (wideId != null) {
                if (spatialVideoStandbyCameraDevice == null && surfaceTexture != null) {
                    startSpatialVideoStandbyCamera(wideId)
                } else if (spatialVideoStandbyCameraDevice != null) {
                    createSpatialVideoStandbySession("ultra-wide preview surface changed")
                }
            }
        }
    }

    fun setSpatialVideoEnabled(enabled: Boolean) {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post { setSpatialVideoEnabled(enabled) }
            return
        }
        if (spatialVideoEnabled == enabled) return
        if (videoRecorder.isRecording() || _state.value.videoRecordingState.isProcessing) {
            onCameraError?.invoke(-1, "Stop recording before changing Special Video.", false)
            return
        }
        if (enabled) {
            if (_state.value.captureMode != CaptureMode.VIDEO) {
                onCameraError?.invoke(-1, "Special Video is only available in Video mode.", false)
                return
            }
            val pair = resolveSpatialVideoConfiguration()
            if (pair == null) {
                val reason = spatialVideoUnsupportedReason
                    ?: "rear main 1x + ultra-wide 0.5x Camera2 pair was not discovered"
                onCameraError?.invoke(-1, "Special Video unsupported: $reason", false)
                return
            }
            if (normalVideoUltraWideInstantSwitchActive) {
                clearNormalVideoUltraWideInstantSwitchRuntime(
                    "Special Video enabled",
                    reopenSelectedCamera = false,
                )
            }
            val activeId = activeOpenCameraId.takeIf { it.isNotBlank() }
                ?: _state.value.getCurrentCameraInfo()?.let { runCatching { it.getOpenCameraId() }.getOrNull() }
            // The selected rear lens is not required to already be the 1x stream.
            // Switch the primary to the resolved main ID below, as VCamera does.
            spatialVideoMainPhysicalCameraId = pair.mainPhysicalCameraId
            spatialVideoUltraWidePhysicalCameraId = pair.ultraWideCameraId
            spatialVideoPrimaryCameraId = pair.primaryCameraId
            spatialVideoStreamingMode = pair.mode
            spatialVideoEnabled = true
            _spatialVideoEnabled.value = true
            if (dualCaptureEnabled) setDualCaptureEnabled(false)
            activeOutputPhysicalCameraId = when {
                pair.mode == SpatialVideoStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL ->
                    pair.mainPhysicalCameraId
                pair.logicalParentCameraId != null ->
                    null // Primary is opened directly by its public physical Camera2 ID.
                else ->
                    pair.mainCameraInfo.outputPhysicalCameraId
            }
            logicalMultiCameraAutoSwitchActive = false
            // Special Video always starts on its primary 1x stream. The ultra-wide
            // CameraDevice is already running in parallel and is selected as a preview source
            // only when the user taps its lens control.
            _state.value = _state.value.copy(
                currentCameraId = pair.mainCameraInfo.cameraId,
                currentLensType = pair.mainCameraInfo.lensType,
                zoomRatio = 1f,
                zoomPanX = 0f,
                zoomPanY = 0f,
                zoomPanEnabled = false,
            )
            PLog.i(
                TAG,
                "Special Video enabled: mode=${pair.mode}, primary=${pair.primaryCameraId}, " +
                    "logicalParent=${pair.logicalParentCameraId}, mainPhysical=${pair.mainPhysicalCameraId}, " +
                    "ultraWide=${pair.ultraWideCameraId}"
            )
            val primaryNeedsReopen = activeId != null && activeId != pair.primaryCameraId
            val currentPreviewTexture = previewSurfaceTexture
            if (primaryNeedsReopen && currentPreviewTexture == null) {
                val reason = "Cannot switch Special Video primary to physical main ID: preview SurfaceTexture is unavailable"
                PLog.e(
                    TAG,
                    "Special Video enable failed: mode=${pair.mode} main=${pair.mainPhysicalCameraId} " +
                        "ultraWide=${pair.ultraWideCameraId} reason=$reason"
                )
                clearSpecialVideoRuntimeState(reason)
                onCameraError?.invoke(-1, reason, false)
                return
            }
            if (primaryNeedsReopen && currentPreviewTexture != null) {
                PLog.i(
                    TAG,
                    "Special Video reopening primary on physical main ID: mode=${pair.mode} " +
                        "main=${pair.mainPhysicalCameraId} ultraWide=${pair.ultraWideCameraId} " +
                        "oldOpenId=$activeId newOpenId=${pair.primaryCameraId}"
                )
                // closeCameraInternal() must retain the Special Video pair while this
                // intentional device handoff replaces the logical parent with the main sensor.
                spatialVideoPrimaryReopenInProgress = true
                // openCameraNow() reads spatialVideoPrimaryCameraId and opens the main physical
                // ID. Its onOpened callback pre-opens the ultra-wide physical CameraDevice.
                openCamera(currentPreviewTexture)
            } else {
                currentPreviewTexture?.let { createPreviewSession(openGeneration = cameraOpenGeneration) }
                if (pair.mode != SpatialVideoStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL &&
                    cameraDevice != null
                ) {
                    startSpatialVideoStandbyCamera(pair.ultraWideCameraId)
                }
            }
        } else {
            val selectedOpenId = _state.value.getCurrentCameraInfo()
                ?.let { runCatching { it.getOpenCameraId() }.getOrNull() }
            val restoreLogicalParent =
                spatialVideoStreamingMode != SpatialVideoStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL &&
                    selectedOpenId != null && selectedOpenId != activeOpenCameraId
            spatialVideoRecorder.forceStop()
            clearSpecialVideoRuntimeState("Special Video disabled")
            val resumeNormalInstantSwitch =
                normalVideoUltraWideInstantSwitchEnabled &&
                    _state.value.captureMode == CaptureMode.VIDEO
            if (resumeNormalInstantSwitch) {
                activateNormalVideoUltraWideInstantSwitch(reopenPrimaryIfNeeded = false)
                previewSurfaceTexture?.let { texture ->
                    if (normalVideoPrimaryCameraId != activeOpenCameraId) {
                        openCamera(texture)
                    } else {
                        if (normalVideoStreamingMode != SpatialVideoStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL &&
                            cameraDevice != null
                        ) {
                            normalVideoUltraWidePhysicalCameraId?.let {
                                startSpatialVideoStandbyCamera(it)
                            }
                        }
                        createPreviewSession(openGeneration = cameraOpenGeneration)
                    }
                }
            } else {
                previewSurfaceTexture?.let { texture ->
                    if (restoreLogicalParent) {
                        PLog.i(
                            TAG,
                            "Special Video disabled: restoring selected CameraDevice $selectedOpenId " +
                                "from physical primary $activeOpenCameraId"
                        )
                        openCamera(texture)
                    } else {
                        createPreviewSession(openGeneration = cameraOpenGeneration)
                    }
                }
            }
        }
    }

    private fun resolveSpatialVideoInputSize(
        physicalCameraId: String,
        preferred: Size,
    ): Size? {
        return runCatching {
            // Camera2 exposes the physical output IDs from the logical parent, but the
            // SDK has no getPhysicalCameraCharacteristics() method. Query the physical ID
            // through CameraManager; if a vendor does not expose its characteristics this
            // way, the normal stream-size fallback below still applies.
            val characteristics = cameraManager.getCameraCharacteristics(physicalCameraId)
                ?: return null
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return null
            val sizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
            if (sizes.isEmpty()) return null
            val preferredRatio = preferred.width.toFloat() / preferred.height.coerceAtLeast(1)
            sizes.filter { it.width <= preferred.width && it.height <= preferred.height }
                .filter { it.width >= 1280 && it.height >= 720 }
                .minByOrNull { size ->
                    val ratioError = abs(size.width.toFloat() / size.height.coerceAtLeast(1) - preferredRatio)
                    ratioError * 1_000_000f - size.width.toLong() * size.height.toLong() / 1_000_000f
                }
                ?: sizes.minByOrNull { size ->
                    abs(size.width.toFloat() / size.height.coerceAtLeast(1) - preferredRatio) * 1_000_000f -
                        size.width.toLong() * size.height.toLong() / 1_000_000f
                }
        }.getOrNull()
    }

    @Synchronized
    private fun finalizeSpatialVideoCompanionIfReady() {
        val primary = spatialPrimaryFinishedUri ?: return
        if (!spatialVideoWideFinishReceived ||
            (spatialVideoVirtualTelephotoEnabled && !spatialVideoTelephotoFinishReceived)
        ) {
            return
        }

        val ultraWide = spatialUltraWideFinishedFile
        val telephoto = spatialTelephotoFinishedFile
        spatialPrimaryFinishedUri = null
        spatialUltraWideFinishedFile = null
        spatialTelephotoFinishedFile = null
        spatialVideoWideFinishReceived = false
        spatialVideoTelephotoFinishReceived = false
        val savedCallback = spatialVideoSavedCallback
        spatialVideoSavedCallback = null
        specialVideoRecordingTimeline.reset()

        val companionsReady = ultraWide?.let { it.isFile && it.length() > 0L } == true &&
            (telephoto == null || (telephoto.isFile && telephoto.length() > 0L))
        if (!companionsReady) {
            PLog.e(
                TAG,
                "Special Video companion missing or empty; retaining the main recording. " +
                    "0.5x=" + (ultraWide?.absolutePath ?: "missing") +
                    " 3.5x=" + (telephoto?.absolutePath ?: "disabled/missing"),
            )
            spatialVideoCompanionFile = null
            spatialVideoTelephotoCompanionFile = null
            cameraHandler?.post {
                if (cameraDevice != null && previewSurface != null) {
                    createPreviewSession(openGeneration = cameraOpenGeneration)
                }
                if (spatialVideoEnabled &&
                    spatialVideoStreamingMode != SpatialVideoStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL &&
                    spatialVideoStandbyCameraDevice != null
                ) {
                    createSpatialVideoStandbySession("Special Video sidecar finalization failed")
                }
            }
            completeSpatialVideoSave(savedCallback)
            return
        }

        // Release the in-progress file guards, then restore camera preview while the final MP4
        // is muxed off-thread. Gallery notification is deliberately deferred until mux completion.
        spatialVideoCompanionFile = null
        spatialVideoTelephotoCompanionFile = null
        cameraHandler?.post {
            if (cameraDevice != null && previewSurface != null) {
                createPreviewSession(openGeneration = cameraOpenGeneration)
            }
            if (spatialVideoEnabled &&
                spatialVideoStreamingMode != SpatialVideoStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL &&
                spatialVideoStandbyCameraDevice != null
            ) {
                createSpatialVideoStandbySession("all Special Video encoders finished")
            }
        }

        Thread({
            try {
                val muxed = SpatialVideoStore.muxSpecialVideoIntoPrimary(
                    context = context,
                    primaryVideoUri = primary,
                    ultraWideFile = ultraWide!!,
                    telephotoFile = telephoto,
                )
                if (muxed) {
                    ultraWide.delete()
                    telephoto?.delete()
                    PLog.i(
                        TAG,
                        "Special Video saved as one MP4 with multiple video tracks: " +
                            "main=1x, ultraWide=0.5x, telephoto=" + (telephoto != null),
                    )
                } else {
                    // Preserve legacy companion clips as a same-install fallback. The main 1x
                    // MP4 is left untouched when multi-track muxing fails.
                    PLog.e(
                        TAG,
                        "Special Video multi-track mux failed; keeping legacy companions at " +
                            ultraWide.absolutePath,
                    )
                }
            } catch (error: Exception) {
                PLog.e(TAG, "Special Video final multi-track mux failed", error)
            } finally {
                if (spatialVideoCompanionFile == ultraWide) spatialVideoCompanionFile = null
                if (telephoto != null && spatialVideoTelephotoCompanionFile == telephoto) {
                    spatialVideoTelephotoCompanionFile = null
                }
                completeSpatialVideoSave(savedCallback)
            }
        }, "SpatialVideoFinalize").start()
    }

    private fun completeSpatialVideoSave(savedCallback: (() -> Unit)?) {
        val complete = Runnable {
            _state.value = _state.value.copy(videoRecordingState = VideoRecordingState())
            savedCallback?.invoke()
        }
        cameraHandler?.post(complete) ?: complete.run()
    }

    @SuppressLint("MissingPermission")
    private fun startSpatialVideoStandbyCamera(cameraId: String): Boolean {
        val handler = cameraHandler ?: return false
        if (Looper.myLooper() != handler.looper) {
            handler.post { startSpatialVideoStandbyCamera(cameraId) }
            return true
        }
        if (!isUltraWideStandbyPreviewActive() || cameraId.isBlank() ||
            cameraId != currentUltraWideStandbyCameraId()
        ) return false
        // Use the Camera2 ID that discovery resolved, including an OEM hidden ID.
        // The actual open callback remains the authority on whether this device permits it.
        val existing = spatialVideoStandbyCameraDevice
        if (existing != null && existing.id == cameraId) {
            createSpatialVideoStandbySession("reuse standby camera")
            return true
        }
        if (spatialVideoStandbyOpeningCameraId == cameraId) {
            PLog.d(TAG, "Special Video standby CameraDevice is already opening: id=$cameraId")
            return true
        }
        stopSpatialVideoStandbyCamera("replace standby camera")
        val generation = ++spatialVideoStandbySessionGeneration
        spatialVideoStandbyOpeningCameraId = cameraId
        val targetSize = spatialVideoStandbyInputSize
            ?: resolveSpatialVideoInputSize(cameraId, _state.value.currentPreviewSize)
        if (targetSize == null) {
            spatialVideoStandbyOpeningCameraId = null
            PLog.e(TAG, "Ultra-wide standby cannot resolve preview stream size: camera=$cameraId")
            if (normalVideoUltraWideInstantSwitchActive) {
                handleUltraWideStandbyPreviewFailure("Unable to resolve ultra-wide preview stream size")
            }
            return false
        }
        spatialVideoStandbyInputSize = targetSize
        _spatialVideoPreviewStreamSize.value = targetSize
        _spatialVideoPreviewSensorOrientationDegrees.value = runCatching {
            cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        }.getOrDefault(0)
        PLog.i(
            TAG,
            "Special Video opening standby device: mode=$spatialVideoStreamingMode, " +
                "primary=$spatialVideoPrimaryCameraId ultraWide=$cameraId stream=${targetSize.width}x${targetSize.height}"
        )
        return try {
            cameraManager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (generation != spatialVideoStandbySessionGeneration ||
                            !isUltraWideStandbyPreviewActive() ||
                            currentUltraWideStandbyCameraId() != cameraId
                        ) {
                            PLog.w(TAG, "Closing stale Special Video standby device: id=${camera.id}, generation=$generation")
                            camera.close()
                            return
                        }
                        spatialVideoStandbyOpeningCameraId = null
                        spatialVideoStandbyCameraDevice = camera
                        PLog.i(TAG, "Special Video standby CameraDevice opened: id=${camera.id}")
                        createSpatialVideoStandbySession("standby camera opened")
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        if (generation != spatialVideoStandbySessionGeneration) {
                            camera.close()
                            return
                        }
                        spatialVideoStandbyOpeningCameraId = null
                        val reason = "Ultra-wide camera disconnected during Special Video"
                        PLog.e(
                            TAG,
                            "Special Video standby disconnected: mode=$spatialVideoStreamingMode " +
                                "main=$spatialVideoMainPhysicalCameraId ultraWide=${camera.id} reason=$reason"
                        )
                        runCatching { camera.close() }
                        handleUltraWideStandbyPreviewFailure(reason)
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        if (generation != spatialVideoStandbySessionGeneration) {
                            camera.close()
                            return
                        }
                        spatialVideoStandbyOpeningCameraId = null
                        val reason = "Ultra-wide Camera2 open failed (error=$error)"
                        PLog.e(
                            TAG,
                            "Special Video standby open failed: mode=$spatialVideoStreamingMode " +
                                "main=$spatialVideoMainPhysicalCameraId ultraWide=${camera.id} " +
                                "error=$error reason=$reason"
                        )
                        runCatching { camera.close() }
                        handleUltraWideStandbyPreviewFailure(reason)
                    }

                    override fun onClosed(camera: CameraDevice) {
                        if (spatialVideoStandbyCameraDevice === camera) {
                            spatialVideoStandbyCameraDevice = null
                            spatialVideoStandbySession = null
                            spatialVideoStandbyRepeatingRequest = null
                            spatialVideoStandbyRequestBuilder = null
                            spatialVideoStandbyEdgeModes = intArrayOf()
                            spatialVideoStandbyNoiseReductionModes = intArrayOf()
                        }
                    }
                },
                handler,
            )
            true
        } catch (error: Exception) {
            spatialVideoStandbyOpeningCameraId = null
            PLog.e(
                TAG,
                "Special Video standby open failed: mode=$spatialVideoStreamingMode " +
                    "main=$spatialVideoMainPhysicalCameraId ultraWide=$cameraId reason=${error.message}",
                error
            )
            handleUltraWideStandbyPreviewFailure(
                "Ultra-wide Camera2 open failed: ${error.message ?: error.javaClass.simpleName}"
            )
            false
        }
    }

    private fun startIndependentSpecialVideoInputsWhenReady() {
        val handler = cameraHandler ?: return
        if (Looper.myLooper() != handler.looper) {
            handler.post { startIndependentSpecialVideoInputsWhenReady() }
            return
        }
        if (!spatialVideoEnabled ||
            spatialVideoStreamingMode == SpatialVideoStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL ||
            !spatialVideoPrimarySessionAwaitingStandby ||
            !_state.value.videoRecordingState.shouldAttachCameraInput()
        ) return

        val mainSession = captureSession ?: return
        val mainRequest = previewRequestBuilder ?: return
        val wideSession = spatialVideoStandbySession ?: return
        val wideRequest = spatialVideoStandbyRepeatingRequest ?: return
        val telephotoRequired = spatialVideoVirtualTelephotoEnabled
        if (!videoRecorder.isRecording() ||
            !spatialVideoRecorder.isRecording() ||
            (telephotoRequired && !spatialVideoTelephotoRecorder.isRecording())
        ) {
            failSpatialVideoStandby(
                if (telephotoRequired) {
                    "All three Special Video encoders must be prepared before camera streams start"
                } else {
                    "The 1x and 0.5x Special Video encoders must be prepared before camera streams start"
                }
            )
            return
        }

        try {
            // Start only the renderer inputs required by the current Special Video options.
            val mainInputStarted = videoRecorder.onCameraInputStarted()
            val ultraWideInputStarted = spatialVideoRecorder.onCameraInputStarted()
            val telephotoInputStarted = if (telephotoRequired) {
                spatialVideoTelephotoRecorder.onCameraInputStarted()
            } else {
                true
            }
            if (!mainInputStarted || !ultraWideInputStarted || !telephotoInputStarted) {
                throw IllegalStateException(
                    "Recorder input readiness failed (main=$mainInputStarted, ultraWide=$ultraWideInputStarted, telephoto=$telephotoInputStarted)"
                )
            }
            submitRepeatingPreviewRequest(mainSession, mainRequest)
            wideSession.setRepeatingRequest(wideRequest, null, handler)
            spatialVideoPrimarySessionAwaitingStandby = false
            startVideoRecordingTicker()
            PLog.i(
                TAG,
                "Special Video dual sessions started together: mode=$spatialVideoStreamingMode " +
                    "main=$spatialVideoPrimaryCameraId ultraWide=${spatialVideoStandbyCameraDevice?.id} " +
                    "timelineOriginUs=${specialVideoRecordingTimeline.currentOriginUs()}"
            )
        } catch (error: Exception) {
            PLog.e(TAG, "Could not start both Special Video Camera2 sessions", error)
            failSpatialVideoStandby(
                "Unable to start simultaneous main and ultra-wide recording sessions: ${error.localizedMessage}"
            )
        }
    }

    private fun createSpatialVideoStandbySession(reason: String) {
        val handler = cameraHandler ?: return
        if (Looper.myLooper() != handler.looper) {
            handler.post { createSpatialVideoStandbySession(reason) }
            return
        }
        val device = spatialVideoStandbyCameraDevice ?: return
        if (!isUltraWideStandbyPreviewActive()) return
        val encoderSurface = spatialVideoRecorder.cameraInputSurface.takeIf { spatialVideoEnabled }
        val normalVideoEncoderSurface = videoRecorder.switchableCameraInputSurface
            ?.takeIf { normalVideoUltraWideInstantSwitchActive && !spatialVideoEnabled && it.isValid }
        // Keep this preview output streaming even when its inset is hidden. The same
        // live frames are promoted to the full viewfinder when the user selects 0.5x.
        val previewSurface = spatialVideoStandbyPreviewSurface?.takeIf { it.isValid }
        val outputSurfaces = buildList {
            previewSurface?.let(::add)
            encoderSurface?.takeIf { it.isValid }?.let(::add)
            normalVideoEncoderSurface?.let(::add)
        }.distinct()
        if (outputSurfaces.isEmpty()) {
            val idleSession = spatialVideoStandbySession
            spatialVideoStandbySession = null
            spatialVideoStandbyRepeatingRequest = null
            spatialVideoStandbyRequestBuilder = null
            spatialVideoStandbyEdgeModes = intArrayOf()
            spatialVideoStandbyNoiseReductionModes = intArrayOf()
            runCatching { idleSession?.stopRepeating() }
            runCatching { idleSession?.abortCaptures() }
            runCatching { idleSession?.close() }
            PLog.d(
                TAG,
                "Special Video standby CameraDevice remains pre-opened without a capture session; " +
                    "waiting for preview/encoder target id=${device.id} reason=$reason"
            )
            return
        }

        val generation = ++spatialVideoStandbySessionGeneration
        val oldSession = spatialVideoStandbySession
        spatialVideoStandbySession = null
        spatialVideoStandbyRepeatingRequest = null
        spatialVideoStandbyRequestBuilder = null
        spatialVideoStandbyEdgeModes = intArrayOf()
        spatialVideoStandbyNoiseReductionModes = intArrayOf()
        runCatching { oldSession?.stopRepeating() }
        runCatching { oldSession?.abortCaptures() }
        runCatching { oldSession?.close() }

        try {
            val characteristics = cameraManager.getCameraCharacteristics(device.id)
            val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
            val wideEdgeModes = characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
                ?: intArrayOf()
            val wideNoiseReductionModes = characteristics.get(
                CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES
            ) ?: intArrayOf()
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                outputSurfaces.forEach(::addTarget)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
                if (afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                }
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                // Match VCamera's resolveSpecialVideoStandbyCropRatio(...)=1.0:
                // the separate 0.5x file keeps the native, uncropped sensor field of view.
                if (activeArray != null) set(CaptureRequest.SCALER_CROP_REGION, activeArray)
                if (spatialVideoEnabled) {
                    applyImageQualitySettings(
                        builder = this,
                        currentState = _state.value,
                        supportedEdgeModes = wideEdgeModes,
                        supportedNoiseReductionModes = wideNoiseReductionModes,
                    )
                } else if (
                    normalVideoUltraWideInstantSwitchActive &&
                    device.id == normalVideoUltraWidePhysicalCameraId
                ) {
                    // Independent Normal Video ultra-wide CameraDevice: disable HAL
                    // sharpness and noise reduction without changing the main camera.
                    if (wideEdgeModes.contains(CaptureRequest.EDGE_MODE_OFF)) {
                        set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                    }
                    if (wideNoiseReductionModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_OFF)) {
                        set(
                            CaptureRequest.NOISE_REDUCTION_MODE,
                            CaptureRequest.NOISE_REDUCTION_MODE_OFF,
                        )
                    }
                }
            }
            spatialVideoStandbyRequestBuilder = request.takeIf { spatialVideoEnabled }
            spatialVideoStandbyEdgeModes = wideEdgeModes.takeIf { spatialVideoEnabled } ?: intArrayOf()
            spatialVideoStandbyNoiseReductionModes =
                wideNoiseReductionModes.takeIf { spatialVideoEnabled } ?: intArrayOf()
            PLog.i(
                TAG,
                "Special Video configuring standby session: id=${device.id}, mode=$spatialVideoStreamingMode, " +
                    "preview=${previewSurface != null}, specialEncoder=${encoderSurface != null}, " +
                    "normalVideoEncoder=${normalVideoEncoderSurface != null}, " +
                    "outputs=${outputSurfaces.size}, stream=$spatialVideoStandbyInputSize"
            )
            device.createCaptureSession(
                outputSurfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (generation != spatialVideoStandbySessionGeneration ||
                            device !== spatialVideoStandbyCameraDevice ||
                            !isUltraWideStandbyPreviewActive()
                        ) {
                            safeCloseCaptureSession(session, "stale Special Video standby session")
                            return
                        }
                        spatialVideoStandbySession = session
                        try {
                            if (encoderSurface != null) {
                                // Wait until the main Camera2 session is also configured.
                                spatialVideoStandbyRepeatingRequest = request.build()
                                startIndependentSpecialVideoInputsWhenReady()
                            } else {
                                // Normal Video feeds this OES surface into the existing encoder.
                                session.setRepeatingRequest(request.build(), null, handler)
                            }
                            PLog.i(
                                TAG,
                                "Special Video standby session configured: camera=${device.id}, " +
                                    "preview=${previewSurface != null}, encoder=${encoderSurface != null}, mode=$spatialVideoStreamingMode"
                            )
                        } catch (error: Exception) {
                            PLog.e(TAG, "Special Video standby repeating request failed: camera=${device.id}", error)
                            safeCloseCaptureSession(session, "Special Video standby repeating failure")
                            spatialVideoStandbySession = null
                            spatialVideoStandbyRepeatingRequest = null
                            if (encoderSurface != null) {
                                failSpatialVideoStandby("Ultra-wide recording request failed: ${error.localizedMessage}")
                            } else if (normalVideoEncoderSurface != null || normalVideoUltraWideInstantSwitchActive) {
                                handleUltraWideStandbyPreviewFailure(
                                    "Normal Video ultra-wide preview repeating request failed: ${error.localizedMessage}"
                                )
                            }
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        safeCloseCaptureSession(session, "Special Video standby configure failed")
                        if (generation == spatialVideoStandbySessionGeneration) {
                            PLog.e(
                                TAG,
                                "Special Video standby session configuration failed: camera=${device.id}, " +
                                    "mode=$spatialVideoStreamingMode, outputs=${outputSurfaces.size}, reason=$reason"
                            )
                            if (encoderSurface != null) {
                                failSpatialVideoStandby("Ultra-wide Camera2 session configuration failed")
                            } else if (normalVideoEncoderSurface != null || normalVideoUltraWideInstantSwitchActive) {
                                handleUltraWideStandbyPreviewFailure(
                                    "Normal Video ultra-wide preview session configuration failed"
                                )
                            }
                        }
                    }

                    override fun onClosed(session: CameraCaptureSession) {
                        if (spatialVideoStandbySession === session) spatialVideoStandbySession = null
                    }
                },
                handler,
            )
        } catch (error: Exception) {
            PLog.e(TAG, "Special Video standby session creation failed: camera=${device.id}, reason=$reason", error)
            if (encoderSurface != null) {
                failSpatialVideoStandby("Ultra-wide recording session could not be created: ${error.localizedMessage}")
            } else if (normalVideoEncoderSurface != null || normalVideoUltraWideInstantSwitchActive) {
                handleUltraWideStandbyPreviewFailure(
                    "Normal Video ultra-wide preview session could not be created: ${error.localizedMessage}"
                )
            }
        }
    }

    /**
     * Clears the Special Video mode and any unpublished companion output after a
     * camera/session failure or explicit disable. Published gallery companions are not
     * deleted: spatialVideoCompanionFile is only held while a recording is unfinished.
     */
    private fun clearSpecialVideoRuntimeState(reason: String) {
        spatialVideoEnabled = false
        _spatialVideoEnabled.value = false
        spatialVideoTelephotoRecorder.forceStop()
        spatialVideoMainPhysicalCameraId = null
        spatialVideoUltraWidePhysicalCameraId = null
        spatialVideoPrimaryCameraId = null
        spatialVideoStreamingMode = null
        spatialVideoStandbyInputSize = null
        spatialPrimaryFinishedUri = null
        spatialUltraWideFinishedFile = null
        spatialTelephotoFinishedFile = null
        spatialVideoStandbyRepeatingRequest = null
        spatialVideoPrimarySessionAwaitingStandby = false
        specialVideoRecordingTimeline.reset()
        stopSpatialVideoStandbyCamera(reason)
        spatialVideoCompanionFile?.let { file ->
            runCatching {
                if (file.exists() && !file.delete()) {
                    PLog.w(TAG, "Could not delete failed Special Video companion: ${file.absolutePath}")
                }
            }.onFailure {
                PLog.w(TAG, "Could not delete failed Special Video companion: ${file.absolutePath}", it)
            }
        }
        spatialVideoCompanionFile = null
        spatialVideoTelephotoCompanionFile?.let { file ->
            runCatching {
                if (file.exists() && !file.delete()) {
                    PLog.w(TAG, "Could not delete failed 3.5x Special Video companion: ${file.absolutePath}")
                }
            }.onFailure {
                PLog.w(TAG, "Could not delete failed 3.5x Special Video companion: ${file.absolutePath}", it)
            }
        }
        spatialVideoTelephotoCompanionFile = null
        activeOutputPhysicalCameraId = resolveRequestedPhysicalCameraId(
            _state.value,
            _state.value.getCurrentCameraInfo()
        )
        PLog.i(TAG, "Special Video runtime cleared: reason=$reason")
    }

    private fun stopSpatialVideoStandbyCamera(reason: String) {
        spatialVideoStandbySessionGeneration++
        spatialVideoStandbyOpeningCameraId = null
        spatialVideoStandbyRepeatingRequest = null
        spatialVideoStandbyRequestBuilder = null
        spatialVideoStandbyEdgeModes = intArrayOf()
        spatialVideoStandbyNoiseReductionModes = intArrayOf()
        spatialVideoPrimarySessionAwaitingStandby = false
        val session = spatialVideoStandbySession
        spatialVideoStandbySession = null
        runCatching { session?.stopRepeating() }
        runCatching { session?.abortCaptures() }
        runCatching { session?.close() }
        val device = spatialVideoStandbyCameraDevice
        spatialVideoStandbyCameraDevice = null
        runCatching { device?.close() }
        PLog.i(TAG, "Special Video standby camera closed: id=${device?.id}, reason=$reason")
    }

    private fun fallbackNormalVideoUltraWideInstantSwitchToMain(reason: String) {
        if (!normalVideoUltraWideInstantSwitchActive) return
        if (videoRecorder.isRecording()) {
            videoRecorder.setSwitchableCameraInputActive(false)
        }
        val mainPhysicalId = normalVideoMainPhysicalCameraId
        val mainInfo = _state.value.availableCameras.firstOrNull { camera ->
            camera.cameraId == normalVideoPrimaryCameraId ||
                camera.cameraId == mainPhysicalId ||
                camera.outputPhysicalCameraId == mainPhysicalId ||
                camera.physicalCameras.any { it.cameraId == mainPhysicalId }
        }
        if (mainInfo != null) {
            _state.value = _state.value.copy(
                currentCameraId = mainInfo.cameraId,
                currentLensType = mainInfo.lensType,
                zoomRatio = 1f,
                zoomPanX = 0f,
                zoomPanY = 0f,
                zoomPanEnabled = false,
            )
        }
        clearNormalVideoUltraWideInstantSwitchRuntime(reason, reopenSelectedCamera = false)
        if (cameraDevice != null && previewSurface != null) {
            createPreviewSession(openGeneration = cameraOpenGeneration)
        }
    }

    private fun handleUltraWideStandbyPreviewFailure(reason: String) {
        if (spatialVideoEnabled) {
            failSpatialVideoStandby(reason)
        } else if (normalVideoUltraWideInstantSwitchActive) {
            PLog.e(TAG, "Normal Video ultra-wide standby preview unavailable: $reason")
            fallbackNormalVideoUltraWideInstantSwitchToMain(reason)
            onCameraError?.invoke(
                -1,
                "Instant ultra-wide switching is unavailable on this camera session: $reason",
                false,
            )
        } else {
            stopSpatialVideoStandbyCamera(reason)
        }
    }

    private fun failSpatialVideoStandby(reason: String) {
        PLog.e(TAG, "Special Video pipeline failure: $reason")
        spatialVideoRecorder.forceStop()
        spatialVideoTelephotoRecorder.forceStop()
        videoRecorder.forceStop()
        clearSpecialVideoRuntimeState(reason)
        stopVideoRecordingTicker()
        videoCameraHlg10InputActive = false
        _state.value = _state.value.copy(videoRecordingState = VideoRecordingState())
        onCameraError?.invoke(-1, reason, false)
        if (cameraDevice != null && previewSurface != null) {
            createPreviewSession(openGeneration = cameraOpenGeneration)
        }
    }

    fun isDualCaptureSupported(): Boolean =
        isDualCaptureSourceSupported(DualCaptureSource.FRONT) ||
            isDualCaptureSourceSupported(DualCaptureSource.ULTRA_WIDE)

    fun setDualCaptureEnabled(enabled: Boolean) {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post { setDualCaptureEnabled(enabled) }
            return
        }
        if (dualCaptureEnabled == enabled) return
        if (enabled && spatialVideoEnabled) setSpatialVideoEnabled(false)
        if (enabled) {
            if (_state.value.captureMode != CaptureMode.VIDEO) {
                onCameraError?.invoke(-1, "Dual Capture is only available in Video mode.", false)
                return
            }
            // Every fresh Dual Capture session starts with the rear ultra-wide inset.
            // The front camera remains available as an explicit source-switch option.
            if (_dualCaptureSource.value != DualCaptureSource.ULTRA_WIDE &&
                isDualCaptureSourceSupported(DualCaptureSource.ULTRA_WIDE)
            ) {
                _dualCaptureSource.value = DualCaptureSource.ULTRA_WIDE
            }
            if (!isDualCaptureSourceSupported(_dualCaptureSource.value)) {
                val fallback = if (_dualCaptureSource.value == DualCaptureSource.FRONT) {
                    DualCaptureSource.ULTRA_WIDE
                } else {
                    DualCaptureSource.FRONT
                }
                if (!isDualCaptureSourceSupported(fallback)) {
                    onCameraError?.invoke(
                        -1,
                        "Dual Capture is not supported for either the front or ultra-wide camera pair.",
                        false,
                    )
                    return
                }
                _dualCaptureSource.value = fallback
            }
            dualCaptureEnabled = true
            startDualCaptureCamera()
        } else {
            if (videoRecorder.isRecording()) {
                onCameraError?.invoke(-1, "Stop recording before disabling Dual Capture.", false)
                return
            }
            dualCaptureEnabled = false
            dualCaptureRecordSurface = null
            if (_dualCaptureSource.value == DualCaptureSource.ULTRA_WIDE) {
                restoreDualCapturePrimaryCameraState()
            }
            stopDualCaptureCamera()
            if (cameraDevice != null && previewSurface != null) {
                createPreviewSession(openGeneration = cameraOpenGeneration)
            }
        }
    }

    fun setDualCaptureSource(source: DualCaptureSource) {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post { setDualCaptureSource(source) }
            return
        }
        if (_dualCaptureSource.value == source) return
        if (videoRecorder.isRecording() || _state.value.videoRecordingState.isProcessing) {
            onCameraError?.invoke(-1, "Stop recording before changing the Dual Capture camera.", false)
            return
        }
        if (!isDualCaptureSourceSupported(source)) {
            val message = when (source) {
                DualCaptureSource.FRONT -> "The front camera cannot run concurrently with the selected rear camera."
                DualCaptureSource.ULTRA_WIDE -> "The selected rear camera does not advertise a simultaneous ultra-wide stream."
            }
            onCameraError?.invoke(-1, message, false)
            return
        }

        val previousSource = _dualCaptureSource.value
        if (previousSource == DualCaptureSource.ULTRA_WIDE) {
            restoreDualCapturePrimaryCameraState()
        }
        stopDualCaptureCamera()
        _dualCaptureSource.value = source
        if (dualCaptureEnabled) {
            startDualCaptureCamera()
        } else if (previousSource == DualCaptureSource.ULTRA_WIDE &&
            cameraDevice != null && previewSurface != null
        ) {
            createPreviewSession(openGeneration = cameraOpenGeneration)
        }
    }

    private fun restoreDualCapturePrimaryCameraState() {
        val saved = dualCaptureSavedPrimaryCameraState ?: return
        activeOutputPhysicalCameraId = saved.physicalCameraId
        logicalMultiCameraAutoSwitchActive = saved.logicalAutoSwitchActive
        _state.value = _state.value.copy(
            zoomRatio = saved.zoomRatio,
            zoomPanX = saved.zoomPanX,
            zoomPanY = saved.zoomPanY,
            zoomPanEnabled = saved.zoomPanEnabled,
        )
        dualCaptureSavedPrimaryCameraState = null
        dualCaptureMainPhysicalCameraId = null
        dualCapturePhysicalCameraId = null
        PLog.i(
            TAG,
            "Restored primary camera after ultra-wide Dual Capture: " +
                "physical=$activeOutputPhysicalCameraId, zoom=${saved.zoomRatio}"
        )
    }

    fun setDualCapturePreviewSurfaceTexture(surfaceTexture: SurfaceTexture?) {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post { setDualCapturePreviewSurfaceTexture(surfaceTexture) }
            return
        }
        if (dualCapturePreviewSurfaceTexture === surfaceTexture) return

        if (dualCaptureEnabled &&
            _dualCaptureSource.value == DualCaptureSource.ULTRA_WIDE &&
            !dualCaptureUsesConcurrentUltraWideCamera
        ) {
            val oldSurface = dualCapturePreviewSurface
            dualCapturePreviewSurface = null
            dualCapturePreviewSurfaceTexture = surfaceTexture
            if (oldSurface != null || dualCaptureRecordSurface != null) {
                // Detach the old TextureView output before releasing it; keep the encoder output
                // configured when SurfaceTexture is recreated during an active recording.
                if (cameraDevice != null && previewSurface != null) {
                    createPreviewSession(openGeneration = cameraOpenGeneration)
                }
            }
            oldSurface?.release()
            if (surfaceTexture == null) {
                if (dualCaptureRecordSurface == null) _dualCaptureStreamSize.value = null
                return
            }
            startDualCaptureCamera()
            return
        }

        dualCaptureSession?.let { safeCloseCaptureSession(it, "dual capture preview surface replaced") }
        dualCaptureSession = null
        dualCapturePreviewSurface?.release()
        dualCapturePreviewSurface = null
        dualCapturePreviewSurfaceTexture = surfaceTexture
        if (surfaceTexture == null) {
            if (videoRecorder.isRecording()) {
                createDualCaptureSession()
            } else {
                stopDualCaptureCamera()
            }
            return
        }
        if (dualCaptureEnabled) startDualCaptureCamera()
    }

    fun setDualCaptureLayout(layout: DualCaptureLayout) {
        dualCaptureLayout = layout
        videoRecorder.updateDualCaptureLayout(layout)
    }

    fun setDualCapturePosition(x: Float, y: Float, widthFraction: Float? = null) {
        dualCapturePositionX = x.coerceIn(0f, 1f)
        dualCapturePositionY = y.coerceIn(0f, 1f)
        if (widthFraction != null) {
            dualCapturePipWidthFraction = widthFraction.coerceIn(0.01f, 0.33f)
        }
        videoRecorder.updateDualCapturePosition(dualCapturePositionX, dualCapturePositionY)
    }

    private fun resolveDualCaptureCameraId(): String? {
        if (!isDualCaptureFrontSupported()) return null
        return _state.value.availableCameras.firstOrNull {
            it.lensFacing == CameraCharacteristics.LENS_FACING_FRONT
        }?.cameraId
    }

    private fun resolveDualCaptureInputSize(cameraId: String): Size? {
        return try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return null
            val sizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
            if (sizes.isEmpty()) return null
            val targetAspect = _state.value.currentPreviewSize.width.toFloat() /
                _state.value.currentPreviewSize.height.coerceAtLeast(1)
            sizes.filter { it.width <= 1920 && it.height <= 1920 }
                .maxWithOrNull(
                    compareBy<Size>(
                        { -kotlin.math.abs(it.width.toFloat() / it.height.coerceAtLeast(1) - targetAspect) },
                        { it.width.toLong() * it.height.toLong() },
                    )
                )
                ?: sizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
        } catch (error: Exception) {
            PLog.e(TAG, "Cannot resolve secondary Dual Capture stream size for camera=$cameraId", error)
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDualCaptureCamera() {
        val handler = cameraHandler ?: return
        if (Looper.myLooper() != handler.looper) {
            handler.post { startDualCaptureCamera() }
            return
        }
        if (!dualCaptureEnabled || _state.value.captureMode != CaptureMode.VIDEO) return
        if (cameraDevice == null || cameraDeviceLifecycle != CameraDeviceLifecycle.OPEN) return
        val previewTexture = dualCapturePreviewSurfaceTexture ?: return
        if (_dualCaptureSource.value == DualCaptureSource.ULTRA_WIDE) {
            if (resolveDualCapturePhysicalCameraPair() != null) {
                dualCaptureUsesConcurrentUltraWideCamera = false
                startDualCaptureUltraWideCamera(previewTexture)
            } else {
                val ultraWideCameraId = resolveConcurrentDualCaptureUltraWideCameraId()
                if (ultraWideCameraId == null) {
                    onCameraError?.invoke(
                        -1,
                        "This device does not advertise simultaneous rear main + ultra-wide camera access.",
                        false,
                    )
                    return
                }
                startDualCaptureConcurrentUltraWideCamera(previewTexture, ultraWideCameraId)
            }
            return
        }
        val targetCameraId = resolveDualCaptureCameraId()
        if (targetCameraId == null) {
            PLog.w(TAG, "Dual Capture unavailable: selected primary/front camera pair is not concurrent")
            return
        }
        val targetSize = resolveDualCaptureInputSize(targetCameraId)
        if (targetSize == null) {
            PLog.w(TAG, "Dual Capture unavailable: front camera exposes no SurfaceTexture output size")
            onCameraError?.invoke(-1, "Front camera has no compatible video preview stream.", false)
            return
        }
        if (dualCaptureCameraDevice != null && dualCaptureCameraId == targetCameraId &&
            dualCaptureInputSize == targetSize
        ) {
            if (dualCapturePreviewSurface == null) {
                previewTexture.setDefaultBufferSize(targetSize.width, targetSize.height)
                dualCapturePreviewSurface = Surface(previewTexture)
            }
            createDualCaptureSession()
            return
        }
        stopDualCaptureCamera()
        try {
            previewTexture.setDefaultBufferSize(targetSize.width, targetSize.height)
            dualCapturePreviewSurface = Surface(previewTexture)
            dualCaptureInputSize = targetSize
            _dualCaptureStreamSize.value = targetSize
            _dualCaptureSensorOrientationDegrees.value = runCatching {
                cameraManager.getCameraCharacteristics(targetCameraId)
                    .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            }.getOrDefault(0)
            dualCaptureCameraId = targetCameraId
            val generation = ++dualCaptureSessionGeneration
            PLog.i(
                TAG,
                "Opening concurrent front camera for Dual Capture: id=$targetCameraId, " +
                    "stream=${targetSize.width}x${targetSize.height}, primary=$activeOpenCameraId",
            )
            cameraManager.openCamera(
                targetCameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (generation != dualCaptureSessionGeneration ||
                            !dualCaptureEnabled ||
                            dualCapturePreviewSurfaceTexture !== previewTexture
                        ) {
                            camera.close()
                            return
                        }
                        dualCaptureCameraDevice = camera
                        createDualCaptureSession()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        PLog.w(TAG, "Concurrent front camera disconnected")
                        if (dualCaptureCameraDevice === camera) {
                            stopDualCaptureCamera()
                        } else {
                            camera.close()
                        }
                        if (videoRecorder.isRecording()) stopVideoRecording()
                        onCameraError?.invoke(-1, "Front camera disconnected during Dual Capture.", false)
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        PLog.e(TAG, "Concurrent front camera open error: $error")
                        if (dualCaptureCameraDevice === camera) {
                            stopDualCaptureCamera()
                        } else {
                            camera.close()
                        }
                        if (videoRecorder.isRecording()) stopVideoRecording()
                        onCameraError?.invoke(-1, "Cannot open front camera for Dual Capture ($error).", false)
                    }

                    override fun onClosed(camera: CameraDevice) {
                        if (dualCaptureCameraDevice === camera) {
                            dualCaptureCameraDevice = null
                            dualCaptureSession = null
                        }
                    }
                },
                handler,
            )
        } catch (error: Exception) {
            PLog.e(TAG, "Failed to open concurrent front camera", error)
            stopDualCaptureCamera()
            onCameraError?.invoke(-1, "Cannot start front-camera Dual Capture: ${error.localizedMessage}", false)
        }
    }

    /**
     * Opens an independently exposed rear ultra-wide Camera2 ID alongside the primary rear
     * camera. This uses the same inset preview and encoder surfaces as the concurrent front
     * camera path; the primary viewfinder remains on its original CameraDevice.
     */
    @SuppressLint("MissingPermission")
    private fun startDualCaptureConcurrentUltraWideCamera(
        previewTexture: SurfaceTexture,
        targetCameraId: String,
    ) {
        val handler = cameraHandler ?: return
        if (cameraDevice == null || cameraDeviceLifecycle != CameraDeviceLifecycle.OPEN) return
        val targetSize = resolveDualCaptureInputSize(targetCameraId)
        if (targetSize == null) {
            onCameraError?.invoke(
                -1,
                "The rear ultra-wide camera has no compatible inset preview stream.",
                false,
            )
            return
        }

        if (dualCaptureCameraDevice != null &&
            dualCaptureCameraId == targetCameraId &&
            dualCaptureInputSize == targetSize
        ) {
            dualCaptureUsesConcurrentUltraWideCamera = true
            if (dualCapturePreviewSurface == null) {
                previewTexture.setDefaultBufferSize(targetSize.width, targetSize.height)
                dualCapturePreviewSurface = Surface(previewTexture)
            }
            createDualCaptureSession()
            return
        }

        stopDualCaptureCamera()
        try {
            previewTexture.setDefaultBufferSize(targetSize.width, targetSize.height)
            dualCapturePreviewSurface = Surface(previewTexture)
            dualCaptureInputSize = targetSize
            dualCaptureCameraId = targetCameraId
            dualCaptureUsesConcurrentUltraWideCamera = true
            _dualCaptureStreamSize.value = targetSize
            _dualCaptureSensorOrientationDegrees.value = runCatching {
                cameraManager.getCameraCharacteristics(targetCameraId)
                    .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            }.getOrDefault(0)

            val generation = ++dualCaptureSessionGeneration
            PLog.i(
                TAG,
                "Opening concurrent rear ultra-wide for Dual Capture: id=$targetCameraId, " +
                    "stream=${targetSize.width}x${targetSize.height}, primary=$activeOpenCameraId",
            )
            cameraManager.openCamera(
                targetCameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (generation != dualCaptureSessionGeneration ||
                            !dualCaptureEnabled ||
                            _dualCaptureSource.value != DualCaptureSource.ULTRA_WIDE ||
                            dualCapturePreviewSurfaceTexture !== previewTexture
                        ) {
                            camera.close()
                            return
                        }
                        dualCaptureCameraDevice = camera
                        createDualCaptureSession()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        PLog.w(TAG, "Concurrent rear ultra-wide camera disconnected")
                        if (dualCaptureCameraDevice === camera) {
                            stopDualCaptureCamera()
                        } else {
                            camera.close()
                        }
                        if (videoRecorder.isRecording()) stopVideoRecording()
                        onCameraError?.invoke(
                            -1,
                            "Rear ultra-wide camera disconnected during Dual Capture.",
                            false,
                        )
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        PLog.e(TAG, "Concurrent rear ultra-wide camera open error: $error")
                        if (dualCaptureCameraDevice === camera) {
                            stopDualCaptureCamera()
                        } else {
                            camera.close()
                        }
                        if (videoRecorder.isRecording()) stopVideoRecording()
                        onCameraError?.invoke(
                            -1,
                            "Cannot open rear ultra-wide camera for Dual Capture ($error).",
                            false,
                        )
                    }

                    override fun onClosed(camera: CameraDevice) {
                        if (dualCaptureCameraDevice === camera) {
                            dualCaptureCameraDevice = null
                            dualCaptureSession = null
                        }
                    }
                },
                handler,
            )
        } catch (error: Exception) {
            PLog.e(TAG, "Failed to open concurrent rear ultra-wide camera", error)
            stopDualCaptureCamera()
            onCameraError?.invoke(
                -1,
                "Cannot start rear ultra-wide Dual Capture: ${error.localizedMessage}",
                false,
            )
        }
    }

    /**
     * Keeps the rear main stream on the logical device's 1x physical sensor and sends the
     * ultra-wide physical output to the existing Dual Capture preview/encoder surfaces.
     */
    private fun startDualCaptureUltraWideCamera(previewTexture: SurfaceTexture) {
        if (cameraDevice == null || cameraDeviceLifecycle != CameraDeviceLifecycle.OPEN) return
        val pair = resolveDualCapturePhysicalCameraPair()
        if (pair == null) {
            PLog.w(TAG, "Ultra-wide Dual Capture unavailable: no logical rear 1x/ultra-wide pair")
            onCameraError?.invoke(-1, "The selected rear camera does not support a simultaneous 1x + ultra-wide stream.", false)
            return
        }
        val (mainPhysicalId, ultraWidePhysicalId) = pair
        val targetSize = resolveDualCaptureInputSize(ultraWidePhysicalId)
        if (targetSize == null) {
            PLog.w(TAG, "Ultra-wide Dual Capture has no compatible SurfaceTexture output size")
            onCameraError?.invoke(-1, "The ultra-wide camera has no compatible video preview stream.", false)
            return
        }

        if (dualCaptureSavedPrimaryCameraState == null) {
            val state = _state.value
            dualCaptureSavedPrimaryCameraState = DualCapturePrimaryCameraState(
                physicalCameraId = activeOutputPhysicalCameraId,
                logicalAutoSwitchActive = logicalMultiCameraAutoSwitchActive,
                zoomRatio = state.zoomRatio,
                zoomPanX = state.zoomPanX,
                zoomPanY = state.zoomPanY,
                zoomPanEnabled = state.zoomPanEnabled,
            )
        }
        previewTexture.setDefaultBufferSize(targetSize.width, targetSize.height)
        if (dualCapturePreviewSurface == null) {
            dualCapturePreviewSurface = Surface(previewTexture)
        }
        dualCaptureInputSize = targetSize
        dualCapturePhysicalCameraId = ultraWidePhysicalId
        dualCaptureMainPhysicalCameraId = mainPhysicalId
        activeOutputPhysicalCameraId = mainPhysicalId
        logicalMultiCameraAutoSwitchActive = false
        _state.value = _state.value.copy(
            zoomRatio = 1f,
            zoomPanX = 0f,
            zoomPanY = 0f,
            zoomPanEnabled = false,
        )
        _dualCaptureSensorOrientationDegrees.value = runCatching {
            cameraManager.getCameraCharacteristics(ultraWidePhysicalId)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        }.getOrDefault(0)
        _dualCaptureStreamSize.value = targetSize
        PLog.i(
            TAG,
            "Ultra-wide Dual Capture ready: main1x=$mainPhysicalId, ultraWide=$ultraWidePhysicalId, " +
                "stream=${targetSize.width}x${targetSize.height}; primary output pinned to 1x"
        )
        if (previewSurface != null) {
            createPreviewSession(openGeneration = cameraOpenGeneration)
        }
    }

    private fun createDualCaptureSession() {
        val handler = cameraHandler ?: return
        if (Looper.myLooper() != handler.looper) {
            handler.post { createDualCaptureSession() }
            return
        }
        val device = dualCaptureCameraDevice ?: return
        val previewSurface = dualCapturePreviewSurface
        val recordSurface = dualCaptureRecordSurface
        if (!dualCaptureEnabled || _state.value.captureMode != CaptureMode.VIDEO) return
        if (previewSurface == null && recordSurface == null) return
        val surfaces = buildList {
            previewSurface?.let(::add)
            recordSurface?.let(::add)
        }
        val generation = ++dualCaptureSessionGeneration
        try {
            dualCaptureSession?.let { safeCloseCaptureSession(it, "dual capture session replaced") }
            dualCaptureSession = null
            val characteristics = cameraManager.getCameraCharacteristics(device.id)
            val availableAfModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                ?: intArrayOf()
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                previewSurface?.let(::addTarget)
                recordSurface?.let(::addTarget)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                if (availableAfModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                }
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            }
            device.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (generation != dualCaptureSessionGeneration ||
                            device !== dualCaptureCameraDevice ||
                            !dualCaptureEnabled
                        ) {
                            safeCloseCaptureSession(session, "stale dual capture session")
                            return
                        }
                        dualCaptureSession = session
                        try {
                            session.setRepeatingRequest(request.build(), null, handler)
                            PLog.i(
                                TAG,
                                "Dual Capture secondary stream active: preview=${dualCaptureInputSize}, " +
                                    "encoder=${recordSurface != null}, layout=${dualCaptureLayout.name}",
                            )
                        } catch (error: Exception) {
                            PLog.e(TAG, "Unable to start secondary-camera repeating request", error)
                            safeCloseCaptureSession(session, "dual capture repeating request failed")
                            dualCaptureSession = null
                            if (recordSurface != null) {
                                onCameraError?.invoke(-1, "Secondary-camera recording stream failed.", false)
                                stopVideoRecording()
                            }
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        safeCloseCaptureSession(session, "dual capture configuration failed")
                        PLog.e(
                            TAG,
                            "Dual Capture session configuration failed: encoderSurface=${recordSurface != null}",
                        )
                        if (recordSurface != null) {
                            onCameraError?.invoke(
                                -1,
                                "The selected secondary camera cannot provide the Dual Capture recording stream.",
                                false,
                            )
                            stopVideoRecording()
                        } else {
                            onCameraError?.invoke(-1, "Secondary-camera preview could not be configured.", false)
                        }
                    }

                    override fun onClosed(session: CameraCaptureSession) {
                        if (dualCaptureSession === session) dualCaptureSession = null
                    }
                },
                handler,
            )
        } catch (error: Exception) {
            PLog.e(TAG, "Failed to configure Dual Capture secondary session", error)
            if (recordSurface != null) {
                onCameraError?.invoke(-1, "Secondary-camera recording stream setup failed.", false)
                stopVideoRecording()
            }
        }
    }

    private fun stopDualCaptureCamera() {
        dualCaptureSessionGeneration++
        val session = dualCaptureSession
        dualCaptureSession = null
        try {
            session?.stopRepeating()
        } catch (_: Exception) {
        }
        try {
            session?.abortCaptures()
        } catch (_: Exception) {
        }
        try {
            session?.close()
        } catch (_: Exception) {
        }
        val device = dualCaptureCameraDevice
        dualCaptureCameraDevice = null
        try {
            device?.close()
        } catch (_: Exception) {
        }
        dualCapturePreviewSurface?.release()
        dualCapturePreviewSurface = null
        dualCaptureRecordSurface = null
        dualCaptureCameraId = null
        dualCapturePhysicalCameraId = null
        dualCaptureMainPhysicalCameraId = null
        dualCaptureUsesConcurrentUltraWideCamera = false
        dualCaptureInputSize = null
        _dualCaptureStreamSize.value = null
        _dualCaptureSensorOrientationDegrees.value = 0
    }

    private fun refreshDualCaptureRecordingTarget() {
        if (!dualCaptureEnabled || !videoRecorder.isRecording()) return
        dualCaptureRecordSurface = videoRecorder.dualCaptureInputSurface
        if (dualCaptureRecordSurface == null) {
            onCameraError?.invoke(-1, "Dual Capture encoder input could not be created.", false)
            videoRecorder.forceStop()
            spatialVideoRecorder.forceStop()
            return
        }
        if (_dualCaptureSource.value != DualCaptureSource.ULTRA_WIDE ||
            dualCaptureUsesConcurrentUltraWideCamera
        ) {
            createDualCaptureSession()
        }
    }

    fun startVideoRecording(
        creativeLutConfig: LutConfig? = null,
        creativeRecipeParams: ColorRecipeParams? = null,
        orientationOffsetDegrees: Int = 0
    ) {
        if (_state.value.captureMode != CaptureMode.VIDEO ||
            _state.value.videoRecordingState.isRecording ||
            _state.value.videoRecordingState.isProcessing
        ) {
            return
        }

        val selectedDualAspectRatio = _state.value.videoConfig.dualAspectRatio
        if (selectedDualAspectRatio != null &&
            (spatialVideoEnabled || dualCaptureEnabled ||
                _state.value.videoConfig.fps == VideoFpsPreset.FPS_120 ||
                shouldUseVideoEnhancedStabilization())
        ) {
            onCameraError?.invoke(
                -1,
                "Dual Aspect requires standard FPS, Enhanced Stabilization off, and Special Video / Dual Capture disabled.",
                false
            )
            return
        }

        if (spatialVideoEnabled && _state.value.videoConfig.fps == VideoFpsPreset.FPS_120) {
            onCameraError?.invoke(-1, "Special Video requires a standard frame rate; 120fps is not supported for dual-camera recording.", false)
            return
        }
        if (normalVideoUltraWideInstantSwitchActive &&
            _state.value.videoConfig.fps == VideoFpsPreset.FPS_120
        ) {
            onCameraError?.invoke(
                -1,
                "Normal Video instant lens switching requires a standard frame rate. Select 24–60fps.",
                false,
            )
            return
        }

        val dualCaptureReady = when (_dualCaptureSource.value) {
            DualCaptureSource.FRONT -> dualCaptureCameraDevice != null && dualCaptureSession != null
            DualCaptureSource.ULTRA_WIDE -> if (dualCaptureUsesConcurrentUltraWideCamera) {
                dualCaptureCameraDevice != null && dualCaptureSession != null
            } else {
                dualCapturePhysicalCameraId != null &&
                    dualCaptureMainPhysicalCameraId != null && captureSession != null
            }
        }
        if (dualCaptureEnabled && !dualCaptureReady) {
            onCameraError?.invoke(
                -1,
                "Wait for the selected Dual Capture camera preview to become ready before recording.",
                false,
            )
            return
        }
        if (dualCaptureEnabled && _dualCaptureSource.value == DualCaptureSource.ULTRA_WIDE &&
            (_state.value.videoConfig.fps == VideoFpsPreset.FPS_120 || shouldUseVideoEnhancedStabilization())
        ) {
            onCameraError?.invoke(
                -1,
                "Ultra-wide Dual Capture requires standard frame rates and Enhanced Stabilization off.",
                false,
            )
            return
        }

        if (_state.value.videoCapabilities.availableFps.isEmpty()) {
            onCameraError?.invoke(
                -1,
                context.getString(com.hinnka.mycamera.R.string.camera_video_fps_unavailable),
                false,
            )
            return
        }

        videoCaptureStatsWindowStartMs = 0L
        videoCaptureStatsFrames = 0
        videoCaptureStatsFirstTimestampNs = 0L
        val useEnhancedStabilization = shouldUseVideoEnhancedStabilization()
        val positionLockStabilization = shouldUsePositionLockStabilization()
        val useMgcStabilization = useEnhancedStabilization || positionLockStabilization
        val recordingCameraInfo = _state.value.getCurrentCameraInfo()
        val normalVideoUltraWideRecordingInput =
            normalVideoUltraWideInstantSwitchActive &&
                isNormalVideoUltraWideLens(recordingCameraInfo)
        val logicalMultiCameraRecordingInput =
            shouldUseLogicalMultiCameraZoom(_state.value, recordingCameraInfo)
        val topOffSoftwareCropActiveForRecording =
            _topOffZoomEnabled.value &&
                _state.value.captureMode == CaptureMode.VIDEO &&
                (!logicalMultiCameraRecordingInput || normalVideoUltraWideRecordingInput) &&
                !spatialVideoEnabled && !dualCaptureEnabled && !positionLockStabilization &&
                (_state.value.zoomRatio >= 1f || normalVideoUltraWideRecordingInput)
        val normalVideoMainInputId = normalVideoMainPhysicalCameraId ?: normalVideoPrimaryCameraId
        val normalVideoMainCharacteristics = if (normalVideoUltraWideInstantSwitchActive && !spatialVideoEnabled) {
            normalVideoMainInputId?.let { getCameraCharacteristicsOrNull(it, "Normal Video main recording input") }
        } else {
            null
        }
        // This source remains fixed at the preferred 12 MP-class size resolved for the camera,
        // regardless of which recording resolution is selected in the UI (with a compatible
        // fallback on cameras that do not expose a 12 MP stream).
        // Top-Off zoom/pan is applied by the GL renderer, not by changing the input size.
        val fixedCameraInputSize = if (useMgcStabilization) {
            _state.value.videoCapabilities.enhancedStabilizationInputSizesByResolution[
                _state.value.videoConfig.resolution
            ]
        } else {
            _state.value.videoCapabilities.cameraInputSizesByResolution[
                _state.value.videoConfig.resolution
            ]
        } ?: _state.value.currentPreviewSize
        val cameraInputSize = if (normalVideoUltraWideInstantSwitchActive && !spatialVideoEnabled) {
            normalVideoMainInputId?.let { resolveSpatialVideoInputSize(it, fixedCameraInputSize) }
                ?: fixedCameraInputSize
        } else {
            fixedCameraInputSize
        }
        val switchableCameraInputSize = if (normalVideoUltraWideInstantSwitchActive && !spatialVideoEnabled) {
            val wideId = normalVideoUltraWidePhysicalCameraId ?: run {
                onCameraError?.invoke(-1, "Normal Video ultra-wide recording input is unavailable.", false)
                return
            }
            resolveSpatialVideoInputSize(wideId, cameraInputSize) ?: run {
                onCameraError?.invoke(-1, "The ultra-wide camera has no compatible recording stream size.", false)
                return
            }
        } else {
            null
        }
        val switchableCameraCharacteristics = if (switchableCameraInputSize != null) {
            normalVideoUltraWidePhysicalCameraId?.let {
                getCameraCharacteristicsOrNull(it, "Normal Video ultra-wide recording input")
            }
        } else {
            null
        }
        if (switchableCameraInputSize != null && switchableCameraCharacteristics == null) {
            onCameraError?.invoke(-1, "The ultra-wide camera characteristics could not be read for recording.", false)
            return
        }
        val recordingSensorOrientation = normalVideoMainCharacteristics
            ?.get(CameraCharacteristics.SENSOR_ORIENTATION)
            ?: _state.value.getCurrentCameraInfo()?.sensorOrientation
            ?: cachedSensorOrientation
        val isOpenGateRecording =
            _state.value.videoConfig.aspectRatio == VideoAspectRatio.OPEN_GATE
        // Try the full native camera-input canvas first. If the codec rejects it,
        // VideoRecorder will try the largest camera-advertised canvas and the other
        // available hardware codec before returning a recording-start error.
        val outputSize = _state.value.videoConfig.resolveOutputSize(
            _state.value.videoCapabilities.openGatePortraitAspectRatio,
            cameraInputSize
        )
        PLog.i(
            "VideoResolution",
            "RECORDING input=${cameraInputSize.width}x${cameraInputSize.height}, " +
                "encoderOutput=${outputSize.width}x${outputSize.height}, " +
                "selectedPreset=${_state.value.videoConfig.resolution.displayName}",
        )
        val fallbackOutputSizes = if (isOpenGateRecording) {
            listOfNotNull(
                _state.value.videoCapabilities.recordingSizesByResolution[
                    _state.value.videoConfig.resolution
                ]
            ).filter { it != outputSize }
        } else {
            emptyList()
        }
        val selectedVideoCodec = _state.value.videoConfig.codec
        val fallbackCodecMimes = if (
            isOpenGateRecording && selectedVideoCodec != com.hinnka.mycamera.video.VideoCodec.APV
        ) {
            _state.value.videoCapabilities.availableCodecs
                .filter { it != selectedVideoCodec && it != com.hinnka.mycamera.video.VideoCodec.APV }
                .map { it.mimeType }
        } else {
            emptyList()
        }
        // Encoded video must not inherit the front-preview selfie mirror.
        // The Selfie Mirror preference applies to still photos only.
        val shouldFlipEncodedFrame = isCurrentCameraFrontFacing()
        val logProfile = _state.value.videoConfig.logProfile
        val isOppoBundledLut = creativeLutConfig?.title?.startsWith("oppo_") == true
        val monitorOnly = logProfile.isEnabled &&
            _state.value.videoConfig.logLutMode == VideoLogLutMode.MONITOR_ONLY &&
            !isOppoBundledLut
        val recordingLut = creativeLutConfig?.takeIf {
            !monitorOnly && logProfile.matchesLut(it.curve, it.colorSpace)
        }
        val recordingRecipe = creativeRecipeParams.takeUnless { monitorOnly }
        val colorLayers = buildList {
            if (recordingLut != null || recordingRecipe?.isDefault() == false) {
                add(VideoColorEffectLayer(recordingLut, recordingRecipe))
            }
        }
        val cameraInputIsHlg10 = logProfile == VideoLogProfile.HLG10 &&
            _state.value.videoConfig.fps != VideoFpsPreset.FPS_120 &&
            !useMgcStabilization &&
            colorLayers.isEmpty() &&
            switchableCameraInputSize == null &&
            supportsCameraHlg10Input()
        videoCameraHlg10InputActive = cameraInputIsHlg10
        PLog.d(
            TAG,
            "Video color output: log=${logProfile.name}, mode=${_state.value.videoConfig.logLutMode}, " +
                "monitorLut=${creativeLutConfig?.title}, recordingLut=${recordingLut?.title}, " +
                "cameraInputHlg10=${cameraInputIsHlg10}"
        )
        // Select the shared origin after both recorders accept their first frame. Until then,
        // VideoRecorder keeps encoded samples pending instead of committing a false start time.
        val hasSharedRecordingTimeline = spatialVideoEnabled || selectedDualAspectRatio != null
        specialVideoDrainDurationMs = if (spatialVideoEnabled) {
            val lookaheadFrames = if (useMgcStabilization) {
                normalizeStabilizationLookahead(
                    _state.value.videoConfig.enhancedStabilizationLookahead
                )
            } else {
                0
            }
            val drainFrames = if (useMgcStabilization) lookaheadFrames + 2 else 2
            ((drainFrames.toLong() * 1_000L + _state.value.videoConfig.fps.fps - 1L) /
                _state.value.videoConfig.fps.fps) + 100L
        } else {
            0L
        }
        val sharedTimelineSecondaryStreamId = when {
            selectedDualAspectRatio != null -> SpecialVideoRecordingTimeline.StreamId.SECONDARY
            spatialVideoEnabled -> SpecialVideoRecordingTimeline.StreamId.ULTRA_WIDE
            else -> SpecialVideoRecordingTimeline.StreamId.SECONDARY
        }
        if (hasSharedRecordingTimeline) {
            specialVideoRecordingTimeline.reset(
                includeTelephoto = spatialVideoEnabled && spatialVideoVirtualTelephotoEnabled
            )
        }
        val baseOrientationHintDegrees = resolveDedicatedVideoOrientationHintDegrees(orientationOffsetDegrees)
        val cameraTimestampSource = normalVideoMainCharacteristics
            ?.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
            ?: getActiveOpenCameraCharacteristics()
                ?.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
            ?: CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN
        val switchableCameraTimestampSource = switchableCameraCharacteristics
            ?.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
            ?: CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN

        if (selectedDualAspectRatio != null) {
            dualAspectRecordingActive = true
            dualAspectPrimaryFinishReceived = false
            dualAspectSecondaryFinishReceived = false
            dualAspectPrimaryFinishedUri = null
            dualAspectSecondaryFinishedFile = null
            dualAspectOutputRatio = selectedDualAspectRatio
            dualAspectOutputDateTakenMs = System.currentTimeMillis()
            dualAspectOutputRecordingPath = _state.value.videoConfig.recordingPath
            dualAspectOutputTreeUri = _state.value.videoConfig.recordingTreeUri
            dualAspectCompanionFile = File(
                context.filesDir,
                "dual_aspect_video/PhotonCamera_" + dualAspectOutputDateTakenMs + "_" + selectedDualAspectRatio.fileSuffix + ".mp4"
            )
        }

        val topOffSoftwareZoomForRecording = if (topOffSoftwareCropActiveForRecording) {
            if (normalVideoUltraWideRecordingInput) {
                // The ultra-wide optical baseline remains selected in the switchable input.
                // Apply only the Top Off factor on that stream, not the main lens' locked ratio.
                _topOffZoomFactor.value.coerceIn(1f, TopOffPanPreset.MAX_ZOOM_RATIO)
            } else {
                resolveTopOffZoomLockedRatio(_state.value.zoomRatio)
            }
        } else {
            1f
        }
        val topOffSoftwarePanXForRecording =
            if (topOffSoftwareCropActiveForRecording) _topOffZoomPanX.value else 0f
        val topOffSoftwarePanYForRecording =
            if (topOffSoftwareCropActiveForRecording) _topOffZoomPanY.value else 0f
        PLog.i(
            "TopOffZoom",
            "RECORDING_RENDER_PATH softwareCrop=$topOffSoftwareCropActiveForRecording " +
                "zoom=$topOffSoftwareZoomForRecording " +
                "displayPan=($topOffSoftwarePanXForRecording,$topOffSoftwarePanYForRecording)"
        )

        val started = videoRecorder.startRecording(
            size = outputSize,
            cameraInputSize = cameraInputSize,
            cameraInputZoomRatio = topOffSoftwareZoomForRecording,
            cameraInputPanX = topOffSoftwarePanXForRecording,
            cameraInputPanY = topOffSoftwarePanYForRecording,
            cameraInputRollDegrees = if (topOffSoftwareZoomForRecording > 1f) {
                _topOffZoomRollDegrees.value
            } else {
                0f
            },
            fps = _state.value.videoConfig.fps.fps,
            bitrateMbps = if (_state.value.videoConfig.fps == VideoFpsPreset.FPS_120) {
                minOf(_state.value.videoConfig.bitrate.bitrateMbps, 100)
            } else {
                _state.value.videoConfig.bitrate.bitrateMbps
            },
            codecMime = _state.value.videoConfig.codec.mimeType,
            apvQuality = _state.value.videoConfig.apvQuality,
            fallbackCodecMimes = fallbackCodecMimes,
            fallbackOutputSizes = fallbackOutputSizes,
            colorConfig = VideoEncoderColorRequest(
                logProfile = logProfile,
                hasActiveLut = recordingLut != null && (recordingRecipe?.lutIntensity ?: 1f) > 0f
            ),
            cameraInputIsHlg10 = cameraInputIsHlg10,
            colorLayers = colorLayers,
            cameraTimestampSource = cameraTimestampSource,
            sensorOrientationDegrees = recordingSensorOrientation,
            orientationHintDegrees = baseOrientationHintDegrees,
            flipEncodedFrame = shouldFlipEncodedFrame,
            enhancedStabilization = useMgcStabilization,
            positionLockStabilization = positionLockStabilization,
            positionLockCropZoom = _topOffZoomFactor.value,
            enhancedStabilizationStrength =
                _state.value.videoConfig.enhancedStabilizationStrength,
            enhancedStabilizationLookahead =
                _state.value.videoConfig.enhancedStabilizationLookahead,
            recordingPath = _state.value.videoConfig.recordingPath,
            recordingTreeUri = _state.value.videoConfig.recordingTreeUri,
            sharedTimelineOriginProvider = if (hasSharedRecordingTimeline) {
                specialVideoRecordingTimeline::currentOriginUs
            } else null,
            sharedTimelineStopTimeProvider = if (hasSharedRecordingTimeline) {
                specialVideoRecordingTimeline::currentStopTimeUs
            } else null,
            onFirstVideoFrame = if (hasSharedRecordingTimeline) {
                { ptsUs ->
                    specialVideoRecordingTimeline.reportFirstFrame(
                        SpecialVideoRecordingTimeline.StreamId.MAIN,
                        ptsUs,
                    )
                }
            } else null,
            sharedTimelineVideoEndReporter = if (spatialVideoEnabled) {
                { endPtsUs, tailOverflowed ->
                    specialVideoRecordingTimeline.reportVideoEnd(
                        SpecialVideoRecordingTimeline.StreamId.MAIN,
                        endPtsUs,
                        tailOverflowed,
                    )
                }
            } else null,
            sharedTimelineCommonEndAwaiter = if (spatialVideoEnabled) {
                specialVideoRecordingTimeline::awaitCommonVideoEndUs
            } else null,
            sharedTimelineDrainCompleter = if (spatialVideoEnabled) {
                specialVideoRecordingTimeline::completeCameraDrain
            } else null,
            sharedCameraTimestampOffsetResolver = if (spatialVideoEnabled) {
                specialVideoRecordingTimeline::resolveCameraTimestampOffsetNs
            } else null,
            onSharedTimelineDrainComplete = if (spatialVideoEnabled) {
                ::stopSpecialVideoCameraInputsAfterDrain
            } else null,
            sharedTimelineStreamName = if (spatialVideoEnabled) "1x-main" else null,
            sharedTimelineDrainDurationMs = specialVideoDrainDurationMs,
            dualCaptureInputSize = if (dualCaptureEnabled) dualCaptureInputSize else null,
            dualCaptureSensorOrientationDegrees = if (dualCaptureEnabled) {
                _dualCaptureSensorOrientationDegrees.value
            } else {
                0
            },
            dualCaptureLayout = dualCaptureLayout,
            dualCapturePositionX = dualCapturePositionX,
            dualCapturePositionY = dualCapturePositionY,
            dualCapturePipWidthFraction = dualCapturePipWidthFraction,
            switchableCameraInputSize = switchableCameraInputSize,
            switchableCameraSensorOrientationDegrees = switchableCameraCharacteristics
                ?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
            switchableCameraTimestampSource = switchableCameraTimestampSource,
            onError = { message ->
                PLog.e(TAG, "Video recording error: $message")
                if (spatialVideoEnabled) {
                    cameraHandler?.post {
                        failSpatialVideoStandby("Primary encoder failed during Special Video: $message")
                    } ?: onCameraError?.invoke(-1, message, false)
                } else if (dualAspectRecordingActive) {
                    cameraHandler?.post {
                        clearDualAspectRecordingState("primary encoder failed")
                        spatialVideoRecorder.forceStop()
                        videoRecorder.forceStop()
                        _state.value = _state.value.copy(videoRecordingState = VideoRecordingState())
                        onCameraError?.invoke(-1, "Dual Aspect primary recording failed: $message", false)
                    } ?: onCameraError?.invoke(-1, message, false)
                } else {
                    onCameraError?.invoke(-1, message, false)
                }
            }
        ) { uri ->
            PLog.i(TAG, "Video saved: $uri")
            val completeRecording = Runnable {
                if (dualAspectRecordingActive) {
                    dualAspectPrimaryFinishedUri = uri
                    dualAspectPrimaryFinishReceived = true
                }
                val deferSpatialSaveNotification = spatialVideoEnabled && uri != null
                if (deferSpatialSaveNotification) {
                    spatialPrimaryFinishedUri = uri
                    spatialVideoSavedCallback = { onVideoSaved?.invoke(uri) }
                }
                if (dualCaptureRecordSurface != null) {
                    dualCaptureRecordSurface = null
                    if (dualCaptureEnabled && _dualCaptureSource.value == DualCaptureSource.FRONT &&
                        dualCapturePreviewSurface != null
                    ) {
                        createDualCaptureSession()
                    } else if (!dualCaptureEnabled) {
                        stopDualCaptureCamera()
                    }
                }
                if (dualAspectRecordingActive || deferSpatialSaveNotification) {
                    _state.value = _state.value.copy(
                        videoRecordingState = _state.value.videoRecordingState.copy(
                            isRecording = false,
                            isPaused = false,
                            isProcessing = true
                        )
                    )
                } else {
                    _state.value = _state.value.copy(videoRecordingState = VideoRecordingState())
                }
                if (deferSpatialSaveNotification) {
                    // Wait until all lens encoders have finished and the combined MP4 is valid
                    // before notifying the gallery that this recording is ready.
                    finalizeSpatialVideoCompanionIfReady()
                }
                if (cameraDevice != null && previewSurface != null) {
                    createPreviewSession(openGeneration = cameraOpenGeneration)
                }
                if (normalVideoUltraWideInstantSwitchActive &&
                    normalVideoStreamingMode != SpatialVideoStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL
                ) {
                    createSpatialVideoStandbySession("Normal Video recording finished")
                }
                if (!deferSpatialSaveNotification) onVideoSaved?.invoke(uri)
                if (dualAspectRecordingActive) finalizeDualAspectCompanionIfReady()
            }
            cameraHandler?.post(completeRecording) ?: completeRecording.run()
        }
        if (!started) {
                videoCameraHlg10InputActive = false
            if (dualAspectRecordingActive) clearDualAspectRecordingState("primary encoder preparation failed")
            return
        }
        if (switchableCameraInputSize != null) {
            videoRecorder.setSwitchableCameraInputActive(
                isNormalVideoUltraWideLens(_state.value.getCurrentCameraInfo())
            )
        }
        if (selectedDualAspectRatio != null) {
            val companionFile = dualAspectCompanionFile
            if (companionFile == null) {
                clearDualAspectRecordingState("secondary output path unavailable")
                videoRecorder.forceStop()
                onCameraError?.invoke(-1, "Dual Aspect could not prepare its second output file.", false)
                return
            }
            val secondaryStarted = spatialVideoRecorder.startRecording(
                size = selectedDualAspectRatio.resolveOutputSize(_state.value.videoConfig.resolution),
                cameraInputSize = cameraInputSize,
                cameraInputZoomRatio = topOffSoftwareZoomForRecording,
                cameraInputPanX = topOffSoftwarePanXForRecording,
                cameraInputPanY = topOffSoftwarePanYForRecording,
                cameraInputRollDegrees = if (topOffSoftwareZoomForRecording > 1f) {
                    _topOffZoomRollDegrees.value
                } else {
                    0f
                },
                fps = _state.value.videoConfig.fps.fps,
                bitrateMbps = _state.value.videoConfig.bitrate.bitrateMbps,
                codecMime = _state.value.videoConfig.codec.mimeType,
                apvQuality = _state.value.videoConfig.apvQuality,
                fallbackCodecMimes = if (_state.value.videoConfig.codec == com.hinnka.mycamera.video.VideoCodec.APV) {
                    emptyList()
                } else {
                    _state.value.videoCapabilities.availableCodecs
                        .filter {
                            it != _state.value.videoConfig.codec &&
                                it != com.hinnka.mycamera.video.VideoCodec.APV
                        }
                        .map { it.mimeType }
                },
                colorConfig = VideoEncoderColorRequest(
                    logProfile = logProfile,
                    hasActiveLut = recordingLut != null && (recordingRecipe?.lutIntensity ?: 1f) > 0f
                ),
                cameraInputIsHlg10 = cameraInputIsHlg10,
                colorLayers = colorLayers,
                cameraTimestampSource = cameraTimestampSource,
                sensorOrientationDegrees = _state.value.getCurrentCameraInfo()?.sensorOrientation
                    ?: cachedSensorOrientation,
                orientationHintDegrees = selectedDualAspectRatio.resolveOrientationHint(baseOrientationHintDegrees),
                flipEncodedFrame = shouldFlipEncodedFrame,
                enhancedStabilization = false,
                recordingPath = dualAspectOutputRecordingPath,
                recordingTreeUri = dualAspectOutputTreeUri,
                privateOutputFile = companionFile,
                recordAudio = false,
                sharedTimelineOriginProvider = if (hasSharedRecordingTimeline) {
                    specialVideoRecordingTimeline::currentOriginUs
                } else null,
                sharedTimelineStopTimeProvider = if (hasSharedRecordingTimeline) {
                    specialVideoRecordingTimeline::currentStopTimeUs
                } else null,
                onFirstVideoFrame = if (hasSharedRecordingTimeline) {
                    { ptsUs ->
                        specialVideoRecordingTimeline.reportFirstFrame(
                            sharedTimelineSecondaryStreamId,
                            ptsUs,
                        )
                    }
                } else null,
                onError = { message ->
                    PLog.e(TAG, "Dual Aspect secondary encoder error: $message")
                    cameraHandler?.post {
                        clearDualAspectRecordingState("secondary encoder failed")
                        videoRecorder.forceStop()
                        spatialVideoRecorder.forceStop()
                        _state.value = _state.value.copy(videoRecordingState = VideoRecordingState())
                        onCameraError?.invoke(-1, "Dual Aspect secondary recording failed: $message", false)
                    } ?: onCameraError?.invoke(-1, message, false)
                }
            ) { finishedUri ->
                val completeSecondary = Runnable {
                    dualAspectSecondaryFinishedFile =
                        finishedUri?.path?.let(::File) ?: companionFile.takeIf { it.isFile }
                    dualAspectSecondaryFinishReceived = true
                    finalizeDualAspectCompanionIfReady()
                }
                cameraHandler?.post(completeSecondary) ?: completeSecondary.run()
            }
            if (!secondaryStarted) {
                clearDualAspectRecordingState("secondary encoder preparation failed")
                videoRecorder.forceStop()
                onCameraError?.invoke(-1, "Dual Aspect could not start the second video encoder.", false)
                return
            }
        } else if (spatialVideoEnabled) {
            val primaryUri = videoRecorder.pendingOutputUri
            val wideId = spatialVideoUltraWidePhysicalCameraId
            if (primaryUri == null || wideId == null) {
                failSpatialVideoStandby("Special Video could not prepare its 0.5x companion output.")
                return
            }
            val wideFile = SpatialVideoStore.ultraWideFile(context, primaryUri)
            if (wideFile.exists() && !wideFile.delete()) {
                failSpatialVideoStandby("The previous ultra-wide companion file could not be cleared.")
                return
            }
            val wideInputSize = resolveSpatialVideoInputSize(wideId, cameraInputSize)
            if (wideInputSize == null) {
                failSpatialVideoStandby("The ultra-wide camera does not expose a compatible video stream size.")
                return
            }
            val telephotoInputSize = if (spatialVideoVirtualTelephotoEnabled) {
                // Prefer a 4K-class main-sensor input for the 3.5x stream even when the selected
                // recording preset is 1080p. This preserves more detail before the live 3.5x crop.
                val videoAspectRatio = cameraInputSize.width.toFloat() /
                    cameraInputSize.height.coerceAtLeast(1)
                val telephotoInputLimit = if (videoAspectRatio >= 1f) {
                    Size(4096, (4096f / videoAspectRatio).toInt().coerceAtLeast(720))
                } else {
                    Size((4096f * videoAspectRatio).toInt().coerceAtLeast(720), 4096)
                }
                val telephotoCameraId = spatialVideoMainPhysicalCameraId ?: spatialVideoPrimaryCameraId
                val inputSize = telephotoCameraId?.let {
                    resolveSpatialVideoInputSize(it, telephotoInputLimit)
                } ?: cameraInputSize
                PLog.i(
                    TAG,
                    "Special Video 3.5x sensor input selected: camera=$telephotoCameraId " +
                        "size=${inputSize.width}x${inputSize.height}, " +
                        "recordingInput=${cameraInputSize.width}x${cameraInputSize.height}"
                )
                inputSize
            } else {
                null
            }
            val telephotoFile = if (spatialVideoVirtualTelephotoEnabled) {
                SpatialVideoStore.telephotoFile(context, primaryUri)
            } else {
                null
            }
            if (telephotoFile != null && telephotoFile.exists() && !telephotoFile.delete()) {
                failSpatialVideoStandby("The previous 3.5x telephoto companion file could not be cleared.")
                return
            }
            spatialPrimaryFinishedUri = null
            spatialUltraWideFinishedFile = null
            spatialTelephotoFinishedFile = null
            spatialVideoWideFinishReceived = false
            spatialVideoTelephotoFinishReceived = !spatialVideoVirtualTelephotoEnabled || telephotoInputSize == null || telephotoFile == null
            spatialVideoSavedCallback = null
            val wideCharacteristics = runCatching {
                cameraManager.getCameraCharacteristics(wideId)
            }.getOrNull()
            spatialVideoCompanionFile = wideFile
            val wideStarted = spatialVideoRecorder.startRecording(
                size = outputSize,
                cameraInputSize = wideInputSize,
                fps = _state.value.videoConfig.fps.fps,
                bitrateMbps = _state.value.videoConfig.bitrate.bitrateMbps,
                codecMime = _state.value.videoConfig.codec.mimeType,
                apvQuality = _state.value.videoConfig.apvQuality,
                colorConfig = VideoEncoderColorRequest(
                    logProfile = logProfile,
                    hasActiveLut = recordingLut != null && (recordingRecipe?.lutIntensity ?: 1f) > 0f
                ),
                cameraInputIsHlg10 = false,
                colorLayers = colorLayers,
                cameraTimestampSource = wideCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
                    ?: CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN,
                sensorOrientationDegrees = wideCharacteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                orientationHintDegrees = resolveDedicatedVideoOrientationHintDegrees(orientationOffsetDegrees),
                flipEncodedFrame = false,
                enhancedStabilization = false,
                recordingPath = _state.value.videoConfig.recordingPath,
                recordingTreeUri = _state.value.videoConfig.recordingTreeUri,
                privateOutputFile = wideFile,
                recordAudio = false,
                sharedTimelineOriginProvider = if (hasSharedRecordingTimeline) {
                    specialVideoRecordingTimeline::currentOriginUs
                } else null,
                sharedTimelineStopTimeProvider = if (hasSharedRecordingTimeline) {
                    specialVideoRecordingTimeline::currentStopTimeUs
                } else null,
                onFirstVideoFrame = if (hasSharedRecordingTimeline) {
                    { ptsUs ->
                        specialVideoRecordingTimeline.reportFirstFrame(
                            sharedTimelineSecondaryStreamId,
                            ptsUs,
                        )
                    }
                } else null,
                sharedTimelineVideoEndReporter = if (spatialVideoEnabled) {
                    { endPtsUs, tailOverflowed ->
                        specialVideoRecordingTimeline.reportVideoEnd(
                            SpecialVideoRecordingTimeline.StreamId.ULTRA_WIDE,
                            endPtsUs,
                            tailOverflowed,
                        )
                    }
                } else null,
                sharedTimelineCommonEndAwaiter = if (spatialVideoEnabled) {
                    specialVideoRecordingTimeline::awaitCommonVideoEndUs
                } else null,
                sharedTimelineDrainAwaiter = if (spatialVideoEnabled) {
                    specialVideoRecordingTimeline::awaitCameraDrain
                } else null,
                sharedTimelineDrainCompleter = if (spatialVideoEnabled) {
                    specialVideoRecordingTimeline::completeCameraDrain
                } else null,
                sharedCameraTimestampOffsetResolver = if (spatialVideoEnabled) {
                    specialVideoRecordingTimeline::resolveCameraTimestampOffsetNs
                } else null,
                sharedTimelineStreamName = if (spatialVideoEnabled) "0.5x-ultraWide" else null,
                onError = { message ->
                    PLog.e(TAG, "Special Video ultra-wide recorder error: $message")
                    cameraHandler?.post {
                        if (spatialVideoEnabled && videoRecorder.isRecording()) {
                            failSpatialVideoStandby("Ultra-wide encoder failed: $message")
                        } else {
                            onCameraError?.invoke(-1, message, false)
                        }
                    } ?: onCameraError?.invoke(-1, message, false)
                }
            ) { wideUri ->
                spatialUltraWideFinishedFile = wideUri?.path?.let(::File)
                spatialVideoWideFinishReceived = true
                finalizeSpatialVideoCompanionIfReady()
            }
            if (!wideStarted) {
                wideFile.delete()
                spatialVideoCompanionFile = null
                spatialVideoRecorder.forceStop()
                failSpatialVideoStandby("Special Video could not start the ultra-wide encoder.")
                return
            }

            if (spatialVideoVirtualTelephotoEnabled && telephotoInputSize != null && telephotoFile != null) {
                spatialVideoTelephotoCompanionFile = telephotoFile
                val telephotoStarted = spatialVideoTelephotoRecorder.startRecording(
                    size = outputSize,
                    cameraInputSize = telephotoInputSize,
                    cameraInputZoomRatio = 3.5f,
                    fps = _state.value.videoConfig.fps.fps,
                    bitrateMbps = _state.value.videoConfig.bitrate.bitrateMbps,
                    codecMime = _state.value.videoConfig.codec.mimeType,
                    apvQuality = _state.value.videoConfig.apvQuality,
                    fallbackCodecMimes = fallbackCodecMimes,
                    fallbackOutputSizes = fallbackOutputSizes,
                    colorConfig = VideoEncoderColorRequest(
                        logProfile = logProfile,
                        hasActiveLut = recordingLut != null && (recordingRecipe?.lutIntensity ?: 1f) > 0f
                    ),
                    cameraInputIsHlg10 = cameraInputIsHlg10,
                    colorLayers = colorLayers,
                    cameraTimestampSource = cameraTimestampSource,
                    sensorOrientationDegrees = _state.value.getCurrentCameraInfo()?.sensorOrientation
                        ?: cachedSensorOrientation,
                    orientationHintDegrees = baseOrientationHintDegrees,
                    flipEncodedFrame = shouldFlipEncodedFrame,
                    enhancedStabilization = false,
                    recordingPath = _state.value.videoConfig.recordingPath,
                    recordingTreeUri = _state.value.videoConfig.recordingTreeUri,
                    privateOutputFile = telephotoFile,
                    recordAudio = false,
                    sharedTimelineOriginProvider = specialVideoRecordingTimeline::currentOriginUs,
                    sharedTimelineStopTimeProvider = specialVideoRecordingTimeline::currentStopTimeUs,
                    onFirstVideoFrame = { ptsUs ->
                        specialVideoRecordingTimeline.reportFirstFrame(
                            SpecialVideoRecordingTimeline.StreamId.TELEPHOTO,
                            ptsUs,
                        )
                    },
                    sharedTimelineVideoEndReporter = { endPtsUs, tailOverflowed ->
                        specialVideoRecordingTimeline.reportVideoEnd(
                            SpecialVideoRecordingTimeline.StreamId.TELEPHOTO,
                            endPtsUs,
                            tailOverflowed,
                        )
                    },
                    sharedTimelineCommonEndAwaiter = specialVideoRecordingTimeline::awaitCommonVideoEndUs,
                    sharedTimelineDrainAwaiter = specialVideoRecordingTimeline::awaitCameraDrain,
                    sharedTimelineDrainCompleter = specialVideoRecordingTimeline::completeCameraDrain,
                    sharedCameraTimestampOffsetResolver = specialVideoRecordingTimeline::resolveCameraTimestampOffsetNs,
                    sharedTimelineStreamName = "3.5x-virtual-telephoto",
                    onError = { message ->
                        PLog.e(TAG, "Special Video 3.5x telephoto recorder error: $message")
                        cameraHandler?.post {
                            if (spatialVideoEnabled && videoRecorder.isRecording()) {
                                failSpatialVideoStandby("3.5x telephoto encoder failed: $message")
                            } else {
                                onCameraError?.invoke(-1, message, false)
                            }
                        } ?: onCameraError?.invoke(-1, message, false)
                    }
                ) { telephotoUri ->
                    spatialTelephotoFinishedFile =
                        telephotoUri?.path?.let(::File) ?: telephotoFile.takeIf { it.isFile }
                    spatialVideoTelephotoFinishReceived = true
                    finalizeSpatialVideoCompanionIfReady()
                }
                if (!telephotoStarted) {
                    telephotoFile.delete()
                    spatialVideoTelephotoCompanionFile = null
                    spatialVideoRecorder.forceStop()
                    videoRecorder.forceStop()
                    failSpatialVideoStandby("Special Video could not start its 3.5x virtual telephoto encoder.")
                    return
                }
            }
            if (spatialVideoStreamingMode != SpatialVideoStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL &&
                !startSpatialVideoStandbyCamera(wideId)
            ) {
                failSpatialVideoStandby("Special Video could not open the independent ultra-wide Camera2 device.")
                return
            }
        }
        if (dualCaptureEnabled) {
            refreshDualCaptureRecordingTarget()
            if (dualCaptureRecordSurface == null) return
        }
        if (switchableCameraInputSize != null &&
            normalVideoStreamingMode != SpatialVideoStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL
        ) {
            val wideId = normalVideoUltraWidePhysicalCameraId
            if (wideId != null) {
                if (spatialVideoStandbyCameraDevice == null) {
                    startSpatialVideoStandbyCamera(wideId)
                } else {
                    createSpatialVideoStandbySession("Normal Video alternate recording input attached")
                }
            }
        }
        _state.value = _state.value.copy(
            videoRecordingState = VideoRecordingState(isRecording = true, elapsedMs = 0L)
        )
        videoRecordingPausedMs = 0L

        createPreviewSession(openGeneration = cameraOpenGeneration)
    }

    private fun finalizeDualAspectCompanionIfReady() {
        if (!dualAspectRecordingActive || !dualAspectPrimaryFinishReceived ||
            !dualAspectSecondaryFinishReceived || dualAspectFinalizing
        ) return

        val primaryUri = dualAspectPrimaryFinishedUri
        val companionFile = dualAspectSecondaryFinishedFile ?: dualAspectCompanionFile
        val ratio = dualAspectOutputRatio
        if (companionFile == null || ratio == null) {
            clearDualAspectRecordingState("dual output missing at finalization")
            _state.value = _state.value.copy(videoRecordingState = VideoRecordingState())
            return
        }

        dualAspectFinalizing = true
        val dateTakenMs = dualAspectOutputDateTakenMs
        val recordingPath = dualAspectOutputRecordingPath
        val recordingTreeUri = dualAspectOutputTreeUri

        Thread({
            val publishedUri = try {
                if (primaryUri != null && companionFile.isFile) {
                    SpatialVideoStore.attachPrimaryAudioTrack(context, primaryUri, companionFile)
                }
                runBlocking(Dispatchers.IO) {
                    VideoMediaStoreWriter.publishVideo(
                        context = context,
                        sourceFile = companionFile,
                        dateTakenMs = dateTakenMs,
                        recordingPath = recordingPath,
                        recordingTreeUri = recordingTreeUri,
                        fileNameSuffix = "Dual_" + ratio.fileSuffix
                    )
                }
            } catch (error: Exception) {
                PLog.e(TAG, "Failed to publish Dual Aspect companion video", error)
                null
            }

            runCatching { companionFile.delete() }
            val finish = Runnable {
                dualAspectRecordingActive = false
                dualAspectFinalizing = false
                dualAspectPrimaryFinishReceived = false
                dualAspectSecondaryFinishReceived = false
                dualAspectPrimaryFinishedUri = null
                dualAspectSecondaryFinishedFile = null
                dualAspectCompanionFile = null
                dualAspectOutputRatio = null
                specialVideoRecordingTimeline.reset()
                _state.value = _state.value.copy(videoRecordingState = VideoRecordingState())
                if (cameraDevice != null && previewSurface != null) {
                    createPreviewSession(openGeneration = cameraOpenGeneration)
                }
                if (publishedUri != null) {
                    PLog.i(TAG, "Dual Aspect companion saved: $publishedUri")
                    onVideoSaved?.invoke(publishedUri)
                } else {
                    onCameraError?.invoke(
                        -1,
                        "The primary video was saved, but the second Dual Aspect video could not be published.",
                        false
                    )
                }
            }
            cameraHandler?.post(finish) ?: finish.run()
        }, "Photon-DualAspect-Finalize").start()
    }

    private fun clearDualAspectRecordingState(reason: String) {
        if (dualAspectFinalizing) return
        dualAspectCompanionFile?.let { file -> if (file.exists()) runCatching { file.delete() } }
        dualAspectRecordingActive = false
        dualAspectPrimaryFinishReceived = false
        dualAspectSecondaryFinishReceived = false
        dualAspectPrimaryFinishedUri = null
        dualAspectSecondaryFinishedFile = null
        dualAspectCompanionFile = null
        dualAspectOutputRatio = null
        dualAspectOutputTreeUri = null
        dualAspectFinalizing = false
        specialVideoRecordingTimeline.reset()
        PLog.w(TAG, "Dual Aspect recording state cleared: $reason")
    }

    private fun isCurrentCameraFrontFacing(): Boolean {
        return cachedLensFacing == CameraCharacteristics.LENS_FACING_FRONT ||
            _state.value.getCurrentCameraInfo()?.lensType == LensType.FRONT
    }

    private fun resolveVideoSessionOperatingMode(): Int {
        if (!oppoSuperStabilizationEnabled || !DeviceUtil.isOppo) {
            return SessionConfiguration.SESSION_REGULAR
        }
        return if (isCurrentCameraFrontFacing()) {
            OPPO_SUPER_STABILIZATION_FRONT_SESSION_MODE
        } else {
            OPPO_SUPER_STABILIZATION_REAR_SESSION_MODE
        }
    }

    private fun shouldUseVideoEnhancedStabilization(): Boolean {
        val state = _state.value
        return state.videoConfig.stabilizationMode == VideoStabilizationMode.ENHANCED &&
            state.videoConfig.fps != VideoFpsPreset.FPS_120 &&
            state.captureMode == CaptureMode.VIDEO &&
            VideoStabilizationMode.ENHANCED in
                state.videoCapabilities.availableStabilizationModes &&
            realtimeStabilizationCoordinator.isCurrentCameraSupported
    }

    private fun shouldUsePositionLockStabilization(): Boolean {
        val state = _state.value
        return _topOffPositionLockActive.value &&
            state.captureMode == CaptureMode.VIDEO &&
            state.videoConfig.fps != VideoFpsPreset.FPS_120 &&
            !spatialVideoEnabled && !dualCaptureEnabled && !dualAspectRecordingActive &&
            !normalVideoUltraWideInstantSwitchActive &&
            realtimeStabilizationCoordinator.isCurrentCameraSupported
    }

    private fun shouldUseVideoMgcStabilization(): Boolean =
        shouldUseVideoEnhancedStabilization() || shouldUsePositionLockStabilization()

    private fun shouldUseAlgorithmicStabilization(): Boolean {
        val state = _state.value
        return when (state.captureMode) {
            CaptureMode.PHOTO -> state.photoPreviewStabilizationEnabled &&
                realtimeStabilizationCoordinator.isCurrentCameraSupported
            CaptureMode.VIDEO -> shouldUseVideoMgcStabilization()
        }
    }

    private fun currentStabilizationCameraId(): String {
        return activeOutputPhysicalCameraId
            ?.takeIf { it.isNotEmpty() }
            ?: getActiveOpenCameraId()
    }

    private fun isEnhancedStabilizationUnavailableForCurrentCamera(): Boolean {
        return currentStabilizationCameraId() in enhancedStabilizationUnavailableCameraIds
    }

    private fun resolvePreviousVideoStabilizationMode(
        availableModes: List<VideoStabilizationMode>
    ): VideoStabilizationMode {
        return previousVideoStabilizationMode.takeIf { mode ->
            mode != VideoStabilizationMode.ENHANCED && mode in availableModes
        } ?: when {
            VideoStabilizationMode.OIS in availableModes -> VideoStabilizationMode.OIS
            VideoStabilizationMode.EIS in availableModes -> VideoStabilizationMode.EIS
            else -> VideoStabilizationMode.OFF
        }
    }

    private fun preferredHardwareVideoStabilizationMode(): VideoStabilizationMode {
        val availableModes = _state.value.videoCapabilities.availableStabilizationModes
        return when {
            VideoStabilizationMode.OIS in availableModes -> VideoStabilizationMode.OIS
            VideoStabilizationMode.EIS in availableModes -> VideoStabilizationMode.EIS
            else -> VideoStabilizationMode.OFF
        }
    }

    fun pauseVideoRecording() {
        if (!_state.value.videoRecordingState.isRecording || _state.value.videoRecordingState.isPaused) return
        videoRecorder.pauseRecording()
        if ((spatialVideoEnabled || dualAspectRecordingActive) && spatialVideoRecorder.isRecording()) {
            spatialVideoRecorder.pauseRecording()
        }
        if (spatialVideoEnabled && spatialVideoVirtualTelephotoEnabled &&
            spatialVideoTelephotoRecorder.isRecording()
        ) {
            spatialVideoTelephotoRecorder.pauseRecording()
        }
        // Keep the paired Camera2 outputs attached during Special Video pause. Removing the
        // main target alone would detach the simultaneous wide/telephoto outputs as well.
        if (videoRecorder.usesDedicatedCameraInput && !spatialVideoEnabled) {
            updateDedicatedVideoRepeatingTarget(includeVideoSurface = false)
        }
        videoRecordingPauseStartElapsedMs = SystemClock.elapsedRealtime()
        _state.value = _state.value.copy(
            videoRecordingState = _state.value.videoRecordingState.copy(isPaused = true)
        )
    }

    fun resumeVideoRecording() {
        if (!_state.value.videoRecordingState.isRecording || !_state.value.videoRecordingState.isPaused) return
        if (videoRecorder.usesDedicatedCameraInput && !spatialVideoEnabled &&
            !updateDedicatedVideoRepeatingTarget(includeVideoSurface = true)
        ) {
            PLog.e(TAG, "Cannot resume dedicated video input because the recording surface is unavailable")
            return
        }
        videoRecorder.resumeRecording()
        if ((spatialVideoEnabled || dualAspectRecordingActive) && spatialVideoRecorder.isRecording()) {
            spatialVideoRecorder.resumeRecording()
        }
        if (spatialVideoEnabled && spatialVideoVirtualTelephotoEnabled &&
            spatialVideoTelephotoRecorder.isRecording()
        ) {
            spatialVideoTelephotoRecorder.resumeRecording()
        }
        videoRecordingPausedMs += SystemClock.elapsedRealtime() - videoRecordingPauseStartElapsedMs
        _state.value = _state.value.copy(
            videoRecordingState = _state.value.videoRecordingState.copy(isPaused = false)
        )
    }

    private suspend fun stopSpecialVideoCameraInputsAfterDrain() {
        val handler = cameraHandler ?: return
        suspendCancellableCoroutine<Unit> { continuation ->
            val posted = handler.post {
                if (spatialVideoEnabled) {
                    runCatching { captureSession?.stopRepeating() }
                        .onFailure {
                            PLog.w(TAG, "Special Video primary repeating request stop failed: ${it.message}")
                        }
                    if (spatialVideoStreamingMode != SpatialVideoStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL) {
                        runCatching { spatialVideoStandbySession?.stopRepeating() }
                            .onFailure {
                                PLog.w(TAG, "Special Video ultra-wide repeating request stop failed: ${it.message}")
                            }
                    }
                    PLog.i(
                        TAG,
                        "Special Video camera inputs stopped after paired drain: mode=$spatialVideoStreamingMode"
                    )
                }
                if (continuation.isActive) continuation.resume(Unit)
            }
            if (!posted && continuation.isActive) continuation.resume(Unit)
        }
        // stopRepeating prevents new requests but the final in-flight Camera2 results and
        // SurfaceTexture callbacks can still arrive just afterwards. Keep the recorder's
        // stop-drain gate open briefly so those final pre-cutoff frames reach the encoder.
        kotlinx.coroutines.delay(100L)
    }

    fun stopVideoRecording() {
        if (!_state.value.videoRecordingState.isRecording) return
        videoCaptureStatsWindowStartMs = 0L
        videoCaptureStatsFrames = 0
        videoCaptureStatsFirstTimestampNs = 0L
        stopVideoRecordingTicker()
        _state.value = _state.value.copy(
            videoRecordingState = _state.value.videoRecordingState.copy(
                isRecording = false,
                isPaused = false,
                isProcessing = videoRecorder.usesDedicatedCameraInput || dualAspectRecordingActive
            )
        )
        val deferCameraInputStopForSpecialVideo = spatialVideoEnabled
        if (videoRecorder.usesDedicatedCameraInput &&
            !_state.value.videoRecordingState.isPaused &&
            !deferCameraInputStopForSpecialVideo
        ) {
            updateDedicatedVideoRepeatingTarget(includeVideoSurface = false)
        } else if (!deferCameraInputStopForSpecialVideo) {
            previewRequestBuilder?.apply {
                applyWhiteBalanceSettings(this, _state.value, isCapture = false)
                updatePreview()
            }
        }
        videoCameraHlg10InputActive = false
        if (dualCaptureRecordSurface != null) {
            dualCaptureRecordSurface = null
            if (dualCaptureEnabled && _dualCaptureSource.value == DualCaptureSource.ULTRA_WIDE) {
                if (cameraDevice != null && previewSurface != null) {
                    createPreviewSession(openGeneration = cameraOpenGeneration)
                }
            } else if (dualCaptureEnabled && dualCapturePreviewSurface != null) {
                createDualCaptureSession()
            } else {
                stopDualCaptureCamera()
            }
        }
        val hasPairedVideoRecording = spatialVideoEnabled || dualAspectRecordingActive
        if (hasPairedVideoRecording) {
            specialVideoRecordingTimeline.requestStop()
        }
        videoRecorder.stopRecording()
        if (hasPairedVideoRecording) {
            spatialVideoRecorder.stopRecording()
            if (spatialVideoEnabled && spatialVideoVirtualTelephotoEnabled) {
                spatialVideoTelephotoRecorder.stopRecording()
            }
        }
        if (spatialVideoEnabled) {
            spatialVideoStandbyRepeatingRequest = null
            spatialVideoPrimarySessionAwaitingStandby = false
            // Do not detach or rebuild either Special Video input here. Both encoders still
            // need the paired drain window; the primary recorder stops both repeating requests
            // only after EIS+ has emitted its delayed pre-cutoff frames.
            PLog.i(
                TAG,
                "Special Video stop requested; keeping both Camera2 inputs alive for " +
                    "the paired drain window (${specialVideoDrainDurationMs}ms)"
            )
        }
        if (pendingVendorSessionParameterRestart &&
            !spatialVideoEnabled &&
            cameraDevice != null &&
            previewSurface != null
        ) {
            PLog.d(TAG, "Restarting preview session after recording for pending vendor session parameter change")
            createPreviewSession(openGeneration = cameraOpenGeneration)
        }
    }

    private fun updateDedicatedVideoRepeatingTarget(includeVideoSurface: Boolean): Boolean {
        val device = cameraDevice ?: return false
        val session = captureSession ?: return false
        val previewTarget = previewSurface ?: return false
        val encoderTarget = videoRecorder.cameraInputSurface
        if (includeVideoSurface && encoderTarget == null) return false
        val highSpeedFpsRange = if (_state.value.videoConfig.fps == VideoFpsPreset.FPS_120) {
            resolveHighSpeed120FpsRange() ?: return false
        } else {
            null
        }

        return try {
            val useEnhancedStabilization = shouldUseVideoEnhancedStabilization()
            val useMgcStabilization = useEnhancedStabilization || shouldUsePositionLockStabilization()
            val templateType = if (useMgcStabilization) {
                CameraDevice.TEMPLATE_PREVIEW
            } else {
                CameraDevice.TEMPLATE_RECORD
            }
            PLog.i(
                TAG,
                "Updating dedicated video repeating request: " +
                    "template=${if (templateType == CameraDevice.TEMPLATE_PREVIEW) "PREVIEW" else "RECORD"}, " +
                    "mgcStabilization=$useMgcStabilization, " +
                    "includeVideoSurface=$includeVideoSurface"
            )
            val builder = device.createCaptureRequest(templateType).apply {
                addTarget(previewTarget)
                stabilizationImageReader?.surface
                    ?.takeIf { useMgcStabilization }
                    ?.let(::addTarget)
                encoderTarget
                    ?.takeIf { includeVideoSurface && !useMgcStabilization }
                    ?.let(::addTarget)
                spatialVideoRecorder.cameraInputSurface
                    ?.takeIf { includeVideoSurface && dualAspectRecordingActive }
                    ?.let(::addTarget)
                videoRecorder.switchableCameraInputSurface
                    ?.takeIf {
                        includeVideoSurface &&
                            normalVideoUltraWideInstantSwitchActive &&
                            normalVideoStreamingMode == SpatialVideoStreamingMode.LOGICAL_MULTI_CAMERA_PHYSICAL
                    }
                    ?.let(::addTarget)
                applyBaseCameraSettings(this, isCapture = false)
            }
            if (useMgcStabilization && _state.value.videoConfig.fps.fps == 30) {
                val min30FpsRange = resolveEnhancedStabilization30FpsRange()
                if (min30FpsRange != null) {
                    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, min30FpsRange)
                    PLog.i(
                        TAG,
                        "EIS+ 4K30 frame-rate floor requested: aeRange=$min30FpsRange " +
                            "(prefers a supported 31-fps floor when the camera advertises one)"
                    )
                } else {
                    PLog.w(
                        TAG,
                        "EIS+ 4K30: camera advertises no AE FPS range with a 30-fps minimum; " +
                            "30-fps sensor cadence cannot be guaranteed by Camera2"
                    )
                }
            }
            if (_state.value.videoConfig.fps == VideoFpsPreset.FPS_120) {
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, requireNotNull(highSpeedFpsRange))
                builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            }
            previewRequestBuilder = builder
            submitRepeatingPreviewRequest(session, builder)
            true
        } catch (e: Exception) {
            PLog.e(
                TAG,
                "Failed to ${if (includeVideoSurface) "attach" else "detach"} dedicated video target",
                e
            )
            false
        }
    }

    private fun resolveDedicatedVideoOrientationHintDegrees(orientationOffsetDegrees: Int): Int {
        // UI 在反向竖屏时保持 0°，录像必须使用包含 180° 的拍摄方向。
        val captureRotationDegrees = OrientationObserver.captureRotationDegrees.toInt()
        val orientationHintDegrees = resolveSurfaceTextureVideoOrientationDegrees(
            deviceRotationDegrees = captureRotationDegrees,
            calibrationOffsetDegrees = orientationOffsetDegrees,
        )
        PLog.d(
            TAG,
            "Video orientation: capture=$captureRotationDegrees, " +
                "calibrationOffset=$orientationOffsetDegrees, orientationHint=$orientationHintDegrees"
        )
        return orientationHintDegrees
    }

// ==================== 延时拍摄和网格线 ====================

    /**
     * 设置延时拍摄秒数
     */
    fun setTimerSeconds(seconds: Int) {
        _state.value = _state.value.copy(timerSeconds = seconds)
    }

    /**
     * 设置倒计时值（用于UI显示）
     */
    fun setCountdownValue(value: Int) {
        _state.value = _state.value.copy(countdownValue = value)
    }

    /**
     * 设置是否显示网格线
     */
    fun setShowGrid(show: Boolean) {
        _state.value = _state.value.copy(showGrid = show)
    }

    fun setGridStyle(style: GridStyle) {
        _state.value = _state.value.copy(gridStyle = style)
    }

    fun setRawCfaCorrectionModes(modes: Map<String, String>) {
        _state.value = _state.value.copy(rawCfaCorrectionModes = modes.toMap())
    }

    fun setMultiFrameOutputScale(outputScale: Float?) {
        val currentState = _state.value
        val normalizedScale = outputScale?.let(MultiFrameConfig::normalizeOutputScale)
        _state.value = currentState.copy(
            multiFrameOutputScale = normalizedScale,
        )
    }

    fun setUseMultipleExposure(useMultipleExposure: Boolean) {
        _state.value = _state.value.copy(useMultipleExposure = useMultipleExposure)
    }

    fun onHdrBracketFramesCollected() {
        _state.value = _state.value.copy(
            hdrBracketCapturing = false,
            hdrBracketFrameCount = 0
        )
    }

    fun setJpgMultiFrameDenoiseFrameCount(frameCount: Int) {
        _state.value = _state.value.copy(
            jpgMultiFrameDenoiseFrameCount = MultiFrameConfig.normalizeDenoiseFrameCount(frameCount),
        )
    }

    fun setHdrPlusMergeMode(mode: MgcRawMaxMode) {
        val currentState = _state.value
        _state.value = currentState.copy(
            hdrPlusMergeMode = mode,
            hdrPlusBracketExposureEnabled = currentState.hdrPlusBracketExposureEnabled &&
                mode.supportsBracketExposure,
        )
    }

    fun setHdrPlusFrameCount(frameCount: Int) {
        val currentState = _state.value
        _state.value = _state.value.copy(
            hdrPlusFrameCount = MultiFrameConfig.normalizeHdrPlusFrameCount(
                frameCount,
                bracketExposureEnabled = currentState.hdrPlusMergeMode.supportsBracketExposure &&
                    currentState.hdrPlusBracketExposureEnabled,
            ),
        )
    }

    fun setUseJpgMaxHdrComposition(enabled: Boolean) {
        _state.value = _state.value.copy(useJpgMaxHdrComposition = enabled)
    }

    fun setAutoHdrEnabled(enabled: Boolean) {
        _state.update { it.copy(autoHdrEnabled = enabled) }
    }

    fun setRefocusCaptureSettings(enabled: Boolean, frameCount: Int) {
        _state.update {
            it.copy(
                refocusCaptureEnabled = enabled,
                refocusFrameCount = frameCount.coerceIn(3, 8),
            )
        }
    }

    fun setFastShutterTargetFps(fps: Int) {
        fastShutterTargetFps = fps.coerceIn(5, 20)
    }

    /**
     * Select only a camera-advertised AE FPS range. Prefer a supported 31-fps floor as headroom;
     * otherwise require a 30-fps floor. Never invent an unsupported Range or silently select a
     * range whose lower bound allows the sensor cadence to fall below 30 fps.
     */
    private fun resolveEnhancedStabilization30FpsRange(): Range<Int>? {
        val ranges = getActiveOpenCameraCharacteristics()
            ?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.toList()
            .orEmpty()
        return ranges
            .filter { it.lower == 31 }
            .minByOrNull { it.upper - it.lower }
            ?: ranges
                .filter { it.lower >= 30 }
                .minWithOrNull(compareBy<Range<Int>> { it.lower }.thenBy { it.upper - it.lower })
    }

    private fun applyFastShutterTargetFps(builder: CaptureRequest.Builder) {
        val ranges = getActiveOpenCameraCharacteristics()
            ?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.toList()
            .orEmpty()
        val requestedFps = fastShutterTargetFps
        val selected = ranges.filter { requestedFps in it.lower..it.upper }
            .minByOrNull { it.upper - it.lower }
            ?: ranges.filter { it.upper <= requestedFps }.maxByOrNull { it.upper }
            ?: ranges.minByOrNull { it.upper }
        if (selected != null) {
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selected)
        }
    }

    fun setHdrPlusBracketExposureEnabled(enabled: Boolean) {
        val currentState = _state.value
        val bracketExposureEnabled = enabled && currentState.hdrPlusMergeMode.supportsBracketExposure
        _state.value = currentState.copy(
            hdrPlusBracketExposureEnabled = bracketExposureEnabled,
            hdrPlusFrameCount = MultiFrameConfig.normalizeHdrPlusFrameCount(
                currentState.hdrPlusFrameCount,
                bracketExposureEnabled = bracketExposureEnabled,
            ),
        )
    }

    fun setHdrPlusLongFrameExposureEv(value: Float) {
        _state.value = _state.value.copy(
            hdrPlusLongFrameExposureEv = MultiFrameConfig.normalizeLongFrameExposureEv(value),
        )
    }

    fun setHdrPlusShortFrameExposureEv(value: Float) {
        _state.value = _state.value.copy(
            hdrPlusShortFrameExposureEv = MultiFrameConfig.normalizeShortFrameExposureEv(value),
        )
    }


    fun setCapturingLivePhoto(enabled: Boolean) {
        _state.value = _state.value.copy(isCapturingLivePhoto = enabled)
    }

    fun setApplyUltraHDR(enabled: Boolean) {
        _state.value = _state.value.copy(applyUltraHDR = enabled)
    }

// ==================== 拍照 ====================

    private fun supportsAfTrigger(afMode: Int): Boolean {
        return afMode == CaptureRequest.CONTROL_AF_MODE_AUTO ||
            afMode == CaptureRequest.CONTROL_AF_MODE_MACRO ||
            afMode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE ||
            afMode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
    }

    private fun resolveValidFocusDistance(result: CaptureResult?, state: CameraState): Float? {
        val resultDistance = result?.get(CaptureResult.LENS_FOCUS_DISTANCE)
            ?.takeIf { it.isFinite() && it >= 0f }
        if (resultDistance != null) return resultDistance
        return if (!state.isAutoFocus) {
            state.focusDistance.takeIf { it.isFinite() && it >= 0f }
        } else {
            null
        }
    }

    private fun submitOneShotAfTrigger(
        afMode: Int,
        reason: String,
    ): Boolean {
        val session = captureSession ?: return false
        val builder = previewRequestBuilder ?: return false
        var submitted = false
        try {
            builder.set(CaptureRequest.CONTROL_AF_MODE, afMode)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            session.capture(builder.build(), previewCallback, cameraHandler)
            submitted = true
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to submit one-shot AF trigger for $reason", e)
        } finally {
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        }

        if (submitted) {
            try {
                submitRepeatingPreviewRequest(session, builder)
            } catch (e: Exception) {
                // The one-shot request is already queued and its callback can still complete the lock.
                PLog.w(TAG, "Unable to resume IDLE repeating request after $reason AF trigger", e)
            }
        }
        return submitted
    }

    private fun prepareCaptureLighting(
        device: CameraDevice,
        reader: ImageReader,
        baseExposureResult: CaptureResult?,
    ) {
        val currentState = _state.value
        val useMultiFrameTorch = shouldUseMultiFrameTorch(currentState)
        val needsPrecapture =
            isFlashSupported &&
            isAePrecaptureSupported() &&
            currentState.flashMode == CameraMetadata.FLASH_MODE_SINGLE &&
            currentState.isIsoAuto &&
            currentState.isShutterSpeedAuto &&
            resolveStillFlashAeMode() != CaptureRequest.CONTROL_AE_MODE_OFF

        if (useMultiFrameTorch) {
            pendingCaptureDevice = device
            pendingCaptureReader = reader
            pendingCaptureBaseExposureResult = baseExposureResult
            isMultiFrameTorchCaptureActive = true
            PLog.d(TAG, "Starting multi-frame torch warm-up")
            runMultiFrameTorchWarmupSequence { torchResult ->
                pendingCaptureBaseExposureResult = torchResult ?: pendingCaptureBaseExposureResult
                runCaptureSequence()
            }
        } else if (needsPrecapture) {
            pendingCaptureDevice = device
            pendingCaptureReader = reader
            pendingCaptureBaseExposureResult = baseExposureResult
            PLog.d(TAG, "启动状态机拍照流程")
            runPrecaptureSequence()
        } else {
            PLog.d(TAG, "直接拍照")
            performCapture(device, reader, baseExposureResult)
        }
    }

    /**
     * 拍照
     */
    fun capture(onAccepted: ((Long) -> Unit)? = null) {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post {
                capture(onAccepted)
            }
            return
        }
        if (_state.value.captureMode != CaptureMode.PHOTO) return
        if (_state.value.isCapturing) {
            PLog.d(TAG, "Ignoring capture request while a capture is already active")
            return
        }
        val device = cameraDevice ?: return
        val reader = imageReader ?: return
        if (captureSession == null) return
        val shot = reservePhotoCapture(_state.value, reader) ?: return
        activePhotoCapture = shot
        _state.update { it.copy(isCapturing = true, photoCaptureProgress = null) }
        try {
            onAccepted?.invoke(shot.id)
        } catch (e: Exception) {
            failPhotoCapture(shot, "capture acceptance callback failed")
            finishPhotoCaptureSequence(shot, successful = false)
            PLog.e(TAG, "Capture acceptance callback failed", e)
            return
        }

        try {
            livePhotoVideoStartTimestampUs = null
            if (_state.value.useLivePhoto) {
                snapshotLivePhoto()
            }

            val baseExposureResult = lastCaptureResult
            // 关键修复：每次拍照前重置拍摄结果
            lastCaptureResult = null

            PLog.i(
                TAG,
                "开始拍照 - 闪光模式: ${_state.value.flashMode}, ISO模式: ${if (_state.value.isIsoAuto) "自动" else "手动(${_state.value.iso})"}"
            )

            if (!_state.value.useLivePhoto) {
                // 播放快门音效
                onPlayShutterSound?.invoke()
            }

            _state.value = _state.value.copy(isCapturing = true)

            // 只有在【自动曝光 + 单次闪光】时才使用预闪流程
            // 手动曝光模式下，AE_PRECAPTURE_TRIGGER 不生效（因为 AE_MODE=OFF），直接拍照
            val currentState = _state.value
            if (
                currentState.requiresMultiFrameCaptureSequence ||
                (currentState.autoHdrEnabled &&
                    currentState.captureMode == CaptureMode.PHOTO &&
                    !currentState.useRaw)
            ) {
                // Auto HDR needs exposure-synchronised gyro windows for OppoCam's alignment prior.
                burstGyroRecorder.start(cameraHandler)
            }
            prepareCaptureLighting(device, reader, baseExposureResult)

        } catch (e: Exception) {
            PLog.e(TAG, "Failed to capture", e)
            PLog.e(TAG, "拍照失败", e)
            _state.value = _state.value.copy(isCapturing = false)
            resetPreviewAfterCapture()
        }
    }

    private fun performRefocusCapture(
        device: CameraDevice,
        reader: ImageReader,
        session: CameraCaptureSession,
        baseExposureResult: CaptureResult?,
    ) {
        val currentState = _state.value
        val maxFocusDiopters = currentState.minimumFocusDistance
        if (maxFocusDiopters <= 0f) {
            PLog.w(TAG, "Refocus unavailable: lens does not expose manual focus distance")
            performCapture(device, reader, baseExposureResult)
            return
        }
        val frameCount = currentState.refocusFrameCount.coerceIn(3, 8)
        val characteristics = getActiveOpenCameraCharacteristics()
        val aeLockAvailable = characteristics?.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true
        val awbLockAvailable = characteristics?.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true
        val requests = (0 until frameCount).map { index ->
            val progress = index.toFloat() / (frameCount - 1).coerceAtLeast(1)
            val diopters = maxFocusDiopters * (1f - progress)
            device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                setTag(index)
                addTarget(reader.surface)
                applyBaseCameraSettings(
                    builder = this,
                    isCapture = true,
                    isRawCapture = false,
                    disableZslForHdrCapture = true,
                )
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, diopters)
                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                setAePrecaptureTriggerIfSupported(
                    this,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE,
                )
                if (aeLockAvailable && currentState.isAutoExposure) {
                    set(CaptureRequest.CONTROL_AE_LOCK, true)
                }
                if (awbLockAvailable && currentState.awbMode != CameraMetadata.CONTROL_AWB_MODE_OFF) {
                    set(CaptureRequest.CONTROL_AWB_LOCK, true)
                }
                PLog.d(TAG, "Refocus plane[$index/$frameCount] distance=$diopters D")
            }.build()
        }
        try {
            val shot = activePhotoCapture ?: error("Missing refocus capture reservation")
            shot.state = shot.state.copy(
                refocusCapturing = true,
                refocusFrameCount = frameCount,
            )
            _state.update {
                it.copy(
                    isCapturing = true,
                    refocusCapturing = true,
                    refocusFrameCount = frameCount,
                )
            }
            if (!currentState.useLivePhoto) onPlayShutterSound?.invoke()
            submitPhotoCapture(shot, session, requests, baseExposureResult)
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to capture refocus planes", e)
            _state.update { it.copy(isCapturing = false, refocusCapturing = false) }
            resetPreviewAfterCapture()
        }
    }

    private fun performHdrBracketCapture(
        device: CameraDevice,
        reader: ImageReader,
        session: CameraCaptureSession,
        playShutterSound: Boolean,
        baseExposureResult: CaptureResult?,
        currentState: CameraState,
    ) {
        val evOffsets = buildHdrBracketEvOffsets(currentState)
        val hdrFrameCount = evOffsets.size
        _state.value = _state.value.copy(
            isCapturing = true,
            hdrBracketCapturing = true,
            hdrBracketFrameCount = hdrFrameCount
        )

        try {
            if (playShutterSound && !_state.value.useLivePhoto) {
                onPlayShutterSound?.invoke()
            }
            val manualBaseExposure = resolveHdrBracketManualBaseExposure(currentState, baseExposureResult)
            logHdrBracketBaseExposure(currentState, baseExposureResult, manualBaseExposure)
            val requests = evOffsets.mapIndexed { index, evOffset ->
                device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    setTag(index)
                    addTarget(reader.surface)
                    applyBaseCameraSettings(
                        builder = this,
                        isCapture = true,
                        isRawCapture = false,
                        disableZslForHdrCapture = true
                    )
                    applyHdrBracketExposure(this, currentState, evOffset, manualBaseExposure)
                    setAePrecaptureTriggerIfSupported(
                        this,
                        CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE,
                    )
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                    if (currentState.awbMode != CameraMetadata.CONTROL_AWB_MODE_OFF) {
                        set(CaptureRequest.CONTROL_AWB_LOCK, true)
                    }
                    copyStillFocusSettingsFromPreview(this)
                    applyMultiFrameCaptureConsistency(
                        builder = this,
                        state = currentState,
                        baseResult = baseExposureResult,
                        lockExposure = false,
                        isRawCapture = false,
                    )
                    PLog.d(TAG, "HDR bracket request[$index]: ev=$evOffset, manual=${manualBaseExposure != null}")
                }.build()
            }

            val shot = activePhotoCapture ?: error("Missing HDR capture reservation")
            shot.state = shot.state.copy(hdrBracketCapturing = true, hdrBracketFrameCount = hdrFrameCount)
            submitPhotoCapture(shot, session, requests, baseExposureResult)
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to capture HDR bracket", e)
            _state.value = _state.value.copy(
                isCapturing = false,
                hdrBracketCapturing = false,
                hdrBracketFrameCount = 0
            )
            onHdrBracketCaptureFailed?.invoke()
            resetPreviewAfterCapture()
        }
    }

    /**
     * OppoCam bracket order: reference, short highlight, optional long shadow.
     * Auto HDR only brackets scenes whose live histogram shows actual highlight/shadow risk.
     */
    private fun buildHdrBracketEvOffsets(state: CameraState): List<Float> {
        if (state.flashMode != 0) return listOf(0f)
        if (!state.autoHdrEnabled || state.useJpgMaxHdrComposition) {
            return listOf(0f, -1.7f, 1.4f)
        }
        return resolveAutoHdrBracketEvOffsets(state)
    }

    /**
     * 执行实际的拍照操作
     */
    private fun resolveHdrBracketManualBaseExposure(
        state: CameraState,
        baseExposureResult: CaptureResult?
    ): Pair<Int, Long>? {
        if (!isManualSensorSupported || !availableAeModes.contains(CaptureRequest.CONTROL_AE_MODE_OFF)) {
            return null
        }
        val baseIso = if (state.isAutoExposure) {
            baseExposureResult?.get(CaptureResult.SENSOR_SENSITIVITY)
        } else {
            state.iso
        } ?: return null
        val baseShutter = if (state.isAutoExposure) {
            baseExposureResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        } else {
            state.shutterSpeed
        } ?: return null
        if (baseIso <= 0 || baseShutter <= 0L) return null
        return Pair(baseIso, baseShutter)
    }

    private fun logHdrBracketBaseExposure(
        state: CameraState,
        baseExposureResult: CaptureResult?,
        manualBaseExposure: Pair<Int, Long>?
    ) {
        val resultIso = baseExposureResult?.get(CaptureResult.SENSOR_SENSITIVITY)
        val resultShutter = baseExposureResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val resultCompensation = baseExposureResult?.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)
        PLog.d(
            TAG,
            "HDR bracket base exposure: auto=${state.isAutoExposure}, " +
                    "userComp=${state.exposureCompensation}, userBias=${state.exposureBias}, " +
                    "resultIso=$resultIso, resultShutter=$resultShutter, resultComp=$resultCompensation, " +
                    "manualBase=${manualBaseExposure?.first}/${manualBaseExposure?.second}"
        )
    }

    private fun logHdrBracketActualExposure(request: CaptureRequest, result: CaptureResult) {
        val requestIndex = request.tag as? Int
        val actualIso = result.get(CaptureResult.SENSOR_SENSITIVITY)
        val actualShutter = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val actualCompensation = result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)
        val aeMode = result.get(CaptureResult.CONTROL_AE_MODE)
        val afMode = result.get(CaptureResult.CONTROL_AF_MODE)
        val afState = result.get(CaptureResult.CONTROL_AF_STATE)
        val focusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
        val lensState = result.get(CaptureResult.LENS_STATE)
        PLog.d(
            TAG,
            "HDR bracket result[$requestIndex]: ISO=$actualIso, shutter=$actualShutter, " +
                    "aeComp=$actualCompensation, aeMode=$aeMode, afMode=$afMode, " +
                    "afState=$afState, focus=$focusDistance, lensState=$lensState"
        )
    }

    private fun applyHdrBracketExposure(
        builder: CaptureRequest.Builder,
        state: CameraState,
        evOffset: Float,
        manualBaseExposure: Pair<Int, Long>?
    ) {
        if (manualBaseExposure != null) {
            val (iso, shutter) = calculateHdrBracketManualExposure(
                baseIso = manualBaseExposure.first,
                baseShutter = manualBaseExposure.second,
                evOffset = evOffset,
                state = state
            )
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
            PLog.d(TAG, "HDR bracket manual exposure: ev=$evOffset, ISO=$iso, shutter=$shutter")
            return
        }

        val compensation = calculateHdrBracketExposureCompensation(state, evOffset)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, compensation)
        PLog.d(TAG, "HDR bracket AE compensation: ev=$evOffset, compensation=$compensation")
    }

    private fun calculateHdrBracketManualExposure(
        baseIso: Int,
        baseShutter: Long,
        evOffset: Float,
        state: CameraState
    ): Pair<Int, Long> {
        val isoRange = state.getIsoRange()
        val shutterRange = state.getShutterSpeedRange()
        return HdrBracketConfig.planManualExposure(
            baseIso = baseIso,
            baseShutterNs = baseShutter,
            evOffset = evOffset,
            isoLower = isoRange.lower,
            isoUpper = isoRange.upper,
            shutterLowerNs = shutterRange.lower,
            shutterUpperNs = shutterRange.upper,
        )
    }

    private fun calculateHdrBracketExposureCompensation(state: CameraState, evOffset: Float): Int {
        val evStep = state.getExposureCompensationStep().takeIf { it > 0f } ?: return state.exposureCompensation
        val range = state.getExposureCompensationRange()
        val steps = roundHdrBracketCompensationSteps(evOffset, evStep)
        return (state.exposureCompensation + steps).coerceIn(range.lower, range.upper)
    }

    private fun roundHdrBracketCompensationSteps(evOffset: Float, evStep: Float): Int {
        if (evOffset == 0f) return 0
        val magnitude = (abs(evOffset / evStep) + 0.0001f).roundToInt()
        return if (evOffset < 0f) -magnitude else magnitude
    }

    private fun copyStillFocusSettingsFromPreview(builder: CaptureRequest.Builder) {
        // Single and multi-frame stills inherit preview focus without starting a new AF sweep.
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        previewRequestBuilder?.let { preview ->
            preview.get(CaptureRequest.CONTROL_AF_MODE)?.let {
                builder.set(CaptureRequest.CONTROL_AF_MODE, it)
            }
            preview.get(CaptureRequest.LENS_FOCUS_DISTANCE)?.let {
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, it)
            }
            preview.get(CaptureRequest.CONTROL_AF_REGIONS)?.let {
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, it)
            }
            preview.get(CaptureRequest.CONTROL_AE_REGIONS)?.let {
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, it)
            }
        }
        applyFrozenCaptureFocus(builder)
    }

    private fun applyMultiFrameCaptureConsistency(
        builder: CaptureRequest.Builder,
        state: CameraState,
        baseResult: CaptureResult?,
        lockExposure: Boolean,
        isRawCapture: Boolean,
    ) {
        if (!state.requiresMultiFrameCaptureSequence) return

        if (lockExposure) {
            val useMultiFrameTorch = isMultiFrameTorchCaptureActive
            if (!useMultiFrameTorch) {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
            val requestUsesManualExposure =
                builder.get(CaptureRequest.CONTROL_AE_MODE) == CaptureRequest.CONTROL_AE_MODE_OFF
            if (isRawCapture && !requestUsesManualExposure && !useMultiFrameTorch) {
                val lockedIso = baseResult?.get(CaptureResult.SENSOR_SENSITIVITY)
                val lockedExposure = baseResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                val canUseManualExposure = isManualSensorSupported &&
                        availableAeModes.contains(CaptureRequest.CONTROL_AE_MODE_OFF) &&
                        lockedIso != null && lockedIso > 0 &&
                        lockedExposure != null && lockedExposure > 0L
                if (canUseManualExposure) {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    builder.set(CaptureRequest.SENSOR_SENSITIVITY, lockedIso)
                    builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, lockedExposure)
                }
            }

            if (builder.get(CaptureRequest.CONTROL_AE_MODE) != CaptureRequest.CONTROL_AE_MODE_OFF) {
                val aeLockAvailable = getActiveOpenCameraCharacteristics()
                    ?.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true
                if (aeLockAvailable) {
                    builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
                }
            }
        }

        val awbLockAvailable = getActiveOpenCameraCharacteristics()
            ?.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true
        if (state.awbMode != CameraMetadata.CONTROL_AWB_MODE_OFF && awbLockAvailable) {
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
        }

        PLog.d(
            TAG,
            "Multi-frame capture locked exposure=$lockExposure " +
                    "ae=${builder.get(CaptureRequest.CONTROL_AE_MODE)} " +
                    "flash=${builder.get(CaptureRequest.FLASH_MODE)} " +
                    "iso=${builder.get(CaptureRequest.SENSOR_SENSITIVITY)} " +
                    "shutter=${builder.get(CaptureRequest.SENSOR_EXPOSURE_TIME)} " +
                    "af=${builder.get(CaptureRequest.CONTROL_AF_MODE)} " +
                    "focus=${builder.get(CaptureRequest.LENS_FOCUS_DISTANCE)}"
        )
    }

    private fun buildMultiFrameShortCaptureRequest(
        device: CameraDevice,
        reader: ImageReader,
        state: CameraState,
        baseResult: CaptureResult?,
        baseRequest: CaptureRequest,
        isRawCapture: Boolean,
    ): CaptureRequest {
        return device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(reader.surface)
            applyBaseCameraSettings(this, isCapture = true, isRawCapture = isRawCapture)
            setAePrecaptureTriggerIfSupported(
                this,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE,
            )
            set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            copyStillFocusSettingsFromPreview(this)
            applyMultiFrameCaptureConsistency(
                builder = this,
                state = state,
                baseResult = baseResult,
                lockExposure = true,
                isRawCapture = isRawCapture,
            )
            applyMultiFrameShortExposure(
                builder = this,
                state = state,
                baseResult = baseResult,
                baseRequest = baseRequest,
            )
            setTag(MultiFrameCaptureRole.SHORT)
        }.build()
    }

    private fun applyMultiFrameShortExposure(
        builder: CaptureRequest.Builder,
        state: CameraState,
        baseResult: CaptureResult?,
        baseRequest: CaptureRequest,
    ) {
        val baseIso = baseRequest.get(CaptureRequest.SENSOR_SENSITIVITY)
            ?: baseResult?.get(CaptureResult.SENSOR_SENSITIVITY)
        val baseShutter = baseRequest.get(CaptureRequest.SENSOR_EXPOSURE_TIME)
            ?: baseResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val canUseManualExposure = isManualSensorSupported &&
            availableAeModes.contains(CaptureRequest.CONTROL_AE_MODE_OFF) &&
            baseIso != null && baseIso > 0 &&
            baseShutter != null && baseShutter > 0L
        if (canUseManualExposure) {
            val manualBaseIso = checkNotNull(baseIso)
            val manualBaseShutter = checkNotNull(baseShutter)
            val (shortIso, shortShutter) = calculateMultiFrameShortExposure(
                baseIso = manualBaseIso,
                baseShutter = manualBaseShutter,
                state = state,
            )
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, shortIso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shortShutter)
            val achievedRatio = shortIso.toDouble() * shortShutter.toDouble() /
                (manualBaseIso.toDouble() * manualBaseShutter.toDouble())
            PLog.i(
                TAG,
                "Multi-frame short request: base=ISO$manualBaseIso/${manualBaseShutter}ns " +
                    "short=ISO$shortIso/${shortShutter}ns " +
                    "targetEv=${state.hdrPlusShortFrameExposureEv} exposureRatio=$achievedRatio",
            )
            return
        }

        val shortEv = state.hdrPlusShortFrameExposureEv
        val compensation = calculateHdrBracketExposureCompensation(state, shortEv)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, compensation)
        PLog.w(
            TAG,
            "Manual sensor exposure unavailable for multi-frame short request; " +
                "using AE compensation=$compensation for ${shortEv}EV",
        )
    }

    private fun calculateMultiFrameShortExposure(
        baseIso: Int,
        baseShutter: Long,
        state: CameraState,
    ): Pair<Int, Long> {
        val isoRange = state.getIsoRange()
        val shutterRange = state.getShutterSpeedRange()
        val exposureMultiplier = 2.0.pow(state.hdrPlusShortFrameExposureEv.toDouble())
        val targetProduct = baseIso.toDouble() * baseShutter.toDouble() * exposureMultiplier
        val initialShutter = (baseShutter.toDouble() * exposureMultiplier)
            .roundToLong()
            .coerceIn(shutterRange.lower, shutterRange.upper)
        val shortIso = (targetProduct / initialShutter.toDouble())
            .roundToInt()
            .coerceIn(isoRange.lower, isoRange.upper)
        val shortShutter = (targetProduct / shortIso.toDouble())
            .roundToLong()
            .coerceIn(shutterRange.lower, shutterRange.upper)
        return shortIso to shortShutter
    }

    private fun buildMultiFrameLongCaptureRequest(
        device: CameraDevice,
        reader: ImageReader,
        state: CameraState,
        baseResult: CaptureResult?,
        baseRequest: CaptureRequest,
        isRawCapture: Boolean,
    ): CaptureRequest? {
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(reader.surface)
            applyBaseCameraSettings(this, isCapture = true, isRawCapture = isRawCapture)
            setAePrecaptureTriggerIfSupported(
                this,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE,
            )
            set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            copyStillFocusSettingsFromPreview(this)
            applyMultiFrameCaptureConsistency(
                builder = this,
                state = state,
                baseResult = baseResult,
                lockExposure = true,
                isRawCapture = isRawCapture,
            )
        }
        if (!applyMultiFrameLongExposure(builder, state, baseResult, baseRequest)) return null
        builder.setTag(MultiFrameCaptureRole.LONG)
        return builder.build()
    }

    private fun applyMultiFrameLongExposure(
        builder: CaptureRequest.Builder,
        state: CameraState,
        baseResult: CaptureResult?,
        baseRequest: CaptureRequest,
    ): Boolean {
        val baseIso = baseRequest.get(CaptureRequest.SENSOR_SENSITIVITY)
            ?: baseResult?.get(CaptureResult.SENSOR_SENSITIVITY)
        val baseShutter = baseRequest.get(CaptureRequest.SENSOR_EXPOSURE_TIME)
            ?: baseResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val canUseManualExposure = isManualSensorSupported &&
            availableAeModes.contains(CaptureRequest.CONTROL_AE_MODE_OFF) &&
            baseIso != null && baseIso > 0 &&
            baseShutter != null && baseShutter > 0L
        if (canUseManualExposure) {
            val manualBaseIso = checkNotNull(baseIso)
            val manualBaseShutter = checkNotNull(baseShutter)
            val isoRange = state.getIsoRange()
            val shutterRange = state.getShutterSpeedRange()
            val sensorCharacteristics = activeOutputPhysicalCameraId?.let { cameraId ->
                getCameraCharacteristicsOrNull(cameraId, "multi-frame long exposure")
            } ?: getActiveOpenCameraCharacteristics()
            val reportedMaxAnalogIso = sensorCharacteristics
                ?.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY)
                ?.takeIf { it > 0 }
            val longFrameIsoUpper = minOf(
                isoRange.upper,
                reportedMaxAnalogIso
                    ?: MultiFrameConfig.LONG_FRAME_FALLBACK_MAX_ANALOG_SENSITIVITY,
            )
            if (longFrameIsoUpper < isoRange.lower) {
                PLog.w(
                    TAG,
                    "Multi-frame long ISO limit ISO$longFrameIsoUpper is below the sensor " +
                        "minimum ISO${isoRange.lower}; long slots will remain normal",
                )
                return false
            }
            val plan = runCatching {
                MultiFrameExposurePlanner.planLongExposure(
                    baseIso = manualBaseIso,
                    baseExposureTimeNs = manualBaseShutter,
                    isoLower = isoRange.lower,
                    isoUpper = longFrameIsoUpper,
                    exposureTimeLowerNs = shutterRange.lower,
                    exposureTimeUpperNs = shutterRange.upper,
                    targetExposureEv = state.hdrPlusLongFrameExposureEv,
                )
            }.onFailure { error ->
                PLog.e(TAG, "Unable to plan bounded multi-frame long exposure", error)
            }.getOrNull()
            if (plan != null) {
                if (plan.upperLimitsProduceLowerExposureThanBase) {
                    PLog.w(
                        TAG,
                        "Multi-frame long exposure cannot reach the normal frame after both " +
                            "limits: base=ISO$manualBaseIso/${manualBaseShutter}ns " +
                            "bounded=ISO${plan.sensitivityIso}/${plan.exposureTimeNs}ns " +
                            "maxAnalogIso=$longFrameIsoUpper; long slots will remain normal",
                    )
                    return false
                }
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, plan.sensitivityIso)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, plan.exposureTimeNs)
                PLog.i(
                    TAG,
                    "Multi-frame long request: base=ISO$manualBaseIso/${manualBaseShutter}ns " +
                        "long=ISO${plan.sensitivityIso}/${plan.exposureTimeNs}ns " +
                        "targetEv=${state.hdrPlusLongFrameExposureEv} " +
                        "plannedEv=${plan.plannedDeltaEv} isoUpperLimited=${plan.isoUpperLimited} " +
                        "shutterUpperLimited=${plan.shutterUpperLimited} " +
                        "maxAnalogIso=$longFrameIsoUpper " +
                        "shutterUpperLimitNs=${plan.exposureTimeUpperLimitNs}",
                )
                return true
            }
        }
        PLog.w(
            TAG,
            "Manual sensor exposure unavailable for bounded multi-frame long request; " +
                "long slots will remain normal",
        )
        return false
    }

    private fun shouldUseJpgMaxHdrCapture(state: CameraState, isRawCapture: Boolean): Boolean {
        val sceneNeedsHdr = state.useJpgMaxHdrComposition ||
            (state.autoHdrEnabled && resolveAutoHdrBracketEvOffsets(state).size > 1)
        return state.captureMode == CaptureMode.PHOTO &&
                state.isJpgMaxEnabled &&
                sceneNeedsHdr &&
                state.flashMode == 0 &&
                !isMultiFrameTorchCaptureActive &&
                !isRawCapture &&
                !state.burstCapturing &&
                !state.hdrBracketCapturing &&
                !state.refocusCaptureEnabled &&
                !state.useMultipleExposure &&
                !state.useLivePhoto
    }

    /**
     * OppoCam Auto-HDR scene planner.
     *
     * Reconstructs the same P1/P5/P18/P50/P95/P99 decision inputs from Photon's live
     * 256-bin histogram, then selects single, highlight-only, or full highlight+shadow capture.
     * Flat scenes stay single-frame; slow shutter scenes never add a long shadow exposure.
     */
    private fun resolveAutoHdrBracketEvOffsets(state: CameraState): List<Float> {
        if (state.flashMode != 0) return emptyList()
        val histogram = state.histogram
        if (histogram == null || histogram.size < 256) {
            PLog.w(TAG, "Oppo Auto HDR: single frame, no scene histogram yet (size=${histogram?.size})")
            return emptyList()
        }

        val total = histogram.sumOf { it.coerceAtLeast(0).toLong() }
        if (total <= 0L) return emptyList()

        fun percentile(normalizedTarget: Double): Float {
            val threshold = (total.toDouble() * normalizedTarget).toLong().coerceAtLeast(1L)
            var accumulated = 0L
            for (index in histogram.indices) {
                accumulated += histogram[index].coerceAtLeast(0).toLong()
                if (accumulated >= threshold) {
                    return index.toFloat() / histogram.lastIndex.coerceAtLeast(1)
                }
            }
            return 1f
        }

        val p1 = percentile(0.01)
        val p5 = percentile(0.05)
        val p18 = percentile(0.18)
        val p50 = percentile(0.50)
        val p95 = percentile(0.95)
        val p99 = percentile(0.99)
        val dynamicRange = (p99 - p1).coerceIn(0f, 1f)

        val motionRms = burstGyroRecorder.recentRmsMotion()
        if (motionRms > 0.38f) {
            PLog.d(TAG, "Oppo Auto HDR: single frame due to high motion (RMS=$motionRms rad/s)")
            return emptyList()
        }

        // Match OppoCam FrameLuminanceAnalyzer scene labels using the same percentiles.
        val isHighContrast = (p95 > 0.82f && p18 < 0.28f) ||
            (p99 > 0.90f && p5 < 0.15f) ||
            dynamicRange > 0.72f
        val isOutdoorSky = (p95 > 0.85f && p50 < 0.36f) ||
            (p99 > 0.92f && p18 < 0.22f)

        val hasBlownHighlights = p99 >= 0.91f ||
            (p95 >= 0.86f && p5 <= 0.22f) ||
            isOutdoorSky
        val hasDeepShadows = p5 <= 0.09f && p95 >= 0.65f
        if (!hasBlownHighlights) {
            PLog.d(
                TAG,
                "Oppo Auto HDR: single frame (P1=$p1 P5=$p5 P95=$p95 P99=$p99)",
            )
            return emptyList()
        }

        val actualExposureNs = lastCaptureResult
            ?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            ?.takeIf { it > 0L }
            ?: state.shutterSpeed
        val isVerySlowShutter = actualExposureNs > 66_666_666L

        val useFullBracket = hasDeepShadows && !isVerySlowShutter &&
            (dynamicRange >= 0.70f || isOutdoorSky || isHighContrast)
        val plan = if (useFullBracket) {
            listOf(0f, -1.7f, 1.4f)
        } else {
            listOf(0f, -1.7f)
        }
        PLog.d(
            TAG,
            "Oppo Auto HDR: ${if (useFullBracket) "3-frame" else "2-frame"} " +
                "P1=$p1 P5=$p5 P18=$p18 P50=$p50 P95=$p95 P99=$p99 " +
                "DR=$dynamicRange slowShutter=$isVerySlowShutter " +
                "highContrast=$isHighContrast outdoorSky=$isOutdoorSky",
        )
        return plan
    }

    private fun shouldUseMultiFrameTorch(state: CameraState): Boolean {
        return state.requiresMultiFrameCaptureSequence &&
                !state.refocusCaptureEnabled &&
                !(state.autoHdrEnabled && !state.useRaw) &&
                isFlashSupported &&
                state.flashMode == CameraMetadata.FLASH_MODE_SINGLE
    }

    private fun shouldUseContinuousBurstTorch(state: CameraState): Boolean {
        return state.burstCapturing &&
                isFlashSupported &&
                state.flashMode == CameraMetadata.FLASH_MODE_SINGLE
    }

    private fun resolveHdrBracketZeroEvFrameCount(state: CameraState): Int {
        return if (state.isMultiFrameEnabled) {
            state.activeMultiFrameCount
        } else {
            0
        }
    }

    private fun performCapture(
        device: CameraDevice,
        reader: ImageReader,
        baseExposureResult: CaptureResult? = lastCaptureResult
    ) {
        try {
            val isRawCapture = isRawCaptureReader(reader)
            val currentState = stillFlashCaptureState ?: _state.value
            val useMultiFrameTorch = isMultiFrameTorchCaptureActive
            if (
                currentState.refocusCaptureEnabled &&
                currentState.captureMode == CaptureMode.PHOTO &&
                !isRawCapture &&
                !currentState.useLivePhoto &&
                !currentState.useMultipleExposure &&
                currentState.minimumFocusDistance > 0f
            ) {
                val session = captureSession ?: run {
                    PLog.e(TAG, "Failed to capture refocus planes: capture session unavailable")
                    _state.update { it.copy(isCapturing = false, refocusCapturing = false) }
                    resetPreviewAfterCapture()
                    return
                }
                performRefocusCapture(device, reader, session, baseExposureResult)
                return
            }
            if (shouldUseJpgMaxHdrCapture(currentState, isRawCapture)) {
                val session = captureSession ?: run {
                    PLog.e(TAG, "Failed to capture JPGmax bracket: capture session unavailable")
                    _state.value = _state.value.copy(isCapturing = false)
                    onHdrBracketCaptureFailed?.invoke()
                    resetPreviewAfterCapture()
                    return
                }
                PLog.d(TAG, "JPGmax YUV bracket capture")
                performHdrBracketCapture(
                    device = device,
                    reader = reader,
                    session = session,
                    playShutterSound = false,
                    baseExposureResult = baseExposureResult,
                    currentState = currentState,
                )
                return
            }
            val session = captureSession
                ?: throw IllegalStateException("Capture session unavailable")

            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)

                // Do not mirror still capture frames to the GL preview SurfaceTexture.
                // Some camera HALs occasionally deliver a still-capture buffer that
                // SurfaceTexture.updateTexImage cannot bind as an external OES image.
                // if (!isRawCapture && shouldMirrorStillCaptureToPreview()) {
                //     previewSurface?.let { addTarget(it) }
                // }

                // 应用所有相机参数（曝光、白平衡、闪光灯、变焦）
                // isCapture = true 确保使用完整的曝光时间（不限制长曝光）
                applyBaseCameraSettings(
                    this,
                    isCapture = true,
                    isRawCapture = isRawCapture,
                    currentState = currentState,
                )

                // A previous multi-frame/torch sequence may have used AE_LOCK. Standard
                // single-frame flash capture must let the flash AE routine choose exposure.
                if (!currentState.requiresMultiFrameCaptureSequence) {
                    set(CaptureRequest.CONTROL_AE_LOCK, false)
                }

                if (useMultiFrameTorch) {
                    if (currentState.isAutoExposure) {
                        set(
                            CaptureRequest.CONTROL_AE_MODE,
                            resolveSupportedAeMode(CaptureRequest.CONTROL_AE_MODE_ON)
                        )
                    }
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                }

                if (isRawCapture && shouldApplyRawMinShutterLimit(currentState)) {
                    if (isManualSensorSupported) {
                        val (adjustedIso, adjustedShutter) = calculateRawMinShutterAdjustedExposure(
                            currentState,
                            currentState.iso,
                            currentState.shutterSpeed
                        )
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                        set(CaptureRequest.SENSOR_SENSITIVITY, adjustedIso)
                        set(CaptureRequest.SENSOR_EXPOSURE_TIME, adjustedShutter)
                        PLog.d(
                            TAG,
                            "Capture RAW min shutter override: minShutter=${currentState.rawMinShutterSpeedNs}, ISO=$adjustedIso, shutter=$adjustedShutter"
                        )
                    } else {
                        PLog.w(TAG, "RAW min shutter requires MANUAL_SENSOR support")
                    }
                }

                // 强制将此请求的触发器设为 IDLE，防止携带预览中的触发状态
                setAePrecaptureTriggerIfSupported(
                    this,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE,
                )

                copyStillFocusSettingsFromPreview(this)

                applyMultiFrameCaptureConsistency(
                    builder = this,
                    state = currentState,
                    baseResult = baseExposureResult,
                    lockExposure = true,
                    isRawCapture = isRawCapture,
                )

                PLog.d(
                    TAG,
                    "Capture request built and ready to send: " +
                        "aeMode=${get(CaptureRequest.CONTROL_AE_MODE)}, " +
                        "aeTrigger=${get(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER)}, " +
                        "aeLock=${get(CaptureRequest.CONTROL_AE_LOCK)}, " +
                        "flashMode=${get(CaptureRequest.FLASH_MODE)}, " +
                        "iso=${get(CaptureRequest.SENSOR_SENSITIVITY)}, " +
                        "shutter=${get(CaptureRequest.SENSOR_EXPOSURE_TIME)}"
                )
            }

            if (currentState.requiresMultiFrameCaptureSequence) {
                // Burst Mode
                val frameCount = currentState.activeMultiFrameCount
                captureBuilder.setTag(MultiFrameCaptureRole.BASE)
                val baseRequest = captureBuilder.build()
                val useHdrPlusBracketExposurePlan =
                    currentState.isHdrPlusBracketExposureEnabled && !useMultiFrameTorch
                val requests = if (!useHdrPlusBracketExposurePlan) {
                    List(frameCount) { baseRequest }
                } else {
                    val normalFrameCount = MultiFrameConfig.hdrPlusNormalFrameCount(frameCount)
                    val shortFrameCount = MultiFrameConfig.hdrPlusShortFrameCount(frameCount)
                    val longFrameCount = MultiFrameConfig.hdrPlusLongFrameCount(frameCount)
                    val shortRequest = if (shortFrameCount > 0) {
                        buildMultiFrameShortCaptureRequest(
                            device = device,
                            reader = reader,
                            state = currentState,
                            baseResult = baseExposureResult,
                            baseRequest = baseRequest,
                            isRawCapture = isRawCapture,
                        )
                    } else {
                        null
                    }
                    val longRequest = if (longFrameCount > 0) {
                        buildMultiFrameLongCaptureRequest(
                            device = device,
                            reader = reader,
                            state = currentState,
                            baseResult = baseExposureResult,
                            baseRequest = baseRequest,
                            isRawCapture = isRawCapture,
                        )
                    } else {
                        null
                    }
                    val radianceRequests = buildList(
                        MultiFrameConfig.hdrPlusCaptureFrameCount(frameCount)
                    ) {
                        repeat(normalFrameCount) {
                            add(baseRequest)
                        }
                        shortRequest?.let(::add)
                        repeat(longFrameCount) {
                            add(longRequest ?: baseRequest)
                        }
                    }
                    val scheduledLongFrameCount = if (longRequest != null) longFrameCount else 0
                    PLog.i(
                        TAG,
                        "HDR+ burst plan: normalFrames=$normalFrameCount shortFrames=" +
                            "$shortFrameCount " +
                            "longFrames=$scheduledLongFrameCount " +
                            "fallbackNormalFrames=${longFrameCount - scheduledLongFrameCount} " +
                            "shortTargetEv=${currentState.hdrPlusShortFrameExposureEv} " +
                            "longTargetEv=${currentState.hdrPlusLongFrameExposureEv} " +
                            "longNominalMaxShutterNs=" +
                            "${MultiFrameConfig.LONG_FRAME_MAX_EXPOSURE_TIME_NS} " +
                            "total=${radianceRequests.size}",
                    )
                    radianceRequests
                }
                if (useMultiFrameTorch) {
                    PLog.i(
                        TAG,
                        "Multi-frame torch denoise burst plan: frames=${requests.size} " +
                            "hdr=false aeMode=${baseRequest.get(CaptureRequest.CONTROL_AE_MODE)} " +
                            "aeLock=${baseRequest.get(CaptureRequest.CONTROL_AE_LOCK)} " +
                            "flashMode=${baseRequest.get(CaptureRequest.FLASH_MODE)}",
                    )
                } else if (currentState.isJpgMaxEnabled) {
                    PLog.i(
                        TAG,
                        "JPGmax denoise burst plan: zeroEvFrames=${requests.size} hdr=false",
                    )
                } else if (!useHdrPlusBracketExposurePlan) {
                    PLog.i(
                        TAG,
                        "RAWmax same-exposure burst plan: baseFrames=${requests.size} " +
                            "hdr=false shortFrames=0 longFrames=0",
                    )
                }

                val shot = activePhotoCapture ?: error("Missing multi-frame capture reservation")
                submitPhotoCapture(shot, session, requests, baseExposureResult)
            } else {
                val shot = activePhotoCapture ?: error("Missing single capture reservation")
                submitPhotoCapture(shot, session, listOf(captureBuilder.build()), baseExposureResult)
            }
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to perform capture", e)
            _state.value = _state.value.copy(isCapturing = false)
            resetPreviewAfterCapture()
        }
    }

    private fun resetPreviewAfterCapture(keepLivePhotoCapture: Boolean = false) {
        pendingContinuousBurstCapture?.let {
            failPhotoCapture(it, "continuous burst preparation cancelled")
        }
        pendingContinuousBurstCapture = null
        activePhotoCapture?.takeIf { !it.submitted }?.let { shot ->
            failPhotoCapture(shot, "capture preparation cancelled")
            activePhotoCapture = null
            _state.update { it.copy(isCapturing = false, hdrBracketCapturing = false, hdrBracketFrameCount = 0, refocusCapturing = false) }
        }
        if (!keepLivePhotoCapture) {
            livePhotoVideoStartTimestampUs?.let(livePhotoRecorder::cancelCapture)
            livePhotoVideoStartTimestampUs = null
        }
        // 重置拍照状态机
        clearPrecaptureTracking()
        clearMultiFrameTorchWarmupTracking()
        isMultiFrameTorchCaptureActive = false
        isContinuousBurstTorchActive = false
        pendingCaptureDevice = null
        pendingCaptureReader = null
        pendingCaptureBaseExposureResult = null

        // 关键修复：检查相机和会话是否仍然有效
        val device = cameraDevice
        val session = captureSession
        val builder = previewRequestBuilder
        val afTriggerModeToCancel = stillFlashAfMode?.takeIf {
            supportsAfTrigger(it) && isCaptureRequestKeyAvailable(CaptureRequest.CONTROL_AF_TRIGGER.name)
        }

        if (device == null || session == null || builder == null) {
            PLog.v(TAG, "resetPreviewAfterCapture: camera not ready, skipping")
            clearCaptureFocusState("preview unavailable after capture")
            return
        }

        try {
            if (afTriggerModeToCancel != null) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, afTriggerModeToCancel)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            } else {
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            }
            setAePrecaptureTriggerIfSupported(
                builder,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL,
            )
            // The preview builder also targets the stabilization ImageReader. Keep this one-shot
            // AF/AE reset request on the normal preview callback so its YUV image has matching
            // CaptureResult metadata; otherwise the stabilizer must emit an identity-pose frame.
            session.capture(builder.build(), previewCallback, cameraHandler)

            clearCaptureFocusState("capture sequence finished")
            applyBaseCameraSettings(builder, isCapture = false)
            builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            setAePrecaptureTriggerIfSupported(
                builder,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE,
            )
            session.setRepeatingRequest(builder.build(), previewCallback, cameraHandler)
            PLog.i(TAG, "Capture preview restored: cancelledAfMode=$afTriggerModeToCancel " +
                "previewAfMode=${builder.get(CaptureRequest.CONTROL_AF_MODE)}")
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to reset preview", e)
            clearCaptureFocusState("preview reset failure")
        }
    }

    private fun clearCaptureFocusState(reason: String) {
        val hadFocusState = isCaptureFocusFrozen
        stillFlashAfMode = null
        stillFlashFocusDistance = null
        stillFlashCaptureState = null
        isCaptureFocusFrozen = false
        if (hadFocusState) {
            PLog.i(TAG, "Capture focus state released: reason=$reason")
        }
    }


    /**
     * 构建 CaptureInfo
     *
     * 从 CaptureResult 和 CameraCharacteristics 提取拍摄信息
     */
    fun rebuildCaptureInfo(
        result: CaptureResult?,
        imageWidth: Int,
        imageHeight: Int,
        latitude: Double? = null,
        longitude: Double? = null,
        effectiveCharacteristics: CameraCharacteristics? = null,
        captureState: CameraState = _state.value,
        captureTime: Long = System.currentTimeMillis(),
        exposureCompensationStep: Float = captureState.getExposureCompensationStep(),
    ): CaptureInfo {
        val openCameraId = getCurrentOpenCameraId()
        val zoomRatio = captureState.zoomRatio

        // 从 CameraCharacteristics 获取镜头固定信息
        var aperture: Float? = null
        var characteristicsForMetadata: CameraCharacteristics? = null

        try {
            val characteristics = effectiveCharacteristics ?: getCameraCharacteristicsCached(openCameraId)
            characteristicsForMetadata = characteristics

            // 光圈值（取第一个可用光圈）
            val apertures = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
            aperture = apertures?.firstOrNull()

        } catch (e: Exception) {
            PLog.e(TAG, "Failed to get camera characteristics for EXIF", e)
        }

        // 从 CaptureResult 获取曝光信息
        val exposureTime = result?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: captureState.shutterSpeed
        val iso = result?.get(CaptureResult.SENSOR_SENSITIVITY) ?: captureState.iso
        val awbModeForExif = result?.get(CaptureResult.CONTROL_AWB_MODE) ?: captureState.awbMode
        val whiteBalance = if (awbModeForExif == CameraMetadata.CONTROL_AWB_MODE_AUTO) 0 else 1
        val flashState = result?.get(CaptureResult.FLASH_STATE) ?: captureState.flashMode

        // 如果有实时的光圈/焦距，使用实时值
        result?.get(CaptureResult.LENS_APERTURE)?.let { aperture = it }
        val resolvedFocalLength = resolveCaptureFocalLength(
            characteristics = characteristicsForMetadata,
            captureResultFocalLength = result?.get(CaptureResult.LENS_FOCAL_LENGTH),
            zoomRatio = zoomRatio,
            captureState = captureState,
        )

        return CaptureInfo(
            exposureTime = exposureTime,
            iso = iso,
            aperture = aperture,
            focalLength = resolvedFocalLength.focalLength,
            focalLength35mm = resolvedFocalLength.focalLength35mm,
            exposureBias = captureState.exposureBias,
            exposureCompensation = captureState.run {
                exposureCompensation * exposureCompensationStep
            },
            whiteBalance = whiteBalance,
            flashState = flashState,
            // 传给下游的方向永远是 NORMAL (1)
            orientation = ExifInterface.ORIENTATION_NORMAL,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            captureTime = captureTime,
            latitude = latitude,
            longitude = longitude,
            colorSpace = when {
                captureState.isP3Supported && captureState.useP3ColorSpace -> ColorSpace.Named.DISPLAY_P3
                else -> ColorSpace.Named.SRGB
            }
        )
    }

    private data class ResolvedCaptureFocalLength(
        val focalLength: Float?,
        val focalLength35mm: Int?
    )

    private fun resolveCaptureFocalLength(
        characteristics: CameraCharacteristics?,
        captureResultFocalLength: Float?,
        zoomRatio: Float,
        captureState: CameraState = _state.value,
    ): ResolvedCaptureFocalLength {
        val selectedCamera = captureState.getCurrentCameraInfo()
        val selectedCameraFocalLength = selectedCamera?.focalLength?.takeIf { it > 0f }
        val selectedCameraFocalLength35mm = selectedCamera?.focalLength35mmEquivalent?.takeIf { it > 0f }
        val characteristicsFocalLength = characteristics
            ?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull()
            ?.takeIf { it > 0f }

        val baseFocalLength = selectedCameraFocalLength
            ?: captureResultFocalLength?.takeIf { it > 0f }
            ?: characteristicsFocalLength
        val baseFocalLength35mm = selectedCameraFocalLength35mm
            ?: characteristics?.let(::calculate35mmEquivalent)?.toFloat()

        return ResolvedCaptureFocalLength(
            focalLength = baseFocalLength?.times(zoomRatio),
            focalLength35mm = baseFocalLength35mm?.times(zoomRatio)?.roundToInt()
        )
    }

    private fun shouldUseP3ColorSpace(): Boolean {
        return _state.value.isP3Supported && _state.value.useP3ColorSpace
    }

    /**
     * 计算等效35mm焦距
     *
     * 基于传感器尺寸计算裁切系数
     */
    private fun calculate35mmEquivalent(characteristics: CameraCharacteristics): Int? {
        try {
            val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)

            if (focalLengths == null || focalLengths.isEmpty() || sensorSize == null) {
                return null
            }

            val focalLength = focalLengths[0]

            // 计算传感器对角线
            val sensorDiagonal = kotlin.math.sqrt(
                (sensorSize.width * sensorSize.width + sensorSize.height * sensorSize.height).toDouble()
            ).toFloat()

            // 35mm 全画幅对角线 (36mm x 24mm)
            val filmDiagonal = 43.2666f

            if (sensorDiagonal <= 0) return null

            return (focalLength * filmDiagonal / sensorDiagonal).roundToInt()
        } catch (e: Exception) {
            return null
        }
    }

// ==================== 生命周期 ====================

    /**
     * 关闭相机
     */
    fun closeCamera(
        preserveVideoRecording: Boolean = false,
        expectedSurfaceTexture: SurfaceTexture? = null
    ) {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post {
                closeCamera(preserveVideoRecording, expectedSurfaceTexture)
            }
            return
        }
        val expectedSurfaceIsCurrent = previewSurfaceTexture === expectedSurfaceTexture
        val expectedSurfaceIsPending = pendingCameraOpenRequest?.surfaceTexture === expectedSurfaceTexture
        val expectedSurfaceIsReconfiguring =
            captureModeReconfiguringSurfaceTexture === expectedSurfaceTexture ||
                pendingCaptureModeReconfigureRequest?.surfaceTexture === expectedSurfaceTexture
        if (
            expectedSurfaceTexture != null &&
            !expectedSurfaceIsCurrent &&
            !expectedSurfaceIsPending &&
            !expectedSurfaceIsReconfiguring
        ) {
            PLog.d(
                TAG,
                "closeCamera skipped for stale SurfaceTexture: expected=" +
                        "${System.identityHashCode(expectedSurfaceTexture)}, current=" +
                        "${previewSurfaceTexture?.let { System.identityHashCode(it) }}"
            )
            return
        }
        cancelPendingCaptureModeReconfigure("camera close request")
        captureModeReconfiguringSurfaceTexture = null
        closeCameraInternal(
            preserveVideoRecording = preserveVideoRecording,
            clearPendingOpen = true,
        )
    }

    private fun closeCameraInternal(
        preserveVideoRecording: Boolean,
        clearPendingOpen: Boolean,
    ) {
        if (clearPendingOpen) {
            pendingCameraOpenRequest = null
        }
        try {
            burstGyroRecorder.stop()
            pendingFrameMetadata.clear()
            cameraOpenGeneration++
            captureModeReconfiguringSurfaceTexture = null
            val lifecycleBeforeClose = cameraDeviceLifecycle
            val keepVideoRecording = preserveVideoRecording && _state.value.videoRecordingState.isRecording
            if (!keepVideoRecording) {
                stopDualCaptureCamera()
                when {
                    spatialVideoEnabled && spatialVideoPrimaryReopenInProgress ->
                        stopSpatialVideoStandbyCamera("primary CameraDevice replacement for physical main")
                    spatialVideoEnabled &&
                        !_state.value.videoRecordingState.isRecording &&
                        !_state.value.videoRecordingState.isProcessing ->
                        clearSpecialVideoRuntimeState("primary CameraDevice closing")
                    spatialVideoEnabled && _state.value.videoRecordingState.isProcessing ->
                        stopSpatialVideoStandbyCamera("primary CameraDevice closing during finalization")
                    !spatialVideoEnabled ->
                        stopSpatialVideoStandbyCamera("primary CameraDevice closing")
                    else -> Unit // stopVideoRecording() owns the paired recorder/session teardown below.
                }
            }
            if (keepVideoRecording) {
                PLog.d(TAG, "Closing camera while keeping active video recording")
            }
            if (_state.value.videoRecordingState.isRecording && !keepVideoRecording) {
                stopVideoRecording()
            } else if (!keepVideoRecording && !_state.value.videoRecordingState.isProcessing) {
                videoRecorder.forceStop()
                spatialVideoRecorder.forceStop()
                spatialVideoTelephotoRecorder.forceStop()
                stopVideoRecordingTicker()
                _state.value = _state.value.copy(videoRecordingState = VideoRecordingState())
            }
            val videoStateAfterStopRequest = _state.value.videoRecordingState
            if (videoStateAfterStopRequest.isProcessing) {
                PLog.d(TAG, "Closing camera while video recording is finalizing")
            }

            clearCameraSessionState(reason = "closeCamera")
            val deviceToClose = cameraDevice
            cameraDevice = null
            clearCameraCapabilityCache()

            _state.value = if (keepVideoRecording) {
                _state.value.copy(isPreviewActive = false)
            } else {
                _state.value.copy(
                    isPreviewActive = false,
                    videoRecordingState = if (videoStateAfterStopRequest.isProcessing) {
                        videoStateAfterStopRequest
                    } else {
                        VideoRecordingState()
                    }
                )
            }

            // 停止 Live Photo 录制，释放旧环境下的 EGL 资源
            livePhotoRecorder.stopRecording()

            when {
                deviceToClose != null -> {
                    closeCameraDeviceAndAwait(deviceToClose, "closeCamera")
                }
                lifecycleBeforeClose == CameraDeviceLifecycle.OPENING -> {
                    // CameraManager 没有取消打开的 API；等待其回调后立即关闭。
                    cameraDeviceLifecycle = CameraDeviceLifecycle.OPENING
                }
                lifecycleBeforeClose == CameraDeviceLifecycle.CLOSING -> {
                    cameraDeviceLifecycle = CameraDeviceLifecycle.CLOSING
                }
                else -> {
                    cameraDeviceLifecycle = CameraDeviceLifecycle.CLOSED
                    openPendingCameraRequest()
                }
            }
            PLog.d(TAG, "Camera close requested: lifecycle=$cameraDeviceLifecycle")
        } catch (e: Exception) {
            PLog.e(TAG, "Error closing camera", e)
        }
    }

    private fun invalidateCaptureSession(session: CameraCaptureSession?, reason: String) {
        // A delayed close callback from the old session must not clear its replacement.
        if (session == null || captureSession !== session) return
        captureSession = null
        _enginePreviewSource.value = null
        previewRequestBuilder = null
        val wasBurstCapturing = _state.value.burstCapturing
        burstCapturing = false
        _state.update {
            it.copy(
                isPreviewActive = false,
                previewFirstFrameTimestampNs = null,
                burstCapturing = false,
                isCapturing = if (wasBurstCapturing) false else it.isCapturing,
            )
        }
        if (wasBurstCapturing) {
            // Release burst preparation without submitting requests to the retired session.
            resetPreviewAfterCapture()
        }
        PLog.d(TAG, "Capture session invalidated: $reason")
    }

    private fun safeCloseCaptureSession(session: CameraCaptureSession?, reason: String) {
        invalidateCaptureSession(session, reason)
        try {
            session?.close()
        } catch (e: SecurityException) {
            PLog.w(TAG, "Ignoring SecurityException while closing capture session ($reason): ${e.message}")
        } catch (e: Exception) {
            PLog.e(TAG, "Error closing capture session ($reason)", e)
        }
    }

    private fun safeCloseImageReader(reader: ImageReader?) {
        reader?.let {
            if (openImagesCount.get() == 0) {
                it.close()
                PLog.d(TAG, "ImageReader closed immediately")
            } else {
                synchronized(pendingCloseReaders) {
                    pendingCloseReaders.add(it)
                }
                PLog.d(TAG, "ImageReader added to pending close list, open images: ${openImagesCount.get()}")
            }
        }
    }

    private fun checkAndClosePendingReaders() {
        synchronized(pendingCloseReaders) {
            val iterator = pendingCloseReaders.iterator()
            while (iterator.hasNext()) {
                val reader = iterator.next()
                try {
                    reader.close()
                    PLog.d(TAG, "Closed pending ImageReader")
                } catch (e: Exception) {
                    PLog.e(TAG, "Error closing pending ImageReader", e)
                }
                iterator.remove()
            }
        }
    }

    private fun processAndTriggerCapture(
        image: SafeImage,
        result: TotalCaptureResult?,
        shot: PhotoCaptureContext,
    ) {
        try {
            // Reject malformed HAL buffers while this capture still owns its failure/cleanup path.
            image.validateBufferLayout()
            val width = image.width
            val height = image.height
            val reportedPhysicalCameraId = result
                ?.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
            val effectivePhysicalCameraId = shot.physicalCameraId
                ?: reportedPhysicalCameraId
            var effectiveCharacteristics = effectivePhysicalCameraId
                ?.let { cameraId ->
                    getCameraCharacteristicsOrNull(cameraId, "captured RAW physical camera")
                }
                ?: shot.characteristics
            var effectiveResult: CaptureResult? = effectivePhysicalCameraId
                ?.let { physicalId -> result?.physicalCameraResults?.get(physicalId) }
                ?: result

            // For RAW images, ensure characteristics match image dimensions to avoid DngCreator crash.
            // This is especially important for logical multi-camera devices where the RAW might come from a physical sub-camera.
            if (image.format == ImageFormat.RAW_SENSOR && result != null) {
                val checkMatch = { chars: CameraCharacteristics? ->
                    val pixelArray = chars?.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                    val preCorrectionArray = chars?.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
                    val matchesPixelArray = pixelArray != null &&
                            pixelArray.width == width &&
                            pixelArray.height == height
                    val matchesPreCorrectionArray = preCorrectionArray != null &&
                            preCorrectionArray.width() == width &&
                            preCorrectionArray.height() == height
                    matchesPixelArray || matchesPreCorrectionArray
                }

                if (!checkMatch(effectiveCharacteristics)) {
                    PLog.d(TAG, "RAW dimensions $width x $height mismatch logical characteristics. Searching physical cameras...")
                    for ((physicalId, physicalResult) in result.physicalCameraResults) {
                        try {
                            val physicalChars = getCameraCharacteristicsCached(physicalId)
                            if (checkMatch(physicalChars)) {
                                PLog.i(TAG, "Found matching physical camera $physicalId for RAW image")
                                effectiveCharacteristics = physicalChars
                                effectiveResult = physicalResult
                                break
                            }
                        } catch (e: Exception) {
                            PLog.w(TAG, "Failed to check physical camera $physicalId", e)
                        }
                    }
                }
            }

//            if (
//                image.format == ImageFormat.RAW_SENSOR &&
//                effectiveCharacteristics != null &&
//                effectiveResult != null
//            ) {
//                logRawColorMatrices(effectiveCharacteristics, effectiveResult)
//            }

            // 构建 CaptureInfo
            val captureInfo = rebuildCaptureInfo(
                result = effectiveResult ?: result,
                imageWidth = width,
                imageHeight = height,
                latitude = shot.state.latitude,
                longitude = shot.state.longitude,
                effectiveCharacteristics = effectiveCharacteristics,
                captureState = shot.state,
                captureTime = shot.captureTime,
                exposureCompensationStep = shot.exposureCompensationStep,
            ).copy(livePhotoVideoStartTimestampUs = shot.livePhotoStartTimestampUs)
            val frameResult = effectiveResult ?: result
            val sensorTimestampNs = frameResult?.get(CaptureResult.SENSOR_TIMESTAMP) ?: image.timestamp
            val exposureTimeNs = frameResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            val sensitivityIso = frameResult?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
            val frozenMetadata = pendingFrameMetadata.remove(image.timestamp)
            val sensitivityLimits = sensorSensitivityLimits(effectiveCharacteristics)
            val frameMetadata = if (image.format == ImageFormat.RAW_SENSOR && frameResult != null) {
                CapturedFrameMetadata(
                    sensorTimestampNs = sensorTimestampNs,
                    frameNumber = frozenMetadata?.frameNumber ?: result?.frameNumber ?: -1L,
                    exposureTimeNs = exposureTimeNs,
                    sensitivityIso = sensitivityIso,
                    minimumSensitivityIso = sensitivityLimits.minimumIso
                        .takeIf { it > 0 }
                        ?: frozenMetadata?.minimumSensitivityIso
                        ?: 0,
                    maximumAnalogSensitivityIso = sensitivityLimits.maximumAnalogIso
                        .takeIf { it > 0 }
                        ?: frozenMetadata?.maximumAnalogSensitivityIso
                        ?: 0,
                    exposureProduct = RawExposureMath.productOrNull(
                        exposureTimeNs,
                        sensitivityIso,
                    ) ?: frozenMetadata?.exposureProduct ?: 0.0,
                    desiredExposureProduct = RawExposureMath.productOrNull(
                        frameResult.request.get(CaptureRequest.SENSOR_EXPOSURE_TIME),
                        frameResult.request.get(CaptureRequest.SENSOR_SENSITIVITY),
                    ) ?: frozenMetadata?.desiredExposureProduct,
                    focusDistanceDiopters = frameResult
                        .get(CaptureResult.LENS_FOCUS_DISTANCE)
                        ?: Float.NaN,
                    lensState = frameResult.get(CaptureResult.LENS_STATE),
                    rollingShutterSkewNs = frameResult.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW),
                    gyroWindow = frozenMetadata?.gyroWindow
                        ?: burstGyroRecorder.exposureWindow(sensorTimestampNs, exposureTimeNs),
                    // Resolve noise from the same result as this RAW's ISO/CFA. The early
                    // snapshot belongs to the logical result, while dimension matching above
                    // may select a different physical sensor. If its profile is absent, let
                    // the noise resolver use its declared fallback, not another sensor's model.
                    channelNoiseProfile = captureChannelNoiseProfile(frameResult),
                    multiFrameCaptureRole = frozenMetadata?.multiFrameCaptureRole
                        ?: (frameResult.request.tag as? MultiFrameCaptureRole),
                    dynamicBlackLevelByCfaPosition = frameResult
                        .get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
                        ?.takeIf { it.size >= 4 }
                        ?.copyOf(4)
                        ?: frozenMetadata?.dynamicBlackLevelByCfaPosition?.copyOf(),
                )
            } else {
                null
            }

            if (shot.continuousBurst) {
                val callback = onImageCaptured
                if (callback != null) {
                    callback(image, captureInfo, effectiveCharacteristics, frameResult, frameMetadata)
                } else {
                    image.close()
                }
            } else {
                shot.frames.put(
                    image.timestamp,
                    CapturedPhotoFrame(image, captureInfo, effectiveCharacteristics, frameResult, frameMetadata),
                )?.image?.close()
            }
            deliverPhotoCaptureIfReady(shot)
        } catch (e: Exception) {
            PLog.e(TAG, "Error processing joined capture data", e)
            image.close()
            failPhotoCapture(shot, "failed to construct captured frame")
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        val handler = cameraHandler
        if (handler != null) {
            val latch = java.util.concurrent.CountDownLatch(1)
            releaseCloseLatch = latch
            handler.post {
                closeCamera()
                if (cameraDeviceLifecycle == CameraDeviceLifecycle.CLOSED) {
                    latch.countDown()
                }
            }
            try {
                latch.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                PLog.e(TAG, "Interrupted while waiting for camera close during release", e)
            } finally {
                releaseCloseLatch = null
            }
        } else {
            closeCamera()
        }
        stopSpatialVideoStandbyCamera("controller release")
        spatialVideoStandbyPreviewSurface?.release()
        spatialVideoStandbyPreviewSurface = null
        spatialVideoStandbyPreviewSurfaceTexture = null
        burstGyroRecorder.release()
        oppoContinuousBurstEngine.release()
        videoRecorder.release()
        spatialVideoRecorder.release()
        spatialVideoTelephotoRecorder.release()
        stopBackgroundThread()
        cameraDiscovery.clearCache()
    }

    /**
     * 设置是否启用 Live Photo
     */
    fun setUseLivePhoto(enabled: Boolean) {
        _state.value = _state.value.copy(useLivePhoto = enabled)
        if (enabled && _state.value.captureMode == CaptureMode.PHOTO) {
            livePhotoRecorder.startRecording()
        } else {
            livePhotoRecorder.stopRecording()
        }
    }

    fun setUseP010(enabled: Boolean) {
        _state.value = _state.value.copy(useP010 = enabled)
    }

    fun setUseP3ColorSpace(enabled: Boolean) {
        _state.value = _state.value.copy(useP3ColorSpace = enabled)
    }

    fun setLocation(latitude: Double?, longitude: Double?) {
        PLog.d(TAG, "setLocation: $latitude, $longitude")
        _state.value = _state.value.copy(latitude = latitude, longitude = longitude)
    }

    /**
     * 执行 Live Photo 快照（在按下快门时尽早调用，以确定“之前”的时间范围）
     */
    fun snapshotLivePhoto() {
        livePhotoVideoStartTimestampUs = livePhotoRecorder.snapshot()
    }

    /**
     * 开始后台录制导出视频（在获得照片精确时间戳后调用）
     * @param timestampUs 精确的拍照瞬间时间戳（纳秒/1000）
     */
    fun recordLivePhotoVideo(
        timestampUs: Long? = null,
        captureStartTimestampUs: Long? = null,
        onCaptured: ((java.io.File, Long) -> Unit)? = null,
    ) {
        livePhotoRecorder.recordVideo(timestampUs, captureStartTimestampUs) { file, timestamp ->
            onCaptured?.invoke(file, timestamp)
            onLivePhotoVideoCaptured?.invoke(file, timestamp)
        }
    }

    private fun dispatchOppoBurstJpegFrame(frame: OppoBurstJpegFrame) {
        val captureState = burstCaptureStateSnapshot ?: _state.value
        val captureInfo = rebuildCaptureInfo(
            result = lastCaptureResult,
            imageWidth = frame.width,
            imageHeight = frame.height,
            latitude = captureState.latitude,
            longitude = captureState.longitude,
            effectiveCharacteristics = burstCaptureCharacteristics,
            captureState = captureState,
            captureTime = frame.timestampMs,
            exposureCompensationStep = captureState.getExposureCompensationStep(),
        )
        try {
            onBurstJpegCaptured?.invoke(frame, captureInfo)
        } catch (error: Exception) {
            PLog.e(TAG, "Burst JPEG delivery failed: frame=${frame.frameIndex}", error)
        }
    }

    private fun finishOppoContinuousBurst() {
        val finish = {
            // The workers are drained. Drop final HAL buffers before ordinary still pairing resumes.
            try {
                imageReader?.acquireLatestImage()?.close()
            } catch (error: Exception) {
                PLog.w(TAG, "Final burst reader drain failed: ${error.message}")
            }
            isOppoBurstYuvSession = false
            burstCaptureStateSnapshot = null
            burstCaptureCharacteristics = null
            burstCapturing = false
            _state.update { it.copy(burstCapturing = false, isCapturing = false) }
            try {
                onBurstCaptureCompleted?.invoke()
            } catch (error: Exception) {
                PLog.e(TAG, "Burst completion notification failed", error)
            }
        }
        val handler = cameraHandler
        if (handler != null) {
            // Keep draining any callbacks already queued by ImageReader after the repeating target is removed.
            handler.postDelayed({ finish() }, 120L)
        } else {
            finish()
        }
    }

    private fun startOppoContinuousBurstCapture(
        session: CameraCaptureSession,
        builder: CaptureRequest.Builder,
        reader: ImageReader,
        onAccepted: (() -> Unit)?,
    ) {
        if (isOppoBurstYuvSession || oppoContinuousBurstEngine.isActive()) return
        if (_state.value.isCapturing || photoCaptures.values.any { it.continuousBurst }) return

        val captureState = _state.value.copy(burstCapturing = true, isCapturing = true)
        burstCaptureStateSnapshot = captureState
        burstCaptureCharacteristics = activeOutputPhysicalCameraId
            ?.let { getCameraCharacteristicsOrNull(it, "burst capture snapshot") }
            ?: getActiveOpenCameraCharacteristics()
        livePhotoVideoStartTimestampUs = null
        burstCapturing = true
        isOppoBurstYuvSession = true
        _state.update { it.copy(burstCapturing = true, isCapturing = true) }

        if (!oppoContinuousBurstEngine.start(fastShutterTargetFps) {
                finishOppoContinuousBurst()
            }
        ) {
            isOppoBurstYuvSession = false
            burstCapturing = false
            burstCaptureStateSnapshot = null
            burstCaptureCharacteristics = null
            _state.update { it.copy(burstCapturing = false, isCapturing = false) }
            return
        }

        try {
            // The YUV ImageReader is already part of this Camera2 session. Attaching it to the
            // repeating preview request produces real sensor frames without repeated still captures.
            builder.addTarget(reader.surface)
            session.setRepeatingRequest(builder.build(), previewCallback, cameraHandler)
            onAccepted?.invoke()
            PLog.i(
                TAG,
                "Oppo-style continuous burst started: fps=${fastShutterTargetFps}, " +
                    "source=${reader.width}x${reader.height}, format=YUV_420_888",
            )
        } catch (error: Exception) {
            PLog.e(TAG, "Unable to attach YUV reader for continuous burst", error)
            try {
                builder.removeTarget(reader.surface)
                session.setRepeatingRequest(builder.build(), previewCallback, cameraHandler)
            } catch (restoreError: Exception) {
                PLog.w(TAG, "Unable to restore preview after burst start failure", restoreError)
            }
            burstCapturing = false
            _state.update { it.copy(burstCapturing = false, isCapturing = false) }
            oppoContinuousBurstEngine.stop()
        }
    }

    private fun stopOppoContinuousBurstCapture() {
        val session = captureSession
        val builder = previewRequestBuilder
        val reader = imageReader
        burstCapturing = false
        _state.update { it.copy(burstCapturing = false, isCapturing = false) }

        if (session != null && builder != null && reader != null) {
            try {
                builder.removeTarget(reader.surface)
                session.setRepeatingRequest(builder.build(), previewCallback, cameraHandler)
                PLog.i(TAG, "Oppo-style burst stopped: YUV target detached; queued JPEG work is draining")
            } catch (error: CameraAccessException) {
                PLog.w(TAG, "Camera inaccessible while detaching burst YUV target", error)
                invalidateCaptureSession(session, "camera inaccessible while stopping continuous burst")
            } catch (error: IllegalStateException) {
                PLog.w(TAG, "Capture session closed while detaching burst YUV target", error)
                invalidateCaptureSession(session, "session closed while stopping continuous burst")
            } catch (error: IllegalArgumentException) {
                PLog.w(TAG, "Burst YUV target was not active during detach", error)
            }
        }
        // Do not call abortCaptures(): already accepted frames must finish JPEG encoding and saving.
        oppoContinuousBurstEngine.stop()
    }

    /**
     * 启动连拍
     */
    fun startBurstCapture(onAccepted: (() -> Unit)? = null) {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post { startBurstCapture(onAccepted) }
            return
        }
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        val reader = imageReader ?: return
        if (reader.imageFormat == ImageFormat.YUV_420_888 && !_state.value.useRaw) {
            startOppoContinuousBurstCapture(session, builder, reader, onAccepted)
            return
        }
        val isStartingNewBurst = !_state.value.burstCapturing
        if (isStartingNewBurst && (_state.value.isCapturing || photoCaptures.values.any { it.continuousBurst })) return
        if (!isStartingNewBurst && onAccepted != null) return
        val shot = pendingContinuousBurstCapture ?: reservePhotoCapture(
            _state.value.copy(burstCapturing = true, isCapturing = true),
            reader,
            continuousBurst = true,
        )
        pendingContinuousBurstCapture = null
        if (shot == null) {
            if (!isStartingNewBurst) {
                burstCapturing = false
                _state.update { it.copy(burstCapturing = false, isCapturing = false) }
                resetPreviewAfterCapture()
            }
            return
        }
        if (isStartingNewBurst) {
            livePhotoVideoStartTimestampUs = null
            PLog.d(TAG, "Start Burst Capture")
            _state.update { it.copy(burstCapturing = true, isCapturing = true) }
            burstCapturing = true
            try {
                onAccepted?.invoke()
            } catch (e: Exception) {
                failPhotoCapture(shot, "continuous burst acceptance callback failed")
                burstCapturing = false
                _state.update { it.copy(burstCapturing = false, isCapturing = false) }
                resetPreviewAfterCapture()
                PLog.e(TAG, "Continuous burst acceptance callback failed", e)
                return
            }
        }
        if (isStartingNewBurst && shouldUseContinuousBurstTorch(_state.value)) {
            pendingContinuousBurstCapture = shot
            PLog.d(TAG, "Starting continuous burst torch warm-up")
            runMultiFrameTorchWarmupSequence {
                if (!_state.value.burstCapturing) return@runMultiFrameTorchWarmupSequence
                isContinuousBurstTorchActive = true
                startBurstCapture()
            }
            return
        }

        try {
            // Apply capture intent
            val isRawCapture = isRawCaptureReader(imageReader)
            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                imageReader?.surface?.let { addTarget(it) }
                if (!isRawCapture && shouldMirrorStillCaptureToPreview()) {
                    previewSurface?.let { addTarget(it) }
                } else if (isRawCapture) {
                    PLog.d(TAG, "RAW burst capture uses RAW target only to avoid unstable RAW+preview still requests")
                }

                applyBaseCameraSettings(this, isCapture = true, isRawCapture = isRawCapture)
                applyFastShutterTargetFps(this)

                if (isContinuousBurstTorchActive) {
                    if (_state.value.isAutoExposure) {
                        set(
                            CaptureRequest.CONTROL_AE_MODE,
                            resolveSupportedAeMode(CaptureRequest.CONTROL_AE_MODE_ON)
                        )
                        val aeLockAvailable = getActiveOpenCameraCharacteristics()
                            ?.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true
                        if (aeLockAvailable) {
                            set(CaptureRequest.CONTROL_AE_LOCK, true)
                        }
                    }
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                }

                setAePrecaptureTriggerIfSupported(
                    this,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE,
                )

                builder.get(CaptureRequest.CONTROL_AF_MODE)?.let { set(CaptureRequest.CONTROL_AF_MODE, it) }
                builder.get(CaptureRequest.LENS_FOCUS_DISTANCE)?.let { set(CaptureRequest.LENS_FOCUS_DISTANCE, it) }
                builder.get(CaptureRequest.CONTROL_AF_REGIONS)?.let { set(CaptureRequest.CONTROL_AF_REGIONS, it) }
                builder.get(CaptureRequest.CONTROL_AE_REGIONS)?.let { set(CaptureRequest.CONTROL_AE_REGIONS, it) }
            }

            val request = captureBuilder.build()
            val requests = mutableListOf<CaptureRequest>()
            for (i in 0 until BURST_CAPTURE_BATCH_SIZE) {
                requests.add(request)
            }
            submitPhotoCapture(shot, session, requests)
        } catch (e: Exception) {
            failPhotoCapture(shot, "continuous burst submission failed")
            PLog.e(TAG, "Failed to start hardware burst capture", e)
            burstCapturing = false
            _state.value = _state.value.copy(burstCapturing = false, isCapturing = false)
            resetPreviewAfterCapture()
        }
    }

    private fun shouldMirrorStillCaptureToPreview(): Boolean {
        val isLivePhotoCapture = _state.value.useLivePhoto || _state.value.isCapturingLivePhoto
        if (isLivePhotoCapture) {
            PLog.i(
                TAG,
                "Skipping preview target on still capture to avoid Live Photo flash frame and preview flicker"
            )
            return false
        }
        return true
    }

    private fun checkBurstCaptureContinue() {
        if (!state.value.burstCapturing) return
        if (CAPTURE_READER_MAX_IMAGES - openImagesCount.get() - reservedImageCount() < BURST_CAPTURE_BATCH_SIZE) {
            burstCapturing = false
            _state.update { it.copy(burstCapturing = false, isCapturing = false) }
            resetPreviewAfterCapture()
            return
        }
        startBurstCapture()
    }

    /**
     * 停止连拍
     */
    fun stopBurstCapture() {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post {
                stopBurstCapture()
            }
            return
        }
        if (!_state.value.burstCapturing) return
        PLog.d(TAG, "Stop Burst Capture")
        if (isOppoBurstYuvSession) {
            stopOppoContinuousBurstCapture()
            return
        }
        val session = captureSession
        // Disable continuation before aborting; completion callbacks must not queue another batch.
        burstCapturing = false
        _state.update { it.copy(burstCapturing = false, isCapturing = false) }
        try {
            session?.abortCaptures()
        } catch (e: CameraAccessException) {
            PLog.w(TAG, "Camera inaccessible during burst stop", e)
            invalidateCaptureSession(session, "camera inaccessible during burst stop")
        } catch (e: IllegalStateException) {
            // The framework can close the session before its onClosed callback reaches us.
            PLog.w(TAG, "Capture session closed during burst stop", e)
            invalidateCaptureSession(session, "session closed during burst stop")
        } finally {
            resetPreviewAfterCapture()
        }
    }
}
