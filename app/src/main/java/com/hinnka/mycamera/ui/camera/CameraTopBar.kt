package com.hinnka.mycamera.ui.camera

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.hinnka.mycamera.ui.theme.AccentColor
import com.hinnka.mycamera.R
import com.hinnka.mycamera.video.CaptureMode
import com.hinnka.mycamera.video.VideoAspectRatio
import com.hinnka.mycamera.video.VideoCapabilities
import com.hinnka.mycamera.video.VideoConfig
import com.hinnka.mycamera.video.VideoLogProfile
import com.hinnka.mycamera.video.VideoResolutionPreset
import com.hinnka.mycamera.ui.icons.AppIcons

@Composable
fun CameraTopBar(
    captureMode: CaptureMode,
    isRecording: Boolean,
    recordingElapsedMs: Long,
    flashMode: Int,
    onFlashToggle: () -> Unit,
    useLivePhoto: Boolean,
    onLivePhotoToggle: () -> Unit,
    useRaw: Boolean,
    rawSupported: Boolean,
    onRawToggle: () -> Unit,
    videoConfig: VideoConfig,
    videoCapabilities: VideoCapabilities,
    onVideoTorchToggle: () -> Unit,
    onVideoResolutionClick: () -> Unit,
    onVideoFpsClick: () -> Unit,
    videoHdrEnabled: Boolean,
    onVideoHdrToggle: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (captureMode == CaptureMode.VIDEO) {
        VideoTopBar(
            isRecording = isRecording,
            recordingElapsedMs = recordingElapsedMs,
            videoConfig = videoConfig,
            videoCapabilities = videoCapabilities,
            onVideoTorchToggle = onVideoTorchToggle,
            onVideoResolutionClick = onVideoResolutionClick,
            onVideoFpsClick = onVideoFpsClick,
            videoHdrEnabled = videoHdrEnabled,
            onVideoHdrToggle = onVideoHdrToggle,
            onSettingsClick = onSettingsClick,
            modifier = modifier
        )
        return
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 32.dp, start = 8.dp, end = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The left capsule is a single RAW/JPEG toggle. The small numeric label
        // mirrors the compact 48 MP indicator in the reference camera UI.
        Row(
            modifier = Modifier
                .height(36.dp)
                .clip(CircleShape)
                .background(Color(0xCC171717))
                .clickable(enabled = rawSupported, onClick = onRawToggle)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(
                    if (useRaw && rawSupported) R.string.gallery_raw_badge
                    else R.string.camera_capture_format_jpeg
                ),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = stringResource(R.string.camera_capture_resolution_12),
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 8.sp,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.padding(top = 3.dp)
            )
        }

        // One continuous capsule holds Flash, Motion Photo and the six-dot menu.
        Row(
            modifier = Modifier
                .height(36.dp)
                .background(Color(0xCC171717), CircleShape)
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TopBarActionButton(
                onClick = onFlashToggle,
                contentDescription = stringResource(R.string.flash)
            ) {
                Icon(
                    imageVector = when (flashMode) {
                        0 -> AppIcons.FlashOff
                        1 -> AppIcons.FlashOn
                        2 -> AppIcons.FlashlightOn
                        else -> AppIcons.FlashOff
                    },
                    modifier = Modifier.size(16.dp).autoRotate(),
                    contentDescription = null,
                    tint = Color.White
                )
            }

            TopBarActionButton(
                onClick = onLivePhotoToggle,
                contentDescription = stringResource(R.string.settings_use_live_photo)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_live_photo),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp).autoRotate(),
                    tint = if (useLivePhoto) AccentColor else Color.White
                )
                if (!useLivePhoto) {
                    Canvas(modifier = Modifier.size(20.dp)) {
                        drawLine(
                            color = Color.White,
                            start = Offset(size.width * 0.22f, size.height * 0.22f),
                            end = Offset(size.width * 0.78f, size.height * 0.78f),
                            strokeWidth = 1.5.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
                }
            }

            TopBarActionButton(
                onClick = onSettingsClick,
                contentDescription = stringResource(R.string.settings)
            ) {
                SixDotMenuIcon()
            }
        }
    }
}


@Composable
private fun TopBarActionButton(
    onClick: () -> Unit,
    contentDescription: String,
    content: @Composable BoxScope.() -> Unit
) {
    // Keep the original 28 dp layout footprint, but let the invisible hit target
    // extend vertically and slightly horizontally for easier tapping.
    Layout(
        modifier = Modifier.size(28.dp),
        content = {
            Box(
                modifier = Modifier
                    .size(width = 30.dp, height = 56.dp)
                    .clickable(
                        interactionSource = null,
                        indication = null,
                        role = Role.Button,
                        onClick = onClick
                    )
                    .semantics { this.contentDescription = contentDescription },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .autoRotate(),
                    contentAlignment = Alignment.Center,
                    content = content
                )
            }
        }
    ) { measurables, _ ->
        val touchWidth = 30.dp.roundToPx()
        val touchHeight = 56.dp.roundToPx()
        val visualSize = 28.dp.roundToPx()
        val touchTarget = measurables.single().measure(
            Constraints.fixed(width = touchWidth, height = touchHeight)
        )

        // The child extends outside the measured footprint; this does not change
        // the Row's spacing or the visible capsule dimensions.
        layout(visualSize, visualSize) {
            touchTarget.placeRelative(
                x = (visualSize - touchWidth) / 2,
                y = (visualSize - touchHeight) / 2
            )
        }
    }
}

