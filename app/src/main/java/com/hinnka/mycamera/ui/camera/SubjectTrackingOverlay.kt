package com.hinnka.mycamera.ui.camera

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.hinnka.mycamera.preview.PreviewSubjectTrackingProcessor

private val TrackingAccent = Color(0xFF43E68A)

@Composable
internal fun SubjectTrackingOverlay(
    target: PreviewSubjectTrackingProcessor.Target?,
    modifier: Modifier = Modifier,
) {
    val current = target ?: return
    // MediaPipe delivers detector boxes asynchronously. Interpolate every edge so the
    // green frame visually follows the subject instead of jumping between inference frames.
    val animation = spring<Float>(dampingRatio = 0.9f, stiffness = 520f)
    val left by animateFloatAsState(current.left.coerceIn(0f, 1f), animationSpec = animation)
    val top by animateFloatAsState(current.top.coerceIn(0f, 1f), animationSpec = animation)
    val right by animateFloatAsState(current.right.coerceIn(0f, 1f), animationSpec = animation)
    val bottom by animateFloatAsState(current.bottom.coerceIn(0f, 1f), animationSpec = animation)

    Canvas(modifier = modifier) {
        val l = left * size.width
        val t = top * size.height
        val r = right * size.width
        val b = bottom * size.height
        if (r <= l || b <= t) return@Canvas

        drawRoundRect(
            color = TrackingAccent,
            topLeft = Offset(l, t),
            size = Size(r - l, b - t),
            cornerRadius = CornerRadius(11.dp.toPx()),
            style = Stroke(width = 2.dp.toPx()),
        )

        // Brighter corner accents keep the subject lock visible without a text label.
        val corner = 13.dp.toPx()
        val stroke = 3.dp.toPx()
        drawLine(TrackingAccent, Offset(l, t + corner), Offset(l, t), stroke)
        drawLine(TrackingAccent, Offset(l, t), Offset(l + corner, t), stroke)
        drawLine(TrackingAccent, Offset(r - corner, t), Offset(r, t), stroke)
        drawLine(TrackingAccent, Offset(r, t), Offset(r, t + corner), stroke)
        drawLine(TrackingAccent, Offset(l, b - corner), Offset(l, b), stroke)
        drawLine(TrackingAccent, Offset(l, b), Offset(l + corner, b), stroke)
        drawLine(TrackingAccent, Offset(r - corner, b), Offset(r, b), stroke)
        drawLine(TrackingAccent, Offset(r, b), Offset(r, b - corner), stroke)
    }
}
