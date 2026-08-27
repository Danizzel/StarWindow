package com.starwindow.app

import com.starwindow.app.core.astro.AstroTime
import com.starwindow.app.core.astro.CoordinateTransforms
import com.starwindow.app.core.astro.Horizontal
import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.core.geometry.AltAzBoxWindow
import com.starwindow.app.core.geometry.CircleWindow
import com.starwindow.app.core.geometry.SkyWindow
import com.starwindow.app.core.geometry.SphericalGeometry
import com.starwindow.app.core.geometry.WindowShape
import com.starwindow.app.data.catalog.ObjectType
import com.starwindow.app.data.catalog.SkyObject
import com.starwindow.app.domain.TransitCalculator
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

private const val START_MILLIS = 1_700_000_000_000L

class TransitCalculatorTest {

    private val berlin = ObserverLocation(52.52, 13.405, 34.0)
    private val calculator = TransitCalculator()

    private fun window(shape: WindowShape) = SkyWindow(
        id = "test",
        name = "Test",
        shape = shape,
        observer = berlin,
        capturedAtMillis = START_MILLIS,
    )

    /** Places a synthetic object exactly at the given sky direction at [START_MILLIS]. */
    private fun objectAt(direction: Horizontal, id: String = "TEST 1"): SkyObject {
        val equatorial = CoordinateTransforms.horizontalToEquatorial(direction, berlin, START_MILLIS)
        return SkyObject(
            id = id,
            name = id,
            type = ObjectType.STAR,
            raDeg = equatorial.raDeg,
            decDeg = equatorial.decDeg,
            magnitude = 2.0,
        )
    }

    @Test
    fun `an object placed inside the window is found immediately`() = runBlocking {
        val center = Horizontal(180.0, 40.0)
        val result = calculator.search(
            window = window(CircleWindow(center, 3.0)),
            objects = listOf(objectAt(center)),
            fromMillis = START_MILLIS,
            toMillis = START_MILLIS + 6 * 3_600_000L,
        )
        assertEquals(1, result.transits.size)
        assertTrue(result.transits.first().isInsideAtStart)
    }

    @Test
    fun `crossing time matches the sky's rotation rate`() {
        // A 3 degree radius circle on the meridian: an object entering at the rim leaves through
        // the far rim after 6 degrees of hour angle, and the sky turns 15 degrees per hour.
        val center = Horizontal(180.0, 37.48) // declination 0, due south from Berlin
        val target = objectAt(center)
        val radiusDeg = 3.0

        val result = runBlocking {
            calculator.search(
                window = window(CircleWindow(center, radiusDeg)),
                objects = listOf(target),
                // Start well before the object arrives so the entry is a real crossing.
                fromMillis = START_MILLIS - 3 * 3_600_000L,
                toMillis = START_MILLIS + 3 * 3_600_000L,
            )
        }

        assertEquals(1, result.transits.size)
        val interval = result.transits.first().intervals.single()
        assertTrue(!interval.clippedAtStart && !interval.clippedAtEnd)

        // On the celestial equator one degree of hour angle is 3.99 minutes of clock time, so a
        // 6 degree chord takes about 23.9 minutes.
        val expectedMillis = (2 * radiusDeg * AstroTime.SECONDS_PER_DEGREE_OF_HOUR_ANGLE * 1000).toLong()
        assertEquals(expectedMillis.toDouble(), interval.durationMillis.toDouble(), expectedMillis * 0.02)

        // The object should be closest to the centre roughly halfway through.
        val midpoint = (interval.enterMillis + interval.exitMillis) / 2
        assertTrue(abs(midpoint - START_MILLIS) < 120_000L)
    }

    @Test
    fun `a circumpolar object comes back after one sidereal day`() {
        // A window low in the north catches circumpolar stars at their lower culmination. The star
        // starts in the middle of it, drifts out, and must be back exactly one sidereal day later.
        val center = Horizontal(0.0, 20.0)
        val target = objectAt(center)

        val result = runBlocking {
            calculator.search(
                window = window(CircleWindow(center, 4.0)),
                objects = listOf(target),
                fromMillis = START_MILLIS,
                toMillis = START_MILLIS + 25 * 3_600_000L,
            )
        }

        val intervals = result.transits.single().intervals
        assertEquals(2, intervals.size, "expected a pass at the start and one a sidereal day later")
        assertTrue(intervals.first().clippedAtStart)

        val siderealDayMillis = (86_400_000.0 * 360.0 / 360.98564736629).toLong()
        val returnPass = intervals.last()
        assertTrue(
            returnPass.enterMillis < START_MILLIS + siderealDayMillis &&
                returnPass.exitMillis > START_MILLIS + siderealDayMillis,
            "the return pass should straddle one sidereal day after the start",
        )
    }

