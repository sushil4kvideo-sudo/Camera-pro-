package com.hinnka.mycamera.ui.camera

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import android.view.TextureView
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.paint
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil.compose.AsyncImage
import com.styropyr0.prismal.PrismalBackdrop
import com.styropyr0.prismal.components.PrismalSegmentedControl
import com.styropyr0.prismal.sources.prismalGlassLayer
import com.styropyr0.prismal.sources.rememberPrismalGlassLayer
import com.hinnka.mycamera.ui.theme.AccentColor
import com.hinnka.mycamera.ui.theme.accentContentColor
import com.hinnka.mycamera.MyCameraApplication
import com.hinnka.mycamera.R
import com.hinnka.mycamera.camera.AspectRatio
import com.hinnka.mycamera.camera.CameraState
import com.hinnka.mycamera.camera.FocusPointSource
import com.hinnka.mycamera.camera.PhotoCaptureProgress
import com.hinnka.mycamera.data.CaptureButtonStyle
import com.hinnka.mycamera.data.DevelopAnimationStyle
import com.hinnka.mycamera.model.CameraPreset
import com.hinnka.mycamera.model.ColorRecipeParams
import com.hinnka.mycamera.model.LutSelectorMode
import com.hinnka.mycamera.raw.SpectralFilmSelection
import com.hinnka.mycamera.ui.components.*
import com.hinnka.mycamera.utils.OrientationObserver
import com.hinnka.mycamera.utils.PhotoCaptureCrashLogger
import com.hinnka.mycamera.viewmodel.CameraViewModel
import com.hinnka.mycamera.viewmodel.GalleryViewModel
import com.hinnka.mycamera.video.CaptureMode
import com.hinnka.mycamera.video.DualCaptureLayout
import com.hinnka.mycamera.video.DualCaptureSource
import com.hinnka.mycamera.video.DualVideoAspectRatio
import com.hinnka.mycamera.video.VideoAspectRatio
import com.hinnka.mycamera.video.VideoFpsPreset
import com.hinnka.mycamera.video.VideoLogProfile
import com.hinnka.mycamera.video.VideoResolutionPreset
import com.hinnka.mycamera.video.VideoStabilizationMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.*
import com.hinnka.mycamera.ui.icons.AppIcons

/**
 * 主相机界面
 */
enum class ActivePanel {
    NONE,
    SETTINGS,
    FILTERS,
    EDIT,
    PRESETS
}

private enum class CameraShootingMode {
    PROFESSIONAL,
    PHOTO,
    VIDEO
}

private const val PreviewTransitionRevealDurationMillis = 800
private const val RawCaptureTapDebounceMillis = 1000L
private const val DefaultShutterSpeedNs = 1_000_000_000f / 60f
private const val DefaultIso = 100f
private const val DefaultAwbTemperature = 5000f
private const val DefaultFocusDistance = 0f
private val CameraTopBarBaseTopPadding = 32.dp
private val CameraTopBarBaseHeight = 80.dp
private val VideoTopBarBaseHeight = 80.dp
private val VideoTopBarLoweredOffset = 36.dp
private val VideoViewfinderAdditionalOffset = 20.dp
private val VideoViewfinderVerticalOffset = 3.dp + VideoViewfinderAdditionalOffset
private val XpanViewfinderInnerBorder = 4.dp
private val XpanSideControlReservedWidth = 56.dp

private fun formatVirtualApertureFStop(aperture: Float): String {
    val rounded = aperture.roundToInt()
    return if (aperture == rounded.toFloat()) rounded.toString() else aperture.toString()
}

@Composable
private fun cameraTopSafePadding(): Dp {
    val density = LocalDensity.current
    val topInset = with(density) {
        maxOf(
            WindowInsets.statusBars.getTop(this).toDp(),
            WindowInsets.displayCutout.getTop(this).toDp()
        )
    }
    return (topInset - CameraTopBarBaseTopPadding).coerceAtLeast(0.dp)
}

private fun Bitmap.copyForCaptureAnimation(): Bitmap? {
    if (isRecycled) return null
    val copyConfig = if (config == Bitmap.Config.HARDWARE) {
        Bitmap.Config.ARGB_8888
    } else {
        config ?: Bitmap.Config.ARGB_8888
    }
    return copy(copyConfig, false) ?: copy(Bitmap.Config.ARGB_8888, false)
}

private fun Bitmap.recycleIfAlive() {
    if (!isRecycled) {
        recycle()
    }
}

@Composable
private fun PanelDismissPreviewOverlay(
    bounds: Rect?,
    parentBounds: Rect?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bounds = bounds ?: return
    if (bounds.width <= 0f || bounds.height <= 0f) return

    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val density = LocalDensity.current
    val parentLeft = parentBounds?.left ?: 0f
    val parentTop = parentBounds?.top ?: 0f
    val width = with(density) { bounds.width.toDp() }
    val height = with(density) { bounds.height.toDp() }

    Box(
        modifier = modifier
            .offset {
                IntOffset(
                    x = (bounds.left - parentLeft).roundToInt(),
                    y = (bounds.top - parentTop).roundToInt()
                )
            }
            .size(width = width, height = height)
            .pointerInput(Unit) {
                detectTapGestures {
                    currentOnDismiss()
                }
            }
    )
}

private fun CameraParameter.defaultResetValue(): Float {
    return when (this) {
        CameraParameter.EXPOSURE_COMPENSATION -> 0f
        CameraParameter.SHUTTER_SPEED -> DefaultShutterSpeedNs
        CameraParameter.ISO -> DefaultIso
        CameraParameter.FOCUS -> DefaultFocusDistance
        CameraParameter.WHITE_BALANCE -> DefaultAwbTemperature
    }
}

