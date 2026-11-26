package com.hinnka.mycamera.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class GyroPanFilterMathTest {
    @Test
    fun lowPassResponseIsConsistentAtSixtyAndOneHundredTwentyHertz() {
        val atSixtyHz = runFilter(sampleCount = 60, deltaSeconds = 1f / 60f)
        val atOneTwentyHz = runFilter(sampleCount = 120, deltaSeconds = 1f / 120f)

        assertEquals(atSixtyHz, atOneTwentyHz, 0.0002f)
    }

    @Test
    fun zeroDeltaDoesNotAdvanceFilter() {
        assertEquals(
            0.25f,
            GyroPanFilterMath.lowPass(
                previous = 0.25f,
                current = 1f,
                deltaSeconds = 0f,
                timeConstantSeconds = 0.18f,
            ),
            0.000001f,
        )
    }

    @Test
    fun invalidCurrentValueDoesNotCorruptFilterState() {
        assertEquals(
            0.25f,
            GyroPanFilterMath.lowPass(
                previous = 0.25f,
                current = Float.NaN,
                deltaSeconds = 1f / 60f,
                timeConstantSeconds = 0.18f,
            ),
            0.000001f,
        )
    }

    private fun runFilter(sampleCount: Int, deltaSeconds: Float): Float {
        var value = 0f
        repeat(sampleCount) {
            value = GyroPanFilterMath.lowPass(
                previous = value,
                current = 1f,
                deltaSeconds = deltaSeconds,
                timeConstantSeconds = 0.22f,
            )
        }
        return value
    }
}