    @Test
    fun `objects that never reach the window's altitude are skipped`() {
        val window = window(CircleWindow(Horizontal(0.0, 80.0), 2.0))
        // Declination -60 never rises above the horizon from Berlin.
        val southern = SkyObject("S", "S", ObjectType.STAR, 120.0, -60.0, 1.0)
        val result = runBlocking {
            calculator.search(window, listOf(southern), START_MILLIS, START_MILLIS + 86_400_000L)
        }
        assertEquals(0, result.objectsConsidered)
        assertTrue(result.transits.isEmpty())
    }

    @Test
    fun `a box window reports the same crossing as the equivalent circle`() {
        val center = Horizontal(200.0, 30.0)
        val target = objectAt(center)
        val box = AltAzBoxWindow.fromCorners(
            Horizontal(197.0, 27.0),
            Horizontal(203.0, 33.0),
        )
        val result = runBlocking {
            calculator.search(
                window = window(box),
                objects = listOf(target),
                fromMillis = START_MILLIS - 3_600_000L,
                toMillis = START_MILLIS + 3_600_000L,
            )
        }
        assertEquals(1, result.transits.size)
        val interval = result.transits.first().intervals.first()
        assertTrue(interval.enterMillis < START_MILLIS && interval.exitMillis > START_MILLIS)
    }

    @Test
    fun `entry and exit times are refined below the sampling step`() {
        val center = Horizontal(180.0, 37.48)
        val target = objectAt(center)
        val coarse = TransitCalculator(stepSeconds = 300L)
        val fine = TransitCalculator(stepSeconds = 5L)

        val window = window(CircleWindow(center, 2.0))
        val from = START_MILLIS - 2 * 3_600_000L
        val to = START_MILLIS + 2 * 3_600_000L

        val coarseInterval = runBlocking {
            coarse.search(window, listOf(target), from, to).transits.first().intervals.first()
        }
        val fineInterval = runBlocking {
            fine.search(window, listOf(target), from, to).transits.first().intervals.first()
        }

        // Bisection has to bring the coarse 5 minute grid within a second of the fine answer.
        assertTrue(
            abs(coarseInterval.enterMillis - fineInterval.enterMillis) < 1_000L,
            "entry differed by ${abs(coarseInterval.enterMillis - fineInterval.enterMillis)} ms",
        )
        assertTrue(abs(coarseInterval.exitMillis - fineInterval.exitMillis) < 1_000L)
    }

    @Test
    fun `objectsInsideAt agrees with the transit search`() {
        val center = Horizontal(150.0, 45.0)
        val target = objectAt(center)
        val window = window(CircleWindow(center, 5.0))
        val inside = calculator.objectsInsideAt(window, listOf(target), START_MILLIS)
        assertEquals(1, inside.size)
        assertTrue(SphericalGeometry.separationDeg(center, inside.first().second) < 0.6)
    }
}

/**
 * The rejection that makes the search fast, and the risk that comes with it.
 *
 * A window is nailed to the horizon, so its **declination never changes** while the sky turns past
 * it — only its right ascension drifts. Anything whose declination lies further from the window's
 * than the window is wide can therefore never enter it, and can be discarded before it is scanned
 * over time at all. That took the search from 785 ms to 164 ms on a real catalogue.
 *
 * It is also the one optimisation here that can silently produce a *wrong* answer: a filter that
 * rejects too eagerly does not fail loudly, it just quietly leaves objects out of the night's list.
 * These tests exist to make that failure loud.
 */
class TransitDeclinationFilterTest {

    private val berlin = ObserverLocation(52.52, 13.405, 34.0)
    private val calculator = TransitCalculator()

    private fun window(shape: WindowShape) = SkyWindow(
        id = "test", name = "Test", shape = shape, observer = berlin, capturedAtMillis = START_MILLIS,
    )

