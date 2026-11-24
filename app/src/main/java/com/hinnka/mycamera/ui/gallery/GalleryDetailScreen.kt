package com.hinnka.mycamera.ui.gallery

import com.hinnka.mycamera.ui.components.AppModalBottomSheet

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.hinnka.mycamera.ui.theme.WarningYellow
import com.hinnka.mycamera.R
import androidx.compose.ui.res.painterResource
import com.hinnka.mycamera.gallery.MediaData
import com.hinnka.mycamera.gallery.GalleryManager
import com.hinnka.mycamera.ui.theme.AccentColor
import com.hinnka.mycamera.ui.theme.OnAccentColor
import com.hinnka.mycamera.viewmodel.GalleryViewModel
import kotlinx.coroutines.delay
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.isVisible
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.ui.AspectRatioFrameLayout
import coil.request.ImageRequest
import coil.compose.AsyncImage
import com.hinnka.mycamera.hdr.HdrGainmapStrength
import com.hinnka.mycamera.lut.creator.AiPhotoCriterion
import com.hinnka.mycamera.lut.creator.AiPhotoEvaluation
import com.hinnka.mycamera.lut.isVideoTransformerExportSupported
import com.hinnka.mycamera.lut.VideoExportOption
import com.hinnka.mycamera.lut.VideoExportResolution
import com.hinnka.mycamera.lut.VideoExportSupport
import com.hinnka.mycamera.lut.VideoLutEffect
import com.hinnka.mycamera.ui.camera.autoRotate
import com.hinnka.mycamera.ui.camera.ZoomContinuousRuler
import com.hinnka.mycamera.ui.camera.ZoomDisplayMode
import com.hinnka.mycamera.ui.components.CustomSlider
import com.hinnka.mycamera.ui.components.PaymentDialog
import com.hinnka.mycamera.ui.components.PhysicalButton
import com.hinnka.mycamera.ui.components.ProcessingPhotoPreview
import com.hinnka.mycamera.utils.DeviceUtil
import com.hinnka.mycamera.utils.PLog
import com.hinnka.mycamera.video.SpatialVideoStore
import com.hinnka.mycamera.viewmodel.GalleryTab
import kotlinx.coroutines.Dispatchers
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import java.io.File
import kotlin.math.min
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import com.hinnka.mycamera.ui.icons.AppIcons
import kotlin.math.max

private val GalleryToolbarSurface = Color(0xFF0E0E0E)
private val GalleryToolbarButton = Color(0xFF242424)
private val GalleryToolbarContent = Color(0xFFF2F2F2)
private val GallerySheetSurface = Color(0xFF171717)

private class VideoPreviewHandle {
    private var activeLease: Lease? = null

    fun attach(vararg players: ExoPlayer): Lease {
        release()
        return Lease(players.toList()).also { activeLease = it }
    }

    fun release(lease: Lease) {
        if (activeLease === lease) activeLease = null
        lease.release()
    }

    fun release() {
        activeLease?.release()
        activeLease = null
    }

    class Lease(private val players: List<ExoPlayer>) {
        private var isReleased = false

        fun release() {
            if (isReleased) return
            isReleased = true
            players.forEach { player -> runCatching { player.release() } }
        }
    }
}

@Composable
private fun videoExportResolutionLabel(resolution: VideoExportResolution): String {
    return stringResource(
        when (resolution) {
            VideoExportResolution.ORIGINAL -> R.string.export_video_resolution_original
            VideoExportResolution.UHD_8K -> R.string.export_video_resolution_8k
            VideoExportResolution.UHD_4K -> R.string.export_video_resolution_4k
            VideoExportResolution.FHD_1080P -> R.string.export_video_resolution_1080p
            VideoExportResolution.HD_720P -> R.string.export_video_resolution_720p
        }
    )
}

