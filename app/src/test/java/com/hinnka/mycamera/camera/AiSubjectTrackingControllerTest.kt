package com.hinnka.mycamera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSubjectTrackingControllerTest {

    @Test
    fun subjectToRightMovesCropToRight() {
        val controller = AiSubjectTrackingController()
        val update = controller.update(
            subjectCenterX = 0.85f,
            subjectCenterY = 0.5f,
            currentPanX = 0f,
            currentPanY = 0f,
        )

        assertTrue(update.changed)
        assertTrue(update.panX > 0f)
        assertEquals(0f, update.panY, 0.0001f)
    }

    @Test
    fun subjectBelowCenterMovesCropDown() {
        val controller = AiSubjectTrackingController()
        val update = controller.update(
            subjectCenterX = 0.5f,
            subjectCenterY = 0.8f,
            currentPanX = 0f,
            currentPanY = 0f,
        )

        assertEquals(0f, update.panX, 0.0001f)
        assertTrue(update.panY > 0f)
    }

    @Test
    fun deadZonePreventsSmallJitter() {
        val controller = AiSubjectTrackingController()
        val update = controller.update(
            subjectCenterX = 0.52f,
            subjectCenterY = 0.48f,
            currentPanX = 0.2f,
            currentPanY = -0.2f,
        )

        assertEquals(0.2f, update.panX, 0.0001f)
        assertEquals(-0.2f, update.panY, 0.0001f)
        assertTrue(!update.changed)
    }

    @Test
    fun panRemainsInsideNormalizedCropBounds() {
        val controller = AiSubjectTrackingController()
        val update = controller.update(
            subjectCenterX = 1f,
            subjectCenterY = 1f,
            currentPanX = 0.99f,
            currentPanY = 0.99f,
        )

        assertEquals(1f, update.panX, 0.0001f)
        assertEquals(1f, update.panY, 0.0001f)
    }
}
