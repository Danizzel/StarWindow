package com.starwindow.app

import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.data.catalog.ObjectType
import com.starwindow.app.data.catalog.SkyObject
import com.starwindow.app.domain.ObjectSearch
import com.starwindow.app.domain.ObjectSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SEARCH_MOMENT = 1_700_000_000_000L

/** Mid-northern latitude, so both circumpolar and never-rising objects are in play. */
private val MUNICH = ObserverLocation(48.14, 11.58, 520.0)

private val andromeda = SkyObject(
    id = "M31",
    name = "Andromedagalaxie",
    type = ObjectType.GALAXY,
    raDeg = 10.6847,
    decDeg = 41.269,
    magnitude = 3.4,
    sizeArcmin = 189.1,
    constellation = "And",
    catalogIds = listOf("NGC 224", "UGC 454", "PGC 2557"),
    alternativeNames = listOf("Andromeda Galaxy"),
)

private val orionNebula = SkyObject(
    id = "M42",
    name = "Orionnebel",
    type = ObjectType.EMISSION_NEBULA,
    raDeg = 83.822,
    decDeg = -5.391,
    magnitude = 4.0,
    sizeArcmin = 85.0,
    constellation = "Ori",
    catalogIds = listOf("NGC 1976"),
)

private val pleiades = SkyObject(
    id = "M45",
    name = "Plejaden",
    type = ObjectType.OPEN_CLUSTER,
    raDeg = 56.8692,
    decDeg = 24.1053,
    magnitude = 1.2,
    sizeArcmin = 150.0,
    constellation = "Tau",
    catalogIds = listOf("Mel022"),
    alternativeNames = listOf("Pleiades"),
)

private val decoy = SkyObject(
    id = "NGC 3184",
    name = "",
    type = ObjectType.GALAXY,
    raDeg = 154.57,
    decDeg = 41.42,
    magnitude = 9.8,
    constellation = "UMa",
    catalogIds = listOf("UGC 5557", "M310"),
)

private val faintTwin = SkyObject(
    id = "IC 434",
    name = "Pferdekopfnebel",
    type = ObjectType.DARK_NEBULA,
    raDeg = 85.24,
    decDeg = -2.46,
    magnitude = 7.3,
    sizeArcmin = 60.0,
    constellation = "Ori",
    alternativeNames = listOf("Horsehead Nebula", "Barnard 33"),
)

private val catalog = listOf(andromeda, orionNebula, pleiades, decoy, faintTwin)

class ObjectSearchFoldingTest {

    @Test
    fun `designations compare the same however they are spaced`() {
        assertEquals(ObjectSearch.fold("NGC 224"), ObjectSearch.fold("ngc224"))
        assertEquals(ObjectSearch.fold("M 45"), ObjectSearch.fold("m45"))
        assertEquals(ObjectSearch.fold("IC-1396"), ObjectSearch.fold("ic 1396"))
    }

    @Test
    fun `german umlauts fold onto their spelled out form`() {
        assertEquals("hoehe", ObjectSearch.fold("Höhe"))
        assertEquals("groesse", ObjectSearch.fold("Größe"))
    }
}

class ObjectSearchRankingTest {

    private fun search(query: String, sort: ObjectSort = ObjectSort.RELEVANCE) =
        ObjectSearch.search(query, catalog, MUNICH, SEARCH_MOMENT, sort)

    @Test
    fun `an exact designation wins over an entry that merely contains it`() {
        // "M31" is a substring of NGC 3184's alternate id "M310"; the real M31 has to come first.
        val hits = search("M31")
        assertTrue(hits.isNotEmpty(), "M31 finds nothing")
        assertEquals("M31", hits.first().obj.id)
    }

    @Test
    fun `a designation is found however the user spaces it`() {
        listOf("M45", "m 45", "M  45").forEach { query ->
            assertEquals("M45", search(query).first().obj.id, "\"$query\" does not find M45")
        }
    }

