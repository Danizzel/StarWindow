package com.starwindow.app.core.calibration

import com.starwindow.app.core.astro.Rotation3
import com.starwindow.app.core.astro.Vec3
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * One tap on a fixed feature during a pan.
 *
 * @param worldFromDisplay the attitude at the moment of the tap. Only *relative* differences
 *   between sightings matter, so this may be the raw magnetic-frame matrix: the result is
 *   independent of the compass heading, of the magnetic declination and of any attitude
 *   calibration. That independence is the whole point — this method works indoors, under an
 *   overcast sky, at any time of day.
 * @param offsetXPx horizontal distance from the centre of the view, positive to the right.
 * @param offsetYPx vertical distance from the centre of the view, positive **upwards**.
 */
data class PanSighting(
    val worldFromDisplay: Rotation3,
    val offsetXPx: Double,
    val offsetYPx: Double,
)

data class FovFitResult(
    /** Focal length in view pixels that makes all sightings agree. */
    val focalPx: Double,
    /** How well they agree afterwards: mean angular scatter, in degrees. */
    val residualDeg: Double,
    val sampleCount: Int,
    /** How far the phone actually turned between the outermost sightings. */
    val panAngleDeg: Double,
    /** How far apart the taps were on screen, in pixels. */
    val screenSpanPx: Double,
)

/** Why a pan sweep could not be turned into a usable field of view. */
enum class FovFitProblem(val message: String) {
    TOO_FEW_SIGHTINGS("Mindestens zwei Antippungen desselben Merkmals nötig"),
    PAN_TOO_SMALL("Zu wenig geschwenkt – das Merkmal muss deutlich durchs Bild wandern"),
    POINTS_TOO_CLOSE("Die Antippungen liegen zu dicht beieinander"),
    NO_AGREEMENT("Die Antippungen passen zu keinem Bildfeld – war es wirklich dasselbe Merkmal?"),
}

/**
 * Works out the camera's real focal length from a pan across one fixed feature.
 *
 * The idea: a feature the user taps twice is a single direction in the world. Each tap gives a ray
 * in the phone's frame that depends on the unknown focal length, and the attitude sensor says how
 * the phone turned in between. There is exactly one focal length for which both rays point at the
 * same direction — finding it needs no star, no map and no compass, only the gyroscope's ability to
 * measure a *relative* turn, which is the one thing phone sensors do reliably.
 *
 * Solved by scanning the plausible range and then refining with a golden-section search, rather
 * than analytically: the cost function is cheap, and a scan cannot be fooled by a local minimum the
 * way a derivative-based method could.
 */
object FieldOfViewSolver {

    /** Below this the pan carries too little information to separate focal lengths. */
    const val MIN_PAN_ANGLE_DEG = 4.0

    /** Below this the taps are too close together for the geometry to say anything. */
    const val MIN_SCREEN_SPAN_PX = 80.0

    /** Above this residual the sightings simply do not describe one direction. */
    const val MAX_RESIDUAL_DEG = 1.5

    fun solve(
        sightings: List<PanSighting>,
        baselineFocalPx: Double,
    ): Result<FovFitResult> {
        if (sightings.size < 2) return Result.failure(FovFitException(FovFitProblem.TOO_FEW_SIGHTINGS))
        if (baselineFocalPx <= 0.0) return Result.failure(FovFitException(FovFitProblem.TOO_FEW_SIGHTINGS))

        // Pan first: a short pan also produces a short screen span, and "turn further" is the more
        // useful thing to tell someone than "your taps were too close together".
        val panAngle = maxPanAngleDeg(sightings)
        if (panAngle < MIN_PAN_ANGLE_DEG) {
            return Result.failure(FovFitException(FovFitProblem.PAN_TOO_SMALL))
        }

        val screenSpan = maxScreenSpan(sightings)
        if (screenSpan < MIN_SCREEN_SPAN_PX) {
            return Result.failure(FovFitException(FovFitProblem.POINTS_TOO_CLOSE))
        }

        // Search a wide bracket around the camera's own claim: a factor of four either way covers
        // every plausible reporting error, including a phone that names the wrong lens entirely.
        val low = ln(baselineFocalPx / 4.0)
        val high = ln(baselineFocalPx * 4.0)
        val focal = exp(minimize(low, high) { logFocal -> scatterDeg(sightings, exp(logFocal)) })
        val residual = scatterDeg(sightings, focal)

        if (residual > MAX_RESIDUAL_DEG) {
            return Result.failure(FovFitException(FovFitProblem.NO_AGREEMENT))
        }

        return Result.success(
            FovFitResult(
                focalPx = focal,
                residualDeg = residual,
                sampleCount = sightings.size,
                panAngleDeg = panAngle,
                screenSpanPx = screenSpan,
            )
        )
    }

