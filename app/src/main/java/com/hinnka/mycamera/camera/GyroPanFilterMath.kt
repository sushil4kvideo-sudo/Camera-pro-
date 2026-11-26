package com.hinnka.mycamera.camera

import kotlin.math.exp

/**
 * First-order low-pass filter with a time constant expressed in seconds.
 * The response is independent of the sensor's actual sample frequency.
 */
internal object GyroPanFilterMath {
    fun lowPass(
        previous: Float,
        current: Float,
        deltaSeconds: Float,
        timeConstantSeconds: Float,
    ): Float {
        if (!previous.isFinite()) return current.takeIf { it.isFinite() } ?: 0f
        if (!current.isFinite()) return previous
        val delta = deltaSeconds.takeIf { it.isFinite() }?.coerceIn(0f, 0.1f) ?: 0f
        val timeConstant = timeConstantSeconds.takeIf { it.isFinite() && it > 0f } ?: 0.18f
        val alpha = (1.0 - exp(-delta.toDouble() / timeConstant.toDouble())).toFloat()
        return previous + (current - previous) * alpha
    }
}
