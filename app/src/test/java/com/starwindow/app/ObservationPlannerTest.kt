package com.starwindow.app

import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.core.astro.SolarEphemeris
import com.starwindow.app.core.astro.Twilight
import com.starwindow.app.data.catalog.ObjectType
import com.starwindow.app.data.catalog.SkyObject
import com.starwindow.app.domain.DarknessQuality
import com.starwindow.app.domain.ObservationNight
import com.starwindow.app.domain.ObservationPlanner
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.time.LocalDate
import java.time.ZoneId

private val BERLIN = ObserverLocation(52.52, 13.405, 34.0)
private val BERLIN_ZONE: ZoneId = ZoneId.of("Europe/Berlin")

/** Tromsø: far enough north for polar day, which is the edge case the planner has to survive. */
private val TROMSO = ObserverLocation(69.65, 18.96, 10.0)
private val TROMSO_ZONE: ZoneId = ZoneId.of("Europe/Oslo")

private fun objectAt(raDeg: Double, decDeg: Double, id: String = "TEST") = SkyObject(
    id = id, name = id, type = ObjectType.GALAXY,
    raDeg = raDeg, decDeg = decDeg, magnitude = 8.0, sizeArcmin = 20.0,
)

/** M31: the object most people plan a season around, high from Central Europe in autumn. */
private val andromeda = objectAt(10.6847, 41.269, "M31")

/** M42: the winter target, and low enough from Berlin to make the season narrow. */
private val orion = objectAt(83.822, -5.391, "M42")

class ObservationPlannerTest {

    private fun plan(
        obj: SkyObject = andromeda,
        observer: ObserverLocation = BERLIN,
        zone: ZoneId = BERLIN_ZONE,
        from: LocalDate = LocalDate.of(2026, 1, 1),
        days: Int = 365,
    ) = ObservationPlanner.plan(obj, observer, zone, from, days)

    /**
     * The whole reason this exists: the best night is not the one where the object is highest, it
     * is the one where "highest" and "dark" overlap the longest.
     */
    @Test
    fun `M31 peaks in autumn from Berlin, not in summer`() {
        val nights = plan()
        val best = nights.maxByOrNull { it.score }
        assertNotNull(best)

        val month = best.date.monthValue
        assertTrue(
            month in 9..12 || month == 1,
            "M31's best night should fall in autumn or early winter, got ${best.date}",
        )

        // In June there is barely any darkness in Berlin, so however high M31 climbs it loses.
        val june = nights.filter { it.date.monthValue == 6 }
        val october = nights.filter { it.date.monthValue == 10 }
        assertTrue(
            october.maxOf { it.usableMillis } > june.maxOf { it.usableMillis },
            "an October night must offer more exposure than a June one",
        )
    }

    /** The seasonal swing itself: winter nights are long, summer nights are not. */
    @Test
    fun `a winter night offers far more darkness than a summer one in Berlin`() {
        val nights = plan(from = LocalDate.of(2026, 1, 1))
        fun darkHours(night: ObservationNight): Double {
            val from = night.darkFromMillis ?: return 0.0
            val to = night.darkToMillis ?: return 0.0
            return (to - from) / 3_600_000.0
        }

        val january = nights.first { it.date == LocalDate.of(2026, 1, 15) }
        val june = nights.first { it.date == LocalDate.of(2026, 6, 21) }

        // Berlin on 15 January: astronomical dusk around 18:15, dawn around 05:55 — near enough
        // to twelve hours, and the published figure this can be checked against.
        assertTrue(
            darkHours(january) in 11.0..12.5,
            "mid-January in Berlin gives about 11.6 dark hours, got ${darkHours(january)}",
        )
        assertTrue(
            darkHours(june) < 4.0,
            "midsummer in Berlin has no astronomical night at all, got ${darkHours(june)}",
        )
        assertTrue(darkHours(january) > 3 * darkHours(june), "the seasonal swing is the whole point")
    }

    /**
     * North of about 49° astronomical darkness disappears entirely around midsummer. Reporting
     * those nights as ordinary would mean recommending sessions that cannot happen.
     */
    @Test
    fun `midsummer in Berlin is honestly reported as not getting dark`() {
        val nights = plan(from = LocalDate.of(2026, 6, 1), days = 40)
        val astronomical = nights.count { it.darkness == DarknessQuality.ASTRONOMICAL }

        assertEquals(0, astronomical, "Berlin has no astronomical night in June")
        assertTrue(
            nights.any { it.darkness == DarknessQuality.NAUTICAL },
            "but nautical twilight still happens, and a short bright night is still a night",
        )
    }

