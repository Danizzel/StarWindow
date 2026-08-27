package com.starwindow.app

import com.starwindow.app.data.catalog.ObjectType
import com.starwindow.app.data.catalog.SkyObject
import com.starwindow.app.domain.CatalogFilter
import com.starwindow.app.domain.Feasibility
import com.starwindow.app.domain.FilterField
import com.starwindow.app.domain.PhotographicInterest
import com.starwindow.app.domain.ResultFilter
import com.starwindow.app.domain.SkyConditions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun obj(
    id: String = "X",
    type: ObjectType = ObjectType.GALAXY,
    magnitude: Double? = 9.0,
    sizeArcmin: Double? = 20.0,
    surfaceBrightness: Double? = null,
    name: String = "",
    constellation: String? = "Ori",
    catalogIds: List<String> = emptyList(),
) = SkyObject(
    id = id,
    name = name,
    type = type,
    raDeg = 100.0,
    decDeg = 20.0,
    magnitude = magnitude,
    sizeArcmin = sizeArcmin,
    surfaceBrightness = surfaceBrightness,
    constellation = constellation,
    catalogIds = catalogIds,
)

class SkyConditionsTest {

    @Test
    fun `a darker Bortle class means a darker sky`() {
        val dark = SkyConditions(bortleLevel = 2).skyBrightness
        val city = SkyConditions(bortleLevel = 8).skyBrightness
        assertTrue(dark > city, "the rural sky must be the fainter background")
        assertTrue(dark in 20.0..23.0, "a Bortle 2 sky should land near 21.8 mag, got $dark")
        assertTrue(city in 17.0..20.0, "an inner-city sky should land near 18.7 mag, got $city")
    }

    /** A moon below the horizon brightens nothing, however full it is. */
    @Test
    fun `the moon only counts while it is up`() {
        val base = SkyConditions(bortleLevel = 4, moonIlluminationPercent = 100.0)
        assertEquals(SkyConditions(bortleLevel = 4).skyBrightness, base.skyBrightness, 1e-9)

        val risen = base.copy(moonAltitudeDeg = 45.0)
        assertTrue(risen.skyBrightness < base.skyBrightness, "a full moon overhead must brighten the sky")
    }

    @Test
    fun `a thin crescent is not treated as interference`() {
        assertFalse(SkyConditions(moonIlluminationPercent = 8.0, moonAltitudeDeg = 30.0).moonInterferes)
        assertTrue(SkyConditions(moonIlluminationPercent = 80.0, moonAltitudeDeg = 30.0).moonInterferes)
    }
}

class FeasibilityTest {

    private val ruralSky = SkyConditions(bortleLevel = 4)
    private val citySky = SkyConditions(bortleLevel = 8)

    @Test
    fun `below the horizon nothing else is considered`() {
        assertEquals(Feasibility.BELOW, Feasibility.assess(obj(), -5.0, ruralSky))
        assertEquals(Feasibility.BELOW, Feasibility.assess(obj(), null, ruralSky))
    }

    @Test
    fun `near the horizon the atmosphere decides, not the object`() {
        // The same bright, large object: high up it works, at eight degrees it does not.
        val bright = obj(surfaceBrightness = 20.0, sizeArcmin = 60.0)
        assertEquals(Feasibility.EASY, Feasibility.assess(bright, 55.0, ruralSky))
        assertEquals(Feasibility.LOW, Feasibility.assess(bright, 8.0, ruralSky))
    }

    /**
     * The sky background is the limit, but it is a *photographic* one.
     *
     * An object fainter per square arcminute than the sky is invisible to an eye and routinely
     * photographed anyway — that is what long exposures are for. So the cut sits well below zero
     * contrast, and this test pins both ends of it: still workable a magnitude under the sky,
     * genuinely out of reach three magnitudes under.
     */
    @Test
    fun `a target below the sky background is still workable, far below it is not`() {
        val ruralBackground = ruralSky.skyBrightness

        val slightlyUnder = obj(surfaceBrightness = ruralBackground + 1.0, sizeArcmin = 30.0)
        assertTrue(
            Feasibility.assess(slightlyUnder, 60.0, ruralSky).isPossible,
            "a magnitude under the sky is normal astrophotography, not an impossibility",
        )

        val farUnder = obj(surfaceBrightness = ruralBackground + 3.0, sizeArcmin = 30.0)
        assertEquals(Feasibility.TOO_FAINT, Feasibility.assess(farUnder, 60.0, ruralSky))
    }