/**
 * 照片详情界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryDetailScreen(
    viewModel: GalleryViewModel,
    initialIndex: Int = 0,
    selectedTab: GalleryTab? = null,
    photoId: String? = null,
    isExpanded: Boolean = false,
    onBack: () -> Unit = {},
    onGoToGallery: (() -> Unit)? = null,
    onEdit: () -> Unit,
    onViewBurst: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val photos by viewModel.currentPhotos.collectAsState()
    val processingPhotos by viewModel.processingPhotos.collectAsState()
    var previousProcessingIds by remember { mutableStateOf(processingPhotos.keys.toSet()) }
    LaunchedEffect(processingPhotos.keys) {
        (previousProcessingIds - processingPhotos.keys).forEach(viewModel::invalidatePreviewCache)
        previousProcessingIds = processingPhotos.keys.toSet()
    }
    val context = LocalContext.current

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var isZoomed by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var isExportingDng by remember { mutableStateOf(false) }
    var isCopyingSettings by remember { mutableStateOf(false) }
    var isPastingSettings by remember { mutableStateOf(false) }
    val isVideoExporting = viewModel.isVideoExporting
    val videoExportProgress = viewModel.videoExportProgress
    var showVideoExportConfirmDialog by remember { mutableStateOf(false) }
    var videoExportOptions by remember { mutableStateOf<List<VideoExportOption>>(emptyList()) }
    var selectedVideoExportOption by remember { mutableStateOf<VideoExportOption?>(null) }
    var isLoadingVideoExportOptions by remember { mutableStateOf(false) }
    val isSharing by viewModel.isSharing.collectAsState()
    val hasCopiedEditSettings by viewModel.hasCopiedEditSettings.collectAsState()
    val isPurchased by viewModel.isPurchased.collectAsState()
    var showAiScoreSheet by remember { mutableStateOf(false) }
    var showMoreSheet by remember { mutableStateOf(false) }
    var showHdrStrengthPanel by remember { mutableStateOf(false) }
    var showRefocusViewer by remember { mutableStateOf(false) }
    var showSpatialShareSheet by remember { mutableStateOf(false) }
    val spatialShareSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val videoPreviewHandle = remember { VideoPreviewHandle() }

    val currentColorSpace = remember { mutableStateOf<ColorSpace?>(null) }

    // Activity Result Launcher for delete confirmation
    val deletePhotoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK || viewModel.selectedTab == GalleryTab.PHOTON) {
            // User confirmed deletion or we are in PHOTON tab (delete internal photo anyway)
            viewModel.deletePhotoAfterConfirmation { success ->
                if (success && viewModel.currentPhotos.value.isEmpty()) {
                    onBack()
                }
            }
        } else {
            // User cancelled deletion in SYSTEM tab
            viewModel.clearDeleteRequest()
        }
    }

    val systemDeletePhotoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.deleteSystemPhotoAfterConfirmation { success ->
                if (success && viewModel.currentPhotos.value.isEmpty()) {
                    onBack()
                }
            }
        } else {
            viewModel.clearDeleteRequest()
        }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab != null) {
            viewModel.selectTab(selectedTab)
        }
    }

    // Monitor deletePendingIntent and launch system delete dialog
    LaunchedEffect(viewModel.deletePendingIntent) {
        viewModel.deletePendingIntent?.let { pendingIntent ->
            try {
                deletePhotoLauncher.launch(
                    IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                )
            } catch (e: Exception) {
                // Failed to launch, clear the request
                viewModel.clearDeleteRequest()
            }
        }
    }

    LaunchedEffect(viewModel.systemDeletePendingIntent) {
        viewModel.systemDeletePendingIntent?.let { pendingIntent ->
            try {
                systemDeletePhotoLauncher.launch(
                    IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                )
            } catch (e: Exception) {
                viewModel.clearDeleteRequest()
            }
        }
    }

    val pagerState = rememberPagerState(
        initialPage = remember(initialIndex, photoId) {
            // 初始页只在首次组合时计算，后续通过 LaunchedEffect 跳转
            if (photoId != null) {
                val index = photos.indexOfFirst { it.id == photoId }
                when {
                    index != -1 -> index
                    selectedTab == GalleryTab.SYSTEM -> 0
                    else -> initialIndex
                }
            } else {
                if (selectedTab == GalleryTab.SYSTEM) 0 else initialIndex
            }
        },
        pageCount = { photos.size }
    )

    // 记录是否已经执行过初始的 photoId 跳转
    var initialJumpDone by rememberSaveable(photoId) { mutableStateOf(false) }
    // 记录上次的照片数量，用于判断是否有新照片增加
    var lastPhotosCount by rememberSaveable { mutableIntStateOf(photos.size) }

    // 同步当前索引
    LaunchedEffect(pagerState.currentPage, photos.getOrNull(pagerState.currentPage)?.id,
        photos.getOrNull(pagerState.currentPage)?.id in processingPhotos) {
        viewModel.setCurrentPhoto(pagerState.currentPage)
        currentColorSpace.value = null
    }

    // 在快到底部时加载更多系统照片
    LaunchedEffect(pagerState.currentPage, photos.size) {
        if (pagerState.currentPage >= photos.size - 5) {
            viewModel.loadCurrentTabMore()
        }
    }

    LaunchedEffect(viewModel.selectedTab, photos, viewModel.currentPhotoIndex) {
        val targetIndex = viewModel.currentPhotoIndex
        if (targetIndex in photos.indices && pagerState.currentPage != targetIndex) {
            pagerState.scrollToPage(targetIndex)
        }
    }

    LaunchedEffect(photos.size, isExpanded) {
        // 仅在分屏模式且照片数量增加（通常是新拍摄）时才自动跳到第一张
        if (isExpanded == true && photos.size > lastPhotosCount) {
            pagerState.scrollToPage(0)
        }
        lastPhotosCount = photos.size
    }

    // 当 photoId 提供时，确保在照片列表加载后自动跳转到该照片（仅执行一次）
    LaunchedEffect(photos, photoId) {
        if (photoId != null && initialJumpDone == false) {
            val index = photos.indexOfFirst { it.id == photoId }
            if (index != -1) {
                // 即使 index == currentPage 也执行跳转，以应对 HorizontalPager 内部键位同步导致的索引偏移
                pagerState.scrollToPage(index)
                initialJumpDone = true
            }
        }
    }

    val currentPhoto = photos.getOrNull(pagerState.currentPage)
    var activeSpatialZoom by rememberSaveable(currentPhoto?.id) { mutableFloatStateOf(1f) }
    var hasCurrentSpatialUltraWide by remember(currentPhoto?.id) { mutableStateOf(false) }
    var hasCurrentSpatialTelephoto by remember(currentPhoto?.id) { mutableStateOf(false) }
    val currentSpatialUltraWideFile = remember(context, currentPhoto?.id, currentPhoto?.uri, currentPhoto?.sourceUri) {
        currentPhoto?.takeIf { it.isVideo }?.let { photo ->
            SpatialVideoStore.ultraWideFile(context, photo.sourceUri ?: photo.uri)
        }
    }
    val currentSpatialTelephotoFile = remember(context, currentPhoto?.id, currentPhoto?.uri, currentPhoto?.sourceUri) {
        currentPhoto?.takeIf { it.isVideo }?.let { photo ->
            SpatialVideoStore.telephotoFile(context, photo.sourceUri ?: photo.uri)
        }
    }
    val isCurrentSpatialVideo = currentPhoto?.isVideo == true && (
        hasCurrentSpatialUltraWide ||
            hasCurrentSpatialTelephoto ||
            currentSpatialUltraWideFile?.let { it.isFile && it.length() > 0L } == true ||
            currentSpatialTelephotoFile?.let { it.isFile && it.length() > 0L } == true
        )

    fun shareSingleSpatialVideo() {
        val photo = currentPhoto ?: return
        val wideFile = currentSpatialUltraWideFile?.takeIf { it.isFile && it.length() > 0L }
        val telephotoFile = currentSpatialTelephotoFile?.takeIf { it.isFile && it.length() > 0L }
        showSpatialShareSheet = false
        val selectedCompanion = when {
            activeSpatialZoom >= 3.35f -> telephotoFile
            activeSpatialZoom < 1f -> wideFile
            else -> null
        }
        if (selectedCompanion == null) {
            viewModel.sharePhoto(photo)
            return
        }
        val lensLabel = if (activeSpatialZoom >= 3.35f) "3.5x telephoto" else "0.5x ultra-wide"
        runCatching {
            val companionUri = FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                selectedCompanion
            )
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, companionUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newUri(
                    context.contentResolver, lensLabel, companionUri
                )
            }
            context.startActivity(Intent.createChooser(share, "Share $lensLabel video"))
        }.onFailure {
            Toast.makeText(context, "Unable to share $lensLabel video", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareBothSpatialVideos() {
        val photo = currentPhoto ?: return
        showSpatialShareSheet = false
        runCatching {
            val primaryUri = photo.sourceUri ?: photo.uri
            val videoUris = arrayListOf(primaryUri)
            currentSpatialUltraWideFile
                ?.takeIf { it.isFile && it.length() > 0L }
                ?.let { file ->
                    videoUris += FileProvider.getUriForFile(
                        context, context.packageName + ".fileprovider", file
                    )
                }
            currentSpatialTelephotoFile
                ?.takeIf { it.isFile && it.length() > 0L }
                ?.let { file ->
                    videoUris += FileProvider.getUriForFile(
                        context, context.packageName + ".fileprovider", file
                    )
                }
            if (videoUris.size == 1) {
                viewModel.sharePhoto(photo)
                return
            }
            val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "video/mp4"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, videoUris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newUri(
                    context.contentResolver, "Special Video", primaryUri
                ).apply {
                    videoUris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) }
                }
            }
            context.startActivity(Intent.createChooser(share, "Share all Special Video lenses"))
        }.onFailure {
            Toast.makeText(context, "Unable to share Special Video recordings", Toast.LENGTH_SHORT).show()
        }
    }

    val refocusSessionId = currentPhoto?.metadata?.customProperties?.get("oppo.refocusSessionId")
    var refocusPlaneFiles by remember(currentPhoto?.id) { mutableStateOf<List<File>>(emptyList()) }
    var refocusDepthBitmap by remember(currentPhoto?.id) { mutableStateOf<Bitmap?>(null) }
    var selectedRefocusPlane by remember(currentPhoto?.id) { mutableIntStateOf(0) }
    LaunchedEffect(currentPhoto?.id, refocusSessionId, currentPhoto?.sourceUri, currentPhoto?.uri) {
        refocusPlaneFiles = emptyList()
        refocusDepthBitmap?.recycle()
        refocusDepthBitmap = null
        val photo = currentPhoto
        if (photo != null && photo.isImage) {
            val assets = GalleryManager.loadRefocusPhotoAssets(
                context = context,
                photoId = photo.id,
                sessionId = refocusSessionId,
                sourceUri = photo.sourceUri ?: photo.uri,
                storedCoverIndex = photo.metadata?.customProperties
                    ?.get("oppo.refocusCoverIndex")
                    ?.toIntOrNull(),
            )
            refocusPlaneFiles = assets?.frameFiles.orEmpty()
            refocusDepthBitmap = assets?.depthMapFile?.let { depthFile ->
                withContext(Dispatchers.IO) {
                    BitmapFactory.decodeFile(depthFile.absolutePath)
                }
            }
            selectedRefocusPlane = assets?.coverIndex
                ?.takeIf { index -> refocusPlaneFiles.isNotEmpty() && index in refocusPlaneFiles.indices }
                ?: (refocusPlaneFiles.size / 2).coerceAtLeast(0)
        }
    }
    val isCurrentPhotoProcessing = currentPhoto?.id?.let(processingPhotos::containsKey) == true

    LaunchedEffect(currentPhoto?.id, isCurrentPhotoProcessing) {
        if (isCurrentPhotoProcessing) {
            isZoomed = false
            showDeleteDialog = false
            showInfoDialog = false
            showExportDialog = false
            showVideoExportConfirmDialog = false
            showMoreSheet = false
            showAiScoreSheet = false
            showHdrStrengthPanel = false
        }
    }

    LaunchedEffect(showVideoExportConfirmDialog, currentPhoto?.id) {
        if (!showVideoExportConfirmDialog || isCurrentPhotoProcessing || currentPhoto?.isVideo != true) return@LaunchedEffect
        selectedVideoExportOption = null
        videoExportOptions = emptyList()
        isLoadingVideoExportOptions = true
        try {
            videoExportOptions = viewModel.getVideoExportOptions(currentPhoto)
            selectedVideoExportOption = videoExportOptions.firstOrNull {
                it.resolution == VideoExportResolution.ORIGINAL && it.isSelectable
            }
        } finally {
            isLoadingVideoExportOptions = false
        }
    }
    val preparingEditPhotoId = viewModel.preparingEditPhotoId
    var hdrStrengthSliderValue by remember(currentPhoto?.id) {
        mutableFloatStateOf(currentPhoto?.let { viewModel.getManualHdrStrength(it) } ?: HdrGainmapStrength.DEFAULT)
    }
    LaunchedEffect(currentPhoto?.id, currentPhoto?.metadata?.hdrEffectStrength) {
        hdrStrengthSliderValue = currentPhoto?.let { viewModel.getManualHdrStrength(it) } ?: HdrGainmapStrength.DEFAULT
    }
    LaunchedEffect(currentPhoto?.id) {
        showHdrStrengthPanel = false
    }
    val isCurrentRawPhoto = !isCurrentPhotoProcessing && currentPhoto?.let {
        it.isImage && (viewModel.selectedTab == GalleryTab.PHOTON || it.relatedPhoto != null) && viewModel.isRaw(it.id)
    } == true
    var displayPhotoSize by remember(currentPhoto?.id) { mutableLongStateOf(currentPhoto?.size ?: 0L) }

    LaunchedEffect(currentPhoto?.id, currentPhoto?.size, currentPhoto?.uri, currentPhoto?.sourceUri, isCurrentPhotoProcessing) {
        if (isCurrentPhotoProcessing) return@LaunchedEffect
        val photo = currentPhoto ?: return@LaunchedEffect
        displayPhotoSize = photo.size
        if (displayPhotoSize > 0L) return@LaunchedEffect

        var resolvedSize = withContext(Dispatchers.IO) {
            resolveCurrentMediaSize(context, photo)
        }
        if (resolvedSize <= 0L && viewModel.awaitPreparedPhotoReady(photo)) {
            resolvedSize = withContext(Dispatchers.IO) {
                maxOf(
                    resolveCurrentMediaSize(context, photo),
                    viewModel.getInternalPhotoSize(photo.id)
                )
            }
        }
        if (resolvedSize > 0L) {
            displayPhotoSize = resolvedSize
        }
    }

    fun leaveDetail(onNavigate: () -> Unit) {
        viewModel.requestGalleryScrollToPhoto(photos.getOrNull(pagerState.currentPage)?.id)
        onNavigate()
    }

    BackHandler(enabled = !isExpanded) {
        leaveDetail(onBack)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier,
                title = {
                    if (onGoToGallery != null) {
                        Surface(
                            onClick = { leaveDetail(onGoToGallery) },
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.15f),
                            contentColor = Color.White
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = AppIcons.GridView,
                                    contentDescription = "Gallery",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "${pagerState.currentPage + 1} / ${photos.size}",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "${pagerState.currentPage + 1} / ${photos.size}",
                            maxLines = 1,
                            color = Color.White,
                            fontSize = 18.sp
                        )
                    }
                },
                navigationIcon = {
                    if (!isExpanded) {
                        IconButton(onClick = { leaveDetail(onBack) }, modifier = Modifier.autoRotate()) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                                tint = Color.White
                            )
                        }
                    }
                },
                actions = {
                    // LIVE 标记
                    if (!isCurrentPhotoProcessing && currentPhoto?.isMotionPhoto == true) {
                        Box(
                            modifier = Modifier.padding(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painterResource(R.drawable.ic_live_photo),
                                    contentDescription = stringResource(R.string.settings_use_live_photo),
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                    if (!isCurrentPhotoProcessing && currentPhoto != null && currentPhoto.isImage && viewModel.isRaw(currentPhoto.id)) {
                        val isRefreshing = viewModel.refreshingPhotos.contains(currentPhoto.id)
                        val infiniteTransition = rememberInfiniteTransition(label = "refresh")
                        val rotation by infiniteTransition.animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1000, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "rotation"
                        )

                        IconButton(
                            onClick = {
                                viewModel.refreshRawPreview(currentPhoto) { success ->
                                    if (success) {
                                        Toast.makeText(context, R.string.refresh_success, Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, R.string.refresh_failed, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            enabled = !isRefreshing,
                            modifier = Modifier.autoRotate()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.refresh),
                                tint = if (isRefreshing) Color.White.copy(alpha = 0.5f) else Color.White,
                                modifier = Modifier.graphicsLayer {
                                    if (isRefreshing) {
                                        rotationZ = rotation
                                    }
                                }
                            )
                        }
                    }
                    if (!isCurrentPhotoProcessing && currentPhoto != null && currentPhoto.isImage && currentPhoto.isBurstPhoto) {
                        IconButton(onClick = { onViewBurst?.invoke(currentPhoto.id) }, modifier = Modifier.autoRotate()) {
                            Icon(
                                imageVector = AppIcons.BurstMode,
                                contentDescription = "查看连拍照片", // 连拍照片
                                tint = Color.White
                            )
                        }
                    }
                    if (
                        !isCurrentPhotoProcessing &&
                        currentPhoto?.isImage == true &&
                        refocusPlaneFiles.isNotEmpty()
                    ) {
                        IconButton(
                            onClick = { showRefocusViewer = true },
                            modifier = Modifier.autoRotate(),
                        ) {
                            Icon(
                                imageVector = AppIcons.Tune,
                                contentDescription = stringResource(R.string.oppo_refocus),
                                tint = Color.White,
                            )
                        }
                    }
                    if (
                        !isCurrentPhotoProcessing &&
                        currentPhoto != null &&
                        currentPhoto.isImage &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                        !DeviceUtil.isHarmonyOS &&
                        viewModel.canToggleManualHdrEnhance(currentPhoto)
                    ) {
                        val hdrEnabled = viewModel.isManualHdrEnhanceEnabled(currentPhoto)
                        TextButton(
                            onClick = {
                                showHdrStrengthPanel = !showHdrStrengthPanel
                            },
                        ) {
                            Text(
                                text = stringResource(R.string.hdr_label),
                                color = if (hdrEnabled) AccentColor else Color.White.copy(alpha = 0.72f),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    IconButton(onClick = { showInfoDialog = true }, enabled = !isCurrentPhotoProcessing, modifier = Modifier.autoRotate()) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = stringResource(if (currentPhoto?.isVideo == true) R.string.video_info else R.string.photo_info),
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.8f)
                )
            )
        },
        bottomBar = {
            GalleryBottomActionBar(
                leadingAction = {
                    GalleryCircleActionButton(
                        icon = Icons.Default.Share,
                        contentDescription = stringResource(R.string.share),
                        isLoading = isSharing,
                        enabled = currentPhoto != null && !isSharing && !isCurrentPhotoProcessing,
                        onClick = {
                            if (isCurrentSpatialVideo) {
                                showSpatialShareSheet = true
                            } else {
                                currentPhoto?.let(viewModel::sharePhoto)
                            }
                        }
                    )
                },
                groupedActions = {
                    if (currentPhoto?.isImage == true) {
                        GalleryGroupedActionButton(
                            icon = AppIcons.AutoAwesome,
                            contentDescription = stringResource(R.string.gallery_ai_analysis),
                            enabled = !isCurrentPhotoProcessing,
                            onClick = { showAiScoreSheet = true }
                        )
                    }

                    if (currentPhoto?.isImage == true || currentPhoto?.isVideo == true) {
                        GalleryGroupedActionButton(
                            icon = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.edit),
                            enabled = !isCurrentPhotoProcessing,
                            isLoading = preparingEditPhotoId == currentPhoto.id,
                            onClick = {
                                viewModel.prepareCurrentPhotoForEdit(
                                    index = pagerState.currentPage,
                                    onReady = onEdit,
                                    onFailure = {
                                        Toast.makeText(
                                            context,
                                            R.string.gallery_prepare_edit_failed,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                )
                            }
                        )
                    }

                    GalleryGroupedActionButton(
                        icon = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete),
                        enabled = currentPhoto != null && !isCurrentPhotoProcessing,
                        onClick = { showDeleteDialog = true }
                    )
                },
                trailingAction = {
                    GalleryCircleActionButton(
                        icon = AppIcons.MoreHoriz,
                        contentDescription = stringResource(R.string.more_options),
                        enabled = currentPhoto != null && !isCurrentPhotoProcessing,
                        onClick = { showMoreSheet = true }
                    )
                }
            )
        },
        containerColor = Color.Black,
        modifier = modifier
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (photos.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.no_photos),
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 16.sp
                    )
                }
            } else {
                HorizontalPager(
                    state = pagerState,
                    key = { page -> if (page < photos.size) photos[page].id else page },
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = !isZoomed,
                    beyondViewportPageCount = 1
                ) { page ->
                    val photo = photos.getOrNull(page)
                    if (photo != null) {
                        val processingPhoto = processingPhotos[photo.id]
                        val showProcessingOverlay = processingPhoto != null &&
                            !processingPhoto.isDisplayReady
                        key(photo.id, showProcessingOverlay) {
                            if (processingPhoto != null && showProcessingOverlay) {
                                ProcessingPhotoPreview(processingPhoto, Modifier.fillMaxSize())
                            } else {

                                var showOrigin by remember { mutableStateOf(false) }
                                var isPlaying by remember { mutableStateOf(false) }

                                Box(
                                    modifier = Modifier.fillMaxSize().pointerInput(photo.id, photo.isImage, photo.isMotionPhoto) {
                                        if (!photo.isImage) return@pointerInput
                                        awaitPointerEventScope {
                                            while (true) {
                                                val downEvent = awaitPointerEvent(PointerEventPass.Initial)
                                                if (downEvent.type == PointerEventType.Press && downEvent.changes.size == 1) {
                                                    val touchSlop = viewConfiguration.touchSlop
                                                    val initialPosition = downEvent.changes[0].position
                                                    val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                                                    var upEvent: PointerEvent? = null
                                                    var isMultiTouch = false
                                                    var isMoved = false

                                                    withTimeoutOrNull(longPressTimeout) {
                                                        while (true) {
                                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                                            if (event.changes.size > 1) {
                                                                isMultiTouch = true
                                                                break
                                                            }

                                                            val currentPosition = event.changes[0].position
                                                            if ((currentPosition - initialPosition).getDistance() > touchSlop) {
                                                                isMoved = true
                                                                break
                                                            }

                                                            if (event.type == PointerEventType.Release) {
                                                                upEvent = event
                                                                break
                                                            }
                                                        }
                                                    }

                                                    if (!isMultiTouch && !isMoved && upEvent == null) {
                                                        if (photo.isMotionPhoto) {
                                                            isPlaying = true
                                                        } else {
                                                            showOrigin = true
                                                        }
                                                        while (true) {
                                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                                            if (event.type == PointerEventType.Release || event.changes.size > 1) {
                                                                break
                                                            }
                                                        }
                                                        showOrigin = false
                                                        isPlaying = false
                                                    }
                                                }
                                            }
                                        }
                                    }
                                ) {
                                    if (photo.isVideo) {
                                        VideoDetailPlayer(
                                            photo = photo,
                                            isActive = page == pagerState.currentPage &&
                                                !viewModel.isEditing &&
                                                !showVideoExportConfirmDialog &&
                                                !isVideoExporting,
                                            viewModel = viewModel,
                                            previewHandle = videoPreviewHandle,
                                            spatialZoomRatio = if (page == pagerState.currentPage) activeSpatialZoom else 1f,
                                            onSpatialZoomChange = { ratio ->
                                                if (page == pagerState.currentPage) activeSpatialZoom = ratio.coerceIn(0.5f, 3.5f)
                                            },
                                            onSpatialCompanionAvailability = { available ->
                                                if (page == pagerState.currentPage) hasCurrentSpatialUltraWide = available
                                            },
                                            onSpatialTelephotoAvailability = { available ->
                                                if (page == pagerState.currentPage) hasCurrentSpatialTelephoto = available
                                            },
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        val forceSystemOrigin = viewModel.selectedTab == GalleryTab.SYSTEM &&
                                            photo.relatedPhoto == null
                                        ZoomableImage(
                                            photo = photo,
                                            colorSpace = currentColorSpace,
                                            showOrigin = showOrigin || forceSystemOrigin,
                                            isActive = page == pagerState.currentPage,
                                            isScrollInProgress = pagerState.isScrollInProgress,
                                            viewModel = viewModel,
                                            showRawBadge = photo.isImage && viewModel.isRawMedia(photo),
                                            onZoomChange = { zoomed ->
                                                if (page == pagerState.currentPage) {
                                                    isZoomed = zoomed
                                                }
                                            },
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        MotionPhotoPlayer(
                                            photo = photo,
                                            isPlaying = isPlaying,
                                            viewModel = viewModel,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (
                !isCurrentPhotoProcessing &&
                showHdrStrengthPanel &&
                currentPhoto != null &&
                currentPhoto.isImage &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                !DeviceUtil.isHarmonyOS &&
                viewModel.canToggleManualHdrEnhance(currentPhoto)
            ) {
                HdrStrengthPanel(
                    enabled = viewModel.isManualHdrEnhanceEnabled(currentPhoto),
                    strength = hdrStrengthSliderValue,
                    onEnabledChange = {
                        viewModel.toggleManualHdrEnhance(currentPhoto) { success ->
                            if (!success) {
                                Toast.makeText(context, R.string.hdr_toggle_failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onStrengthChange = { hdrStrengthSliderValue = it },
                    onStrengthChangeFinished = {
                        viewModel.setManualHdrStrength(currentPhoto, hdrStrengthSliderValue) { success ->
                            if (!success) {
                                Toast.makeText(context, R.string.hdr_strength_update_failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 12.dp, end = 12.dp)
                )
            }
        }
    }

    val deleteExportedPref by viewModel.deleteExported.collectAsState()

    // 删除确认对话框
    if (showDeleteDialog && !isCurrentPhotoProcessing) {
        val exportedPhotosCount = remember(currentPhoto, currentPhoto?.metadata, currentPhoto?.metadata?.exportedUris) {
            val baseCount = currentPhoto?.metadata?.exportedUris?.size ?: 0
            val sourceUri = currentPhoto?.metadata?.sourceUri
            val isVideoAndCaptured = currentPhoto?.isVideo == true &&
                    currentPhoto.metadata?.isImported != true &&
                    !sourceUri.isNullOrBlank()
            baseCount + (if (isVideoAndCaptured) 1 else 0)
        }
        var deleteExportedState by remember(showDeleteDialog) { mutableStateOf(deleteExportedPref) }

        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete)) },
            text = {
                Column {
                    Text(stringResource(R.string.delete_confirm))
                    if (viewModel.selectedTab == GalleryTab.PHOTON) {
                        if (exportedPhotosCount > 0) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { deleteExportedState = !deleteExportedState }
                                    .padding(vertical = 4.dp)
                            ) {
                                Checkbox(
                                    checked = deleteExportedState,
                                    onCheckedChange = { deleteExportedState = it },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = AccentColor,
                    checkmarkColor = OnAccentColor,
                                        uncheckedColor = Color.White.copy(alpha = 0.6f)
                                    )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (currentPhoto?.isVideo == true) {
                                        stringResource(R.string.delete_exported_videos_count, exportedPhotosCount)
                                    } else {
                                        stringResource(R.string.delete_exported_photos_count, exportedPhotosCount)
                                    },
                                    color = Color.White,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        currentPhoto?.let { photo ->
                            val finalDeleteExported = if (viewModel.selectedTab == GalleryTab.PHOTON && exportedPhotosCount > 0) deleteExportedState else true
                            if (viewModel.selectedTab == GalleryTab.PHOTON && exportedPhotosCount > 0) {
                                viewModel.setDeleteExported(deleteExportedState)
                            }
                            viewModel.requestDeletePhoto(photo, finalDeleteExported)
                        }
                        showDeleteDialog = false
                    }
                ) {
                    Text(stringResource(R.string.delete), color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            containerColor = Color(0xFF2D2D2D),
            titleContentColor = Color.White,
            textContentColor = Color.White
        )
    }

    // 导出确认对话框
    if (showExportDialog && !isCurrentPhotoProcessing) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text(stringResource(R.string.export)) },
            text = {
                Text(stringResource(R.string.export_confirm))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExportDialog = false
                        currentPhoto?.let {
                            isSaving = true
                            viewModel.exportPhoto(it) { success ->
                                isSaving = false
                                if (success) {
                                    Toast.makeText(context, R.string.export_success, Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.export), color = AccentColor)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            containerColor = Color(0xFF2D2D2D),
            titleContentColor = Color.White,
            textContentColor = Color.White
        )
    }

    // 视频导出确认对话框
    if (showVideoExportConfirmDialog && !isCurrentPhotoProcessing) {
        AlertDialog(
            onDismissRequest = {
                showVideoExportConfirmDialog = false
                selectedVideoExportOption = null
            },
            title = { Text(stringResource(R.string.export_video)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.export_video_select_resolution))
                    if (isLoadingVideoExportOptions) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                        videoExportOptions.forEach { option ->
                            val selectable = option.isSelectable && !isVideoExporting
                            val statusText = when (option.support) {
                                VideoExportSupport.SUPPORTED -> null
                                VideoExportSupport.MAY_FAIL -> R.string.export_video_resolution_may_fail
                                VideoExportSupport.UNSUPPORTED -> R.string.export_video_resolution_unsupported
                                VideoExportSupport.SOURCE_TOO_SMALL -> R.string.export_video_resolution_source_too_small
                            }
                            val statusColor = when (option.support) {
                                VideoExportSupport.SUPPORTED -> MaterialTheme.colorScheme.primary
                                VideoExportSupport.MAY_FAIL -> WarningYellow
                                VideoExportSupport.UNSUPPORTED,
                                VideoExportSupport.SOURCE_TOO_SMALL -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(enabled = selectable) {
                                        selectedVideoExportOption = option
                                    }
                                    .padding(horizontal = 4.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = selectedVideoExportOption == option,
                                    onClick = { selectedVideoExportOption = option },
                                    enabled = selectable,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = videoExportResolutionLabel(option.resolution),
                                        color = if (selectable) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                        },
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        text = if (statusText == null) {
                                            stringResource(
                                                R.string.export_video_resolution_dimensions,
                                                option.outputWidth,
                                                option.outputHeight,
                                            )
                                        } else {
                                            stringResource(
                                                R.string.export_video_resolution_details,
                                                option.outputWidth,
                                                option.outputHeight,
                                                stringResource(statusText),
                                            )
                                        },
                                        color = statusColor,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.export_video_confirm),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selectedVideoExportOption != null && !isLoadingVideoExportOptions,
                    onClick = {
                        val exportOption = selectedVideoExportOption ?: return@TextButton
                        showVideoExportConfirmDialog = false
                        selectedVideoExportOption = null
                        currentPhoto?.let { photo ->
                            viewModel.exportVideo(photo, exportOption) { success, _ ->
                                val msgRes = if (success) R.string.export_video_success else R.string.export_video_failed
                                Toast.makeText(context, msgRes, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.export_video))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showVideoExportConfirmDialog = false
                    selectedVideoExportOption = null
                }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // 照片信息对话框
    if (showInfoDialog && currentPhoto != null && !isCurrentPhotoProcessing) {
        val infoPhoto = currentPhoto.relatedPhoto ?: currentPhoto
        val infoMetadata = infoPhoto.metadata ?: currentPhoto.metadata
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text(stringResource(if (currentPhoto.isVideo) R.string.video_info else R.string.photo_info)) },
            text = {
                Column {
                    if (viewModel.selectedTab == GalleryTab.SYSTEM) {
                        InfoRow(stringResource(R.string.name), currentPhoto.displayName)
                    }
                    InfoRow(stringResource(R.string.photo_info_date), currentPhoto.getFormattedDate())
                    InfoRow(stringResource(R.string.photo_info_resolution), currentPhoto.getResolution())
                    InfoRow(stringResource(R.string.photo_info_size), currentPhoto.copy(size = displayPhotoSize).getFormattedSize())
                    infoMetadata?.let {
                        if (currentPhoto.isVideo) {
                            InfoRow(stringResource(R.string.video_info_duration), currentPhoto.getFormattedDuration())
                            InfoRow(stringResource(R.string.video_info_mime), it.mimeType ?: (currentPhoto.mimeType ?: "N/A"))
                            InfoRow(stringResource(R.string.video_info_frame_rate), it.frameRate?.toString() ?: "N/A")
                            InfoRow(stringResource(R.string.video_info_bitrate), it.bitrate?.let { bitrate -> "${bitrate / 1000} kbps" } ?: "N/A")
                            InfoRow(stringResource(R.string.video_info_has_audio), it.hasAudio?.let { hasAudio -> if (hasAudio) stringResource(R.string.yes) else stringResource(R.string.no) } ?: "N/A")
                            InfoRow(stringResource(R.string.video_info_rotation), it.rotationDegrees?.toString() ?: "N/A")
                        } else {
                            InfoRow(stringResource(R.string.photo_info_focal_length), it.focalLength35mm ?: "N/A")
                            InfoRow(stringResource(R.string.photo_info_aperture), it.aperture ?: "N/A")
                            InfoRow(stringResource(R.string.photo_info_iso), it.iso?.toString() ?: "N/A")
                            InfoRow(stringResource(R.string.photo_info_shutter_speed), it.shutterSpeed ?: "N/A")
                            if (DeviceUtil.canShowPhantom) {
                                InfoRow("LV", "%.2f".format(it.lv))
                                InfoRow("平均亮度", "%.2f".format(viewModel.currentBrightness[currentPhoto.id]))
                            }
                        }
                    }
                    currentColorSpace.value?.let {
                        InfoRow(stringResource(R.string.color_space), it.name)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            containerColor = Color(0xFF2D2D2D),
            titleContentColor = Color.White,
            textContentColor = Color.White
        )
    }

    // AI 评分 BottomSheet
    if (showAiScoreSheet && currentPhoto != null && currentPhoto.isImage && !isCurrentPhotoProcessing) {
        AiScoreBottomSheet(
            photo = currentPhoto,
            viewModel = viewModel,
            isPurchased = isPurchased,
            onPurchase = { viewModel.showPaymentDialog = true },
            onDismissRequest = { showAiScoreSheet = false }
        )
    }

    if (viewModel.showPaymentDialog) {
        val activity = context.findActivity()
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

    if (showRefocusViewer && currentPhoto != null && refocusPlaneFiles.isNotEmpty()) {
        AppModalBottomSheet(
            containerColor = GallerySheetSurface,
            shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
            scrimColor = Color.Black.copy(alpha = 0.64f),
            onDismissRequest = { showRefocusViewer = false },
            contentColor = GalleryToolbarContent,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 24.dp),
            ) {
                Text(
                    text = stringResource(R.string.oppo_refocus),
                    color = GalleryToolbarContent,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.oppo_refocus_tap_subject),
                    color = GalleryToolbarContent.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                val planeIndex = selectedRefocusPlane.coerceIn(0, refocusPlaneFiles.lastIndex)
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(min = 220.dp, max = 440.dp)
                        .pointerInput(refocusDepthBitmap, refocusPlaneFiles) {
                            detectTapGestures { offset ->
                                val map = refocusDepthBitmap
                                if (map != null && !map.isRecycled && map.width > 0 && map.height > 0) {
                                    val x = (offset.x / size.width * map.width).toInt().coerceIn(0, map.width - 1)
                                    val y = (offset.y / size.height * map.height).toInt().coerceIn(0, map.height - 1)
                                    val depth = (map.getPixel(x, y) and 0xFF) / 255f
                                    selectedRefocusPlane = (depth * refocusPlaneFiles.lastIndex).roundToInt()
                                        .coerceIn(0, refocusPlaneFiles.lastIndex)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = refocusPlaneFiles[planeIndex],
                        contentDescription = stringResource(R.string.oppo_refocus_preview),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 440.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.oppo_refocus_near),
                        color = GalleryToolbarContent.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                    )
                    Text(
                        text = stringResource(R.string.oppo_refocus_plane_count, planeIndex + 1, refocusPlaneFiles.size),
                        color = AccentColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.oppo_refocus_far),
                        color = GalleryToolbarContent.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                    )
                }
                Slider(
                    value = planeIndex.toFloat(),
                    valueRange = 0f..refocusPlaneFiles.lastIndex.toFloat(),
                    steps = (refocusPlaneFiles.size - 2).coerceAtLeast(0),
                    onValueChange = { selectedRefocusPlane = it.roundToInt() },
                )
            }
        }
    }

    // 更多 BottomSheet
    if (showMoreSheet && currentPhoto != null && !isCurrentPhotoProcessing) {
        val moreSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val moreActions = buildList {
            if (currentPhoto.isImage && (viewModel.selectedTab == GalleryTab.PHOTON || currentPhoto.relatedPhoto != null)) {
                add(
                    GalleryMoreAction(
                        icon = AppIcons.Output,
                        text = context.getString(R.string.export),
                        isLoading = isSaving,
                        enabled = !isCopyingSettings && !isPastingSettings,
                        onClick = {
                            showExportDialog = true
                        }
                    )
                )
            }

            if (currentPhoto.isVideo) {
                add(
                    GalleryMoreAction(
                        icon = AppIcons.Output,
                        text = if (isVideoExporting && videoExportProgress > 0) {
                            "$videoExportProgress%"
                        } else {
                            context.getString(R.string.export)
                        },
                        isLoading = isVideoExporting,
                        enabled = !isCopyingSettings && !isPastingSettings,
                        onClick = {
                            if (isVideoTransformerExportSupported()) {
                                videoPreviewHandle.release()
                                showVideoExportConfirmDialog = true
                            } else {
                                Toast.makeText(
                                    context,
                                    R.string.export_video_requires_android12,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                )
            }

            add(
                GalleryMoreAction(
                    icon = AppIcons.ContentCopy,
                    text = context.getString(R.string.copy_settings),
                    isLoading = isCopyingSettings,
                    enabled = !isCopyingSettings &&
                        !isPastingSettings &&
                        !isSharing &&
                        !isSaving,
                    onClick = {
                        isCopyingSettings = true
                        viewModel.copyPhotoSettings(currentPhoto) { success ->
                            isCopyingSettings = false
                            if (success) {
                                showMoreSheet = false
                            }
                            Toast.makeText(
                                context,
                                if (success) {
                                    R.string.copy_settings_success
                                } else {
                                    R.string.copy_settings_failed
                                },
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            )

            add(
                GalleryMoreAction(
                    icon = AppIcons.ContentCopy,
                    text = context.getString(R.string.paste_settings),
                    isLoading = isPastingSettings,
                    enabled = hasCopiedEditSettings &&
                        !isCopyingSettings &&
                        !isPastingSettings &&
                        !isSharing &&
                        !isSaving,
                    onClick = {
                        isPastingSettings = true
                        viewModel.pasteCopiedSettingsToPhoto(currentPhoto) { success ->
                            isPastingSettings = false
                            if (success) {
                                showMoreSheet = false
                            }
                            Toast.makeText(
                                context,
                                if (success) {
                                    R.string.paste_settings_success
                                } else {
                                    R.string.paste_settings_failed
                                },
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            )

            if (isCurrentRawPhoto) {
                add(
                    GalleryMoreAction(
                        iconText = context.getString(R.string.dng_format),
                        text = context.getString(R.string.dng_format),
                        isLoading = isExportingDng,
                        enabled = !isSaving &&
                            !isExportingDng &&
                            !isCopyingSettings &&
                            !isPastingSettings,
                        onClick = {
                            showMoreSheet = false
                            isExportingDng = true
                            viewModel.exportDng(currentPhoto) { success ->
                                isExportingDng = false
                                Toast.makeText(
                                    context,
                                    if (success) R.string.export_dng_success else R.string.export_dng_failed,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                )
            }
        }

        @OptIn(ExperimentalMaterial3Api::class)
        AppModalBottomSheet(
            containerColor = GallerySheetSurface,
            shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
            scrimColor = Color.Black.copy(alpha = 0.64f),
            onDismissRequest = { showMoreSheet = false },
            sheetState = moreSheetState,
            contentColor = GalleryToolbarContent,
            dragHandle = {
                BottomSheetDefaults.DragHandle(
                    width = 36.dp,
                    height = 4.dp,
                    color = GalleryToolbarContent.copy(alpha = 0.24f)
                )
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                    Text(
                        text = stringResource(R.string.more_options),
                        color = GalleryToolbarContent,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.2).sp
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))
                HorizontalDivider(color = GalleryToolbarContent.copy(alpha = 0.09f))
                Spacer(modifier = Modifier.height(14.dp))

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    moreActions.chunked(4).forEach { rowActions ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            rowActions.forEach { action ->
                                GalleryMoreActionCard(
                                    action = action,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            repeat(4 - rowActions.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSpatialShareSheet && currentPhoto != null && isCurrentSpatialVideo &&
        (currentSpatialUltraWideFile != null || currentSpatialTelephotoFile != null)
    ) {
        AppModalBottomSheet(
            containerColor = GallerySheetSurface,
            shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
            scrimColor = Color.Black.copy(alpha = 0.64f),
            onDismissRequest = { showSpatialShareSheet = false },
            sheetState = spatialShareSheetState,
            contentColor = GalleryToolbarContent,
            dragHandle = {
                BottomSheetDefaults.DragHandle(
                    width = 36.dp,
                    height = 4.dp,
                    color = GalleryToolbarContent.copy(alpha = 0.24f)
                )
            }
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Share Special Video", color = GalleryToolbarContent, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Choose the 0.5x, 1x or 3.5x recording, or share every available lens file.",
                    color = GalleryToolbarContent.copy(alpha = 0.68f),
                    fontSize = 13.sp
                )
                Surface(
                    onClick = { shareSingleSpatialVideo() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = GalleryToolbarButton,
                    contentColor = GalleryToolbarContent
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Single video", fontWeight = FontWeight.SemiBold)
                            Text(
                                when {
                                    activeSpatialZoom >= 3.35f -> "Share the selected 3.5x virtual telephoto recording"
                                    activeSpatialZoom < 1f -> "Share the selected 0.5x ultra-wide recording"
                                    else -> "Share the selected 1x main-camera recording"
                                },
                                color = GalleryToolbarContent.copy(alpha = 0.65f),
                                fontSize = 12.sp
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                    }
                }
                Surface(
                    onClick = { shareBothSpatialVideos() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = GalleryToolbarButton,
                    contentColor = GalleryToolbarContent
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(AppIcons.GridView, contentDescription = null)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("All lenses", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Share the original 1x, 0.5x and 3.5x videos together",
                                color = GalleryToolbarContent.copy(alpha = 0.65f),
                                fontSize = 12.sp
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiScoreBottomSheet(
    photo: MediaData,
    viewModel: GalleryViewModel,
    isPurchased: Boolean,
    onPurchase: () -> Unit,
    onDismissRequest: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var requestToken by remember(photo.id) { mutableIntStateOf(0) }
    var uiState by remember(photo.id) { mutableStateOf<AiEvaluationUiState>(AiEvaluationUiState.Loading) }

    val openAIKey by viewModel.openAIApiKey.collectAsState()
    val canEvaluate = isPurchased || !openAIKey.isNullOrBlank()

    LaunchedEffect(photo.id, requestToken, canEvaluate) {
        if (!canEvaluate) return@LaunchedEffect
        uiState = AiEvaluationUiState.Loading
        uiState = viewModel.evaluatePhotoWithAi(photo).fold(
            onSuccess = { AiEvaluationUiState.Success(it) },
            onFailure = { AiEvaluationUiState.Error(it) }
        )
    }

    AppModalBottomSheet(
        containerColor = AiEditorialBackground,
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = AiEditorialOnSurface.copy(alpha = 0.28f))
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 36.dp, start = 20.dp, end = 20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.gallery_ai_quality_title),
                        color = AiEditorialOnSurface,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(
                        text = stringResource(R.string.gallery_ai_editorial_framework),
                        color = AiEditorialAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                }
                IconButton(onClick = onDismissRequest) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.cancel),
                        tint = AiEditorialOnSurface.copy(alpha = 0.62f)
                    )
                }
            }

            if (!canEvaluate) {
                Text(
                    text = stringResource(R.string.gallery_ai_premium_required),
                    color = AiEditorialOnSurface.copy(alpha = 0.68f),
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onPurchase,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentColor,
                        contentColor = OnAccentColor
                    )
                ) {
                    Text(
                        text = stringResource(R.string.billing_premium_get_access),
                        fontWeight = FontWeight.Bold
                    )
                }
                return@Column
            }

            when (val state = uiState) {
                AiEvaluationUiState.Loading -> {
                    AiScoreLoading()
                }

                is AiEvaluationUiState.Error -> {
                    AiScoreError(onRetry = { requestToken += 1 })
                }

                is AiEvaluationUiState.Success -> {
                    AiScoreReview(evaluation = state.evaluation)
                }
            }
        }
    }
}

@Composable
private fun AiScoreLoading() {
    Surface(
        color = AiEditorialSurface,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, AiEditorialOnSurface.copy(alpha = 0.08f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(vertical = 44.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(
                color = AiEditorialAccent,
                modifier = Modifier.size(30.dp),
                strokeWidth = 3.dp
            )
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.gallery_ai_analyzing),
                color = AiEditorialOnSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.gallery_ai_analyzing_description),
                color = AiEditorialOnSurface.copy(alpha = 0.5f),
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
        }
    }
}

@Composable
private fun AiScoreError(onRetry: () -> Unit) {
    Surface(
        color = AiEditorialSurface,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, AiEditorialOnSurface.copy(alpha = 0.08f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = Color(0xFFD98270),
                modifier = Modifier.size(30.dp)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.gallery_ai_analysis_failed),
                color = AiEditorialOnSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.gallery_ai_analysis_failed_description),
                color = AiEditorialOnSurface.copy(alpha = 0.55f),
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
            Spacer(modifier = Modifier.height(14.dp))
            TextButton(onClick = onRetry) {
                Text(
                    text = stringResource(R.string.lut_creator_try_again),
                    color = AiEditorialAccent,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun AiScoreReview(evaluation: AiPhotoEvaluation) {
    val score = evaluation.overallScore
    val scoreColor = aiScoreColor(score)
    val rankText = when {
        score >= 90 -> stringResource(R.string.gallery_ai_rank_excellent_plus)
        score >= 82 -> stringResource(R.string.gallery_ai_rank_excellent)
        score >= 74 -> stringResource(R.string.gallery_ai_rank_good)
        score >= 65 -> stringResource(R.string.gallery_ai_rank_pass)
        else -> stringResource(R.string.gallery_ai_rank_needs_work)
    }
    val animatedProgress by animateFloatAsState(
        targetValue = score / 100f,
        animationSpec = tween(1200, easing = FastOutSlowInEasing),
        label = "overallScoreProgress"
    )
    val animatedScore by animateIntAsState(
        targetValue = score,
        animationSpec = tween(1200, easing = FastOutSlowInEasing),
        label = "overallScore"
    )

    Surface(
        color = AiEditorialSurface,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, AiEditorialOnSurface.copy(alpha = 0.08f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(22.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.gallery_ai_score_label),
                        color = AiEditorialOnSurface.copy(alpha = 0.48f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = animatedScore.toString(),
                            color = scoreColor,
                            fontSize = 56.sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 58.sp
                        )
                        Text(
                            text = stringResource(R.string.gallery_ai_score_out_of),
                            color = AiEditorialOnSurface.copy(alpha = 0.42f),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(bottom = 9.dp, start = 4.dp)
                        )
                    }
                }
                Surface(
                    color = scoreColor.copy(alpha = 0.12f),
                    shape = CircleShape,
                    border = BorderStroke(1.dp, scoreColor.copy(alpha = 0.28f))
                ) {
                    Text(
                        text = rankText,
                        color = scoreColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(CircleShape),
                color = scoreColor,
                trackColor = AiEditorialOnSurface.copy(alpha = 0.08f),
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            Spacer(modifier = Modifier.height(22.dp))
            HorizontalDivider(color = AiEditorialOnSurface.copy(alpha = 0.08f))
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = evaluation.verdict,
                color = AiEditorialOnSurface.copy(alpha = 0.88f),
                fontSize = 16.sp,
                lineHeight = 24.sp
            )
        }
    }

    Spacer(modifier = Modifier.height(12.dp))
    AiEditorialCallout(
        title = stringResource(R.string.gallery_ai_strength),
        text = evaluation.strength,
        icon = AppIcons.AutoAwesome,
        accent = AiEditorialAccent
    )
    Spacer(modifier = Modifier.height(10.dp))
    AiEditorialCallout(
        title = stringResource(R.string.gallery_ai_next_step),
        text = evaluation.improvement,
        icon = AppIcons.AutoMirroredArrowRight,
        accent = Color(0xFF8FAEA3)
    )

    Spacer(modifier = Modifier.height(28.dp))
    Text(
        text = stringResource(R.string.gallery_ai_criteria_title),
        color = AiEditorialOnSurface,
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(modifier = Modifier.height(5.dp))
    Text(
        text = stringResource(R.string.gallery_ai_criteria_description),
        color = AiEditorialOnSurface.copy(alpha = 0.48f),
        fontSize = 12.sp,
        lineHeight = 18.sp
    )
    Spacer(modifier = Modifier.height(14.dp))

    val criteria = listOf(
        stringResource(R.string.gallery_ai_criterion_visual_impact) to
            evaluation.scores.visualImpact,
        stringResource(R.string.gallery_ai_criterion_originality_voice) to
            evaluation.scores.originalityAndVoice,
        stringResource(R.string.gallery_ai_criterion_narrative_meaning) to
            evaluation.scores.narrativeAndMeaning,
        stringResource(R.string.gallery_ai_criterion_intent_coherence) to
            evaluation.scores.intentAndCoherence,
        stringResource(R.string.gallery_ai_criterion_aesthetic_technical) to
            evaluation.scores.aestheticAndTechnicalExecution
    )
    criteria.forEachIndexed { index, (label, criterion) ->
        AiScoreCriterionCard(
            index = index + 1,
            label = label,
            criterion = criterion
        )
        if (index != criteria.lastIndex) {
            Spacer(modifier = Modifier.height(10.dp))
        }
    }

    Spacer(modifier = Modifier.height(18.dp))
    Text(
        text = stringResource(R.string.gallery_ai_scale_note),
        color = AiEditorialOnSurface.copy(alpha = 0.38f),
        fontSize = 11.sp,
        lineHeight = 16.sp,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun AiEditorialCallout(
    title: String,
    text: String,
    icon: ImageVector,
    accent: Color
) {
    Surface(
        color = accent.copy(alpha = 0.08f),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.18f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(17.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(19.dp)
            )
            Spacer(modifier = Modifier.width(13.dp))
            Column {
                Text(
                    text = title,
                    color = accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = text,
                    color = AiEditorialOnSurface.copy(alpha = 0.78f),
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            }
        }
    }
}

@Composable
private fun AiScoreCriterionCard(
    index: Int,
    label: String,
    criterion: AiPhotoCriterion
) {
    val color = aiScoreColor(criterion.score)
    val animatedProgress by animateFloatAsState(
        targetValue = criterion.score / 100f,
        animationSpec = tween(
            durationMillis = 900,
            delayMillis = index * 90,
            easing = FastOutSlowInEasing
        ),
        label = "criterionScore$index"
    )

    Surface(
        color = AiEditorialSurface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, AiEditorialOnSurface.copy(alpha = 0.07f)),
        modifier = Modifier
            .fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(17.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = index.toString().padStart(2, '0'),
                    color = AiEditorialAccent.copy(alpha = 0.72f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.width(30.dp)
                )
                Text(
                    text = label,
                    color = AiEditorialOnSurface.copy(alpha = 0.86f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = criterion.score.toString(),
                    color = color,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(AiEditorialOnSurface.copy(alpha = 0.08f), CircleShape)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animatedProgress)
                        .background(color, CircleShape)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = criterion.feedback,
                color = AiEditorialOnSurface.copy(alpha = 0.58f),
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
        }
    }
}

private val AiEditorialBackground = Color(0xFF11110F)
private val AiEditorialSurface = Color(0xFF1A1A17)
private val AiEditorialOnSurface = Color(0xFFF3EFE7)
private val AiEditorialAccent = Color(0xFFE7904F)

private fun aiScoreColor(score: Int): Color =
    when {
        score >= 90 -> Color(0xFFF0C96B)
        score >= 82 -> AiEditorialAccent
        score >= 74 -> Color(0xFFD7A36D)
        score >= 65 -> Color(0xFFB8B2A7)
        else -> Color(0xFFD98270)
    }

private sealed class AiEvaluationUiState {
    object Loading : AiEvaluationUiState()
    data class Success(val evaluation: AiPhotoEvaluation) : AiEvaluationUiState()
    data class Error(val error: Throwable) : AiEvaluationUiState()
}

@Composable
private fun HdrStrengthPanel(
    enabled: Boolean,
    strength: Float,
    onEnabledChange: (Boolean) -> Unit,
    onStrengthChange: (Float) -> Unit,
    onStrengthChangeFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color(0x881E1E1E),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.width(200.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.hdr_label),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = OnAccentColor,
                        checkedTrackColor = AccentColor,
                        uncheckedThumbColor = Color.LightGray,
                        uncheckedTrackColor = Color.DarkGray
                    )
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.hdr_strength_label),
                        color = Color.White.copy(alpha = 0.86f),
                        fontSize = 9.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${(strength * 100f).roundToInt()}%",
                        color = AccentColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp
                    )
                }
                CustomSlider(
                    value = strength,
                    onValueChange = onStrengthChange,
                    valueRange = HdrGainmapStrength.MIN..HdrGainmapStrength.MAX,
                    enabled = enabled,
                    onValueChangeFinished = onStrengthChangeFinished,
                )
            }
        }
    }
}

private data class GalleryMoreAction(
    val text: String,
    val onClick: () -> Unit,
    val icon: ImageVector? = null,
    val iconText: String? = null,
    val enabled: Boolean = true,
    val contentColor: Color = GalleryToolbarContent,
    val isLoading: Boolean = false
)

@Composable
private fun GalleryBottomActionBar(
    leadingAction: @Composable () -> Unit,
    groupedActions: @Composable RowScope.() -> Unit,
    trailingAction: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = GalleryToolbarSurface,
        shadowElevation = 10.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier
                    .widthIn(max = 380.dp)
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                leadingAction()
                Surface(
                    modifier = Modifier
                        .height(40.dp)
                        .widthIn(min = 116.dp)
                        .shadow(
                            elevation = 5.dp,
                            shape = CircleShape,
                            ambientColor = Color.Black.copy(alpha = 0.18f),
                            spotColor = Color.Black.copy(alpha = 0.18f)
                        ),
                    shape = CircleShape,
                    color = GalleryToolbarButton,
                    tonalElevation = 0.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        content = groupedActions
                    )
                }
                trailingAction()
            }
        }
    }
}

@Composable
private fun GalleryCircleActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    isLoading: Boolean = false
) {
    val isInteractive = enabled && !isLoading
    PhysicalButton(
        modifier = Modifier
            .size(40.dp)
            .shadow(
                elevation = 5.dp,
                shape = CircleShape,
                ambientColor = Color.Black.copy(alpha = 0.18f),
                spotColor = Color.Black.copy(alpha = 0.18f)
            )
            .alpha(if (enabled || isLoading) 1f else 0.42f),
        onClick = onClick,
        enabled = isInteractive,
        shape = CircleShape,
        backgroundColor = GalleryToolbarButton
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = GalleryToolbarContent,
                modifier = Modifier.size(17.dp),
                strokeWidth = 1.8.dp
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = GalleryToolbarContent,
                modifier = Modifier
                    .size(18.dp)
                    .autoRotate()
            )
        }
    }
}

@Composable
private fun GalleryGroupedActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    isLoading: Boolean = false
) {
    IconButton(
        onClick = onClick,
        enabled = enabled && !isLoading,
        modifier = Modifier.size(36.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = GalleryToolbarContent,
                modifier = Modifier.size(17.dp),
                strokeWidth = 1.8.dp
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = GalleryToolbarContent.copy(alpha = if (enabled) 1f else 0.38f),
                modifier = Modifier
                    .size(18.dp)
                    .autoRotate()
            )
        }
    }
}

@Composable
private fun GalleryMoreActionCard(
    action: GalleryMoreAction,
    modifier: Modifier = Modifier
) {
    val isInteractive = action.enabled && !action.isLoading
    Column(
        modifier = modifier.alpha(if (action.enabled || action.isLoading) 1f else 0.38f),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PhysicalButton(
            modifier = Modifier
                .size(44.dp)
                .shadow(
                    elevation = 4.dp,
                    shape = CircleShape,
                    ambientColor = Color.Black.copy(alpha = 0.15f),
                    spotColor = Color.Black.copy(alpha = 0.15f)
                ),
            onClick = action.onClick,
            enabled = isInteractive,
            shape = CircleShape,
            backgroundColor = GalleryToolbarButton
        ) {
            if (action.isLoading) {
                CircularProgressIndicator(
                    color = action.contentColor,
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 1.8.dp
                )
            } else if (action.icon != null) {
                Icon(
                    imageVector = action.icon,
                    contentDescription = action.text,
                    tint = action.contentColor,
                    modifier = Modifier.size(19.dp)
                )
            } else if (action.iconText != null) {
                Text(
                    text = action.iconText,
                    color = action.contentColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(modifier = Modifier.height(7.dp))
        Text(
            text = action.text,
            color = GalleryToolbarContent.copy(alpha = 0.86f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 14.sp,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp,
            modifier = Modifier.width(80.dp)
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 14.sp
        )
    }
}

private fun resolveCurrentMediaSize(context: Context, photo: MediaData): Long {
    if (photo.size > 0L) return photo.size

    val candidates = listOfNotNull(photo.uri, photo.sourceUri).distinctBy { it.toString() }
    candidates.forEach { uri ->
        val size = resolveUriSize(context, uri)
        if (size > 0L) return size
    }

    return photo.size
}

private fun resolveUriSize(context: Context, uri: Uri): Long {
    if (uri.scheme == null || uri.scheme == "file") {
        return uri.path?.let { File(it).takeIf(File::exists)?.length() } ?: 0L
    }

    if (uri.scheme == "content") {
        val queriedSize = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use 0L
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
            } ?: 0L
        }.getOrDefault(0L)
        if (queriedSize > 0L) return queriedSize

        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.statSize.takeIf { it > 0L } ?: 0L
            } ?: 0L
        }.getOrDefault(0L)
    }

    return 0L
}

private fun Context.findActivity(): Activity? {
    var currentContext = this
    while (currentContext is ContextWrapper) {
        if (currentContext is Activity) return currentContext
        currentContext = currentContext.baseContext
    }
    return null
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun rememberVideoPlayerView(
    player: ExoPlayer?,
    showController: Boolean = true
): PlayerView {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val playerView = remember(context, showController) {
        LayoutInflater.from(context).inflate(R.layout.view_motion_photo_player, null) as PlayerView
    }

    DisposableEffect(player, playerView, lifecycle, showController) {
        fun updatePlayback() {
            val isForeground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!isForeground && player?.playWhenReady == true) {
                PLog.d("GalleryVideoPlayback", "Pausing playback while the screen is not resumed.")
                player.pause()
            }
            playerView.keepScreenOn = isForeground && player?.isPlaying == true
        }

        playerView.useController = showController
        playerView.controllerAutoShow = false
        playerView.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
        playerView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                updatePlayback()
            }
        }
        val observer = LifecycleEventObserver { _, _ -> updatePlayback() }
        playerView.player = player
        player?.addListener(listener)
        lifecycle.addObserver(observer)
        updatePlayback()

        onDispose {
            lifecycle.removeObserver(observer)
            player?.removeListener(listener)
            playerView.keepScreenOn = false
            playerView.player = null
        }
    }

    return playerView
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoDetailPlayer(
    photo: MediaData,
    isActive: Boolean,
    viewModel: GalleryViewModel,
    previewHandle: VideoPreviewHandle,
    spatialZoomRatio: Float = 1f,
    onSpatialZoomChange: (Float) -> Unit = {},
    onSpatialCompanionAvailability: (Boolean) -> Unit = {},
    onSpatialTelephotoAvailability: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mediaUri = remember(photo.id, photo.uri, photo.sourceUri) {
        photo.sourceUri ?: photo.uri
    }
    val ultraWideFile = remember(photo.id, mediaUri) {
        SpatialVideoStore.ultraWideFile(context, mediaUri)
    }
    val telephotoFile = remember(photo.id, mediaUri) {
        SpatialVideoStore.telephotoFile(context, mediaUri)
    }
    var hasSpatialUltraWide by remember(photo.id, mediaUri) {
        mutableStateOf(ultraWideFile.isFile && ultraWideFile.length() > 0L)
    }
    var hasSpatialTelephoto by remember(photo.id, mediaUri) {
        mutableStateOf(telephotoFile.isFile && telephotoFile.length() > 0L)
    }
    var isWidePlayerReady by remember(photo.id, mediaUri) { mutableStateOf(false) }
    var isTelephotoPlayerReady by remember(photo.id, mediaUri) { mutableStateOf(false) }
    val companionCallback by rememberUpdatedState(onSpatialCompanionAvailability)
    val telephotoCallback by rememberUpdatedState(onSpatialTelephotoAvailability)
    // Only the lens currently visible in the gallery should decode continuously.
    // Playing both hidden companion streams wastes decoder/GPU resources and can
    // make the third lens stutter on devices with limited video-decoder capacity.
    val isUltraWideFeedRequested = spatialZoomRatio < 1f && hasSpatialUltraWide
    val isTelephotoFeedRequested = spatialZoomRatio >= 3.35f && hasSpatialTelephoto

    LaunchedEffect(photo.id, mediaUri, isActive) {
        if (!isActive) return@LaunchedEffect
        val restored = withContext(Dispatchers.IO) {
            SpatialVideoStore.restoreCompanionFiles(context, mediaUri)
        }
        hasSpatialUltraWide = restored.ultraWide?.isFile == true && restored.ultraWide.length() > 0L
        hasSpatialTelephoto = restored.telephoto?.isFile == true && restored.telephoto.length() > 0L
        companionCallback(hasSpatialUltraWide)
        telephotoCallback(hasSpatialTelephoto)
        PLog.i(
            "VideoDetailPlayer",
            "Special Video companion restore complete: 0.5x=$hasSpatialUltraWide, telephoto=$hasSpatialTelephoto"
        )
    }
    PLog.d("VideoDetailPlayer", "Initializing gallery video. photoId=${photo.id}, active=$isActive, spatialWide=$hasSpatialUltraWide, telephoto=$hasSpatialTelephoto")

    val contentRepository = remember {
        com.hinnka.mycamera.data.ContentRepository.getInstance(context)
    }
    var lutConfig by remember { mutableStateOf<com.hinnka.mycamera.lut.LutConfig?>(null) }
    var recipeParams by remember { mutableStateOf<com.hinnka.mycamera.model.ColorRecipeParams?>(null) }
    val refreshKey = viewModel.photoRefreshKeys[photo.id] ?: 0L

    LaunchedEffect(photo.id, refreshKey) {
        withContext(Dispatchers.IO) {
            val metadata = com.hinnka.mycamera.gallery.GalleryManager.loadMetadata(context, photo.id) ?: photo.metadata
            val lutId = metadata?.lutId
            val params = metadata?.colorRecipeParams
            val config = if (lutId != null) contentRepository.lutManager.loadLut(lutId) else null
            withContext(Dispatchers.Main) {
                lutConfig = config
                recipeParams = params
            }
        }
    }

    val videoLutEffect = remember(photo.id, mediaUri) { VideoLutEffect(lutConfig, recipeParams) }
    val wideVideoLutEffect = remember(photo.id, mediaUri) { VideoLutEffect(lutConfig, recipeParams) }
    val telephotoVideoLutEffect = remember(photo.id, mediaUri) { VideoLutEffect(lutConfig, recipeParams) }
    val videoOutputSize by videoLutEffect.outputSize.collectAsState()
    val wideVideoOutputSize by wideVideoLutEffect.outputSize.collectAsState()
    val telephotoVideoOutputSize by telephotoVideoLutEffect.outputSize.collectAsState()

    LaunchedEffect(lutConfig, recipeParams) {
        videoLutEffect.update(lutConfig, recipeParams)
        wideVideoLutEffect.update(lutConfig, recipeParams)
        telephotoVideoLutEffect.update(lutConfig, recipeParams)
    }

    val exoPlayer = remember(photo.id, mediaUri, isActive) {
        if (!isActive) return@remember null
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            setVideoEffects(listOf(videoLutEffect))
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    PLog.d("VideoDetailPlayer", "Primary player state changed: $state")
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    PLog.w("VideoDetailPlayer", "Primary ExoPlayer error.", error)
                }
            })
        }
    }

    // The secondary player is created once for this active page, but is prepared only when
    // the paired ultra-wide file exists. Its timeline is synchronized to the primary stream.
    val wideExoPlayer = remember(photo.id, mediaUri, isActive) {
        if (!isActive) return@remember null
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            volume = 0f
            setVideoEffects(listOf(wideVideoLutEffect))
            addListener(object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    PLog.w("VideoDetailPlayer", "Ultra-wide ExoPlayer error.", error)
                }
            })
        }
    }

    val telephotoExoPlayer = remember(photo.id, mediaUri, isActive) {
        if (!isActive) return@remember null
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            volume = 0f
            setVideoEffects(listOf(telephotoVideoLutEffect))
            addListener(object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    PLog.w("VideoDetailPlayer", "3.5x telephoto ExoPlayer error.", error)
                }
            })
        }
    }

    val playerView = rememberVideoPlayerView(exoPlayer, showController = true)
    val ultraWidePlayerView = rememberVideoPlayerView(wideExoPlayer, showController = false)
    val telephotoPlayerView = rememberVideoPlayerView(telephotoExoPlayer, showController = false)

    DisposableEffect(exoPlayer, wideExoPlayer, telephotoExoPlayer, previewHandle) {
        val players = listOfNotNull(exoPlayer, wideExoPlayer, telephotoExoPlayer)
        val lease = if (players.isNotEmpty()) previewHandle.attach(*players.toTypedArray()) else null
        onDispose {
            if (lease != null) previewHandle.release(lease)
        }
    }

    // Prepare the main track once. Lens changes below never replace its MediaItem.
    LaunchedEffect(exoPlayer, isActive, lifecycle) {
        val primary = exoPlayer ?: return@LaunchedEffect
        if (!isActive) return@LaunchedEffect
        delay(150)
        primary.setMediaItem(MediaItem.fromUri(mediaUri))
        primary.prepare()
        primary.playWhenReady = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }

    // If the companion becomes available later, start it at the current primary timestamp;
    // this does not interrupt or seek the main video.
    LaunchedEffect(wideExoPlayer, exoPlayer, hasSpatialUltraWide, isActive) {
        val primary = exoPlayer ?: return@LaunchedEffect
        val secondary = wideExoPlayer ?: return@LaunchedEffect
        if (!isActive || !hasSpatialUltraWide) return@LaunchedEffect

        runCatching {
            secondary.setMediaItem(MediaItem.fromUri(Uri.fromFile(ultraWideFile)))
            secondary.prepare()
            var attempts = 0
            while (secondary.playbackState != Player.STATE_READY &&
                secondary.playbackState != Player.STATE_ENDED &&
                attempts < 100
            ) {
                delay(100)
                attempts++
            }
            if (secondary.playbackState == Player.STATE_READY) {
                secondary.seekTo(primary.currentPosition.coerceAtLeast(0L))
                secondary.playWhenReady = false
                secondary.pause()
                isWidePlayerReady = true
                companionCallback(true)
                PLog.i("VideoDetailPlayer", "Ultra-wide companion prepared and held for lens selection.")
            } else {
                PLog.w("VideoDetailPlayer", "Ultra-wide companion did not become ready in time.")
            }
        }.onFailure { error ->
            PLog.w("VideoDetailPlayer", "Unable to prepare ultra-wide companion.", error)
        }
    }

    LaunchedEffect(telephotoExoPlayer, exoPlayer, hasSpatialTelephoto, isActive) {
        val primary = exoPlayer ?: return@LaunchedEffect
        val telephoto = telephotoExoPlayer ?: return@LaunchedEffect
        if (!isActive || !hasSpatialTelephoto) return@LaunchedEffect
        runCatching {
            telephoto.setMediaItem(MediaItem.fromUri(Uri.fromFile(telephotoFile)))
            telephoto.prepare()
            var attempts = 0
            while (telephoto.playbackState != Player.STATE_READY &&
                telephoto.playbackState != Player.STATE_ENDED && attempts < 100
            ) {
                delay(100)
                attempts++
            }
            if (telephoto.playbackState == Player.STATE_READY) {
                telephoto.seekTo(primary.currentPosition.coerceAtLeast(0L))
                telephoto.playWhenReady = false
                telephoto.pause()
                isTelephotoPlayerReady = true
                telephotoCallback(true)
                PLog.i("VideoDetailPlayer", "3.5x telephoto companion prepared and held for lens selection.")
            } else {
                PLog.w("VideoDetailPlayer", "3.5x telephoto companion did not become ready in time.")
            }
        }.onFailure { error ->
            PLog.w("VideoDetailPlayer", "Unable to prepare 3.5x telephoto companion.", error)
        }
    }

    // On every lens handoff, stop the hidden companion and align only the selected
    // companion to the main player's current position. This also works while paused.
    LaunchedEffect(
        exoPlayer,
        wideExoPlayer,
        telephotoExoPlayer,
        isUltraWideFeedRequested,
        isTelephotoFeedRequested,
        isWidePlayerReady,
        isTelephotoPlayerReady,
        isActive
    ) {
        val primary = exoPlayer ?: return@LaunchedEffect
        if (!isActive) return@LaunchedEffect

        val wide = wideExoPlayer
        val telephoto = telephotoExoPlayer
        if (!isUltraWideFeedRequested) wide?.pause()
        if (!isTelephotoFeedRequested) telephoto?.pause()

        val selected = when {
            isUltraWideFeedRequested && isWidePlayerReady -> wide
            isTelephotoFeedRequested && isTelephotoPlayerReady -> telephoto
            else -> null
        } ?: return@LaunchedEffect

        selected.seekTo(primary.currentPosition.coerceAtLeast(0L))
        val shouldPlay = primary.isPlaying && primary.playbackState != Player.STATE_ENDED
        selected.playWhenReady = shouldPlay
        if (shouldPlay) selected.play() else selected.pause()
        PLog.d(
            "VideoDetailPlayer",
            "Gallery lens handoff: feed=${if (isUltraWideFeedRequested) "0.5x" else "3.5x"}, " +
                "position=${primary.currentPosition}ms, playing=$shouldPlay"
        )
    }

    // Mirror main playback events to only the currently visible companion. The old
    // 300ms loop sought both companions repeatedly, causing extra decoder work/judder.
    DisposableEffect(
        exoPlayer,
        wideExoPlayer,
        telephotoExoPlayer,
        isUltraWideFeedRequested,
        isTelephotoFeedRequested,
        isWidePlayerReady,
        isTelephotoPlayerReady
    ) {
        val primary = exoPlayer ?: return@DisposableEffect onDispose { }
        val selected = when {
            isUltraWideFeedRequested && isWidePlayerReady -> wideExoPlayer
            isTelephotoFeedRequested && isTelephotoPlayerReady -> telephotoExoPlayer
            else -> null
        }
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady && primary.playbackState == Player.STATE_ENDED) {
                    // Explicitly restart the main and companion timelines after EOS.
                    primary.seekTo(0L)
                    wideExoPlayer?.seekTo(0L)
                    telephotoExoPlayer?.seekTo(0L)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                selected?.let { companion ->
                    if (isPlaying) companion.play() else companion.pause()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    primary.pause()
                    wideExoPlayer?.pause()
                    telephotoExoPlayer?.pause()
                    // Keep the main clip at EOS, but reset the visible companion so it
                    // cannot remain stuck on its last frame when playback is restarted.
                    selected?.seekTo(0L)
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                selected?.seekTo(primary.currentPosition.coerceAtLeast(0L))
            }
        }
        primary.addListener(listener)
        onDispose { primary.removeListener(listener) }
    }

    // Retain a low-frequency drift guard for long clips, but only for the selected
    // companion. A 500ms threshold avoids the repeated seeks that were stuttering video.
    LaunchedEffect(
        exoPlayer,
        isUltraWideFeedRequested,
        isTelephotoFeedRequested,
        isWidePlayerReady,
        isTelephotoPlayerReady,
        isActive
    ) {
        val primary = exoPlayer ?: return@LaunchedEffect
        if (!isActive) return@LaunchedEffect
        val companion = when {
            isUltraWideFeedRequested && isWidePlayerReady -> wideExoPlayer
            isTelephotoFeedRequested && isTelephotoPlayerReady -> telephotoExoPlayer
            else -> null
        } ?: return@LaunchedEffect

        while (true) {
            if (primary.isPlaying && companion.isPlaying &&
                primary.playbackState == Player.STATE_READY &&
                companion.playbackState == Player.STATE_READY
            ) {
                val driftMs = primary.currentPosition - companion.currentPosition
                if (abs(driftMs) > 500L) {
                    companion.seekTo(primary.currentPosition.coerceAtLeast(0L))
                }
            }
            delay(1_200)
        }
    }

    DisposableEffect(exoPlayer, wideExoPlayer, telephotoExoPlayer, lifecycle, isActive) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    exoPlayer?.pause()
                    wideExoPlayer?.pause()
                    telephotoExoPlayer?.pause()
                }
                Lifecycle.Event.ON_RESUME -> if (isActive) {
                    exoPlayer?.play()
                    // Only onIsPlayingChanged resumes the currently selected companion.
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val animatedZoomRatio by animateFloatAsState(
        targetValue = spatialZoomRatio.coerceIn(0.5f, 3.5f),
        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
        label = "gallerySpatialZoom"
    )

    if (exoPlayer != null) {
        val showWideFeed = hasSpatialUltraWide && isWidePlayerReady && wideExoPlayer != null
        val showTelephotoFeed = hasSpatialTelephoto && isTelephotoPlayerReady && telephotoExoPlayer != null
        val wideAlpha = if (showWideFeed) {
            1f - ((animatedZoomRatio - 0.94f) / 0.06f).coerceIn(0f, 1f)
        } else 0f
        // Main footage carries the continuous 1x-3.5x digital ramp. Switch to the higher
        // detail 3.5x sensor crop only in the final handoff band to avoid scaling a pre-cropped
        // 3.5x recording down to a false 1x field of view.
        val telephotoAlpha = if (showTelephotoFeed) {
            ((animatedZoomRatio - 3.35f) / 0.15f).coerceIn(0f, 1f)
        } else 0f
        val mainAlpha = (1f - wideAlpha) * (1f - telephotoAlpha)
        val wideScale = (animatedZoomRatio / 0.5f).coerceIn(1f, 2f)
        val mainScale = animatedZoomRatio.coerceIn(1f, 3.5f)
        // The 3.5x companion already contains the 3.5x sensor crop, so it fills the output
        // frame at handoff; scaling it smaller would undo the captured field of view.
        val telephotoScale = 1f

        Box(modifier = modifier.autoRotate(matchParentSize = true)) {
            if (showWideFeed) {
                AndroidView(
                    factory = { ultraWidePlayerView },
                    update = { view ->
                        view.player = wideExoPlayer
                        view.useController = false
                        view.controllerAutoShow = false
                        view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        view.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        view.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        view.isClickable = false
                        view.isVisible = true
                        view.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)?.let { frame ->
                            frame.alpha = wideAlpha
                            frame.scaleX = wideScale
                            frame.scaleY = wideScale
                            frame.pivotX = view.width / 2f
                            frame.pivotY = view.height / 2f
                            wideVideoOutputSize?.let { size -> frame.setAspectRatio(size.width.toFloat() / size.height) }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (showTelephotoFeed) {
                AndroidView(
                    factory = { telephotoPlayerView },
                    update = { view ->
                        view.player = telephotoExoPlayer
                        view.useController = false
                        view.controllerAutoShow = false
                        view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        view.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        view.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        view.isClickable = false
                        view.isVisible = true
                        view.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)?.let { frame ->
                            frame.alpha = telephotoAlpha
                            frame.scaleX = telephotoScale
                            frame.scaleY = telephotoScale
                            frame.pivotX = view.width / 2f
                            frame.pivotY = view.height / 2f
                            telephotoVideoOutputSize?.let { size ->
                                frame.setAspectRatio(size.width.toFloat() / size.height)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Main player stays on top so native playback controls remain usable.
            AndroidView(
                factory = { playerView },
                update = { view ->
                    view.player = exoPlayer
                    view.useController = true
                    view.controllerAutoShow = false
                    view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    view.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    view.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    view.isVisible = true
                    view.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)?.let { frame ->
                        frame.alpha = mainAlpha
                        frame.scaleX = mainScale
                        frame.scaleY = mainScale
                        frame.pivotX = view.width / 2f
                        frame.pivotY = view.height / 2f
                        videoOutputSize?.let { size -> frame.setAspectRatio(size.width.toFloat() / size.height) }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (showWideFeed || showTelephotoFeed) {
                SpatialVideoZoomControl(
                    zoomRatio = spatialZoomRatio,
                    onZoomChange = onSpatialZoomChange,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 76.dp)
                )
            }
        }
    } else {
        // Show video thumbnail with a play icon when player is not active
        Box(
            modifier = modifier.autoRotate(matchParentSize = true),
            contentAlignment = Alignment.Center
        ) {
            val transformation = remember(photo) {
                viewModel.getPhotoTransformation(photo)
            }
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(photo.thumbnailUri)
                    .crossfade(true)
                    .apply {
                        if (transformation != null) {
                            transformations(transformation)
                        }
                    }
                    .build(),
                contentDescription = photo.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(64.dp)
            )
        }
    }
}

/**
 * 可缩放的图片组件
 * 使用 Telephoto 库支持大尺寸图片查看
 */
