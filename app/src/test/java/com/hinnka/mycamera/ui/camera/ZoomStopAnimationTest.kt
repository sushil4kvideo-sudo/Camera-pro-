package com.hinnka.mycamera.ui.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomStopAnimationTest {

    @Test
    fun mainToUltraWideAtPointSixUsesInstantHandoffWithAnimatedPreview() {
        assertTrue(
            shouldUseNormalVideoUltraWideHandoffAnimation(
                currentLensIntrinsicZoomRatio = 1f,
                targetLensIntrinsicZoomRatio = 0.6f,
                targetZoomRatio = 0.6f,
            )
        )
    }

    @Test
    fun ultraWideToMainAtOneXUsesInstantHandoffWithAnimatedPreview() {
        assertTrue(
            shouldUseNormalVideoUltraWideHandoffAnimation(
                currentLensIntrinsicZoomRatio = 0.6f,
                targetLensIntrinsicZoomRatio = 1f,
                targetZoomRatio = 1f,
            )
        )
    }

    @Test
    fun otherZoomStopsDoNotUseTheSpecialHandoffPath() {
        assertFalse(
            shouldUseNormalVideoUltraWideHandoffAnimation(
                currentLensIntrinsicZoomRatio = 0.6f,
                targetLensIntrinsicZoomRatio = 1f,
                targetZoomRatio = 2f,
            )
        )
        assertFalse(
            shouldUseNormalVideoUltraWideHandoffAnimation(
                currentLensIntrinsicZoomRatio = 1f,
                targetLensIntrinsicZoomRatio = 0.6f,
                targetZoomRatio = 0.8f,
            )
        )
    }

    @Test
    fun sameLensDoesNotTriggerALensHandoff() {
        assertFalse(
            shouldUseNormalVideoUltraWideHandoffAnimation(
                currentLensIntrinsicZoomRatio = 0.6f,
                targetLensIntrinsicZoomRatio = 0.6f,
                targetZoomRatio = 0.6f,
            )
        )
    }

    @Test
    fun ultraWidePreviewScalesToMatchMainLensFieldOfView() {
        assertEquals(
            1f / 0.6f,
            normalVideoUltraWideHandoffPreviewScale(0.6f),
            0.0001f,
        )
        assertEquals(
            2f,
            normalVideoUltraWideHandoffPreviewScale(0.5f),
            0.0001f,
        )
    }

    @Test
    fun invalidUltraWideRatioUsesSafePointSixFallback() {
        assertEquals(
            1f / 0.6f,
            normalVideoUltraWideHandoffPreviewScale(Float.NaN),
            0.0001f,
        )
    }
}
