package com.starwindow.app

import com.starwindow.app.core.astro.AstroTime
import com.starwindow.app.core.astro.CoordinateTransforms
import com.starwindow.app.core.astro.Equatorial
import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.core.astro.Precession
import com.starwindow.app.core.astro.Vec3
import com.starwindow.app.core.geometry.SphericalGeometry
import com.starwindow.app.data.catalog.ObjectType
import com.starwindow.app.data.catalog.SkyObject
import com.starwindow.app.domain.ObjectSearch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Mean obliquity of the ecliptic at J2000, degrees. */
private const val OBLIQUITY_DEG = 23.4392911

/** Ecliptic longitude and latitude of an equatorial direction, in degrees. */
private fun toEcliptic(equatorial: Equatorial): Pair<Double, Double> {
    val v = equatorial.toVector()
    val e = Math.toRadians(OBLIQUITY_DEG)
    // Rotate about the x axis (the equinox) by -obliquity: equator plane onto ecliptic plane.
    val rotated = Vec3(v.x, v.y * cos(e) + v.z * sin(e), -v.y * sin(e) + v.z * cos(e))
    val longitude = Math.toDegrees(atan2(rotated.y, rotated.x))
    val latitude = Math.toDegrees(kotlin.math.asin(rotated.z.coerceIn(-1.0, 1.0)))
    return (if (longitude < 0) longitude + 360.0 else longitude) to latitude
}

/** The north ecliptic pole in equatorial coordinates: RA 18h, Dec 90° - obliquity. */
private val ECLIPTIC_POLE = Equatorial(270.0, 90.0 - OBLIQUITY_DEG)

private fun centuriesOfYears(years: Double) = years / 100.0

class PrecessionTest {

    @Test
    fun `at J2000 precession does nothing`() {
        val identity = Precession.forJulianCenturies(0.0)
        assertEquals(0.0, identity.angleDeg, 1e-12)

        val star = Equatorial(83.822, -5.391)
        val moved = identity.toDate(star)
        assertEquals(star.raDeg, moved.raDeg, 1e-9)
        assertEquals(star.decDeg, moved.decDeg, 1e-9)
    }

    @Test
    fun `precession is a rotation, so it preserves angles between stars`() {
        val precession = Precession.forJulianCenturies(centuriesOfYears(26.0))
        val a = Equatorial(83.822, -5.391)   // M42
        val b = Equatorial(10.6847, 41.269)  // M31

        val before = SphericalGeometry.separationDeg(a.toVector(), b.toVector())
        val after = SphericalGeometry.separationDeg(
            precession.toDate(a).toVector(),
            precession.toDate(b).toVector(),
        )
        assertEquals(before, after, 1e-9)
    }

    @Test
    fun `the frame turns by the general precession rate of about 50 arcseconds a year`() {
        // This is the number the textbooks quote; getting it wrong by a factor or a sign would
        // show up here immediately.
        for (years in listOf(10.0, 26.0, 50.0, 100.0)) {
            val arcsecPerYear = Precession
                .forJulianCenturies(centuriesOfYears(years))
                .angleDeg * 3600.0 / years
            assertTrue(
                arcsecPerYear in 50.0..50.6,
                "after $years years the rate is $arcsecPerYear arcsec/year",
            )
        }
    }

    @Test
    fun `precession turns about the ecliptic pole, which therefore barely moves`() {
        val precession = Precession.forJulianCenturies(centuriesOfYears(26.0))
        val moved = precession.toDate(ECLIPTIC_POLE)
        val displacement = SphericalGeometry.separationDeg(
            ECLIPTIC_POLE.toVector(),
            moved.toVector(),
        )
        // Not exactly zero — the obliquity itself drifts a little — but two orders of magnitude
        // below what a star on the ecliptic moves.
        assertTrue(displacement < 0.005, "the ecliptic pole moved by $displacement°")
    }

    @Test
    fun `ecliptic longitude grows while ecliptic latitude stays put`() {
        // The defining property of general precession: the equinox slides along the ecliptic.
        val years = 26.0
        val precession = Precession.forJulianCenturies(centuriesOfYears(years))

        for (raDeg in 0 until 360 step 30) {
            for (decDeg in listOf(-60.0, -20.0, 0.0, 20.0, 60.0)) {
                val star = Equatorial(raDeg.toDouble(), decDeg)
                val (longitudeBefore, latitudeBefore) = toEcliptic(star)
                val (longitudeAfter, latitudeAfter) = toEcliptic(precession.toDate(star))

                var delta = longitudeAfter - longitudeBefore
                if (delta < -180.0) delta += 360.0
                if (delta > 180.0) delta -= 360.0

                // Read in the *fixed* J2000 ecliptic while precession actually turns about the
                // ecliptic pole of date, which leaves a small position-dependent remainder — about
                // nine arcseconds over these 26 years. The tolerance covers that and nothing more:
                // a sign error, a swapped angle or a missing term all miss by far more.
                assertEquals(
                    50.29 * years / 3600.0, delta, 0.004,
                    "longitude of RA $raDeg / Dec $decDeg drifted by $delta°",
                )
                assertEquals(
                    latitudeBefore, latitudeAfter, 0.004,
                    "latitude of RA $raDeg / Dec $decDeg changed",
                )
            }
        }
    }

