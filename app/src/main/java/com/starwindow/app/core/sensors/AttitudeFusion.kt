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
     * Dip angle of the measured field: how far below the horizon the field lines point, in degrees.
     *
     * [fieldDevice] is the magnetometer reading in device axes, [deviceToWorld] the platform's
     * row-major device→world matrix. Only the *tilt* of that matrix is used, and the tilt comes from
     * gravity, so the answer does not depend on the heading — which is what makes this an
     * independent check on the compass rather than a restatement of it.
     */
    fun inclinationDeg(fieldDevice: FloatArray, deviceToWorld: FloatArray): Double? {
        if (fieldDevice.size < 3 || deviceToWorld.size < 9) return null
        var east = 0.0
        var north = 0.0
        var up = 0.0
        for (k in 0..2) {
            east += deviceToWorld[k].toDouble() * fieldDevice[k]
            north += deviceToWorld[3 + k].toDouble() * fieldDevice[k]
            up += deviceToWorld[6 + k].toDouble() * fieldDevice[k]
        }
        val horizontal = kotlin.math.hypot(east, north)
        if (horizontal < 1e-6 && abs(up) < 1e-6) return null
        // Positive downwards, matching GeomagneticField.getInclination().
        return Math.toDegrees(atan2(-up, horizontal))
    }

    /**
     * Whether the measured dip angle matches the geomagnetic model.
     *
     * This is the check the field-strength test cannot make. A magnet, a steel window frame or the
     * soft iron in a phone case adds a vector to the Earth's field: it can *rotate* the result by
     * twenty degrees while barely changing its length, and a magnitude-only gate waves that
     * straight through. The dip angle is fixed by latitude and known to a fraction of a degree, so
     * a reading far away from it means the direction is being bent — which is precisely the error
     * that ends up in the heading.
     */
    fun isInclinationPlausible(measuredDeg: Double, expectedDeg: Double): Boolean =
        abs(measuredDeg - expectedDeg) <= INCLINATION_TOLERANCE_DEG

    /**
     * Tolerance on the dip angle.
     *
     * Wide enough to survive the phone's own residual hard iron and the accelerometer's idea of
     * level while it is being held in a hand, narrow enough to catch anything worth calling a
     * disturbance.
     */
    const val INCLINATION_TOLERANCE_DEG = 12.0

    /**
     * How far north may have wandered after the gyroscope has been carrying it alone for a while.
     *
     * A budget, not a measurement — and deliberately conservative. The fused game rotation vector
     * is bias-compensated by the platform and in practice drifts well under this, but the whole
     * point of the number is to be an upper bound the app can quote honestly while the compass is
     * not being believed. Above [MAX_HELD_DRIFT_DEG] the truthful answer stops being a number at
     * all: the heading is simply no longer known.
     */
    fun heldHeadingDriftDeg(heldSeconds: Double): Double =
        (heldSeconds / 60.0 * GYRO_DRIFT_DEG_PER_MINUTE).coerceIn(0.0, MAX_HELD_DRIFT_DEG)

    /** Replace with a measured figure once the drift has been characterised on real hardware. */
    const val GYRO_DRIFT_DEG_PER_MINUTE = 0.5

    const val MAX_HELD_DRIFT_DEG = 30.0

    /**
     * How much hard iron the platform is having to subtract before the compass is usable at all.
     *
     * `TYPE_MAGNETIC_FIELD_UNCALIBRATED` hands over its own hard-iron estimate, and its size is the
     * one number that separates the two failure modes the user can act on differently: a large,
     * settled offset is the phone's own speaker and camera magnets and is nothing to worry about,
     * while an offset this large *together with* an accuracy the platform calls poor means the
     * estimate has not converged — and that one is fixed in five seconds by waving a figure eight.
     */
    const val HARD_IRON_NOTABLE_MICRO_TESLA = 40.0f

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
