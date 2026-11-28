package com.hinnka.mycamera.camera

import kotlin.math.max
import kotlin.math.min

/**
 * A crop rectangle expressed as fractions of the source sensor bounds.
 * The rectangle always keeps the requested output aspect ratio and remains
 * completely inside the active array.
 */
data class NormalizedZoomCropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** Translation fractions for moving a zoomed preview while keeping its magnification fixed. */
data class NormalizedZoomPanTranslation(
    val xFraction: Float,
    val yFraction: Float,
)

/** Pan converted from viewfinder display axes into the unrotated camera-texture axes. */
data class SensorCoordinateZoomPan(
    val x: Float,
    val y: Float,
)

/**
 * Shared geometry for the movable crop editor and Camera2 SCALER_CROP_REGION.
 *
 * The aspect ratio is expressed in the coordinate system of sourceWidth/sourceHeight.
 * For the portrait UI, pass the portrait ratio; for Camera2 sensor coordinates, pass
 * the equivalent ratio after accounting for SENSOR_ORIENTATION.
 */
object ZoomCropGeometry {

    /**
     * Convert a pan measured in the rotated viewfinder into the source-texture axes.
     *
     * Preview crop rectangles are applied before the SurfaceTexture presentation matrix,
     * so a portrait sensor swaps the two axes and changes one direction. Keep this mapping
     * shared with Camera2's freeform crop path and account for the front-preview mirror.
     */
    fun sensorSpacePanFromDisplay(
        displayPanX: Float,
        displayPanY: Float,
        sensorOrientationDegrees: Int,
        isFrontFacing: Boolean = false,
    ): SensorCoordinateZoomPan {
        val safeX = displayPanX.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
        val safeY = displayPanY.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
        val rotated = when (Math.floorMod(sensorOrientationDegrees, 360)) {
            90 -> safeY to -safeX
            180 -> -safeX to -safeY
            270 -> -safeY to safeX
            else -> safeX to safeY
        }
        val sensorX = if (isFrontFacing) -rotated.first else rotated.first
        return SensorCoordinateZoomPan(
            x = sensorX.coerceIn(-1f, 1f),
            y = rotated.second.coerceIn(-1f, 1f),
        )
    }

    /**
     * Converts the selected sensor crop offset into a display-space translation.
     * Positive pan moves the crop window right/down, so the image itself moves left/up.
     * Fractions are relative to the unzoomed viewfinder dimensions.
     */
    fun displayPanTranslationFraction(
        zoomRatio: Float,
        panX: Float,
        panY: Float,
    ): NormalizedZoomPanTranslation {
        val zoom = zoomRatio.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f
        val safePanX = panX.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
        val safePanY = panY.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
        val maxTravelFraction = (zoom - 1f) * 0.5f
        return NormalizedZoomPanTranslation(
            xFraction = -safePanX * maxTravelFraction,
            yFraction = -safePanY * maxTravelFraction,
        )
    }

    fun calculate(
        sourceWidth: Float,
        sourceHeight: Float,
        outputAspectRatio: Float,
        zoomRatio: Float,
        panX: Float,
        panY: Float,
    ): NormalizedZoomCropRect {
        val width = sourceWidth.takeIf { it.isFinite() && it > 0f } ?: 1f
        val height = sourceHeight.takeIf { it.isFinite() && it > 0f } ?: 1f
        val sourceAspect = width / height
        val targetAspect = outputAspectRatio.takeIf { it.isFinite() && it > 0f }
            ?: sourceAspect
        val zoom = zoomRatio.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f
        val safePanX = panX.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
        val safePanY = panY.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f

        // First fit the requested output ratio into the full sensor. Additional zoom
        // then scales that correctly-shaped crop, rather than cropping the sensor as
        // a square/4:3 rectangle and allowing the stream scaler to centre-crop again.
        val baseWidth: Float
        val baseHeight: Float
        if (sourceAspect > targetAspect) {
            baseHeight = height
            baseWidth = height * targetAspect
        } else {
            baseWidth = width
            baseHeight = width / targetAspect
        }

        val cropWidth = (baseWidth / zoom).coerceIn(0.0001f, width)
        val cropHeight = (baseHeight / zoom).coerceIn(0.0001f, height)
        val travelX = (width - cropWidth).coerceAtLeast(0f)
        val travelY = (height - cropHeight).coerceAtLeast(0f)
        val left = (travelX * (0.5f + 0.5f * safePanX)) / width
        val top = (travelY * (0.5f + 0.5f * safePanY)) / height

        return NormalizedZoomCropRect(
            left = left.coerceIn(0f, 1f),
            top = top.coerceIn(0f, 1f),
            right = (left + cropWidth / width).coerceIn(0f, 1f),
            bottom = (top + cropHeight / height).coerceIn(0f, 1f),
        )
    }

    /** Portrait representation of the physical active-array rectangle. */
    fun portraitSensorAspectRatio(activeArrayWidth: Int, activeArrayHeight: Int): Float {
        val longEdge = max(activeArrayWidth, activeArrayHeight).coerceAtLeast(1)
        val shortEdge = min(activeArrayWidth, activeArrayHeight).coerceAtLeast(1)
        return (shortEdge.toFloat() / longEdge).coerceIn(0.1f, 1f)
    }

    /** Convert a portrait display aspect ratio to unrotated Camera2 sensor coordinates. */
    fun sensorCoordinateAspectRatio(portraitAspectRatio: Float, sensorOrientation: Int): Float {
        val safePortraitRatio = portraitAspectRatio.takeIf { it.isFinite() && it > 0f }
            ?.coerceIn(0.1f, 1f) ?: (3f / 4f)
        return if (Math.floorMod(sensorOrientation, 180) == 90) {
            1f / safePortraitRatio
        } else {
            safePortraitRatio
        }
    }
}
