package com.starwindow.app

import com.starwindow.app.data.catalog.ObjectType
import com.starwindow.app.data.catalog.SkyObject
import com.starwindow.app.domain.OverlaySelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The cut between "what the catalogue holds" and "what the viewfinder draws".
 *
 * Worth its own tests because it is the only thing standing between a twenty-two thousand entry
 * catalogue and a draw lambda that runs fifty times a second — and because every one of its rules
 * is a judgement that could reasonably have gone the other way.
 */
class OverlaySelectionTest {

    private fun star(id: String, magnitude: Double?, decDeg: Double = 20.0) = SkyObject(
        id = id,
        name = id,
        type = ObjectType.STAR,
        raDeg = 100.0,
        decDeg = decDeg,
        magnitude = magnitude,
    )

    private fun target(
        id: String,
        sizeArcmin: Double?,
        magnitude: Double? = 8.0,
        decDeg: Double = 20.0,
        name: String = id,
        type: ObjectType = ObjectType.EMISSION_NEBULA,
    ) = SkyObject(
        id = id,
        name = name,
        type = type,
        raDeg = 100.0,
        decDeg = decDeg,
        magnitude = magnitude,
        sizeArcmin = sizeArcmin,
    )

    @Test
    fun `stars fainter than the naked eye get no marker`() {
        val catalog = listOf(star("bright", 2.0), star("faint", 5.6))

        val selected = OverlaySelection.select(catalog, latitudeDeg = 50.0, magnitudeLimit = 6.0)

        assertEquals(listOf("bright"), selected.map { it.id })
    }

    /**
     * The point of the whole class: the viewfinder is not a catalogue browser. An anonymous
     * fourteenth-magnitude galaxy is a fine entry and a useless marker.
     */
    @Test
    fun `anonymous small objects get no marker however many there are`() {
        val catalog = (1..500).map {
            target("NGC $it", sizeArcmin = 0.8, magnitude = 13.5, name = "")
        }

        assertTrue(OverlaySelection.select(catalog, 50.0, 14.0).isEmpty())
    }

    @Test
    fun `real targets are drawn and ranked by photographic worth`() {
        val catalog = listOf(
            target("small", sizeArcmin = 2.0, magnitude = 9.0),
            target("M42", sizeArcmin = 85.0, magnitude = 4.0, name = "Orionnebel"),
        )

        val selected = OverlaySelection.select(catalog, 50.0, 12.0)

        assertTrue(selected.any { it.id == "M42" }, "the Orion Nebula is not drawn")
    }

    /**
     * The southern sky is most of the catalogue and none of it is ever drawable from Central
     * Europe. Cutting it costs one subtraction per entry and can never remove something the user
     * could have seen.
     */
    @Test
    fun `objects that never rise at this latitude are left out`() {
        val catalog = listOf(
            target("overhead", 40.0, decDeg = 50.0),
            target("southern", 40.0, decDeg = -60.0),
        )

        assertEquals(listOf("overhead"), OverlaySelection.select(catalog, 52.5, 12.0).map { it.id })
        assertTrue(OverlaySelection.select(catalog, -33.0, 12.0).any { it.id == "southern" })
    }

    /** Without a position every declination is still possible, so nothing may be cut on it. */
    @Test
    fun `nothing is cut on latitude while the position is unknown`() {
        val catalog = listOf(target("north", 40.0, decDeg = 80.0), target("south", 40.0, decDeg = -80.0))

        assertEquals(2, OverlaySelection.select(catalog, null, 12.0).size)
    }

    /**
     * A nebula with no measured brightness is not thereby invisible — the California Nebula has no
     * useful integrated magnitude and is nearly three degrees across.
     */
    @Test
    fun `large objects without a magnitude survive, small ones do not`() {
        val catalog = listOf(
            target("bigNebula", sizeArcmin = 120.0, magnitude = null),
            target("tinyNebula", sizeArcmin = 0.5, magnitude = null),
        )

        assertEquals(listOf("bigNebula"), OverlaySelection.select(catalog, 50.0, 6.0).map { it.id })
    }

    /** The ceiling is what keeps a generous magnitude setting from being a trap. */
    @Test
    fun `the hard ceiling holds no matter how generous the limit is`() {
        val catalog = (1..2000).map {
            target("NGC $it", sizeArcmin = 30.0, magnitude = 6.0 + it / 1000.0, name = "Nebel $it")
        }

        val selected = OverlaySelection.select(catalog, 50.0, 30.0)

        assertEquals(OverlaySelection.MAX_OBJECTS, selected.size)
    }

    /**
     * Pointed at Cygnus, brightness alone would fill the entire budget with stars and drop the
     * nebulae that are the reason for looking there — hence a reserved share rather than one pool.
     */
    @Test
    fun `stars cannot crowd out the targets`() {
        val stars = (1..1000).map { star("HR $it", magnitude = 1.0) }
        val targets = (1..1000).map {
            target("NGC $it", sizeArcmin = 40.0, magnitude = 8.0, name = "Nebel $it")
        }

        val selected = OverlaySelection.select(stars + targets, 50.0, 12.0)

        assertEquals(OverlaySelection.MAX_STARS, selected.count { it.type.isStar })
        assertEquals(
            OverlaySelection.MAX_OBJECTS - OverlaySelection.MAX_STARS,
            selected.count { !it.type.isStar },
        )
    }

    /**
     * Bright stars are not targets, but they are the frame of reference: aiming at a known star and
     * checking that its marker sits on it is how the whole projection chain gets verified, and the
     * star calibration measures against them. An overlay without them is unverifiable.
     */
    @Test
    fun `bright stars stay, because the overlay is checked against them`() {
        val catalog = listOf(star("Sirius", -1.46), target("M42", 85.0, 4.0, name = "Orionnebel"))

        val selected = OverlaySelection.select(catalog, 50.0, 6.0)

        assertTrue(selected.any { it.id == "Sirius" }, "the reference stars are gone")
        assertTrue(selected.any { it.id == "M42" })
    }

    @Test
    fun `the magnitude setting only ever tightens the selection`() {
        val catalog = listOf(
            target("faint", sizeArcmin = 40.0, magnitude = 11.0, name = "Schwacher Nebel"),
            target("bright", sizeArcmin = 40.0, magnitude = 5.0, name = "Heller Nebel"),
        )

        assertEquals(listOf("bright"), OverlaySelection.select(catalog, 50.0, 8.0).map { it.id })
        assertEquals(2, OverlaySelection.select(catalog, 50.0, 12.0).size)
    }

    @Test
    fun `an object exactly at the pole distance still rises`() {
        // 90° minus the gap: at latitude 50 an object at declination -40 culminates exactly on the
        // horizon, and one a degree north of it clears it.
        assertFalse(OverlaySelection.everRises(target("edge", 10.0, decDeg = -40.0), 50.0))
        assertTrue(OverlaySelection.everRises(target("just", 10.0, decDeg = -39.0), 50.0))
    }
}