@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
    galleryViewModel: GalleryViewModel,
    onGalleryClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onFilterManagementClick: (String?) -> Unit,
    onFrameManagementClick: () -> Unit,
    onToolboxClick: () -> Unit,
    onPresetEditClick: (String?) -> Unit,
    onPresetManagementClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsState()
    val pendingPhotoCrashReport by PhotoCaptureCrashLogger.pendingCrashReport.collectAsState()
    pendingPhotoCrashReport?.let { report ->
        PhotoCaptureCrashDialog(
            report = report,
            onDismiss = { PhotoCaptureCrashLogger.dismissPendingReport() },
        )
    }
    var dualCaptureEnabled by remember { mutableStateOf(false) }
    val spatialVideoEnabled by viewModel.spatialVideoEnabled.collectAsState()
    val spatialVideoShowUltraWidePreview by viewModel.spatialVideoShowUltraWidePreview.collectAsState()
    val spatialVideoPreviewStreamSize by viewModel.spatialVideoPreviewStreamSize.collectAsState()
    val spatialVideoPreviewSensorOrientation by
        viewModel.spatialVideoPreviewSensorOrientationDegrees.collectAsState()

    LaunchedEffect(state.captureMode) {
        if (state.captureMode != CaptureMode.VIDEO && dualCaptureEnabled) {
            dualCaptureEnabled = false
            viewModel.setDualCaptureEnabled(false)
        }
        if (state.captureMode != CaptureMode.VIDEO && spatialVideoEnabled) {
            viewModel.setSpatialVideoEnabled(false)
        }
    }

    val uiStyle by viewModel.uiStyle.collectAsState()
    val isCameraInitialized by viewModel.isInitialized.collectAsState()
    val latestPhoto by galleryViewModel.latestPhoto.collectAsState()
    val showLevelIndicator by viewModel.showLevelIndicator.collectAsState(initial = false)
    val focusPeakingEnabled by viewModel.focusPeakingEnabled.collectAsState(initial = true)
    val eyeFocusEnabled by viewModel.eyeFocusEnabled.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState(initial = false)
    val cameraMenuBlurRadius by viewModel.cameraMenuBlurRadius.collectAsState()
    val cameraMenuTransparency by viewModel.cameraMenuTransparency.collectAsState()
    val currentLutId by viewModel.currentLutId.collectAsState()
    val lutNameOverlayState = rememberLutNameOverlayState()
    val currentRecipeParams by viewModel.currentRecipeParams.collectAsState()
    val currentVideoRecipeParams by viewModel.currentVideoRecipeParams.collectAsState()
    val videoHdrEnabled by viewModel.videoHdrEnabled.collectAsState()
    val videoPortraitEnabled by viewModel.videoPortraitEnabled.collectAsState()
    val lutSelectorMode by viewModel.lutSelectorMode.collectAsState()
    val activePresetId by viewModel.activePresetId.collectAsState()
    val activePresetModified by viewModel.isActivePresetModified.collectAsState()
    val customPresets by viewModel.customPresets.collectAsState()
    val currentBaselineRecipeParams by viewModel.currentBaselineRecipeParams.collectAsState()
    val categoryOrder by viewModel.categoryOrder.collectAsState(emptyList())
    val useRaw by viewModel.useRaw.collectAsState()
    val oppoPhotoSettings by viewModel.oppoPhotoSettings.collectAsState()
    val useJpgMax by viewModel.useJpgMax.collectAsState()
    val useMultipleExposure by viewModel.useMultipleExposure.collectAsState()
    val useRawMax by viewModel.useRawMax.collectAsState()
    val ultraHdrEnabled by viewModel.ultraHdrGainMapEnabled.collectAsState()
    val useLivePhoto by viewModel.useLivePhoto.collectAsState()
    val enableDevelopAnimation by viewModel.enableDevelopAnimation.collectAsState()
    val developAnimationStyle by viewModel.developAnimationStyle.collectAsState()
    val phantomMode by viewModel.phantomMode.collectAsState()
    val topSheetAspectRatios by viewModel.topSheetAspectRatios.collectAsState()
    val videoCodec by viewModel.videoCodec.collectAsState()
    val videoAudioInputOptions by viewModel.videoAudioInputOptions.collectAsState()
    val phantomPipPreview by viewModel.phantomPipPreview.collectAsState()
    var dualCaptureLayout by remember { mutableStateOf(DualCaptureLayout.PICTURE_IN_PICTURE) }
    var dualCaptureLayoutMenuExpanded by remember { mutableStateOf(false) }
    var dualCaptureDragOffset by remember { mutableStateOf(Offset.Zero) }
    val dualCaptureStreamSize by viewModel.dualCaptureStreamSize.collectAsState()
    val dualCaptureSource by viewModel.dualCaptureSource.collectAsState()
    val dualCaptureSensorOrientationDegrees by viewModel.dualCaptureSensorOrientationDegrees.collectAsState()
    val latestDualCaptureStreamSize = rememberUpdatedState(dualCaptureStreamSize)
    val latestDualCaptureSensorOrientation = rememberUpdatedState(dualCaptureSensorOrientationDegrees)
    val latestDualCaptureSource = rememberUpdatedState(dualCaptureSource)
    val latestSpatialVideoPreviewSize = rememberUpdatedState(spatialVideoPreviewStreamSize)
    val latestSpatialVideoPreviewOrientation = rememberUpdatedState(spatialVideoPreviewSensorOrientation)
    val rawDcpId by viewModel.rawDcpId.collectAsState()
    val rawDcpIdsByLens by viewModel.rawDcpIdsByLens.collectAsState()
    val rawHncsFilmCurveMode by viewModel.rawHncsFilmCurveMode.collectAsState()
    val rawColorEngine by viewModel.rawRenderingEngine.collectAsState()
    val rawToneMappingParameters by viewModel.rawToneMappingParameters.collectAsState()
    val rawSpectralFilmStock by viewModel.rawSpectralFilmStock.collectAsState()
    val rawSpectralFilmSelection by viewModel.rawSpectralFilmSelection.collectAsState()
    val rawSpectralFilmPrint by viewModel.rawSpectralFilmPrint.collectAsState()
    val multipleExposureState = viewModel.multipleExposureState
    val canStartShutterAnimation by viewModel.canStartShutterAnimation.collectAsState()
    val currentCaptureModeForEffects by rememberUpdatedState(state.captureMode)
    val activeRecipeParams = if (state.captureMode == CaptureMode.VIDEO) {
        currentVideoRecipeParams ?: currentRecipeParams
    } else {
        currentRecipeParams
    }
    var previewRecipeParamsOverride by remember(currentLutId, activePresetId, state.captureMode) {
        mutableStateOf<ColorRecipeParams?>(null)
    }
    val effectiveRecipeParams = viewModel.getMergedRecipeParams(
        previewRecipeParamsOverride ?: activeRecipeParams,
        forVideo = state.captureMode == CaptureMode.VIDEO
    )
    var pendingCaptureAnimationBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var captureAnimationJob by remember { mutableStateOf<Job?>(null) }
    var cameraScreenBounds by remember { mutableStateOf<Rect?>(null) }
    var previewBounds by remember { mutableStateOf<Rect?>(null) }
    var previewGlBounds by remember { mutableStateOf<Rect?>(null) }
    var settingsGlassBounds by remember { mutableStateOf<Rect?>(null) }
    var videoSettingsGlassBounds by remember { mutableStateOf<Rect?>(null) }
    var videoEditGlassBounds by remember { mutableStateOf<Rect?>(null) }
    var videoEditPillBounds by remember { mutableStateOf<Rect?>(null) }
    var cameraGLSurfaceView by remember { mutableStateOf<CameraGLSurfaceView?>(null) }
    var zoomJoystickOpen by remember { mutableStateOf(false) }
    var viewfinderAreaBounds by remember { mutableStateOf<Rect?>(null) }
    var zoomBarBounds by remember { mutableStateOf<Rect?>(null) }
    var filterButtonBounds by remember { mutableStateOf<Rect?>(null) }
    var captureButtonBounds by remember { mutableStateOf<Rect?>(null) }
    var parameterRulerBounds by remember { mutableStateOf<Rect?>(null) }
    var galleryThumbnailBounds by remember { mutableStateOf<Rect?>(null) }
    var captureAnimationSnapshot by remember { mutableStateOf<CaptureAnimationSnapshot?>(null) }
    val zoomStopAnimationProgress = remember { Animatable(0f) }
    var zoomStopAnimationJob by remember { mutableStateOf<Job?>(null) }
    var previewTransitionActive by remember { mutableStateOf(false) }
    var previewTransitionRevealing by remember { mutableStateOf(false) }
    var previewTransitionToken by remember { mutableIntStateOf(0) }
    var previewTransitionAwaitingResume by remember { mutableStateOf(false) }
    var previewTransitionSawPause by remember { mutableStateOf(false) }
    var rawCaptureTapLocked by remember { mutableStateOf(false) }
    fun discardTransientLookEdits() {
        previewRecipeParamsOverride = null
    }

    LaunchedEffect(activeRecipeParams) {
        if (previewRecipeParamsOverride?.isSameAs(activeRecipeParams) == true) {
            previewRecipeParamsOverride = null
        }
    }

    // Surface may become ready while persisted camera settings are still being restored.
    // Retain the exact instance and open only after initialization has completed.
    var previewSurfaceTexture by remember { mutableStateOf<SurfaceTexture?>(null) }
    var isCameraPrepared by remember { mutableStateOf(false) }

    LaunchedEffect(isCameraInitialized) {
        isCameraPrepared = isCameraInitialized && viewModel.prepareCamera()
    }

    LaunchedEffect(
        isCameraInitialized,
        isCameraPrepared,
        previewSurfaceTexture,
    ) {
        val surfaceTexture = previewSurfaceTexture ?: return@LaunchedEffect
        if (!isCameraInitialized || !isCameraPrepared) return@LaunchedEffect
        viewModel.openCamera(surfaceTexture)
    }

    // UI State
    var activePanel by remember { mutableStateOf(ActivePanel.NONE) }
    var videoSettingsPanelOpen by remember { mutableStateOf(false) }
    var videoEditOpenedFromModePill by remember { mutableStateOf(false) }

    LaunchedEffect(
        state.captureMode,
        state.videoRecordingState.isRecording,
        state.videoRecordingState.isProcessing,
        activePanel,
    ) {
        if (state.captureMode != CaptureMode.VIDEO ||
            state.videoRecordingState.isRecording ||
            state.videoRecordingState.isProcessing ||
            activePanel != ActivePanel.NONE
        ) {
            videoSettingsPanelOpen = false
        }
    }

    // Blur the visible menu area in the existing camera GL output. Coordinates from
    // Compose and the GLSurfaceView share the same root-pixel space.
    val glassDensity = LocalDensity.current
    LaunchedEffect(
        cameraGLSurfaceView,
        activePanel,
        state.captureMode,
        settingsGlassBounds,
        videoSettingsGlassBounds,
        videoEditGlassBounds,
        videoSettingsPanelOpen,
        previewGlBounds,
        cameraMenuBlurRadius,
    ) {
        val glView = cameraGLSurfaceView ?: return@LaunchedEffect
        val preview = previewGlBounds
        val isVideoEditOpen = activePanel == ActivePanel.EDIT && state.captureMode == CaptureMode.VIDEO
        val panel = when {
            activePanel == ActivePanel.SETTINGS -> settingsGlassBounds
            isVideoEditOpen -> videoEditGlassBounds
            videoSettingsPanelOpen -> videoSettingsGlassBounds
            else -> null
        }
        if ((activePanel != ActivePanel.SETTINGS && !isVideoEditOpen && !videoSettingsPanelOpen) || preview == null || panel == null ||
            preview.width <= 0f || preview.height <= 0f || cameraMenuBlurRadius <= 0f
        ) {
            glView.setQuickMenuGlassEffect(enabled = false)
            return@LaunchedEffect
        }

        val left = maxOf(preview.left, panel.left)
        val top = maxOf(preview.top, panel.top)
        val right = minOf(preview.right, panel.right)
        val bottom = minOf(preview.bottom, panel.bottom)
        if (right <= left || bottom <= top) {
            glView.setQuickMenuGlassEffect(enabled = false)
            return@LaunchedEffect
        }

        val leftPx = (left - preview.left).coerceIn(0f, preview.width)
        val rightPx = (right - preview.left).coerceIn(0f, preview.width)
        val bottomPx = (preview.height - (bottom - preview.top)).coerceIn(0f, preview.height)
        val topPx = (preview.height - (top - preview.top)).coerceIn(0f, preview.height)
        val blurRadiusPx = (cameraMenuBlurRadius.coerceIn(0f, 50f) * 2.2f).coerceIn(0f, 160f)
        val cornerRadiusPx = with(glassDensity) { 30.dp.toPx() }

        glView.setQuickMenuGlassEffect(
            enabled = true,
            leftPx = leftPx,
            bottomPx = bottomPx,
            rightPx = rightPx,
            topPx = topPx,
            blurRadiusPx = blurRadiusPx,
            cornerRadiusPx = cornerRadiusPx,
        )
    }

    var selectedParameter by remember { mutableStateOf(CameraParameter.EXPOSURE_COMPENSATION) }
    var showParameterRuler by remember(state.captureMode, state.useRaw) { mutableStateOf(false) }
    val isXpan = state.aspectRatio == AspectRatio.XPAN
    val burstCapturingCount = viewModel.burstImageCount

    var isGhostPermissionFlowActive by remember { mutableStateOf(false) }
    val activity = remember(context) { context.findActivity() }

    val ghostLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { _ ->
            // Results are handled via the ON_RESUME lifecycle effect to avoid self-reference issues
        }
    )

    val dcpImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris ->
            if (uris.isNotEmpty()) {
                viewModel.importRawDcps(uris) { _, _ -> }
            }
        }
    )

    // 当打开滤镜面板时，生成预览图
    LaunchedEffect(activePanel) {
        if (activePanel == ActivePanel.FILTERS) {
            viewModel.generateThumbnail()
        }
    }

    LaunchedEffect(state.useRaw, state.captureMode) {
        if (!state.useRaw || state.captureMode != CaptureMode.PHOTO) {
            rawCaptureTapLocked = false
        }
    }

    // 从后台返回时检查并恢复相机，刷新最新照片
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.checkAndRecoverCamera()
        viewModel.refreshLocationOnResume()
        galleryViewModel.refreshLatestPhoto()

        // Handle automated ghost mode permission sequence
        if (isGhostPermissionFlowActive) {
            val hasOverlay = Settings.canDrawOverlays(context)
            val hasFiles = Environment.isExternalStorageManager()

            if (hasOverlay && !hasFiles) {
                // Overlay granted, now request files
                ghostLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        ("package:${context.packageName}").toUri()
                    )
                )
            } else if (hasOverlay) {
                // All permissions granted
                isGhostPermissionFlowActive = false
                if (!phantomMode) {
                    viewModel.togglePhantomMode()
                }
            } else {
                // If overlay is still missing after returning, user might have cancelled
                // We stop the automatic flow to avoid getting stuck
                isGhostPermissionFlowActive = false
            }
        }

        viewModel.updateLut()
    }

    DisposableEffect(Unit) {
        onDispose {
            pendingCaptureAnimationBitmap?.recycleIfAlive()
            pendingCaptureAnimationBitmap = null
            captureAnimationJob?.cancel()
        }
    }

    LaunchedEffect(enableDevelopAnimation, state.captureMode) {
        if (!enableDevelopAnimation || state.captureMode != CaptureMode.PHOTO) {
            pendingCaptureAnimationBitmap?.recycleIfAlive()
            pendingCaptureAnimationBitmap = null
            captureAnimationJob?.cancel()
            captureAnimationSnapshot = null
        }
    }

    // 监听照片保存完成事件，立即刷新缩略图
    LaunchedEffect(Unit) {
        viewModel.imageSavedEvent.collect {
            galleryViewModel.refreshLatestPhoto()
            MyCameraApplication.updateWidgets(context)
        }
    }

    // 显影在拍摄请求完成时开始，不等待图像处理或保存。
    LaunchedEffect(Unit) {
        viewModel.captureCompletedEvent.collect { metadata ->
            if (!enableDevelopAnimation || currentCaptureModeForEffects != CaptureMode.PHOTO) {
                pendingCaptureAnimationBitmap?.recycleIfAlive()
                pendingCaptureAnimationBitmap = null
                captureAnimationSnapshot = null
                return@collect
            }
            val rootOffset = cameraScreenBounds?.topLeft ?: androidx.compose.ui.geometry.Offset.Zero
            val sourceBounds = previewBounds?.translate(-rootOffset)
            val targetBounds = galleryThumbnailBounds?.translate(-rootOffset)
            val animationStyle = developAnimationStyle

            fun startCaptureAnimation(bitmap: Bitmap) {
                if (sourceBounds == null || targetBounds == null || !scope.isActive ||
                    !enableDevelopAnimation || currentCaptureModeForEffects != CaptureMode.PHOTO
                ) {
                    bitmap.recycleIfAlive()
                    return
                }
                captureAnimationJob?.cancel()
                captureAnimationJob = scope.launch {
                    var processedBitmap: Bitmap? = null
                    try {
                        processedBitmap = if (animationStyle == DevelopAnimationStyle.INSTANT_PRINT) {
                            viewModel.renderCaptureAnimationFrame(bitmap, metadata)
                        } else {
                            viewModel.applyLut(bitmap)
                        }
                        processedBitmap.copyForCaptureAnimation()?.let {
                            captureAnimationSnapshot = CaptureAnimationSnapshot(
                                bitmap = it.asImageBitmap(),
                                sourceBounds = sourceBounds,
                                targetBounds = targetBounds,
                                style = animationStyle
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        com.hinnka.mycamera.utils.PLog.e("CaptureAnimation", "Failed to prepare capture animation", e)
                    } finally {
                        processedBitmap?.recycleIfAlive()
                        if (processedBitmap !== bitmap) bitmap.recycleIfAlive()
                    }
                }
            }

            pendingCaptureAnimationBitmap?.let(::startCaptureAnimation)
                ?: viewModel.glSurfaceView?.capturePreviewFrame(::startCaptureAnimation)
            pendingCaptureAnimationBitmap = null
        }
    }

    val previewSize = state.currentPreviewSize
    val previewAspectRatio = state.getPreviewAspectRatio()
    val previewTransitionCoverFractionState = animateFloatAsState(
        targetValue = when {
            previewTransitionActive && !previewTransitionRevealing -> 1f
            previewTransitionActive -> 0f
            else -> 0f
        },
        animationSpec = if (previewTransitionActive && !previewTransitionRevealing) {
            snap()
        } else {
            tween(
                durationMillis = PreviewTransitionRevealDurationMillis,
                easing = FastOutSlowInEasing
            )
        },
        label = "previewTransitionCoverFraction"
    )
    val previewTransitionCoverFraction = previewTransitionCoverFractionState.value

    fun runPreviewTransition(onSwitch: () -> Unit) {
        previewTransitionActive = true
        previewTransitionRevealing = false
        previewTransitionToken += 1
        previewTransitionAwaitingResume = true
        previewTransitionSawPause = false
        scope.launch {
            withFrameNanos { }
            onSwitch()
        }
    }

    fun setZoomWithPreviewTransition(targetZoom: Float) {
        if (viewModel.isVideoLensLocked() ||
            viewModel.isCurrentLensCustomZoomRatioStop(targetZoom)
        ) {
            viewModel.setZoomRatio(targetZoom)
            return
        }
        val currentCamera = state.getCurrentCameraInfo()
        val camera = viewModel.findOptimalLens(
            targetZoom,
            state.availableCameras,
            currentCamera?.cameraId ?: "0"
        )
        if (camera != null && camera.cameraId != currentCamera?.cameraId) {
            viewModel.switchToLensAndSetZoomRatio(camera.cameraId, targetZoom)
        } else {
            viewModel.setZoomRatio(targetZoom)
        }
    }

    suspend fun runZoomStopAnimation(startZoom: Float, targetZoom: Float) {
        if (!startZoom.isFinite() || startZoom <= 0f ||
            !targetZoom.isFinite() || targetZoom <= 0f
        ) {
            viewModel.setZoomRatio(targetZoom)
            return
        }
        zoomStopAnimationProgress.snapTo(0f)
        zoomStopAnimationProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = ZoomStopAnimationDurationMillis,
                easing = ZoomStopAnimationEasing
            )
        ) {
            viewModel.setZoomRatio(
                interpolateZoomRatio(startZoom, targetZoom, value)
            )
        }
        viewModel.setZoomRatio(targetZoom)
    }

    fun animateZoomStopTo(targetZoom: Float, requestedCameraId: String? = null) {
        val currentCamera = state.getCurrentCameraInfo()
        val startZoom = viewModel.zoomRatioByMain
            .takeIf { it.isFinite() && it > 0f }
            ?: state.zoomRatio.coerceAtLeast(0.01f)
        val videoLensLocked = viewModel.isVideoLensLocked()
        val targetCamera = if (videoLensLocked) {
            null
        } else if (requestedCameraId != null) {
            state.availableCameras.firstOrNull { it.cameraId == requestedCameraId }
        } else {
            viewModel.findOptimalLens(
                targetZoom,
                state.availableCameras,
                currentCamera?.cameraId ?: "0"
            )
        }
        val shouldSwitchLens = targetCamera != null &&
            targetCamera.cameraId != currentCamera?.cameraId

        zoomStopAnimationJob?.cancel()
        if (abs(targetZoom - startZoom) <= 0.001f) {
            if (shouldSwitchLens) {
                viewModel.switchToLensAndSetZoomRatio(targetCamera!!.cameraId, targetZoom)
            } else {
                viewModel.setZoomRatio(targetZoom)
            }
            return
        }

        zoomStopAnimationJob = scope.launch {
            if (shouldSwitchLens && targetCamera != null) {
                val targetIntrinsic = targetCamera.displayIntrinsicZoomRatio
                    .takeIf { it > 0f } ?: 1f
                val targetLensStartZoom = startZoom / targetIntrinsic
                val canRepresentCurrentZoom = targetLensStartZoom >= targetCamera.minZoom &&
                    targetLensStartZoom <= targetCamera.maxZoom

                if (canRepresentCurrentZoom) {
                    // Open the destination lens at the current field of view, then animate
                    // the remaining zoom on that lens.
                    viewModel.switchToLensAndSetZoomRatio(targetCamera.cameraId, startZoom)
                    runZoomStopAnimation(startZoom, targetZoom)
                } else {
                    // Keep the current lens active while it can provide a valid continuous
                    // zoom path; perform the physical lens handoff at the end of the motion.
                    val currentIntrinsic = currentCamera?.displayIntrinsicZoomRatio
                        ?.takeIf { it > 0f } ?: 1f
                    val minReachableZoom = (currentCamera?.minZoom ?: 1f) * currentIntrinsic
                    val maxReachableZoom = (currentCamera?.maxZoom ?: 10f) * currentIntrinsic
                    val currentLensTarget = targetZoom.coerceIn(minReachableZoom, maxReachableZoom)
                    runZoomStopAnimation(startZoom, currentLensTarget)
                    viewModel.switchToLensAndSetZoomRatio(targetCamera.cameraId, targetZoom)
                }
            } else {
                runZoomStopAnimation(startZoom, targetZoom)
            }
        }
    }

    fun switchToLensWithPreviewTransition(cameraId: String) {
        if (cameraId == state.getCurrentCameraInfo()?.cameraId) return
        val targetCamera = state.availableCameras.firstOrNull { it.cameraId == cameraId } ?: return
        val targetZoom = targetCamera.displayIntrinsicZoomRatio.takeIf { it > 0f }
            ?: targetCamera.intrinsicZoomRatio.takeIf { it > 0f }
            ?: 1f
        animateZoomStopTo(
            targetZoom = targetZoom,
            requestedCameraId = cameraId.takeUnless { viewModel.isVideoLensLocked() }
        )
    }

    fun switchCameraWithPreviewTransition() {
        // Switch cameras immediately without the black preview-cover reveal.
        viewModel.switchCamera()
    }

    fun setShootingModeImmediately(mode: CameraShootingMode) {
        if (mode == CameraShootingMode.PROFESSIONAL && !state.isRawSupported) return

        val targetCaptureMode = when (mode) {
            CameraShootingMode.PROFESSIONAL,
            CameraShootingMode.PHOTO -> CaptureMode.PHOTO
            CameraShootingMode.VIDEO -> CaptureMode.VIDEO
        }
        val targetUseRaw = when (mode) {
            CameraShootingMode.PROFESSIONAL -> true
            CameraShootingMode.PHOTO -> false
            CameraShootingMode.VIDEO -> null
        }
        if (
            targetCaptureMode == state.captureMode &&
            (targetUseRaw == null || targetUseRaw == state.useRaw)
        ) {
            return
        }

        // Keep the camera preview visible during the required capture-mode restart.
        // The preview-cover animation is reserved for aspect-ratio and preset changes.
        if (targetCaptureMode == CaptureMode.VIDEO && state.aspectRatio == AspectRatio.XPAN) {
            viewModel.setAspectRatio(AspectRatio.RATIO_4_3)
        }
        viewModel.setShootingMode(targetCaptureMode, targetUseRaw)
    }

    fun presetTargetAspectRatio(preset: CameraPreset?): AspectRatio {
        return try {
            AspectRatio.valueOf(preset?.aspectRatio ?: AspectRatio.RATIO_4_3.name)
        } catch (_: Exception) {
            AspectRatio.RATIO_4_3
        }
    }

    fun presetRequiresPreviewTransition(preset: CameraPreset?): Boolean {
        if (state.captureMode == CaptureMode.VIDEO) return false
        val targetAspectRatio = presetTargetAspectRatio(preset)
        return targetAspectRatio != state.aspectRatio
    }

    LaunchedEffect(previewTransitionToken, state.isPreviewActive, previewTransitionAwaitingResume) {
        if (!previewTransitionAwaitingResume) return@LaunchedEffect

        if (!state.isPreviewActive) {
            previewTransitionSawPause = true
            return@LaunchedEffect
        }

        if (previewTransitionSawPause) {
            delay(80)
            previewTransitionRevealing = true
            delay(220)
            previewTransitionActive = false
            previewTransitionAwaitingResume = false
            previewTransitionSawPause = false
        }
    }

    val shouldKeepScreenOn = keepScreenOn || state.videoRecordingState.isRecording

    DisposableEffect(activity, shouldKeepScreenOn) {
        if (shouldKeepScreenOn) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(previewTransitionToken) {
        if (previewTransitionToken == 0) return@LaunchedEffect
        delay(700)
        if (previewTransitionAwaitingResume) {
            previewTransitionRevealing = true
            delay(220)
            previewTransitionActive = false
            previewTransitionAwaitingResume = false
            previewTransitionSawPause = false
        }
    }

    var showGhostPermissionDialog by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel.showGhostPermissions) {
        if (viewModel.showGhostPermissions) {
            showGhostPermissionDialog = true
            viewModel.showGhostPermissions = false
        }
    }

    if (viewModel.showPaymentDialog) {
        PaymentDialog(
            onDismiss = { viewModel.showPaymentDialog = false },
            onPurchase = {
                if (activity != null) {
                    viewModel.purchase(activity)
                }
                viewModel.showPaymentDialog = false
            }
        )
    }

    if (viewModel.showEnhancedStabilizationUnavailableDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissEnhancedStabilizationUnavailableDialog,
            title = {
                Text(
                    text = stringResource(R.string.eis_plus_unavailable_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.eis_plus_unavailable_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissEnhancedStabilizationUnavailableDialog) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }

    if (showGhostPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showGhostPermissionDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.ghost_mode_dialog_title),
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.ghost_mode_dialog_description),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.ghost_mode_permissions_required),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.ghost_mode_overlay_permission),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.ghost_mode_file_permission),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showGhostPermissionDialog = false
                        isGhostPermissionFlowActive = true
                        if (!Settings.canDrawOverlays(context)) {
                            ghostLauncher.launch(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    ("package:${context.packageName}").toUri()
                                )
                            )
                        } else if (!Environment.isExternalStorageManager()) {
                            ghostLauncher.launch(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    ("package:${context.packageName}").toUri()
                                )
                            )
                        } else {
                            isGhostPermissionFlowActive = false
                            viewModel.togglePhantomMode()
                        }
                    }
                ) {
                    Text(stringResource(R.string.ghost_mode_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showGhostPermissionDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                cameraScreenBounds = coordinates.boundsInRoot()
            }
    ) {

        val backgroundPainter = rememberBackgroundPainter(viewModel)
        val modeSwitcherBackdrop = rememberPrismalGlassLayer()

        val isVideoMode = state.captureMode == CaptureMode.VIDEO
        val isPhotoStyleMode = state.captureMode != CaptureMode.VIDEO
        val zoomJoystickSupported =
            !state.useRaw &&
                (state.captureMode == CaptureMode.PHOTO || state.captureMode == CaptureMode.VIDEO) &&
                state.zoomRatio > 2f &&
                state.getCurrentCameraInfo()?.supportsFreeformZoomPan == true
        val canShowZoomJoystick = activePanel == ActivePanel.NONE && zoomJoystickSupported

        LaunchedEffect(canShowZoomJoystick) {
            if (!canShowZoomJoystick) zoomJoystickOpen = false
        }
        LaunchedEffect(zoomJoystickSupported) {
            if (!zoomJoystickSupported) {
                zoomJoystickOpen = false
                viewModel.resetZoomPan()
            }
        }
        val onParameterClick: (CameraParameter) -> Unit = { parameter ->
            val wasSelected = selectedParameter == parameter
            selectedParameter = parameter
            showParameterRuler = !wasSelected || !showParameterRuler
        }
        LaunchedEffect(showParameterRuler) {
            if (!showParameterRuler) {
                parameterRulerBounds = null
            }
        }
        val isOpenGateVideo =
            isVideoMode && state.videoConfig.aspectRatio == VideoAspectRatio.OPEN_GATE

        val density = LocalDensity.current

        val width = with(density) { constraints.maxWidth.toDp() }
        val height = with(density) { constraints.maxHeight.toDp() }
        val topSafePadding = cameraTopSafePadding()
        val topBarOffset = 0.dp
        val topBarBaseHeight = if (isVideoMode) {
            VideoTopBarBaseHeight
        } else {
            CameraTopBarBaseHeight
        }
        val topBarHeight = topBarBaseHeight + topSafePadding
        val topBarBottom = topBarHeight + topBarOffset - 4.dp
        val navigationBarHeight = with(density) {
            WindowInsets.navigationBars.getBottom(this).toDp()
        }
        val contentHeight = (height - navigationBarHeight).coerceAtLeast(0.dp)
        // In video mode, stop the preview at the shutter's lower edge. The mode
        // selector remains below it instead of being covered by the viewfinder.
        val videoViewfinderBottom = (
            contentHeight - (CameraControlsLayoutDefaults.ModeAreaHeight -
                CameraControlsLayoutDefaults.VerticalOffset)
        ).coerceAtLeast(0.dp)
        val viewfinderFramePadding = if (isXpan) XpanViewfinderInnerBorder * 2 else 0.dp
        val sideControlSpace = if (isXpan) XpanSideControlReservedWidth else 0.dp
        val maxCardWidth = (width - sideControlSpace * 2)
            .coerceAtLeast(viewfinderFramePadding)
        // Open Gate is a full-sensor capture mode: present its viewfinder with the same
        // placement and available area as Photo mode. Standard video ratios remain bottom
        // anchored to the video controls.
        val usesPhotoStyleViewfinder = !isVideoMode || isOpenGateVideo
        val maxCardHeight = when {
            isXpan -> (contentHeight - topBarBottom - CameraControlsLayoutDefaults.XpanViewfinderClearance)
                .coerceAtLeast(viewfinderFramePadding)
            !usesPhotoStyleViewfinder -> videoViewfinderBottom
            else -> contentHeight
        }
        val maxViewfinderContentWidth = (maxCardWidth - viewfinderFramePadding)
            .coerceAtLeast(0.dp)
        val maxViewfinderContentHeight = (maxCardHeight - viewfinderFramePadding)
            .coerceAtLeast(0.dp)
        // A portrait 21:9 recording frame is taller than the usable phone viewport.
        // Keep the preview edge-to-edge and let the GL renderer center-crop the live
        // sensor image to the visible screen area; the encoder still writes true 21:9.
        val fillsVideoViewfinderWidth = isVideoMode && !isOpenGateVideo
        val viewfinderContentWidth = if (fillsVideoViewfinderWidth) {
            maxViewfinderContentWidth
        } else {
            minOf(
                maxViewfinderContentWidth,
                maxViewfinderContentHeight * previewAspectRatio
            )
        }
        val viewfinderContentHeight = if (fillsVideoViewfinderWidth) {
            minOf(maxViewfinderContentHeight, viewfinderContentWidth / previewAspectRatio)
        } else {
            viewfinderContentWidth / previewAspectRatio
        }
        val cardWidth = viewfinderContentWidth + viewfinderFramePadding
        val cardHeight = viewfinderContentHeight + viewfinderFramePadding
        val xpanSideWidth = ((width - cardWidth) / 2).coerceAtLeast(0.dp)
        val placeViewfinderBelowTopBar = contentHeight - cardHeight >= topBarBottom
        // Balance small previews between the toolbar and collapsed controls. Reserving both
        // bars first prevents the downward shift from pushing parameters back into the image.
        val extraViewfinderTopSpacing = if (isXpan) {
            0.dp
        } else {
            ((contentHeight - topBarBottom - cardHeight -
                CameraControlsLayoutDefaults.CollapsedControlsHeight) / 2).coerceAtLeast(0.dp)
        }
        val viewfinderTop = if (!usesPhotoStyleViewfinder) {
            // Standard video frames stay anchored to the shutter bottom.
            (videoViewfinderBottom - cardHeight).coerceAtLeast(0.dp)
        } else {
            // Photo and Open Gate share the same balanced, photo-style placement.
            (if (placeViewfinderBelowTopBar) {
                topBarBottom + extraViewfinderTopSpacing
            } else {
                ((contentHeight - cardHeight) / 2).coerceAtLeast(0.dp)
            }) + CameraControlsLayoutDefaults.VerticalOffset
        }
        // Keep the video top bar at the exact position it occupies in the 9:16 layout,
        // independent of the selected recording ratio and the viewfinder's top edge.
        val nineSixteenViewfinderHeight = minOf(
            videoViewfinderBottom,
            maxViewfinderContentWidth / (9f / 16f)
        )
        val videoTopBarAnchorTop = (videoViewfinderBottom - nineSixteenViewfinderHeight)
            .coerceAtLeast(0.dp)
        val viewfinderTopOverlayPadding = if (!usesPhotoStyleViewfinder) {
            0.dp
        } else {
            (topBarBottom - viewfinderTop).coerceAtLeast(0.dp)
        }
        // Photo/video mode changes use the final viewfinder geometry immediately.
        // No intermediate bounds or sensor-crop interpolation is applied.
        val animatedViewfinderHeight = cardHeight
        val animatedViewfinderTop = viewfinderTop

        val topBar = @Composable {
            CameraTopBar(
                captureMode = state.captureMode,
                isRecording = state.videoRecordingState.isRecording,
                recordingElapsedMs = state.videoRecordingState.elapsedMs,
                flashMode = state.flashMode,
                onFlashToggle = {
                    viewModel.toggleFlash()
                },
                useLivePhoto = useLivePhoto,
                onLivePhotoToggle = { viewModel.setUseLivePhoto(!state.useLivePhoto) },
                useRaw = useRaw && state.isRawSupported,
                rawSupported = state.isRawSupported,
                onRawToggle = { viewModel.toggleRaw() },
                videoConfig = state.videoConfig,
                videoCapabilities = state.videoCapabilities,
                videoPortraitEnabled = videoPortraitEnabled,
                onVideoPortraitToggle = { viewModel.setVideoPortraitEnabled(!videoPortraitEnabled) },
                onVideoTorchToggle = { viewModel.setVideoTorchEnabled(!state.videoConfig.torchEnabled) },
                onVideoResolutionClick = {
                    if (state.videoConfig.aspectRatio != VideoAspectRatio.OPEN_GATE) {
                        videoSettingsPanelOpen = !videoSettingsPanelOpen
                    }
                },
                onVideoFpsClick = {
                    videoSettingsPanelOpen = !videoSettingsPanelOpen
                },
                videoHdrEnabled = videoHdrEnabled,
                onVideoHdrToggle = {
                    viewModel.setVideoHdrEnabled(!videoHdrEnabled)
                },
                onSettingsClick = {
                    activePanel = if (activePanel == ActivePanel.SETTINGS) ActivePanel.NONE else ActivePanel.SETTINGS
                },
                modifier = Modifier
                    .padding(top = topSafePadding)
                    .offset(y = topBarOffset)
            )
        }

        val zoomBar = @Composable {
            if (activePanel == ActivePanel.NONE && !isXpan) {
                ZoomControlBar(
                    viewModel = viewModel,
                    zoomRatio = viewModel.zoomRatioByMain,
                    availableCameras = state.availableCameras,
                    currentCameraId = state.getCurrentCameraInfo()?.cameraId ?: "0",
                    onZoomChange = { animateZoomStopTo(it) },
                    onPreviewZoomChange = viewModel::setZoomRatio,
                    onZoomStopClick = { animateZoomStopTo(it) },
                    onLensSwitch = { lensId -> switchToLensWithPreviewTransition(lensId) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coordinates ->
                            zoomBarBounds = coordinates.boundsInRoot()
                        }
                )
            } else {
                SideEffect {
                    zoomBarBounds = null
                }
            }
        }


        val zoomBarWithDualCaptureLayout = @Composable {
            if (dualCaptureEnabled && isVideoMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        zoomBar()
                    }
                    Box {
                        IconButton(
                            onClick = { dualCaptureLayoutMenuExpanded = true },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Text(
                                text = "▤",
                                color = Color.White,
                                fontSize = 19.sp,
                            )
                        }
                        DropdownMenu(
                            expanded = dualCaptureLayoutMenuExpanded,
                            onDismissRequest = { dualCaptureLayoutMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.dual_capture_layout_picture_in_picture)) },
                                onClick = {
                                    dualCaptureLayout = DualCaptureLayout.PICTURE_IN_PICTURE
                                    dualCaptureDragOffset = Offset.Zero
                                    viewModel.setDualCaptureLayout(dualCaptureLayout)
                                    viewModel.setDualCapturePosition(0.66f, 0.05f)
                                    dualCaptureLayoutMenuExpanded = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.dual_capture_layout_top_bottom)) },
                                onClick = {
                                    dualCaptureLayout = DualCaptureLayout.TOP_BOTTOM
                                    viewModel.setDualCaptureLayout(dualCaptureLayout)
                                    dualCaptureLayoutMenuExpanded = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.dual_capture_layout_side_by_side)) },
                                onClick = {
                                    dualCaptureLayout = DualCaptureLayout.SIDE_BY_SIDE
                                    viewModel.setDualCaptureLayout(dualCaptureLayout)
                                    dualCaptureLayoutMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            } else {
                zoomBar()
            }
        }

        val parameterRuler = @Composable { rulerModifier: Modifier ->
            val whiteBalanceCurrentValue = if (
                state.awbMode == android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO
            ) {
                state.actualAwbTemperature?.toFloat() ?: state.awbTemperature.toFloat()
            } else {
                state.awbTemperature.toFloat()
            }
            ParameterRuler(
                parameter = selectedParameter,
                currentValue = when (selectedParameter) {
                    CameraParameter.EXPOSURE_COMPENSATION -> state.exposureCompensation * state.getExposureCompensationStep()
                    CameraParameter.SHUTTER_SPEED -> state.shutterSpeed.toFloat()
                    CameraParameter.ISO -> state.iso.toFloat()
                    CameraParameter.FOCUS -> state.focusDistance
                    CameraParameter.WHITE_BALANCE -> whiteBalanceCurrentValue
                },
                minValue = when (selectedParameter) {
                    CameraParameter.EXPOSURE_COMPENSATION -> state.getExposureCompensationRange().lower * state.getExposureCompensationStep()
                    CameraParameter.SHUTTER_SPEED -> state.getManualShutterSpeedRange().lower.toFloat()
                    CameraParameter.ISO -> state.getIsoRange().lower.toFloat()
                    CameraParameter.FOCUS -> 0f
                    CameraParameter.WHITE_BALANCE -> state.awbTemperatureMin.toFloat()
                },
                maxValue = when (selectedParameter) {
                    CameraParameter.EXPOSURE_COMPENSATION -> state.getExposureCompensationRange().upper * state.getExposureCompensationStep()
                    CameraParameter.SHUTTER_SPEED -> state.getManualShutterSpeedRange().upper.toFloat()
                    CameraParameter.ISO -> state.getIsoRange().upper.toFloat()
                    CameraParameter.FOCUS -> state.minimumFocusDistance
                    CameraParameter.WHITE_BALANCE -> state.awbTemperatureMax.toFloat()
                },
                isAdjustable = when (selectedParameter) {
                    CameraParameter.EXPOSURE_COMPENSATION -> state.isAutoExposure
                    CameraParameter.SHUTTER_SPEED, CameraParameter.ISO -> true
                    CameraParameter.FOCUS -> state.minimumFocusDistance > 0f
                    CameraParameter.WHITE_BALANCE -> state.canAdjustWhiteBalance
                },
                isAutoMode = when (selectedParameter) {
                    CameraParameter.SHUTTER_SPEED -> state.isShutterSpeedAuto
                    CameraParameter.ISO -> state.isIsoAuto
                    CameraParameter.FOCUS -> state.isAutoFocus
                    CameraParameter.WHITE_BALANCE ->
                        state.awbMode == android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO
                    CameraParameter.EXPOSURE_COMPENSATION -> false
                },
                showAutoButton = when (selectedParameter) {
                    CameraParameter.SHUTTER_SPEED, CameraParameter.ISO, CameraParameter.WHITE_BALANCE, CameraParameter.FOCUS -> true
                    else -> false
                },
                isAutoModeToggleEnabled = when (selectedParameter) {
                    CameraParameter.WHITE_BALANCE -> state.canAdjustWhiteBalance
                    CameraParameter.FOCUS -> state.minimumFocusDistance > 0f
                    else -> true
                },
                valueStep = state.getExposureCompensationStep(),
                resetValue = selectedParameter.defaultResetValue(),
                showHyperfocalButton = selectedParameter == CameraParameter.FOCUS && state.minimumFocusDistance > 0f,
                hyperfocalEnabled = state.isHyperfocalFocusEnabled,
                hyperfocalDistanceMeters = state.hyperfocalDistanceMeters,
                onHyperfocalToggle = { enabled ->
                    viewModel.setHyperfocalFocusEnabled(enabled)
                },
                onValueChange = { value ->
                    when (selectedParameter) {
                        CameraParameter.EXPOSURE_COMPENSATION -> viewModel.setExposureCompensation((value / state.getExposureCompensationStep()).roundToInt())
                        CameraParameter.SHUTTER_SPEED -> viewModel.setShutterSpeed(value.toLong())
                        CameraParameter.ISO -> viewModel.setIso(value.toInt())
                        CameraParameter.FOCUS -> viewModel.setFocusDistance(value)
                        CameraParameter.WHITE_BALANCE -> viewModel.setAwbTemperature(value.toInt())
                    }
                },
                onAutoModeToggle = {
                    when (selectedParameter) {
                        CameraParameter.SHUTTER_SPEED -> viewModel.setShutterSpeedAuto(!state.isShutterSpeedAuto)
                        CameraParameter.ISO -> viewModel.setIsoAuto(!state.isIsoAuto)
                        CameraParameter.WHITE_BALANCE -> {
                            if (state.canAdjustWhiteBalance) {
                                if (state.awbMode == android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO) {
                                    viewModel.setAwbMode(android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_OFF)
                                } else {
                                    viewModel.setAwbMode(android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO)
                                }
                            }
                        }

                        CameraParameter.FOCUS -> viewModel.setAutoFocus(!state.isAutoFocus)
                        else -> {}
                    }
                },
                modifier = rulerModifier,
            )
        }

        val parameterBar = @Composable {
                activeParameter: CameraParameter?,
                onParameterClick: (CameraParameter) -> Unit ->
            CameraParameterBar(
                state = state,
                selectedParameter = activeParameter,
                onParameterClick = onParameterClick
            )
        }

        val viewfinder = @Composable {
            Card(
                modifier = Modifier
                    // Keep the live GL surface at its final aspect ratio during mode changes.
                    // animateContentSize() resizes its SurfaceView buffer through intermediate
                    // heights while the Camera2 session is paused, visibly stretching the last frame.
                    .width(cardWidth)
                    .height(cardHeight),
                shape = RoundedCornerShape(if (isXpan) XpanViewfinderInnerBorder else 0.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black)
            ) {
                Box(
                    modifier = Modifier
                        .padding(if (isXpan) XpanViewfinderInnerBorder else 0.dp)
                        .fillMaxSize()
                        .background(Color.Black)
                        .onGloballyPositioned { coordinates ->
                            previewBounds = coordinates.boundsInRoot()
                        }
                        .pointerInput(state.availableCameras) {
                            var totalDrag = 0f
                            awaitEachGesture {
                                var gestureStartedInPreviewControl: Boolean? = null
                                var childConsumedDrag = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (gestureStartedInPreviewControl == null) {
                                        val firstPressedChange =
                                            event.changes.firstOrNull { it.pressed }
                                        val currentPreviewBounds = previewBounds
                                        gestureStartedInPreviewControl =
                                            if (firstPressedChange != null && currentPreviewBounds != null) {
                                                val positionInRoot =
                                                    currentPreviewBounds.topLeft + firstPressedChange.position
                                                zoomBarBounds?.contains(positionInRoot) == true ||
                                                    parameterRulerBounds?.contains(positionInRoot) == true
                                            } else {
                                                false
                                            }
                                    }
                                    if (gestureStartedInPreviewControl == true) {
                                        if (event.changes.all { !it.pressed }) {
                                            viewModel.isZooming = false
                                            totalDrag = 0f
                                            break
                                        }
                                        continue
                                    }
                                    if (event.changes.size >= 2) {
                                        // Pinch to zoom
                                        childConsumedDrag = true
                                        totalDrag = 0f
                                        viewModel.isZooming = true
                                        val zoom = event.calculateZoom()
                                        if (zoom != 1f) {
                                            val nextZoom =
                                                (viewModel.zoomRatioByMain * zoom).coerceIn(
                                                    viewModel.globalMinZoom,
                                                    viewModel.globalMaxZoom
                                                )
                                            setZoomWithPreviewTransition(nextZoom)
                                        }
                                        event.changes.forEach { it.consume() }
                                    } else if (event.changes.size == 1) {
                                        // Single finger -> horizontal drag for LUT switch
                                        val change = event.changes[0]
                                        if (change.isConsumed && change.position != change.previousPosition) {
                                            childConsumedDrag = true
                                            totalDrag = 0f
                                        }
                                        if (change.pressed && !childConsumedDrag) {
                                            val dragAmount =
                                                change.position.x - change.previousPosition.x
                                            totalDrag += dragAmount
                                        } else if (!change.pressed) {
                                            // onDragEnd logic
                                            if (!childConsumedDrag && abs(totalDrag) > 100) {
                                                val selectedLut = if (totalDrag > 0) {
                                                    viewModel.switchToPreviousLut()
                                                } else {
                                                    viewModel.switchToNextLut()
                                                }
                                                selectedLut?.let {
                                                    lutNameOverlayState.show(it.getName())
                                                }
                                            }
                                            totalDrag = 0f
                                            viewModel.isZooming = false
                                        }
                                    }

                                    if (event.changes.all { !it.pressed }) {
                                        viewModel.isZooming = false
                                        totalDrag = 0f
                                        break
                                    }
                                }
                            }
                        },
                ) {
                    val activeCameraInfo = state.getCurrentCameraInfo()
                    // Special Video's primary CameraDevice remains the 1x sensor while the
                    // secondary TextureView is promoted to full screen for 0.5x. Keep GL
                    // calibration bound to that real primary sensor instead of changing its
                    // camera identity every time the displayed lens is switched.
                    val previewCameraInfo = if (spatialVideoEnabled) {
                        state.availableCameras.asSequence()
                            .filter {
                                it.lensFacing == CameraCharacteristics.LENS_FACING_BACK &&
                                    it.intrinsicZoomRatio.isFinite() &&
                                    abs(it.intrinsicZoomRatio - 1f) <= 0.15f
                            }
                            .minByOrNull { abs(it.intrinsicZoomRatio - 1f) }
                            ?: activeCameraInfo
                    } else {
                        activeCameraInfo
                    }
                    val previewCameraId = previewCameraInfo?.cameraId ?: state.currentCameraId
                    val calibrationOffset by viewModel.getCameraOrientationOffset(previewCameraId)
                        .collectAsState(initial = 0)
                    val eyeFocusActive = eyeFocusEnabled &&
                        viewModel.isEyeFocusRuntimeAvailable &&
                        state.isAutoFocus &&
                        !state.isHyperfocalFocusEnabled &&
                        (state.focusPoint == null || state.focusPointSource == FocusPointSource.EYE)
                    val portraitMaskActive = state.captureMode == CaptureMode.PHOTO &&
                        state.useRaw &&
                        state.isRawSupported

                    // 相机准备完成后再创建 Surface，首次只打开最终选中的镜头。
                    if (isCameraPrepared) {
                        CameraPreviewGL(
                            aspectRatio = previewAspectRatio,
                            previewSize = previewSize,
                            captureSize = state.currentCaptureSize,
                            captureMode = state.captureMode,
                            cameraId = previewCameraInfo?.cameraId ?: state.currentCameraId,
                            sensorOrientation = previewCameraInfo?.sensorOrientation ?: 0,
                            lensFacing = if (previewCameraInfo?.lensFacing == CameraCharacteristics.LENS_FACING_FRONT) 0 else 1,
                            calibrationOffset = calibrationOffset,
                            baselineLut = viewModel.currentBaselineLutConfig,
                            currentLut = viewModel.currentLutConfig,
                            enginePreviewPlan = viewModel.currentEnginePreviewPlan,
                            baselineColorRecipeParams = currentBaselineRecipeParams,
                            colorRecipeParams = effectiveRecipeParams,
                            focusPoint = state.focusPoint,
                            meteringPoint = state.meteringPoint,
                            canMoveMeteringPoint = state.supportsPointMetering &&
                                !state.isCapturing && activePanel == ActivePanel.NONE,
                            onMeteringPointMove = viewModel::moveMeteringPoint,
                            onFocusPointMove = viewModel::moveFocusPoint,
                            onMeteringPointMerge = viewModel::mergeMeteringPoint,
                            focusPointSource = state.focusPointSource,
                            isFocusLocked = state.isFocusLocked,
                            isFocusing = state.isFocusing,
                            focusSuccess = state.focusSuccess,
                            meteringMode = state.effectiveMeteringMode,
                            onSurfaceTextureReady = { surfaceTexture ->
                                previewSurfaceTexture = surfaceTexture
                            },
                            onSurfaceDestroyed = { surfaceTexture ->
                                previewGlBounds = null
                                cameraGLSurfaceView = null
                                if (previewSurfaceTexture === surfaceTexture) {
                                    previewSurfaceTexture = null
                                }
                                viewModel.closeCamera(surfaceTexture)
                            },
                            onTap = { x, y, w, h ->
                                if (activePanel != ActivePanel.NONE) {
                                    activePanel = ActivePanel.NONE
                                } else if (state.isFocusLocked) {
                                    viewModel.unlockFocus()
                                } else {
                                    viewModel.focusOnPoint(x, y, w, h)
                                }
                            },
                            onLongPress = { x, y, w, h ->
                                if (activePanel != ActivePanel.NONE) {
                                    activePanel = ActivePanel.NONE
                                } else {
                                    viewModel.lockFocusOnPoint(x, y, w, h)
                                }
                            },
                            onHistogramUpdated = { viewModel.handleHistogramUpdate(it) },
                            onMeteringUpdated = { w, l -> viewModel.handleMeteringUpdate(w, l) },
                            onHighlightPointUpdated = { hx, hy ->
                                viewModel.handleHighlightPointUpdate(
                                    hx,
                                    hy
                                )
                            },
                            onEyeFocusInputAvailable = if (
                                eyeFocusActive || portraitMaskActive
                            ) {
                                viewModel::handleEyeFocusInputUpdate
                            } else {
                                null
                            },
                            onFirstPreviewFrame = viewModel::onFirstPreviewFrame,
                            onGLSurfaceViewReady = {
                                viewModel.attachCameraGLSurfaceView(it)
                                cameraGLSurfaceView = it
                            },
                            onPreviewBoundsChanged = { previewGlBounds = it },
                            livePhotoRecorder = viewModel.livePhotoRecorder,
                            videoLogProfile = state.videoConfig.logProfile,
                            isEyeFocusBusy = viewModel.isEyeFocusBusy,
                            isAutoFocus = state.isAutoFocus,
                            focusPeakingEnabled = focusPeakingEnabled && !state.isHyperfocalFocusEnabled,
                            stabilizationCoordinator = viewModel.realtimeStabilizationCoordinator,
                            stabilizationInputSize = if (
                                state.captureMode == CaptureMode.VIDEO &&
                                state.videoConfig.stabilizationMode ==
                                    VideoStabilizationMode.ENHANCED
                            ) {
                                state.videoCapabilities
                                    .enhancedStabilizationInputSizesByResolution[
                                        state.videoConfig.resolution
                                    ] ?: previewSize
                            } else {
                                previewSize
                            },
                            photoPreviewStabilizationEnabled =
                                state.photoPreviewStabilizationEnabled,
                            videoPreviewStabilizationEnabled =
                                state.videoConfig.stabilizationMode ==
                                    VideoStabilizationMode.ENHANCED,
                            videoPreviewStabilizationStrength =
                                state.videoConfig.enhancedStabilizationStrength,
                            videoPreviewStabilizationLookahead =
                                state.videoConfig.enhancedStabilizationLookahead,
                            modifier = Modifier
                                .fillMaxSize()
                                .offset(
                                    y = if (isVideoMode && !isOpenGateVideo) 3.dp else 0.dp
                                )
                        )
                    }


                    if (
                        state.captureMode == CaptureMode.VIDEO &&
                        spatialVideoEnabled
                    ) {
                        val specialPreviewShape = RoundedCornerShape(12.dp)
                        val selectedZoomRatio = activeCameraInfo?.intrinsicZoomRatio ?: 1f
                        val isSpatialVideoUltraWideSelected =
                            activeCameraInfo?.lensFacing == CameraCharacteristics.LENS_FACING_BACK &&
                                selectedZoomRatio.isFinite() &&
                                selectedZoomRatio in 0.2f..0.85f
                        val showUltraWideInset =
                            spatialVideoShowUltraWidePreview && !isSpatialVideoUltraWideSelected
                        val showUltraWideFullScreen = isSpatialVideoUltraWideSelected
                        val previewModifier = when {
                            showUltraWideFullScreen -> Modifier.fillMaxSize()
                            showUltraWideInset -> Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 12.dp, bottom = 104.dp)
                                .size(width = 112.dp, height = 150.dp)
                            // Keep the TextureView and its SurfaceTexture alive while hidden.
                            // This lets the standby camera keep producing frames before a tap.
                            // A tiny non-zero view keeps SurfaceTexture attached without
                            // placing an invisible full-screen AndroidView over the camera UI.
                            else -> Modifier
                                .align(Alignment.TopStart)
                                .size(1.dp)
                        }
                        Box(
                            modifier = previewModifier
                                .graphicsLayer {
                                    alpha = if (showUltraWideFullScreen || showUltraWideInset) 1f else 0f
                                }
                                .then(
                                    if (showUltraWideInset) Modifier
                                        .clip(specialPreviewShape)
                                        .border(1.dp, Color.White.copy(alpha = 0.85f), specialPreviewShape)
                                    else Modifier.clipToBounds()
                                )
                                .background(Color.Black)
                        ) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { viewContext ->
                                    TextureView(viewContext).apply {
                                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                            override fun onSurfaceTextureAvailable(
                                                surface: SurfaceTexture,
                                                width: Int,
                                                height: Int,
                                            ) {
                                                viewModel.setSpatialVideoPreviewSurfaceTexture(surface)
                                                this@apply.post {
                                                    updateDualCaptureTextureTransform(
                                                        latestSpatialVideoPreviewSize.value,
                                                        latestSpatialVideoPreviewOrientation.value,
                                                    )
                                                }
                                            }

                                            override fun onSurfaceTextureSizeChanged(
                                                surface: SurfaceTexture,
                                                width: Int,
                                                height: Int,
                                            ) {
                                                this@apply.post {
                                                    updateDualCaptureTextureTransform(
                                                        latestSpatialVideoPreviewSize.value,
                                                        latestSpatialVideoPreviewOrientation.value,
                                                    )
                                                }
                                                viewModel.setSpatialVideoPreviewSurfaceTexture(surface)
                                            }

                                            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                                viewModel.setSpatialVideoPreviewSurfaceTexture(null)
                                                return true
                                            }

                                            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                                        }
                                    }
                                },
                                update = { textureView ->
                                    textureView.updateDualCaptureTextureTransform(
                                        spatialVideoPreviewStreamSize,
                                        spatialVideoPreviewSensorOrientation,
                                    )
                                },
                            )
                            if (showUltraWideInset) {
                                Text(
                                    text = "0.5x",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(6.dp)
                                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(5.dp))
                                        .padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    if (state.captureMode == CaptureMode.VIDEO) {
                        state.videoConfig.dualAspectRatio?.let { secondaryRatio ->
                            DualAspectPreviewOverlay(
                                ratio = secondaryRatio,
                                isRecording = state.videoRecordingState.isRecording,
                                modifier = Modifier.fillMaxSize().padding(top = topSafePadding + 58.dp, bottom = 116.dp)
                            )
                        }
                    }

                    if (state.captureMode == CaptureMode.VIDEO && dualCaptureEnabled) {
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            val insetPadding = 12.dp
                            // VideoTopBar's 30 dp icon pill is centered in its 40 dp row,
                            // which sits 13 dp below the safe-area top: its bottom edge is
                            // 48 dp below that inset. Keep the PiP preview exactly 5 dp below it.
                            val pipTopPadding = topSafePadding + 53.dp
                            val dualInsetAspectRatio = 3f / 4f
                            val availablePipHeight = (maxHeight - pipTopPadding - insetPadding).coerceAtLeast(1.dp)
                            val insetWidth = minOf(maxWidth * 0.33f, availablePipHeight * dualInsetAspectRatio)
                            val insetHeight = insetWidth / dualInsetAspectRatio
                            val insetOuterWidth = insetWidth + insetPadding * 2
                            val insetOuterHeight = pipTopPadding + insetHeight + insetPadding
                            val density = LocalDensity.current
                            val pipModifier = Modifier
                                .padding(
                                    top = pipTopPadding,
                                    start = insetPadding,
                                    end = insetPadding,
                                    bottom = insetPadding,
                                )
                                .width(insetWidth)
                                .aspectRatio(dualInsetAspectRatio)
                                .align(Alignment.TopEnd)
                                .offset {
                                    IntOffset(
                                        dualCaptureDragOffset.x.roundToInt(),
                                        dualCaptureDragOffset.y.roundToInt(),
                                    )
                                }
                                .pointerInput(dualCaptureLayout, maxWidth, maxHeight) {
                                    if (dualCaptureLayout == DualCaptureLayout.PICTURE_IN_PICTURE) {
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            val maxX = -(maxWidth.toPx() - insetOuterWidth.toPx()).coerceAtLeast(0f)
                                            val maxY = (maxHeight.toPx() - insetOuterHeight.toPx()).coerceAtLeast(0f)
                                            val next = Offset(
                                                (dualCaptureDragOffset.x + dragAmount.x).coerceIn(maxX, 0f),
                                                (dualCaptureDragOffset.y + dragAmount.y).coerceIn(0f, maxY),
                                            )
                                            dualCaptureDragOffset = next
                                            val normalizedX = (maxWidth.toPx() - insetWidth.toPx() -
                                                insetPadding.toPx() + next.x) /
                                                maxWidth.toPx().coerceAtLeast(1f)
                                            val normalizedY = (pipTopPadding.toPx() + next.y) /
                                                maxHeight.toPx().coerceAtLeast(1f)
                                            val normalizedWidth = insetWidth.toPx() /
                                                maxWidth.toPx().coerceAtLeast(1f)
                                            viewModel.setDualCapturePosition(normalizedX, normalizedY, normalizedWidth)
                                        }
                                    }
                                }
                            LaunchedEffect(dualCaptureEnabled, dualCaptureLayout, maxWidth, maxHeight, insetWidth, pipTopPadding) {
                                if (dualCaptureEnabled &&
                                    dualCaptureLayout == DualCaptureLayout.PICTURE_IN_PICTURE &&
                                    !state.videoRecordingState.isRecording &&
                                    !state.videoRecordingState.isProcessing
                                ) {
                                    val normalizedX = with(density) {
                                        (maxWidth - insetWidth - insetPadding).toPx() /
                                            maxWidth.toPx().coerceAtLeast(1f)
                                    }
                                    val normalizedY = with(density) {
                                        pipTopPadding.toPx() / maxHeight.toPx().coerceAtLeast(1f)
                                    }
                                    val normalizedWidth = with(density) {
                                        insetWidth.toPx() / maxWidth.toPx().coerceAtLeast(1f)
                                    }
                                    viewModel.setDualCapturePosition(normalizedX, normalizedY, normalizedWidth)
                                }
                            }

                            val frontPreviewModifier = when (dualCaptureLayout) {
                                DualCaptureLayout.PICTURE_IN_PICTURE -> pipModifier
                                DualCaptureLayout.TOP_BOTTOM -> Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .fillMaxHeight(0.5f)
                                DualCaptureLayout.SIDE_BY_SIDE -> Modifier
                                    .align(Alignment.CenterEnd)
                                    .fillMaxWidth(0.5f)
                                    .fillMaxHeight()
                            }
                            val pipShape = RoundedCornerShape(
                                if (dualCaptureLayout == DualCaptureLayout.PICTURE_IN_PICTURE) 16.dp else 0.dp
                            )
                            Box(
                                modifier = frontPreviewModifier
                                    .clip(pipShape)
                                    .border(
                                        width = if (dualCaptureLayout == DualCaptureLayout.PICTURE_IN_PICTURE) 1.dp else 0.dp,
                                        color = Color.White.copy(alpha = 0.85f),
                                        shape = pipShape,
                                    ),
                            ) {
                                AndroidView(
                                    modifier = Modifier.fillMaxSize(),
                                    factory = { context ->
                                        TextureView(context).apply {
                                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                                override fun onSurfaceTextureAvailable(
                                                    surface: SurfaceTexture,
                                                    width: Int,
                                                    height: Int,
                                                ) {
                                                    viewModel.setDualCapturePreviewSurfaceTexture(surface)
                                                    this@apply.post {
                                                        updateDualCaptureTextureTransform(
                                                            latestDualCaptureStreamSize.value,
                                                            latestDualCaptureSensorOrientation.value,
                                                        )
                                                    }
                                                }

                                                override fun onSurfaceTextureSizeChanged(
                                                    surface: SurfaceTexture,
                                                    width: Int,
                                                    height: Int,
                                                ) {
                                                    this@apply.post {
                                                        updateDualCaptureTextureTransform(
                                                            latestDualCaptureStreamSize.value,
                                                            latestDualCaptureSensorOrientation.value,
                                                        )
                                                    }
                                                    viewModel.setDualCapturePreviewSurfaceTexture(surface)
                                                }

                                                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                                    viewModel.setDualCapturePreviewSurfaceTexture(null)
                                                    return true
                                                }

                                                // The TextureView is reused when Dual Capture switches between
                                                // front and ultra-wide cameras. A one-shot boolean leaves the
                                                // previous producer transform active for the next camera.
                                                // Re-apply only when the source, stream geometry, sensor
                                                // orientation, or producer transform actually changes.
                                                private var lastProducerTransformSignature: Long? = null

                                                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                                                    val streamSize = latestDualCaptureStreamSize.value
                                                    val sensorOrientation =
                                                        latestDualCaptureSensorOrientation.value
                                                    val source = latestDualCaptureSource.value
                                                    val producerMatrix = FloatArray(16)
                                                    if (runCatching {
                                                            surface.getTransformMatrix(producerMatrix)
                                                        }.isFailure
                                                    ) {
                                                        return
                                                    }

                                                    var signature =
                                                        if (source == DualCaptureSource.FRONT) 1L else 2L
                                                    signature = signature * 31L + (streamSize?.width ?: 0)
                                                    signature = signature * 31L + (streamSize?.height ?: 0)
                                                    signature = signature * 31L + sensorOrientation
                                                    signature = signature * 31L + producerMatrix[0].toBits()
                                                    signature = signature * 31L + producerMatrix[1].toBits()
                                                    signature = signature * 31L + producerMatrix[4].toBits()
                                                    signature = signature * 31L + producerMatrix[5].toBits()

                                                    if (lastProducerTransformSignature != signature) {
                                                        lastProducerTransformSignature = signature
                                                        updateDualCaptureTextureTransform(
                                                            streamSize,
                                                            sensorOrientation,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    update = { textureView ->
                                        textureView.updateDualCaptureTextureTransform(
                                            dualCaptureStreamSize,
                                            dualCaptureSensorOrientationDegrees,
                                        )

                                    },
                                )
                                IconButton(
                                    onClick = {
                                        if (state.videoRecordingState.isRecording ||
                                            state.videoRecordingState.isProcessing
                                        ) {
                                            android.widget.Toast.makeText(
                                                context,
                                                context.getString(R.string.dual_capture_stop_recording_to_change),
                                                android.widget.Toast.LENGTH_SHORT,
                                            ).show()
                                        } else {
                                            val nextSource = if (dualCaptureSource == DualCaptureSource.FRONT) {
                                                DualCaptureSource.ULTRA_WIDE
                                            } else {
                                                DualCaptureSource.FRONT
                                            }
                                            if (!viewModel.isDualCaptureSourceSupported(nextSource)) {
                                                android.widget.Toast.makeText(
                                                    context,
                                                    if (nextSource == DualCaptureSource.FRONT) {
                                                        "Front camera is not available with this rear camera."
                                                    } else {
                                                        "Rear ultra-wide is not available with this camera."
                                                    },
                                                    android.widget.Toast.LENGTH_SHORT,
                                                ).show()
                                            } else {
                                                dualCaptureDragOffset = Offset.Zero
                                                viewModel.setDualCaptureSource(nextSource)
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(8.dp)
                                        .size(40.dp)
                                        .background(
                                            Color.Black.copy(alpha = 0.58f),
                                            CircleShape,
                                        ),
                                ) {
                                    Icon(
                                        imageVector = AppIcons.Cameraswitch,
                                        contentDescription = if (dualCaptureSource == DualCaptureSource.FRONT) {
                                            "Switch inset to rear ultra-wide camera"
                                        } else {
                                            "Switch inset to front camera"
                                        },
                                        tint = Color.White,
                                        modifier = Modifier.size(21.dp),
                                    )
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = if (previewTransitionActive || previewTransitionCoverFractionState.value > 0.001f) 1f else 0f
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(0.5f)
                                .align(Alignment.TopCenter)
                                .graphicsLayer {
                                    translationY = -size.height * (1f - previewTransitionCoverFractionState.value)
                                }
                                .background(Color.Black)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(0.5f)
                                .align(Alignment.BottomCenter)
                                .graphicsLayer {
                                    translationY = size.height * (1f - previewTransitionCoverFractionState.value)
                                }
                                .background(Color.Black)
                        )
                    }


                    val showLivePhotoIndicator =
                        state.captureMode == CaptureMode.PHOTO && useLivePhoto
                    val showVirtualApertureIndicator =
                        state.captureMode != CaptureMode.VIDEO &&
                            state.isVirtualApertureEnabled
                    if (showLivePhotoIndicator || showVirtualApertureIndicator) {
                        Column(
                            modifier = Modifier
                                .padding(
                                    top = viewfinderTopOverlayPadding + 8.dp,
                                    end = 12.dp
                                )
                                .align(Alignment.TopEnd),
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (showLivePhotoIndicator) {
                                Surface(
                                    color = Color.Black.copy(alpha = 0.8f),
                                    shape = CircleShape
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_live_photo),
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier
                                            .padding(4.dp)
                                            .size(18.dp)
                                    )
                                }
                            }
                            if (showVirtualApertureIndicator) {
                                Surface(
                                    color = Color.Black.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = stringResource(
                                            R.string.virtual_aperture_viewfinder_hint,
                                            formatVirtualApertureFStop(state.virtualAperture)
                                        ),
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Medium,
                                        style = TextStyle(shadow = ViewfinderTextShadow),
                                        modifier = Modifier.padding(
                                            horizontal = 6.dp,
                                            vertical = 3.dp
                                        )
                                    )
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        multipleExposureState.previewBitmap?.let { previewBitmap ->
                            Image(
                                bitmap = previewBitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .autoRotate(matchParentSize = true),
                                alpha = 0.45f,
                                contentScale = ContentScale.Crop
                            )
                        }

                        // 实时直方图 (Overlaid on preview if enabled)
                        if (isPhotoStyleMode && state.histogram != null && viewModel.showHistogram) {
                            HistogramView(
                                histogram = state.histogram,
                                modifier = Modifier
                                    .padding(
                                        start = 8.dp,
                                        top = viewfinderTopOverlayPadding + 8.dp
                                    )
                                    .size(80.dp, 40.dp)
                                    .align(Alignment.TopStart)
                                    .autoRotate(dx = -20.dp, dy = 20.dp),
                            )
                        }

                        // 网格线覆盖
                        if (state.showGrid) {
                            GridOverlay(
                                aspectRatio = previewAspectRatio,
                                style = state.gridStyle,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        // 水平仪覆盖
                        if (showLevelIndicator) {
                            LevelIndicatorOverlay(
                                aspectRatio = previewAspectRatio,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        // 倒计时覆盖
                        if (state.countdownValue > 0) {
                            CountdownOverlay(
                                countdownValue = state.countdownValue,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        if (state.burstCapturing && burstCapturingCount > 0) {
                            BurstCaptureOverlay(
                                count = burstCapturingCount,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = viewfinderTopOverlayPadding)
                            )
                        }

                        if (useMultipleExposure && state.captureMode == CaptureMode.PHOTO) {
                            MultipleExposureOverlay(
                                state = multipleExposureState,
                                onFinish = { viewModel.finishMultipleExposureSession() },
                                onUndo = { viewModel.undoLastMultipleExposureFrame() },
                                onCancel = { viewModel.cancelMultipleExposureSession() },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = viewfinderTopOverlayPadding)
                            )
                        }

                    }

                    if (isXpan) {
                        CornerOverlay(
                            modifier = Modifier.fillMaxSize(),
                            radius = XpanViewfinderInnerBorder,
                            color = Color.Black
                        )
                    }

                    LutNameOverlay(
                        state = lutNameOverlayState,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }

            if (isXpan) {
                // Center both side bars in the space above the capture controls, which
                // overlap the lower part of the XPAN viewfinder.
                val sideControlsBottom = minOf(
                    viewfinderTop + cardHeight,
                    contentHeight - CameraControlsLayoutDefaults.CaptureAreaHeight -
                        CameraControlsLayoutDefaults.CaptureClearance +
                        CameraControlsLayoutDefaults.VerticalOffset
                )
                val sideControlsHeight = (sideControlsBottom - viewfinderTop)
                    .coerceIn(0.dp, cardHeight)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(cardHeight)
                        .padding(bottom = cardHeight - sideControlsHeight),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .width(xpanSideWidth),
                        contentAlignment = Alignment.Center
                    ) {
                        ZoomControlBarVerticel(
                            viewModel = viewModel,
                            zoomRatio = viewModel.zoomRatioByMain,
                            availableCameras = state.availableCameras,
                            currentCameraId = state.getCurrentCameraInfo()?.cameraId ?: "0",
                            onZoomChange = { animateZoomStopTo(it) },
                            onPreviewZoomChange = viewModel::setZoomRatio,
                            onZoomStopClick = { animateZoomStopTo(it) },
                            onLensSwitch = { lensId -> switchToLensWithPreviewTransition(lensId) },
                            onFilterClick = {
                                if (isVideoMode) {
                                    // Open the regular LUT selector; its Edit action opens adjustments.
                                    activePanel = if (activePanel == ActivePanel.FILTERS) {
                                        ActivePanel.NONE
                                    } else {
                                        ActivePanel.FILTERS
                                    }
                                } else {
                                    activePanel = if (activePanel == ActivePanel.EDIT) ActivePanel.NONE else ActivePanel.EDIT
                                }
                            },
                            onFilterButtonBoundsChanged = { bounds ->
                                filterButtonBounds = bounds
                            },
                        )
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(xpanSideWidth),
                        contentAlignment = Alignment.Center
                    ) {
                        // Manual parameter controls are intentionally hidden in the camera UI.
                    }
                }
            }
        }


        val controls = @Composable { modifier: Modifier ->
            Controls(
                state = state,
                uiStyle = uiStyle,
                viewModel = viewModel,
                galleryViewModel = galleryViewModel,
                latestPhoto = latestPhoto,
                useMultipleExposure = useMultipleExposure,
                multipleExposureState = multipleExposureState,
                fastShutterEnabled = oppoPhotoSettings.fastShutterEnabled,
                onGalleryThumbnailBoundsChanged = { bounds ->
                    galleryThumbnailBounds = bounds
                },
                onCaptureButtonBoundsChanged = { bounds ->
                    captureButtonBounds = bounds
                },
                onSwitchCameraClick = ::switchCameraWithPreviewTransition,
                onCaptureModeSelected = ::setShootingModeImmediately,
                modeSwitchEnabled = !previewTransitionActive,
                modeSwitcherBackdrop = modeSwitcherBackdrop,
                onVideoEditSwipeUp = {
                    if (state.captureMode == CaptureMode.VIDEO &&
                        !state.videoRecordingState.isRecording &&
                        !state.videoRecordingState.isProcessing &&
                        activePanel == ActivePanel.NONE
                    ) {
                        videoEditOpenedFromModePill = true
                        activePanel = ActivePanel.EDIT
                    }
                },
                onVideoPillBoundsChanged = { bounds -> videoEditPillBounds = bounds },
                onCaptureTap = {
                    val shouldDebounceRawCapture =
                        state.useRaw && state.captureMode == CaptureMode.PHOTO
                    if (shouldDebounceRawCapture) {
                        if (rawCaptureTapLocked) {
                            return@Controls
                        }
                        rawCaptureTapLocked = true
                        scope.launch {
                            delay(RawCaptureTapDebounceMillis)
                            rawCaptureTapLocked = false
                        }
                    }

                    if (enableDevelopAnimation && state.captureMode == CaptureMode.PHOTO) {
                        viewModel.glSurfaceView?.capturePreviewFrame(maxLongEdge = 1440) { bitmap ->
                            if (scope.isActive && enableDevelopAnimation &&
                                currentCaptureModeForEffects == CaptureMode.PHOTO
                            ) {
                                pendingCaptureAnimationBitmap?.recycleIfAlive()
                                pendingCaptureAnimationBitmap = bitmap
                            } else {
                                bitmap.recycleIfAlive()
                            }
                        }
                    }
                    viewModel.capture()
                },
                onGalleryClick = {
                    onGalleryClick()
                },
                sideControlInset = if (isXpan) {
                    (xpanSideWidth - CameraControlsLayoutDefaults.SideButtonSize) / 2
                } else {
                    null
                },
                modifier = modifier
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            // Capture only the camera scene behind the bottom controls. The segmented
            // selector stays outside this layer so its glass lens cannot sample itself.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .prismalGlassLayer(modeSwitcherBackdrop)
                    .paint(backgroundPainter, contentScale = ContentScale.Crop)
                    .navigationBarsPadding(),
            ) {
                // The viewfinder is always the bottom layer. Its selected aspect ratio is fitted
                // to the full available area before camera controls are overlaid.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(animatedViewfinderHeight)
                        .align(Alignment.TopCenter)
                        .offset(y = animatedViewfinderTop + (if (!usesPhotoStyleViewfinder) VideoViewfinderVerticalOffset else 0.dp))
                        .clipToBounds()
                        .onGloballyPositioned { coordinates ->
                            viewfinderAreaBounds = coordinates.boundsInRoot()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .requiredSize(cardWidth, cardHeight)
                    ) {
                        viewfinder()
                    }
                    if (uiStyle == "OPPO" && activePanel == ActivePanel.NONE && !isXpan) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .height(CameraControlsLayoutDefaults.ZoomBarHeight)
                                .offset(y = -VideoViewfinderAdditionalOffset),
                            contentAlignment = Alignment.Center
                        ) {
                            zoomBarWithDualCaptureLayout()
                        }
                    }
                    if (canShowZoomJoystick) {
                        Box(
                            modifier = Modifier
                                .width(cardWidth)
                                .requiredHeight(cardHeight)
                                .align(Alignment.Center)
                                .offset(y = -VideoViewfinderAdditionalOffset)
                        ) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = 12.dp)
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.68f))
                                    .border(1.dp, Color.White.copy(alpha = 0.78f), CircleShape)
                                    .clickable { zoomJoystickOpen = !zoomJoystickOpen },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("🕹", fontSize = 23.sp)
                            }
                            if (zoomJoystickOpen) {
                                ZoomPanJoystick(
                                    modifier = Modifier.align(Alignment.Center),
                                    onPanBy = viewModel::panZoomBy,
                                )
                            }
                        }
                    }
                }
    
                if (!isVideoMode && activePanel != ActivePanel.SETTINGS) topBar()
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
            ) {
                CameraBottomControlsLayout(
                    viewfinderBottom = viewfinderTop + cardHeight,
                    modifier = Modifier.fillMaxSize(),
                    zoomBar = {
                        // Panel visibility changes the zoom content, not the space used to
                        // position the parameter row beneath it. XPAN has no horizontal bar.
                        if (uiStyle != "OPPO" || isXpan) {
                            Box(
                                modifier = if (isXpan) Modifier else Modifier
                                    .fillMaxWidth()
                                    .height(CameraControlsLayoutDefaults.ZoomBarHeight)
                            ) {
                                zoomBarWithDualCaptureLayout()
                            }
                        } else {
                            Spacer(modifier = Modifier.height(0.dp))
                        }
                    },
                    // Hide the manual controls (EV/Tv/ISO/AF/AWB and their value ruler)
                    // in both Photo and Video modes without changing capture functionality.
                    parameterRuler = {},
                    parameterBar = {},
                    captureControls = {
                        // The video-edit glass panel occupies the lower camera area.
                        // Remove the complete shutter/gallery/mode-switcher cluster while it is open.
                        if (activePanel != ActivePanel.EDIT || !isVideoMode) {
                            controls(Modifier.fillMaxWidth())
                        }
                    }
                )
            }

            // The toolbar is a screen-level overlay, not a child of the ratio-sized
            // viewfinder. Its anchor is calculated from the 9:16 layout and never moves
            // when the selected video aspect ratio changes.
            if (isVideoMode && activePanel != ActivePanel.SETTINGS) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .offset(y = videoTopBarAnchorTop + 10.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    topBar()
                }
            }
        }

        if (isVideoMode && videoSettingsPanelOpen &&
            !state.videoRecordingState.isRecording &&
            !state.videoRecordingState.isProcessing &&
            activePanel == ActivePanel.NONE
        ) {
            FloatingVideoSettingsPanel(
                isOpen = true,
                videoConfig = state.videoConfig,
                videoCapabilities = state.videoCapabilities,
                onResolutionSelected = viewModel::setVideoResolution,
                onFpsSelected = viewModel::setVideoFps,
                onDismiss = { videoSettingsPanelOpen = false },
                glassTransparency = cameraMenuTransparency,
                glassBlurStrength = cameraMenuBlurRadius,
                onGlassBoundsChanged = { videoSettingsGlassBounds = it },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    // Keep the floating selector below the video top-bar controls.
                    .padding(top = 112.dp)
            )
        } else {
            SideEffect { videoSettingsGlassBounds = null }
        }

        val panelDismissBounds = if (isXpan) {
            viewfinderAreaBounds ?: previewBounds
        } else {
            previewBounds
        }

        if (activePanel != ActivePanel.NONE) {
            PanelDismissPreviewOverlay(
                bounds = panelDismissBounds,
                parentBounds = cameraScreenBounds,
                onDismiss = { activePanel = ActivePanel.NONE }
            )
        }

        // TopSheet for settings
        CameraTopSheet(
            visible = activePanel == ActivePanel.SETTINGS,
            glassTransparency = cameraMenuTransparency,
            glassBlurStrength = cameraMenuBlurRadius,
            onGlassBoundsChanged = { settingsGlassBounds = it },
            captureMode = state.captureMode,
            aspectRatio = state.aspectRatio,
            topSheetAspectRatios = topSheetAspectRatios,
            onAspectRatioChange = { runPreviewTransition { viewModel.setAspectRatio(it) } },
            videoAspectRatio = state.videoConfig.aspectRatio,
            onVideoAspectRatioChange = { runPreviewTransition { viewModel.setVideoAspectRatio(it) } },
            dualVideoAspectRatio = state.videoConfig.dualAspectRatio,
            onDualVideoAspectToggle = {
                if (state.videoRecordingState.isRecording || state.videoRecordingState.isProcessing) {
                    android.widget.Toast.makeText(context, "Stop recording before changing Dual Aspect.", android.widget.Toast.LENGTH_SHORT).show()
                } else if (state.videoConfig.dualAspectRatio == null && (spatialVideoEnabled || dualCaptureEnabled)) {
                    android.widget.Toast.makeText(
                        context,
                        "Turn off Special Video and Dual Capture before enabling Dual Aspect.",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    viewModel.setDualVideoAspectRatio(
                        if (state.videoConfig.dualAspectRatio == null)
                            DualVideoAspectRatio.defaultFor(state.videoConfig.aspectRatio)
                        else null
                    )
                }
            },
            onDualVideoAspectRatioChange = viewModel::setDualVideoAspectRatio,
            videoLogProfile = state.videoConfig.logProfile,
            availableVideoLogProfiles = state.videoCapabilities.availableLogProfiles,
            onVideoLogProfileChange = { viewModel.setVideoLogProfile(it) },
            videoBitrate = state.videoConfig.bitrate,
            onVideoBitrateChange = { viewModel.setVideoBitrate(it) },
            useLivePhoto = useLivePhoto,
            onLivePhotoToggle = { viewModel.setUseLivePhoto(!useLivePhoto) },
            timerSeconds = state.timerSeconds,
            onTimerToggle = viewModel::toggleTimer,
            onPhotoFilterClick = { activePanel = ActivePanel.FILTERS },
            onPhotoDepthClick = {
                android.widget.Toast.makeText(
                    context,
                    "Depth map is generated with Refocus captures",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            },
            videoTorchEnabled = state.videoConfig.torchEnabled,
            onVideoTorchToggle = {
                viewModel.setVideoTorchEnabled(!state.videoConfig.torchEnabled)
            },
            videoHdrEnabled = videoHdrEnabled,
            onVideoHdrToggle = { viewModel.setVideoHdrEnabled(!videoHdrEnabled) },
            videoStabilizationMode = state.videoConfig.stabilizationMode,
            videoActionModeAvailable =
                state.videoCapabilities.availableStabilizationModes.contains(VideoStabilizationMode.ENHANCED) &&
                    !state.videoRecordingState.isRecording &&
                    !state.videoRecordingState.isProcessing,
            onVideoStabilizationModeChange = viewModel::setVideoStabilizationMode,
            spatialVideoEnabled = spatialVideoEnabled,
            spatialVideoSupported = true,
            onVideoSpatialVideoClick = {
                if (state.videoRecordingState.isRecording || state.videoRecordingState.isProcessing) {
                    android.widget.Toast.makeText(
                        context,
                        "Stop recording before changing Special Video.",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                } else if (!spatialVideoEnabled && state.videoConfig.dualAspectRatio != null) {
                    android.widget.Toast.makeText(
                        context,
                        "Turn off Dual Aspect before enabling Special Video.",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    viewModel.setSpatialVideoEnabled(!spatialVideoEnabled)
                }
            },
            spatialVideoShowUltraWidePreview = spatialVideoShowUltraWidePreview,
            onSpatialVideoPreviewToggle = {
                if (state.videoRecordingState.isRecording || state.videoRecordingState.isProcessing) {
                    android.widget.Toast.makeText(
                        context,
                        "Stop recording before changing the 0.5x preview.",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    viewModel.setSpatialVideoShowUltraWidePreview(!spatialVideoShowUltraWidePreview)
                }
            },
            onVideoDualCaptureClick = {
                if (state.videoRecordingState.isRecording || state.videoRecordingState.isProcessing) {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.dual_capture_stop_recording_to_change),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                } else if (!dualCaptureEnabled && state.videoConfig.dualAspectRatio != null) {
                    android.widget.Toast.makeText(
                        context,
                        "Turn off Dual Aspect before enabling Dual Capture.",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                } else if (!dualCaptureEnabled && !viewModel.isDualCaptureSupported()) {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.dual_capture_not_supported),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                } else {
                    dualCaptureEnabled = !dualCaptureEnabled
                    dualCaptureDragOffset = Offset.Zero
                    viewModel.setDualCaptureEnabled(dualCaptureEnabled)
                }
            },
            onVideoEditClick = {
                videoEditOpenedFromModePill = false
                activePanel = ActivePanel.EDIT
            },
            videoCodec = videoCodec,
            onVideoCodecChange = { viewModel.setVideoCodec(it) },
            videoAudioInputId = state.videoConfig.audioInputId,
            videoAudioInputOptions = videoAudioInputOptions,
            onVideoAudioInputChange = { viewModel.setVideoAudioInputId(it) },
            useRaw = useRaw && state.isRawSupported,
            isRawSupported = state.isRawSupported,
            rawDcpId = rawDcpId,
            rawDcpIdsByLens = rawDcpIdsByLens,
            rawDcpLensOptions = rawDcpLensOptions(state.availableCameras),
            availableDcps = viewModel.availableDcps,
            rawHncsFilmCurveMode = rawHncsFilmCurveMode,
            rawRenderingEngine = rawColorEngine,
            rawToneMappingParameters = rawToneMappingParameters,
            rawSpectralFilmSelection = rawSpectralFilmSelection ?: SpectralFilmSelection(rawSpectralFilmStock ?: "kodak_portra_400"),
            rawSpectralFilmPrint = rawSpectralFilmPrint ?: "kodak_portra_endura",
            ultraHdrEnabled = ultraHdrEnabled,
            onUltraHdrToggle = viewModel::setUltraHdrGainMapEnabled,
            photoPreviewStabilizationEnabled =
                state.photoPreviewStabilizationEnabled,
            photoPreviewStabilizationAvailable =
                viewModel.realtimeStabilizationCoordinator.isCurrentCameraSupported,
            onPhotoPreviewStabilizationChange =
                viewModel::setPhotoPreviewStabilizationEnabled,
            onRawDcpChange = { viewModel.setRawDcpId(it) },
            onRawDcpIdsByLensChange = { viewModel.setRawDcpIdsByLens(it) },
            onRawHncsFilmCurveModeChange = { viewModel.setRawHncsFilmCurveMode(it) },
            onImportRawDcp = { dcpImportLauncher.launch(arrayOf("*/*")) },
            onDeleteRawDcp = { dcp ->
                viewModel.deleteRawDcp(dcp.id) { success ->
                    android.widget.Toast.makeText(
                        context,
                        if (success) R.string.raw_dcp_delete_success else R.string.raw_dcp_delete_failed,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onRawColorEngineChange = { viewModel.setRawColorEngine(it) },
            onRawToneMappingParametersChange = { viewModel.setRawToneMappingParameters(it) },
            onRawSpectralFilmSelectionChange = { viewModel.setRawSpectralFilmSelection(it) },
            onRawSpectralFilmPrintChange = { viewModel.setRawSpectralFilmPrint(it) },
            meteringMode = state.meteringMode,
            onMeteringModeChange = { viewModel.setMeteringMode(it) },
            onFilterManageClick = {
                activePanel = ActivePanel.NONE
                onFilterManagementClick(null)
            },
            onFrameManageClick = {
                activePanel = ActivePanel.NONE
                onFrameManagementClick()
            },
            onPresetManageClick = {
                activePanel = ActivePanel.NONE
                onPresetManagementClick()
            },
            onToolboxClick = {
                activePanel = ActivePanel.NONE
                onToolboxClick()
            },
            onMoreSettingsClick = {
                activePanel = ActivePanel.NONE
                onSettingsClick()
            },
            useJpgMax = useJpgMax,
            onJpgMaxToggle = {
                viewModel.setUseJpgMax(it)
            },
            useMultipleExposure = useMultipleExposure,
            onMultipleExposureToggle = { viewModel.setUseMultipleExposure(it) },
            oppoPhotoSettings = oppoPhotoSettings,
            onOppoPhotoSettingsChange = viewModel::updateOppoPhotoSettings,
            refocusAvailable = state.minimumFocusDistance > 0f,
            contentTopPadding = CameraTopBarBaseTopPadding + topSafePadding,
        )

        // During navigation measurement or window resizing, the viewport can be
        // shorter than the toolbar. The panel then has no available vertical space.
        val filterPanelTop = minOf(
            if (!isXpan && !isVideoMode) topBarHeight else 0.dp,
            maxHeight
        )
        val fallbackFilterPanelBottom = when {
            isXpan -> maxHeight - 48.dp
            isVideoMode -> maxHeight - 170.dp
            else -> topBarHeight + cardHeight - 48.dp
        }
        val filterPanelAnchorY = if (isXpan) {
            captureButtonBounds?.top
        } else {
            filterButtonBounds?.bottom
        }
        val requestedFilterPanelBottom = filterPanelAnchorY?.let { anchorY ->
            val parentTop = cameraScreenBounds?.top ?: 0f
            with(density) { (anchorY - parentTop).toDp() - 8.dp }
        } ?: fallbackFilterPanelBottom
        val filterPanelBottom = requestedFilterPanelBottom.coerceIn(filterPanelTop, maxHeight)

        AnimatedVisibility(
            activePanel == ActivePanel.FILTERS,
            enter = if (isXpan) {slideInVertically(initialOffsetY = { it })} else fadeIn(),
            exit = if (isXpan) {slideOutVertically(targetOffsetY = { it })} else fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(filterPanelBottom)
                    .padding(top = filterPanelTop),
                contentAlignment = Alignment.BottomCenter
            ) {
                val allPresets by viewModel.allPresets.collectAsState()
                val defaultPresetName = stringResource(R.string.preset_new_preset_default)
                val isVideoLog = isVideoMode && state.videoConfig.logProfile.isEnabled

                // LUT 选择器 (内嵌 Presets 列表与统一控制，无多余背景遮挡)
                LutSelector(
                    availableLuts = if (isVideoLog) {
                        // Prefer the six requested Log looks when they are installed.
                        // Never hide every LUT when bundled/custom LUT IDs differ or
                        // those assets have not been added to this build yet.
                        val allLuts = viewModel.selectableLutList
                        val requestedLogLuts = allLuts.filter { lut ->
                            lut.id in setOf(
                                "romance_neutral",
                                "romance_soft1",
                                "summerdusk",
                                "tealandorange",
                                "truecinematic",
                                "weddingpack1"
                            )
                        }
                        requestedLogLuts.ifEmpty { allLuts }
                    } else viewModel.selectableLutList,
                    currentLutId = currentLutId,
                    thumbnail = viewModel.previewThumbnail,
                    onLutSelected = { viewModel.setLut(it) },
                    allPresets = allPresets,
                    presetModeEnabled = !isVideoLog,
                    activePresetId = activePresetId,
                    activePresetModified = activePresetModified,
                    selectedMode = if (isVideoLog) LutSelectorMode.Style else lutSelectorMode,
                    onModeSelected = { viewModel.setLutSelectorMode(it) },
                    availableFrames = viewModel.availableFrameList,
                    currentFrameId = viewModel.currentFrameId,
                    onFrameSelected = if (isVideoLog) null else viewModel::setFrame,
                    onFrameManagementClick = {
                        activePanel = ActivePanel.NONE
                        onFrameManagementClick()
                    },
                    onPresetSelected = { preset ->
                        discardTransientLookEdits()
                        if (presetRequiresPreviewTransition(preset)) {
                            runPreviewTransition { viewModel.applyPreset(preset) }
                        } else {
                            viewModel.applyPreset(preset)
                        }
                    },
                    onCreatePresetClick = {
                        viewModel.prepareCurrentSettingsPresetDraft(defaultPresetName)
                        onPresetEditClick(null)
                    },
                    onResetPresetClick = {
                        discardTransientLookEdits()
                        val preset = allPresets.firstOrNull { it.id == activePresetId }
                        if (presetRequiresPreviewTransition(preset)) {
                            runPreviewTransition { viewModel.resetActivePreset() }
                        } else {
                            viewModel.resetActivePreset()
                        }
                    },
                    onSavePresetClick = viewModel::saveCurrentSettingsToActivePreset,
                    onPresetManagementClick = onPresetManagementClick,
                    onEditClick = {
                        videoEditOpenedFromModePill = false
                        activePanel = ActivePanel.EDIT
                    },
                    showEditButtonWhenNoLut = isVideoMode,
                    onManageClick = { lutId ->
                        activePanel = ActivePanel.NONE
                        onFilterManagementClick(lutId)
                    },
                    categoryOrder = categoryOrder,
                    headerContent = if (isVideoLog) {
                        {
                            VideoLogLutModeSelector(
                                mode = state.videoConfig.logLutMode,
                                enabled = !state.videoRecordingState.isRecording &&
                                    !state.videoRecordingState.isProcessing,
                                onModeSelected = viewModel::setVideoLogLutMode
                            )
                        }
                    } else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 6.dp, top = 10.dp, end = 6.dp)
                )
            }
        }

        val videoEditPanelMaxHeight = maxHeight * 0.72f
        val isVideoEditVisible = activePanel == ActivePanel.EDIT && isVideoMode
        val videoEditTransformOrigin = remember(videoEditPillBounds, cameraScreenBounds) {
            val pill = videoEditPillBounds
            val screen = cameraScreenBounds
            if (pill != null && screen != null && screen.width > 0f && screen.height > 0f) {
                val panelHeight = screen.height * 0.72f
                val panelTop = screen.bottom - panelHeight
                TransformOrigin(
                    pivotFractionX = ((pill.left + pill.right) * 0.5f - screen.left) / screen.width,
                    pivotFractionY = ((pill.top + pill.bottom) * 0.5f - panelTop) / panelHeight
                ).let { origin ->
                    TransformOrigin(origin.pivotFractionX.coerceIn(0f, 1f), origin.pivotFractionY.coerceIn(0f, 1f))
                }
            } else {
                TransformOrigin(0.5f, 0.86f)
            }
        }
        LaunchedEffect(isVideoEditVisible) {
            if (!isVideoEditVisible) {
                videoEditGlassBounds = null
            }
        }
        if (isVideoEditVisible) {
            BackHandler {
                activePanel = ActivePanel.NONE
            }
        }
        AnimatedVisibility(
            visible = isVideoEditVisible,
            enter = if (videoEditOpenedFromModePill) {
                scaleIn(
                    initialScale = 0.16f,
                    transformOrigin = videoEditTransformOrigin,
                    animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f)
                ) + fadeIn(tween(durationMillis = 110))
            } else {
                slideInVertically(initialOffsetY = { it }) + fadeIn()
            },
            exit = if (videoEditOpenedFromModePill) {
                scaleOut(
                    targetScale = 0.16f,
                    transformOrigin = videoEditTransformOrigin,
                    animationSpec = spring(dampingRatio = 0.78f, stiffness = 420f)
                ) + fadeOut(tween(durationMillis = 110))
            } else {
                slideOutVertically(targetOffsetY = { it }) + fadeOut()
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            OppoCameraGlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                transparency = cameraMenuTransparency,
                blurStrength = cameraMenuBlurRadius,
                onBoundsChanged = { videoEditGlassBounds = it },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = videoEditPanelMaxHeight)
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(top = 6.dp, bottom = 10.dp)
                ) {
                    LutEditPanelContent(
                        lutId = currentLutId,
                        initialParams = effectiveRecipeParams,
                        onParamsPreviewChange = { previewRecipeParamsOverride = it },
                        showEffects = true,
                        replaceVignetteWithVideoBrightness = true,
                        videoOnly = true,
                        transparentGlassSurface = true,
                    )
                }
            }
        }

        if (activePanel == ActivePanel.EDIT && !isVideoMode) {
            LutEditBottomSheet(
                lutId = currentLutId,
                initialParams = effectiveRecipeParams,
                onParamsPreviewChange = { previewRecipeParamsOverride = it },
                showEffects = true,
                replaceVignetteWithVideoBrightness = state.captureMode == CaptureMode.VIDEO,
                videoOnly = state.captureMode == CaptureMode.VIDEO,
                onDismiss = {
                    // Keep the Video preview override until the separate recipe reaches DataStore.
                    if (state.captureMode != CaptureMode.VIDEO) {
                        previewRecipeParamsOverride = null
                    }
                    activePanel = ActivePanel.NONE
                }
            )
        }

        captureAnimationSnapshot?.let { snapshot ->
            CaptureAnimationOverlay(
                snapshot = snapshot,
                modifier = Modifier.fillMaxSize(),
                onFinished = {
                    if (captureAnimationSnapshot?.id == snapshot.id) {
                        captureAnimationSnapshot = null
                    }
                }
            )
        }

    }
}

