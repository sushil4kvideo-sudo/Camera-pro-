package com.hinnka.mycamera.lut

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Size
import android.view.Surface
import com.hinnka.mycamera.model.ColorRecipeParams
import com.hinnka.mycamera.stabilization.DEFAULT_VIDEO_STABILIZATION_LOOKAHEAD
import com.hinnka.mycamera.stabilization.POSITION_LOCK_CROP_ZOOM
import com.hinnka.mycamera.stabilization.RealtimeStabilizationCoordinator
import com.hinnka.mycamera.stabilization.StabilizationUseCase
import com.hinnka.mycamera.utils.MediaWatermark
import com.hinnka.mycamera.utils.PLog
import com.hinnka.mycamera.video.DualCaptureLayout
import com.hinnka.mycamera.video.VideoEncoderColorConfig
import com.hinnka.mycamera.video.VideoEncodedColorPipeline
import com.hinnka.mycamera.video.VideoLogProfile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

/**
 * Camera2 录像专用实时渲染器。
 *
 * 普通录像从独立 [SurfaceTexture] 取 OES 帧；MGC 防抖录像改取与 CaptureResult 精确
 * 对时的共享 YUV 图像。两条路径最终都在专用 EGL context 中写入 MediaCodec surface，
 * 因而录像帧率不受 GLSurfaceView 调度影响。
 */
