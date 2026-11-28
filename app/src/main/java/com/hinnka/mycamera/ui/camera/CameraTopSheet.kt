package com.hinnka.mycamera.ui.camera

import android.media.AudioDeviceInfo
import android.os.Build
import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.hinnka.mycamera.R
import com.hinnka.mycamera.camera.AspectRatio
import com.hinnka.mycamera.camera.MeteringMode
import com.hinnka.mycamera.data.OppoPhotoSettings
import com.hinnka.mycamera.raw.DcpInfo
import com.hinnka.mycamera.raw.HncsFilmCurveMode
import com.hinnka.mycamera.raw.RawRenderingEngine
import com.hinnka.mycamera.raw.RawToneMappingParameters
import com.hinnka.mycamera.raw.SpectralFilmSelection
import com.hinnka.mycamera.ui.components.AppModalBottomSheet
import com.hinnka.mycamera.ui.components.RawDcpLensOption
import com.hinnka.mycamera.ui.components.RawRenderingEngineSettingsPanel
import com.hinnka.mycamera.ui.components.OppoCameraGlassSurface
import com.hinnka.mycamera.video.*
import com.hinnka.mycamera.video.VideoCodec
import com.hinnka.mycamera.ui.icons.AppIcons
import com.hinnka.mycamera.ui.theme.AccentColor
import com.hinnka.mycamera.ui.theme.OnAccentColor

private enum class VideoSettingPanel {
    ASPECT_RATIO,
    LOG_PROFILE,
    BITRATE,
    CODEC,
    MICROPHONE
}

