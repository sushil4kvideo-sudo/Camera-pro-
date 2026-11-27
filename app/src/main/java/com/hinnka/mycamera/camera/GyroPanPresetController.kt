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
import kotlin.math.abs

data class GyroPanCorrection(
    val panX: Float,
    val panY: Float,
    val rollDegrees: Float,
    val fixedRollCover: Boolean = false,
)

/** Camera optics used to map gyro angles into the active Top-Off crop. */
data class GyroPanOptics(
    val horizontalFieldOfViewDegrees: Float,
    val verticalFieldOfViewDegrees: Float,
    val zoomRatio: Float,
)

/**
 * Relative-orientation controller for Top-Off gyro presets.
 *
 * Both absolute sensor orientations are remapped into the same display coordinate frame before
 * their relative rotation is calculated. The resulting rotation is decomposed as an axis-angle
 * vector rather than Euler angles, so simultaneous pitch and yaw do not hide the horizontal axis.
 */
class GyroPanPresetController(
    context: Context,
    private val mode: GyroPanMode,
    initialPanX: Float,
    initialPanY: Float,
    initialHorizontalFieldOfViewDegrees: Float = DEFAULT_HORIZONTAL_FOV_DEGREES,
    initialVerticalFieldOfViewDegrees: Float = DEFAULT_VERTICAL_FOV_DEGREES,
    initialZoomRatio: Float = 2f,
    private val onCorrection: (GyroPanCorrection) -> Unit,
) : SensorEventListener {
    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val orientationSensor =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val deviceRotation = FloatArray(9)
    private val referenceRotation = FloatArray(9)
    private val referenceScreenRotation = FloatArray(9)
    private val inverseReferenceScreenRotation = FloatArray(9)
    private val relativeScreenRotation = FloatArray(9)
    private val screenRotation = FloatArray(9)
    private val relativeRotationVector = FloatArray(3)

    private var hasReference = false
    private var screenRotationBucket = -1
    private var lastSensorTimestampNs = 0L
    @Volatile private var sensorEventCount = 0L
    @Volatile private var sensorThread: HandlerThread? = null
    private var lastLoggedError: String? = null

    private var basePanX = initialPanX.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
    private var basePanY = initialPanY.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
    @Volatile private var horizontalFieldOfViewDegrees = sanitizeFieldOfView(initialHorizontalFieldOfViewDegrees)
    @Volatile private var verticalFieldOfViewDegrees = sanitizeFieldOfView(initialVerticalFieldOfViewDegrees)
    @Volatile private var zoomRatio = sanitizeZoom(initialZoomRatio)
    private var lowPassHorizontal = 0f
    private var lowPassVertical = 0f
    private var lowPassRoll = 0f
    private var smoothHorizontal = 0f
    private var smoothVertical = 0f
    private var smoothRoll = 0f
    @Volatile private var lastCorrection = GyroPanCorrection(
        basePanX,
        basePanY,
        0f,
        fixedRollCover = mode == GyroPanMode.HORIZON_LOCK,
    )

    val currentCorrection: GyroPanCorrection
        get() = lastCorrection

    /** Update optical gain without unregistering or re-anchoring the orientation sensor. */
    fun updateOptics(optics: GyroPanOptics) {
        horizontalFieldOfViewDegrees = sanitizeFieldOfView(optics.horizontalFieldOfViewDegrees)
        verticalFieldOfViewDegrees = sanitizeFieldOfView(optics.verticalFieldOfViewDegrees)
        zoomRatio = sanitizeZoom(optics.zoomRatio)
    }

    private fun sanitizeFieldOfView(value: Float): Float =
        value.takeIf { it.isFinite() && it > 0f }?.coerceIn(1f, 179f)
            ?: DEFAULT_HORIZONTAL_FOV_DEGREES

    private fun sanitizeZoom(value: Float): Float =
        value.takeIf { it.isFinite() }?.coerceIn(1f, 20f) ?: 1f

    fun start(): Boolean {
        val sensor = orientationSensor
        if (sensor == null) {
            PLog.e(TAG, "No GAME_ROTATION_VECTOR or ROTATION_VECTOR sensor is available")
            return false
        }
        val callbackThread = HandlerThread("GyroPanSensor-$mode").apply { start() }
        val samplingHandler = Handler(callbackThread.looper)
        val registered = runCatching {
            if (mode == GyroPanMode.POSITION_LOCK) {
                // Position Lock is a stabilization loop, not a gesture recognizer. Request
                // low-latency samples and do not let Android batch them behind old samples.
                sensorManager.registerListener(
                    this,
                    sensor,
                    POSITION_LOCK_SAMPLING_PERIOD_US,
                    0,
                    samplingHandler,
                )
            } else {
                sensorManager.registerListener(
                    this,
                    sensor,
                    SensorManager.SENSOR_DELAY_GAME,
                    samplingHandler,
                )
            }
        }.onFailure { error ->
            PLog.e(TAG, "Failed to register orientation sensor listener", error)
        }.getOrDefault(false)
        if (registered) {
            sensorThread = callbackThread
            // Apply the mode-specific crop immediately. The first sensor event only anchors
            // the reference orientation and intentionally emits no motion correction.
            onCorrection(lastCorrection)
        } else {
            callbackThread.quitSafely()
        }
        PLog.i(
            TAG,
            "START mode=$mode sensor=${sensor.name} type=${sensor.type} vendor=${sensor.vendor} " +
                "delay=${if (mode == GyroPanMode.POSITION_LOCK) "${POSITION_LOCK_SAMPLING_PERIOD_US}us" else "GAME"} " +
                "thread=dedicated registered=$registered"
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

            // Remap BOTH absolute orientations into the same display coordinate frame
            // before taking their relative rotation. remapCoordinateSystem() changes the
            // input basis; remapping only (reference^-1 * current) leaves the reference side
            // in the old basis and can mix horizontal/vertical correction after screen rotation.
            val axes = screenAxes(rotationBucket)
            if (!SensorManager.remapCoordinateSystem(
                    deviceRotation,
                    axes.first,
                    axes.second,
                    screenRotation,
                )
            ) {
                PLog.w(TAG, "Could not remap current gyro orientation for display rotation=$rotationBucket")
                return
            }
            if (!SensorManager.remapCoordinateSystem(
                    referenceRotation,
                    axes.first,
                    axes.second,
                    referenceScreenRotation,
                )
            ) {
                PLog.w(TAG, "Could not remap reference gyro orientation for display rotation=$rotationBucket")
                return
            }

            transpose3x3(referenceScreenRotation, inverseReferenceScreenRotation)
            multiply3x3(
                inverseReferenceScreenRotation,
                screenRotation,
                relativeScreenRotation,
            )
            // Extract axis-angle components in the display frame. Euler-angle extraction
            // can couple yaw into pitch/roll when the phone is already tilted; the rotation
            // vector retains the independent screen X/Y/Z components.
            if (!GyroPanFilterMath.rotationVectorFromMatrix(
                    relativeScreenRotation,
                    relativeRotationVector,
                )
            ) {
                PLog.w(TAG, "Could not extract relative gyro rotation vector for mode=$mode")
                return
            }

            val deltaSeconds = resolveDeltaSeconds(event.timestamp)
            // SensorManager's historical orientation convention is negative X pitch,
            // positive Y roll, and negative Z azimuth for a positive matrix rotation.
            // Position Lock must react to sub-pixel angular changes. The shared dead-zone
            // used by the other presets was suppressing the first 0.04 degrees of motion.
            val precisePositionLock = mode == GyroPanMode.POSITION_LOCK
            val opticalRoll = if (precisePositionLock) {
                -relativeRotationVector[2]
            } else {
                deadZone(-relativeRotationVector[2])
            }
            val verticalAngle = if (precisePositionLock) {
                -relativeRotationVector[0]
            } else {
                deadZone(-relativeRotationVector[0])
            }
            val horizontalAngle = if (precisePositionLock) {
                relativeRotationVector[1]
            } else {
                deadZone(relativeRotationVector[1])
            }
            val correction = calculateCorrection(
                horizontalAngle = horizontalAngle,
                verticalAngle = verticalAngle,
                opticalRoll = opticalRoll,
                deltaSeconds = deltaSeconds,
            )

            sensorEventCount++
            val panUpdateEpsilon = if (precisePositionLock) {
                POSITION_LOCK_UPDATE_EPSILON
            } else {
                UPDATE_EPSILON
            }
            if (
                abs(correction.panX - lastCorrection.panX) >= panUpdateEpsilon ||
                abs(correction.panY - lastCorrection.panY) >= panUpdateEpsilon ||
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
        // Keep the reference in the sensor's native device frame. Both the reference and
        // current matrices are remapped into the same display frame for every sample.
        rotation.copyInto(referenceRotation)
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
        return when (mode) {
            GyroPanMode.POSITION_LOCK,
            GyroPanMode.AUTO_AXIS_BALANCE -> GyroPanCorrection(
                panX = GyroPanFilterMath.positionLockHorizontalPan(
                    basePanX = basePanX,
                    horizontalAngleRadians = horizontalAngle,
                    fieldOfViewDegrees = horizontalFieldOfViewDegrees,
                    zoomRatio = zoomRatio,
                ),
                panY = (
                    basePanY + GyroPanFilterMath.normalizedPanForAngle(
                        verticalAngle, verticalFieldOfViewDegrees, zoomRatio
                    )
                ).coerceIn(-1f, 1f),
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
                // Retain the established high-pass correction: remove only the fast residual.
                val shakeX = horizontalAngle - lowPassHorizontal
                val shakeY = verticalAngle - lowPassVertical
                val shakeRoll = opticalRoll - lowPassRoll
                GyroPanCorrection(
                    panX = (
                        basePanX - GyroPanFilterMath.normalizedPanForAngle(
                            shakeX, horizontalFieldOfViewDegrees, zoomRatio
                        )
                    ).coerceIn(-1f, 1f),
                    panY = (
                        basePanY + GyroPanFilterMath.normalizedPanForAngle(
                            shakeY, verticalFieldOfViewDegrees, zoomRatio
                        )
                    ).coerceIn(-1f, 1f),
                    rollDegrees = (-shakeRoll / DEG_TO_RAD)
                        .coerceIn(-MAX_ANTI_SHAKE_ROLL, MAX_ANTI_SHAKE_ROLL),
                )
            }

            GyroPanMode.HORIZON_LOCK -> GyroPanCorrection(
                panX = basePanX,
                panY = basePanY,
                rollDegrees = (-opticalRoll / DEG_TO_RAD).coerceIn(
                    -GyroPanFilterMath.HORIZON_LOCK_MAX_CORRECTION_DEGREES,
                    GyroPanFilterMath.HORIZON_LOCK_MAX_CORRECTION_DEGREES,
                ),
                fixedRollCover = true,
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
                // The virtual camera follows lowPass(orientation). The crop-window offset
                // relative to the physical camera is lowPass(angle) - angle, not lowPass(angle).
                val followHorizontalOffset = GyroPanFilterMath.smoothFollowOffset(
                    smoothHorizontal, horizontalAngle
                )
                val followVerticalOffset = GyroPanFilterMath.smoothFollowOffset(
                    smoothVertical, verticalAngle
                )
                val followRollOffset = GyroPanFilterMath.smoothFollowOffset(
                    smoothRoll, opticalRoll
                )
                GyroPanCorrection(
                    panX = (
                        basePanX + GyroPanFilterMath.normalizedPanForAngle(
                            followHorizontalOffset, horizontalFieldOfViewDegrees, zoomRatio
                        )
                    ).coerceIn(-1f, 1f),
                    panY = (
                        basePanY - GyroPanFilterMath.normalizedPanForAngle(
                            followVerticalOffset, verticalFieldOfViewDegrees, zoomRatio
                        )
                    ).coerceIn(-1f, 1f),
                    rollDegrees = (followRollOffset / DEG_TO_RAD)
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

    private fun deadZone(value: Float): Float =
        if (abs(value) < DEAD_ZONE_RADIANS) 0f else value

    private companion object {
        const val TAG = "GyroPan"
        const val DEG_TO_RAD = 0.017453292519943295f
        const val DEFAULT_HORIZONTAL_FOV_DEGREES = 60f
        const val DEFAULT_VERTICAL_FOV_DEGREES = 45f
        const val MAX_HORIZON_CORRECTION_DEGREES = 15f
        const val MAX_ANTI_SHAKE_ROLL = 6f
        const val ANTI_SHAKE_TIME_CONSTANT_SECONDS = 0.18f
        const val SMOOTH_FOLLOW_TIME_CONSTANT_SECONDS = 0.22f
        const val DEAD_ZONE_RADIANS = 0.04f * DEG_TO_RAD
        const val UPDATE_EPSILON = 0.0008f
        const val POSITION_LOCK_UPDATE_EPSILON = 0.000005f
        const val POSITION_LOCK_SAMPLING_PERIOD_US = 5_000
        const val ROLL_UPDATE_EPSILON = 0.025f
        const val DEFAULT_DELTA_SECONDS = 1f / 60f
        const val MIN_DELTA_SECONDS = 1f / 500f
        const val MAX_DELTA_SECONDS = 0.1f
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