@Composable
private fun ZoomableImage(
    photo: MediaData,
    colorSpace: MutableState<ColorSpace?>,
    showOrigin: Boolean,
    isActive: Boolean,
    isScrollInProgress: Boolean,
    viewModel: GalleryViewModel,
    showRawBadge: Boolean,
    onZoomChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var isLoading by remember { mutableStateOf(true) }
    val maxZoom = max(photo.width, photo.height) / 500f
    val zoomableState = rememberZoomableImageState(
        zoomableState = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = maxZoom))
    )

    LaunchedEffect(zoomableState.zoomableState.zoomFraction) {
        onZoomChange((zoomableState.zoomableState.zoomFraction ?: 0f) > 0.01f)
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val displayPhoto = if (viewModel.selectedTab == GalleryTab.SYSTEM) {
            photo.relatedPhoto ?: photo
        } else {
            photo
        }
        // 使用 hashCode() 代替 toJson() 序列化，避免 composition 时做 JSON 序列化
        val metadataHash = remember(displayPhoto.metadata, photo.metadata) {
            displayPhoto.metadata?.hashCode() ?: photo.metadata?.hashCode() ?: 0
        }

        var bitmap by remember { mutableStateOf<Bitmap?>(null) }
        var hdrBitmap by remember { mutableStateOf<Bitmap?>(null) }
        var showHdr by remember { mutableStateOf(false) }
        val hdrAlpha by animateFloatAsState(
            targetValue = if (showHdr) 1f else 0f,
            animationSpec = tween(durationMillis = 750, easing = LinearOutSlowInEasing),
            label = "hdrFadeIn"
        )
        val refreshKey = viewModel.photoRefreshKeys[displayPhoto.id] ?: 0L
        val isSettledActive = isActive && !isScrollInProgress
        val previewMaxEdge = if (isSettledActive) 4096 else 1024

        LaunchedEffect(displayPhoto.id, metadataHash, showOrigin, refreshKey, isSettledActive) {
            isLoading = bitmap == null
            bitmap = viewModel.getPreviewBitmap(
                displayPhoto,
                showOrigin = showOrigin,
                ignoreDenoise = !isSettledActive,
                maxEdge = previewMaxEdge
            )
            if (bitmap == null && viewModel.awaitPreparedPhotoReady(displayPhoto)) {
                bitmap = viewModel.getPreviewBitmap(
                    displayPhoto,
                    showOrigin = showOrigin,
                    ignoreDenoise = !isSettledActive,
                    maxEdge = previewMaxEdge
                )
            }
            colorSpace.value = bitmap?.colorSpace
            isLoading = bitmap == null

            if (displayPhoto.metadata?.manualHdrEffectEnabled == true && isSettledActive) {
                hdrBitmap = viewModel.getDetailBitmap(displayPhoto, maxEdge = previewMaxEdge)
                hdrBitmap?.let {
                    colorSpace.value = it.colorSpace
                }
            } else {
                hdrBitmap = null
            }
        }

        LaunchedEffect(hdrBitmap, showOrigin, isActive) {
            delay(300)
            showHdr = hdrBitmap != null && !showOrigin && isActive
        }

        if (bitmap != null) {
            val imageModel = remember(displayPhoto.id, metadataHash, bitmap) {
                ImageRequest.Builder(context)
                    .data(bitmap)
                    .crossfade(false) // 禁用交叉淡入淡出，避免滑动时同时渲染两张大图
                    .build()
            }

            ZoomableAsyncImage(
                model = imageModel,
                contentDescription = displayPhoto.displayName,
                contentScale = ContentScale.Fit,
                state = zoomableState,
                onDoubleClick = DoubleClickToZoomListener.cycle(maxZoomFactor = 3f),
                modifier = Modifier.fillMaxSize().autoRotate(matchParentSize = true)
            )
        }

        if (showHdr && hdrBitmap != null) {
            val imageModel = remember(displayPhoto.id, metadataHash, hdrBitmap) {
                ImageRequest.Builder(context)
                    .data(hdrBitmap)
                    .crossfade(false) // 禁用交叉淡入淡出，避免滑动时同时渲染两张大图
                    .build()
            }

            ZoomableAsyncImage(
                model = imageModel,
                contentDescription = displayPhoto.displayName,
                contentScale = ContentScale.Fit,
                state = zoomableState,
                modifier = Modifier.fillMaxSize().alpha(hdrAlpha).autoRotate(matchParentSize = true)
            )
        }

        if (showRawBadge && bitmap != null) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color.Black.copy(alpha = 0.58f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(R.string.gallery_raw_badge),
                    color = AccentColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        if (isLoading) {
            CircularProgressIndicator(
                color = AccentColor,
                modifier = Modifier.size(48.dp)
            )
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun MotionPhotoPlayer(
    photo: MediaData,
    isPlaying: Boolean,
    viewModel: GalleryViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val videoFile = remember(photo.id) {
        viewModel.getMotionPhotoVideo(photo)
    }

    if (!photo.isMotionPhoto || videoFile == null || !videoFile.exists()) {
        if (photo.isMotionPhoto) {
            PLog.w("MotionPhotoPlayer", "Motion Photo video file missing: ${photo.id}")
        }
        return
    }

    val contentRepository = remember {
        com.hinnka.mycamera.data.ContentRepository.getInstance(context)
    }

    var lutConfig by remember { mutableStateOf<com.hinnka.mycamera.lut.LutConfig?>(null) }
    var recipeParams by remember { mutableStateOf<com.hinnka.mycamera.model.ColorRecipeParams?>(null) }
    var effectMetadataLoaded by remember(photo.id) { mutableStateOf(false) }
    val refreshKey = viewModel.photoRefreshKeys[photo.id] ?: 0L

    LaunchedEffect(photo.id, refreshKey) {
        effectMetadataLoaded = false
        withContext(Dispatchers.IO) {
            PLog.d("MotionPhotoPlayer", "Loading video metadata from DB.")
            val metadata = com.hinnka.mycamera.gallery.GalleryManager.loadMetadata(context, photo.id) ?: photo.metadata
            val applyEffects = metadata?.applyEffectsToVideo == true
            val lutId = if (applyEffects) metadata.lutId else null
            val params = if (applyEffects) metadata.colorRecipeParams else null
            PLog.d("MotionPhotoPlayer", "Metadata loaded. applyEffects: $applyEffects, lutId: $lutId, recipeEnabled: ${params != null}")

            val config = if (lutId != null) {
                contentRepository.lutManager.loadLut(lutId)
            } else {
                null
            }

            withContext(Dispatchers.Main) {
                lutConfig = config
                recipeParams = params
                effectMetadataLoaded = true
            }
        }
    }

    // Maintain the video LUT effect
    val videoLutEffect = remember {
        PLog.d("MotionPhotoPlayer", "Instantiating new VideoLutEffect.")
        VideoLutEffect(lutConfig, recipeParams)
    }

    // Update effect parameters dynamically on the GL pipeline without reconstruction
    LaunchedEffect(lutConfig, recipeParams) {
        PLog.d("MotionPhotoPlayer", "Updating VideoLutEffect params. lut: ${lutConfig?.title}, recipe: ${recipeParams != null}")
        videoLutEffect.update(lutConfig, recipeParams)
    }

    if (!effectMetadataLoaded) return

    val hasVideoEffects = lutConfig != null || recipeParams != null
    val shouldApplyVideoEffects = hasVideoEffects

    var isReadyToShow by remember(photo.id) { mutableStateOf(false) }

    val exoPlayer = remember(photo.id, videoFile.absolutePath, shouldApplyVideoEffects) {
        ExoPlayer.Builder(context).build().apply {
            PLog.d(
                "MotionPhotoPlayer",
                "Creating ExoPlayer for ${photo.id}, video=${videoFile.absolutePath}, size=${videoFile.length()}, effects=$shouldApplyVideoEffects"
            )
            repeatMode = Player.REPEAT_MODE_ONE
            if (shouldApplyVideoEffects) {
                setVideoEffects(listOf(videoLutEffect))
            }
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    isReadyToShow = true
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                }

                override fun onPlayerError(error: PlaybackException) {
                }
            })
            prepare()
        }
    }

    val playerView = rememberVideoPlayerView(exoPlayer)

    LaunchedEffect(exoPlayer, isPlaying, shouldApplyVideoEffects, lifecycle) {
        if (isPlaying) {
            isReadyToShow = false
            delay(150)
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
            if (exoPlayer.mediaItemCount == 0) {
                PLog.d("MotionPhotoPlayer", "Preparing Motion Photo player after PlayerView attach: ${photo.id}")
                exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(videoFile)))
                exoPlayer.prepare()
            }
            exoPlayer.seekTo(0)
            exoPlayer.playWhenReady = true
            exoPlayer.play()
        } else {
            isReadyToShow = false
            exoPlayer.playWhenReady = false
            exoPlayer.pause()
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = {
            playerView
        },
        update = {
            it.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            it.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
            it.isVisible = true
            it.alpha = if (isPlaying && isReadyToShow) 1f else 0f
        },
        modifier = modifier.autoRotate(matchParentSize = true)
    )
}

