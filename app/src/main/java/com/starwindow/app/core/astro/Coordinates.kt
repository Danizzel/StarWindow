package com.starwindow.app.core.astro

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.serialization.Serializable

/**
 * A direction on the local sky.
 *
 * @param azimuthDeg  0 = true north, growing towards east (90 = E, 180 = S, 270 = W).
 * @param altitudeDeg 0 = horizon, +90 = zenith, negative = below the horizon.
 */
@Serializable
data class Horizontal(
    val azimuthDeg: Double,
    val altitudeDeg: Double,
) {
    fun normalized(): Horizontal = Horizontal(
        azimuthDeg = Angles.normalizeDeg(azimuthDeg),
        altitudeDeg = altitudeDeg.coerceIn(-90.0, 90.0),
    )

    /** Unit vector in the local ENU frame: x = east, y = north, z = up. */
    fun toVector(): Vec3 {
        val alt = Math.toRadians(altitudeDeg)
        val az = Math.toRadians(azimuthDeg)
        val cosAlt = cos(alt)
        return Vec3(cosAlt * sin(az), cosAlt * cos(az), sin(alt))
    }

    companion object {
        /** Inverse of [toVector]; the vector does not need to be normalized. */
        fun fromVector(v: Vec3): Horizontal {
            val n = v.normalized()
            return Horizontal(
                azimuthDeg = Angles.normalizeDeg(Math.toDegrees(kotlin.math.atan2(n.x, n.y))),
                altitudeDeg = Math.toDegrees(kotlin.math.asin(n.z.coerceIn(-1.0, 1.0))),
            )
        }
    }
}

/**
 * A direction on the celestial sphere (J2000 / "of date" is treated as equivalent here; the
 * precession error over a few decades stays far below the pointing accuracy of a phone).
 *
 * @param raDeg  right ascension in degrees, 0..360 (1 h = 15 deg).
 * @param decDeg declination in degrees, -90..+90.
 */
@Serializable
data class Equatorial(
    val raDeg: Double,
    val decDeg: Double,
) {
    val raHours: Double get() = raDeg / 15.0

    fun normalized(): Equatorial = Equatorial(
        raDeg = Angles.normalizeDeg(raDeg),
        decDeg = decDeg.coerceIn(-90.0, 90.0),
    )

    /**
     * Unit vector in the equatorial frame: x towards the vernal equinox, y 90° east of it in the
     * equatorial plane, z towards the north celestial pole.
     *
     * Precession is a rotation of that frame, and a rotation is far easier to get right — and to
     * invert — on vectors than on a pair of angles with a wrap at 360°.
     */
    fun toVector(): Vec3 {
        val ra = Math.toRadians(raDeg)
        val dec = Math.toRadians(decDeg)
        val cosDec = cos(dec)
        return Vec3(cosDec * cos(ra), cosDec * sin(ra), sin(dec))
    }

    companion object {
        /** Inverse of [toVector]; the vector does not need to be normalized. */
        fun fromVector(v: Vec3): Equatorial {
            val n = v.normalized()
            return Equatorial(
                raDeg = Angles.normalizeDeg(Math.toDegrees(kotlin.math.atan2(n.y, n.x))),
                decDeg = Math.toDegrees(kotlin.math.asin(n.z.coerceIn(-1.0, 1.0))),
            )
        }
    }
}

/** Where the observer stands. Height is only used for a small refraction/horizon-dip correction. */
@Serializable
data class ObserverLocation(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val elevationM: Double = 0.0,
    /** Horizontal accuracy of the fix in metres, or null when entered manually. */
    val accuracyM: Float? = null,
    /** True when the user typed the position instead of using a GPS fix. */
    val manual: Boolean = false,
) {
    /**
     * Great-circle distance to another position, in kilometres.
     *
     * Used to ask whether a compass calibration measured somewhere else still applies here. Built
     * on the same unit-vector machinery as everything else rather than on a latitude/longitude
     * formula, so it cannot break at the date line or near the poles.
     */
    fun distanceKmTo(other: ObserverLocation): Double {
        val a = unitVector()
        val b = other.unitVector()
        val angleRad = kotlin.math.atan2((a cross b).length, a dot b)
        return Math.toDegrees(angleRad) * KM_PER_DEGREE
    }

    /** Position on the unit sphere; the axis convention does not matter, only the angle does. */
    private fun unitVector(): Vec3 {
        val lat = Math.toRadians(latitudeDeg)
        val lon = Math.toRadians(longitudeDeg)
        val cosLat = cos(lat)
        return Vec3(cosLat * cos(lon), cosLat * sin(lon), sin(lat))
    }

    companion object {
        /** One degree of great circle on Earth, in kilometres (mean radius 6371 km). */
        const val KM_PER_DEGREE = 111.195

        /** Fallback so the app stays usable before the first location fix (Greenwich). */
        val UNKNOWN = ObserverLocation(51.4779, 0.0, 0.0, null, manual = true)
    }
}

/** Minimal 3-component vector; used for all spherical maths so we avoid sign mistakes. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    val length: Double get() = kotlin.math.sqrt(x * x + y * y + z * z)

    fun normalized(): Vec3 {
        val l = length
        return if (l < 1e-12) Vec3(0.0, 0.0, 1.0) else Vec3(x / l, y / l, z / l)
    }

    infix fun dot(o: Vec3): Double = x * o.x + y * o.y + z * o.z

    infix fun cross(o: Vec3): Vec3 = Vec3(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x,
    )

    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
}

object Angles {
    /** Wraps an angle into [0, 360). */
    fun normalizeDeg(deg: Double): Double {
        val m = deg % 360.0
        return if (m < 0) m + 360.0 else m
    }

    /** Wraps an angle into (-180, 180]. */
    fun wrapDeg180(deg: Double): Double {
        var d = normalizeDeg(deg)
        if (d > 180.0) d -= 360.0
        return d
    }

    /** Smallest absolute difference between two azimuths, in degrees. */
    fun deltaDeg(a: Double, b: Double): Double = abs(wrapDeg180(a - b))

    /** Formats degrees as `123.4°`. */
    fun formatDeg(deg: Double, decimals: Int = 1): String = "%.${decimals}f°".format(deg)

    /** Formats a right ascension as `12h 34.5m`. */
    fun formatRa(raDeg: Double): String {
        val hours = normalizeDeg(raDeg) / 15.0
        val h = hours.toInt()
        val m = (hours - h) * 60.0
        return "%dh %04.1fm".format(h, m)
    }

    /** Formats a declination as `+41° 16'`. */
    fun formatDec(decDeg: Double): String {
        val sign = if (decDeg < 0) "-" else "+"
        val a = abs(decDeg)
        val d = a.toInt()
        val m = (a - d) * 60.0
        return "%s%d° %04.1f'".format(sign, d, m)
    }
}
