package com.starwindow.app

import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.data.catalog.CatalogFile
import com.starwindow.app.data.catalog.ObjectType
import com.starwindow.app.data.catalog.SkyObject
import com.starwindow.app.domain.Popularity
import com.starwindow.app.domain.Season
import com.starwindow.app.domain.SeasonalHighlights
import com.starwindow.app.domain.TargetKind
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private val BERLIN = ObserverLocation(52.52, 13.405, 34.0)
private val BERLIN_ZONE: ZoneId = ZoneId.of("Europe/Berlin")

/** Kapstadt: südlich des Äquators, wo der Kalender die Jahreszeit umgekehrt buchstabiert. */
private val CAPE_TOWN = ObserverLocation(-33.92, 18.42, 25.0)
private val CAPE_TOWN_ZONE: ZoneId = ZoneId.of("Africa/Johannesburg")

/** Tromsø: weit genug im Norden, dass der Sommer keine Nacht kennt. */
private val TROMSO = ObserverLocation(69.65, 18.96, 10.0)
private val TROMSO_ZONE: ZoneId = ZoneId.of("Europe/Oslo")

class SeasonTest {

    @Test
    fun `northern seasons follow the calendar`() {
        assertEquals(Season.WINTER, Season.of(LocalDate.of(2026, 1, 15), 52.52))
        assertEquals(Season.SPRING, Season.of(LocalDate.of(2026, 4, 15), 52.52))
        assertEquals(Season.SUMMER, Season.of(LocalDate.of(2026, 7, 15), 52.52))
        assertEquals(Season.AUTUMN, Season.of(LocalDate.of(2026, 10, 15), 52.52))
    }

    /** Im Januar ist in Kapstadt Sommer, und der Hub muss ihn so nennen. */
    @Test
    fun `southern seasons are the other way round`() {
        assertEquals(Season.SUMMER, Season.of(LocalDate.of(2026, 1, 15), -33.92))
        assertEquals(Season.AUTUMN, Season.of(LocalDate.of(2026, 4, 15), -33.92))
        assertEquals(Season.WINTER, Season.of(LocalDate.of(2026, 7, 15), -33.92))
        assertEquals(Season.SPRING, Season.of(LocalDate.of(2026, 10, 15), -33.92))
    }

    /** Die laufende Jahreszeit zeigt heute; die drei anderen ihre eigene Mitte. */
    @Test
    fun `the running season is computed for today`() {
        val today = LocalDate.of(2026, 10, 12)
        assertEquals(today, SeasonalHighlights.referenceDate(Season.AUTUMN, today, 52.52))

        val winter = SeasonalHighlights.referenceDate(Season.WINTER, today, 52.52)
        assertTrue(winter > today, "eine kommende Jahreszeit liegt in der Zukunft")
        assertEquals(Season.WINTER, Season.of(winter, 52.52), "und mitten in ihr")

        for (season in Season.entries) {
            val date = SeasonalHighlights.referenceDate(season, today, 52.52)
            assertEquals(season, Season.of(date, 52.52), "$season trifft sich selbst")
        }
    }
}

class PopularityTest {

    @Test
    fun `the famous objects carry a weight and anonymous ones do not`() {
        assertEquals(100, Popularity.of(objectWith("M42")))
        assertTrue(Popularity.of(objectWith("NGC 7000")) > 0)
        assertEquals(0, Popularity.of(objectWith("NGC 4321")))
    }

    /**
     * Der Pferdekopfnebel heißt im Katalog `B033` und führt `B 33` als Zweitnamen — die kuratierte
     * Liste kennt ihn unter der zweiten Schreibweise. Ohne Faltung über alle Bezeichnungen fiele
     * eines der bekanntesten Motive überhaupt aus dem Hub.
     */
    @Test
    fun `any of an entry's designations finds it`() {
        val horsehead = SkyObject(
            id = "B033", name = "Horsehead Nebula", type = ObjectType.DARK_NEBULA,
            raDeg = 85.2458, decDeg = -2.4583, sizeArcmin = 6.0,
            catalogIds = listOf("Sh2-277", "B 33"),
        )
        assertTrue(Popularity.of(horsehead) > 0, "über den Zweitnamen gefunden")
    }

    private fun objectWith(id: String) = SkyObject(
        id = id, name = "", type = ObjectType.EMISSION_NEBULA,
        raDeg = 0.0, decDeg = 0.0, sizeArcmin = 30.0,
    )
}

/**
 * Der Hub gegen den echten Katalog.
 *
 * Absichtlich gegen die mitgelieferte Datei und nicht gegen eine Handvoll erfundener Einträge: Was
 * hier schiefgehen kann, geht an der Verbindung zwischen kuratierter Liste und Katalog schief — eine
 * Schreibweise, die sich nicht trifft, ein Objekt, das gar nicht enthalten ist. Genau das würde ein
 * Test mit selbst gebauten Objekten nie sehen.
 */
class SeasonalHighlightsTest {

    private val catalog: List<SkyObject> by lazy {
        val file = listOf(
            "src/main/assets/catalog/deepsky.json",
            "app/src/main/assets/catalog/deepsky.json",
        ).map(::File).firstOrNull { it.exists() } ?: error("Deep-Sky-Katalog nicht gefunden")
        Json { ignoreUnknownKeys = true }
            .decodeFromString<CatalogFile>(file.readText())
            .objects
    }