    /** The same object is a different proposition under a city sky than under a dark one. */
    @Test
    fun `the same object can be doable from a dark site and out of reach from a city`() {
        val faint = obj(surfaceBrightness = 22.5, sizeArcmin = 30.0)

        assertTrue(Feasibility.assess(faint, 60.0, SkyConditions(bortleLevel = 2)).isPossible)
        assertEquals(Feasibility.TOO_FAINT, Feasibility.assess(faint, 60.0, citySky))
    }

    /** The same object, the same sky, but the moon is up: this is what a verdict has to notice. */
    @Test
    fun `a bright moon can push a target out of reach`() {
        val moonless = SkyConditions(bortleLevel = 4)
        val moonlit = moonless.copy(moonIlluminationPercent = 100.0, moonAltitudeDeg = 60.0)
        // Placed just inside the moonless limit, so only the Moon's own 1.6 magnitudes decide.
        val marginal = obj(surfaceBrightness = moonless.skyBrightness + 2.2, sizeArcmin = 30.0)

        assertTrue(Feasibility.assess(marginal, 60.0, moonless).isPossible)
        assertEquals(Feasibility.TOO_FAINT, Feasibility.assess(marginal, 60.0, moonlit))
    }

    @Test
    fun `something too small to resolve is hard however bright it is`() {
        val tiny = obj(surfaceBrightness = 18.0, sizeArcmin = 0.4)
        assertEquals(Feasibility.HARD, Feasibility.assess(tiny, 70.0, ruralSky))
    }

    /**
     * Without a surface brightness there is nothing to compare against the sky, so the verdict
     * falls back on the integrated magnitude rather than inventing a contrast.
     */
    @Test
    fun `an entry without surface brightness is judged on its magnitude`() {
        assertEquals(Feasibility.EASY, Feasibility.assess(obj(magnitude = 7.0), 50.0, ruralSky))
        assertEquals(Feasibility.OK, Feasibility.assess(obj(magnitude = 11.0), 50.0, ruralSky))
        assertEquals(Feasibility.HARD, Feasibility.assess(obj(magnitude = 13.5), 50.0, ruralSky))
        assertNull(Feasibility.contrastAgainstSky(obj(), ruralSky))
    }

    @Test
    fun `stars are judged on brightness alone`() {
        val star = obj(type = ObjectType.STAR, magnitude = 2.0, sizeArcmin = null)
        assertEquals(Feasibility.EASY, Feasibility.assess(star, 40.0, citySky))
    }

    @Test
    fun `the verdicts are ordered from best to worst`() {
        val ordered = listOf(
            Feasibility.EASY, Feasibility.OK, Feasibility.HARD,
            Feasibility.LOW, Feasibility.TOO_FAINT, Feasibility.BELOW,
        )
        assertEquals(ordered, ordered.sortedBy { it.ordinal })
        assertTrue(ordered.take(3).all { it.isPossible })
        assertTrue(ordered.drop(3).none { it.isPossible })
    }
}

class PhotographicInterestTest {

    /** Size over brightness — the difference between ranking for a camera and for an eye. */
    @Test
    fun `a large faint nebula outranks a small bright one`() {
        val california = obj(id = "NGC 1499", sizeArcmin = 160.0, magnitude = 6.0, name = "Kaliforniennebel")
        val compact = obj(id = "NGC 6572", sizeArcmin = 0.4, magnitude = 8.1, name = "Smaragdnebel")

        assertTrue(
            PhotographicInterest.score(california) > PhotographicInterest.score(compact),
            "the large nebula must rank above the tiny one",
        )
    }

    @Test
    fun `stars are not photographic targets, wide doubles are`() {
        val star = obj(type = ObjectType.STAR, magnitude = 1.0, sizeArcmin = null)
        assertFalse(PhotographicInterest.isPhotoTarget(star))

        val double = SkyObject(
            id = "HR 7417",
            name = "Albireo",
            type = ObjectType.DOUBLE_STAR,
            raDeg = 292.7,
            decDeg = 28.0,
            magnitude = 3.1,
            separationArcsec = 34.7,
        )
        assertTrue(PhotographicInterest.isPhotoTarget(double))
        assertTrue(PhotographicInterest.score(double) > PhotographicInterest.score(star))
    }

