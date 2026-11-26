package com.hinnka.mycamera.ui.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinnka.mycamera.camera.ZoomCropGeometry
import com.hinnka.mycamera.ui.components.OppoCameraGlassSurface
import com.hinnka.mycamera.video.VideoAspectRatio
import com.hinnka.mycamera.video.VideoCapabilities
import com.hinnka.mycamera.video.VideoConfig
import com.hinnka.mycamera.video.VideoFpsPreset
import com.hinnka.mycamera.video.VideoResolutionPreset

private val VideoSelectorGold = Color(0xFFFFC107)
private val VideoSelectorLabel = Color.White.copy(alpha = 0.72f)
private val VideoSelectorText = Color.White
private val VideoSelectorDescription = Color.White.copy(alpha = 0.85f)

/**
 * OPPO Cam-style floating video selector. Choices are limited to modes supported
 * by the active camera. The frosted material reuses the current GL preview; it
 * does not open a second camera stream or create a separate preview renderer.
 */
@Composable
fun FloatingVideoSettingsPanel(
    isOpen: Boolean,
    videoConfig: VideoConfig,
    videoCapabilities: VideoCapabilities,
    onResolutionSelected: (VideoResolutionPreset) -> Unit,
    onFpsSelected: (VideoFpsPreset) -> Unit,
    virtualTelephotoEnabled: Boolean,
    onVirtualTelephotoEnabledChange: (Boolean) -> Unit,
    topOffZoomAvailable: Boolean,
    topOffZoomEnabled: Boolean,
    topOffZoomFactor: Float,
    effectiveZoomRatio: Float,
    baseZoomRatio: Float,
    sensorAspectRatio: Float,
    cropAspectRatio: Float,
    topOffZoomPanX: Float,
    topOffZoomPanY: Float,
    onTopOffZoomEnabledChange: (Boolean) -> Unit,
    onTopOffZoomFactorChange: (Float) -> Unit,
    onTopOffZoomPanChange: (Float, Float) -> Unit,
    onDismiss: () -> Unit,
    glassTransparency: Float,
    glassBlurStrength: Float,
    onGlassBoundsChanged: (Rect?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!isOpen) return

    val resolutionOptions = videoCapabilities.availableResolutions
    val fpsOptions = videoCapabilities.availableFpsByResolution[videoConfig.resolution]
        ?.takeIf { it.isNotEmpty() }
        ?: videoCapabilities.availableFps
    val visibleFpsOptions = fpsOptions.ifEmpty { listOf(videoConfig.fps) }
    val description = when (videoConfig.resolution) {
        VideoResolutionPreset.UHD_2160P -> "Ultra HD resolution"
        VideoResolutionPreset.THREE_K -> "3K resolution"
        VideoResolutionPreset.QHD_1440P -> "2K QHD resolution"
        VideoResolutionPreset.FHD_1080P -> "Full HD resolution"
        VideoResolutionPreset.HD_720P -> "720p HD resolution"
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            AnimatedVisibility(
                visible = isOpen,
                enter = fadeIn(tween(170)) +
                    scaleIn(
                        initialScale = 0.78f,
                        animationSpec = spring(dampingRatio = 0.52f, stiffness = 430f),
                    ) +
                    expandVertically(
                        expandFrom = Alignment.Top,
                        animationSpec = spring(dampingRatio = 0.68f, stiffness = 380f),
                    ) +
                    slideInVertically(
                        initialOffsetY = { -it / 5 },
                        animationSpec = spring(dampingRatio = 0.68f, stiffness = 380f),
                    ),
                exit = fadeOut(tween(120)) +
                    scaleOut(targetScale = 0.94f, animationSpec = tween(120)) +
                    shrinkVertically(
                        shrinkTowards = Alignment.Top,
                        animationSpec = tween(130),
                    ),
            ) {
                OppoCameraGlassSurface(
                    modifier = Modifier
                        .widthIn(min = 300.dp, max = 340.dp)
                        .padding(horizontal = 12.dp)
                        .testTag("floating_video_settings_panel"),
                    transparency = glassTransparency,
                    blurStrength = glassBlurStrength,
                    onBoundsChanged = onGlassBoundsChanged,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Size",
                                color = VideoSelectorLabel,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.width(46.dp),
                            )
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceEvenly,
                            ) {
                                if (videoConfig.aspectRatio == VideoAspectRatio.OPEN_GATE ||
                                    resolutionOptions.isEmpty()
                                ) {
                                    Text(
                                        text = if (videoConfig.aspectRatio == VideoAspectRatio.OPEN_GATE) "MAX" else "—",
                                        color = VideoSelectorText,
                                        fontSize = 14.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                } else {
                                    resolutionOptions.forEach { resolution ->
                                        val selected = resolution == videoConfig.resolution
                                        val label = when (resolution) {
                                            VideoResolutionPreset.UHD_2160P -> "UHD"
                                            VideoResolutionPreset.THREE_K -> "3K"
                                            VideoResolutionPreset.QHD_1440P -> "2K"
                                            VideoResolutionPreset.FHD_1080P -> "FHD"
                                            VideoResolutionPreset.HD_720P -> "720p"
                                        }
                                        Box(
                                            modifier = Modifier.weight(1f),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = label,
                                                color = if (selected) VideoSelectorGold else VideoSelectorText,
                                                fontSize = 14.5.sp,
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                                letterSpacing = 0.2.sp,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable { onResolutionSelected(resolution) }
                                                    .padding(horizontal = 6.dp, vertical = 5.dp)
                                                    .testTag("video_resolution_$label"),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "FPS",
                                color = VideoSelectorLabel,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.width(46.dp),
                            )
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceEvenly,
                            ) {
                                visibleFpsOptions.forEach { fps ->
                                    val selected = fps == videoConfig.fps
                                    Box(
                                        modifier = Modifier.weight(1f),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = fps.fps.toString(),
                                            color = if (selected) VideoSelectorGold else VideoSelectorText,
                                            fontSize = 14.5.sp,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                            letterSpacing = 0.2.sp,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable { onFpsSelected(fps) }
                                                .padding(horizontal = 6.dp, vertical = 5.dp)
                                                .testTag("video_fps_" + fps.fps),
                                        )
                                    }
                                }
                                repeat((4 - visibleFpsOptions.size).coerceAtLeast(0)) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = description,
                            color = VideoSelectorDescription,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Normal,
                            letterSpacing = 0.2.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("video_resolution_description"),
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("special_video_virtual_telephoto_row"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = "Virtual telephoto",
                                    color = VideoSelectorText,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "Include the 3.5× lens in Special Video",
                                    color = VideoSelectorDescription,
                                    fontSize = 11.5.sp,
                                )
                            }
                            Switch(
                                checked = virtualTelephotoEnabled,
                                onCheckedChange = onVirtualTelephotoEnabledChange,
                                modifier = Modifier.testTag("special_video_virtual_telephoto_toggle"),
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = VideoSelectorGold,
                                    checkedTrackColor = VideoSelectorGold.copy(alpha = 0.34f),
                                    uncheckedThumbColor = Color.White.copy(alpha = 0.78f),
                                    uncheckedTrackColor = Color.White.copy(alpha = 0.18f),
                                ),
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        if (topOffZoomAvailable) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("top_off_zoom_row"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = "Top Off Zoom",
                                        color = VideoSelectorText,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = "Move the sensor crop without changing magnification",
                                        color = VideoSelectorDescription,
                                        fontSize = 11.5.sp,
                                    )
                                }
                                Switch(
                                    checked = topOffZoomEnabled,
                                    onCheckedChange = onTopOffZoomEnabledChange,
                                    modifier = Modifier.testTag("top_off_zoom_toggle"),
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = VideoSelectorGold,
                                        checkedTrackColor = VideoSelectorGold.copy(alpha = 0.34f),
                                        uncheckedThumbColor = Color.White.copy(alpha = 0.78f),
                                        uncheckedTrackColor = Color.White.copy(alpha = 0.18f),
                                    ),
                                )
                            }
                            if (topOffZoomEnabled) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "Zoom value",
                                        color = VideoSelectorLabel,
                                        fontSize = 12.5.sp,
                                    )
                                    Text(
                                        text = String.format(java.util.Locale.US, "%.1fx", topOffZoomFactor),
                                        color = VideoSelectorGold,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                                Slider(
                                    value = topOffZoomFactor.coerceIn(1f, 4f),
                                    onValueChange = onTopOffZoomFactorChange,
                                    valueRange = 1f..4f,
                                    steps = 5,
                                    modifier = Modifier.testTag("top_off_zoom_factor_slider"),
                                )
                                Text(
                                    text = "Sensor crop position",
                                    color = VideoSelectorLabel,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                if (baseZoomRatio >= 1f) {
                                    TopOffZoomCropEditor(
                                        zoomFactor = effectiveZoomRatio,
                                        panX = topOffZoomPanX,
                                        panY = topOffZoomPanY,
                                        sensorAspectRatio = sensorAspectRatio,
                                        cropAspectRatio = cropAspectRatio,
                                        onPanChange = onTopOffZoomPanChange,
                                    )
                                } else {
                                    Text(
                                        text = "Crop positioning is available from 1x and above.",
                                        color = VideoSelectorDescription,
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = "Top Off Zoom is unavailable while logical multi-camera, Special Video, or Dual Capture is active.",
                                color = VideoSelectorDescription,
                                fontSize = 11.5.sp,
                                modifier = Modifier.testTag("top_off_zoom_unavailable"),
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun TopOffZoomCropEditor(
    zoomFactor: Float,
    panX: Float,
    panY: Float,
    sensorAspectRatio: Float,
    cropAspectRatio: Float,
    onPanChange: (Float, Float) -> Unit,
) {
    val density = LocalDensity.current
    val currentPanX = rememberUpdatedState(panX.coerceIn(-1f, 1f))
    val currentPanY = rememberUpdatedState(panY.coerceIn(-1f, 1f))
    val currentOnPanChange = rememberUpdatedState(onPanChange)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(sensorAspectRatio.coerceIn(0.1f, 1f))
                .clip(RoundedCornerShape(9.dp))
                .background(Color(0xFF11151B))
                .border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(9.dp))
                .testTag("top_off_zoom_crop_frame"),
        ) {
            val crop = ZoomCropGeometry.calculate(
                sourceWidth = maxWidth.value,
                sourceHeight = maxHeight.value,
                outputAspectRatio = cropAspectRatio,
                zoomRatio = zoomFactor,
                panX = panX,
                panY = panY,
            )
            val cropWidth = maxWidth * crop.width
            val cropHeight = maxHeight * crop.height
            val cropOffsetX = maxWidth * crop.left
            val cropOffsetY = maxHeight * crop.top
            val maxTravelX = maxWidth * ((1f - crop.width).coerceAtLeast(0f) / 2f)
            val maxTravelY = maxHeight * ((1f - crop.height).coerceAtLeast(0f) / 2f)
            val latestTravelX = rememberUpdatedState(maxTravelX)
            val latestTravelY = rememberUpdatedState(maxTravelY)
            // Keep drag coordinates in the stationary sensor-frame coordinate space.
            // The crop box itself is offset every time pan state changes; attaching
            // pointerInput to that moving child rebases pointer coordinates during the
            // same gesture and can cancel/undo the requested pan.
            val currentCropBounds = rememberUpdatedState(
                Rect(
                    left = with(density) { cropOffsetX.toPx() },
                    top = with(density) { cropOffsetY.toPx() },
                    right = with(density) { (cropOffsetX + cropWidth).toPx() },
                    bottom = with(density) { (cropOffsetY + cropHeight).toPx() },
                )
            )
            var isCropDragging by remember { androidx.compose.runtime.mutableStateOf(false) }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(maxWidth, maxHeight) {
                        detectDragGestures(
                            onDragStart = { start ->
                                val bounds = currentCropBounds.value
                                val hitSlop = with(density) { 16.dp.toPx() }
                                isCropDragging =
                                    start.x >= bounds.left - hitSlop &&
                                    start.x <= bounds.right + hitSlop &&
                                    start.y >= bounds.top - hitSlop &&
                                    start.y <= bounds.bottom + hitSlop
                            },
                            onDragEnd = { isCropDragging = false },
                            onDragCancel = { isCropDragging = false },
                        ) { change, dragAmount ->
                            if (!isCropDragging) return@detectDragGestures
                            change.consume()
                            val travelXpx = with(density) { latestTravelX.value.toPx() }
                            val travelYpx = with(density) { latestTravelY.value.toPx() }
                            val nextPanX = if (travelXpx > 0f) {
                                (currentPanX.value + dragAmount.x / travelXpx).coerceIn(-1f, 1f)
                            } else {
                                0f
                            }
                            val nextPanY = if (travelYpx > 0f) {
                                (currentPanY.value + dragAmount.y / travelYpx).coerceIn(-1f, 1f)
                            } else {
                                0f
                            }
                            currentOnPanChange.value(nextPanX, nextPanY)
                        }
                    },
                contentAlignment = Alignment.TopStart,
            ) {
                Box(
                    modifier = Modifier
                        .offset(x = cropOffsetX, y = cropOffsetY)
                        .size(width = cropWidth, height = cropHeight)
                        .background(VideoSelectorGold.copy(alpha = 0.12f))
                        .border(2.dp, VideoSelectorGold, RoundedCornerShape(3.dp))
                        .testTag("top_off_zoom_crop_box"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "CROP",
                        color = VideoSelectorGold,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        Text(
            text = "3:4 sensor area · inner box follows the selected video aspect ratio.",
            color = VideoSelectorDescription,
            fontSize = 11.sp,
            textAlign = TextAlign.Start,
        )
    }
}
