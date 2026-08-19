package com.starwindow.app.core.sensors

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.core.astro.Rotation3
import com.starwindow.app.core.calibration.Calibration
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sin
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * Turns the fused rotation-vector sensor into a stream of [DeviceAttitude].
 *
 * Three things happen here that a naive `getOrientation()` implementation gets wrong:
 *  * the matrix is remapped for the current display rotation, so the maths stays valid in
 *    landscape as well as portrait,
 *  * successive quaternions are slerped together, because the raw sensor jitters by a degree or
 *    two and that is very visible when markers are pinned to the sky,
 *  * the magnetic declination for the observer's position is carried along, so consumers can
 *    convert to true north.
 */
class OrientationTracker(context: Context) {

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val location = AtomicReference<ObserverLocation?>(null)
    private val calibration = AtomicReference(Calibration.NONE)

    /** True when the device can deliver a fused orientation at all. */
    val isAvailable: Boolean get() = preferredSensor() != null

    /** Feed the latest position in so the declination stays right as the user travels. */
    fun updateLocation(observer: ObserverLocation?) {
        location.set(observer)
    }

    /**
     * Feed the measured calibration in. Every attitude from here on carries it, so no consumer can
     * accidentally work with an uncorrected direction.
     */
    fun updateCalibration(value: Calibration) {
        calibration.set(value)
    }

    private fun preferredSensor(): Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)

    fun attitudes(samplingPeriodUs: Int = SensorManager.SENSOR_DELAY_GAME): Flow<DeviceAttitude> =
        callbackFlow {
            val sensor = preferredSensor()
            if (sensor == null) {
                close()
                return@callbackFlow
            }

            val filtered = FloatArray(4)
            var hasSample = false
            val rotation = FloatArray(9)
            val remapped = FloatArray(9)
            var accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
            var declinationDeg = 0.0
            var declinationForLocation: ObserverLocation? = null
            var correction: Rotation3 = Rotation3.IDENTITY
            var correctionFor: Calibration? = null

            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val incoming = quaternionFromEvent(event) ?: return
                    if (!hasSample) {
                        incoming.copyInto(filtered)
                        hasSample = true
                    } else {
                        slerpInto(filtered, incoming, SMOOTHING)
                    }

                    SensorManager.getRotationMatrixFromVector(rotation, filtered)
                    remapForDisplay(rotation, displayRotation(), remapped)

                    val current = location.get()
                    if (current !== declinationForLocation) {
                        declinationForLocation = current
                        declinationDeg = current?.let { declinationDegFor(it) } ?: 0.0
                    }

                    // Rebuilding the correction matrix per event would be wasteful; it only
                    // changes when the user recalibrates.
                    val currentCalibration = calibration.get()
                    if (currentCalibration !== correctionFor) {
                        correctionFor = currentCalibration
                        correction = currentCalibration.correction
                    }

                    trySend(
                        DeviceAttitude(
                            rotationMatrix = remapped.copyOf(),
                            magneticDeclinationDeg = declinationDeg,
                            accuracy = accuracy,
                            timestampMs = System.currentTimeMillis(),
                            correction = correction,
                        )
                    )
                }

                override fun onAccuracyChanged(sensor: Sensor?, newAccuracy: Int) {
                    accuracy = newAccuracy
                }
            }

            sensorManager.registerListener(listener, sensor, samplingPeriodUs)
            awaitClose { sensorManager.unregisterListener(listener) }
        }.conflate()

    private fun declinationDegFor(observer: ObserverLocation): Double =
        GeomagneticField(
            observer.latitudeDeg.toFloat(),
            observer.longitudeDeg.toFloat(),
            observer.elevationM.toFloat(),
            System.currentTimeMillis(),
        ).declination.toDouble()

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            appContext.display?.rotation ?: Surface.ROTATION_0
        } else {
            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.defaultDisplay.rotation
        }

    companion object {
        /**
         * Weight of each new sample. Low enough to kill the sensor jitter, high enough that the
         * overlay does not visibly lag behind a slow pan.
         */
        private const val SMOOTHING = 0.35f

        /**
         * Rotation-vector events carry (x, y, z[, w[, accuracy]]). Older devices omit w, and
         * passing an over-long array to [SensorManager.getRotationMatrixFromVector] used to throw,
         * so we always normalise to exactly four components ourselves.
         */
        internal fun quaternionFromEvent(event: SensorEvent): FloatArray? {
            val v = event.values ?: return null
            if (v.size < 3) return null
            val x = v[0]
            val y = v[1]
            val z = v[2]
            val w = if (v.size >= 4) v[3] else {
                val squared = 1f - x * x - y * y - z * z
                if (squared > 0f) kotlin.math.sqrt(squared) else 0f
            }
            return floatArrayOf(x, y, z, w)
        }

        /**
         * Remaps a device-frame rotation matrix into the frame of the currently displayed
         * orientation, so "screen right" is always +x and "screen up" always +y.
         */
        internal fun remapForDisplay(input: FloatArray, displayRotation: Int, output: FloatArray) {
            val ok = when (displayRotation) {
                Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                    input, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, output
                )
                Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(
                    input, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, output
                )
                Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                    input, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, output
                )
                else -> {
                    input.copyInto(output)
                    true
                }
            }
            if (!ok) input.copyInto(output)
        }

        /** In-place spherical interpolation of [current] towards [target] by [t]. */
        internal fun slerpInto(current: FloatArray, target: FloatArray, t: Float) {
            var dot = current[0] * target[0] + current[1] * target[1] +
                current[2] * target[2] + current[3] * target[3]
            // A quaternion and its negation describe the same rotation; pick the near one.
            val sign = if (dot < 0f) -1f else 1f
            dot = abs(dot)

            if (dot > 0.9995f) {
                for (i in 0..3) current[i] += t * (sign * target[i] - current[i])
            } else {
                val theta = acos(dot.coerceIn(-1f, 1f))
                val sinTheta = sin(theta)
                val a = sin((1f - t) * theta) / sinTheta
                val b = sin(t * theta) / sinTheta
                for (i in 0..3) current[i] = a * current[i] + b * sign * target[i]
            }

            val norm = kotlin.math.sqrt(
                current[0] * current[0] + current[1] * current[1] +
                    current[2] * current[2] + current[3] * current[3]
            )
            if (norm > 1e-6f) for (i in 0..3) current[i] /= norm
        }
    }
}

/** Human readable label for [SensorManager] accuracy constants. */
fun compassAccuracyLabel(accuracy: Int): String = when (accuracy) {
    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "hoch"
    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "mittel"
    SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "niedrig"
    else -> "unzuverlässig"
}