    @Test
    fun `an anonymous speck is not a target, a named or Messier object always is`() {
        assertFalse(PhotographicInterest.isPhotoTarget(obj(sizeArcmin = 0.6, name = "")))
        assertTrue(PhotographicInterest.isPhotoTarget(obj(sizeArcmin = 0.6, name = "Etwas")))
        assertTrue(
            PhotographicInterest.isPhotoTarget(
                obj(id = "NGC 6720", sizeArcmin = 0.6, name = "", catalogIds = listOf("M57"))
            ),
            "a Messier object is a target whatever its size",
        )
        // …but a UGC number is not a Messier number, however much it looks like one.
        assertFalse(
            PhotographicInterest.isPhotoTarget(
                obj(sizeArcmin = 0.6, name = "", catalogIds = listOf("MCG+05-31-045"))
            )
        )
    }

    @Test
    fun `the score stays inside its stated range`() {
        val extremes = listOf(
            obj(sizeArcmin = 600.0, magnitude = -1.0, surfaceBrightness = 15.0, name = "Riesig"),
            obj(sizeArcmin = null, magnitude = null, name = ""),
            obj(type = ObjectType.STAR, magnitude = 12.0, sizeArcmin = null),
        )
        assertTrue(extremes.all { PhotographicInterest.score(it) in 0.0..100.0 })
    }
}

class CatalogFilterTest {

    private val conditions = SkyConditions(bortleLevel = 4)

    @Test
    fun `an empty filter restricts nothing and says so`() {
        assertTrue(CatalogFilter.NONE.isEmpty)
        assertEquals(0, CatalogFilter.NONE.activeCount)
        assertTrue(CatalogFilter.NONE.matches(obj(), 40.0, conditions))
    }

    @Test
    fun `each restriction excludes what it names`() {
        assertFalse(CatalogFilter(magnitudeLimit = 8.0).matches(obj(magnitude = 9.5), 40.0, conditions))
        assertFalse(CatalogFilter(minSizeArcmin = 30.0).matches(obj(sizeArcmin = 10.0), 40.0, conditions))
        assertFalse(CatalogFilter(constellation = "Cyg").matches(obj(constellation = "Ori"), 40.0, conditions))
        assertFalse(CatalogFilter(minAltitudeDeg = 30.0).matches(obj(), 12.0, conditions))
        assertFalse(CatalogFilter(kind = ResultFilter.NEBULAE).matches(obj(type = ObjectType.GALAXY), 40.0, conditions))
    }

    /**
     * A filter that is set but not shown makes the list lie quietly, so every active restriction
     * has to produce exactly one chip.
     */
    @Test
    fun `every active restriction appears as its own chip`() {
        val filter = CatalogFilter(
            kind = ResultFilter.GALAXIES,
            magnitudeLimit = 9.0,
            minSizeArcmin = 10.0,
            constellation = "Uma",
            minAltitudeDeg = 25.0,
            hideImpossible = true,
            onlyThroughWindow = true,
        )
        assertEquals(7, filter.activeCount)
        assertEquals(filter.activeCount, filter.activeLabels.size)
        assertFalse(filter.isEmpty)
    }

    @Test
    fun `clearing a chip removes exactly that restriction`() {
        val filter = CatalogFilter(magnitudeLimit = 9.0, constellation = "Ori")

        val cleared = filter.without(FilterField.MAGNITUDE)

        assertNull(cleared.magnitudeLimit)
        assertEquals("Ori", cleared.constellation)
        assertEquals(1, cleared.activeCount)
    }

    @Test
    fun `the feasibility filter drops what cannot be done tonight`() {
        val filter = CatalogFilter(hideImpossible = true)
        // Faint enough that a city sky puts it out of reach and a dark one does not.
        val faint = obj(surfaceBrightness = 22.5, sizeArcmin = 30.0)

        assertFalse(filter.matches(faint, 60.0, SkyConditions(bortleLevel = 8)))
        assertTrue(filter.matches(faint, 60.0, SkyConditions(bortleLevel = 1)))
    }

    /** The constellation comparison must not care how the user capitalised it. */
    @Test
    fun `the constellation filter ignores case`() {
        assertTrue(CatalogFilter(constellation = "ori").matches(obj(constellation = "Ori"), 40.0, conditions))
    }
}