class RealtimeVideoRenderer(
    context: Context,
    cameraInputSize: Size,
    private val encoderOutputSize: Size,
    private val sensorOrientationDegrees: Int,
    colorLayers: List<VideoColorEffectLayer>,
    private val videoLogProfile: VideoLogProfile,
    @Volatile private var mirrorHorizontally: Boolean,
    @Volatile private var mirrorVertically: Boolean,
    private val encoderColorConfig: VideoEncoderColorConfig,
    private val stabilizationCoordinator: RealtimeStabilizationCoordinator?,
    private val enhancedStabilizationEnabled: Boolean,
    private val enhancedStabilizationStrength: Float,
    private val enhancedStabilizationLookahead: Int = DEFAULT_VIDEO_STABILIZATION_LOOKAHEAD,
    private val positionLockEnabled: Boolean = false,
    private val positionLockCropZoom: Float = POSITION_LOCK_CROP_ZOOM,
    cameraInputIsHlg10: Boolean = false,
    dualCaptureInputSize: Size? = null,
    dualCaptureSensorOrientationDegrees: Int = 0,
    dualCaptureLayout: DualCaptureLayout = DualCaptureLayout.PICTURE_IN_PICTURE,
    dualCapturePositionX: Float = 0.66f,
    dualCapturePositionY: Float = 0.05f,
    dualCapturePipWidthFraction: Float = 0.33f,
    private val encoderOrientationHintDegrees: Int = 0,
    cameraInputZoomRatio: Float = 1f,
    cameraInputPanX: Float = 0f,
    cameraInputPanY: Float = 0f,
    cameraInputRollDegrees: Float = 0f,
    private val watermarkLabel: String? = null,
    private val switchableCameraInputSize: Size? = null,
    private val switchableCameraSensorOrientationDegrees: Int = 0,
) {
    companion object {
        private const val TAG = "RealtimeVideoRenderer"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val EGL_OPENGL_ES3_BIT_KHR = 0x0040
        private val WATERMARK_VERTEX_SHADER = """
            #version 300 es
            in vec4 aPosition;
            in vec2 aTexCoord;
            uniform mat4 uMVPMatrix;
            out vec2 vTexCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTexCoord = vec2(aTexCoord.x, 1.0 - aTexCoord.y);
            }
        """.trimIndent()
        private val WATERMARK_FRAGMENT_SHADER = """
            #version 300 es
            precision mediump float;
            in vec2 vTexCoord;
            uniform sampler2D uTexture;
            out vec4 fragColor;
            void main() {
                fragColor = texture(uTexture, vTexCoord);
            }
        """.trimIndent()
    }

    private data class LayerResources(
        val lutConfig: LutConfig?,
        @Volatile var params: ColorRecipeParams,
        var lutTextureId: Int = 0,
        var curveTextureId: Int = 0,
    )

    private val appContext = context.applicationContext
    @Volatile private var cameraInputAlreadyHlg10 = cameraInputIsHlg10
    private val layers = colorLayers
        .map { layer ->
            LayerResources(
                lutConfig = layer.lutConfig,
                params = layer.recipeParams ?: ColorRecipeParams.DEFAULT,
            )
        }
        .ifEmpty {
            listOf(LayerResources(lutConfig = null, params = ColorRecipeParams.DEFAULT))
        }
        .also { resolved ->
            require(resolved.size <= 2) {
                "Realtime video supports the baseline and creative color layers only"
            }
        }
    private val colorProgramCache = PreviewColorProgramCache()
    private val basicToneTextures = BasicToneGlTextures()
    private val filmGrainGl = FilmGrainGl(TAG)
    private val mgcEisHardwareBufferRenderer = MgcEisHardwareBufferRenderer(TAG)
    private val mgcEisYuvRenderer = MgcEisYuvRenderer(TAG)
    private val filmGrainAmount: Float
        get() = layers.lastOrNull { it.params.filmGrain > 0.001f }?.params?.filmGrain ?: 0f
    private val finalFilmGrainEnabled: Boolean
        get() = filmGrainAmount > 0.001f &&
            encoderColorConfig.pipeline != VideoEncodedColorPipeline.CUSTOM_LOG &&
            encoderColorConfig.pipeline != VideoEncodedColorPipeline.HLG10
    private val identityMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val firstPassMvpMatrix = FloatArray(16).also {
        Matrix.setIdentityM(it, 0)
        if (mirrorHorizontally || mirrorVertically) {
            Matrix.scaleM(
                it,
                0,
                if (mirrorHorizontally) -1f else 1f,
                if (mirrorVertically) -1f else 1f,
                1f,
            )
        }
    }
    private val fullCropRect = floatArrayOf(0f, 0f, 1f, 1f)
    private var cameraCropRect = fullCropRect
    @Volatile private var currentCameraInputZoomRatio =
        cameraInputZoomRatio.takeIf { it.isFinite() }?.coerceIn(1f, 20f) ?: 1f
    @Volatile private var currentCameraInputPanX =
        cameraInputPanX.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
    @Volatile private var currentCameraInputPanY =
        cameraInputPanY.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
    @Volatile private var currentCameraInputRollDegrees =
        cameraInputRollDegrees.takeIf { it.isFinite() }?.coerceIn(-20f, 20f) ?: 0f
    @Volatile private var cameraTransformAxesSwapped: Boolean? = null
    @Volatile private var currentCameraSensorOrientationDegrees = sensorOrientationDegrees
    @Volatile private var cameraSensorOrientationFallbackApplied = false
    private val stMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val cameraPresentationMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val mgcEisPresentationTransform = MgcEisPresentationTransform()

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var encoderEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var cameraTextureId = 0
    private var cameraSurfaceTexture: SurfaceTexture? = null
    @Volatile private var currentCameraInputSize: Size = cameraInputSize
    private var cameraSurface: Surface? = null
    private var switchableCameraTextureId = 0
    private var switchableCameraSurfaceTexture: SurfaceTexture? = null
    private var switchableCameraSurface: Surface? = null
    private val switchableCameraFramePending = AtomicBoolean(false)
    private val switchableCameraStMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val switchableCameraPresentationMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    @Volatile private var switchableCameraHasFrame = false
    @Volatile private var switchableCameraInputActive = false
    private var dualCaptureTextureId = 0
    private var dualCaptureSurfaceTexture: SurfaceTexture? = null
    private var dualCaptureSurface: Surface? = null
    private val dualCaptureFramePending = AtomicBoolean(false)
    private val dualCaptureStMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val dualCaptureMvpMatrix = FloatArray(16).also {
        Matrix.setIdentityM(it, 0)
    }
    private var dualCaptureHasFrame = false
    private var dualCaptureProgramId = 0
    @Volatile private var currentDualCaptureLayout = dualCaptureLayout
    @Volatile private var currentDualCapturePositionX = dualCapturePositionX.coerceIn(0f, 1f)
    @Volatile private var currentDualCapturePositionY = dualCapturePositionY.coerceIn(0f, 1f)
    private val currentDualCapturePipWidthFraction = dualCapturePipWidthFraction.coerceIn(0.01f, 0.33f)
    private val currentDualCaptureInputSize = dualCaptureInputSize
    private val currentDualCaptureSensorOrientationDegrees = dualCaptureSensorOrientationDegrees
    private val dualCapturePresentationMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private var intermediateTextureId = 0
    private var intermediateFboId = 0
    private var finalColorTextureId = 0
    private var finalColorFboId = 0
    private var watermarkProgramId = 0
    private var watermarkTextureId = 0
    private var vertexBuffer: FloatBuffer? = null
    private var textureCoordinateBuffer: FloatBuffer? = null
    private var indexBuffer: ShortBuffer? = null
    private var initialized = false
    private val mgcStabilizationEnabled: Boolean
        get() = enhancedStabilizationEnabled || positionLockEnabled
    private val cameraFramePending = AtomicBoolean(false)
    @Volatile
    private var stabilizationSession: RealtimeStabilizationCoordinator.Session? = null
    @Volatile
    private var stabilizationSessionStarted = false

    @Volatile private var diagnosticLastSkipReason = "NOT_STARTED"
    private val diagnosticSkipReasons = linkedMapOf<String, Long>()
    @Volatile private var diagnosticGpuVendor = "unavailable"
    @Volatile private var diagnosticGpuRenderer = "unavailable"
    @Volatile private var diagnosticGpuVersion = "unavailable"
    private var diagnosticHardwareBufferFrames = 0L
    private var diagnosticYuvFallbackFrames = 0L
    private var diagnosticEisRenderFailures = 0L
    private var diagnosticEisRenderTotalNs = 0L
    private var diagnosticEisRenderMaxNs = 0L
    private var diagnosticEglSwapFrames = 0L
    private var diagnosticEglSwapTotalNs = 0L
    private var diagnosticEglSwapMaxNs = 0L

    val inputSurface: Surface?
        get() = cameraSurface

    val dualCaptureInputSurface: Surface?
        get() = dualCaptureSurface

    val switchableCameraInputSurface: Surface?
        get() = switchableCameraSurface

    fun setSwitchableCameraInputActive(active: Boolean) {
        if (active && switchableCameraSurfaceTexture == null) {
            PLog.w(TAG, "Ignoring ultra-wide recording handoff: alternate camera input is unavailable")
            switchableCameraInputActive = false
            return
        }
        switchableCameraInputActive = active
        PLog.i(TAG, "Recording camera source selected: ${if (active) "0.5x-ultraWide" else "1x-main"}")
    }

    fun updateCameraInputCrop(
        zoomRatio: Float,
        panX: Float,
        panY: Float,
        rollDegrees: Float = 0f,
    ) {
        currentCameraInputZoomRatio = zoomRatio.takeIf { it.isFinite() }?.coerceIn(1f, 20f) ?: 1f
        currentCameraInputPanX = panX.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
        currentCameraInputPanY = panY.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
        currentCameraInputRollDegrees = rollDegrees.takeIf { it.isFinite() }?.coerceIn(-20f, 20f) ?: 0f
        PLog.d(
            TAG,
            "Live software crop updated: zoom=$currentCameraInputZoomRatio, " +
                "pan=($currentCameraInputPanX,$currentCameraInputPanY), roll=$currentCameraInputRollDegrees°",
        )
    }

    fun updateDualCaptureLayout(layout: DualCaptureLayout) {
        currentDualCaptureLayout = layout
    }

    fun updateDualCapturePosition(x: Float, y: Float) {
        currentDualCapturePositionX = x.coerceIn(0f, 1f)
        currentDualCapturePositionY = y.coerceIn(0f, 1f)
    }

    /**
     * Update the creative recipe on the GL thread without rebuilding the encoder or EGL session.
     * The last layer is the creative layer (or the identity fallback when no creative effects
     * were selected at recording start).
     */
    fun updateCreativeRecipeParams(params: ColorRecipeParams) {
        layers.last().params = params
        PLog.i(TAG, "Live recording recipe updated")
    }

    fun setCameraInputHlg10Source(enabled: Boolean) {
        cameraInputAlreadyHlg10 = enabled
    }
    /**
     * A single recording may span multiple lenses. Refresh the output mirror in place instead of
     * retaining the facing of the camera that started the recording.
     *
     * The caller serializes this update on the render dispatcher with frame drawing.
     */
    fun updateMirrorTransform(mirrorHorizontally: Boolean, mirrorVertically: Boolean) {
        if (this.mirrorHorizontally == mirrorHorizontally &&
            this.mirrorVertically == mirrorVertically
        ) return

        this.mirrorHorizontally = mirrorHorizontally
        this.mirrorVertically = mirrorVertically
        Matrix.setIdentityM(firstPassMvpMatrix, 0)
        if (mirrorHorizontally || mirrorVertically) {
            Matrix.scaleM(
                firstPassMvpMatrix,
                0,
                if (mirrorHorizontally) -1f else 1f,
                if (mirrorVertically) -1f else 1f,
                1f,
            )
        }
        PLog.i(
            TAG,
            "Recording mirror transform updated: mirrorH=$mirrorHorizontally, " +
                "mirrorV=$mirrorVertically",
        )
    }


    /**
     * Keep the encoder resolution fixed while adapting the camera input stream when
     * recording continues across a front/rear camera switch.
     */
    fun updateCameraInputSize(
        size: Size,
        sensorOrientationDegrees: Int = currentCameraSensorOrientationDegrees,
    ) {
        if (size.width <= 0 || size.height <= 0) return
        val cameraInputChanged = currentCameraInputSize != size ||
            currentCameraSensorOrientationDegrees != sensorOrientationDegrees
        currentCameraInputSize = size
        currentCameraSensorOrientationDegrees = sensorOrientationDegrees
        cameraSensorOrientationFallbackApplied = false
        cameraSurfaceTexture?.setDefaultBufferSize(size.width, size.height)
        // The next frame may carry a different transform even when its axes have not swapped.
        // Force crop geometry to be recalculated against the new camera's input dimensions.
        cameraTransformAxesSwapped = null
        if (mgcStabilizationEnabled && cameraInputChanged) {
            // The stabilized YUV texture is an independent producer. Refresh its presentation
            // matrix from the replacement camera's metadata, never from a possibly stale OES frame.
            mgcEisPresentationTransform.reset()
            check(mgcEisPresentationTransform.captureFromSensorOrientation(sensorOrientationDegrees)) {
                "Camera reported non-cardinal sensor orientation $sensorOrientationDegrees"
            }
            updateCameraTransformGeometry(mgcEisPresentationTransform.matrix)
        }
        PLog.i(
            TAG,
            "Camera input geometry refreshed: input=${size.width}x${size.height}, " +
                "encoder=${encoderOutputSize.width}x${encoderOutputSize.height}",
        )
    }

    fun initialize(
        encoderSurface: Surface,
        onFrameAvailable: () -> Unit,
    ) {
        check(!initialized) { "Realtime video renderer is already initialized" }
        initializeEgl(encoderSurface)
        check(makeCurrent()) { "Cannot make realtime video EGL context current" }
        diagnosticGpuVendor = GLES30.glGetString(GLES30.GL_VENDOR) ?: "unavailable"
        diagnosticGpuRenderer = GLES30.glGetString(GLES30.GL_RENDERER) ?: "unavailable"
        diagnosticGpuVersion = GLES30.glGetString(GLES30.GL_VERSION) ?: "unavailable"
        initializeGeometry()
        initializeLayerTextures()
        initializeWatermarkOverlay()
        if (layers.size > 1 || finalFilmGrainEnabled) {
            initializeIntermediateFramebuffer()
        }
        if (layers.size > 1 && finalFilmGrainEnabled) {
            val finalTarget = createColorFramebuffer("final color")
            finalColorTextureId = finalTarget.first
            finalColorFboId = finalTarget.second
        }
        if (finalFilmGrainEnabled) {
            check(filmGrainGl.prepare()) { "Cannot create realtime video film grain program" }
        }

        cameraTextureId = GlUtils.createOESTexture()
        check(cameraTextureId != 0) { "Cannot create realtime video OES texture" }
        cameraSurfaceTexture = SurfaceTexture(cameraTextureId).apply {
            setDefaultBufferSize(currentCameraInputSize.width, currentCameraInputSize.height)
            setOnFrameAvailableListener {
                cameraFramePending.set(true)
                val useMgcFrames = mgcStabilizationEnabled &&
                    stabilizationCoordinator?.isCurrentCameraSupported == true &&
                    stabilizationSessionStarted &&
                    stabilizationSession?.isOperational == true
                // Fall back to the regular OES frame callback whenever EIS+ is requested but
                // its session is not actually running (native start failure, HAL conflict,
                // camera switch reset, or coordinator teardown). Otherwise one failed EIS
                // session can suppress every subsequent normal video frame.
                if (!useMgcFrames) {
                    onFrameAvailable()
                }
            }
        }
        cameraSurface = Surface(cameraSurfaceTexture)
        switchableCameraInputSize?.takeIf { it.width > 0 && it.height > 0 }?.let { size ->
            initializeSwitchableCameraInput(size, onFrameAvailable)
        }
        currentDualCaptureInputSize?.let(::initializeDualCaptureInput)
        if (mgcStabilizationEnabled) {
            stabilizationCoordinator?.beginVideoDiagnostics()
            check(
                mgcEisPresentationTransform.captureFromSensorOrientation(
                    sensorOrientationDegrees,
                )
            ) {
                "Camera reported non-cardinal sensor orientation $sensorOrientationDegrees"
            }
            updateCameraTransformGeometry(mgcEisPresentationTransform.matrix)
            stabilizationSession = stabilizationCoordinator?.createSession(
                useCase = if (positionLockEnabled) StabilizationUseCase.POSITION_LOCK else StabilizationUseCase.VIDEO,
                strength = enhancedStabilizationStrength,
                frameWidth = currentCameraInputSize.width,
                frameHeight = currentCameraInputSize.height,
                lookaheadFrames = enhancedStabilizationLookahead,
                positionLockCropZoom = positionLockCropZoom
                    .takeIf { it.isFinite() }?.coerceIn(1f, 10f) ?: POSITION_LOCK_CROP_ZOOM,
                onFrameAvailable = onFrameAvailable,
            )
        }
        initialized = true
        PLog.i(
            TAG,
            "Initialized: camera=${currentCameraInputSize.width}x${currentCameraInputSize.height}, " +
                "encoder=${encoderOutputSize.width}x${encoderOutputSize.height}, " +
                "sensorOrientation=$sensorOrientationDegrees, " +
                "layers=${layers.size}, log=${videoLogProfile.name}, " +
                "cameraInputHlg10=$cameraInputAlreadyHlg10, " +
                "mgcStabilization=$mgcStabilizationEnabled, positionLock=$positionLockEnabled, " +
                "stabilizationStrength=$enhancedStabilizationStrength, " +
                "mirrorH=$mirrorHorizontally, mirrorV=$mirrorVertically, " +
                "color=${encoderColorConfig.debugName}"
        )
    }

    /**
     * 消费 SurfaceTexture 中最新的一帧并写入编码器。必须在初始化所用的录像 GL 线程调用。
     *
     * [mapPresentationTimeNs] 将相机时间轴映射到编码器使用的公共时间轴。
     *
     * @return 写入编码器的帧时间戳（纳秒）；没有有效帧时返回 null。
     */
    fun renderLatestFrame(
        mapPresentationTimeNs: (cameraTimestampNs: Long, switchableInput: Boolean) -> Long,
    ): Long? {
        if (!initialized || !makeCurrent()) {
            markDiagnosticFrameSkip("EGL_CONTEXT_UNAVAILABLE")
            return null
        }
        val surfaceTexture = cameraSurfaceTexture ?: run {
            markDiagnosticFrameSkip("CAMERA_SURFACE_TEXTURE_MISSING")
            return null
        }
        val useSwitchableCameraInput = switchableCameraInputActive &&
            switchableCameraSurfaceTexture != null
        if (dualCaptureSurfaceTexture != null) {
            acquireLatestDualCaptureFrame()
            if (!dualCaptureHasFrame) {
                markDiagnosticFrameSkip("DUAL_CAPTURE_WAITING_FOR_FRAME")
                return null
            }
        }

        val cameraFrameUpdated = if (useSwitchableCameraInput) {
            false
        } else {
            cameraFramePending.getAndSet(false) && acquireLatestCameraFrame(surfaceTexture)
        }
        val switchableFrameUpdated = if (useSwitchableCameraInput) {
            switchableCameraFramePending.getAndSet(false) && acquireLatestSwitchableCameraFrame()
        } else {
            false
        }

        var sourceTextureSource = PreviewColorTextureSource.EXTERNAL_OES
        var sourceTextureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        var sourceTextureId = cameraTextureId
        var sourceStMatrix = if (mgcStabilizationEnabled) stMatrix else cameraPresentationMatrix
        var sourceCropRect = fullCropRect
        var timestampNs = surfaceTexture.timestamp

        val session = stabilizationSession
        val stabilizationSessionOperational = mgcStabilizationEnabled &&
            stabilizationCoordinator?.isCurrentCameraSupported == true &&
            stabilizationSessionStarted &&
            session?.isOperational == true
        // Keep EIS+ alive while the alternate lens is selected. It is bypassed only for those
        // ultra-wide frames; returning to 1x can immediately resume the existing stabilized feed.
        if (stabilizationSessionStarted && !stabilizationSessionOperational) {
            session?.stop()
            stabilizationSessionStarted = false
            mgcEisPresentationTransform.reset()
        }

        if (useSwitchableCameraInput) {
            if (!switchableFrameUpdated) {
                markDiagnosticFrameSkip("SWITCHABLE_CAMERA_FRAME_UNAVAILABLE")
                return null
            }
            val alternateSize = switchableCameraInputSize ?: run {
                markDiagnosticFrameSkip("SWITCHABLE_CAMERA_SIZE_UNAVAILABLE")
                return null
            }
            val presentationMatrix = resolveSwitchableCameraPresentationMatrix(
                switchableCameraStMatrix,
                alternateSize,
            )
            timestampNs = switchableCameraSurfaceTexture?.timestamp ?: run {
                markDiagnosticFrameSkip("SWITCHABLE_CAMERA_TIMESTAMP_UNAVAILABLE")
                return null
            }
            if (timestampNs <= 0L) {
                markDiagnosticFrameSkip("INVALID_SWITCHABLE_CAMERA_TIMESTAMP")
                return null
            }
            sourceTextureId = switchableCameraTextureId
            sourceStMatrix = presentationMatrix
            sourceCropRect = resolveCameraCropRect(
                presentationMatrix = presentationMatrix,
                inputWidth = alternateSize.width,
                inputHeight = alternateSize.height,
            )
        } else if (stabilizationSessionOperational) {
            val frame = session?.dequeueFrame() ?: run {
                markDiagnosticFrameSkip("EIS_LOOKAHEAD_WAITING_FOR_NATIVE_RESULT")
                return null
            }
            if (positionLockEnabled && frame.transform == null) {
                markDiagnosticFrameSkip("EIS_POSE_MISSING_POSITION_LOCK")
                // A missing MGC pose must not reset the virtual camera to a centered 2.5x crop.
                // Skip this timestamp; the next valid Position Lock pose resumes from the same anchor.
                frame.image.close()
                return null
            }
            val rendered = renderMgcEisFrame(frame) ?: run {
                markDiagnosticFrameSkip("EIS_HARDWAREBUFFER_AND_YUV_RENDER_FAILED")
                return null
            }
            sourceTextureSource = PreviewColorTextureSource.TEXTURE_2D
            sourceTextureTarget = GLES30.GL_TEXTURE_2D
            sourceTextureId = rendered.textureId
            sourceStMatrix = mgcEisPresentationTransform.matrix
            sourceCropRect = resolveCameraCropRect(
                presentationMatrix = mgcEisPresentationTransform.matrix,
                inputWidth = rendered.width,
                inputHeight = rendered.height,
            )
            timestampNs = rendered.timestampNs
        } else {
            if (!cameraFrameUpdated || timestampNs <= 0L) {
                markDiagnosticFrameSkip("CAMERA_OES_FRAME_UNAVAILABLE")
                return null
            }
            sourceCropRect = cameraCropRect
        }
        if (timestampNs <= 0L) {
            markDiagnosticFrameSkip("INVALID_SOURCE_FRAME_TIMESTAMP")
            return null
        }
        if (positionLockEnabled && sourceTextureSource == PreviewColorTextureSource.EXTERNAL_OES) {
            // Match the selected Position Lock crop until the first timestamp-matched MGC frame arrives.
            val safePositionLockZoom = positionLockCropZoom
                .takeIf { it.isFinite() }?.coerceIn(1f, 10f) ?: POSITION_LOCK_CROP_ZOOM
            sourceCropRect = resolveRealtimeVideoZoomCropRect(sourceCropRect, safePositionLockZoom, 0f, 0f)
        }
        // Special Video's virtual telephoto is cropped from the live sensor frame here,
        // before it reaches the dedicated MediaCodec input surface (never after recording).
        sourceCropRect = resolveRealtimeVideoZoomCropRect(
            sourceCropRect,
            currentCameraInputZoomRatio,
            currentCameraInputPanX,
            currentCameraInputPanY,
        )
        val presentationTimeNs = mapPresentationTimeNs(timestampNs, useSwitchableCameraInput)
        if (presentationTimeNs <= 0L) {
            markDiagnosticFrameSkip("INVALID_MAPPED_PRESENTATION_TIMESTAMP")
            return null
        }

        val firstLayer = layers.first()
        val grainEnabled = finalFilmGrainEnabled
        val firstTargetFbo = if (layers.size > 1 || grainEnabled) intermediateFboId else 0
        drawLayer(
            layer = firstLayer,
            targetFboId = firstTargetFbo,
            textureSource = sourceTextureSource,
            textureTarget = sourceTextureTarget,
            textureId = sourceTextureId,
            sourceStMatrix = sourceStMatrix,
            cropRect = sourceCropRect,
            mvpMatrix = resolveCameraInputRollMvpMatrix(
                firstPassMvpMatrix,
                encoderOutputSize.width,
                encoderOutputSize.height,
            ),
            enableVideoLog = videoLogProfile.isEnabled && !cameraInputAlreadyHlg10,
            presentationTimeNs = timestampNs,
            viewport = resolvePrimaryViewport(),
        )

        if (layers.size > 1) {
            layers.drop(1).forEachIndexed { index, layer ->
                val isLast = index == layers.lastIndex - 1
                drawLayer(
                    layer = layer,
                    targetFboId = if (isLast) {
                        if (grainEnabled) finalColorFboId else 0
                    } else {
                        intermediateFboId
                    },
                    textureSource = PreviewColorTextureSource.TEXTURE_2D,
                    textureTarget = GLES30.GL_TEXTURE_2D,
                    textureId = intermediateTextureId,
                    sourceStMatrix = identityMatrix,
                    cropRect = fullCropRect,
                    mvpMatrix = identityMatrix,
                    enableVideoLog = false,
                    presentationTimeNs = timestampNs,
                )
            }
        }

        if (grainEnabled) {
            val grainSourceTextureId = if (layers.size > 1) finalColorTextureId else intermediateTextureId
            check(
                filmGrainGl.drawToFramebuffer(
                    targetFramebufferId = 0,
                    sourceTextureId = grainSourceTextureId,
                    width = encoderOutputSize.width,
                    height = encoderOutputSize.height,
                    amount = filmGrainAmount,
                    frameSeed = FilmGrainShaders.frameSeed(timestampNs),
                    drawQuad = ::drawSimpleQuad,
                )
            ) {
                "Realtime video film grain pass failed"
            }
        }
        drawDualCaptureOverlay()
        drawWatermarkOverlay()
        GLES30.glViewport(0, 0, encoderOutputSize.width, encoderOutputSize.height)

        EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderEglSurface, presentationTimeNs)
        val swapStartNs = System.nanoTime()
        if (!EGL14.eglSwapBuffers(eglDisplay, encoderEglSurface)) {
            markDiagnosticFrameSkip("EGL_SWAP_BUFFERS_FAILED")
            throw IllegalStateException(
                "Realtime video eglSwapBuffers failed: 0x${Integer.toHexString(EGL14.eglGetError())}"
            )
        }
        val swapDurationNs = (System.nanoTime() - swapStartNs).coerceAtLeast(0L)
        diagnosticEglSwapFrames += 1L
        diagnosticEglSwapTotalNs += swapDurationNs
        diagnosticEglSwapMaxNs = maxOf(diagnosticEglSwapMaxNs, swapDurationNs)
        diagnosticLastSkipReason = "NONE"
        return presentationTimeNs
    }

    private fun initializeSwitchableCameraInput(size: Size, onFrameAvailable: () -> Unit) {
        switchableCameraTextureId = GlUtils.createOESTexture()
        check(switchableCameraTextureId != 0) { "Cannot create switchable camera OES texture" }
        switchableCameraSurfaceTexture = SurfaceTexture(switchableCameraTextureId).apply {
            setDefaultBufferSize(size.width, size.height)
            setOnFrameAvailableListener {
                switchableCameraFramePending.set(true)
                // Wake the encoder renderer as soon as the standby lens delivers a frame.
                onFrameAvailable()
            }
        }
        switchableCameraSurface = Surface(switchableCameraSurfaceTexture)
        PLog.i(
            TAG,
            "Switchable ultra-wide recording input ready: ${size.width}x${size.height}, " +
                "sensorOrientation=$switchableCameraSensorOrientationDegrees",
        )
    }

    private fun initializeDualCaptureInput(size: Size) {
        dualCaptureTextureId = GlUtils.createOESTexture()
        check(dualCaptureTextureId != 0) { "Cannot create dual-capture OES texture" }
        dualCaptureProgramId = GlUtils.linkProgram(
            GlUtils.compileShader(GLES30.GL_VERTEX_SHADER, Shaders.VERTEX_SHADER),
            GlUtils.compileShader(GLES30.GL_FRAGMENT_SHADER, Shaders.FRAGMENT_SHADER_DUAL_CAPTURE),
        )
        check(dualCaptureProgramId != 0) { "Cannot create dual-capture GL program" }
        dualCaptureSurfaceTexture = SurfaceTexture(dualCaptureTextureId).apply {
            setDefaultBufferSize(size.width, size.height)
            setOnFrameAvailableListener { dualCaptureFramePending.set(true) }
        }
        dualCaptureSurface = Surface(dualCaptureSurfaceTexture)
        PLog.i(TAG, "Dual capture encoder input ready: ${size.width}x${size.height}")
    }

    private fun resolvePrimaryViewport(): IntArray? = when (currentDualCaptureLayout.takeIf { dualCaptureSurface != null }) {
        DualCaptureLayout.TOP_BOTTOM -> intArrayOf(
            0, encoderOutputSize.height / 2, encoderOutputSize.width,
            encoderOutputSize.height - encoderOutputSize.height / 2,
        )
        DualCaptureLayout.SIDE_BY_SIDE -> intArrayOf(
            0, 0, encoderOutputSize.width / 2, encoderOutputSize.height,
        )
        else -> null
    }

    /**
     * Resolves the inset in the same display-oriented coordinate space as the Compose
     * viewfinder, then maps it back into the unrotated encoder buffer. MediaMuxer applies
     * the orientation hint when the saved video is displayed.
     */
    private fun resolvePictureInPictureViewport(
        outputWidth: Int,
        outputHeight: Int,
    ): IntArray {
        val rotation = ((encoderOrientationHintDegrees % 360) + 360) % 360
        val swapsAxes = rotation == 90 || rotation == 270
        val displayWidth = if (swapsAxes) outputHeight else outputWidth
        val displayHeight = if (swapsAxes) outputWidth else outputHeight

        // Match the live PiP: 33% of viewfinder width, with a 3:4 window aspect ratio.
        val width = (displayWidth * currentDualCapturePipWidthFraction)
            .toInt().coerceAtLeast(1)
        val height = (width / (3f / 4f)).toInt().coerceAtLeast(1)
        val displayLeft = (currentDualCapturePositionX * displayWidth)
            .toInt().coerceIn(0, (displayWidth - width).coerceAtLeast(0))
        val displayTop = (currentDualCapturePositionY * displayHeight)
            .toInt().coerceIn(0, (displayHeight - height).coerceAtLeast(0))

        val sourceRect = when (rotation) {
            90 -> intArrayOf(
                displayTop,
                outputHeight - displayLeft - width,
                height,
                width,
            )
            180 -> intArrayOf(
                outputWidth - displayLeft - width,
                outputHeight - displayTop - height,
                width,
                height,
            )
            270 -> intArrayOf(
                outputWidth - displayTop - height,
                displayLeft,
                height,
                width,
            )
            else -> intArrayOf(displayLeft, displayTop, width, height)
        }
        val sourceLeft = sourceRect[0]
        val sourceTop = sourceRect[1]
        val sourceWidth = sourceRect[2]
        val sourceHeight = sourceRect[3]
        val sourceBottom = outputHeight - sourceTop - sourceHeight
        return intArrayOf(sourceLeft, sourceBottom, sourceWidth, sourceHeight)
    }

    private fun acquireLatestDualCaptureFrame(): Boolean {
        val surfaceTexture = dualCaptureSurfaceTexture ?: return false
        if (!dualCaptureFramePending.getAndSet(false)) return false
        return try {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(dualCaptureStMatrix)
            dualCaptureHasFrame = true
            true
        } catch (error: RuntimeException) {
            PLog.e(TAG, "Failed to acquire dual-capture frame", error)
            false
        }
    }

    private fun resolveDualCapturePresentationMatrix(sourceSize: Size): FloatArray {
        System.arraycopy(
            dualCaptureStMatrix,
            0,
            dualCapturePresentationMatrix,
            0,
            dualCapturePresentationMatrix.size,
        )
        val needsSensorRotation = shouldApplyDualCaptureSensorOrientationFallback(
            inputWidth = sourceSize.width,
            inputHeight = sourceSize.height,
            sensorOrientationDegrees = currentDualCaptureSensorOrientationDegrees,
            textureTransformMatrix = dualCaptureStMatrix,
        )
        if (!needsSensorRotation) return dualCapturePresentationMatrix
        val sensorRotation = resolveMgcEisPresentationMatrix(
            currentDualCaptureSensorOrientationDegrees,
        ) ?: return dualCapturePresentationMatrix
        Matrix.multiplyMM(
            dualCapturePresentationMatrix,
            0,
            dualCaptureStMatrix,
            0,
            sensorRotation,
            0,
        )
        return dualCapturePresentationMatrix
    }

    private fun drawDualCaptureOverlay() {
        if (dualCaptureSurfaceTexture == null || dualCaptureProgramId == 0) return
        acquireLatestDualCaptureFrame()
        if (!dualCaptureHasFrame) return
        val sourceSize = currentDualCaptureInputSize ?: return
        val presentationMatrix = resolveDualCapturePresentationMatrix(sourceSize)

        val outputWidth = encoderOutputSize.width
        val outputHeight = encoderOutputSize.height
        val viewport = when (currentDualCaptureLayout) {
            DualCaptureLayout.TOP_BOTTOM -> intArrayOf(
                0, 0, outputWidth, outputHeight / 2,
            )
            DualCaptureLayout.SIDE_BY_SIDE -> intArrayOf(
                outputWidth / 2, 0, outputWidth - outputWidth / 2, outputHeight,
            )
            DualCaptureLayout.PICTURE_IN_PICTURE -> resolvePictureInPictureViewport(
                outputWidth = outputWidth,
                outputHeight = outputHeight,
            )
        }
        GLES30.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        GLES30.glUseProgram(dualCaptureProgramId)
        val isPictureInPicture = currentDualCaptureLayout == DualCaptureLayout.PICTURE_IN_PICTURE
        val density = appContext.resources.displayMetrics.density
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(dualCaptureProgramId, "uShapeSize"),
            viewport[2].toFloat(),
            viewport[3].toFloat(),
        )
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(dualCaptureProgramId, "uCornerRadiusPx"),
            if (isPictureInPicture) minOf(16f * density, minOf(viewport[2], viewport[3]) * 0.5f) else 0f,
        )
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(dualCaptureProgramId, "uBorderWidthPx"),
            if (isPictureInPicture) 1f * density else 0f,
        )
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, dualCaptureTextureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(dualCaptureProgramId, "uCameraTexture"), 0)
        GLES30.glUniformMatrix4fv(
            GLES30.glGetUniformLocation(dualCaptureProgramId, "uMVPMatrix"),
            1, false, dualCaptureMvpMatrix, 0,
        )
        GLES30.glUniformMatrix4fv(
            GLES30.glGetUniformLocation(dualCaptureProgramId, "uSTMatrix"),
            1, false, presentationMatrix, 0,
        )
        val crop = resolveOutputQuadCropRect(
            presentationMatrix = presentationMatrix,
            inputWidth = sourceSize.width,
            inputHeight = sourceSize.height,
            targetWidth = viewport[2],
            targetHeight = viewport[3],
        )
        GLES30.glUniform4f(
            GLES30.glGetUniformLocation(dualCaptureProgramId, "uCropRect"),
            crop[0], crop[1], crop[2], crop[3],
        )
        drawSimpleQuad(dualCaptureProgramId)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    private fun initializeWatermarkOverlay() {
        val label = watermarkLabel?.takeIf { it.isNotBlank() } ?: return
        var vertexShader = 0
        var fragmentShader = 0
        var badge: android.graphics.Bitmap? = null
        try {
            vertexShader = GlUtils.compileShader(GLES30.GL_VERTEX_SHADER, WATERMARK_VERTEX_SHADER)
            fragmentShader = GlUtils.compileShader(GLES30.GL_FRAGMENT_SHADER, WATERMARK_FRAGMENT_SHADER)
            if (vertexShader == 0 || fragmentShader == 0) return
            watermarkProgramId = GlUtils.linkProgram(vertexShader, fragmentShader)
            if (watermarkProgramId == 0) return

            val overlayBitmap = MediaWatermark.createOverlayBitmap(label)
            badge = overlayBitmap
            val textures = IntArray(1)
            GLES30.glGenTextures(1, textures, 0)
            watermarkTextureId = textures[0]
            if (watermarkTextureId == 0) return
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, watermarkTextureId)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, overlayBitmap, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        } catch (_: Exception) {
            PLog.w(TAG, "Watermark overlay unavailable; continuing video without watermark")
            if (watermarkTextureId != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(watermarkTextureId), 0)
                watermarkTextureId = 0
            }
            if (watermarkProgramId != 0) {
                GLES30.glDeleteProgram(watermarkProgramId)
                watermarkProgramId = 0
            }
        } finally {
            if (vertexShader != 0) GLES30.glDeleteShader(vertexShader)
            if (fragmentShader != 0) GLES30.glDeleteShader(fragmentShader)
            badge?.takeIf { !it.isRecycled }?.recycle()
        }
    }

    private fun drawWatermarkOverlay() {
        if (watermarkProgramId == 0 || watermarkTextureId == 0) return
        val outputWidth = encoderOutputSize.width
        val outputHeight = encoderOutputSize.height
        val rotation = ((encoderOrientationHintDegrees % 360) + 360) % 360
        val swapsAxes = rotation == 90 || rotation == 270
        val displayWidth = if (swapsAxes) outputHeight else outputWidth
        val displayHeight = if (swapsAxes) outputWidth else outputHeight
        val badgeWidthPx = (displayWidth * 0.22f).coerceAtLeast(1f)
        val badgeHeightPx = badgeWidthPx * (MediaWatermark.BADGE_HEIGHT.toFloat() / MediaWatermark.BADGE_WIDTH)
        val marginX = displayWidth * 0.028f
        val marginY = displayHeight * 0.028f
        val displayCenterX = 1f - 2f * (marginX + badgeWidthPx * 0.5f) / displayWidth
        val displayCenterY = -1f + 2f * (marginY + badgeHeightPx * 0.5f) / displayHeight
        val radians = Math.toRadians(rotation.toDouble())
        val cos = kotlin.math.cos(radians).toFloat()
        val sin = kotlin.math.sin(radians).toFloat()
        val centerX = cos * displayCenterX - sin * displayCenterY
        val centerY = sin * displayCenterX + cos * displayCenterY
        val mvp = FloatArray(16).also {
            Matrix.setIdentityM(it, 0)
            Matrix.translateM(it, 0, centerX, centerY, 0f)
            Matrix.rotateM(it, 0, rotation.toFloat(), 0f, 0f, 1f)
            Matrix.scaleM(it, 0, badgeWidthPx / displayWidth, badgeHeightPx / displayHeight, 1f)
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, outputWidth, outputHeight)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUseProgram(watermarkProgramId)
        GLES30.glUniformMatrix4fv(
            GLES30.glGetUniformLocation(watermarkProgramId, "uMVPMatrix"),
            1, false, mvp, 0,
        )
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, watermarkTextureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(watermarkProgramId, "uTexture"), 0)
        drawSimpleQuad(watermarkProgramId)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glUseProgram(0)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun acquireLatestSwitchableCameraFrame(): Boolean {
        val surfaceTexture = switchableCameraSurfaceTexture ?: return false
        return try {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(switchableCameraStMatrix)
            switchableCameraHasFrame = true
            true
        } catch (error: RuntimeException) {
            PLog.e(TAG, "Failed to acquire switchable ultra-wide recording frame", error)
            false
        }
    }

    private fun resolveSwitchableCameraPresentationMatrix(
        sourceMatrix: FloatArray,
        inputSize: Size,
    ): FloatArray {
        System.arraycopy(
            sourceMatrix,
            0,
            switchableCameraPresentationMatrix,
            0,
            switchableCameraPresentationMatrix.size,
        )
        val shouldApplySensorRotation = shouldApplySensorOrientationFallback(
            inputWidth = inputSize.width,
            inputHeight = inputSize.height,
            targetWidth = encoderOutputSize.width,
            targetHeight = encoderOutputSize.height,
            sensorOrientationDegrees = switchableCameraSensorOrientationDegrees,
            textureTransformMatrix = sourceMatrix,
        )
        if (!shouldApplySensorRotation) return switchableCameraPresentationMatrix
        val sensorRotation = resolveMgcEisPresentationMatrix(switchableCameraSensorOrientationDegrees)
            ?: return switchableCameraPresentationMatrix
        Matrix.multiplyMM(
            switchableCameraPresentationMatrix,
            0,
            sourceMatrix,
            0,
            sensorRotation,
            0,
        )
        return switchableCameraPresentationMatrix
    }

    private fun acquireLatestCameraFrame(surfaceTexture: SurfaceTexture): Boolean {
        return try {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(stMatrix)
            updateCameraTransformGeometry(
                if (mgcStabilizationEnabled) {
                    mgcEisPresentationTransform.matrix
                } else {
                    resolveCameraPresentationMatrix(stMatrix)
                },
            )
            true
        } catch (error: RuntimeException) {
            PLog.e(TAG, "Failed to acquire realtime video frame", error)
            false
        }
    }

    private fun renderMgcEisFrame(
        frame: com.hinnka.mycamera.stabilization.StabilizationFrame,
    ): MgcEisHardwareBufferRenderer.Frame? {
        val renderStartNs = System.nanoTime()
        try {
            val hardwareRendered = mgcEisHardwareBufferRenderer.render(frame)
            if (hardwareRendered != null) {
                diagnosticHardwareBufferFrames += 1L
                frame.image.close()
                return hardwareRendered
            }
            val yuvRendered = mgcEisYuvRenderer.render(frame)
            if (yuvRendered != null) {
                diagnosticYuvFallbackFrames += 1L
                return MgcEisHardwareBufferRenderer.Frame(
                    timestampNs = yuvRendered.timestampNs,
                    textureId = yuvRendered.textureId,
                    width = yuvRendered.width,
                    height = yuvRendered.height,
                )
            }
            diagnosticEisRenderFailures += 1L
            return null
        } finally {
            val durationNs = (System.nanoTime() - renderStartNs).coerceAtLeast(0L)
            diagnosticEisRenderTotalNs += durationNs
            diagnosticEisRenderMaxNs = maxOf(diagnosticEisRenderMaxNs, durationNs)
        }
    }

    private fun resolveCameraCropRect(
        presentationMatrix: FloatArray,
        inputWidth: Int,
        inputHeight: Int,
    ): FloatArray {
        return resolveOutputQuadCropRect(
            presentationMatrix = presentationMatrix,
            inputWidth = inputWidth,
            inputHeight = inputHeight,
            targetWidth = encoderOutputSize.width,
            targetHeight = encoderOutputSize.height,
        )
    }

    private fun resolveCameraPresentationMatrix(sourceMatrix: FloatArray): FloatArray {
        System.arraycopy(sourceMatrix, 0, cameraPresentationMatrix, 0, cameraPresentationMatrix.size)

        val shouldApplySensorRotation = shouldApplySensorOrientationFallback(
            inputWidth = currentCameraInputSize.width,
            inputHeight = currentCameraInputSize.height,
            targetWidth = encoderOutputSize.width,
            targetHeight = encoderOutputSize.height,
            sensorOrientationDegrees = currentCameraSensorOrientationDegrees,
            textureTransformMatrix = sourceMatrix,
        )
        if (!shouldApplySensorRotation) {
            cameraSensorOrientationFallbackApplied = false
            return cameraPresentationMatrix
        }

        val sensorRotation = resolveMgcEisPresentationMatrix(currentCameraSensorOrientationDegrees)
        if (sensorRotation == null) {
            cameraSensorOrientationFallbackApplied = false
            return cameraPresentationMatrix
        }

        // Some front sensors deliver the 8 MP 4:3 stream without a quarter-turn in the
        // SurfaceTexture matrix. Compose SENSOR_ORIENTATION only for this known geometry
        // and only when the producer has not already swapped axes, avoiding double rotation.
        Matrix.multiplyMM(
            cameraPresentationMatrix, 0,
            sourceMatrix, 0,
            sensorRotation, 0,
        )
        cameraSensorOrientationFallbackApplied = true
        return cameraPresentationMatrix
    }

    private fun updateCameraTransformGeometry(presentationMatrix: FloatArray) {
        val axesSwapped = isTextureTransformAxesSwapped(presentationMatrix)
        if (cameraTransformAxesSwapped == axesSwapped) return

        cameraTransformAxesSwapped = axesSwapped
        cameraCropRect = resolveCameraCropRect(
            presentationMatrix = presentationMatrix,
            inputWidth = currentCameraInputSize.width,
            inputHeight = currentCameraInputSize.height,
        )
        PLog.i(
            TAG,
            "Camera texture transform: axesSwapped=$axesSwapped, " +
                "effectiveInput=${if (axesSwapped) currentCameraInputSize.height else currentCameraInputSize.width}x" +
                "${if (axesSwapped) currentCameraInputSize.width else currentCameraInputSize.height}, " +
                "crop=${cameraCropRect.joinToString(prefix = "[", postfix = "]")}, " +
                "sensorOrientationFallback=$cameraSensorOrientationFallbackApplied, " +
                "matrix=${presentationMatrix.joinToString(prefix = "[", postfix = "]")}"
        )
    }

    /**
     * Apply gyro horizon correction to the actual MediaCodec render pass, not just preview.
     * Scale to cover the output bounds so the rotated frame never exposes black corners.
     */
    private fun resolveCameraInputRollMvpMatrix(
        baseMatrix: FloatArray,
        width: Int,
        height: Int,
    ): FloatArray {
        val angle = Math.toRadians(currentCameraInputRollDegrees.toDouble())
        if (kotlin.math.abs(angle) < 0.0001) return baseMatrix
        val aspect = (width.toFloat() / height.coerceAtLeast(1).toFloat()).coerceIn(0.1f, 10f)
        val cosine = kotlin.math.cos(angle).toFloat()
        val sine = kotlin.math.sin(angle).toFloat()
        val coverScale = maxOf(
            kotlin.math.abs(cosine) + kotlin.math.abs(sine) / aspect,
            kotlin.math.abs(sine) * aspect + kotlin.math.abs(cosine),
        )
        val rollMatrix = FloatArray(16)
        Matrix.setIdentityM(rollMatrix, 0)
        rollMatrix[0] = cosine * coverScale
        rollMatrix[1] = sine * aspect * coverScale
        rollMatrix[4] = -sine / aspect * coverScale
        rollMatrix[5] = cosine * coverScale
        val result = FloatArray(16)
        Matrix.multiplyMM(result, 0, baseMatrix, 0, rollMatrix, 0)
        return result
    }

    private fun drawLayer(
        layer: LayerResources,
        targetFboId: Int,
        textureSource: PreviewColorTextureSource,
        textureTarget: Int,
        textureId: Int,
        sourceStMatrix: FloatArray,
        cropRect: FloatArray,
        mvpMatrix: FloatArray,
        enableVideoLog: Boolean,
        presentationTimeNs: Long,
        viewport: IntArray? = null,
    ) {
        val lutEnabled = layer.lutConfig != null && layer.lutTextureId != 0
        val variant = PreviewColorShaderVariant.forPass(
            textureSource = textureSource,
            params = layer.params,
            lutConfig = layer.lutConfig,
            lutEnabled = lutEnabled,
            videoLogEnabled = enableVideoLog,
        )
        val locations = colorProgramCache.get(variant)
            ?: throw IllegalStateException("Cannot create realtime video color shader: $variant")

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFboId)
        if (viewport == null) {
            GLES30.glViewport(0, 0, encoderOutputSize.width, encoderOutputSize.height)
        } else {
            GLES30.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        }
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(locations.programId)
        colorProgramCache.bindLogInput(locations)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(textureTarget, textureId)
        GLES30.glUniform1i(locations.uCameraTextureLocation, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(
            GLES30.GL_TEXTURE_3D,
            if (lutEnabled) layer.lutTextureId else 0,
        )
        GLES30.glUniform1i(locations.uLutTextureLocation, 1)

        GLES30.glUniformMatrix4fv(locations.uMVPMatrixLocation, 1, false, mvpMatrix, 0)
        GLES30.glUniformMatrix4fv(locations.uSTMatrixLocation, 1, false, sourceStMatrix, 0)
        GLES30.glUniform4f(
            locations.uCropRectLocation,
            cropRect[0],
            cropRect[1],
            cropRect[2],
            cropRect[3],
        )
        GLES30.glUniform1f(
            locations.uLutSizeLocation,
            layer.lutConfig?.size?.toFloat() ?: 1f,
        )
        GLES30.glUniform1f(locations.uLutIntensityLocation, layer.params.lutIntensity)
        GLES30.glUniform1i(locations.uLutEnabledLocation, if (lutEnabled) 1 else 0)
        GLES30.glUniform1i(locations.uLutMaskTypeLocation, 0)
        GLES30.glUniform1i(
            locations.uLutCurveLocation,
            LutShaderMappings.transferCurveId(layer.lutConfig?.curve),
        )
        GLES30.glUniform1i(
            locations.uLutColorSpaceLocation,
            LutShaderMappings.colorSpaceId(
                layer.lutConfig?.colorSpace ?: com.hinnka.mycamera.raw.ColorSpace.SRGB
            ),
        )
        GLES30.glUniform1i(
            locations.uVideoLogEnabledLocation,
            if (enableVideoLog && videoLogProfile.isEnabled) 1 else 0,
        )
        GLES30.glUniform1i(
            locations.uVideoLogCurveLocation,
            LutShaderMappings.transferCurveId(videoLogProfile.logCurve),
        )
        GLES30.glUniform1i(
            locations.uVideoColorSpaceLocation,
            LutShaderMappings.colorSpaceId(videoLogProfile.colorSpace),
        )
        bindRecipeUniforms(
            locations = locations,
            params = layer.params,
            presentationTimeNs = presentationTimeNs,
        )

        val curveEnabled = layer.curveTextureId != 0
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(
            GLES30.GL_TEXTURE_2D,
            if (curveEnabled) layer.curveTextureId else 0,
        )
        GLES30.glUniform1i(locations.uCurveTextureLocation, 2)
        GLES30.glUniform1i(locations.uCurveEnabledLocation, if (curveEnabled) 1 else 0)

        basicToneTextures.bind(
            context = appContext,
            textureUnit = 3,
            samplerLocation = locations.uBasicToneLutLocation,
            intensityLocation = locations.uBasicToneIntensityLocation,
            amount = layer.params.tonality,
        )

        GLES30.glUniform2f(
            locations.uTexelSizeLocation,
            1f / max(1, encoderOutputSize.width).toFloat(),
            1f / max(1, encoderOutputSize.height).toFloat(),
        )

        val positions = vertexBuffer ?: return
        val textureCoordinates = textureCoordinateBuffer ?: return
        val indices = indexBuffer ?: return
        positions.position(0)
        textureCoordinates.position(0)
        indices.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        GLES30.glEnableVertexAttribArray(locations.aPositionLocation)
        GLES30.glVertexAttribPointer(
            locations.aPositionLocation, 2, GLES30.GL_FLOAT, false, 0, positions,
        )
        GLES30.glEnableVertexAttribArray(locations.aTexCoordLocation)
        GLES30.glVertexAttribPointer(
            locations.aTexCoordLocation, 2, GLES30.GL_FLOAT, false, 0, textureCoordinates,
        )
        GLES30.glDrawElements(
            GLES30.GL_TRIANGLES,
            Shaders.DRAW_ORDER.size,
            GLES30.GL_UNSIGNED_SHORT,
            indices,
        )
        GLES30.glDisableVertexAttribArray(locations.aPositionLocation)
        GLES30.glDisableVertexAttribArray(locations.aTexCoordLocation)
        GlUtils.checkGlError("RealtimeVideoRenderer.drawLayer")
    }

    private fun bindRecipeUniforms(
        locations: ColorPassLocations,
        params: ColorRecipeParams,
        presentationTimeNs: Long,
    ) {
        val enabled = !params.isDefault()
        GLES30.glUniform1i(locations.uColorRecipeEnabledLocation, if (enabled) 1 else 0)
        // 低像素和色散采样位于 shader 的 recipeEnabled 分支之前，必须每个 pass
        // 都显式赋值，避免复用同一 program 时继承上一层的 uniform。
        GLES30.glUniform1f(locations.uChromaticAberrationLocation, params.chromaticAberration)
        GLES30.glUniform1f(locations.uLowResLocation, params.lowRes)
        GLES30.glUniform1f(
            locations.uAspectRatioLocation,
            encoderOutputSize.width.toFloat() / max(1, encoderOutputSize.height).toFloat(),
        )
        if (!enabled) return

        GLES30.glUniform1f(locations.uExposureLocation, params.exposure)
        GLES30.glUniform1f(locations.uContrastLocation, params.contrast)
        GLES30.glUniform1f(locations.uSaturationLocation, params.saturation)
        GLES30.glUniform1f(locations.uTemperatureLocation, params.temperature)
        GLES30.glUniform1f(locations.uTintLocation, params.tint)
        GLES30.glUniform1f(locations.uFadeLocation, params.fade)
        GLES30.glUniform1f(locations.uVibranceLocation, params.color)
        ShadowsHighlightsShader.bindUniformLocations(
            highlightsLocation = locations.uHighlightsLocation,
            shadowsLocation = locations.uShadowsLocation,
            highlights = params.highlights,
            shadows = params.shadows,
        )
        GLES30.glUniform1f(locations.uSharpeningLocation, params.sharpness.coerceIn(-1f, 1f))
        GLES30.glUniform1f(locations.uFilmGrainLocation, params.filmGrain)
        GLES30.glUniform1f(
            locations.uFilmGrainSeedLocation,
            FilmGrainShaders.frameSeed(presentationTimeNs),
        )
        GLES30.glUniform1f(
            locations.uFilmGrainPixelScaleLocation,
            FilmGrainShaders.pixelScale(encoderOutputSize.width, encoderOutputSize.height),
        )
        GLES30.glUniform1f(locations.uVignetteLocation, params.vignette)
        GLES30.glUniform1f(locations.uFlashLocation, params.flash)
        GLES30.glUniform1f(locations.uBleachBypassLocation, params.bleachBypass)
        GLES30.glUniform1f(locations.uNoiseLocation, params.noise)
        GLES30.glUniform1f(
            locations.uNoiseSeedLocation,
            (presentationTimeNs % 10_000_000_000L) / 1_000_000_000f,
        )
        GLES30.glUniform3f(
            locations.uGradingHuesLocation,
            params.gradingShadowHue,
            params.gradingMidtoneHue,
            params.gradingHighlightHue,
        )
        GLES30.glUniform3f(
            locations.uGradingAmountsLocation,
            params.gradingShadowAmount,
            params.gradingMidtoneAmount,
            params.gradingHighlightAmount,
        )
        GLES30.glUniform3f(
            locations.uGradingLuminancesLocation,
            params.gradingShadowLuminance,
            params.gradingMidtoneLuminance,
            params.gradingHighlightLuminance,
        )
        GLES30.glUniform1f(locations.uGradingBalanceLocation, params.gradingBalance)
        GLES30.glUniform1f(locations.uGradingBlendingLocation, params.gradingBlending)
        ColorRecipeGl.bindLchAdjustments(
            locations.uLchHueAdjustmentsLocation,
            locations.uLchChromaAdjustmentsLocation,
            locations.uLchLightnessAdjustmentsLocation,
            ColorRecipeGl.lchAdjustments(params),
        )
        GLES30.glUniformMatrix3fv(
            locations.uPrimaryCalibrationMatrixLocation,
            1,
            false,
            CameraRawCalibrationMatrix.build(params),
            0,
        )
    }

    private fun initializeEgl(encoderSurface: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "Cannot obtain EGL display" }
        val major = IntArray(1)
        val minor = IntArray(1)
        check(EGL14.eglInitialize(eglDisplay, major, 0, minor, 0)) {
            "Cannot initialize EGL display"
        }
        val candidates = buildList {
            if (encoderColorConfig.prefer10BitInputSurface) {
                chooseConfig(red = 10, green = 10, blue = 10, alpha = 2)
                    ?.let { add("RGB10_A2" to it) }
            }
            if (!encoderColorConfig.require10BitInputSurface) {
                chooseConfig(red = 8, green = 8, blue = 8, alpha = 8)
                    ?.let { add("RGBA8888" to it) }
            }
        }
        check(candidates.isNotEmpty()) { "Cannot choose recordable EGL config" }

        var lastError = EGL14.EGL_SUCCESS
        candidates.forEach { (label, config) ->
            eglContext = EGL14.eglCreateContext(
                eglDisplay,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
                0,
            )
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                lastError = EGL14.eglGetError()
                PLog.w(TAG, "$label realtime EGL context unavailable: 0x${Integer.toHexString(lastError)}")
                return@forEach
            }

            encoderEglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay,
                config,
                encoderSurface,
                intArrayOf(EGL14.EGL_NONE),
                0,
            )
            if (encoderEglSurface != EGL14.EGL_NO_SURFACE && makeCurrent()) {
                if (encoderColorConfig.prefer10BitInputSurface && label != "RGB10_A2") {
                    PLog.w(
                        TAG,
                        "10-bit encoder EGL surface unavailable; using RGBA8888 for " +
                            "${encoderColorConfig.debugName}"
                    )
                } else {
                    PLog.i(TAG, "Using $label recordable EGL surface for ${encoderColorConfig.debugName}")
                }
                return
            }

            lastError = EGL14.eglGetError()
            PLog.w(TAG, "$label encoder EGL surface unavailable: 0x${Integer.toHexString(lastError)}")
            if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, encoderEglSurface)
                encoderEglSurface = EGL14.EGL_NO_SURFACE
            }
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            eglContext = EGL14.EGL_NO_CONTEXT
        }

        if (encoderColorConfig.prefer10BitInputSurface) {
            PLog.w(TAG, "10-bit encoder EGL surface unavailable; the Log path cannot preserve 10-bit precision")
        }
        throw IllegalStateException(
            "Cannot create encoder EGL surface: 0x${Integer.toHexString(lastError)}"
        )
    }

    private fun chooseConfig(red: Int, green: Int, blue: Int, alpha: Int): EGLConfig? {
        return chooseConfig(red, green, blue, alpha, EGL_OPENGL_ES3_BIT_KHR)
            ?: chooseConfig(red, green, blue, alpha, EGL14.EGL_OPENGL_ES2_BIT)
    }

    private fun chooseConfig(
        red: Int,
        green: Int,
        blue: Int,
        alpha: Int,
        renderableType: Int,
    ): EGLConfig? {
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, red,
            EGL14.EGL_GREEN_SIZE, green,
            EGL14.EGL_BLUE_SIZE, blue,
            EGL14.EGL_ALPHA_SIZE, alpha,
            EGL14.EGL_RENDERABLE_TYPE, renderableType,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val success = EGL14.eglChooseConfig(
            eglDisplay,
            attributes,
            0,
            configs,
            0,
            configs.size,
            count,
            0,
        )
        return if (success && count[0] > 0) configs[0] else null
    }

    private fun makeCurrent(): Boolean {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY ||
            eglContext == EGL14.EGL_NO_CONTEXT ||
            encoderEglSurface == EGL14.EGL_NO_SURFACE
        ) {
            return false
        }
        return EGL14.eglMakeCurrent(
            eglDisplay,
            encoderEglSurface,
            encoderEglSurface,
            eglContext,
        )
    }

    private fun initializeGeometry() {
        vertexBuffer = ByteBuffer.allocateDirect(Shaders.FULL_QUAD_VERTICES.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(Shaders.FULL_QUAD_VERTICES)
                position(0)
            }
        textureCoordinateBuffer =
            ByteBuffer.allocateDirect(Shaders.TEXTURE_COORDS.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(Shaders.TEXTURE_COORDS)
                    position(0)
                }
        indexBuffer = ByteBuffer.allocateDirect(Shaders.DRAW_ORDER.size * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .apply {
                put(Shaders.DRAW_ORDER)
                position(0)
            }
    }

    private fun initializeLayerTextures() {
        layers.forEach { layer ->
            layer.lutConfig?.let { config ->
                layer.lutTextureId = GlUtils.create3DTexture(config)
                check(layer.lutTextureId != 0) {
                    "Cannot upload realtime video LUT ${config.title}"
                }
            }
            val params = layer.params
            val hasCurve = params.masterCurvePoints != null ||
                params.redCurvePoints != null ||
                params.greenCurvePoints != null ||
                params.blueCurvePoints != null
            if (hasCurve) {
                layer.curveTextureId = ColorRecipeGl.ensureCurveTextureUploaded(
                    textureId = 0,
                    pendingBuffer = CurveUtils.buildCurveTextureBuffer(
                        params.masterCurvePoints,
                        params.redCurvePoints,
                        params.greenCurvePoints,
                        params.blueCurvePoints,
                    ),
                )
                check(layer.curveTextureId != 0) {
                    "Cannot upload realtime video curve texture"
                }
            }
        }
    }

    private fun initializeIntermediateFramebuffer() {
        val target = createColorFramebuffer("intermediate")
        intermediateTextureId = target.first
        intermediateFboId = target.second
    }

    private fun createColorFramebuffer(label: String): Pair<Int, Int> {
        val maxTextureSize = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0)
        check(
            encoderOutputSize.width in 1..maxTextureSize[0] &&
                encoderOutputSize.height in 1..maxTextureSize[0]
        ) {
            "Realtime video $label target ${encoderOutputSize.width}x${encoderOutputSize.height} " +
                "exceeds GL_MAX_TEXTURE_SIZE=${maxTextureSize[0]}"
        }
        check(GLES30.glGetError() == GLES30.GL_NO_ERROR) {
            "Realtime video $label framebuffer has an upstream GL error"
        }

        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        val textureId = textures[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA8,
            encoderOutputSize.width,
            encoderOutputSize.height,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            null,
        )

        val framebuffers = IntArray(1)
        GLES30.glGenFramebuffers(1, framebuffers, 0)
        val framebufferId = framebuffers[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebufferId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            textureId,
            0,
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        val glError = GLES30.glGetError()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE || glError != GLES30.GL_NO_ERROR) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(framebufferId), 0)
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            error(
                "Realtime video $label framebuffer is incomplete: status=$status glError=$glError"
            )
        }
        return textureId to framebufferId
    }

    private fun drawSimpleQuad(programId: Int) {
        val positions = vertexBuffer ?: return
        val textureCoordinates = textureCoordinateBuffer ?: return
        val indices = indexBuffer ?: return
        val positionLocation = GLES30.glGetAttribLocation(programId, "aPosition")
        val textureLocation = GLES30.glGetAttribLocation(programId, "aTexCoord")
        positions.position(0)
        textureCoordinates.position(0)
        indices.position(0)
        GLES30.glEnableVertexAttribArray(positionLocation)
        GLES30.glVertexAttribPointer(positionLocation, 2, GLES30.GL_FLOAT, false, 0, positions)
        GLES30.glEnableVertexAttribArray(textureLocation)
        GLES30.glVertexAttribPointer(textureLocation, 2, GLES30.GL_FLOAT, false, 0, textureCoordinates)
        GLES30.glDrawElements(
            GLES30.GL_TRIANGLES,
            Shaders.DRAW_ORDER.size,
            GLES30.GL_UNSIGNED_SHORT,
            indices,
        )
        GLES30.glDisableVertexAttribArray(positionLocation)
        GLES30.glDisableVertexAttribArray(textureLocation)
    }

    /**
     * Runtime diagnostics for paired video recording stop. The requested setting alone does not
     * prove that the shared MGC session stayed operational (the camera HAL can force it off).
     */
    @Synchronized
    fun stabilizationDiagnostics(): String {
        val session = stabilizationSession
        return "requested=$mgcStabilizationEnabled, positionLock=$positionLockEnabled, " +
            "started=$stabilizationSessionStarted, " +
            "operational=${session?.isOperational == true}, " +
            "poseReady=${session?.isPoseSourceReady == true}, " +
            "pendingResults=${session?.pendingResultCount() ?: 0}"
    }

    private fun markDiagnosticFrameSkip(reason: String) {
        diagnosticLastSkipReason = reason
        diagnosticSkipReasons[reason] = (diagnosticSkipReasons[reason] ?: 0L) + 1L
    }

    fun videoDiagnosticsSnapshot(): String {
        val eisAverageMs = if (diagnosticHardwareBufferFrames + diagnosticYuvFallbackFrames == 0L) {
            0.0
        } else {
            diagnosticEisRenderTotalNs.toDouble() /
                (diagnosticHardwareBufferFrames + diagnosticYuvFallbackFrames + diagnosticEisRenderFailures) /
                1_000_000.0
        }
        val swapAverageMs = if (diagnosticEglSwapFrames == 0L) 0.0 else
            diagnosticEglSwapTotalNs.toDouble() / diagnosticEglSwapFrames / 1_000_000.0
        return listOf(
            "gpuVendor=" + diagnosticGpuVendor,
            "gpuRenderer=" + diagnosticGpuRenderer,
            "gpuVersion=" + diagnosticGpuVersion,
            "eisHardwareBufferFrames=" + diagnosticHardwareBufferFrames,
            "eisYuvFallbackFrames=" + diagnosticYuvFallbackFrames,
            "eisRenderFailures=" + diagnosticEisRenderFailures,
            "eisImageRenderAverageMs=" + String.format(java.util.Locale.US, "%.3f", eisAverageMs),
            "eisImageRenderMaxMs=" + String.format(java.util.Locale.US, "%.3f", diagnosticEisRenderMaxNs / 1_000_000.0),
            "eglSwapFrames=" + diagnosticEglSwapFrames,
            "eglSwapAverageMs=" + String.format(java.util.Locale.US, "%.3f", swapAverageMs),
            "eglSwapMaxMs=" + String.format(java.util.Locale.US, "%.3f", diagnosticEglSwapMaxNs / 1_000_000.0),
            "lastFrameSkipReason=" + diagnosticLastSkipReason,
            "skipReasonCounts=" + diagnosticSkipReasons.entries.joinToString(",") {
                it.key + ":" + it.value
            },
            "stabilization=" + stabilizationDiagnostics(),
        ).joinToString("\n")
    }

    @Synchronized
    fun pauseStabilization() {
        stabilizationSession?.stop()
        stabilizationSessionStarted = false
    }

    @Synchronized
    fun resumeStabilization() {
        if (!mgcStabilizationEnabled || stabilizationSessionStarted) return
        stabilizationSessionStarted = stabilizationSession?.start() == true
    }

    /**
     * Camera2 closes and recreates its capture session during a physical lens switch. Restart the
     * EIS session against the new calibration so returning to a supported lens cannot leave the
     * encoder waiting forever on an inactive stabilization session.
     */
    @Synchronized
    fun restartStabilizationForCameraSwitch(sensorOrientationDegrees: Int) {
        if (!initialized || !mgcStabilizationEnabled) return

        // A selected physical camera may expose a different cardinal sensor orientation.
        // Reset only the presentation transform; keep the encoder and stabilization lifecycle
        // intact so the existing no-freeze fix is not undone.
        mgcEisPresentationTransform.reset()
        if (mgcEisPresentationTransform.captureFromSensorOrientation(sensorOrientationDegrees)) {
            cameraTransformAxesSwapped = null
            updateCameraTransformGeometry(mgcEisPresentationTransform.matrix)
        } else {
            PLog.w(TAG, "Cannot refresh EIS presentation orientation: $sensorOrientationDegrees")
        }

        val session = stabilizationSession ?: return
        session.stop()
        stabilizationSessionStarted = false
        stabilizationSessionStarted = session.start()
        PLog.i(
            TAG,
            "MGC EIS recording session refreshed after camera switch: " +
                "sensorOrientation=$sensorOrientationDegrees, " +
                "started=$stabilizationSessionStarted, " +
                "cameraSupported=${stabilizationCoordinator?.isCurrentCameraSupported}",
        )
    }

    fun release() {
        stabilizationSession?.stop()
        stabilizationSession = null
        stabilizationSessionStarted = false
        if (eglDisplay != EGL14.EGL_NO_DISPLAY &&
            eglContext != EGL14.EGL_NO_CONTEXT &&
            encoderEglSurface != EGL14.EGL_NO_SURFACE
        ) {
            makeCurrent()
        }
        cameraSurfaceTexture?.setOnFrameAvailableListener(null)
        cameraSurface?.release()
        cameraSurface = null
        cameraSurfaceTexture?.release()
        cameraSurfaceTexture = null
        switchableCameraSurfaceTexture?.setOnFrameAvailableListener(null)
        switchableCameraSurface?.release()
        switchableCameraSurface = null
        switchableCameraSurfaceTexture?.release()
        switchableCameraSurfaceTexture = null
        if (switchableCameraTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(switchableCameraTextureId), 0)
            switchableCameraTextureId = 0
        }
        dualCaptureSurfaceTexture?.setOnFrameAvailableListener(null)
        dualCaptureSurface?.release()
        dualCaptureSurface = null
        dualCaptureSurfaceTexture?.release()
        dualCaptureSurfaceTexture = null
        if (dualCaptureTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(dualCaptureTextureId), 0)
            dualCaptureTextureId = 0
        }
        if (dualCaptureProgramId != 0) {
            GLES30.glDeleteProgram(dualCaptureProgramId)
            dualCaptureProgramId = 0
        }

        layers.forEach { layer ->
            if (layer.lutTextureId != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(layer.lutTextureId), 0)
                layer.lutTextureId = 0
            }
            if (layer.curveTextureId != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(layer.curveTextureId), 0)
                layer.curveTextureId = 0
            }
        }
        if (watermarkTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(watermarkTextureId), 0)
            watermarkTextureId = 0
        }
        if (watermarkProgramId != 0) {
            GLES30.glDeleteProgram(watermarkProgramId)
            watermarkProgramId = 0
        }
        if (cameraTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(cameraTextureId), 0)
            cameraTextureId = 0
        }
        if (intermediateTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(intermediateTextureId), 0)
            intermediateTextureId = 0
        }
        if (intermediateFboId != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(intermediateFboId), 0)
            intermediateFboId = 0
        }
        if (finalColorTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(finalColorTextureId), 0)
            finalColorTextureId = 0
        }
        if (finalColorFboId != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(finalColorFboId), 0)
            finalColorFboId = 0
        }
        filmGrainGl.release()
        mgcEisHardwareBufferRenderer.release()
        mgcEisYuvRenderer.release()
        colorProgramCache.release()
        basicToneTextures.release()

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, encoderEglSurface)
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            }
        }
        encoderEglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
        initialized = false
    }
}