    /** Der Winterhimmel über Berlin ohne den Orionnebel wäre kein Winterhimmel. */
    @Test
    fun `orion leads the winter over berlin`() {
        val targets = hub(Season.WINTER, BERLIN, BERLIN_ZONE, LocalDate.of(2026, 1, 15))
        val ids = targets.map { it.obj.id }
        assertTrue("M42" in ids, "M42 fehlt im Winter über Berlin: $ids")
        assertTrue("M45" in ids, "M45 fehlt im Winter über Berlin: $ids")
    }

    /** Und der Herbst gehört Andromeda. */
    @Test
    fun `andromeda leads the autumn over berlin`() {
        val targets = hub(Season.AUTUMN, BERLIN, BERLIN_ZONE, LocalDate.of(2026, 10, 15))
        assertTrue("M31" in targets.map { it.obj.id }, "M31 fehlt im Herbst")
    }

    /**
     * Der Kern des Ganzen: Der Hub ist keine hinterlegte Liste, sondern eine Rechnung. Wäre er
     * eine Liste, stünde im Januar in Kapstadt derselbe „Winter" wie in Berlin.
     */
    @Test
    fun `the same season shows different objects in the two hemispheres`() {
        val date = LocalDate.of(2026, 1, 15)
        val north = hub(Season.WINTER, BERLIN, BERLIN_ZONE, date).map { it.obj.id }.toSet()
        val south = hub(Season.SUMMER, CAPE_TOWN, CAPE_TOWN_ZONE, date).map { it.obj.id }.toSet()

        assertNotEquals(north, south)
        assertTrue(
            south.any { it !in north },
            "Kapstadt zeigt Motive, die von Berlin aus nie aufgehen",
        )
    }

    /** Vier Wochen später steht der Himmel anders — sonst wäre die Monatsangabe eine Lüge. */
    @Test
    fun `the ranking shifts from month to month within one season`() {
        val early = hub(Season.AUTUMN, BERLIN, BERLIN_ZONE, LocalDate.of(2026, 9, 5))
        val late = hub(Season.AUTUMN, BERLIN, BERLIN_ZONE, LocalDate.of(2026, 11, 20))
        assertNotEquals(
            early.map { it.obj.id },
            late.map { it.obj.id },
            "September und November zeigen dieselbe Reihenfolge",
        )
    }

    /** Nördlich des Polarkreises gibt es im Sommer nichts zu empfehlen, und das ehrlich. */
    @Test
    fun `a polar summer offers nothing and says so`() {
        val targets = hub(Season.SUMMER, TROMSO, TROMSO_ZONE, LocalDate.of(2026, 6, 21))
        assertTrue(targets.isEmpty(), "Mitternachtssonne, aber ${targets.size} Vorschläge")
    }

    /** Zwei Katalogeinträge für denselben Nebel sind ein Bild, nicht zwei Karten. */
    @Test
    fun `the same patch of sky appears only once`() {
        val targets = hub(Season.WINTER, BERLIN, BERLIN_ZONE, LocalDate.of(2026, 1, 15))
        for (a in targets.indices) {
            for (b in a + 1 until targets.size) {
                val separation = com.starwindow.app.core.geometry.SphericalGeometry.separationDeg(
                    targets[a].obj.equatorialJ2000.toVector(),
                    targets[b].obj.equatorialJ2000.toVector(),
                )
                assertTrue(
                    separation >= 0.75,
                    "${targets[a].obj.id} und ${targets[b].obj.id} stehen ${"%.2f".format(separation)}° " +
                        "auseinander — dasselbe Bild zweimal",
                )
            }
        }
    }

    /** Kein Stern und keine anonyme Winzigkeit: Der Hub zeigt Motive, keine Katalogzeilen. */
    @Test
    fun `only photographic targets make it in`() {
        val targets = hub(Season.SPRING, BERLIN, BERLIN_ZONE, LocalDate.of(2026, 4, 15))
        assertTrue(targets.isNotEmpty())
        assertTrue(targets.none { it.obj.type.isStar }, "ein Stern ist kein Motiv")
        assertTrue(targets.all { it.usableHours >= 0.75 }, "unter 45 Minuten ist keine Nacht")
    }

    /** Im Frühling führen die Galaxien — der Blick geht senkrecht aus der Milchstraße heraus. */
    @Test
    fun `galaxies dominate the spring`() {
        val targets = hub(Season.SPRING, BERLIN, BERLIN_ZONE, LocalDate.of(2026, 4, 15))
        val galaxies = targets.count { it.kind == TargetKind.GALAXY }
        assertTrue(
            galaxies >= 4,
            "nur $galaxies Galaxien in der Galaxienzeit: ${targets.map { it.obj.id }}",
        )
    }

    /** Die Reihen tragen zusammen genau die Motive der Liste, keines doppelt und keines fehlend. */
    @Test
    fun `the rows partition the targets`() {
        val targets = hub(Season.AUTUMN, BERLIN, BERLIN_ZONE, LocalDate.of(2026, 10, 15))
        val rows = SeasonalHighlights.rows(targets)
        assertEquals(targets.size, rows.sumOf { it.second.size })
        assertEquals(targets.map { it.obj.id }.toSet(), rows.flatMap { it.second }.map { it.obj.id }.toSet())
    }

    private fun hub(season: Season, observer: ObserverLocation, zone: ZoneId, today: LocalDate) =
        SeasonalHighlights.build(
            objects = catalog,
            observer = observer,
            zone = zone,
            date = SeasonalHighlights.referenceDate(season, today, observer.latitudeDeg),
        )
}
