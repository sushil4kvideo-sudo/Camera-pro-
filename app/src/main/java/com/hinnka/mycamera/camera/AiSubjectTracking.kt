package com.hinnka.mycamera.camera

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

/** Detector classes used by the on-device AI tracker. */
enum class AiSubjectTrackingMode(val title: String) {
    HUMAN("Human"),
    ANY_OBJECT("Any object"),
}

/** Responsiveness of the automatic crop controller. */
enum class AiTrackingFollowSpeed(val title: String, val smoothingAlpha: Float) {
    SLOW("Slow", 0.28f),
    NORMAL("Normal", 0.52f),
    FAST("Fast", 0.78f),
}

/** One detector result in normalized viewfinder coordinates. */
data class AiSubjectTrackingTarget(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val category: String,
    val confidence: Float,
    val isFresh: Boolean = true,
    val isLocked: Boolean = false,
) {
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f
}

/** Absolute crop correction for the existing Top-Off zoom crop. */
data class AiSubjectTrackingPanCorrection(
    val panX: Float,
    val panY: Float,
)

/**
 * Converts a normalized subject position into the absolute Top-Off crop position.
 *
 * The controller deliberately changes only pan: the currently selected zoom ratio is
 * never modified by AI tracking. The target may move inside a configurable dead zone
 * before the crop follows it, which prevents constant micro-corrections.
 */
class AiSubjectTrackingPanController {
    private var smoothedPanX = 0f
    private var smoothedPanY = 0f
    private var initialized = false

    fun reset(panX: Float = 0f, panY: Float = 0f) {
        smoothedPanX = panX.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
        smoothedPanY = panY.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
        initialized = true
    }

    fun update(
        target: AiSubjectTrackingTarget,
        zoomRatio: Float,
        followSpeed: AiTrackingFollowSpeed = AiTrackingFollowSpeed.NORMAL,
        deadZoneFraction: Float = DEFAULT_DEAD_ZONE,
    ): AiSubjectTrackingPanCorrection? {
        if (!target.isFresh) return null
        val zoom = zoomRatio.takeIf { it.isFinite() }?.coerceIn(1f, 20f) ?: 1f
        if (zoom <= MIN_TRACKING_ZOOM) return null

        val deadZone = deadZoneFraction.takeIf { it.isFinite() }?.coerceIn(0f, 0.35f)
            ?: DEFAULT_DEAD_ZONE
        val horizontalError = outsideDeadZone(target.centerX - 0.5f, deadZone)
        val verticalError = outsideDeadZone(target.centerY - 0.5f, deadZone)

        // A pan value of +/-1 moves the crop centre through half of its available travel.
        // The gain therefore compensates for the active crop's (zoom - 1) travel range.
        val gain = (2f * zoom / (zoom - 1f)).coerceIn(2f, MAX_PAN_GAIN)
        val desiredX = (horizontalError * gain).coerceIn(-1f, 1f)
        val desiredY = (verticalError * gain).coerceIn(-1f, 1f)

        if (!initialized) reset()
        val alpha = followSpeed.smoothingAlpha
        smoothedPanX += (desiredX - smoothedPanX) * alpha
        smoothedPanY += (desiredY - smoothedPanY) * alpha
        smoothedPanX = smoothedPanX.coerceIn(-1f, 1f)
        smoothedPanY = smoothedPanY.coerceIn(-1f, 1f)
        return AiSubjectTrackingPanCorrection(smoothedPanX, smoothedPanY)
    }

    private fun outsideDeadZone(error: Float, deadZone: Float): Float {
        val magnitude = abs(error)
        if (magnitude <= deadZone) return 0f
        return sign(error) * (magnitude - deadZone)
    }

    companion object {
        private const val DEFAULT_DEAD_ZONE = 0.06f
        private const val MIN_TRACKING_ZOOM = 1.001f
        private const val MAX_PAN_GAIN = 24f
    }
}