private val CameraTopSheetContentTopPadding = 32.dp

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CameraTopSheet(
    visible: Boolean,
    glassTransparency: Float,
    glassBlurStrength: Float,
    onGlassBoundsChanged: (Rect?) -> Unit,
    captureMode: CaptureMode,
    aspectRatio: AspectRatio,
    topSheetAspectRatios: List<AspectRatio>,
    onAspectRatioChange: (AspectRatio) -> Unit,
    videoAspectRatio: VideoAspectRatio,
    onVideoAspectRatioChange: (VideoAspectRatio) -> Unit,
    dualVideoAspectRatio: DualVideoAspectRatio?,
    onDualVideoAspectToggle: () -> Unit,
    onDualVideoAspectRatioChange: (DualVideoAspectRatio) -> Unit,
    videoLogProfile: VideoLogProfile,
    availableVideoLogProfiles: List<VideoLogProfile>,
    onVideoLogProfileChange: (VideoLogProfile) -> Unit,
    videoBitrate: VideoBitratePreset,
    onVideoBitrateChange: (VideoBitratePreset) -> Unit,
    useLivePhoto: Boolean,
    onLivePhotoToggle: () -> Unit,
    timerSeconds: Int,
    onTimerToggle: () -> Unit,
    onPhotoFilterClick: () -> Unit,
    onPhotoDepthClick: () -> Unit,
    videoTorchEnabled: Boolean,
    onVideoTorchToggle: () -> Unit,
    videoHdrEnabled: Boolean,
    onVideoHdrToggle: () -> Unit,
    videoStabilizationMode: VideoStabilizationMode,
    videoActionModeAvailable: Boolean,
    onVideoStabilizationModeChange: (VideoStabilizationMode) -> Unit,
    onVideoDualCaptureClick: () -> Unit,
    spatialVideoEnabled: Boolean,
    spatialVideoSupported: Boolean,
    onVideoSpatialVideoClick: () -> Unit,
    onVideoEditClick: () -> Unit,
    videoCodec: VideoCodec,
    availableVideoCodecs: List<VideoCodec>,
    apvQuality: ApvQuality,
    onApvQualityChange: (ApvQuality) -> Unit,
    onVideoCodecChange: (VideoCodec) -> Unit,
    videoAudioInputId: String,
    videoAudioInputOptions: List<VideoAudioInputOption>,
    onVideoAudioInputChange: (String) -> Unit,
    useRaw: Boolean,
    isRawSupported: Boolean,
    rawDcpId: String?,
    rawDcpIdsByLens: Map<String, String?> = emptyMap(),
    rawDcpLensOptions: List<RawDcpLensOption> = emptyList(),
    availableDcps: List<DcpInfo>,
    rawHncsFilmCurveMode: HncsFilmCurveMode,
    rawRenderingEngine: RawRenderingEngine,
    rawToneMappingParameters: RawToneMappingParameters,
    rawSpectralFilmSelection: SpectralFilmSelection?,
    rawSpectralFilmPrint: String?,
    ultraHdrEnabled: Boolean,
    onUltraHdrToggle: (Boolean) -> Unit,
    photoPreviewStabilizationEnabled: Boolean,
    photoPreviewStabilizationAvailable: Boolean,
    onPhotoPreviewStabilizationChange: (Boolean) -> Unit,
    onRawDcpChange: (String?) -> Unit,
    onRawDcpIdsByLensChange: ((Map<String, String?>) -> Unit)? = null,
    onRawHncsFilmCurveModeChange: (HncsFilmCurveMode) -> Unit,
    onImportRawDcp: () -> Unit,
    onDeleteRawDcp: (DcpInfo) -> Unit,
    onRawColorEngineChange: (RawRenderingEngine) -> Unit,
    onRawToneMappingParametersChange: (RawToneMappingParameters) -> Unit,
    onRawSpectralFilmSelectionChange: (SpectralFilmSelection?) -> Unit,
    onRawSpectralFilmPrintChange: (String?) -> Unit,
    meteringMode: MeteringMode,
    onMeteringModeChange: (MeteringMode) -> Unit,
    onFilterManageClick: () -> Unit,
    onFrameManageClick: () -> Unit,
    onPresetManageClick: () -> Unit,
    onToolboxClick: () -> Unit,
    onMoreSettingsClick: () -> Unit,
    useJpgMax: Boolean,
    onJpgMaxToggle: (Boolean) -> Unit,
    useMultipleExposure: Boolean,
    onMultipleExposureToggle: (Boolean) -> Unit,
    oppoPhotoSettings: OppoPhotoSettings,
    onOppoPhotoSettingsChange: (OppoPhotoSettings) -> Unit,
    refocusAvailable: Boolean,
    contentTopPadding: Dp = CameraTopSheetContentTopPadding,
    performanceMode: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var expandedVideoPanel by rememberSaveable { mutableStateOf<VideoSettingPanel?>(null) }
    var showRenderingEngineSheet by rememberSaveable { mutableStateOf(false) }
    var showOppoPipelineSheet by rememberSaveable { mutableStateOf(false) }
    var showContentManagementOptions by rememberSaveable { mutableStateOf(false) }

    fun handleContentManagementAction(action: () -> Unit) {
        showContentManagementOptions = false
        action()
    }

    LaunchedEffect(visible, captureMode) {
        showContentManagementOptions = false
        expandedVideoPanel = null
    }

    LaunchedEffect(visible) {
        if (!visible) onGlassBoundsChanged(null)
    }

    LaunchedEffect(useRaw) {
        if (!useRaw) {
            showRenderingEngineSheet = false
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = if (performanceMode) EnterTransition.None else slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = if (performanceMode) ExitTransition.None else slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier
    ) {
        OppoCameraGlassSurface(
            modifier = Modifier.fillMaxWidth(),
            transparency = glassTransparency,
            blurStrength = glassBlurStrength,
            onBoundsChanged = onGlassBoundsChanged,
            performanceMode = performanceMode,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (captureMode == CaptureMode.VIDEO) 24.dp else 26.dp)
                    .autoRotate()
                    .padding(horizontal = if (captureMode == CaptureMode.VIDEO) 20.dp else 24.dp)
                    .padding(bottom = 10.dp)
            ) {
            Column {
                if (captureMode == CaptureMode.PHOTO) {
                    val isProfessionalMode = useRaw && isRawSupported

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        AspectRatio.sanitizeTopSheetRatios(topSheetAspectRatios).forEach { ratio ->
                            val isSelected = aspectRatio == ratio
                            val shape = RoundedCornerShape(14.dp)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .then(
                                        if (isSelected) {
                                            Modifier.clip(shape).background(AccentColor)
                                        } else {
                                            Modifier.menuCardSurface(
                                                shape = shape,
                                                background = VideoMenuCard,
                                                elevation = 3.dp
                                            )
                                        }
                                    )
                                    .clickable { onAspectRatioChange(ratio) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = ratio.getDisplayName(),
                                    color = if (isSelected) OnAccentColor else Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(5.dp))

                    QuickSettingGrid(
                        columns = 4,
                        itemHeight = 52.dp,
                        horizontalSpacing = 5.dp,
                        verticalSpacing = 4.dp
                    ) { itemModifier ->
                        if (isProfessionalMode) {
                            PhotoMenuQuickTile(
                                title = stringResource(R.string.settings_raw_color_engine),
                                leadingIcon = { tint ->
                                    Icon(AppIcons.Layers, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
                                },
                                selected = true,
                                onClick = { showRenderingEngineSheet = true },
                                modifier = itemModifier
                            )
                        } else {
                            PhotoMenuQuickTile(
                                label = stringResource(R.string.settings_use_jpg_max),
                                leadingIcon = { tint ->
                                    Icon(AppIcons.Layers, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
                                },
                                selected = useJpgMax,
                                checked = useJpgMax,
                                showStatusDot = true,
                                onClick = { onJpgMaxToggle(!useJpgMax) },
                                modifier = itemModifier
                            )
                            PhotoMenuQuickTile(
                                label = stringResource(R.string.settings_use_multiple_exposure),
                                leadingIcon = { tint ->
                                    Icon(AppIcons.Layers, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
                                },
                                selected = useMultipleExposure,
                                checked = useMultipleExposure,
                                showStatusDot = true,
                                onClick = { onMultipleExposureToggle(!useMultipleExposure) },
                                modifier = itemModifier
                            )
                        }

                        PhotoMenuQuickTile(
                            title = stringResource(R.string.settings_ultra_hdr_gain_map),
                            accentTitle = false,
                            leadingIcon = { tint -> PhotoMenuHdrGlyph(tint) },
                            selected = ultraHdrEnabled,
                            checked = ultraHdrEnabled,
                            showStatusDot = true,
                            onClick = { onUltraHdrToggle(!ultraHdrEnabled) },
                            modifier = itemModifier
                        )
                        PhotoMenuQuickTile(
                            label = stringResource(R.string.camera_preview_stabilization),
                            leadingIcon = { tint ->
                                Icon(AppIcons.Stabilization, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
                            },
                            selected = photoPreviewStabilizationEnabled,
                            checked = photoPreviewStabilizationEnabled,
                            enabled = photoPreviewStabilizationAvailable,
                            showStatusDot = true,
                            onClick = { onPhotoPreviewStabilizationChange(!photoPreviewStabilizationEnabled) },
                            modifier = itemModifier
                        )
                        PhotoMenuQuickTile(
                            title = stringResource(R.string.metering_mode),
                            subtitle = photoMeteringModeLabel(meteringMode),
                            leadingIcon = { tint -> PhotoMenuApertureGlyph(tint) },
                            accentTitle = true,
                            onClick = {
                                val next = when (meteringMode) {
                                    MeteringMode.SPOT -> MeteringMode.SYSTEM_DEFAULT
                                    MeteringMode.SYSTEM_DEFAULT -> MeteringMode.CENTER_WEIGHTED
                                    MeteringMode.CENTER_WEIGHTED -> MeteringMode.AVERAGE
                                    MeteringMode.AVERAGE -> MeteringMode.HIGHLIGHT_PRIORITY
                                    MeteringMode.HIGHLIGHT_PRIORITY -> MeteringMode.SPOT
                                }
                                onMeteringModeChange(next)
                            },
                            modifier = itemModifier
                        )
                        PhotoMenuQuickTile(
                            title = stringResource(R.string.toolbox_title),
                            accentTitle = false,
                            leadingIcon = { tint ->
                                Icon(AppIcons.Palette, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
                            },
                            onClick = onToolboxClick,
                            modifier = itemModifier
                        )
                        PhotoMenuContentManagementTile(
                            onClick = { showContentManagementOptions = !showContentManagementOptions },
                            modifier = itemModifier
                        )
                        PhotoMenuQuickTile(
                            title = "LIVE",
                            subtitle = "PHOTO",
                            accentTitle = false,
                            leadingIcon = { tint -> PhotoMenuLivePhotoGlyph(tint) },
                            selected = useLivePhoto,
                            checked = useLivePhoto,
                            showStatusDot = true,
                            onClick = onLivePhotoToggle,
                            modifier = itemModifier
                        )
                        PhotoMenuQuickTile(
                            title = "TIMER",
                            subtitle = if (timerSeconds > 0) "${timerSeconds}s" else "Off",
                            accentTitle = true,
                            leadingIcon = { tint ->
                                Icon(AppIcons.Timer, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                            },
                            onClick = onTimerToggle,
                            modifier = itemModifier
                        )
                        PhotoMenuQuickTile(
                            title = "REFOCUS",
                            accentTitle = false,
                            leadingIcon = { tint -> PhotoMenuRefocusGlyph(tint) },
                            selected = oppoPhotoSettings.refocusEnabled,
                            checked = oppoPhotoSettings.refocusEnabled,
                            enabled = refocusAvailable && !isProfessionalMode,
                            showStatusDot = true,
                            onClick = {
                                val enabled = !oppoPhotoSettings.refocusEnabled
                                onOppoPhotoSettingsChange(
                                    // Refocus takes precedence for this shutter only. Keep the user's
                                    // Auto HDR preference intact so it resumes when Refocus is turned off.
                                    oppoPhotoSettings.copy(refocusEnabled = enabled)
                                )
                            },
                            modifier = itemModifier
                        )
                        PhotoMenuQuickTile(
                            title = "BURST",
                            accentTitle = false,
                            leadingIcon = { tint ->
                                Icon(AppIcons.BurstMode, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                            },
                            selected = oppoPhotoSettings.fastShutterEnabled,
                            checked = oppoPhotoSettings.fastShutterEnabled,
                            enabled = !isProfessionalMode,
                            showStatusDot = true,
                            onClick = {
                                onOppoPhotoSettingsChange(
                                    oppoPhotoSettings.copy(fastShutterEnabled = !oppoPhotoSettings.fastShutterEnabled)
                                )
                            },
                            modifier = itemModifier
                        )
                        PhotoMenuQuickTile(
                            title = "STYLES",
                            accentTitle = false,
                            leadingIcon = { tint ->
                                Icon(AppIcons.AutoAwesome, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                            },
                            selected = isProfessionalMode || oppoPhotoSettings.customPipelineEnabled,
                            trailingIcon = AppIcons.ChevronRight,
                            onClick = {
                                if (isProfessionalMode) {
                                    showRenderingEngineSheet = true
                                } else {
                                    showOppoPipelineSheet = true
                                }
                            },
                            modifier = itemModifier
                        )
                    }

                    ContentManagementOptionsPanel(
                        visible = showContentManagementOptions,
                        onFilterManageClick = {
                            handleContentManagementAction(onFilterManageClick)
                        },
                        onFrameManageClick = {
                            handleContentManagementAction(onFrameManageClick)
                        },
                        onPresetManageClick = {
                            handleContentManagementAction(onPresetManageClick)
                        }
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PhotoMenuWideActionTile(
                            title = "FILTER",
                            leadingIcon = { tint -> PhotoMenuFilterGlyph(tint) },
                            trailingIcon = AppIcons.AutoAwesome,
                            trailingTint = Color.White.copy(alpha = 0.75f),
                            onClick = onPhotoFilterClick,
                            modifier = Modifier.weight(1f).height(35.dp)
                        )
                        PhotoMenuWideActionTile(
                            title = "DEPTH",
                            leadingIcon = { tint -> PhotoMenuDepthGlyph(tint) },
                            trailingIcon = AppIcons.Tune,
                            trailingTint = AccentColor,
                            onClick = onPhotoDepthClick,
                            modifier = Modifier.weight(1f).height(38.dp)
                        )
                    }

                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        VideoAspectRatio.entries
                            .filter { it != VideoAspectRatio.OPEN_GATE }
                            .forEach { ratio ->
                                val selected = videoAspectRatio == ratio
                                val shape = RoundedCornerShape(15.dp)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp)
                                        .then(
                                            if (selected) Modifier.clip(shape).background(AccentColor)
                                            else Modifier.menuCardSurface(shape = shape, background = VideoMenuCard, elevation = 3.dp)
                                        )
                                        .clickable { onVideoAspectRatioChange(ratio) }
                                        .padding(horizontal = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        VideoAspectRatioMenuIcon(ratio = ratio, tint = if (selected) OnAccentColor else AccentColor)
                                        Text(
                                            text = videoAspectRatioLabel(ratio),
                                            color = if (selected) OnAccentColor else Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        val dualSelected = dualVideoAspectRatio != null
                        val dualShape = RoundedCornerShape(15.dp)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .then(
                                    if (dualSelected) Modifier.clip(dualShape).background(AccentColor)
                                    else Modifier.menuCardSurface(shape = dualShape, background = VideoMenuCard, elevation = 3.dp)
                                )
                                .clickable { onDualVideoAspectToggle() }
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(AppIcons.Videocam, contentDescription = null,
                                    tint = if (dualSelected) OnAccentColor else AccentColor,
                                    modifier = Modifier.size(21.dp))
                                Text("DUAL", color = if (dualSelected) OnAccentColor else Color.White,
                                    fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                        }
                    }

                    AnimatedVisibility(visible = dualVideoAspectRatio != null) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            DualVideoAspectRatio.entries
                                .filterNot { it.matchesPrimary(videoAspectRatio) }
                                .forEach { ratio ->
                                    VideoOptionChip(
                                        title = ratio.displayName,
                                        selected = dualVideoAspectRatio == ratio,
                                        onClick = { onDualVideoAspectRatioChange(ratio) }
                                    )
                                }
                        }
                    }

                    Spacer(modifier = Modifier.height(7.dp))

                    QuickSettingGrid(
                        columns = 4,
                        itemHeight = 56.dp,
                        horizontalSpacing = 5.dp,
                        verticalSpacing = 4.dp
                    ) { itemModifier ->
                        VideoSettingTile(
                            leadingIcon = { tint ->
                                Icon(AppIcons.BarChart, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
                            },
                            title = stringResource(R.string.video_log_chip),
                            summary = videoLogProfileLabel(videoLogProfile),
                            expanded = expandedVideoPanel == VideoSettingPanel.LOG_PROFILE,
                            onClick = {
                                expandedVideoPanel = if (expandedVideoPanel == VideoSettingPanel.LOG_PROFILE) {
                                    null
                                } else {
                                    VideoSettingPanel.LOG_PROFILE
                                }
                            },
                            modifier = itemModifier
                        )
                        VideoSettingTile(
                            leadingIcon = { tint ->
                                Icon(AppIcons.Layers, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
                            },
                            title = if (videoCodec == VideoCodec.APV) "APV quality" else stringResource(R.string.video_bitrate_chip),
                            summary = if (videoCodec == VideoCodec.APV) apvQuality.displayName else "${videoBitrate.bitrateMbps}M",
                            expanded = expandedVideoPanel == VideoSettingPanel.BITRATE,
                            onClick = {
                                expandedVideoPanel = if (expandedVideoPanel == VideoSettingPanel.BITRATE) {
                                    null
                                } else {
                                    VideoSettingPanel.BITRATE
                                }
                            },
                            modifier = itemModifier
                        )
                        VideoSettingTile(
                            leadingIcon = { tint ->
                                Icon(AppIcons.Videocam, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
                            },
                            title = stringResource(R.string.video_codec_chip),
                            summary = videoCodec.displayName,
                            expanded = expandedVideoPanel == VideoSettingPanel.CODEC,
                            onClick = {
                                expandedVideoPanel = if (expandedVideoPanel == VideoSettingPanel.CODEC) {
                                    null
                                } else {
                                    VideoSettingPanel.CODEC
                                }
                            },
                            modifier = itemModifier
                        )
                        VideoSettingTile(
                            leadingIcon = { tint -> VideoMicrophoneMenuGlyph(tint) },
                            title = stringResource(R.string.video_microphone_title),
                            summary = selectedVideoAudioInputLabel(
                                selectedAudioInputId = videoAudioInputId,
                                options = videoAudioInputOptions
                            ),
                            expanded = expandedVideoPanel == VideoSettingPanel.MICROPHONE,
                            onClick = {
                                expandedVideoPanel = if (expandedVideoPanel == VideoSettingPanel.MICROPHONE) {
                                    null
                                } else {
                                    VideoSettingPanel.MICROPHONE
                                }
                            },
                            modifier = itemModifier
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    QuickSettingGrid(
                        columns = 4,
                        itemHeight = 42.dp,
                        horizontalSpacing = 5.dp,
                        verticalSpacing = 4.dp
                    ) { itemModifier ->
                        VideoMenuToggleTile(
                            title = "FLASH",
                            leadingIcon = { tint ->
                                Icon(AppIcons.FlashOn, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
                            },
                            checked = videoTorchEnabled,
                            onClick = onVideoTorchToggle,
                            modifier = itemModifier
                        )
                        VideoMenuToggleTile(
                            title = "HDR",
                            leadingIcon = { tint -> VideoHdrMenuGlyph(tint) },
                            checked = videoHdrEnabled,
                            onClick = onVideoHdrToggle,
                            modifier = itemModifier
                        )
                        VideoMenuToggleTile(
                            title = "ACTION",
                            leadingIcon = { tint ->
                                Icon(AppIcons.DirectionsWalk, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
                            },
                            checked = videoStabilizationMode == VideoStabilizationMode.ENHANCED,
                            enabled = videoActionModeAvailable,
                            onClick = {
                                onVideoStabilizationModeChange(
                                    if (videoStabilizationMode == VideoStabilizationMode.ENHANCED) {
                                        VideoStabilizationMode.OFF
                                    } else {
                                        VideoStabilizationMode.ENHANCED
                                    }
                                )
                            },
                            modifier = itemModifier
                        )
                        VideoMenuToggleTile(
                            title = "SPECIAL VIDEO",
                            leadingIcon = { tint ->
                                Icon(AppIcons.Videocam, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                            },
                            checked = spatialVideoEnabled,
                            enabled = true,
                            onClick = onVideoSpatialVideoClick,
                            modifier = itemModifier
                        )
                        VideoMenuActionTile(
                            title = "DUAL CAPTURE",
                            value = "Open",
                            leadingIcon = { tint ->
                                Icon(AppIcons.Videocam, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                            },
                            onClick = onVideoDualCaptureClick,
                            modifier = itemModifier
                        )
                    }

                    AnimatedVisibility(
                        visible = expandedVideoPanel != null,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column {
                            Spacer(modifier = Modifier.height(12.dp))
                            VideoSettingExpandedPanel {
                                when (expandedVideoPanel) {
                                    VideoSettingPanel.ASPECT_RATIO -> {
                                        FlowRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            VideoAspectRatio.entries
                                                .filter { it != VideoAspectRatio.OPEN_GATE }
                                                .forEach { ratio ->
                                                    VideoOptionChip(
                                                        title = videoAspectRatioLabel(ratio),
                                                        selected = videoAspectRatio == ratio,
                                                        onClick = { onVideoAspectRatioChange(ratio) }
                                                    )
                                                }
                                            DualVideoAspectRatio.entries
                                                .filterNot { it.matchesPrimary(videoAspectRatio) }
                                                .forEach { ratio ->
                                                    VideoOptionChip(
                                                        title = "Dual " + ratio.displayName,
                                                        selected = dualVideoAspectRatio == ratio,
                                                        onClick = { onDualVideoAspectRatioChange(ratio) }
                                                    )
                                                }
                                        }
                                    }

                                    VideoSettingPanel.LOG_PROFILE -> {
                                        FlowRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            availableVideoLogProfiles.forEach { profile ->
                                                VideoOptionChip(
                                                    title = videoLogProfileLabel(profile),
                                                    selected = videoLogProfile == profile,
                                                    onClick = { onVideoLogProfileChange(profile) }
                                                )
                                            }
                                        }
                                    }

                                    VideoSettingPanel.BITRATE -> {
                                        FlowRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            if (videoCodec == VideoCodec.APV) {
                                                ApvQuality.entries.forEach { quality ->
                                                    VideoOptionChip(
                                                        title = quality.displayName,
                                                        selected = apvQuality == quality,
                                                        onClick = { onApvQualityChange(quality) }
                                                    )
                                                }
                                            } else {
                                                VideoBitratePreset.entries.forEach { bitrate ->
                                                    VideoOptionChip(
                                                        title = "${bitrate.bitrateMbps}M",
                                                        selected = videoBitrate == bitrate,
                                                        onClick = { onVideoBitrateChange(bitrate) }
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    VideoSettingPanel.CODEC -> {
                                        FlowRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            availableVideoCodecs.ifEmpty { listOf(videoCodec) }.forEach { codec ->
                                                VideoOptionChip(
                                                    title = codec.displayName,
                                                    selected = videoCodec == codec,
                                                    onClick = { onVideoCodecChange(codec) }
                                                )
                                            }
                                        }
                                    }

                                    VideoSettingPanel.MICROPHONE -> {
                                        FlowRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            VideoOptionChip(
                                                title = stringResource(R.string.video_microphone_auto),
                                                selected = videoAudioInputId == VIDEO_AUDIO_INPUT_AUTO,
                                                onClick = { onVideoAudioInputChange(VIDEO_AUDIO_INPUT_AUTO) }
                                            )
                                            videoAudioInputOptions.forEach { option ->
                                                VideoOptionChip(
                                                    title = videoAudioInputLabel(option),
                                                    selected = videoAudioInputId == option.id,
                                                    onClick = { onVideoAudioInputChange(option.id) }
                                                )
                                            }
                                        }
                                    }

                                    null -> Unit
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    VideoEditTile(
                        onClick = onVideoEditClick,
                        modifier = Modifier.fillMaxWidth().height(34.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuCardSurface(
                                shape = RoundedCornerShape(16.dp),
                                background = VideoMenuCard,
                                elevation = 4.dp
                            )
                            .clickable(onClick = onMoreSettingsClick)
                            .padding(horizontal = 12.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(VideoMenuRaisedCard),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = null,
                                tint = AccentColor,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.settings_title),
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = AppIcons.ChevronRight,
                            contentDescription = null,
                            tint = AccentColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }

    }

    if (showOppoPipelineSheet) {
        OppoPhotoPipelineSheet(
            settings = oppoPhotoSettings,
            onSettingsChange = onOppoPhotoSettingsChange,
            onDismiss = { showOppoPipelineSheet = false }
        )
    }

    if (showRenderingEngineSheet) {
        AppModalBottomSheet(
            onDismissRequest = { showRenderingEngineSheet = false },
            dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.2f)) }
        ) {
            Text(
                text = stringResource(R.string.settings_raw_color_engine),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = 4.dp, bottom = 8.dp)
            ) {
                RawRenderingEngineSettingsPanel(
                    selectedDcpId = rawDcpId,
                    rawDcpIdsByLens = rawDcpIdsByLens,
                    dcpLensOptions = rawDcpLensOptions,
                    availableDcps = availableDcps,
                    rawRenderingEngine = rawRenderingEngine,
                    rawToneMappingParameters = rawToneMappingParameters,
                    spectralFilmSelection = rawSpectralFilmSelection,
                    spectralFilmPrint = rawSpectralFilmPrint,
                    onSelectDcp = onRawDcpChange,
                    onRawDcpIdsByLensChange = onRawDcpIdsByLensChange,
                    onImportDcp = onImportRawDcp,
                    onDeleteDcp = onDeleteRawDcp,
                    hncsFilmCurveMode = rawHncsFilmCurveMode,
                    onHncsFilmCurveModeChange = onRawHncsFilmCurveModeChange,
                    onRawColorEngineChange = onRawColorEngineChange,
                    onRawToneMappingParametersChange = onRawToneMappingParametersChange,
                    onSpectralFilmSelectionChange = onRawSpectralFilmSelectionChange,
                    onSpectralFilmPrintChange = onRawSpectralFilmPrintChange,
                    inlineEngineOptions = true,
                    showToneMappingControls = true
                )
            }
        }
    }

}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun OppoPhotoPipelineSheet(
    settings: OppoPhotoSettings,
    onSettingsChange: (OppoPhotoSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    AppModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.2f)) }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = stringResource(R.string.oppo_custom_pipeline),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            QuickSettingToggle(
                title = stringResource(R.string.oppo_enable_custom_pipeline),
                checked = settings.customPipelineEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(customPipelineEnabled = it)) },
                modifier = Modifier.fillMaxWidth(),
            )
            SectionLabel(title = stringResource(R.string.oppo_color_profile))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    "NATURAL" to R.string.oppo_profile_natural,
                    "HASSELBLAD" to R.string.oppo_profile_hasselblad,
                    "SAMSUNG" to R.string.oppo_profile_samsung,
                    "PIXEL" to R.string.oppo_profile_pixel,
                    "IPHONE" to R.string.oppo_profile_iphone,
                    "CUSTOM" to R.string.oppo_profile_custom,
                ).forEach { (profile, label) ->
                    VideoOptionChip(
                        title = stringResource(label),
                        selected = settings.pipelineProfile == profile,
                        onClick = {
                            onSettingsChange(settings.withPhotoProfile(profile))
                        },
                    )
                }
            }
            if (settings.pipelineProfile == "CUSTOM") {
                Spacer(Modifier.height(8.dp))
                SectionLabel(title = "Custom 3×3 Color Matrix")
                val matrixLabels = listOf(
                    "Red from red", "Red from green", "Red from blue",
                    "Green from red", "Green from green", "Green from blue",
                    "Blue from red", "Blue from green", "Blue from blue",
                )
                settings.customColorMatrix.forEachIndexed { index, value ->
                    OppoPipelineSliderLabel(matrixLabels[index], value, -1f..2f) { next ->
                        val matrix = settings.customColorMatrix.toMutableList().also { it[index] = next }
                        onSettingsChange(
                            settings.copy(
                                customColorMatrix = matrix,
                                customPipelineEnabled = true,
                                pipelineProfile = "CUSTOM",
                            )
                        )
                    }
                }
            }
            OppoPipelineSlider(R.string.oppo_pipeline_exposure, settings.exposure, -2f..2f) {
                onSettingsChange(settings.copy(exposure = it, customPipelineEnabled = true, pipelineProfile = "CUSTOM"))
            }
            OppoPipelineSlider(R.string.oppo_pipeline_contrast, settings.contrast, 0.6f..1.4f) {
                onSettingsChange(settings.copy(contrast = it, customPipelineEnabled = true, pipelineProfile = "CUSTOM"))
            }
            OppoPipelineSlider(R.string.oppo_pipeline_highlights, settings.highlights, -100f..100f) {
                onSettingsChange(settings.copy(highlights = it, customPipelineEnabled = true, pipelineProfile = "CUSTOM"))
            }
            OppoPipelineSlider(R.string.oppo_pipeline_shadows, settings.shadows, -100f..100f) {
                onSettingsChange(settings.copy(shadows = it, customPipelineEnabled = true, pipelineProfile = "CUSTOM"))
            }
            OppoPipelineSliderLabel("Whites", settings.whites, -100f..100f) {
                onSettingsChange(settings.copy(whites = it, customPipelineEnabled = true, pipelineProfile = "CUSTOM"))
            }
            OppoPipelineSliderLabel("Blacks", settings.blacks, -100f..100f) {
                onSettingsChange(settings.copy(blacks = it, customPipelineEnabled = true, pipelineProfile = "CUSTOM"))
            }
            OppoPipelineSliderLabel("Highlight roll-off", settings.highlightRollOff, 0f..100f) {
                onSettingsChange(settings.copy(highlightRollOff = it, customPipelineEnabled = true, pipelineProfile = "CUSTOM"))
            }
            OppoPipelineSliderLabel("Shadow recovery", settings.shadowRecovery, 0f..100f) {
                onSettingsChange(settings.copy(shadowRecovery = it, customPipelineEnabled = true, pipelineProfile = "CUSTOM"))
            }
            OppoPipelineSliderLabel("Local tone mapping", settings.localToneMapping, 0f..100f) {
                onSettingsChange(settings.copy(localToneMapping = it, customPipelineEnabled = true, pipelineProfile = "CUSTOM"))
            }
            OppoPipelineSlider(R.string.oppo_pipeline_saturation, settings.saturation, -100f..100f) {
                onSettingsChange(settings.copy(saturation = it, customPipelineEnabled = true, pipelineProfile = "CUSTOM"))
            }
            OppoPipelineSlider(R.string.oppo_pipeline_vibrance, settings.vibrance, -100f..100f) {
                onSettingsChange(settings.copy(vibrance = it, customPipelineEnabled = true, pipelineProfile = "CUSTOM"))
            }
            OppoPipelineSlider(R.string.oppo_pipeline_temperature, settings.temperature, -100f..100f) {
                onSettingsChange(settings.copy(temperature = it, customPipelineEnabled = true, pipelineProfile = "CUSTOM"))
            }
            OppoPipelineSlider(R.string.oppo_pipeline_tint, settings.tint, -100f..100f) {
                onSettingsChange(settings.copy(tint = it, customPipelineEnabled = true, pipelineProfile = "CUSTOM"))
            }
            OppoPipelineSlider(R.string.oppo_pipeline_sharpness, settings.sharpness, 0f..100f) {
                onSettingsChange(settings.copy(sharpness = it, customPipelineEnabled = true, pipelineProfile = "CUSTOM"))
            }
            OppoPipelineSliderLabel("Micro contrast", settings.microContrast, -100f..100f) {
                onSettingsChange(settings.copy(microContrast = it, customPipelineEnabled = true, pipelineProfile = "CUSTOM"))
            }
            OppoPipelineSlider(R.string.oppo_pipeline_texture, settings.texture, -100f..100f) {
                onSettingsChange(settings.copy(texture = it, customPipelineEnabled = true, pipelineProfile = "CUSTOM"))
            }
            OppoPipelineSlider(R.string.oppo_pipeline_noise_reduction, settings.noiseReduction, 0f..100f) {
                onSettingsChange(settings.copy(noiseReduction = it, customPipelineEnabled = true, pipelineProfile = "CUSTOM"))
            }
            OppoPipelineSlider(R.string.oppo_pipeline_detail_preservation, settings.detailPreservation, 0f..100f) {
                onSettingsChange(settings.copy(detailPreservation = it, customPipelineEnabled = true, pipelineProfile = "CUSTOM"))
            }
        }
    }
}

@Composable
private fun OppoPipelineSlider(
    titleRes: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(titleRes),
                color = Color.White.copy(alpha = 0.88f),
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = String.format("%.2f", value),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
    }
}

@Composable
private fun OppoPipelineSliderLabel(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                color = Color.White.copy(alpha = 0.88f),
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = String.format("%.2f", value),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
    }
}

@Composable
private fun PhotoRefocusGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(38.dp)) {
        val stroke = 2.2.dp.toPx()
        val left = size.width * 0.14f
        val right = size.width * 0.86f
        val top = size.height * 0.14f
        val bottom = size.height * 0.86f
        val leg = size.width * 0.22f
        drawLine(tint, Offset(left, top + leg), Offset(left, top), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(left, top), Offset(left + leg, top), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(right - leg, top), Offset(right, top), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(right, top), Offset(right, top + leg), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(left, bottom - leg), Offset(left, bottom), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(left, bottom), Offset(left + leg, bottom), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(right - leg, bottom), Offset(right, bottom), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(right, bottom), Offset(right, bottom - leg), stroke, cap = StrokeCap.Round)
        drawCircle(tint, radius = 3.5.dp.toPx(), center = Offset(size.width / 2f, size.height / 2f))
    }
}

@Composable
private fun PhotoLiveGlyph(tint: Color) {
    Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val center = Offset(size.width * 0.46f, size.height * 0.47f)
            val base = size.minDimension * 0.35f
            val stroke = 1.8.dp.toPx()
            drawCircle(tint, radius = base, center = center, style = Stroke(width = stroke))
            drawCircle(tint, radius = base * 0.72f, center = center, style = Stroke(width = stroke))
            drawCircle(tint, radius = base * 0.40f, center = center, style = Stroke(width = stroke))
            drawCircle(tint, radius = base * 0.12f, center = center)
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(17.dp)
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            Text("A", color = Color(0xFF17171B), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PhotoAspectGlyph(tint: Color, ratioLabel: String) {
    Box(modifier = Modifier.size(38.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val stroke = 2.1.dp.toPx()
            val left = size.width * 0.08f
            val right = size.width * 0.92f
            val top = size.height * 0.12f
            val bottom = size.height * 0.88f
            val leg = size.width * 0.20f
            drawLine(tint, Offset(left, top + leg), Offset(left, top), stroke, cap = StrokeCap.Round)
            drawLine(tint, Offset(left, top), Offset(left + leg, top), stroke, cap = StrokeCap.Round)
            drawLine(tint, Offset(right - leg, top), Offset(right, top), stroke, cap = StrokeCap.Round)
            drawLine(tint, Offset(right, top), Offset(right, top + leg), stroke, cap = StrokeCap.Round)
            drawLine(tint, Offset(left, bottom - leg), Offset(left, bottom), stroke, cap = StrokeCap.Round)
            drawLine(tint, Offset(left, bottom), Offset(left + leg, bottom), stroke, cap = StrokeCap.Round)
            drawLine(tint, Offset(right - leg, bottom), Offset(right, bottom), stroke, cap = StrokeCap.Round)
            drawLine(tint, Offset(right, bottom), Offset(right, bottom - leg), stroke, cap = StrokeCap.Round)
        }
        Text(
            text = ratioLabel,
            color = tint,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun PhotoTimerGlyph(tint: Color, timerSeconds: Int) {
    Canvas(modifier = Modifier.size(38.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.34f
        val stroke = 2.2.dp.toPx()
        drawCircle(tint, radius = radius, center = center, style = Stroke(width = stroke))
        drawLine(tint, Offset(center.x, center.y - radius * 0.60f), center, stroke, cap = StrokeCap.Round)
        drawLine(tint, center, Offset(center.x + radius * 0.48f, center.y + radius * 0.30f), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(center.x, center.y - radius - 4.dp.toPx()), Offset(center.x, center.y - radius - 1.dp.toPx()), stroke, cap = StrokeCap.Round)
        if (timerSeconds == 0) {
            drawLine(
                tint,
                Offset(size.width * 0.16f, size.height * 0.16f),
                Offset(size.width * 0.84f, size.height * 0.84f),
                stroke,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun PhotoBurstGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(40.dp)) {
        val stroke = 1.9.dp.toPx()
        val radius = CornerRadius(3.dp.toPx())
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * 0.28f, size.height * 0.08f),
            size = Size(size.width * 0.62f, size.height * 0.54f),
            cornerRadius = radius,
            style = Stroke(width = stroke)
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * 0.16f, size.height * 0.22f),
            size = Size(size.width * 0.62f, size.height * 0.54f),
            cornerRadius = radius,
            style = Stroke(width = stroke)
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * 0.05f, size.height * 0.36f),
            size = Size(size.width * 0.62f, size.height * 0.54f),
            cornerRadius = radius,
            style = Stroke(width = stroke)
        )
    }
}

@Composable
private fun PhotoStylesGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(40.dp)) {
        val stroke = 2.0.dp.toPx()
        fun drawLayer(left: Float, top: Float, right: Float, bottom: Float) {
            val path = Path().apply {
                moveTo(left, top + size.height * 0.10f)
                lineTo(right - size.width * 0.08f, top)
                lineTo(right, bottom - size.height * 0.10f)
                lineTo(left + size.width * 0.08f, bottom)
                close()
            }
            drawPath(path, tint, style = Stroke(width = stroke))
        }
        drawLayer(size.width * 0.25f, size.height * 0.04f, size.width * 0.84f, size.height * 0.77f)
        drawLayer(size.width * 0.12f, size.height * 0.18f, size.width * 0.71f, size.height * 0.90f)
    }
}

@Composable
private fun PhotoFilterGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(40.dp)) {
        val stroke = 2.1.dp.toPx()
        drawCircle(tint, radius = size.minDimension * 0.23f, center = Offset(size.width * 0.42f, size.height * 0.38f), style = Stroke(width = stroke))
        drawCircle(tint, radius = size.minDimension * 0.23f, center = Offset(size.width * 0.60f, size.height * 0.58f), style = Stroke(width = stroke))
        drawCircle(tint, radius = size.minDimension * 0.23f, center = Offset(size.width * 0.35f, size.height * 0.65f), style = Stroke(width = stroke))
    }
}

@Composable
private fun PhotoDepthGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(40.dp)) {
        val stroke = 2.2.dp.toPx()
        val left = size.width * 0.19f
        val right = size.width * 0.80f
        val top = size.height * 0.16f
        val bottom = size.height * 0.84f
        drawRoundRect(
            color = tint,
            topLeft = Offset(left, top),
            size = Size(size.width * 0.54f, size.height * 0.68f),
            cornerRadius = CornerRadius(4.dp.toPx()),
            style = Stroke(width = stroke)
        )
        drawRoundRect(
            color = tint.copy(alpha = 0.72f),
            topLeft = Offset(size.width * 0.34f, size.height * 0.27f),
            size = Size(size.width * 0.48f, size.height * 0.57f),
            cornerRadius = CornerRadius(4.dp.toPx()),
            style = Stroke(width = stroke)
        )
        drawLine(
            tint,
            Offset(size.width * 0.13f, bottom),
            Offset(size.width * 0.83f, top),
            stroke,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun VideoMenuItem(
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit
) {
    // The parent menu is the only glass surface. Keep shortcuts as transparent
    // icon/label content so their own disks, shadows and borders cannot stack over it.
    val iconTint = when {
        !enabled -> Color.White.copy(alpha = 0.36f)
        active -> Color.White
        else -> Color.White.copy(alpha = 0.78f)
    }
    Column(
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(62.dp),
            contentAlignment = Alignment.Center
        ) {
            icon(iconTint)
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.42f),
            fontSize = 11.sp,
            lineHeight = 12.sp,
            letterSpacing = 0.30.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 2
        )
    }
}

@Composable
private fun VideoEditGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(36.dp)) {
        val stroke = 2.3.dp.toPx()
        val centerX = size.width / 2f
        drawLine(tint, Offset(centerX, size.height * 0.06f), Offset(centerX, size.height * 0.47f), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(size.width * 0.18f, size.height * 0.27f), Offset(size.width * 0.82f, size.height * 0.27f), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(size.width * 0.18f, size.height * 0.73f), Offset(size.width * 0.82f, size.height * 0.73f), stroke, cap = StrokeCap.Round)
    }
}

@Composable
private fun VideoActionGlyph(tint: Color, active: Boolean) {
    Canvas(modifier = Modifier.size(38.dp)) {
        val scale = size.minDimension / 32f
        val stroke = 2.0.dp.toPx()
        fun p(x: Float, y: Float) = Offset(x * scale, y * scale)
        drawLine(tint, p(2f, 10f), p(9f, 10f), stroke, cap = StrokeCap.Round)
        drawLine(tint, p(1f, 17f), p(7f, 17f), stroke, cap = StrokeCap.Round)
        drawLine(tint, p(2f, 24f), p(6f, 24f), stroke, cap = StrokeCap.Round)
        drawCircle(tint, radius = 2.5f * scale, center = p(21f, 6.5f))
        drawLine(tint, p(18.5f, 10f), p(15f, 17f), stroke, cap = StrokeCap.Round)
        drawLine(tint, p(16.5f, 12f), p(11f, 10f), stroke, cap = StrokeCap.Round)
        drawLine(tint, p(11f, 10f), p(8f, 13f), stroke, cap = StrokeCap.Round)
        drawLine(tint, p(17f, 13f), p(24f, 15f), stroke, cap = StrokeCap.Round)
        drawLine(tint, p(24f, 15f), p(28f, 12f), stroke, cap = StrokeCap.Round)
        drawLine(tint, p(15f, 17f), p(11f, 24f), stroke, cap = StrokeCap.Round)
        drawLine(tint, p(11f, 24f), p(6f, 28f), stroke, cap = StrokeCap.Round)
        drawLine(tint, p(15f, 17f), p(21f, 23f), stroke, cap = StrokeCap.Round)
        drawLine(tint, p(21f, 23f), p(27f, 27f), stroke, cap = StrokeCap.Round)
        if (!active) drawLine(tint, p(5f, 2.5f), p(28f, 29.5f), stroke, cap = StrokeCap.Round)
    }
}

@Composable
private fun VideoDualCaptureGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(38.dp)) {
        val scale = size.minDimension / 32f
        val stroke = 1.9.dp.toPx()
        drawRoundRect(color = tint, topLeft = Offset(3f * scale, 4f * scale), size = Size(19f * scale, 15f * scale), cornerRadius = CornerRadius(2.2f * scale), style = Stroke(width = stroke))
        drawRoundRect(color = tint, topLeft = Offset(10f * scale, 12f * scale), size = Size(19f * scale, 15f * scale), cornerRadius = CornerRadius(2.2f * scale), style = Stroke(width = stroke))
        drawCircle(tint, radius = 2.2f * scale, center = Offset(20f * scale, 18f * scale))
        drawLine(tint, Offset(16.5f * scale, 24f * scale), Offset(23.5f * scale, 24f * scale), stroke, cap = StrokeCap.Round)
    }
}

@Composable
private fun VideoLogGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(38.dp)) {
        val stroke = 2.6.dp.toPx()
        val left = size.width * 0.16f
        val right = size.width * 0.84f
        val top = size.height * 0.16f
        val bottom = size.height * 0.84f
        val leg = size.width * 0.19f
        drawLine(tint, Offset(left, top + leg), Offset(left, top), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(left, top), Offset(left + leg, top), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(right - leg, top), Offset(right, top), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(right, top), Offset(right, top + leg), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(left, bottom - leg), Offset(left, bottom), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(left, bottom), Offset(left + leg, bottom), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(right - leg, bottom), Offset(right, bottom), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(right, bottom), Offset(right, bottom - leg), stroke, cap = StrokeCap.Round)
        drawCircle(tint, radius = 2.6.dp.toPx(), center = Offset(size.width / 2f, size.height / 2f))
    }
}

@Composable
private fun VideoAspectRatioGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(38.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.34f
        val dotRadius = 1.15.dp.toPx()
        repeat(12) { index ->
            val angle = Math.toRadians((index * 30.0 - 90.0))
            val x = center.x + kotlin.math.cos(angle).toFloat() * radius
            val y = center.y + kotlin.math.sin(angle).toFloat() * radius
            drawCircle(tint, radius = dotRadius, center = Offset(x, y))
        }
        drawLine(tint, center, Offset(center.x, center.y - radius * 0.74f), 2.2.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(tint, radius = 2.2.dp.toPx(), center = center)
    }
}


@Composable
private fun photoMeteringModeLabel(mode: MeteringMode): String {
    return when (mode) {
        MeteringMode.SPOT -> stringResource(R.string.metering_spot)
        MeteringMode.CENTER_WEIGHTED -> stringResource(R.string.metering_center_weighted)
        MeteringMode.SYSTEM_DEFAULT -> stringResource(R.string.metering_system_default)
        MeteringMode.AVERAGE -> stringResource(R.string.metering_average)
        MeteringMode.HIGHLIGHT_PRIORITY -> stringResource(R.string.metering_highlight_priority)
    }
}

@Composable
private fun PhotoMenuQuickTile(
    title: String = "",
    subtitle: String? = null,
    label: String? = null,
    leadingIcon: @Composable (Color) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    checked: Boolean = false,
    enabled: Boolean = true,
    showStatusDot: Boolean = false,
    accentTitle: Boolean = subtitle != null || label != null,
    trailingIcon: ImageVector? = null
) {
    val shape = RoundedCornerShape(10.dp)
    val contentAlpha = if (enabled) 1f else 0.42f
    val foreground = if (selected) OnAccentColor else Color.White.copy(alpha = contentAlpha)
    val iconTint = if (selected) OnAccentColor else AccentColor.copy(alpha = contentAlpha)
    val displayedTitle = label?.substringBefore(' ') ?: title
    val displayedSubtitle = label?.substringAfter(' ', "")?.takeIf { it.isNotBlank() } ?: subtitle

    Box(
        modifier = modifier
            .fillMaxHeight()
            .then(
                if (selected) Modifier.clip(shape).background(AccentColor)
                else Modifier.menuCardSurface(
                    shape = shape,
                    background = VideoMenuCard,
                    elevation = 2.dp
                )
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                leadingIcon(iconTint)
            }
            if (displayedSubtitle != null) {
                QuickSettingLabel(
                    text = displayedTitle,
                    color = if (selected) OnAccentColor else if (accentTitle) AccentColor else foreground,
                    fontWeight = FontWeight.SemiBold,
                    maxFontSize = 9.sp,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth()
                )
                QuickSettingLabel(
                    text = displayedSubtitle,
                    color = foreground,
                    fontWeight = FontWeight.Bold,
                    maxFontSize = 9.sp,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                QuickSettingLabel(
                    text = displayedTitle,
                    color = foreground,
                    fontWeight = FontWeight.Bold,
                    maxFontSize = 9.5.sp,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        when {
            showStatusDot -> {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                selected -> OnAccentColor
                                checked -> AccentColor
                                else -> Color(0xFF77777D)
                            }.copy(alpha = contentAlpha)
                        )
                )
            }
            trailingIcon != null -> {
                Icon(
                    imageVector = trailingIcon,
                    contentDescription = null,
                    tint = foreground,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(11.dp)
                )
            }
        }
    }
}

@Composable
private fun PhotoMenuContentManagementTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .menuCardSurface(
                shape = RoundedCornerShape(10.dp),
                background = VideoMenuCard,
                elevation = 2.dp
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Icon(
                imageVector = AppIcons.Tune,
                contentDescription = null,
                tint = AccentColor,
                modifier = Modifier.size(18.dp)
            )
            QuickSettingLabel(
                text = "Content",
                color = AccentColor,
                fontWeight = FontWeight.SemiBold,
                maxFontSize = 9.sp,
                modifier = Modifier.fillMaxWidth()
            )
            QuickSettingLabel(
                text = "Management",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxFontSize = 9.sp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun PhotoMenuWideActionTile(
    title: String,
    leadingIcon: @Composable (Color) -> Unit,
    trailingIcon: ImageVector,
    trailingTint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .menuCardSurface(
                shape = RoundedCornerShape(14.dp),
                background = VideoMenuCard,
                elevation = 3.dp
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        leadingIcon(AccentColor)
        Text(
            text = title,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
            maxLines = 1
        )
        Icon(
            imageVector = trailingIcon,
            contentDescription = null,
            tint = trailingTint,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun PhotoMenuHdrGlyph(tint: Color) {
    Box(
        modifier = Modifier
            .size(width = 25.dp, height = 17.dp)
            .border(1.5.dp, tint, RoundedCornerShape(3.dp))
            .padding(1.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "HDR",
            color = tint,
            fontSize = 8.sp,
            lineHeight = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun PhotoMenuApertureGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(22.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.36f
        val stroke = 1.8.dp.toPx()
        drawCircle(tint, radius = radius, center = center, style = Stroke(width = stroke))
        repeat(6) { index ->
            val angle = Math.toRadians(index * 60.0)
            val start = Offset(
                center.x + kotlin.math.cos(angle).toFloat() * radius * 0.22f,
                center.y + kotlin.math.sin(angle).toFloat() * radius * 0.22f
            )
            val end = Offset(
                center.x + kotlin.math.cos(angle + Math.PI / 3.0).toFloat() * radius,
                center.y + kotlin.math.sin(angle + Math.PI / 3.0).toFloat() * radius
            )
            drawLine(tint, start, end, stroke, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun PhotoMenuLivePhotoGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(22.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(tint, radius = size.minDimension * 0.34f, center = center, style = Stroke(width = 1.8.dp.toPx()))
        drawCircle(tint, radius = size.minDimension * 0.10f, center = center)
    }
}

@Composable
private fun PhotoMenuRefocusGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(22.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val stroke = 1.8.dp.toPx()
        val radius = size.minDimension * 0.31f
        drawCircle(tint, radius = radius, center = center, style = Stroke(width = stroke))
        drawLine(tint, Offset(center.x, 0f), Offset(center.x, size.height * 0.22f), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(center.x, size.height * 0.78f), Offset(center.x, size.height), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(0f, center.y), Offset(size.width * 0.22f, center.y), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(size.width * 0.78f, center.y), Offset(size.width, center.y), stroke, cap = StrokeCap.Round)
        drawCircle(tint, radius = 2.dp.toPx(), center = center)
    }
}

@Composable
private fun PhotoMenuFilterGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(22.dp)) {
        val radius = size.minDimension * 0.18f
        val centers = listOf(
            Offset(size.width * 0.36f, size.height * 0.36f),
            Offset(size.width * 0.64f, size.height * 0.36f),
            Offset(size.width * 0.50f, size.height * 0.64f)
        )
        centers.forEach { center ->
            drawCircle(tint, radius = radius, center = center, style = Stroke(width = 1.7.dp.toPx()))
        }
    }
}

@Composable
private fun PhotoMenuDepthGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(22.dp)) {
        val leg = size.minDimension * 0.22f
        val inset = size.minDimension * 0.18f
        val stroke = 2.dp.toPx()
        val left = inset
        val top = inset
        val right = size.width - inset
        val bottom = size.height - inset
        drawLine(tint, Offset(left, top + leg), Offset(left, top), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(left, top), Offset(left + leg, top), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(right - leg, top), Offset(right, top), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(right, top), Offset(right, top + leg), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(left, bottom - leg), Offset(left, bottom), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(left, bottom), Offset(left + leg, bottom), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(right - leg, bottom), Offset(right, bottom), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(right, bottom), Offset(right, bottom - leg), stroke, cap = StrokeCap.Round)
    }
}

@Composable
private fun VideoSettingTile(
    leadingIcon: @Composable (Color) -> Unit,
    title: String,
    summary: String,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .fillMaxHeight()
            .then(
                if (expanded) Modifier.clip(shape).background(AccentColor)
                else Modifier.menuCardSurface(
                    shape = shape,
                    background = VideoMenuCard,
                    elevation = 2.dp
                )
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                leadingIcon(if (expanded) OnAccentColor else AccentColor)
            }
            QuickSettingLabel(
                text = title,
                color = if (expanded) OnAccentColor.copy(alpha = 0.82f) else AccentColor,
                fontWeight = FontWeight.SemiBold,
                maxFontSize = 9.sp,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth()
            )
            QuickSettingLabel(
                text = summary,
                color = if (expanded) OnAccentColor else Color.White,
                maxFontSize = 10.sp,
                maxLines = 1,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun VideoMenuToggleTile(
    title: String,
    leadingIcon: @Composable (Color) -> Unit,
    checked: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(10.dp)
    val alpha = if (enabled) 1f else 0.42f
    val selected = checked && enabled
    val foreground = if (selected) OnAccentColor else Color.White.copy(alpha = alpha)
    val iconTint = if (selected) OnAccentColor else AccentColor.copy(alpha = alpha)
    Box(
        modifier = modifier
            .fillMaxHeight()
            .then(
                if (selected) Modifier.clip(shape).background(AccentColor)
                else Modifier.menuCardSurface(
                    shape = shape,
                    background = VideoMenuCard,
                    elevation = 2.dp
                )
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                leadingIcon(iconTint)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                QuickSettingLabel(
                    text = title,
                    color = foreground,
                    fontWeight = FontWeight.SemiBold,
                    maxFontSize = 9.5.sp,
                    maxLines = 1
                )
                Spacer(Modifier.width(3.dp))
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                !enabled -> Color.White.copy(alpha = 0.14f)
                                selected -> OnAccentColor
                                else -> Color(0xFF77777D)
                            }
                        )
                )
            }
        }
    }
}

@Composable
private fun VideoMenuActionTile(
    title: String,
    value: String,
    leadingIcon: @Composable (Color) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .fillMaxHeight()
            .menuCardSurface(
                shape = shape,
                background = VideoMenuCard,
                elevation = 2.dp
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Box(Modifier.size(17.dp), contentAlignment = Alignment.Center) {
                leadingIcon(AccentColor)
            }
            QuickSettingLabel(
                text = title,
                color = AccentColor,
                maxFontSize = 9.sp,
                maxLines = 1,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth()
            )
            QuickSettingLabel(
                text = value,
                color = Color.White.copy(alpha = 0.72f),
                maxFontSize = 8.5.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun VideoEditTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .menuCardSurface(
                shape = RoundedCornerShape(15.dp),
                background = VideoMenuCard,
                elevation = 3.dp
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Edit,
            contentDescription = null,
            tint = AccentColor,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = stringResource(R.string.edit),
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            maxLines = 1
        )
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(VideoMenuRaisedCard),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = AppIcons.Tune,
                contentDescription = null,
                tint = AccentColor,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun VideoMicrophoneMenuGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(22.dp)) {
        val stroke = 1.9.dp.toPx()
        val centerX = size.width / 2f
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * 0.38f, size.height * 0.05f),
            size = Size(size.width * 0.24f, size.height * 0.55f),
            cornerRadius = CornerRadius(size.width * 0.12f),
            style = Stroke(width = stroke)
        )
        drawArc(
            color = tint,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(size.width * 0.22f, size.height * 0.24f),
            size = Size(size.width * 0.56f, size.height * 0.55f),
            style = Stroke(width = stroke)
        )
        drawLine(
            tint,
            Offset(centerX, size.height * 0.79f),
            Offset(centerX, size.height * 0.93f),
            stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            tint,
            Offset(size.width * 0.34f, size.height * 0.93f),
            Offset(size.width * 0.66f, size.height * 0.93f),
            stroke,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun VideoHdrMenuGlyph(tint: Color) {
    Box(
        modifier = Modifier
            .size(width = 25.dp, height = 17.dp)
            .border(1.5.dp, tint, RoundedCornerShape(3.dp))
            .padding(1.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "HDR",
            color = tint,
            fontSize = 8.sp,
            lineHeight = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun VideoAspectRatioMenuIcon(
    ratio: VideoAspectRatio,
    tint: Color
) {
    Canvas(modifier = Modifier.size(22.dp)) {
        val stroke = 2.dp.toPx()
        when (ratio) {
            VideoAspectRatio.RATIO_16_9 -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(size.width * 0.08f, size.height * 0.23f),
                    size = Size(size.width * 0.84f, size.height * 0.54f),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                    style = Stroke(width = stroke)
                )
            }
            VideoAspectRatio.RATIO_21_9 -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(size.width * 0.06f, size.height * 0.30f),
                    size = Size(size.width * 0.88f, size.height * 0.40f),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                    style = Stroke(width = stroke)
                )
            }
            VideoAspectRatio.OPEN_GATE -> {
                val radius = size.minDimension * 0.34f
                repeat(6) { index ->
                    drawArc(
                        color = tint,
                        startAngle = index * 60f + 5f,
                        sweepAngle = 42f,
                        useCenter = false,
                        topLeft = Offset(
                            size.width / 2f - radius,
                            size.height / 2f - radius
                        ),
                        size = Size(radius * 2f, radius * 2f),
                        style = Stroke(width = stroke * 2f)
                    )
                }
                drawCircle(tint, radius = 2.2.dp.toPx())
            }
        }
    }
}

@Composable
private fun VideoSettingExpandedPanel(
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .menuCardSurface(
                shape = RoundedCornerShape(14.dp),
                background = CameraMenuRaisedCard,
                elevation = 5.dp
            )
            .padding(12.dp),
        content = content
    )
}

@Composable
private fun VideoOptionChip(
    title: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .then(
                if (selected) Modifier.clip(RoundedCornerShape(8.dp)).background(AccentColor)
                else Modifier.menuCardSurface(shape = RoundedCornerShape(8.dp), elevation = 2.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = when {
                !enabled -> Color.White.copy(alpha = 0.38f)
                selected -> OnAccentColor
                else -> Color.White.copy(alpha = 0.72f)
            },
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun selectedVideoAudioInputLabel(
    selectedAudioInputId: String,
    options: List<VideoAudioInputOption>
): String {
    if (selectedAudioInputId == VIDEO_AUDIO_INPUT_AUTO) {
        return stringResource(R.string.video_microphone_auto)
    }
    val option = options.firstOrNull { it.id == selectedAudioInputId }
    return option?.let { videoAudioInputLabel(it) } ?: stringResource(R.string.video_microphone_disconnected)
}

@Composable
private fun videoAudioInputLabel(option: VideoAudioInputOption): String {
    val baseLabel = when (option.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> stringResource(R.string.video_microphone_builtin)
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> stringResource(R.string.video_microphone_wired)
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> stringResource(R.string.video_microphone_bluetooth)
        AudioDeviceInfo.TYPE_BLE_HEADSET -> stringResource(R.string.video_microphone_ble)
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE -> stringResource(R.string.video_microphone_usb)
        AudioDeviceInfo.TYPE_HDMI -> stringResource(R.string.video_microphone_hdmi)
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> stringResource(R.string.video_microphone_line_in)
        else -> stringResource(R.string.video_microphone_external)
    }
    val customName = option.productName
        ?.takeUnless { it.equals(Build.MODEL, ignoreCase = true) }
        ?.takeUnless { it.equals(Build.DEVICE, ignoreCase = true) }
        ?.takeUnless { it.equals(Build.PRODUCT, ignoreCase = true) }
    val suffix = when {
        option.type == AudioDeviceInfo.TYPE_BUILTIN_MIC && !option.address.isNullOrBlank() -> option.address
        customName != null && customName != baseLabel -> customName
        else -> null
    }
    return if (suffix != null) "$baseLabel (${suffix})" else baseLabel
}

@Composable
private fun videoAspectRatioLabel(aspectRatio: VideoAspectRatio): String {
    return when (aspectRatio) {
        VideoAspectRatio.RATIO_16_9 -> stringResource(R.string.video_aspect_16_9)
        VideoAspectRatio.RATIO_21_9 -> stringResource(R.string.video_aspect_21_9)
        VideoAspectRatio.OPEN_GATE -> stringResource(R.string.video_aspect_open_gate)
    }
}

@Composable
private fun videoLogProfileLabel(profile: VideoLogProfile): String {
    return when (profile) {
        VideoLogProfile.OFF -> stringResource(R.string.video_log_off)
        else -> profile.displayName
    }
}

@Composable
private fun SectionLabel(title: String) {
    Text(
        text = title,
        color = Color.White.copy(alpha = 0.6f),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun MeteringModeQuickSetting(
    meteringMode: MeteringMode,
    onMeteringModeChange: (MeteringMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val meteringLabel = when (meteringMode) {
        MeteringMode.SPOT -> stringResource(R.string.metering_spot)
        MeteringMode.CENTER_WEIGHTED -> stringResource(R.string.metering_center_weighted)
        MeteringMode.SYSTEM_DEFAULT -> stringResource(R.string.metering_system_default)
        MeteringMode.AVERAGE -> stringResource(R.string.metering_average)
        MeteringMode.HIGHLIGHT_PRIORITY -> stringResource(R.string.metering_highlight_priority)
    }
    QuickSettingValue(
        title = stringResource(R.string.metering_mode),
        value = meteringLabel,
        onClick = {
            val next = when (meteringMode) {
                MeteringMode.SPOT -> MeteringMode.SYSTEM_DEFAULT
                MeteringMode.SYSTEM_DEFAULT -> MeteringMode.CENTER_WEIGHTED
                MeteringMode.CENTER_WEIGHTED -> MeteringMode.AVERAGE
                MeteringMode.AVERAGE -> MeteringMode.HIGHLIGHT_PRIORITY
                MeteringMode.HIGHLIGHT_PRIORITY -> MeteringMode.SPOT
            }
            onMeteringModeChange(next)
        },
        modifier = modifier
    )
}

@Composable
private fun ToolboxQuickSetting(
    onToolboxClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    QuickSettingButton(
        title = stringResource(R.string.toolbox_title),
        icon = AppIcons.Palette,
        onClick = onToolboxClick,
        modifier = modifier
    )
}

@Composable
private fun ContentManagementQuickSetting(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    QuickSettingButton(
        title = stringResource(R.string.settings_section_management),
        icon = AppIcons.Tune,
        onClick = onClick,
        modifier = modifier
    )
}

private val CameraMenuBackground = Color(0xFF111418)
private val CameraMenuCard = Color(0x5520242A)
private val CameraMenuRaisedCard = Color(0x77282D35)

private val VideoMenuBackground = Color(0xFF0F0F13)
private val VideoMenuCard = Color(0x55242429)
private val VideoMenuRaisedCard = Color(0x77303036)


@Suppress("UNUSED_PARAMETER")
private fun Modifier.menuCardSurface(
    shape: RoundedCornerShape = RoundedCornerShape(9.dp),
    background: Color = CameraMenuCard,
    elevation: Dp = 3.dp
): Modifier = this
    .clip(shape)
    // Menu tiles stay visually transparent; only the subtle outline separates them.
    .border(0.75.dp, Color.White.copy(alpha = 0.13f), shape)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickSettingGrid(
    columns: Int = 3,
    itemHeight: Dp = 36.dp,
    horizontalSpacing: Dp = 8.dp,
    verticalSpacing: Dp = 6.dp,
    content: @Composable (Modifier) -> Unit
) {
    // Keep a consistent column count as the available photo controls change.
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
        maxItemsInEachRow = columns
    ) {
        content(Modifier.weight(1f).height(itemHeight))
    }
}

@Composable
private fun QuickSettingLabel(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Normal,
    maxFontSize: TextUnit = 10.sp,
    maxLines: Int = 2
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = LocalTextStyle.current.copy(
            color = color,
            fontWeight = fontWeight,
            fontSize = maxFontSize,
            lineHeight = 1.2.em
        ),
        maxLines = maxLines,
        autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = maxFontSize)
    )
}

@Composable
private fun ContentManagementOptionsPanel(
    visible: Boolean,
    onFilterManageClick: () -> Unit,
    onFrameManageClick: () -> Unit,
    onPresetManageClick: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        Column {
            Spacer(modifier = Modifier.height(12.dp))
            QuickSettingGrid { itemModifier ->
                QuickSettingButton(
                    title = stringResource(R.string.settings_filter_management),
                    icon = AppIcons.AutoAwesome,
                    onClick = onFilterManageClick,
                    modifier = itemModifier
                )
                QuickSettingButton(
                    title = stringResource(R.string.settings_frame_management),
                    icon = AppIcons.BorderBottom,
                    onClick = onFrameManageClick,
                    modifier = itemModifier
                )
                QuickSettingButton(
                    title = stringResource(R.string.settings_preset_management),
                    icon = AppIcons.Bookmark,
                    onClick = onPresetManageClick,
                    modifier = itemModifier
                )
            }
        }
    }
}

@Composable
fun QuickSettingValue(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .menuCardSurface(shape = RoundedCornerShape(9.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 8.sp,
                lineHeight = 10.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
            )
            QuickSettingLabel(
                text = value,
                color = Color.White,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun QuickSettingButton(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .menuCardSurface(shape = RoundedCornerShape(9.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QuickSettingLabel(
                text = title,
                color = Color.White,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}



@Composable
fun QuickSettingButton2(
    title: String,
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .then(
                if (checked) Modifier.clip(RoundedCornerShape(9.dp)).background(AccentColor)
                else Modifier.menuCardSurface(shape = RoundedCornerShape(9.dp))
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QuickSettingLabel(
                text = title,
                color = if (checked) OnAccentColor else Color.White.copy(alpha = 0.9f),
                fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = AppIcons.ChevronRight,
                contentDescription = null,
                tint = if (checked) OnAccentColor else Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
fun QuickSettingToggle(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val contentAlpha = if (enabled) 1f else 0.38f
    Box(
        modifier = modifier
            .fillMaxHeight()
            .then(
                if (checked) Modifier.clip(RoundedCornerShape(9.dp)).background(AccentColor.copy(alpha = contentAlpha))
                else Modifier.menuCardSurface(shape = RoundedCornerShape(9.dp))
            )
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QuickSettingLabel(
                text = title,
                color = if (checked) {
                    OnAccentColor.copy(alpha = contentAlpha)
                } else {
                    Color.White.copy(alpha = 0.9f * contentAlpha)
                },
                fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )

            // Simple indicator
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(
                        if (checked) {
                            OnAccentColor.copy(alpha = contentAlpha)
                        } else {
                            Color.White.copy(alpha = 0.2f * contentAlpha)
                        }
                    )
            )
        }
    }
}
