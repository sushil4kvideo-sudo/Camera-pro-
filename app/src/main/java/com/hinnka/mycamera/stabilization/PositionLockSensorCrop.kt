package com.hinnka.mycamera.stabilization

import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Crop bounds normalized against the complete Camera2 active-array rectangle. */
data class PositionLockSensorCropBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val isOutsideSensor: Boolean
        get() = left < 0f || top < 0f || right > 1f || bottom > 1f
}

/**
 * Converts MGC's live forward strip homographies into the sensor area required to fill
 * the stabilized output viewport. The MGC mesh maps source vertices to output positions,
 * so the viewport edges must be mapped back through each strip's inverse homography.
 */
object PositionLockSensorCropMapper {
    fun map(
        rowHomographies: FloatArray?,
        activeArray: Rect?,
        scalerCropRegion: Rect?,
        fallbackZoom: Float = POSITION_LOCK_CROP_ZOOM,
    ): PositionLockSensorCropBounds? {
        if (activeArray == null || activeArray.width() <= 0 || activeArray.height() <= 0) {
            return null
        }

        val sensorCrop = Rect(scalerCropRegion ?: activeArray)
        if (!sensorCrop.intersect(activeArray)) sensorCrop.set(activeArray)
        val safeZoom = fallbackZoom.takeIf { it.isFinite() }?.coerceIn(1f, 10f)
            ?: POSITION_LOCK_CROP_ZOOM

        if (rowHomographies == null || rowHomographies.size != STABILIZATION_ROW_COUNT * 9) {
            val widthFraction = 1f / safeZoom
            val heightFraction = 1f / safeZoom
            val cropLeft = (1f - widthFraction) * 0.5f
            val cropTop = (1f - heightFraction) * 0.5f
            return normalizeSensorBounds(
                sensorCrop.left + cropLeft * sensorCrop.width(),
                sensorCrop.top + cropTop * sensorCrop.height(),
                sensorCrop.left + (cropLeft + widthFraction) * sensorCrop.width(),
                sensorCrop.top + (cropTop + heightFraction) * sensorCrop.height(),
                activeArray,
            )
        }

        var minSensorX = Float.POSITIVE_INFINITY
        var minSensorY = Float.POSITIVE_INFINITY
        var maxSensorX = Float.NEGATIVE_INFINITY
        var maxSensorY = Float.NEGATIVE_INFINITY
        var mappedPointCount = 0

        for (row in 0 until STABILIZATION_ROW_COUNT) {
            val offset = row * 9
            val topOutputY = 1f - 2f * row / STABILIZATION_ROW_COUNT.toFloat()
            val bottomOutputY = 1f - 2f * (row + 1) / STABILIZATION_ROW_COUNT.toFloat()
            val matrix = rowHomographies.copyOfRange(offset, offset + 9)

            for (outputY in floatArrayOf(topOutputY, bottomOutputY)) {
                for (outputX in floatArrayOf(-1f, 1f)) {
                    val source = inverseProject(matrix, outputX, outputY) ?: continue
                    // Mesh clip coordinates use +Y at the top; Camera2 rectangles use top-left origin.
                    val sourceX = (source.first + 1f) * 0.5f
                    val sourceY = (1f - source.second) * 0.5f
                    if (!sourceX.isFinite() || !sourceY.isFinite()) continue

                    val sensorX = sensorCrop.left + sourceX * sensorCrop.width()
                    val sensorY = sensorCrop.top + sourceY * sensorCrop.height()
                    minSensorX = min(minSensorX, sensorX)
                    minSensorY = min(minSensorY, sensorY)
                    maxSensorX = max(maxSensorX, sensorX)
                    maxSensorY = max(maxSensorY, sensorY)
                    mappedPointCount++
                }
            }
        }

        if (mappedPointCount == 0 ||
            !minSensorX.isFinite() || !minSensorY.isFinite() ||
            !maxSensorX.isFinite() || !maxSensorY.isFinite()
        ) {
            return null
        }
        return normalizeSensorBounds(
            minSensorX,
            minSensorY,
            maxSensorX,
            maxSensorY,
            activeArray,
        )
    }

    private fun inverseProject(
        h: FloatArray,
        outputX: Float,
        outputY: Float,
    ): Pair<Float, Float>? {
        // Solve H * [x, y, 1] = [outputX, outputY, 1] after homogeneous division.
        val a = h[0] - outputX * h[6]
        val b = h[1] - outputX * h[7]
        val c = h[3] - outputY * h[6]
        val d = h[4] - outputY * h[7]
        val rhsX = outputX * h[8] - h[2]
        val rhsY = outputY * h[8] - h[5]
        val determinant = a * d - b * c
        if (!determinant.isFinite() || abs(determinant) < 1e-7f) return null
        val x = (rhsX * d - b * rhsY) / determinant
        val y = (a * rhsY - rhsX * c) / determinant
        return if (x.isFinite() && y.isFinite()) x to y else null
    }

    private fun normalizeSensorBounds(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        activeArray: Rect,
    ): PositionLockSensorCropBounds? {
        val width = activeArray.width().toFloat()
        val height = activeArray.height().toFloat()
        if (width <= 0f || height <= 0f) return null
        // Keep overflow visible in the UI, but reject pathological matrices.
        val normalized = PositionLockSensorCropBounds(
            left = ((left - activeArray.left) / width).coerceIn(-4f, 5f),
            top = ((top - activeArray.top) / height).coerceIn(-4f, 5f),
            right = ((right - activeArray.left) / width).coerceIn(-4f, 5f),
            bottom = ((bottom - activeArray.top) / height).coerceIn(-4f, 5f),
        )
        return normalized.takeIf {
            it.left.isFinite() && it.top.isFinite() &&
                it.right.isFinite() && it.bottom.isFinite() &&
                it.right > it.left && it.bottom > it.top
        }
    }
}
