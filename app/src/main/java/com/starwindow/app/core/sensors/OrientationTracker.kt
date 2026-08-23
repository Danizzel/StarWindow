package com.starwindow.app.core.sensors

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.core.astro.Rotation3
import com.starwindow.app.core.calibration.Calibration
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * Turns the phone's motion sensors into a stream of [DeviceAttitude].
 *
 * The interesting decision here is *which* sensors, and it is the difference between an overlay
 * that sits on the sky and one that shivers on it.
 *
 * The obvious choice, `TYPE_ROTATION_VECTOR`, folds the magnetometer into every sample. That buys
 * an absolute north — and pays for it with the magnetometer's noise on all three axes, at full
 * rate, for ever. Point a phone at a star and the marker wanders by a degree or two; walk past a
 * car and the whole sky lurches.
 *
 * So it is used for one thing only. `TYPE_GAME_ROTATION_VECTOR` — gyroscope and accelerometer, no
 * magnetometer — carries the orientation, because over the seconds and minutes that matter for
 * aiming a camera it is the steadier instrument by an order of magnitude. The compass is then
 * consulted solely for the **heading offset** between that frame and true north, through a ten
 * second filter, and only while the magnetic field actually looks like the Earth's. A passing
 * disturbance can no longer move the sky; it just means north stops being refreshed for a moment
 * while the gyroscope carries it, which is exactly the right failure mode.
 *
 * On top of that:
 *  * the matrix is remapped for the current display rotation, so the maths stays valid in landscape
 *    as well as portrait,
 *  * smoothing runs on a **time constant** rather than a fixed weight, so it behaves the same on a
 *    phone delivering 30 samples a second and one delivering 200, and adapts to how fast the phone
 *    is actually turning: heavy when still, almost absent during a pan,
 *  * the magnetic declination for the observer's position is carried along, so consumers can
 *    convert to true north.
 */
class OrientationTracker(context: Context) {

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val location = AtomicReference<ObserverLocation?>(null)
    private val calibration = AtomicReference(Calibration.NONE)

    /**
     * True when the device can deliver a fused orientation at all.
     *
     * The compass is the requirement, not the gyroscope: without it there is no north, and a sky
     * turned by an unknown amount is worse than no sky at all.
     */
    val isAvailable: Boolean get() = compassSensor() != null

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

