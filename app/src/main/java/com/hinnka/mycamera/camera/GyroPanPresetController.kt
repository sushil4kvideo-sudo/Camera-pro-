package com.hinnka.mycamera.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.hinnka.mycamera.utils.OrientationObserver
import kotlin.math.PI
import kotlin.math.abs

data class GyroPanCorrection(
    val panX: Float,
    val panY: Float,
    val rollDegrees: Float,
)

/**
 * Relative orientation tracker for gyro gimbal presets.
 * TYPE_GAME_ROTATION_VECTOR is preferred because it fuses gyro and accelerometer data
 * without magnetometer heading jumps. The output is applied by the realtime LUT renderer.
 */
class GyroPanPresetController(
    context: Context,
    private val mode: GyroPanMode,
    initialPanX: Float,
    initialPanY: Float,
    private val onCorrection: (GyroPanCorrection) -> Unit,
) : SensorEventListener {
    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val orientationSensor =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val deviceRotation = FloatArray(9)
    private val screenRotation = FloatArray(9)
    private val referenceRotation = FloatArray(9)
    private val inverseReferenceRotation = FloatArray(9)
    private val relativeRotation = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private var hasReference = false
    private var screenRotationBucket = -1
    private var basePanX = initialPanX.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
    private var basePanY = initialPanY.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
    private var lowPassHorizontal = 0f
    private var lowPassVertical = 0f
    private var lowPassRoll = 0f
    private var smoothHorizontal = 0f
    private var smoothVertical = 0f
    private var smoothRoll = 0f
    private var lastCorrection = GyroPanCorrection(basePanX, basePanY, 0f)

    val currentCorrection: GyroPanCorrection
        get() = lastCorrection

    fun start(): Boolean {
        val sensor = orientationSensor ?: return false
        return runCatching {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }.getOrDefault(false)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR &&
            event.sensor.type != Sensor.TYPE_ROTATION_VECTOR
        ) return
        if (event.values.size < 3) return

        runCatching {
            SensorManager.getRotationMatrixFromVector(deviceRotation, event.values)
            val rotationBucket = currentScreenRotationBucket()
            val axes = screenAxes(rotationBucket)
            if (!SensorManager.remapCoordinateSystem(
                    deviceRotation,
                    axes.first,
                    axes.second,
                    screenRotation,
                )
            ) return

            if (!hasReference || (mode == GyroPanMode.AUTO_AXIS_BALANCE &&
                    rotationBucket != screenRotationBucket)
            ) {
                if (hasReference && mode == GyroPanMode.AUTO_AXIS_BALANCE) {
                    basePanX = lastCorrection.panX
                    basePanY = lastCorrection.panY
                }
                screenRotation.copyInto(referenceRotation)
                transpose3x3(referenceRotation, inverseReferenceRotation)
                screenRotationBucket = rotationBucket
                hasReference = true
                resetFilters()
                return
            }

            multiply3x3(inverseReferenceRotation, screenRotation, relativeRotation)
            SensorManager.getOrientation(relativeRotation, orientationAngles)

            // In display coordinates: yaw is optical-axis roll, pitch is vertical pan,
            // and roll is horizontal pan. All angles are relative to the lock reference.
            val opticalRoll = deadZone(wrapRadians(orientationAngles[0]))
            val verticalAngle = deadZone(wrapRadians(orientationAngles[1]))
            val horizontalAngle = deadZone(wrapRadians(orientationAngles[2]))
            val correction = calculateCorrection(horizontalAngle, verticalAngle, opticalRoll)
            if (
                abs(correction.panX - lastCorrection.panX) >= UPDATE_EPSILON ||
                abs(correction.panY - lastCorrection.panY) >= UPDATE_EPSILON ||
                abs(correction.rollDegrees - lastCorrection.rollDegrees) >= ROLL_UPDATE_EPSILON
            ) {
                lastCorrection = correction
                onCorrection(correction)
            }
        }
    }

    private fun calculateCorrection(
        horizontalAngle: Float,
        verticalAngle: Float,
        opticalRoll: Float,
    ): GyroPanCorrection {
        val maxPanRadians = MAX_PAN_ANGLE_DEGREES * DEG_TO_RAD
        return when (mode) {
            GyroPanMode.POSITION_LOCK,
            GyroPanMode.AUTO_AXIS_BALANCE -> GyroPanCorrection(
                panX = (basePanX - horizontalAngle / maxPanRadians).coerceIn(-1f, 1f),
                panY = (basePanY + verticalAngle / maxPanRadians).coerceIn(-1f, 1f),
                rollDegrees = 0f,
            )

            GyroPanMode.ANTI_SHAKE -> {
                lowPassHorizontal += (horizontalAngle - lowPassHorizontal) * ANTI_SHAKE_ALPHA
                lowPassVertical += (verticalAngle - lowPassVertical) * ANTI_SHAKE_ALPHA
                lowPassRoll += (opticalRoll - lowPassRoll) * ANTI_SHAKE_ALPHA
                val shakeX = horizontalAngle - lowPassHorizontal
                val shakeY = verticalAngle - lowPassVertical
                val shakeRoll = opticalRoll - lowPassRoll
                GyroPanCorrection(
                    panX = (basePanX - shakeX / maxPanRadians).coerceIn(-1f, 1f),
                    panY = (basePanY + shakeY / maxPanRadians).coerceIn(-1f, 1f),
                    rollDegrees = (-shakeRoll / DEG_TO_RAD).coerceIn(-MAX_ANTI_SHAKE_ROLL, MAX_ANTI_SHAKE_ROLL),
                )
            }

            GyroPanMode.HORIZON_LOCK -> GyroPanCorrection(
                panX = basePanX,
                panY = basePanY,
                rollDegrees = (-opticalRoll / DEG_TO_RAD)
                    .coerceIn(-MAX_HORIZON_CORRECTION_DEGREES, MAX_HORIZON_CORRECTION_DEGREES),
            )

            GyroPanMode.SMOOTH_FOLLOW -> {
                smoothHorizontal += (horizontalAngle - smoothHorizontal) * SMOOTH_FOLLOW_ALPHA
                smoothVertical += (verticalAngle - smoothVertical) * SMOOTH_FOLLOW_ALPHA
                smoothRoll += (opticalRoll - smoothRoll) * SMOOTH_FOLLOW_ALPHA
                GyroPanCorrection(
                    panX = (basePanX - smoothHorizontal / maxPanRadians).coerceIn(-1f, 1f),
                    panY = (basePanY + smoothVertical / maxPanRadians).coerceIn(-1f, 1f),
                    rollDegrees = (-smoothRoll / DEG_TO_RAD)
                        .coerceIn(-MAX_HORIZON_CORRECTION_DEGREES, MAX_HORIZON_CORRECTION_DEGREES),
                )
            }
        }
    }

    private fun resetFilters() {
        lowPassHorizontal = 0f
        lowPassVertical = 0f
        lowPassRoll = 0f
        smoothHorizontal = 0f
        smoothVertical = 0f
        smoothRoll = 0f
    }

    private fun currentScreenRotationBucket(): Int {
        val degrees = ((OrientationObserver.captureRotationDegrees.toInt() % 360) + 360) % 360
        return when (degrees) {
            in 45..134 -> 90
            in 135..224 -> 180
            in 225..314 -> 270
            else -> 0
        }
    }

    private fun screenAxes(rotation: Int): Pair<Int, Int> = when (rotation) {
        90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
        180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
        270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
        else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    private fun transpose3x3(input: FloatArray, output: FloatArray) {
        for (row in 0..2) {
            for (column in 0..2) output[row * 3 + column] = input[column * 3 + row]
        }
    }

    private fun multiply3x3(a: FloatArray, b: FloatArray, output: FloatArray) {
        for (row in 0..2) {
            for (column in 0..2) {
                var value = 0f
                for (index in 0..2) value += a[row * 3 + index] * b[index * 3 + column]
                output[row * 3 + column] = value
            }
        }
    }

    private fun wrapRadians(value: Float): Float {
        val twoPi = (2.0 * PI).toFloat()
        var wrapped = (value + PI.toFloat()) % twoPi
        if (wrapped < 0f) wrapped += twoPi
        return wrapped - PI.toFloat()
    }

    private fun deadZone(value: Float): Float =
        if (abs(value) < DEAD_ZONE_RADIANS) 0f else value

    private companion object {
        const val MAX_PAN_ANGLE_DEGREES = 18f
        const val MAX_HORIZON_CORRECTION_DEGREES = 15f
        const val MAX_ANTI_SHAKE_ROLL = 6f
        const val ANTI_SHAKE_ALPHA = 0.10f
        const val SMOOTH_FOLLOW_ALPHA = 0.075f
        const val DEAD_ZONE_RADIANS = 0.15f * DEG_TO_RAD
        const val UPDATE_EPSILON = 0.0008f
        const val ROLL_UPDATE_EPSILON = 0.035f
        const val DEG_TO_RAD = 0.017453292519943295f
    }
}