/**
 * Applies centered digital zoom to a resolved live camera-frame crop rectangle.
 * This is executed by the realtime encoder renderer before each frame is submitted to MediaCodec.
 */
internal fun resolveRealtimeVideoZoomCropRect(
    sourceCropRect: FloatArray,
    zoomRatio: Float,
    panX: Float = 0f,
    panY: Float = 0f,
): FloatArray {
    if (sourceCropRect.size < 4) return sourceCropRect
    val zoom = zoomRatio.takeIf { it.isFinite() }?.coerceIn(1f, 20f) ?: 1f
    if (zoom <= 1.001f) return sourceCropRect

    // The crop rect is already expressed in the oriented output-quad axes. Apply
    // pan in those same display axes so preview and encoded video move identically,
    // independent of SENSOR_ORIENTATION and the HAL's cropping type.
    val sourceLeft = sourceCropRect[0]
    val sourceTop = sourceCropRect[1]
    val sourceWidth = (sourceCropRect[2] - sourceLeft).coerceAtLeast(0f)
    val sourceHeight = (sourceCropRect[3] - sourceTop).coerceAtLeast(0f)
    if (sourceWidth <= 0f || sourceHeight <= 0f) return sourceCropRect

    val cropWidth = (sourceWidth / zoom).coerceAtLeast(0f)
    val cropHeight = (sourceHeight / zoom).coerceAtLeast(0f)
    val travelX = (sourceWidth - cropWidth).coerceAtLeast(0f)
    val travelY = (sourceHeight - cropHeight).coerceAtLeast(0f)
    val safePanX = panX.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
    val safePanY = panY.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
    val left = sourceLeft + travelX * (0.5f + 0.5f * safePanX)
    val top = sourceTop + travelY * (0.5f + 0.5f * safePanY)
    return floatArrayOf(
        left.coerceIn(sourceLeft, sourceCropRect[2] - cropWidth),
        top.coerceIn(sourceTop, sourceCropRect[3] - cropHeight),
        (left + cropWidth).coerceIn(sourceLeft, sourceCropRect[2]),
        (top + cropHeight).coerceIn(sourceTop, sourceCropRect[3]),
    )
}