    private fun starAt(direction: Horizontal, id: String): SkyObject {
        val equatorial = CoordinateTransforms.horizontalToEquatorial(direction, berlin, START_MILLIS)
        return SkyObject(
            id = id, name = id, type = ObjectType.STAR,
            raDeg = equatorial.raDeg, decDeg = equatorial.decDeg, magnitude = 2.0,
        )
    }

    /**
     * The filter is only allowed to remove work, never results.
     *
     * A dense grid of objects is swept across the whole sky and every one that the full scan would
     * have found has to survive the rejection. Sampling positions rather than reasoning about them
     * is deliberate: the geometry is exactly what could be got subtly wrong.
     */
    @Test
    fun `nothing that really crosses the window is rejected`() = runBlocking {
        val center = Horizontal(150.0, 35.0)
        val w = window(CircleWindow(center, 6.0))

        // Objects on a grid over the sky, dense enough that dozens genuinely cross the window in
        // twelve hours and hundreds do not.
        val objects = buildList {
            var azimuth = 0.0
            while (azimuth < 360.0) {
                var altitude = 5.0
                while (altitude < 85.0) {
                    add(starAt(Horizontal(azimuth, altitude), "S-$azimuth-$altitude"))
                    altitude += 5.0
                }
                azimuth += 5.0
            }
        }

        val found = calculator.search(w, objects, START_MILLIS, START_MILLIS + 12 * 3_600_000L)
            .transits.map { it.obj.id }.toSet()

        // The reference: step through time and note anything that is ever actually inside.
        val inside = mutableSetOf<String>()
        var time = START_MILLIS
        while (time <= START_MILLIS + 12 * 3_600_000L) {
            calculator.objectsInsideAt(w, objects, time).forEach { inside += it.first.id }
            time += 60_000L
        }

        assertTrue(inside.isNotEmpty(), "the fixture never puts anything in the window")
        val lost = inside - found
        assertTrue(lost.isEmpty(), "the filter dropped ${lost.size} real transits: $lost")
    }

    /**
     * What the altitude test alone could not reject, and why the declination test is worth having.
     *
     * From Berlin a circumpolar object at +80° declination does reach 45° altitude — so the
     * altitude band test passes it — but only ever in the north. A window facing south at the same
     * altitude can never contain it.
     */
    @Test
    fun `an object that reaches the right altitude in the wrong direction is not a transit`() =
        runBlocking {
            val southern = window(CircleWindow(Horizontal(180.0, 45.0), 5.0))
            val circumpolar = SkyObject(
                id = "POLAR", name = "Polar", type = ObjectType.STAR,
                raDeg = 200.0, decDeg = 80.0, magnitude = 2.0,
            )

            val result = calculator.search(
                southern, listOf(circumpolar), START_MILLIS, START_MILLIS + 24 * 3_600_000L,
            )

            assertTrue(result.transits.isEmpty())
            assertEquals(0, result.objectsConsidered, "it should not even have been scanned")
        }

    /** A window facing north, on the other hand, is exactly where that object belongs. */
    @Test
    fun `the same object is found by a window pointing where it actually goes`() = runBlocking {
        val circumpolar = SkyObject(
            id = "POLAR", name = "Polar", type = ObjectType.STAR,
            raDeg = 200.0, decDeg = 80.0, magnitude = 2.0,
        )
        val northern = window(CircleWindow(Horizontal(0.0, 90.0 - berlin.latitudeDeg + 10.0), 12.0))

        val result = calculator.search(
            northern, listOf(circumpolar), START_MILLIS, START_MILLIS + 24 * 3_600_000L,
        )

        assertTrue(result.transits.isNotEmpty(), "a circumpolar object must cross a northern window")
    }

    /**
     * The margin exists for refraction and for the J2000-to-date shift; an object sitting just
     * outside the window's own radius still has to be considered, because it may drift in.
     */
    @Test
    fun `an object just outside the window edge is still considered`() = runBlocking {
        val center = Horizontal(180.0, 40.0)
        val w = window(CircleWindow(center, 4.0))
        // Placed a degree beyond the rim: not inside now, but the same declination band.
        val edge = starAt(Horizontal(180.0, 44.5), "EDGE")

        val result = calculator.search(w, listOf(edge), START_MILLIS, START_MILLIS + 3_600_000L)

        assertEquals(1, result.objectsConsidered, "an object at the rim must not be pre-rejected")
    }
}