    /** Polar day: no darkness of any kind, and the planner must say so rather than invent one. */
    @Test
    fun `polar day yields no usable nights at all`() {
        val nights = ObservationPlanner.plan(
            andromeda, TROMSO, TROMSO_ZONE, LocalDate.of(2026, 6, 10), days = 20,
        )

        assertTrue(nights.all { it.darkness == DarknessQuality.NONE })
        assertTrue(nights.all { it.darkFromMillis == null && it.usableMillis == 0L })
        assertTrue(nights.all { it.score == 0.0 })
        assertTrue(nights.none { it.isWorthwhile })
    }

    /** And the same place in winter is the best observing site in the test set. */
    @Test
    fun `polar night yields long usable stretches`() {
        val nights = ObservationPlanner.plan(
            andromeda, TROMSO, TROMSO_ZONE, LocalDate.of(2026, 12, 10), days = 20,
        )
        assertTrue(
            nights.any { it.usableHours > 10.0 },
            "a polar-night session should run well over ten hours",
        )
    }

    /**
     * The dark window is solved rather than sampled, so it has to agree with the sampling
     * implementation that the weather screen already uses.
     */
    @Test
    fun `the computed dark window agrees with the sampled one`() {
        val dates = listOf(
            LocalDate.of(2026, 1, 15),
            LocalDate.of(2026, 3, 20),
            LocalDate.of(2026, 9, 23),
            LocalDate.of(2026, 11, 5),
        )
        for (date in dates) {
            val night = ObservationPlanner.plan(andromeda, BERLIN, BERLIN_ZONE, date, days = 1).single()
            val sampled = Twilight.nightOf(date, BERLIN, BERLIN_ZONE)

            val computedDusk = night.darkFromMillis
            val sampledDusk = sampled.astronomicalDuskMillis
            assertNotNull(computedDusk, "no dusk computed for $date")
            assertNotNull(sampledDusk, "no dusk sampled for $date")

            val gapMinutes = abs(computedDusk - sampledDusk) / 60_000.0
            assertTrue(gapMinutes < 6.0, "dusk on $date differs by %.1f min".format(gapMinutes))
        }
    }

    /** The Sun really is below the threshold across the window the planner reports. */
    @Test
    fun `the sun is genuinely below the twilight threshold throughout the dark window`() {
        val night = ObservationPlanner
            .plan(andromeda, BERLIN, BERLIN_ZONE, LocalDate.of(2026, 10, 15), days = 1).single()
        val from = requireNotNull(night.darkFromMillis)
        val to = requireNotNull(night.darkToMillis)

        // A few minutes inside each edge, to stay clear of the crossing itself.
        for (moment in listOf(from + 120_000L, (from + to) / 2, to - 120_000L)) {
            val altitude = SolarEphemeris.altitudeDeg(moment, BERLIN)
            assertTrue(
                altitude < ObservationPlanner.ASTRONOMICAL_TWILIGHT_DEG + 0.3,
                "sun at %.2f° inside the dark window".format(altitude),
            )
        }
    }

    /** An object that never climbs high enough is not offered, however dark the night gets. */
    @Test
    fun `an object that never rises usefully offers no exposure time`() {
        val deepSouth = objectAt(80.0, -70.0, "SOUTH")
        val nights = ObservationPlanner.plan(deepSouth, BERLIN, BERLIN_ZONE, LocalDate.of(2026, 1, 1), 60)

        assertTrue(nights.all { it.usableMillis == 0L })
        assertTrue(nights.none { it.isWorthwhile })
    }

    /** A circumpolar object high overhead is usable for the whole of every dark window. */
    @Test
    fun `a circumpolar object is usable for the entire night`() {
        val polaris = objectAt(37.95, 89.26, "POLARIS")
        val night = ObservationPlanner
            .plan(polaris, BERLIN, BERLIN_ZONE, LocalDate.of(2026, 11, 15), days = 1).single()

        val darkMillis = requireNotNull(night.darkToMillis) - requireNotNull(night.darkFromMillis)
        assertEquals(darkMillis, night.usableMillis, "Polaris never sets and never drops low")
    }

    @Test
    fun `usable time never exceeds the darkness available`() {
        for (night in plan(orion)) {
            val dark = (night.darkToMillis ?: 0L) - (night.darkFromMillis ?: 0L)
            assertTrue(
                night.usableMillis <= dark.coerceAtLeast(0L),
                "${night.date}: ${night.usableMillis} ms usable in $dark ms of darkness",
            )
        }
    }

    @Test
    fun `the best moment always lies inside the dark window`() {
        for (night in plan(orion).filter { it.bestMillis != null }) {
            val best = requireNotNull(night.bestMillis)
            assertTrue(best >= requireNotNull(night.darkFromMillis))
            assertTrue(best <= requireNotNull(night.darkToMillis))
        }
    }

