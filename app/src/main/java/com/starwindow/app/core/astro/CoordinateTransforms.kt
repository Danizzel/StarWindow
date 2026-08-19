package com.starwindow.app.core.astro

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Horizontal ⇄ equatorial conversions.
 *
 * Sign conventions used throughout the app:
 *  * azimuth A grows from north (0°) towards east (90°),
 *  * altitude a is measured from the horizon upwards,
 *  * hour angle H = LST - RA, positive after the object crossed the meridian.
 */
object CoordinateTransforms {

    /** Local sky direction → celestial coordinates for the given moment and place. */
    fun horizontalToEquatorial(
        horizontal: Horizontal,
        location: ObserverLocation,
        epochMillis: Long,
    ): Equatorial {
        val lat = Math.toRadians(location.latitudeDeg)
        val alt = Math.toRadians(horizontal.altitudeDeg)
        val az = Math.toRadians(horizontal.azimuthDeg)

        val sinDec = sin(alt) * sin(lat) + cos(alt) * cos(az) * cos(lat)
        val dec = asin(sinDec.coerceIn(-1.0, 1.0))

        val y = -cos(alt) * sin(az)
        val x = sin(alt) * cos(lat) - cos(alt) * cos(az) * sin(lat)
        val hourAngleDeg = Math.toDegrees(atan2(y, x))

        val lst = AstroTime.lstDeg(epochMillis, location.longitudeDeg)
        return Equatorial(
            raDeg = Angles.normalizeDeg(lst - hourAngleDeg),
            decDeg = Math.toDegrees(dec),
        )
    }

    /** Celestial coordinates → local sky direction for the given moment and place. */
    fun equatorialToHorizontal(
        equatorial: Equatorial,
        location: ObserverLocation,
        epochMillis: Long,
    ): Horizontal {
        val lst = AstroTime.lstDeg(epochMillis, location.longitudeDeg)
        return equatorialToHorizontalAtLst(equatorial, location.latitudeDeg, lst)
    }

    /**
     * Same as [equatorialToHorizontal] but takes the sidereal time directly. Stepping the LST
     * instead of recomputing it from a timestamp keeps the transit search cheap.
     */
    fun equatorialToHorizontalAtLst(
        equatorial: Equatorial,
        latitudeDeg: Double,
        lstDeg: Double,
    ): Horizontal {
        val lat = Math.toRadians(latitudeDeg)
        val dec = Math.toRadians(equatorial.decDeg)
        val h = Math.toRadians(Angles.wrapDeg180(lstDeg - equatorial.raDeg))

        val sinAlt = sin(dec) * sin(lat) + cos(dec) * cos(h) * cos(lat)
        val alt = asin(sinAlt.coerceIn(-1.0, 1.0))

        val y = -cos(dec) * sin(h)
        val x = sin(dec) * cos(lat) - cos(dec) * cos(h) * sin(lat)

        return Horizontal(
            azimuthDeg = Angles.normalizeDeg(Math.toDegrees(atan2(y, x))),
            altitudeDeg = Math.toDegrees(alt),
        )
    }

    /**
     * Bennett's atmospheric refraction, in degrees, to be *added* to a true altitude to get the
     * altitude the camera actually sees. Below the horizon it is clamped to the horizon value.
     */
    fun refractionDeg(trueAltitudeDeg: Double): Double {
        val h = trueAltitudeDeg.coerceAtLeast(-0.9)
        val arcmin = 1.0 / tan(Math.toRadians(h + 7.31 / (h + 4.4)))
        return (arcmin / 60.0).coerceIn(0.0, 0.6)
    }

    /** [equatorialToHorizontalAtLst] plus refraction, i.e. where the object *appears* to be. */
    fun apparentHorizontalAtLst(
        equatorial: Equatorial,
        latitudeDeg: Double,
        lstDeg: Double,
    ): Horizontal {
        val geometric = equatorialToHorizontalAtLst(equatorial, latitudeDeg, lstDeg)
        return geometric.copy(altitudeDeg = geometric.altitudeDeg + refractionDeg(geometric.altitudeDeg))
    }

    /**
     * Highest altitude the object ever reaches from this latitude (used to skip objects that can
     * never enter a window). Returns -90 for objects that stay below the horizon.
     */
    fun maxAltitudeDeg(decDeg: Double, latitudeDeg: Double): Double =
        90.0 - kotlin.math.abs(latitudeDeg - decDeg)

    /** Lowest altitude the object ever reaches (at lower culmination). */
    fun minAltitudeDeg(decDeg: Double, latitudeDeg: Double): Double =
        kotlin.math.abs(latitudeDeg + decDeg) - 90.0
}