@Composable
fun MultipleExposureOverlay(
    state: com.hinnka.mycamera.viewmodel.MultipleExposureSessionState,
    onFinish: () -> Unit,
    onUndo: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.padding(8.dp)) {
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .wrapContentWidth(),
            color = Color.Transparent,
            shape = RoundedCornerShape(20.dp)
        ) {
            Row(
                modifier = Modifier
                    .background(
                        brush = Brush.horizontalGradient(
                            listOf(
                                Color(0xD90D1117),
                                Color(0xB8141B22)
                            )
                        ),
                        shape = RoundedCornerShape(20.dp)
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = AppIcons.Layers,
                    contentDescription = null,
                    tint = AccentColor,
                    modifier = Modifier.size(16.dp)
                )

                Text(
                    text = "${state.capturedCount}/${state.targetCount}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge.copy(
                        shadow = ViewfinderTextShadow
                    )
                )

                if (state.isSessionActive) {
                    IconButton(
                        onClick = onUndo,
                        enabled = state.capturedCount > 0 && !state.isProcessing,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            AppIcons.AutoMirroredOutlinedUndo,
                            contentDescription = stringResource(R.string.multiple_exposure_undo),
                            modifier = Modifier.size(16.dp),
                            tint = Color.White
                        )
                    }

                    IconButton(
                        onClick = onFinish,
                        enabled = state.canFinish,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = stringResource(R.string.multiple_exposure_finish),
                            modifier = Modifier.size(16.dp),
                            tint = AccentColor
                        )
                    }

                    IconButton(
                        onClick = onCancel,
                        enabled = !state.isProcessing,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.multiple_exposure_cancel),
                            modifier = Modifier.size(16.dp),
                            tint = Color.Red
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Controls(
    state: CameraState,
    uiStyle: String,
    viewModel: CameraViewModel,
    galleryViewModel: GalleryViewModel,
    latestPhoto: com.hinnka.mycamera.gallery.MediaData?,
    useMultipleExposure: Boolean,
    multipleExposureState: com.hinnka.mycamera.viewmodel.MultipleExposureSessionState,
    fastShutterEnabled: Boolean,
    onGalleryThumbnailBoundsChanged: (Rect) -> Unit,
    onCaptureButtonBoundsChanged: (Rect) -> Unit,
    onSwitchCameraClick: () -> Unit,
    onCaptureModeSelected: (CameraShootingMode) -> Unit,
    modeSwitchEnabled: Boolean,
    modeSwitcherBackdrop: PrismalBackdrop,
    onVideoEditSwipeUp: () -> Unit,
    onVideoPillBoundsChanged: (Rect) -> Unit,
    onCaptureTap: () -> Unit,
    onGalleryClick: () -> Unit,
    sideControlInset: Dp?,
    modifier: Modifier = Modifier.fillMaxSize()
) {
    val captureButtonStyle by viewModel.captureButtonStyle.collectAsState()
    val captureButtonColor by viewModel.captureButtonColor.collectAsState()
    val captureButtonImagePath by viewModel.captureButtonImagePath.collectAsState()
    val captureProcessingState by viewModel.captureProcessingState.collectAsState()
    val capturedThumbnail by viewModel.capturedThumbnail.collectAsState()

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = CameraControlsLayoutDefaults.BottomPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Keep the shutter centered on its own row.
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CaptureButton(
                    captureMode = state.captureMode,
                    isProfessionalMode = state.useRaw && state.isRawSupported,
                    isCapturing = state.isCapturing,
                    photoCaptureProgress = state.photoCaptureProgress,
                    isBurstCapturing = state.burstCapturing,
                    isVideoRecording = state.videoRecordingState.isRecording,
                    isVideoProcessing = state.videoRecordingState.isProcessing,
                    isPaused = state.videoRecordingState.isPaused,
                    allowLongPress = state.captureMode == CaptureMode.PHOTO &&
                        !useMultipleExposure && fastShutterEnabled,
                    multipleExposureEnabled = useMultipleExposure && state.captureMode == CaptureMode.PHOTO,
                    multipleExposureProgress = multipleExposureState.capturedCount.toFloat() /
                        multipleExposureState.targetCount.coerceAtLeast(1).toFloat(),
                    customStyle = if (uiStyle == "OPPO" && state.captureMode != CaptureMode.VIDEO) {
                        CaptureButtonStyle.COLOR
                    } else {
                        captureButtonStyle
                    },
                    customColor = if (uiStyle == "OPPO" && state.captureMode != CaptureMode.VIDEO) {
                        0xFFFF8500.toInt()
                    } else {
                        captureButtonColor
                    },
                    customImagePath = captureButtonImagePath,
                    onTap = onCaptureTap,
                    onLongPressStart = { viewModel.startContinuousCapture() },
                    onLongPressEnd = { viewModel.stopContinuousCapture() },
                    modifier = Modifier
                        .offset(
                            y = if (state.captureMode == CaptureMode.VIDEO) (-1).dp else (-2).dp
                        )
                        .onGloballyPositioned { coordinates ->
                            onCaptureButtonBoundsChanged(coordinates.boundsInRoot())
                        },
                )

                if (state.videoRecordingState.isRecording) {
                    IconButton(
                        onClick = {
                            if (state.videoRecordingState.isPaused) {
                                viewModel.resumeVideoRecording()
                            } else {
                                viewModel.pauseVideoRecording()
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 100.dp)
                            .size(48.dp)
                            .autoRotate()
                    ) {
                        Icon(
                            imageVector = if (state.videoRecordingState.isPaused) Icons.Default.PlayArrow else AppIcons.Pause,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(CameraControlsLayoutDefaults.ModeSwitcherSpacing))

            // Match the reference: gallery, Photo/Video selector and camera switch
            // share one horizontal row beneath the centered shutter.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CameraControlsLayoutDefaults.ModeSwitcherHeight),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = sideControlInset ?: 32.dp)
                        .onGloballyPositioned { coordinates ->
                            onGalleryThumbnailBoundsChanged(coordinates.boundsInRoot())
                        }
                        .autoRotate()
                ) {
                    GalleryThumbnail(
                        latestPhoto = latestPhoto,
                        viewModel = galleryViewModel,
                        capturedThumbnail = capturedThumbnail,
                        onCapturedThumbnailLoaded = viewModel::acknowledgeCapturedThumbnail,
                        onClick = onGalleryClick
                    )
                    key(capturedThumbnail?.captureId) {
                        GalleryProcessingOverlay(
                            pendingCount = if (
                                capturedThumbnail == null ||
                                capturedThumbnail?.bitmap != null ||
                                capturedThumbnail?.finalThumbnailLoaded == true
                            ) {
                                captureProcessingState.pendingCount
                            } else {
                                0
                            },
                            completedCount = captureProcessingState.completedCount,
                            modifier = Modifier.matchParentSize()
                        )
                    }
                }

                CaptureModeSwitcher(
                    oppoStyle = uiStyle == "OPPO",
                    modeSwitcherBackdrop = modeSwitcherBackdrop,
                    // Pro/RAW remains an internal capture option, not a visible mode.
                    shootingMode = if (state.captureMode == CaptureMode.VIDEO) {
                        CameraShootingMode.VIDEO
                    } else {
                        CameraShootingMode.PHOTO
                    },
                    enabled = modeSwitchEnabled &&
                        !state.videoRecordingState.isRecording &&
                        !state.videoRecordingState.isProcessing,
                    onModeSelected = onCaptureModeSelected,
                    onVideoEditSwipeUp = onVideoEditSwipeUp,
                    onVideoPillBoundsChanged = onVideoPillBoundsChanged
                )

                PhysicalButton(
                    onClick = {
                        if (!state.videoRecordingState.isProcessing) {
                            onSwitchCameraClick()
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = sideControlInset ?: 32.dp)
                        .size(CameraControlsLayoutDefaults.SideButtonSize)
                        .autoRotate(),
                    shape = CircleShape,
                    backgroundColor = Color.White.copy(alpha = 0.12f)
                ) {
                    Icon(
                        imageVector = AppIcons.RestartAlt,
                        contentDescription = stringResource(R.string.switch_camera),
                        tint = Color.White.copy(
                            alpha = if (state.videoRecordingState.isProcessing) 0.35f else 1f
                        ),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

/** Keeps render progress independent of the shutter and the optional photo fly-in animation. */
@Composable
private fun GalleryProcessingOverlay(
    pendingCount: Int,
    completedCount: Long,
    modifier: Modifier = Modifier
) {
    val completionAlpha = remember { Animatable(0f) }
    var lastAcknowledgedCompletion by remember { mutableLongStateOf(completedCount) }
    val thumbnailShape = CircleShape

    LaunchedEffect(pendingCount, completedCount) {
        if (pendingCount > 0) {
            completionAlpha.snapTo(0f)
        } else if (completedCount != lastAcknowledgedCompletion) {
            lastAcknowledgedCompletion = completedCount
            // A neutral highlight acknowledges completion without implying success on failure.
            completionAlpha.snapTo(0f)
            completionAlpha.animateTo(0.75f, tween(durationMillis = 120))
            completionAlpha.animateTo(0f, tween(durationMillis = 700))
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = pendingCount > 0,
            enter = fadeIn(tween(durationMillis = 180)),
            exit = fadeOut(tween(durationMillis = 220)),
            modifier = Modifier.matchParentSize()
        ) {
            // Keep the photo visible beneath a soft diagonal reflection. The transition is
            // disposed after fade-out, so an idle thumbnail does not keep requesting frames.
            val transition = rememberInfiniteTransition(label = "thumbnailProcessing")
            val sweep = transition.animateFloat(
                initialValue = -1f,
                targetValue = 2f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1800, delayMillis = 200, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "thumbnailLightSweep"
            )
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(thumbnailShape)
                    .border(1.dp, Color.White.copy(alpha = 0.14f), thumbnailShape)
            ) {
                drawRect(Color.Black.copy(alpha = 0.08f))
                val centerX = size.width * sweep.value
                val bandWidth = size.width * 0.65f
                drawRect(
                    brush = Brush.linearGradient(
                        0f to Color.Transparent,
                        0.3f to Color.White.copy(alpha = 0.03f),
                        0.5f to Color.White.copy(alpha = 0.24f),
                        0.7f to Color.White.copy(alpha = 0.03f),
                        1f to Color.Transparent,
                        start = Offset(centerX - bandWidth, 0f),
                        end = Offset(centerX + bandWidth, size.height * 0.7f)
                    )
                )
            }
        }
        if (completionAlpha.value > 0f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(thumbnailShape)
                    .background(Color.White.copy(alpha = completionAlpha.value * 0.08f))
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = completionAlpha.value),
                        shape = thumbnailShape
                    )
            )
        }
    }
}


/**
 * 拍照按钮
 */
@Composable
fun CaptureButton(
    captureMode: CaptureMode,
    isProfessionalMode: Boolean,
    isCapturing: Boolean,
    photoCaptureProgress: PhotoCaptureProgress?,
    isBurstCapturing: Boolean,
    isVideoRecording: Boolean,
    isVideoProcessing: Boolean,
    isPaused: Boolean,
    allowLongPress: Boolean,
    multipleExposureEnabled: Boolean,
    multipleExposureProgress: Float,
    customStyle: CaptureButtonStyle,
    customColor: Int,
    customImagePath: String?,
    onTap: () -> Unit,
    onLongPressStart: () -> Unit,
    onLongPressEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progressColor = AccentColor
    val showMultipleExposureProgress = captureMode == CaptureMode.PHOTO && multipleExposureEnabled
    val showCaptureProgress = captureMode == CaptureMode.PHOTO && isCapturing
    val showVideoProcessingProgress = captureMode == CaptureMode.VIDEO && isVideoProcessing
    val showActivityProgress = showCaptureProgress || showVideoProcessingProgress
    val progressRingCount = (if (showMultipleExposureProgress) 1 else 0) +
        (if (showActivityProgress) 1 else 0)
    val infiniteTransition = rememberInfiniteTransition(label = "infinite transition")
    var isPressed by remember { mutableStateOf(false) }
    val customImage = remember(customImagePath) {
        customImagePath
            ?.let(::File)
            ?.takeIf(File::isFile)
    }

    val scale by animateFloatAsState(
        targetValue = if (isPressed || isCapturing || isVideoRecording || isVideoProcessing) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.5f),
        label = "captureScale"
    )

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "videoPulse"
    )

    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1500)),
        label = "captureProgressRotation"
    )

    val captureProgress = rememberPhotoCaptureProgress(
        photoCaptureProgress.takeIf { isCapturing && captureMode == CaptureMode.PHOTO }
    )

    val currentDisabled by rememberUpdatedState(
        isCapturing ||
            isVideoProcessing ||
            (captureMode == CaptureMode.VIDEO && isVideoRecording)
    )

    PhysicalButton(
        modifier = modifier
            .size(CameraControlsLayoutDefaults.CaptureButtonSize)
            .scale(scale)
            .pointerInput(allowLongPress) {
                var isLongPressStarted = false
                detectTapGestures(
                    onTap = {
                        if (!isCapturing && !isVideoProcessing) {
                            onTap()
                        }
                    },
                    onLongPress = if (allowLongPress) {
                        {
                            if (!currentDisabled) {
                                isLongPressStarted = true
                                onLongPressStart()
                            }
                        }
                    } else null,
                    onPress = {
                        isPressed = true
                        isLongPressStarted = false
                        try {
                            tryAwaitRelease()
                        } finally {
                            isPressed = false
                            if (isLongPressStarted) {
                                onLongPressEnd()
                            }
                        }
                    }
                )
            },
        shape = CircleShape,
        backgroundColor = Color(0xFF2D2D2D),
        contentAlignment = Alignment.Center
    ) {
        val ringColor = when {
            captureMode == CaptureMode.VIDEO && isVideoProcessing ->
                Color.White.copy(alpha = 0.5f)
            captureMode == CaptureMode.VIDEO && isVideoRecording -> {
                if (isPaused) Color.White.copy(alpha = 0.8f)
                else Color.Red.copy(alpha = pulseAlpha)
            }
            else -> Color(0xFF505050)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(
                    width = if (captureMode == CaptureMode.VIDEO) 2.dp else 1.dp,
                    color = ringColor,
                    shape = CircleShape
                )
        )

        // Center shutter surface
        val centerPadding by animateDpAsState(
            targetValue = when {
                captureMode == CaptureMode.VIDEO && isVideoRecording -> 19.dp
                progressRingCount > 0 -> CaptureProgressRingDefaults.contentPadding(progressRingCount)
                else -> 3.dp
            },
            animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow),
            label = "centerPadding"
        )
        val centerCorner by animateDpAsState(
            targetValue = if (captureMode == CaptureMode.VIDEO && isVideoRecording) 8.dp else 36.dp,
            label = "centerCorner"
        )
        val defaultCenterBackground = when {
            captureMode == CaptureMode.VIDEO -> {
                // Flat saturated red video shutter, matching the reference capture button.
                Modifier.background(Color(0xFFFF3038))
            }
            isProfessionalMode -> Modifier.background(AccentColor)
            else -> {
                Modifier.background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White,
                            Color(0xFFD7D7D7)
                        )
                    )
                )
            }
        }
        val useCustomAppearance = captureMode != CaptureMode.VIDEO
        val centerBackgroundModifier = when {
            useCustomAppearance && customStyle == CaptureButtonStyle.COLOR ->
                Modifier.background(Color(customColor))
            else -> defaultCenterBackground
        }

        Box(
            modifier = Modifier
                .padding(centerPadding)
                .fillMaxSize()
                .clip(RoundedCornerShape(centerCorner))
                .then(centerBackgroundModifier)
        ) {
            if (
                useCustomAppearance &&
                customStyle == CaptureButtonStyle.IMAGE &&
                customImage != null
            ) {
                AsyncImage(
                    model = customImage,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .autoRotate(matchParentSize = true)
                )
            }
        }
        if (showMultipleExposureProgress) {
            CaptureProgressRing(
                color = progressColor,
                startAngle = -90f,
                sweepAngle = 360f * multipleExposureProgress.coerceIn(0f, 1f)
            )
        }
        if (showActivityProgress) {
            val isIndeterminate = isBurstCapturing || showVideoProcessingProgress
            CaptureProgressRing(
                color = progressColor,
                startAngle = if (isIndeterminate) rotation - 90f else -90f,
                sweepAngle = if (isIndeterminate) 90f else 360f * captureProgress.value,
                inset = CaptureProgressRingDefaults.OuterInset +
                    if (showMultipleExposureProgress) CaptureProgressRingDefaults.RingSpacing else 0.dp,
                modifier = if (isIndeterminate) Modifier.progressSemantics()
                    else Modifier.progressSemantics(captureProgress.value)
            )
        }
    }
}

