package com.starwindow.app.core.geometry

import com.starwindow.app.core.astro.Angles
import com.starwindow.app.core.astro.CoordinateTransforms
import com.starwindow.app.core.astro.Equatorial
import com.starwindow.app.core.astro.Horizontal
import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.core.astro.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The geometry of a sky window. All shapes are defined in *horizontal* coordinates (true-north
 * azimuth / altitude), because a window is a hole in the observer's surroundings — the gap
 * between two roofs, a slot between trees, the reachable part of a mount. It stays put while the
 * sky rotates through it, and that rotation is exactly what the transit search reports on.
 */
@Serializable
sealed interface WindowShape {

    /** The points the user actually tapped, in the order they tapped them. */
    val anchors: List<Horizontal>

    /** Direction of the middle of the window. */
    fun center(): Horizontal

    /** True when the given sky direction falls inside the window. */
    fun contains(point: Horizontal): Boolean

    /**
     * A reusable containment test. Shapes whose test needs setup work (the polygon has to build a
     * tangent plane and project its corners) do that once here instead of on every call, which
     * matters when the transit search runs it a few hundred thousand times.
     */
    fun membershipTest(): (Horizontal) -> Boolean = { contains(it) }

    /** Solid angle covered, in square degrees. */
    fun areaSquareDeg(): Double

    /**
     * A closed loop of directions tracing the outline, dense enough that drawing straight screen
     * segments between them looks like the real great-circle boundary.
     */
    fun outline(segments: Int = DEFAULT_OUTLINE_SEGMENTS): List<Horizontal>

    /** Largest angular distance from [center] to the outline, in degrees. */
    fun angularRadiusDeg(): Double = outline(DEFAULT_OUTLINE_SEGMENTS)
        .maxOfOrNull { SphericalGeometry.separationDeg(center(), it) } ?: 0.0
}

/** Enough segments that a drawn outline reads as a smooth curve without wasting projections. */
const val DEFAULT_OUTLINE_SEGMENTS: Int = 24

/** Free-form outline: every tap adds a corner. Concave shapes are fine. */
@Serializable
@SerialName("polygon")
data class PolygonWindow(
    override val anchors: List<Horizontal>,
) : WindowShape {

    init {
        require(anchors.size >= 3) { "A polygon window needs at least three points" }
    }

    override fun center(): Horizontal = SphericalGeometry.centroid(anchors)

    override fun contains(point: Horizontal): Boolean = membershipTest()(point)

    override fun membershipTest(): (Horizontal) -> Boolean {
        val plane = TangentPlane(center())
        val projected = anchors.mapNotNull { plane.project(it) }
        if (projected.size != anchors.size) return { false }
        return { point ->
            val p = plane.project(point)
            p != null && projected.containsPoint(p)
        }
    }

    override fun areaSquareDeg(): Double = abs(SphericalGeometry.signedAreaSquareDeg(anchors))

    /**
     * True when the corners were tapped in an order that makes the outline cross itself.
     *
     * Worth asking before saving: a bow tie reports a far too small area and answers the
     * containment test for the wrong lobe, and neither is visible at a glance on a dark screen.
     */
    val isSelfIntersecting: Boolean
        get() {
            val plane = TangentPlane(center())
            val projected = anchors.mapNotNull { plane.project(it) }
            // Corners on the far hemisphere cannot be judged; that is the >90° limit, not a loop.
            if (projected.size != anchors.size) return false
            return projected.hasSelfIntersection()
        }

    override fun outline(segments: Int): List<Horizontal> {
        val perEdge = (segments / anchors.size).coerceAtLeast(2)
        val result = ArrayList<Horizontal>(anchors.size * perEdge)
        for (i in anchors.indices) {
            val a = anchors[i].toVector().normalized()
            val b = anchors[(i + 1) % anchors.size].toVector().normalized()
            for (s in 0 until perEdge) {
                result += Horizontal.fromVector(slerp(a, b, s.toDouble() / perEdge))
            }
        }
        return result
    }
}

