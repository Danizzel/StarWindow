package com.starwindow.app.core.calibration

import com.starwindow.app.core.astro.Angles
import com.starwindow.app.core.astro.Horizontal
import com.starwindow.app.core.astro.Rotation3
import com.starwindow.app.core.astro.Vec3
import com.starwindow.app.core.geometry.SphericalGeometry
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One "this is where the phone said it was pointing, this is where it actually was" observation.
 *
 * Both directions are in true-north horizontal coordinates. [measured] must come from the
 * *uncalibrated* pipeline — feeding back an already corrected direction would fit the correction
 * against itself.
 */
data class DirectionPair(
    val measured: Horizontal,
    val reference: Horizontal,
    val label: String = "",
) {
    /** Angular distance between the two, i.e. how far off the phone was for this observation. */
    val errorDeg: Double
        get() = SphericalGeometry.separationDeg(measured, reference)
}

data class AttitudeFitResult(
    val rotation: Rotation3,
    /** Root mean square angular residual after applying the fit, in degrees. */
    val residualDeg: Double,
    val worstResidualDeg: Double,
    val sampleCount: Int,
    /** Largest angular distance between any two reference directions used. */
    val baselineDeg: Double,
) {
    /**
     * With a single observation the fit can only shift the sky, not tilt it — the result is exact
     * for that one direction and degrades away from it. Two or more well separated references also
     * pin down the tilt.
     */
    val correctsTiltToo: Boolean get() = sampleCount >= 2 && baselineDeg >= 10.0
}

/**
 * Least-squares attitude correction (Wahba's problem) from a handful of sighted references.
 *
 * Solved by iteration rather than by an SVD: starting from no correction, each step rotates by the
 * mean of the cross products between the currently corrected measurements and their references.
 * That mean *is* the gradient of the alignment cost, so the iteration walks straight downhill and
 * converges quickly for the small corrections a phone compass needs. It costs a few dozen lines
 * instead of a matrix decomposition, and it is easy to check against known rotations — which the
 * unit tests do.
 */
object AttitudeFit {

    /** Full three-axis fit. Needs at least one pair; two or more also correct the tilt. */
    fun solve(pairs: List<DirectionPair>, iterations: Int = 400): AttitudeFitResult? {
        if (pairs.isEmpty()) return null

        val measured = pairs.map { it.measured.toVector().normalized() }
        val reference = pairs.map { it.reference.toVector().normalized() }

        var rotation = Rotation3.IDENTITY
        repeat(iterations) {
            var omega = Vec3(0.0, 0.0, 0.0)
            for (i in measured.indices) {
                omega += (rotation.apply(measured[i]) cross reference[i])
            }
            omega *= 1.0 / measured.size
            if (omega.length < 1e-12) return@repeat
            rotation = Rotation3.fromRotationVector(omega) * rotation
        }

        return result(rotation, measured, reference, pairs)
    }

    /**
     * Heading-only fit: the correction is constrained to a rotation about the zenith, so altitudes
     * are left alone. This is the honest model when the reference only carries a bearing — a
     * landmark whose compass direction is known from a map, for instance.
     */
    fun solveHeadingOnly(pairs: List<DirectionPair>): AttitudeFitResult? {
        if (pairs.isEmpty()) return null

        // Circular mean of the azimuth differences: averaging degrees directly would break across
        // the 0/360 seam.
        var sinSum = 0.0
        var cosSum = 0.0
        for (pair in pairs) {
            val delta = Math.toRadians(pair.reference.azimuthDeg - pair.measured.azimuthDeg)
            sinSum += sin(delta)
            cosSum += cos(delta)
        }
        if (sqrt(sinSum * sinSum + cosSum * cosSum) < 1e-12) return null

        val offsetDeg = Math.toDegrees(atan2(sinSum, cosSum))
        val rotation = Rotation3.aboutZenith(offsetDeg)

        return result(
            rotation,
            pairs.map { it.measured.toVector().normalized() },
            pairs.map { it.reference.toVector().normalized() },
            pairs,
        )
    }

    private fun result(
        rotation: Rotation3,
        measured: List<Vec3>,
        reference: List<Vec3>,
        pairs: List<DirectionPair>,
    ): AttitudeFitResult {
        var squareSum = 0.0
        var worst = 0.0
        for (i in measured.indices) {
            val corrected = rotation.apply(measured[i])
            val error = Math.toDegrees(
                atan2((corrected cross reference[i]).length, corrected dot reference[i])
            )
            squareSum += error * error
            worst = maxOf(worst, error)
        }

        var baseline = 0.0
        for (i in reference.indices) {
            for (j in i + 1 until reference.size) {
                val separation = Math.toDegrees(
                    atan2((reference[i] cross reference[j]).length, reference[i] dot reference[j])
                )
                baseline = maxOf(baseline, separation)
            }
        }

        return AttitudeFitResult(
            rotation = rotation,
            residualDeg = sqrt(squareSum / measured.size),
            worstResidualDeg = worst,
            sampleCount = pairs.size,
            baselineDeg = baseline,
        )
    }
}

/** Convenience: the heading a pure-zenith correction would apply, wrapped to (-180, 180]. */
fun Rotation3.headingOffsetDeg(): Double =
    Angles.wrapDeg180(Horizontal.fromVector(apply(Horizontal(0.0, 0.0).toVector())).azimuthDeg)
