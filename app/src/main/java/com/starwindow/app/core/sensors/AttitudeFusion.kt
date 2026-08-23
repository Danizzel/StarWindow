package com.starwindow.app.core.sensors

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * The arithmetic behind a steady sky overlay.
 *
 * The phone offers two fused orientations and both are bad in a different way. `ROTATION_VECTOR`
 * knows where north is because it uses the magnetometer — and therefore twitches by a degree or two
 * whenever a car, a radiator or the phone's own speaker passes near it. `GAME_ROTATION_VECTOR`
 * leaves the magnetometer out: rock steady over seconds and minutes, but its idea of "north" is
 * arbitrary and drifts slowly with the gyroscope's bias.
 *
 * Splitting them along the frequency axis gets the best of both. The gyroscope carries the
 * orientation from moment to moment, and the compass is used only to pin down **one number** — the
 * heading offset between the two frames — through a filter slow enough that no passing disturbance
 * can move it. That is the standard complementary-filter argument, applied to the single degree of
 * freedom the two sensors actually disagree about.
 *
 * All of it is plain arithmetic on the matrices and quaternions the platform hands over, so it can
 * be tested without a device; only the wiring in [OrientationTracker] needs real hardware.
 */
object AttitudeFusion {

    // --- Smoothing ------------------------------------------------------------------------------

    /** Time constant used while the phone is being held still: heavy smoothing, jitter gone. */
    const val STILL_TIME_CONSTANT_S = 0.30

    /** Time constant while panning: almost none, so the overlay does not lag behind the image. */
    const val FAST_TIME_CONSTANT_S = 0.02

    private const val STILL_RATE_DEG_S = 1.5
    private const val FAST_RATE_DEG_S = 25.0

    /**
     * Weight of the incoming sample for one smoothing step.
     *
     * Derived from the elapsed time rather than fixed, because sensor delivery rates differ by a
     * factor of three between devices: a constant weight makes the same code smooth heavily on a
     * slow phone and barely at all on a fast one, which is exactly the kind of difference that is
     * impossible to debug in the field.
     */
    fun alphaFor(dtSeconds: Double, timeConstantSeconds: Double): Float {
        if (dtSeconds <= 0.0) return 0f
        if (timeConstantSeconds <= 1e-6) return 1f
        return (1.0 - exp(-dtSeconds / timeConstantSeconds)).coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * How hard to smooth at a given turn rate.
     *
     * Still hands need heavy smoothing — there is no real motion to preserve, only noise. A pan
     * needs almost none, because there the same filter shows up as the overlay sliding behind the
     * camera image, which reads as "the app is broken" far more loudly than jitter ever does.
     */
    fun timeConstantFor(rateDegPerSecond: Double): Double {
        val t = ((rateDegPerSecond - STILL_RATE_DEG_S) / (FAST_RATE_DEG_S - STILL_RATE_DEG_S))
            .coerceIn(0.0, 1.0)
        return STILL_TIME_CONSTANT_S + t * (FAST_TIME_CONSTANT_S - STILL_TIME_CONSTANT_S)
    }

    /** Angle between two unit quaternions, in degrees. */
    fun angleBetweenDeg(a: FloatArray, b: FloatArray): Double {
        val dot = abs(a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]).toDouble()
        return Math.toDegrees(2.0 * acos(dot.coerceIn(0.0, 1.0)))
    }

    /** In-place spherical interpolation of [current] towards [target] by [t]. */
    fun slerpInto(current: FloatArray, target: FloatArray, t: Float) {
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

    // --- Heading fusion -------------------------------------------------------------------------

    /**
     * The rotation about the world's vertical that takes the gyroscope's frame onto the compass's.
     *
     * Both matrices are the platform's row-major device→world form and describe the same physical
     * orientation; they can only differ by a turn about the up axis, because gravity pins the other
     * two degrees of freedom in both. So `D = magnetic · gyro⁻¹` is that turn, and its angle falls
     * straight out of where it sends the world's x axis.
     *
     * Returned in degrees, wrapped to (-180, 180].
     */
    fun headingOffsetDeg(magneticFrame: FloatArray, gyroFrame: FloatArray): Double {
        // First column of magnetic · gyroᵀ.
        var dxx = 0.0
        var dyx = 0.0
        for (k in 0..2) {
            dxx += magneticFrame[k].toDouble() * gyroFrame[k]
            dyx += magneticFrame[3 + k].toDouble() * gyroFrame[k]
        }
        return Math.toDegrees(atan2(dyx, dxx))
    }

    /**
     * Turns a device→world matrix about the world's vertical by [headingDeg], writing the result
     * into [out]. This is what puts the gyroscope's steady attitude back onto true north.
     */
    fun applyHeading(frame: FloatArray, headingDeg: Double, out: FloatArray) {
        val a = Math.toRadians(headingDeg)
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        for (column in 0..2) {
            val x = frame[column]
            val y = frame[3 + column]
            out[column] = c * x - s * y
            out[3 + column] = s * x + c * y
            out[6 + column] = frame[6 + column]
        }
    }

    /** Circular blend of two headings — averaging degrees directly breaks across the 0/360 seam. */
    fun blendHeadingDeg(currentDeg: Double, targetDeg: Double, alpha: Float): Double {
        var delta = (targetDeg - currentDeg) % 360.0
        if (delta > 180.0) delta -= 360.0
        if (delta < -180.0) delta += 360.0
        var blended = (currentDeg + delta * alpha) % 360.0
        if (blended > 180.0) blended -= 360.0
        if (blended <= -180.0) blended += 360.0
        return blended
    }

    // --- Magnetic health ------------------------------------------------------------------------

    /**
     * Whether a measured field strength is consistent with the geomagnetic model for this place.
     *
     * The Earth's field is between roughly 25 and 65 µT and the model knows which it should be
     * here to within a per cent. A reading well away from that is not a compass error to be
     * averaged down — it is iron, a magnet or a speaker nearby, and the only sane response is to
     * stop believing the compass until it goes away. The tolerance is deliberately generous
     * because a phone's own hard-iron offset already eats part of the budget.
     */
    fun isFieldPlausible(measuredMicroTesla: Float, expectedMicroTesla: Float): Boolean {
        if (expectedMicroTesla <= 0f) return true
        val tolerance = 0.30f * expectedMicroTesla + 5f
        return abs(measuredMicroTesla - expectedMicroTesla) <= tolerance
    }

    /**
     * Time constant for tracking the heading offset.
     *
     * Ten seconds: long enough that walking past a parked car cannot drag the sky with it, short
     * enough to absorb the gyroscope's bias drift, which on a phone is a fraction of a degree per
     * minute.
     */
    const val HEADING_TIME_CONSTANT_S = 10.0

    /**
     * Above this turn rate the heading offset is left alone.
     *
     * The two fused sensors do not have the same latency, so while the phone is swinging they
     * disagree by an amount that is pure lag rather than compass error. Feeding that into the
     * filter would teach it a heading offset that only exists during motion.
     */
    const val HEADING_UPDATE_MAX_RATE_DEG_S = 8.0
}