    /**
     * Mean angular distance of the sighted directions from their common mean, for a given focal
     * length. Zero when every tap really did land on the same point in the world.
     */
    internal fun scatterDeg(sightings: List<PanSighting>, focalPx: Double): Double {
        if (focalPx <= 0.0) return 180.0
        val directions = sightings.map { it.worldDirection(focalPx) }
        var sum = Vec3(0.0, 0.0, 0.0)
        for (d in directions) sum += d
        if (sum.length < 1e-9) return 180.0
        val mean = sum.normalized()

        var squareSum = 0.0
        for (d in directions) {
            val angle = Math.toDegrees(atan2((d cross mean).length, d dot mean))
            squareSum += angle * angle
        }
        return sqrt(squareSum / directions.size)
    }

    /** Golden-section minimisation preceded by a coarse scan, so a local dip cannot trap it. */
    private inline fun minimize(low: Double, high: Double, cost: (Double) -> Double): Double {
        val samples = 240
        var bestX = low
        var bestCost = Double.MAX_VALUE
        for (i in 0..samples) {
            val x = low + (high - low) * i / samples
            val c = cost(x)
            if (c < bestCost) {
                bestCost = c
                bestX = x
            }
        }

        val step = (high - low) / samples
        var a = (bestX - step).coerceAtLeast(low)
        var b = (bestX + step).coerceAtMost(high)

        val phi = (sqrt(5.0) - 1.0) / 2.0
        var c1 = b - phi * (b - a)
        var c2 = a + phi * (b - a)
        var f1 = cost(c1)
        var f2 = cost(c2)
        repeat(80) {
            if (f1 < f2) {
                b = c2; c2 = c1; f2 = f1; c1 = b - phi * (b - a); f1 = cost(c1)
            } else {
                a = c1; c1 = c2; f1 = f2; c2 = a + phi * (b - a); f2 = cost(c2)
            }
        }
        return (a + b) / 2.0
    }

    private fun maxScreenSpan(sightings: List<PanSighting>): Double {
        var span = 0.0
        for (i in sightings.indices) {
            for (j in i + 1 until sightings.size) {
                val dx = sightings[i].offsetXPx - sightings[j].offsetXPx
                val dy = sightings[i].offsetYPx - sightings[j].offsetYPx
                span = maxOf(span, sqrt(dx * dx + dy * dy))
            }
        }
        return span
    }

    private fun maxPanAngleDeg(sightings: List<PanSighting>): Double {
        // The camera axis is -z in the display frame; how far it swung is how far the phone turned.
        val axes = sightings.map { it.worldFromDisplay.apply(Vec3(0.0, 0.0, -1.0)).normalized() }
        var angle = 0.0
        for (i in axes.indices) {
            for (j in i + 1 until axes.size) {
                angle = maxOf(
                    angle,
                    Math.toDegrees(atan2((axes[i] cross axes[j]).length, axes[i] dot axes[j])),
                )
            }
        }
        return angle
    }

    /** Field of view across a view of the given width, for a focal length in view pixels. */
    fun fieldOfViewDeg(focalPx: Double, extentPx: Double): Double =
        2.0 * Math.toDegrees(atan(extentPx / (2.0 * focalPx)))
}

class FovFitException(val problem: FovFitProblem) : Exception(problem.message)

private fun PanSighting.worldDirection(focalPx: Double): Vec3 =
    worldFromDisplay.apply(Vec3(offsetXPx, offsetYPx, -focalPx)).normalized()

/** Kept for readability at call sites that only care whether a residual is acceptable. */
fun FovFitResult.isTrustworthy(): Boolean =
    residualDeg <= FieldOfViewSolver.MAX_RESIDUAL_DEG &&
        panAngleDeg >= FieldOfViewSolver.MIN_PAN_ANGLE_DEG &&
        abs(screenSpanPx) >= FieldOfViewSolver.MIN_SCREEN_SPAN_PX