@Composable
private fun rememberPhotoCaptureProgress(progress: PhotoCaptureProgress?): State<Float> {
    val fraction = remember(progress) {
        mutableFloatStateOf(progress?.fractionAt(SystemClock.elapsedRealtimeNanos()) ?: 0f)
    }
    LaunchedEffect(progress) {
        if (progress == null) return@LaunchedEffect
        while (isActive && fraction.floatValue < 1f) {
            withFrameNanos {
                fraction.floatValue = progress.fractionAt(SystemClock.elapsedRealtimeNanos())
            }
        }
    }
    return fraction
}

@Composable
private fun CaptureModeSwitcher(
    oppoStyle: Boolean,
    modeSwitcherBackdrop: PrismalBackdrop,
    shootingMode: CameraShootingMode,
    enabled: Boolean,
    onModeSelected: (CameraShootingMode) -> Unit,
    onVideoEditSwipeUp: () -> Unit,
    onVideoPillBoundsChanged: (Rect) -> Unit
) {
    val selectedIndex = if (shootingMode == CameraShootingMode.VIDEO) 0 else 1

    if (oppoStyle) {
        BoxWithConstraints(
            modifier = Modifier
                .width(171.dp)
                .height(CameraControlsLayoutDefaults.ModeSwitcherHeight)
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInRoot()
                    onVideoPillBoundsChanged(Rect(bounds.left, bounds.top, bounds.left + bounds.width / 2f, bounds.bottom))
                }
                .videoPillSwipeUpGesture(
                    videoSelected = shootingMode == CameraShootingMode.VIDEO,
                    enabled = enabled,
                    onSwipeUp = onVideoEditSwipeUp
                )
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF202020))
                .padding(3.dp)
        ) {
            // Preserve the existing OPPO-style switcher; only the standard liquid-glass
            // switcher is replaced by the Prismal segmented-control component.
            val knobWidth = maxWidth / 2
            val knobOffset = knobWidth * selectedIndex

            Box(
                modifier = Modifier
                    .offset(x = knobOffset)
                    .width(knobWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(15.dp))
                    .background(Color(0xFF303030))
            )

            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ModeSwitcherItem(
                    label = stringResource(R.string.capture_mode_video).uppercase() +
                        if (shootingMode == CameraShootingMode.VIDEO) " ▴" else "",
                    selected = shootingMode == CameraShootingMode.VIDEO,
                    enabled = enabled,
                    onClick = { onModeSelected(CameraShootingMode.VIDEO) },
                    modifier = Modifier.weight(1f),
                    selectedContentColor = Color(0xFFFF9A32)
                )
                ModeSwitcherItem(
                    label = stringResource(R.string.capture_mode_photo).uppercase() +
                        if (shootingMode != CameraShootingMode.VIDEO) " ▴" else "",
                    selected = shootingMode != CameraShootingMode.VIDEO,
                    enabled = enabled,
                    onClick = { onModeSelected(CameraShootingMode.PHOTO) },
                    modifier = Modifier.weight(1f),
                    selectedContentColor = Color(0xFFFF9A32)
                )
            }
        }
    } else {
        // Use the actual Prismal segmented control (the top component in the reference),
        // not the bottom-tabs or horizontal-selector component. It provides the springy
        // selected pill, refractive highlight, tap selection and draggable thumb.
        Box(
            modifier = Modifier
                .width(171.dp)
                .height(CameraControlsLayoutDefaults.ModeSwitcherHeight)
                // Match the outer track height to Prismal's full-height selected pill.
                // The library's built-in track is intentionally inset by 6.dp vertically.
                .clip(CircleShape)
                .background(Color(0xFF787880).copy(alpha = 0.36f))
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInRoot()
                    onVideoPillBoundsChanged(Rect(bounds.left, bounds.top, bounds.left + bounds.width / 2f, bounds.bottom))
                }
                .videoPillSwipeUpGesture(
                    videoSelected = shootingMode == CameraShootingMode.VIDEO,
                    enabled = enabled,
                    onSwipeUp = onVideoEditSwipeUp
                )
                .pointerInput(enabled) {
                    if (!enabled) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial)
                                    .changes
                                    .forEach { it.consume() }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            PrismalSegmentedControl(
                labels = listOf(
                    stringResource(R.string.capture_mode_video).uppercase(),
                    stringResource(R.string.capture_mode_photo).uppercase()
                ),
                selectedIndex = selectedIndex,
                onSelected = { index ->
                    if (enabled) {
                        onModeSelected(
                            if (index == 0) CameraShootingMode.VIDEO else CameraShootingMode.PHOTO
                        )
                    }
                },
                backdrop = modeSwitcherBackdrop,
                modifier = Modifier.fillMaxSize(),
                textStyle = TextStyle(
                    fontSize = 14.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.sp
                ),
                textColor = Color.White,
                trackColor = Color.Transparent,
                thumbColor = Color.White.copy(alpha = 0.28f),
                height = CameraControlsLayoutDefaults.ModeSwitcherHeight,
                hapticFeedback = enabled
            )
        }
    }
}

