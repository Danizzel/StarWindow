package com.starwindow.app

import com.starwindow.app.core.astro.CoordinateTransforms
import com.starwindow.app.core.astro.Horizontal
import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.core.geometry.SphericalGeometry
import com.starwindow.app.data.catalog.ObjectType
import com.starwindow.app.data.catalog.SkyObject
import com.starwindow.app.domain.ResultFilter
import com.starwindow.app.domain.SkyTrackBuilder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val MOMENT = 1_700_000_000_000L

class ResultFilterTest {

    private fun obj(
        type: ObjectType,
        name: String = "",
        sizeArcmin: Double? = null,
    ) = SkyObject(
        id = "X", name = name, type = type, raDeg = 10.0, decDeg = 20.0, sizeArcmin = sizeArcmin,
    )

    @Test
    fun `every filter accepts only its own kinds`() {
        val nebula = obj(ObjectType.EMISSION_NEBULA, "Nordamerikanebel", 120.0)
        val galaxy = obj(ObjectType.GALAXY, "Andromedagalaxie", 178.0)
        val cluster = obj(ObjectType.GLOBULAR_CLUSTER, "Herkuleshaufen", 20.0)
        val star = obj(ObjectType.STAR, "Wega")

        assertTrue(ResultFilter.NEBULAE.matches(nebula))
        assertTrue(!ResultFilter.NEBULAE.matches(galaxy))
        assertTrue(ResultFilter.GALAXIES.matches(galaxy))
        assertTrue(!ResultFilter.GALAXIES.matches(cluster))
        assertTrue(ResultFilter.CLUSTERS.matches(cluster))
        assertTrue(ResultFilter.STARS.matches(star))
        assertTrue(!ResultFilter.STARS.matches(nebula))
        assertTrue(ResultFilter.ALL.matches(star) && ResultFilter.ALL.matches(galaxy))
    }

    @Test
    fun `the nebula filter covers every kind of nebula`() {
        // These used to fall through the cracks: the old filter only knew three nebula types.
        listOf(
            ObjectType.NEBULA,
            ObjectType.EMISSION_NEBULA,
            ObjectType.REFLECTION_NEBULA,
            ObjectType.DARK_NEBULA,
            ObjectType.PLANETARY_NEBULA,
            ObjectType.SUPERNOVA_REMNANT,
            ObjectType.CLUSTER_NEBULA,
        ).forEach { type ->
            assertTrue(ResultFilter.NEBULAE.matches(obj(type)), "$type is not counted as a nebula")
        }
    }

    @Test
    fun `photo targets are named or large, and never single stars`() {
        assertTrue(ResultFilter.PHOTO.matches(obj(ObjectType.GALAXY, "Andromedagalaxie", 178.0)))
        // Big and anonymous still counts: many photographic targets have no popular name.
        assertTrue(ResultFilter.PHOTO.matches(obj(ObjectType.EMISSION_NEBULA, "", 80.0)))
        // Small and anonymous does not: nobody plans a night around it.
        assertTrue(!ResultFilter.PHOTO.matches(obj(ObjectType.GALAXY, "", 0.8)))
        // A star is not a photographic deep sky target however bright it is.
        assertTrue(!ResultFilter.PHOTO.matches(obj(ObjectType.STAR, "Sirius")))
    }

    @Test
    fun `constellations and objects never both show in the constellation filter`() {
        assertTrue(!ResultFilter.CONSTELLATIONS.matches(obj(ObjectType.GALAXY, "irgendwas", 90.0)))
        assertTrue(ResultFilter.CONSTELLATIONS.showsConstellations)
        assertTrue(!ResultFilter.CONSTELLATIONS.showsObjects)
        assertTrue(ResultFilter.ALL.showsConstellations && ResultFilter.ALL.showsObjects)
        assertTrue(!ResultFilter.GALAXIES.showsConstellations)
    }
}

class SkyObjectDataTest {