@Composable
private fun SpatialVideoZoomControl(
    zoomRatio: Float,
    onZoomChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentZoom by rememberUpdatedState(zoomRatio.coerceIn(0.5f, 3.5f))
    val zoomChange by rememberUpdatedState(onZoomChange)
    var isDragging by remember { mutableStateOf(false) }
    var showRuler by remember { mutableStateOf(false) }
    var dragZoom by remember { mutableFloatStateOf(zoomRatio.coerceIn(0.5f, 3.5f)) }
    var lastInteractionTime by remember { mutableLongStateOf(0L) }

    val displayedZoom by animateFloatAsState(
        targetValue = if (isDragging) dragZoom else zoomRatio.coerceIn(0.5f, 3.5f),
        animationSpec = tween(durationMillis = 130, easing = FastOutSlowInEasing),
        label = "gallerySpatialZoomControl"
    )

    LaunchedEffect(showRuler, lastInteractionTime, isDragging) {
        if (showRuler && !isDragging) {
            delay(2000)
            if (!isDragging && System.currentTimeMillis() - lastInteractionTime >= 1900L) showRuler = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(33.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragZoom = currentZoom
                        isDragging = true
                        showRuler = true
                        lastInteractionTime = System.currentTimeMillis()
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        dragZoom = (dragZoom * exp(-dragAmount.toDouble() * 0.0027).toFloat()).coerceIn(0.5f, 3.5f)
                        lastInteractionTime = System.currentTimeMillis()
                        zoomChange(dragZoom)
                    },
                    onDragEnd = {
                        isDragging = false
                        lastInteractionTime = System.currentTimeMillis()
                    },
                    onDragCancel = {
                        isDragging = false
                        lastInteractionTime = System.currentTimeMillis()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (isDragging || showRuler) {
            ZoomContinuousRuler(
                zoomRatio = displayedZoom,
                minZoom = 0.5f,
                maxZoom = 3.5f,
                mainCamera = null,
                displayMode = ZoomDisplayMode.ZOOM_RATIO,
                onClose = {
                    showRuler = false
                    isDragging = false
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Row(
                modifier = Modifier.wrapContentWidth().fillMaxHeight(),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(0.5f, 1f, 2f, 3.5f).forEach { preset ->
                    val selected = abs(zoomRatio - preset) <= 0.045f
                    val label = when (preset) {
                        0.5f -> "0.5"
                        3.5f -> "3.5"
                        else -> preset.toInt().toString()
                    }
                    Box(
                        modifier = Modifier
                            .width(43.dp)
                            .height(33.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { showRuler = false; zoomChange(preset) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) {
                            Box(
                                modifier = Modifier.size(29.dp).clip(CircleShape).background(Color(0xFF484651))
                            )
                        }
                        Text(
                            text = if (selected) "${label}x" else label,
                            style = androidx.compose.ui.text.TextStyle(
                                fontSize = if (selected) 14.sp else 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            ),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}
