package com.hinnka.mycamera.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.hinnka.mycamera.utils.OrientationObserver
import com.hinnka.mycamera.utils.PLog
import kotlin.math.PI
import kotlin.math.abs

data class GyroPanCorrection(
    val panX: Float,
    val panY: Float,
    val rollDegrees: Float,
)

/**
 * Relative-orientation controller for Top-Off gyro presets.
 *
 * Rotation deltas are calculated in one stable sensor coordinate system first, then remapped
 * into the current display axes. This avoids multiplying matrices expressed in different
 * coordinate systems when the device changes between portrait and landscape.
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
    private val referenceRotation = FloatArray(9)
    private val inverseReferenceRotation = FloatArray(9)
    private val relativeDeviceRotation = FloatArray(9)
    private val screenRotation = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private var hasReference = false
    private var screenRotationBucket = -1
    private var lastSensorTimestampNs = 0L
    @Volatile private var sensorEventCount = 0L
    @Volatile private var sensorThread: HandlerThread? = null
    private var lastLoggedError: String? = null

    private var basePanX = initialPanX.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
    private var basePanY = initialPanY.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
    private var lowPassHorizontal = 0f
    private var lowPassVertical = 0f
    private var lowPassRoll = 0f
    private var smoothHorizontal = 0f
    private var smoothVertical = 0f
    private var smoothRoll = 0f
    @Volatile private var lastCorrection = GyroPanCorrection(basePanX, basePanY, 0f)

    val currentCorrection: GyroPanCorrection
        get() = lastCorrection

    fun start(): Boolean {
        val sensor = orientationSensor
        if (sensor == null) {
            PLog.e(TAG, "No GAME_ROTATION_VECTOR or ROTATION_VECTOR sensor is available")
            return false
        }
        val callbackThread = HandlerThread("GyroPanSensor-$mode").apply { start() }
        val registered = runCatching {
            sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_GAME,
                Handler(callbackThread.looper),
            )
        }.onFailure { error ->
            PLog.e(TAG, "Failed to register orientation sensor listener", error)
        }.getOrDefault(false)
        if (registered) {
            sensorThread = callbackThread
        } else {
            callbackThread.quitSafely()
        }
        PLog.i(
            TAG,
            "START mode=$mode sensor=${sensor.name} type=${sensor.type} vendor=${sensor.vendor} " +
                "delay=GAME thread=dedicated registered=$registered"
        )
        return registered
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR &&
            event.sensor.type != Sensor.TYPE_ROTATION_VECTOR
        ) return
        if (event.values.size < 3) return

        try {
            SensorManager.getRotationMatrixFromVector(deviceRotation, event.values)
            val rotationBucket = currentScreenRotationBucket()

            if (!hasReference) {
                setReference(deviceRotation, rotationBucket)
                lastSensorTimestampNs = event.timestamp
                sensorEventCount++
                PLog.d(TAG, "First orientation sample received; reference locked for mode=$mode")
                return
            }

            // Auto Axis Balance intentionally re-anchors at a display-axis change. Preserve
            // the existing crop position so rotating the handset cannot cause a framing jump.
            if (mode == GyroPanMode.AUTO_AXIS_BALANCE &&
                rotationBucket != screenRotationBucket
            ) {
                basePanX = lastCorrection.panX
                basePanY = lastCorrection.panY
                setReference(deviceRotation, rotationBucket)
                lastSensorTimestampNs = event.timestamp
                return
            }

            // Both matrices here are in the same, un-remapped device coordinate system.
            multiply3x3(inverseReferenceRotation, deviceRotation, relativeDeviceRotation)
            val axes = screenAxes(rotationBucket)
            if (!SensorManager.remapCoordinateSystem(
                    relativeDeviceRotation,
                    axes.first,
                    axes.second,
                    screenRotation,
                )
            ) {
                PLog.w(TAG, "Could not remap gyro delta for display rotation=$rotationBucket")
                return
            }
            screenRotationBucket = rotationBucket
            SensorManager.getOrientation(screenRotation, orientationAngles)

            val deltaSeconds = resolveDeltaSeconds(event.timestamp)
            val opticalRoll = deadZone(wrapRadians(orientationAngles[0]))
            val verticalAngle = deadZone(wrapRadians(orientationAngles[1]))
            val horizontalAngle = deadZone(wrapRadians(orientationAngles[2]))
            val correction = calculateCorrection(
                horizontalAngle = horizontalAngle,
                verticalAngle = verticalAngle,
                opticalRoll = opticalRoll,
                deltaSeconds = deltaSeconds,
            )

            sensorEventCount++
            if (
                abs(correction.panX - lastCorrection.panX) >= UPDATE_EPSILON ||
                abs(correction.panY - lastCorrection.panY) >= UPDATE_EPSILON ||
                abs(correction.rollDegrees - lastCorrection.rollDegrees) >= ROLL_UPDATE_EPSILON
            ) {
                lastCorrection = correction
                onCorrection(correction)
            }
        } catch (error: Exception) {
            val signature = "${error.javaClass.simpleName}:${error.message}"
            if (signature != lastLoggedError) {
                lastLoggedError = signature
                PLog.e(TAG, "Gyro sample processing failed mode=$mode", error)
            }
        }
    }

    private fun resolveDeltaSeconds(timestampNs: Long): Float {
        val previous = lastSensorTimestampNs
        lastSensorTimestampNs = timestampNs
        if (previous <= 0L || timestampNs <= previous) return DEFAULT_DELTA_SECONDS
        return ((timestampNs - previous).toDouble() / NANOS_PER_SECOND)
            .toFloat()
            .coerceIn(MIN_DELTA_SECONDS, MAX_DELTA_SECONDS)
    }

    private fun setReference(rotation: FloatArray, rotationBucket: Int) {
        rotation.copyInto(referenceRotation)
        transpose3x3(referenceRotation, inverseReferenceRotation)
        screenRotationBucket = rotationBucket
        hasReference = true
        resetFilters()
    }

    private fun calculateCorrection(
        horizontalAngle: Float,
        verticalAngle: Float,
        opticalRoll: Float,
        deltaSeconds: Float,
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
                lowPassHorizontal = GyroPanFilterMath.lowPass(
                    lowPassHorizontal, horizontalAngle, deltaSeconds, ANTI_SHAKE_TIME_CONSTANT_SECONDS
                )
                lowPassVertical = GyroPanFilterMath.lowPass(
                    lowPassVertical, verticalAngle, deltaSeconds, ANTI_SHAKE_TIME_CONSTANT_SECONDS
                )
                lowPassRoll = GyroPanFilterMath.lowPass(
                    lowPassRoll, opticalRoll, deltaSeconds, ANTI_SHAKE_TIME_CONSTANT_SECONDS
                )
                val shakeX = horizontalAngle - lowPassHorizontal
                val shakeY = verticalAngle - lowPassVertical
                val shakeRoll = opticalRoll - lowPassRoll
                GyroPanCorrection(
                    panX = (basePanX - shakeX / maxPanRadians).coerceIn(-1f, 1f),
                    panY = (basePanY + shakeY / maxPanRadians).coerceIn(-1f, 1f),
                    rollDegrees = (-shakeRoll / DEG_TO_RAD)
                        .coerceIn(-MAX_ANTI_SHAKE_ROLL, MAX_ANTI_SHAKE_ROLL),
                )
            }

            GyroPanMode.HORIZON_LOCK -> GyroPanCorrection(
                panX = basePanX,
                panY = basePanY,
                rollDegrees = (-opticalRoll / DEG_TO_RAD)
                    .coerceIn(-MAX_HORIZON_CORRECTION_DEGREES, MAX_HORIZON_CORRECTION_DEGREES),
            )

            GyroPanMode.SMOOTH_FOLLOW -> {
                smoothHorizontal = GyroPanFilterMath.lowPass(
                    smoothHorizontal, horizontalAngle, deltaSeconds, SMOOTH_FOLLOW_TIME_CONSTANT_SECONDS
                )
                smoothVertical = GyroPanFilterMath.lowPass(
                    smoothVertical, verticalAngle, deltaSeconds, SMOOTH_FOLLOW_TIME_CONSTANT_SECONDS
                )
                smoothRoll = GyroPanFilterMath.lowPass(
                    smoothRoll, opticalRoll, deltaSeconds, SMOOTH_FOLLOW_TIME_CONSTANT_SECONDS
                )
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

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == orientationSensor?.type) {
            PLog.d(TAG, "Sensor accuracy changed mode=$mode accuracy=$accuracy")
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        sensorThread?.quitSafely()
        sensorThread = null
        PLog.i(TAG, "STOP mode=$mode receivedEvents=$sensorEventCount final=$lastCorrection")
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
        const val TAG = "GyroPan"
        const val DEG_TO_RAD = 0.017453292519943295f
        const val MAX_PAN_ANGLE_DEGREES = 18f
        const val MAX_HORIZON_CORRECTION_DEGREES = 15f
        const val MAX_ANTI_SHAKE_ROLL = 6f
        const val ANTI_SHAKE_TIME_CONSTANT_SECONDS = 0.18f
        const val SMOOTH_FOLLOW_TIME_CONSTANT_SECONDS = 0.22f
        const val DEAD_ZONE_RADIANS = 0.04f * DEG_TO_RAD
        const val UPDATE_EPSILON = 0.0008f
        const val ROLL_UPDATE_EPSILON = 0.025f
        const val DEFAULT_DELTA_SECONDS = 1f / 60f
        const val MIN_DELTA_SECONDS = 1f / 500f
        const val MAX_DELTA_SECONDS = 0.1f
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
