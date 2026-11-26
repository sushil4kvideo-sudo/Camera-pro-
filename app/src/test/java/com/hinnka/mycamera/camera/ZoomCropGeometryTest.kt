package com.hinnka.mycamera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomCropGeometryTest {

    @Test
    fun landscapeFourByThreeSensorProducesSixteenByNineNativeCrop() {
        val crop = ZoomCropGeometry.calculate(
            sourceWidth = 4000f,
            sourceHeight = 3000f,
            outputAspectRatio = 16f / 9f,
            zoomRatio = 2f,
            panX = 0f,
            panY = 0f,
        )

        assertEquals(0.5f, crop.width, 0.0001f)
        assertEquals(0.375f, crop.height, 0.0001f)
        assertEquals(16f / 9f, crop.width * 4000f / (crop.height * 3000f), 0.0001f)
        assertEquals(0.25f, crop.left, 0.0001f)
        assertEquals(0.3125f, crop.top, 0.0001f)
    }

    @Test
    fun portraitThreeByFourSensorProducesNineBySixteenCrop() {
        val crop = ZoomCropGeometry.calculate(
            sourceWidth = 3000f,
            sourceHeight = 4000f,
            outputAspectRatio = 9f / 16f,
            zoomRatio = 2f,
            panX = 0f,
            panY = 0f,
        )

        assertEquals(0.375f, crop.width, 0.0001f)
        assertEquals(0.5f, crop.height, 0.0001f)
        assertEquals(9f / 16f, crop.width * 3000f / (crop.height * 4000f), 0.0001f)
        assertEquals(0.3125f, crop.left, 0.0001f)
        assertEquals(0.25f, crop.top, 0.0001f)
    }

    @Test
    fun panningMovesCropWithoutChangingItsMagnificationOrAspectRatio() {
        val left = ZoomCropGeometry.calculate(4000f, 3000f, 16f / 9f, 2f, -1f, 0f)
        val right = ZoomCropGeometry.calculate(4000f, 3000f, 16f / 9f, 2f, 1f, 0f)

        assertEquals(left.width, right.width, 0.0001f)
        assertEquals(left.height, right.height, 0.0001f)
        assertEquals(left.top, right.top, 0.0001f)
        assertTrue(left.left < right.left)
        assertEquals(0f, left.left, 0.0001f)
        assertEquals(1f, right.right, 0.0001f)
    }

    @Test
    fun portraitSensorRatioAndCamera2AspectAreConvertedForNinetyDegreeSensor() {
        assertEquals(
            3f / 4f,
            ZoomCropGeometry.portraitSensorAspectRatio(4000, 3000),
            0.0001f,
        )
        assertEquals(
            16f / 9f,
            ZoomCropGeometry.sensorCoordinateAspectRatio(9f / 16f, 90),
            0.0001f,
        )
    }

    @Test
    fun cropRemainsInsideSensorAtExtremePan() {
        val crop = ZoomCropGeometry.calculate(
            sourceWidth = 4000f,
            sourceHeight = 3000f,
            outputAspectRatio = 16f / 9f,
            zoomRatio = 3f,
            panX = 1f,
            panY = -1f,
        )

        assertTrue(crop.left >= 0f)
        assertTrue(crop.top >= 0f)
        assertTrue(crop.right <= 1f)
        assertTrue(crop.bottom <= 1f)
        assertEquals(16f / 9f, crop.width * 4000f / (crop.height * 3000f), 0.0001f)
    }
}
