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

/**
 * Returns true when a selected ultra-wide preview must stay active while the
 * main 1x camera takes over a zoom target above 1x.
 */
internal fun shouldUseNormalVideoUltraWideExtendedZoomHandoff(
    currentLensIntrinsicZoomRatio: Float?,
    targetLensIntrinsicZoomRatio: Float?,
    targetZoomRatio: Float,
): Boolean {
    val currentIntrinsic = currentLensIntrinsicZoomRatio
        ?.takeIf { it.isFinite() && it > 0f } ?: return false
    val targetIntrinsic = targetLensIntrinsicZoomRatio
        ?.takeIf { it.isFinite() && it > 0f } ?: return false
    if (!targetZoomRatio.isFinite() || targetZoomRatio <= 1.05f) return false

    val currentIsUltraWide = currentIntrinsic in 0.2f..0.85f
    val targetIsFurtherTelephoto = targetIntrinsic > currentIntrinsic + 0.15f
    return currentIsUltraWide && targetIsFurtherTelephoto
}

/**
 * Route a zoomed-in rear-camera -> ultra-wide tap through a continuous zoom-out animation
 * starting at the actual selected ratio. The established 1x -> 0.6x handoff is unchanged.
 */
internal fun shouldUseNormalVideoUltraWideReverseZoomHandoff(
    currentLensIntrinsicZoomRatio: Float?,
    targetLensIntrinsicZoomRatio: Float?,
    startZoomRatio: Float,
    targetZoomRatio: Float,
): Boolean {
    val currentIntrinsic = currentLensIntrinsicZoomRatio
        ?.takeIf { it.isFinite() && it > 0f } ?: return false
    val targetIntrinsic = targetLensIntrinsicZoomRatio
        ?.takeIf { it.isFinite() && it > 0f } ?: return false
    if (!startZoomRatio.isFinite() || startZoomRatio <= 1.05f ||
        !targetZoomRatio.isFinite() || targetZoomRatio !in 0.2f..0.85f
    ) return false

    return currentIntrinsic > 0.85f &&
        targetIntrinsic in 0.2f..0.85f &&
        startZoomRatio > targetZoomRatio + 0.05f
}

/** Scale the standby ultra-wide preview to match the requested main-camera field of view. */
internal fun normalVideoUltraWideHandoffPreviewScale(
    ultraWideIntrinsicZoomRatio: Float?,
    targetZoomRatio: Float = 1f,
): Float {
    val safeIntrinsic = ultraWideIntrinsicZoomRatio
        ?.takeIf { it.isFinite() && it in 0.2f..0.85f }
        ?: NormalVideoUltraWideHandoffZoomRatio
    val safeTarget = targetZoomRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
    return (safeTarget / safeIntrinsic).coerceIn(1f, 8f)
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