/**
 * Resolves the center-crop rectangle in output-quad coordinates for the exact path used by
 * [RealtimeVideoRenderer]. Do not swap this rectangle's X/Y bounds afterward: the vertex shader
 * interpolates uCropRect across aTexCoord first and applies the SurfaceTexture matrix second.
 * Regression tests cover the rotated 8 MP 4:3 front-camera -> portrait 4K path.
 */
internal fun resolveOutputQuadCropRect(
    presentationMatrix: FloatArray,
    inputWidth: Int,
    inputHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
): FloatArray {
    val safeWidth = inputWidth.coerceAtLeast(1)
    val safeHeight = inputHeight.coerceAtLeast(1)
    val axesSwapped = isTextureTransformAxesSwapped(presentationMatrix)
    // Keep this geometry helper free of android.util.Size: local JVM tests run against
    // Android's mockable jar, whose Size constructor/accessors throw "not mocked".
    // The renderer gets the same oriented dimensions without allocating a framework type.
    val orientedInputWidth = if (axesSwapped) safeHeight else safeWidth
    val orientedInputHeight = if (axesSwapped) safeWidth else safeHeight

    // IMPORTANT: return this crop in the oriented output-quad axes. Never remap
    // floatArrayOf(left, top, right, bottom) by swapping X and Y; that is a regression
    // which makes rotated portrait video crop the orthogonal axis and appear stretched.
    return resolveCenterCropRect(orientedInputWidth, orientedInputHeight, targetWidth, targetHeight)
}

