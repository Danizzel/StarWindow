package com.starwindow.app

import com.starwindow.app.core.astro.AstroTime
import com.starwindow.app.core.astro.CoordinateTransforms
import com.starwindow.app.core.astro.Equatorial
import com.starwindow.app.core.astro.Horizontal
import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.core.geometry.CircleWindow
import com.starwindow.app.core.geometry.SkyWindow
import com.starwindow.app.core.geometry.SphericalGeometry
import com.starwindow.app.core.geometry.WindowShape
import com.starwindow.app.data.catalog.Constellation
import com.starwindow.app.data.catalog.FigureStar
import com.starwindow.app.domain.ConstellationTransitCalculator
import com.starwindow.app.domain.SkyTrackBuilder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

private const val START = 1_700_000_000_000L

class ConstellationTransitTest {

    private val berlin = ObserverLocation(52.52, 13.405, 34.0)
    private val calculator = ConstellationTransitCalculator()

    private fun window(shape: WindowShape) = SkyWindow(
        id = "w", name = "w", shape = shape, observer = berlin, capturedAtMillis = START,
    )

    /** Turns a sky direction into the equatorial position that stands there at [START]. */
    private fun equatorialAt(direction: Horizontal): Equatorial =
        CoordinateTransforms.horizontalToEquatorial(direction, berlin, START)

    private fun figure(vararg directions: Horizontal): Constellation {
        val stars = directions.mapIndexed { index, direction ->
            val equatorial = equatorialAt(direction)
            FigureStar("S$index", equatorial.raDeg, equatorial.decDeg)
        }
        return Constellation(
            id = "Tst",
            name = "Testbild",
            stars = stars,
            lines = List(stars.size - 1) { listOf(it, it + 1) },
        )
    }

    @Test
    fun `a figure crossing the window is reported`() {
        val center = Horizontal(180.0, 37.48)
        val constellation = figure(center, Horizontal(181.0, 38.0), Horizontal(179.0, 37.0))

        val transits = runBlocking {
            calculator.search(
                window(CircleWindow(center, 3.0)),
                listOf(constellation),
                START - 3_600_000L,
                START + 3_600_000L,
            )
        }

        assertEquals(1, transits.size)
        val transit = transits.single()
        assertEquals(3, transit.figureStarCount)
        assertEquals(3, transit.peakStarsInside, "all three stars fit in a three degree circle")
        assertTrue(transit.showsMostOfTheFigure)
    }

    @Test
    fun `only the part of the figure that fits is counted`() {
        // A tight window and a figure spread over fifteen degrees: never more than one star at once.
        val center = Horizontal(180.0, 37.48)
        val constellation = figure(
            center,
            Horizontal(180.0, 47.0),
            Horizontal(180.0, 27.0),
        )

        val transit = runBlocking {
            calculator.search(
                window(CircleWindow(center, 1.5)),
                listOf(constellation),
                START - 6 * 3_600_000L,
                START + 6 * 3_600_000L,
            )
        }.single()

        assertEquals(1, transit.peakStarsInside)
        assertTrue(!transit.showsMostOfTheFigure)
    }

    @Test
    fun `a figure that never reaches the window is skipped entirely`() {
        val northern = Horizontal(0.0, 80.0)
        // Declination -70 never rises from Berlin.
        val constellation = Constellation(
            id = "Far", name = "Fern",
            stars = listOf(FigureStar("a", 120.0, -70.0), FigureStar("b", 130.0, -72.0)),
            lines = listOf(listOf(0, 1)),
        )
        val transits = runBlocking {
            calculator.search(
                window(CircleWindow(northern, 2.0)),
                listOf(constellation),
                START,
                START + 86_400_000L,
            )
        }
        assertTrue(transits.isEmpty())
    }

    @Test
    fun `the figure snapshot puts the stars where they belong`() {
        val center = Horizontal(140.0, 30.0)
        val constellation = figure(center, Horizontal(143.0, 32.0))
        val segments = calculator.figureAt(constellation, window(CircleWindow(center, 5.0)), START)

        assertEquals(1, segments.size)
        val (a, b) = segments.single()
        // Refraction lifts both a little, so allow a fraction of a degree.
        assertTrue(SphericalGeometry.separationDeg(a, center) < 0.2, "first star landed at $a")
        assertTrue(SphericalGeometry.separationDeg(b, Horizontal(143.0, 32.0)) < 0.2)
    }

