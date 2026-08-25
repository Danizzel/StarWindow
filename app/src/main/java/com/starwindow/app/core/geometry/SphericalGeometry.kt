package com.starwindow.app.core.geometry

import com.starwindow.app.core.astro.Horizontal
import com.starwindow.app.core.astro.Vec3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos

/**
 * Small helpers for doing geometry *on the sphere* instead of on the screen. Everything that
 * decides "is this object inside the window?" goes through here, because doing it in raw
 * azimuth/altitude degrees breaks near the zenith and across the 0°/360° seam.
 */
object SphericalGeometry {

    /** Angular distance between two sky directions, in degrees. */
    fun separationDeg(a: Horizontal, b: Horizontal): Double =
        separationDeg(a.toVector(), b.toVector())

    fun separationDeg(a: Vec3, b: Vec3): Double {
        val na = a.normalized()
        val nb = b.normalized()
        // atan2 form stays accurate for very small separations, unlike plain acos(dot).
        val cross = (na cross nb).length
        val dot = na dot nb
        return Math.toDegrees(atan2(cross, dot))
    }

    /** Direction "in the middle" of a set of directions (normalized vector sum). */
    fun centroid(points: List<Horizontal>): Horizontal {
        require(points.isNotEmpty()) { "centroid of an empty point list" }
        var sum = Vec3(0.0, 0.0, 0.0)
        for (p in points) sum += p.toVector()
        if (sum.length < 1e-9) return points.first()
        return Horizontal.fromVector(sum)
    }

    /**
     * Signed area of a spherical polygon in square degrees, via the Van Oosterom & Strackee
     * excess formula summed over a triangle fan. Handles concave outlines; the sign follows the
     * winding order, so callers normally take the absolute value.
     */
    fun signedAreaSquareDeg(points: List<Horizontal>): Double {
        if (points.size < 3) return 0.0
        val v = points.map { it.toVector().normalized() }
        var steradians = 0.0
        for (i in 1 until v.size - 1) {
            steradians += triangleExcess(v[0], v[i], v[i + 1])
        }
        val degPerRad = 180.0 / Math.PI
        return steradians * degPerRad * degPerRad
    }

    private fun triangleExcess(a: Vec3, b: Vec3, c: Vec3): Double {
        val numerator = a dot (b cross c)
        val denominator = 1.0 + (a dot b) + (b dot c) + (c dot a)
        return 2.0 * atan2(numerator, denominator)
    }

    /** Area of a spherical cap of the given angular radius, in square degrees. */
    fun capAreaSquareDeg(radiusDeg: Double): Double {
        val steradians = 2.0 * Math.PI * (1.0 - cos(Math.toRadians(radiusDeg)))
        val degPerRad = 180.0 / Math.PI
        return steradians * degPerRad * degPerRad
    }

    /**
     * Area of an azimuth/altitude aligned box, in square degrees. Exact: the altitude band
     * contributes sin(altMax) - sin(altMin).
     */
    fun altAzBoxAreaSquareDeg(
        azimuthWidthDeg: Double,
        altitudeMinDeg: Double,
        altitudeMaxDeg: Double,
    ): Double {
        val steradians = Math.toRadians(azimuthWidthDeg) *
            (kotlin.math.sin(Math.toRadians(altitudeMaxDeg)) - kotlin.math.sin(Math.toRadians(altitudeMinDeg)))
        val degPerRad = 180.0 / Math.PI
        return abs(steradians) * degPerRad * degPerRad
    }

    /** Angle between two directions clamped for callers that only need a rough value. */
    fun angleBetweenDeg(a: Vec3, b: Vec3): Double =
        Math.toDegrees(acos(((a.normalized()) dot (b.normalized())).coerceIn(-1.0, 1.0)))
}

/**
 * Gnomonic (tangent plane) projection around a centre direction. Great circles become straight
 * lines, which is exactly what we need to run a flat point-in-polygon test on a sky polygon.
 * Only valid for the hemisphere around the centre; points further away return `null`.
 */
class TangentPlane(val center: Horizontal) {

    private val c: Vec3 = center.toVector().normalized()
    private val east: Vec3
    private val north: Vec3

    init {
        val up = Vec3(0.0, 0.0, 1.0)
        var e = up cross c
        if (e.length < 1e-6) {
            // Looking straight up or down: any perpendicular basis will do.
            e = Vec3(0.0, 1.0, 0.0) cross c
        }
        east = e.normalized()
        north = (c cross east).normalized()
    }

    /** Projects a direction onto the tangent plane; `null` when it lies on the far hemisphere. */
    fun project(point: Horizontal): PlanarPoint? {
        val v = point.toVector().normalized()
        val d = v dot c
        if (d <= 1e-6) return null
        return PlanarPoint((v dot east) / d, (v dot north) / d)
    }

    /** Inverse of [project]. */
    fun unproject(point: PlanarPoint): Horizontal =
        Horizontal.fromVector(c + east * point.x + north * point.y)
}

/** A point on a [TangentPlane]; units are tan(angle), i.e. radians for small angles. */
data class PlanarPoint(val x: Double, val y: Double)

/**
 * True when the closed outline through these points crosses itself.
 *
 * Tapping the corners of a window out of order produces a bow tie rather than the shape the user
 * meant: the area comes out too small (the two lobes cancel in the signed sum) and the containment
 * test answers "inside" for the wrong patch of sky. Both failures are silent, and neither is
 * obvious from the drawn outline in the dark — hence the check.
 *
 * Only *proper* crossings count. Edges that merely touch at a shared corner, or that lie along one
 * another, are the degenerate cases of a legitimate outline and must not raise the alarm.
 */
fun List<PlanarPoint>.hasSelfIntersection(): Boolean {
    if (size < 4) return false
    for (i in indices) {
        val a1 = this[i]
        val a2 = this[(i + 1) % size]
        // Start at i + 2: edge i and edge i + 1 share a corner by construction.
        for (j in i + 2 until size) {
            // The last edge closes back onto the first, so those two are neighbours as well.
            if (i == 0 && j == size - 1) continue
            if (segmentsCross(a1, a2, this[j], this[(j + 1) % size])) return true
        }
    }
    return false
}

/** Strict segment crossing: the two must genuinely pass through each other. */
private fun segmentsCross(a1: PlanarPoint, a2: PlanarPoint, b1: PlanarPoint, b2: PlanarPoint): Boolean {
    fun side(from: PlanarPoint, to: PlanarPoint, p: PlanarPoint): Double =
        (to.x - from.x) * (p.y - from.y) - (to.y - from.y) * (p.x - from.x)

    val d1 = side(a1, a2, b1)
    val d2 = side(a1, a2, b2)
    val d3 = side(b1, b2, a1)
    val d4 = side(b1, b2, a2)
    return ((d1 > 0.0 && d2 < 0.0) || (d1 < 0.0 && d2 > 0.0)) &&
        ((d3 > 0.0 && d4 < 0.0) || (d3 < 0.0 && d4 > 0.0))
}

/** Even–odd ray casting test on the tangent plane. */
fun List<PlanarPoint>.containsPoint(p: PlanarPoint): Boolean {
    if (size < 3) return false
    var inside = false
    var j = size - 1
    for (i in indices) {
        val a = this[i]
        val b = this[j]
        val straddles = (a.y > p.y) != (b.y > p.y)
        if (straddles) {
            val xCross = a.x + (p.y - a.y) / (b.y - a.y) * (b.x - a.x)
            if (p.x < xCross) inside = !inside
        }
        j = i
    }
    return inside
}
