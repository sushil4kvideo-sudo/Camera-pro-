package com.hinnka.mycamera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiSubjectTrackingPanControllerTest {
    private fun target(centerX: Float, centerY: Float, fresh: Boolean = true) =
        AiSubjectTrackingTarget(
            left = (centerX - 0.05f).coerceIn(0f, 1f),
            top = (centerY - 0.05f).coerceIn(0f, 1f),
            right = (centerX + 0.05f).coerceIn(0f, 1f),
            bottom = (centerY + 0.05f).coerceIn(0f, 1f),
            category = "person",
            confidence = 0.95f,
            isFresh = fresh,
        )

    @Test
    fun subjectOnRightMovesCropToRight() {
        val controller = AiSubjectTrackingPanController().apply { reset() }
        val correction = controller.update(
            target(centerX = 0.82f, centerY = 0.5f),
            zoomRatio = 2f,
            followSpeed = AiTrackingFollowSpeed.FAST,
            deadZoneFraction = 0f,
        )!!
        assertEquals(true, correction.panX > 0f)
        assertEquals(0f, correction.panY, 0.0001f)
    }

    @Test
    fun subjectOnLeftMovesCropToLeft() {
        val controller = AiSubjectTrackingPanController().apply { reset() }
        val correction = controller.update(
            target(centerX = 0.18f, centerY = 0.5f),
            zoomRatio = 2f,
            followSpeed = AiTrackingFollowSpeed.FAST,
            deadZoneFraction = 0f,
        )!!
        assertEquals(true, correction.panX < 0f)
        assertEquals(0f, correction.panY, 0.0001f)
    }

    @Test
    fun subjectAboveMovesCropUp() {
        val controller = AiSubjectTrackingPanController().apply { reset() }
        val correction = controller.update(
            target(centerX = 0.5f, centerY = 0.2f),
            zoomRatio = 2f,
            followSpeed = AiTrackingFollowSpeed.FAST,
            deadZoneFraction = 0f,
        )!!
        assertEquals(true, correction.panY < 0f)
        assertEquals(0f, correction.panX, 0.0001f)
    }

    @Test
    fun oneTimesZoomAndStaleTargetsDoNotMoveCrop() {
        val controller = AiSubjectTrackingPanController().apply { reset() }
        assertNull(controller.update(target(0.9f, 0.1f), zoomRatio = 1f))
        assertNull(controller.update(target(0.9f, 0.1f, fresh = false), zoomRatio = 3f))
    }

    @Test
    fun deadZoneDoesNotMicroPanAroundCenter() {
        val controller = AiSubjectTrackingPanController().apply { reset() }
        val correction = controller.update(
            target(centerX = 0.53f, centerY = 0.48f),
            zoomRatio = 3f,
            deadZoneFraction = 0.06f,
        )!!
        assertEquals(0f, correction.panX, 0.0001f)
        assertEquals(0f, correction.panY, 0.0001f)
    }
}