internal fun shouldApplyDualCaptureSensorOrientationFallback(
    inputWidth: Int,
    inputHeight: Int,
    sensorOrientationDegrees: Int,
    textureTransformMatrix: FloatArray,
): Boolean {
    return inputWidth > inputHeight &&
        sensorOrientationDegrees % 180 != 0 &&
        sensorOrientationDegrees % 90 == 0 &&
        !isTextureTransformAxesSwapped(textureTransformMatrix)
}

internal fun shouldApplySensorOrientationFallback(
    inputWidth: Int,
    inputHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
    sensorOrientationDegrees: Int,
    textureTransformMatrix: FloatArray,
): Boolean {
    if (
        inputWidth <= 0 ||
        inputHeight <= 0 ||
        targetWidth <= 0 ||
        targetHeight <= 0 ||
        inputWidth <= inputHeight ||
        targetWidth >= targetHeight ||
        sensorOrientationDegrees % 180 == 0 ||
        isTextureTransformAxesSwapped(textureTransformMatrix)
    ) {
        return false
    }

    val pixelCount = inputWidth.toLong() * inputHeight.toLong()
    val portraitAspect = minOf(inputWidth, inputHeight).toFloat() /
        maxOf(inputWidth, inputHeight).toFloat()
    return pixelCount >= 7_500_000L &&
        pixelCount < 12_000_000L &&
        abs(portraitAspect - (3f / 4f)) <= 0.04f
}