    @Test
    fun `by the mid twenties a star on the ecliptic has moved about a third of a degree`() {
        // The whole reason this class exists: the error it removes is comparable to the pointing
        // accuracy a calibrated phone can reach, not far below it.
        val precession = Precession.forEpoch(AstroTime.epochMillis(AstroTime.J2000_JD + 26.5 * 365.25))
        assertTrue(
            precession.angleDeg in 0.35..0.40,
            "26.5 years of precession came out as ${precession.angleDeg}°",
        )
    }

    @Test
    fun `going to date and back returns the catalogue position`() {
        val precession = Precession.forJulianCenturies(centuriesOfYears(37.0))
        for (raDeg in 0 until 360 step 23) {
            for (decDeg in -80..80 step 17) {
                val star = Equatorial(raDeg.toDouble(), decDeg.toDouble())
                val roundTrip = precession.toJ2000(precession.toDate(star))
                assertTrue(
                    SphericalGeometry.separationDeg(star.toVector(), roundTrip.toVector()) < 1e-9,
                    "RA $raDeg / Dec $decDeg did not survive the round trip",
                )
            }
        }
    }

    @Test
    fun `the north celestial pole drifts towards Polaris as it should this century`() {
        // Polaris is still closing on the pole; it passes closest around 2100. So the angular
        // distance between the two has to shrink, not grow.
        val polaris = Equatorial(37.9529, 89.2641)
        val pole = Equatorial(0.0, 90.0)

        val atJ2000 = SphericalGeometry.separationDeg(polaris.toVector(), pole.toVector())
        val now = Precession.forJulianCenturies(centuriesOfYears(26.0))
        val atNow = SphericalGeometry.separationDeg(now.toDate(polaris).toVector(), pole.toVector())

        assertTrue(atNow < atJ2000, "Polaris moved away from the pole: $atJ2000° → $atNow°")
        // Roughly 0.005° a year, so a quarter of a degree closer over a human lifetime; over 26
        // years the change is small but well outside the noise of the computation.
        assertTrue(abs(atJ2000 - atNow) > 0.05, "the change of ${atJ2000 - atNow}° is implausibly small")
    }

    @Test
    fun `the search really applies precession instead of using the catalogue frame raw`() {
        // A canary for the wiring, not for the maths: it fails the moment a call site goes back to
        // handing a raw J2000 coordinate to the horizontal transform, which is how this whole class
        // of error gets reintroduced.
        val nowMillis = AstroTime.epochMillis(AstroTime.J2000_JD + 26.0 * 365.25)
        val star = SkyObject(
            id = "TEST 1",
            name = "Prüfstern",
            type = ObjectType.STAR,
            raDeg = 120.0,
            decDeg = 10.0,
            magnitude = 2.0,
        )
        val observer = ObserverLocation(48.14, 11.58, 520.0)

        val reported = ObjectSearch
            .search("TEST 1", listOf(star), observer, nowMillis)
            .single()
            .position!!

        val naive = CoordinateTransforms.apparentHorizontalAtLst(
            star.equatorialJ2000,
            observer.latitudeDeg,
            AstroTime.lstDeg(nowMillis, observer.longitudeDeg),
        )

        val difference = SphericalGeometry.separationDeg(reported, naive)
        assertTrue(
            difference > 0.2,
            "the search position differs from the uncorrected one by only $difference° — " +
                "precession looks like it is not being applied",
        )
        assertTrue(difference < 0.5, "the difference of $difference° is too large to be precession")
    }

    @Test
    fun `equatorial coordinates survive the trip through vector form`() {
        for (raDeg in 0 until 360 step 17) {
            for (decDeg in -89..89 step 13) {
                val original = Equatorial(raDeg.toDouble(), decDeg.toDouble())
                val roundTrip = Equatorial.fromVector(original.toVector())
                assertEquals(original.raDeg, roundTrip.raDeg, 1e-9)
                assertEquals(original.decDeg, roundTrip.decDeg, 1e-9)
            }
        }
    }
}
