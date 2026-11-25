package com.hinnka.mycamera.ui.camera

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import androidx.compose.animation.core.CubicBezierEasing

internal const val ZoomStopAnimationDurationMillis = 400
internal const val NormalVideoUltraWideHandoffZoomRatio = 0.6f

/**
 * Returns true only for the normal-video handoff between the main 1x lens and
 * the user's 0.6x ultra-wide stop. Other lens/zoom transitions keep their
 * existing continuous zoom animation path.
 */
internal fun shouldUseNormalVideoUltraWideHandoffAnimation(
    currentLensIntrinsicZoomRatio: Float?,
    targetLensIntrinsicZoomRatio: Float?,
    targetZoomRatio: Float
): Boolean {
    val currentIntrinsic = currentLensIntrinsicZoomRatio
        ?.takeIf { it.isFinite() && it > 0f } ?: return false
    val targetIntrinsic = targetLensIntrinsicZoomRatio
        ?.takeIf { it.isFinite() && it > 0f } ?: return false
    if (!targetZoomRatio.isFinite() || targetZoomRatio <= 0f) return false

    val currentIsUltraWide = currentIntrinsic in 0.2f..0.85f
    val targetIsUltraWide = targetIntrinsic in 0.2f..0.85f
    val currentIsMain = abs(currentIntrinsic - 1f) <= 0.15f
    val targetIsMain = abs(targetIntrinsic - 1f) <= 0.15f
    val targetIsUltraWideStop =
        abs(targetZoomRatio - NormalVideoUltraWideHandoffZoomRatio) <= 0.05f
    val targetIsMainStop = abs(targetZoomRatio - 1f) <= 0.05f

    return (currentIsMain && targetIsUltraWide && targetIsUltraWideStop) ||
        (currentIsUltraWide && targetIsMain && targetIsMainStop)
}

/** Scale the standby ultra-wide preview until it matches the main 1x field of view. */
internal fun normalVideoUltraWideHandoffPreviewScale(
    ultraWideIntrinsicZoomRatio: Float?
): Float {
    val safeIntrinsic = ultraWideIntrinsicZoomRatio
        ?.takeIf { it.isFinite() && it in 0.2f..0.85f }
        ?: NormalVideoUltraWideHandoffZoomRatio
    return (1f / safeIntrinsic).coerceIn(1f, 5f)
}
internal val ZoomStopAnimationEasing = CubicBezierEasing(0.4f, 0f, 0.6f, 1f)

internal fun interpolateZoomRatio(
    startZoom: Float,
    targetZoom: Float,
    progress: Float
): Float {
    require(startZoom.isFinite() && startZoom > 0f)
    require(targetZoom.isFinite() && targetZoom > 0f)
    val fraction = progress.coerceIn(0f, 1f)
    val startLog = ln(startZoom.toDouble())
    val targetLog = ln(targetZoom.toDouble())
    return exp(startLog + (targetLog - startLog) * fraction).toFloat()
}

internal fun resolveZoomStopAnimationDurationMillis(
    startZoom: Float,
    targetZoom: Float
): Int {
    require(startZoom.isFinite() && startZoom > 0f)
    require(targetZoom.isFinite() && targetZoom > 0f)
    return ZoomStopAnimationDurationMillis
}