    @Test
    fun `malformed line indices are dropped instead of crashing`() {
        val constellation = Constellation(
            id = "Bad", name = "Kaputt",
            stars = listOf(FigureStar("a", 10.0, 10.0), FigureStar("b", 12.0, 12.0)),
            lines = listOf(listOf(0, 1), listOf(0, 7), listOf(3), emptyList()),
        )
        assertEquals(1, constellation.segments.size)
    }
}

class SkyTrackTest {

    private val berlin = ObserverLocation(52.52, 13.405, 34.0)

    @Test
    fun `a track covers the window pass plus a lead in and out`() {
        val center = Horizontal(180.0, 37.48)
        val equatorial = CoordinateTransforms.horizontalToEquatorial(center, berlin, START)
        val enter = START - 600_000L
        val exit = START + 600_000L

        val track = SkyTrackBuilder.forInterval("Test", equatorial, berlin, enter, exit)

        assertTrue(track.points.size > 100)
        assertTrue(track.points.first().millis < enter, "the path must start before the entry")
        assertTrue(track.points.last().millis > exit, "and end after the exit")
        assertEquals(enter, track.enterMillis)
        assertEquals(exit, track.exitMillis)
    }

    @Test
    fun `the track passes through the sky position it was built for`() {
        val center = Horizontal(180.0, 37.48)
        val equatorial = CoordinateTransforms.horizontalToEquatorial(center, berlin, START)
        val track = SkyTrackBuilder.forInterval(
            "Test", equatorial, berlin, START - 300_000L, START + 300_000L,
        )
        val nearest = track.points.minByOrNull { abs(it.millis - START) }!!
        assertTrue(
            SphericalGeometry.separationDeg(nearest.position, center) < 0.2,
            "closest sample sat at ${nearest.position}",
        )
    }

    @Test
    fun `the path moves westwards as the sky turns`() {
        val center = Horizontal(180.0, 37.48)
        val equatorial = CoordinateTransforms.horizontalToEquatorial(center, berlin, START)
        val track = SkyTrackBuilder.forInterval(
            "Test", equatorial, berlin, START, START + 3_600_000L,
        )
        // Due south, an object drifts towards the west, so its azimuth grows past 180.
        assertTrue(track.points.first().position.azimuthDeg < track.points.last().position.azimuthDeg)
    }

    @Test
    fun `hour marks land on full hours`() {
        val center = Horizontal(180.0, 40.0)
        val equatorial = CoordinateTransforms.horizontalToEquatorial(center, berlin, START)
        val track = SkyTrackBuilder.forInterval(
            "Test", equatorial, berlin, START, START + 3 * 3_600_000L, samples = 400,
        )
        val marks = track.hourMarks()
        assertTrue(marks.isNotEmpty(), "a three hour path must cross at least one full hour")
        marks.forEach { mark ->
            val offsetFromHour = mark.millis % 3_600_000L
            val distance = minOf(offsetFromHour, 3_600_000L - offsetFromHour)
            assertTrue(distance < 60_000L, "mark was ${distance / 1000} s off a full hour")
        }
    }

    @Test
    fun `an empty interval still yields a drawable path`() {
        val equatorial = Equatorial(100.0, 20.0)
        val track = SkyTrackBuilder.forInterval("Test", equatorial, berlin, START, START)
        assertTrue(!track.isEmpty)
    }
}

class IntervalScannerRegressionTest {

    private val berlin = ObserverLocation(52.52, 13.405, 34.0)

    @Test
    fun `sidereal timing still holds after moving the scan into a shared helper`() {
        // Same check as the object transit test, restated here so a future refactor of the shared
        // scanner cannot quietly change the answer for constellations either.
        val center = Horizontal(180.0, 37.48)
        val equatorial = CoordinateTransforms.horizontalToEquatorial(center, berlin, START)
        val constellation = Constellation(
            id = "One", name = "Einzelstern",
            stars = listOf(FigureStar("a", equatorial.raDeg, equatorial.decDeg)),
            lines = emptyList(),
        )
        val window = SkyWindow(
            id = "w", name = "w", shape = CircleWindow(center, 3.0),
            observer = berlin, capturedAtMillis = START,
        )

        val transit = runBlocking {
            ConstellationTransitCalculator().search(
                window, listOf(constellation), START - 3 * 3_600_000L, START + 3 * 3_600_000L,
            )
        }.single()

        val interval = transit.intervals.single()
        val expected = (2 * 3.0 * AstroTime.SECONDS_PER_DEGREE_OF_HOUR_ANGLE * 1000).toLong()
        assertEquals(expected.toDouble(), interval.durationMillis.toDouble(), expected * 0.02)
    }
}
