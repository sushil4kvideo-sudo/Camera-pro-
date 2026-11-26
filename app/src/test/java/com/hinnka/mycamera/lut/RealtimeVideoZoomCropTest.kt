package com.hinnka.mycamera.lut

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class RealtimeVideoZoomCropTest {

    @Test
    fun oneXKeepsTheResolvedSensorCropUnchanged() {
        val source = floatArrayOf(0.1f, 0.2f, 0.9f, 0.8f)

        val result = resolveRealtimeVideoZoomCropRect(source, 1f)

        assertArrayEquals(source, result, 0.0001f)
    }

    @Test
    fun threeXUsesCenteredOneThirdOfTheLiveFrame() {
        val result = resolveRealtimeVideoZoomCropRect(
            sourceCropRect = floatArrayOf(0f, 0f, 1f, 1f),
            zoomRatio = 3f,
        )

        assertArrayEquals(
            floatArrayOf(1f / 3f, 1f / 3f, 2f / 3f, 2f / 3f),
            result,
            0.0001f,
        )
    }

    @Test
    fun twoXRightPanMovesTheCropToTheRightEdgeWithoutChangingZoom() {
        val result = resolveRealtimeVideoZoomCropRect(
            sourceCropRect = floatArrayOf(0f, 0f, 1f, 1f),
            zoomRatio = 2f,
            panX = 1f,
            panY = 0f,
        )

        assertArrayEquals(
            floatArrayOf(0.5f, 0.25f, 1f, 0.75f),
            result,
            0.0001f,
        )
    }

    @Test
    fun twoXTopPanMovesTheCropToTheTopEdgeWithoutChangingZoom() {
        val result = resolveRealtimeVideoZoomCropRect(
            sourceCropRect = floatArrayOf(0f, 0f, 1f, 1f),
            zoomRatio = 2f,
            panX = 0f,
            panY = -1f,
        )

        assertArrayEquals(
            floatArrayOf(0.25f, 0f, 0.75f, 0.5f),
            result,
            0.0001f,
        )
    }

    @Test
    fun virtualTelephotoPreservesTheCenterOfAnExistingCrop() {
        val result = resolveRealtimeVideoZoomCropRect(
            sourceCropRect = floatArrayOf(0.2f, 0.1f, 0.8f, 0.9f),
            zoomRatio = 3f,
        )

        assertArrayEquals(
            floatArrayOf(0.4f, 0.36666667f, 0.6f, 0.6333333f),
            result,
            0.0001f,
        )
    }

    @Test
    fun threePointFiveXUsesCenteredOneOverThreePointFiveOfTheLiveFrame() {
        val result = resolveRealtimeVideoZoomCropRect(
            sourceCropRect = floatArrayOf(0f, 0f, 1f, 1f),
            zoomRatio = 3.5f,
        )

        assertArrayEquals(
            floatArrayOf(5f / 14f, 5f / 14f, 9f / 14f, 9f / 14f),
            result,
            0.0001f,
        )
    }

    @Test
    fun invalidZoomFallsBackToOneX() {
        val source = floatArrayOf(0f, 0.1f, 1f, 0.9f)

        assertArrayEquals(source, resolveRealtimeVideoZoomCropRect(source, Float.NaN), 0.0001f)
    }
}