    @Test
    fun `fill factor compares the object against the window`() {
        val big = SkyObject("A", "", ObjectType.GALAXY, 0.0, 0.0, sizeArcmin = 180.0)
        // Three degrees across a window of two degrees diameter: it overflows.
        assertTrue(requireNotNull(big.fillFactor(1.0)) > 1.0)
        assertEquals(0.5, requireNotNull(big.fillFactor(3.0)), 1e-9)
        // Without a size there is nothing to compare.
        assertTrue(SkyObject("B", "", ObjectType.GALAXY, 0.0, 0.0).fillFactor(1.0) == null)
    }

    @Test
    fun `search matches names and every designation`() {
        val obj = SkyObject(
            "M31", "Andromedagalaxie", ObjectType.GALAXY, 10.68, 41.27,
            catalogIds = listOf("NGC 224", "UGC 00454"),
            alternativeNames = listOf("Andromeda Galaxy"),
        )
        assertTrue(obj.matches("andromeda"))
        assertTrue(obj.matches("ngc 224"))
        assertTrue(obj.matches("UGC"))
        assertTrue(obj.matches(""), "an empty query must not filter anything out")
        assertTrue(!obj.matches("Orion"))
    }

    @Test
    fun `narrowband hint marks emission objects only`() {
        assertTrue(ObjectType.EMISSION_NEBULA.respondsToNarrowband)
        assertTrue(ObjectType.PLANETARY_NEBULA.respondsToNarrowband)
        assertTrue(ObjectType.SUPERNOVA_REMNANT.respondsToNarrowband)
        assertTrue(!ObjectType.GALAXY.respondsToNarrowband)
        assertTrue(!ObjectType.REFLECTION_NEBULA.respondsToNarrowband)
    }
}

class AltitudeSpanTest {

    private val berlin = ObserverLocation(52.52, 13.405, 34.0)

    @Test
    fun `a span track covers exactly the requested window of time`() {
        val equatorial = CoordinateTransforms.horizontalToEquatorial(
            Horizontal(180.0, 40.0), berlin, MOMENT,
        )
        val track = SkyTrackBuilder.overSpan(
            "Test", equatorial, berlin, MOMENT, MOMENT + 12 * 3_600_000L, samples = 240,
        )
        assertTrue(track.points.size in 200..260, "got ${track.points.size} samples")
        assertEquals(MOMENT, track.points.first().millis)
        assertTrue(track.points.last().millis <= MOMENT + 12 * 3_600_000L)
        // Monotone in time, or the curve would fold back on itself.
        assertTrue(track.points.zipWithNext().all { (a, b) -> b.millis > a.millis })
    }

    @Test
    fun `the curve reaches the culmination altitude somewhere in a full day`() {
        val vega = com.starwindow.app.core.astro.Equatorial(279.234, 38.784)
        val track = SkyTrackBuilder.overSpan(
            "Wega", vega, berlin, MOMENT, MOMENT + 24 * 3_600_000L, samples = 600,
        )
        val expected = 90.0 - abs(berlin.latitudeDeg - vega.decDeg)
        val peak = track.points.maxOf { it.position.altitudeDeg }
        // Refraction lifts the apparent altitude a little; it never lowers it.
        assertTrue(peak >= expected - 0.05 && peak <= expected + 0.2, "peak was $peak, expected $expected")
    }

    @Test
    fun `a span track and an interval track agree where they overlap`() {
        val equatorial = CoordinateTransforms.horizontalToEquatorial(
            Horizontal(120.0, 30.0), berlin, MOMENT,
        )
        val span = SkyTrackBuilder.overSpan(
            "Test", equatorial, berlin, MOMENT, MOMENT + 6 * 3_600_000L, samples = 400,
        )
        val interval = SkyTrackBuilder.forInterval(
            "Test", equatorial, berlin, MOMENT + 3_600_000L, MOMENT + 2 * 3_600_000L,
        )
        val probe = interval.points[interval.points.size / 2]
        val nearest = span.points.minByOrNull { abs(it.millis - probe.millis) }!!
        assertTrue(
            SphericalGeometry.separationDeg(nearest.position, probe.position) < 0.1,
            "the two samplers disagree by more than a tenth of a degree",
        )
    }
}
