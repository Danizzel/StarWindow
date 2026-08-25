package com.starwindow.app

import com.starwindow.app.core.astro.Angles
import com.starwindow.app.core.astro.CoordinateTransforms
import com.starwindow.app.core.astro.LunarEphemeris
import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.core.astro.SolarEphemeris
import com.starwindow.app.core.astro.Twilight
import com.starwindow.app.core.astro.TwilightPhase
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ein Zeitpunkt aus Datum und Uhrzeit in UTC. */
private fun utc(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
    java.time.LocalDateTime.of(year, month, day, hour, minute)
        .toInstant(java.time.ZoneOffset.UTC)
        .toEpochMilli()

private val berlin = ObserverLocation(52.52, 13.405, 34.0)
private val quito = ObserverLocation(-0.18, -78.47, 2850.0)
private val tromso = ObserverLocation(69.65, 18.96, 10.0)

class SolarEphemerisTest {

    /**
     * Meeus, Beispiel 25.a: 1992 Oktober 13, 0h TD. Der Unterschied zwischen TD und UT von rund
     * einer Minute bewegt die Sonne um 0,0004° und bleibt damit weit unter der Toleranz.
     */
    @Test
    fun `sun position matches the worked example`() {
        val millis = utc(1992, 10, 13)
        val sun = SolarEphemeris.at(millis)

        assertEquals(199.90895, sun.apparentLongitudeDeg, 0.01)
        assertEquals(198.38083, sun.equatorial.raDeg, 0.01)
        assertEquals(-7.78507, sun.equatorial.decDeg, 0.01)
    }

    @Test
    fun `declination reaches the obliquity at the solstices`() {
        val june = SolarEphemeris.at(utc(2024, 6, 20, 21)).equatorial.decDeg
        val december = SolarEphemeris.at(utc(2024, 12, 21, 9)).equatorial.decDeg

        assertEquals(23.44, june, 0.02)
        assertEquals(-23.44, december, 0.02)
    }

    @Test
    fun `right ascension passes through zero at the March equinox`() {
        // Frühlingspunkt 2024: 20. März, 03:06 UTC.
        val ra = SolarEphemeris.at(utc(2024, 3, 20, 3)).equatorial.raDeg
        assertTrue(abs(Angles.wrapDeg180(ra)) < 0.1, "RA war $ra°")
    }

    @Test
    fun `earth distance stays inside the orbit`() {
        // Perihel Anfang Januar, Aphel Anfang Juli — beides innerhalb von 1,7 % von 1 AE.
        val perihelion = SolarEphemeris.at(utc(2024, 1, 3)).distanceKm / SolarEphemeris.AU_KM
        val aphelion = SolarEphemeris.at(utc(2024, 7, 5)).distanceKm / SolarEphemeris.AU_KM

        assertEquals(0.9833, perihelion, 0.001)
        assertEquals(1.0167, aphelion, 0.001)
    }

    /**
     * Die Kulminationshöhe ist die eine Größe, die sich unabhängig nachrechnen lässt:
     * 90° minus dem Abstand zwischen Deklination und geografischer Breite. Sie prüft Ephemeride
     * und Koordinatenwandlung in einem — ohne dass irgendwo eine Uhrzeit erraten werden müsste.
     */
    @Test
    fun `the sun culminates at the geometrically required altitude`() {
        val local = ZoneId.of("America/Guayaquil")
        val start = LocalDate.of(2024, 3, 20).atStartOfDay(local).toInstant().toEpochMilli()

        var best = -90.0
        var bestMillis = start
        for (step in 0 until 24 * 60) {
            val millis = start + step * 60_000L
            val altitude = SolarEphemeris.altitudeDeg(millis, quito)
            if (altitude > best) {
                best = altitude
                bestMillis = millis
            }
        }

        val declination = SolarEphemeris.at(bestMillis).equatorial.decDeg
        assertEquals(90.0 - abs(declination - quito.latitudeDeg), best, 0.02)
        assertTrue(best > 89.0, "Auf dem Äquator zur Tagundnachtgleiche fast senkrecht: $best°")
    }
}

class LunarEphemerisTest {

    /** Meeus, Beispiel 47.a: 1992 April 12, 0h TD. */
    @Test
    fun `moon position matches the worked example`() {
        val moon = LunarEphemeris.at(utc(1992, 4, 12))

        assertEquals(133.162655, moon.eclipticLongitudeDeg, 0.02)
        assertEquals(-3.229126, moon.eclipticLatitudeDeg, 0.02)
        assertEquals(368409.7, moon.distanceKm, 300.0)
    }

    @Test
    fun `distance and latitude stay inside the known bounds`() {
        // Über zwei Jahre in Tagesschritten: der Mond verlässt weder seinen Abstandsbereich noch
        // die gut 5° Bahnneigung. Das fängt Vorzeichenfehler in den Reihen ab.
        var millis = utc(2024, 1, 1)
        repeat(730) {
            val moon = LunarEphemeris.at(millis)
            assertTrue(
                moon.distanceKm in 355_000.0..407_500.0,
                "Abstand ${moon.distanceKm} km fiel aus dem Rahmen",
            )
            assertTrue(
                abs(moon.eclipticLatitudeDeg) < 5.4,
                "Breite ${moon.eclipticLatitudeDeg}° fiel aus dem Rahmen",
            )
            millis += 86_400_000L
        }
    }

    @Test
    fun `illumination is empty at new moon and full at full moon`() {
        // Neumond 11. Januar 2024, 11:57 UTC; Vollmond 25. Januar 2024, 17:54 UTC.
        val newMoon = LunarEphemeris.illuminationAt(utc(2024, 1, 11, 11, 57))
        val fullMoon = LunarEphemeris.illuminationAt(utc(2024, 1, 25, 17, 54))

        assertTrue(newMoon.fraction < 0.01, "Neumond war zu ${newMoon.percent} % beleuchtet")
        assertTrue(fullMoon.fraction > 0.99, "Vollmond war zu ${fullMoon.percent} % beleuchtet")
        assertEquals("Neumond", newMoon.phaseName)
        assertEquals("Vollmond", fullMoon.phaseName)
    }

    @Test
    fun `the moon waxes after new moon and wanes after full moon`() {
        assertTrue(LunarEphemeris.illuminationAt(utc(2024, 1, 15)).waxing)
        assertTrue(!LunarEphemeris.illuminationAt(utc(2024, 1, 29)).waxing)
    }

    /**
     * Die Parallaxe drückt den Mond nach unten, am Horizont am stärksten und im Zenit gar nicht.
     * Genau das ist der Unterschied, der über „geht gerade auf" entscheidet.
     */
    @Test
    fun `parallax lowers the moon and vanishes at the zenith`() {
        val millis = utc(2024, 3, 15, 22)
        val moon = LunarEphemeris.at(millis)
        val geocentric = CoordinateTransforms
            .equatorialToHorizontal(moon.equatorial, berlin, millis).altitudeDeg
        val topocentric = LunarEphemeris.topocentricAltitudeDeg(moon, berlin, millis)

        assertTrue(topocentric < geocentric, "Die Parallaxe muss den Mond senken")
        assertTrue(geocentric - topocentric < 1.05, "Die Parallaxe bleibt unter gut einem Grad")
        assertTrue(moon.horizontalParallaxDeg in 0.85..1.02)
    }
}

class TwilightTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")

    @Test
    fun `the twilight phases follow the sun altitude`() {
        assertEquals(TwilightPhase.DAY, TwilightPhase.forSunAltitude(10.0))
        assertEquals(TwilightPhase.CIVIL, TwilightPhase.forSunAltitude(-3.0))
        assertEquals(TwilightPhase.NAUTICAL, TwilightPhase.forSunAltitude(-9.0))
        assertEquals(TwilightPhase.ASTRONOMICAL, TwilightPhase.forSunAltitude(-15.0))
        assertEquals(TwilightPhase.ASTRONOMICAL_DARKNESS, TwilightPhase.forSunAltitude(-20.0))
    }

    @Test
    fun `a winter night in Berlin runs through all four thresholds in order`() {
        val night = Twilight.nightOf(LocalDate.of(2024, 12, 21), berlin, zone)

        val sunset = assertNotNull(night.sunsetMillis)
        val civil = assertNotNull(night.civilDuskMillis)
        val nautical = assertNotNull(night.nauticalDuskMillis)
        val astronomical = assertNotNull(night.astronomicalDuskMillis)
        val dawn = assertNotNull(night.astronomicalDawnMillis)
        val sunrise = assertNotNull(night.sunriseMillis)

        assertTrue(sunset < civil && civil < nautical && nautical < astronomical)
        assertTrue(astronomical < dawn && dawn < sunrise)
        // Mitte Dezember steht die Sonne in Berlin gut vierzehn Stunden unter dem Horizont, davon
        // über elf tiefer als -18°.
        assertTrue(night.darkDurationMillis > 11 * 3_600_000L, "nur ${night.darkDurationMillis} ms")
    }

    @Test
    fun `Berlin has no astronomical darkness at midsummer`() {
        val night = Twilight.nightOf(LocalDate.of(2024, 6, 21), berlin, zone)

        assertNull(night.astronomicalDuskMillis)
        assertEquals(0L, night.darkDurationMillis)
        assertTrue(night.lowestSunAltitudeDeg > -18.0)
    }

    @Test
    fun `Tromso keeps the sun up in June and down in December`() {
        val summer = Twilight.nightOf(LocalDate.of(2024, 6, 21), tromso, ZoneId.of("Europe/Oslo"))
        val winter = Twilight.nightOf(LocalDate.of(2024, 12, 21), tromso, ZoneId.of("Europe/Oslo"))

        assertNull(summer.sunsetMillis)
        assertNull(winter.sunriseMillis)
        assertTrue(winter.darkDurationMillis > 0)
    }

    @Test
    fun `the sun really stands at the threshold at the reported time`() {
        val night = Twilight.nightOf(LocalDate.of(2024, 3, 15), berlin, zone)
        val dusk = assertNotNull(night.astronomicalDuskMillis)

        assertEquals(-18.0, SolarEphemeris.altitudeDeg(dusk, berlin), 0.02)
    }

    @Test
    fun `isDark covers the stretch between dusk and dawn`() {
        val night = Twilight.nightOf(LocalDate.of(2024, 11, 5), berlin, zone)
        val dusk = assertNotNull(night.astronomicalDuskMillis)
        val dawn = assertNotNull(night.astronomicalDawnMillis)

        assertTrue(night.isDark((dusk + dawn) / 2))
        assertTrue(!night.isDark(dusk - 60_000L))
        assertTrue(!night.isDark(dawn + 60_000L))
    }

    @Test
    fun `moonrise and moonset land on the horizon`() {
        val night = Twilight.nightOf(LocalDate.of(2024, 9, 10), berlin, zone)
        listOfNotNull(night.moonriseMillis, night.moonsetMillis).forEach { millis ->
            val altitude = LunarEphemeris.topocentricAltitudeDeg(
                LunarEphemeris.at(millis),
                berlin,
                millis,
            )
            assertEquals(Twilight.HORIZON_DEG, altitude, 0.05)
        }
    }
}

