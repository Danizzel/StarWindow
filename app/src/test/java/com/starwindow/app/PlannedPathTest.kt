package com.starwindow.app

import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.core.astro.Precession
import com.starwindow.app.data.catalog.ObjectType
import com.starwindow.app.data.catalog.SkyObject
import com.starwindow.app.domain.ObservationPlanner
import com.starwindow.app.domain.SkyTrack
import com.starwindow.app.domain.SkyTrackBuilder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val BERLIN = ObserverLocation(52.52, 13.405, 34.0)
private val BERLIN_ZONE: ZoneId = ZoneId.of("Europe/Berlin")

private val andromeda = SkyObject(
    id = "M31",
    name = "Andromedagalaxie",
    type = ObjectType.GALAXY,
    raDeg = 10.6847,
    decDeg = 41.269,
    magnitude = 3.4,
    sizeArcmin = 178.0,
)

/**
 * The path drawn over the camera belongs to the **planned night**, not to tonight.
 *
 * This is the question the whole feature stands or falls on, and it is not obvious from reading the
 * code: the span comes from `ObservationPlanner` for the session's date, travels through
 * `TrackedObject` as two timestamps, and only becomes a curve inside `SkyTrackBuilder.overSpan`,
 * which converts each timestamp to sidereal time. Three files, and a single `System.currentTimeMillis()`
 * slipped in anywhere along it would silently draw tonight's arc while the label promised
 * November's — a wrong answer that looks entirely plausible.
 *
 * These tests walk that chain end to end for a night three months out.
 */
class PlannedPathTest {

    /** The same resolution `PlannedPath` performs, minus the Android plumbing around it. */
    private fun pathFor(nightDate: LocalDate): SkyTrack {
        val night = ObservationPlanner
            .plan(andromeda, BERLIN, BERLIN_ZONE, nightDate, days = 1)
            .single()
        return SkyTrackBuilder.overSpan(
            label = andromeda.name,
            equatorial = andromeda.positionAt(Precession.forEpoch(System.currentTimeMillis())),
            observer = BERLIN,
            fromMillis = requireNotNull(night.darkFromMillis),
            toMillis = requireNotNull(night.darkToMillis),
        )
    }

    private fun hourOf(millis: Long): Int =
        Instant.ofEpochMilli(millis).atZone(BERLIN_ZONE).hour

    /** The samples must fall on the planned date, not on today. */
    @Test
    fun `the path is sampled inside the planned night, three months out`() {
        val planned = LocalDate.of(2026, 11, 20)
        val path = pathFor(planned)

        assertTrue(path.points.size > 100, "the arc should be finely sampled")

        val dates = path.points.map {
            Instant.ofEpochMilli(it.millis).atZone(BERLIN_ZONE).toLocalDate()
        }.toSet()

        // A night spans midnight, so it touches the planned date and the morning after — and
        // nothing else.
        assertEquals(setOf(planned, planned.plusDays(1)), dates)
    }

    /**
     * The decisive comparison: the same object, the same place, two nights three months apart.
     *
     * M31 rises about four minutes earlier each night, so by late November it culminates in the
     * evening where in August it culminated near dawn. If the path were being built for "now"
     * regardless of the planned date, these two arcs would be identical.
     */
    @Test
    fun `a path three months out differs from tonight's`() {
        val august = pathFor(LocalDate.of(2026, 8, 28))
        val november = pathFor(LocalDate.of(2026, 11, 28))

        val augustPeak = august.points.maxBy { it.position.altitudeDeg }
        val novemberPeak = november.points.maxBy { it.position.altitudeDeg }

        // In August the culmination falls in the small hours; in November it is before midnight.
        assertTrue(
            hourOf(augustPeak.millis) in 2..5,
            "August: expected a pre-dawn peak, got ${hourOf(augustPeak.millis)}h",
        )
        assertTrue(
            hourOf(novemberPeak.millis) in 20..23,
            "November: expected an evening peak, got ${hourOf(novemberPeak.millis)}h",
        )

        // And at the *start* of each dark window the object stands somewhere quite different —
        // which is precisely what makes tonight's arrow useless for a night in three months.
        val augustStart = august.points.first().position
        val novemberStart = november.points.first().position
        assertTrue(
            abs(augustStart.azimuthDeg - novemberStart.azimuthDeg) > 30.0,
            "the two nights start with the object %.0f° apart in azimuth — too close to tell apart"
                .format(abs(augustStart.azimuthDeg - novemberStart.azimuthDeg)),
        )
    }

    /**
     * The arrow aims at the arc's high point, and for a distant night that is genuinely elsewhere
     * than the object's position right now.
     */
    @Test
    fun `the arc's high point is not where the object stands tonight`() {
        val november = pathFor(LocalDate.of(2026, 11, 28))
        val peak = november.points.maxBy { it.position.altitudeDeg }.position

        assertTrue(peak.altitudeDeg > 70.0, "M31 culminates high from Berlin, got ${peak.altitudeDeg}")
        // Culmination is due south by definition; anything else means the wrong moment was picked.
        assertTrue(
            abs(peak.azimuthDeg - 180.0) < 12.0,
            "a culmination should sit near due south, got %.0f°".format(peak.azimuthDeg),
        )
    }

    /**
     * Precession is computed for *now* rather than for the planned night, which is a deliberate
     * simplification — this pins down that it really is negligible over a season, so the shortcut
     * cannot quietly become wrong if the horizon is ever extended to years.
     */
    @Test
    fun `using today's precession for a night months away changes nothing visible`() {
        val plannedNight = LocalDate.of(2026, 11, 28)
        val night = ObservationPlanner
            .plan(andromeda, BERLIN, BERLIN_ZONE, plannedNight, days = 1).single()
        val from = requireNotNull(night.darkFromMillis)
        val to = requireNotNull(night.darkToMillis)

        fun arc(epochMillis: Long) = SkyTrackBuilder.overSpan(
            label = "M31",
            equatorial = andromeda.positionAt(Precession.forEpoch(epochMillis)),
            observer = BERLIN,
            fromMillis = from,
            toMillis = to,
        )

        val withToday = arc(System.currentTimeMillis())
        val withThatNight = arc(from)

        val worst = withToday.points.zip(withThatNight.points).maxOf { (a, b) ->
            abs(a.position.altitudeDeg - b.position.altitudeDeg)
        }
        assertTrue(worst < 0.05, "precession over a season moved the arc by %.3f°".format(worst))
    }
}
