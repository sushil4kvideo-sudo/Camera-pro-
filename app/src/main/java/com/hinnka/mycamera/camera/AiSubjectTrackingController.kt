package com.hinnka.mycamera.camera

import kotlin.math.abs

/**
 * Converts a detected subject's normalized viewfinder position into small, bounded
 * Top-Off crop corrections. A dead zone prevents sensor/inference jitter from making
 * the camera continuously drift when the subject is already framed.
 */
internal class AiSubjectTrackingController(
    private val deadZone: Float = 0.045f,
    private val proportionalGain: Float = 0.12f,
    private val smoothingAlpha: Float = 0.55f,
    private val maxStepPerDetection: Float = 0.035f,
) {
    data class PanUpdate(
        val panX: Float,
        val panY: Float,
        val changed: Boolean,
    )

    private var filteredErrorX = 0f
    private var filteredErrorY = 0f

    fun update(
        subjectCenterX: Float,
        subjectCenterY: Float,
        currentPanX: Float,
        currentPanY: Float,
    ): PanUpdate {
        if (
            !subjectCenterX.isFinite() ||
            !subjectCenterY.isFinite() ||
            !currentPanX.isFinite() ||
            !currentPanY.isFinite()
        ) {
            return PanUpdate(
                panX = currentPanX.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f,
                panY = currentPanY.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f,
                changed = false,
            )
        }

        val errorX = deadZoneAdjustedError(subjectCenterX - 0.5f)
        val errorY = deadZoneAdjustedError(subjectCenterY - 0.5f)
        filteredErrorX = filter(errorX, filteredErrorX)
        filteredErrorY = filter(errorY, filteredErrorY)

        val stepX = (filteredErrorX * proportionalGain)
            .coerceIn(-maxStepPerDetection, maxStepPerDetection)
        val stepY = (filteredErrorY * proportionalGain)
            .coerceIn(-maxStepPerDetection, maxStepPerDetection)
        val nextPanX = (currentPanX + stepX).coerceIn(-1f, 1f)
        val nextPanY = (currentPanY + stepY).coerceIn(-1f, 1f)

        return PanUpdate(
            panX = nextPanX,
            panY = nextPanY,
            changed = abs(nextPanX - currentPanX) > 0.0005f ||
                abs(nextPanY - currentPanY) > 0.0005f,
        )
    }

    fun reset() {
        filteredErrorX = 0f
        filteredErrorY = 0f
    }

    private fun deadZoneAdjustedError(error: Float): Float {
        val magnitude = abs(error)
        if (magnitude <= deadZone) return 0f
        return if (error < 0f) -(magnitude - deadZone) else magnitude - deadZone
    }

    private fun filter(input: Float, previous: Float): Float {
        if (input == 0f) return 0f
        return previous + (input - previous) * smoothingAlpha
    }
}
