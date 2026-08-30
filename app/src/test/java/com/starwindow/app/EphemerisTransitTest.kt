package com.starwindow.app

import com.starwindow.app.core.astro.CoordinateTransforms
import com.starwindow.app.core.astro.Horizontal
import com.starwindow.app.core.astro.LunarEphemeris
import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.core.astro.Precession
import com.starwindow.app.core.astro.SolarEphemeris
import com.starwindow.app.core.geometry.CircleWindow
import com.starwindow.app.core.geometry.SkyWindow
import com.starwindow.app.data.catalog.EphemerisBody
import com.starwindow.app.data.catalog.EphemerisCatalog
import com.starwindow.app.data.catalog.ObjectType
import com.starwindow.app.data.catalog.SkyObject
import com.starwindow.app.domain.DarkSpans
import com.starwindow.app.domain.ObservationPlanner
import com.starwindow.app.domain.OverlaySelection
import com.starwindow.app.domain.TransitCalculator
import com.starwindow.app.domain.TransitDarkness
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** 13. November 2026, 22:00 UTC. */
private const val NIGHT_MILLIS = 1_794_693_600_000L

private val berlin = ObserverLocation(52.52, 13.405, 34.0)

class EphemerisObjectTest {

    @Test
    fun `the moon knows where it is, and it is not where the catalogue entry says`() {
        val fromEphemeris = LunarEphemeris.at(NIGHT_MILLIS).equatorial
        val fromCatalog = EphemerisCatalog.moon.positionAtMillis(NIGHT_MILLIS)

        assertEquals(fromEphemeris.raDeg, fromCatalog.raDeg, 1e-9)
        assertEquals(fromEphemeris.decDeg, fromCatalog.decDeg, 1e-9)
        // Die gespeicherten Koordinaten sind ein Platzhalter — genau deshalb darf niemand sie
        // benutzen, und der Test hält fest, dass es einen Unterschied gibt.
        assertTrue(abs(fromCatalog.raDeg - EphemerisCatalog.moon.raDeg) > 1.0)
    }

    /**
     * Der Weg, über den fast der ganze Rest der App Positionen holt: `positionAt(precession)`.
     * Käme dort für den Mond die Katalogposition heraus, stünde er in Sucher, Suche und
     * Fensterrechnung an einer festen falschen Stelle — und nichts würde danach noch auffallen.
     */
    @Test
    fun `the precession path gives the moon its ephemeris position too`() {
        val viaPrecession = EphemerisCatalog.moon.positionAt(Precession.forEpoch(NIGHT_MILLIS))
        val direct = LunarEphemeris.at(NIGHT_MILLIS).equatorial
        assertEquals(direct.raDeg, viaPrecession.raDeg, 1e-6)
        assertEquals(direct.decDeg, viaPrecession.decDeg, 1e-6)
    }

    @Test
    fun `a precession remembers the moment it was built for`() {
        val precession = Precession.forEpoch(NIGHT_MILLIS)
        // Eine Sekunde Toleranz: Der Rückweg läuft über eine Fließkommazahl in Julianischen
        // Jahrhunderten, und feiner braucht es niemand.
        assertTrue(abs(precession.epochMillis - NIGHT_MILLIS) < 1000L)
    }

    @Test
    fun `the sun moves about a degree a day against the stars`() {
        val day = 24 * 3_600_000L
        val today = SolarEphemeris.at(NIGHT_MILLIS).equatorial
        val tomorrow = SolarEphemeris.at(NIGHT_MILLIS + day).equatorial
        val step = abs(tomorrow.raDeg - today.raDeg)
        assertTrue(step in 0.9..1.2, "Die Sonne läuft rund ein Grad pro Tag, hier: $step")
    }

    @Test
    fun `the moon moves about half a degree an hour`() {
        val before = LunarEphemeris.at(NIGHT_MILLIS).equatorial
        val after = LunarEphemeris.at(NIGHT_MILLIS + 3_600_000L).equatorial
        val step = abs(after.raDeg - before.raDeg)
        assertTrue(step in 0.35..0.85, "Der Mond läuft rund ein halbes Grad pro Stunde, hier: $step")
    }

    @Test
    fun `sun and moon are always drawn in the viewfinder`() {
        val faint = (1..500).map { index ->
            SkyObject(
                id = "NGC $index",
                name = "",
                type = ObjectType.GALAXY,
                raDeg = index.toDouble(),
                decDeg = 40.0,
                magnitude = 9.0,
                sizeArcmin = 5.0,
            )
        }
        val selected = OverlaySelection.select(
            catalog = EphemerisCatalog.all + faint,
            latitudeDeg = 52.52,
            magnitudeLimit = 12.0,
        )
        assertTrue(selected.any { it.body == EphemerisBody.MOON })
        assertTrue(selected.any { it.body == EphemerisBody.SUN })
        assertTrue(selected.size <= OverlaySelection.MAX_OBJECTS)
    }

