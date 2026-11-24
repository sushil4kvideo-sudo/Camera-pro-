package com.hinnka.mycamera.video

import org.junit.Assert.assertEquals
import org.junit.Test

class ApvQualityTest {
    @Test
    fun resolvesLumaCompatibleHqAndLqBitrates() {
        assertEquals(597_196_800, ApvQuality.HQ.resolveBitrate(3840, 2160, 30, 1_000_000_000))
        assertEquals(298_598_400, ApvQuality.LQ.resolveBitrate(3840, 2160, 30, 1_000_000_000))
    }

    @Test
    fun clampsApvBitrateToEncoderMaximum() {
        assertEquals(250_000_000, ApvQuality.HQ.resolveBitrate(3840, 2160, 30, 250_000_000))
    }

    @Test
    fun usesLumaFallbackWhenEncoderDoesNotAdvertiseMaximumBitrate() {
        assertEquals(400_000_000, ApvQuality.HQ.resolveBitrate(3840, 2160, 30, 0))
    }
}
