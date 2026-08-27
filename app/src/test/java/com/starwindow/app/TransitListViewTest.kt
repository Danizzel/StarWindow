package com.starwindow.app

import com.starwindow.app.data.catalog.ObjectType
import com.starwindow.app.data.catalog.SkyObject
import com.starwindow.app.domain.ObjectTransit
import com.starwindow.app.domain.ResultFilter
import com.starwindow.app.domain.TransitInterval
import com.starwindow.app.domain.TransitListView
import com.starwindow.app.domain.TransitSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val START = 1_700_000_000_000L

private fun transit(
    id: String,
    type: ObjectType = ObjectType.GALAXY,
    name: String = "",
    magnitude: Double? = 9.0,
    sizeArcmin: Double? = 10.0,
    catalogIds: List<String> = emptyList(),
    alternativeNames: List<String> = emptyList(),
    enterOffsetMinutes: Long = 0,
    durationMinutes: Long = 60,
): ObjectTransit {
    val enter = START + enterOffsetMinutes * 60_000L
    return ObjectTransit(
        obj = SkyObject(
            id = id,
            name = name,
            type = type,
            raDeg = 10.0,
            decDeg = 40.0,
            magnitude = magnitude,
            sizeArcmin = sizeArcmin,
            catalogIds = catalogIds,
            alternativeNames = alternativeNames,
        ),
        intervals = listOf(
            TransitInterval(
                enterMillis = enter,
                exitMillis = enter + durationMinutes * 60_000L,
                clippedAtStart = false,
                clippedAtEnd = false,
                bestAltitudeDeg = 45.0,
                bestMillis = enter,
            )
        ),
    )
}

/**
 * The list a window's night turns into.
 *
 * The ordering question here is not cosmetic. A window used to return thirty objects and reading
 * them chronologically was right; it now returns several hundred, mostly anonymous galaxies that
 * happen to cross the gap, and in that list M31 sits somewhere in the middle unnoticed.
 */
class TransitListViewTest {

    private val andromeda = transit(
        id = "M31", name = "Andromedagalaxie", magnitude = 3.4, sizeArcmin = 178.0,
        catalogIds = listOf("NGC 224"), alternativeNames = listOf("Andromeda Galaxy"),
        enterOffsetMinutes = 300, durationMinutes = 40,
    )
    private val anonymous = transit(id = "NGC 5387", magnitude = 13.2, sizeArcmin = 1.2, enterOffsetMinutes = 0)
    private val cluster = transit(
        id = "M13", name = "Herkuleshaufen", type = ObjectType.GLOBULAR_CLUSTER,
        magnitude = 5.8, sizeArcmin = 16.5, enterOffsetMinutes = 120, durationMinutes = 200,
    )
    private val nebula = transit(
        id = "NGC 7000", name = "Nordamerikanebel", type = ObjectType.EMISSION_NEBULA,
        magnitude = 4.0, sizeArcmin = 120.0, enterOffsetMinutes = 60, durationMinutes = 90,
    )
    private val all = listOf(anonymous, andromeda, cluster, nebula)

    /**
     * The default order is the whole point of the change: chronologically, the anonymous
     * thirteenth-magnitude galaxy comes first and M31 fifth of five hundred.
     */
    @Test
    fun `by default the most rewarding target comes first, not the earliest`() {
        val list = TransitListView.build(all, ResultFilter.ALL)

        // M31 is three degrees across, fourth magnitude and a Messier object; it wins on all three.
        assertEquals("M31", list.first().obj.id)
        assertEquals("NGC 5387", list.last().obj.id, "the anonymous galaxy must sink to the bottom")
        assertTrue(
            list.indexOfFirst { it.obj.id == "NGC 7000" } < list.indexOfFirst { it.obj.id == "M13" },
            "a two-degree nebula outranks a sixteen-arcminute cluster",
        )
    }

    @Test
    fun `the chronological order is still available`() {
        val list = TransitListView.build(all, ResultFilter.ALL, sort = TransitSort.ENTRY)
        assertEquals(listOf("NGC 5387", "NGC 7000", "M13", "M31"), list.map { it.obj.id })
    }

    @Test
    fun `sorting by duration puts the longest stay first`() {
        val list = TransitListView.build(all, ResultFilter.ALL, sort = TransitSort.DURATION)
        assertEquals("M13", list.first().obj.id)
        assertEquals("M31", list.last().obj.id)
    }

    @Test
    fun `sorting by brightness and by size each do what they say`() {
        assertEquals(
            "M31",
            TransitListView.build(all, ResultFilter.ALL, sort = TransitSort.BRIGHTNESS).first().obj.id,
        )
        assertEquals(
            "M31",
            TransitListView.build(all, ResultFilter.ALL, sort = TransitSort.SIZE).first().obj.id,
        )
    }

    /** Within one kind the ranking still has to hold — that is what the kind filter is for. */
    @Test
    fun `filtering by kind keeps the best of that kind on top`() {
        val galaxies = TransitListView.build(all, ResultFilter.GALAXIES)
        assertEquals(listOf("M31", "NGC 5387"), galaxies.map { it.obj.id })
    }

    @Test
    fun `the query finds an object by name, by designation and by either spelling`() {
        for (query in listOf("andromeda", "M31", "m 31", "ngc224", "NGC 224")) {
            val hits = TransitListView.build(all, ResultFilter.ALL, query = query)
            assertEquals("M31", hits.first().obj.id, "\"$query\" does not find M31")
        }
    }

    @Test
    fun `a query that matches nothing yields an empty list rather than everything`() {
        assertTrue(TransitListView.build(all, ResultFilter.ALL, query = "qzxwv").isEmpty())
    }

    /** While something is typed, match quality outranks the chosen sort order. */
    @Test
    fun `the query overrides the sort order`() {
        val hits = TransitListView.build(all, ResultFilter.ALL, query = "M31", sort = TransitSort.ENTRY)
        assertEquals("M31", hits.first().obj.id)
    }

    @Test
    fun `the query and the kind filter apply together`() {
        val hits = TransitListView.build(all, ResultFilter.NEBULAE, query = "M31")
        assertTrue(hits.isEmpty(), "M31 is a galaxy and must not survive the nebula filter")
    }

    @Test
    fun `the constellations only filter hides every object`() {
        assertTrue(TransitListView.build(all, ResultFilter.CONSTELLATIONS).isEmpty())
    }

    /**
     * The point of the highlights row: "this gap is a galaxy window" is a useful thing to know
     * before scrolling, and it is invisible in any single ordering of the list.
     */
    @Test
    fun `the highlights offer the best few of each kind and skip empty kinds`() {
        val highlights = TransitListView.highlightsByKind(all, perKind = 2)

        assertEquals("M31", highlights.getValue(ResultFilter.GALAXIES).first().obj.id)
        assertEquals("NGC 7000", highlights.getValue(ResultFilter.NEBULAE).first().obj.id)
        assertEquals("M13", highlights.getValue(ResultFilter.CLUSTERS).single().obj.id)
        assertTrue(ResultFilter.STARS !in highlights, "there are no stars, so no star row")
        assertTrue(highlights.values.all { it.size <= 2 })
    }
}
