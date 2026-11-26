package com.hinnka.mycamera.ui.camera

import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.util.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import com.hinnka.mycamera.utils.PLog
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hinnka.mycamera.livephoto.LivePhotoRecorder
import com.hinnka.mycamera.camera.FocusPointSource
import com.hinnka.mycamera.camera.MeteringMode
import com.hinnka.mycamera.camera.AiSubjectTrackingTarget
import com.hinnka.mycamera.lut.LutConfig
import com.hinnka.mycamera.raw.RawEnginePreviewPlan
import kotlinx.coroutines.flow.StateFlow
import com.hinnka.mycamera.model.ColorRecipeParams
import com.hinnka.mycamera.preview.EyeFocusPreviewFrame
import com.hinnka.mycamera.stabilization.DEFAULT_VIDEO_STABILIZATION_LOOKAHEAD
import com.hinnka.mycamera.stabilization.RealtimeStabilizationCoordinator
import com.hinnka.mycamera.stabilization.StabilizationUseCase
import com.hinnka.mycamera.ui.components.FocusIndicator
import com.hinnka.mycamera.utils.OrientationObserver
import com.hinnka.mycamera.video.CaptureMode
import com.hinnka.mycamera.video.VideoLogProfile

/**
 * 相机预览组件 - OpenGL ES 版本（Camera2 适配）
 *
 * 使用 GLSurfaceView 渲染相机预览，支持实时 3D LUT 滤镜和色彩配方
 */