    @Test
    fun `an object is found under any of its catalogue designations`() {
        assertEquals("M31", search("NGC224").first().obj.id)
        assertEquals("M31", search("ugc 454").first().obj.id)
        assertEquals("M45", search("mel022").first().obj.id)
    }

    @Test
    fun `a german name and its english alternative both find the object`() {
        assertEquals("M45", search("Plejaden").first().obj.id)
        assertEquals("M45", search("pleiades").first().obj.id)
        assertEquals("IC 434", search("horsehead").first().obj.id)
    }

    @Test
    fun `a word inside a compound name is enough`() {
        // Nobody types "Pferdekopfnebel" in full on a phone in the dark.
        assertEquals("IC 434", search("pferdekopf").first().obj.id)
    }

    @Test
    fun `a name prefix beats a match buried in the middle of another name`() {
        val hits = search("orion")
        assertEquals("M42", hits.first().obj.id)
    }

    @Test
    fun `an empty query returns the whole catalogue rather than nothing`() {
        assertEquals(catalog.size, search("").size)
    }

    @Test
    fun `nonsense finds nothing instead of everything`() {
        assertTrue(search("qzxwv").isEmpty())
    }

    @Test
    fun `the constellation abbreviation is a match of last resort`() {
        val hits = search("Ori").map { it.obj.id }
        assertTrue("M42" in hits, "M42 is in Orion and is not found by its constellation")
        assertTrue("IC 434" in hits)
        assertTrue("M31" !in hits, "M31 is in Andromeda and must not answer to Ori")
    }
}

class ObjectSearchSortTest {

    private fun search(sort: ObjectSort) =
        ObjectSearch.search("", catalog, MUNICH, SEARCH_MOMENT, sort)

    @Test
    fun `sorting by brightness puts the brightest first`() {
        val hits = search(ObjectSort.BRIGHTNESS)
        assertEquals("M45", hits.first().obj.id)
        assertEquals("NGC 3184", hits.last().obj.id)
    }

    @Test
    fun `sorting by size puts the largest first`() {
        assertEquals("M31", search(ObjectSort.SIZE).first().obj.id)
    }

    @Test
    fun `sorting by altitude is monotone and never puts a set object above a risen one`() {
        val altitudes = search(ObjectSort.ALTITUDE).map { it.altitudeDeg ?: -999.0 }
        assertEquals(altitudes.sortedDescending(), altitudes)
    }

    @Test
    fun `every hit carries the position it stands at right now`() {
        search(ObjectSort.ALTITUDE).forEach {
            assertTrue(it.position != null, "${it.obj.id} has no position")
            assertTrue(it.altitudeDeg!! in -90.0..90.0)
        }
    }

    @Test
    fun `without an observer there are hits but no altitudes`() {
        val hits = ObjectSearch.search("M31", catalog, observer = null, nowMillis = SEARCH_MOMENT)
        assertEquals("M31", hits.first().obj.id)
        assertTrue(hits.all { it.position == null })
    }
}

class ObjectSearchSuggestionTest {

    @Test
    fun `the suggestion list only offers what is actually up`() {
        val suggestions = ObjectSearch.visibleNow(catalog, MUNICH, SEARCH_MOMENT, minAltitudeDeg = 15.0)
        assertTrue(suggestions.all { it.altitudeDeg!! >= 15.0 }, "something below the cut is offered")
    }

    @Test
    fun `without a position nothing can be suggested`() {
        assertTrue(ObjectSearch.visibleNow(catalog, observer = null, nowMillis = SEARCH_MOMENT).isEmpty())
    }

    @Test
    fun `the suggestion list stays within its limit`() {
        val many = (1..200).map {
            andromeda.copy(id = "X$it", name = "Test $it", magnitude = 5.0)
        }
        assertTrue(ObjectSearch.visibleNow(many, MUNICH, SEARCH_MOMENT, 0.0, limit = 12).size <= 12)
    }
}