/** A cap on the sky: first tap sets the centre, second tap sets the radius. */
@Serializable
@SerialName("circle")
data class CircleWindow(
    val centerPoint: Horizontal,
    val radiusDeg: Double,
) : WindowShape {

    override val anchors: List<Horizontal>
        get() = listOf(centerPoint, edgePoint())

    override fun center(): Horizontal = centerPoint

    override fun contains(point: Horizontal): Boolean =
        SphericalGeometry.separationDeg(centerPoint, point) <= radiusDeg

    override fun areaSquareDeg(): Double = SphericalGeometry.capAreaSquareDeg(radiusDeg)

    override fun angularRadiusDeg(): Double = radiusDeg

    override fun outline(segments: Int): List<Horizontal> {
        val n = segments.coerceAtLeast(8)
        val c = centerPoint.toVector().normalized()
        val basis = perpendicularBasis(c)
        val r = Math.toRadians(radiusDeg)
        return (0 until n).map { i ->
            val theta = 2.0 * Math.PI * i / n
            val v = c * cos(r) + (basis.first * cos(theta) + basis.second * sin(theta)) * sin(r)
            Horizontal.fromVector(v)
        }
    }

    private fun edgePoint(): Horizontal {
        val c = centerPoint.toVector().normalized()
        val basis = perpendicularBasis(c)
        val r = Math.toRadians(radiusDeg)
        return Horizontal.fromVector(c * cos(r) + basis.second * sin(r))
    }

    companion object {
        /** Builds a circle from the two taps: centre first, then a point on the rim. */
        fun fromCenterAndEdge(center: Horizontal, edge: Horizontal): CircleWindow =
            CircleWindow(center, SphericalGeometry.separationDeg(center, edge))
    }
}

/**
 * An azimuth/altitude aligned box — the natural shape for "the gap between those two buildings,
 * from that roof line up to there". [azimuthStartDeg] is the western edge and the box extends
 * [azimuthSpanDeg] eastwards, so it survives the 0°/360° seam.
 */
@Serializable
@SerialName("box")
data class AltAzBoxWindow(
    val azimuthStartDeg: Double,
    val azimuthSpanDeg: Double,
    val altitudeMinDeg: Double,
    val altitudeMaxDeg: Double,
) : WindowShape {

    override val anchors: List<Horizontal>
        get() = listOf(
            Horizontal(azimuthStartDeg, altitudeMinDeg),
            Horizontal(azimuthStartDeg + azimuthSpanDeg, altitudeMaxDeg),
        )

    override fun center(): Horizontal = Horizontal(
        azimuthDeg = Angles.normalizeDeg(azimuthStartDeg + azimuthSpanDeg / 2.0),
        altitudeDeg = (altitudeMinDeg + altitudeMaxDeg) / 2.0,
    )

    override fun contains(point: Horizontal): Boolean {
        val offset = Angles.normalizeDeg(point.azimuthDeg - azimuthStartDeg)
        return offset <= azimuthSpanDeg &&
            point.altitudeDeg >= altitudeMinDeg &&
            point.altitudeDeg <= altitudeMaxDeg
    }

    override fun areaSquareDeg(): Double =
        SphericalGeometry.altAzBoxAreaSquareDeg(azimuthSpanDeg, altitudeMinDeg, altitudeMaxDeg)

    override fun outline(segments: Int): List<Horizontal> {
        val perEdge = (segments / 4).coerceAtLeast(2)
        val result = ArrayList<Horizontal>(perEdge * 4)
        fun azAt(t: Double) = azimuthStartDeg + azimuthSpanDeg * t
        for (s in 0 until perEdge) result += Horizontal(azAt(s.toDouble() / perEdge), altitudeMinDeg)
        for (s in 0 until perEdge) {
            val t = s.toDouble() / perEdge
            result += Horizontal(azAt(1.0), altitudeMinDeg + (altitudeMaxDeg - altitudeMinDeg) * t)
        }
        for (s in 0 until perEdge) result += Horizontal(azAt(1.0 - s.toDouble() / perEdge), altitudeMaxDeg)
        for (s in 0 until perEdge) {
            val t = s.toDouble() / perEdge
            result += Horizontal(azAt(0.0), altitudeMaxDeg - (altitudeMaxDeg - altitudeMinDeg) * t)
        }
        return result.map { it.normalized() }
    }

    companion object {
        /** Builds the box spanning the two tapped corners the short way around in azimuth. */
        fun fromCorners(a: Horizontal, b: Horizontal): AltAzBoxWindow {
            val delta = Angles.wrapDeg180(b.azimuthDeg - a.azimuthDeg)
            val start = if (delta >= 0) a.azimuthDeg else b.azimuthDeg
            return AltAzBoxWindow(
                azimuthStartDeg = Angles.normalizeDeg(start),
                azimuthSpanDeg = abs(delta),
                altitudeMinDeg = minOf(a.altitudeDeg, b.altitudeDeg),
                altitudeMaxDeg = maxOf(a.altitudeDeg, b.altitudeDeg),
            )
        }
    }
}