    /** Orion is a winter target from Berlin; the planner has to find that without being told. */
    @Test
    fun `the season of a winter object falls in winter`() {
        val nights = plan(orion, from = LocalDate.of(2026, 1, 1))
        val bestMonths = ObservationPlanner.bestNights(nights, limit = 8)
            .map { it.date.monthValue }
            .toSet()

        assertTrue(
            bestMonths.all { it in listOf(1, 2, 3, 10, 11, 12) },
            "Orion's best nights should be winter ones, got months $bestMonths",
        )
    }

    /**
     * Without a spacing rule the answer would be one good week listed twelve times: consecutive
     * nights differ by four minutes of sky rotation and a sliver of Moon.
     */
    @Test
    fun `the best nights are spread out rather than clustered`() {
        val best = ObservationPlanner.bestNights(plan(), limit = 10, minimumGapDays = 5)

        assertTrue(best.size > 1)
        best.zipWithNext { a, b ->
            assertTrue(
                b.date.toEpochDay() - a.date.toEpochDay() >= 5,
                "${a.date} and ${b.date} are too close together",
            )
        }
        assertTrue(best.all { it.isWorthwhile })
        assertEquals(best.sortedBy { it.date }, best, "the shortlist should read chronologically")
    }

    /** A full-Moon night scores below a new-Moon night that is otherwise the same. */
    @Test
    fun `moonlight lowers the score`() {
        val nights = plan()
        val autumn = nights.filter { it.date.monthValue in 9..11 && it.isWorthwhile }
        val moonlit = autumn.filter { it.moonAltitudeDeg > 20.0 && it.moonIlluminationPercent > 85.0 }
        val dark = autumn.filter { it.moonAltitudeDeg < 0.0 && it.usableHours > 5.0 }

        assertTrue(moonlit.isNotEmpty() && dark.isNotEmpty(), "the fixture needs both kinds of night")
        assertTrue(
            dark.maxOf { it.score } > moonlit.maxOf { it.score },
            "a moonless night must beat a moonlit one",
        )
    }

    @Test
    fun `a season is reported for a normal target and withheld for an unreachable one`() {
        assertNotNull(ObservationPlanner.season(plan()))
        assertNull(ObservationPlanner.season(plan(objectAt(80.0, -70.0))))
    }

    /**
     * The season must be the longest unbroken run, not the outer bounds.
     *
     * Almost every object has a dead stretch when it sits in the daytime sky. M31 from Berlin is
     * useless in April and May; taking first-to-last would report "all year" and be exactly
     * backwards about the two months that matter.
     */
    @Test
    fun `the season skips the months the object is not observable`() {
        val nights = plan(from = LocalDate.of(2026, 8, 28))
        val season = requireNotNull(ObservationPlanner.season(nights))

        assertTrue(
            season.endInclusive.monthValue in 2..3,
            "M31's season should close in late winter, got ${season.endInclusive}",
        )
        assertTrue(
            season.start.monthValue in 6..9,
            "and open in summer, got ${season.start}",
        )

        // Every night inside the reported range really is usable — that is what a range promises.
        val inside = nights.filter { it.date >= season.start && it.date <= season.endInclusive }
        assertTrue(
            inside.all { it.usableMillis >= ObservationPlanner.MIN_WORTHWHILE_MILLIS },
            "the season contains nights that are not usable",
        )
    }

    /** A near-circumpolar target has no dead stretch, and saying "Saison" about it would mislead. */
    @Test
    fun `a circumpolar target is reported as available year round`() {
        val polaris = objectAt(37.95, 89.26, "POLARIS")
        assertTrue(ObservationPlanner.isYearRound(plan(polaris)))
        assertFalse(ObservationPlanner.isYearRound(plan(orion)))
    }

    @Test
    fun `hour angles at altitude cover both degenerate cases`() {
        // Circumpolar and always above 30°: no crossing.
        assertNull(ObservationPlanner.hourAngleAtAltitude(30.0, 89.0, 52.5))
        // Never gets anywhere near 30°: also no crossing.
        assertNull(ObservationPlanner.hourAngleAtAltitude(30.0, -70.0, 52.5))
        // A normal object crosses twice, symmetrically about its transit.
        val hourAngle = ObservationPlanner.hourAngleAtAltitude(30.0, 20.0, 52.5)
        assertNotNull(hourAngle)
        assertTrue(hourAngle in 0.0..180.0)
    }

    /** A year of planning has to be quick enough to run while the screen opens. */
    @Test
    fun `a full year is computed without sampling the sky`() {
        val started = System.nanoTime()
        val nights = plan()
        val millis = (System.nanoTime() - started) / 1_000_000

        assertEquals(365, nights.size)
        assertTrue(millis < 1_500, "a year took $millis ms — the analytic path is not being used")
    }
}
