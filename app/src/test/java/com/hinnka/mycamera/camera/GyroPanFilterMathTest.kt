package com.hinnka.mycamera.camera

import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun rotationVectorExtractsAllThreeIndependentDisplayAxes() {
        val angle = 18f * Math.PI.toFloat() / 180f
        val output = FloatArray(3)

        assertTrue(GyroPanFilterMath.rotationVectorFromMatrix(rotationX(angle), output))
        assertEquals(angle, output[0], 0.0001f)
        assertEquals(0f, output[1], 0.0001f)
        assertEquals(0f, output[2], 0.0001f)

        assertTrue(GyroPanFilterMath.rotationVectorFromMatrix(rotationY(angle), output))
        assertEquals(0f, output[0], 0.0001f)
        assertEquals(angle, output[1], 0.0001f)
        assertEquals(0f, output[2], 0.0001f)

        assertTrue(GyroPanFilterMath.rotationVectorFromMatrix(rotationZ(angle), output))
        assertEquals(0f, output[0], 0.0001f)
        assertEquals(0f, output[1], 0.0001f)
        assertEquals(angle, output[2], 0.0001f)
    }

    @Test
    fun horizontalAxisIsPreservedWhenPitchAndYawOccurTogether() {
        val pitch = -0.12f
        val yaw = 0.20f
        val combined = multiply3x3(rotationY(yaw), rotationX(pitch))
        val output = FloatArray(3)

        assertTrue(GyroPanFilterMath.rotationVectorFromMatrix(combined, output))
        assertTrue("vertical component was lost: ${output[0]}", abs(output[0]) > 0.10f)
        assertTrue("horizontal component was lost: ${output[1]}", abs(output[1]) > 0.18f)
    }

    @Test
    fun nearIdentityRotationReturnsFiniteSmallAngles() {
        val output = FloatArray(3)

        assertTrue(GyroPanFilterMath.rotationVectorFromMatrix(rotationY(0.00001f), output))
        assertTrue(output.all { it.isFinite() })
        assertEquals(0.00001f, output[1], 0.000001f)
    }

    @Test
    fun invalidRotationMatrixIsRejected() {
        assertTrue(
            !GyroPanFilterMath.rotationVectorFromMatrix(
                floatArrayOf(Float.NaN, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
                FloatArray(3),
            )
        )
    }

    @Test
    fun smoothFollowOffsetIsLowPassOrientationMinusCurrentOrientation() {
        assertEquals(
            -0.12f,
            GyroPanFilterMath.smoothFollowOffset(
                lowPassAngleRadians = 0.08f,
                currentAngleRadians = 0.20f,
            ),
            0.000001f,
        )
    }

    @Test
    fun normalizedPanUsesActualFovAndZoomTravel() {
        val halfFovTangent = tan(Math.toRadians(45.0))
        val zoom = 2f
        val desiredPan = 0.5f
        val angle = atan(halfFovTangent * (1.0 - 1.0 / zoom) * desiredPan).toFloat()

        assertEquals(
            desiredPan,
            GyroPanFilterMath.normalizedPanForAngle(
                angleRadians = angle,
                fieldOfViewDegrees = 90f,
                zoomRatio = zoom,
            ),
            0.0001f,
        )
    }

    @Test
    fun positionLockHorizontalPanMovesCropAgainstLeftAndRightPhoneYaw() {
        val yawRadians = 0.25f

        // Android's rotation matrix maps device coordinates to world coordinates.
        // In upright portrait, device +Y points upward; a rightward phone turn is
        // a negative rotation around world +Z and therefore a negative relative Y.
        val uprightPortraitDeviceToWorld = floatArrayOf(
            1f, 0f, 0f,
            0f, 0f, -1f,
            0f, 1f, 0f,
        )
        val turnedRight = multiply3x3(
            rotationZ(-yawRadians),
            uprightPortraitDeviceToWorld,
        )
        val relativeRotation = multiply3x3(
            transpose3x3(uprightPortraitDeviceToWorld),
            turnedRight,
        )
        val relativeVector = FloatArray(3)

        assertTrue(GyroPanFilterMath.rotationVectorFromMatrix(relativeRotation, relativeVector))
        assertEquals(-yawRadians, relativeVector[1], 0.0001f)

        val rightTurnPan = GyroPanFilterMath.positionLockHorizontalPan(
            basePanX = 0f,
            horizontalAngleRadians = relativeVector[1],
            fieldOfViewDegrees = 70f,
            zoomRatio = 2f,
        )
        val leftTurnPan = GyroPanFilterMath.positionLockHorizontalPan(
            basePanX = 0f,
            horizontalAngleRadians = -relativeVector[1],
            fieldOfViewDegrees = 70f,
            zoomRatio = 2f,
        )

        assertTrue("Right turn must move the crop left to counter camera yaw", rightTurnPan < 0f)
        assertTrue("Left turn must move the crop right to counter camera yaw", leftTurnPan > 0f)
    }

    @Test
    fun normalizedPanIsZeroWhenThereIsNoCropTravelAtOneX() {
        assertEquals(
            0f,
            GyroPanFilterMath.normalizedPanForAngle(
                angleRadians = 0.3f,
                fieldOfViewDegrees = 70f,
                zoomRatio = 1f,
            ),
            0f,
        )
    }

    @Test
    fun fieldOfViewUsesActiveAspectCropAndSwapsDisplayAxesForPortraitRotation() {
        val result = GyroPanFilterMath.fieldOfViewDegrees(
            activeSensorWidthMm = 6f,
            activeSensorHeightMm = 4.5f,
            sensorCoordinateAspectRatio = 16f / 9f,
            focalLengthMm = 5f,
            sensorOrientationDegrees = 90,
        ) ?: error("Valid sensor optics should produce an FOV")

        val sensorHorizontal = Math.toDegrees(2.0 * atan(6.0 / 10.0)).toFloat()
        val croppedSensorVertical = Math.toDegrees(2.0 * atan(3.375 / 10.0)).toFloat()
        assertEquals(croppedSensorVertical, result.horizontalDegrees, 0.0001f)
        assertEquals(sensorHorizontal, result.verticalDegrees, 0.0001f)
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

    private fun rotationX(angle: Float): FloatArray {
        val c = cos(angle)
        val s = sin(angle)
        return floatArrayOf(
            1f, 0f, 0f,
            0f, c, -s,
            0f, s, c,
        )
    }

    private fun rotationY(angle: Float): FloatArray {
        val c = cos(angle)
        val s = sin(angle)
        return floatArrayOf(
            c, 0f, s,
            0f, 1f, 0f,
            -s, 0f, c,
        )
    }

    private fun rotationZ(angle: Float): FloatArray {
        val c = cos(angle)
        val s = sin(angle)
        return floatArrayOf(
            c, -s, 0f,
            s, c, 0f,
            0f, 0f, 1f,
        )
    }

    private fun transpose3x3(matrix: FloatArray): FloatArray {
        return floatArrayOf(
            matrix[0], matrix[3], matrix[6],
            matrix[1], matrix[4], matrix[7],
            matrix[2], matrix[5], matrix[8],
        )
    }

    private fun multiply3x3(left: FloatArray, right: FloatArray): FloatArray {
        val result = FloatArray(9)
        for (row in 0..2) {
            for (column in 0..2) {
                for (index in 0..2) {
                    result[row * 3 + column] +=
                        left[row * 3 + index] * right[index * 3 + column]
                }
            }
        }
        return result
    }
}