/**
 * A saved window: the geometry plus everything needed to reproduce the measurement — where the
 * observer stood, when it was captured, and how trustworthy the compass was at that moment.
 */
@Serializable
data class SkyWindow(
    val id: String,
    val name: String,
    val shape: WindowShape,
    val observer: ObserverLocation,
    val capturedAtMillis: Long,
    /** Magnetic declination applied to get from compass north to true north, in degrees. */
    val magneticDeclinationDeg: Double = 0.0,
    /** Compass accuracy reported by the sensor when the window was closed (0 = unreliable). */
    val compassAccuracy: Int = 0,
    /**
     * Residual of the attitude calibration that was in force at capture, in degrees, or null when
     * the window was drawn uncalibrated. Unlike [compassAccuracy] this is a measured number, and
     * that difference is what lets the detail view quote a real error instead of a guess.
     */
    val calibrationResidualDeg: Double? = null,
    /**
     * True when the compass was disturbed at capture and north was being carried by the gyroscope.
     * Recorded because it is invisible afterwards and it widens the error.
     */
    val headingHeld: Boolean = false,
    /**
     * How long the gyroscope had been carrying north on its own when the window was saved, in
     * seconds. Zero means the compass was being followed normally.
     *
     * The duration matters, not just the fact: held for a few seconds costs nothing, held for a
     * quarter of an hour means the direction had drifted into something worth doubting.
     */
    val headingHeldSeconds: Double = 0.0,
    /** Horizontal field of view of the camera used, for the record. */
    val cameraFovDeg: Double? = null,
    val notes: String = "",
) {
    val centerHorizontal: Horizontal get() = shape.center()

    /**
     * Where the centre of the window pointed on the celestial sphere at capture time, in J2000 —
     * the frame every catalogue and every star atlas is written in, so the number stays usable
     * years after the window was drawn.
     */
    fun centerEquatorialAtCapture(): Equatorial =
        CoordinateTransforms.horizontalToEquatorialJ2000(
            centerHorizontal,
            observer,
            capturedAtMillis,
        )
}

/** Spherical linear interpolation between two unit vectors. */
internal fun slerp(a: Vec3, b: Vec3, t: Double): Vec3 {
    val dot = (a dot b).coerceIn(-1.0, 1.0)
    val omega = kotlin.math.acos(dot)
    if (omega < 1e-9) return a
    val sinOmega = sin(omega)
    return a * (sin((1 - t) * omega) / sinOmega) + b * (sin(t * omega) / sinOmega)
}

/** Two unit vectors perpendicular to [c] and to each other: (roughly east, roughly up). */
internal fun perpendicularBasis(c: Vec3): Pair<Vec3, Vec3> {
    val up = Vec3(0.0, 0.0, 1.0)
    var e = up cross c
    if (e.length < 1e-6) e = Vec3(0.0, 1.0, 0.0) cross c
    val east = e.normalized()
    return east to (c cross east).normalized()
}