    /** Gyroscope-backed orientation without a magnetometer. Absent on some budget devices. */
    private fun motionSensor(): Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    /** Orientation that knows where north is. */
    private fun compassSensor(): Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)

    fun attitudes(samplingPeriodUs: Int = SensorManager.SENSOR_DELAY_GAME): Flow<DeviceAttitude> =
        callbackFlow {
            val compass = compassSensor()
            if (compass == null) {
                close()
                return@callbackFlow
            }
            val motion = motionSensor()

            // Which sensor drives the output. The gyroscope one when it exists; the compass alone
            // otherwise, in which case there is no heading offset to track and this degrades to the
            // old behaviour with better smoothing.
            val fusing = motion != null
            val source = when {
                fusing -> AttitudeSource.GYRO_WITH_COMPASS_HEADING
                compass.type == Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> AttitudeSource.GEOMAGNETIC
                else -> AttitudeSource.FUSED_COMPASS
            }
            val primary = motion ?: compass

            val state = FusionState(fusing, source)
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    when (event.sensor.type) {
                        Sensor.TYPE_MAGNETIC_FIELD -> state.onField(event)
                        primary.type -> state.onPrimary(event, ::emit)
                        else -> state.onCompass(event)
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, newAccuracy: Int) {
                    // Only the compass's accuracy says anything about where north is; the game
                    // rotation vector reports its own and it means something else entirely.
                    if (sensor?.type != Sensor.TYPE_GAME_ROTATION_VECTOR) {
                        state.accuracy = newAccuracy
                    }
                }

                private fun emit(attitude: DeviceAttitude) {
                    trySend(attitude)
                }
            }

            sensorManager.registerListener(listener, primary, samplingPeriodUs)
            if (fusing) {
                // The heading offset moves at the pace of the gyroscope's bias drift, so there is
                // nothing to gain from sampling the compass fast — and a real battery cost.
                sensorManager.registerListener(listener, compass, SensorManager.SENSOR_DELAY_UI)
            }
            sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
                sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
            }

            awaitClose { sensorManager.unregisterListener(listener) }
        }.conflate()

    /**
     * Everything the listener has to remember between events.
     *
     * A class rather than a pile of captured locals so the ordering is visible: the compass and the
     * magnetometer only ever write into it, and one path — [onPrimary] — reads it and produces an
     * attitude.
     */
    private inner class FusionState(
        private val fusing: Boolean,
        private val source: AttitudeSource,
    ) {
        var accuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE

        private val smoothed = FloatArray(4)
        private val previousRaw = FloatArray(4)
        private var hasSample = false

        private val rotation = FloatArray(9)
        private val headed = FloatArray(9)
        private val remapped = FloatArray(9)
        private val compassFrame = FloatArray(9)
        private var hasCompassFrame = false

        private var headingOffsetDeg = 0.0
        private var hasHeading = false
        private var headingHeld = false

        private var fieldMicroTesla: Float? = null
        private var expectedFieldMicroTesla: Float? = null

        private var lastEventNanos = 0L
        private var rateDegPerSecond = 0.0

        private var declinationDeg = 0.0
        private var declinationForLocation: ObserverLocation? = null
        private var correction: Rotation3 = Rotation3.IDENTITY
        private var correctionFor: Calibration? = null

        fun onField(event: SensorEvent) {
            val v = event.values ?: return
            if (v.size < 3) return
            fieldMicroTesla = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        }

        fun onCompass(event: SensorEvent) {
            val quaternion = quaternionFromEvent(event) ?: return
            SensorManager.getRotationMatrixFromVector(compassFrame, quaternion)
            hasCompassFrame = true
            // Taken from the event as well as from onAccuracyChanged: the callback is delivered on
            // registration on most devices, but "most" is not a guarantee, and an accuracy stuck at
            // "unreliable" would keep the heading from ever being refreshed.
            accuracy = event.accuracy
        }

        fun onPrimary(event: SensorEvent, emit: (DeviceAttitude) -> Unit) {
            val incoming = quaternionFromEvent(event) ?: return
            // Without the gyroscope the primary sensor *is* the compass, so its accuracy counts.
            if (!fusing) accuracy = event.accuracy

            val dtSeconds = if (lastEventNanos == 0L) {
                0.0
            } else {
                ((event.timestamp - lastEventNanos) / 1e9).coerceIn(0.0, 0.5)
            }
            lastEventNanos = event.timestamp

            if (!hasSample) {
                incoming.copyInto(smoothed)
                incoming.copyInto(previousRaw)
                hasSample = true
            } else {
                if (dtSeconds > 0.0) {
                    rateDegPerSecond = AttitudeFusion.angleBetweenDeg(previousRaw, incoming) / dtSeconds
                }
                incoming.copyInto(previousRaw)
                AttitudeFusion.slerpInto(
                    smoothed,
                    incoming,
                    AttitudeFusion.alphaFor(dtSeconds, AttitudeFusion.timeConstantFor(rateDegPerSecond)),
                )
            }

            SensorManager.getRotationMatrixFromVector(rotation, smoothed)

            refreshLocationDerived()
            val worldFrame = if (fusing) {
                updateHeading(dtSeconds)
                // Until the first compass sample arrives there is no north, and a sky turned by an
                // unknown amount is worse than a blank one. It is a matter of milliseconds.
                if (!hasHeading) return
                AttitudeFusion.applyHeading(rotation, headingOffsetDeg, headed)
                headed
            } else {
                rotation
            }

            remapForDisplay(worldFrame, displayRotation(), remapped)
            refreshCalibration()

            emit(
                DeviceAttitude(
                    rotationMatrix = remapped.copyOf(),
                    magneticDeclinationDeg = declinationDeg,
                    accuracy = accuracy,
                    timestampMs = System.currentTimeMillis(),
                    correction = correction,
                    source = source,
                    fieldMicroTesla = fieldMicroTesla,
                    expectedFieldMicroTesla = expectedFieldMicroTesla,
                    headingHeld = fusing && hasHeading && headingHeld,
                )
            )
        }

        /**
         * Tracks the offset between the gyroscope's arbitrary north and the compass's.
         *
         * Three gates, and each one exists because of a way the naive version goes wrong: a compass
         * the platform itself calls unreliable is not worth listening to, a field that does not
         * look like the Earth's means iron rather than error, and a phone that is mid-swing shows a
         * difference that is the two sensors' latency rather than any heading at all.
         */
        private fun updateHeading(dtSeconds: Double) {
            if (!hasCompassFrame) return
            val measured = AttitudeFusion.headingOffsetDeg(compassFrame, rotation)

            if (!hasHeading) {
                // First fix: take whatever the compass says, ungated. A heading that is a few
                // degrees off because the phone happened to be moving is worth having — the filter
                // refines it within seconds — whereas applying the gates here would mean showing no
                // sky at all until the user happens to hold still over an undisturbed field.
                headingOffsetDeg = measured
                hasHeading = true
                headingHeld = false
                return
            }

            val trustworthy = accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM &&
                fieldLooksLikeEarth() &&
                rateDegPerSecond <= AttitudeFusion.HEADING_UPDATE_MAX_RATE_DEG_S
            // Only a disturbance or a compass Android itself distrusts counts as "held"; merely
            // swinging the phone about is normal and must not raise a warning.
            headingHeld = !fieldLooksLikeEarth() ||
                accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
            if (!trustworthy) return

            headingOffsetDeg = AttitudeFusion.blendHeadingDeg(
                headingOffsetDeg,
                measured,
                AttitudeFusion.alphaFor(dtSeconds, AttitudeFusion.HEADING_TIME_CONSTANT_S),
            )
        }

        private fun fieldLooksLikeEarth(): Boolean {
            val measured = fieldMicroTesla ?: return true
            val expected = expectedFieldMicroTesla ?: return true
            return AttitudeFusion.isFieldPlausible(measured, expected)
        }

        private fun refreshLocationDerived() {
            val current = location.get()
            if (current === declinationForLocation) return
            declinationForLocation = current
            if (current == null) {
                declinationDeg = 0.0
                expectedFieldMicroTesla = null
                return
            }
            val field = GeomagneticField(
                current.latitudeDeg.toFloat(),
                current.longitudeDeg.toFloat(),
                current.elevationM.toFloat(),
                System.currentTimeMillis(),
            )
            declinationDeg = field.declination.toDouble()
            // The model reports nanotesla, the sensor microtesla.
            expectedFieldMicroTesla = field.fieldStrength / 1000f
        }

        private fun refreshCalibration() {
            // Rebuilding the correction matrix per event would be wasteful; it only changes when
            // the user recalibrates.
            val currentCalibration = calibration.get()
            if (currentCalibration === correctionFor) return
            correctionFor = currentCalibration
            correction = currentCalibration.correction
        }
    }

    // The application context is not a visual context, so asking it for its display throws on
    // API 30+. The display manager answers from any context and needs no version split.
    private fun displayRotation(): Int =
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0

    companion object {

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
                if (squared > 0f) sqrt(squared) else 0f
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

        /** Kept for the tests that were written against it; the arithmetic lives in [AttitudeFusion]. */
        internal fun slerpInto(current: FloatArray, target: FloatArray, t: Float) =
            AttitudeFusion.slerpInto(current, target, t)
    }
}

/** Human readable label for [SensorManager] accuracy constants. */
fun compassAccuracyLabel(accuracy: Int): String = when (accuracy) {
    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "hoch"
    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "mittel"
    SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "niedrig"
    else -> "unzuverlässig"
}
