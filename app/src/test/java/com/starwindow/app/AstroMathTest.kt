package com.starwindow.app

import com.starwindow.app.core.astro.Angles
import com.starwindow.app.core.astro.AstroTime
import com.starwindow.app.core.astro.CoordinateTransforms
import com.starwindow.app.core.astro.Equatorial
import com.starwindow.app.core.astro.Horizontal
import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.core.geometry.SphericalGeometry
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 2000-01-01T12:00:00Z, the J2000.0 epoch. */
private const val J2000_MILLIS = 946_728_000_000L

class AstroTimeTest {

    @Test
    fun `julian date matches the J2000 epoch`() {
        assertEquals(2451545.0, AstroTime.julianDate(J2000_MILLIS), 1e-6)
    }

    @Test
    fun `gmst at J2000 matches the IAU constant`() {
        assertEquals(280.46061837, AstroTime.gmstDeg(J2000_MILLIS), 1e-6)
    }

    @Test
    fun `sidereal day is about four minutes short of a solar day`() {
        val start = AstroTime.gmstDeg(J2000_MILLIS)
        val afterOneSolarDay = AstroTime.gmstDeg(J2000_MILLIS + 86_400_000L)
        val drift = Angles.wrapDeg180(afterOneSolarDay - start)
        // 360.98565 - 360 = 0.98565 degrees per solar day.
        assertEquals(0.98565, drift, 1e-4)
    }

    @Test
    fun `local sidereal time follows longitude`() {
        val gmst = AstroTime.gmstDeg(J2000_MILLIS)
        assertEquals(Angles.normalizeDeg(gmst + 13.4), AstroTime.lstDeg(J2000_MILLIS, 13.4), 1e-9)
    }
}

class CoordinateTransformsTest {

    private val berlin = ObserverLocation(52.52, 13.405, 34.0)
    private val sydney = ObserverLocation(-33.87, 151.21, 20.0)
    private val quito = ObserverLocation(-0.18, -78.47, 2850.0)

    @Test
    fun `zenith maps to the observer's declination on the meridian`() {
        for (location in listOf(berlin, sydney, quito)) {
            val zenith = Horizontal(0.0, 90.0)
            val equatorial = CoordinateTransforms.horizontalToEquatorial(zenith, location, J2000_MILLIS)
            assertEquals(location.latitudeDeg, equatorial.decDeg, 1e-6)

            val lst = AstroTime.lstDeg(J2000_MILLIS, location.longitudeDeg)
            assertEquals(0.0, Angles.wrapDeg180(lst - equatorial.raDeg), 1e-6)
        }
    }

    @Test
    fun `the celestial pole sits due north at the latitude's altitude`() {
        val pole = Equatorial(0.0, 90.0)
        val horizontal = CoordinateTransforms.equatorialToHorizontal(pole, berlin, J2000_MILLIS)
        assertEquals(berlin.latitudeDeg, horizontal.altitudeDeg, 1e-6)
        assertEquals(0.0, Angles.wrapDeg180(horizontal.azimuthDeg), 1e-6)

        // Southern hemisphere: the north pole is below the horizon, due north.
        val south = CoordinateTransforms.equatorialToHorizontal(pole, sydney, J2000_MILLIS)
        assertEquals(sydney.latitudeDeg, south.altitudeDeg, 1e-6)
    }

    @Test
    fun `due east on the horizon is on the celestial equator six hours before transit`() {
        val east = Horizontal(90.0, 0.0)
        val equatorial = CoordinateTransforms.horizontalToEquatorial(east, berlin, J2000_MILLIS)
        assertEquals(0.0, equatorial.decDeg, 1e-6)

        val lst = AstroTime.lstDeg(J2000_MILLIS, berlin.longitudeDeg)
        val hourAngle = AstroTime.hourAngleDeg(lst, equatorial.raDeg)
        assertEquals(-90.0, hourAngle, 1e-6)
    }

    @Test
    fun `horizontal to equatorial round trips`() {
        var worst = 0.0
        for (az in 0 until 360 step 17) {
            for (alt in -80..80 step 13) {
                for (location in listOf(berlin, sydney, quito)) {
                    for (offsetHours in listOf(0L, 5L, 13L, 200L)) {
                        val time = J2000_MILLIS + offsetHours * 3_600_000L
                        val start = Horizontal(az.toDouble(), alt.toDouble())
                        val equatorial = CoordinateTransforms.horizontalToEquatorial(start, location, time)
                        val back = CoordinateTransforms.equatorialToHorizontal(equatorial, location, time)
                        worst = maxOf(worst, SphericalGeometry.separationDeg(start, back))
                    }
                }
            }
        }
        assertTrue(worst < 1e-9, "worst round trip error was $worst deg")
    }

    @Test
    fun `angular separation is the same in both frames`() {
        // The transform is a rotation, so distances between directions must be preserved exactly.
        val a = Equatorial(101.287, -16.716)   // Sirius
        val b = Equatorial(88.793, 7.407)      // Betelgeuse
        val separationEquatorial = SphericalGeometry.separationDeg(
            Horizontal(a.raDeg, a.decDeg),
            Horizontal(b.raDeg, b.decDeg),
        )
        val time = J2000_MILLIS + 7 * 3_600_000L
        val ha = CoordinateTransforms.equatorialToHorizontal(a, berlin, time)
        val hb = CoordinateTransforms.equatorialToHorizontal(b, berlin, time)
        assertEquals(separationEquatorial, SphericalGeometry.separationDeg(ha, hb), 1e-9)
        // Sirius and Betelgeuse really are about 27 degrees apart.
        assertEquals(27.1, separationEquatorial, 0.2)
    }

    @Test
    fun `an object culminates at the expected altitude`() {
        // Vega, declination +38.78, seen from Berlin: 90 - |52.52 - 38.78| = 76.26 degrees.
        val vega = Equatorial(279.234, 38.784)
        val expected = 90.0 - abs(berlin.latitudeDeg - vega.decDeg)

        var best = -90.0
        for (minute in 0 until 1440) {
            val time = J2000_MILLIS + minute * 60_000L
            val altitude = CoordinateTransforms.equatorialToHorizontal(vega, berlin, time).altitudeDeg
            best = maxOf(best, altitude)
        }
        assertEquals(expected, best, 0.01)
    }

    @Test
    fun `refraction lifts objects near the horizon and vanishes at the zenith`() {
        assertEquals(0.0, CoordinateTransforms.refractionDeg(90.0), 0.001)
        // At the horizon the standard value is close to 34 arcminutes.
        assertEquals(34.0 / 60.0, CoordinateTransforms.refractionDeg(0.0), 0.03)
        assertTrue(CoordinateTransforms.refractionDeg(5.0) < CoordinateTransforms.refractionDeg(1.0))
    }
}