/**
 * Opens the video edit window only for an upward swipe that starts on the selected
 * Video segment. Vertical motion is consumed only after the upward threshold is crossed,
 * so ordinary taps and horizontal segment dragging keep their native behavior.
 */
private fun Modifier.videoPillSwipeUpGesture(
    videoSelected: Boolean,
    enabled: Boolean,
    onSwipeUp: () -> Unit
): Modifier = composed {
    // The callback is recreated by the parent composable. Keep the pointer-input
    // coroutine alive during recomposition and always invoke the latest callback.
    val currentOnSwipeUp by rememberUpdatedState(onSwipeUp)

    pointerInput(videoSelected, enabled) {
        if (!videoSelected || !enabled) return@pointerInput

        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial
            )

            // The Video segment is the left half. Photo taps/drags remain untouched.
            if (down.position.x >= size.width / 2f) {
                return@awaitEachGesture
            }

            var triggered = false
            while (true) {
                // Observe after child handlers so the segmented control cannot hide
                // movement from this parent-level swipe detector.
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val drag = change.position - down.position
                val upwardDistance = -drag.y
                val isUpwardSwipe = upwardDistance > viewConfiguration.touchSlop * 1.5f &&
                    upwardDistance > abs(drag.x) * 1.1f

                if (!triggered && isUpwardSwipe) {
                    triggered = true
                    change.consume()
                    currentOnSwipeUp()
                } else if (triggered) {
                    change.consume()
                }

                if (!change.pressed) break
            }
        }
    }
}
@Composable
private fun ModeSwitcherItem(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectedContentColor: Color = Color.Black
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = when {
                selected -> selectedContentColor.copy(alpha = 0.88f)
                enabled -> Color.White.copy(alpha = 0.82f)
                else -> Color.White.copy(alpha = 0.3f)
            },
            fontSize = 14.sp,
            lineHeight = 16.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

private fun cycleVideoResolution(state: CameraState): VideoResolutionPreset? {
    return nextOption(state.videoConfig.resolution, state.videoCapabilities.availableResolutions)
}

private fun cycleVideoFps(state: CameraState): VideoFpsPreset? {
    return nextOption(state.videoConfig.fps, state.videoCapabilities.availableFps)
}

private fun cycleVideoAspectRatio(state: CameraState): VideoAspectRatio? {
    return nextOption(state.videoConfig.aspectRatio, state.videoCapabilities.availableAspectRatios)
}

private fun cycleVideoLogProfile(state: CameraState): VideoLogProfile? {
    return nextOption(state.videoConfig.logProfile, state.videoCapabilities.availableLogProfiles)
}

private fun <T> nextOption(current: T, options: List<T>): T? {
    if (options.isEmpty()) return null
    val currentIndex = options.indexOf(current)
    return if (currentIndex == -1) {
        options.firstOrNull()
    } else {
        options[(currentIndex + 1) % options.size]
    }
}


fun Modifier.autoRotate(
    dx: Dp = 0.dp,
    dy: Dp = 0.dp,
    matchParentSize: Boolean = false
): Modifier = composed {
    val targetDegrees =
        if (OrientationObserver.rotationDegrees != 0f) OrientationObserver.rotationDegrees - 180 else 0f

    val animatedDegrees by animateFloatAsState(
        targetValue = targetDegrees,
        animationSpec = tween(durationMillis = 300),
        label = "rotationAnimation"
    )

    layout { measurable, constraints ->
        val rad = Math.toRadians(animatedDegrees.toDouble())
        val cos = abs(cos(rad))
        val sin = abs(sin(rad))

        // 核心优化：匹配父布局大小时，如果旋转接近 90 度，交换约束，
        // 从而让子组件（如 AsyncImage）按旋转后的方向进行测量，实现“铺满”效果。
        val modifiedConstraints = if (matchParentSize && sin > 0.5f) {
            Constraints(
                minWidth = constraints.minHeight,
                maxWidth = constraints.maxHeight,
                minHeight = constraints.minWidth,
                maxHeight = constraints.maxWidth
            )
        } else {
            constraints
        }

        val placeable = measurable.measure(modifiedConstraints)
        val width = placeable.width
        val height = placeable.height

        if (matchParentSize) {
            val visualWidth = width * cos + height * sin
            val visualHeight = width * sin + height * cos

            val containerWidth = constraints.maxWidth.toFloat()
            val containerHeight = constraints.maxHeight.toFloat()

            // 计算缩放：确保即使在动画中，内容也能刚好填满或不超出边界
            val scale = min(
                if (visualWidth > 0) containerWidth / visualWidth else 1.0,
                if (visualHeight > 0) containerHeight / visualHeight else 1.0
            ).toFloat().coerceAtMost(1.0f)

            layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.placeRelativeWithLayer(
                    (constraints.maxWidth - width) / 2,
                    (constraints.maxHeight - height) / 2
                ) {
                    rotationZ = animatedDegrees
                    scaleX = scale
                    scaleY = scale
                }
            }
        } else {
            val newWidth = (placeable.width * cos + placeable.height * sin).toInt()
            val newHeight = (placeable.width * sin + placeable.height * cos).toInt()

            val nDx = dx.toPx() * sin
            val nDy = dy.toPx() * sin

            layout(newWidth, newHeight) {
                placeable.placeRelativeWithLayer(
                    x = (newWidth - placeable.width) / 2 + nDx.toInt(),
                    y = (newHeight - placeable.height) / 2 + nDy.toInt()
                ) {
                    rotationZ = animatedDegrees
                }
            }
        }
    }
}