internal fun isTextureTransformAxesSwapped(matrix: FloatArray): Boolean {
    if (matrix.size < 6) return false
    val transformedXAxisFollowsY = abs(matrix[1]) > abs(matrix[0])
    val transformedYAxisFollowsX = abs(matrix[4]) > abs(matrix[5])
    return transformedXAxisFollowsY && transformedYAxisFollowsX
}

private fun resolveCenterCropRect(source: Size, target: Size): FloatArray =
    resolveCenterCropRect(source.width, source.height, target.width, target.height)

internal fun resolveCenterCropRect(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
): FloatArray {
    val sourceAspect = sourceWidth.coerceAtLeast(1).toFloat() /
        sourceHeight.coerceAtLeast(1).toFloat()
    val targetAspect = targetWidth.coerceAtLeast(1).toFloat() /
        targetHeight.coerceAtLeast(1).toFloat()
    return when {
        sourceAspect > targetAspect -> {
            val visibleWidth = targetAspect / sourceAspect
            val inset = (1f - visibleWidth) / 2f
            floatArrayOf(inset, 0f, 1f - inset, 1f)
        }
        sourceAspect < targetAspect -> {
            val visibleHeight = sourceAspect / targetAspect
            val inset = (1f - visibleHeight) / 2f
            floatArrayOf(0f, inset, 1f, 1f - inset)
        }
        else -> floatArrayOf(0f, 0f, 1f, 1f)
    }
}