@Composable
fun CameraPreviewGL(
    aspectRatio: Float,
    previewSize: Size,
    captureSize: Size,
    captureMode: CaptureMode,
    modeSwitchPreviewAspectRatio: Float = 0f,
    cameraId: String,
    sensorOrientation: Int,
    lensFacing: Int,
    calibrationOffset: Int,
    baselineLut: LutConfig?,
    currentLut: LutConfig?,
    enginePreviewPlan: StateFlow<RawEnginePreviewPlan?>,
    baselineColorRecipeParams: ColorRecipeParams,
    colorRecipeParams: ColorRecipeParams,
    focusPoint: Pair<Float, Float>?,
    meteringPoint: Pair<Float, Float>? = null,
    canMoveMeteringPoint: Boolean = false,
    onMeteringPointMove: (Float, Float, Float) -> Unit = { _, _, _ -> },
    onFocusPointMove: (Float, Float, Float, Boolean) -> Unit = { _, _, _, _ -> },
    onMeteringPointMerge: () -> Unit = {},
    focusPointSource: FocusPointSource = FocusPointSource.MANUAL,
    isFocusLocked: Boolean = false,
    isFocusing: Boolean,
    focusSuccess: Boolean?,
    meteringMode: MeteringMode = MeteringMode.SYSTEM_DEFAULT,
    meteringFromSceneFrame: Boolean = false,
    onSurfaceTextureReady: (SurfaceTexture) -> Unit,
    onSurfaceDestroyed: (SurfaceTexture?) -> Unit,
    onTap: (Float, Float, Int, Int) -> Unit,
    onLongPress: (Float, Float, Int, Int) -> Unit,
    onHistogramUpdated: ((IntArray) -> Unit)? = null,
    onMeteringUpdated: ((Double, Double) -> Unit)? = null,
    onHighlightPointUpdated: ((Float, Float) -> Unit)? = null,
    onEyeFocusInputAvailable: ((EyeFocusPreviewFrame) -> Unit)? = null,
    onFirstPreviewFrame: (() -> Unit)? = null,
    livePhotoRecorder: LivePhotoRecorder? = null,
    videoLogProfile: VideoLogProfile = VideoLogProfile.OFF,
    isEyeFocusBusy: Boolean = false,
    onGLSurfaceViewReady: ((CameraGLSurfaceView) -> Unit)? = null,
    onPreviewBoundsChanged: (Rect) -> Unit = {},
    subjectTrackingTarget: AiSubjectTrackingTarget? = null,
    isAutoFocus: Boolean = true,
    focusPeakingEnabled: Boolean = true,
    stabilizationCoordinator: RealtimeStabilizationCoordinator? = null,
    stabilizationInputSize: Size = previewSize,
    photoPreviewStabilizationEnabled: Boolean = false,
    videoPreviewStabilizationEnabled: Boolean = false,
    videoPreviewStabilizationStrength: Float = 0f,
    videoPreviewStabilizationLookahead: Int = DEFAULT_VIDEO_STABILIZATION_LOOKAHEAD,
    modifier: Modifier = Modifier
) {
    val rotationDegrees = OrientationObserver.rotationDegrees
    val lifecycleOwner = LocalLifecycleOwner.current
    var glSurfaceViewRef by remember { mutableStateOf<CameraGLSurfaceView?>(null) }
    var resumeGeneration by remember { mutableIntStateOf(0) }
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnLongPress by rememberUpdatedState(onLongPress)

    // AWB/CCM changes update only the engine pass, not the entire Compose/LUT layer.
    LaunchedEffect(glSurfaceViewRef, enginePreviewPlan, resumeGeneration) {
        val view = glSurfaceViewRef ?: return@LaunchedEffect
        enginePreviewPlan.collect { view.setEnginePreview(it) }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> glSurfaceViewRef?.onPause()
                Lifecycle.Event.ON_RESUME -> {
                    glSurfaceViewRef?.onResume()
                    glSurfaceViewRef?.restoreRenderStateAfterResume()
                    resumeGeneration++
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Render into the full viewfinder slot. LutRenderer applies a center-crop
    // matrix using the real sensor and viewport dimensions; do not constrain this
    // surface to the sensor aspect ratio or horizontal gutters appear on phones.
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onGloballyPositioned { onPreviewBoundsChanged(it.boundsInRoot()) },
        contentAlignment = Alignment.Center
    ) {

        var viewWidth by remember { mutableIntStateOf(0) }
        var viewHeight by remember { mutableIntStateOf(0) }

        // 标记是否已经通知过 SurfaceTexture
        var surfaceTextureNotified by remember { mutableStateOf(false) }
        var notifiedPreviewSize by remember { mutableStateOf<Size?>(null) }
        var notifiedResumeGeneration by remember { mutableIntStateOf(-1) }
        var notifiedSurfaceTexture by remember { mutableStateOf<SurfaceTexture?>(null) }
        // 标记 Surface 是否已经准备好
        var surfaceAvailable by remember { mutableStateOf(false) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .onSizeChanged { size ->
                    viewWidth = size.width
                    viewHeight = size.height
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = { offset ->
                            currentOnLongPress(offset.x, offset.y, viewWidth, viewHeight)
                        },
                        onTap = { offset ->
                            currentOnTap(offset.x, offset.y, viewWidth, viewHeight)
                        }
                    )
                }
        ) {
            // Keep one GL/EGL surface for the lifetime of this preview slot. Buffer-size and
            // capture-mode changes are applied to the existing renderer in the update block.
            AndroidView(
                    factory = { ctx ->
                        CameraGLSurfaceView(ctx).apply {
                            glSurfaceViewRef = this
                            // 通知 GLSurfaceView 已准备好
                            onGLSurfaceViewReady?.invoke(this)
                        }
                    },
                    update = { glSurfaceView ->
                        // 更新闭包捕获的状态
                        glSurfaceView.onSurfaceReady = { _ ->
                            if (glSurfaceViewRef !== glSurfaceView) {
                                PLog.d("CameraPreviewGL", "Ignoring stale onSurfaceReady")
                            } else {
                                PLog.d("CameraPreviewGL", "onSurfaceReady called")
                                // SurfaceTexture 已经准备好，可以开始预览
                                surfaceAvailable = true
                                glSurfaceView.getSurfaceTexture()?.let { surfaceTexture ->
                                    glSurfaceView.setPreviewSize(previewSize.width, previewSize.height)
                                    glSurfaceView.setStabilizationInputSize(
                                        stabilizationInputSize.width,
                                        stabilizationInputSize.height,
                                    )
                                    PLog.d("CameraPreviewGL", "onSurfaceReady: notifiedST=$notifiedSurfaceTexture, newST=$surfaceTexture")
                                    // If the SurfaceTexture changed (e.g. Surface recreated due to layout bounds change), force notify
                                    if (notifiedSurfaceTexture != null && notifiedSurfaceTexture != surfaceTexture) {
                                        PLog.d("CameraPreviewGL", "onSurfaceReady: Forcing surfaceTextureNotified = false")
                                        surfaceTextureNotified = false
                                    }
                                    // 取消从这里回调，统一在 update 中处理
                                }
                            }
                        }

                        glSurfaceView.onSurfaceDestroyed = {
                            val destroyedSurfaceTexture = glSurfaceView.getSurfaceTexture()
                            if (glSurfaceViewRef === glSurfaceView) {
                                surfaceAvailable = false
                                surfaceTextureNotified = false
                                notifiedPreviewSize = null
                                notifiedResumeGeneration = -1
                                notifiedSurfaceTexture = null
                                glSurfaceViewRef = null
                                onSurfaceDestroyed(destroyedSurfaceTexture)
                            } else if (destroyedSurfaceTexture != null) {
                                onSurfaceDestroyed(destroyedSurfaceTexture)
                            }
                        }

                        glSurfaceView.onHistogramUpdated = { onHistogramUpdated?.invoke(it) }
                        glSurfaceView.onMeteringUpdated = { w, l -> onMeteringUpdated?.invoke(w, l) }
                        glSurfaceView.onHighlightPointUpdated = { hx, hy -> onHighlightPointUpdated?.invoke(hx, hy) }
                        glSurfaceView.onEyeFocusInputAvailable = onEyeFocusInputAvailable
                        glSurfaceView.onFirstPreviewFrame = {
                            if (glSurfaceViewRef === glSurfaceView) {
                                onFirstPreviewFrame?.invoke()
                            }
                        }

                        viewWidth = glSurfaceView.width
                        viewHeight = glSurfaceView.height
                        glSurfaceView.setPreviewCameraConfiguration(
                            cameraId = cameraId,
                            sensorOrientation = sensorOrientation,
                            lensFacing = lensFacing,
                        )
                        glSurfaceView.setDeviceRotation(rotationDegrees.toInt())
                        glSurfaceView.setCalibrationOffset(calibrationOffset)
                        glSurfaceView.setCaptureAspectRatio(aspectRatio)
                        glSurfaceView.setModeSwitchPreviewAspectRatio(modeSwitchPreviewAspectRatio)
                        glSurfaceView.setStabilizationInputSize(
                            stabilizationInputSize.width,
                            stabilizationInputSize.height,
                        )
                        glSurfaceView.setStabilizationCoordinator(stabilizationCoordinator)
                        val previewStabilizationUseCase = when {
                            captureMode == CaptureMode.VIDEO &&
                                videoPreviewStabilizationEnabled -> StabilizationUseCase.VIDEO
                            captureMode == CaptureMode.PHOTO &&
                                photoPreviewStabilizationEnabled -> StabilizationUseCase.PHOTO_PREVIEW
                            else -> null
                        }
                        glSurfaceView.setPreviewStabilization(
                            useCase = previewStabilizationUseCase,
                            strength = if (previewStabilizationUseCase == StabilizationUseCase.VIDEO) {
                                videoPreviewStabilizationStrength
                            } else {
                                StabilizationUseCase.PHOTO_PREVIEW.defaultStrength
                            },
                            lookaheadFrames = if (previewStabilizationUseCase == StabilizationUseCase.VIDEO) {
                                videoPreviewStabilizationLookahead
                            } else {
                                DEFAULT_VIDEO_STABILIZATION_LOOKAHEAD
                            },
                        )

                        // 强制在所有的条件分支之外读取这几个 State，让 Compose 确保能监听到变化！
                        val currentSurfaceNotified = surfaceTextureNotified
                        val currentNotifiedST = notifiedSurfaceTexture
                        val currentNotifiedResumeGen = notifiedResumeGeneration
                        val currentNotifiedPreviewSize = notifiedPreviewSize
                        
                        if (viewWidth > 0 && viewHeight > 0 && surfaceAvailable) {
                            glSurfaceView.getSurfaceTexture()?.let { surfaceTexture ->
                                glSurfaceView.setPreviewSize(previewSize.width, previewSize.height)
                                glSurfaceView.setStabilizationInputSize(
                                    stabilizationInputSize.width,
                                    stabilizationInputSize.height,
                                )
                                glSurfaceView.setCaptureSize(captureSize.width, captureSize.height)
                                val shouldNotifySurfaceTexture =
                                    !currentSurfaceNotified ||
                                        currentNotifiedPreviewSize != previewSize ||
                                        currentNotifiedResumeGen != resumeGeneration ||
                                        currentNotifiedST != surfaceTexture
                                if (shouldNotifySurfaceTexture) {
                                    PLog.d("CameraPreviewGL", "shouldNotifySurfaceTexture is true, calling onSurfaceTextureReady")
                                    surfaceTextureNotified = true
                                    notifiedPreviewSize = previewSize
                                    notifiedResumeGeneration = resumeGeneration
                                    notifiedSurfaceTexture = surfaceTexture
                                    PLog.i(
                                        "CameraPreviewGL",
                                        "onSurfaceTextureReady: mode=$captureMode, " +
                                            "preview=${previewSize.width}x${previewSize.height}, " +
                                            "surface=${System.identityHashCode(surfaceTexture)}, " +
                                            "elapsedRealtimeMs=${SystemClock.elapsedRealtime()}"
                                    )
                                    onSurfaceTextureReady(surfaceTexture)
                                }
                            }
                        }
                        val colorRecipeEnabled = !colorRecipeParams.isDefault(includeWashedOutRecovery = true)
                        val baselineColorRecipeEnabled = !baselineColorRecipeParams.isDefault()
                        // 更新 LUT 设置
                        glSurfaceView.setEnginePreview(enginePreviewPlan.value)
                        glSurfaceView.setBaselineLut(baselineLut)
                        glSurfaceView.setBaselineLutEnabled(baselineLut != null)
                        glSurfaceView.setLut(currentLut)
                        glSurfaceView.setLutEnabled(currentLut != null)
                        glSurfaceView.setBaselineColorRecipeEnabled(baselineColorRecipeEnabled)
                        glSurfaceView.setColorRecipeEnabled(colorRecipeEnabled)

                        glSurfaceView.setBaselineParams(baselineColorRecipeParams)
                        glSurfaceView.setParams(colorRecipeParams)

                        glSurfaceView.setMeteringPoint((meteringPoint ?: focusPoint)?.takeIf {
                            focusPointSource == FocusPointSource.MANUAL
                        }?.let {
                            android.graphics.PointF(
                                it.first,
                                it.second
                            )
                        })
                        glSurfaceView.setMeteringMode(meteringMode)
                        glSurfaceView.setMeteringFromSceneFrame(meteringFromSceneFrame)
                        glSurfaceView.setLivePhotoRecorder(livePhotoRecorder)
                        glSurfaceView.setVideoLogProfile(videoLogProfile)
                        glSurfaceView.setAutoFocus(isAutoFocus)
                        glSurfaceView.setFocusPeakingEnabled(focusPeakingEnabled)
                        glSurfaceView.setEyeFocusBusy(isEyeFocusBusy)
                    },
                    modifier = Modifier.fillMaxSize()
                )

            // 对焦指示器
            FocusIndicator(
                position = focusPoint,
                meteringPosition = meteringPoint,
                canMoveMeteringPoint = canMoveMeteringPoint,
                onMeteringPointMove = onMeteringPointMove,
                onFocusPointMove = onFocusPointMove,
                onMeteringPointMerge = onMeteringPointMerge,
                source = focusPointSource,
                isFocusLocked = isFocusLocked,
                isFocusing = isFocusing,
                focusSuccess = focusSuccess,
                modifier = Modifier.fillMaxSize()
            )

            subjectTrackingTarget?.let { target ->
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val left = target.left.coerceIn(0f, 1f) * size.width
                    val top = target.top.coerceIn(0f, 1f) * size.height
                    val right = target.right.coerceIn(0f, 1f) * size.width
                    val bottom = target.bottom.coerceIn(0f, 1f) * size.height
                    val boxWidth = right - left
                    val boxHeight = bottom - top
                    if (boxWidth > 1f && boxHeight > 1f) {
                        val alpha = if (target.isFresh) 0.96f else 0.42f
                        val strokeWidth = 2.dp.toPx()
                        val cornerLength = minOf(18.dp.toPx(), boxWidth * 0.28f, boxHeight * 0.28f)
                        val color = Color(0xFF62E6A4).copy(alpha = alpha)
                        drawRect(
                            color = color,
                            topLeft = Offset(left, top),
                            size = ComposeSize(boxWidth, boxHeight),
                            style = Stroke(width = strokeWidth),
                        )
                        // Brighter corner brackets keep the subject box visible on busy scenes.
                        listOf(
                            Offset(left, top) to Offset(left + cornerLength, top),
                            Offset(left, top) to Offset(left, top + cornerLength),
                            Offset(right, top) to Offset(right - cornerLength, top),
                            Offset(right, top) to Offset(right, top + cornerLength),
                            Offset(left, bottom) to Offset(left + cornerLength, bottom),
                            Offset(left, bottom) to Offset(left, bottom - cornerLength),
                            Offset(right, bottom) to Offset(right - cornerLength, bottom),
                            Offset(right, bottom) to Offset(right, bottom - cornerLength),
                        ).forEach { (start, end) ->
                            drawLine(color = color, start = start, end = end, strokeWidth = strokeWidth * 1.8f)
                        }
                    }
                }
            }
        }
    }
}