@Composable
private fun CornerOverlay(
    modifier: Modifier = Modifier,
    radius: Dp,
    color: Color = Color.Black
) {
    Canvas(modifier = modifier) {
        val frame = Path().apply {
            addRect(Rect(0f, 0f, size.width, size.height))
        }
        val roundedContent = Path().apply {
            addRoundRect(
                RoundRect(
                    left = 0f,
                    top = 0f,
                    right = size.width,
                    bottom = size.height,
                    cornerRadius = CornerRadius(radius.toPx(), radius.toPx())
                )
            )
        }
        frame.op(frame, roundedContent, PathOperation.Difference)
        drawPath(frame, color)
    }
}


private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}


/**
 * Keeps the concurrent front-camera preview proportional inside any dual-capture layout.
 *
 * TextureView stretches its producer buffer to the view bounds by default. This matrix first
 * compensates for that buffer-to-view scaling, then applies the front sensor orientation, and
 * finally center-crops uniformly to the target bounds. A producer transform that already swaps
 * the texture axes is respected to avoid applying the sensor quarter-turn twice.
 */
private fun TextureView.updateDualCaptureTextureTransform(
    streamSize: Size?,
    sensorOrientationDegrees: Int,
) {
    val input = streamSize ?: return
    val viewWidth = width
    val viewHeight = height
    if (input.width <= 0 || input.height <= 0 || viewWidth <= 0 || viewHeight <= 0) return
    val producer = surfaceTexture ?: return
    val producerMatrix = FloatArray(16)
    runCatching { producer.getTransformMatrix(producerMatrix) }.getOrElse { return }

    // TextureView already presents the camera's sensor-oriented image. Use the producer
    // matrix when it exposes the quarter-turn; otherwise use SENSOR_ORIENTATION to select
    // the displayed width/height. Do not rotate the TextureView a second time.
    val producerAxesSwapped =
        kotlin.math.abs(producerMatrix[1]) > kotlin.math.abs(producerMatrix[0]) &&
            kotlin.math.abs(producerMatrix[4]) > kotlin.math.abs(producerMatrix[5])
    val sensorAxesSwapped = sensorOrientationDegrees % 180 != 0
    val displayedWidth = if (producerAxesSwapped || sensorAxesSwapped) input.height else input.width
    val displayedHeight = if (producerAxesSwapped || sensorAxesSwapped) input.width else input.height

    // TextureView fills its bounds by default, so compensate for that non-uniform scaling
    // before applying a single uniform center-crop scale.
    val scale = maxOf(
        viewWidth.toFloat() / displayedWidth.coerceAtLeast(1),
        viewHeight.toFloat() / displayedHeight.coerceAtLeast(1),
    )
    val scaleX = scale * displayedWidth / viewWidth.toFloat()
    val scaleY = scale * displayedHeight / viewHeight.toFloat()
    val transform = Matrix().apply {
        setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f)
    }
    setTransform(transform)
}
