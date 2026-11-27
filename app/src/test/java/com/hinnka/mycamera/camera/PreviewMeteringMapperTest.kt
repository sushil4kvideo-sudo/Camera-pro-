package com.hinnka.mycamera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PreviewMeteringMapperTest {

    @Test
    fun positionLockZoomMapsMeteringIntoVisibleCenterCrop() {
        val activeArray = CameraCoordinateRect(
            left = 0,
            top = 0,
            right = 4_000,
            bottom = 3_000,
        )

        val mapping = PreviewMeteringMapper.mapPoint(
            normalizedX = 0.5f,
            normalizedY = 0.5f,
            activeArray = activeArray,
            scalerCropRegion = null,
            zoomMode = PreviewMeteringZoomMode.POST_ZOOM_ACTIVE_ARRAY,
            previewViewAspectRatio = 4f / 3f,
            sensorOrientationDegrees = 0,
            isFrontFacing = false,
            previewCropZoom = 2f,
        )

        assertNotNull(mapping)
        assertEquals(
            CameraCoordinateRect(1_000, 750, 3_000, 2_250),
            mapping?.visibleRegion,
        )
        assertEquals(2_000, mapping?.centerX)
        assertEquals(1_500, mapping?.centerY)
    }

    @Test
    fun meteringTapAtPreviewEdgesStaysInsidePositionLockCrop() {
        val activeArray = CameraCoordinateRect(0, 0, 4_000, 3_000)

        val leftTop = PreviewMeteringMapper.mapPoint(
            normalizedX = 0f,
            normalizedY = 0f,
            activeArray = activeArray,
            scalerCropRegion = null,
            zoomMode = PreviewMeteringZoomMode.POST_ZOOM_ACTIVE_ARRAY,
            previewViewAspectRatio = 4f / 3f,
            sensorOrientationDegrees = 0,
            isFrontFacing = false,
            previewCropZoom = 2f,
        )
        val rightBottom = PreviewMeteringMapper.mapPoint(
            normalizedX = 1f,
            normalizedY = 1f,
            activeArray = activeArray,
            scalerCropRegion = null,
            zoomMode = PreviewMeteringZoomMode.POST_ZOOM_ACTIVE_ARRAY,
            previewViewAspectRatio = 4f / 3f,
            sensorOrientationDegrees = 0,
            isFrontFacing = false,
            previewCropZoom = 2f,
        )

        assertNotNull(leftTop)
        assertNotNull(rightBottom)
        assertEquals(1_000, leftTop?.centerX)
        assertEquals(750, leftTop?.centerY)
        assertEquals(2_999, rightBottom?.centerX)
        assertEquals(2_249, rightBottom?.centerY)
    }

    @Test
    fun defaultCropZoomPreservesExistingMeteringGeometry() {
        val activeArray = CameraCoordinateRect(0, 0, 4_000, 3_000)

        val mapping = PreviewMeteringMapper.mapPoint(
            normalizedX = 0.5f,
            normalizedY = 0.5f,
            activeArray = activeArray,
            scalerCropRegion = null,
            zoomMode = PreviewMeteringZoomMode.POST_ZOOM_ACTIVE_ARRAY,
            previewViewAspectRatio = 4f / 3f,
            sensorOrientationDegrees = 0,
            isFrontFacing = false,
        )

        assertNotNull(mapping)
        assertEquals(activeArray, mapping?.visibleRegion)
    }
}
