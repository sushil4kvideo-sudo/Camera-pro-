package com.hinnka.mycamera.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Compact four-direction crop trackpad, styled to sit beside the shutter.
 * Its thumb returns to centre on release while the selected camera framing stays put.
 */
@Composable
internal fun ZoomPanJoystick(
    modifier: Modifier = Modifier,
    onPanBy: (Float, Float) -> Unit,
    speedMultiplier: Float = 1f,
    onLongPress: (() -> Unit)? = null,
) {
    val density = LocalDensity.current
    val maxTravelPx = with(density) { 19.dp.toPx() }
    val cornerRadiusPx = with(density) { 17.dp.toPx() }
    val dotSpacingPx = with(density) { 8.5.dp.toPx() }
    val dotRadiusPx = with(density) { 0.75.dp.toPx() }
    var knobOffset by remember { mutableStateOf(Offset.Zero) }
    var isDragging by remember { mutableStateOf(false) }
    val currentOnPanBy by rememberUpdatedState(onPanBy)
    val currentSpeedMultiplier by rememberUpdatedState(speedMultiplier.coerceIn(0.25f, 4f))
    val currentOnLongPress by rememberUpdatedState(onLongPress)

    // Keep panning while the thumb is held off-centre, rather than only while the finger moves.
    LaunchedEffect(isDragging) {
        while (isDragging) {
            val x = (knobOffset.x / maxTravelPx).coerceIn(-1f, 1f)
            val y = (knobOffset.y / maxTravelPx).coerceIn(-1f, 1f)
            if (abs(x) > 0.04f || abs(y) > 0.04f) {
                // Compose pointer Y grows downward; invert it so thumb-up moves the preview up.
                currentOnPanBy(x * 0.006f * currentSpeedMultiplier, -y * 0.006f * currentSpeedMultiplier)
            }
            delay(16L)
        }
    }

    val padShape = RoundedCornerShape(17.dp)
    Box(
        modifier = modifier
            .size(76.dp)
            .clip(padShape)
            .drawBehind {
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF1D4742),
                            Color(0xFF1B3036),
                            Color(0xFF512438),
                        ),
                        start = Offset(0f, size.height),
                        end = Offset(size.width, 0f),
                    ),
                    cornerRadius = CornerRadius(cornerRadiusPx),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFFF3F60).copy(alpha = 0.58f),
                            Color(0xFFDD3455).copy(alpha = 0.16f),
                            Color.Transparent,
                        ),
                        center = Offset(size.width * 0.91f, size.height * 0.08f),
                        radius = size.width * 0.72f,
                    ),
                    radius = size.width * 0.72f,
                    center = Offset(size.width * 0.91f, size.height * 0.08f),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF65C5A4).copy(alpha = 0.34f),
                            Color(0xFF2E776C).copy(alpha = 0.12f),
                            Color.Transparent,
                        ),
                        center = Offset(size.width * 0.08f, size.height * 0.88f),
                        radius = size.width * 0.76f,
                    ),
                    radius = size.width * 0.76f,
                    center = Offset(size.width * 0.08f, size.height * 0.88f),
                )

                var y = dotSpacingPx
                while (y < size.height - dotSpacingPx * 0.6f) {
                    var x = dotSpacingPx
                    while (x < size.width - dotSpacingPx * 0.6f) {
                        drawCircle(
                            color = Color.White.copy(alpha = 0.54f),
                            radius = dotRadiusPx,
                            center = Offset(x, y),
                        )
                        x += dotSpacingPx
                    }
                    y += dotSpacingPx
                }
            }
            .border(1.dp, Color.White.copy(alpha = 0.46f), padShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { currentOnLongPress?.invoke() },
                )
            }
            .pointerInput(maxTravelPx) {
                fun clampOffset(offset: Offset): Offset {
                    val distance = hypot(offset.x, offset.y)
                    if (distance <= maxTravelPx || distance == 0f) return offset
                    val scale = maxTravelPx / distance
                    return Offset(offset.x * scale, offset.y * scale)
                }

                detectDragGestures(
                    onDragStart = { start ->
                        knobOffset = clampOffset(start - Offset(size.width / 2f, size.height / 2f))
                        isDragging = true
                    },
                    onDragEnd = {
                        isDragging = false
                        knobOffset = Offset.Zero
                    },
                    onDragCancel = {
                        isDragging = false
                        knobOffset = Offset.Zero
                    },
                ) { change, dragAmount ->
                    change.consume()
                    knobOffset = clampOffset(knobOffset + dragAmount)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Layered translucent rings recreate the small glass/metal control in the reference.
        Box(
            modifier = Modifier
                .size(35.dp)
                .offset { IntOffset(knobOffset.x.roundToInt(), knobOffset.y.roundToInt()) }
                .background(Color.White.copy(alpha = 0.16f), CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.48f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFF7FBFA),
                                Color(0xFFA9B6B6),
                                Color(0xFF556366),
                            ),
                        ),
                        CircleShape,
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.86f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(Color.White, Color(0xFFE1E9E8), Color(0xFFB6C4C3)),
                            ),
                            CircleShape,
                        )
                        .border(0.8.dp, Color.White.copy(alpha = 0.92f), CircleShape),
                )
            }
        }
    }
}