@Composable
private fun SixDotMenuIcon() {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.5.dp)) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .size(2.5.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoTopBar(
    isRecording: Boolean,
    recordingElapsedMs: Long,
    videoConfig: VideoConfig,
    videoCapabilities: VideoCapabilities,
    onVideoTorchToggle: () -> Unit,
    onVideoResolutionClick: () -> Unit,
    onVideoFpsClick: () -> Unit,
    videoHdrEnabled: Boolean,
    onVideoHdrToggle: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 13.dp, start = 8.dp, end = 8.dp)
            .height(40.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (isRecording) {
                // Keep the two remaining actions anchored to the right edge.
                Spacer(modifier = Modifier.weight(1f))
            } else {
                VideoParameterCluster(
                    videoConfig = videoConfig,
                    onResolutionClick = onVideoResolutionClick,
                    onFpsClick = onVideoFpsClick
                )
            }

            // During recording, leave only Flash/Torch and HDR visible.
            Box(
                modifier = Modifier
                    .height(30.dp)
                    .background(Color(0xCC171717), CircleShape)
            ) {
                Row(
                    modifier = Modifier
                        .height(30.dp)
                        .padding(horizontal = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TopBarActionButton(
                        onClick = onVideoTorchToggle,
                        contentDescription = stringResource(R.string.video_torch_chip)
                    ) {
                        Icon(
                            imageVector = if (videoConfig.torchEnabled) AppIcons.FlashlightOn else AppIcons.FlashlightOff,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp).autoRotate(),
                            tint = when {
                                !videoCapabilities.supportsTorch -> Color.White.copy(alpha = 0.3f)
                                videoConfig.torchEnabled -> AccentColor
                                else -> Color.White
                            }
                        )
                    }

                    TopBarActionButton(
                        onClick = {
                            if (!videoConfig.logProfile.isEnabled) onVideoHdrToggle()
                        },
                        contentDescription = "HDR"
                    ) {
                        Icon(
                            imageVector = AppIcons.Contrast,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp).autoRotate(),
                            tint = when {
                                videoConfig.logProfile.isEnabled -> Color.White.copy(alpha = 0.3f)
                                videoHdrEnabled -> AccentColor
                                else -> Color.White
                            }
                        )
                    }

                    if (!isRecording) {
                        TopBarActionButton(
                            onClick = onSettingsClick,
                            contentDescription = stringResource(R.string.settings)
                        ) {
                            SixDotMenuIcon()
                        }
                    }
                }
            }
        }

        if (isRecording) {
            RecordingHud(
                elapsedMs = recordingElapsedMs,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@Composable
private fun VideoParameterCluster(
    videoConfig: VideoConfig,
    onResolutionClick: () -> Unit,
    onFpsClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .height(30.dp)
                    .background(Color(0xCC171717), CircleShape)
    ) {
        Row(
            modifier = Modifier
                .height(30.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val isOpenGate = videoConfig.aspectRatio == VideoAspectRatio.OPEN_GATE
            val resLabel = if (isOpenGate) {
                stringResource(R.string.video_resolution_max)
            } else when (videoConfig.resolution) {
                VideoResolutionPreset.UHD_2160P -> "4K"
                VideoResolutionPreset.THREE_K -> "3K"
                VideoResolutionPreset.QHD_1440P -> "2K"
                VideoResolutionPreset.FHD_1080P -> "1080p"
                VideoResolutionPreset.HD_720P -> "720p"
            }
            ParameterText(resLabel, onResolutionClick, enabled = !isOpenGate)
            ParameterText(videoConfig.fps.fps.toString(), onFpsClick)
        }
    }
}

@Composable
private fun ParameterText(
    text: String,
    onClick: () -> Unit,
    tint: Color = Color.White,
    enabled: Boolean = true
) {
    Text(
        text = text,
        color = if (enabled) tint else tint.copy(alpha = 0.4f),
        style = MaterialTheme.typography.labelLarge.copy(
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Default,
            letterSpacing = 0.sp
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

@Composable
private fun ParameterDivider() {
    Box(
        modifier = Modifier
            .size(1.dp, 12.dp)
            .background(Color.White.copy(alpha = 0.2f))
    )
}

@Composable
private fun VideoActionIcon(
    icon: ImageVector,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    contentDescription: String
) {
    IconButton(
        onClick = onClick,
        enabled = enabled
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(16.dp).autoRotate(),
            tint = if (enabled) {
                if (active) AccentColor else Color.White
            } else {
                Color.White.copy(alpha = 0.3f)
            }
        )
    }
}

@Composable
private fun RecordingHud(
    elapsedMs: Long,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "recBlink")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "recDotAlpha"
    )

    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.5f),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color.Red.copy(alpha = dotAlpha))
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = formatRecordingDuration(elapsedMs),
                color = Color.White,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.5.sp
                )
            )
        }
    }
}

private fun formatRecordingDuration(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d".format(minutes, seconds)
}
