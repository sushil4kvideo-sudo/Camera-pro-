package com.hinnka.mycamera.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinnka.mycamera.preview.PreviewSubjectTrackingProcessor

private val TrackingAccent = Color(0xFF43E68A)

@Composable
internal fun SubjectTrackingOverlay(
    target: PreviewSubjectTrackingProcessor.Target?,
    modifier: Modifier = Modifier,
) {
    val current = target ?: return
    BoxWithConstraints(modifier = modifier) {
        Canvas(Modifier.matchParentSize()) {
            val left = current.left.coerceIn(0f, 1f) * size.width
            val top = current.top.coerceIn(0f, 1f) * size.height
            val right = current.right.coerceIn(0f, 1f) * size.width
            val bottom = current.bottom.coerceIn(0f, 1f) * size.height
            if (right <= left || bottom <= top) return@Canvas
            drawRoundRect(
                color = TrackingAccent,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                cornerRadius = CornerRadius(11.dp.toPx()),
                style = Stroke(width = 2.dp.toPx()),
            )
            val corner = 13.dp.toPx()
            val stroke = 3.dp.toPx()
            // Short corner accents keep the tracked target readable over bright scenes.
            drawLine(TrackingAccent, Offset(left, top + corner), Offset(left, top), stroke)
            drawLine(TrackingAccent, Offset(left, top), Offset(left + corner, top), stroke)
            drawLine(TrackingAccent, Offset(right - corner, top), Offset(right, top), stroke)
            drawLine(TrackingAccent, Offset(right, top), Offset(right, top + corner), stroke)
            drawLine(TrackingAccent, Offset(left, bottom - corner), Offset(left, bottom), stroke)
            drawLine(TrackingAccent, Offset(left, bottom), Offset(left + corner, bottom), stroke)
            drawLine(TrackingAccent, Offset(right - corner, bottom), Offset(right, bottom), stroke)
            drawLine(TrackingAccent, Offset(right, bottom), Offset(right, bottom - corner), stroke)
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(
                    start = maxWidth * current.left.coerceIn(0f, 1f),
                    top = (maxHeight * current.top.coerceIn(0f, 1f) - 25.dp).coerceAtLeast(0.dp),
                )
                .clip(RoundedCornerShape(6.dp))
                .background(TrackingAccent)
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${current.label.uppercase()} ${(current.confidence * 100).toInt()}%",
                color = Color(0xFF092015),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
internal fun AiSubjectTrackingButton(
    enabled: Boolean,
    hasTarget: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(if (enabled) Color(0xDD173D2A) else Color(0xCC171717))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(
                    when {
                        hasTarget -> TrackingAccent
                        enabled -> Color(0xFFFFC857)
                        else -> Color.White.copy(alpha = 0.65f)
                    }
                ),
        )
        Text(
            text = when {
                enabled && hasTarget -> "AI TRACKING"
                enabled -> "AI SEARCHING"
                else -> "AI TRACK"
            },
            modifier = Modifier.padding(start = 7.dp),
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