    /**
     * Der Mond läuft über 57° Deklination. Eine Prüfung, die ihn wie einen Katalogeintrag mit
     * fester Deklination behandelt, wirft ihn je nach Monat aus jeder zweiten Suche.
     */
    @Test
    fun `the moon is never rejected for its declination`() {
        assertTrue(OverlaySelection.everRises(EphemerisCatalog.moon, latitudeDeg = 52.52))
        assertTrue(OverlaySelection.everRises(EphemerisCatalog.moon, latitudeDeg = -33.9))
    }

    /**
     * Die Jahresplanung rechnete bisher **eine** Position für alle 365 Nächte. Für einen Fixstern
     * ist das richtig, für den Mond wäre es nach einer Woche Unsinn: Er läuft jede Nacht dreizehn
     * Grad weiter, in vierzehn Nächten also von einem Ende des Himmels zum anderen.
     */
    @Test
    fun `the year plan follows the moon from night to night`() {
        val zone = java.time.ZoneId.of("Europe/Berlin")
        val nights = ObservationPlanner.plan(
            obj = EphemerisCatalog.moon,
            observer = berlin,
            zone = zone,
            from = java.time.LocalDate.of(2026, 11, 13),
            days = 20,
        )
        val altitudes = nights.map { it.bestAltitudeDeg }
        val spread = altitudes.max() - altitudes.min()
        assertTrue(
            spread > 20.0,
            "Über zwanzig Nächte muss sich die Kulminationshöhe des Mondes deutlich ändern, hier: $spread",
        )
    }
}

class MovingTransitTest {

    private val calculator = TransitCalculator()

    private fun windowAt(direction: Horizontal, radiusDeg: Double) = SkyWindow(
        id = "test",
        name = "Test",
        shape = CircleWindow(direction, radiusDeg),
        observer = berlin,
        capturedAtMillis = NIGHT_MILLIS,
    )

    /**
     * Die Frage, für die es diesen ganzen Abschnitt gibt: Wann zieht der Mond durch mein Fenster?
     *
     * Aufgebaut wird das Fenster dort, wo der Mond zu einem bekannten Zeitpunkt steht — dann muss
     * die Suche ihn genau um diese Zeit darin finden.
     */
    @Test
    fun `the moon passes through a window pointed at it`() = runBlocking {
        val moonNow = CoordinateTransforms.equatorialToHorizontal(
            LunarEphemeris.at(NIGHT_MILLIS).equatorial, berlin, NIGHT_MILLIS,
        )
        val result = calculator.search(
            window = windowAt(moonNow, radiusDeg = 4.0),
            objects = EphemerisCatalog.all,
            fromMillis = NIGHT_MILLIS - 3_600_000L,
            toMillis = NIGHT_MILLIS + 3 * 3_600_000L,
        )

        val moonTransit = assertNotNull(
            result.transits.firstOrNull { it.obj.body == EphemerisBody.MOON },
            "Der Mond muss durch ein Fenster ziehen, das auf ihn zeigt",
        )
        val interval = moonTransit.intervals.first { NIGHT_MILLIS in it.enterMillis..it.exitMillis }
        assertTrue(interval.durationMillis > 0L)
        // Die Sonne steht nachts nicht in demselben Fenster.
        assertTrue(result.transits.none { it.obj.body == EphemerisBody.SUN })
    }

