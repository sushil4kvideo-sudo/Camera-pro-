package com.hinnka.mycamera.camera

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Small, allocation-free rotation helpers used by live gyro pan presets.
 */
internal object GyroPanFilterMath {

    /**
     * Display-space field of view after fitting the requested video aspect ratio into the
     * active physical sensor area. SENSOR_ORIENTATION determines whether the sensor axes
     * need to be swapped to obtain display-horizontal/display-vertical FOV.
     */
    data class GyroPanFieldOfView(
        val horizontalDegrees: Float,
        val verticalDegrees: Float,
    )

    fun fieldOfViewDegrees(
        activeSensorWidthMm: Float,
        activeSensorHeightMm: Float,
        sensorCoordinateAspectRatio: Float,
        focalLengthMm: Float,
        sensorOrientationDegrees: Int,
    ): GyroPanFieldOfView? {
        val width = activeSensorWidthMm.takeIf { it.isFinite() && it > 0f }?.toDouble()
            ?: return null
        val height = activeSensorHeightMm.takeIf { it.isFinite() && it > 0f }?.toDouble()
            ?: return null
        val aspect = sensorCoordinateAspectRatio.takeIf { it.isFinite() && it > 0f }?.toDouble()
            ?: return null
        val focalLength = focalLengthMm.takeIf { it.isFinite() && it > 0f }?.toDouble()
            ?: return null

        val cropWidth: Double
        val cropHeight: Double
        if (width / height > aspect) {
            cropHeight = height
            cropWidth = height * aspect
        } else {
            cropWidth = width
            cropHeight = width / aspect
        }

        val sensorHorizontalFov = Math.toDegrees(2.0 * atan(cropWidth / (2.0 * focalLength))).toFloat()
        val sensorVerticalFov = Math.toDegrees(2.0 * atan(cropHeight / (2.0 * focalLength))).toFloat()
        if (!sensorHorizontalFov.isFinite() || !sensorVerticalFov.isFinite()) return null

        return if (Math.floorMod(sensorOrientationDegrees, 180) == 90) {
            GyroPanFieldOfView(
                horizontalDegrees = sensorVerticalFov,
                verticalDegrees = sensorHorizontalFov,
            )
        } else {
            GyroPanFieldOfView(
                horizontalDegrees = sensorHorizontalFov,
                verticalDegrees = sensorVerticalFov,
            )
        }
    }

    /**
     * Converts an angular displacement into the Top-Off crop's normalized pan coordinate.
     *
     * At pan=+/-1 the crop window reaches one edge, so the available travel is
     * (1 - 1/zoom) of the displayed frame. The tangent mapping keeps the response correct
     * for each camera's actual field of view instead of assuming a fixed angle per pan unit.
     */
    fun normalizedPanForAngle(
        angleRadians: Float,
        fieldOfViewDegrees: Float,
        zoomRatio: Float,
    ): Float {
        if (!angleRadians.isFinite()) return 0f
        val safeFov = fieldOfViewDegrees.takeIf { it.isFinite() && it > 0f }
            ?.coerceIn(1f, 179f) ?: return 0f
        val zoom = zoomRatio.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f
        val availableTravel = 1.0 - 1.0 / zoom.toDouble()
        if (availableTravel <= PAN_EPSILON) return 0f

        val halfFovTangent = tan(Math.toRadians(safeFov.toDouble() * 0.5))
        if (!halfFovTangent.isFinite() || halfFovTangent <= PAN_EPSILON) return 0f
        val safeAngle = angleRadians.toDouble().coerceIn(
            -PI * 0.5 + ANGLE_EPSILON,
            PI * 0.5 - ANGLE_EPSILON,
        )
        val pan = tan(safeAngle) / (halfFovTangent * availableTravel)
        return if (pan.isFinite()) pan.toFloat().coerceIn(-1f, 1f) else 0f
    }

    /**
     * Extracts the shortest axis-angle rotation vector from a row-major 3x3 matrix.
     *
     * The returned X/Y/Z values are signed rotation angles in radians around the matrix's
     * corresponding axes. Unlike Euler angles, the vector does not lose the Y component when
     * the phone also has a small pitch or optical-axis roll.
     */
    fun rotationVectorFromMatrix(matrix: FloatArray, output: FloatArray): Boolean {
        if (matrix.size < 9 || output.size < 3) return false
        if ((0 until 9).any { !matrix[it].isFinite() }) return false

        val trace = matrix[0] + matrix[4] + matrix[8]
        var qw: Float
        var qx: Float
        var qy: Float
        var qz: Float

        if (trace > 0f) {
            val scale = sqrt((trace + 1f).coerceAtLeast(0f)) * 2f
            if (scale <= ROTATION_EPSILON) return false
            qw = 0.25f * scale
            qx = (matrix[7] - matrix[5]) / scale
            qy = (matrix[2] - matrix[6]) / scale
            qz = (matrix[3] - matrix[1]) / scale
        } else if (matrix[0] > matrix[4] && matrix[0] > matrix[8]) {
            val scale = sqrt((1f + matrix[0] - matrix[4] - matrix[8]).coerceAtLeast(0f)) * 2f
            if (scale <= ROTATION_EPSILON) return false
            qw = (matrix[7] - matrix[5]) / scale
            qx = 0.25f * scale
            qy = (matrix[1] + matrix[3]) / scale
            qz = (matrix[2] + matrix[6]) / scale
        } else if (matrix[4] > matrix[8]) {
            val scale = sqrt((1f + matrix[4] - matrix[0] - matrix[8]).coerceAtLeast(0f)) * 2f
            if (scale <= ROTATION_EPSILON) return false
            qw = (matrix[2] - matrix[6]) / scale
            qx = (matrix[1] + matrix[3]) / scale
            qy = 0.25f * scale
            qz = (matrix[5] + matrix[7]) / scale
        } else {
            val scale = sqrt((1f + matrix[8] - matrix[0] - matrix[4]).coerceAtLeast(0f)) * 2f
            if (scale <= ROTATION_EPSILON) return false
            qw = (matrix[3] - matrix[1]) / scale
            qx = (matrix[2] + matrix[6]) / scale
            qy = (matrix[5] + matrix[7]) / scale
            qz = 0.25f * scale
        }

        val quaternionLength = sqrt(qw * qw + qx * qx + qy * qy + qz * qz)
        if (!quaternionLength.isFinite() || quaternionLength <= ROTATION_EPSILON) return false
        val inverseLength = 1f / quaternionLength
        qw *= inverseLength
        qx *= inverseLength
        qy *= inverseLength
        qz *= inverseLength

        // q and -q represent the same rotation. Keep the shortest rotation (angle <= PI).
        if (qw < 0f) {
            qw = -qw
            qx = -qx
            qy = -qy
            qz = -qz
        }

        val vectorLength = sqrt(qx * qx + qy * qy + qz * qz)
        if (vectorLength <= ROTATION_EPSILON) {
            output[0] = 2f * qx
            output[1] = 2f * qy
            output[2] = 2f * qz
        } else {
            val angle = 2f * atan2(vectorLength, qw.coerceAtLeast(0f))
            val scale = angle / vectorLength
            output[0] = qx * scale
            output[1] = qy * scale
            output[2] = qz * scale
        }

        return output[0].isFinite() && output[1].isFinite() && output[2].isFinite()
    }

    /**
     * First-order low-pass filter with a time constant expressed in seconds.
     * The response is independent of the sensor's actual sample frequency.
     */
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

    private const val ROTATION_EPSILON = 1e-7f
    private const val PAN_EPSILON = 1e-7
    private const val ANGLE_EPSILON = 1e-5
}