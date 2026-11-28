package com.hinnka.mycamera.stabilization

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionLockSensorCropMapperTest {
    private val activeArray = Rect(0, 0, 4000, 3000)

    @Test
    fun fixedPositionLockCropIsCenteredInsideTheFullSensor() {
        val bounds = PositionLockSensorCropMapper.map(
            rowHomographies = zoomHomographies(2.5f),
            activeArray = activeArray,
            scalerCropRegion = activeArray,
        )

        assertNotNull(bounds)
        assertEquals(0.3f, bounds!!.left, 0.0001f)
        assertEquals(0.3f, bounds.top, 0.0001f)
        assertEquals(0.7f, bounds.right, 0.0001f)
        assertEquals(0.7f, bounds.bottom, 0.0001f)
        assertFalse(bounds.isOutsideSensor)
    }

    @Test
    fun liveTransformCanReportCropBeyondTheSensorBoundary() {
        val matrices = zoomHomographies(2.5f).apply {
            repeat(STABILIZATION_ROW_COUNT) { row ->
                this[row * 9 + 2] = 0.7f
            }
        }
        val bounds = PositionLockSensorCropMapper.map(
            rowHomographies = matrices,
            activeArray = activeArray,
            scalerCropRegion = activeArray,
        )

        assertNotNull(bounds)
        assertTrue(bounds!!.left < 0f)
        assertTrue(bounds.isOutsideSensor)
    }

    @Test
    fun cameraScalerCropIsMappedIntoTheFullActiveArrayCoordinates() {
        val scalerCrop = Rect(1000, 750, 3000, 2250)
        val bounds = PositionLockSensorCropMapper.map(
            rowHomographies = zoomHomographies(2.5f),
            activeArray = activeArray,
            scalerCropRegion = scalerCrop,
        )

        assertNotNull(bounds)
        assertEquals(0.4f, bounds!!.left, 0.0001f)
        assertEquals(0.4f, bounds.top, 0.0001f)
        assertEquals(0.6f, bounds.right, 0.0001f)
        assertEquals(0.6f, bounds.bottom, 0.0001f)
    }

    private fun zoomHomographies(zoom: Float): FloatArray =
        FloatArray(STABILIZATION_ROW_COUNT * 9).also { values ->
            repeat(STABILIZATION_ROW_COUNT) { row ->
                val offset = row * 9
                values[offset] = 1f
                values[offset + 4] = 1f
                values[offset + 8] = 1f / zoom
            }
        }
}