    /**
     * Der Mond zieht **schneller** durch als ein Fixstern an derselben Stelle: Die Erddrehung
     * bringt ihn hinein, seine Eigenbewegung hält ihn zurück — sie läuft ihr entgegen, sodass er
     * am Himmel etwas hinterherbleibt und dadurch länger im Fenster steht. Geprüft wird deshalb
     * nur, dass sich beide Dauern überhaupt unterscheiden: Wären sie gleich, würde die Suche die
     * Eigenbewegung ignorieren, und genau das war vorher der Fall.
     */
    @Test
    fun `a moving body does not cross like a fixed one`() = runBlocking {
        val moonPosition = LunarEphemeris.at(NIGHT_MILLIS).equatorial
        val direction = CoordinateTransforms
            .equatorialToHorizontal(moonPosition, berlin, NIGHT_MILLIS)
        val standIn = SkyObject(
            id = "Attrappe",
            name = "Attrappe",
            type = ObjectType.STAR,
            // Als J2000-Position eingetragen; über zwei Stunden ist der Unterschied zur
            // Position des Datums klein gegen den Effekt, um den es hier geht.
            raDeg = moonPosition.raDeg,
            decDeg = moonPosition.decDeg,
            magnitude = 1.0,
        )

        val result = calculator.search(
            window = windowAt(direction, radiusDeg = 5.0),
            objects = listOf(EphemerisCatalog.moon, standIn),
            fromMillis = NIGHT_MILLIS - 3_600_000L,
            toMillis = NIGHT_MILLIS + 3 * 3_600_000L,
        )

        val moon = assertNotNull(result.transits.firstOrNull { it.obj.isMoving })
        val fixed = assertNotNull(result.transits.firstOrNull { !it.obj.isMoving })
        val difference = abs(moon.totalDurationMillis - fixed.totalDurationMillis)
        assertTrue(
            difference > 60_000L,
            "Eigenbewegung muss sich auf die Verweildauer auswirken, Unterschied: ${difference / 1000} s",
        )
    }
}

class DarkSpansTest {

    @Test
    fun `a November night in Berlin is dark in the middle and bright at the edges`() {
        val noon = NIGHT_MILLIS - 10 * 3_600_000L
        val spans = DarkSpans.over(noon, noon + 24 * 3_600_000L, berlin)

        assertFalse(spans.isEmpty)
        assertFalse(spans.contains(noon), "mittags ist es nicht dunkel")
        assertTrue(spans.contains(NIGHT_MILLIS), "um 23 Uhr Ortszeit schon")

        val darkHours = spans.overlap(noon, noon + 24 * 3_600_000L) / 3_600_000.0
        assertTrue(darkHours in 10.0..15.0, "Mitte November gut dreizehn Stunden, hier: $darkHours")
    }

    @Test
    fun `midsummer above the arctic circle has no darkness at all`() {
        val tromso = ObserverLocation(69.65, 18.96, 0.0)
        // 21. Juni 2026, mittags UTC.
        val midsummer = 1_782_216_000_000L
        val spans = DarkSpans.over(midsummer, midsummer + 24 * 3_600_000L, tromso)
        assertTrue(spans.isEmpty)
        assertEquals(0L, spans.overlap(midsummer, midsummer + 24 * 3_600_000L))
    }

    @Test
    fun `an empty span answers nothing rather than everything`() {
        assertTrue(DarkSpans.NONE.isEmpty)
        assertEquals(0L, DarkSpans.NONE.overlap(0L, Long.MAX_VALUE / 2))
    }
}

class TransitConditionsTest {

    private val calculator = TransitCalculator()

    private fun objectAt(direction: Horizontal, millis: Long): SkyObject {
        val equatorial = CoordinateTransforms.horizontalToEquatorial(direction, berlin, millis)
        return SkyObject(
            id = "TEST 1",
            name = "TEST 1",
            type = ObjectType.STAR,
            raDeg = equatorial.raDeg,
            decDeg = equatorial.decDeg,
            magnitude = 2.0,
        )
    }

    private fun search(atMillis: Long) = runBlocking {
        val direction = Horizontal(180.0, 40.0)
        calculator.search(
            window = SkyWindow(
                id = "w",
                name = "w",
                shape = CircleWindow(direction, 6.0),
                observer = berlin,
                capturedAtMillis = atMillis,
            ),
            objects = listOf(objectAt(direction, atMillis)),
            fromMillis = atMillis,
            toMillis = atMillis + 2 * 3_600_000L,
        )
    }

    /** Ein Durchgang mitten in der Nacht ist dunkel — und wird auch so beschrieben. */
    @Test
    fun `a pass at midnight is dark`() {
        val transit = search(NIGHT_MILLIS).transits.first()
        val interval = transit.intervals.first()
        assertTrue(interval.darkMillis > 0L)
        assertEquals(TransitDarkness.DARK, interval.darkness)
        assertTrue(transit.hasDarkTime)
        assertTrue(interval.conditionLabel.startsWith("dunkel"))
    }

    /** Und einer um die Mittagszeit nicht — das war bisher in der Liste nicht zu sehen. */
    @Test
    fun `a pass at noon is bright`() {
        val transit = search(NIGHT_MILLIS - 10 * 3_600_000L).transits.first()
        val interval = transit.intervals.first()
        assertEquals(0L, interval.darkMillis)
        assertEquals(TransitDarkness.BRIGHT, interval.darkness)
        assertFalse(transit.hasDarkTime)
        assertEquals("zu hell", interval.conditionLabel)
    }
}
